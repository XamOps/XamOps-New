package com.xammer.cloud.service.automation.workload.api.dto;

import java.util.List;

/**
 * One workload's cost + savings, additive on {@link VerticalWorkloadDto}.
 *
 * <p><strong>{@code null} on a money field means unavailable</strong> (rendered as a dash + the
 * {@code reason}); a {@code 0} is emitted only for a true computed zero, e.g. a recommendation that
 * frees nothing. {@code capacityTypeInferred == true} means the node carried no capacity-type label,
 * so the price is a real on-demand figure but NOT authoritative — the UI shows an "assumed on-demand"
 * qualifier.
 *
 * <p>{@code pods} is the per-pod breakdown the SAME attribution engine computes internally before it
 * collapses to the workload total (FEAT-002, GAP 2): each {@link PodCostDto} is either a real
 * engine-derived {@code monthlyCost} or null + reason, and the workload's {@code monthlyCost} equals
 * the sum of its priced pods. It is {@code null} on the pre-cost / unavailable path (and absent from
 * a build that predates the field), never a fabricated or empty stand-in.
 *
 * @param monthlyCost           current $/mo, or null + reason
 * @param monthlySavings        $/mo freed by the recommendation, or null + reason
 * @param reason                machine reason code when either money field is null, else null
 * @param source                {@code PRICE_LIST} / {@code SPOT_HISTORY}, or null when unpriceable
 * @param capacityType          {@code on-demand} / {@code spot}, or null
 * @param capacityTypeInferred  true when on-demand was assumed from a missing label
 * @param note                  provenance, for a tooltip
 * @param pods                  per-pod cost shares, or null on the unavailable / pre-cost path
 */
public record WorkloadCostDto(Double monthlyCost, Double monthlySavings, String reason, String source,
                              String capacityType, boolean capacityTypeInferred, String note,
                              List<PodCostDto> pods) {

    /** The pre-per-pod shape: {@code pods} delegates to null, for call sites built before FEAT-002. */
    public WorkloadCostDto(Double monthlyCost, Double monthlySavings, String reason, String source,
                           String capacityType, boolean capacityTypeInferred, String note) {
        this(monthlyCost, monthlySavings, reason, source, capacityType, capacityTypeInferred, note, null);
    }

    /** An unavailable cost carrying only a machine reason — never a fabricated number. */
    public static WorkloadCostDto unavailable(String reason) {
        return new WorkloadCostDto(null, null, reason, null, null, false, null, null);
    }

    /** An unavailable cost carrying the per-pod unavailable list alongside the machine reason. */
    public static WorkloadCostDto unavailable(String reason, List<PodCostDto> pods) {
        return new WorkloadCostDto(null, null, reason, null, null, false, null, pods);
    }
}
