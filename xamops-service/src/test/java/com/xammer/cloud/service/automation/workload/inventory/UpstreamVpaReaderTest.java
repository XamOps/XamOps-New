package com.xammer.cloud.service.automation.workload.inventory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
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
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upstream-VPA comparison read: real targets when the optional rule is granted, and
 * {@code null} plus a finding when it is not.
 *
 * <p>The whole point of the column is that a reader can check XamOps against the thing they already
 * trust. A fabricated or stale-looking-current number would defeat that, so the two cases this
 * fixes in place are "no number, because VPA has none" and "no number, because we were not allowed
 * to look" — which must not look the same.
 */
class UpstreamVpaReaderTest {

    private static final Path FIXTURES = Path.of("src/test/resources/k8s/inventory");
    private static final String VPA_PATH = "/apis/autoscaling.k8s.io/v1/verticalpodautoscalers";

    private static HttpServer server;
    private static String baseUrl;
    private static int responseStatus = 200;
    private static String fixture = "vpas.json";

    private KubernetesClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", UpstreamVpaReaderTest::respond);
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
    void connect() {
        responseStatus = 200;
        fixture = "vpas.json";
        client = new KubernetesClientBuilder()
                // No retry: the 403/404 cases here are deliberate, and the default backoff would
                // spend seconds proving each one.
                .withConfig(new ConfigBuilder().withMasterUrl(baseUrl)
                        .withRequestRetryBackoffLimit(0).build())
                .build();
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("a recorded VPA status maps per container, matched by spec.targetRef")
    void targetsMapPerContainer() {
        UpstreamVpaReader.View view = new UpstreamVpaReader().read(client);

        assertThat(view.readable()).isTrue();

        Map<String, UpstreamVpaReader.ContainerTarget> api = view.forWorkload(
                new WorkloadRef("team-a", "Deployment", "overprovisioned-api", null));
        assertThat(api).containsOnlyKeys("idler");
        assertThat(api.get("idler").cpuMillis()).isEqualTo(26);
        assertThat(api.get("idler").memBytes())
                .as("262144k is 250Mi exactly; a rounding Value()-equivalent would read 1 core/1Mi")
                .isEqualTo(262_144_000L);
        assertThat(api.get("idler").observedAt())
                .as("a comparison shown without saying how old it is invites the reader to assume"
                        + " it is current")
                .isEqualTo(Instant.parse("2026-10-11T09:15:00Z"));
    }

    @Test
    @DisplayName("a sidecar gets its own target, never the main container's")
    void sidecarsAreDistinct() {
        Map<String, UpstreamVpaReader.ContainerTarget> targets = new UpstreamVpaReader().read(client)
                .forWorkload(new WorkloadRef("dev-sim", "Deployment", "no-requests-sidecar-app", null));

        assertThat(targets).containsOnlyKeys("app", "logshipper");
        assertThat(targets.get("app").cpuMillis()).isEqualTo(25);
        assertThat(targets.get("logshipper").cpuMillis()).isEqualTo(11);
        assertThat(targets.get("logshipper").memBytes()).isEqualTo(104_857_600L);
    }

    @Test
    @DisplayName("a VPA with no recommendation yet contributes nothing, rather than a zero")
    void anUnrecommendedWorkloadIsAbsent() {
        UpstreamVpaReader.View view = new UpstreamVpaReader().read(client);

        assertThat(view.forWorkload(new WorkloadRef("prod-sim", "Deployment", "rightsized-worker", null)))
                .isEmpty();
        assertThat(view.readable())
                .as("'VPA has no opinion yet' is not 'we could not look'")
                .isTrue();
    }

    @Test
    @DisplayName("an unreadable quantity is dropped, not rounded and not zeroed")
    void anUnreadableTargetIsDropped() {
        assertThat(new UpstreamVpaReader().read(client)
                .forWorkload(new WorkloadRef("team-b", "Deployment", "php-apache", null)))
                .isEmpty();
    }

    @Test
    @DisplayName("a workload with no VPA object at all has no target")
    void aWorkloadWithoutAVpaHasNoTarget() {
        assertThat(new UpstreamVpaReader().read(client)
                .forWorkload(new WorkloadRef("team-b", "Deployment", "load-generator", null)))
                .isEmpty();
    }

    @Test
    @DisplayName("403 — the optional ClusterRole rule was not granted — is a finding, not a number")
    void forbiddenIsAFinding() {
        responseStatus = 403;

        UpstreamVpaReader.View view = new UpstreamVpaReader().read(client);

        assertThat(view.readable()).isFalse();
        assertThat(view.byWorkload()).isEmpty();
        assertThat(view.forWorkload(new WorkloadRef("team-a", "Deployment", "overprovisioned-api", null)))
                .isEmpty();
        assertThat(UpstreamVpaReader.FINDING_NOT_READABLE).isEqualTo("VPA_NOT_READABLE");
    }

    @Test
    @DisplayName("404 — upstream VPA is not installed — is also not readable, and does not throw")
    void aMissingCrdIsNotFatal() {
        responseStatus = 404;

        assertThat(new UpstreamVpaReader().read(client).readable()).isFalse();
    }

    @Test
    @DisplayName("notReadable() and none() are different answers, so the UI can tell them apart")
    void theTwoEmptyStatesAreDistinguishable() {
        assertThat(UpstreamVpaReader.View.notReadable().readable()).isFalse();
        assertThat(UpstreamVpaReader.View.none().readable()).isTrue();
        assertThat(UpstreamVpaReader.View.none().byWorkload()).isEmpty();
    }

    private static void respond(HttpExchange exchange) throws IOException {
        byte[] body = responseStatus == 200
                ? read(fixture)
                : ("{\"kind\":\"Status\",\"code\":" + responseStatus + ",\"message\":\"nope\"}")
                        .getBytes(StandardCharsets.UTF_8);
        boolean vpaPath = exchange.getRequestURI().getPath().equals(VPA_PATH);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(vpaPath ? responseStatus : 404, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] read(String name) {
        try {
            return Files.readAllBytes(FIXTURES.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
