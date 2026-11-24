package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.FallbackProperties;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.reconcile.DriftClassifier;
import com.xammer.cloud.service.autospotting.core.reconcile.FallbackEnqueuer;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GCP stockout and quota fallback and its exit on real PostgreSQL, with the real runner, lease, stores, lifecycle and GCP
 * provider over a stubbed Compute (plan-phase-28.md 28.2; AC-HLT-02, AC-HLT-03, AC-HLT-04; DC-07, DC-12). The mutation
 * switches are on in this context only. No GCP call is made.
 */
@Tag("containers")
class GcpFallbackIT {
    private static String planHash(GcpOpsHarness h) {
        return GcpFingerprint.planHash(GcpMigFacts.from(FakeGcp.PROJECT, h.f.gcp.mig()));
    }

    /** Five of six instances run, with three distinct stockout errors: a one-VM shortfall with a capacity cause. */
    private static void stockout(GcpOpsHarness h) {
        h.f.gcp.managedInstances.remove(5);
        for (int i = 0; i < 3; i++) {
            h.f.gcp.errors.add(GcpConversionFixture.JSON.createObjectNode().<ObjectNode>set("error",
                    GcpConversionFixture.JSON.createObjectNode().put("code", "ZONE_RESOURCE_POOL_EXHAUSTED"))
                    .put("timestamp", h.f.clock.instant().toString()).set("instanceActionDetails",
                            GcpConversionFixture.JSON.createObjectNode().put("instance", "web-" + i)));
        }
    }

    /** Sweeps five minutes apart until the rules ask for a fallback; returns how many sweeps it took. */
    private static int sweepsUntilEnter(GcpOpsHarness h) {
        GcpMigSpotProvider provider = h.provider();
        SpotGroupRepository groups = h.ctx.getBean(SpotGroupRepository.class);
        for (int sweep = 1; sweep <= 8; sweep++) {
            SpotGroup row = groups.findById(h.f.groupId).orElseThrow();
            GroupStatus status = provider.getStatus(row, h.f.gcp.view());
            h.jdbc.update("UPDATE spot_group SET health_state = ?::jsonb WHERE id = ?", status.healthJson(), h.f.groupId);
            if (provider.reconcile(row, status).contains(ReconcileAction.ENQUEUE_FALLBACK)) {
                return sweep;
            }
            h.f.clock.advance(Duration.ofMinutes(5));
        }
        throw new AssertionError("no fallback was asked for");
    }

    private static FallbackEnqueuer enqueuer(GcpOpsHarness h) {
        return new FallbackEnqueuer(h.ctx.getBean(SpotOperationRepository.class), h.ctx.getBean(PreemptionService.class),
                h.store, FallbackProperties.defaults(), h.ctx.getBean(AutoSpottingAuditWriter.class),
                h.ctx.getBean(AutoSpottingMetrics.class), h.ctx.getBean(SwitchService.class));
    }

    private static <T> T inTx(GcpOpsHarness h, java.util.function.Supplier<T> body) {
        return new TransactionTemplate(h.ctx.getBean(PlatformTransactionManager.class)).execute(s -> body.get());
    }

    /** Stockout during ACTIVE: the FALLBACK_ENTER is queued and claimed; returns its id. */
    private static UUID enterOp(GcpOpsHarness h) {
        stockout(h);
        sweepsUntilEnter(h);
        SpotGroupRepository groups = h.ctx.getBean(SpotGroupRepository.class);
        FallbackEnqueuer.Result r = inTx(h, () -> enqueuer(h).enqueueEnter(groups.findById(h.f.groupId).orElseThrow(), "RECONCILE"));
        assertThat(r).isEqualTo(FallbackEnqueuer.Result.ENQUEUED);
        h.opId = h.jdbc.queryForObject("SELECT id FROM spot_operation WHERE group_id = ? AND kind = 'FALLBACK_ENTER'"
                + " AND status = 'QUEUED'", UUID.class, h.f.groupId);
        return h.opId;
    }

    private static void standardOnly(GcpOpsHarness h, int running) {
        h.f.gcp.managedInstances.clear();
        for (int i = 0; i < running; i++) {
            h.f.gcp.managedInstances.add(GcpConversionFixture.instance(i, GcpMigFacts.STANDARD_VERSION,
                    GcpConversionFixture.ORIGINAL, "NONE"));
        }
        ObjectNode status = (ObjectNode) h.f.gcp.mig().path("status");
        status.put("isStable", true);
        ((ObjectNode) status.path("versionTarget")).put("isReached", true);
    }

    private static void noDrift(GcpOpsHarness h) {
        GcpMigSpotProvider provider = h.provider();
        SpotGroup row = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
        for (int i = 0; i < 2; i++) {
            GroupStatus status = provider.getStatus(row, h.f.gcp.view());
            assertThat(new DriftClassifier().classify(row, status, false, false, false, false, false, false).code())
                    .isEqualTo(DriftClassifier.Code.NONE);
        }
    }

    @Test
    void aSustainedStockoutEntersFallbackAtTheVerifiedPatch_degradesAfterTwentyMinutesAndRecoversToFallback() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            h.enableToActive(3);
            stockout(h);
            assertThat(sweepsUntilEnter(h)).as("not before 15 minutes of shortfall: the fourth sweep").isEqualTo(4);
            // The stockout hits standard capacity too: two of the six are missing when the op runs.
            h.f.gcp.managedInstances.remove(4);
            SpotGroupRepository groups = h.ctx.getBean(SpotGroupRepository.class);
            FallbackEnqueuer.Result r = inTx(h, () -> enqueuer(h).enqueueEnter(groups.findById(h.f.groupId).orElseThrow(), "RECONCILE"));
            assertThat(r).isEqualTo(FallbackEnqueuer.Result.ENQUEUED);
            assertThat(inTx(h, () -> enqueuer(h).enqueueEnter(groups.findById(h.f.groupId).orElseThrow(), "RECONCILE")))
                    .as("a second sweep adds nothing: the group is busy").isNotEqualTo(FallbackEnqueuer.Result.ENQUEUED);
            h.opId = h.jdbc.queryForObject("SELECT id FROM spot_operation WHERE group_id = ? AND kind = 'FALLBACK_ENTER'"
                    + " AND status = 'QUEUED'", UUID.class, h.f.groupId);
            h.claimAndRun();
            // FALLBACK right after the verified PATCH, config_mode FALLBACK, the fingerprint stored, one mutation sent.
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            assertThat(h.groupColumn("config_mode")).isEqualTo("FALLBACK");
            assertThat(h.groupColumn("fallback_fingerprint")).isNotBlank();
            assertThat(h.f.mutating.patches).hasSize(1);
            JsonNode sent = h.f.mutating.patches.get(0);
            assertThat(sent.path("versions")).hasSize(1);
            assertThat(sent.path("versions").path(0).path("name").asText()).isEqualTo("xas-standard");
            assertThat(sent.path("versions").path(0).path("instanceTemplate").asText()).isEqualTo(GcpConversionFixture.ORIGINAL);
            assertThat(sent.path("updatePolicy").path("maxUnavailable").path("fixed").asInt()).isZero();
            assertThat(h.f.mutating.inserted).isEmpty();
            assertThat(h.opStatus()).as("the tail wait is still running").isEqualTo("WAITING");
            // effective_at equals the PATCH step's intent_at, so billing ends at the mutation.
            Timestamp effective = h.jdbc.queryForObject("SELECT effective_at FROM spot_group_transition"
                    + " WHERE group_id = ? AND to_state = 'FALLBACK'", Timestamp.class, h.f.groupId);
            Timestamp intent = h.jdbc.queryForObject("SELECT intent_at FROM spot_operation_step"
                    + " WHERE operation_id = ? AND name = 'PATCH_MIG'", Timestamp.class, h.opId);
            assertThat(effective).isEqualTo(intent);
            assertThat(h.count("SELECT count(*) FROM autospotting_audit WHERE group_id = ? AND event_type = 'FALLBACK_ENTERED'",
                    h.f.groupId)).isEqualTo(1);
            // 19 minutes in: still FALLBACK. At 20: standard is still short, so DEGRADED; config_mode stays FALLBACK.
            h.f.clock.advance(Duration.ofMinutes(19));
            h.makeDue();
            h.claimAndRun();
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            h.f.clock.advance(Duration.ofMinutes(1));
            h.makeDue();
            h.claimAndRun();
            assertThat(h.groupState()).isEqualTo("DEGRADED");
            assertThat(h.groupColumn("config_mode")).isEqualTo("FALLBACK");
            // Capacity returns: DEGRADED goes back to FALLBACK, never straight to ACTIVE, and the op ends.
            standardOnly(h, 6);
            for (int i = 0; i < 12 && !"SUCCEEDED".equals(h.opStatus()); i++) {
                h.f.clock.advance(Duration.ofSeconds(61));
                h.makeDue();
                h.claimAndRun();
            }
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            assertThat(h.f.mutating.patches).as("the fallback PATCH and the customer-policy restore").hasSize(2);
            assertThat(h.f.mutating.deleted).isEmpty();
            // The stored fingerprint is the hash the MIG has once the policy is final: two sweeps show no drift.
            assertThat(h.groupColumn("fallback_fingerprint")).isEqualTo(planHash(h));
            noDrift(h);
        }
    }

    @Test
    void aShortageThatOutlastsTwoHoursEndsSucceededCapacityPendingAndLeavesTheXamOpsPolicyOnTheMig() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            h.enableToActive(3);
            enterOp(h);
            h.f.gcp.managedInstances.remove(3);
            h.claimAndRun();
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            h.f.clock.advance(Duration.ofMinutes(121));
            h.makeDue();
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).as("the finished op does not move the group again").isEqualTo("DEGRADED");
            assertThat(h.groupColumn("config_mode")).isEqualTo("FALLBACK");
            assertThat(h.f.mutating.patches).as("no restore: the surge-first policy stays").hasSize(1);
            assertThat(h.f.gcp.mig().path("updatePolicy").path("maxUnavailable").path("fixed").asInt()).isZero();
            assertThat(h.groupColumn("fallback_fingerprint")).isEqualTo(planHash(h));
            noDrift(h);
        }
    }

    @Test
    void aRevertDuringTheTailWaitPreemptsItAndStartsWithinOnePoll() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            h.enableToActive(3);
            enterOp(h);
            h.f.gcp.managedInstances.remove(3);
            h.claimAndRun();
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            UUID revertId = UUID.randomUUID();
            PreemptionService.Preemption p = h.ctx.getBean(PreemptionService.class)
                    .preemptFor(h.f.groupId, OperationKind.REVERT, revertId);
            assertThat(p.verdict()).isEqualTo(PreemptionService.Verdict.PREEMPTED);
            h.store.insertQueued(OperationRecord.builder(revertId, h.f.groupId, 3L, OperationKind.REVERT)
                    .priorState(GroupState.FALLBACK).compensationTarget(CompensationTarget.NONE).requestedBy("tester").build());
            h.makeDue();
            h.claimAndRun(); // the next poll of the fallback op: it sees the cancel and ends
            assertThat(h.opStatus()).isEqualTo("CANCELLED");
            assertThat(h.store.claimForRun(revertId)).as("the revert starts on the next dispatcher poll").isTrue();
        }
    }

    @Test
    void theExitReturnsToTheSpotMixWithTheExistingTemplate_andTheDailyCapHoldsAGroupInFallbackWithoutDegradingIt() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            h.enableToActive(3);
            String spot = h.jdbc.queryForObject("SELECT self_link FROM spot_gcp_template WHERE created_by_op = ?", String.class,
                    h.f.opId);
            UUID enter = enterOp(h);
            h.claimAndRun();
            standardOnly(h, 6);
            for (int i = 0; i < 12 && !"SUCCEEDED".equals(h.opStatus()); i++) {
                h.f.clock.advance(Duration.ofSeconds(61));
                h.makeDue();
                h.claimAndRun();
            }
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            SpotGroupRepository groups = h.ctx.getBean(SpotGroupRepository.class);
            // The group has been in FALLBACK for two hours.
            h.jdbc.update("UPDATE spot_group SET state_changed_at = now() - interval '2 hours' WHERE id = ?", h.f.groupId);
            // Cap: with four transitions already today an exit would leave no room for an enter (4 + 2 > 5).
            for (int i = 0; i < 3; i++) {
                UUID id = UUID.randomUUID();
                h.store.insertQueued(OperationRecord.builder(id, h.f.groupId, 3L, OperationKind.FALLBACK_ENTER)
                        .priorState(GroupState.ACTIVE).compensationTarget(CompensationTarget.NONE).requestedBy("system").build());
                h.jdbc.update("UPDATE spot_operation SET status = 'SUCCEEDED', finished_at = now() WHERE id = ?", id);
            }
            FallbackEnqueuer.Result held = inTx(h, () -> enqueuer(h).enqueueExit(groups.findById(h.f.groupId).orElseThrow(), Instant.now()));
            assertThat(held).isEqualTo(FallbackEnqueuer.Result.CAPPED);
            assertThat(h.groupState()).as("the cap never makes a group DEGRADED").isEqualTo("FALLBACK");
            assertThat(h.count("SELECT count(*) FROM spot_operation WHERE group_id = ? AND kind = 'FALLBACK_EXIT'", h.f.groupId))
                    .isZero();
            // After 00:00 UTC the same group exits.
            Instant tomorrow = Instant.now().plus(Duration.ofHours(26));
            FallbackEnqueuer.Result next = inTx(h, () -> enqueuer(h).enqueueExit(groups.findById(h.f.groupId).orElseThrow(), tomorrow));
            assertThat(next).isEqualTo(FallbackEnqueuer.Result.ENQUEUED);
            h.opId = h.jdbc.queryForObject("SELECT id FROM spot_operation WHERE group_id = ? AND kind = 'FALLBACK_EXIT'",
                    UUID.class, h.f.groupId);
            h.f.mutating.patches.clear();
            h.claimAndRun();
            assertThat(h.groupState()).as("a surge-first rollout back to the Spot mix").isEqualTo("APPLYING");
            JsonNode v = h.f.mutating.patches.get(0).path("versions");
            assertThat(v).hasSize(2);
            assertThat(v.path(0).path("name").asText()).isEqualTo("xas-standard");
            assertThat(v.path(0).path("targetSize").path("fixed").asInt()).isEqualTo(3);
            assertThat(v.path(1).path("instanceTemplate").asText()).as("the existing Spot template, none created").isEqualTo(spot);
            assertThat(h.f.mutating.inserted).isEmpty();
            h.f.completeRollout(3, spot);
            for (int i = 0; i < 30 && !"SUCCEEDED".equals(h.opStatus()); i++) {
                h.f.clock.advance(Duration.ofSeconds(61));
                h.makeDue();
                h.claimAndRun();
            }
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(h.groupColumn("config_mode")).isEqualTo("MANAGED");
            assertThat(h.groupColumn("fallback_fingerprint")).isNull();
            assertThat(h.groupColumn("desired_fingerprint")).isEqualTo(planHash(h));
            assertThat(h.jdbc.queryForObject("SELECT status FROM spot_gcp_template WHERE self_link = ?", String.class, spot))
                    .isEqualTo("IN_USE");
            assertThat(enter).isNotNull();
        }
    }

    @Test
    void anExitThatHitsACapacityStallCompensatesBackToTheSingleStandardVersionAndTheGroupReturnsToFallback() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            h.enableToActive(3);
            String spot = h.jdbc.queryForObject("SELECT self_link FROM spot_gcp_template WHERE created_by_op = ?", String.class,
                    h.f.opId);
            enterOp(h);
            h.claimAndRun();
            standardOnly(h, 6);
            for (int i = 0; i < 12 && !"SUCCEEDED".equals(h.opStatus()); i++) {
                h.f.clock.advance(Duration.ofSeconds(61));
                h.makeDue();
                h.claimAndRun();
            }
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            h.jdbc.update("UPDATE spot_group SET state_changed_at = now() - interval '2 hours' WHERE id = ?", h.f.groupId);
            SpotGroupRepository groups = h.ctx.getBean(SpotGroupRepository.class);
            FallbackEnqueuer.Result r = inTx(h, () -> enqueuer(h).enqueueExit(groups.findById(h.f.groupId).orElseThrow(),
                    Instant.now().plus(Duration.ofHours(26))));
            // Today's transitions are the one enter, so the exit is allowed whatever the clock says.
            assertThat(r).isEqualTo(FallbackEnqueuer.Result.ENQUEUED);
            h.opId = h.jdbc.queryForObject("SELECT id FROM spot_operation WHERE group_id = ? AND kind = 'FALLBACK_EXIT'",
                    UUID.class, h.f.groupId);
            h.claimAndRun();
            assertThat(h.groupState()).isEqualTo("APPLYING");
            // Nothing moves and the Spot pool is out of capacity for 25 minutes.
            h.f.gcp.errors.add(GcpConversionFixture.JSON.createObjectNode().set("error",
                    GcpConversionFixture.JSON.createObjectNode().put("code", "ZONE_RESOURCE_POOL_EXHAUSTED")));
            for (int i = 0; i < 8 && !"COMPENSATING".equals(h.opPhase()); i++) {
                h.f.clock.advance(Duration.ofMinutes(7));
                h.makeDue();
                h.claimAndRun();
            }
            assertThat(h.opPhase()).isEqualTo("COMPENSATING");
            h.f.gcp.errors.clear();
            for (int i = 0; i < 30 && !"SUCCEEDED".equals(h.opStatus()); i++) {
                if (!h.f.gcp.mig().path("status").path("isStable").asBoolean()) {
                    standardOnly(h, 6);
                }
                h.f.clock.advance(Duration.ofSeconds(61));
                h.makeDue();
                h.claimAndRun();
            }
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).as("compensated back to FALLBACK").isEqualTo("FALLBACK");
            assertThat(h.groupColumn("config_mode")).isEqualTo("FALLBACK");
            assertThat(h.f.gcp.mig().path("versions")).hasSize(1);
            assertThat(h.f.gcp.mig().path("versions").path(0).path("instanceTemplate").asText())
                    .isEqualTo(GcpConversionFixture.ORIGINAL);
            assertThat(h.jdbc.queryForObject("SELECT status FROM spot_gcp_template WHERE self_link = ?", String.class, spot))
                    .as("the Spot copy is kept for the next exit attempt").isEqualTo("IN_USE");
            assertThat(h.f.mutating.deleted).isEmpty();
        }
    }
}
