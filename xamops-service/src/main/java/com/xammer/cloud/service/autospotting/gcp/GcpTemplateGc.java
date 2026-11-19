package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template garbage collection (design-gcp.md §9.3, FR-GCP-07, AC-GCP-05). The registry is the only basis for
 * deletion: a template that is not a registry row is never touched, whatever its name, description or label.
 * A row is deleted only when ALL hold: it is RETIRED and past its grace period (set when a rollout completed
 * and the MIG stopped using it), its creating operation is terminal, the cloud template's numeric id equals the
 * recorded {@code gcp_id} (a customer template recreated under the same name is never ours), no MIG in the
 * project references it, and the GCP mutation gate is open. A template still in use puts the row back to RETIRED
 * with a new grace period. The original customer template is never in the registry.
 *
 * <p>Nothing schedules this class in this phase: it is invoked by the Phase 28 job, behind the same gate.
 */
@Component
public class GcpTemplateGc {
    private static final Logger log = LoggerFactory.getLogger(GcpTemplateGc.class);
    private static final Pattern PROJECT = Pattern.compile("/projects/([^/]+)/");

    /** What one run did. */
    public record Result(int deleted, int keptInUse, int gone, int skipped) {
    }

    private final GcpTemplateRegistry registry;
    private final CloudAccountRepository accounts;
    private final GcpViewSource views;
    private final GcpMutatingSource mutating;
    private final GcpProviderGate gate;
    private final Clock clock;
    private final Duration retention;

    @Autowired
    public GcpTemplateGc(GcpTemplateRegistry registry, CloudAccountRepository accounts, GcpViewSource views,
                         GcpMutatingSource mutating, GcpProviderGate gate,
                         @Value("${autospotting.gcp.template-retention:7d}") Duration retention) {
        this(registry, accounts, views, mutating, gate, Clock.systemUTC(), retention);
    }

    GcpTemplateGc(GcpTemplateRegistry registry, CloudAccountRepository accounts, GcpViewSource views,
                  GcpMutatingSource mutating, GcpProviderGate gate, Clock clock, Duration retention) {
        this.registry = registry;
        this.accounts = accounts;
        this.views = views;
        this.mutating = mutating;
        this.gate = gate;
        this.clock = clock;
        this.retention = retention;
    }

    public Result runOnce() {
        if (!gate.open()) {
            return new Result(0, 0, 0, 0); // dark: every GCP mutation switch must be on
        }
        Instant now = clock.instant();
        int deleted = 0;
        int inUse = 0;
        int gone = 0;
        int skipped = 0;
        for (GcpTemplateRegistry.Row row : registry.deletionCandidates(now)) {
            Optional<CloudAccount> account = accountOf(row);
            if (account.isEmpty() || !registry.markDeleting(row.id(), now)) {
                skipped++;
                continue;
            }
            try {
                switch (collect(row, account.get(), now)) {
                    case DELETED -> deleted++;
                    case IN_USE -> inUse++;
                    case GONE -> gone++;
                }
            } catch (GcpReadException e) {
                registry.backToRetired(row.id(), retention, now);
                skipped++;
                log.warn("template {} not deleted this run: {}", row.name(), e.check());
            }
        }
        return new Result(deleted, inUse, gone, skipped);
    }

    private enum Outcome { DELETED, IN_USE, GONE }

    private Outcome collect(GcpTemplateRegistry.Row row, CloudAccount account, Instant now) {
        GcpReadOnlyView view = views.view(account);
        JsonNode cloud;
        try {
            cloud = view.reads().getInstanceTemplate(row.selfLink());
        } catch (GcpReadException e) {
            if (e.kind() == ErrorKind.NOT_FOUND) {
                registry.markGone(row.id(), now);
                return Outcome.GONE;
            }
            throw e;
        }
        if (!row.gcpId().equals(cloud.path("id").asText(null))) {
            registry.markGone(row.id(), now); // someone recreated the name: not ours, never deleted
            return Outcome.GONE;
        }
        for (JsonNode mig : view.reads().aggregatedListMigs()) {
            GcpMigFacts g = GcpMigFacts.from(view.project(), mig);
            if (g.templateLinks().stream().anyMatch(l -> GcpTemplateRegistry.sameTemplate(l, row.selfLink()))) {
                registry.backToRetired(row.id(), retention, now);
                return Outcome.IN_USE;
            }
        }
        GcpMigClient.Mutating client = mutating.mutating(account);
        try {
            client.deleteInstanceTemplate(row.regionOrNull(), row.name(), UUID.nameUUIDFromBytes(
                    ("gc-" + row.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (GcpReadException e) {
            if (e.kind() == ErrorKind.NOT_FOUND) {
                registry.markGone(row.id(), now);
                return Outcome.GONE;
            }
            if (e.kind() == ErrorKind.CONFLICT || e.httpStatus() == 400) {
                // resourceInUseByAnotherResource: back to RETIRED with a new delete_after, not an error.
                registry.backToRetired(row.id(), retention, now);
                return Outcome.IN_USE;
            }
            throw e;
        }
        registry.markDeleted(row.id(), now);
        return Outcome.DELETED;
    }

    private Optional<CloudAccount> accountOf(GcpTemplateRegistry.Row row) {
        Matcher m = PROJECT.matcher(row.selfLink());
        if (!m.find()) {
            return Optional.empty();
        }
        String project = m.group(1);
        List<CloudAccount> rows = accounts.findByAwsAccountIdOrGcpProjectId(project, project);
        return rows.stream().filter(a -> project.equals(a.getGcpProjectId())).filter(a -> a.getId() != null)
                .min(Comparator.comparing(CloudAccount::getId));
    }
}
