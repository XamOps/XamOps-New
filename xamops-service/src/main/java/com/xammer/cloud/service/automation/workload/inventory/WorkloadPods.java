package com.xammer.cloud.service.automation.workload.inventory;

import com.xammer.cloud.service.automation.workload.WorkloadRef;

import java.util.List;

/**
 * The pods that actually belong to one workload, resolved through {@code ownerReferences}.
 *
 * <h2>Why resolution is by uid, not by name (audit bug 8)</h2>
 *
 * The Go rightsizer selected pods with {@code pod=~"<name>-.*"} and no namespace. That regex claims
 * {@code php-apache-canary-7d9f}'s pods for {@code php-apache}, claims a different namespace's
 * identically-named workload, and keeps claiming the pods of a Deployment that was deleted and
 * recreated under new ownership. Owner-reference resolution — Deployment → ReplicaSet → Pod by
 * {@code metadata.uid}, StatefulSet → Pod by uid — is exact across all three.
 *
 * <p>Both the names and the uids are kept: the names are what the usage query selects on, and the
 * uids are what an executor must re-check before patching a pod, since a name can be reused by a new
 * pod between the read and the apply.
 *
 * @param ref  the workload these pods belong to
 * @param pods its pods; empty when the workload currently has none
 */
public record WorkloadPods(WorkloadRef ref, List<Pod> pods) {

    public WorkloadPods {
        if (ref == null) {
            throw new IllegalArgumentException("ref is mandatory");
        }
        pods = pods == null ? List.of() : List.copyOf(pods);
    }

    /**
     * @param name {@code metadata.name}
     * @param uid  {@code metadata.uid} — identity that survives a name being reused
     */
    public record Pod(String name, String uid) {

        public Pod {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("pod name is mandatory");
            }
        }
    }

    public List<String> names() {
        return pods.stream().map(Pod::name).toList();
    }

    public boolean isEmpty() {
        return pods.isEmpty();
    }
}
