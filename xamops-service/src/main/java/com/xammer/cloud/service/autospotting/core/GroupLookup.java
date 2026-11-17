package com.xammer.cloud.service.autospotting.core;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-only lookups on the spine for provider code. The provider packages may not depend on the
 * spine repositories directly (the architecture test confines them to core services and controllers),
 * so a provider asks this class instead. It makes no write and no cloud call.
 */
@Service
public class GroupLookup {

    private final SpotGroupRepository groups;

    public GroupLookup(SpotGroupRepository groups) {
        this.groups = groups;
    }

    /** The live (non-GONE) row for a group key, if any. */
    public Optional<SpotGroup> live(Provider provider, Long cloudAccountId, String groupKey) {
        return groups.findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(provider, cloudAccountId, groupKey,
                GroupState.GONE);
    }

    /** Every row of an account for a provider (discovery uses it to apply rule 1). */
    public List<SpotGroup> forAccount(Provider provider, Long cloudAccountId) {
        return groups.findByProviderAndCloudAccountId(provider, cloudAccountId);
    }

    /** True when EBS automation holds the group's lease right now (OTHER_FEATURE_IN_FLIGHT, core §3.3). */
    public boolean ebsHoldsLease(Provider provider, Long cloudAccountId, String groupKey) {
        return live(provider, cloudAccountId, groupKey)
                .filter(g -> g.getLeaseHolder() != null && g.getLeaseHolder().contains(":ebs:"))
                .filter(g -> g.getLeaseExpiresAt() != null && g.getLeaseExpiresAt().isAfter(Instant.now()))
                .isPresent();
    }
}
