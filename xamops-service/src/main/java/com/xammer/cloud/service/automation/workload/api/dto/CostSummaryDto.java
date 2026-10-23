package com.xammer.cloud.service.automation.workload.api.dto;

import java.util.List;

/**
 * The cluster-level cost roll-up, additive on {@link VerticalEngineResponse}.
 *
 * <p>{@code monthlySavings} / {@code clusterMonthlyCost} are {@code null} when nothing was priceable
 * (with {@code monthlySavingsUnavailableReason} set), never a fabricated 0. {@code pricedWorkloads} /
 * {@code unpricedWorkloads} support an honest "n of m priced" line.
 *
 * <p>{@code nodeCosts} / {@code namespaceCosts} are the additive FEAT-001 roll-ups. They are not
 * built by {@link #summary} (which has no node facts or per-workload namespaces); the one place that
 * constructs this record passes them as {@code null}, and
 * {@code VerticalAutomationReadService.evaluate(...)} attaches the real lists afterwards via
 * {@link #withRollups}. {@code nodeCosts} is {@code null} <strong>only</strong> when the cost
 * feature is off; when on it is the <em>full fleet</em> (non-empty, possibly all-dash rows).
 *
 * @param currency                        always {@code "USD"}
 * @param hoursPerMonth                   the hours/month basis used (730 by default)
 * @param monthlySavings                  Σ priceable workloads' monthly savings, or null
 * @param monthlySavingsUnavailableReason set when {@code monthlySavings} is null
 * @param clusterMonthlyCost              Σ priceable workloads' current monthly cost, or null
 * @param pricedWorkloads                 how many workloads contributed a real number
 * @param unpricedWorkloads               how many fell to a dash
 * @param nodeCosts                       the full-fleet per-node roll-up, or null when the feature is off
 * @param namespaceCosts                  the per-namespace roll-up, or null when the feature is off
 * @param previousClusterMonthlyCost      the prior-period {@code clusterMonthlyCost} the Overview
 *                                        hero's Trend chip compares against, or {@code null}. It is
 *                                        now sourced from the persisted {@code cluster_cost_snapshot}
 *                                        prior period: {@code VerticalAutomationReadService
 *                                        .evaluate(...)} reads the newest snapshot at or before the
 *                                        prior-lookback cutoff for the cluster and attaches it via
 *                                        {@link #withPreviousClusterMonthlyCost} right after
 *                                        {@link #withRollups}. {@link #summary} still passes
 *                                        {@code null} (it has no cluster context), and it stays
 *                                        {@code null} whenever no prior snapshot exists — never
 *                                        computed or fabricated here.
 */
public record CostSummaryDto(String currency, double hoursPerMonth, Double monthlySavings,
                             String monthlySavingsUnavailableReason, Double clusterMonthlyCost,
                             int pricedWorkloads, int unpricedWorkloads,
                             List<NodeCostDto> nodeCosts, List<NamespaceCostDto> namespaceCosts,
                             Double previousClusterMonthlyCost) {

    /** Returns a copy carrying the fleet + namespace roll-ups; all other fields unchanged. */
    public CostSummaryDto withRollups(List<NodeCostDto> nodeCosts,
                                      List<NamespaceCostDto> namespaceCosts) {
        return new CostSummaryDto(currency, hoursPerMonth, monthlySavings,
                monthlySavingsUnavailableReason, clusterMonthlyCost, pricedWorkloads, unpricedWorkloads,
                nodeCosts, namespaceCosts, previousClusterMonthlyCost);
    }

    /**
     * Returns a copy carrying the prior-period baseline read back from the persisted
     * {@code cluster_cost_snapshot}; every other field is unchanged. {@code previous} is
     * {@code null} when no prior snapshot exists — the hero's Trend chip then stays an honest
     * em-dash rather than a fabricated comparison.
     */
    public CostSummaryDto withPreviousClusterMonthlyCost(Double previous) {
        return new CostSummaryDto(currency, hoursPerMonth, monthlySavings,
                monthlySavingsUnavailableReason, clusterMonthlyCost, pricedWorkloads, unpricedWorkloads,
                nodeCosts, namespaceCosts, previous);
    }
}
