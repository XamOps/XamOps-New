package com.xammer.cloud.service.automation.workload.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The configuration of the real-Kubernetes cost + savings feature (roadmap C2), under
 * {@code xamops.k8s.cost.*}.
 *
 * <p>A <strong>standalone</strong> {@code @Component}, following the {@code RpsSizingProperties}
 * exemplar exactly: one {@code @Autowired} {@code @Value} production constructor plus a private
 * canonical constructor behind the static {@link #of} test factory. It is explicitly
 * {@code @Autowired} on the production constructor because the class declares more than one
 * constructor; with multiple constructors and none marked, Spring falls back to a no-arg constructor,
 * finds none, and the whole context fails to start — the exact boot regression
 * {@code K8sCostPropertiesContextTest} guards against.
 *
 * <p>The defaults encode the no-fake-data, default-OFF posture:
 *
 * <ul>
 *   <li>{@code enabled} default <strong>false</strong> — while off, the feature makes no AWS pricing
 *       call and every cost field is {@code null}.</li>
 *   <li>Durations bound as {@link Duration}: {@code on-demand-ttl} 6h, {@code spot-ttl} 15m,
 *       {@code transient-negative-ttl} 60s (the short floor so a transient AWS blip is not masked for
 *       hours), {@code spot-lookback} 30m.</li>
 *   <li>{@code hours-per-month} 730, {@code weight-cpu} 1.0, {@code weight-mem} 0.5,
 *       {@code max-cache-entries} 10000.</li>
 * </ul>
 *
 * <p>Every key has an inline {@code :default}; the keys are optional in every profile and there is
 * <strong>no</strong> {@code KEYS} parity test (as with {@code RpsSizingProperties}). The weights are
 * each validated {@code > 0} and finite — the guarantee the §3.3 split relies on for a
 * provably-positive denominator.
 */
@Component
public class K8sCostProperties {

    private static final Logger logger = LoggerFactory.getLogger(K8sCostProperties.class);

    public static final String ENABLED_KEY = "xamops.k8s.cost.enabled";
    public static final String ON_DEMAND_TTL_KEY = "xamops.k8s.cost.on-demand-ttl";
    public static final String SPOT_TTL_KEY = "xamops.k8s.cost.spot-ttl";
    public static final String TRANSIENT_NEGATIVE_TTL_KEY = "xamops.k8s.cost.transient-negative-ttl";
    public static final String SPOT_LOOKBACK_KEY = "xamops.k8s.cost.spot-lookback";
    public static final String HOURS_PER_MONTH_KEY = "xamops.k8s.cost.hours-per-month";
    public static final String WEIGHT_CPU_KEY = "xamops.k8s.cost.weight-cpu";
    public static final String WEIGHT_MEM_KEY = "xamops.k8s.cost.weight-mem";
    public static final String MAX_CACHE_ENTRIES_KEY = "xamops.k8s.cost.max-cache-entries";

    static final Duration DEFAULT_ON_DEMAND_TTL = Duration.ofHours(6);
    static final Duration DEFAULT_SPOT_TTL = Duration.ofMinutes(15);
    static final Duration DEFAULT_TRANSIENT_NEGATIVE_TTL = Duration.ofSeconds(60);
    static final Duration DEFAULT_SPOT_LOOKBACK = Duration.ofMinutes(30);
    static final double DEFAULT_HOURS_PER_MONTH = 730;
    static final double DEFAULT_WEIGHT_CPU = 1.0;
    static final double DEFAULT_WEIGHT_MEM = 0.5;
    static final int DEFAULT_MAX_CACHE_ENTRIES = 10000;

    private final boolean enabled;
    private final Duration onDemandTtl;
    private final Duration spotTtl;
    private final Duration transientNegativeTtl;
    private final Duration spotLookback;
    private final double hoursPerMonth;
    private final double weightCpu;
    private final double weightMem;
    private final int maxCacheEntries;

    @Autowired
    public K8sCostProperties(
            @Value("${" + ENABLED_KEY + ":false}") boolean enabled,
            @Value("${" + ON_DEMAND_TTL_KEY + ":6h}") String onDemandTtl,
            @Value("${" + SPOT_TTL_KEY + ":15m}") String spotTtl,
            @Value("${" + TRANSIENT_NEGATIVE_TTL_KEY + ":60s}") String transientNegativeTtl,
            @Value("${" + SPOT_LOOKBACK_KEY + ":30m}") String spotLookback,
            @Value("${" + HOURS_PER_MONTH_KEY + ":730}") double hoursPerMonth,
            @Value("${" + WEIGHT_CPU_KEY + ":1.0}") double weightCpu,
            @Value("${" + WEIGHT_MEM_KEY + ":0.5}") double weightMem,
            @Value("${" + MAX_CACHE_ENTRIES_KEY + ":10000}") int maxCacheEntries) {
        this.enabled = enabled;
        this.onDemandTtl = positiveDurationOr(parseDuration(onDemandTtl, ON_DEMAND_TTL_KEY),
                DEFAULT_ON_DEMAND_TTL, ON_DEMAND_TTL_KEY);
        this.spotTtl = positiveDurationOr(parseDuration(spotTtl, SPOT_TTL_KEY),
                DEFAULT_SPOT_TTL, SPOT_TTL_KEY);
        this.transientNegativeTtl = positiveDurationOr(
                parseDuration(transientNegativeTtl, TRANSIENT_NEGATIVE_TTL_KEY),
                DEFAULT_TRANSIENT_NEGATIVE_TTL, TRANSIENT_NEGATIVE_TTL_KEY);
        this.spotLookback = positiveDurationOr(parseDuration(spotLookback, SPOT_LOOKBACK_KEY),
                DEFAULT_SPOT_LOOKBACK, SPOT_LOOKBACK_KEY);
        this.hoursPerMonth = positiveFiniteOr(hoursPerMonth, DEFAULT_HOURS_PER_MONTH, HOURS_PER_MONTH_KEY);
        this.weightCpu = positiveFiniteOr(weightCpu, DEFAULT_WEIGHT_CPU, WEIGHT_CPU_KEY);
        this.weightMem = positiveFiniteOr(weightMem, DEFAULT_WEIGHT_MEM, WEIGHT_MEM_KEY);
        this.maxCacheEntries = positiveIntOr(maxCacheEntries, DEFAULT_MAX_CACHE_ENTRIES,
                MAX_CACHE_ENTRIES_KEY);
    }

    private K8sCostProperties(boolean enabled, Duration onDemandTtl, Duration spotTtl,
                             Duration transientNegativeTtl, Duration spotLookback, double hoursPerMonth,
                             double weightCpu, double weightMem, int maxCacheEntries, boolean resolved) {
        this.enabled = enabled;
        this.onDemandTtl = positiveDurationOr(onDemandTtl, DEFAULT_ON_DEMAND_TTL, ON_DEMAND_TTL_KEY);
        this.spotTtl = positiveDurationOr(spotTtl, DEFAULT_SPOT_TTL, SPOT_TTL_KEY);
        this.transientNegativeTtl = positiveDurationOr(transientNegativeTtl,
                DEFAULT_TRANSIENT_NEGATIVE_TTL, TRANSIENT_NEGATIVE_TTL_KEY);
        this.spotLookback = positiveDurationOr(spotLookback, DEFAULT_SPOT_LOOKBACK, SPOT_LOOKBACK_KEY);
        this.hoursPerMonth = positiveFiniteOr(hoursPerMonth, DEFAULT_HOURS_PER_MONTH, HOURS_PER_MONTH_KEY);
        this.weightCpu = positiveFiniteOr(weightCpu, DEFAULT_WEIGHT_CPU, WEIGHT_CPU_KEY);
        this.weightMem = positiveFiniteOr(weightMem, DEFAULT_WEIGHT_MEM, WEIGHT_MEM_KEY);
        this.maxCacheEntries = positiveIntOr(maxCacheEntries, DEFAULT_MAX_CACHE_ENTRIES,
                MAX_CACHE_ENTRIES_KEY);
    }

    /**
     * Test seam: bind already-resolved values without Spring. A separate factory rather than an
     * overloaded constructor, so there is never an ambiguous overload against the {@code @Value}
     * production constructor.
     */
    public static K8sCostProperties of(boolean enabled, Duration onDemandTtl, Duration spotTtl,
                                        Duration transientNegativeTtl, Duration spotLookback,
                                        double hoursPerMonth, double weightCpu, double weightMem,
                                        int maxCacheEntries) {
        return new K8sCostProperties(enabled, onDemandTtl, spotTtl, transientNegativeTtl, spotLookback,
                hoursPerMonth, weightCpu, weightMem, maxCacheEntries, true);
    }

    /** A disabled instance with every default, built without Spring (the inert test default). */
    public static K8sCostProperties disabled() {
        return of(false, DEFAULT_ON_DEMAND_TTL, DEFAULT_SPOT_TTL, DEFAULT_TRANSIENT_NEGATIVE_TTL,
                DEFAULT_SPOT_LOOKBACK, DEFAULT_HOURS_PER_MONTH, DEFAULT_WEIGHT_CPU, DEFAULT_WEIGHT_MEM,
                DEFAULT_MAX_CACHE_ENTRIES);
    }

    /** An enabled instance with every default, for tests that exercise the on path. */
    public static K8sCostProperties enabledDefaults() {
        return of(true, DEFAULT_ON_DEMAND_TTL, DEFAULT_SPOT_TTL, DEFAULT_TRANSIENT_NEGATIVE_TTL,
                DEFAULT_SPOT_LOOKBACK, DEFAULT_HOURS_PER_MONTH, DEFAULT_WEIGHT_CPU, DEFAULT_WEIGHT_MEM,
                DEFAULT_MAX_CACHE_ENTRIES);
    }

    /** Whether the feature prices anything at all. Off by default. */
    public boolean enabled() {
        return enabled;
    }

    public Duration onDemandTtl() {
        return onDemandTtl;
    }

    public Duration spotTtl() {
        return spotTtl;
    }

    public Duration transientNegativeTtl() {
        return transientNegativeTtl;
    }

    public Duration spotLookback() {
        return spotLookback;
    }

    public double hoursPerMonth() {
        return hoursPerMonth;
    }

    public double weightCpu() {
        return weightCpu;
    }

    public double weightMem() {
        return weightMem;
    }

    public int maxCacheEntries() {
        return maxCacheEntries;
    }

    private static double positiveFiniteOr(double value, double fallback, String key) {
        if (!Double.isFinite(value) || value <= 0) {
            logger.warn("Non-positive {} value {}; using {}", key, value, fallback);
            return fallback;
        }
        return value;
    }

    private static int positiveIntOr(int value, int fallback, String key) {
        if (value <= 0) {
            logger.warn("Non-positive {} value {}; using {}", key, value, fallback);
            return fallback;
        }
        return value;
    }

    private static final java.util.regex.Pattern SIMPLE_DURATION =
            java.util.regex.Pattern.compile("^(\\d+)\\s*(ns|us|ms|s|m|h|d)$");

    /**
     * Parses a Spring-style simple duration ({@code 6h}, {@code 15m}, {@code 60s}, {@code 500ms}, …)
     * or an ISO-8601 duration ({@code PT6H}). Returns {@code null} on anything unparseable, so the
     * {@code positiveDurationOr} guard then falls back to the default rather than failing boot.
     */
    static Duration parseDuration(String raw, String key) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        java.util.regex.Matcher matcher = SIMPLE_DURATION.matcher(trimmed.toLowerCase(java.util.Locale.ROOT));
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "ns" -> Duration.ofNanos(amount);
                case "us" -> Duration.ofNanos(amount * 1000);
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> null;
            };
        }
        try {
            return Duration.parse(trimmed);
        } catch (RuntimeException notADuration) {
            logger.warn("Unparseable {} value '{}'; using default", key, raw);
            return null;
        }
    }

    private static Duration positiveDurationOr(Duration value, Duration fallback, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            logger.warn("Non-positive {} value {}; using {}", key, value, fallback);
            return fallback;
        }
        return value;
    }
}
