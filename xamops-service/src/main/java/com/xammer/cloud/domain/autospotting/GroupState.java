package com.xammer.cloud.domain.autospotting;

/**
 * Lifecycle state of a managed group (design-core.md §2.1, {@code spot_group.state}). FR-SPN-02's
 * set plus the bookkeeping state {@code UNMANAGED}. Persisted as its name.
 */
public enum GroupState {
    UNMANAGED,
    PLANNED,
    APPLYING,
    ACTIVE,
    DEGRADED,
    FALLBACK,
    PAUSED,
    REVERTING,
    REVERTED,
    FAILED,
    LEGACY_UNCONFIRMED,
    GONE
}
