package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.AwsPricingService;
import com.xammer.cloud.service.SpotPricingService;
import com.xammer.cloud.service.automation.workload.ConfidenceProperties;
import com.xammer.cloud.service.automation.workload.RpsSizingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import com.xammer.cloud.service.automation.workload.cost.K8sCostProperties;
import com.xammer.cloud.service.automation.workload.cost.NodeRateResolver;
import com.xammer.cloud.service.automation.workload.cost.WorkloadCostAttributionService;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import com.xammer.cloud.service.automation.workload.usage.NoMetricsSourceProvider;
import com.xammer.cloud.service.automation.workload.usage.UsageHistoryProvider;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Boot/slice guard for the two-constructor read service after the three cost collaborators were added
 * to BOTH constructors (FEAT-001, REQUIRED). The service declares a {@code @Autowired} production
 * constructor delegating via {@code this(...)} to a package-private test-seam constructor; adding a
 * dependency to one and not the other reproduces the "passes tests but won't boot" regression. This
 * binds the service plus the three cost beans (and its other collaborators as mocks) through an
 * {@link ApplicationContextRunner} and asserts the context still loads and the bean constructs.
 *
 * <p>Also a slice assertion: the read response carries {@code cost} when the feature is enabled and
 * {@code cost == null} when the switch is off — with no AWS call on the off path.
 */
class VerticalAutomationReadServiceContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(WorkloadAutoscalingProperties.class, () -> new WorkloadAutoscalingProperties(false, false))
            .withBean(VerticalMetricsProperties.class,
                    () -> new VerticalMetricsProperties("monitoring", "prometheus-server", 9090, 20, 120, 24, "OFF"))
            .withBean(ConfidenceProperties.class, () -> new ConfidenceProperties(0.5, 0.8))
            .withBean(RpsSizingProperties.class, () -> RpsSizingProperties.of(false, "", 0.70, null))
            .withBean(UsageHistoryProvider.class, NoMetricsSourceProvider::new)
            .withBean(ClusterWorkloadInventory.class, ClusterWorkloadInventory::new)
            .withBean(UpstreamVpaReader.class, UpstreamVpaReader::new)
            .withBean(WorkloadPolicyService.class, () -> mock(WorkloadPolicyService.class))
            .withBean(WorkloadVerticalActionRepository.class, () -> mock(WorkloadVerticalActionRepository.class))
            .withBean(AwsPricingService.class, () -> mock(AwsPricingService.class))
            .withBean(SpotPricingService.class, () -> mock(SpotPricingService.class))
            .withBean(AwsClientProvider.class, () -> mock(AwsClientProvider.class))
            .withBean(K8sCostProperties.class, K8sCostProperties::disabled)
            .withBean(NodeRateResolver.class)
            .withBean(WorkloadCostAttributionService.class)
            .withBean(VerticalAutomationReadService.class);

    @Test
    @DisplayName("the context still loads with the three cost deps on both constructors")
    void contextLoads() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(VerticalAutomationReadService.class);
            assertThat(context).hasSingleBean(NodeRateResolver.class);
            assertThat(context).hasSingleBean(WorkloadCostAttributionService.class);
            assertThat(context).hasSingleBean(K8sCostProperties.class);
        });
    }

    @Test
    @DisplayName("with the cost switch off the read response carries cost == null and makes no AWS call")
    void costNullWhenOff() {
        AwsPricingService pricing = mock(AwsPricingService.class);
        WorkloadPolicyService policyService = mock(WorkloadPolicyService.class);
        when(policyService.resolveAll(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Map.of());
        WorkloadVerticalActionRepository actions = mock(WorkloadVerticalActionRepository.class);
        when(actions.findByClusterConfigIdOrderByAppliedAtDesc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());

        NodeRateResolver resolver = new NodeRateResolver(pricing, mock(SpotPricingService.class),
                mock(AwsClientProvider.class), K8sCostProperties.disabled());
        VerticalAutomationReadService service = new VerticalAutomationReadService(
                new WorkloadAutoscalingProperties(false, false),
                new VerticalMetricsProperties("monitoring", "prometheus-server", 9090, 20, 120, 24, "OFF"),
                new ConfidenceProperties(0.5, 0.8), RpsSizingProperties.of(false, "", 0.70, null),
                new NoMetricsSourceProvider(), java.util.List.of(), new ClusterWorkloadInventory(),
                new UpstreamVpaReader(), policyService, actions,
                resolver, new WorkloadCostAttributionService(K8sCostProperties.disabled()),
                K8sCostProperties.disabled());

        CloudAccount account = new CloudAccount("acct", "ext", "read-only", null);
        ClusterTarget target = new ClusterTarget(7L, 42L, "102227938259", "c", "ap-south-1");
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(42L);

        VerticalEngineResponse response = service.read(account, target, config);

        assertThat(response.cost()).isNull();
        assertThat(response.workloads()).allSatisfy(w -> assertThat(w.cost()).isNull());
        org.mockito.Mockito.verifyNoInteractions(pricing);
    }
}
