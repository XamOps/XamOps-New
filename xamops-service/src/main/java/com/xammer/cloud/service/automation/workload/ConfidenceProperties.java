package com.xammer.cloud.service.automation.workload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two band edges of the vertical-rightsizing confidence score.
 *
 * <p>A <strong>standalone</strong> {@code @Component}, deliberately not a key on
 * {@link com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties}: that class's
 * {@code KEYS} list is pinned by {@code VerticalMetricsPropertiesTest}, which reads all five
 * {@code application*.properties} profiles from disk and asserts every bound key appears in each. A
 * separate component keeps that parity test untouched — these two keys need not be declared in any
 * profile, and no parity test asserts them.
 *
 * <p>Same defensive {@code @Value}-with-{@code :}-default style as
 * {@code VerticalMetricsProperties.positiveOr}: an absent, empty or non-finite value falls back to
 * the safe default, and both edges are clamped to {@code [0,1]}. If a misconfiguration inverts the
 * cuts ({@code high-threshold < floor}), the HIGH cut is corrected up to the floor so the bands can
 * never invert.
 */
@Component
public class ConfidenceProperties {

    private static final Logger logger = LoggerFactory.getLogger(ConfidenceProperties.class);

    public static final String FLOOR_KEY = "xamops.automation.vertical.confidence.floor";
    public static final String HIGH_THRESHOLD_KEY =
            "xamops.automation.vertical.confidence.high-threshold";

    static final double DEFAULT_FLOOR = 0.5;
    static final double DEFAULT_HIGH_THRESHOLD = 0.8;

    private final double floor;
    private final double highThreshold;

    public ConfidenceProperties(
            @Value("${" + FLOOR_KEY + ":0.5}") double floor,
            @Value("${" + HIGH_THRESHOLD_KEY + ":0.8}") double highThreshold) {
        double safeFloor = clampOr(floor, DEFAULT_FLOOR, FLOOR_KEY);
        double safeHigh = clampOr(highThreshold, DEFAULT_HIGH_THRESHOLD, HIGH_THRESHOLD_KEY);
        if (safeHigh < safeFloor) {
            // A HIGH cut below the LOW cut would make the MEDIUM band empty and HIGH unreachably
            // easy. Correct the HIGH cut up to the floor rather than throwing.
            logger.warn("{} ({}) is below {} ({}); raising it to the floor so the bands cannot invert",
                    HIGH_THRESHOLD_KEY, safeHigh, FLOOR_KEY, safeFloor);
            safeHigh = safeFloor;
        }
        this.floor = safeFloor;
        this.highThreshold = safeHigh;
    }

    /** The LOW cut: a score below this is {@code LOW}. Default {@value #DEFAULT_FLOOR}. */
    public double floor() {
        return floor;
    }

    /** The HIGH cut: a score at or above this is {@code HIGH}. Default {@value #DEFAULT_HIGH_THRESHOLD}. */
    public double highThreshold() {
        return highThreshold;
    }

    private static double clampOr(double value, double fallback, String key) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            logger.warn("Out-of-range {} value {}; using {}", key, value, fallback);
            return fallback;
        }
        return value;
    }
}
