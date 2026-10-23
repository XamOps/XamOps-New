package com.xammer.cloud.service.automation.workload.cost;

import com.xammer.cloud.service.automation.workload.ChangeKind;
import com.xammer.cloud.service.automation.workload.ContainerDecision;
import com.xammer.cloud.service.automation.workload.ContainerReason;
import com.xammer.cloud.service.automation.workload.QosClass;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.api.dto.CostSummaryDto;
import com.xammer.cloud.service.automation.workload.api.dto.WorkloadCostDto;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.WorkloadPods;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The §3.3–§3.5 cost attribution math, in pure arithmetic over fixtures.
 */
class WorkloadCostAttributionServiceTest {

    private static final long GI = 1024L * 1024 * 1024;
    private static final WorkloadRef REF = new WorkloadRef("team-a", "Deployment", "api", "uid-1");
    private static final String NODE = "node-1";

    private final WorkloadCostAttributionService service =
            new WorkloadCostAttributionService(K8sCostProperties.enabledDefaults());

    /** m5.large-ish node: 2 vCPU / 8 GiB capacity, 2000m / 8Gi allocatable, $0.096/hr on-demand. */
    private static ClusterWorkloadInventory.NodeFacts node() {
        return new ClusterWorkloadInventory.NodeFacts("m5.large", "on-demand", false, "ap-south-1a",
                false, 2.0, 8.0, 2000, 8 * GI);
    }

    private static ClusterWorkloadInventory.Workload workload(int pods) {
        List<WorkloadPods.Pod> podList = new java.util.ArrayList<>();
        for (int i = 0; i < pods; i++) {
            podList.add(new WorkloadPods.Pod("api-" + i, "uid-" + i, true, NODE));
        }
        return new ClusterWorkloadInventory.Workload(REF, pods, false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("app",
                        new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI), Map.of())),
                new WorkloadPods(REF, podList));
    }

    private static ContainerDecision decision(ResourceEnvelope current, ResourceEnvelope recommended) {
        if (recommended == null) {
            return new ContainerDecision("app", QosClass.GUARANTEED, current, null,
                    List.of(ContainerReason.WITHIN_HYSTERESIS), ChangeKind.NONE);
        }
        return new ContainerDecision("app", QosClass.GUARANTEED, current, recommended,
                List.of(ContainerReason.LIMIT_MOVED), ChangeKind.DECREASE);
    }

    @Test
    @DisplayName("the split is by cores/GiB (NOT millis/bytes — the HIGH-1 trap) and savings price freed capacity")
    void splitAndSavings() {
        // Node $0.096/hr; weights 1.0 cores : 0.5 GiB; capacity 2 vCPU, 8 GiB.
        // denom = 1*2 + 0.5*8 = 6; cpuComponent = 0.096*2/6 = 0.032; memComponent = 0.096*4/6 = 0.064.
        // Pod requests 1000m / 4Gi; cpuUnit = 0.032/2000; memUnit = 0.064/(8Gi).
        ResourceEnvelope current = new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI);
        ResourceEnvelope recommended = new ResourceEnvelope(500, 1000, 2L * GI, 4L * GI);

        WorkloadCostDto cost = service.attribute(workload(1), List.of(decision(current, recommended)),
                Map.of(NODE, node()), Map.of(NODE, onDemandRate()));

        // Current cost/hr: 1000*(0.032/2000) + 4Gi*(0.064/8Gi) = 0.016 + 0.032 = 0.048 -> *730 = 35.04.
        assertThat(cost.monthlyCost()).isCloseTo(35.04, org.assertj.core.data.Offset.offset(0.01));
        // Freed 500m + 2Gi: 500*(0.032/2000) + 2Gi*(0.064/8Gi) = 0.008 + 0.016 = 0.024 -> *730 = 17.52.
        assertThat(cost.monthlySavings()).isCloseTo(17.52, org.assertj.core.data.Offset.offset(0.01));
        assertThat(cost.reason()).isNull();
        assertThat(cost.source()).isEqualTo("PRICE_LIST");
        assertThat(cost.capacityTypeInferred()).isFalse();
    }

    @Test
    @DisplayName("per-pod shares sum across the workload's live pods (×pods)")
    void perPodSum() {
        ResourceEnvelope current = new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI);
        WorkloadCostDto one = service.attribute(workload(1), List.of(decision(current, null)),
                Map.of(NODE, node()), Map.of(NODE, onDemandRate()));
        WorkloadCostDto three = service.attribute(workload(3), List.of(decision(current, null)),
                Map.of(NODE, node()), Map.of(NODE, onDemandRate()));

        assertThat(three.monthlyCost()).isCloseTo(one.monthlyCost() * 3, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("a true no-op recommendation yields monthlySavings == 0 (a real zero, not a dash)")
    void noOpRecommendationIsZeroSavings() {
        ResourceEnvelope current = new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI);
        WorkloadCostDto cost = service.attribute(workload(1), List.of(decision(current, null)),
                Map.of(NODE, node()), Map.of(NODE, onDemandRate()));

        assertThat(cost.monthlySavings()).isEqualTo(0.0);
        assertThat(cost.monthlyCost()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("a recommendation that increases a request contributes 0 savings, never negative")
    void negativeDeltaContributesZero() {
        ResourceEnvelope current = new ResourceEnvelope(500, 1000, 2L * GI, 4L * GI);
        ResourceEnvelope recommended = new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI);
        WorkloadCostDto cost = service.attribute(workload(1), List.of(decision(current, recommended)),
                Map.of(NODE, node()), Map.of(NODE, onDemandRate()));

        assertThat(cost.monthlySavings()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("an unpriceable node rate yields null money + the machine reason")
    void unpriceableRateIsReason() {
        ResourceEnvelope current = new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI);
        NodeRate unknown = NodeRate.unpriceable(NodeRate.Reason.UNKNOWN_INSTANCE_TYPE, "x", null,
                null, "ap-south-1a", "ap-south-1");

        WorkloadCostDto cost = service.attribute(workload(1), List.of(decision(current, null)),
                Map.of(NODE, node()), Map.of(NODE, unknown));

        assertThat(cost.monthlyCost()).isNull();
        assertThat(cost.monthlySavings()).isNull();
        assertThat(cost.reason()).isEqualTo("UNKNOWN_INSTANCE_TYPE");
    }

    @Test
    @DisplayName("capacity unreadable (null vcpus) yields NODE_CAPACITY_UNKNOWN, never a divide-by-zero")
    void unknownCapacityIsReason() {
        ClusterWorkloadInventory.NodeFacts noCapacity = new ClusterWorkloadInventory.NodeFacts(
                "m5.large", "on-demand", false, "ap-south-1a", false, null, null, 2000, 8 * GI);
        ResourceEnvelope current = new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI);

        WorkloadCostDto cost = service.attribute(workload(1), List.of(decision(current, null)),
                Map.of(NODE, noCapacity), Map.of(NODE, onDemandRate()));

        assertThat(cost.monthlyCost()).isNull();
        assertThat(cost.reason()).isEqualTo("NODE_CAPACITY_UNKNOWN");
    }

    @Test
    @DisplayName("a workload with no live pods is unavailable (dash + reason), never 0")
    void noPodsIsUnavailable() {
        ClusterWorkloadInventory.Workload noPods = new ClusterWorkloadInventory.Workload(REF, 0, false,
                false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("app",
                        new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI), Map.of())),
                new WorkloadPods(REF, List.of()));

        WorkloadCostDto cost = service.attribute(noPods, List.of(), Map.of(NODE, node()),
                Map.of(NODE, onDemandRate()));

        assertThat(cost.monthlyCost()).isNull();
        assertThat(cost.reason()).isEqualTo("NODE_CAPACITY_UNKNOWN");
    }

    @Test
    @DisplayName("a pod whose nodeName is null is unavailable, never silently under-counted")
    void nullNodeNameIsUnavailable() {
        ClusterWorkloadInventory.Workload detached = new ClusterWorkloadInventory.Workload(REF, 1, false,
                false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("app",
                        new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI), Map.of())),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod("api-0", "uid-0", true, null))));

        WorkloadCostDto cost = service.attribute(detached,
                List.of(decision(new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI), null)),
                Map.of(NODE, node()), Map.of(NODE, onDemandRate()));

        assertThat(cost.monthlyCost()).isNull();
        assertThat(cost.reason()).isEqualTo("NODE_CAPACITY_UNKNOWN");
    }

    @Test
    @DisplayName("capacityTypeInferred is carried through when the node had no label")
    void inferredCarried() {
        NodeRate inferred = NodeRate.inferred(0.096, NodeRate.Source.PRICE_LIST, "note", "m5.large",
                "ap-south-1a", "ap-south-1");
        ResourceEnvelope current = new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI);

        WorkloadCostDto cost = service.attribute(workload(1), List.of(decision(current, null)),
                Map.of(NODE, node()), Map.of(NODE, inferred));

        assertThat(cost.capacityTypeInferred()).isTrue();
    }

    @Test
    @DisplayName("the roll-up sums priceable workloads and counts priced vs unpriced")
    void rollUp() {
        WorkloadCostDto priced = new WorkloadCostDto(10.0, 3.0, null, "PRICE_LIST", "on-demand", false, "n");
        WorkloadCostDto priced2 = new WorkloadCostDto(20.0, 5.0, null, "PRICE_LIST", "on-demand", false, "n");
        WorkloadCostDto unpriced = WorkloadCostDto.unavailable("NO_LIVE_PRICE");

        CostSummaryDto summary = service.summary(List.of(priced, priced2, unpriced));

        assertThat(summary.clusterMonthlyCost()).isEqualTo(30.0);
        assertThat(summary.monthlySavings()).isEqualTo(8.0);
        assertThat(summary.pricedWorkloads()).isEqualTo(2);
        assertThat(summary.unpricedWorkloads()).isEqualTo(1);
        assertThat(summary.currency()).isEqualTo("USD");
        assertThat(summary.nodeCosts())
                .as("summary() owns only the workload-Σ; the fleet roll-up is attached later in evaluate()")
                .isNull();
        assertThat(summary.namespaceCosts())
                .as("summary() owns only the workload-Σ; the namespace roll-up is attached later in evaluate()")
                .isNull();
        assertThat(summary.previousClusterMonthlyCost())
                .as("GAP 1: no prior-period source exists yet, so the prior is null (never fabricated)")
                .isNull();
    }

    @Test
    @DisplayName("a roll-up with nothing priceable is null + reason, never a fabricated 0")
    void rollUpNothingPriceable() {
        CostSummaryDto summary = service.summary(List.of(WorkloadCostDto.unavailable("NO_LIVE_PRICE")));

        assertThat(summary.clusterMonthlyCost()).isNull();
        assertThat(summary.monthlySavings()).isNull();
        assertThat(summary.monthlySavingsUnavailableReason()).isNotNull();
        assertThat(summary.pricedWorkloads()).isZero();
    }

    private static NodeRate onDemandRate() {
        return NodeRate.priceable(0.096, NodeRate.Source.PRICE_LIST, "note", "m5.large", "on-demand",
                "ap-south-1a", "ap-south-1");
    }
}
