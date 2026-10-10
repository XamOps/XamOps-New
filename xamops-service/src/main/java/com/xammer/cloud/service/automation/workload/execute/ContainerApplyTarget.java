package com.xammer.cloud.service.automation.workload.execute;

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

    private static void requirePositive(String field, Number value) {
        if (value != null && value.longValue() <= 0) {
            throw new IllegalArgumentException(field + " must be positive when set, got " + value
                    + "; a zero or negative request is not a resource value the engine can express");
        }
    }
}
