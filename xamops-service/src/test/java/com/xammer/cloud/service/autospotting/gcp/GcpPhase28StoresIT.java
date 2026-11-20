package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL the Phase 28 GCP code adds, on real PostgreSQL: the write-time dedupe of preemption rows
 * ({@code ux_sge_dedupe}, AC-HLT-06, AC-EVT-06) and the registry queries behind revert, fallback exit and the template
 * lifecycle (AC-GCP-05, AC-GCP-06). No GCP call is made.
 */
@Tag("containers")
class GcpPhase28StoresIT {
    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    @Test
    void thePreemptionOfOneOperationIsOneRowWhoeverRecordsItHowOften_andSuspectedRowsAreNeverCounted() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            GcpEventRecorder events = new GcpEventRecorder(h.jdbc);
            assertThat(events.record(h.f.groupId, "PREEMPTION", "op-1", "web-1", "n2-standard-4", "us-central1-b", T0.minusSeconds(60), T0)).isTrue();
            assertThat(events.record(h.f.groupId, "PREEMPTION", "op-1", "web-1", "n2-standard-4", "us-central1-b", T0.minusSeconds(60), T0.plusSeconds(300))).isFalse();
            assertThat(events.record(h.f.groupId, "PREEMPTION", "op-2", "web-2", "n2-standard-4", "us-central1-b", T0.minusSeconds(30), T0)).isTrue();
            assertThat(events.record(h.f.groupId, "PREEMPTION", "op-3", "web-3", "n2-standard-4", "us-central1-a", T0.minus(Duration.ofHours(30)), T0)).isTrue();
            assertThat(events.record(h.f.groupId, "SUSPECTED_PREEMPTION", "susp:web-4:x", "web-4", "n2-standard-4", "us-central1-a", T0, T0)).isTrue();
            assertThat(h.count("SELECT count(*) FROM spot_group_event WHERE group_id = ? AND provider = 'GCP'", h.f.groupId)).isEqualTo(4);
            assertThat(h.jdbc.queryForObject("SELECT source FROM spot_group_event WHERE event_key = 'op-1'", String.class)).isEqualTo("POLL");
            assertThat(h.jdbc.queryForObject("SELECT instance_id FROM spot_group_event WHERE event_key = 'op-1'", String.class)).isEqualTo("web-1");
            assertThat(events.countSince(h.f.groupId, "PREEMPTION", T0.minus(Duration.ofHours(1)))).isEqualTo(2);
            assertThat(events.countSince(h.f.groupId, "PREEMPTION", T0.minus(Duration.ofHours(24)))).isEqualTo(2);
            assertThat(events.countSince(h.f.groupId, "PREEMPTION", T0.minus(Duration.ofHours(48)))).isEqualTo(3);
            assertThat(events.countByZoneSince(h.f.groupId, T0.minus(Duration.ofHours(24)))).containsOnlyKeys("us-central1-b")
                    .containsEntry("us-central1-b", 2);
        }
    }

    private UUID finishedOp(GcpOpsHarness h, String status) {
        UUID id = UUID.randomUUID();
        h.store.insertQueued(OperationRecord.builder(id, h.f.groupId, 3L, OperationKind.CONFIG_APPLY).priorState(GroupState.ACTIVE)
                .compensationTarget(CompensationTarget.NONE).requestedBy("tester").build());
        h.jdbc.update("UPDATE spot_operation SET status = ?, finished_at = now() WHERE id = ?", status, id);
        return id;
    }

    @Test
    void theRegistryQueriesFindTheOwnedLinksTheNewestSpotAndOnlyOrphansOfFinishedOps() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            GcpTemplateRegistry registry = new GcpTemplateRegistry(h.jdbc);
            String base = FakeGcp.P + "/global/instanceTemplates/";
            UUID done1 = finishedOp(h, "FAILED");
            UUID done2 = finishedOp(h, "SUCCEEDED");
            UUID running = UUID.randomUUID();
            h.store.insertQueued(OperationRecord.builder(running, h.f.groupId, 3L, OperationKind.CONFIG_APPLY)
                    .priorState(GroupState.ACTIVE).compensationTarget(CompensationTarget.NONE).requestedBy("tester").build());
            GcpTemplateRegistry.Row g1 = registry.registerCreating(h.f.groupId, 1, "SPOT", "web-xas-aaaa0001", "global",
                    base + "web-xas-aaaa0001", GcpConversionFixture.ORIGINAL, "7700", "sha1", done1, T0);
            GcpTemplateRegistry.Row g2 = registry.registerCreating(h.f.groupId, 2, "SPOT", "web-xas-aaaa0002", "global",
                    base + "web-xas-aaaa0002", GcpConversionFixture.ORIGINAL, "7700", "sha2", done2, T0);
            registry.markInUse(g2.id(), "9002", T0);
            GcpTemplateRegistry.Row g3 = registry.registerCreating(h.f.groupId, 3, "REBUILT", "web-xas-aaaa0003", "global",
                    base + "web-xas-aaaa0003", GcpConversionFixture.ORIGINAL, null, "sha3", running, T0);
            assertThat(registry.ownedLinks(h.f.groupId)).containsExactlyInAnyOrder(g1.selfLink(), g2.selfLink(), g3.selfLink());
            assertThat(registry.ownedLinks(UUID.randomUUID())).isEmpty();
            // The newest SPOT row that still exists: g2 (IN_USE); g1 is CREATING, g3 is REBUILT.
            assertThat(registry.latestSpot(h.f.groupId).orElseThrow().id()).isEqualTo(g2.id());
            registry.retireUnreferenced(h.f.groupId, java.util.List.of(), Duration.ofDays(7), T0);
            assertThat(registry.latestSpot(h.f.groupId).orElseThrow().status()).as("a retired copy can still be exited to").isEqualTo("RETIRED");
            // Orphans: CREATING rows whose creating op is finished and untouched for the quiet window.
            h.jdbc.update("UPDATE spot_gcp_template SET updated_at = ? WHERE id = ?", java.sql.Timestamp.from(T0.minus(Duration.ofHours(3))), java.util.UUID.fromString(g1.id().toString()));
            h.jdbc.update("UPDATE spot_gcp_template SET updated_at = ? WHERE id = ?", java.sql.Timestamp.from(T0.minus(Duration.ofHours(3))), g3.id());
            assertThat(registry.orphanCandidates(T0, Duration.ofHours(1))).extracting(GcpTemplateRegistry.Row::id)
                    .containsExactly(g1.id()); // g3's op is still running
            assertThat(registry.orphanCandidates(T0, Duration.ofHours(6))).isEmpty();
            registry.retireOrphan(g1.id(), "9001", Duration.ofDays(7), T0);
            GcpTemplateRegistry.Row retired = registry.findByOp(done1).orElseThrow();
            assertThat(retired.status()).isEqualTo("RETIRED");
            assertThat(retired.gcpId()).isEqualTo("9001");
            assertThat(retired.deleteAfter()).isEqualTo(T0.plus(Duration.ofDays(7)));
            assertThat(registry.deletionCandidates(T0.plus(Duration.ofDays(8)))).extracting(GcpTemplateRegistry.Row::id)
                    .contains(g1.id()).doesNotContain(g3.id());
            assertThat(registry.deletionCandidates(T0.plus(Duration.ofDays(6)))).extracting(GcpTemplateRegistry.Row::id)
                    .doesNotContain(g1.id());
        }
    }

    @Test
    void theSnapshotOfAGroupIsFoundByItsKeyForTheRevertPlan() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            h.enableToActive(3);
            GcpSnapshotStore store = new GcpSnapshotStore(h.jdbc, null);
            assertThat(store.findLinkedByKey(3L, h.f.key)).isPresent();
            assertThat(store.findLinkedByKey(3L, h.f.key).orElseThrow().payload()).contains(GcpConversionFixture.ORIGINAL);
            assertThat(store.findLinkedByKey(4L, h.f.key)).isEmpty();
            assertThat(store.findLinkedByKey(3L, "xamops-test/us-central1/other")).isEmpty();
        }
    }
}
