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
     * The same band with its <strong>absolute</strong> threshold waived, leaving the relative half as
     * the whole test.
     *
     * <h2>Why this exists</h2>
     *
     * The absolute threshold is a churn guard: it suppresses a move that is too small to pay for the
     * write, however large it looks in percentage terms. That reasoning holds for a target derived
     * from an uncensored measurement, and it fails for one derived from a container <em>pinned at its
     * own limit</em>.
     *
     * <p>The CPU floor (25m) and the absolute CPU-increase band (25m) are the same size, and headroom
     * is 25%, so on a Guaranteed-shaped container — where the limit <em>is</em> the request, and CFS
     * caps measured usage at that limit — the largest expressible target is {@code request × 1.25}.
     * The delta offered to this band is therefore at most 25% of the request, which clears 25m only
     * from a 100m request upwards. Below that the engine could cut a container to its floor and then
     * never raise it again at any load, while reporting {@code THROTTLED} and
     * {@code DEMAND_CENSORED_BY_LIMIT} — the recovery path for its own most aggressive
     * recommendation. Memory escapes the trap because its increase band (32Mi) is small relative to
     * its floor (250Mi); CPU does not.
     *
     * <p>So where usage is <em>measurably</em> censored by the limit, the move clears on relative
     * grounds alone. A percentile sitting on the ceiling is direct evidence of unmet demand rather
     * than a speculative reclaim, and evidence does not need a churn guard. The relative half still
     * applies, so the step must still be worth 15% of the request, and the basis is still bounded by
     * measurement — see {@link WorkloadPolicyDefaults#CENSORED_USAGE_FRACTION_OF_LIMIT}.
     *
     * @return this band with {@code minAbsolute} dropped to zero
     */
    public HysteresisBand relativeOnly() {
        return minAbsolute == 0 ? this : new HysteresisBand(minPercent, 0);
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
