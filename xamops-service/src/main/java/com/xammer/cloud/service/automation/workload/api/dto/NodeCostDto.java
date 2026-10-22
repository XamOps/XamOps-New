package com.xammer.cloud.service.automation.workload.api.dto;

/**
 * One fleet node's resolved monthly {@code $}, additive on {@link CostSummaryDto} (FEAT-001).
 *
 * <p><strong>{@code monthlyCost == null} means the node is unpriceable</strong> — rendered as a dash
 * plus {@code reason} in the UI, mirroring {@link WorkloadCostDto}'s contract — never a fabricated
 * {@code 0}. The roll-up is built over the <em>full fleet</em> ({@code cluster.nodesByName()}) so a
 * node hosting only DaemonSet/system pods, or no evaluated pod at all, still appears — priced when
 * AWS can price its shape, dashed with its {@code reason} otherwise.
 *
 * @param nodeName             the node's name
 * @param instanceType         the EC2 instance type the node reported, or null
 * @param capacityType         {@code on-demand} / {@code spot}, or null when unknown
 * @param capacityTypeInferred true when no capacity-type label was present and on-demand was assumed
 *                             — priced but NOT authoritative (the UI shows "assumed on-demand")
 * @param monthlyCost          {@code usdPerHour × hoursPerMonth} when priceable, else null
 * @param source               {@code PRICE_LIST} / {@code SPOT_HISTORY}, or null when unpriceable
 * @param reason               the machine reason code when {@code monthlyCost} is null, else null
 * @param note                 provenance, suitable for a tooltip
 */
public record NodeCostDto(String nodeName, String instanceType, String capacityType,
                          boolean capacityTypeInferred, Double monthlyCost, String source,
                          String reason, String note) {
}
