package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SreInvestigation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SreInvestigationRepository extends JpaRepository<SreInvestigation, Long> {

    List<SreInvestigation> findByClientIdOrderByCreatedAtDesc(Long clientId);

    List<SreInvestigation> findByClientIdAndStatus(Long clientId, SreInvestigation.InvestigationStatus status);

    List<SreInvestigation> findByStatus(SreInvestigation.InvestigationStatus status);

    long countByClientId(Long clientId);

    long countByClientIdAndStatus(Long clientId, SreInvestigation.InvestigationStatus status);

    @Query("SELECT COUNT(i) FROM SreInvestigation i WHERE i.clientId = :clientId AND i.status = :status AND i.completedAt > :since")
    long countByClientIdAndStatusAndCompletedAtAfter(@Param("clientId") Long clientId,
                                                      @Param("status") SreInvestigation.InvestigationStatus status,
                                                      @Param("since") Instant since);

    @Query("SELECT COUNT(i) FROM SreInvestigation i WHERE i.resourceId = :resourceId AND i.createdAt > :since")
    int countRecentByResourceId(@Param("resourceId") String resourceId, @Param("since") Instant since);
}
