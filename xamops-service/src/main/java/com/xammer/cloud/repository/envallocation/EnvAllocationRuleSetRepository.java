package com.xammer.cloud.repository.envallocation;

import com.xammer.cloud.domain.envallocation.EnvAllocationRuleSet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EnvAllocationRuleSetRepository extends JpaRepository<EnvAllocationRuleSet, Long> {

    Optional<EnvAllocationRuleSet> findByClientId(Long clientId);

    /** Serialises two publishes for one client, so they cannot both take the same version number. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM EnvAllocationRuleSet s WHERE s.clientId = :clientId")
    Optional<EnvAllocationRuleSet> findByClientIdForUpdate(@Param("clientId") Long clientId);
}
