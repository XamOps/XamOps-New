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

    /**
     * Baselines keyed by hour-of-week. A slot uses its own samples (the weekly rhythm: Monday 09:00 is not Sunday
     * 09:00) once it has {@link #MIN_SAMPLES}; with hourly samples that takes three weeks, because each hour of the
     * week occurs once a week. Until then it falls back to the same hour of the day across every day (the daily
     * rhythm), which needs three days. A slot with neither is omitted: no baseline, no band, no anomaly claim.
     */
    public static Map<Integer, Baseline> compute(List<Sample> samples) {
        Map<Integer, List<Double>> byWeekHour = new HashMap<>();
        Map<Integer, List<Double>> byDayHour = new HashMap<>();
        for (Sample s : samples) {
            if (Double.isFinite(s.value())) {
                int how = hourOfWeek(s.ts());
                byWeekHour.computeIfAbsent(how, k -> new ArrayList<>()).add(s.value());
                byDayHour.computeIfAbsent(how % 24, k -> new ArrayList<>()).add(s.value());
            }
        }
        Map<Integer, Baseline> out = new HashMap<>();
        for (int how = 0; how < 168; how++) {
            List<Double> weekly = byWeekHour.get(how);
            List<Double> daily = byDayHour.get(how % 24);
            if (weekly != null && weekly.size() >= MIN_SAMPLES) {
                out.put(how, of(weekly));
            } else if (daily != null && daily.size() >= MIN_SAMPLES) {
                out.put(how, of(daily));
            }
        }
        return out;
    }

    private static Baseline of(List<Double> vals) {
        double med = median(vals);
        List<Double> dev = new ArrayList<>();
        for (double v : vals) {
            dev.add(Math.abs(v - med));
        }
        return new Baseline(med, median(dev));
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
