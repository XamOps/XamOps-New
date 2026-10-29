package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.SpotOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Finders for {@link SpotOperation} (plan-phase-03.md task 3.2). The runner's guarded native writes
 * arrive in Phases 06 and 07.
 */
@Repository
public interface SpotOperationRepository extends JpaRepository<SpotOperation, UUID> {

    List<SpotOperation> findByGroupId(UUID groupId);

    Optional<SpotOperation> findByPlanId(UUID planId);

    Optional<SpotOperation> findByCloudAccountIdAndIdempotencyKey(Long cloudAccountId, String idempotencyKey);
}
