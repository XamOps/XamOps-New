package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DbHealthAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DbHealthAlertRepository extends JpaRepository<DbHealthAlert, Long> {

    Optional<DbHealthAlert> findFirstByAccountIdAndInstanceIdAndAlertTypeAndIsActiveTrue(
        Long accountId, String instanceId, String alertType);

    @Query("SELECT a FROM DbHealthAlert a WHERE a.accountId = :accountId " +
           "AND (a.isActive = true OR a.resolvedAt > :since) " +
           "ORDER BY CASE WHEN a.severity = 'CRITICAL' THEN 0 ELSE 1 END, a.triggeredAt DESC")
    List<DbHealthAlert> findForFeed(@Param("accountId") Long accountId,
                                    @Param("since") LocalDateTime since);

    long countByAccountIdAndIsActiveTrueAndSeverity(Long accountId, String severity);
}
