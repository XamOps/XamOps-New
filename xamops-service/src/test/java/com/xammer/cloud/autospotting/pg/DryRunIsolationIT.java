package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.autospotting.fake.ProgrammableSpotProvider;
import com.xammer.cloud.autospotting.fake.ProgrammableSpotProvider.ScriptedStep;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.core.SpotOpsLifecycle;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.OperationRunner;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.StepExecutor;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
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
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.opStatus;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dry-run ENABLE runs while a real op is queued for the same group, and touches nothing (design-core.md
 * §7.2, FR-PLN-05, AC-PLN-06; plan-phase-11.md task 11.3 verify): no {@code spot_group.state},
 * transition or {@code desired_*} write, zero mutating fake calls, and the queued real op is
 * untouched (the dry-run op is excluded from {@code ux_spot_op_running}).
 */
@Tag("containers")
class DryRunIsolationIT {

    @Test
    void dryRunEnableTouchesNothingWhileARealOpIsQueued() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);

            // A PLANNED group with a confirmed real ENABLE queued, and a dry-run ENABLE for the same group.
            UUID group = insertGroup(jdbc, GroupState.PLANNED);
            UUID realOp = insertOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED, false);
            UUID dryOp = insertOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED, true);

            String groupBefore = jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, group);
            String desiredBefore = jdbc.queryForObject("SELECT desired_config FROM spot_group WHERE id = ?",
                    String.class, group);

            // A provider whose mutating step counts its real executes; describeIntent never does.
            AtomicInteger mutatingExecutes = new AtomicInteger(0);
            SpotProvider provider = new ProgrammableSpotProvider(Provider.AWS,
                    () -> new StepProgram(List.of(
                            ScriptedStep.done("VERIFY_PRECONDITIONS", false),
                            new CountingMutatingStep("APPLY_TARGET_CONFIG", mutatingExecutes))),
                    () -> new StepProgram(List.of()));

            // The dry-run op is claimed and driven by the runner.
            store.claimForRun(dryOp);
            LeaseHandle handle = ctx.getBean(LeaseService.class)
                    .acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();
            runnerWith(ctx, provider).runClaimed(handle, dryOp);

            // The dry-run op succeeded.
            assertThat(opStatus(jdbc, dryOp)).isEqualTo("SUCCEEDED");
            // Zero mutating fake calls: describeIntent ran, execute did not (AC-PLN-06).
            assertThat(mutatingExecutes.get()).isZero();
            // No state change, no transition, no desired_* write.
            assertThat(groupState(jdbc, group)).isEqualTo(groupBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group_transition WHERE group_id = ?",
                    Long.class, group)).isZero();
            assertThat(jdbc.queryForObject("SELECT desired_config FROM spot_group WHERE id = ?", String.class,
                    group)).isEqualTo(desiredBefore);
            // The dry-run op is not visible to ux_spot_op_running: the real op is still QUEUED.
            assertThat(opStatus(jdbc, realOp)).isEqualTo("QUEUED");
            // WOULD_DO audit rows were written for the dry-run (one per step).
            assertThat(jdbc.queryForObject("SELECT count(*) FROM autospotting_audit WHERE operation_id = ?"
                    + " AND outcome = 'WOULD_DO'", Long.class, dryOp)).isGreaterThan(0L);
        }
    }

    private static UUID insertOp(OperationStore store, UUID groupId, OperationKind kind, GroupState priorState,
                                 boolean dryRun) {
        UUID opId = UUID.randomUUID();
        store.insertQueued(OperationRecord.builder(opId, groupId, 1L, kind)
                .priorState(priorState)
                .compensationTarget(CompensationTargets.forKind(kind))
                .targetConfigJson("{\"profile\":\"conservative\"}")
                .requestedBy("tester")
                .dryRun(dryRun)
                .build());
        return opId;
    }

    private static OperationRunner runnerWith(AnnotationConfigApplicationContext ctx, SpotProvider provider) {
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
                new ProviderRegistry(List.of(provider)),
                ctx.getBean(com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics.class));
    }

    /** A mutating step that counts its real executes; its describeIntent never increments the counter. */
    private static final class CountingMutatingStep
            implements com.xammer.cloud.service.autospotting.spi.Step {
        private final String name;
        private final AtomicInteger executes;

        CountingMutatingStep(String name, AtomicInteger executes) {
            this.name = name;
            this.executes = executes;
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
            executes.incrementAndGet();
            ctx.leaseHandle().assertHeld(ctx.callTimeout());
            return new StepOutcome.Done("{\"ok\":true}", List.of());
        }

        @Override
        public StepOutcome probe(StepContext ctx) {
            return new StepOutcome.Done("{\"ok\":true}", List.of());
        }

        @Override
        public String describeIntent(StepContext ctx) {
            return "would apply " + name;
        }
    }
}
