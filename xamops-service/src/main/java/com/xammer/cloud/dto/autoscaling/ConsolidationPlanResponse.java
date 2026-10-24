package com.xammer.cloud.dto.autoscaling;

import com.xammer.cloud.service.automation.workload.api.dto.NodeCostDto;

import java.util.List;

/**
 * GET /api/eks/autoscaling/{clusterName}/consolidation-plan: the latest look-only plan, with the
 * removed nodes priced by the cost engine when the plan is current.
 *
 * <p>
 * {@code savings} is {@code null} unless {@code freshness} is {@link #CURRENT}: a stale, missing or
 * unreadable plan never carries a dollar figure.
 *
 * @param freshness   {@link #CURRENT}, {@link #STALE}, {@link #NO_PLAN} or {@link #UNREADABLE}
 * @param generatedAt the plan's own generatedAt, or null without a plan
 * @param ageSeconds  seconds since generatedAt, or null when there is no parseable time
 * @param plan        the stored plan, or null for NO_PLAN / UNREADABLE
 * @param savings     the priced removed nodes, or null when not CURRENT
 */
public record ConsolidationPlanResponse(String freshness, String generatedAt, Long ageSeconds,
                                        ConsolidationPlanDto plan, Savings savings) {

    public static final String CURRENT = "CURRENT";
    public static final String STALE = "STALE";
    public static final String NO_PLAN = "NO_PLAN";
    public static final String UNREADABLE = "UNREADABLE";

    /**
     * Monthly cost of the nodes the plan's moves would remove.
     *
     * @param projectedMonthlySavings the sum over every move node, only when all of them are priced
     *                                (0.0 for a plan with no moves), else null
     * @param pricedMonthlySubtotal   the sum over the priced move nodes, or null when none is priced
     * @param pricedNodes             move nodes with a monthly cost
     * @param unpricedNodes           move nodes without one (each carries its reason)
     * @param nodes                   one entry per move, in plan order
     */
    public record Savings(Double projectedMonthlySavings, Double pricedMonthlySubtotal, int pricedNodes,
                          int unpricedNodes, List<NodeCostDto> nodes) {
    }

    public static ConsolidationPlanResponse noPlan() {
        return new ConsolidationPlanResponse(NO_PLAN, null, null, null, null);
    }

    public static ConsolidationPlanResponse unreadable() {
        return new ConsolidationPlanResponse(UNREADABLE, null, null, null, null);
    }
}
