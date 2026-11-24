package com.xammer.cloud.xampulse.service.query;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a baseline is learned from: the same host-level value the pages show, not the average of a metric's label
 * series. {@code system.cpu.utilization} arrives as one series per CPU state, so averaging them gives 1/8 whatever the
 * load (a constant 12.5% on a host that is 83% busy); memory has six states. Every user of a baseline (the baseline
 * writer, anomaly rules, the bands on Metrics and Host 360) reads the value through this one definition.
 *
 * <p>Values are fractions (0..1) for utilisations, as the agent sends them; the host pages multiply by 100.
 *
 * @param filter   the one label series the value comes from, or null to use every series of the metric
 * @param invert   the value is 1 minus the series (CPU busy = 1 - idle)
 * @param maxOfSeries combine the metric's series by taking the largest (the fullest filesystem), else average them
 */
public record BaselineSignal(String metric, MetricSpec.LabelFilter filter, boolean invert, boolean maxOfSeries) {

    public static final BaselineSignal CPU = new BaselineSignal("system.cpu.utilization",
            new MetricSpec.LabelFilter("state", "=", "idle"), true, false);
    public static final BaselineSignal MEMORY = new BaselineSignal("system.memory.utilization",
            new MetricSpec.LabelFilter("state", "=", "used"), false, false);
    public static final BaselineSignal DISK = new BaselineSignal("system.filesystem.utilization", null, false, true);
    public static final BaselineSignal LOAD = new BaselineSignal("system.cpu.load_average.1m", null, false, false);

    private static final Map<String, BaselineSignal> BY_METRIC = Map.of(
            CPU.metric(), CPU, MEMORY.metric(), MEMORY, DISK.metric(), DISK, LOAD.metric(), LOAD);

    /** The definition for a baseline metric; empty for a metric XamPulse does not learn a normal range for. */
    public static Optional<BaselineSignal> of(String metric) {
        return Optional.ofNullable(metric == null ? null : BY_METRIC.get(metric));
    }

    /** Label filters that select the series the value is read from. */
    public List<MetricSpec.LabelFilter> filters() {
        return filter == null ? List.of() : List.of(filter);
    }

    /** The host-level value from the selected series' value. */
    public double value(double series) {
        return invert ? 1 - series : series;
    }

    /**
     * A band learned on the host-level value, expressed for a chart of the selected series: for CPU the chart plots
     * idle, so the busy band [lo, hi] becomes [1 - hi, 1 - lo].
     */
    public double[] bandForSeries(double lo, double hi) {
        double[] v = bandOnValue(lo, hi);
        return invert ? new double[] {1 - v[1], 1 - v[0]} : v;
    }

    /** A band on the host-level value itself (Host 360 plots CPU busy), clamped to what the value can be. */
    public double[] bandOnValue(double lo, double hi) {
        double top = metric.endsWith(".utilization") ? 1 : Double.MAX_VALUE;
        return new double[] {Math.max(0, Math.min(top, lo)), Math.max(0, Math.min(top, hi))};
    }
}
