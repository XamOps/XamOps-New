package com.xammer.cloud.service.automation.workload;

/**
 * A change is worth acting on only when it clears <strong>both</strong> a relative and an absolute
 * threshold.
 *
 * <h2>Why {@code AND}, not {@code OR}</h2>
 *
 * The deliberately correctly-sized control workload (80m CPU, 64Mi) was measured being cut by
 * <strong>−32.8%, i.e. by 26m</strong>. A relative-only band cannot suppress that without being set
 * above ~33%, which would also suppress a genuine 30% decrease on a 1000m container — 300m, real
 * money. An absolute-only band cannot suppress a {@code 1Mi → 4Mi} thrash step, which is a 300%
 * delta. Requiring both means the suppressed region is exactly "small in percentage terms
 * <em>or</em> small in absolute terms" — where acting costs more churn than it returns capacity.
 *
 * <p>The thresholds are also <strong>asymmetric by direction</strong>, because an increase relieves
 * measured pressure while a decrease speculatively reclaims capacity and is the direction that
 * causes outages. See {@link WorkloadPolicyDefaults} for each number and its justification.
 *
 * <p>The delta is always measured against the <strong>current</strong> request. That is what makes
 * the measured thrash loop structurally impossible: a second cycle computing the same target sees a
 * zero delta and recommends nothing.
 *
 * @param minPercent  minimum change as a percentage of the current request
 * @param minAbsolute minimum change in canonical units (millicores or bytes)
 */
public record HysteresisBand(int minPercent, long minAbsolute) {

    public HysteresisBand {
        if (minPercent < 0) {
            throw new IllegalArgumentException("minPercent cannot be negative");
        }
        if (minAbsolute < 0) {
            throw new IllegalArgumentException("minAbsolute cannot be negative");
        }
    }

    /**
     * @param current the present request, in canonical units
     * @param target  the computed target, in canonical units
     * @return true when the move clears both thresholds and should be recommended
     */
    public boolean clears(long current, long target) {
        long delta = Math.abs(target - current);
        if (delta < minAbsolute) {
            return false;
        }
        if (current <= 0) {
            // No baseline to take a percentage of; the absolute threshold is the whole test.
            return true;
        }
        // Integer comparison rather than floating-point division, so the boundary is exact:
        // delta/current >= minPercent/100  <=>  delta*100 >= current*minPercent.
        return delta * 100L >= current * (long) minPercent;
    }
}
