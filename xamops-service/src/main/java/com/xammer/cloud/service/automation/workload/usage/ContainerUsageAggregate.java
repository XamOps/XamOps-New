package com.xammer.cloud.service.automation.workload.usage;

import java.time.Duration;
import java.time.Instant;

/**
 * What one container actually used over one window, pre-aggregated.
 *
 * <p>Units are the engine's canonical pair: <strong>CPU in millicores, memory in bytes</strong>. No
 * quantity string reaches the engine, so a rounding {@code Quantity.Value()}-equivalent cannot be
 * written anywhere downstream (audit bug 3).
 *
 * <p>{@code sampleCoverage} and the explicit window bounds are <strong>mandatory</strong>, so
 * {@code INSUFFICIENT_HISTORY} is computed rather than guessed. The measured harness run had only
 * 4h52m of history against a 24h window, which is exactly the situation these fields exist to make
 * visible instead of quietly recommending from four hours of data.
 *
 * @param containerName     the container, not the pod and not the workload (audit bug 8)
 * @param cpuMillis         CPU percentiles, millicores
 * @param memBytes          memory working-set percentiles, bytes
 * @param cpuThrottleRatio  throttled CFS periods / total CFS periods, in [0,1]
 * @param oomKillsInWindow  OOM kills observed in the window
 * @param windowStart       inclusive start of the observed window
 * @param windowEnd         exclusive end of the observed window
 * @param sampleCoverage    observed / expected samples, in [0,1]
 * @param sourceName        which provider produced this, reported as the recommendation's basis
 * @param cpuMeanMillis     mean of the measured CPU rate, millicores; {@code null} when Prometheus
 *                          returned no finite value (the confidence volatility half then degrades to
 *                          neutral rather than reading a fabricated zero)
 * @param cpuStddevMillis   population standard deviation of the measured CPU rate, millicores;
 *                          {@code null} on the same honest-absence terms as {@code cpuMeanMillis}
 */
public record ContainerUsageAggregate(String containerName,
                                      PercentileSet cpuMillis,
                                      PercentileSet memBytes,
                                      double cpuThrottleRatio,
                                      int oomKillsInWindow,
                                      Instant windowStart,
                                      Instant windowEnd,
                                      double sampleCoverage,
                                      String sourceName,
                                      Long cpuMeanMillis,
                                      Long cpuStddevMillis) {

    public ContainerUsageAggregate {
        if (containerName == null || containerName.isBlank()) {
            throw new IllegalArgumentException("containerName is mandatory: usage is per container");
        }
        if (cpuMillis == null || memBytes == null) {
            throw new IllegalArgumentException("both CPU and memory percentiles are required");
        }
        if (windowStart == null || windowEnd == null || !windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException(
                    "an explicit, positive-length window is mandatory so INSUFFICIENT_HISTORY is"
                            + " computed rather than guessed");
        }
        if (cpuThrottleRatio < 0 || cpuThrottleRatio > 1) {
            throw new IllegalArgumentException("cpuThrottleRatio out of range: " + cpuThrottleRatio);
        }
        if (oomKillsInWindow < 0) {
            throw new IllegalArgumentException("oomKillsInWindow cannot be negative");
        }
        if (sampleCoverage < 0 || sampleCoverage > 1) {
            throw new IllegalArgumentException("sampleCoverage out of range: " + sampleCoverage);
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceName is mandatory: a recommendation must state where its numbers came from");
        }
        if (cpuMeanMillis != null && cpuMeanMillis < 0) {
            throw new IllegalArgumentException("cpuMeanMillis cannot be negative: " + cpuMeanMillis);
        }
        if (cpuStddevMillis != null && cpuStddevMillis < 0) {
            throw new IllegalArgumentException("cpuStddevMillis cannot be negative: " + cpuStddevMillis);
        }
    }

    /**
     * The shape every caller used before the confidence score added the two CPU volatility scalars:
     * both default to {@code null}, so existing fixtures compile and read as "no volatility data",
     * which is the honest neutral case.
     */
    public ContainerUsageAggregate(String containerName, PercentileSet cpuMillis, PercentileSet memBytes,
                                   double cpuThrottleRatio, int oomKillsInWindow, Instant windowStart,
                                   Instant windowEnd, double sampleCoverage, String sourceName) {
        this(containerName, cpuMillis, memBytes, cpuThrottleRatio, oomKillsInWindow, windowStart,
                windowEnd, sampleCoverage, sourceName, null, null);
    }

    public Duration window() {
        return Duration.between(windowStart, windowEnd);
    }
}
