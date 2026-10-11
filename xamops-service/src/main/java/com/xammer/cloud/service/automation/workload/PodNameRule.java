package com.xammer.cloud.service.automation.workload;

/**
 * One <strong>shape</strong> of pod name a workload's pods are guaranteed to have, so a usage query
 * can measure the pods the workload owned <em>during the window</em> and not only the ones it owns
 * right now.
 *
 * <h2>Why a shape and not a list of names (orchestrator addendum A)</h2>
 *
 * Selecting usage by the workload's <em>current</em> pod names makes a workload's history only as old
 * as its newest pod: any rollout, eviction, node drain or reschedule resets it to zero. With a 72h
 * window and {@link WorkloadPolicyDefaults#MIN_SAMPLE_COVERAGE} at 0.5, a workload would need 36h of
 * uninterrupted pod life before it could be sized at all — so the engine would rarely recommend
 * anything for a service that deploys daily. Upstream VPA aggregates per controller and container,
 * which is the correct semantics, and this type is how that semantics is reached without widening the
 * selector into the prefix match the audit removed.
 *
 * <h2>Why this cannot become {@code pod=~"<name>-.*"}</h2>
 *
 * The rule carries a <strong>literal</strong> prefix and a closed {@link Kind}. The rendering lives in
 * {@code PromQl}, which regex-escapes the prefix and appends one of exactly two fixed suffix
 * expressions. There is no field, and no constructor, through which {@code .*} can reach a query:
 * {@code php-apache} can never claim {@code php-apache-canary}'s pods, because a pod of the canary
 * Deployment is named {@code php-apache-canary-<hash>-<5 chars>} and that does not match
 * {@code php-apache-<hash>-[a-z0-9]{5}} under Prometheus' full anchoring.
 *
 * <h2>Known limitation, stated rather than hidden</h2>
 *
 * A Deployment's history reaches back only as far as its <em>retained</em> ReplicaSets
 * ({@code spec.revisionHistoryLimit}, 10 by default). A revision pruned by the Deployment controller
 * takes its pod-name shape with it, so its samples are no longer selected. That under-counts history;
 * it never over-counts, so it cannot manufacture a recommendation.
 *
 * @param prefix the literal name the pods are derived from — a ReplicaSet name for
 *               {@link Kind#REPLICA_SET_POD}, a StatefulSet name for {@link Kind#STATEFUL_SET_POD}
 * @param kind   which naming scheme the controller uses
 */
public record PodNameRule(String prefix, Kind kind) {

    /** The two pod-naming schemes this engine's inventory can produce. */
    public enum Kind {

        /**
         * {@code <replicaset name>-<5 random chars>} — how the ReplicaSet controller names a pod,
         * and therefore how every Deployment pod is named.
         */
        REPLICA_SET_POD,

        /**
         * {@code <statefulset name>-<ordinal>} — stable identities, so a StatefulSet's historical
         * pods are named exactly like its current ones even after it scales down and back up.
         */
        STATEFUL_SET_POD
    }

    public PodNameRule {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException(
                    "a pod-name rule without a prefix would match every pod in the namespace");
        }
        if (prefix.length() > 253) {
            throw new IllegalArgumentException("prefix is longer than a Kubernetes name: " + prefix);
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is mandatory");
        }
    }

    /** The rule for every pod a ReplicaSet of this name has ever created. */
    public static PodNameRule replicaSetPods(String replicaSetName) {
        return new PodNameRule(replicaSetName, Kind.REPLICA_SET_POD);
    }

    /** The rule for every ordinal a StatefulSet of this name has ever had. */
    public static PodNameRule statefulSetPods(String statefulSetName) {
        return new PodNameRule(statefulSetName, Kind.STATEFUL_SET_POD);
    }
}
