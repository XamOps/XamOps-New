package com.xammer.cloud.service.automation.workload.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.List;

/**
 * The response body of {@code GET /api/eks/automation/usage/{cluster}}: CPU or memory over a window,
 * for the cluster, every namespace, or one workload.
 *
 * <p>Every field is always present, {@code null} when it does not apply, so the wire shape does not
 * change with the scope or the outcome. A source failure is a {@code 200} with
 * {@link Unavailable} set, {@code series} empty and {@code coverage} null — never a fabricated series.
 *
 * @param schemaVersion 1
 * @param scope         {@code cluster | namespace | workload}
 * @param resource      {@code cpu | memory}
 * @param unit          {@code cores | bytes}: Prometheus' own units, unconverted
 * @param workloadRef   the workload for {@code scope=workload}; null otherwise
 * @param window        {@code 24h | 72h | 168h | custom}
 * @param stepSeconds   the distance between points
 * @param start         the first point's time
 * @param end           the last point's time
 * @param source        where the numbers come from
 * @param series        cluster and workload scopes; empty for {@code scope=namespace} and when
 *                      unavailable
 * @param namespaces    {@code scope=namespace} only, one entry per namespace; null otherwise
 * @param coverage      how much of the window the {@code used} series has samples for; null when
 *                      unavailable
 * @param unavailable   why there is no data; null when there is
 */
public record UsageSeriesDto(int schemaVersion, String scope, String resource, String unit,
                             Ref workloadRef, String window, int stepSeconds, Instant start, Instant end,
                             Source source, List<Series> series, List<NamespaceSeries> namespaces,
                             Coverage coverage, Unavailable unavailable) {

    public UsageSeriesDto {
        series = series == null ? List.of() : List.copyOf(series);
        namespaces = namespaces == null ? null : List.copyOf(namespaces);
    }

    /** A workload, by namespace, kind and name. */
    public record Ref(String namespace, String kind, String name) {
    }

    /**
     * @param type    {@code PROMETHEUS_PROXY}: the cluster's own Prometheus, through the Kubernetes
     *                API service proxy
     * @param service {@code namespace/service:port}
     */
    public record Source(String type, String service) {

        public static final String PROMETHEUS_PROXY = "PROMETHEUS_PROXY";

        public static Source prometheusProxy(String service) {
            return new Source(PROMETHEUS_PROXY, service);
        }
    }

    /**
     * One point, on the wire as {@code [epochSeconds, value]}; {@code value} is {@code null} where
     * nothing was measured, never 0.
     */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @JsonPropertyOrder({"t", "v"})
    public record Point(long t, Double v) {
    }

    /**
     * @param key    stable machine key; equal to {@code kind} here
     * @param kind   {@code used | request | limit | allocatable | capacity}
     * @param label  the legend word (glossary §15)
     * @param points one per step from {@code start} to {@code end}
     * @param query  the PromQL that produced them, for the chart's source line
     */
    public record Series(String key, String kind, String label, List<Point> points, String query) {

        public Series {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    /**
     * @param requestedNow the {@code request} series' last point (Running pods); null when not measured
     * @param usedAvg      the mean of the {@code used} series' measured points over the window; null
     *                     when none was measured
     * @param series       {@code used} and {@code request} for this namespace
     */
    public record NamespaceSeries(String namespace, Double requestedNow, Double usedAvg, List<Series> series) {

        public NamespaceSeries {
            series = series == null ? List.of() : List.copyOf(series);
        }
    }

    /**
     * @param firstSampleAt the first point at which {@code used} has a sample; null when it has none.
     *                      Points before it are null — "no data before", not zero
     * @param covered       the share of points, 0 to 1, at which {@code used} has a sample
     */
    public record Coverage(Instant firstSampleAt, double covered) {
    }

    /**
     * @param code    {@code ENGINE_DISABLED | CLUSTER_ACCESS_NOT_CONFIGURED | CLUSTER_UNREACHABLE |
     *                NO_METRICS_SOURCE | METRICS_TIMEOUT | METRICS_UNREADABLE}, or
     *                {@code INSUFFICIENT_HISTORY} for a workload with no running pod to select by
     * @param message the reason in words
     */
    public record Unavailable(String code, String message) {
    }
}
