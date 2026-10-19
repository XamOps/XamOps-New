package com.xammer.cloud.service.automation.workload.rca;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The RCA engine's tunables, bound under {@code xamops.automation.vertical.rca.*}.
 *
 * <p>A <strong>standalone</strong> {@code @Component}, deliberately not a key on
 * {@code VerticalMetricsProperties}: that class's {@code KEYS} list is pinned by
 * {@code VerticalMetricsPropertiesTest}, which reads all five {@code application*.properties}
 * profiles from disk and asserts every bound key appears in each. A separate component keeps that
 * parity test untouched — these keys need not be declared in any profile.
 *
 * <p>Every field is {@code @Value} with a {@code :} default, so an absent property binds to the safe
 * default; a non-positive or inverted value falls back defensively. {@link #toConfig()} exposes the
 * immutable {@link RcaConfig} the analyzer consumes.
 */
@Component
public class RcaProperties {

    private static final Logger logger = LoggerFactory.getLogger(RcaProperties.class);

    public static final String WINDOW_HOURS_KEY = "xamops.automation.vertical.rca.window-hours";
    public static final String TOP_K_KEY = "xamops.automation.vertical.rca.top-k";
    public static final String FAILED_KEY = "xamops.automation.vertical.rca.min-occurrences.failed";
    public static final String REFUSED_KEY = "xamops.automation.vertical.rca.min-occurrences.refused";
    public static final String PARTIAL_KEY = "xamops.automation.vertical.rca.min-occurrences.partial";
    public static final String EXPIRED_KEY = "xamops.automation.vertical.rca.min-occurrences.expired";
    public static final String FLIP_FLOP_KEY =
            "xamops.automation.vertical.rca.min-occurrences.flip-flop";

    private final RcaConfig config;

    public RcaProperties(
            @Value("${" + WINDOW_HOURS_KEY + ":24}") int windowHours,
            @Value("${" + TOP_K_KEY + ":10}") int topK,
            @Value("${" + FAILED_KEY + ":2}") int failed,
            @Value("${" + REFUSED_KEY + ":3}") int refused,
            @Value("${" + PARTIAL_KEY + ":2}") int partial,
            @Value("${" + EXPIRED_KEY + ":2}") int expired,
            @Value("${" + FLIP_FLOP_KEY + ":3}") int flipFlop) {
        this.config = new RcaConfig(
                positiveOr(failed, RcaConfig.DEFAULT_FAILED_THRESHOLD, FAILED_KEY),
                positiveOr(refused, RcaConfig.DEFAULT_REFUSED_THRESHOLD, REFUSED_KEY),
                positiveOr(partial, RcaConfig.DEFAULT_PARTIAL_THRESHOLD, PARTIAL_KEY),
                positiveOr(expired, RcaConfig.DEFAULT_EXPIRED_THRESHOLD, EXPIRED_KEY),
                positiveOr(flipFlop, RcaConfig.DEFAULT_FLIP_FLOP_THRESHOLD, FLIP_FLOP_KEY),
                positiveOr(windowHours, RcaConfig.DEFAULT_WINDOW_HOURS, WINDOW_HOURS_KEY),
                positiveOr(topK, RcaConfig.DEFAULT_TOP_K, TOP_K_KEY));
    }

    /** The analyzer-facing config. */
    public RcaConfig toConfig() {
        return config;
    }

    /** The default trailing window, used when the caller sends no {@code windowHours}. */
    public int windowHours() {
        return config.windowHours();
    }

    private static int positiveOr(int value, int fallback, String key) {
        if (value < 1) {
            logger.warn("Non-positive {} value {}; using {}", key, value, fallback);
            return fallback;
        }
        return value;
    }
}
