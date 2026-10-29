package com.xammer.cloud.domain.autospotting;

/**
 * Action class of a Spot operation (design-core.md §4.5, §6.2, {@code spot_operation.action_class}).
 * Persisted as its name.
 */
public enum ActionClass {
    PROGRESS,
    SAFETY
}
