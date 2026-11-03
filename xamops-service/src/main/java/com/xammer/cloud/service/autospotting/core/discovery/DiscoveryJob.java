package com.xammer.cloud.service.autospotting.core.discovery;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroupSnapshot;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.autospotting.DiscoveryStore;
import com.xammer.cloud.repository.autospotting.SpotGroupSnapshotRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.spi.DiscoveryRegistry;
import com.xammer.cloud.service.autospotting.spi.GroupDiscovery;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The discovery engine (design-core.md §2.5, plan-phase-08.md task 8.3). Per provider it runs
 * {@code discovery:{aws,gcp}} daily at 03:00 UTC and on account connect, under that job's lease
 * ({@link JobRunner#runLeased}, §3.2). It drives the per-cloud {@link GroupDiscovery} (dark until
 * Phases 12/26), classifies groups by the §2.5 rules, and:
 *
 * <ul>
 *   <li>rule 1 — an identity check: a different or missing stable id for an existing LEGACY_UNCONFIRMED
 *       row moves that row to GONE first (2.2), so a fresh row can be inserted under rules 2-3 or the
 *       next plan;</li>
 *   <li>rules 2-3 — insert a LEGACY_UNCONFIRMED row with a reconstructed snapshot ({@code exact=false});
 *       rule 3 records reason {@code ORPHAN_MARKER}; {@code desired_config} is seeded from the legacy
 *       config the discovery read (AWS reads {@code asg_spot_config} read-only);</li>
 *   <li>rule 4 — {@code EXTERNAL_SPOT}: a read-time class with no row (the discovery returns no
 *       {@link DiscoveredGroup} for it);</li>
 *   <li>rule 5 — nothing.</li>
 * </ul>
 *
 * <p>A LEGACY_UNCONFIRMED row seen on two consecutive runs with neither a XamOps marker nor a Spot
 * share moves to UNMANAGED (§2.5). Unreadable regions or projects are skipped and reported, never
 * "no groups" (FR-OBS-06). A duplicate {@code gcp_project_id} across {@code CloudAccount} rows is
 * skipped and reported (AC-SPN-14): the run ignores every account whose project id is shared, so a
 * group is never attributed to the wrong account. Each run writes a {@code DISCOVERY_RUN} audit
 * record with counts per rule.
 *
 * <p><b>Dark.</b> No {@code GroupDiscovery} bean exists in production yet, so every provider run is a
 * no-op. No {@link ReadOnlyCloudView} is wired either, so even the read path is inert. Discovery
 * mutates no cloud and enables no customer-visible path (plan-phase-08.md "Flag or rollout gate:
 * None").
 */
@Component
public class DiscoveryJob {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryJob.class);

    static final String JOB_PREFIX = "discovery:";
    static final int SNAPSHOT_SCHEMA_VERSION = 1;

    private final DiscoveryRegistry discoveries;
    private final CloudAccountRepository cloudAccounts;
    private final DiscoveryStore discoveryStore;
    private final SpotGroupSnapshotRepository snapshots;
    private final AutoSpottingAuditWriter auditWriter;
    private final JobRunner jobRunner;
    private final TransactionTemplate txTemplate;

    /** Counts completed provider runs (test observation; a run happened even when it found nothing). */
    private final AtomicInteger runCount = new AtomicInteger();

    public DiscoveryJob(DiscoveryRegistry discoveries, CloudAccountRepository cloudAccounts,
                        DiscoveryStore discoveryStore, SpotGroupSnapshotRepository snapshots,
                        AutoSpottingAuditWriter auditWriter, JobRunner jobRunner,
                        PlatformTransactionManager transactionManager) {
        this.discoveries = discoveries;
        this.cloudAccounts = cloudAccounts;
        this.discoveryStore = discoveryStore;
        this.snapshots = snapshots;
        this.auditWriter = auditWriter;
        this.jobRunner = jobRunner;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Daily discovery at 03:00 UTC (§2.5). Each provider's run takes the {@code discovery:{cloud}}
     * job lease via {@link JobRunner#runLeased} (AC-SPN-13), and the day's {@code runKey} skips a
     * re-run within the day (§3.5). The cron declares {@code zone = "UTC"} (F-05).
     */
    @Scheduled(cron = "${autospotting.discovery.cron:0 0 3 * * *}", zone = "UTC")
    public void scheduledDaily() {
        String runKey = LocalDate.now(ZoneOffset.UTC).toString();
        for (Provider provider : Provider.values()) {
            String job = JOB_PREFIX + provider.name().toLowerCase(Locale.ROOT);
            jobRunner.runLeased(job, runKey, () -> runProvider(provider));
        }
    }

    /**
     * On account connect (§2.5). Runs the one provider's discovery through the job lease immediately,
     * with no {@code runKey} so it isn't skipped by the day's daily run. Wired by the connect path in
     * a later phase; dark until a {@link GroupDiscovery} is registered.
     */
    public void onAccountConnect(Provider provider) {
        String job = JOB_PREFIX + provider.name().toLowerCase(Locale.ROOT);
        jobRunner.runLeased(job, null, () -> runProvider(provider));
    }

    /** The number of provider runs completed (test observation, §2.5). */
    public int runCount() {
        return runCount.get();
    }

    /**
     * One provider's discovery run across its accounts. Public so {@code DiscoveryJobIT} can drive it
     * directly with a fake discovery (bypassing the scheduler and the job lease the IT exercises
     * separately). Does nothing when no {@link GroupDiscovery} is registered for the provider.
     */
    public void runProvider(Provider provider) {
        GroupDiscovery discovery = discoveries.find(provider).orElse(null);
        if (discovery == null) {
            runCount.incrementAndGet();
            return; // dark: no discovery wired for this cloud yet
        }
        List<CloudAccount> accounts = cloudAccounts.findByProvider(provider.name());
        Set<String> duplicateProjectIds = duplicateProjectIds(provider, accounts);
        for (CloudAccount account : accounts) {
            if (provider == Provider.GCP && account.getGcpProjectId() != null
                    && duplicateProjectIds.contains(account.getGcpProjectId())) {
                // AC-SPN-14: a project shared by two CloudAccount rows is ambiguous; skip and report,
                // never attribute a group to the wrong account.
                writeRunAudit(provider, account, null, List.of("DUPLICATE_PROJECT:" + account.getGcpProjectId()));
                continue;
            }
            runAccount(provider, discovery, account);
        }
        runCount.incrementAndGet();
    }

    private void runAccount(Provider provider, GroupDiscovery discovery, CloudAccount account) {
        DiscoveredGroup.Result result;
        try {
            result = discovery.discover(account, readOnlyView());
        } catch (RuntimeException e) {
            log.warn("discovery for {} account {} failed: {}", provider, account.getId(), e.toString());
            return;
        }
        applyResult(provider, account, result);
        writeRunAudit(provider, account, result, result.failedLocations());
    }

    /**
     * Persist one account's classified groups (§2.5) in one transaction: insert the rule 2-3
     * LEGACY_UNCONFIRMED rows (with their reconstructed {@code exact=false} snapshots), run the
     * identity check (rule 1) and the two-run UNMANAGED rule against the live discovery rows.
     */
    public void applyResult(Provider provider, CloudAccount account, DiscoveredGroup.Result result) {
        txTemplate.executeWithoutResult(tx -> applyResultInTx(provider, account, result));
    }

    private void applyResultInTx(Provider provider, CloudAccount account, DiscoveredGroup.Result result) {
        Long cloudAccountId = account.getId();
        Long clientId = account.getClient() == null ? null : account.getClient().getId();
        Instant now = Instant.now();

        // Existing discovery rows, keyed by group_key, with their current stable id.
        Map<String, DiscoveryStore.LegacyRow> existing = new HashMap<>();
        for (DiscoveryStore.LegacyRow row : discoveryStore.findLegacyRows(cloudAccountId)) {
            existing.put(row.groupKey(), row);
        }

        for (DiscoveredGroup group : result.groups()) {
            DiscoveryStore.LegacyRow current = existing.get(group.groupKey());

            if (group.matchedRule() == 1) {
                // Identity-only match: an existing row. A different/missing stable id → GONE first.
                if (current != null && identityChanged(current.providerStableId(), group.providerStableId())) {
                    discoveryStore.moveToGone(current.id(), now);
                    writeGroupTransitionAudit(provider, account, current.id(), group.groupKey(),
                            "LEGACY_UNCONFIRMED", "GONE", "IDENTITY_CHANGED");
                } else if (current != null) {
                    applyMarkerlessRule(provider, account, current, group, now);
                }
                continue;
            }

            if (group.matchedRule() == 2 || group.matchedRule() == 3) {
                if (current == null) {
                    insertLegacyRow(provider, cloudAccountId, clientId, group, now);
                } else {
                    applyMarkerlessRule(provider, account, current, group, now);
                }
            }
            // rule 4 (EXTERNAL_SPOT) and rule 5 produce no row (§2.5).
        }
    }

    /**
     * The two-run UNMANAGED rule for one live discovery row (§2.5). When the group still shows a
     * marker or a Spot share, clear the marker-less marker and stamp the reconcile time. When it
     * shows neither: if a prior marker-less run was already recorded, this is the second consecutive
     * one and the row moves to UNMANAGED; otherwise record this first marker-less run.
     */
    private void applyMarkerlessRule(Provider provider, CloudAccount account,
                                     DiscoveryStore.LegacyRow current, DiscoveredGroup group, Instant now) {
        if (group.hasMarker() || group.hasSpotShare()) {
            discoveryStore.clearMarkerlessRun(current.id(), now);
            return;
        }
        if (current.markerlessRunRecorded()) {
            discoveryStore.moveToUnmanaged(current.id(), now);
            writeGroupTransitionAudit(provider, account, current.id(), group.groupKey(),
                    "LEGACY_UNCONFIRMED", "UNMANAGED", "NO_MARKER_NO_SPOT_TWO_RUNS");
        } else {
            discoveryStore.markMarkerlessRun(current.id(), now);
        }
    }

    private void insertLegacyRow(Provider provider, Long cloudAccountId, Long clientId,
                                 DiscoveredGroup group, Instant now) {
        UUID groupId = UUID.randomUUID();
        UUID snapshotId = null;
        SnapshotPayload snap = group.reconstructedSnapshot();
        if (snap != null) {
            snapshotId = UUID.randomUUID();
            SpotGroupSnapshot row = new SpotGroupSnapshot();
            row.setId(snapshotId);
            row.setGroupId(groupId);
            row.setProvider(provider);
            row.setSchemaVersion(snap.schemaVersion() > 0 ? snap.schemaVersion() : SNAPSHOT_SCHEMA_VERSION);
            row.setExact(false); // reconstructed snapshot is never exact (§2.5, aws §5.4, gcp §2.2)
            row.setPayload(snap.payloadJson());
            row.setPayloadSha256(snap.payloadSha256());
            row.setCapturedAt(now);
            snapshots.save(row);
        }
        discoveryStore.insertLegacyUnconfirmed(groupId, provider.name(), cloudAccountId, clientId,
                group.groupKey(), group.providerStableId(), group.desiredConfigJson(), null, snapshotId, now);
        if (log.isDebugEnabled()) {
            log.debug("discovery inserted LEGACY_UNCONFIRMED {} rule={} reason={} group={}", groupId,
                    group.matchedRule(), group.reason(), group.groupKey());
        }
    }

    /** Write the per-run {@code DISCOVERY_RUN} audit record with counts per rule (§2.5). */
    public void writeRunAudit(Provider provider, CloudAccount account, DiscoveredGroup.Result result,
                              List<String> failedLocations) {
        txTemplate.executeWithoutResult(tx -> writeRunAuditInTx(provider, account, result, failedLocations));
    }

    private void writeRunAuditInTx(Provider provider, CloudAccount account, DiscoveredGroup.Result result,
                                   List<String> failedLocations) {
        Map<Integer, Integer> counts = result == null ? Map.of() : result.ruleCounts();
        StringBuilder details = new StringBuilder("{\"ruleCounts\":{");
        boolean first = true;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (!first) {
                details.append(',');
            }
            details.append("\"rule").append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        details.append("},\"failedLocations\":").append(jsonStringArray(failedLocations)).append('}');
        auditWriter.write(AuditRecord.builder("DISCOVERY_RUN", Actor.system(), "DISCOVER", "SUCCESS")
                .provider(provider.name())
                .clientId(account.getClient() == null ? null : account.getClient().getId())
                .cloudAccountId(account.getId())
                .details(details.toString())
                .build());
    }

    private void writeGroupTransitionAudit(Provider provider, CloudAccount account, UUID groupId,
                                           String groupKey, String from, String to, String reason) {
        auditWriter.write(AuditRecord.builder("GROUP_TRANSITION", Actor.system(), reason, "SUCCESS")
                .provider(provider.name())
                .clientId(account.getClient() == null ? null : account.getClient().getId())
                .cloudAccountId(account.getId())
                .groupId(groupId)
                .groupKey(groupKey)
                .beforeState("{\"state\":\"" + from + "\"}")
                .afterState("{\"state\":\"" + to + "\"}")
                .build());
    }

    private static boolean identityChanged(String stored, String discovered) {
        if (discovered == null) {
            return stored != null; // a missing stable id where one was known is an identity change
        }
        return !discovered.equals(stored);
    }

    private static String jsonStringArray(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private Set<String> duplicateProjectIds(Provider provider, List<CloudAccount> accounts) {
        if (provider != Provider.GCP) {
            return Set.of();
        }
        Map<String, Integer> byProject = new HashMap<>();
        for (CloudAccount account : accounts) {
            String projectId = account.getGcpProjectId();
            if (projectId != null) {
                byProject.merge(projectId, 1, Integer::sum);
            }
        }
        Set<String> dups = new HashSet<>();
        byProject.forEach((projectId, count) -> {
            if (count > 1) {
                dups.add(projectId);
            }
        });
        return dups;
    }

    /**
     * The read-only cloud view for discovery. Dark: no real view is wired yet, so this is never
     * reached in production (the registry has no discovery). A registered discovery supplies its own
     * view in Phases 12/26; the IT's fake discovery ignores the argument.
     */
    private ReadOnlyCloudView readOnlyView() {
        return new ReadOnlyCloudView() {
        };
    }
}
