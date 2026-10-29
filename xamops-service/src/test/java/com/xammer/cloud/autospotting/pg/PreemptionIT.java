package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService.Preemption;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService.Verdict;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static com.xammer.cloud.autospotting.pg.OpsTestSupport.contextFor;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertGroup;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertQueuedOp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PreemptionService} on real PostgreSQL (plan-phase-07.md task 7.3 verify, design-core.md
 * §4.7). Exercises the preemption table under the group row lock and the runner's CANCELLED outcomes.
 * {@code @Tag("containers")}.
 */
@Tag("containers")
class PreemptionIT {

    /** Make a WAITING op look mutated (so cancel after mutation ends CANCELLED, not prior_state). */
    private static void markMutated(JdbcTemplate jdbc, UUID opId, int seq) {
        jdbc.update("UPDATE spot_operation SET first_mutation_seq = ? WHERE id = ?", seq, opId);
    }

    private static void park(JdbcTemplate jdbc, UUID opId) {
        jdbc.update("UPDATE spot_operation SET status = 'WAITING', wait_reason = 'CLOUD_PROGRESS' WHERE id = ?", opId);
    }

    // ── a user REVERT preempts a WAITING ENABLE (PREEMPTED_BY_REVERT) ───────────

    @Test
    void revertPreemptsAWaitingEnable() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            PreemptionService preemption = ctx.getBean(PreemptionService.class);

            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID enable = insertQueuedOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED);
            park(jdbc, enable);
            markMutated(jdbc, enable, 2);

            UUID revertOpId = UUID.randomUUID();
            Preemption result = preemption.preemptFor(group, OperationKind.REVERT, revertOpId);

            assertThat(result.verdict()).isEqualTo(Verdict.PREEMPTED);
            assertThat(result.preemptedOpId()).isEqualTo(enable);
            assertThat(jdbc.queryForObject("SELECT cancel_requested FROM spot_operation WHERE id = ?",
                    Boolean.class, enable)).isTrue();
            assertThat(jdbc.queryForObject("SELECT cancel_reason FROM spot_operation WHERE id = ?",
                    String.class, enable)).isEqualTo(PreemptionService.PREEMPTED_BY_REVERT);
            assertThat(jdbc.queryForObject("SELECT preempted_by_op FROM spot_operation WHERE id = ?",
                    UUID.class, enable)).isEqualTo(revertOpId);
        }
    }

    // ── REVERT vs REVERT is busy ────────────────────────────────────────────────

    @Test
    void revertVsRevertIsBusy() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            PreemptionService preemption = ctx.getBean(PreemptionService.class);

            UUID group = insertGroup(jdbc, GroupState.REVERTING);
            UUID revert = insertQueuedOp(store, group, OperationKind.REVERT, GroupState.ACTIVE);
            park(jdbc, revert);

            Preemption result = preemption.preemptFor(group, OperationKind.REVERT, UUID.randomUUID());
            assertThat(result.verdict()).isEqualTo(Verdict.BUSY);
        }
    }

    // ── a new PROGRESS op is busy whenever any non-terminal op exists ───────────

    @Test
    void progressOpIsBusyWhenAnyNonTerminalOpExists() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            PreemptionService preemption = ctx.getBean(PreemptionService.class);

            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID enable = insertQueuedOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED);
            park(jdbc, enable);

            assertThat(preemption.preemptFor(group, OperationKind.CONFIG_APPLY, UUID.randomUUID()).verdict())
                    .isEqualTo(Verdict.BUSY);
        }
    }

    // ── FALLBACK_ENTER preempts a CONFIG_APPLY after mutation, with config deferral ─

    @Test
    void fallbackEnterPreemptsAMutatedConfigApplyWithDeferral() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            PreemptionService preemption = ctx.getBean(PreemptionService.class);

            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID configApply = insertQueuedOp(store, group, OperationKind.CONFIG_APPLY, GroupState.ACTIVE);
            park(jdbc, configApply);
            markMutated(jdbc, configApply, 1);

            Preemption result = preemption.preemptFor(group, OperationKind.FALLBACK_ENTER, UUID.randomUUID());
            assertThat(result.verdict()).isEqualTo(Verdict.PREEMPTED);
            assertThat(result.configDeferred()).isTrue();
            assertThat(jdbc.queryForObject("SELECT cancel_reason FROM spot_operation WHERE id = ?",
                    String.class, configApply)).isEqualTo(PreemptionService.PREEMPTED_BY_FALLBACK);
        }
    }

    // ── FALLBACK_ENTER does NOT preempt a CONFIG_APPLY before its first mutation ─

    @Test
    void fallbackEnterDoesNotPreemptAnUnmutatedConfigApply() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            PreemptionService preemption = ctx.getBean(PreemptionService.class);

            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID configApply = insertQueuedOp(store, group, OperationKind.CONFIG_APPLY, GroupState.ACTIVE);
            park(jdbc, configApply); // first_mutation_seq stays null (no mutation yet)

            Preemption result = preemption.preemptFor(group, OperationKind.FALLBACK_ENTER, UUID.randomUUID());
            assertThat(result.verdict()).isEqualTo(Verdict.BUSY);
            assertThat(jdbc.queryForObject("SELECT cancel_requested FROM spot_operation WHERE id = ?",
                    Boolean.class, configApply)).isFalse();
        }
    }

    // ── a preemption before the first mutation ends CANCELLED back to prior_state ─

    @Test
    void cancelBeforeFirstMutationReturnsTheGroupToPriorState() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            PreemptionService preemption = ctx.getBean(PreemptionService.class);
            var runner = ctx.getBean(com.xammer.cloud.service.autospotting.core.ops.OperationRunner.class);
            LeaseService leases = ctx.getBean(LeaseService.class);

            // An ENABLE op in APPLYING with no mutation yet, running; a REVERT preempts it.
            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID enable = insertQueuedOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED);
            store.claimForRun(enable); // RUNNING, first_mutation_seq null

            Preemption result = preemption.preemptFor(group, OperationKind.REVERT, UUID.randomUUID());
            assertThat(result.verdict()).isEqualTo(Verdict.PREEMPTED);

            // Drive the ENABLE runner: it sees cancel_requested at the boundary and cancels. No
            // mutation → group returns to prior_state (UNMANAGED), op CANCELLED (§4.7).
            LeaseHandle handle = leases.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();
            try {
                runner.runClaimed(handle, enable);
            } finally {
                leases.releaseGroup(handle);
            }

            assertThat(OpsTestSupport.opStatus(jdbc, enable)).isEqualTo("CANCELLED");
            assertThat(OpsTestSupport.groupState(jdbc, group)).isEqualTo("UNMANAGED");
            assertThat(OpsTestSupport.opCancelReason(jdbc, enable)).isEqualTo(PreemptionService.PREEMPTED_BY_REVERT);
        }
    }

    // ── reconciler-requested compensation sets compensate_requested under the lock ─

    @Test
    void requestCompensationSetsTheFlagForAMutatedOp() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            PreemptionService preemption = ctx.getBean(PreemptionService.class);

            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID enable = insertQueuedOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED);
            park(jdbc, enable);
            markMutated(jdbc, enable, 2);

            boolean requested = preemption.requestCompensation(group, enable, "SPOT_SHORTFALL_DURING_ROLLOUT");
            assertThat(requested).isTrue();
            assertThat(jdbc.queryForObject("SELECT compensate_requested FROM spot_operation WHERE id = ?",
                    Boolean.class, enable)).isTrue();
            assertThat(jdbc.queryForObject("SELECT compensate_reason FROM spot_operation WHERE id = ?",
                    String.class, enable)).isEqualTo("SPOT_SHORTFALL_DURING_ROLLOUT");

            // An un-mutated op must not get compensation (§4.7: "past its first mutation").
            UUID group2 = insertGroup(jdbc, GroupState.APPLYING);
            UUID enable2 = insertQueuedOp(store, group2, OperationKind.ENABLE, GroupState.UNMANAGED);
            park(jdbc, enable2);
            assertThat(preemption.requestCompensation(group2, enable2, "X")).isFalse();
        }
    }
}
