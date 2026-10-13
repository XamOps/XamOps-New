package com.xammer.cloud.service.automation.workload;

/**
 * Why one HPA's bounds and target are what they are — including why they did <strong>not</strong>
 * move.
 *
 * <p>The horizontal counterpart to {@link ContainerReason}. The same discipline applies: every HPA
 * decision carries these so a "no change" is an explanation rather than a silence, and so the Go
 * reference's bare replica arithmetic ({@code min(2, replicas)}, {@code max(2 × replicas, 3)}, a
 * fixed 80% target with the collected {@code p90Cpu} never used) cannot recur without a named,
 * tested reason behind every number.
 *
 * <p>These are <strong>not</strong> shared gate {@code ReasonCode}s: the contract test pins the gate
 * codes to exactly nine, and these refusals live here instead. The UI renders an unrecognised value
 * as free-form text, so this enum can grow without a frontend change — but a rename or reorder is a
 * breaking change, which is why {@code WorkloadReasonCodeContractTest} pins these as literal strings
 * in order.
 */
public enum HpaReason {

    // ---- change reasons

    /** D1: the HPA is pinned at {@code maxReplicas} with the metric above target, so the ceiling rose. */
    AT_MAX_UNMET_DEMAND,

    /** D2: the ceiling sat far above anything the window ever used, so it was lowered. */
    MAX_ABOVE_OBSERVED_PEAK,

    /**
     * D3a: the vertical draft moved the CPU request on an HPA-managed workload, so the target
     * utilisation was co-adjusted to preserve the effective trigger point {@code request × target}.
     */
    TARGET_COADJUSTED,

    /** D3b: a standalone target retune (behind its own sub-flag), not driven by a request move. */
    TARGET_RETUNED,

    /** D4: no HPA targets this workload but one is recommended — recommend only, never auto-created. */
    HPA_RECOMMENDED_NEW,

    // ---- refusals / holds

    /** The proposed max is within one replica of the current max, so no change clears the band. */
    WITHIN_REPLICA_HYSTERESIS,

    /** A change was applied too recently; the cooldown window has not elapsed. */
    COOLDOWN,

    /** The HPA is owned by Argo CD / Flux / Helm, so XamOps shows the change but never patches it. */
    GITOPS_MANAGED,

    /** A lower would drop {@code minReplicas} below a PDB's {@code minAvailable}, so it is refused. */
    PDB_FLOOR_BINDS,

    /** The formula wanted more than the user-set hard ceiling, so the raise was capped to it. */
    USER_MAX_RESPECTED,

    /** A lower was refused because the look-back history was unreadable: never shrink below need. */
    BELOW_OBSERVED_NEED,

    /** A target co-adjust would have left the 10–90 band, so the vertical CPU freeze stands instead. */
    CLAMP_BOUND,

    /** A mutating native VPA owns this workload's requests, so vertical refuses the whole workload. */
    VPA_MANAGED,

    /** HPA ownership was unreadable (RBAC 403 / unreachable API), so the resource yields. */
    HPA_NOT_READABLE,

    /** VPA ownership was unreadable (RBAC 403 / unreachable API), so the resource yields. */
    VPA_NOT_READABLE
}
