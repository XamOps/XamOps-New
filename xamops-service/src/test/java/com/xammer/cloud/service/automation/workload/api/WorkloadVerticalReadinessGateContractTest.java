package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.workload.NodeCapacity;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;
import com.xammer.cloud.service.automation.workload.execute.ActionSubmission;
import com.xammer.cloud.service.automation.workload.execute.DirectInPlaceResizeExecutor;
import com.xammer.cloud.service.automation.workload.execute.ResizeProof;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader;
import com.xammer.cloud.service.automation.workload.inventory.WorkloadPods;
import com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the vertical rightsizer-apply readiness gate: the {@code vertical-rightsizing} readiness key
 * is a hard enforcement term in {@link VerticalApplyService}'s {@code requireControllerReady}, so a
 * read-write caller on an enabled engine with an executor present is still refused
 * {@code 403 K8S_CONTROLLER_NOT_READY} while the key is false, and only clears the gate once the key
 * flips true.
 *
 * <p>This mirrors {@link VerticalWriteGateContractTest}'s intent (a gate is enforced in code, not
 * merely reported) but for the readiness key rather than the per-user write gate. The gate is
 * <strong>only pinned here</strong> in this task: it is not re-wired. If someone drops the
 * {@code properties.isReady()} term from the vertical gate, this test fails.
 */
class WorkloadVerticalReadinessGateContractTest {

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");
    private static final WorkloadRef REF =
            WorkloadRef.of("team-a", "Deployment", "overprovisioned-api");
    private static final ResourceEnvelope GUARANTEED_1CPU_1GI =
            new ResourceEnvelope(1000, 1000, 1_073_741_824L, 1_073_741_824L);
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);
    private static final VerticalMetricsProperties METRICS =
            VerticalReadServiceFixtures.metricsProperties("RECOMMEND", 72);

    private AutomationWritePermission writePermission;
    private ClusterWorkloadInventory inventory;
    private UpstreamVpaReader vpaReader;
    private DirectInPlaceResizeExecutor executor;
    private WorkloadPolicyService policyService;
    private WorkloadActionRecorder actionRecorder;
    private FakeProvider provider;

    @BeforeEach
    void setUp() {
        writePermission = mock(AutomationWritePermission.class);
        when(writePermission.decide(any(), any()))
                .thenReturn(new AutomationWritePermission.Decision(true, null, null, null, null));
        inventory = mock(ClusterWorkloadInventory.class);
        vpaReader = mock(UpstreamVpaReader.class);
        when(vpaReader.read(any())).thenReturn(UpstreamVpaReader.View.none());
        executor = mock(DirectInPlaceResizeExecutor.class);
        policyService = mock(WorkloadPolicyService.class);
        actionRecorder = mock(WorkloadActionRecorder.class);
        provider = new FakeProvider();
        when(inventory.read(any())).thenReturn(new ClusterWorkloadInventory.Inventory(
                List.of(workload()), new NodeCapacity(1930, 3_454_423_040L)));
        when(policyService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Test
    @DisplayName("vertical-rightsizing=false + vertical.enabled=true + executor present is 403 CONTROLLER_NOT_READY")
    void theReadinessKeyFalseRefusesEvenWithEngineOnAndExecutorPresent() {
        // enabled=true, ready(vertical-rightsizing)=false, executorPresent=true.
        VerticalApplyService notReady = service(new WorkloadAutoscalingProperties(true, false, true));

        VerticalApplyService.ApplyRefusedException refusal = catchRefusal(
                () -> notReady.apply(principal(), readWriteAccount(), TARGET, REF, body(false)));

        assertThat(refusal.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusal.code()).isEqualTo("K8S_CONTROLLER_NOT_READY");
        verifyNoInteractions(inventory, executor);
    }

    @Test
    @DisplayName("flipping only the vertical-rightsizing readiness key true clears the gate")
    void flippingTheReadinessKeyTrueClearsTheGate() {
        // enabled=true, ready(vertical-rightsizing)=true, executorPresent=true: the readiness gate is
        // cleared. The call then proceeds past it -- whether it is accepted or refused by a later
        // rule, the one code it must never be once the key is true is CONTROLLER_NOT_READY.
        VerticalApplyService ready = service(new WorkloadAutoscalingProperties(true, true, true));

        when(executor.submit(any(), any(), any())).thenReturn(ActionSubmission.accepted("corr-1"));
        when(executor.proof(any())).thenReturn(java.util.Optional.of(new ResizeProof(
                ResizeProof.Outcome.DRY_RUN, "Dry run: no patch was sent to the cluster.", false,
                List.of(), List.of())));

        try {
            ready.apply(principal(), readWriteAccount(), TARGET, REF, body(true));
        } catch (VerticalApplyService.ApplyRefusedException refused) {
            assertThat(refused.code())
                    .as("the readiness gate is cleared, so this is no longer CONTROLLER_NOT_READY")
                    .isNotEqualTo("K8S_CONTROLLER_NOT_READY");
        }
    }

    // ---------------------------------------------------------------- fixtures

    private VerticalApplyService service(WorkloadAutoscalingProperties properties) {
        return new VerticalApplyService(properties, writePermission, List.of(provider), inventory,
                vpaReader, executor, policyService, actionRecorder, METRICS, FIXED);
    }

    private static VerticalApplyService.ApplyRefusedException catchRefusal(Runnable call) {
        try {
            call.run();
        } catch (VerticalApplyService.ApplyRefusedException refused) {
            return refused;
        }
        throw new AssertionError("the call was expected to be refused, and was not");
    }

    private static ApplyRequestDto body(boolean dryRun) {
        return new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", 25, 250L, 25, 250L)), "IN_PLACE", dryRun);
    }

    private static ClusterWorkloadInventory.Workload workload() {
        return new ClusterWorkloadInventory.Workload(REF, 2, false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", GUARANTEED_1CPU_1GI, Map.of())),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod("api-1", "uid-1"),
                        new WorkloadPods.Pod("api-2", "uid-2"))));
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
