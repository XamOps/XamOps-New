package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
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
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertQueuedOp;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.opStatus;
import static org.assertj.core.api.Assertions.assertThat;

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
}
