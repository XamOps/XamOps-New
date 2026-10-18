package com.xammer.cloud.service.automation.workload.usage;

import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * How a Prometheus range-query answer becomes grid-aligned values, and how every way it can go
 * wrong becomes a typed {@code unavailable} code. The bodies are shaped like the real ones read from
 * xamops-test-1 ({@code {"status":"success","data":{"resultType":"matrix","result":[...]}}}).
 */
class PrometheusRangeReaderTest {

    /** Five points, 30 minutes apart. */
    private static final PrometheusRangeReader.Grid GRID = new PrometheusRangeReader.Grid(
            1_792_000_000L - 1_792_000_000L % 1800, 1_792_000_000L - 1_792_000_000L % 1800 + 4 * 1800, 1800);

    private static final VerticalMetricsProperties PROPERTIES =
            new VerticalMetricsProperties("monitoring", "prometheus-server", 80, 20, 120, 24, "OFF");

    @Test
    @DisplayName("a missing step, NaN and an infinity are null, never 0; answered points land on their step")
    void gapsStayNull() throws Exception {
        long t0 = GRID.startEpochSecond();
        String body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"namespace\":\"team-b\"},\"values\":[[" + t0 + ",\"0.5\"],[" + (t0 + 3600)
                + ",\"NaN\"],[" + (t0 + 5400) + ",\"+Inf\"],[" + (t0 + 7200) + ",\"0\"]]},"
                + "{\"metric\":{},\"values\":[[" + (t0 + 1800) + ".0,\"2.5e-1\"],[" + (t0 + 1) + ",\"9\"]]}]}}";

        List<PrometheusRangeReader.Series> series = reader(new ScriptedTransport(body)).parse(body, GRID);

        assertThat(series).hasSize(2);
        assertThat(series.get(0).label("namespace")).isEqualTo("team-b");
        assertThat(series.get(0).values()).containsExactly(0.5, null, null, null, 0.0);
        assertThat(series.get(0).values()[4])
                .as("a measured zero is still a zero")
                .isEqualTo(0.0);
        assertThat(series.get(1).labels()).isEmpty();
        assertThat(series.get(1).values())
                .as("a point off the grid is not placed anywhere")
                .containsExactly(null, 0.25, null, null, null);
    }

    @Test
    @DisplayName("a body that is not JSON is METRICS_UNREADABLE and carries at most its first 200 characters")
    void notJsonIsUnreadable() {
        String body = "<html>" + "x".repeat(500) + "</html>";

        PrometheusRangeReader.RangeQueryException failure = assertThrows(
                PrometheusRangeReader.RangeQueryException.class,
                () -> reader(new ScriptedTransport(body)).read(client(), "up", GRID));

        assertThat(failure.code()).isEqualTo(PrometheusRangeReader.RangeQueryException.Code.METRICS_UNREADABLE);
        assertThat(failure.bodyPrefix()).hasSize(200).startsWith("<html>");
        assertThat(failure.getMessage()).contains("monitoring/prometheus-server:80");
    }

    @Test
    @DisplayName("an instant vector where a matrix belongs, or a malformed point, is METRICS_UNREADABLE")
    void theWrongShapeIsUnreadable() {
        long t0 = GRID.startEpochSecond();
        for (String body : List.of(
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"values\":7}]}}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"values\":[[\"t\",\"1\"]]}]}}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"values\":[[" + t0
                        + ",\"one\"]]}]}}",
                "[]")) {
            PrometheusRangeReader.RangeQueryException failure = assertThrows(
                    PrometheusRangeReader.RangeQueryException.class,
                    () -> reader(new ScriptedTransport(body)).read(client(), "up", GRID), body);
            assertThat(failure.code()).as(body)
                    .isEqualTo(PrometheusRangeReader.RangeQueryException.Code.METRICS_UNREADABLE);
        }
    }

    @Test
    @DisplayName("Prometheus' own errorType timeout is METRICS_TIMEOUT; any other refusal is NO_METRICS_SOURCE")
    void prometheusErrorsAreTyped() {
        PrometheusRangeReader.RangeQueryException timeout = assertThrows(
                PrometheusRangeReader.RangeQueryException.class,
                () -> reader(new ScriptedTransport("{\"status\":\"error\",\"errorType\":\"timeout\","
                        + "\"error\":\"query timed out in expression evaluation\"}")).read(client(), "up", GRID));
        PrometheusRangeReader.RangeQueryException refused = assertThrows(
                PrometheusRangeReader.RangeQueryException.class,
                () -> reader(new ScriptedTransport("{\"status\":\"error\",\"errorType\":\"bad_data\","
                        + "\"error\":\"parse error\"}")).read(client(), "up", GRID));

        assertThat(timeout.code()).isEqualTo(PrometheusRangeReader.RangeQueryException.Code.METRICS_TIMEOUT);
        assertThat(timeout.getMessage()).contains("20s");
        assertThat(refused.code()).isEqualTo(PrometheusRangeReader.RangeQueryException.Code.NO_METRICS_SOURCE);
        assertThat(refused.getMessage()).contains("parse error");
    }

    @Test
    @DisplayName("a transport failure is NO_METRICS_SOURCE with its message, unless it was a timeout")
    void transportFailuresAreTyped() {
        UsageUnavailableException forbidden = new UsageUnavailableException(
                UsageUnavailableException.Kind.NO_METRICS_SOURCE, "Grant the ClusterRole rule ...",
                new KubernetesClientException("Forbidden", 403, null));
        UsageUnavailableException clientTimeout = new UsageUnavailableException(
                UsageUnavailableException.Kind.NO_METRICS_SOURCE, "could not be queried",
                new KubernetesClientException("request failed", new SocketTimeoutException("Read timed out")));
        UsageUnavailableException prometheusTimeout = new UsageUnavailableException(
                UsageUnavailableException.Kind.NO_METRICS_SOURCE, "could not be queried (HTTP 503)",
                new KubernetesClientException(new StatusBuilder().withCode(503)
                        .addToAdditionalProperties("errorType", "timeout").build()));
        UsageUnavailableException gatewayTimeout = new UsageUnavailableException(
                UsageUnavailableException.Kind.NO_METRICS_SOURCE, "could not be queried (HTTP 504)",
                new KubernetesClientException("Gateway Timeout", 504, null));
        UsageUnavailableException withTimeoutInTheUrl = new UsageUnavailableException(
                UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                "Failure executing: GET at: https://x/proxy/api/v1/query_range?query=up&timeout=20s. 503",
                new KubernetesClientException("no endpoints available", 503, null));

        assertThat(code(forbidden)).isEqualTo(PrometheusRangeReader.RangeQueryException.Code.NO_METRICS_SOURCE);
        assertThat(message(forbidden)).isEqualTo("Grant the ClusterRole rule ...");
        assertThat(code(clientTimeout)).isEqualTo(PrometheusRangeReader.RangeQueryException.Code.METRICS_TIMEOUT);
        assertThat(code(prometheusTimeout)).isEqualTo(PrometheusRangeReader.RangeQueryException.Code.METRICS_TIMEOUT);
        assertThat(code(gatewayTimeout)).isEqualTo(PrometheusRangeReader.RangeQueryException.Code.METRICS_TIMEOUT);
        assertThat(code(withTimeoutInTheUrl))
                .as("the proxied URL always says timeout=20s; that word alone is not a timeout")
                .isEqualTo(PrometheusRangeReader.RangeQueryException.Code.NO_METRICS_SOURCE);
    }

    @Test
    @DisplayName("the grid: whole steps from start to end, exact positions only, and the same span at 60 s")
    void gridArithmetic() {
        PrometheusRangeReader.Grid day = new PrometheusRangeReader.Grid(0, 86_400, 600);

        assertThat(day.points()).isEqualTo(145);
        assertThat(day.at(144)).isEqualTo(86_400);
        assertThat(day.indexOf(600)).isEqualTo(1);
        assertThat(day.indexOf(601)).isEqualTo(-1);
        assertThat(day.indexOf(-600)).isEqualTo(-1);
        assertThat(day.indexOf(87_000)).isEqualTo(-1);
        assertThat(day.withStep(60).points()).isEqualTo(1441);
        assertThat(new PrometheusRangeReader.Grid(0, 7 * 86_400, 60).points())
                .as("7 days at 60 s stays under Prometheus' 11,000-point limit")
                .isEqualTo(10_081);
        assertThat(day.start()).isEqualTo(Instant.EPOCH);
        assertThrows(IllegalArgumentException.class, () -> new PrometheusRangeReader.Grid(10, 0, 60));
        assertThrows(IllegalArgumentException.class, () -> new PrometheusRangeReader.Grid(0, 10, 0));
    }

    @Test
    @DisplayName("the reader asks the transport for exactly the grid's span and step")
    void theGridIsWhatIsAsked() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}");

        assertThat(reader(transport).read(client(), "up", GRID)).isEmpty();

        assertThat(transport.asked).isEqualTo(Map.of("start", GRID.start(), "end", GRID.end()));
        assertThat(transport.step).isEqualTo(1800);
    }

    // ---------------------------------------------------------------- helpers

    private static PrometheusRangeReader.RangeQueryException.Code code(UsageUnavailableException failure) {
        return assertThrows(PrometheusRangeReader.RangeQueryException.class,
                () -> reader(new ScriptedTransport(failure)).read(client(), "up", GRID)).code();
    }

    private static String message(UsageUnavailableException failure) {
        return assertThrows(PrometheusRangeReader.RangeQueryException.class,
                () -> reader(new ScriptedTransport(failure)).read(client(), "up", GRID)).getMessage();
    }

    private static PrometheusRangeReader reader(ScriptedTransport transport) {
        return new PrometheusRangeReader(transport, PROPERTIES);
    }

    private static KubernetesClient client() {
        return mock(KubernetesClient.class);
    }

    /** Answers every range query with one body, or fails it with one exception. */
    private static final class ScriptedTransport extends PrometheusProxyTransport {

        private final String body;
        private final UsageUnavailableException failure;
        private Map<String, Instant> asked;
        private int step;

        private ScriptedTransport(String body) {
            super(PROPERTIES);
            this.body = body;
            this.failure = null;
        }

        private ScriptedTransport(UsageUnavailableException failure) {
            super(PROPERTIES);
            this.body = null;
            this.failure = failure;
        }

        @Override
        public String rangeQuery(KubernetesClient client, String promql, Instant start, Instant end,
                                 int stepSeconds) throws UsageUnavailableException {
            asked = Map.of("start", start, "end", end);
            step = stepSeconds;
            if (failure != null) {
                throw failure;
            }
            return body;
        }
    }
}
