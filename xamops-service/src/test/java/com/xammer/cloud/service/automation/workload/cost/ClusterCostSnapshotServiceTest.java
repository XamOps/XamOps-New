package com.xammer.cloud.service.automation.workload.cost;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.ClusterCostSnapshot;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ClusterCostSnapshotRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationReadService;
import com.xammer.cloud.service.automation.workload.api.dto.CostSummaryDto;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The scheduled cluster-cost-snapshot loop's hard limits, with the read service, the repositories
 * and the clock all mocked / pinned so nothing reaches AWS or a cluster. Every assertion here is
 * about <em>whether</em> and <em>which</em> snapshot row it writes, and that it never fabricates a
 * cost.
 */
class ClusterCostSnapshotServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC);

    private ClusterCostSnapshotProperties snapshotProperties;
    private K8sCostProperties costProperties;
    private VerticalAutomationReadService readService;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private CloudAccountRepository cloudAccountRepository;
    private ClusterCostSnapshotRepository snapshotRepository;

    @BeforeEach
    void setUp() {
        // Both gates on by default; individual tests flip one off.
        snapshotProperties = new ClusterCostSnapshotProperties(true, 1440, 24, 12);
        costProperties = K8sCostProperties.enabledDefaults();
        readService = mock(VerticalAutomationReadService.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);
        snapshotRepository = mock(ClusterCostSnapshotRepository.class);

        when(clusterConfigRepository.findAll()).thenReturn(List.of(config(42L)));
        when(cloudAccountRepository.findById(3L)).thenReturn(Optional.of(account(3L)));
        when(readService.read(any(), any(), any())).thenReturn(responseWithCost(814.0));
    }

    // ---------------------------------------------------------------- the two gates

    @Test
    @DisplayName("the snapshot master switch off means the loop does nothing: no read, no save")
    void snapshotSwitchOffDoesNothing() {
        snapshotProperties = new ClusterCostSnapshotProperties(false, 1440, 24, 12);

        service().runCycle();

        verifyNoInteractions(clusterConfigRepository, readService, snapshotRepository);
    }

    @Test
    @DisplayName("the cost feature off means the loop does nothing, even with the snapshot switch on")
    void costFeatureOffDoesNothing() {
        costProperties = K8sCostProperties.disabled();

        service().runCycle();

        verifyNoInteractions(clusterConfigRepository, readService, snapshotRepository);
    }

    // ---------------------------------------------------------------- write / skip

    @Test
    @DisplayName("a real clusterMonthlyCost is written exactly once with that value and the fixed-clock instant")
    void aRealCostIsSnapshottedWithTheClockInstant() {
        service().runCycle();

        ArgumentCaptor<ClusterCostSnapshot> saved = ArgumentCaptor.forClass(ClusterCostSnapshot.class);
        verify(snapshotRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getClusterConfigId()).isEqualTo(42L);
        assertThat(saved.getValue().getClusterMonthlyCost()).isEqualTo(814.0);
        assertThat(saved.getValue().getCapturedAt()).isEqualTo(Instant.parse("2026-10-22T12:00:00Z"));
    }

    @Test
    @DisplayName("a null cost() writes no row")
    void aNullCostSummaryWritesNoRow() {
        when(readService.read(any(), any(), any())).thenReturn(responseWithCost(null, false));

        service().runCycle();

        verify(snapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("a null clusterMonthlyCost() writes no row (never a fabricated 0)")
    void aNullClusterMonthlyCostWritesNoRow() {
        when(readService.read(any(), any(), any())).thenReturn(responseWithCost(null, true));

        service().runCycle();

        verify(snapshotRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- per-cluster failure isolation

    @Test
    @DisplayName("one cluster whose read throws does not stop the others from being snapshotted")
    void oneClusterFailingDoesNotStopTheOthers() {
        KubernetesClusterConfig first = config(42L);
        KubernetesClusterConfig second = config(43L);
        second.setCloudAccountId(4L);
        when(clusterConfigRepository.findAll()).thenReturn(List.of(first, second));
        when(cloudAccountRepository.findById(4L)).thenReturn(Optional.of(account(4L)));
        // The first cluster's read blows up; the second returns a real cost.
        when(readService.read(any(), any(), any()))
                .thenThrow(new RuntimeException("the cluster is unreachable"))
                .thenReturn(responseWithCost(500.0));

        service().runCycle();

        ArgumentCaptor<ClusterCostSnapshot> saved = ArgumentCaptor.forClass(ClusterCostSnapshot.class);
        verify(snapshotRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getClusterConfigId())
                .as("the second cluster is still snapshotted after the first one failed")
                .isEqualTo(43L);
        assertThat(saved.getValue().getClusterMonthlyCost()).isEqualTo(500.0);
    }

    // ---------------------------------------------------------------- fixtures

    private ClusterCostSnapshotService service() {
        return new ClusterCostSnapshotService(snapshotProperties, costProperties, readService,
                clusterConfigRepository, cloudAccountRepository, snapshotRepository, FIXED);
    }

    private static KubernetesClusterConfig config(Long id) {
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(id);
        config.setCloudAccountId(3L);
        config.setClusterName("xamops-test-" + id);
        config.setRegion("ap-south-1");
        return config;
    }

    private static CloudAccount account(Long id) {
        Client client = new Client();
        client.setId(7L);
        CloudAccount account = new CloudAccount();
        account.setId(id);
        account.setProvider("AWS");
        account.setAwsAccountId("102227938259");
        account.setAccessType("read-write");
        account.setClient(client);
        return account;
    }

    private static VerticalEngineResponse.EngineStatus engineStatus() {
        return new VerticalEngineResponse.EngineStatus(true, true, "CONFIGURED", "DIRECT",
                new VerticalEngineResponse.ExecutionPathDetail(
                        new VerticalEngineResponse.PathAvailability(true, null),
                        new VerticalEngineResponse.AgentAvailability(false, "AGENT_NOT_INSTALLED", null)),
                new VerticalEngineResponse.MetricsSource(true, "prometheus"),
                VerticalEngineResponse.LastCycle.never());
    }

    /** A response whose cost summary carries a real (or null) clusterMonthlyCost. */
    private static VerticalEngineResponse responseWithCost(Double clusterMonthlyCost) {
        return responseWithCost(clusterMonthlyCost, true);
    }

    /**
     * @param clusterMonthlyCost the cost value (may be null)
     * @param withCostSummary    when false, {@code cost()} itself is null
     */
    private static VerticalEngineResponse responseWithCost(Double clusterMonthlyCost,
                                                           boolean withCostSummary) {
        CostSummaryDto cost = withCostSummary
                ? new CostSummaryDto("USD", 730, null, null, clusterMonthlyCost, 0, 0, null, null, null)
                : null;
        return new VerticalEngineResponse(engineStatus(), List.of(), List.of(), java.util.Map.of(),
                false, cost);
    }
}
