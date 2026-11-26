package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.autospotting.fake.FakeSpotProvider;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.plan.FeeBasisResolver;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.plan.PlanService;
import com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.PlanOptions;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.xammer.cloud.autospotting.pg.OpsTestSupport.contextFor;
import static com.xammer.cloud.autospotting.pg.OpsTestSupport.insertGroup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * D-23b-03 option (b) through the real PlanService on PostgreSQL. A REVERTED row whose desired_config records
 * configOnGroup (drain-only revert): an ENABLE plan blocked CONFIG_STILL_ON_GROUP does not move the group to PLANNED,
 * so the REVERT it asks for stays available (configOnGroup also re-grants GR-02 for a CHECK_FAILED drain). A plain
 * REVERTED row still has nothing to revert.
 */
@Tag("containers")
class ConfigStillOnGroupPlanIT {

    /** A provider that blocks ENABLE as AwsSpotProvider does when the options say configOnGroup. */
    private static FakeSpotProvider provider() {
        return new FakeSpotProvider(Provider.AWS) {
            @Override
            public EligibilityResult checkEligibility(GroupRef ref, ReadOnlyCloudView view, Profile profile,
                                                      EvalMode mode, PlanOptions options) {
                if (options != null && options.configOnGroup()) {
                    return new EligibilityResult(List.of(EligibilityResult.Check.of("b-CONFIG_STILL_ON_GROUP",
                            "CONFIG_STILL_ON_GROUP", "XamOps' configuration is still on this group.")), List.of(),
                            List.of());
                }
                return super.checkEligibility(ref, view, profile, mode, options);
            }

            @Override
            public com.xammer.cloud.service.autospotting.spi.PlanBody createPlan(
                    com.xammer.cloud.service.autospotting.spi.PlanRequest req, ReadOnlyCloudView view) {
                var b = super.createPlan(req, view);
                EligibilityResult e = req.kind() == OperationKind.REVERT ? b.eligibility()
                        : checkEligibility(req.ref(), view, req.profile(), EvalMode.PLAN, req.options());
                return new com.xammer.cloud.service.autospotting.spi.PlanBody(b.currentJson(), b.targetJson(),
                        b.diffJson(), b.snapshotToStore(), e, b.rollout(), b.estimates(), b.onDemandFloorJson(),
                        b.feeBasisJson(), b.revertPathJson());
            }
        };
    }

    private static PlanService planService(AnnotationConfigApplicationContext ctx) {
        return new PlanService(ctx.getBean(SpotPlanRepository.class), ctx.getBean(SpotGroupRepository.class),
                ctx.getBean(SpotOperationRepository.class), new ProviderRegistry(List.of(provider())),
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

    private static PlanService.CreateCommand command(OperationKind kind, UUID groupId) {
        return new PlanService.CreateCommand(kind, groupId, null, "conservative", false, null, "hash-" + kind, "tester");
    }

    private static String state(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, id);
    }

    @Test
    void aBlockedEnableLeavesTheGroupRevertedAndTheRevertStaysAvailable() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            UUID group = insertGroup(jdbc, GroupState.REVERTED);
            jdbc.update("UPDATE spot_group SET desired_config = ?::jsonb WHERE id = ?",
                    "{\"configOnGroup\":true,\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}}",
                    group);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            PlanService service = planService(ctx);
            var enable = tx.execute(s -> service.createPlan(account(), null, command(OperationKind.ENABLE, group)));
            assertThat(enable.getBody()).contains("CONFIG_STILL_ON_GROUP");
            assertThat(state(jdbc, group)).as("not moved to PLANNED").isEqualTo("REVERTED");
            var revert = tx.execute(s -> service.createPlan(account(), null, command(OperationKind.REVERT, group)));
            assertThat(revert.getKind()).isEqualTo(OperationKind.REVERT);
            assertThat(jdbc.queryForObject("SELECT status FROM spot_plan WHERE id = ?", String.class, enable.getId()))
                    .as("the revert plan superseded the blocked enable").isEqualTo("SUPERSEDED");
        }
    }

    @Test
    void aRevertedGroupWithoutXamOpsConfigurationHasNothingToRevert() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            UUID group = insertGroup(jdbc, GroupState.REVERTED);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            PlanService service = planService(ctx);
            assertThatThrownBy(() -> tx.execute(s -> service.createPlan(account(), null,
                    command(OperationKind.REVERT, group))))
                    .isInstanceOfSatisfying(AutoSpottingApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("NOT_MANAGED"));
            var enable = tx.execute(s -> service.createPlan(account(), null, command(OperationKind.ENABLE, group)));
            assertThat(enable.getBody()).doesNotContain("CONFIG_STILL_ON_GROUP");
            assertThat(state(jdbc, group)).isEqualTo("PLANNED");
        }
    }
}
