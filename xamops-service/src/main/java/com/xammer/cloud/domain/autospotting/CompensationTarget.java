package com.xammer.cloud.domain.autospotting;

/**
 * What a compensating operation restores (design-core.md §4.5, §2.2.1,
 * {@code spot_operation.compensation_target}). Persisted as its name.
 */
public enum CompensationTarget {
    SNAPSHOT,
    PRIOR_CONFIG,
    FALLBACK,
    NONE
}
