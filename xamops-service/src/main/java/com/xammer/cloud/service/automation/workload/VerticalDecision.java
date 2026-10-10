package com.xammer.cloud.service.automation.workload;

import java.util.List;

/**
 * The vertical half of a workload decision: one entry per container, always.
 *
 * @param containerDecisions one per container in the snapshot, in the same order; empty only when a
 *                           gate skipped the workload entirely
 */
public record VerticalDecision(List<ContainerDecision> containerDecisions) {

    public VerticalDecision {
        containerDecisions = containerDecisions == null ? List.of() : List.copyOf(containerDecisions);
    }

    public static VerticalDecision skipped() {
        return new VerticalDecision(List.of());
    }

    /** True when at least one container has a recommendation. */
    public boolean hasRecommendations() {
        return containerDecisions.stream().anyMatch(ContainerDecision::hasRecommendation);
    }
}
