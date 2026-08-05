package com.xammer.cloud.repository.compliance;

import com.xammer.cloud.domain.compliance.ComplianceControlResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComplianceControlResultRepository extends JpaRepository<ComplianceControlResult, Long> {

    List<ComplianceControlResult> findByScanId(Long scanId);

    List<ComplianceControlResult> findByScanIdAndStatus(Long scanId, String status);

    /**
     * Per-check history, used to tell "newly broken since the last scan" from
     * "has been failing for a month". Ordered oldest-first so callers can diff.
     */
    @Query("SELECT r FROM ComplianceControlResult r "
            + "WHERE r.cloudAccountId = :accountId AND r.checkId = :checkId "
            + "ORDER BY r.scanId ASC")
    List<ComplianceControlResult> findHistory(@Param("accountId") String accountId,
            @Param("checkId") String checkId);

    /**
     * Failing-control counts per scan per severity, for the whole trend window in one
     * round trip. Returns rows of {scanId, severity, count} — a projection rather than
     * entities because loading every result row for 30 scans to count them would be
     * tens of thousands of objects for four numbers per point.
     */
    @Query("SELECT r.scanId, r.severity, COUNT(r) FROM ComplianceControlResult r "
            + "WHERE r.scanId IN :scanIds AND r.status = 'FAIL' "
            + "GROUP BY r.scanId, r.severity")
    List<Object[]> countFailedBySeverity(@Param("scanIds") List<Long> scanIds);

    /**
     * {scanId, checkId} pairs for every failing control across a set of scans. Powers
     * both the "newly failing since last scan" diff and the consecutive-failure streak,
     * in one query rather than one per control.
     */
    @Query("SELECT r.scanId, r.checkId FROM ComplianceControlResult r "
            + "WHERE r.scanId IN :scanIds AND r.status = 'FAIL'")
    List<Object[]> findFailingByScans(@Param("scanIds") List<Long> scanIds);

    /** Check ids that passed in a given scan — the other half of the scan-to-scan diff. */
    @Query("SELECT r.checkId FROM ComplianceControlResult r "
            + "WHERE r.scanId = :scanId AND r.status = 'PASS'")
    List<String> findPassingCheckIds(@Param("scanId") Long scanId);

    void deleteByScanId(Long scanId);
}
