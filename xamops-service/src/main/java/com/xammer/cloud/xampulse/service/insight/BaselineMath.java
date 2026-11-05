package com.xammer.cloud.xampulse.service.insight;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Median/MAD baselines per hour-of-week (0..167, Monday 00:00 UTC = 0) and the anomaly band derived from them.
 * MAD is scaled by 1.4826 so it compares to a standard deviation. Cold-start rule: an hour-of-week slot needs at
 * least {@link #MIN_SAMPLES} samples, otherwise there is no baseline for it (and so no band).
 */
public final class BaselineMath {

    public static final int MIN_SAMPLES = 3;
    public static final double MAD_SCALE = 1.4826;

    public record Baseline(double median, double mad) {}

    public record Sample(Instant ts, double value) {}

    private BaselineMath() {}

    public static int hourOfWeek(Instant ts) {
        var z = ts.atZone(ZoneOffset.UTC);
        return (z.getDayOfWeek().getValue() - 1) * 24 + z.getHour();
    }

    /** Baselines keyed by hour-of-week; slots with fewer than MIN_SAMPLES are omitted. */
    public static Map<Integer, Baseline> compute(List<Sample> samples) {
        Map<Integer, List<Double>> byHour = new HashMap<>();
        for (Sample s : samples) {
            if (Double.isFinite(s.value())) {
                byHour.computeIfAbsent(hourOfWeek(s.ts()), k -> new ArrayList<>()).add(s.value());
            }
        }
        Map<Integer, Baseline> out = new HashMap<>();
        byHour.forEach((h, vals) -> {
            if (vals.size() >= MIN_SAMPLES) {
                double med = median(vals);
                List<Double> dev = new ArrayList<>();
                for (double v : vals) {
                    dev.add(Math.abs(v - med));
                }
                out.put(h, new Baseline(med, median(dev)));
            }
        });
        return out;
    }

    /** Returns {lower, upper} for {@code k} scaled MADs, or null when there is no baseline (cold start). */
    public static double[] band(Baseline b, double k) {
        if (b == null) {
            return null;
        }
        // A perfectly flat history has MAD 0; keep a small floor so the band is not a zero-width line.
        double spread = Math.max(b.mad() * MAD_SCALE, Math.max(Math.abs(b.median()) * 0.02, 1e-9));
        return new double[] {b.median() - k * spread, b.median() + k * spread};
    }

    public static boolean isAnomalous(double value, Baseline b, double k) {
        double[] band = band(b, k);
        return band != null && (value < band[0] || value > band[1]);
    }

    static double median(List<Double> values) {
        double[] a = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = a.length;
        if (n == 0) {
            return Double.NaN;
        }
        return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2.0;
    }

    static double[] sorted(double[] in) {
        double[] c = Arrays.copyOf(in, in.length);
        Arrays.sort(c);
        return c;
    }
}
