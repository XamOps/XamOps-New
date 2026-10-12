package com.xammer.cloud.service.automation.workload;

/**
 * Why a workload was not evaluated at all. The first matching gate wins, and the decision then
 * carries <strong>no</strong> container recommendations — not an empty one, not a zero.
 *
 * <p>Free-form strings in the API, rendered as text by the UI; these are not shared gate codes.
 */
public enum SkipReason {

    /**
     * The namespace is one XamOps will not rightsize: {@code kube-system}, {@code monitoring},
     * {@code amazon-cloudwatch}, {@code vpa}, {@code xamops-system}.
     *
     * <p>A code constant, not policy: a per-workload row cannot unprotect {@code kube-system}.
     */
    PROTECTED_NAMESPACE,

    /**
     * One replica, so any disruption is a full outage of that workload. One of the two guards the Go
     * rightsizer did honour — kept, and moved into the engine so the future horizontal half reads
     * the same rule instead of its own (the Go HPA manager did not honour it).
     */
    SINGLE_REPLICA,

    /** {@code generation != status.observedGeneration}: a rollout is in flight, so usage is unstable. */
    ROLLOUT_IN_PROGRESS,

    /** The workload carries {@code xamops.io/lock: active}. */
    LOCKED,

    /** The HPA scaled recently, so the current usage is a transient. */
    HPA_VOLATILE,

    /**
     * Too little of the policy window was observed. The measured harness run had 4h52m of history
     * against a 24h policy; recommending from that would misstate the basis of the number.
     */
    INSUFFICIENT_HISTORY,

    /** The workload's policy mode is {@code OFF}. */
    POLICY_OFF
}
