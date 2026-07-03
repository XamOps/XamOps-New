package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindThreatDetection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UpwindThreatDetectionRepository extends JpaRepository<UpwindThreatDetection, Long> {

    Optional<UpwindThreatDetection> findByUpwindId(String upwindId);

    Page<UpwindThreatDetection> findByCloudAccountIdInOrderByLastSeenTimeDesc(
            List<String> accountIds, Pageable pageable);

    Page<UpwindThreatDetection> findByCloudAccountIdInAndSeverityOrderByLastSeenTimeDesc(
            List<String> accountIds, String severity, Pageable pageable);

    @Query("SELECT d.severity, COUNT(d) FROM UpwindThreatDetection d " +
           "WHERE d.cloudAccountId IN :accountIds AND d.status = 'OPEN' GROUP BY d.severity")
    List<Object[]> countOpenBySeverity(@Param("accountIds") List<String> accountIds);
}
