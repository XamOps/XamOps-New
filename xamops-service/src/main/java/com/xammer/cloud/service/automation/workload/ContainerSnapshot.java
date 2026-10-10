package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.usage.ContainerUsageAggregate;

/**
 * One container's present state and measured usage.
 *
 * <p>The engine is per container (audit bug 8): the Go rightsizer collected one metric per Deployment
 * name and applied it identically to every container, sidecars included, so a 1-core API container
 * and a 10m log shipper received the same recommendation.
 *
 * @param name    the container name
 * @param current its present requests and limits; {@link ResourceEnvelope#UNSET} when it has none
 * @param usage   its measured usage over the policy window
 */
public record ContainerSnapshot(String name, ResourceEnvelope current, ContainerUsageAggregate usage) {

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
}
