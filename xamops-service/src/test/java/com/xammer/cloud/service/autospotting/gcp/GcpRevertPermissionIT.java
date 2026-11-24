package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Decision;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Role;
import com.xammer.cloud.service.autospotting.core.FallbackProperties;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService;
import com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions;
import com.xammer.cloud.service.autospotting.core.reconcile.FallbackEnqueuer;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GR-03 on real PostgreSQL (plan-phase-28.md 28.1): a GCP revert with a missing restore permission is refused at
 * confirm (409 {@code REVERT_NEEDS_PERMISSION}, retryable) and a step 0 that finds the permission missing ends the op
 * FAILED before any mutation, so the group stays ACTIVE, fallback and billing resume, and a Spot shortage then enqueues
 * FALLBACK_ENTER. The real runner, lease, stores and GCP provider run over a stubbed Compute; no GCP call is made.
 */
@Tag("containers")
class GcpRevertPermissionIT {
    private static final String UPDATE = "compute.instanceGroupManagers.update";

    private static PlanBody revertPlan(GcpOpsHarness h) {
        return h.provider().createPlan(new PlanRequest(new GroupRef(Provider.GCP, 3L, h.f.key, null), OperationKind.REVERT,
                new Profile("conservative", null)), h.f.gcp.view());
    }

    private static String planBody(PlanBody b) {
        String code = b.eligibility().blockers().isEmpty() ? "" : b.eligibility().blockers().get(0).code();
        return "{\"current\":" + b.currentJson() + ",\"target\":" + b.targetJson() + ",\"revertPath\":" + b.revertPathJson()
                + ",\"eligibility\":{\"blockers\":" + (code.isEmpty() ? "[]" : "[{\"code\":\"" + code + "\",\"text\":\"x\"}]")
                + "}}";
    }

    private ConfirmService confirmService(GcpOpsHarness h) {
        AutoSpottingAuthorization authorization = mock(AutoSpottingAuthorization.class);
        when(authorization.authorizeConfirm(any(), any(), any(), any()))
                .thenReturn(new Decision(true, Role.CLIENT_OWNER, AutoSpottingAuditWriter.Actor.user(100L, "ROLE_USER"),
                        false, null, null, null));
        return new ConfirmService(h.ctx.getBean(SpotPlanRepository.class), h.ctx.getBean(SpotGroupRepository.class),
                h.ctx.getBean(SpotOperationRepository.class), h.store, h.ctx.getBean(PreemptionService.class), authorization,
                new RevertPreconditions(mock(AutoSpottingSwitchProperties.class)), h.ctx.getBean(SwitchService.class),
                mock(AutoSpottingSwitchProperties.class), new ProviderRegistry(List.of(h.provider())),
                h.ctx.getBean(AutoSpottingAuditWriter.class), mock(AutoSpottingNotificationService.class));
    }

    @Test
    void aRevokedUpdatePermissionMakesTheRevertPlanCarryTheCodeAndConfirmRefusesItWith409() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            h.enableToActive(3);
            h.f.gcp.grantedProject.remove(UPDATE);
            PlanBody plan = revertPlan(h);
            assertThat(plan.eligibility().blockers()).extracting(c -> c.code()).containsExactly("REVERT_NEEDS_PERMISSION");
            UUID planId = UUID.randomUUID();
            h.insertPlan(planId, "REVERT", "ACTIVE", GcpFingerprint.planHash(GcpMigFacts.from(FakeGcp.PROJECT, h.f.gcp.mig())),
                    planBody(plan), "[]", "[]");
            h.jdbc.update("UPDATE spot_plan SET status = 'OPEN' WHERE id = ?", planId);
            CloudAccount account = new CloudAccount();
            account.setId(3L);
            account.setProvider("GCP");
            Client client = new Client();
            client.setId(1L);
            account.setClient(client);
            ClientUserDetails user = new ClientUserDetails("owner", "x", List.of(new SimpleGrantedAuthority("ROLE_USER")),
                    1L, 100L, "t");
            ConfirmService confirm = confirmService(h);
            assertThatThrownBy(() -> confirm.confirm(account, user, planId,
                    new ConfirmService.ConfirmCommand(1, List.of(), false, null, false, "k", "h")))
                    .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo("REVERT_NEEDS_PERMISSION");
                        assertThat(ex.isRetryable()).isTrue();
                    });
            assertThat(h.count("SELECT count(*) FROM spot_operation WHERE kind = 'REVERT'")).isZero();
            assertThat(h.jdbc.queryForObject("SELECT status FROM spot_plan WHERE id = ?", String.class, planId))
                    .as("the plan stays open: re-plan after the grant").isEqualTo("OPEN");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            // After the grant a fresh plan carries no such blocker.
            h.f.gcp.grantedProject.add(UPDATE);
            assertThat(revertPlan(h).eligibility().blockers()).isEmpty();
        }
    }

    @Test
    void aStepZeroDiscoveryEndsTheOpFailedWithTheGroupStillActive_thenASpotShortageEnqueuesFallbackEnter() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            h.enableToActive(3);
            PlanBody plan = revertPlan(h);
            assertThat(plan.eligibility().blockers()).as("the permission was there when the plan was made").isEmpty();
            UUID revertOp = h.queueRevert("[]", "[]", planBody(plan));
            h.f.gcp.grantedProject.remove(UPDATE); // revoked between plan and run
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("FAILED");
            assertThat(h.jdbc.queryForObject("SELECT error_code FROM spot_operation WHERE id = ?", String.class, revertOp))
                    .isEqualTo("REVERT_NEEDS_PERMISSION");
            assertThat(h.jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?", Integer.class,
                    revertOp)).as("nothing mutated").isNull();
            assertThat(h.f.mutating.calls).isEmpty();
            assertThat(h.groupState()).as("the group stays in its prior state").isEqualTo("ACTIVE");
            assertThat(h.count("SELECT count(*) FROM spot_group_transition WHERE group_id = ? AND to_state = 'REVERTING'",
                    h.f.groupId)).isZero();
            assertThat(h.f.gcp.mig().path("versions")).as("the MIG is still the converted pair").hasSize(2);
            // Fallback and billing resume: a sustained Spot shortage now enqueues FALLBACK_ENTER.
            h.f.gcp.managedInstances.remove(5);
            for (int i = 0; i < 3; i++) {
                h.f.gcp.errors.add(GcpConversionFixture.JSON.createObjectNode().<com.fasterxml.jackson.databind.node.ObjectNode>set(
                        "error", GcpConversionFixture.JSON.createObjectNode().put("code", "ZONE_RESOURCE_POOL_EXHAUSTED"))
                        .put("timestamp", h.f.clock.instant().toString()).set("instanceActionDetails",
                                GcpConversionFixture.JSON.createObjectNode().put("instance", "web-" + i)));
            }
            GcpMigSpotProvider provider = h.provider();
            SpotGroupRepository groups = h.ctx.getBean(SpotGroupRepository.class);
            boolean enqueue = false;
            for (int sweep = 0; sweep < 4; sweep++) {
                SpotGroup row = groups.findById(h.f.groupId).orElseThrow();
                GroupStatus status = provider.getStatus(row, h.f.gcp.view());
                enqueue = provider.reconcile(row, status).contains(ReconcileAction.ENQUEUE_FALLBACK);
                h.jdbc.update("UPDATE spot_group SET health_state = ?::jsonb WHERE id = ?", status.healthJson(), h.f.groupId);
                if (!enqueue) {
                    h.f.clock.advance(Duration.ofMinutes(5));
                }
            }
            assertThat(enqueue).isTrue();
            FallbackEnqueuer enqueuer = new FallbackEnqueuer(h.ctx.getBean(SpotOperationRepository.class),
                    h.ctx.getBean(PreemptionService.class), h.store, FallbackProperties.defaults(),
                    h.ctx.getBean(AutoSpottingAuditWriter.class), h.ctx.getBean(AutoSpottingMetrics.class),
                    h.ctx.getBean(SwitchService.class));
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    h.ctx.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            FallbackEnqueuer.Result result = tx.execute(s ->
                    enqueuer.enqueueEnter(groups.findById(h.f.groupId).orElseThrow(), "RECONCILE"));
            assertThat(result).isEqualTo(FallbackEnqueuer.Result.ENQUEUED);
            assertThat(h.count("SELECT count(*) FROM spot_operation WHERE group_id = ? AND kind = 'FALLBACK_ENTER'"
                    + " AND status = 'QUEUED'", h.f.groupId)).isEqualTo(1);
        }
    }

    @Test
    void anExactRevertRunsToRevertedOnRealPostgresWithTheOriginalBackAndNothingDeleted() {
        try (GcpOpsHarness h = new GcpOpsHarness()) {
            h.enableToActive(3);
            String spot = h.jdbc.queryForObject("SELECT self_link FROM spot_gcp_template WHERE created_by_op = ?",
                    String.class, h.f.opId);
            PlanBody plan = revertPlan(h);
            UUID revertOp = h.queueRevert("[]", "[]", planBody(plan));
            h.claimAndRun();
            assertThat(h.groupState()).as("REVERTING starts with the PATCH's intent").isEqualTo("REVERTING");
            assertThat(h.jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?", Integer.class,
                    revertOp)).isEqualTo(3);
            h.restart(); // a process restart mid-revert: the program is re-derived and resumes
            h.f.gcp.managedInstances.clear();
            for (int i = 0; i < 6; i++) {
                h.f.gcp.managedInstances.add(GcpConversionFixture.instance(i, null, GcpConversionFixture.ORIGINAL, "NONE"));
            }
            var status = (com.fasterxml.jackson.databind.node.ObjectNode) h.f.gcp.mig().path("status");
            status.put("isStable", true);
            ((com.fasterxml.jackson.databind.node.ObjectNode) status.path("versionTarget")).put("isReached", true);
            for (int i = 0; i < 40 && !"SUCCEEDED".equals(h.opStatus()); i++) {
                h.f.clock.advance(Duration.ofSeconds(61));
                h.makeDue();
                h.claimAndRun();
            }
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.gcp.mig().path("versions")).hasSize(1);
            assertThat(h.f.gcp.mig().path("versions").path(0).path("instanceTemplate").asText())
                    .isEqualTo(GcpConversionFixture.ORIGINAL);
            assertThat(h.f.mutating.deleted).as("the Spot copy is retired, never deleted inline").isEmpty();
            assertThat(h.jdbc.queryForObject("SELECT status FROM spot_gcp_template WHERE self_link = ?", String.class, spot))
                    .isEqualTo("RETIRED");
            assertThat(h.f.mutating.inserted).as("an exact revert creates no template").isEmpty();
            List<String> done = h.jdbc.queryForList("SELECT name FROM spot_operation_step WHERE operation_id = ? ORDER BY seq",
                    String.class, revertOp);
            assertThat(done).isEqualTo(GcpRestoreProgramTest.REVERT);
        }
    }
}
