package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.service.autospotting.ledger.calc.AwsBillingUnits.Charge;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.EndCause;

/**
 * GCP provider billing units (design-ledger.md §3.5, V-LED-3): Spot is billed per second with a one-minute
 * minimum, and a Spot VM preempted inside its first minute is not billed. The counterfactual always uses actual
 * seconds and the Spot side the larger figure, so a rounding question can only lower the savings.
 */
final class GcpBillingUnits {
    static final long MINIMUM_SECONDS = 60L;

    private GcpBillingUnits() {}

    /** Classifies a known end; an unknown or running end is always {@link Charge#NORMAL} here. */
    static Charge charge(boolean endKnown, long life, EndCause cause) {
        if (!endKnown || life >= MINIMUM_SECONDS) {
            return Charge.NORMAL;
        }
        EndCause effective = cause == null ? EndCause.UNKNOWN : cause;
        return switch (effective) {
            case PROVIDER_INTERRUPTED -> Charge.NOT_CHARGED;
            case UNKNOWN -> Charge.CAUSE_UNKNOWN;
            case TERMINATED -> Charge.NORMAL;
        };
    }

    /** Seconds GCP bills for a Spot VM that lived {@code life} seconds. */
    static long billedSeconds(long life) {
        return life <= 0 ? 0L : Math.max(MINIMUM_SECONDS, life);
    }
}
