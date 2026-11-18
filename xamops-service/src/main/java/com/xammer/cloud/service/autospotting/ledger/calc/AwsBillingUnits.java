package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.EndCause;

/**
 * AWS provider billing units (design-ledger.md §3.5). Spot is billed per second with a 60 second
 * minimum, SUSE per full hour. The counterfactual always uses actual seconds, the smaller figure, and
 * the Spot side the larger one, so a rounding question can only lower the savings.
 */
final class AwsBillingUnits {
    /** Per-second billing with this minimum, in seconds. */
    static final long MINIMUM_SECONDS = 60L;
    static final long HOUR = 3600L;

    /** How the provider charges an interval that ended inside its first instance-hour. */
    enum Charge {
        /** Charged normally. */
        NORMAL,
        /** Interrupted by EC2 in the first instance-hour: no charge, zero CF and zero Spot cost. */
        NOT_CHARGED,
        /** Ended in the first instance-hour, cause unknown: CF 0, Spot cost at the upper bound. */
        CAUSE_UNKNOWN
    }

    private AwsBillingUnits() {}

    /**
     * Classifies a known end. {@code life} is the instance's whole life in seconds; an interval that is
     * still running or has an unknown end is always {@link Charge#NORMAL} here (its gap is
     * {@code UNKNOWN_INTERVAL_END}).
     */
    static Charge charge(boolean endKnown, long life, EndCause cause) {
        if (!endKnown || life >= HOUR) {
            return Charge.NORMAL;
        }
        EndCause effective = cause == null ? EndCause.UNKNOWN : cause;
        return switch (effective) {
            case PROVIDER_INTERRUPTED -> Charge.NOT_CHARGED;
            case UNKNOWN -> Charge.CAUSE_UNKNOWN;
            case TERMINATED -> Charge.NORMAL;
        };
    }

    /** Seconds the provider charges for an instance that lived {@code life} seconds. */
    static long billedSeconds(boolean suse, long life) {
        if (life <= 0) {
            return 0L;
        }
        if (suse) {
            return ((life + HOUR - 1) / HOUR) * HOUR;
        }
        return Math.max(MINIMUM_SECONDS, life);
    }

    static boolean isSuse(String normalizedPlatform) {
        return "SUSE".equals(normalizedPlatform);
    }
}
