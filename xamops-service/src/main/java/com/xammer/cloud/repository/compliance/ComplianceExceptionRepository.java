package com.xammer.cloud.repository.compliance;

import com.xammer.cloud.domain.compliance.ComplianceException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ComplianceExceptionRepository extends JpaRepository<ComplianceException, Long> {

    List<ComplianceException> findByCloudAccountIdOrderByCreatedAtDesc(String cloudAccountId);

    /**
     * Waivers that should actually suppress a failing control right now. Expiry is
     * enforced in the query rather than by a nightly job, so a lapsed waiver stops
     * hiding its control the moment it lapses even if no sweeper has run.
     */
    @Query("SELECT e FROM ComplianceException e "
            + "WHERE e.cloudAccountId = :accountId AND e.status = 'APPROVED' "
            + "AND e.expiresAt > :now")
    List<ComplianceException> findActive(@Param("accountId") String accountId,
            @Param("now") Instant now);

    /** Approved waivers already past their expiry — the "needs re-review" queue. */
    @Query("SELECT e FROM ComplianceException e "
            + "WHERE e.cloudAccountId = :accountId AND e.status = 'APPROVED' "
            + "AND e.expiresAt <= :now")
    List<ComplianceException> findLapsed(@Param("accountId") String accountId,
            @Param("now") Instant now);
}
