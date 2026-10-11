package com.xammer.cloud.service.automation.workload.inventory;

import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.NodeCapacity;
import com.xammer.cloud.service.automation.workload.Quantities;
import com.xammer.cloud.service.automation.workload.QuantityParseException;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerResizePolicy;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads everything the engine needs to know about a cluster's workloads, in one pass.
 *
 * <h2>Why this exists</h2>
 *
 * {@code WorkloadSnapshot} needs replicas, rollout state, the lock annotation, the HPA, the PDB and a
 * per-container {@code ResourceEnvelope}; {@code NodeCapacity} needs the largest schedulable node.
 * Without this reader the read endpoint cannot return a single real workload, whatever the usage
 * source says.
 *
 * <h2>Seven list calls, not N+1</h2>
 *
 * Pods, ReplicaSets, Deployments, StatefulSets, HPAs, PDBs and Nodes are each listed once and joined
 * in memory. A per-workload pod lookup would be one API call per workload per poll.
 *
 * <h2>What it refuses to do</h2>
 *
 * <ul>
 *   <li><strong>Claim pods by name.</strong> Resolution is Deployment → ReplicaSet → Pod and
 *       StatefulSet → Pod, by {@code metadata.uid}. See {@link WorkloadPods}.</li>
 *   <li><strong>Turn an unset resource into {@code 0}.</strong> A container with no requests gets
 *       {@link ResourceEnvelope#UNSET}, whose fields are {@code null}. The measured {@code 0m / 1Mi}
 *       fabricated baseline has nowhere to come from.</li>
 *   <li><strong>Read a protected namespace.</strong> {@link WorkloadPolicyDefaults#PROTECTED_NAMESPACES}
 *       workloads are not even collected, so nothing downstream can decide about them.</li>
 *   <li><strong>Guess past an unreadable quantity.</strong> A workload whose resource strings do not
 *       parse is dropped with a warning rather than defaulted — {@code Quantities} has no
 *       {@code parseOrDefault} for the same reason.</li>
 * </ul>
 */
@Component
public class ClusterWorkloadInventory {

    private static final Logger logger = LoggerFactory.getLogger(ClusterWorkloadInventory.class);

    private static final String KIND_DEPLOYMENT = "Deployment";
    private static final String KIND_STATEFUL_SET = "StatefulSet";
    private static final String CPU = "cpu";
    private static final String MEMORY = "memory";

    /** One pass over a cluster. */
    public Inventory read(KubernetesClient client) {
        List<Pod> pods = client.pods().inAnyNamespace().list().getItems();
        List<ReplicaSet> replicaSets = client.apps().replicaSets().inAnyNamespace().list().getItems();
        List<Deployment> deployments = client.apps().deployments().inAnyNamespace().list().getItems();
        List<StatefulSet> statefulSets = client.apps().statefulSets().inAnyNamespace().list().getItems();
        List<HorizontalPodAutoscaler> hpas =
                client.autoscaling().v2().horizontalPodAutoscalers().inAnyNamespace().list().getItems();
        List<PodDisruptionBudget> pdbs =
                client.policy().v1().podDisruptionBudget().inAnyNamespace().list().getItems();
        List<Node> nodes = client.nodes().list().getItems();

        Map<String, List<Pod>> podsByOwnerUid = podsByOwnerUid(pods);
        Map<String, List<String>> replicaSetUidsByOwnerUid = replicaSetUidsByOwnerUid(replicaSets);

        List<Workload> workloads = new ArrayList<>();
        for (Deployment deployment : deployments) {
            Workload workload = workload(deployment, KIND_DEPLOYMENT, deployment.getSpec() == null
                            ? null : deployment.getSpec().getTemplate(),
                    replicas(deployment.getSpec() == null ? null : deployment.getSpec().getReplicas()),
                    observedGeneration(deployment.getStatus() == null
                            ? null : deployment.getStatus().getObservedGeneration()),
                    deploymentPods(deployment, replicaSetUidsByOwnerUid, podsByOwnerUid),
                    hpas, pdbs);
            if (workload != null) {
                workloads.add(workload);
            }
        }
        for (StatefulSet statefulSet : statefulSets) {
            Workload workload = workload(statefulSet, KIND_STATEFUL_SET, statefulSet.getSpec() == null
                            ? null : statefulSet.getSpec().getTemplate(),
                    replicas(statefulSet.getSpec() == null ? null : statefulSet.getSpec().getReplicas()),
                    observedGeneration(statefulSet.getStatus() == null
                            ? null : statefulSet.getStatus().getObservedGeneration()),
                    ownedPods(uid(statefulSet), podsByOwnerUid),
                    hpas, pdbs);
            if (workload != null) {
                workloads.add(workload);
            }
        }
        return new Inventory(List.copyOf(workloads), capacity(nodes));
    }

    // ---------------------------------------------------------------- one workload

    /** @return the workload, or null when it is protected, template-less or unreadable */
    private Workload workload(HasMetadata object, String kind, PodTemplateSpec template, int replicas,
                              Long observedGeneration, List<Pod> ownedPods,
                              List<HorizontalPodAutoscaler> hpas, List<PodDisruptionBudget> pdbs) {
        ObjectMeta metadata = object.getMetadata();
        if (metadata == null || metadata.getNamespace() == null || metadata.getName() == null) {
            return null;
        }
        String namespace = metadata.getNamespace();
        if (WorkloadPolicyDefaults.PROTECTED_NAMESPACES.contains(namespace.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (template == null || template.getSpec() == null || template.getSpec().getContainers() == null
                || template.getSpec().getContainers().isEmpty()) {
            return null;
        }
        WorkloadRef ref = new WorkloadRef(namespace, kind, metadata.getName(), metadata.getUid());

        List<ContainerSpec> containers = new ArrayList<>();
        for (Container container : template.getSpec().getContainers()) {
            if (container.getName() == null || container.getName().isBlank()) {
                return null;
            }
            try {
                containers.add(new ContainerSpec(container.getName(), envelope(container.getResources()),
                        resizePolicy(container)));
            } catch (QuantityParseException unreadable) {
                // Dropped, not defaulted: a fabricated baseline is the failure this engine exists to
                // prevent, and the API server does not normally produce an unparseable quantity.
                logger.warn("Skipping {} {}: container '{}' has an unreadable resource quantity ({})",
                        kind, ref.describe(), container.getName(), unreadable.getMessage());
                return null;
            }
        }

        return new Workload(ref, replicas,
                rolloutInProgress(metadata.getGeneration(), observedGeneration),
                lockActive(metadata),
                hpaFor(ref, hpas),
                pdbMinAvailable(ref, template, pdbs),
                List.copyOf(containers),
                new WorkloadPods(ref, ownedPods.stream()
                        .map(pod -> new WorkloadPods.Pod(pod.getMetadata().getName(),
                                pod.getMetadata().getUid()))
                        .toList()));
    }

    /**
     * Requests and limits as the engine's canonical units, with <strong>unset staying null</strong>.
     *
     * <p>{@code dev-sim/no-requests-sidecar-app} declares no {@code resources} block at all, and this
     * is the method that has to keep saying so all the way to the wire.
     */
    private static ResourceEnvelope envelope(ResourceRequirements resources) {
        if (resources == null) {
            return ResourceEnvelope.UNSET;
        }
        return new ResourceEnvelope(
                cpu(resources.getRequests(), "cpu request"),
                cpu(resources.getLimits(), "cpu limit"),
                memory(resources.getRequests(), "memory request"),
                memory(resources.getLimits(), "memory limit"));
    }

    private static Integer cpu(Map<String, Quantity> values, String field) {
        Quantity quantity = values == null ? null : values.get(CPU);
        return quantity == null ? null : Quantities.cpuMillis(quantity.toString(), field);
    }

    private static Long memory(Map<String, Quantity> values, String field) {
        Quantity quantity = values == null ? null : values.get(MEMORY);
        return quantity == null ? null : Quantities.memBytes(quantity.toString(), field);
    }

    /**
     * {@code resourceName -> restartPolicy}, as declared.
     *
     * <p>Carried because a container declaring {@code RestartContainer} for a resource being changed
     * is restarted by the kubelet when that resource is resized. An executor that promised
     * "restart-free" without reading this would be lying; none of the nine seeded workloads declares
     * one, so the live proof is expected to be restart-free.
     */
    private static Map<String, String> resizePolicy(Container container) {
        List<ContainerResizePolicy> declared = container.getResizePolicy();
        if (declared == null || declared.isEmpty()) {
            return Map.of();
        }
        Map<String, String> byResource = new LinkedHashMap<>();
        for (ContainerResizePolicy policy : declared) {
            if (policy.getResourceName() != null && policy.getRestartPolicy() != null) {
                byResource.put(policy.getResourceName(), policy.getRestartPolicy());
            }
        }
        return Map.copyOf(byResource);
    }

    private static boolean rolloutInProgress(Long generation, Long observedGeneration) {
        if (generation == null || observedGeneration == null) {
            // Unknown is not "settled": a rollout in flight makes current usage a transient, so the
            // safe reading of an absent observedGeneration is that the controller has not caught up.
            return generation != null;
        }
        return !generation.equals(observedGeneration);
    }

    private static boolean lockActive(ObjectMeta metadata) {
        Map<String, String> annotations = metadata.getAnnotations();
        if (annotations == null) {
            return false;
        }
        return WorkloadPolicyDefaults.LOCK_ANNOTATION_ACTIVE
                .equalsIgnoreCase(annotations.get(WorkloadPolicyDefaults.LOCK_ANNOTATION));
    }

    // ---------------------------------------------------------------- owner resolution

    private static Map<String, List<Pod>> podsByOwnerUid(List<Pod> pods) {
        Map<String, List<Pod>> byOwner = new LinkedHashMap<>();
        for (Pod pod : pods) {
            if (pod.getMetadata() == null || pod.getMetadata().getName() == null) {
                continue;
            }
            for (OwnerReference owner : owners(pod.getMetadata())) {
                if (owner.getUid() != null) {
                    byOwner.computeIfAbsent(owner.getUid(), key -> new ArrayList<>()).add(pod);
                }
            }
        }
        return byOwner;
    }

    private static Map<String, List<String>> replicaSetUidsByOwnerUid(List<ReplicaSet> replicaSets) {
        Map<String, List<String>> byOwner = new LinkedHashMap<>();
        for (ReplicaSet replicaSet : replicaSets) {
            String replicaSetUid = uid(replicaSet);
            if (replicaSetUid == null || replicaSet.getMetadata() == null) {
                continue;
            }
            for (OwnerReference owner : owners(replicaSet.getMetadata())) {
                if (owner.getUid() != null) {
                    byOwner.computeIfAbsent(owner.getUid(), key -> new ArrayList<>()).add(replicaSetUid);
                }
            }
        }
        return byOwner;
    }

    /** Deployment → every ReplicaSet it owns → every Pod those own. Old ReplicaSets included. */
    private static List<Pod> deploymentPods(Deployment deployment,
                                            Map<String, List<String>> replicaSetUidsByOwnerUid,
                                            Map<String, List<Pod>> podsByOwnerUid) {
        String deploymentUid = uid(deployment);
        if (deploymentUid == null) {
            return List.of();
        }
        List<Pod> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String replicaSetUid : replicaSetUidsByOwnerUid.getOrDefault(deploymentUid, List.of())) {
            for (Pod pod : podsByOwnerUid.getOrDefault(replicaSetUid, List.of())) {
                if (seen.add(pod.getMetadata().getUid() == null
                        ? pod.getMetadata().getName() : pod.getMetadata().getUid())) {
                    resolved.add(pod);
                }
            }
        }
        return resolved;
    }

    private static List<Pod> ownedPods(String ownerUid, Map<String, List<Pod>> podsByOwnerUid) {
        return ownerUid == null ? List.of() : podsByOwnerUid.getOrDefault(ownerUid, List.of());
    }

    private static List<OwnerReference> owners(ObjectMeta metadata) {
        return metadata.getOwnerReferences() == null ? List.of() : metadata.getOwnerReferences();
    }

    private static String uid(HasMetadata object) {
        return object.getMetadata() == null ? null : object.getMetadata().getUid();
    }

    // ---------------------------------------------------------------- HPA, PDB, nodes

    /**
     * The HPA that targets this workload, matched on namespace plus {@code scaleTargetRef} kind and
     * name — never a prefix, which would make {@code php-apache} and {@code php-apache-canary} the
     * same workload.
     */
    private static HpaView hpaFor(WorkloadRef ref, List<HorizontalPodAutoscaler> hpas) {
        for (HorizontalPodAutoscaler hpa : hpas) {
            if (hpa.getMetadata() == null || hpa.getSpec() == null
                    || hpa.getSpec().getScaleTargetRef() == null) {
                continue;
            }
            HpaView view = new HpaView(hpa.getMetadata().getName(), hpa.getMetadata().getNamespace(),
                    hpa.getSpec().getScaleTargetRef().getKind(),
                    hpa.getSpec().getScaleTargetRef().getName(),
                    hpa.getSpec().getMinReplicas(), hpa.getSpec().getMaxReplicas(),
                    hpa.getStatus() == null ? null : hpa.getStatus().getCurrentReplicas(),
                    resourceMetricPresent(hpa, CPU), resourceMetricPresent(hpa, MEMORY),
                    targetCpuUtilization(hpa),
                    lastScaleTime(hpa));
            if (view.targets(ref)) {
                return view;
            }
        }
        return null;
    }

    private static boolean resourceMetricPresent(HorizontalPodAutoscaler hpa, String resource) {
        for (MetricSpec metric : metrics(hpa)) {
            if ("Resource".equals(metric.getType()) && metric.getResource() != null
                    && resource.equals(metric.getResource().getName())) {
                return true;
            }
        }
        return false;
    }

    private static Integer targetCpuUtilization(HorizontalPodAutoscaler hpa) {
        for (MetricSpec metric : metrics(hpa)) {
            if ("Resource".equals(metric.getType()) && metric.getResource() != null
                    && CPU.equals(metric.getResource().getName())
                    && metric.getResource().getTarget() != null) {
                return metric.getResource().getTarget().getAverageUtilization();
            }
        }
        return null;
    }

    private static List<MetricSpec> metrics(HorizontalPodAutoscaler hpa) {
        if (hpa.getSpec() == null || hpa.getSpec().getMetrics() == null) {
            return List.of();
        }
        return hpa.getSpec().getMetrics();
    }

    private static Instant lastScaleTime(HorizontalPodAutoscaler hpa) {
        String lastScaleTime = hpa.getStatus() == null ? null : hpa.getStatus().getLastScaleTime();
        if (lastScaleTime == null || lastScaleTime.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(lastScaleTime);
        } catch (RuntimeException notAnInstant) {
            logger.debug("Unparseable HPA lastScaleTime '{}'; treating the HPA as never scaled",
                    lastScaleTime);
            return null;
        }
    }

    /**
     * A PDB's {@code minAvailable}, when it is an absolute number and its selector matches this
     * workload's pod template.
     *
     * <p>A percentage {@code minAvailable} reads as null: the engine's field is an absolute count,
     * and converting a percentage here would invent a number that depends on a replica count the PDB
     * does not mention.
     */
    private static Integer pdbMinAvailable(WorkloadRef ref, PodTemplateSpec template,
                                           List<PodDisruptionBudget> pdbs) {
        Map<String, String> labels = template.getMetadata() == null || template.getMetadata().getLabels() == null
                ? Map.of() : template.getMetadata().getLabels();
        for (PodDisruptionBudget pdb : pdbs) {
            if (pdb.getMetadata() == null || !ref.namespace().equals(pdb.getMetadata().getNamespace())
                    || pdb.getSpec() == null || pdb.getSpec().getMinAvailable() == null
                    || pdb.getSpec().getSelector() == null) {
                continue;
            }
            Map<String, String> selector = pdb.getSpec().getSelector().getMatchLabels();
            if (selector == null || selector.isEmpty() || !matches(selector, labels)) {
                continue;
            }
            Integer absolute = pdb.getSpec().getMinAvailable().getIntVal();
            if (absolute != null) {
                return absolute;
            }
        }
        return null;
    }

    private static boolean matches(Map<String, String> selector, Map<String, String> labels) {
        for (Map.Entry<String, String> entry : selector.entrySet()) {
            if (!entry.getValue().equals(labels.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The largest allocatable CPU and the largest allocatable memory across <strong>schedulable</strong>
     * nodes.
     *
     * <p>Per resource, not per node: the engine caps CPU and memory independently, so the question it
     * asks is "is there a node that could take this much CPU" and separately "…this much memory". On a
     * homogeneous node group — the measured reference cluster, and the common case — both maxima come
     * from the same shape of node anyway.
     *
     * <p>A cordoned node is excluded: a pod cannot be scheduled onto it, so its size is not capacity.
     *
     * @return null when the cluster has no schedulable node with readable allocatable values — an
     *         unknown capacity is not a capacity of zero, and {@link NodeCapacity} refuses to express
     *         one
     */
    private static NodeCapacity capacity(List<Node> nodes) {
        long maxCpuMillis = 0;
        long maxMemBytes = 0;
        for (Node node : nodes) {
            if (node.getSpec() != null && Boolean.TRUE.equals(node.getSpec().getUnschedulable())) {
                continue;
            }
            Map<String, Quantity> allocatable =
                    node.getStatus() == null ? null : node.getStatus().getAllocatable();
            if (allocatable == null) {
                continue;
            }
            try {
                Integer cpu = cpu(allocatable, "node allocatable cpu");
                Long memory = memory(allocatable, "node allocatable memory");
                if (cpu != null) {
                    maxCpuMillis = Math.max(maxCpuMillis, cpu);
                }
                if (memory != null) {
                    maxMemBytes = Math.max(maxMemBytes, memory);
                }
            } catch (QuantityParseException unreadable) {
                logger.warn("Ignoring node {} in capacity: {}",
                        node.getMetadata() == null ? "?" : node.getMetadata().getName(),
                        unreadable.getMessage());
            }
        }
        if (maxCpuMillis <= 0 || maxMemBytes <= 0) {
            return null;
        }
        return new NodeCapacity(maxCpuMillis, maxMemBytes);
    }

    private static int replicas(Integer declared) {
        return declared == null ? 1 : Math.max(0, declared);
    }

    private static Long observedGeneration(Long observed) {
        return observed;
    }

    // ---------------------------------------------------------------- results

    /**
     * @param workloads every candidate workload, protected namespaces already excluded
     * @param capacity  the largest schedulable node, or null when the cluster reports none
     */
    public record Inventory(List<Workload> workloads, NodeCapacity capacity) {

        public Inventory {
            workloads = workloads == null ? List.of() : List.copyOf(workloads);
        }
    }

    /**
     * One workload's present state, with its pods resolved — everything a {@code WorkloadSnapshot}
     * needs except the measured usage, which only the usage provider can supply.
     */
    public record Workload(WorkloadRef ref,
                           int replicas,
                           boolean rolloutInProgress,
                           boolean lockAnnotationActive,
                           HpaView hpa,
                           Integer pdbMinAvailable,
                           List<ContainerSpec> containers,
                           WorkloadPods pods) {

        public Workload {
            containers = containers == null ? List.of() : List.copyOf(containers);
        }

        public List<String> containerNames() {
            return containers.stream().map(ContainerSpec::name).toList();
        }
    }

    /**
     * @param current      the container's declared requests and limits; {@code UNSET}, never zeros
     * @param resizePolicy {@code resourceName -> restartPolicy}, empty when none is declared
     */
    public record ContainerSpec(String name, ResourceEnvelope current, Map<String, String> resizePolicy) {

        public ContainerSpec {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("container name is mandatory");
            }
            if (current == null) {
                throw new IllegalArgumentException(
                        "use ResourceEnvelope.UNSET for a container with no requests, never a"
                                + " fabricated zero");
            }
            resizePolicy = resizePolicy == null ? Map.of() : Map.copyOf(resizePolicy);
        }
    }
}
