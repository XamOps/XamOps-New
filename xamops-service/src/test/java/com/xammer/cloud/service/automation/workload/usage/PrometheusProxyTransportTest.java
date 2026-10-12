package com.xammer.cloud.service.automation.workload.usage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The transport, proved against a real HTTP server on loopback.
 *
 * <p>A JDK {@link HttpServer} on {@code 127.0.0.1:0} with a fabric8 client pointed at it — the
 * precedent is {@code K8sClientFactoryTlsTest}, and the module has no
 * {@code kubernetes-server-mock} or WireMock dependency. There is no network egress and no cluster
 * contact: what is asserted is the exact path the client puts on the wire, which is the thing the
 * Kubernetes API server's RBAC matches on.
 */
class PrometheusProxyTransportTest {

    private static final String SUCCESS =
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";

    private static HttpServer server;
    private static String baseUrl;
    private static final List<String> requestedUris = new ArrayList<>();
    private static int responseStatus = 200;
    private static String responseBody = SUCCESS;

    private KubernetesClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", PrometheusProxyTransportTest::respond);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        requestedUris.clear();
        responseStatus = 200;
        responseBody = SUCCESS;
        client = new KubernetesClientBuilder()
                // No retry: the failure cases here are deliberate, and the client's default
                // exponential backoff would spend ten seconds proving each one.
                .withConfig(new ConfigBuilder().withMasterUrl(baseUrl)
                        .withRequestRetryBackoffLimit(0).build())
                .build();
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("the query goes through the API service proxy, at exactly the documented path")
    void theProxiedPathIsExact() throws Exception {
        PrometheusProxyTransport transport = transport();

        String body = transport.instantQuery(client, "up");

        assertThat(body).isEqualTo(SUCCESS);
        assertThat(requestedUris).hasSize(1);
        assertThat(requestedUris.get(0))
                .as("RBAC matches services/proxy on the name as it appears in the URL, so this path"
                        + " shape is the thing the committed ClusterRole grants")
                .startsWith("/api/v1/namespaces/monitoring/services/prometheus-server:9090/proxy"
                        + "/api/v1/query?query=up");
    }

    @Test
    @DisplayName("the namespace, service and port come from configuration, not from constants")
    void theTargetIsConfigurable() throws Exception {
        PrometheusProxyTransport transport = new PrometheusProxyTransport(
                new VerticalMetricsProperties("obs", "prom", 9999, 7, 120, 24, "OFF"));

        transport.instantQuery(client, "up");

        assertThat(requestedUris.get(0))
                .startsWith("/api/v1/namespaces/obs/services/prom:9999/proxy/api/v1/query?query=up");
        assertThat(requestedUris.get(0))
                .as("Prometheus is told the timeout too, so a slow query fails instead of hanging")
                .contains("timeout=7s");
        assertThat(transport.describeTarget()).isEqualTo("obs/prom:9999");
    }

    @Test
    @DisplayName("the shipped default port 80 builds the path an installer-provisioned cluster serves")
    void theInstallerDefaultPortIsProxyable() throws Exception {
        // XamOps' own installer never sets server.service.servicePort, so Prometheus lands on the
        // chart default of 80. That -- not the harness's 9090 -- is the shipped default, and the
        // committed ClusterRole grants services/proxy on both names.
        PrometheusProxyTransport transport = new PrometheusProxyTransport(
                new VerticalMetricsProperties("monitoring", "prometheus-server", 80, 20, 120, 24, "OFF"));

        transport.instantQuery(client, "up");

        assertThat(requestedUris.get(0))
                .startsWith("/api/v1/namespaces/monitoring/services/prometheus-server:80/proxy"
                        + "/api/v1/query?query=up");
        assertThat(transport.describeTarget()).isEqualTo("monitoring/prometheus-server:80");
    }

    @Test
    @DisplayName("PromQL is URL-encoded, and decodes back to exactly what was asked")
    void promqlIsUrlEncoded() throws Exception {
        String promql = "max by (container) (quantile_over_time(0.95,"
                + " rate(container_cpu_usage_seconds_total{namespace=\"team-a\",pod=~\"api-1|api-2\","
                + "container=~\"idler\"}[5m])[24h:] offset 120s)) * 1000";

        transport().instantQuery(client, promql);

        String uri = requestedUris.get(0);
        String encoded = uri.substring(uri.indexOf("query=") + "query=".length(), uri.indexOf("&timeout="));
        assertThat(encoded)
                .as("an unencoded '+' or '&' in PromQL would silently truncate the query")
                .doesNotContain("{").doesNotContain("\"").doesNotContain(" ");
        // URLDecoder, not a hand-rolled check: '+' is a space in a query string, and the API server
        // and Prometheus both parse it that way.
        assertThat(URLDecoder.decode(encoded, StandardCharsets.UTF_8)).isEqualTo(promql);
    }

    @Test
    @DisplayName("a 403 is NO_METRICS_SOURCE whose message names the missing ClusterRole rule")
    void forbiddenNamesTheRule() {
        responseStatus = 403;
        responseBody = "{\"kind\":\"Status\",\"code\":403,\"message\":\"forbidden\"}";
        PrometheusProxyTransport transport = transport();

        UsageUnavailableException failure = assertThrows(UsageUnavailableException.class,
                () -> transport.instantQuery(client, "up"));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.NO_METRICS_SOURCE);
        assertThat(failure.getMessage())
                .as("'Forbidden' alone is not something a customer can act on")
                .contains("services/proxy")
                .contains("prometheus-server:9090")
                .contains("monitoring");
    }

    @Test
    @DisplayName("any other transport failure is also NO_METRICS_SOURCE, never an untyped error")
    void otherFailuresAreTyped() {
        responseStatus = 503;
        responseBody = "{\"kind\":\"Status\",\"code\":503,\"message\":\"no endpoints available\"}";
        PrometheusProxyTransport transport = transport();

        UsageUnavailableException failure = assertThrows(UsageUnavailableException.class,
                () -> transport.instantQuery(client, "up"));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.NO_METRICS_SOURCE);
        assertThat(failure.getMessage()).contains("monitoring/prometheus-server:9090");
    }

    @Test
    @DisplayName("an empty body is NO_METRICS_SOURCE, not an empty result set")
    void anEmptyBodyIsNotAnEmptyResult() {
        responseBody = "";
        PrometheusProxyTransport transport = transport();

        UsageUnavailableException failure = assertThrows(UsageUnavailableException.class,
                () -> transport.instantQuery(client, "up"));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.NO_METRICS_SOURCE);
    }

    @Test
    @DisplayName("reachable() proves the whole proxy chain, and never reads the Service object")
    void reachabilityUsesTheProxyNotTheServiceObject() {
        // The committed ClusterRole grants services/proxy but NOT get on services -- verified live,
        // where `kubectl auth can-i get services -n monitoring` answers no for the role. An
        // OpenCost-style Service-existence probe would therefore report "no metrics source" on a
        // cluster whose Prometheus is perfectly reachable.
        assertThat(transport().reachable(client)).isTrue();
        assertThat(requestedUris).hasSize(1);
        assertThat(requestedUris.get(0)).contains("/services/prometheus-server:9090/proxy/api/v1/query");
        assertThat(requestedUris.get(0)).doesNotContain("/services/prometheus-server?");
    }

    @Test
    @DisplayName("reachable() is false when the proxy refuses, and does not throw")
    void reachabilityIsFalseNotFatal() {
        responseStatus = 403;
        responseBody = "{\"kind\":\"Status\",\"code\":403}";

        assertThat(transport().reachable(client)).isFalse();
    }

    @Test
    @DisplayName("reachable() is false when Prometheus answers without status=success")
    void reachabilityRequiresASuccessStatus() {
        responseBody = "{\"status\":\"error\",\"error\":\"parse error\"}";

        assertThat(transport().reachable(client)).isFalse();
    }

    private static PrometheusProxyTransport transport() {
        return new PrometheusProxyTransport(
                new VerticalMetricsProperties("monitoring", "prometheus-server", 9090, 20, 120, 24, "OFF"));
    }

    private static void respond(HttpExchange exchange) throws IOException {
        requestedUris.add(exchange.getRequestURI().toString());
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
