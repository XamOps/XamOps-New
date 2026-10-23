package com.xammer.cloud.service.automation.workload.cost;

import com.xammer.cloud.service.automation.workload.ContainerDecision;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.api.dto.CostSummaryDto;
import com.xammer.cloud.service.automation.workload.api.dto.PodCostDto;
import com.xammer.cloud.service.automation.workload.api.dto.WorkloadCostDto;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.WorkloadPods;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Attributes real {@code $} cost + savings to each workload, given an inventory and a per-node
 * {@link NodeRate} map (FEAT-001 §3.3–§3.5). Pure: no I/O, no AWS, no cluster.
 *
 * <p>The OpenCost-style split prices a pod as a share of its node, with the node's single {@code $/hr}
 * split into a CPU component and a memory component weighted {@code w_cpu : w_mem} over the node's
 * physical capacity in <strong>cores / GiB</strong> (the ratio is only meaningful in those units —
 * passing millis/bytes is the HIGH-1 trap). Shares and the per-unit savings divisor are in
 * millis/bytes. Every §3.5 fail-honest path yields {@code null} money + a machine reason, never a
 * fabricated or zero-as-real number; a true no-op recommendation yields {@code monthlySavings = 0}.
 */
@Service
public class WorkloadCostAttributionService {

    private final K8sCostProperties properties;

    public WorkloadCostAttributionService(K8sCostProperties properties) {
        this.properties = properties;
    }

    /**
     * One workload's cost annotation.
     *
     * @param workload  the inventory workload (its live pods carry {@code nodeName})
     * @param containers the engine's per-container decisions (current/recommended in millis/bytes)
     * @param nodesByName the node facts keyed by node name
     * @param rates     per-node-name resolved {@link NodeRate}
     */
    public WorkloadCostDto attribute(ClusterWorkloadInventory.Workload workload,
                                     List<ContainerDecision> containers,
                                     Map<String, ClusterWorkloadInventory.NodeFacts> nodesByName,
                                     Map<String, NodeRate> rates) {
        List<WorkloadPods.Pod> pods = workload.pods().pods();
        if (pods.isEmpty()) {
            // No live pods: there is nothing to price per-pod either, so the list stays null (never
            // a fabricated empty-but-priced breakdown).
            return WorkloadCostDto.unavailable(NodeRate.Reason.NODE_CAPACITY_UNKNOWN.name());
        }

        double hoursPerMonth = properties.hoursPerMonth();
        double totalCostPerHour = 0.0;
        double totalSavingsPerHour = 0.0;
        NodeRate exemplar = null;
        boolean inferredSeen = false;
        List<PodCostDto> podCosts = new ArrayList<>(pods.size());

        for (WorkloadPods.Pod pod : pods) {
            String nodeName = pod.nodeName();
            if (nodeName == null) {
                return WorkloadCostDto.unavailable(NodeRate.Reason.NODE_CAPACITY_UNKNOWN.name(),
                        unpriceablePods(pods, NodeRate.Reason.NODE_CAPACITY_UNKNOWN.name()));
            }
            ClusterWorkloadInventory.NodeFacts facts = nodesByName.get(nodeName);
            NodeRate rate = rates.get(nodeName);
            if (facts == null || rate == null || !rate.priceable()) {
                String reason = rate == null || rate.reason() == null
                        ? NodeRate.Reason.NODE_CAPACITY_UNKNOWN.name() : rate.reason().name();
                return WorkloadCostDto.unavailable(reason, unpriceablePods(pods, reason));
            }
            if (facts.capacityVcpus() == null || facts.capacityMemGiB() == null
                    || facts.capacityVcpus() <= 0 || facts.capacityMemGiB() <= 0) {
                return WorkloadCostDto.unavailable(NodeRate.Reason.NODE_CAPACITY_UNKNOWN.name(),
                        unpriceablePods(pods, NodeRate.Reason.NODE_CAPACITY_UNKNOWN.name()));
            }
            if (facts.allocatableCpuMillis() == null || facts.allocatableMemBytes() == null
                    || facts.allocatableCpuMillis() <= 0 || facts.allocatableMemBytes() <= 0) {
                return WorkloadCostDto.unavailable(NodeRate.Reason.NODE_CAPACITY_UNKNOWN.name(),
                        unpriceablePods(pods, NodeRate.Reason.NODE_CAPACITY_UNKNOWN.name()));
            }

            exemplar = rate;
            inferredSeen = inferredSeen || rate.capacityTypeInferred();

            // Guard BOTH capacities > 0 before computing the split denominator (§3.3).
            double wCpu = properties.weightCpu();
            double wMem = properties.weightMem();
            double denom = wCpu * facts.capacityVcpus() + wMem * facts.capacityMemGiB();
            double cpuComponent = rate.usdPerHour() * (wCpu * facts.capacityVcpus()) / denom;
            double memComponent = rate.usdPerHour() * (wMem * facts.capacityMemGiB()) / denom;

            double cpuUnitPerHour = cpuComponent / facts.allocatableCpuMillis();
            double memUnitPerHour = memComponent / facts.allocatableMemBytes();

            // This pod's current cost: Σ containers' request share of the node.
            double podCostPerHour = 0.0;
            for (ContainerDecision container : containers) {
                ResourceEnvelope current = container.current();
                int cpuReq = current.cpuRequestMillis() == null ? 0 : current.cpuRequestMillis();
                long memReq = current.memRequestBytes() == null ? 0 : current.memRequestBytes();
                podCostPerHour += cpuReq * cpuUnitPerHour + memReq * memUnitPerHour;

                // Freed capacity from the recommendation (before - after), clamped at 0.
                if (container.recommended() != null) {
                    long cpuFreed = freed(current.cpuRequestMillis(),
                            container.recommended().cpuRequestMillis());
                    long memFreed = freed(current.memRequestBytes(),
                            container.recommended().memRequestBytes());
                    totalSavingsPerHour += cpuFreed * cpuUnitPerHour + memFreed * memUnitPerHour;
                }
            }
            totalCostPerHour += podCostPerHour;

            // This pod's real engine-derived share, carrying the exemplar provenance. Summing these
            // reproduces the workload total exactly (same hoursPerMonth, same arithmetic).
            podCosts.add(new PodCostDto(pod.name(), nodeName, podCostPerHour * hoursPerMonth, null,
                    rate.source() == null ? null : rate.source().name(), rate.capacityType(),
                    rate.capacityTypeInferred()));
        }

        double monthlyCost = totalCostPerHour * hoursPerMonth;
        double monthlySavings = totalSavingsPerHour * hoursPerMonth;

        String source = exemplar.source() == null ? null : exemplar.source().name();
        return new WorkloadCostDto(monthlyCost, monthlySavings, null, source,
                exemplar.capacityType(), inferredSeen, exemplar.note(), List.copyOf(podCosts));
    }

    /**
     * The cluster roll-up over already-attributed workloads: Σ over the priceable ones.
     */
    public CostSummaryDto summary(List<WorkloadCostDto> costs) {
        int priced = 0;
        int unpriced = 0;
        double savings = 0.0;
        double cost = 0.0;
        for (WorkloadCostDto c : costs) {
            if (c != null && c.monthlyCost() != null) {
                priced++;
                cost += c.monthlyCost();
                if (c.monthlySavings() != null) {
                    savings += c.monthlySavings();
                }
            } else {
                unpriced++;
            }
        }
        boolean anyPriced = priced > 0;
        // The two roll-up lists are built in VerticalAutomationReadService.evaluate(...) and attached
        // via CostSummaryDto.withRollups(...); summary(...) owns only the workload-Σ, so it passes null.
        return new CostSummaryDto("USD", properties.hoursPerMonth(),
                anyPriced ? savings : null,
                anyPriced ? null : NodeRate.Reason.NODE_CAPACITY_UNKNOWN.name(),
                anyPriced ? cost : null,
                priced, unpriced,
                null, null,
                // No prior-period source exists yet (no cost snapshot / history persistence), so the
                // previousClusterMonthlyCost is null: the Trend chip stays honestly dashed rather than
                // comparing against a fabricated value.
                null);
    }

    /** On a fail-honest path, one {@link PodCostDto#unavailable} per pod — never a fabricated number. */
    private static List<PodCostDto> unpriceablePods(List<WorkloadPods.Pod> pods, String reason) {
        List<PodCostDto> unavailable = new ArrayList<>(pods.size());
        for (WorkloadPods.Pod pod : pods) {
            unavailable.add(PodCostDto.unavailable(pod.name(), pod.nodeName(), reason));
        }
        return List.copyOf(unavailable);
    }

    private static long freed(Integer before, Integer after) {
        if (before == null) {
            return 0;
        }
        int afterValue = after == null ? 0 : after;
        return Math.max(0L, (long) before - afterValue);
    }

    private static long freed(Long before, Long after) {
        if (before == null) {
            return 0;
        }
        long afterValue = after == null ? 0 : after;
        return Math.max(0L, before - afterValue);
    }
}
