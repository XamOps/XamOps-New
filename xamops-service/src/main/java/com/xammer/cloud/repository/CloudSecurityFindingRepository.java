package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudSecurityFinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes for live security findings.
 *
 * <p>
 * Every read is scoped by {@code cloudAccountId} — the {@code cloud_account} row, meaning one
 * tenant's connection — and never by {@code awsAccountId}. The distinction is load-bearing
 * for the same reason it is in {@code AwsCloudResourceRepository}: a single AWS account can
 * own several {@code cloud_account} rows, and on a findings table specifically, scoping by
 * the 12-digit id would show one tenant another tenant's vulnerabilities.
 */
@Repository
public interface CloudSecurityFindingRepository extends JpaRepository<CloudSecurityFinding, Long> {

    Optional<CloudSecurityFinding> findByCloudAccountIdAndFindingId(Long cloudAccountId, String findingId);

    // ── Page reads ──────────────────────────────────────────────────────────

    /**
     * Active findings, most severe first then newest.
     *
     * <p>
     * Ordered by a {@code CASE} over severity rather than alphabetically: sorting the
     * strings would put CRITICAL after both HIGH and LOW, which is exactly wrong for the
     * first screen a user looks at.
     */
    @Query("SELECT f FROM CloudSecurityFinding f "
            + "WHERE f.cloudAccountId = :cloudAccountId AND f.status = 'ACTIVE' "
            + "ORDER BY CASE f.severity "
            + "  WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 "
            + "  WHEN 'LOW' THEN 3 ELSE 4 END, f.lastSeenAt DESC")
    List<CloudSecurityFinding> findActive(@Param("cloudAccountId") Long cloudAccountId);

    @Query("SELECT f FROM CloudSecurityFinding f "
            + "WHERE f.cloudAccountId = :cloudAccountId AND f.status = 'ACTIVE' "
            + "ORDER BY CASE f.severity "
            + "  WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 "
            + "  WHEN 'LOW' THEN 3 ELSE 4 END, f.lastSeenAt DESC")
    Page<CloudSecurityFinding> findActive(@Param("cloudAccountId") Long cloudAccountId, Pageable pageable);

    @Query("SELECT f FROM CloudSecurityFinding f "
            + "WHERE f.cloudAccountId = :cloudAccountId AND f.status = 'ACTIVE' "
            + "AND f.severity IN :severities "
            + "ORDER BY f.lastSeenAt DESC")
    List<CloudSecurityFinding> findActiveBySeverityIn(@Param("cloudAccountId") Long cloudAccountId,
            @Param("severities") List<String> severities);

    /** Counts per severity without materialising the findings. Backs the summary strip. */
    @Query("SELECT f.severity, COUNT(f) FROM CloudSecurityFinding f "
            + "WHERE f.cloudAccountId = :cloudAccountId AND f.status = 'ACTIVE' "
            + "GROUP BY f.severity")
    List<Object[]> countActiveBySeverity(@Param("cloudAccountId") Long cloudAccountId);

    /** Backs the "new since" strip — findings first observed inside the window. */
    @Query("SELECT f FROM CloudSecurityFinding f "
            + "WHERE f.cloudAccountId = :cloudAccountId AND f.status = 'ACTIVE' "
            + "AND f.firstSeenAt >= :since ORDER BY f.firstSeenAt DESC")
    List<CloudSecurityFinding> findRecent(@Param("cloudAccountId") Long cloudAccountId,
            @Param("since") Instant since);

    long countByCloudAccountIdAndStatus(Long cloudAccountId, String status);

    /** All findings against one resource, for the drill-down. */
    @Query("SELECT f FROM CloudSecurityFinding f "
            + "WHERE f.cloudAccountId = :cloudAccountId AND f.resourceArn = :arn "
            + "ORDER BY f.lastSeenAt DESC")
    List<CloudSecurityFinding> findByResource(@Param("cloudAccountId") Long cloudAccountId,
            @Param("arn") String arn);

    // ── Notification ────────────────────────────────────────────────────────

    /**
     * Findings at or above a severity that have never been notified.
     *
     * <p>
     * Driving notification from a query rather than from the ingest call site is deliberate:
     * a crash between "row written" and "email sent" would otherwise lose the alert
     * permanently, and this way the next pass picks it up.
     */
    @Query("SELECT f FROM CloudSecurityFinding f "
            + "WHERE f.cloudAccountId = :cloudAccountId AND f.status = 'ACTIVE' "
            + "AND f.notifiedAt IS NULL AND f.severity IN :severities "
            + "ORDER BY f.firstSeenAt ASC")
    List<CloudSecurityFinding> findUnnotified(@Param("cloudAccountId") Long cloudAccountId,
            @Param("severities") List<String> severities);

    @Modifying
    @Query("UPDATE CloudSecurityFinding f SET f.notifiedAt = :now WHERE f.id IN :ids")
    int markNotified(@Param("ids") List<Long> ids, @Param("now") Instant now);

    // ── Retention ───────────────────────────────────────────────────────────

    @Modifying
    @Query("DELETE FROM CloudSecurityFinding f "
            + "WHERE f.status <> 'ACTIVE' AND f.resolvedAt < :before")
    int purgeResolvedBefore(@Param("before") Instant before);

    /**
     * Deletes findings whose owning {@code cloud_account} row is gone.
     *
     * <p>
     * Disconnecting an account deletes the {@code cloud_account} row but leaves its data
     * behind, and reconnecting mints a new id rather than reusing the old one. Those orphans
     * are unreachable by every scoped read above and no scheduler runs for them any more, so
     * left alone they accumulate for the lifetime of the database — the same leak
     * {@code AwsCloudResourceRepository.deleteOrphanedByMissingAccount} exists to close.
     */
    @Modifying
    @Query("DELETE FROM CloudSecurityFinding f WHERE f.cloudAccountId NOT IN "
            + "(SELECT a.id FROM CloudAccount a)")
    int deleteOrphanedByMissingAccount();
}
