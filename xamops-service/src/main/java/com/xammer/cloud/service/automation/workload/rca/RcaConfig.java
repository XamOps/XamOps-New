package com.xammer.cloud.service.automation.workload.rca;

/**
 * The tunable inputs to {@link RcaAnalyzer} — the per-kind min-occurrences thresholds, the trailing
 * window, and the top-K cap.
 *
 * <p>A plain value object, injected into the pure analyzer so the analyzer reads no Spring and no
 * configuration itself. The runtime binding lives in {@link RcaProperties}; tests build this record
 * directly with literal thresholds.
 *
 * <p>The thresholds are the min-occurrences each pattern must reach to fire. They are the
 * configurable half of the design's confidence table — the {@code base}/{@code perOccurrence} half
 * is fixed on {@link RcaPatternKind}. Defaults (design §2.3/§2.8): FAILED 2, REFUSED 3, PARTIAL 2,
 * EXPIRED 2, FLIP_FLOP 3, window 24h, top-K 10.
 *
 * @param repeatedFailedThreshold    min FAILED rows to fire {@link RcaPatternKind#REPEATED_FAILED}
 * @param repeatedRefusedThreshold   min REFUSED rows to fire {@link RcaPatternKind#REPEATED_REFUSED}
 * @param partialApplyChurnThreshold min partial/not-restart-free rows for
 *                                   {@link RcaPatternKind#PARTIAL_APPLY_CHURN}
 * @param expiryChurnThreshold       min EXPIRED rows to fire {@link RcaPatternKind#EXPIRY_CHURN}
 * @param flipFlopThreshold          min sign reversals to fire {@link RcaPatternKind#RESIZE_FLIP_FLOP}
 * @param windowHours                the trailing window, in hours
 * @param topK                       the maximum number of causes to return
 */
public record RcaConfig(int repeatedFailedThreshold,
                        int repeatedRefusedThreshold,
                        int partialApplyChurnThreshold,
                        int expiryChurnThreshold,
                        int flipFlopThreshold,
                        int windowHours,
                        int topK) {

    public static final int DEFAULT_FAILED_THRESHOLD = 2;
    public static final int DEFAULT_REFUSED_THRESHOLD = 3;
    public static final int DEFAULT_PARTIAL_THRESHOLD = 2;
    public static final int DEFAULT_EXPIRED_THRESHOLD = 2;
    public static final int DEFAULT_FLIP_FLOP_THRESHOLD = 3;
    public static final int DEFAULT_WINDOW_HOURS = 24;
    public static final int DEFAULT_TOP_K = 10;

    /** The analyzer-facing defaults, matching design §2.3/§2.8. */
    public static RcaConfig defaults() {
        return new RcaConfig(DEFAULT_FAILED_THRESHOLD, DEFAULT_REFUSED_THRESHOLD,
                DEFAULT_PARTIAL_THRESHOLD, DEFAULT_EXPIRED_THRESHOLD, DEFAULT_FLIP_FLOP_THRESHOLD,
                DEFAULT_WINDOW_HOURS, DEFAULT_TOP_K);
    }

    /** The configured min-occurrences threshold for one emitted kind. */
    public int thresholdFor(RcaPatternKind kind) {
        return switch (kind) {
            case REPEATED_FAILED -> repeatedFailedThreshold;
            case REPEATED_REFUSED -> repeatedRefusedThreshold;
            case PARTIAL_APPLY_CHURN -> partialApplyChurnThreshold;
            case EXPIRY_CHURN -> expiryChurnThreshold;
            case RESIZE_FLIP_FLOP -> flipFlopThreshold;
            case OOM_EVICTION, HPA_FLAP -> Integer.MAX_VALUE; // never emitted in v1
        };
    }

    /** This config with a different window, used after the controller clamps {@code windowHours}. */
    public RcaConfig withWindowHours(int hours) {
        return new RcaConfig(repeatedFailedThreshold, repeatedRefusedThreshold,
                partialApplyChurnThreshold, expiryChurnThreshold, flipFlopThreshold, hours, topK);
    }
}
