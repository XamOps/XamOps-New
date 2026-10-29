package com.xammer.cloud.domain.autospotting;

/**
 * Which configuration XamOps maintains on a managed group (design-core.md §2.1, §2.4,
 * {@code spot_group.config_mode}; default {@code MANAGED}). Persisted as its name.
 */
public enum ConfigMode {
    MANAGED,
    FALLBACK
}
