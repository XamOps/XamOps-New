package com.xammer.cloud.service.automation.workload;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The engine's answer for one workload.
 *
 * <p>Carries <strong>both</strong> halves — vertical (implemented) and horizontal (modelled, always
 * {@link HorizontalDecision.State#NOT_EVALUATED} in this run) — because they were reconciled by one
 * function rather than produced by two components that never spoke.
 *
 * @param ref               identity
 * @param skipReason        non-null when a gate stopped evaluation; the vertical half is then empty
 * @param findings          workload-level observations; a finding never implies a recommendation
 * @param provenance        where the numbers came from
 * @param autoApplyEligible whether anything here may be applied without a human naming the numbers
 * @param vertical          per-container decisions
 * @param horizontal        the horizontal state, kept in step with {@link #hpa}
 * @param hpa               the HPA bounds / target decision (D1–D4); the rich horizontal half,
 *                          carrying reasons and the formula the read path surfaces
 * @param rps               the RPS-based replica-sizing advisory (F3), or {@code null} when the
 *                          feature did not run for this workload. Optional and carries no cross-field
 *                          invariant — the engine never sets it; the read service attaches it after
 *                          {@code engine.decide(...)} via {@link #withRps(RpsReplicaRecommendation)}.
 */
public record WorkloadDecision(WorkloadRef ref,
                               SkipReason skipReason,
                               Set<Finding> findings,
                               Provenance provenance,
                               boolean autoApplyEligible,
                               VerticalDecision vertical,
                               HorizontalDecision horizontal,
                               HpaDecision hpa,
                               RpsReplicaRecommendation rps) {

    /**
     * The shape the pure engine and every existing fixture build: no RPS advisory. {@code rps}
     * defaults to {@code null}, so the engine's {@code new WorkloadDecision(...)} call sites and the
     * tests that predate F3 compile unchanged and read as "the RPS feature did not run".
     */
    public WorkloadDecision(WorkloadRef ref, SkipReason skipReason, Set<Finding> findings,
                            Provenance provenance, boolean autoApplyEligible, VerticalDecision vertical,
                            HorizontalDecision horizontal, HpaDecision hpa) {
        this(ref, skipReason, findings, provenance, autoApplyEligible, vertical, horizontal, hpa, null);
    }

    public WorkloadDecision {
        if (ref == null) {
            throw new IllegalArgumentException("ref is mandatory");
        }
        if (provenance == null) {
            throw new IllegalArgumentException("provenance is mandatory on every decision");
        }
        if (vertical == null) {
            throw new IllegalArgumentException("vertical is mandatory");
        }
        if (horizontal == null) {
            throw new IllegalArgumentException("horizontal is mandatory");
        }
        if (hpa == null) {
            throw new IllegalArgumentException("hpa is mandatory");
        }
        findings = findings == null || findings.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(findings));
        if (skipReason != null && !vertical.containerDecisions().isEmpty()) {
            throw new IllegalArgumentException("skipped with skipReason " + skipReason
                    + " but carries container decisions; a gated workload must carry none at all, "
                    + "not empty ones");
        }
        if (autoApplyEligible && skipReason != null) {
            throw new IllegalArgumentException("a skipped workload cannot be auto-apply eligible");
        }
    }

    /**
     * A copy of this decision with the RPS advisory set, every other field preserved.
     *
     * <p>The single attach mechanism the read service uses after {@code engine.decide(...)}: it keeps
     * the engine output immutable and the {@code VerticalDecisionMapper.toDto(...)} signature stable
     * (the mapper reads {@code decision.rps()} rather than taking it as a new parameter).
     */
    public WorkloadDecision withRps(RpsReplicaRecommendation rps) {
        return new WorkloadDecision(ref, skipReason, findings, provenance, autoApplyEligible,
                vertical, horizontal, hpa, rps);
    }

    public boolean skipped() {
        return skipReason != null;
    }

    public List<ContainerDecision> containers() {
        return vertical.containerDecisions();
    }
}
