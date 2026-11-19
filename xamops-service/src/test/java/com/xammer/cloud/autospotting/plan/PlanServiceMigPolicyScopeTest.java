package com.xammer.cloud.autospotting.plan;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.plan.PlanService;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A MIG_POLICY_UPDATE is GCP-only and creates no row for a missing MIG (phase 27 review B1). */
class PlanServiceMigPolicyScopeTest {

    private final SpotPlanRepository plans = mock(SpotPlanRepository.class);
    private final SpotGroupRepository groups = mock(SpotGroupRepository.class);
    private final com.xammer.cloud.service.autospotting.spi.ProviderRegistry providers =
            mock(com.xammer.cloud.service.autospotting.spi.ProviderRegistry.class);

    private PlanService service() {
        return new PlanService(plans, groups, mock(SpotOperationRepository.class), providers,
                mock(com.xammer.cloud.service.autospotting.core.plan.FeeBasisResolver.class),
                mock(com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions.class),
                mock(com.xammer.cloud.service.autospotting.core.switches.SwitchService.class),
                mock(com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties.class),
                mock(com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.class),
                Duration.ofMinutes(15));
    }

    private static CloudAccount account(String provider) {
        CloudAccount a = new CloudAccount();
        a.setId(7L);
        a.setProvider(provider);
        return a;
    }

    private static PlanService.CreateCommand cmd(OperationKind kind, String edit) {
        return new PlanService.CreateCommand(kind, null, "p/us-central1/mig", null, false, "k", "h", "u", edit);
    }

    @Test
    void anAwsAccountCannotPlanAPolicyUpdateOrCarryAPolicyEdit() {
        SpotProvider aws = mock(SpotProvider.class);
        when(providers.find(Provider.AWS)).thenReturn(Optional.of(aws));
        for (OperationKind kind : new OperationKind[] {OperationKind.MIG_POLICY_UPDATE, OperationKind.CONFIG_APPLY}) {
            assertThatThrownBy(() -> service().createPlan(account("AWS"), null, cmd(kind, "{}")))
                    .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> {
                        org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("AS_KIND_NOT_SUPPORTED");
                        org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    });
        }
        verify(aws, never()).createPlan(any(), any());
        verify(groups, never()).saveAndFlush(any());
        verify(plans, never()).saveAndFlush(any());
    }
    @Test
    void aMissingMigCreatesNoUnmanagedRowAndNoPlan() {
        SpotProvider gcp = mock(SpotProvider.class);
        when(providers.find(Provider.GCP)).thenReturn(Optional.of(gcp));
        when(groups.findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(gcp.createPlan(any(), any())).thenReturn(new PlanBody(null, null, null, null,
                new EligibilityResult(List.of(EligibilityResult.Check.of("b-GCP_MIG_NOT_FOUND", "GCP_MIG_NOT_FOUND", "gone")),
                        List.of(), List.of()), null, List.of(), null, null, null));
        assertThatThrownBy(() -> service().createPlan(account("GCP"), null, cmd(OperationKind.MIG_POLICY_UPDATE, "{}")))
                .isInstanceOf(AutoSpottingApiException.class);
        verify(groups, never()).saveAndFlush(any());
        verify(plans, never()).saveAndFlush(any());
    }
}
