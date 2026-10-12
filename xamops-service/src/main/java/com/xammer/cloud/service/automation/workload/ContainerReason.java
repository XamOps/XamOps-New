package com.xammer.cloud.service.automation.workload;

/**
 * Why one container's numbers are what they are — including why they did <strong>not</strong> move.
 *
 * <p>The harness's central complaint about the Go output was that the numbers arrived with no stated
 * basis. Every container decision carries these, so "no change" is an explanation rather than a
 * silence.
 */
public enum ContainerReason {

    /** A CPU-metric HPA targets this workload, so the CPU request is the HPA's denominator. */
    HPA_CPU_MANAGED,

    /** A memory-metric HPA targets this workload, so the memory request is frozen. */
    HPA_MEMORY_MANAGED,

    /** The computed target fell below the floor, so the floor was recommended instead. */
    FLOOR_CLAMPED,

    /**
     * The target is below the floor and the current request is <em>also</em> below it, so nothing is
     * recommended. Increases are driven only by measured demand, never by the floor's existence.
     */
    BELOW_FLOOR_NO_RAISE,

    /**
     * A limit moved along with its request — in <strong>either</strong> direction.
     *
     * <p>Deliberately direction-neutral. This was {@code LIMIT_RAISED}, which the engine added
     * whenever the new limit differed from the current one, so the flagship floored decrease
     * (1000m/1Gi → 25m/250Mi, a 97.5% <em>reduction</em>) reported a limit <em>raise</em>. The UI
     * renders these codes as text, so that told an operator the opposite of what the recommendation
     * does. The direction is already unambiguous from {@code current} and {@code recommended} in the
     * same payload; the reason only needs to say that the limit was not left behind.
     */
    LIMIT_MOVED,

    /**
     * Usage was pinned at the limit, so the target came from the limit rather than from the
     * percentile.
     *
     * <p>Recorded only when the container is <strong>both</strong> throttled (ratio ≥ 0.2) and
     * measured within 10% of its limit — the condition under which the percentile genuinely is not a
     * measurement of demand. Throttling on its own does not qualify: the engine used to take the
     * limit whenever the ratio cleared 0.2, which turned a container measured using 80m against a
     * 1000m limit into a 1250m request, and — because the limit moves with the request — made each
     * cycle's basis a number the previous cycle had written.
     */
    DEMAND_CENSORED_BY_LIMIT,

    /** Capped to fit the largest schedulable node. */
    NODE_CAPACITY_CAPPED,

    /**
     * The resource could not move without changing the QoS class, so it did not move. On a
     * Guaranteed workload an HPA therefore means no change to that resource at all.
     */
    QOS_PRESERVED_NO_CHANGE,

    /** A change was applied too recently; the cooldown window has not elapsed. */
    COOLDOWN,

    /**
     * The delta did not clear both the relative and the absolute band. This is the reason the
     * measured −32.8% / −26m false positive on the correctly-sized control workload is suppressed.
     */
    WITHIN_HYSTERESIS,

    /** This container has no request for some resource, so its recommendation is advisory only. */
    REQUESTS_UNSET,

    /**
     * OOM kills were observed in the window, so this container's memory is <strong>increase-only</strong>
     * regardless of measured usage.
     *
     * <p>Two effects, both from the same evidence: a memory decrease is refused, and a memory
     * increase outranks the {@link #COOLDOWN}. The second matters because the sequence that produces
     * it is ordinary — a decrease is applied, the container OOM-kills under the new request, and the
     * next cycle lands inside the cooldown window. A direction-blind cooldown would hold the remedy
     * for the rest of the hour while reporting {@code OOM_RISK} beside {@code COOLDOWN} as the reason
     * nothing was proposed.
     */
    OOM_RISK
}
