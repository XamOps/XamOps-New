package com.xammer.cloud.domain.autospotting;

/**
 * Cloud provider of a managed group (design-core.md §2.4, {@code spot_group.provider}). Persisted as
 * its name via {@code @Enumerated(EnumType.STRING)}.
 */
public enum Provider {
    AWS,
    GCP
}
