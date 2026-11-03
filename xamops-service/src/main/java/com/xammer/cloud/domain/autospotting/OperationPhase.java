package com.xammer.cloud.domain.autospotting;

/**
 * Phase of a Spot operation (design-core.md §4.5, §2.2.1, {@code spot_operation.phase}; default
 * {@code FORWARD}). Persisted as its name.
 */
public enum OperationPhase {
    FORWARD,
    COMPENSATING
}
