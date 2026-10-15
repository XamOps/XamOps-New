package com.xammer.cloud.service.automation.workload.execute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The HPA patch request, proved on the wire against a JDK {@link HttpServer} on loopback — the same
 * pattern {@code PodResizeClientTest} uses. What matters is the <strong>exact</strong> request: the
 * path, the method, the content type and the field manager are what the API server routes and RBAC
 * match on, and the body is what decides that only the bounds and the one CPU target move.
 */
class HpaPatchClientTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private static int responseStatus = 200;
    private static String responseBody;

    private KubernetesClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", HpaPatchClientTest::respond);
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
        requests.clear();
        responseStatus = 200;
        responseBody = answeredHpa();
        client = new KubernetesClientBuilder()
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
    @DisplayName("a PATCH to the autoscaling/v2 HPA with the strategic-merge content type and field manager")
    void theRequestIsExactlyTheHpaObject() throws Exception {
        new HpaPatchClient().patch(client, "team-b", "php-apache", cpuHpa(50), 1, 8, null);

        assertThat(requests).hasSize(1);
        Recorded recorded = requests.get(0);
        assertThat(recorded.method())
                .as("a strategic-merge PATCH, never a PUT that would replace the whole HPA")
                .isEqualTo("PATCH");
        assertThat(recorded.path())
                .isEqualTo("/apis/autoscaling/v2/namespaces/team-b/horizontalpodautoscalers/php-apache");
        assertThat(recorded.query()).isEqualTo("fieldManager=xamops-hpa");
        assertThat(recorded.contentType()).isEqualTo("application/strategic-merge-patch+json");
    }

    @Test
    @DisplayName("the body carries only the bounds when the target is left null")
    void theBodyIsBoundsOnlyWhenTargetIsNull() {
        String body = new HpaPatchClient().patchBody(cpuHpa(50), 1, 8, null);

        assertThat(body)
                .as("no metrics array at all when the CPU target does not move")
                .isEqualTo("{\"spec\":{\"minReplicas\":1,\"maxReplicas\":8}}");
    }

    @Test
    @DisplayName("a null bound is absent from the body, so the cluster leaves it alone")
    void nullBoundsAreNotSent() {
        String body = new HpaPatchClient().patchBody(cpuHpa(50), null, 8, null);

        assertThat(body)
                .as("minReplicas left alone: not patched with its own current value")
                .isEqualTo("{\"spec\":{\"maxReplicas\":8}}");
    }

    @Test
    @DisplayName("a CPU target move rebuilds the metrics array with only averageUtilization changed")
    void aTargetMoveRebuildsTheOneCpuMetric() throws Exception {
        String body = new HpaPatchClient().patchBody(cpuHpa(50), null, null, 69);

        JsonNode spec = new ObjectMapper().readTree(body).path("spec");
        assertThat(spec.has("minReplicas")).as("no bound was moved").isFalse();
        assertThat(spec.has("maxReplicas")).isFalse();
        JsonNode metric = spec.path("metrics").path(0);
        assertThat(metric.path("type").asText()).isEqualTo("Resource");
        assertThat(metric.path("resource").path("name").asText()).isEqualTo("cpu");
        assertThat(metric.path("resource").path("target").path("averageUtilization").asInt())
                .isEqualTo(69);
        assertThat(metric.path("resource").path("target").path("type").asText())
                .as("utilisation-only: averageValue must not be left beside the new utilisation")
                .isEqualTo("Utilization");
    }

    @Test
    @DisplayName("a non-CPU metric beside the CPU one is carried through unchanged, never dropped")
    void nonCpuMetricsArePreserved() throws Exception {
        HorizontalPodAutoscaler hpa = new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withNamespace("team-b").withName("php-apache").endMetadata()
                .withNewSpec()
                .withMinReplicas(1).withMaxReplicas(5)
                .addNewMetric().withType("Resource")
                .withNewResource().withName("cpu")
                .withNewTarget().withType("Utilization").withAverageUtilization(50).endTarget()
                .endResource().endMetric()
                .addNewMetric().withType("Resource")
                .withNewResource().withName("memory")
                .withNewTarget().withType("Utilization").withAverageUtilization(80).endTarget()
                .endResource().endMetric()
                .endSpec()
                .build();

        String body = new HpaPatchClient().patchBody(hpa, null, null, 69);

        JsonNode metrics = new ObjectMapper().readTree(body).path("spec").path("metrics");
        assertThat(metrics).hasSize(2);
        assertThat(metrics.path(0).path("resource").path("target").path("averageUtilization").asInt())
                .isEqualTo(69);
        assertThat(metrics.path(1).path("resource").path("name").asText())
                .as("the memory metric survives the rebuild")
                .isEqualTo("memory");
        assertThat(metrics.path(1).path("resource").path("target").path("averageUtilization").asInt())
                .isEqualTo(80);
    }

    @Test
    @DisplayName("the body never carries a request, a limit, or anything but bounds and the CPU target")
    void theBodyCarriesNothingButBoundsAndTarget() throws Exception {
        String body = new HpaPatchClient().patchBody(cpuHpa(50), 1, 8, 69);

        JsonNode root = new ObjectMapper().readTree(body);
        assertThat(root.fieldNames()).toIterable().containsExactly("spec");
        assertThat(root.path("spec").fieldNames()).toIterable()
                .containsExactly("minReplicas", "maxReplicas", "metrics");
        assertThat(body).doesNotContain("requests").doesNotContain("limits");
    }

    @Test
    @DisplayName("moving a target on an HPA with no Resource: cpu metric is rejected before any write")
    void aTargetMoveNeedsACpuMetric() {
        HorizontalPodAutoscaler memOnly = new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withNamespace("team-b").withName("php-apache").endMetadata()
                .withNewSpec().withMinReplicas(1).withMaxReplicas(5)
                .addNewMetric().withType("Resource")
                .withNewResource().withName("memory")
                .withNewTarget().withType("Utilization").withAverageUtilization(80).endTarget()
                .endResource().endMetric()
                .endSpec().build();

        HpaPatchClient patchClient = new HpaPatchClient();
        assertThrows(IllegalArgumentException.class,
                () -> patchClient.patchBody(memOnly, null, null, 69));
    }

    @Test
    @DisplayName("a no-op patch (no bound, no target) cannot produce a body")
    void anEmptyPatchIsRejected() {
        HpaPatchClient patchClient = new HpaPatchClient();
        assertThrows(IllegalArgumentException.class,
                () -> patchClient.patchBody(cpuHpa(50), null, null, null));
    }

    @Test
    @DisplayName("a refusal carries the HTTP status, so a 409 conflict is told apart from no answer")
    void aRefusalCarriesItsStatus() {
        responseStatus = 409;
        responseBody = "{\"kind\":\"Status\",\"code\":409,\"message\":\"the object has been modified\"}";
        HpaPatchClient patchClient = new HpaPatchClient();
        HorizontalPodAutoscaler current = cpuHpa(50);

        HpaPatchClient.HpaPatchRejected rejected = assertThrows(HpaPatchClient.HpaPatchRejected.class,
                () -> patchClient.patch(client, "team-b", "php-apache", current, 1, 8, null));

        assertThat(rejected.code()).isEqualTo(409);
        assertThat(rejected.answered()).isTrue();
        assertThat(rejected.conflict()).isTrue();
    }

    private static HorizontalPodAutoscaler cpuHpa(int targetUtilizationPct) {
        return new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withNamespace("team-b").withName("php-apache")
                .withResourceVersion("12345").endMetadata()
                .withNewSpec()
                .withMinReplicas(1).withMaxReplicas(5)
                .addNewMetric().withType("Resource")
                .withNewResource().withName("cpu")
                .withNewTarget().withType("Utilization").withAverageUtilization(targetUtilizationPct)
                .endTarget().endResource().endMetric()
                .endSpec()
                .build();
    }

    private static String answeredHpa() {
        return "{\"apiVersion\":\"autoscaling/v2\",\"kind\":\"HorizontalPodAutoscaler\","
                + "\"metadata\":{\"namespace\":\"team-b\",\"name\":\"php-apache\","
                + "\"resourceVersion\":\"12346\"},"
                + "\"spec\":{\"minReplicas\":1,\"maxReplicas\":8}}";
    }

    private record Recorded(String method, String path, String query, String contentType, String body) {
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
        byte[] answer = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, answer.length == 0 ? -1 : answer.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(answer);
        }
    }
}
