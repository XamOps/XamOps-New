package com.xammer.cloud.service.automation.workload;

/**
 * How a recommendation is written to the cluster.
 *
 * <p>Shared vocabulary between the policy model and the (deferred) executor, which is why it sits in
 * the root package rather than in either.
 */
public enum ApplyMethod {

    /**
     * Patch the running pods' {@code resize} subresource only. The default, and it needs no opt-in.
     *
     * <p>Measured on EKS 1.36.4: the resize subresource changed CPU <em>and</em> memory with the
     * <strong>same pod UID, same containerID and {@code restartCount: 0}</strong> — no restart, no
     * rescheduling, no new ReplicaSet. It requires only {@code pods/resize: [patch]}.
     *
     * <p>Its limitation is real and must be stated: the pod-scoped resize leaves the Deployment
     * template untouched, so replicas created later start at the old size.
     */
    IN_PLACE,

    /**
     * In-place resize <em>and</em> patch the Deployment template so new replicas inherit the size.
     *
     * <p><strong>Restarts every pod</strong> (rolling replacement, new pod UIDs) and needs a
     * separate, opt-in ClusterRole carrying {@code apps/deployments: [patch]}. Granting that verb by
     * default is what let the Go rightsizer force a full rolling replacement while writing requests
     * without limits, silently downgrading QoS from Guaranteed to Burstable. Refused on a
     * GitOps-managed workload, whose template belongs to Argo CD or Flux.
     */
    IN_PLACE_AND_PERSIST
}
