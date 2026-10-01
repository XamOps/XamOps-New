package com.xammer.cloud.util;

/**
 * Splits a cost change into the part caused by using more or less, and the part caused by paying a
 * different rate.
 *
 * <h2>Why this is one class rather than three copies</h2>
 * It was three copies. AWS, Azure and GCP each implemented the same decomposition inline, and the two
 * later ones were written from the formula rather than from the AWS code — so they dropped the
 * new-service fallback AWS had, and reported a service that appeared for the first time as a 100% rate
 * increase. That is the opposite of the truth, and it contradicted the footnote the shared UI shows
 * the reader. Three definitions of one calculation is a calculation that will be fixed in two.
 *
 * <h2>The decomposition</h2>
 * Given cost and usage quantity for two periods:
 *
 * <pre>
 *   R₁ = C₁ / Q₁                     the earlier period's average unit rate
 *   volume = (Q₂ − Q₁) × R₁          the usage change, valued at the old rate
 *   price  = (C₂ − C₁) − volume      whatever the total change leaves over
 * </pre>
 *
 * <p><b>Volume first, price as the residual.</b> Computing both directly — {@code (Q₂−Q₁)R₁} and
 * {@code (R₂−R₁)Q₂} — happens to be algebraically equivalent when there was prior usage, but leaves a
 * cross-term when there was not. Taking price as the remainder guarantees the two always sum to the
 * cost change exactly, which is the property the UI states and the reader checks.
 *
 * <p><b>The interaction belongs to price.</b> When quantity and rate both move the same way, the
 * overlap between them (the extra usage bought at the extra rate) lands in the price term. That is a
 * convention, not a fact: the opposite pairing would put it in volume. It is consistent across all
 * three clouds, which matters more than which side it falls on.
 */
public final class PriceVolumeSplit {

    private PriceVolumeSplit() {
    }

    /**
     * The two effects for one cost change.
     *
     * @param volumeEffect the change attributable to using more or less
     * @param priceEffect  the change attributable to a different unit rate
     */
    public record Effects(double volumeEffect, double priceEffect) {
    }

    /**
     * Decomposes a change, handling the period with no prior usage.
     *
     * <p>The baseline rate falls back to the current rate when there was no prior quantity. Without
     * that, {@code R₁} is zero, the volume term vanishes and the entire cost is reported as a rate
     * increase — for something that did not exist before, and is therefore pure volume.
     *
     * <p>A period with no <em>current</em> usage is the mirror case and needs no special handling: the
     * quantity went to zero, so the volume term carries the whole decrease at the old rate, which is
     * the right answer for a resource that was switched off.
     */
    public static Effects of(double priorCost, double currentCost,
                             double priorQuantity, double currentQuantity) {
        double priorRate = priorQuantity > 0 ? priorCost / priorQuantity : 0.0;
        double currentRate = currentQuantity > 0 ? currentCost / currentQuantity : 0.0;

        double baselineRate = priorQuantity > 0 ? priorRate : currentRate;
        double volumeEffect = (currentQuantity - priorQuantity) * baselineRate;
        double priceEffect = (currentCost - priorCost) - volumeEffect;

        return new Effects(volumeEffect, priceEffect);
    }
}
