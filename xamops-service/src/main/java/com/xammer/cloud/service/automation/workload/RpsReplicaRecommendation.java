package com.xammer.cloud.service.automation.workload;

/**
 * The RPS-based replica-sizing advisory for one workload (F3), carried on {@link WorkloadDecision}
 * rather than on {@link HpaDecision}.
 *
 * <h2>Why it is not on {@code HpaDecision}</h2>
 *
 * {@code HpaDecision}'s canonical constructor throws when a non-{@code CHANGE} decision carries a
 * number, and the common case for this advisory is exactly a workload whose HPA bounds are
 * <em>not</em> changing ({@code NO_CHANGE}/{@code NOT_EVALUATED}). Placing a replica count there
 * would either trip that invariant or defeat it. So the advisory is a separate, optional value on
 * {@link WorkloadDecision} that the mapper reads alongside {@code decision.hpa()}.
 *
 * <h2>Recommend-only</h2>
 *
 * This is advisory context next to the HPA tuning. It is never applied, never patches
 * min/max/target, and {@code HorizontalDto.autoApplyEligible} stays false at the mapper regardless
 * of what this carries.
 *
 * @param replicas the recommended replica count, or {@code null} in the two dash outcomes
 *                 ({@link RpsReason#NO_RPS_SOURCE} / {@link RpsReason#NO_CAPACITY_BASIS})
 * @param reason   why this is sized or dashed; never null
 * @param formula  the human-readable derivation (e.g.
 *                 {@code "ceil(1200 / 50 / 0.70) = 35, clamped to HPA [2,10] → 10"}), non-null only
 *                 when {@code replicas} is
 */
public record RpsReplicaRecommendation(Integer replicas, RpsReason reason, String formula) {

    public RpsReplicaRecommendation {
        if (reason == null) {
            throw new IllegalArgumentException("reason is mandatory on an RPS advisory");
        }
        boolean sized = reason == RpsReason.RPS_SIZED;
        if (sized && replicas == null) {
            throw new IllegalArgumentException("RPS_SIZED must carry a replica count");
        }
        if (!sized && replicas != null) {
            throw new IllegalArgumentException(
                    "a dash outcome (" + reason + ") must carry no replica count, not " + replicas);
        }
        if ((replicas == null) != (formula == null)) {
            throw new IllegalArgumentException(
                    "the formula is present exactly when a replica count is: a number must say how it"
                            + " was derived, and a dash has nothing to derive");
        }
    }

    /** The sized outcome: a real count with the derivation string. */
    public static RpsReplicaRecommendation sized(int replicas, String formula) {
        return new RpsReplicaRecommendation(replicas, RpsReason.RPS_SIZED, formula);
    }

    /** Throughput unknown / feature off: a dash with {@link RpsReason#NO_RPS_SOURCE}. */
    public static RpsReplicaRecommendation noRpsSource() {
        return new RpsReplicaRecommendation(null, RpsReason.NO_RPS_SOURCE, null);
    }

    /** Throughput known but no configured capacity: a dash with {@link RpsReason#NO_CAPACITY_BASIS}. */
    public static RpsReplicaRecommendation noCapacityBasis() {
        return new RpsReplicaRecommendation(null, RpsReason.NO_CAPACITY_BASIS, null);
    }
}
