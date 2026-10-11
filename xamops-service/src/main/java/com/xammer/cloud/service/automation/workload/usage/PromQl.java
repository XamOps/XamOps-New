package com.xammer.cloud.service.automation.workload.usage;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The PromQL this engine sends. Pure string building, no I/O, so every query is assertable.
 *
 * <h2>The selector is the whole point (audit bug 8)</h2>
 *
 * Every query here carries <strong>three</strong> things: the workload's namespace, the
 * <strong>owner-resolved pod names</strong>, and the container names. The Go rightsizer queried
 * {@code pod=~"overprovisioned-api-.*"} with no namespace, so one metric was collected per Deployment
 * <em>name</em> and applied identically to every container, sidecars included — and a workload in
 * another namespace with the same name prefix contributed to it. There is no method in this class
 * that can build a name-prefix selector: {@link #selector} takes an explicit pod list, and
 * {@link #podMatcher} regex-escapes every name, so {@code .*} cannot appear in a built query.
 *
 * <h2>Results are grouped {@code by (container)}</h2>
 *
 * One query per signal per workload, aggregated {@code by (container)} — so a sidecar is still a
 * distinct answer, but a workload with two containers costs two aggregations rather than two round
 * trips. Across pods the aggregation is {@code max}, not {@code avg}: a request is satisfied per pod,
 * so the basis has to be the busiest replica. Averaging would under-size every replica above the
 * mean.
 *
 * <h2>The offset</h2>
 *
 * Every range and subquery carries {@code offset <ignoreContainerStartSeconds>s}. A container that
 * started inside that offset has no samples in the evaluated window at all, so it is reported as
 * {@code INSUFFICIENT_HISTORY} rather than sized from its first couple of scrapes.
 */
public final class PromQl {

    /** CPU is read as a rate over this window before the percentile is taken, as upstream VPA does. */
    private static final String CPU_RATE_WINDOW = "5m";

    private static final Pattern REGEX_META = Pattern.compile("([\\\\.+*?()|\\[\\]{}^$])");

    private PromQl() {
    }

    /**
     * The measured CPU percentile per container, in <strong>millicores</strong>.
     *
     * <p>{@code quantile_over_time} over a {@code rate(...)} subquery: the rate is cores, the
     * percentile is taken across the window, and the {@code * 1000} is the only unit conversion in
     * the query — everything downstream of the provider is already in the engine's canonical units.
     */
    public static String cpuPercentileMillis(Selector selector, int percentile, int windowHours,
                                             int offsetSeconds) {
        return "max by (container) (quantile_over_time(" + quantile(percentile)
                + ", rate(container_cpu_usage_seconds_total{" + selector.labels() + "}["
                + CPU_RATE_WINDOW + "])" + subquery(windowHours, offsetSeconds) + ")) * 1000";
    }

    /**
     * The memory working-set <strong>maximum</strong> over the window, in bytes.
     *
     * <p>Max rather than a percentile, deliberately: a memory shortfall OOM-kills immediately and
     * irreversibly, so the brief specifies p100 for memory. {@link PercentileSet#at(int)} must answer
     * exactly the policy's percentile or throw, so the provider files this value under the requested
     * percentile <em>and</em> carries it as the window max — see
     * {@link PrometheusUsageHistoryProvider}.
     */
    public static String memoryMaxBytes(Selector selector, int windowHours, int offsetSeconds) {
        return "max by (container) (max_over_time(container_memory_working_set_bytes{"
                + selector.labels() + "}" + subquery(windowHours, offsetSeconds) + "))";
    }

    /**
     * Throttled CFS periods over total CFS periods, in [0,1].
     *
     * <p>Summed over the window rather than sampled, so a short burst of throttling cannot read as a
     * permanently throttled container, and a permanently throttled one cannot read as clean.
     */
    public static String cpuThrottleRatio(Selector selector, int windowHours, int offsetSeconds) {
        String range = range(windowHours, offsetSeconds);
        return "sum by (container) (rate(container_cpu_cfs_throttled_periods_total{"
                + selector.labels() + "}" + range + "))"
                + " / sum by (container) (rate(container_cpu_cfs_periods_total{"
                + selector.labels() + "}" + range + "))";
    }

    /**
     * OOM kills observed in the window, from kube-state-metrics.
     *
     * <p>The series only exists for a container that has actually terminated with a reason, so an
     * empty result means "no OOM kill was recorded", not "no source". That distinction matters: the
     * provider must not turn a clean container into {@code INSUFFICIENT_HISTORY}, and must not turn a
     * missing kube-state-metrics into a fabricated zero for a container that did OOM — which is why
     * the CPU series, not this one, decides whether a container was measured at all.
     */
    public static String oomKills(Selector selector, int windowHours, int offsetSeconds) {
        return "sum by (container) (max_over_time(kube_pod_container_status_last_terminated_reason{"
                + selector.labels() + ",reason=\"OOMKilled\"}" + range(windowHours, offsetSeconds) + "))";
    }

    /**
     * The real number of CPU samples in the window, per container.
     *
     * <p>Together with {@link #firstSampleTimestamp} and {@link #lastSampleTimestamp} this is where
     * {@code sampleCoverage} and the window bounds come from — measured, never assumed from the
     * clock, and never assumed from a configured scrape interval.
     */
    public static String sampleCount(Selector selector, int windowHours, int offsetSeconds) {
        return "max by (container) (count_over_time(container_cpu_usage_seconds_total{"
                + selector.labels() + "}" + range(windowHours, offsetSeconds) + "))";
    }

    /** Unix seconds of the earliest observation in the window. */
    public static String firstSampleTimestamp(Selector selector, int windowHours, int offsetSeconds) {
        return "min by (container) (min_over_time(timestamp(container_cpu_usage_seconds_total{"
                + selector.labels() + "})" + subquery(windowHours, offsetSeconds) + "))";
    }

    /** Unix seconds of the latest observation in the window. */
    public static String lastSampleTimestamp(Selector selector, int windowHours, int offsetSeconds) {
        return "max by (container) (max_over_time(timestamp(container_cpu_usage_seconds_total{"
                + selector.labels() + "})" + subquery(windowHours, offsetSeconds) + "))";
    }

    /**
     * A label selector that cannot be built without a namespace, a pod list and a container list.
     *
     * @param namespace      the workload's namespace; mandatory
     * @param podNames       the owner-resolved pod names; mandatory and non-empty
     * @param containerNames the containers to measure; mandatory and non-empty
     */
    public record Selector(String namespace, List<String> podNames, List<String> containerNames) {

        public Selector {
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException(
                        "a usage query without a namespace is how one metric ends up shared between two"
                                + " workloads of the same name");
            }
            if (podNames == null || podNames.isEmpty()) {
                throw new IllegalArgumentException(
                        "a usage query without owner-resolved pod names is how pod=~\"<name>-.*\" comes"
                                + " back; resolve pods through ownerReferences first");
            }
            if (containerNames == null || containerNames.isEmpty()) {
                throw new IllegalArgumentException(
                        "a usage query must name its containers: usage is per container, sidecars"
                                + " distinct");
            }
            podNames = List.copyOf(podNames);
            containerNames = List.copyOf(containerNames);
        }

        /** {@code namespace="…",pod=~"…",container=~"…"} — the three mandatory dimensions. */
        public String labels() {
            return "namespace=\"" + namespace + "\",pod=~\"" + podMatcher(podNames)
                    + "\",container=~\"" + podMatcher(containerNames) + "\"";
        }
    }

    /**
     * An alternation of exactly these names, every metacharacter escaped.
     *
     * <p>Prometheus anchors {@code =~} on both ends, so {@code pod=~"a|b"} matches {@code a} or
     * {@code b} and nothing else — which is what makes an explicit list exact rather than merely
     * narrower than a prefix.
     */
    static String podMatcher(List<String> names) {
        return names.stream().map(PromQl::escape).reduce((a, b) -> a + "|" + b).orElseThrow();
    }

    private static String escape(String literal) {
        return REGEX_META.matcher(literal).replaceAll("\\\\$1");
    }

    /** {@code 0.95} for p95 — Prometheus wants a fraction, the policy speaks percentiles. */
    private static String quantile(int percentile) {
        return String.format(Locale.ROOT, "%.2f", percentile / 100.0);
    }

    /** {@code [24h] offset 120s} — a plain range vector. */
    private static String range(int windowHours, int offsetSeconds) {
        return "[" + windowHours + "h]" + offset(offsetSeconds);
    }

    /** {@code [24h:] offset 120s} — a subquery at the server's own evaluation resolution. */
    private static String subquery(int windowHours, int offsetSeconds) {
        return "[" + windowHours + "h:]" + offset(offsetSeconds);
    }

    private static String offset(int offsetSeconds) {
        return offsetSeconds <= 0 ? "" : " offset " + offsetSeconds + "s";
    }
}
