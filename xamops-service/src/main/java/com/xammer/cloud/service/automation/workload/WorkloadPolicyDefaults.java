package com.xammer.cloud.service.automation.workload;

import java.time.Duration;
import java.util.Set;

/**
 * Every default the engine uses when no policy row exists, with the reasoning for each number.
 *
 * <p>Defaults live in exactly two places: these constants (so the no-row case is safe) and the SQL
 * column defaults on {@code vertical_workload_policy} (so a row written by any path is safe). They
 * must agree, and {@code WorkloadPolicyServiceTest} asserts they do.
 *
 * <p>The protected-namespace set and the volatility window are <strong>constants, not
 * properties</strong>: they are safety rules reviewed with the code, and they keep the new property
 * surface to exactly the two mandated flags.
 */
public final class WorkloadPolicyDefaults {

    // ---------------------------------------------------------------- measurement

    /** p95. The Go engine used CPU p80, which the harness showed understates a throttled workload. */
    public static final int PERCENTILE = 95;

    /** 24h. Long enough to contain a daily cycle. */
    public static final int WINDOW_HOURS = 24;

    /** Headroom above the measured percentile, in percent. */
    public static final int HEADROOM_PCT = 25;

    /**
     * Below this fraction of the expected samples, the window is not a basis for a decision. The
     * measured harness run had 4h52m of a 24h window.
     */
    public static final double MIN_SAMPLE_COVERAGE = 0.5;

    /** A window shorter than this cannot support a percentile, whatever its coverage. */
    public static final Duration MIN_WINDOW = Duration.ofHours(2);

    // ---------------------------------------------------------------- floors (bug 4)

    /**
     * 25m CPU / 250Mi memory: upstream VPA's {@code --pod-min-cpu-millicores} and
     * {@code --pod-min-memory-mb} defaults, and — more to the point — the values VPA was
     * <em>measured</em> stopping at on five of the nine reference workloads. That is both the
     * justification for the numbers and the reason the Go engine's {@code 1m / 1Mi} output was
     * identifiably wrong rather than merely aggressive.
     *
     * <p>Floors clamp <strong>decreases only</strong>. A workload already below the floor is never
     * raised to reach it: increases are driven by measured demand, never by the floor's existence.
     */
    public static final int CPU_FLOOR_MILLIS = 25;

    /** 250Mi in bytes. */
    public static final long MEM_FLOOR_BYTES = 250L * 1024 * 1024;

    // ---------------------------------------------------------------- hysteresis (§4.4)

    /**
     * CPU increase: ≥ 15% and ≥ 25m.
     *
     * <p>15% because the harness measured agent-vs-{@code kubectl top} CPU jitter up to ~17% on
     * individual nodes, so a 10% band sits inside the measurement noise floor. 25m is one
     * floor-unit — the smallest CPU quantity this engine will express at all. An increase relieves
     * measured pressure rather than speculatively reclaiming, so the absolute band is one unit
     * instead of a node fraction. This asymmetry is what lets the throttled encoder's +53m increase
     * through while the control workload's −26m decrease is suppressed: similar magnitude, opposite
     * risk.
     */
    public static final HysteresisBand CPU_INCREASE = new HysteresisBand(15, 25);

    /**
     * CPU decrease: ≥ 30% and ≥ 100m.
     *
     * <p>100m is ~4× the measured 26m false positive, so the control case sits well inside the band
     * rather than on its edge. It is also the smallest decrease that can pay for itself: on the
     * measured node shape (t3.medium, 1930m allocatable) 100m is ~5% of a node, so freeing less than
     * that per container cannot aggregate into a drained node without hundreds of containers. Below
     * 100m a recommendation buys churn, not capacity. 30% because a decrease is the direction that
     * causes outages, and because the kubelet was measured applying even a {@code 1Mi} memory
     * request in place without complaint — there is no platform backstop below this number.
     */
    public static final HysteresisBand CPU_DECREASE = new HysteresisBand(30, 100);

    /**
     * Memory increase: ≥ 15% and ≥ 32Mi. 32Mi is the smallest memory step worth writing and sits
     * below the control workload's 64Mi request, so the band cannot be cleared by rounding noise.
     */
    public static final HysteresisBand MEM_INCREASE = new HysteresisBand(15, 32L * 1024 * 1024);

    /**
     * Memory decrease: ≥ 30% and ≥ 128Mi — <strong>4× the increase band</strong>.
     *
     * <p>A CPU shortfall throttles; a memory shortfall OOM-kills immediately and irreversibly. 128Mi
     * is about half the 250Mi floor and ~4% of the measured 3294Mi node allocatable.
     */
    public static final HysteresisBand MEM_DECREASE = new HysteresisBand(30, 128L * 1024 * 1024);

    // ---------------------------------------------------------------- guards

    /** Minutes after an apply during which nothing new is recommended for that workload. */
    public static final int COOLDOWN_MINUTES = 60;

    /** A limit is never raised beyond this multiple of its current value. */
    public static final double MAX_LIMIT_INCREASE_FACTOR = 4.0;

    /** Single-replica workloads are skipped by default: any disruption is a full outage. */
    public static final boolean PROTECT_SINGLE_REPLICA = true;

    /** Fraction of the largest schedulable node a pod's total requests may occupy. */
    public static final double NODE_CAPACITY_FRACTION = 0.9;

    /** An HPA that scaled within this window makes current usage a transient. */
    public static final Duration HPA_VOLATILITY_WINDOW = Duration.ofMinutes(5);

    /** CFS throttling at or above this ratio is worth reporting as a {@code THROTTLED} finding. */
    public static final double THROTTLE_RATIO_THRESHOLD = 0.2;

    /**
     * How close the measured CPU percentile has to sit to the container's own limit before that
     * limit may be used as the demand basis: <strong>within 10% of it</strong>.
     *
     * <p>Throttling alone is not evidence that usage is censored. A container throttled 25% of the
     * time while its p95 sits at 8% of its limit is bursting briefly, not running pinned at the
     * ceiling — and deriving its request from the limit would have produced a 12.5× increase for a
     * container measured using 80m, justified with {@code DEMAND_CENSORED_BY_LIMIT}. The premise of
     * that rule ("measured usage <em>is</em> the limit, so it is not a measurement of demand") holds
     * at the measured throttle ratio of 1.0, where usage sat exactly on the limit; this constant is
     * what makes the code apply the rule only where its premise is true.
     *
     * <p>It is also what bounds the series under repetition. The limit moves with the request, so an
     * ungated limit-derived basis makes the next cycle's input a number this engine wrote: 10m →
     * 63m → 250m → 993m, each step clearing the band by hundreds of percent and terminating only at
     * the node-capacity cap. With the gate, the basis can exceed the measured percentile by at most
     * {@code 1/0.9} ≈ 11%, so every increase stays anchored to measurement — the same property that
     * makes the decrease path converge.
     */
    public static final double CENSORED_USAGE_FRACTION_OF_LIMIT = 0.9;

    /** CPU usage at or below {@code max(1m, this fraction of the request)} counts as idle. */
    public static final double IDLE_CPU_FRACTION_OF_REQUEST = 0.01;

    /**
     * Namespaces XamOps will not rightsize, whatever a policy row says.
     *
     * <p>A constant rather than a property, and not per-workload-overridable: a row written by any
     * path cannot unprotect {@code kube-system}.
     */
    public static final Set<String> PROTECTED_NAMESPACES =
            Set.of("kube-system", "monitoring", "amazon-cloudwatch", "vpa", "xamops-system");

    /** The annotation that suspends automation for one workload. */
    public static final String LOCK_ANNOTATION = "xamops.io/lock";

    /** The annotation value that means locked. */
    public static final String LOCK_ANNOTATION_ACTIVE = "active";

    private WorkloadPolicyDefaults() {
    }

    /** The policy that applies when a workload has no row. */
    public static WorkloadPolicy policy() {
        return new WorkloadPolicy(
                WorkloadMode.OFF, HEADROOM_PCT, PERCENTILE, WINDOW_HOURS,
                CPU_FLOOR_MILLIS, MEM_FLOOR_BYTES,
                CPU_INCREASE, CPU_DECREASE, MEM_INCREASE, MEM_DECREASE,
                ApplyMethod.IN_PLACE, COOLDOWN_MINUTES, MAX_LIMIT_INCREASE_FACTOR,
                PROTECT_SINGLE_REPLICA, NODE_CAPACITY_FRACTION, null,
                WorkloadPolicy.Source.DEFAULTS);
    }

    /** The defaults with a mode applied — the shape the engine's tests and the read path use. */
    public static WorkloadPolicy policy(WorkloadMode mode) {
        return policy().withMode(mode);
    }
}
