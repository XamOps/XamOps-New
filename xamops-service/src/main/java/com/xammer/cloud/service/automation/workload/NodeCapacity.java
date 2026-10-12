package com.xammer.cloud.service.automation.workload;

/**
 * The largest node a pod in this cluster could be scheduled onto.
 *
 * <p>Exists because a recommendation that cannot be scheduled is worse than no recommendation: the
 * pod goes {@code Pending} and the workload loses capacity it had. Measured reference:
 * {@code team-a/unschedulable-giant} asks for 32 CPU / 64Gi on nodes with 1930m / 3294Mi allocatable
 * and had been {@code Pending} for 8 hours.
 *
 * @param maxAllocatableCpuMillis largest allocatable CPU across the cluster's schedulable nodes
 * @param maxAllocatableMemBytes  largest allocatable memory across the same
 */
public record NodeCapacity(long maxAllocatableCpuMillis, long maxAllocatableMemBytes) {

    public NodeCapacity {
        if (maxAllocatableCpuMillis <= 0 || maxAllocatableMemBytes <= 0) {
            throw new IllegalArgumentException(
                    "node capacity must be positive; an unknown capacity is not a capacity of zero");
        }
    }

    /** The measured shape of the reference cluster's nodes (t3.medium). */
    public static NodeCapacity t3Medium() {
        return new NodeCapacity(1930, 3294L * 1024 * 1024);
    }

    /** A capacity large enough never to bind, for fixtures that are not testing the cap. */
    public static NodeCapacity unbounded() {
        return new NodeCapacity(64_000, 512L * 1024 * 1024 * 1024);
    }

    long cpuBudgetMillis(double usableFraction) {
        return Math.round(maxAllocatableCpuMillis * usableFraction);
    }

    long memBudgetBytes(double usableFraction) {
        return Math.round(maxAllocatableMemBytes * usableFraction);
    }
}
