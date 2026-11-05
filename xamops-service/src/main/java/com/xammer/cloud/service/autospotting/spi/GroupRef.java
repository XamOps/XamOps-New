package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.autospotting.Provider;

/**
 * A cloud-neutral reference to a group before (or without) a {@code spot_group} row
 * (design-core.md §9.1). Identifies the group by its provider, the owning CloudAccount, and the
 * provider {@code groupKey} (AWS: {@code region/asgName}; GCP: {@code project/location/migName}),
 * with the optional provider-stable id when known (ASG ARN, MIG numeric id).
 */
public record GroupRef(
        Provider provider,
        Long cloudAccountId,
        String groupKey,
        String providerStableId) {

    public GroupRef {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        if (groupKey == null || groupKey.isBlank()) {
            throw new IllegalArgumentException("groupKey is required");
        }
    }
}
