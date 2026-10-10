package com.xammer.cloud.service.automation.workload;

import java.util.List;
import java.util.Set;

/**
 * What the engine decided for one container.
 *
 * <p>{@code recommended} is <strong>null when nothing is recommended</strong>. It is never a copy of
 * {@code current}, never a zero and never a placeholder: a caller reading a non-null
 * {@code recommended} knows a real change was decided, and does not have to compare it against
 * {@code current} to find out.
 *
 * @param containerName the container
 * @param qosClass      the <em>pod's</em> QoS class, which is what the UI shows on the row
 * @param current       its present envelope; {@code null} fields mean unset, never {@code 0}
 * @param recommended   the decided envelope, or null when no change is recommended
 * @param reasons       why — including why nothing moved
 * @param changeKind    direction, measured against the current request
 */
public record ContainerDecision(String containerName,
                                QosClass qosClass,
                                ResourceEnvelope current,
                                ResourceEnvelope recommended,
                                List<ContainerReason> reasons,
                                ChangeKind changeKind) {

    public ContainerDecision {
        if (containerName == null || containerName.isBlank()) {
            throw new IllegalArgumentException("containerName is mandatory");
        }
        if (current == null) {
            throw new IllegalArgumentException("current is mandatory");
        }
        if (changeKind == null) {
            throw new IllegalArgumentException("changeKind is mandatory");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        if (recommended == null && changeKind != ChangeKind.NONE) {
            throw new IllegalArgumentException(
                    "no recommendation but changeKind " + changeKind + ": one of the two is wrong");
        }
        if (recommended != null && changeKind == ChangeKind.NONE) {
            throw new IllegalArgumentException(
                    "a recommendation with changeKind NONE would be indistinguishable from no change");
        }
        if (recommended == null && reasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "a container with no recommendation must say why; silence is what made the Go "
                            + "output unreviewable");
        }
    }

    public boolean hasRecommendation() {
        return recommended != null;
    }

    /**
     * Whether this container's recommendation may be applied without a human first naming the
     * numbers. {@code REQUESTS_UNSET} is advisory by definition: the engine is inventing a size for a
     * container that never had one.
     */
    public boolean autoApplyEligible() {
        return recommended != null && !reasons.contains(ContainerReason.REQUESTS_UNSET);
    }

    static ContainerDecision noChange(String containerName, QosClass qosClass, ResourceEnvelope current,
                                      Set<ContainerReason> reasons) {
        return new ContainerDecision(containerName, qosClass, current, null,
                List.copyOf(reasons), ChangeKind.NONE);
    }
}
