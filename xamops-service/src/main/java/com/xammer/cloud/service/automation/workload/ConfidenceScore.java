package com.xammer.cloud.service.automation.workload;

/**
 * The explicit {@code 0..1} confidence score for one vertical rightsizing recommendation.
 *
 * <p>A pure function — no I/O, no Spring, no configuration — reimplementing k8opt's {@code _confidence}
 * idea natively:
 *
 * <pre>
 * confidence = 0.5 * sampleFraction + 0.5 * volatilityFactor
 *   sampleFraction  = clamp(sampleCoverage, 0, 1)                         // the already-measured field
 *   volatilityFactor:
 *       if mean == null or stddev == null or mean == 0 -> 1.0             // no measurable volatility
 *       else                                           -> clamp(1 - CV, 0, 1),  CV = stddev / mean
 * result rounded to 3 decimal places, clamped to [0,1]
 * </pre>
 *
 * <h2>sampleFraction is the measured sampleCoverage, verbatim</h2>
 *
 * The {@code sampleFraction} term <strong>is</strong> {@code ContainerUsageAggregate.sampleCoverage}
 * — the same window-coverage ratio the data-sufficiency gate uses — consumed directly, never
 * recomputed. One coverage number travels end to end, so the score can never disagree with the gate
 * that let the recommendation through.
 *
 * <h2>The volatility half is CPU-only and degrades honestly</h2>
 *
 * When Prometheus returned no finite mean/stddev (or the mean is zero), there is no measurable
 * coefficient of variation, so the volatility factor is the neutral {@code 1.0} rather than a
 * fabricated value. A {@code < 2}-sample series could never have cleared the data gate, so the
 * unreachable {@code nSamples < 2} branch of the reference is expressed here as "inputs absent".
 *
 * <h2>Band is presentational and takes both cut points as arguments</h2>
 *
 * {@link #band(double, double, double)} bakes in no literal: the LOW {@code floor} and the HIGH
 * {@code highThreshold} both arrive from configuration (bound in {@code ConfidenceProperties}) and are
 * applied downstream in the mapper, so the pure engine never reads them.
 */
public final class ConfidenceScore {

    private ConfidenceScore() {
    }

    /** The presentational quality band for a score, computed from configurable cut points. */
    public enum Band {
        HIGH,
        MEDIUM,
        LOW
    }

    /**
     * @param sampleCoverage  the aggregate's measured window coverage, used verbatim as the
     *                        {@code sampleFraction} term (clamped defensively to {@code [0,1]})
     * @param cpuMeanMillis   mean of the measured CPU rate, or {@code null} when unavailable
     * @param cpuStddevMillis population stddev of the measured CPU rate, or {@code null}
     * @return the confidence in {@code [0,1]}, rounded to 3 decimal places
     */
    public static double of(double sampleCoverage, Long cpuMeanMillis, Long cpuStddevMillis) {
        double sampleFraction = clamp01(sampleCoverage);
        double volatilityFactor = volatilityFactor(cpuMeanMillis, cpuStddevMillis);
        double score = 0.5 * sampleFraction + 0.5 * volatilityFactor;
        return round3(clamp01(score));
    }

    /**
     * {@code HIGH} when {@code score >= highThreshold}, {@code MEDIUM} when {@code score >= floor},
     * else {@code LOW}. Both edges are supplied by the caller — no literal is baked in.
     */
    public static Band band(double score, double floor, double highThreshold) {
        if (score >= highThreshold) {
            return Band.HIGH;
        }
        if (score >= floor) {
            return Band.MEDIUM;
        }
        return Band.LOW;
    }

    private static double volatilityFactor(Long cpuMeanMillis, Long cpuStddevMillis) {
        if (cpuMeanMillis == null || cpuStddevMillis == null || cpuMeanMillis == 0L) {
            return 1.0;
        }
        double cv = (double) cpuStddevMillis / (double) cpuMeanMillis;
        return clamp01(1.0 - cv);
    }

    private static double clamp01(double value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(1.0, value);
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
