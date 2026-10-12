package com.xammer.cloud.service.automation.workload.usage;

import java.util.Map;
import java.util.TreeMap;

/**
 * Pre-aggregated percentiles of one resource over one window, in the engine's canonical unit
 * (millicores for CPU, bytes for memory).
 *
 * <p><strong>{@link #at(int)} throws when the percentile was not supplied.</strong> It does not
 * substitute a neighbour. A provider that computes only p90 must not be able to silently answer a
 * p95 policy: "never swallow a parse failure" extends to "never silently substitute a percentile you
 * were not given", because the whole value of reporting the percentile in the response is that it is
 * the one that was used.
 *
 * @param values percentile → value, in canonical units
 * @param max    the window maximum, or null when the source did not provide it
 */
public record PercentileSet(Map<Integer, Long> values, Long max) {

    public PercentileSet {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("a percentile set with no values is not an observation");
        }
        values = Map.copyOf(new TreeMap<>(values));
        for (Map.Entry<Integer, Long> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 1 || entry.getKey() > 100) {
                throw new IllegalArgumentException("percentile out of range: " + entry.getKey());
            }
            if (entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException(
                        "percentile p" + entry.getKey() + " has no non-negative value");
            }
        }
    }

    /**
     * @return the value at exactly {@code percentile}
     * @throws PercentileUnavailableException if this set does not contain that percentile
     */
    public long at(int percentile) {
        Long value = values.get(percentile);
        if (value == null) {
            throw new PercentileUnavailableException(percentile, values.keySet());
        }
        return value;
    }

    public boolean has(int percentile) {
        return values.containsKey(percentile);
    }

    /** Convenience for fixtures and providers that compute the standard five. */
    public static PercentileSet of(long p50, long p90, long p95, long p99, long max) {
        return new PercentileSet(Map.of(50, p50, 90, p90, 95, p95, 99, p99), max);
    }

    /** A set that holds one percentile only — the honest shape for a single-query provider. */
    public static PercentileSet single(int percentile, long value) {
        return new PercentileSet(Map.of(percentile, value), null);
    }
}
