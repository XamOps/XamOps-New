package com.xammer.cloud.domain.autospotting;

/**
 * Status of a Spot operation step (design-core.md §4.5, {@code spot_operation_step.status}).
 * Persisted as its name.
 */
public enum StepStatus {
    INTENT_RECORDED,
    DONE,
    IN_PROGRESS,
    FAILED,
    SKIPPED
}
