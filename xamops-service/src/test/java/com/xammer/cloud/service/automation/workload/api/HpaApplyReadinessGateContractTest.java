package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.workload.HorizontalAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.NativeVpaMode;
import com.xammer.cloud.service.automation.workload.NodeCapacity;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.HpaApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.HpaApplyResponseDto;
import com.xammer.cloud.service.automation.workload.execute.HpaActionCommand;
import com.xammer.cloud.service.automation.workload.execute.HpaApplyExecutor;
import com.xammer.cloud.service.automation.workload.execute.HpaApplyOutcome;
import com.xammer.cloud.service.automation.workload.execute.HpaPatchClient;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader;
import com.xammer.cloud.service.automation.workload.inventory.WorkloadPods;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the HPA apply readiness gate as strictly tightened by FEAT-001: {@code hpa-apply} readiness
 * (the standalone {@code xamops.k8s.automation.readiness.hpa-apply} staging flag, surfaced as
 * {@link HorizontalAutoscalingProperties#isReady()}) is a hard AND term in
 * {@link HpaApplyService}'s {@code requireControllerReady}. A read-write caller with the feature on
 * and an executor present is refused {@code 403 K8S_CONTROLLER_NOT_READY} while the staging flag is
 * false, and clears the gate only once it flips true.
 *
 * <p>Mirrors {@link VerticalWriteGateContractTest}'s intent for the readiness key. If the staging
 * flag is ever dropped from the HPA gate again, this test fails.
 */
class HpaApplyReadinessGateContractTest {

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");
    private static final WorkloadRef REF = WorkloadRef.of("team-b", "Deployment", "php-apache");
    private static final ResourceEnvelope GUARANTEED =
            new ResourceEnvelope(200, 200, 268_435_456L, 268_435_456L);
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);
    private static final VerticalMetricsProperties METRICS =
            VerticalReadServiceFixtures.metricsProperties("RECOMMEND", 72);

    private AutomationWritePermission writePermission;
    private ClusterWorkloadInventory inventory;
    private UpstreamVpaReader vpaReader;
    private HpaPatchClient patchClient;
    private HpaApplyExecutor executor;
    private com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService policyService;
    private FakeProvider provider;

    @BeforeEach
    void setUp() {
        writePermission = mock(AutomationWritePermission.class);
        when(writePermission.decide(any(), any()))
                .thenReturn(new AutomationWritePermission.Decision(true, null, null, null, null));
        inventory = mock(ClusterWorkloadInventory.class);
        vpaReader = mock(UpstreamVpaReader.class);
        patchClient = mock(HpaPatchClient.class);
        executor = mock(HpaApplyExecutor.class);
        policyService = mock(com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService.class);
        provider = new FakeProvider();

        when(inventory.read(any())).thenReturn(new ClusterWorkloadInventory.Inventory(
                List.of(workload()), new NodeCapacity(1930, 3_454_423_040L)));
        when(vpaReader.read(any())).thenReturn(upstreamMode(NativeVpaMode.OFF));
        when(patchClient.currentHpa(any(), eq("team-b"), eq("php-apache"))).thenReturn(cpuHpa());
        when(policyService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(executor.submit(any(), any(), any())).thenAnswer(invocation -> {
            HpaActionCommand command = invocation.getArgument(0);
            return HpaApplyOutcome.applied(command, command.dryRun());
        });
    }

    @Test
    @DisplayName("horizontal.enabled + read-write + executor but hpa-apply=false is 403 CONTROLLER_NOT_READY")
    void theStagingFlagFalseRefusesEvenReadWriteWithExecutor() {
        // enabled=true, ready(hpa-apply)=false, executorPresent=true, read-write account.
        HpaApplyService notReady = service(new HorizontalAutoscalingProperties(true, false, true));

        VerticalApplyService.ApplyRefusedException refusal = catchRefusal(
                () -> notReady.apply(principal(), readWriteAccount(), TARGET, REF, body(true)));

        assertThat(refusal.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusal.code()).isEqualTo("K8S_CONTROLLER_NOT_READY");
        verifyNoInteractions(inventory, executor, patchClient);
    }

    @Test
    @DisplayName("flipping hpa-apply=true clears the gate and the apply reaches the executor")
    void flippingTheStagingFlagTrueClearsTheGate() {
        HpaApplyService ready = service(new HorizontalAutoscalingProperties(true, true, true));

        HpaApplyResponseDto response = ready.apply(principal(), readWriteAccount(), TARGET, REF, body(true));

        assertThat(response.state()).isEqualTo("ACCEPTED");
        verify(executor).submit(any(), any(), any());
    }

    // ---------------------------------------------------------------- fixtures

    private HpaApplyService service(HorizontalAutoscalingProperties properties) {
        return new HpaApplyService(properties, writePermission, List.of(provider), inventory,
                vpaReader, patchClient, executor, policyService, METRICS, FIXED);
    }

    private static VerticalApplyService.ApplyRefusedException catchRefusal(Runnable call) {
        try {
            call.run();
        } catch (VerticalApplyService.ApplyRefusedException refused) {
            return refused;
        }
        throw new AssertionError("the call was expected to be refused, and was not");
    }

    private static HpaApplyRequestDto body(boolean dryRun) {
        return new HpaApplyRequestDto(null, 8, null, dryRun);
    }

    private static ClusterWorkloadInventory.Workload workload() {
        return new ClusterWorkloadInventory.Workload(REF, 5, false, false,
                HpaView.cpuHpa("php-apache", REF, 50, 1, 5), null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("php-apache", GUARANTEED, Map.of())),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod("php-apache-1", "uid-1"),
                        new WorkloadPods.Pod("php-apache-2", "uid-2"))));
    }

    private static UpstreamVpaReader.View upstreamMode(NativeVpaMode mode) {
        return new UpstreamVpaReader.View(Map.of(), Map.of(UpstreamVpaReader.key(REF), mode), true);
    }

    private static HorizontalPodAutoscaler cpuHpa() {
        return new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withNamespace("team-b").withName("php-apache")
                .withResourceVersion("12345").endMetadata()
                .withNewSpec().withMinReplicas(1).withMaxReplicas(5)
                .addNewMetric().withType("Resource")
                .withNewResource().withName("cpu")
                .withNewTarget().withType("Utilization").withAverageUtilization(50).endTarget()
                .endResource().endMetric()
                .endSpec().build();
    }

    private static ClientUserDetails principal() {
        List<SimpleGrantedAuthority> granted = new ArrayList<>();
        granted.add(new SimpleGrantedAuthority("ROLE_XAMOPS"));
        return new ClientUserDetails("user@example.com", "pw", granted, 7L, 10L, "tenant-a");
    }

    private static CloudAccount readWriteAccount() {
        Client client = new Client();
        client.setId(7L);
        CloudAccount account = new CloudAccount();
        account.setId(3L);
        account.setProvider("AWS");
        account.setAwsAccountId("102227938259");
        account.setAccessType("read-write");
        account.setClient(client);
        return account;
    }

    /** A provider that can be told not to support the target, or to fail opening it. */
    private static final class FakeProvider implements ClusterAccessProvider {

        private boolean supports = true;
        private ClusterAccessException failure;

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public boolean supports(ClusterTarget target) {
            return supports;
        }

        @Override
        public ClusterAccess open(ClusterTarget target) throws ClusterAccessException {
            if (failure != null) {
                throw failure;
            }
            return new ClusterAccess(mock(KubernetesClient.class), "https://example.invalid",
                    target.clusterName(), target.region(), providerName());
        }
    }
}
