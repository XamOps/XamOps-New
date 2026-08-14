package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DiskAlertState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository for disk alert state management.
 */
@Repository
public interface DiskAlertStateRepository extends JpaRepository<DiskAlertState, Long> {

    /** Active (unresolved) alert for this volume+type — used for deduplication. */
    Optional<DiskAlertState> findByAccount_IdAndVolumeIdAndAlertTypeAndResolvedAtIsNull(
            Long accountId, String volumeId, String alertType);

    /** All unresolved alerts for an account — used for recovery detection. */
    List<DiskAlertState> findByAccount_IdAndResolvedAtIsNull(Long accountId);

    /** Any alert (resolved or not) for upsert logic. */
    Optional<DiskAlertState> findByAccount_IdAndVolumeIdAndAlertType(
            Long accountId, String volumeId, String alertType);

    /** Clears alert history when the owning account is removed. */
    @Modifying
    @Transactional
    void deleteByAccount_Id(Long accountId);
}
