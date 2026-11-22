package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.autospotting.fake.FakeSpotProvider;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.plan.FeeBasisResolver;
import com.xammer.cloud.service.autospotting.core.plan.PlanService;
import com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.xammer.cloud.service.autospotting.core.ops.OperationDispatcher;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.xammer.cloud.autospotting.pg.OpsTestSupport.contextFor;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertGroup;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertQueuedOp;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.opStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Phase 23 defect D-23-06 on real PostgreSQL: the first plan for a group with no row inserts exactly one
 * UNMANAGED row even when two plans race, and an op whose group has no row fails fast instead of staying
 * QUEUED forever.
 */
@Tag("containers")
class RowlessGroupIT {

    private static final String KEY = "us-east-1/e2e-asg";

    @Test
    void twoConcurrentFirstPlansEndWithOneUnmanagedRowAndNeitherFails() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger inserted = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int i = 0; i < 2; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            tx.executeWithoutResult(s -> {
                                inserted.addAndGet(groups.insertUnmanagedIfAbsent(UUID.randomUUID(), "AWS", 1L, 1L,
                                        KEY, null, "plan", Instant.now()));
                                assertThat(groups.findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(
                                        Provider.AWS, 1L, KEY, GroupState.GONE)).isPresent();
                            });
                        } catch (Throwable t) {
                            failures.incrementAndGet();
                        }
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }
            assertThat(failures.get()).isZero();
            assertThat(inserted.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group WHERE group_key = ?", Integer.class, KEY))
                    .isEqualTo(1);
            SpotGroup row = groups.findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(
                    Provider.AWS, 1L, KEY, GroupState.GONE).orElseThrow();
            assertThat(row.getState()).isEqualTo(GroupState.UNMANAGED);
            assertThat(row.getCreatedBy()).isEqualTo("plan");
            // A second (sequential) plan reuses the row.
            Integer again = tx.execute(s -> groups.insertUnmanagedIfAbsent(UUID.randomUUID(), "AWS", 1L, 1L, KEY, null,
                    "plan", Instant.now()));
            assertThat(again).isZero();
        }
    }

    /**
     * D-23-07: the row inserted for a first plan commits on its own, so the lifecycle's REQUIRES_NEW row lock
     * (the move to PLANNED) sees it while the plan transaction is still open, and the group ends PLANNED.
     */
    @Test
    void theFirstPlansRowIsVisibleToTheLifecycleSoTheGroupMovesToPlanned() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);
            com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService lifecycle =
                    ctx.getBean(com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            Object result = tx.execute(s -> {
                // As PlanService.createPlan does inside its own (still open) transaction.
                groups.insertUnmanagedIfAbsent(UUID.randomUUID(), "AWS", 1L, 1L, KEY, null, "plan", Instant.now());
                SpotGroup row = groups.findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(
                        Provider.AWS, 1L, KEY, GroupState.GONE).orElseThrow();
                return lifecycle.transitionUnderRowLock(row,
                        com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.TransitionRequest.of(
                                GroupState.PLANNED, "ENABLE_PLAN_CREATED",
                                com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor.system(),
                                null, Instant.now()));
            });
            assertThat(result).isEqualTo(com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.Result.APPLIED);
            assertThat(jdbc.queryForObject("SELECT state FROM spot_group WHERE group_key = ?", String.class, KEY))
                    .isEqualTo("PLANNED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group_transition t JOIN spot_group g ON g.id = t.group_id"
                    + " WHERE g.group_key = ? AND t.to_state = 'PLANNED'", Integer.class, KEY)).isEqualTo(1);
        }
    }

    @Test
    void anOpWhoseGroupHasNoRowFailsFastWithGroupMissing() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            UUID op = insertQueuedOp(store, UUID.randomUUID(), OperationKind.ENABLE, GroupState.UNMANAGED);
            assertThat(opStatus(jdbc, op)).isEqualTo("QUEUED");

            ctx.getBean(OperationDispatcher.class).poll();

            assertThat(opStatus(jdbc, op)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT error_code FROM spot_operation WHERE id = ?", String.class, op))
                    .isEqualTo("GROUP_MISSING");
        }
    }

    /** Finding #13: each orphan op gets its own OP_FAILED audit row and its own group_missing metric increment. */
    @Test
    void eachOrphanOpWritesOneAuditRowAndOneMetricIncrement() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            UUID missingGroup = UUID.randomUUID();
            UUID op1 = insertQueuedOp(store, missingGroup, OperationKind.ENABLE, GroupState.UNMANAGED);
            UUID op2 = insertQueuedOp(store, missingGroup, OperationKind.CONFIG_APPLY, GroupState.ACTIVE);
            io.micrometer.core.instrument.MeterRegistry meters =
                    ctx.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            double before = dispatchCount(meters);
            ctx.getBean(OperationDispatcher.class).poll();
            assertThat(opStatus(jdbc, op1)).isEqualTo("FAILED");
            assertThat(opStatus(jdbc, op2)).isEqualTo("FAILED");
            for (UUID op : new UUID[] {op1, op2}) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM autospotting_audit WHERE operation_id = ?"
                        + " AND event_type = 'OP_FAILED' AND outcome = 'FAILED' AND group_id = ?"
                        + " AND error_code = 'GROUP_MISSING' AND cloud_account_id = 1 AND plan_id IS NULL",
                        Integer.class, op, missingGroup)).isEqualTo(1);
            }
            assertThat(dispatchCount(meters) - before).isEqualTo(2.0);
        }
    }

    private static final String ARN =
            "arn:aws:autoscaling:us-east-1:111122223333:autoScalingGroup:uuid:autoScalingGroupName/e2e-asg";

    /** A fake AWS provider whose plan carries a stable id, as {@code AwsSpotProvider} does with the ASG ARN. */
    private static FakeSpotProvider providerWithStableId(String stableId) {
        return new FakeSpotProvider(Provider.AWS) {
            @Override
            public PlanBody createPlan(PlanRequest req, ReadOnlyCloudView view) {
                PlanBody b = super.createPlan(req, view);
                return new PlanBody(b.currentJson(), b.targetJson(), b.diffJson(), b.snapshotToStore(),
                        b.eligibility(), b.rollout(), b.estimates(), b.onDemandFloorJson(), b.feeBasisJson(),
                        b.revertPathJson(), stableId);
            }
        };
    }

    private static PlanService planService(AnnotationConfigApplicationContext ctx, SpotProvider provider) {
        return new PlanService(ctx.getBean(SpotPlanRepository.class), ctx.getBean(SpotGroupRepository.class),
                ctx.getBean(SpotOperationRepository.class), new ProviderRegistry(List.of(provider)),
                mock(FeeBasisResolver.class), mock(RevertPreconditions.class), ctx.getBean(SwitchService.class),
                mock(AutoSpottingSwitchProperties.class), ctx.getBean(GroupLifecycleService.class),
                Duration.ofMinutes(15));
    }

    private static CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(1L);
        a.setProvider("AWS");
        Client client = new Client();
        client.setId(1L);
        a.setClient(client);
        return a;
    }

    private static PlanService.CreateCommand enable(String key) {
        return new PlanService.CreateCommand(OperationKind.ENABLE, null, key, "conservative", false, null,
                "hash", "tester");
    }

    private static SpotPlan createPlanInNewTx(TransactionTemplate tx, PlanService service, String key) {
        return tx.execute(s -> service.createPlan(account(), null, enable(key)));
    }

    /** Finding #1: the row a first plan creates carries the ASG ARN, so the PLANNED move can't hit the identity index with NULL. */
    @Test
    void aPlanCreatedRowStoresTheProvidersStableId() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            createPlanInNewTx(tx, planService(ctx, providerWithStableId(ARN)), KEY);
            assertThat(jdbc.queryForObject("SELECT provider_stable_id FROM spot_group WHERE group_key = ?",
                    String.class, KEY)).isEqualTo(ARN);
            assertThat(jdbc.queryForObject("SELECT state FROM spot_group WHERE group_key = ?", String.class, KEY))
                    .isEqualTo("PLANNED");
        }
    }

    /** Finding #1: a second live row for the same stable id is a 409, not a unique-index 500. */
    @Test
    void aPlanForAStableIdAnotherLiveRowHoldsIsRefusedWith409() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            UUID live = insertGroup(jdbc, GroupState.ACTIVE);
            jdbc.update("UPDATE spot_group SET provider_stable_id = ?, cloud_account_id = 2 WHERE id = ?", ARN, live);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            PlanService service = planService(ctx, providerWithStableId(ARN));
            assertThatThrownBy(() -> createPlanInNewTx(tx, service, KEY))
                    .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("DUPLICATE_PROVIDER_ACCOUNT");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group WHERE group_key = ?", Integer.class, KEY))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_plan", Integer.class)).isZero();
        }
    }

    /** Finding #4: two full first plans for one rowless group both succeed and leave one OPEN plan. */
    @Test
    void twoConcurrentFullFirstPlansLeaveOneOpenPlanAndNeitherFails() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            PlanService service = planService(ctx, providerWithStableId(ARN));
            CountDownLatch start = new CountDownLatch(1);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int i = 0; i < 2; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            createPlanInNewTx(tx, service, KEY);
                        } catch (Throwable t) {
                            failures.add(t);
                        }
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }
            assertThat(failures).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_plan WHERE status = 'OPEN'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group WHERE group_key = ?", Integer.class, KEY))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state FROM spot_group WHERE group_key = ?", String.class, KEY))
                    .isEqualTo("PLANNED");
        }
    }

    private static double dispatchCount(io.micrometer.core.instrument.MeterRegistry meters) {
        return meters.find("autospotting.dispatch").tag("event", "group_missing").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }
}
