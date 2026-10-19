package com.xammer.cloud.service.automation.workload;

import java.util.OptionalInt;

/**
 * The pure RPS-based replica-sizing calculation (F3), reimplementing k8opt's intent natively:
 * hold a target utilisation at the observed throughput against a <strong>real</strong> per-replica
 * capacity.
 *
 * <pre>{@code
 * neededReplicas = ceil( (observedRps / capacityPerReplicaRps) / targetUtil )
 *   then, IF an HPA range is present, bound by [minReplicas, maxReplicas]; otherwise unbounded
 * }</pre>
 *
 * <h2>Capacity is never self-derived (the H1 fix)</h2>
 *
 * {@code capacityPerReplicaRps} is a real, independent input supplied by the caller — never derived
 * from the same {@code observedRps} the formula divides by. The prior design derived capacity from
 * the live RPS and then divided the live RPS back into it, which cancelled {@code observedRps}
 * entirely and collapsed the result to a CPU-utilisation rescale with zero throughput content. There
 * is <strong>no</strong> method on this helper that consumes {@code observedRps} to produce a
 * capacity; two different {@code observedRps} values yield two different counts for a fixed real
 * capacity, which is the regression guard.
 *
 * <h2>Total function over the bounds (finding 1)</h2>
 *
 * The bounds are nullable {@link Integer} and bounding is a <strong>total</strong> branch, never a
 * suppression:
 *
 * <ul>
 *   <li>both {@code minReplicas} and {@code maxReplicas} non-null → clamp the computed count to
 *       {@code [min, max]};</li>
 *   <li>either null (no HPA, or a partial range) → return the computed count <strong>unbounded</strong>.</li>
 * </ul>
 *
 * Absence of bounds changes only the provenance note the caller renders; it never fabricates a
 * {@code 0}/{@code MAX_VALUE} clamp and never turns a successfully-computed count into an empty
 * result. The only empty results are the true "cannot size" cases:
 * {@code capacityPerReplicaRps <= 0}, {@code targetUtil <= 0}, or any non-finite input.
 *
 * <p>Pure: no I/O, no Spring, no ambient state.
 */
public final class RpsReplicaSizing {

    private RpsReplicaSizing() {
    }

    /**
     * The recommended replica count, or empty when the inputs cannot produce one.
     *
     * @param observedRps           total observed request rate the replicas must serve
     * @param capacityPerReplicaRps the real per-replica capacity (supplied metadata), &gt; 0
     * @param targetUtil            the target utilisation to hold, e.g. {@code 0.70}; &gt; 0
     * @param minReplicas           the HPA minimum, or {@code null} when no HPA range applies
     * @param maxReplicas           the HPA maximum, or {@code null} when no HPA range applies
     * @return the computed count (clamped only when both bounds are present), or
     *         {@link OptionalInt#empty()} only for a non-positive capacity, a non-positive target, or
     *         a non-finite input
     */
    public static OptionalInt size(double observedRps, double capacityPerReplicaRps, double targetUtil,
                                   Integer minReplicas, Integer maxReplicas) {
        if (!Double.isFinite(observedRps) || !Double.isFinite(capacityPerReplicaRps)
                || !Double.isFinite(targetUtil)) {
            return OptionalInt.empty();
        }
        if (capacityPerReplicaRps <= 0 || targetUtil <= 0) {
            return OptionalInt.empty();
        }
        double raw = Math.ceil((observedRps / capacityPerReplicaRps) / targetUtil);
        // ceil of a finite non-negative quotient is a whole number that fits an int for any sane
        // throughput; guard the extreme anyway so a pathological capacity can never overflow.
        long rounded = (long) raw;
        if (bounded(minReplicas, maxReplicas)) {
            long clamped = Math.max(minReplicas, Math.min(maxReplicas, rounded));
            return OptionalInt.of((int) clamped);
        }
        long safe = Math.max(0L, Math.min(Integer.MAX_VALUE, rounded));
        return OptionalInt.of((int) safe);
    }

    /**
     * Whether a real HPA range exists — both bounds non-null — so the caller can choose the
     * {@code (no HPA range)} formula note without re-deriving the condition.
     */
    public static boolean bounded(Integer minReplicas, Integer maxReplicas) {
        return minReplicas != null && maxReplicas != null;
    }
}
