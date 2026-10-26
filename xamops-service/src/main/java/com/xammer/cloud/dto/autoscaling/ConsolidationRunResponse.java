package com.xammer.cloud.dto.autoscaling;

import com.xammer.cloud.service.automation.workload.api.dto.NodeCostDto;

import java.util.List;

/**
 * GET /api/eks/autoscaling/{clusterName}/consolidation-runs: the consolidation controller's run
 * history (the LIST collector {@code autoscaling-consolidation-runs}), newest first, with the nodes
 * the most recent run actually removed priced by the cost engine.
 *
 * <p>
 * {@code realizedSavings} is {@code null} when there is no run, the stored history is unreadable, or
 * the newest run removed no node: a run that removed nothing never carries a dollar figure, and like
 * the plan response an unpriceable node nulls the projected total rather than reporting a partial sum.
 *
 * @param status          {@link #OK}, {@link #NO_RUNS} or {@link #UNREADABLE}
 * @param runs            the stored run history (newest first), or an empty list when none
 * @param realizedSavings the priced removed nodes of the newest run, or null when not priceable
 */
public record ConsolidationRunResponse(String status, List<ConsolidationRunDto> runs,
                                       RealizedSavings realizedSavings) {

    public static final String OK = "OK";
    public static final String NO_RUNS = "NO_RUNS";
    public static final String UNREADABLE = "UNREADABLE";

    /**
     * Monthly cost of the nodes the newest run actually removed (its {@code removed} nodes).
     *
     * @param realizedMonthlySavings the sum over every removed node, only when all of them are priced,
     *                               else null
     * @param pricedMonthlySubtotal  the sum over the priced removed nodes, or null when none is priced
     * @param pricedNodes            removed nodes with a monthly cost
     * @param unpricedNodes          removed nodes without one (each carries its reason)
     * @param nodes                  one entry per removed node, in run order
     */
    public record RealizedSavings(Double realizedMonthlySavings, Double pricedMonthlySubtotal, int pricedNodes,
                                  int unpricedNodes, List<NodeCostDto> nodes) {
    }

    public static ConsolidationRunResponse noRuns() {
        return new ConsolidationRunResponse(NO_RUNS, List.of(), null);
    }

    public static ConsolidationRunResponse unreadable() {
        return new ConsolidationRunResponse(UNREADABLE, List.of(), null);
    }
}
