package com.xammer.cloud.service.automation.workload;

/** The outcome of one reconciliation pass, which decides when the next one happens. */
public enum ReconcileOutcome {
    /** Evaluated normally. */
    NORMAL,
    /** The workload was mid-rollout, HPA-volatile or locked. */
    VOLATILE,
    /** The usage source could not be read. */
    METRICS_FAILURE,
    /** The engine or this workload's policy is off. */
    DISABLED
}
