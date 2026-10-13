package com.xammer.cloud.service.automation.workload.api.dto;

/**
 * The HPA apply endpoint's request body, modelled on {@link ApplyRequestDto} — the horizontal
 * counterpart to the vertical apply body.
 *
 * <pre>{@code
 * {
 *   "minReplicas": 1,
 *   "maxReplicas": 8,
 *   "targetUtilizationPct": 69,
 *   "dryRun": true
 * }
 * }</pre>
 *
 * <h2>Why every bound is a boxed type</h2>
 *
 * A <strong>{@code null} or omitted bound means "leave that field alone"</strong>, the same
 * convention {@link com.xammer.cloud.service.automation.workload.HpaDecision} uses: a diff of the
 * non-null fields against the current HPA is what an apply patches. That is not a convenience —
 * copying a whole HPA spec in would overwrite a bound the engine deliberately did not decide, and
 * would also clobber anything that moved between the read and the apply. At least one of the three
 * must be non-null, else there is nothing to change (checked in {@code HpaApplyService}, which
 * answers {@code 400 K8S_INVALID_REQUEST}).
 *
 * <p>{@code dryRun} is {@link Boolean} rather than {@code boolean} for the same reason as the
 * vertical body: it is <strong>mandatory</strong>, and a primitive would silently default an
 * omitted value to {@code false}, which is the real-apply case. An absent {@code dryRun} is a
 * {@code 400 K8S_INVALID_REQUEST} naming the field, never a live patch.
 *
 * <p>No validation lives here. {@code HpaApplyService} checks the numbers against the
 * <em>current</em> HPA and the engine's safety rules, which is the only place that can.
 *
 * @param minReplicas          proposed {@code spec.minReplicas}, or null to leave it alone
 * @param maxReplicas          proposed {@code spec.maxReplicas}, or null to leave it alone
 * @param targetUtilizationPct proposed CPU {@code averageUtilization}, or null to leave it alone
 * @param dryRun               mandatory; echoed in the response and on every audit record
 */
public record HpaApplyRequestDto(Integer minReplicas, Integer maxReplicas,
                                 Integer targetUtilizationPct, Boolean dryRun) {

    /** True when the body names no bound and no target to change — a no-op. */
    public boolean isEmpty() {
        return minReplicas == null && maxReplicas == null && targetUtilizationPct == null;
    }
}
