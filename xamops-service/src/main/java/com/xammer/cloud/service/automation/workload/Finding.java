package com.xammer.cloud.service.automation.workload;

/**
 * A workload-level observation. <strong>A finding never implies a recommendation</strong> — it is
 * something true about the workload, which may or may not have a number attached.
 *
 * <p>This separation is the answer to two measured failures: an idle workload was given a {@code 0m}
 * CPU request (the honest output is "this is idle", not a smaller number), and a container with no
 * requests at all was given a fabricated {@code 0m / 1Mi} baseline and then "fixed" to
 * {@code 1m / 1Mi} with 0% savings.
 */
public enum Finding {

    /**
     * Some container has no CPU or no memory request (BestEffort, or a partially-set Burstable).
     * The engine emits an <strong>advisory</strong> recommendation from measured usage with
     * {@code autoApplyEligible: false}, and never a fabricated current value.
     */
    REQUESTS_UNSET,

    /**
     * Effectively no load: the CPU percentile is at or below {@code max(1m, 1% of the request)} and
     * the memory percentile is at or below its floor. Emits <strong>no request recommendation</strong>
     * — the honest remedy is scale-to-zero or deletion, which this engine does not do.
     */
    IDLE,

    /**
     * CFS throttling ratio ≥ 0.2: this container is being held back by its CPU limit often enough to
     * report.
     *
     * <p>An observation, <strong>not</strong> a branch. Whether the limit then stands in for demand
     * is a separate, stricter test — the measured percentile must also sit within 10% of the limit,
     * i.e. usage must actually <em>be</em> the limit. A container that bursts briefly throttles
     * without its percentile being censored, and for that one the ordinary usage-derived target
     * applies; see {@code ContainerReason.DEMAND_CENSORED_BY_LIMIT}.
     */
    THROTTLED,

    /** OOM kills occurred in the window. Forces that container's memory direction to increase-only. */
    OOM_RISK,

    /** A resource-metric HPA targets this workload, so that resource's request is frozen. */
    HPA_COEXISTENCE,

    /** A recommendation was capped to fit the largest schedulable node. */
    NODE_CAPACITY_CAPPED
}
