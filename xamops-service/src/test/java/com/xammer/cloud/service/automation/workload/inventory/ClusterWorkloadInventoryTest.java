package com.xammer.cloud.service.automation.workload.inventory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xammer.cloud.service.automation.workload.NodeCapacity;
import com.xammer.cloud.service.automation.workload.PodNameRule;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inventory reader, against recorded list JSON for the nine seeded harness workloads.
 *
 * <p>A JDK {@link HttpServer} on {@code 127.0.0.1:0} serves {@code src/test/resources/k8s/inventory}
 * and a real fabric8 client reads from it, so the deserialisation, the owner-reference join and the
 * quantity parsing are all exercised end to end with no cluster and no network egress. The precedent
 * for the server is {@code K8sClientFactoryTlsTest}.
 */
class ClusterWorkloadInventoryTest {

    private static final Path FIXTURES = Path.of("src/test/resources/k8s/inventory");
    private static final long MI = 1024L * 1024L;

    private static final Map<String, String> ROUTES = Map.of(
            "/api/v1/pods", "pods.json",
            "/api/v1/nodes", "nodes.json",
            "/apis/apps/v1/deployments", "deployments.json",
            "/apis/apps/v1/statefulsets", "statefulsets.json",
            "/apis/apps/v1/replicasets", "replicasets.json",
            "/apis/autoscaling/v2/horizontalpodautoscalers", "hpas.json",
            "/apis/policy/v1/poddisruptionbudgets", "pdbs.json");

    private static HttpServer server;
    private static String baseUrl;
    private static final Map<String, String> overrides = new HashMap<>();

    private KubernetesClient client;
    private ClusterWorkloadInventory.Inventory inventory;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ClusterWorkloadInventoryTest::respond);
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

    // ---------------------------------------------------------------- owner resolution

    @Test
    @DisplayName("pods are resolved by owner uid: a same-prefix foreign pod is NOT claimed")
    void podsAreResolvedByOwnerUid() {
        // pod=~"overprovisioned-api-.*" claims overprovisioned-api-canary-6f2a-aaaaa. The owner
        // chain Deployment -> ReplicaSet -> Pod by metadata.uid does not. This is audit bug 8.
        WorkloadPods pods = workload("team-a", "overprovisioned-api").pods();

        assertThat(pods.names()).containsExactlyInAnyOrder(
                "overprovisioned-api-7d9f-aaaaa", "overprovisioned-api-7d9f-bbbbb");
        assertThat(pods.names())
                .as("a name-prefix regex is how one workload ends up sized by another's metrics")
                .doesNotContain("overprovisioned-api-canary-6f2a-aaaaa");
        assertThat(pods.pods()).extracting(WorkloadPods.Pod::uid)
                .as("the uid is what an executor must re-check: a pod name can be reused")
                .containsExactlyInAnyOrder("pod-1a", "pod-1b");
    }

    @Test
    @DisplayName("the canary keeps its own pod, so the trap is a real trap")
    void theForeignWorkloadKeepsItsOwnPod() {
        assertThat(workload("team-a", "overprovisioned-api-canary").pods().names())
                .containsExactly("overprovisioned-api-canary-6f2a-aaaaa");
    }

    @Test
    @DisplayName("a StatefulSet owns its pods directly, with no ReplicaSet in between")
    void statefulSetPodsAreResolved() {
        ClusterWorkloadInventory.Workload queue = workload("prod-sim", "stateful-queue");

        assertThat(queue.ref().kind()).isEqualTo("StatefulSet");
        assertThat(queue.pods().names()).containsExactlyInAnyOrder("stateful-queue-0", "stateful-queue-1");
        assertThat(queue.replicas()).isEqualTo(2);
    }

    @Test
    @DisplayName("a superseded ReplicaSet with no pods does not break resolution")
    void anEmptyOldReplicaSetIsHarmless() {
        assertThat(workload("team-a", "overprovisioned-api").pods().pods()).hasSize(2);
    }

    // ---------------------------------------------------------------- live pods (review S2, S3)

    @Test
    @DisplayName("current is what the live pods run with, not the template an in-place resize leaves alone")
    void currentIsWhatTheLivePodsRunWith() {
        overrides.put("/api/v1/pods", "pods-live-state.json");

        ClusterWorkloadInventory.ContainerSpec idler =
                workload("team-a", "overprovisioned-api").containers().get(0);

        assertThat(idler.current())
                .as("the template still says 200m/256Mi; the pods were resized in place to 25m/250Mi")
                .isEqualTo(new ResourceEnvelope(25, 100, 250 * MI, 512 * MI));
    }

    @Test
    @DisplayName("live pods that disagree fall back to the template, the size every pod converges to")
    void podsThatDisagreeFallBackToTheTemplate() {
        overrides.put("/api/v1/pods", "pods-live-state.json");

        assertThat(workload("prod-sim", "rightsized-worker").containers().get(0).current())
                .isEqualTo(new ResourceEnvelope(100, 100, 128 * MI, 128 * MI));
    }

    @Test
    @DisplayName("Evicted, Pending and Terminating pods are never live pods, so never the canary")
    void onlyRunningPodsAreLive() {
        overrides.put("/api/v1/pods", "pods-live-state.json");

        assertThat(workload("team-a", "overprovisioned-api").pods().names())
                .as("no revision annotations here, so every owned ReplicaSet's Running pods count")
                .containsExactlyInAnyOrder("overprovisioned-api-7d9f-aaaaa",
                        "overprovisioned-api-7d9f-bbbbb", "overprovisioned-api-5b8c-fffff")
                .doesNotContain("overprovisioned-api-7d9f-ccccc", "overprovisioned-api-7d9f-ddddd",
                        "overprovisioned-api-7d9f-eeeee");
    }

    @Test
    @DisplayName("only the current revision's pods are live; history still spans every retained revision")
    void onlyTheCurrentRevisionsPodsAreLive() {
        overrides.put("/api/v1/pods", "pods-live-state.json");
        overrides.put("/apis/apps/v1/deployments", "deployments-revisions.json");
        overrides.put("/apis/apps/v1/replicasets", "replicasets-revisions.json");

        WorkloadPods pods = workload("team-a", "overprovisioned-api").pods();

        assertThat(pods.names()).containsExactlyInAnyOrder(
                "overprovisioned-api-7d9f-aaaaa", "overprovisioned-api-7d9f-bbbbb");
        assertThat(pods.nameRules())
                .as("measured history is selected by name rules, which keep the old revision")
                .hasSize(2);
    }

    // ---------------------------------------------------------------- history (addendum A)

    @Test
    @DisplayName("every retained ReplicaSet yields a pod-name rule, including the empty old one")
    void historicalPodNameRulesCoverEveryRetainedRevision() {
        // The pods of overprovisioned-api-5b8c are gone, but Prometheus still holds their samples
        // and the ReplicaSet object is still there to name them. Selecting on the CURRENT pod names
        // alone made a workload's history only as old as its newest pod (orchestrator addendum A).
        List<PodNameRule> rules = workload("team-a", "overprovisioned-api").pods().nameRules();

        assertThat(rules).containsExactlyInAnyOrder(
                PodNameRule.replicaSetPods("overprovisioned-api-7d9f"),
                PodNameRule.replicaSetPods("overprovisioned-api-5b8c"));
        assertThat(rules)
                .as("the canary's ReplicaSet belongs to a different Deployment uid")
                .doesNotContain(PodNameRule.replicaSetPods("overprovisioned-api-canary-6f2a"));
    }

    @Test
    @DisplayName("a StatefulSet needs one rule: its pod names are stable across its whole history")
    void aStatefulSetYieldsOneOrdinalRule() {
        assertThat(workload("prod-sim", "stateful-queue").pods().nameRules())
                .containsExactly(PodNameRule.statefulSetPods("stateful-queue"));
    }

    // ---------------------------------------------------------------- protected namespaces

    @Test
    @DisplayName("protected namespaces are not collected at all")
    void protectedNamespacesAreAbsent() {
        // Not filtered later, not gated later: not collected. Nothing downstream can decide about
        // kube-system because nothing downstream is ever told about it.
        assertThat(inventory().workloads())
                .extracting(w -> w.ref().namespace())
                .doesNotContainAnyElementsOf(WorkloadPolicyDefaults.PROTECTED_NAMESPACES);
        assertThat(found("monitoring", "prometheus-server")).isEmpty();
        assertThat(found("kube-system", "coredns")).isEmpty();
    }

    @Test
    @DisplayName("the nine seeded workloads plus the canary and the StatefulSet are all collected")
    void everyCandidateWorkloadIsCollected() {
        assertThat(inventory().workloads())
                .extracting(w -> w.ref().namespace() + "/" + w.ref().name())
                .containsExactlyInAnyOrder(
                        "team-a/overprovisioned-api",
                        "team-a/overprovisioned-api-canary",
                        "team-a/unschedulable-giant",
                        "prod-sim/rightsized-worker",
                        "prod-sim/throttled-encoder",
                        "prod-sim/stateful-queue",
                        "dev-sim/idle-batch-listener",
                        "dev-sim/no-requests-sidecar-app",
                        "team-b/php-apache",
                        "team-b/load-generator",
                        "team-b/pdb-guarded-web");
    }

    // ---------------------------------------------------------------- resources

    @Test
    @DisplayName("no-requests-sidecar-app yields null requests, NEVER 0 and never 1Mi")
    void unsetResourcesStayNull() {
        // The measured Go output reported "0m" and "1Mi" as the current state of a container that
        // had neither, then "fixed" that fabricated baseline for 0% savings.
        ClusterWorkloadInventory.Workload bestEffort = workload("dev-sim", "no-requests-sidecar-app");

        assertThat(bestEffort.containerNames()).containsExactly("app", "logshipper");
        for (ClusterWorkloadInventory.ContainerSpec container : bestEffort.containers()) {
            assertThat(container.current().cpuRequestMillis()).isNull();
            assertThat(container.current().cpuLimitMillis()).isNull();
            assertThat(container.current().memRequestBytes()).isNull();
            assertThat(container.current().memLimitBytes()).isNull();
            assertThat(container.current().anyRequestUnset()).isTrue();
        }
    }

    @Test
    @DisplayName("quantities are read exactly, in the engine's canonical units")
    void quantitiesAreExact() {
        // 250m must not round to 1 core (audit bug 3): CPU is millicores, memory is bytes.
        var idler = workload("team-a", "overprovisioned-api").containers().get(0);

        assertThat(idler.name()).isEqualTo("idler");
        assertThat(idler.current().cpuRequestMillis()).isEqualTo(200);
        assertThat(idler.current().cpuLimitMillis()).isEqualTo(500);
        assertThat(idler.current().memRequestBytes()).isEqualTo(256 * MI);
        assertThat(idler.current().memLimitBytes()).isEqualTo(512 * MI);

        var giant = workload("team-a", "unschedulable-giant").containers().get(0);
        assertThat(giant.current().cpuRequestMillis()).isEqualTo(32_000);
        assertThat(giant.current().memRequestBytes()).isEqualTo(64L * 1024 * MI);
    }

    @Test
    @DisplayName("a declared resizePolicy is read, so no executor can promise restart-free blindly")
    void theResizePolicyIsRead() {
        var worker = workload("prod-sim", "rightsized-worker").containers().get(0);

        assertThat(worker.resizePolicy())
                .containsEntry("cpu", "NotRequired")
                .containsEntry("memory", "RestartContainer");
        assertThat(workload("team-a", "overprovisioned-api").containers().get(0).resizePolicy())
                .as("none of the seeded workloads but this one declares one")
                .isEmpty();
    }

    // ---------------------------------------------------------------- state, HPA, PDB

    @Test
    @DisplayName("generation vs observedGeneration is the rollout signal")
    void rolloutInProgressIsRead() {
        assertThat(workload("team-a", "unschedulable-giant").rolloutInProgress()).isTrue();
        assertThat(workload("team-a", "overprovisioned-api").rolloutInProgress()).isFalse();
    }

    @Test
    @DisplayName("the lock annotation suspends automation for one workload")
    void theLockAnnotationIsRead() {
        assertThat(workload("dev-sim", "idle-batch-listener").lockAnnotationActive()).isTrue();
        assertThat(workload("dev-sim", "no-requests-sidecar-app").lockAnnotationActive()).isFalse();
    }

    @Test
    @DisplayName("an HPA attaches only on an exact scaleTargetRef match, never on a prefix")
    void hpaMatchingIsExact() {
        var phpApache = workload("team-b", "php-apache");

        assertThat(phpApache.hpa()).isNotNull();
        assertThat(phpApache.hpa().name()).isEqualTo("php-apache");
        assertThat(phpApache.hpa().cpuResourceMetricPresent())
                .as("a Resource: cpu metric means the CPU request is the HPA's denominator")
                .isTrue();
        assertThat(phpApache.hpa().memResourceMetricPresent())
                .as("the memory-metric HPA in the fixture targets php-apache-canary, not this")
                .isFalse();
        assertThat(phpApache.hpa().targetCpuUtilizationPct()).isEqualTo(50);
        assertThat(phpApache.hpa().minReplicas()).isEqualTo(1);
        assertThat(phpApache.hpa().maxReplicas()).isEqualTo(5);
        assertThat(phpApache.hpa().lastScaleTime()).isEqualTo(Instant.parse("2026-10-11T09:00:00Z"));

        assertThat(workload("team-b", "load-generator").hpa()).isNull();
    }

    @Test
    @DisplayName("a PDB attaches by selector; a percentage minAvailable reads as null, not as a number")
    void pdbMinAvailableIsRead() {
        assertThat(workload("team-b", "pdb-guarded-web").pdbMinAvailable()).isEqualTo(2);
        assertThat(workload("prod-sim", "rightsized-worker").pdbMinAvailable())
                .as("converting 50% here would invent a number from a replica count the PDB does"
                        + " not mention")
                .isNull();
        assertThat(workload("team-b", "load-generator").pdbMinAvailable()).isNull();
    }

    // ---------------------------------------------------------------- node capacity

    @Test
    @DisplayName("node capacity is the largest SCHEDULABLE node; a cordoned node is not capacity")
    void capacityIsTheLargestSchedulableNode() {
        NodeCapacity capacity = inventory().capacity();

        assertThat(capacity).isNotNull();
        assertThat(capacity.maxAllocatableCpuMillis())
                .as("the 15-core node is cordoned, so a pod cannot be scheduled onto it")
                .isEqualTo(3860);
        assertThat(capacity.maxAllocatableMemBytes()).isEqualTo(7000 * MI);
    }

    @Test
    @DisplayName("with no schedulable node the capacity is null, not a capacity of zero")
    void noSchedulableNodeMeansNoCapacity() {
        overrides.put("/api/v1/nodes", "nodes-all-cordoned.json");

        assertThat(new ClusterWorkloadInventory().read(client).capacity())
                .as("an unknown capacity is not a capacity of zero, and the read path treats null"
                        + " as a cluster-wide failure rather than publishing uncapped numbers")
                .isNull();
    }

    // ---------------------------------------------------------------- helpers

    private ClusterWorkloadInventory.Inventory inventory() {
        if (inventory == null) {
            inventory = new ClusterWorkloadInventory().read(client);
        }
        return inventory;
    }

    private ClusterWorkloadInventory.Workload workload(String namespace, String name) {
        return found(namespace, name).orElseThrow(
                () -> new AssertionError("no workload " + namespace + "/" + name + " in the inventory"));
    }

    private Optional<ClusterWorkloadInventory.Workload> found(String namespace, String name) {
        return inventory().workloads().stream()
                .filter(w -> w.ref().namespace().equals(namespace) && w.ref().name().equals(name))
                .findFirst();
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
