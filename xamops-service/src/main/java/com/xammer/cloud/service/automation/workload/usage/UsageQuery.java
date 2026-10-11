package com.xammer.cloud.service.automation.workload.usage;

import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;

import java.util.List;

/**
 * What to measure. Namespace-scoped and owner-selected by construction.
 *
 * <p>{@link WorkloadRef} carries a mandatory namespace and an optional owner uid, so an
 * implementation has everything it needs to resolve pods through owner references
 * (Deployment → ReplicaSet → Pod). The Go rightsizer's query was
 * {@code pod=~"overprovisioned-api-.*"} with the workload identified by Deployment name alone and
 * <strong>no namespace</strong> — one metric per name, applied to every container including
 * sidecars.
 *
 * @param target         which cluster
 * @param ref            which workload, namespace mandatory
 * @param containerNames the containers to measure; at least one, and the result must be keyed by
 *                       these names
 * @param podNames       the pods that <strong>actually belong</strong> to this workload, resolved
 *                       through {@code ownerReferences} by the cluster inventory. Mandatory and
 *                       non-empty: it is what a provider selects on instead of a name regex, so the
 *                       Go rightsizer's {@code pod=~"<name>-.*"} has nowhere to come back from.
 * @param percentile     the percentile the policy asks for; the result must contain exactly it
 * @param windowHours    the policy window
 */
public record UsageQuery(ClusterTarget target, WorkloadRef ref, List<String> containerNames,
                         List<String> podNames, int percentile, int windowHours) {

    public UsageQuery {
        if (target == null) {
            throw new IllegalArgumentException("target is mandatory");
        }
        if (ref == null) {
            throw new IllegalArgumentException("ref is mandatory");
        }
        if (containerNames == null || containerNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "containerNames is mandatory: a query for 'the workload' is how one metric ends up"
                            + " applied to every container");
        }
        containerNames = List.copyOf(containerNames);
        if (podNames == null || podNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "podNames is mandatory: a query that does not name the pods it measures is how"
                            + " pod=~\"<name>-.*\" ends up selecting another workload's pods");
        }
        podNames = List.copyOf(podNames);
        if (percentile < 1 || percentile > 100) {
            throw new IllegalArgumentException("percentile out of range: " + percentile);
        }
        if (windowHours < 1) {
            throw new IllegalArgumentException("windowHours must be positive, got " + windowHours);
        }
    }
}
