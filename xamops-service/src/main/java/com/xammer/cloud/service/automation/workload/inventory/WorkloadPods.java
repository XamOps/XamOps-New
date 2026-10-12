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
 * <h2>The pods that were left out are kept too (review-2 SF3)</h2>
 *
 * Only Running, non-deleting pods of the current revision are resized. The others are recorded in
 * {@code skipped} with why, so an outcome can say "every running pod (2 of 3)" instead of claiming
 * every pod while a Pending one will start at the template's size.
 *
 * @param ref       the workload these pods belong to
 * @param pods      its live pods; empty when the workload currently has none
 * @param nameRules the pod-name shapes it has owned; empty when none could be resolved
 * @param skipped   its pods that are not resized, and why
 */
public record WorkloadPods(WorkloadRef ref, List<Pod> pods, List<PodNameRule> nameRules,
                           List<Skipped> skipped) {

    /** Not Running (Pending, Failed, Succeeded or unknown); {@link Skipped#phase()} says which. */
    public static final String SKIPPED_NOT_RUNNING = "NOT_RUNNING";
    /** Being deleted. */
    public static final String SKIPPED_TERMINATING = "TERMINATING";
    /** Owned by an older revision during a rollout, so sized against another template. */
    public static final String SKIPPED_OLD_REVISION = "OLD_REVISION";

    public WorkloadPods {
        if (ref == null) {
            throw new IllegalArgumentException("ref is mandatory");
        }
        pods = pods == null ? List.of() : List.copyOf(pods);
        nameRules = nameRules == null ? List.of() : List.copyOf(nameRules);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    public WorkloadPods(WorkloadRef ref, List<Pod> pods, List<PodNameRule> nameRules) {
        this(ref, pods, nameRules, List.of());
    }

    /** Current pods with no historical shapes — the pre-addendum-A shape, kept for callers that
     * genuinely only need the live pods (every executor path). */
    public WorkloadPods(WorkloadRef ref, List<Pod> pods) {
        this(ref, pods, List.of(), List.of());
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

    /**
     * @param phase  {@code status.phase}, or null when the pod reports none
     * @param reason {@link #SKIPPED_NOT_RUNNING}, {@link #SKIPPED_TERMINATING} or
     *               {@link #SKIPPED_OLD_REVISION}
     */
    public record Skipped(String name, String phase, String reason) {

        public Skipped {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("pod name is mandatory");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason is mandatory");
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
