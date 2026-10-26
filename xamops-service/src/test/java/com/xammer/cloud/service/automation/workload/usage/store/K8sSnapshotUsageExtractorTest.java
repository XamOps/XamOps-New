package com.xammer.cloud.service.automation.workload.usage.store;

import com.xammer.cloud.service.automation.workload.usage.store.K8sSnapshotUsageExtractor.Result;
import com.xammer.cloud.service.automation.workload.usage.store.K8sSnapshotUsageExtractor.Sample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Payloads are built inline in the agent's JSON shape ({@code xamops-agent/internal/collector/types.go}). */
class K8sSnapshotUsageExtractorTest {

    // ---------------------------------------------------------------- owners

    @Test
    @DisplayName("a ReplicaSet pod with the matching pod-template-hash is attributed to its Deployment")
    void replicaSetWithHashIsDeployment() {
        Map<String, Object> pod = pod("shop", "web-7d9f8b6c4-x2k9p", "Running", "ReplicaSet", "web-7d9f8b6c4",
                Map.of("pod-template-hash", "7d9f8b6c4"), container("app", "100m", "200m", "64Mi", "128Mi"));

        Sample s = single(snapshot(List.of(pod), List.of(metrics(pod, "app", "5m", "10Mi")), List.of()));

        assertThat(s.kind()).isEqualTo("Deployment");
        assertThat(s.name()).isEqualTo("web");
        assertThat(s.namespace()).isEqualTo("shop");
        assertThat(s.container()).isEqualTo("app");
    }

    @Test
    @DisplayName("a ReplicaSet whose name does not end with the pod's hash label stays a ReplicaSet")
    void replicaSetWithoutMatchingHashStays() {
        Map<String, Object> noLabel = pod("shop", "legacy-abc12", "Running", "ReplicaSet", "legacy",
                Map.of(), container("app", "", "", "", ""));
        Map<String, Object> otherHash = pod("shop", "web-1-zz", "Running", "ReplicaSet", "web-1",
                Map.of("pod-template-hash", "999"), container("app", "", "", "", ""));

        Result r = K8sSnapshotUsageExtractor.extract(snapshot(List.of(noLabel, otherHash),
                List.of(metrics(noLabel, "app", "1m", "1Mi"), metrics(otherHash, "app", "1m", "1Mi")), List.of()));

        assertThat(r.samples()).extracting(Sample::kind, Sample::name)
                .containsExactly(tuple("ReplicaSet", "legacy"),
                        tuple("ReplicaSet", "web-1"));
    }

    @Test
    @DisplayName("StatefulSet and DaemonSet pods are attributed to their direct controller")
    void statefulSetAndDaemonSetDirect() {
        Map<String, Object> sts = pod("db", "pg-0", "Running", "StatefulSet", "pg", Map.of(),
                container("postgres", "1", "", "1Gi", ""));
        Map<String, Object> ds = pod("kube-system", "node-exporter-abcde", "Running", "DaemonSet", "node-exporter",
                Map.of(), container("exporter", "", "", "", ""));

        Result r = K8sSnapshotUsageExtractor.extract(snapshot(List.of(sts, ds),
                List.of(metrics(sts, "postgres", "1m", "1Mi"), metrics(ds, "exporter", "1m", "1Mi")), List.of()));

        assertThat(r.samples()).extracting(Sample::kind, Sample::name)
                .containsExactly(tuple("StatefulSet", "pg"),
                        tuple("DaemonSet", "node-exporter"));
    }

    @Test
    @DisplayName("a Job named <cronjob>-<digits> of a CronJob in the same namespace is the CronJob; others stay Job")
    void jobOfCronJob() {
        Map<String, Object> scheduled = pod("ops", "backup-29012345-abcde", "Running", "Job", "backup-29012345",
                Map.of(), container("backup", "", "", "", ""));
        Map<String, Object> adHoc = pod("ops", "migrate-42-xyz", "Running", "Job", "migrate-42",
                Map.of(), container("migrate", "", "", "", ""));
        Map<String, Object> otherNamespace = pod("dev", "backup-29012345-fghij", "Running", "Job", "backup-29012345",
                Map.of(), container("backup", "", "", "", ""));

        Result r = K8sSnapshotUsageExtractor.extract(snapshot(List.of(scheduled, adHoc, otherNamespace),
                List.of(metrics(scheduled, "backup", "1m", "1Mi"), metrics(adHoc, "migrate", "1m", "1Mi"),
                        metrics(otherNamespace, "backup", "1m", "1Mi")),
                List.of(Map.of("name", "backup", "namespace", "ops"))));

        assertThat(r.samples()).extracting(Sample::namespace, Sample::kind, Sample::name)
                .containsExactly(tuple("ops", "CronJob", "backup"),
                        tuple("ops", "Job", "migrate-42"),
                        tuple("dev", "Job", "backup-29012345"));
    }

    @Test
    @DisplayName("a pod without a controller is its own workload, kind Pod")
    void barePod() {
        Map<String, Object> pod = pod("default", "debug", "Running", "", "", Map.of(), container("sh", "", "", "", ""));

        Sample s = single(snapshot(List.of(pod), List.of(metrics(pod, "sh", "1m", "1Mi")), List.of()));

        assertThat(s.kind()).isEqualTo("Pod");
        assertThat(s.name()).isEqualTo("debug");
    }

    @Test
    @DisplayName("a controller kind longer than the column is truncated to 32 characters")
    void longKindTruncated() {
        String kind = "VeryLongCustomControllerKindNameThatOverflows";
        Map<String, Object> pod = pod("x", "p", "Running", kind, "c", Map.of(), container("a", "", "", "", ""));

        Sample s = single(snapshot(List.of(pod), List.of(metrics(pod, "a", "1m", "1Mi")), List.of()));

        assertThat(s.kind()).hasSize(32).isEqualTo(kind.substring(0, 32));
    }

    // ---------------------------------------------------------------- phases / replicas

    @Test
    @DisplayName("Pending and Succeeded pods produce no sample and do not count as running replicas")
    void onlyRunningPods() {
        Map<String, Object> running = deploymentPod("web", "aaaaa", "Running");
        Map<String, Object> pending = deploymentPod("web", "bbbbb", "Pending");
        Map<String, Object> done = deploymentPod("web", "ccccc", "Succeeded");

        Result r = K8sSnapshotUsageExtractor.extract(snapshot(List.of(running, pending, done),
                List.of(metrics(running, "app", "1m", "1Mi"), metrics(pending, "app", "1m", "1Mi"),
                        metrics(done, "app", "1m", "1Mi")), List.of()));

        assertThat(r.samples()).hasSize(1);
        assertThat(r.samples().get(0).runningReplicas()).isEqualTo(1);
        assertThat(r.podsSeen()).isEqualTo(1);
    }

    @Test
    @DisplayName("running_replicas is the number of Running pods of the owner in the snapshot")
    void runningReplicasCounted() {
        List<Map<String, Object>> pods = List.of(deploymentPod("web", "aaaaa", "Running"),
                deploymentPod("web", "bbbbb", "Running"), deploymentPod("web", "ccccc", "Running"));
        List<Map<String, Object>> podMetrics = new ArrayList<>();
        pods.forEach(p -> podMetrics.add(metrics(p, "app", "1m", "1Mi")));

        Result r = K8sSnapshotUsageExtractor.extract(snapshot(pods, podMetrics, List.of()));

        assertThat(r.samples()).hasSize(3).allSatisfy(s -> assertThat(s.runningReplicas()).isEqualTo(3));
        assertThat(r.samples()).extracting(Sample::podHash).doesNotHaveDuplicates()
                .contains(K8sSnapshotUsageExtractor.podHash("web-7d9f8b6c4-aaaaa"));
        assertThat(r.samples()).extracting(Sample::workloadKey).containsOnly(WorkloadKey.of("Deployment", "shop", "web", "app"));
    }

    // ---------------------------------------------------------------- metrics

    @Test
    @DisplayName("a pod without a podMetrics entry produces no sample, never a zero, and is counted")
    void podWithoutMetrics() {
        Map<String, Object> measured = deploymentPod("web", "aaaaa", "Running");
        Map<String, Object> unmeasured = pod("shop", "api-0", "Running", "StatefulSet", "api", Map.of(),
                container("app", "", "", "", ""), container("sidecar", "", "", "", ""));

        Result r = K8sSnapshotUsageExtractor.extract(snapshot(List.of(measured, unmeasured),
                List.of(metrics(measured, "app", "1m", "1Mi")), List.of()));

        assertThat(r.samples()).extracting(Sample::name).containsExactly("web");
        assertThat(r.containersWithoutMetrics()).isEqualTo(2);
        assertThat(r.podsSeen()).isEqualTo(2);
    }

    @Test
    @DisplayName("nanocores keep their precision and memory is bytes")
    void quantities() {
        Map<String, Object> pod = deploymentPod("web", "aaaaa", "Running");

        Sample s = single(snapshot(List.of(pod), List.of(metrics(pod, "app", "1234567n", "34Mi")), List.of()));

        assertThat(s.cpuM()).isEqualTo(1.234567);
        assertThat(s.memB()).isEqualTo(35651584L);
        assertThat(s.cpuReqM()).isEqualTo(100.0);
        assertThat(s.cpuLimM()).isEqualTo(500.0);
        assertThat(s.memReqB()).isEqualTo(64L * 1024 * 1024);
        assertThat(s.memLimB()).isEqualTo(256L * 1024 * 1024);
    }

    @Test
    @DisplayName("blank requests and limits are null, not zero")
    void blankRequestsAreNull() {
        Map<String, Object> pod = pod("shop", "p", "Running", "", "", Map.of(), container("app", "", "", "", ""));

        Sample s = single(snapshot(List.of(pod), List.of(metrics(pod, "app", "1m", "1Mi")), List.of()));

        assertThat(s.cpuReqM()).isNull();
        assertThat(s.cpuLimM()).isNull();
        assertThat(s.memReqB()).isNull();
        assertThat(s.memLimB()).isNull();
    }

    @Test
    @DisplayName("an unparseable quantity skips that one sample and is counted")
    void unparseableSkipped() {
        Map<String, Object> pod = pod("shop", "p", "Running", "", "", Map.of(),
                container("good", "", "", "", ""), container("bad", "", "", "", ""), container("badreq", "abc", "", "", ""));
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("podName", "p");
        pm.put("namespace", "shop");
        pm.put("containers", List.of(usage("good", "1m", "1Mi"), usage("bad", "lots", "1Mi"), usage("badreq", "1m", "1Mi")));

        Result r = K8sSnapshotUsageExtractor.extract(snapshot(List.of(pod), List.of(pm), List.of()));

        assertThat(r.samples()).extracting(Sample::container).containsExactly("good");
        assertThat(r.skippedUnparseable()).isEqualTo(2);
    }

    @Test
    @DisplayName("initContainers are ignored even when metrics-server reports them")
    void initContainersIgnored() {
        Map<String, Object> pod = deploymentPod("web", "aaaaa", "Running");
        pod.put("initContainers", List.of(container("init", "", "", "", "")));
        Map<String, Object> pm = metrics(pod, "app", "1m", "1Mi");
        pm.put("containers", List.of(usage("app", "1m", "1Mi"), usage("init", "1m", "1Mi")));

        Result r = K8sSnapshotUsageExtractor.extract(snapshot(List.of(pod), List.of(pm), List.of()));

        assertThat(r.samples()).extracting(Sample::container).containsExactly("app");
    }

    @Test
    @DisplayName("null and missing lists and fields are safe")
    void nullSafe() {
        assertThat(K8sSnapshotUsageExtractor.extract(null).samples()).isEmpty();
        assertThat(K8sSnapshotUsageExtractor.extract(Map.of()).samples()).isEmpty();

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("pods", null);
        snapshot.put("podMetrics", "not-a-list");
        assertThat(K8sSnapshotUsageExtractor.extract(snapshot).samples()).isEmpty();

        Map<String, Object> noContainers = new HashMap<>();
        noContainers.put("name", "p");
        noContainers.put("namespace", "ns");
        noContainers.put("phase", "Running");
        Map<String, Object> noName = new HashMap<>();
        noName.put("phase", "Running");
        Map<String, Object> pmNoContainers = new HashMap<>();
        pmNoContainers.put("podName", "p");
        pmNoContainers.put("namespace", "ns");
        List<Object> pods = new ArrayList<>();
        pods.add(noContainers);
        pods.add(noName);
        pods.add(null);
        pods.add("garbage");
        List<Object> podMetrics = new ArrayList<>();
        podMetrics.add(pmNoContainers);
        podMetrics.add(null);
        Map<String, Object> partial = new HashMap<>();
        partial.put("pods", pods);
        partial.put("podMetrics", podMetrics);

        Result r = K8sSnapshotUsageExtractor.extract(partial);
        assertThat(r.samples()).isEmpty();
        assertThat(r.podsSeen()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- pod hash / compact form

    @Test
    @DisplayName("pod_hash is 64-bit: names that collide under String.hashCode() get different hashes")
    void podHashIs64Bit() {
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        assertThat(K8sSnapshotUsageExtractor.podHash("Aa")).isNotEqualTo(K8sSnapshotUsageExtractor.podHash("BB"));
        assertThat(K8sSnapshotUsageExtractor.podHash("web-7d9f8b6c4-aaaaa"))
                .as("stable across calls, so a replay keys the same")
                .isEqualTo(K8sSnapshotUsageExtractor.podHash("web-7d9f8b6c4-aaaaa"));
    }

    @Test
    @DisplayName("extract(compact(s)) equals extract(s), and compact keeps only what extract reads")
    void compactExtractsTheSame() {
        Map<String, Object> deployment = deploymentPod("web", "aaaaa", "Running");
        deployment.put("nodeName", "ip-10-0-0-1");
        deployment.put("podIp", "10.0.0.9");
        Map<String, Object> rich = container("app", "100m", "500m", "64Mi", "256Mi");
        rich.put("image", "registry/web:1.2.3");
        rich.put("ports", List.of(Map.of("containerPort", 8080)));
        deployment.put("containers", List.of(rich, container("sidecar", "", "", "", "")));
        deployment.put("initContainers", List.of(container("init", "", "", "", "")));
        Map<String, Object> scheduled = pod("ops", "backup-29012345-abcde", "Running", "Job", "backup-29012345",
                Map.of(), container("backup", "", "", "", ""));
        Map<String, Object> bare = pod("default", "debug", "Running", "", "", Map.of(), container("sh", "abc", "", "", ""));
        Map<String, Object> pending = deploymentPod("web", "bbbbb", "Pending");
        Map<String, Object> noLabels = pod("shop", "api-0", "Running", "StatefulSet", "api", Map.of(),
                container("app", "", "", "", ""));
        noLabels.remove("labels");

        Map<String, Object> deploymentMetrics = metrics(deployment, "app", "1234567n", "34Mi");
        deploymentMetrics.put("containers", List.of(usage("app", "1234567n", "34Mi"), usage("sidecar", "2m", "5Mi"),
                usage("init", "1m", "1Mi")));
        Map<String, Object> s = snapshot(List.of(deployment, scheduled, bare, pending, noLabels),
                List.of(deploymentMetrics, metrics(scheduled, "backup", "1m", "1Mi"), metrics(bare, "sh", "1m", "1Mi"),
                        metrics(pending, "app", "1m", "1Mi")),
                List.of(Map.of("name", "backup", "namespace", "ops", "schedule", "0 * * * *")));
        s.put("nodes", List.of(Map.of("name", "ip-10-0-0-1", "labels", Map.of("a", "b"))));
        s.put("events", List.of(Map.of("reason", "Scheduled", "message", "x".repeat(500))));
        s.put("summary", Map.of("totalPods", 5));

        Map<String, Object> compact = K8sSnapshotUsageExtractor.compact(s);

        assertThat(K8sSnapshotUsageExtractor.extract(compact)).isEqualTo(K8sSnapshotUsageExtractor.extract(s));
        assertThat(K8sSnapshotUsageExtractor.extract(compact).samples())
                .as("web app + sidecar, the CronJob's backup; debug's request is unparseable, api-0 unmeasured")
                .hasSize(3);
        assertThat(compact).containsOnlyKeys("pods", "podMetrics", "cronJobs");
        assertThat((List<?>) compact.get("pods")).as("Running pods only").hasSize(4).allSatisfy(p -> {
            Map<?, ?> pod = (Map<?, ?>) p;
            assertThat(keys(pod)).isSubsetOf("name", "namespace", "phase", "controllerKind", "controllerName",
                    "labels", "containers");
            if (pod.get("labels") instanceof Map<?, ?> labels) {
                assertThat(keys(labels)).containsOnly("pod-template-hash");
            }
            for (Object c : (List<?>) pod.get("containers")) {
                assertThat(keys((Map<?, ?>) c)).isSubsetOf("name", "cpuRequest", "cpuLimit", "memoryRequest",
                        "memoryLimit");
            }
        });
        assertThat(K8sSnapshotUsageExtractor.compact(null)).isEmpty();
        assertThat(K8sSnapshotUsageExtractor.extract(K8sSnapshotUsageExtractor.compact(Map.of())).samples()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> keys(Map<?, ?> map) {
        return map.keySet().stream().map(String::valueOf).toList();
    }

    private static Sample single(Map<String, Object> snapshot) {
        Result r = K8sSnapshotUsageExtractor.extract(snapshot);
        assertThat(r.samples()).hasSize(1);
        return r.samples().get(0);
    }

    private static Map<String, Object> snapshot(List<? extends Map<String, Object>> pods,
                                                List<? extends Map<String, Object>> podMetrics,
                                                List<? extends Map<String, Object>> cronJobs) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("pods", pods);
        s.put("podMetrics", podMetrics);
        s.put("cronJobs", cronJobs);
        return s;
    }

    private static Map<String, Object> deploymentPod(String deployment, String suffix, String phase) {
        String hash = "7d9f8b6c4";
        return pod("shop", deployment + "-" + hash + "-" + suffix, phase, "ReplicaSet", deployment + "-" + hash,
                Map.of("pod-template-hash", hash, "app", deployment),
                container("app", "100m", "500m", "64Mi", "256Mi"));
    }

    @SafeVarargs
    private static Map<String, Object> pod(String namespace, String name, String phase, String controllerKind,
                                           String controllerName, Map<String, String> labels,
                                           Map<String, Object>... containers) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("namespace", namespace);
        p.put("phase", phase);
        p.put("labels", labels);
        p.put("controllerKind", controllerKind);
        p.put("controllerName", controllerName);
        p.put("containers", List.of(containers));
        return p;
    }

    private static Map<String, Object> container(String name, String cpuRequest, String cpuLimit,
                                                 String memoryRequest, String memoryLimit) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("cpuRequest", cpuRequest);
        c.put("cpuLimit", cpuLimit);
        c.put("memoryRequest", memoryRequest);
        c.put("memoryLimit", memoryLimit);
        return c;
    }

    private static Map<String, Object> metrics(Map<String, Object> pod, String container, String cpu, String memory) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("podName", pod.get("name"));
        pm.put("namespace", pod.get("namespace"));
        pm.put("containers", List.of(usage(container, cpu, memory)));
        return pm;
    }

    private static Map<String, Object> usage(String container, String cpu, String memory) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("name", container);
        u.put("cpuUsage", cpu);
        u.put("memoryUsage", memory);
        return u;
    }
}
