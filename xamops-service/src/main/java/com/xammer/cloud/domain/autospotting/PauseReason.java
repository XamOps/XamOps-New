package com.xammer.cloud.domain.autospotting;

/**
 * Why a group is PAUSED (design-core.md §2.1, §6.4, {@code spot_group.pause_reason}). Persisted as
 * its name.
 */
public enum PauseReason {
    ACCOUNT_SWITCH,
    FREEZE,
    OPERATOR,
    DRIFT
}
