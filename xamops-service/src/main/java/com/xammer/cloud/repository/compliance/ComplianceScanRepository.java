package com.xammer.cloud.repository.compliance;

import com.xammer.cloud.domain.compliance.ComplianceScan;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ComplianceScanRepository extends JpaRepository<ComplianceScan, Long> {

    /** The scan the report is built from. */
    Optional<ComplianceScan> findFirstByCloudAccountIdAndStatusOrderByStartedAtDesc(
            String cloudAccountId, String status);

    /**
     * Trend series. Only COMPLETED scans, because plotting a failed run as a zero would
     * read as "everything passed" when in fact nothing was measured.
     */
    @Query("SELECT s FROM ComplianceScan s "
            + "WHERE s.cloudAccountId = :accountId AND s.status = 'COMPLETED' "
            + "AND s.startedAt >= :since ORDER BY s.startedAt ASC")
    List<ComplianceScan> findCompletedSince(@Param("accountId") String accountId,
            @Param("since") Instant since);

    /** Scan history for the audit trail, newest first, including FAILED runs. */
    List<ComplianceScan> findByCloudAccountIdOrderByStartedAtDesc(String cloudAccountId,
            Pageable pageable);

    long countByCloudAccountIdAndStatus(String cloudAccountId, String status);
}
