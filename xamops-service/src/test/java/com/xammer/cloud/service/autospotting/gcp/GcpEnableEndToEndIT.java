package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.autospotting.pg.OpsContexts;
import com.xammer.cloud.autospotting.pg.PgHarness;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationStepRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.service.autospotting.core.SpotOpsLifecycle;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.ops.OperationFacts;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.OperationRunner;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.StepExecutor;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * plan -> confirm -> APPLYING -> ACTIVE on real PostgreSQL with the real runner, the real GCP provider and the real
 * registry and snapshot stores, over a stubbed Compute (plan-phase-27.md 27.3; AC-GCP-02, AC-GCP-03, AC-GCP-04,
 * AC-GCP-08, AC-GCP-09). The mutation switches are on in this context only. No GCP call is made, and the write
 * client has no method but template insert/delete and the MIG PATCH.
 */
@Tag("containers")
class GcpEnableEndToEndIT {
    /** One database, one runner, one stubbed project. */
    private static final class Harness implements AutoCloseable {
        final PgHarness.Database db = PgHarness.emptySchema();
        final AnnotationConfigApplicationContext ctx = OpsContexts.contextFor(db);
        final JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
        final GcpConversionFixture f = new GcpConversionFixture();
        final OperationStore store = ctx.getBean(OperationStore.class);
        final PlanBody plan;
        final LeaseHandle lease;
        OperationRunner runner;

        Harness() {
            insertGroup();
            plan = fresh().createPlan(new PlanRequest(new GroupRef(Provider.GCP, 3L, f.key, null), OperationKind.ENABLE,
                    new Profile("conservative", null)), f.gcp.view());
            insertPlan();
            store.insertQueued(OperationRecord.builder(f.opId, f.groupId, 3L, OperationKind.ENABLE).planId(f.planId)
                    .priorState(GroupState.UNMANAGED).compensationTarget(CompensationTargets.forKind(OperationKind.ENABLE))
                    .targetConfigJson(plan.targetJson()).requestedBy("tester").build());
            store.claimForRun(f.opId);
            restart();
            lease = ctx.getBean(LeaseService.class).acquireGroup(f.groupId, LeaseService.Component.DISPATCHER).orElseThrow();
        }

        /** A provider over the real stores: what a new instance builds after a restart. */
        GcpMigSpotProvider fresh() {
            OperationFacts facts = new OperationFacts(ctx.getBean(SpotPlanRepository.class),
                    ctx.getBean(SpotOperationRepository.class), ctx.getBean(SpotOperationStepRepository.class));
            GcpStepServices services = new GcpStepServices(f.accounts, a -> f.gcp.view(), a -> f.mutating, f.gate, f.evaluator,
                    new GcpMigStatusReader(), new GcpSnapshotStore(jdbc, ctx.getBean(FencedTx.class)),
                    new GcpTemplateRegistry(jdbc), new GcpVersionTargetEvaluator(), facts, f.clock, Duration.ofDays(7));
            GcpViewSource views = a -> f.gcp.view();
            return new GcpMigSpotProvider(f.accounts, views, f.evaluator, new GcpMigStatusReader(),
                    new GcpEstimatePriceService(views, f.shapes, java.time.Clock.systemUTC()), f.gate, false, false,
                    java.time.Clock.systemUTC(), new GcpStepLibrary(services));
        }

        /** A new runner over a new provider, as after a process restart: nothing carried over in memory. */
        void restart() {
            runner = new OperationRunner(ctx.getBean(SpotOperationRepository.class), ctx.getBean(SpotGroupRepository.class),
                    store, ctx.getBean(StepExecutor.class), ctx.getBean(GroupLifecycleService.class),
                    ctx.getBean(FencedTx.class), ctx.getBean(AutoSpottingAuditWriter.class), ctx.getBean(SwitchService.class),
                    ctx.getBean(SpotOpsLifecycle.class), new ProviderRegistry(List.of(fresh())),
                    ctx.getBean(com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics.class));
        }

        void insertGroup() {
            Instant now = f.clock.instant();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.groupId);
            row.put("provider", "GCP");
            row.put("cloud_account_id", 3L);
            row.put("client_id", 1L);
            row.put("group_key", f.key);
            row.put("state", "APPLYING");
            row.put("config_mode", "MANAGED");
            row.put("state_changed_at", Timestamp.from(now));
            row.put("created_by", "test");
            row.put("created_at", Timestamp.from(now));
            row.put("updated_at", Timestamp.from(now));
            PgHarness.insertRow(jdbc, "spot_group", row);
        }

        void insertPlan() {
            Instant now = f.clock.instant();
            String fingerprint = GcpFingerprint.planHash(GcpMigFacts.from(FakeGcp.PROJECT, f.gcp.mig()));
            String body = "{\"current\":" + plan.currentJson() + ",\"target\":" + plan.targetJson() + "}";
            jdbc.update("INSERT INTO spot_plan (id, group_id, cloud_account_id, kind, status, from_state, version,"
                            + " schema_version, live_fingerprint, body, requires_full_spot_optin, warning_ids, confirm_acks,"
                            + " dry_run, created_by, created_at, expires_at) VALUES (?, ?, 3, 'ENABLE', 'CONFIRMED',"
                            + " 'UNMANAGED', 1, 1, ?, ?::jsonb, false, '[]'::jsonb, '[]'::jsonb, false, 'test', ?, ?)",
                    f.planId, f.groupId, fingerprint, body, Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
        }

        void claimAndRun() {
            store.claimForRun(f.opId);
            runner.runClaimed(lease, f.opId);
        }

        void makeDue() {
            jdbc.update("UPDATE spot_operation SET next_attempt_at = now() - interval '1 second' WHERE id = ?", f.opId);
        }

        String opStatus() {
            return jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, f.opId);
        }

        String opPhase() {
            return jdbc.queryForObject("SELECT phase FROM spot_operation WHERE id = ?", String.class, f.opId);
        }

        String groupState() {
            return jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, f.groupId);
        }

        long count(String sql, Object... args) {
            Long n = jdbc.queryForObject(sql, Long.class, args);
            return n == null ? 0 : n;
        }

        String spotLink() {
            return jdbc.queryForObject("SELECT self_link FROM spot_gcp_template WHERE created_by_op = ?", String.class, f.opId);
        }

        @Override
        public void close() {
            ctx.close();
            db.close();
        }
    }

    @Test
    void enableWaitsForTheRolloutAndIsActiveOnlyWhenVersionTargetIsReachedAndStable() {
        try (Harness h = new Harness()) {
            assertThat(h.plan.eligibility().blockers()).isEmpty();
            h.claimAndRun();
            // The PATCH was accepted, nothing has moved: the op waits and the group is still APPLYING.
            assertThat(h.opStatus()).isEqualTo("WAITING");
            assertThat(h.groupState()).isEqualTo("APPLYING");
            assertThat(h.f.mutating.patches).hasSize(1);
            assertThat(h.count("SELECT count(*) FROM spot_operation_step WHERE operation_id = ? AND status = 'DONE'", h.f.opId))
                    .isEqualTo(6);
            assertThat(h.jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?", Integer.class,
                    h.f.opId)).isEqualTo(2);
            // A process restart: nothing in memory, the program is re-derived and resumes at the rollout wait.
            h.restart();
            h.f.completeRollout(3, h.spotLink());
            for (int i = 0; i < 30 && !"SUCCEEDED".equals(h.opStatus()); i++) {
                h.f.clock.advance(Duration.ofSeconds(61));
                h.makeDue();
                h.claimAndRun();
            }
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            List<Map<String, Object>> steps = h.jdbc.queryForList(
                    "SELECT name, status FROM spot_operation_step WHERE operation_id = ? ORDER BY seq", h.f.opId);
            assertThat(steps).extracting(s -> s.get("name")).containsExactlyElementsOf(GcpStepLibraryTest.FORWARD);
            assertThat(steps).extracting(s -> s.get("status")).containsOnly("DONE");
            // Exactly one template insert and one conversion PATCH: nothing doubled by the restart. With a floor of 3
            // PO-GCP-A keeps type PROACTIVE and maxUnavailable 0, which is what the customer's other fields already
            // equal here, so the restore step converges without a second PATCH.
            assertThat(h.f.mutating.inserted).hasSize(1);
            assertThat(h.f.mutating.calls).containsExactly("insert:global", "patch");
            assertThat(h.f.mutating.deleted).as("no template is ever deleted by a conversion").isEmpty();
            // The original was saved before the first change, and the registry owns exactly the Spot copy.
            assertThat(h.count("SELECT count(*) FROM spot_group_snapshot WHERE captured_by_op = ?", h.f.opId)).isEqualTo(1);
            assertThat(h.jdbc.queryForObject("SELECT payload->>'instanceTemplate' FROM spot_group_snapshot"
                    + " WHERE captured_by_op = ?", String.class, h.f.opId)).isEqualTo(GcpConversionFixture.ORIGINAL);
            assertThat(h.count("SELECT count(*) FROM spot_gcp_template WHERE group_id = ?", h.f.groupId)).isEqualTo(1);
            assertThat(h.jdbc.queryForObject("SELECT status FROM spot_gcp_template WHERE created_by_op = ?", String.class,
                    h.f.opId)).isEqualTo("IN_USE");
            assertThat(h.jdbc.queryForObject("SELECT gcp_id FROM spot_gcp_template WHERE created_by_op = ?", String.class,
                    h.f.opId)).isNotBlank();
            // desired_config is the confirmed target; the customer's policy rides in it.
            JsonNode desired = GcpConversionFixture.JSON.readTree(h.jdbc.queryForObject(
                    "SELECT desired_config::text FROM spot_group WHERE id = ?", String.class, h.f.groupId));
            assertThat(desired.path("versions").path(0).path("targetSize").path("fixed").asInt()).isEqualTo(3);
            assertThat(desired.path("customerUpdatePolicy").path("type").asText()).isEqualTo("OPPORTUNISTIC");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aCapacityStallCompensatesToTheSavedOriginalAndRetiresTheXamOpsTemplateWithoutDeletingAnything() {
        try (Harness h = new Harness()) {
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("WAITING");
            // Nothing moves and GCP reports a stockout for 25 minutes.
            h.f.gcp.errors.add(GcpConversionFixture.JSON.createObjectNode().set("error",
                    GcpConversionFixture.JSON.createObjectNode().put("code", "ZONE_RESOURCE_POOL_EXHAUSTED")));
            for (int i = 0; i < 6 && !"COMPENSATING".equals(h.opPhase()); i++) {
                h.f.clock.advance(Duration.ofMinutes(7));
                h.makeDue();
                h.claimAndRun();
            }
            assertThat(h.opPhase()).isEqualTo("COMPENSATING");
            assertThat(h.groupState()).isEqualTo("REVERTING");
            for (int i = 0; i < 30 && !"SUCCEEDED".equals(h.opStatus()); i++) {
                // The cloud follows the compensation PATCH: everything back on the original template.
                if (h.f.gcp.mig().path("status").path("isStable").asBoolean() == false) {
                    h.f.resetInstances(true);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) h.f.gcp.mig().path("status")).put("isStable", true);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) h.f.gcp.mig().path("status").path("versionTarget"))
                            .put("isReached", true);
                }
                h.f.clock.advance(Duration.ofSeconds(61));
                h.makeDue();
                h.claimAndRun();
            }
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.gcp.mig().path("versions").path(0).path("instanceTemplate").asText())
                    .isEqualTo(GcpConversionFixture.ORIGINAL);
            assertThat(h.f.gcp.mig().path("updatePolicy").path("type").asText()).isEqualTo("OPPORTUNISTIC");
            assertThat(h.jdbc.queryForObject("SELECT status FROM spot_gcp_template WHERE created_by_op = ?", String.class,
                    h.f.opId)).isEqualTo("RETIRED");
            assertThat(h.jdbc.queryForObject("SELECT delete_after IS NOT NULL FROM spot_gcp_template WHERE created_by_op = ?",
                    Boolean.class, h.f.opId)).isTrue();
            assertThat(h.f.mutating.deleted).as("retired, never deleted inline").isEmpty();
            assertThat(h.f.mutating.calls).doesNotContain("delete:" + GcpConversionFixture.ORIGINAL);
        }
    }

    @Test
    void aConflictOnThePatchIsNeverRecordedAsSuccess_theOpWaitsForTheForeignChange() {
        try (Harness h = new Harness()) {
            h.f.mutating.patchStatusToFail = 409;
            h.f.mutating.patchFailuresLeft = 1000;
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("WAITING");
            assertThat(h.jdbc.queryForObject("SELECT wait_reason FROM spot_operation WHERE id = ?", String.class, h.f.opId))
                    .isEqualTo("FOREIGN_CHANGE");
            assertThat(h.groupState()).isEqualTo("APPLYING");
            assertThat(h.count("SELECT count(*) FROM spot_operation_step WHERE operation_id = ? AND name = 'PATCH_MIG'"
                    + " AND status = 'DONE'", h.f.opId)).isZero();
            assertThat(h.f.gcp.mig().path("versions").path(0).path("instanceTemplate").asText())
                    .as("the MIG still runs the customer's template").isEqualTo(GcpConversionFixture.ORIGINAL);
        }
    }
}
