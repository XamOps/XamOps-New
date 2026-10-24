package com.xammer.cloud.service.spot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds agent payloads in the shapes the agents send them ({@code xamops-agent} {@code K8sResources},
 * autoscaler {@code NodeOptimizerReport} / {@code ActionRecord}) for the spot-interruption tests.
 */
final class SpotFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String CREATED = "2026-10-24T06:00:00Z";
    static final Instant CREATED_AT = Instant.parse(CREATED);

    private SpotFixtures() {
    }

    /** A k8s-resources snapshot under construction. Lists left null are omitted (agent: list call failed). */
    static final class Snap {
        final List<Map<String, Object>> nodes = new ArrayList<>();
        final List<Map<String, Object>> pods = new ArrayList<>();
        List<Map<String, Object>> deployments = new ArrayList<>();
        List<Map<String, Object>> statefulSets = new ArrayList<>();
        final List<Map<String, Object>> events = new ArrayList<>();

        Snap node(String name, String capacityType, String... taintKeys) {
            return node(name, capacityType, false, Map.of(), taintKeys);
        }

        Snap node(String name, String capacityType, boolean unschedulable, Map<String, String> labels, String... taintKeys) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("name", name);
            n.put("instanceType", "t3.medium");
            n.put("capacityType", capacityType);
            n.put("zone", "ap-south-1a");
            Map<String, String> l = new LinkedHashMap<>(labels);
            l.putIfAbsent("eks.amazonaws.com/nodegroup", "ng-spot-1");
            n.put("labels", l);
            List<Map<String, String>> taints = new ArrayList<>();
            for (String key : taintKeys) {
                taints.add(Map.of("key", key, "effect", "NoSchedule"));
            }
            if (!taints.isEmpty()) {
                n.put("taints", taints);
            }
            n.put("unschedulable", unschedulable);
            n.put("age", CREATED);
            nodes.add(n);
            return this;
        }

        Snap pod(String ns, String name, String node, String phase, String controllerKind, String controllerName) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", name);
            p.put("namespace", ns);
            if (node != null) {
                p.put("nodeName", node);
            }
            p.put("phase", phase);
            if (controllerKind != null) {
                p.put("controllerKind", controllerKind);
                p.put("controllerName", controllerName);
            }
            pods.add(p);
            return this;
        }

        Snap deployment(String ns, String name, int replicas, int ready) {
            deployments.add(Map.of("namespace", ns, "name", name, "replicas", replicas, "readyReplicas", ready));
            return this;
        }

        Snap statefulSet(String ns, String name, int replicas, int ready) {
            statefulSets.add(Map.of("namespace", ns, "name", name, "replicas", replicas, "readyReplicas", ready));
            return this;
        }

        Snap noDeployments() {
            deployments = null;
            return this;
        }

        Snap noStatefulSets() {
            statefulSets = null;
            return this;
        }

        Snap event(String type, String reason, String objectKind, String involvedObject, String firstTime) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", type);
            e.put("reason", reason);
            e.put("objectKind", objectKind);
            e.put("involvedObject", involvedObject);
            if (firstTime != null) {
                e.put("firstTime", firstTime);
            }
            events.add(e);
            return this;
        }

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodes", nodes);
            m.put("pods", pods);
            if (deployments != null) {
                m.put("deployments", deployments);
            }
            if (statefulSets != null) {
                m.put("statefulSets", statefulSets);
            }
            m.put("events", events);
            return m;
        }

        String json() {
            return SpotFixtures.json(asMap());
        }

        SnapshotView view(Instant at) {
            return SnapshotView.parse(MAPPER, json(), at);
        }
    }

    static Snap snap() {
        return new Snap();
    }

    /** One autoscaler interruption notice ({@code InterruptionNotice} JSON). */
    static Map<String, Object> notice(String node, String kind, String source, boolean drainEligible) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("nodeName", node);
        n.put("nodeCreatedAt", CREATED);
        n.put("instanceId", "i-0abc");
        n.put("instanceType", "t3.medium");
        n.put("zone", "ap-south-1a");
        n.put("capacityType", "spot");
        n.put("nodegroup", "ng-spot-1");
        n.put("kind", kind);
        n.put("source", source);
        n.put("cordoned", false);
        n.put("drainEligible", drainEligible);
        return n;
    }

    /** A node-optimizer-report carrying the given notices and handling mode (null → no decision). */
    static Map<String, Object> report(String mode, boolean detectionFailed, List<Map<String, Object>> notices) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalNodes", 3);
        r.put("interruptedNodes", List.of());
        if (detectionFailed) {
            r.put("interruptedNodesDetectionFailed", true);
        }
        r.put("interruptionNotices", notices);
        if (mode != null) {
            r.put("interruptionHandling", Map.of("mode", mode, "reason", "DRY_RUN=true"));
        }
        return r;
    }

    /** One autoscaler {@code ActionRecord}. */
    static Map<String, Object> action(String target, String status, boolean dryRun, String trigger,
                                      String startedAt, String finishedAt) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "node-optimizer-" + trigger + "-set-1");
        a.put("controller", "node-optimizer");
        a.put("action", "drain");
        a.put("target", target);
        a.put("dryRun", dryRun);
        a.put("status", status);
        a.put("verified", false);
        a.put("reason", "drained 1 node");
        a.put("startedAt", startedAt);
        if (finishedAt != null) {
            a.put("finishedAt", finishedAt);
        }
        a.put("details", Map.of("trigger", trigger));
        return a;
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
