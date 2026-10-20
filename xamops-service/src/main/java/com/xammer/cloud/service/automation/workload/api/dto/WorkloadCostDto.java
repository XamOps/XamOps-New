package com.xammer.cloud.service.automation.workload.api.dto;

/**
 * One workload's cost + savings, additive on {@link VerticalWorkloadDto}.
 *
 * <p><strong>{@code null} on a money field means unavailable</strong> (rendered as a dash + the
 * {@code reason}); a {@code 0} is emitted only for a true computed zero, e.g. a recommendation that
 * frees nothing. {@code capacityTypeInferred == true} means the node carried no capacity-type label,
 * so the price is a real on-demand figure but NOT authoritative — the UI shows an "assumed on-demand"
 * qualifier.
 *
 * @param monthlyCost           current $/mo, or null + reason
 * @param monthlySavings        $/mo freed by the recommendation, or null + reason
 * @param reason                machine reason code when either money field is null, else null
 * @param source                {@code PRICE_LIST} / {@code SPOT_HISTORY}, or null when unpriceable
 * @param capacityType          {@code on-demand} / {@code spot}, or null
 * @param capacityTypeInferred  true when on-demand was assumed from a missing label
 * @param note                  provenance, for a tooltip
 */
public record WorkloadCostDto(Double monthlyCost, Double monthlySavings, String reason, String source,
                              String capacityType, boolean capacityTypeInferred, String note) {

    /** An unavailable cost carrying only a machine reason — never a fabricated number. */
    public static WorkloadCostDto unavailable(String reason) {
        return new WorkloadCostDto(null, null, reason, null, null, false, null);
    }
}
