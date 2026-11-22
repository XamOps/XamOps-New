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

    /**
     * Insert an UNMANAGED row for a group that has none, unless a live row for the same identity
     * already exists ({@code ux_spot_group_identity}). Race-safe: two concurrent first plans for the same
     * group end with exactly one row, and neither transaction fails (phase 23 defect D-23-06). This commits in
     * its own transaction, so an orphan UNMANAGED row left when the surrounding plan transaction later fails
     * is accepted: it is non-billable and the next plan finds and reuses it, so no sweep is needed. Returns
     * rows inserted (0 when the other transaction won); the caller then re-reads the live row.
     */
    // REQUIRES_NEW: the row commits before the caller's plan transaction goes on, so the lifecycle's own
    // REQUIRES_NEW row lock (the move to PLANNED) can see it. Inside the plan transaction the uncommitted row
    // was invisible to that lock, the PLANNED move missed, and the group stayed UNMANAGED (D-23-07).
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "INSERT INTO spot_group"
            + " (id, provider, cloud_account_id, client_id, group_key, provider_stable_id, state, drift_status,"
            + "  config_mode, owned_artifacts, state_changed_at, created_by, created_at, updated_at,"
            + "  lease_generation, version)"
            + " VALUES (:id, :provider, :cloudAccountId, :clientId, :groupKey, :providerStableId, 'UNMANAGED', 'NONE',"
            + "  'MANAGED', CAST('{}' AS jsonb), :now, :createdBy, :now, :now, 0, 0)"
            + " ON CONFLICT (provider, cloud_account_id, group_key) WHERE state <> 'GONE' DO NOTHING",
            nativeQuery = true)
    int insertUnmanagedIfAbsent(@org.springframework.data.repository.query.Param("id") UUID id,
                                @org.springframework.data.repository.query.Param("provider") String provider,
                                @org.springframework.data.repository.query.Param("cloudAccountId") Long cloudAccountId,
                                @org.springframework.data.repository.query.Param("clientId") Long clientId,
                                @org.springframework.data.repository.query.Param("groupKey") String groupKey,
                                @org.springframework.data.repository.query.Param("providerStableId") String providerStableId,
                                @org.springframework.data.repository.query.Param("createdBy") String createdBy,
                                @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
