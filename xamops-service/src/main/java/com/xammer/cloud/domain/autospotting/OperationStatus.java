package com.xammer.cloud.domain.autospotting;

/**
 * Status of a Spot operation (design-core.md §4.5, {@code spot_operation.status}). Persisted as its
 * name.
 */
public enum OperationStatus {
    QUEUED,
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
