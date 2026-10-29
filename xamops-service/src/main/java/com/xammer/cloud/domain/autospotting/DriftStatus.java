package com.xammer.cloud.domain.autospotting;

/**
 * Drift classification of a group (design-core.md §2.4, {@code spot_group.drift_status}; default
 * {@code NONE}). Persisted as its name.
 */
public enum DriftStatus {
    NONE,
    MINOR,
    MATERIAL
}
