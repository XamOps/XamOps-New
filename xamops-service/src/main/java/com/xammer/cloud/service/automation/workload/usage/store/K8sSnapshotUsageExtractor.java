package com.xammer.cloud.service.automation.workload.usage.store;

import com.xammer.cloud.service.automation.workload.Quantities;
import com.xammer.cloud.service.automation.workload.QuantityParseException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one agent {@code k8s-resources} snapshot (the Jackson {@code Map} of
 * {@code xamops-agent/internal/collector/types.go K8sResources}) into per-container usage samples.
 * Pure: no Spring, no I/O.
 *
 * <h2>Grain</h2>
 *
 * One sample per (Running pod, container with a metrics-server entry), attributed to the pod's
 * owning workload so pod churn does not fragment history:
 * <ul>
 *   <li>{@code ReplicaSet <d>-<pod-template-hash>} → {@code Deployment <d>} (only when the pod's
 *       {@code pod-template-hash} label is the suffix; otherwise the ReplicaSet stays itself)</li>
 *   <li>{@code Job <cj>-<digits>} where {@code <cj>} is a CronJob in the same namespace →
 *       {@code CronJob <cj>}</li>
 *   <li>no controller → {@code Pod <pod name>}</li>
 *   <li>anything else as reported</li>
 * </ul>
 *
 * <p>A container without a metrics entry produces <strong>no</strong> sample, never a zero: "not
 * measured" is not "used nothing". An unparseable quantity skips that one sample. Init containers are
 * not read. Every list and field may be missing.
 */
public final class K8sSnapshotUsageExtractor {

    /** {@code k8s_workload_identity.workload_kind} is VARCHAR(32). */
    static final int MAX_KIND_LENGTH = 32;

    private static final String RUNNING = "Running";
    private static final String POD_TEMPLATE_HASH = "pod-template-hash";
    private static final Pattern CRONJOB_JOB = Pattern.compile("^(.+)-\\d+$");

    /** One raw point, before identity resolution. A {@code null} request/limit means "not set". */
    public record Sample(String namespace, String kind, String name, String container,
                         double cpuM, long memB, Double cpuReqM, Double cpuLimM, Long memReqB, Long memLimB,
                         long podHash, int runningReplicas) {

        public String workloadKey() {
            return WorkloadKey.of(kind, namespace, name, container);
        }
    }

    /**
     * @param podsSeen                 Running pods examined
     * @param containersWithoutMetrics containers of Running pods with no metrics-server entry
     * @param skippedUnparseable       samples dropped because a quantity could not be read
     */
    public record Result(List<Sample> samples, int podsSeen, int containersWithoutMetrics, int skippedUnparseable) {
    }

    private record Owner(String namespace, String kind, String name) {
    }

    private record Usage(String cpu, String memory) {
    }

    private K8sSnapshotUsageExtractor() {
    }

    public static Result extract(Map<String, Object> k8sResources) {
        if (k8sResources == null) {
            return new Result(List.of(), 0, 0, 0);
        }
        Map<String, Map<String, Usage>> metrics = indexMetrics(list(k8sResources.get("podMetrics")));
        Set<String> cronJobs = cronJobNames(list(k8sResources.get("cronJobs")));

        // Pass 1: owners of Running pods, and how many Running pods each owner has.
        List<Map<?, ?>> running = new ArrayList<>();
        List<Owner> owners = new ArrayList<>();
        Map<Owner, Integer> replicas = new HashMap<>();
        for (Object element : list(k8sResources.get("pods"))) {
            if (!(element instanceof Map<?, ?> pod) || !RUNNING.equals(str(pod.get("phase")))) {
                continue;
            }
            String name = str(pod.get("name"));
            String namespace = str(pod.get("namespace"));
            if (isBlank(name) || namespace == null) {
                continue;
            }
            Owner owner = ownerOf(pod, namespace, name, cronJobs);
            running.add(pod);
            owners.add(owner);
            replicas.merge(owner, 1, Integer::sum);
        }

        // Pass 2: one sample per measured container.
        List<Sample> samples = new ArrayList<>();
        int withoutMetrics = 0;
        int unparseable = 0;
        for (int i = 0; i < running.size(); i++) {
            Map<?, ?> pod = running.get(i);
            Owner owner = owners.get(i);
            String podName = str(pod.get("name"));
            long podHash = podHash(podName);
            Map<String, Usage> podUsage = metrics.getOrDefault(podKey(owner.namespace(), podName), Map.of());
            for (Object c : list(pod.get("containers"))) {
                if (!(c instanceof Map<?, ?> container)) {
                    continue;
                }
                String containerName = str(container.get("name"));
                if (isBlank(containerName)) {
                    continue;
                }
                Usage usage = podUsage.get(containerName);
                if (usage == null) {
                    withoutMetrics++;
                    continue;
                }
                try {
                    samples.add(new Sample(owner.namespace(), owner.kind(), owner.name(), containerName,
                            Quantities.cpuMillisPrecise(usage.cpu(), "cpuUsage"),
                            Quantities.memBytes(usage.memory(), "memoryUsage"),
                            optionalCpu(container.get("cpuRequest"), "cpuRequest"),
                            optionalCpu(container.get("cpuLimit"), "cpuLimit"),
                            optionalMem(container.get("memoryRequest"), "memoryRequest"),
                            optionalMem(container.get("memoryLimit"), "memoryLimit"),
                            podHash,
                            replicas.get(owner)));
                } catch (QuantityParseException e) {
                    unparseable++;
                }
            }
        }
        return new Result(samples, running.size(), withoutMetrics, unparseable);
    }

    /**
     * The {@code pod_hash} of the sample key: the first 8 bytes of the sha256 of the pod name (UTF-8).
     * 64 bits, so two pods of one workload in one snapshot do not collide (a 32-bit
     * {@code String.hashCode()} would, silently dropping a point on {@code ON CONFLICT DO NOTHING}).
     */
    public static long podHash(String podName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(podName.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Only what {@link #extract} reads, so {@code extract(compact(s))} equals {@code extract(s)}:
     * Running pods reduced to name, namespace, phase, controller, the {@code pod-template-hash} label
     * and each container's name and requests/limits; {@code podMetrics} as is (already per-container
     * usage only); CronJob namespace/name. The capture queue holds this instead of the whole snapshot
     * (nodes, events, services, every label, image, port, ...). Plain map copies, no parsing.
     */
    public static Map<String, Object> compact(Map<?, ?> k8sResources) {
        if (k8sResources == null) {
            return Map.of();
        }
        List<Object> pods = new ArrayList<>();
        for (Object element : list(k8sResources.get("pods"))) {
            if (!(element instanceof Map<?, ?> pod) || !RUNNING.equals(str(pod.get("phase")))) {
                continue;
            }
            Map<String, Object> slim = copy(pod, "name", "namespace", "phase", "controllerKind", "controllerName");
            if (pod.get("labels") instanceof Map<?, ?> labels && labels.get(POD_TEMPLATE_HASH) != null) {
                slim.put("labels", Map.of(POD_TEMPLATE_HASH, labels.get(POD_TEMPLATE_HASH)));
            }
            List<Object> containers = new ArrayList<>();
            for (Object c : list(pod.get("containers"))) {
                if (c instanceof Map<?, ?> container) {
                    containers.add(copy(container, "name", "cpuRequest", "cpuLimit", "memoryRequest", "memoryLimit"));
                }
            }
            slim.put("containers", containers);
            pods.add(slim);
        }
        List<Object> cronJobs = new ArrayList<>();
        for (Object element : list(k8sResources.get("cronJobs"))) {
            if (element instanceof Map<?, ?> cj) {
                cronJobs.add(copy(cj, "name", "namespace"));
            }
        }
        Map<String, Object> compact = new HashMap<>();
        compact.put("pods", pods);
        compact.put("podMetrics", list(k8sResources.get("podMetrics")));
        compact.put("cronJobs", cronJobs);
        return compact;
    }

    private static Map<String, Object> copy(Map<?, ?> from, String... keys) {
        Map<String, Object> to = new HashMap<>();
        for (String key : keys) {
            Object value = from.get(key);
            if (value != null) {
                to.put(key, value);
            }
        }
        return to;
    }

    private static Owner ownerOf(Map<?, ?> pod, String namespace, String podName, Set<String> cronJobs) {
        String kind = str(pod.get("controllerKind"));
        String controller = str(pod.get("controllerName"));
        if (isBlank(kind)) {
            return new Owner(namespace, "Pod", podName);
        }
        if (isBlank(controller)) {
            controller = podName;
        }
        if ("ReplicaSet".equals(kind)) {
            String hash = pod.get("labels") instanceof Map<?, ?> labels ? str(labels.get(POD_TEMPLATE_HASH)) : null;
            String suffix = "-" + hash;
            if (!isBlank(hash) && controller.endsWith(suffix) && controller.length() > suffix.length()) {
                return new Owner(namespace, "Deployment", controller.substring(0, controller.length() - suffix.length()));
            }
        } else if ("Job".equals(kind)) {
            Matcher m = CRONJOB_JOB.matcher(controller);
            if (m.matches() && cronJobs.contains(podKey(namespace, m.group(1)))) {
                return new Owner(namespace, "CronJob", m.group(1));
            }
        }
        return new Owner(namespace, kind.length() > MAX_KIND_LENGTH ? kind.substring(0, MAX_KIND_LENGTH) : kind,
                controller);
    }

    /** {@code namespace/podName -> container -> usage}. */
    private static Map<String, Map<String, Usage>> indexMetrics(List<?> podMetrics) {
        Map<String, Map<String, Usage>> index = new HashMap<>();
        for (Object element : podMetrics) {
            if (!(element instanceof Map<?, ?> pm)) {
                continue;
            }
            String podName = str(pm.get("podName"));
            String namespace = str(pm.get("namespace"));
            if (isBlank(podName) || namespace == null) {
                continue;
            }
            Map<String, Usage> containers = index.computeIfAbsent(podKey(namespace, podName), k -> new HashMap<>());
            for (Object c : list(pm.get("containers"))) {
                if (c instanceof Map<?, ?> cm && !isBlank(str(cm.get("name")))) {
                    containers.put(str(cm.get("name")), new Usage(str(cm.get("cpuUsage")), str(cm.get("memoryUsage"))));
                }
            }
        }
        return index;
    }

    /** {@code namespace/name} of every CronJob. */
    private static Set<String> cronJobNames(List<?> cronJobs) {
        Set<String> names = new HashSet<>();
        for (Object element : cronJobs) {
            if (element instanceof Map<?, ?> cj && !isBlank(str(cj.get("name"))) && str(cj.get("namespace")) != null) {
                names.add(podKey(str(cj.get("namespace")), str(cj.get("name"))));
            }
        }
        return names;
    }

    private static Double optionalCpu(Object quantity, String field) {
        String value = str(quantity);
        return isBlank(value) ? null : Quantities.cpuMillisPrecise(value, field);
    }

    private static Long optionalMem(Object quantity, String field) {
        String value = str(quantity);
        return isBlank(value) ? null : Quantities.memBytes(value, field);
    }

    private static String podKey(String namespace, String name) {
        return namespace + "/" + name;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
