package com.xammer.cloud.service.automation.workload.inventory;

import com.xammer.cloud.service.automation.workload.PodNameRule;
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
 * <h2>Names alone are not enough for history (orchestrator addendum A)</h2>
 *
 * A pod list is a snapshot of <em>now</em>. A usage query built from it measures only as far back as
 * the newest pod, so one rollout or one eviction resets a workload's observed history to nothing. So
 * the resolution also yields {@link PodNameRule}s — the pod-name shapes this workload's controllers
 * produce (one per retained ReplicaSet for a Deployment, one for a StatefulSet) — which is what lets
 * a provider select the pods the workload owned <strong>during</strong> the window without going back
 * to a prefix match.
 *
 * @param ref       the workload these pods belong to
 * @param pods      its pods; empty when the workload currently has none
 * @param nameRules the pod-name shapes it has owned; empty when none could be resolved
 */
public record WorkloadPods(WorkloadRef ref, List<Pod> pods, List<PodNameRule> nameRules) {

    public WorkloadPods {
        if (ref == null) {
            throw new IllegalArgumentException("ref is mandatory");
        }
        pods = pods == null ? List.of() : List.copyOf(pods);
        nameRules = nameRules == null ? List.of() : List.copyOf(nameRules);
    }

    /** Current pods with no historical shapes — the pre-addendum-A shape, kept for callers that
     * genuinely only need the live pods (every executor path). */
    public WorkloadPods(WorkloadRef ref, List<Pod> pods) {
        this(ref, pods, List.of());
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
