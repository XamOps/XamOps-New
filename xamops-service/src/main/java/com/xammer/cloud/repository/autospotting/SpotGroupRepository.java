package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Finders for {@link SpotGroup} (plan-phase-03.md task 3.2). The guarded native lifecycle, lease and
 * runtime UPDATEs arrive with their owners in Phases 04 and 06; this interface has finders only.
 */
@Repository
public interface SpotGroupRepository extends JpaRepository<SpotGroup, UUID> {

    /** The live row for a key (the partial index {@code ux_spot_group_identity} keeps at most one). */
    Optional<SpotGroup> findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(
            Provider provider, Long cloudAccountId, String groupKey, GroupState state);

    List<SpotGroup> findByProviderAndCloudAccountId(Provider provider, Long cloudAccountId);

    List<SpotGroup> findByCloudAccountId(Long cloudAccountId);

    List<SpotGroup> findByProviderAndState(Provider provider, GroupState state);

    List<SpotGroup> findByProviderAndProviderStableId(Provider provider, String providerStableId);
}
