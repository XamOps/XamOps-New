package com.xammer.cloud.service.automation.workload;

/**
 * One container's {@code (request, limit)} pair for CPU and memory, in canonical units.
 *
 * <p><strong>{@code null} means unset, and is distinct from zero.</strong> The measured Go output
 * reported {@code "currentCpuRequest": "0m"} and {@code "currentMemRequest": "1Mi"} for a container
 * that had <em>neither</em>, then "fixed" the fabricated baseline to {@code 1m / 1Mi} with 0%
 * savings. A fabricated current value is unrepresentable here: there is no way to write zero and
 * mean "unset", and {@link #recommendation} refuses a non-positive request outright.
 *
 * @param cpuRequestMillis CPU request in millicores, or null when unset
 * @param cpuLimitMillis   CPU limit in millicores, or null when unset
 * @param memRequestBytes  memory request in bytes, or null when unset
 * @param memLimitBytes    memory limit in bytes, or null when unset
 */
public record ResourceEnvelope(Integer cpuRequestMillis, Integer cpuLimitMillis,
                               Long memRequestBytes, Long memLimitBytes) {

    public static final ResourceEnvelope UNSET = new ResourceEnvelope(null, null, null, null);

    public ResourceEnvelope {
        requireNonNegative("cpuRequestMillis", cpuRequestMillis);
        requireNonNegative("cpuLimitMillis", cpuLimitMillis);
        requireNonNegative("memRequestBytes", memRequestBytes);
        requireNonNegative("memLimitBytes", memLimitBytes);
    }

    /**
     * Builds a <em>recommended</em> envelope and validates everything that must be true of one.
     *
     * <p>Rejected at construction, so an invalid recommendation cannot exist as a value, let alone be
     * served or applied:
     *
     * <ol>
     *   <li><strong>A non-positive or absent request.</strong> Zero CPU and {@code 1Mi} memory were
     *       both measured being recommended; neither is a resource value worth writing. (bug 4)</li>
     *   <li><strong>A request above its own limit.</strong> The in-place resize subresource rejects
     *       it, so this is a correctness requirement, not a style preference. (bug 7)</li>
     *   <li><strong>A change of QoS shape.</strong> Writing requests without limits is what silently
     *       moved a workload from Guaranteed to Burstable in the measured live run, and the resize
     *       subresource refuses a QoS-class change outright. The one allowed transition is
     *       <em>from</em> an unset shape: a BestEffort container has no class to preserve, and
     *       surfacing a size for it is the entire point of the advisory — which is why that case is
     *       also marked {@code REQUESTS_UNSET} and never auto-applied. (bug 7)</li>
     * </ol>
     *
     * @param current the container's present envelope, the baseline the shape must preserve
     * @throws IllegalArgumentException if any rule above is violated
     */
    public static ResourceEnvelope recommendation(ResourceEnvelope current,
                                                  Integer cpuRequestMillis, Integer cpuLimitMillis,
                                                  Long memRequestBytes, Long memLimitBytes) {
        if (current == null) {
            throw new IllegalArgumentException("a recommendation needs a current envelope to validate against");
        }
        ResourceEnvelope recommended =
                new ResourceEnvelope(cpuRequestMillis, cpuLimitMillis, memRequestBytes, memLimitBytes);

        if (cpuRequestMillis == null && memRequestBytes == null) {
            throw new IllegalArgumentException(
                    "a recommendation with no request at all is not a recommendation");
        }
        requirePositive("cpuRequestMillis", cpuRequestMillis);
        requirePositive("memRequestBytes", memRequestBytes);

        if (cpuRequestMillis != null && cpuLimitMillis != null && cpuRequestMillis > cpuLimitMillis) {
            throw new IllegalArgumentException("recommended CPU request " + cpuRequestMillis
                    + "m exceeds its limit " + cpuLimitMillis + "m; in-place resize would reject it");
        }
        if (memRequestBytes != null && memLimitBytes != null && memRequestBytes > memLimitBytes) {
            throw new IllegalArgumentException("recommended memory request " + memRequestBytes
                    + "B exceeds its limit " + memLimitBytes + "B; in-place resize would reject it");
        }
        if (!QosRules.preservesShape(current, recommended)) {
            throw new IllegalArgumentException("recommendation would change the container's QoS shape from "
                    + QosRules.shapeOf(current) + " to " + QosRules.shapeOf(recommended)
                    + "; in-place resize rejects a QoS-class change, and writing requests without"
                    + " limits is what silently downgraded Guaranteed to Burstable in the measured run");
        }
        return recommended;
    }

    public boolean hasCpuRequest() {
        return cpuRequestMillis != null;
    }

    public boolean hasMemRequest() {
        return memRequestBytes != null;
    }

    /** True when either request is missing — the {@code REQUESTS_UNSET} condition. */
    public boolean anyRequestUnset() {
        return cpuRequestMillis == null || memRequestBytes == null;
    }

    public ResourceEnvelope withCpu(Integer requestMillis, Integer limitMillis) {
        return new ResourceEnvelope(requestMillis, limitMillis, memRequestBytes, memLimitBytes);
    }

    public ResourceEnvelope withMemory(Long requestBytes, Long limitBytes) {
        return new ResourceEnvelope(cpuRequestMillis, cpuLimitMillis, requestBytes, limitBytes);
    }

    private static void requireNonNegative(String field, Number value) {
        if (value != null && value.longValue() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative, got " + value);
        }
    }

    private static void requirePositive(String field, Number value) {
        if (value != null && value.longValue() <= 0) {
            throw new IllegalArgumentException("recommended " + field + " must be positive, got " + value
                    + "; a zero or near-zero request is the measured failure this engine exists to prevent");
        }
    }
}
