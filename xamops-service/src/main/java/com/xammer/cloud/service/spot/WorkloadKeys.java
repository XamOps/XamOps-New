package com.xammer.cloud.service.spot;

import java.util.Set;

/**
 * Normalizes a pod's controller owner reference to the workload recovery is measured on
 * (design §B3.3). The agent reports Deployment pods as {@code ReplicaSet/<deployment>-<hash>} and
 * ownerless pods as {@code ""} ({@code k8s_resources.go:712-719}); the dashboard does the same
 * mapping in {@code AgentK8sDashboardMapper}. Used by both the workload builder and the recovery
 * evaluator so they can never disagree.
 */
public final class WorkloadKeys {

    private WorkloadKeys() {
    }

    /** A normalized workload identity. */
    public record Key(String namespace, String kind, String name) {
    }

    /**
     * @param snapshotDeployments the {@code ns/name} keys of {@code deployments[]} in the snapshot
     *                            the pod came from
     */
    public static Key normalize(String ns, String kind, String name, String podName, Set<String> snapshotDeployments) {
        if ("ReplicaSet".equals(kind) && name != null) {
            int i = name.lastIndexOf('-');
            String dep = i > 0 ? name.substring(0, i) : null;
            if (dep != null && snapshotDeployments.contains(ns + "/" + dep)) {
                kind = "Deployment";
                name = dep;
            }
            // else keep kind=ReplicaSet (bare ReplicaSet): contributes only via the "no Pending pod" check.
            // The builder never reaches this with an unobserved Deployment list: it returns null first (§B4.2 capture rule 3).
        }
        if (kind == null || kind.isEmpty()) {
            kind = "Pod";
            name = podName;
        }   // ownerless pod (agent sends "")
        return new Key(ns, kind, name);
    }

    /** True for the two kinds recovery is measured on. */
    public static boolean isMeasurable(String kind) {
        return "Deployment".equals(kind) || "StatefulSet".equals(kind);
    }
}
