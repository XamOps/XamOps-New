package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SreInvestigation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    Optional<SreInvestigation> findByIncidentId(String incidentId);

    /**
     * Detaches investigations from an account being deleted. The FK is nullable, so
     * the incident history survives the account it was raised against — dropping the
     * link is enough to clear the constraint.
     */
    @Modifying
    @Transactional
    @Query("UPDATE SreInvestigation i SET i.cloudAccountId = NULL WHERE i.cloudAccountId = :accountId")
    int detachCloudAccount(@Param("accountId") Long accountId);
}
