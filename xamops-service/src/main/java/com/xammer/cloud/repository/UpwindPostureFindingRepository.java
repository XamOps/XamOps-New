package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindPostureFinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UpwindPostureFindingRepository extends JpaRepository<UpwindPostureFinding, Long> {

    Optional<UpwindPostureFinding> findByUpwindId(String upwindId);

    Page<UpwindPostureFinding> findByCloudAccountIdInOrderByLastSeenTimeDesc(
            List<String> accountIds, Pageable pageable);

    Page<UpwindPostureFinding> findByCloudAccountIdInAndSeverityOrderByLastSeenTimeDesc(
            List<String> accountIds, String severity, Pageable pageable);

    @Query("SELECT p.severity, COUNT(p) FROM UpwindPostureFinding p " +
           "WHERE p.cloudAccountId IN :accountIds AND p.status = 'FAIL' GROUP BY p.severity")
    List<Object[]> countFailBySeverity(@Param("accountIds") List<String> accountIds);

    @Query("SELECT p.frameworkTitle, p.status, COUNT(p) FROM UpwindPostureFinding p " +
           "WHERE p.cloudAccountId IN :accountIds AND p.frameworkTitle IS NOT NULL " +
           "GROUP BY p.frameworkTitle, p.status")
    List<Object[]> countByFrameworkAndStatus(@Param("accountIds") List<String> accountIds);

    @Modifying
    @Query("UPDATE UpwindPostureFinding p SET p.status = 'RESOLVED' " +
           "WHERE p.syncedAt < :runStart AND p.status <> 'RESOLVED'")
    int markResolvedNotSyncedSince(@Param("runStart") Instant runStart);
}
