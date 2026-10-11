package com.xammer.cloud.service.automation.workload.api.dto;

import java.util.List;

/**
 * The apply endpoint's request body, exactly as published (api-contract.md §3.2):
 *
 * <pre>{@code
 * {
 *   "containers": [
 *     { "name": "api", "cpuRequestM": 25, "memRequestMi": 250, "cpuLimitM": 25, "memLimitMi": 250 }
 *   ],
 *   "applyMethod": "IN_PLACE",
 *   "dryRun": true
 * }
 * }</pre>
 *
 * <h2>Why every field is a boxed type</h2>
 *
 * Per container, a <strong>{@code null} or omitted field means "leave that resource alone"</strong>,
 * which is the same convention {@code ContainerApplyTarget} uses. That is not a convenience: the
 * engine deliberately freezes fields (an HPA's CPU utilisation denominator, for one), so a caller
 * that copied a whole {@code recommended} envelope in would patch a request the engine refused to
 * decide — and would also overwrite anything that moved between the read and the apply.
 *
 * <p>{@code dryRun} is {@link Boolean} rather than {@code boolean} for a different reason: it is
 * <strong>mandatory</strong>, and a primitive would silently default an omitted value to
 * {@code false}, which is the real-apply case. An absent {@code dryRun} must be a {@code 400}, not a
 * live patch. The measured reference implementation emitted {@code severity: "success"} /
 * "Rebalance completed: 2 nodes drained" under {@code DRY_RUN=true} with no marker anywhere; the
 * cost of getting this field wrong in either direction is an audit trail nobody can trust.
 *
 * <p>No validation lives here. {@code VerticalApplyService} checks the numbers against the
 * <em>current</em> cluster state and the engine's floors, which is the only place that can, and
 * answers {@code 400 K8S_INVALID_POLICY} naming the exact field.
 *
 * @param containers  per-container targets; at least one is required
 * @param applyMethod {@code IN_PLACE} or {@code IN_PLACE_AND_PERSIST}; defaults to {@code IN_PLACE}
 *                    when absent, because that is the only method this release can carry out
 * @param dryRun      mandatory; echoed in the response and on every audit record
 */
public record ApplyRequestDto(List<Container> containers, String applyMethod, Boolean dryRun) {

    /**
     * CPU in integer millicores, memory in <strong>whole MiB</strong>.
     *
     * <p>Whole MiB is the contract's unit, so a container configured at a non-whole-MiB request can
     * only be applied at MiB granularity. A {@code 0} is not "unset" — it is rejected, because
     * Kubernetes cannot express a request of zero and a recommendation of zero is the measured
     * reference failure this engine exists to prevent.
     */
    public record Container(String name, Integer cpuRequestM, Long memRequestMi,
                            Integer cpuLimitM, Long memLimitMi) {

        /** True when the body names a container but asks for no change at all. */
        public boolean isEmpty() {
            return cpuRequestM == null && memRequestMi == null && cpuLimitM == null && memLimitMi == null;
        }
    }
}
