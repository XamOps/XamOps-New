package com.xammer.cloud.service.autospotting.ledger.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.gcp.GcpEligibilityEvaluator;
import com.xammer.cloud.service.autospotting.gcp.GcpErrorMapper;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpMigStatusReader;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpViewSource;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code ledger-observe:gcp} (design-ledger.md §6.4, §2.1; plan-phase-30.md 30.2): every 5 minutes, under its job
 * lease, for every GCP group in a measured state it opens and closes {@code spot_usage_interval} rows with
 * {@code instance_name} = the GCP {@code resource.name}, and writes {@code spot_group_capacity_sample} (MIG
 * {@code targetSize}, floor N, original Spot units) when they change.
 *
 * <p>Membership is {@code listManagedInstances}, read through {@link GcpReadOnlyView} only; nothing here mutates the
 * cloud or writes money. An instance that left is closed from the Phase 28 preemption tracker's
 * {@code PREEMPTION} event (a {@code compute.instances.preempted} zone operation) with
 * {@code end_cause=PROVIDER_INTERRUPTED} and {@code end_cause_source=GCE_PREEMPTED_OPERATION}, else from a
 * {@code compute.instances.delete} zone operation (a known end, the provider charged normally), else it keeps
 * {@code first_absent_at} and closes UNKNOWN after the grace ({@link IntervalTracker}). A VM is opened at the sweep
 * that first sees it ({@code SWEEP_FIRST_SEEN}): the read view has no instances.get, and a later start can only lower
 * both the counterfactual and the Spot cost. It is governed only by switch L ({@link LedgerGate#gcpWriterOn()}).
 */
@Component
public class GcpLedgerObserver {
    private static final Logger log = LoggerFactory.getLogger(GcpLedgerObserver.class);
    public static final String JOB = "ledger-observe:gcp";
    public static final String PROVIDER = "GCP";
    static final String DELETE_OPERATION = "compute.instances.delete";
    static final int KNOWN_DAYS = 3;
    static final java.time.Duration DELETE_LOOKBACK = java.time.Duration.ofHours(2);

    private final JobRunner jobRunner;
    private final LedgerGate gate;
    private final ObservationStore store;
    private final CloudAccountRepository accounts;
    private final GcpViewSource views;
    private final GcpMigStatusReader reader;
    private final JdbcTemplate jdbc;
    private final AutoSpottingMetrics metrics;
    private final Clock clock;

    @Autowired
    public GcpLedgerObserver(JobRunner jobRunner, LedgerGate gate, ObservationStore store,
                             CloudAccountRepository accounts, GcpViewSource views, GcpMigStatusReader reader,
                             JdbcTemplate jdbc, AutoSpottingMetrics metrics) {
        this(jobRunner, gate, store, accounts, views, reader, jdbc, metrics, Clock.systemUTC());
    }

    public GcpLedgerObserver(JobRunner jobRunner, LedgerGate gate, ObservationStore store,
                             CloudAccountRepository accounts, GcpViewSource views, GcpMigStatusReader reader,
                             JdbcTemplate jdbc, AutoSpottingMetrics metrics, Clock clock) {
        this.jobRunner = jobRunner;
        this.gate = gate;
        this.store = store;
        this.accounts = accounts;
        this.views = views;
        this.reader = reader;
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Every 5 minutes. */
    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
    public void sweep() {
        jobRunner.runLeased(JOB, null, () -> {
            if (gate.gcpWriterOn()) {
                observeAll();
            }
        });
    }

    /** One sweep over every measured GCP group; returns the number of groups observed without error. */
    public int observeAll() {
        int observed = 0;
        Map<Long, Optional<CloudAccount>> cache = new LinkedHashMap<>();
        for (ObservationStore.ObservedGroup group : store.measuredGroups(PROVIDER, AwsLedgerObserver.MEASURED_STATES)) {
            Optional<CloudAccount> account = cache.computeIfAbsent(group.cloudAccountId(), accounts::findById);
            if (account.isEmpty()) {
                continue;
            }
            try {
                if (observeGroup(account.get(), group)) {
                    observed++;
                }
            } catch (RuntimeException e) {
                metrics.cloudError(Provider.GCP, "ledger-observe", GcpErrorMapper.classify(e).name());
                log.warn("ledger-observe:gcp skipped group {}: {}", group.id(), e.getClass().getSimpleName());
            }
        }
        return observed;
    }

    public boolean observeGroup(CloudAccount account, ObservationStore.ObservedGroup group) {
        GcpEligibilityEvaluator.Key key;
        try {
            key = GcpEligibilityEvaluator.split(group.groupKey());
        } catch (IllegalArgumentException e) {
            return false;
        }
        GcpReadOnlyView view = views.view(account);
        Optional<JsonNode> described = reader.describeGroup(view, key);
        if (described.isEmpty()) {
            return false; // the reconciler owns the GONE decision; the observer writes nothing for a missing group
        }
        Instant now = clock.instant();
        GcpMigFacts mig = GcpMigFacts.from(key.project(), described.get());
        List<JsonNode> managed = view.reads().listManagedInstances(mig.regional(), mig.location(), mig.name());
        Map<String, String> templateTypes = new HashMap<>();
        for (GcpMigFacts.Version v : mig.versions()) {
            templateTypes.put(v.name(), machineType(view, v.instanceTemplate()));
        }
        Map<String, IntervalTracker.Present> present = new LinkedHashMap<>();
        for (JsonNode mi : managed) {
            if (!"RUNNING".equals(mi.path("instanceStatus").asText())) {
                continue; // a VM that is not running is not billed: it opens when it runs
            }
            String link = mi.path("instance").asText("");
            String name = last(link);
            String zone = zoneOf(link);
            String versionName = mi.path("version").path("name").asText("");
            String type = templateTypes.get(versionName);
            if (name == null || type == null) {
                continue;
            }
            boolean spot = GcpMigFacts.SPOT_VERSION.equals(versionName);
            present.put(name, new IntervalTracker.Present(name, type, zone, null, spot ? "SPOT" : "ON_DEMAND",
                    BigDecimal.ONE.setScale(6), null));
        }
        List<IntervalTracker.Open> open = store.openIntervals(group.id());
        Set<String> known = new HashSet<>(store.knownInstanceIds(group.id(), KNOWN_DAYS));
        Set<String> absent = new HashSet<>();
        open.forEach(o -> {
            if (!present.containsKey(o.instanceId())) {
                absent.add(o.instanceId());
            }
        });
        Map<String, IntervalTracker.Ending> endings = absent.isEmpty() ? Map.of() : endings(view, group, absent, now);
        Map<String, IntervalTracker.Present> forTracker = new LinkedHashMap<>(present);
        for (String id : present.keySet()) {
            if (known.contains(id)) {
                forTracker.put(id, new IntervalTracker.Present(id, null, null, null, null, null, null));
            }
        }
        List<IntervalTracker.Action> actions = IntervalTracker.plan(now, open, known, forTracker, endings,
                "GCE_OPERATION");
        ObservationStore.Sample sample = sampleIfChanged(mig, store.latestSample(group.id()));
        store.apply(PROVIDER, group.id(), group.cloudAccountId(), now, actions, sample);
        return true;
    }

    /** Ends of the absent instances: a recorded preemption first (the tracker's row), then a delete operation. */
    private Map<String, IntervalTracker.Ending> endings(GcpReadOnlyView view, ObservationStore.ObservedGroup group,
                                                        Set<String> absent, Instant now) {
        Map<String, IntervalTracker.Ending> out = new LinkedHashMap<>();
        jdbc.query("SELECT instance_id, occurred_at FROM spot_group_event WHERE group_id = ? AND event_type = 'PREEMPTION'"
                        + " AND instance_id IN (" + String.join(",", absent.stream().map(a -> "?").toList()) + ")",
                rs -> {
                    out.merge(rs.getString(1), new IntervalTracker.Ending(rs.getString(1),
                                    rs.getTimestamp(2).toInstant(),
                                    new EndCauseClassifier.Cause(EndCauseClassifier.PROVIDER_INTERRUPTED,
                                            EndCauseClassifier.SOURCE_GCE_PREEMPTED_OPERATION), null),
                            (a, b) -> a.endAt().isBefore(b.endAt()) ? a : b);
                }, concat(group.id(), absent));
        Set<String> rest = new HashSet<>(absent);
        rest.removeAll(out.keySet());
        if (rest.isEmpty()) {
            return out;
        }
        Set<String> zones = new HashSet<>();
        for (IntervalTracker.Open o : store.openIntervals(group.id())) {
            if (rest.contains(o.instanceId())) {
                zones.add(zoneOfInterval(group.id(), o.instanceId()));
            }
        }
        Instant since = now.minus(DELETE_LOOKBACK);
        for (String zone : zones) {
            if (zone == null) {
                continue;
            }
            for (JsonNode op : view.reads().listZoneOperations(zone, DELETE_OPERATION, since.toString())) {
                String instance = last(op.path("targetLink").asText(""));
                String end = op.path("endTime").asText(op.path("insertTime").asText(null));
                if (instance != null && rest.contains(instance) && end != null) {
                    out.putIfAbsent(instance, new IntervalTracker.Ending(instance, parse(end),
                            new EndCauseClassifier.Cause(EndCauseClassifier.SCALE_IN,
                                    EndCauseClassifier.SOURCE_GCE_DELETE_OPERATION), null));
                }
            }
        }
        return out;
    }

    private String zoneOfInterval(UUID groupId, String instanceId) {
        List<String> z = jdbc.queryForList("SELECT zone FROM spot_usage_interval WHERE group_id = ? AND instance_id = ?"
                + " AND closed = false LIMIT 1", String.class, groupId, instanceId);
        return z.isEmpty() ? null : z.get(0);
    }

    private static Object[] concat(UUID groupId, Set<String> ids) {
        Object[] a = new Object[ids.size() + 1];
        a[0] = groupId;
        int i = 1;
        for (String id : ids) {
            a[i++] = id;
        }
        return a;
    }

    // ------------------------------------------------------------------ capacity sample

    /**
     * Desired is the MIG {@code targetSize}; the floor N is the standard version's fixed target size (0 when it is
     * not fixed); the original Spot units are 0, because a group XamOps manages was converted from a standard MIG.
     */
    private ObservationStore.Sample sampleIfChanged(GcpMigFacts mig, Optional<ObservationStore.Sample> latest) {
        BigDecimal desired = BigDecimal.valueOf(mig.targetSize());
        BigDecimal floor = BigDecimal.ZERO;
        for (GcpMigFacts.Version v : mig.versions()) {
            if (GcpMigFacts.STANDARD_VERSION.equals(v.name()) && v.targetSizeFixed() != null) {
                floor = BigDecimal.valueOf(v.targetSizeFixed());
            }
        }
        ObservationStore.Sample next = new ObservationStore.Sample(desired.setScale(6), floor.setScale(6),
                BigDecimal.ZERO.setScale(6));
        if (latest.isPresent() && latest.get().desired().compareTo(next.desired()) == 0
                && latest.get().odFloor().compareTo(next.odFloor()) == 0
                && latest.get().originalSpot().compareTo(next.originalSpot()) == 0) {
            return null;
        }
        return next;
    }

    // ------------------------------------------------------------------ reads

    private String machineType(GcpReadOnlyView view, String templateLink) {
        if (templateLink == null) {
            return null;
        }
        String type = view.reads().getInstanceTemplate(templateLink).path("properties").path("machineType").asText(null);
        return type == null ? null : last(type);
    }

    private static String last(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private static String zoneOf(String instanceLink) {
        int i = instanceLink.indexOf("/zones/");
        if (i < 0) {
            return null;
        }
        String rest = instanceLink.substring(i + 7);
        return rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : null;
    }

    private static Instant parse(String v) {
        try {
            return java.time.OffsetDateTime.parse(v).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return Instant.parse(v);
        }
    }
}
