package com.xammer.cloud.service.automation.workload.inventory;

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
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Node fact extraction (FEAT-001 §3.0) from the already-listed {@code client.nodes()}, against
 * recorded list JSON — the same JDK {@link HttpServer} + real-fabric8-client pattern
 * {@code ClusterWorkloadInventoryTest} uses (no mock-server dependency, no network egress).
 */
class NodeFactsExtractionTest {

    private static final Path FIXTURES = Path.of("src/test/resources/k8s/inventory");

    /** Only nodes carry data; every other list the inventory reads is served empty. */
    private static final Map<String, String> ROUTES = Map.of("/api/v1/nodes", "nodes-cost.json");

    private static final java.util.Set<String> EMPTY_LIST_PATHS = java.util.Set.of(
            "/api/v1/pods",
            "/apis/apps/v1/replicasets",
            "/apis/apps/v1/deployments",
            "/apis/apps/v1/statefulsets",
            "/apis/autoscaling/v2/horizontalpodautoscalers",
            "/apis/policy/v1/poddisruptionbudgets");

    private static HttpServer server;
    private static String baseUrl;

    private KubernetesClient client;
    private Map<String, ClusterWorkloadInventory.NodeFacts> nodes;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", NodeFactsExtractionTest::respond);
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
        client = new KubernetesClientBuilder()
                .withConfig(new ConfigBuilder().withMasterUrl(baseUrl).build())
                .build();
        nodes = new ClusterWorkloadInventory().read(client).nodesByName();
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("a labeled on-demand node yields instance type, zone, cores/GiB capacity, millis/bytes allocatable")
    void labeledOnDemandNode() {
        ClusterWorkloadInventory.NodeFacts facts = nodes.get("on-demand-node");

        assertThat(facts).isNotNull();
        assertThat(facts.instanceType()).isEqualTo("m5.large");
        assertThat(facts.capacityType()).isEqualTo("on-demand");
        assertThat(facts.capacityTypeInferred()).isFalse();
        assertThat(facts.zone()).isEqualTo("ap-south-1a");
        assertThat(facts.fargate()).isFalse();
        assertThat(facts.capacityVcpus()).isEqualTo(2.0);
        assertThat(facts.capacityMemGiB()).isEqualTo(8.0);
        assertThat(facts.allocatableCpuMillis()).isEqualTo(1930);
        assertThat(facts.allocatableMemBytes()).isEqualTo(7600L * 1024 * 1024);
    }

    @Test
    @DisplayName("a spot node reads its karpenter capacity-type and zone")
    void spotNode() {
        ClusterWorkloadInventory.NodeFacts facts = nodes.get("spot-node");

        assertThat(facts.capacityType()).isEqualTo("spot");
        assertThat(facts.capacityTypeInferred()).isFalse();
        assertThat(facts.zone()).isEqualTo("ap-south-1b");
    }

    @Test
    @DisplayName("no capacity-type label sets capacityTypeInferred == true")
    void inferredNode() {
        ClusterWorkloadInventory.NodeFacts facts = nodes.get("unlabeled-node");

        assertThat(facts.capacityTypeInferred()).isTrue();
        assertThat(facts.capacityType()).isNull();
        assertThat(facts.instanceType()).isEqualTo("m5.large");
    }

    @Test
    @DisplayName("a Fargate node is flagged fargate with no instance type")
    void fargateNode() {
        ClusterWorkloadInventory.NodeFacts facts = nodes.get("fargate-node");

        assertThat(facts.fargate()).isTrue();
        assertThat(facts.instanceType()).isNull();
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String fixture = ROUTES.get(path);
        byte[] body;
        int status;
        if (fixture != null) {
            body = read(fixture);
            status = 200;
        } else if (EMPTY_LIST_PATHS.contains(path)) {
            body = "{\"apiVersion\":\"v1\",\"kind\":\"List\",\"items\":[]}"
                    .getBytes(StandardCharsets.UTF_8);
            status = 200;
        } else {
            body = ("{\"kind\":\"Status\",\"code\":404,\"message\":\"unrouted path " + path + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            status = 404;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
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
