package com.xammer.cloud.domain.autospotting;

/**
 * Kind of a Spot operation (design-core.md §4.5, {@code spot_operation.kind}). Persisted as its
 * name.
 */
public enum OperationKind {
    ENABLE,
    ADOPT,
    CONFIG_APPLY,
    PROFILE_CHANGE,
    REVERT,
    FALLBACK_ENTER,
    FALLBACK_EXIT,
    ACCEPT_BASELINE,
    MIG_POLICY_UPDATE
}
