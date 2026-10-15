package com.xammer.cloud.service.automation.workload;

import java.util.List;

/**
 * The engine's decision about one HPA's bounds and target — the horizontal counterpart to
 * {@link VerticalDecision}.
 *
 * <p>Produced inside {@link WorkloadAutoscalingEngine#reconcile}, the single place vertical and
 * horizontal meet, so a target co-adjust (D3a) sees the same cycle's vertical draft. Like the
 * vertical decision it is a plain value: no I/O, no Spring, assertable as a literal fixture.
 *
 * <p><strong>{@code null} means leave that field alone.</strong> A diff of the non-null fields
 * against the current HPA is what an apply would patch — the same convention the vertical
 * {@link ResourceEnvelope} uses. {@link #noChange} carries no numbers; a value in any of
 * {@code minReplicas} / {@code maxReplicas} / {@code targetUtilizationPct} only ever appears on a
 * {@link State#CHANGE}.
 *
 * @param state                the decision state; {@link State#NOT_EVALUATED} while the horizontal
 *                             flag is off, {@link State#NO_CHANGE} or {@link State#CHANGE} otherwise
 * @param minReplicas          proposed {@code spec.minReplicas}, or null to leave it alone
 * @param maxReplicas          proposed {@code spec.maxReplicas}, or null to leave it alone
 * @param targetUtilizationPct proposed CPU {@code averageUtilization}, or null to leave it alone
 * @param reasons              why, including why nothing moved; never null
 * @param formula              the human-readable derivation, e.g. {@code "ceil(5 × 64 ÷ 50) capped +3"},
 *                             or null when there is no number to explain
 */
public record HpaDecision(State state,
                          Integer minReplicas,
                          Integer maxReplicas,
                          Integer targetUtilizationPct,
                          List<HpaReason> reasons,
                          String formula) {

    /** The three decision states, mirroring {@link HorizontalDecision.State}. */
    public enum State {
        /** The horizontal rules were not run (the master flag is off). */
        NOT_EVALUATED,
        /** The rules ran and nothing should change. */
        NO_CHANGE,
        /** The rules ran and at least one bound or the target should change. */
        CHANGE
    }

    public HpaDecision {
        if (state == null) {
            throw new IllegalArgumentException("state is mandatory");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        if (state != State.CHANGE
                && (minReplicas != null || maxReplicas != null || targetUtilizationPct != null)) {
            throw new IllegalArgumentException(
                    state + " cannot carry numbers: a decision that is not a change but emits values "
                            + "is indistinguishable from one that is");
        }
        if (state == State.CHANGE
                && minReplicas == null && maxReplicas == null && targetUtilizationPct == null) {
            throw new IllegalArgumentException("a CHANGE must move at least one field");
        }
    }

    /** The modelled state: the rules were not run at all. */
    public static HpaDecision notEvaluated() {
        return new HpaDecision(State.NOT_EVALUATED, null, null, null, List.of(), null);
    }

    /** The rules ran and found nothing to change; the reasons say why. */
    public static HpaDecision noChange(List<HpaReason> reasons) {
        return new HpaDecision(State.NO_CHANGE, null, null, null, reasons, null);
    }

    /** A real change to one or more of the bounds / target, with the formula that produced it. */
    public static HpaDecision change(Integer minReplicas, Integer maxReplicas,
                                     Integer targetUtilizationPct, List<HpaReason> reasons,
                                     String formula) {
        return new HpaDecision(State.CHANGE, minReplicas, maxReplicas, targetUtilizationPct,
                reasons, formula);
    }

    public boolean isChange() {
        return state == State.CHANGE;
    }

    /** The matching {@link HorizontalDecision} state, so both halves stay in step. */
    public HorizontalDecision.State horizontalState() {
        return switch (state) {
            case NOT_EVALUATED -> HorizontalDecision.State.NOT_EVALUATED;
            case NO_CHANGE -> HorizontalDecision.State.NO_CHANGE;
            case CHANGE -> HorizontalDecision.State.CHANGE;
        };
    }
}
