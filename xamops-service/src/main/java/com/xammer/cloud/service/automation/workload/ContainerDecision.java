package com.xammer.cloud.service.automation.workload;

import java.util.List;
import java.util.Set;

/**
 * What the engine decided for one container.
 *
 * <p>{@code recommended} is <strong>null when nothing is recommended</strong>. It is never a zero
 * and never a placeholder: a non-null {@code recommended} means at least one field was decided.
 *
 * <h2>It is a complete envelope, not a change set</h2>
 *
 * A non-null {@code recommended} is the <strong>whole desired state</strong> of the container, so a
 * field that was deliberately left alone carries its current value rather than a null. That is
 * required, not incidental: the QoS rules are a property of the complete {@code (request, limit)}
 * pairs, so {@link ResourceEnvelope#recommendation} can only validate a complete envelope — and
 * {@code null} inside an envelope already means "unset", so it cannot also mean "unchanged" without
 * making the two indistinguishable.
 *
 * <p><strong>Consequence for consumers: a per-field change must be found by diffing against
 * {@code current}.</strong> The clearest case is an HPA-managed workload, where {@code reconcile}
 * freezes the CPU request ({@code HPA_CPU_MANAGED}) while memory still moves: the recommended CPU
 * request then equals the current one, because the engine refused to touch it. Anything turning a
 * decision into a cluster write must therefore go through
 * {@code ContainerApplyTarget.ofChanges(ContainerDecision)}, which performs exactly that diff and
 * leaves a frozen field null — i.e. unpatched. Writing {@code recommended} wholesale would patch a
 * request the engine deliberately did not decide.
 *
 * @param containerName the container
 * @param qosClass      the <em>pod's</em> QoS class, which is what the UI shows on the row
 * @param current       its present envelope; {@code null} fields mean unset, never {@code 0}
 * @param recommended   the complete decided envelope, or null when no change is recommended
 * @param reasons       why — including why nothing moved
 * @param changeKind    direction, measured against the current request
 * @param confidence    the measured confidence facts, <strong>non-null exactly when
 *                      {@code recommended != null}</strong> and null otherwise — a confidence score
 *                      with no recommendation to qualify is meaningless. Carries measured facts only
 *                      (score, the coverage used, the window); the presentational band and
 *                      {@code belowFloor} are computed downstream in the mapper from the configurable
 *                      cut points, so the pure engine never reads config.
 */
public record ContainerDecision(String containerName,
                                QosClass qosClass,
                                ResourceEnvelope current,
                                ResourceEnvelope recommended,
                                List<ContainerReason> reasons,
                                ChangeKind changeKind,
                                ConfidenceView confidence) {

    /**
     * The measured confidence facts attached to a recommendation.
     *
     * @param score          the {@code [0,1]} score from {@link ConfidenceScore#of}
     * @param sampleCoverage the aggregate's coverage the score used, carried for the UI "why" line
     * @param windowHours    the policy window the measurement covered
     */
    public record ConfidenceView(double score, double sampleCoverage, int windowHours) {
    }

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
        if (recommended == null && confidence != null) {
            throw new IllegalArgumentException(
                    "a confidence score with no recommendation to qualify is meaningless: confidence "
                            + "is attached exactly when there is a recommendation");
        }
    }

    /** The shape every caller used before confidence: no confidence view attached. */
    public ContainerDecision(String containerName, QosClass qosClass, ResourceEnvelope current,
                             ResourceEnvelope recommended, List<ContainerReason> reasons,
                             ChangeKind changeKind) {
        this(containerName, qosClass, current, recommended, reasons, changeKind, null);
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
