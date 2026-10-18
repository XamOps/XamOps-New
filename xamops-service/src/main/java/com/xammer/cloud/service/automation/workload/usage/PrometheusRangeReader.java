package com.xammer.cloud.service.automation.workload.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Runs one PromQL range query through {@link PrometheusProxyTransport#rangeQuery} and lays
 * Prometheus' {@code matrix} answer onto a fixed step grid, for the read-only usage charts.
 *
 * <h2>A missing point is null, never 0</h2>
 *
 * Every series comes back as one value per grid step. A step Prometheus did not answer for, and a
 * {@code NaN} or infinite value, is {@code null}: "nothing was measured" and "zero was measured"
 * are different statements, and only one of them belongs on a chart.
 *
 * <h2>Three typed failures</h2>
 *
 * {@link RangeQueryException.Code#NO_METRICS_SOURCE} (the proxy refused or failed, or Prometheus
 * refused the query), {@link RangeQueryException.Code#METRICS_TIMEOUT} (Prometheus' own query timeout,
 * a client or gateway timeout) and {@link RangeQueryException.Code#METRICS_UNREADABLE} (a body that
 * is not a range-query matrix, which carries its first 200 characters for the caller's ERROR log).
 * None of them is a shared reason code: they are the usage endpoints' own {@code unavailable.code}
 * values, so {@link UsageUnavailableException.Kind} keeps exactly its two.
 */
@Component
public class PrometheusRangeReader {

    /** How much of an unreadable body the caller logs. */
    static final int BODY_PREFIX_CHARS = 200;

    private final PrometheusProxyTransport transport;
    private final VerticalMetricsProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PrometheusRangeReader(PrometheusProxyTransport transport, VerticalMetricsProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    /** {@code monitoring/prometheus-server:80}: where this reader's data comes from, for the payload. */
    public String describeTarget() {
        return transport.describeTarget();
    }

    /**
     * @return every series Prometheus answered with, each laid onto {@code grid}; empty when the
     *         query matched nothing
     */
    public List<Series> read(KubernetesClient client, String promql, Grid grid) throws RangeQueryException {
        String body;
        try {
            body = transport.rangeQuery(client, promql, grid.start(), grid.end(), grid.stepSeconds());
        } catch (UsageUnavailableException e) {
            if (isTimeout(e)) {
                throw new RangeQueryException(RangeQueryException.Code.METRICS_TIMEOUT, timeoutMessage(), null, e);
            }
            throw new RangeQueryException(RangeQueryException.Code.NO_METRICS_SOURCE, e.getMessage(), null, e);
        }
        return parse(body, grid);
    }

    /** Package-private so recorded bodies can be parsed without a transport. */
    List<Series> parse(String body, Grid grid) throws RangeQueryException {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception notJson) {
            throw unreadable(body, "is not JSON", notJson);
        }
        if (root == null || !root.isObject()) {
            throw unreadable(body, "is not a JSON object", null);
        }
        String status = root.path("status").asText("");
        if (!"success".equals(status)) {
            if ("timeout".equals(root.path("errorType").asText(""))) {
                throw new RangeQueryException(RangeQueryException.Code.METRICS_TIMEOUT, timeoutMessage(), null, null);
            }
            throw new RangeQueryException(RangeQueryException.Code.NO_METRICS_SOURCE,
                    "Prometheus at " + describeTarget() + " refused the query: "
                            + root.path("error").asText("status=" + status), null, null);
        }
        JsonNode data = root.path("data");
        JsonNode result = data.path("result");
        if (!"matrix".equals(data.path("resultType").asText("")) || !result.isArray()) {
            throw unreadable(body, "is not a range-query matrix", null);
        }
        List<Series> series = new ArrayList<>();
        for (JsonNode entry : result) {
            JsonNode values = entry.path("values");
            if (!values.isArray()) {
                throw unreadable(body, "has a series without values", null);
            }
            Double[] aligned = new Double[grid.points()];
            for (JsonNode pair : values) {
                if (!pair.isArray() || pair.size() < 2 || !pair.get(0).isNumber()) {
                    throw unreadable(body, "has a point that is not [time, value]", null);
                }
                int index = grid.indexOf(Math.round(pair.get(0).asDouble()));
                if (index < 0) {
                    // Off the grid: Prometheus evaluates at start + k * step only, so this cannot be
                    // one of the requested points. Not placed anywhere, rather than placed wrongly.
                    continue;
                }
                aligned[index] = value(pair.get(1).asText(""), body);
            }
            series.add(new Series(labels(entry.path("metric")), aligned));
        }
        return List.copyOf(series);
    }

    /** A sample's value; {@code NaN} and infinities are "not measured", never a number. */
    private Double value(String text, String body) throws RangeQueryException {
        String trimmed = text.trim();
        if ("+Inf".equals(trimmed) || "-Inf".equals(trimmed) || "Inf".equals(trimmed)) {
            return null;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(trimmed);
        } catch (NumberFormatException notANumber) {
            throw unreadable(body, "has a value that is not a number", notANumber);
        }
        return Double.isFinite(parsed) ? parsed : null;
    }

    private static Map<String, String> labels(JsonNode metric) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (metric != null && metric.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = metric.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                labels.put(field.getKey(), field.getValue().asText(""));
            }
        }
        return Collections.unmodifiableMap(labels);
    }

    private String timeoutMessage() {
        return "Prometheus at " + describeTarget() + " did not answer within the "
                + properties.queryTimeoutSeconds() + "s query timeout, so this history is not available"
                + " right now.";
    }

    private RangeQueryException unreadable(String body, String what, Throwable cause) {
        String prefix = body == null ? "" : body.substring(0, Math.min(BODY_PREFIX_CHARS, body.length()));
        return new RangeQueryException(RangeQueryException.Code.METRICS_UNREADABLE,
                "Prometheus at " + describeTarget() + " answered with a body that " + what
                        + ", so no history could be read from it.", prefix, cause);
    }

    /**
     * Whether a transport failure was a timeout: Prometheus' own query timeout (its
     * {@code errorType: timeout}, carried through the proxy in the Kubernetes Status), the API
     * server's gateway timeout, or the client giving up waiting.
     */
    static boolean isTimeout(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable node = failure; node != null && seen.add(node); node = node.getCause()) {
            if (node instanceof TimeoutException || node instanceof InterruptedIOException
                    || node instanceof HttpTimeoutException
                    || node.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            if (node instanceof KubernetesClientException k8s) {
                if (k8s.getCode() == 504) {
                    return true;
                }
                Status status = k8s.getStatus();
                if (status != null && status.getAdditionalProperties() != null
                        && "timeout".equals(String.valueOf(status.getAdditionalProperties().get("errorType")))) {
                    return true;
                }
            }
            String message = node.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("timed out")) {
                // Not "timeout": every proxied URL carries "&timeout=<n>s", so that word alone would
                // classify every failure as one.
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- types

    /**
     * The evaluation instants of one range query: {@code start + k * step} for every {@code k} with
     * {@code start + k * step <= end}. Whole epoch seconds, so every answered point lands on one.
     */
    public record Grid(long startEpochSecond, long endEpochSecond, int stepSeconds) {

        public Grid {
            if (stepSeconds <= 0) {
                throw new IllegalArgumentException("a grid needs a positive step, got " + stepSeconds);
            }
            if (endEpochSecond < startEpochSecond) {
                throw new IllegalArgumentException("a grid needs start <= end");
            }
        }

        public int points() {
            return (int) ((endEpochSecond - startEpochSecond) / stepSeconds) + 1;
        }

        /** Epoch seconds of point {@code index}. */
        public long at(int index) {
            return startEpochSecond + (long) index * stepSeconds;
        }

        /** The point at exactly this instant, or -1 when it is not one of this grid's points. */
        public int indexOf(long epochSecond) {
            long offset = epochSecond - startEpochSecond;
            if (offset < 0 || offset % stepSeconds != 0) {
                return -1;
            }
            long index = offset / stepSeconds;
            return index < points() ? (int) index : -1;
        }

        public Instant start() {
            return Instant.ofEpochSecond(startEpochSecond);
        }

        public Instant end() {
            return Instant.ofEpochSecond(endEpochSecond);
        }

        /** The same span at another step, e.g. the 60 s detail behind a 30-minute chart. */
        public Grid withStep(int step) {
            return new Grid(startEpochSecond, endEpochSecond, step);
        }
    }

    /**
     * One answered series: its labels and one value per grid point, {@code null} where nothing was
     * measured. The array is the reader's own copy; callers treat it as read-only.
     */
    public record Series(Map<String, String> labels, Double[] values) {

        /** A label's value, or null when the series does not carry it. */
        public String label(String key) {
            return labels.get(key);
        }
    }

    /** A range query that produced no usable answer, with the payload's {@code unavailable.code}. */
    public static class RangeQueryException extends Exception {

        public enum Code { NO_METRICS_SOURCE, METRICS_TIMEOUT, METRICS_UNREADABLE }

        private final Code code;
        private final String bodyPrefix;

        public RangeQueryException(Code code, String message, String bodyPrefix, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.bodyPrefix = bodyPrefix;
        }

        public Code code() {
            return code;
        }

        /** The first 200 characters of an unreadable body, for the caller's ERROR log; null otherwise. */
        public String bodyPrefix() {
            return bodyPrefix;
        }
    }
}
