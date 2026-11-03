package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.autospotting.fake.ProgrammableSpotProvider;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.core.SpotOpsLifecycle;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.ops.OperationRunner;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.StepExecutor;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.xammer.cloud.autospotting.pg.OpsTestSupport.contextFor;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.groupState;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertGroup;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertQueuedOp;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.opStatus;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash-resume and the DR-04 startup rollback on real PostgreSQL (plan-phase-07.md task 7.2 verify,
 * design-core.md §4.5, §4.6). {@code @Tag("containers")}.
 */
@Tag("containers")
class CrashResumeIT {

    private static OperationRunner runnerWith(AnnotationConfigApplicationContext ctx,
                                              com.xammer.cloud.service.autospotting.spi.SpotProvider provider) {
        return new OperationRunner(
                ctx.getBean(SpotOperationRepository.class),
                ctx.getBean(SpotGroupRepository.class),
                ctx.getBean(OperationStore.class),
                ctx.getBean(StepExecutor.class),
                ctx.getBean(GroupLifecycleService.class),
                ctx.getBean(FencedTx.class),
                ctx.getBean(AutoSpottingAuditWriter.class),
                ctx.getBean(SwitchService.class),
                ctx.getBean(SpotOpsLifecycle.class),
                new ProviderRegistry(List.of(provider)));
    }

    /**
     * A step whose side effect is tracked by a shared flag: {@code probe} reports Done once the side
     * effect happened, so a resumer adopts it with no new mutating call (AC-SPN-06, AC-SPN-09).
     */
    static final class IdempotentMutatingStep implements Step {
        private final String name;
        private final AtomicInteger executeCount;
        private final boolean[] sideEffectDone;

        IdempotentMutatingStep(String name, AtomicInteger executeCount, boolean[] sideEffectDone) {
            this.name = name;
            this.executeCount = executeCount;
            this.sideEffectDone = sideEffectDone;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public StepOutcome execute(StepContext ctx) {
            ctx.leaseHandle().assertHeld(ctx.callTimeout());
            executeCount.incrementAndGet();
            sideEffectDone[0] = true;
            return new StepOutcome.Done("{\"applied\":true}", List.of("req-apply"));
        }

        @Override
        public StepOutcome probe(StepContext ctx) {
            return sideEffectDone[0]
                    ? new StepOutcome.Done("{\"probed\":true}", List.of("req-apply"))
                    : new StepOutcome.InProgress(java.time.Duration.ofMillis(10), null);
        }

        @Override
        public String describeIntent(StepContext ctx) {
            return "idempotent mutate " + name;
        }
    }

    // ── a step at INTENT_RECORDED with an expired lease is probed and resumed ───

    @Test
    void anIntentRecordedStepIsProbedAndResumedWithoutADuplicateCall() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            LeaseService leases = ctx.getBean(LeaseService.class);

            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID op = insertQueuedOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED);
            store.claimForRun(op);

            AtomicInteger executes = new AtomicInteger();
            boolean[] sideEffectDone = {false};
            IdempotentMutatingStep applyStep = new IdempotentMutatingStep("APPLY_TARGET_CONFIG", executes, sideEffectDone);

            // Simulate a crash mid-step: the mutating step's intent is recorded and its side effect
            // happened in the cloud, but the result was never written (the row stays INTENT_RECORDED).
            LeaseHandle first = leases.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();
            store.recordIntent(first, op, 0, applyStep.name(), StepContext.idempotencyToken(op, 0).toString(),
                    "{\"in\":1}", true);
            sideEffectDone[0] = true; // the cloud call succeeded, then the instance died
            // Expire the lease (crash), so another instance can acquire it.
            jdbc.update("UPDATE spot_group SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                    group);

            // Resume: the runner re-derives the program and, finding seq 0 INTENT_RECORDED, probes it.
            var provider = new ProgrammableSpotProvider(Provider.AWS,
                    () -> new StepProgram(List.of(applyStep)),
                    () -> new StepProgram(List.of()));
            LeaseHandle resumed = leases.acquireGroup(group, LeaseService.Component.RECONCILER).orElseThrow();
            runnerWith(ctx, provider).runClaimed(resumed, op);

            // The probe adopted the side effect: execute() was never called on resume (no duplicate).
            assertThat(executes.get()).isEqualTo(0);
            assertThat(jdbc.queryForObject("SELECT status FROM spot_operation_step WHERE operation_id = ? AND seq = 0",
                    String.class, op)).isEqualTo("DONE");
            assertThat(opStatus(jdbc, op)).isEqualTo("SUCCEEDED");
            assertThat(groupState(jdbc, group)).isEqualTo("ACTIVE");
        }
    }

    // ── DR-04: a REVERTING group whose last REVERT never mutated returns to prior_state ─

    @Test
    void aRevertingGroupWithAnUnmutatedRevertReturnsToPriorStateWithRevertNotStarted() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            var startupRecovery = ctx.getBean(com.xammer.cloud.service.autospotting.core.ops.StartupRecovery.class);

            // A REVERTING group whose only REVERT op finished CANCELLED/FAILED before any mutation
            // (first_mutation_seq null), with no live op. DR-04: back to prior_state (ACTIVE).
            UUID group = insertGroup(jdbc, GroupState.REVERTING);
            UUID revert = insertQueuedOp(store, group, OperationKind.REVERT, GroupState.ACTIVE);
            // Finish it terminal with no mutation, leaving the group REVERTING (the crash gap, §4.6).
            store.finishFailed(revert, "CRASH", "instance died before the revert mutated");

            SpotGroup reloaded = ctx.getBean(SpotGroupRepository.class).findById(group).orElseThrow();
            startupRecovery.recoverGroup(reloaded);

            assertThat(groupState(jdbc, group)).isEqualTo("ACTIVE");
            // The rollback wrote a GROUP_ROLLBACK audit record carrying REVERT_NOT_STARTED (§4.6).
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM autospotting_audit WHERE group_id = ? AND event_type = 'GROUP_ROLLBACK'"
                            + " AND details::text LIKE '%REVERT_NOT_STARTED%'",
                    Long.class, group)).isEqualTo(1L);
            // A spot_group_transition records REVERTING -> ACTIVE with reason REVERT_NOT_STARTED.
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM spot_group_transition WHERE group_id = ? AND reason_code = 'REVERT_NOT_STARTED'",
                    Long.class, group)).isEqualTo(1L);
        }
    }

    // ── a past-deadline live op is marked STUCK, never FAILED (§4.6, GCP-20) ────

    @Test
    void aPastDeadlineLiveOpIsMarkedStuckNotFailed() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            var startupRecovery = ctx.getBean(com.xammer.cloud.service.autospotting.core.ops.StartupRecovery.class);

            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID op = insertQueuedOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED);
            // Make it a live WAITING op past its deadline.
            jdbc.update("UPDATE spot_operation SET status = 'WAITING', wait_reason = 'CLOUD_PROGRESS',"
                    + " deadline_at = clock_timestamp() - INTERVAL '1 hour' WHERE id = ?", op);

            SpotGroup reloaded = ctx.getBean(SpotGroupRepository.class).findById(group).orElseThrow();
            startupRecovery.recoverGroup(reloaded);

            assertThat(jdbc.queryForObject("SELECT wait_reason FROM spot_operation WHERE id = ?", String.class, op))
                    .isEqualTo("STUCK");
            assertThat(opStatus(jdbc, op)).isEqualTo("WAITING"); // not FAILED (GCP-20)
            assertThat(groupState(jdbc, group)).isEqualTo("APPLYING"); // state kept
        }
    }
}
