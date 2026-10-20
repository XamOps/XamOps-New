package com.xammer.cloud.service.automation.workload.api.dto;

/**
 * The cluster-level cost roll-up, additive on {@link VerticalEngineResponse}.
 *
 * <p>{@code monthlySavings} / {@code clusterMonthlyCost} are {@code null} when nothing was priceable
 * (with {@code monthlySavingsUnavailableReason} set), never a fabricated 0. {@code pricedWorkloads} /
 * {@code unpricedWorkloads} support an honest "n of m priced" line.
 *
 * @param currency                        always {@code "USD"}
 * @param hoursPerMonth                   the hours/month basis used (730 by default)
 * @param monthlySavings                  Σ priceable workloads' monthly savings, or null
 * @param monthlySavingsUnavailableReason set when {@code monthlySavings} is null
 * @param clusterMonthlyCost              Σ priceable workloads' current monthly cost, or null
 * @param pricedWorkloads                 how many workloads contributed a real number
 * @param unpricedWorkloads               how many fell to a dash
 */
public record CostSummaryDto(String currency, double hoursPerMonth, Double monthlySavings,
                             String monthlySavingsUnavailableReason, Double clusterMonthlyCost,
                             int pricedWorkloads, int unpricedWorkloads) {
}
