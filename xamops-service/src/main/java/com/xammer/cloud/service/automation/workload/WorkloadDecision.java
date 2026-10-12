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
 * @param horizontal        modelled; {@code NOT_EVALUATED} this run
 */
public record WorkloadDecision(WorkloadRef ref,
                               SkipReason skipReason,
                               Set<Finding> findings,
                               Provenance provenance,
                               boolean autoApplyEligible,
                               VerticalDecision vertical,
                               HorizontalDecision horizontal) {

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

    public boolean skipped() {
        return skipReason != null;
    }

    public List<ContainerDecision> containers() {
        return vertical.containerDecisions();
    }
}
