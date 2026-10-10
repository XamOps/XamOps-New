package com.xammer.cloud.service.automation.workload;

/**
 * Identity of one workload inside one cluster.
 *
 * <p><strong>{@code namespace} is mandatory</strong> (audit bug 8). The Go rightsizer identified a
 * workload by Deployment <em>name alone</em> and queried usage with {@code pod=~"<name>-.*"} and no
 * namespace, so one metric was collected per name and applied identically to every container,
 * sidecars included. A ref that cannot be constructed without a namespace makes that class of
 * mistake unrepresentable rather than merely discouraged.
 *
 * <p>{@code ownerUid} is the workload object's {@code metadata.uid} when known. Usage providers must
 * resolve pods through owner references (Deployment → ReplicaSet → Pod), never by a name regex; the
 * uid is what makes that resolution exact across a rename or a recreate. It is nullable because the
 * engine's unit fixtures do not have one.
 *
 * @param namespace the Kubernetes namespace; never null or blank
 * @param kind      workload kind, e.g. {@code Deployment}
 * @param name      workload name
 * @param ownerUid  {@code metadata.uid} of the workload object, or null when not known
 */
public record WorkloadRef(String namespace, String kind, String name, String ownerUid) {

    public WorkloadRef {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("workload namespace is mandatory");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("workload kind is mandatory");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("workload name is mandatory");
        }
    }

    public static WorkloadRef of(String namespace, String kind, String name) {
        return new WorkloadRef(namespace, kind, name, null);
    }

    /** {@code namespace/name} — for logs and test failure messages, never for lookups. */
    public String describe() {
        return namespace + "/" + name;
    }
}
