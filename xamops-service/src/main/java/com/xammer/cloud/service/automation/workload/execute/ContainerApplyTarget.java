package com.xammer.cloud.service.automation.workload.execute;

import com.xammer.cloud.service.automation.workload.ContainerDecision;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;

import java.util.Objects;
import java.util.Optional;

/**
 * The numbers to write for one container: a {@code (request, limit)} pair per resource.
 *
 * <p>Units are the engine's canonical pair — <strong>CPU in integer millicores, memory in integer
 * bytes</strong> — so no quantity string is ever re-parsed on the way to the cluster (audit bug 3).
 * {@code null} means "leave this field alone", which is distinct from a value of zero; zero is not
 * a legal request and the engine cannot produce one.
 *
 * <p>Deliberately <strong>not</strong> the engine's {@code ContainerDecision}. An executor needs the
 * numbers, not the reasoning: an operator-approved apply may legitimately differ from the current
 * recommendation, and the apply endpoint's request body is exactly this shape. Keeping the command
 * independent of the decision record also means the deferred agent queue does not have to serialise
 * the engine's reason enums.
 *
 * <p>When a command <em>is</em> derived from a decision, {@link #ofChanges(ContainerDecision)} is the
 * conversion: a decision's {@code recommended} is a complete envelope, so only a diff against
 * {@code current} tells a field the engine decided from one it deliberately froze.
 *
 * @param containerName    the container this applies to; sidecars are addressed separately (bug 8)
 * @param cpuRequestMillis target CPU request in millicores, or null to leave unchanged
 * @param cpuLimitMillis   target CPU limit in millicores, or null to leave unchanged
 * @param memRequestBytes  target memory request in bytes, or null to leave unchanged
 * @param memLimitBytes    target memory limit in bytes, or null to leave unchanged
 */
public record ContainerApplyTarget(String containerName,
                                   Integer cpuRequestMillis, Integer cpuLimitMillis,
                                   Long memRequestBytes, Long memLimitBytes) {

    public ContainerApplyTarget {
        if (containerName == null || containerName.isBlank()) {
            throw new IllegalArgumentException("containerName is mandatory: one target per container");
        }
        requirePositive("cpuRequestMillis", cpuRequestMillis);
        requirePositive("cpuLimitMillis", cpuLimitMillis);
        requirePositive("memRequestBytes", memRequestBytes);
        requirePositive("memLimitBytes", memLimitBytes);

        // The in-place resize subresource rejects a request above its own limit, so an unsatisfiable
        // command must not become durable in the queue in the first place.
        if (cpuRequestMillis != null && cpuLimitMillis != null && cpuRequestMillis > cpuLimitMillis) {
            throw new IllegalArgumentException("container " + containerName
                    + ": CPU request " + cpuRequestMillis + "m exceeds its limit " + cpuLimitMillis + "m");
        }
        if (memRequestBytes != null && memLimitBytes != null && memRequestBytes > memLimitBytes) {
            throw new IllegalArgumentException("container " + containerName
                    + ": memory request " + memRequestBytes + "B exceeds its limit " + memLimitBytes + "B");
        }
        if (cpuRequestMillis == null && cpuLimitMillis == null
                && memRequestBytes == null && memLimitBytes == null) {
            throw new IllegalArgumentException("container " + containerName + ": nothing to apply");
        }
    }

    /**
     * The <strong>decision-to-command conversion</strong>: the fields a decision actually changed,
     * with everything it left alone left null.
     *
     * <p>This exists because {@link ContainerDecision#recommended()} is a complete envelope rather
     * than a change set. A field there may equal its current value because the engine deliberately
     * refused to move it — the HPA-managed CPU request is the live case, frozen with
     * {@code HPA_CPU_MANAGED} while memory still moves. Copying that envelope into a command
     * wholesale would patch a request the engine never decided, and in the window between a read and
     * an apply it would also overwrite whatever else had changed it. Diffing is the only safe
     * conversion, so it lives here, once, rather than in each future executor.
     *
     * @return the changed fields, or {@link Optional#empty()} when the decision recommends nothing or
     *         its recommendation is field-for-field identical to the current state
     */
    public static Optional<ContainerApplyTarget> ofChanges(ContainerDecision decision) {
        Objects.requireNonNull(decision, "decision");
        ResourceEnvelope recommended = decision.recommended();
        if (recommended == null) {
            return Optional.empty();
        }
        ResourceEnvelope current = decision.current();
        Integer cpuRequest = changed(recommended.cpuRequestMillis(), current.cpuRequestMillis());
        Integer cpuLimit = changed(recommended.cpuLimitMillis(), current.cpuLimitMillis());
        Long memRequest = changed(recommended.memRequestBytes(), current.memRequestBytes());
        Long memLimit = changed(recommended.memLimitBytes(), current.memLimitBytes());

        if (cpuRequest == null && cpuLimit == null && memRequest == null && memLimit == null) {
            return Optional.empty();
        }
        return Optional.of(new ContainerApplyTarget(decision.containerName(),
                cpuRequest, cpuLimit, memRequest, memLimit));
    }

    /** @return {@code decided} when it differs from {@code currently}, else null — "leave it alone" */
    private static <T> T changed(T decided, T currently) {
        return Objects.equals(decided, currently) ? null : decided;
    }

    private static void requirePositive(String field, Number value) {
        if (value != null && value.longValue() <= 0) {
            throw new IllegalArgumentException(field + " must be positive when set, got " + value
                    + "; a zero or negative request is not a resource value the engine can express");
        }
    }
}
