package com.xammer.cloud.service.automation.workload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The configuration of the RPS-based replica sizing (F3), under
 * {@code xamops.automation.horizontal.rps.*}.
 *
 * <p>A <strong>standalone</strong> {@code @Component}, deliberately not a key on
 * {@link HorizontalAutoscalingProperties}: that class is read positionally by
 * {@code HorizontalAutoscalingPropertiesTest} (which loads all five {@code application*.properties}
 * profiles), so a new key there would break the parity test. These keys need not appear in any
 * profile and no parity test asserts them.
 *
 * <p>The defaults encode the no-fake-data posture:
 *
 * <ul>
 *   <li>{@code enabled} default <strong>false</strong> — while off, every workload is the honest
 *       {@code NO_RPS_SOURCE} dash; the feature reads a source only when explicitly turned on.</li>
 *   <li>{@code metric} default <strong>empty</strong> — the PromQL request-rate expression; empty
 *       means no RPS source, never a guessed metric.</li>
 *   <li>{@code target-util} default <strong>0.70</strong> — k8opt's {@code target_utilization = 70.0};
 *       a non-positive configured value falls back to the default so the sizing cannot divide by a
 *       bad target.</li>
 *   <li>{@code capacity-per-replica-rps} default <strong>unset</strong> — the real per-replica
 *       capacity (§3.2); unset means {@code NO_CAPACITY_BASIS}. Capacity is never self-derived from
 *       the observed RPS.</li>
 * </ul>
 */
@Component
public class RpsSizingProperties {

    private static final Logger logger = LoggerFactory.getLogger(RpsSizingProperties.class);

    public static final String ENABLED_KEY = "xamops.automation.horizontal.rps.enabled";
    public static final String METRIC_KEY = "xamops.automation.horizontal.rps.metric";
    public static final String TARGET_UTIL_KEY = "xamops.automation.horizontal.rps.target-util";
    public static final String CAPACITY_KEY =
            "xamops.automation.horizontal.rps.capacity-per-replica-rps";

    static final double DEFAULT_TARGET_UTIL = 0.70;

    private final boolean enabled;
    private final String metric;
    private final double targetUtil;
    private final Double capacityPerReplicaRps;

    public RpsSizingProperties(
            @Value("${" + ENABLED_KEY + ":false}") boolean enabled,
            @Value("${" + METRIC_KEY + ":}") String metric,
            @Value("${" + TARGET_UTIL_KEY + ":0.70}") double targetUtil,
            @Value("${" + CAPACITY_KEY + ":}") String capacityPerReplicaRps) {
        this.enabled = enabled;
        this.metric = metric == null ? "" : metric.trim();
        this.targetUtil = positiveFiniteOr(targetUtil, DEFAULT_TARGET_UTIL, TARGET_UTIL_KEY);
        this.capacityPerReplicaRps = parseCapacity(capacityPerReplicaRps);
    }

    private RpsSizingProperties(boolean enabled, String metric, double targetUtil,
                               Double capacityPerReplicaRps, boolean resolved) {
        this.enabled = enabled;
        this.metric = metric == null ? "" : metric.trim();
        this.targetUtil = positiveFiniteOr(targetUtil, DEFAULT_TARGET_UTIL, TARGET_UTIL_KEY);
        this.capacityPerReplicaRps = capacityPerReplicaRps != null && capacityPerReplicaRps > 0
                ? capacityPerReplicaRps : null;
    }

    /**
     * Test seam: bind already-resolved values directly, bypassing the {@code @Value} placeholders
     * and the capacity string-parse. A separate factory rather than an overloaded constructor, so a
     * {@code null} capacity is never ambiguous against the production {@code String}-capacity
     * constructor.
     */
    public static RpsSizingProperties of(boolean enabled, String metric, double targetUtil,
                                         Double capacityPerReplicaRps) {
        return new RpsSizingProperties(enabled, metric, targetUtil, capacityPerReplicaRps, true);
    }

    /** Whether the feature reads an RPS source at all. Off by default. */
    public boolean enabled() {
        return enabled;
    }

    /** The configured PromQL request-rate expression; blank means no RPS source. */
    public String metric() {
        return metric;
    }

    /** Whether a request-rate metric is configured; blank/absent means there is no RPS source. */
    public boolean hasMetric() {
        return !metric.isBlank();
    }

    /** The target utilisation to hold, e.g. {@code 0.70}. Always positive and finite. */
    public double targetUtil() {
        return targetUtil;
    }

    /**
     * The real per-replica capacity, or {@code null} when unset. {@code null} means
     * {@code NO_CAPACITY_BASIS}: capacity is never self-derived from the observed RPS.
     */
    public Double capacityPerReplicaRps() {
        return capacityPerReplicaRps;
    }

    /** Whether a real per-replica capacity basis exists. */
    public boolean hasCapacity() {
        return capacityPerReplicaRps != null;
    }

    private static double positiveFiniteOr(double value, double fallback, String key) {
        if (!Double.isFinite(value) || value <= 0) {
            logger.warn("Non-positive {} value {}; using {}", key, value, fallback);
            return fallback;
        }
        return value;
    }

    private static Double parseCapacity(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (!Double.isFinite(parsed) || parsed <= 0) {
                logger.warn("Non-positive {} value {}; treating capacity as unset", CAPACITY_KEY, raw);
                return null;
            }
            return parsed;
        } catch (NumberFormatException notANumber) {
            logger.warn("Unparseable {} value '{}'; treating capacity as unset", CAPACITY_KEY, raw);
            return null;
        }
    }
}
