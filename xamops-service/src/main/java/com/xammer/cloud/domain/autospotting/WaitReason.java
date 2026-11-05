package com.xammer.cloud.domain.autospotting;

/**
 * Why a Spot operation is WAITING (design-core.md §4.5, {@code spot_operation.wait_reason}).
 * Persisted as its name.
 */
public enum WaitReason {
    CLOUD_PROGRESS,
    SWITCH,
    FOREIGN_CHANGE,
    BACKOFF,
    STUCK
}
