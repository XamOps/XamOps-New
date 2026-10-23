package com.xammer.cloud.service.automation.workload.api.dto;

/**
 * One pod's real {@code $} cost share, additive on {@link WorkloadCostDto} (FEAT-002, GAP 2).
 *
 * <p>Same honesty contract as {@link WorkloadCostDto}: <strong>{@code null} on {@code monthlyCost}
 * means unavailable</strong> — the backend could not price this pod's node — rendered as a dash plus
 * the machine {@code reason}; a {@code 0} is only ever a true computed zero. The per-pod figures are
 * derived from the SAME attribution engine that prices the workload, so the workload's
 * {@code monthlyCost} equals the sum of its priced pods' {@code monthlyCost}.
 *
 * @param podName               {@code metadata.name} of the pod this share belongs to
 * @param nodeName              the node it runs on, or null when the pod had none
 * @param monthlyCost           this pod's current $/mo share, or null + reason when unavailable
 * @param reason                machine reason code when {@code monthlyCost} is null, else null
 * @param source                {@code PRICE_LIST} / {@code SPOT_HISTORY}, or null when unpriceable
 * @param capacityType          {@code on-demand} / {@code spot}, or null
 * @param capacityTypeInferred  true when on-demand was assumed from a missing label
 */
public record PodCostDto(String podName, String nodeName, Double monthlyCost, String reason,
                         String source, String capacityType, boolean capacityTypeInferred) {

    /** An unavailable pod cost carrying only a machine reason — never a fabricated number. */
    public static PodCostDto unavailable(String podName, String nodeName, String reason) {
        return new PodCostDto(podName, nodeName, null, reason, null, null, false);
    }
}
