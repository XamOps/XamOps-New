package com.xammer.cloud.service.automation.workload.api.dto;

/**
 * One namespace's monthly {@code $} roll-up, additive on {@link CostSummaryDto} (FEAT-001).
 *
 * <p>Grouped from the evaluated {@link VerticalWorkloadDto}s by {@code ref().namespace()} (the
 * namespace lives on the workload ref, not on {@link WorkloadCostDto}). {@code monthlyCost} is the
 * Σ of the namespace's priceable workloads; it is {@code null} — a dash + reason in the UI, never a
 * fabricated {@code 0} — when no workload in the namespace was priceable.
 *
 * @param namespace         the namespace name
 * @param monthlyCost       Σ priceable workloads' monthly cost, or null when none was priceable
 * @param pricedWorkloads   how many workloads in the namespace contributed a real number
 * @param unpricedWorkloads how many fell to a dash
 */
public record NamespaceCostDto(String namespace, Double monthlyCost, int pricedWorkloads,
                               int unpricedWorkloads) {
}
