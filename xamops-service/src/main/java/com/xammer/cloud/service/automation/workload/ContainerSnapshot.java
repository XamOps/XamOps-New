package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.usage.ContainerUsageAggregate;

/**
 * One container's present state and measured usage.
 *
 * <p>The engine is per container (audit bug 8): the Go rightsizer collected one metric per Deployment
 * name and applied it identically to every container, sidecars included, so a 1-core API container
 * and a 10m log shipper received the same recommendation.
 *
 * <h2>When the live pods disagree (review-2 SF2)</h2>
 *
 * After a partial apply or a replaced pod, the pods of one workload can run different sizes.
 * {@code current} is then the template, the size every pod converges to and new pods start at. But
 * the OOM guard must not judge against it: a pod resized down to 250Mi that is being OOM-killed needs
 * a raise above 250Mi, and against a 1 GiB template that raise reads as a decrease and is dropped.
 * {@code smallestLive} carries the per-field minimum the live pods run with, and
 * {@link #oomGuardMemBytes()} is what the guard compares against.
 *
 * @param name         the container name
 * @param current      its present requests and limits; {@link ResourceEnvelope#UNSET} when it has none
 * @param usage        its measured usage over the policy window
 * @param smallestLive the smallest value per field across its live pods when they disagree, else null
 */
public record ContainerSnapshot(String name, ResourceEnvelope current, ContainerUsageAggregate usage,
                                ResourceEnvelope smallestLive) {

    public ContainerSnapshot {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("container name is mandatory");
        }
        if (current == null) {
            throw new IllegalArgumentException(
                    "current is mandatory; use ResourceEnvelope.UNSET for a container with no requests, "
                            + "never a fabricated zero");
        }
        if (usage == null) {
            throw new IllegalArgumentException("usage is mandatory: there is no deciding without measuring");
        }
        if (!name.equals(usage.containerName())) {
            throw new IllegalArgumentException("usage for container '" + usage.containerName()
                    + "' attached to container '" + name
                    + "'; one metric applied to the wrong container is exactly audit bug 8");
        }
    }

    /** A container whose live pods agree, or have not reported. */
    public ContainerSnapshot(String name, ResourceEnvelope current, ContainerUsageAggregate usage) {
        this(name, current, usage, null);
    }

    /** Whether the live pods run different resources for this container. */
    public boolean podsDisagree() {
        return smallestLive != null;
    }

    /**
     * The memory request the OOM guard judges a target against: the smallest one a live pod runs
     * with when they disagree, else {@code current}'s.
     */
    public Long oomGuardMemBytes() {
        Long declared = current.memRequestBytes();
        Long live = smallestLive == null ? null : smallestLive.memRequestBytes();
        if (declared == null) {
            return live;
        }
        return live == null ? declared : Math.min(declared, live);
    }
}
