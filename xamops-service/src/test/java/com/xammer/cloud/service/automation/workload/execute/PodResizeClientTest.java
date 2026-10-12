package com.xammer.cloud.service.automation.workload.execute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.Pod;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The resize request, proved on the wire against a JDK {@link HttpServer} on loopback.
 *
 * <p>Same pattern as {@code PrometheusProxyTransportTest} and {@code K8sClientFactoryTlsTest}: the
 * module has no {@code kubernetes-server-mock} and no WireMock dependency, and what matters here is
 * the <strong>exact</strong> request — the path, the method, the content type and the field manager
 * are what the API server's subresource routing and RBAC match on, and the body is what decides
 * whether a workload keeps its QoS class.
 */
class PodResizeClientTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private static int responseStatus = 200;
    private static String responseBody;

    private KubernetesClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", PodResizeClientTest::respond);
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
    void reset() throws IOException {
        requests.clear();
        responseStatus = 200;
        responseBody = fixture("resize-completed.json");
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
    @DisplayName("a PATCH to pods/{name}/resize with the strategic-merge content type and the field manager")
    void theRequestIsExactlyTheResizeSubresource() throws Exception {
        new PodResizeClient().patchResize(client, "team-a", "overprovisioned-api-5d9f7c-abcde",
                List.of(new ContainerApplyTarget("api", 25, 25, 262_144_000L, 262_144_000L)));

        assertThat(requests).hasSize(1);
        Recorded recorded = requests.get(0);
        assertThat(recorded.method())
                .as("the subresource only accepts a PATCH; a PUT would replace the whole pod")
                .isEqualTo("PATCH");
        assertThat(recorded.path())
                .isEqualTo("/api/v1/namespaces/team-a/pods/overprovisioned-api-5d9f7c-abcde/resize");
        assertThat(recorded.query())
                .as("server-side-apply ownership, so a field this feature wrote is attributable")
                .isEqualTo("fieldManager=xamops-vertical-rightsizer");
        assertThat(recorded.contentType())
                .as("fabric8 7.8.0's Client.raw hard-codes application/json, which the API server "
                        + "rejects for this PATCH -- which is why the request is built by hand")
                .isEqualTo("application/strategic-merge-patch+json");
    }

    @Test
    @DisplayName("the body carries only the diffed container resources, requests and limits together")
    void theBodyIsTheDiffWithRequestsAndLimitsTogether() throws Exception {
        new PodResizeClient().patchResize(client, "team-a", "overprovisioned-api-5d9f7c-abcde",
                List.of(new ContainerApplyTarget("api", 25, 25, 262_144_000L, 262_144_000L)));

        JsonNode body = new ObjectMapper().readTree(requests.get(0).body());
        JsonNode container = body.path("spec").path("containers").path(0);

        assertThat(body.has("metadata")).as("a resize patch must not carry anything but resources").isFalse();
        assertThat(container.path("name").asText()).isEqualTo("api");
        assertThat(container.fieldNames()).toIterable().containsExactly("name", "resources");
        assertThat(container.path("resources").path("requests").path("cpu").asText()).isEqualTo("25m");
        assertThat(container.path("resources").path("requests").path("memory").asText()).isEqualTo("250Mi");
        assertThat(container.path("resources").path("limits").path("cpu").asText())
                .as("a request sent without its limit is what silently downgraded Guaranteed to "
                        + "Burstable in the measured reference run")
                .isEqualTo("25m");
        assertThat(container.path("resources").path("limits").path("memory").asText()).isEqualTo("250Mi");
    }

    @Test
    @DisplayName("a field left null is absent from the body, so the cluster leaves it alone")
    void unchangedFieldsAreNotSent() {
        String body = new PodResizeClient().patchBody(
                List.of(new ContainerApplyTarget("php-apache", null, null, 524_288_000L, null)));

        assertThat(body)
                .as("the HPA-frozen case: CPU is not in the patch at all, rather than patched with "
                        + "its own current value")
                .isEqualTo("{\"spec\":{\"containers\":[{\"name\":\"php-apache\","
                        + "\"resources\":{\"requests\":{\"memory\":\"500Mi\"}}}]}}");
    }

    @Test
    @DisplayName("every container in one patch, so a multi-container pod is resized once")
    void severalContainersArePatchedTogether() throws Exception {
        new PodResizeClient().patchResize(client, "dev-sim", "sidecar-app-1",
                List.of(new ContainerApplyTarget("app", 50, 100, null, null),
                        new ContainerApplyTarget("sidecar", 25, 25, null, null)));

        JsonNode containers = new ObjectMapper().readTree(requests.get(0).body())
                .path("spec").path("containers");

        assertThat(containers).hasSize(2);
        assertThat(containers.path(0).path("name").asText()).isEqualTo("app");
        assertThat(containers.path(1).path("name").asText())
                .as("a sidecar is addressed by name, never given the main container's number")
                .isEqualTo("sidecar");
    }

    @Test
    @DisplayName("the answer is parsed as the updated pod, which is the first status sample")
    void theAnswerIsTheUpdatedPod() throws Exception {
        Pod answered = new PodResizeClient().patchResize(client, "team-a",
                "overprovisioned-api-5d9f7c-abcde",
                List.of(new ContainerApplyTarget("api", 25, 25, 262_144_000L, 262_144_000L)));

        assertThat(answered.getMetadata().getUid()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(PodResizeStatus.of(answered).container("api").observed().cpuRequestMillis())
                .isEqualTo(25);
    }

    @Test
    @DisplayName("a refusal carries the HTTP status, so 'the kubelet said no' is not 'no answer'")
    void aRefusalCarriesItsStatus() {
        responseStatus = 422;
        responseBody = "{\"kind\":\"Status\",\"code\":422,\"message\":\"pod updates may not change"
                + " fields other than spec.containers[*].resources\"}";
        PodResizeClient resizeClient = new PodResizeClient();
        List<ContainerApplyTarget> targets =
                List.of(new ContainerApplyTarget("api", 25, 25, 262_144_000L, 262_144_000L));

        PodResizeClient.ResizeRejected rejected = assertThrows(PodResizeClient.ResizeRejected.class,
                () -> resizeClient.patchResize(client, "team-a", "overprovisioned-api-5d9f7c-abcde",
                        targets));

        assertThat(rejected.code()).isEqualTo(422);
        assertThat(rejected.answered()).isTrue();
        assertThat(rejected.getMessage()).contains("may not change fields");
    }

    @Test
    @DisplayName("an accepted patch that returns no pod is a refusal, not a silent success")
    void anEmptyAnswerIsNotASuccess() {
        responseBody = "";
        PodResizeClient resizeClient = new PodResizeClient();
        List<ContainerApplyTarget> targets =
                List.of(new ContainerApplyTarget("api", 25, 25, 262_144_000L, 262_144_000L));

        PodResizeClient.ResizeRejected rejected = assertThrows(PodResizeClient.ResizeRejected.class,
                () -> resizeClient.patchResize(client, "team-a", "overprovisioned-api-5d9f7c-abcde",
                        targets));

        assertThat(rejected.getMessage()).contains("cannot be confirmed");
    }

    @Test
    @DisplayName("an empty target list cannot produce a patch")
    void anEmptyPatchIsRejected() {
        PodResizeClient resizeClient = new PodResizeClient();

        assertThrows(IllegalArgumentException.class, () -> resizeClient.patchBody(List.of()));
    }

    static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/k8s/pods", name), StandardCharsets.UTF_8);
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
        // -1, not 0: a length of 0 means "chunked, length unknown" to HttpServer.
        exchange.sendResponseHeaders(responseStatus, answer.length == 0 ? -1 : answer.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(answer);
        }
    }
}
