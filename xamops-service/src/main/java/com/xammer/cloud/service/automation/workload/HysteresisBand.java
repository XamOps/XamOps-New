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
     * The same band with its <strong>absolute</strong> threshold capped at the largest move the
     * engine can actually express in this direction, at this container's scale.
     *
     * <h2>The invariant</h2>
     *
     * <strong>An absolute threshold may filter a move. It may never make a direction
     * unreachable.</strong> A flat threshold does exactly that below a certain request size, and the
     * flat numbers were calibrated on one container shape (80m–1000m) where the condition never
     * binds.
     *
     * <p>The reachability bound differs by direction, and both are properties of the engine's own
     * arithmetic rather than of the workload:
     *
     * <ul>
     *   <li><strong>Increase → {@code request × headroomPct/100}.</strong> On a Guaranteed-shaped
     *       container the limit <em>is</em> the request, and CFS caps measured CPU usage at the
     *       limit, so the demand basis cannot exceed the request and the largest target expressible
     *       is {@code request × (1 + headroom)}. Headroom alone generates the whole delta. With a
     *       25m floor, a 25m absolute band and 25% headroom, that cleared only from a 100m request
     *       upwards — and the engine's own flagship recommendation <em>writes</em> containers to
     *       25m. A container floored at 25m and later throttled at ratio 1.0 was reported
     *       {@code THROTTLED} with {@code DEMAND_CENSORED_BY_LIMIT} and handed no recommendation, at
     *       any load, permanently: the engine could cut a workload by 97.5% and then had no way to
     *       give any of it back. Memory escaped the trap only because its increase band (32Mi) is
     *       small relative to its floor (250Mi).</li>
     *   <li><strong>Decrease → {@code request − floor}.</strong> Floors clamp every target, so no
     *       decrease larger than this is expressible at all. A 120m container measured using 10m
     *       floors to 25m — a 95m move that a flat 100m band suppressed, so small workloads were
     *       systematically ignored in <em>both</em> directions.</li>
     * </ul>
     *
     * <h2>What it does not do</h2>
     *
     * It is a cap, never a raise: {@code min(minAbsolute, bound)}, so the band is unchanged wherever
     * it was already satisfiable, and no suppression that held before is loosened at any scale where
     * the flat number could be met. The <strong>relative</strong> half is untouched and keeps doing
     * the filtering at small scale — a floor-clamped decrease always has
     * {@code delta == request − floor}, so it always clears the capped absolute half and is then
     * judged purely on percentage. That is what still suppresses the measured false positive on the
     * control workload: 80m with a 25m floor gives a 55m cap, and the measured drift was 26m.
     *
     * @param largestExpressibleMove the reachability bound for this direction, in canonical units;
     *                               negative values are treated as zero
     * @return this band, or a copy with {@code minAbsolute} lowered to the bound
     */
    public HysteresisBand cappedTo(long largestExpressibleMove) {
        long bound = Math.max(0, largestExpressibleMove);
        return bound >= minAbsolute ? this : new HysteresisBand(minPercent, bound);
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
