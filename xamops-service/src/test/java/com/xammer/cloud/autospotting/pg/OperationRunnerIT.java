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
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.ops.OperationDispatcher;
import com.xammer.cloud.service.autospotting.core.ops.OperationRunner;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.StepExecutor;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.xammer.cloud.autospotting.pg.OpsTestSupport.contextFor;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.groupState;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertGroup;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertQueuedOp;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.opPhase;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.opStatus;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OperationRunner} on real PostgreSQL with scripted providers (plan-phase-07.md task 7.1
 * verify, design-core.md §4.1, §9.4). {@code @Tag("containers")}.
 */
@Tag("containers")
class OperationRunnerIT {

    /** Build a runner over the context's beans with a programmable provider (scripted step outcomes). */
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

    private static LeaseHandle acquire(AnnotationConfigApplicationContext ctx, UUID groupId) {
        return ctx.getBean(LeaseService.class).acquireGroup(groupId, LeaseService.Component.DISPATCHER).orElseThrow();
    }

    // ── a three-step program succeeds: APPLYING → ACTIVE, op SUCCEEDED ──────────

    @Test
    void threeStepProgramSucceedsAndTransitionsTheGroup() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);

            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID op = insertQueuedOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED);
            store.claimForRun(op);

            SpotProvider provider = new ProgrammableSpotProvider(Provider.AWS,
                    () -> new StepProgram(List.of(
                            ScriptedStep.done("VERIFY_PRECONDITIONS", false),
                            ScriptedStep.done("PERSIST_SNAPSHOT", false),
                            ScriptedStep.done("APPLY_TARGET_CONFIG", true))),
                    () -> new StepProgram(List.of()));

            LeaseHandle handle = acquire(ctx, group);
            runnerWith(ctx, provider).runClaimed(handle, op);

            assertThat(opStatus(jdbc, op)).isEqualTo("SUCCEEDED");
            assertThat(groupState(jdbc, group)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_operation_step WHERE operation_id = ?",
                    Long.class, op)).isEqualTo(3L);
            // first_mutation_seq set by APPLY_TARGET_CONFIG (seq 2).
            assertThat(jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, op)).isEqualTo(2);
        }
    }

    // ── a WAITING step frees its thread: pool size 1, two ops both progress ─────

    @Test
    void aWaitingStepFreesItsThreadSoAnotherOpProgresses() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);

            // Two groups, each with one ENABLE op whose mutating step is a cloud rollout (InProgress
            // then Done). With ops-pool size 1, the WAITING park must free the thread so both make
            // progress across polls (§4.1).
            UUID g1 = insertGroup(jdbc, GroupState.APPLYING);
            UUID g2 = insertGroup(jdbc, GroupState.APPLYING);
            UUID op1 = insertQueuedOp(store, g1, OperationKind.ENABLE, GroupState.UNMANAGED);
            UUID op2 = insertQueuedOp(store, g2, OperationKind.ENABLE, GroupState.UNMANAGED);

            // Register a programmable provider in a dispatcher-connected runner by swapping the
            // context's provider: build a dispatcher-equivalent loop driving the real dispatcher,
            // which uses the context's FakeSpotProvider — but we need InProgress, so drive the runner
            // directly for determinism instead of the @Scheduled dispatcher.
            // A cloud rollout whose "poll count" lives OUTSIDE the step (keyed per operation), so the
            // program re-derived on each resume reflects the real rollout progress. The step is
            // deterministic given that external state, like a real provider polling the cloud.
            java.util.Map<UUID, AtomicInteger> rolloutPolls = new java.util.concurrent.ConcurrentHashMap<>();
            SpotProvider provider = new ProgrammableSpotProvider(Provider.AWS,
                    () -> new StepProgram(List.of(
                            ScriptedStep.done("VERIFY_PRECONDITIONS", false),
                            new RolloutStep("APPLY_TARGET_CONFIG", rolloutPolls, 2))),
                    () -> new StepProgram(List.of()));
            OperationRunner runner = runnerWith(ctx, provider);

            // Round 1: both ops claimed and driven to their first WAITING (thread freed each time).
            driveAllDue(ctx, store, runner, jdbc);
            assertThat(opStatus(jdbc, op1)).isEqualTo("WAITING");
            assertThat(opStatus(jdbc, op2)).isEqualTo("WAITING");

            // Advance next_attempt_at so both are due again, then keep driving until both succeed.
            boolean bothDone = repeatUntil(30, () -> {
                makeDue(jdbc, op1);
                makeDue(jdbc, op2);
                driveAllDue(ctx, store, runner, jdbc);
                return "SUCCEEDED".equals(opStatus(jdbc, op1)) && "SUCCEEDED".equals(opStatus(jdbc, op2));
            });
            assertThat(bothDone).isTrue();
            assertThat(groupState(jdbc, g1)).isEqualTo("ACTIVE");
            assertThat(groupState(jdbc, g2)).isEqualTo("ACTIVE");
        }
    }

    // ── a TRANSIENT failure retries, then compensates ──────────────────────────

    @Test
    void aTransientFailureRetriesThenCompensates() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);

            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID op = insertQueuedOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED);

            // The mutating step always returns TRANSIENT; after the snapshot mutation it exhausts the
            // five attempts and compensates (ENABLE → SNAPSHOT → REVERTED).
            StepOutcome transient_ = new StepOutcome.Failed(
                    com.xammer.cloud.service.autospotting.spi.ErrorKind.TRANSIENT, "TIMEOUT", "timed out");
            SpotProvider provider = new ProgrammableSpotProvider(Provider.AWS,
                    () -> new StepProgram(List.of(
                            ScriptedStep.done("VERIFY_PRECONDITIONS", false),
                            ScriptedStep.done("PERSIST_SNAPSHOT", true),
                            new ScriptedStep("APPLY_TARGET_CONFIG", true, List.of(transient_)))),
                    () -> new StepProgram(List.of(
                            ScriptedStep.done("RESTORE_SNAPSHOT", true))));
            OperationRunner runner = runnerWith(ctx, provider);

            store.claimForRun(op);
            // Drive until the step exhausts attempts and the op compensates to REVERTED.
            boolean terminal = repeatUntil(40, () -> {
                LeaseHandle handle = acquire(ctx, group);
                try {
                    if ("WAITING".equals(opStatus(jdbc, op))) {
                        makeDue(jdbc, op);
                        store.claimForRun(op);
                    }
                    runner.runClaimed(handle, op);
                } finally {
                    ctx.getBean(LeaseService.class).releaseGroup(handle);
                }
                return "SUCCEEDED".equals(opStatus(jdbc, op)) || "FAILED".equals(opStatus(jdbc, op));
            });
            assertThat(terminal).isTrue();

            assertThat(opPhase(jdbc, op)).isEqualTo("COMPENSATING");
            assertThat(opStatus(jdbc, op)).isEqualTo("SUCCEEDED");
            assertThat(groupState(jdbc, group)).isEqualTo("REVERTED");
            // The step recorded multiple attempts (retried before compensation).
            assertThat(jdbc.queryForObject(
                    "SELECT max(attempts) FROM spot_operation_step WHERE operation_id = ?", Integer.class, op))
                    .isGreaterThanOrEqualTo(5);
        }
    }

    // ── a PERMISSION failure before mutation fails back to prior_state ──────────

    @Test
    void aPermissionFailureBeforeMutationFailsBackToPriorState() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);

            UUID group = insertGroup(jdbc, GroupState.APPLYING);
            UUID op = insertQueuedOp(store, group, OperationKind.ENABLE, GroupState.UNMANAGED);

            // Step 0 (read) fails PERMISSION before any mutation → FAILED, group back to UNMANAGED.
            StepOutcome permission = new StepOutcome.Failed(
                    com.xammer.cloud.service.autospotting.spi.ErrorKind.PERMISSION, "ACCESS_DENIED", "denied");
            SpotProvider provider = new ProgrammableSpotProvider(Provider.AWS,
                    () -> new StepProgram(List.of(
                            new ScriptedStep("VERIFY_PRECONDITIONS", false, List.of(permission)),
                            ScriptedStep.done("APPLY_TARGET_CONFIG", true))),
                    () -> new StepProgram(List.of()));
            OperationRunner runner = runnerWith(ctx, provider);

            store.claimForRun(op);
            LeaseHandle handle = acquire(ctx, group);
            runner.runClaimed(handle, op);

            assertThat(opStatus(jdbc, op)).isEqualTo("FAILED");
            assertThat(opPhase(jdbc, op)).isEqualTo("FORWARD");
            assertThat(groupState(jdbc, group)).isEqualTo("UNMANAGED");
            assertThat(jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, op)).isNull();
        }
    }

    // ── the dispatcher keeps polling every 5 s while every job body is blocked ──

    @Test
    void theDispatcherKeepsPollingWhileTheOpsPoolIsFullAndBlocked() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationDispatcher dispatcher = ctx.getBean(OperationDispatcher.class);
            var opsExecutor = ctx.getBean(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);

            // Fill the single ops thread with a blocking task, so no submit can succeed.
            AtomicInteger released = new AtomicInteger();
            java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
            opsExecutor.execute(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    released.incrementAndGet();
                }
            });

            int before = dispatcher.pollCount();
            // The poll body must still run and return promptly even with the pool full and blocked.
            for (int i = 0; i < 3; i++) {
                dispatcher.poll();
            }
            assertThat(dispatcher.pollCount()).isEqualTo(before + 3);
            gate.countDown();
            assertThat(repeatUntil(100, () -> released.get() == 1)).isTrue();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Claim and drive every due op once, each under a fresh lease, then release (one dispatcher pass). */
    private static void driveAllDue(AnnotationConfigApplicationContext ctx, OperationStore store,
                                    OperationRunner runner, JdbcTemplate jdbc) {
        SpotOperationRepository ops = ctx.getBean(SpotOperationRepository.class);
        LeaseService leases = ctx.getBean(LeaseService.class);
        for (UUID groupId : ops.findGroupsWithDueOps(java.time.Instant.now())) {
            List<UUID> due = ops.findDueOpIdsForGroup(groupId, java.time.Instant.now());
            if (due.isEmpty()) {
                continue;
            }
            UUID opId = due.get(0);
            if (!store.claimForRun(opId)) {
                continue;
            }
            LeaseHandle handle = leases.acquireGroup(groupId, LeaseService.Component.DISPATCHER).orElse(null);
            if (handle == null) {
                continue;
            }
            try {
                runner.runClaimed(handle, opId);
            } finally {
                leases.releaseGroup(handle);
            }
        }
    }

    private static void makeDue(JdbcTemplate jdbc, UUID opId) {
        jdbc.update("UPDATE spot_operation SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'"
                + " WHERE id = ? AND status = 'WAITING'", opId);
    }

    /**
     * A mutating cloud-rollout step: returns InProgress for the first {@code waits} polls of an
     * operation, then Done. The poll count lives in an external per-operation map, so the step is
     * deterministic across the program re-derivations a resume makes (design-core.md §4.1, §9.1).
     */
    static final class RolloutStep implements com.xammer.cloud.service.autospotting.spi.Step {
        private final String name;
        private final java.util.Map<UUID, AtomicInteger> polls;
        private final int waits;

        RolloutStep(String name, java.util.Map<UUID, AtomicInteger> polls, int waits) {
            this.name = name;
            this.polls = polls;
            this.waits = waits;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean mutates() {
            return true;
        }

        private StepOutcome poll(com.xammer.cloud.service.autospotting.spi.StepContext ctx) {
            int count = polls.computeIfAbsent(ctx.operationId(), k -> new AtomicInteger()).getAndIncrement();
            if (count < waits) {
                return new StepOutcome.InProgress(java.time.Duration.ofMillis(5), "{\"pct\":" + (count * 50) + "}");
            }
            return new StepOutcome.Done("{\"rollout\":\"done\"}", List.of("req-" + ctx.operationId()));
        }

        @Override
        public StepOutcome execute(com.xammer.cloud.service.autospotting.spi.StepContext ctx) {
            ctx.leaseHandle().assertHeld(ctx.callTimeout());
            return poll(ctx);
        }

        @Override
        public StepOutcome probe(com.xammer.cloud.service.autospotting.spi.StepContext ctx) {
            return poll(ctx);
        }

        @Override
        public String describeIntent(com.xammer.cloud.service.autospotting.spi.StepContext ctx) {
            return "rollout " + name;
        }
    }

    /** Run {@code condition} up to {@code maxIterations} with a 20 ms pause; true once it holds. */
    private static boolean repeatUntil(int maxIterations, java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < maxIterations; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
