package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindPostureFinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UpwindPostureFindingRepository extends JpaRepository<UpwindPostureFinding, Long> {

    Optional<UpwindPostureFinding> findByUpwindId(String upwindId);

    Page<UpwindPostureFinding> findByCloudAccountIdInOrderByLastSeenTimeDesc(
            List<String> accountIds, Pageable pageable);

    Page<UpwindPostureFinding> findByCloudAccountIdInAndSeverityOrderByLastSeenTimeDesc(
            List<String> accountIds, String severity, Pageable pageable);

    /**
     * Distinct findings for the Configurations → Findings list. A finding is stored once per
     * framework it maps to (same check on the same resource → many rows), so this collapses
     * to one row per (check, resource) via DISTINCT ON, keeping the most recently seen copy.
     * Severity is optional (pass null for all). Uses cast(:severity as text) — NOT ::text —
     * so Hibernate's native-query parser doesn't misread the PostgreSQL cast operator.
     * Pass an UNSORTED Pageable: ordering is baked in; Spring only appends LIMIT/OFFSET.
     */
    @Query(value =
            "SELECT * FROM (" +
            "  SELECT DISTINCT ON (coalesce(p.check_title, p.title), p.resource_id) p.* " +
            "  FROM upwind_posture_finding p " +
            "  WHERE p.cloud_account_id IN (:accountIds) " +
            "  AND (cast(:severity as text) IS NULL OR p.severity = cast(:severity as text)) " +
            "  ORDER BY coalesce(p.check_title, p.title), p.resource_id, p.last_seen_time DESC" +
            ") t ORDER BY t.last_seen_time DESC",
           countQuery =
            "SELECT count(*) FROM (" +
            "  SELECT DISTINCT coalesce(p.check_title, p.title) ct, p.resource_id " +
            "  FROM upwind_posture_finding p " +
            "  WHERE p.cloud_account_id IN (:accountIds) " +
            "  AND (cast(:severity as text) IS NULL OR p.severity = cast(:severity as text))" +
            ") x",
           nativeQuery = true)
    Page<UpwindPostureFinding> findDistinctFindings(@Param("accountIds") List<String> accountIds,
                                                    @Param("severity") String severity,
                                                    Pageable pageable);

    @Query("SELECT p.severity, COUNT(p) FROM UpwindPostureFinding p " +
           "WHERE p.cloudAccountId IN :accountIds AND p.status = 'FAIL' GROUP BY p.severity")
    List<Object[]> countFailBySeverity(@Param("accountIds") List<String> accountIds);

    @Query("SELECT p.frameworkTitle, p.status, COUNT(p) FROM UpwindPostureFinding p " +
           "WHERE p.cloudAccountId IN :accountIds AND p.frameworkTitle IS NOT NULL " +
           "GROUP BY p.frameworkTitle, p.status")
    List<Object[]> countByFrameworkAndStatus(@Param("accountIds") List<String> accountIds);

    /**
     * Scalar projection of every failing finding for the given accounts. Grouped in the
     * service layer into one "issue" per check. Deliberately excludes raw_payload so we
     * never load the large JSON blobs for the list view.
     * Columns: checkTitle, title, severity, frameworkTitle, firstSeenTime, lastSeenTime,
     *          resourceId, resourceName, resourceType, cloudProvider, region, cloudAccountId
     */
    @Query("SELECT p.checkTitle, p.title, p.severity, p.frameworkTitle, p.firstSeenTime, " +
           "p.lastSeenTime, p.resourceId, p.resourceName, p.resourceType, p.cloudProvider, " +
           "p.region, p.cloudAccountId " +
           "FROM UpwindPostureFinding p " +
           "WHERE p.cloudAccountId IN :accountIds AND p.status = 'FAIL'")
    List<Object[]> findFailingForIssues(@Param("accountIds") List<String> accountIds);

    /**
     * Failing findings whose resource is internet/externally exposed (Upwind's
     * {@code hasExposure} risk category). Powers the Attack Surface view. Native query:
     * risk_categories lives in the raw_payload JSON, and jsonb_exists avoids the '?'
     * operator clashing with JDBC bind placeholders.
     * Columns: checkTitle, title, severity, resourceId, resourceName, resourceType,
     *          firstSeenTime, lastSeenTime, frameworkTitle
     */
    @Query(value = "SELECT check_title, title, severity, resource_id, resource_name, resource_type, " +
           "first_seen_time, last_seen_time, framework_title, region " +
           "FROM upwind_posture_finding " +
           "WHERE cloud_account_id IN (:accountIds) AND status = 'FAIL' " +
           "AND jsonb_exists((cast(raw_payload as jsonb) -> 'resource' -> 'risk_categories'), 'hasExposure')",
           nativeQuery = true)
    List<Object[]> findExposureFailings(@Param("accountIds") List<String> accountIds);

    /**
     * Failing findings on identity resources (IAM users, roles, identity providers).
     * Upwind's Identities module has no reachable API — the v1 identity paths 404 and the
     * inventory search exposes only one identity asset — so the Identities view is derived
     * from configuration findings whose resource is an identity.
     * Columns: resourceId, resourceName, resourceType, checkTitle, title, severity,
     *          cloudProvider, cloudAccountId, lastSeenTime, firstSeenTime
     */
    @Query("SELECT p.resourceId, p.resourceName, p.resourceType, p.checkTitle, p.title, " +
           "p.severity, p.cloudProvider, p.cloudAccountId, p.lastSeenTime, p.firstSeenTime " +
           "FROM UpwindPostureFinding p " +
           "WHERE p.cloudAccountId IN :accountIds AND p.status = 'FAIL' " +
           "AND (p.resourceType LIKE '%IAM%' OR p.resourceType LIKE '%IDENTITY%' " +
           "     OR p.resourceType LIKE '%ROLE%' OR p.resourceType LIKE '%USER%')")
    List<Object[]> findIdentityFailings(@Param("accountIds") List<String> accountIds);

    /** Failing findings against one resource — powers the unified resource view. */
    @Query("SELECT p FROM UpwindPostureFinding p WHERE p.cloudAccountId IN :accountIds " +
           "AND p.resourceId = :resourceId AND p.status = 'FAIL' ORDER BY p.lastSeenTime DESC")
    List<UpwindPostureFinding> findFailingByResource(@Param("accountIds") List<String> accountIds,
                                                    @Param("resourceId") String resourceId);

    /** Full failing findings for one check — used by the issue detail drawer. */
    @Query("SELECT p FROM UpwindPostureFinding p " +
           "WHERE p.cloudAccountId IN :accountIds AND p.status = 'FAIL' " +
           "AND (p.checkTitle = :check OR (p.checkTitle IS NULL AND p.title = :check)) " +
           "ORDER BY p.lastSeenTime DESC")
    List<UpwindPostureFinding> findFailingByCheck(@Param("accountIds") List<String> accountIds,
                                                  @Param("check") String check);

    @Transactional
    @Modifying
    @Query("UPDATE UpwindPostureFinding p SET p.status = 'RESOLVED' " +
           "WHERE p.syncedAt < :runStart AND p.status <> 'RESOLVED'")
    int markResolvedNotSyncedSince(@Param("runStart") Instant runStart);
}
