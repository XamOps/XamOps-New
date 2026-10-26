package com.xammer.cloud.service.spot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One XamOps agent {@code k8s-resources} snapshot, parsed <strong>once</strong> with
 * {@code readTree} and shared by every spot-interruption step (design §B4.2, NFR4).
 *
 * <p>Tolerant by construction: a missing field reads as null/false, unknown fields are ignored.
 * Presence is recorded separately from emptiness for the two workload lists, because the agent
 * serializes a failed list call and a cluster with none of that kind identically
 * ({@code "deployments": null}, {@code k8s_resources.go:96-99}, {@code types.go:309}): a missing
 * or empty list is "not observed", never "deleted".
 */
public final class SnapshotView {

    /** A node as the agent's {@code NodeDetail} reports it. */
    public record NodeView(String name,
                           Instant createdAt,
                           String instanceType,
                           String zone,
                           String capacityType,
                           String nodegroup,
                           List<String> taintKeys,
                           Map<String, String> labels,
                           boolean unschedulable) {
        public boolean isSpot() {
            return "spot".equalsIgnoreCase(capacityType);
        }
    }

    /** A pod as the agent's {@code PodDetail} reports it (controller owner reference, not normalized). */
    public record PodView(String name, String namespace, String nodeName, String phase,
                          String controllerKind, String controllerName) {
    }

    /** A Deployment's or StatefulSet's replica readiness. */
    public record WorkloadStatus(String namespace, String name, int replicas, int readyReplicas) {
    }

    /** One collected Kubernetes event. */
    public record EventView(String type, String reason, String objectKind, String involvedObject,
                            String firstTime, String lastTime) {
    }

    private final Instant snapshotAt;
    private final boolean nodesPresentAndNonEmpty;
    private final Map<String, NodeView> nodesByName;
    private final List<PodView> pods;
    private final Map<String, List<PodView>> podsByNode;
    private final Map<String, WorkloadStatus> deployments;
    private final Map<String, WorkloadStatus> statefulSets;
    private final boolean deploymentsPresentAndNonEmpty;
    private final boolean statefulSetsPresentAndNonEmpty;
    private final List<EventView> events;

    private SnapshotView(JsonNode root, Instant snapshotAt) {
        this.snapshotAt = snapshotAt;
        JsonNode nodes = root.path("nodes");
        this.nodesPresentAndNonEmpty = nodes.isArray() && !nodes.isEmpty();
        Map<String, NodeView> byName = new LinkedHashMap<>();
        if (nodes.isArray()) {
            for (JsonNode n : nodes) {
                String name = text(n, "name");
                if (name == null) {
                    continue;
                }
                Map<String, String> labels = new LinkedHashMap<>();
                n.path("labels").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
                List<String> taints = new ArrayList<>();
                for (JsonNode t : n.path("taints")) {
                    String key = text(t, "key");
                    if (key != null) {
                        taints.add(key);
                    }
                }
                String nodegroup = labels.get("eks.amazonaws.com/nodegroup");
                if (nodegroup == null || nodegroup.isBlank()) {
                    nodegroup = labels.get("karpenter.sh/nodepool");
                }
                byName.put(name, new NodeView(name, parseInstant(text(n, "age")), text(n, "instanceType"),
                        text(n, "zone"), text(n, "capacityType"),
                        nodegroup == null || nodegroup.isBlank() ? null : nodegroup,
                        List.copyOf(taints), Collections.unmodifiableMap(labels),
                        n.path("unschedulable").asBoolean(false)));
            }
        }
        this.nodesByName = Collections.unmodifiableMap(byName);

        List<PodView> podList = new ArrayList<>();
        Map<String, List<PodView>> byNode = new LinkedHashMap<>();
        for (JsonNode p : root.path("pods")) {
            PodView pod = new PodView(text(p, "name"), text(p, "namespace"), text(p, "nodeName"),
                    text(p, "phase"), text(p, "controllerKind"), text(p, "controllerName"));
            podList.add(pod);
            if (pod.nodeName() != null) {
                byNode.computeIfAbsent(pod.nodeName(), k -> new ArrayList<>()).add(pod);
            }
        }
        this.pods = List.copyOf(podList);
        this.podsByNode = Collections.unmodifiableMap(byNode);

        JsonNode deps = root.path("deployments");
        this.deploymentsPresentAndNonEmpty = deps.isArray() && !deps.isEmpty();
        this.deployments = workloads(deps);
        JsonNode sts = root.path("statefulSets");
        this.statefulSetsPresentAndNonEmpty = sts.isArray() && !sts.isEmpty();
        this.statefulSets = workloads(sts);

        List<EventView> eventList = new ArrayList<>();
        for (JsonNode e : root.path("events")) {
            eventList.add(new EventView(text(e, "type"), text(e, "reason"), text(e, "objectKind"),
                    text(e, "involvedObject"), text(e, "firstTime"), text(e, "lastTime")));
        }
        this.events = List.copyOf(eventList);
    }

    /**
     * Parses one snapshot. Returns null when there is no JSON, it is not an object, or it does not
     * parse: an unreadable snapshot is unknown, never an empty cluster.
     */
    public static SnapshotView parse(ObjectMapper mapper, String json, Instant snapshotAt) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(json);
            return root != null && root.isObject() ? new SnapshotView(root, snapshotAt) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, WorkloadStatus> workloads(JsonNode list) {
        Map<String, WorkloadStatus> out = new LinkedHashMap<>();
        if (list.isArray()) {
            for (JsonNode w : list) {
                String ns = text(w, "namespace");
                String name = text(w, "name");
                if (name == null) {
                    continue;
                }
                out.put(ns + "/" + name, new WorkloadStatus(ns, name,
                        w.path("replicas").asInt(0), w.path("readyReplicas").asInt(0)));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    /** RFC3339 (agent {@code age}, Karpenter {@code firstTime}, notice {@code nodeCreatedAt}); null when absent or unparseable. */
    public static Instant parseInstant(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(rfc3339).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Server receive time of this snapshot. */
    public Instant snapshotAt() {
        return snapshotAt;
    }

    /** True when {@code nodes} is a non-empty array; a failed collection never looks like removals. */
    public boolean nodesPresentAndNonEmpty() {
        return nodesPresentAndNonEmpty;
    }

    public Map<String, NodeView> nodesByName() {
        return nodesByName;
    }

    public NodeView node(String name) {
        return nodesByName.get(name);
    }

    public boolean listsNode(String name) {
        return nodesByName.containsKey(name);
    }

    public List<PodView> pods() {
        return pods;
    }

    public List<PodView> podsOn(String nodeName) {
        return podsByNode.getOrDefault(nodeName, List.of());
    }

    /** Deployments by {@code ns/name}. */
    public Map<String, WorkloadStatus> deployments() {
        return deployments;
    }

    /** StatefulSets by {@code ns/name}. */
    public Map<String, WorkloadStatus> statefulSets() {
        return statefulSets;
    }

    /** The {@code ns/name} keys of {@code deployments[]}, for {@link WorkloadKeys#normalize}. */
    public Set<String> deploymentKeys() {
        return deployments.keySet();
    }

    public boolean deploymentsPresentAndNonEmpty() {
        return deploymentsPresentAndNonEmpty;
    }

    public boolean statefulSetsPresentAndNonEmpty() {
        return statefulSetsPresentAndNonEmpty;
    }

    public List<EventView> events() {
        return events;
    }
}
