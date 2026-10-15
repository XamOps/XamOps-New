package com.xammer.cloud.service.automation.workload;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
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
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline D1 case proven end to end over the <strong>production read path</strong>:
 * a real fabric8 client reads recorded cluster JSON, {@link ClusterWorkloadInventory#read} enriches
 * the {@link HpaView} from the live HPA's status, and the engine turns it into
 * {@link HpaReason#AT_MAX_UNMET_DEMAND} raising {@code maxReplicas} 5 → 8.
 *
 * <p>This is the test the gap in Defect 1 hid behind: every other HPA-decision test hand-builds a
 * full {@code HpaView} fixture, so none of them could ever catch an inventory that never populated
 * the fields D1 keys off. Here the only HpaView in play is the one the inventory produced.
 */
class HpaReadPathD1Test {

    private static final Path FIXTURES = Path.of("src/test/resources/k8s/inventory");

    // The inventory read needs every list route the reader calls, not just the HPAs.
    private static final Map<String, String> ROUTES = Map.of(
            "/api/v1/pods", "pods.json",
            "/api/v1/nodes", "nodes.json",
            "/apis/apps/v1/deployments", "deployments.json",
            "/apis/apps/v1/statefulsets", "statefulsets.json",
            "/apis/apps/v1/replicasets", "replicasets.json",
            "/apis/autoscaling/v2/horizontalpodautoscalers", "hpas-pinned.json",
            "/apis/policy/v1/poddisruptionbudgets", "pdbs.json");

    private static HttpServer server;
    private static String baseUrl;
    private static final Map<String, String> overrides = new HashMap<>();

    private KubernetesClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", HpaReadPathD1Test::respond);
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
        overrides.clear();
        client = new KubernetesClientBuilder()
                .withConfig(new ConfigBuilder().withMasterUrl(baseUrl).build())
                .build();
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("D1 fires 5 -> 8 from a HpaView the production inventory read actually produced")
    void d1FiresOnTheRealReadPath() {
        // The clock is after the pinned condition's lastTransitionTime, so the 30-minute dwell is met.
        WorkloadAutoscalingEngine engine = new WorkloadAutoscalingEngine(
                Clock.fixed(ReferenceWorkloads.NOW, ZoneOffset.UTC));

        ClusterWorkloadInventory.Workload phpApache = new ClusterWorkloadInventory().read(client)
                .workloads().stream()
                .filter(w -> w.ref().namespace().equals("team-b") && w.ref().name().equals("php-apache"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("php-apache missing from the inventory read"));

        // The HpaView is the inventory's own, not a hand-built fixture: this is the production path.
        HpaView hpa = phpApache.hpa();
        assertThat(hpa.scalingLimited()).isTrue();
        assertThat(hpa.currentMetricPct()).isEqualTo(72);

        WorkloadRef ref = phpApache.ref();
        WorkloadSnapshot snapshot = new WorkloadSnapshot(ref, 5, false, false, hpa, null, null,
                List.of(ReferenceWorkloads.container("php-apache",
                        ReferenceWorkloads.burstable(200, null, 64 * ReferenceWorkloads.MI, null),
                        ReferenceWorkloads.usage("php-apache", 320, 400 * ReferenceWorkloads.MI))));

        WorkloadDecision decision = engine.decide(snapshot,
                WorkloadPolicyDefaults.policy(WorkloadMode.RECOMMEND), NodeCapacity.unbounded());

        HpaDecision d1 = decision.hpa();
        assertThat(d1.state()).isEqualTo(HpaDecision.State.CHANGE);
        assertThat(d1.reasons()).contains(HpaReason.AT_MAX_UNMET_DEMAND);
        assertThat(d1.maxReplicas())
                .as("ceil(5 × 72 ÷ 50) = 8, capped +3 => 8")
                .isEqualTo(8);
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String fixture = overrides.getOrDefault(path, ROUTES.get(path));
        byte[] body = fixture == null
                ? ("{\"kind\":\"Status\",\"code\":404,\"message\":\"unrouted path " + path + "\"}")
                        .getBytes(StandardCharsets.UTF_8)
                : read(fixture);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(fixture == null ? 404 : 200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] read(String fixture) {
        try {
            return Files.readAllBytes(FIXTURES.resolve(fixture));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
