package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Decision;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Role;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService.ConfirmCommand;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService.ConfirmResult;
import com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.xammer.cloud.autospotting.pg.OpsTestSupport.contextFor;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertGroup;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two confirms of one plan with different idempotency keys produce exactly one operation, and the
 * loser reads the winner's op id back (design-core.md §7.4 "Idempotent confirm", AC-SPN-01;
 * plan-phase-11.md task 11.2 verify). Real PostgreSQL: the {@code plan_id UNIQUE} op index plus the
 * conditional {@code confirmIfOpen} UPDATE serialize the two confirms.
 */
@Tag("containers")
class ConfirmConcurrencyIT {

    @Test
    void twoConfirmsOnePlanProduceOneOperationId() throws Exception {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { });
             AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = ctx.getBean("beanJdbc", JdbcTemplate.class);
            UUID groupId = insertGroup(jdbc, GroupState.ACTIVE);
            SpotPlanRepository plans = ctx.getBean(SpotPlanRepository.class);
            SpotPlan plan = insertOpenConfigApplyPlan(plans, groupId);

            ConfirmService confirm = confirmServiceOver(ctx);
            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(
                            ctx.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            // Emulate the @Transactional(REQUIRES_NEW) proxy ConfirmService carries as a Spring bean in
            // production: each confirm runs in its own transaction, so the conditional confirmIfOpen
            // UPDATE and the op insert commit together and serialize against the other thread.
            CloudAccount account = account();
            ClientUserDetails user = user();

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<ConfirmResult> r1 = new AtomicReference<>();
            AtomicReference<ConfirmResult> r2 = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> run(tx, confirm, account, user, plan.getId(), "key-A", start, r1));
                pool.submit(() -> run(tx, confirm, account, user, plan.getId(), "key-B", start, r2));
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            // Exactly one op row for the plan, and both confirms report the same op id.
            Integer opCount = jdbc.queryForObject("SELECT count(*) FROM spot_operation WHERE plan_id = ?",
                    Integer.class, plan.getId());
            assertThat(opCount).isEqualTo(1);
            UUID theOp = jdbc.queryForObject("SELECT id FROM spot_operation WHERE plan_id = ?", UUID.class,
                    plan.getId());
            assertThat(r1.get()).isNotNull();
            assertThat(r2.get()).isNotNull();
            assertThat(r1.get().operationId()).isEqualTo(theOp);
            assertThat(r2.get().operationId()).isEqualTo(theOp);
        }
    }

    private static void run(org.springframework.transaction.support.TransactionTemplate tx, ConfirmService confirm,
                            CloudAccount account, ClientUserDetails user, UUID planId, String key,
                            CountDownLatch start, AtomicReference<ConfirmResult> out) {
        try {
            start.await();
            out.set(tx.execute(status -> confirm.confirm(account, user, planId,
                    new ConfirmCommand(1, List.of(), false, null, false, key, "hash-" + key))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // A loser may hit a transient unique-violation then be re-read by the other path; the
            // post-conditions below assert the single-op invariant regardless of which thread set out.
        }
    }

    private static SpotPlan insertOpenConfigApplyPlan(SpotPlanRepository plans, UUID groupId) {
        SpotPlan p = new SpotPlan();
        p.setId(UUID.randomUUID());
        p.setGroupId(groupId);
        p.setCloudAccountId(1L);
        p.setKind(OperationKind.CONFIG_APPLY);
        p.setStatus(SpotPlan.PlanStatus.OPEN);
        p.setFromState(GroupState.ACTIVE);
        p.setVersion(1);
        p.setSchemaVersion(1);
        p.setLiveFingerprint("fp:1");
        p.setBody("{\"target\":{}}");
        p.setRequiresFullSpotOptin(false);
        p.setWarningIds("[]");
        p.setDryRun(false);
        p.setCreatedBy("tester");
        p.setCreatedAt(Instant.now());
        p.setExpiresAt(Instant.now().plusSeconds(600));
        return plans.saveAndFlush(p);
    }

    private static ConfirmService confirmServiceOver(AnnotationConfigApplicationContext ctx) {
        AutoSpottingAuthorization authz = Mockito.mock(AutoSpottingAuthorization.class);
        Mockito.when(authz.authorizeConfirm(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(new Decision(true, Role.CLIENT_OWNER, Actor.user(100L, "ROLE_USER"), false,
                        null, null, null));
        AutoSpottingSwitchProperties props = Mockito.mock(AutoSpottingSwitchProperties.class);
        Mockito.when(props.mutationsDryRun()).thenReturn(false);
        return new ConfirmService(
                ctx.getBean(SpotPlanRepository.class),
                ctx.getBean(SpotGroupRepository.class),
                ctx.getBean(SpotOperationRepository.class),
                ctx.getBean(OperationStore.class),
                ctx.getBean(PreemptionService.class),
                authz,
                new RevertPreconditions(props),
                ctx.getBean(SwitchService.class),
                props,
                new ProviderRegistry(List.of()),
                ctx.getBean(AutoSpottingAuditWriter.class),
                Mockito.mock(com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService.class));
    }

    private static CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(1L);
        a.setProvider("AWS");
        Client c = new Client();
        c.setId(1L);
        a.setClient(c);
        return a;
    }

    private static ClientUserDetails user() {
        return new ClientUserDetails("owner", "x", List.of(new SimpleGrantedAuthority("ROLE_USER")),
                1L, 100L, "t");
    }
}
