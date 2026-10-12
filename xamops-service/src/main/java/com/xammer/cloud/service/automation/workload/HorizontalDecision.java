package com.xammer.cloud.service.automation.workload;

import java.util.List;

/**
 * The horizontal half of a workload decision. <strong>Modelled, not implemented:</strong> the engine
 * returns {@link State#NOT_EVALUATED} for every input in this run, and a test asserts it.
 *
 * <h2>Why it is modelled now rather than added later</h2>
 *
 * The measured failure was not that either controller was individually miscalibrated — it was that
 * <strong>two controllers rewrote one workload in one cycle with zero cross-reference</strong>, and
 * the CPU request they disagreed about <em>is</em> the HPA's utilisation denominator. A veto bolted
 * onto a vertical-only engine reproduces that split, just with one side silent. Declaring both
 * outputs now, with the reconciliation as a named function, means the horizontal rules land as an
 * implementation of an existing extension point rather than as a second engine.
 *
 * <p>For the record, the Go HPA manager's measured output was pure replica arithmetic —
 * {@code min(2, replicas)}, {@code max(2 × replicas, 3)} and a fixed 80% target for every workload —
 * the {@code p90Cpu} it collected was never used, it proposed an HPA for a pod that could never
 * schedule, and it proposed {@code minReplicas: 2} for a workload whose PDB demanded
 * {@code minAvailable: 3}. None of that is being ported.
 *
 * @param state                   always {@link State#NOT_EVALUATED} in this run
 * @param minReplicas             null until implemented
 * @param maxReplicas             null until implemented
 * @param targetCpuUtilizationPct null until implemented
 * @param reasons                 free-form, empty in this run
 */
public record HorizontalDecision(State state,
                                 Integer minReplicas,
                                 Integer maxReplicas,
                                 Integer targetCpuUtilizationPct,
                                 List<String> reasons) {

    public enum State {
        /** The horizontal rules are not implemented. The only state this run produces. */
        NOT_EVALUATED,
        NO_CHANGE,
        CHANGE
    }

    public HorizontalDecision {
        if (state == null) {
            throw new IllegalArgumentException("state is mandatory");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        if (state == State.NOT_EVALUATED
                && (minReplicas != null || maxReplicas != null || targetCpuUtilizationPct != null)) {
            throw new IllegalArgumentException(
                    "NOT_EVALUATED cannot carry numbers: a half-built feature that emits values is "
                            + "indistinguishable from a working one");
        }
    }

    public static HorizontalDecision notEvaluated() {
        return new HorizontalDecision(State.NOT_EVALUATED, null, null, null, List.of());
    }

    /**
     * The extension point for the deferred horizontal rules. Deliberately unreferenced today.
     *
     * <p>When implemented, it lands inside the engine's reconciliation step and nowhere else. The
     * alternative to freezing the CPU request is <strong>co-adjustment</strong>: preserve the
     * effective trigger point {@code request × targetUtilisation} when the request moves, i.e.
     * {@code newTargetUtilPct = round(oldRequestMillis × oldTargetUtilPct / newRequestMillis)},
     * clamped to a sane band (say 10–90) and refused when the clamp would bind or when a PDB's
     * {@code minAvailable} would exceed the resulting {@code minReplicas}. For the measured
     * {@code php-apache} case (200m at 50%, target request 144m) that is
     * {@code 200 × 50 / 144 ≈ 69%}. Co-adjustment requires writing the HPA, which this engine does
     * not yet own.
     */
    public static HorizontalDecision change(int minReplicas, int maxReplicas, Integer targetCpuUtilizationPct,
                                            List<String> reasons) {
        return new HorizontalDecision(State.CHANGE, minReplicas, maxReplicas, targetCpuUtilizationPct, reasons);
    }

    public static HorizontalDecision noChange(List<String> reasons) {
        return new HorizontalDecision(State.NO_CHANGE, null, null, null, reasons);
    }
}
