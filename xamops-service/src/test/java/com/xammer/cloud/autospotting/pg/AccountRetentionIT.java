package com.xammer.cloud.autospotting.pg;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AccountDeletionGuard;
import com.xammer.cloud.service.autospotting.core.AccountHasManagedGroupsException;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Decision;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Role;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService;
import com.xammer.cloud.service.autospotting.core.plan.PlanService;
import com.xammer.cloud.service.autospotting.core.plan.RevertAllService;
import com.xammer.cloud.service.autospotting.core.plan.RevertAllService.Batch;
import com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.contextFor;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertGroup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
/**
 * Account deletion and "Revert all" on real PostgreSQL (plan-phase-24.md task 24.2, PO-21, AC-CMP-03, AC-LED-21):
 * deletion is refused while a group is ACTIVE; revert-all creates one plan per revertable group; the batch
 * confirm queues one operation each; once every group is REVERTED the guard lets deletion through, and every
 * group, operation, audit, ledger and statement row is still there.
 */
@Tag("containers")
class AccountRetentionIT {
    private static final String[] RETAINED = {"spot_group", "spot_operation", "autospotting_audit",
            "savings_ledger_run", "savings_ledger_entry", "savings_statement", "savings_statement_line"};
    @Test
    void revertAllThenDeleteKeepsEveryRetainedRow() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { });
             AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = ctx.getBean("beanJdbc", JdbcTemplate.class);
            UUID g1 = insertGroup(jdbc, GroupState.ACTIVE);
            UUID g2 = insertGroup(jdbc, GroupState.FAILED);
            UUID g3 = insertGroup(jdbc, GroupState.DEGRADED);
            seedRetained(ctx, jdbc, g1);
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);
            AccountDeletionGuard guard = new AccountDeletionGuard(groups);

            // 1. an ACTIVE group blocks deletion; DEGRADED and FAILED are listed, not blocking.
            assertThatThrownBy(() -> guard.assertDeletable(1L)).isInstanceOf(AccountHasManagedGroupsException.class);
            assertThat(guard.check(1L).stillConverted()).hasSize(2);
            long[] before = counts(jdbc);

            // 2. revert-all: one REVERT plan per revertable group, one shared batch id.
            SpotPlanRepository plans = ctx.getBean(SpotPlanRepository.class);
            PlanService planService = Mockito.mock(PlanService.class);
            Mockito.when(planService.createPlan(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(inv -> {
                PlanService.CreateCommand cmd = inv.getArgument(2);
                return insertRevertPlan(plans, cmd.groupId());
            });
            RevertAllService revertAll = new RevertAllService(groups, plans,
                    ctx.getBean(SpotOperationRepository.class), planService, confirmServiceOver(ctx),
                    new IdempotencyService());
            Batch batch = revertAll.create(account(), user(), "key-1");
            assertThat(batch.total()).isEqualTo(3);
            Integer batched = jdbc.queryForObject("SELECT count(*) FROM spot_plan WHERE batch_id = ?", Integer.class,
                    batch.batchId());
            assertThat(batched).isEqualTo(3);

            // 3. batch confirm queues exactly one operation per group.
            Batch confirmed = revertAll.confirm(account(), user(), batch.batchId(), List.of(), null);
            assertThat(confirmed.items()).allSatisfy(i -> assertThat(i.operationId()).isNotNull());
            Integer ops = jdbc.queryForObject("SELECT count(*) FROM spot_operation WHERE kind = 'REVERT'",
                    Integer.class);
            assertThat(ops).isEqualTo(3);

            // 4. once every group is REVERTED, deletion is allowed and nothing is removed.
            jdbc.update("UPDATE spot_group SET state = 'REVERTED' WHERE id IN (?, ?, ?)", g1, g2, g3);
            assertThatCode(() -> guard.assertDeletable(1L)).doesNotThrowAnyException();
            long[] after = counts(jdbc);
            for (int i = 0; i < RETAINED.length; i++) {
                assertThat(after[i]).as(RETAINED[i]).isGreaterThanOrEqualTo(before[i]).isGreaterThan(0);
            }
        }
    }
    private static long[] counts(JdbcTemplate jdbc) {
        long[] out = new long[RETAINED.length];
        for (int i = 0; i < RETAINED.length; i++) {
            out[i] = jdbc.queryForObject("SELECT count(*) FROM " + RETAINED[i], Long.class);
        }
        return out;
    }
    private static void seedRetained(AnnotationConfigApplicationContext ctx, JdbcTemplate jdbc, UUID groupId) {
        OpsTestSupport.insertQueuedOp(ctx.getBean(OperationStore.class), groupId, OperationKind.CONFIG_APPLY,
                GroupState.ACTIVE);
        TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(s -> ctx.getBean(AutoSpottingAuditWriter.class).write(
                AuditRecord.builder("TEST_EVENT", Actor.system(), "TEST", "SUCCESS").groupId(groupId).build()));
        UUID run = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        UUID statement = UUID.randomUUID();
        LedgerSql.insertRun(jdbc, run, null);
        LedgerSql.insertEntry(jdbc, entry, run, groupId);
        LedgerSql.insertStatement(jdbc, statement, "DRAFT");
        LedgerSql.insertStatementLine(jdbc, statement);
    }
    private static SpotPlan insertRevertPlan(SpotPlanRepository plans, UUID groupId) {
        SpotPlan p = new SpotPlan();
        p.setId(UUID.randomUUID());
        p.setGroupId(groupId);
        p.setCloudAccountId(1L);
        p.setKind(OperationKind.REVERT);
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
        ConfirmService delegate = new ConfirmService(
                ctx.getBean(SpotPlanRepository.class), ctx.getBean(SpotGroupRepository.class),
                ctx.getBean(SpotOperationRepository.class), ctx.getBean(OperationStore.class),
                ctx.getBean(PreemptionService.class), authz, new RevertPreconditions(props),
                ctx.getBean(SwitchService.class), props, new ProviderRegistry(List.of()),
                ctx.getBean(AutoSpottingAuditWriter.class),
                Mockito.mock(com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService.class));
        // ConfirmService is @Transactional(REQUIRES_NEW) as a Spring bean; emulate that per call.
        TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        return Mockito.mock(ConfirmService.class, inv -> tx.execute(s -> {
            try {
                return inv.getMethod().invoke(delegate, inv.getArguments());
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }));
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
