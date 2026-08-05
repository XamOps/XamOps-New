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

    /**
     * Detections named by a threat story, restricted to the caller's accounts.
     *
     * Threat stories carry no cloud_account_id of their own — the payload has only
     * id/title/severity/status/create_time/update_time/detection_ids (verified live
     * 2026-08-04). Tenancy therefore has to come from the detections a story correlates,
     * which do carry one: a story belongs to the caller only if it references a detection in
     * one of their accounts. This also supplies the story drawer's detection list.
     */
    @Query("SELECT d FROM UpwindThreatDetection d " +
           "WHERE d.cloudAccountId IN :accountIds AND d.upwindId IN :upwindIds " +
           "ORDER BY d.lastSeenTime DESC")
    List<UpwindThreatDetection> findByAccountsAndUpwindIds(
            @Param("accountIds") List<String> accountIds,
            @Param("upwindIds") List<String> upwindIds);

    Page<UpwindThreatDetection> findByCloudAccountIdInOrderByLastSeenTimeDesc(
            List<String> accountIds, Pageable pageable);

    Page<UpwindThreatDetection> findByCloudAccountIdInAndSeverityOrderByLastSeenTimeDesc(
            List<String> accountIds, String severity, Pageable pageable);

    // Upwind emits a synthetic "smoke" detection (upwind_id = 'uwnd-smoke') to health-check the
    // detection pipeline. It has no resource/timestamps/tactic and isn't a real threat, so it is
    // excluded from the Threats table and Overview counts.
    @Query("SELECT d FROM UpwindThreatDetection d WHERE d.cloudAccountId IN :accountIds " +
           "AND d.upwindId <> 'uwnd-smoke' ORDER BY d.lastSeenTime DESC")
    Page<UpwindThreatDetection> findRealByCloudAccountIdIn(
            @Param("accountIds") List<String> accountIds, Pageable pageable);

    @Query("SELECT d FROM UpwindThreatDetection d WHERE d.cloudAccountId IN :accountIds " +
           "AND d.severity = :severity AND d.upwindId <> 'uwnd-smoke' ORDER BY d.lastSeenTime DESC")
    Page<UpwindThreatDetection> findRealByCloudAccountIdInAndSeverity(
            @Param("accountIds") List<String> accountIds, @Param("severity") String severity, Pageable pageable);

    @Query("SELECT d.severity, COUNT(d) FROM UpwindThreatDetection d " +
           "WHERE d.cloudAccountId IN :accountIds AND d.status = 'OPEN' " +
           "AND d.upwindId <> 'uwnd-smoke' GROUP BY d.severity")
    List<Object[]> countOpenBySeverity(@Param("accountIds") List<String> accountIds);

    /**
     * The four columns the Threats dashboard aggregates over, for the caller's accounts.
     *
     * Aggregated in Java rather than SQL on purpose. MITRE tactics are stored as a
     * comma-joined string (a detection can carry several), and bucketing by day needs date
     * truncation — neither is expressible portably in JPQL, and the Postgres-only
     * alternatives can't be exercised on the H2 slice DB used by the repository tests.
     * Detections are by far the smallest of the three finding tables, so scanning four
     * columns is cheaper than the round trips a per-facet query set would cost.
     */
    @Query("SELECT d.severity, d.mitreTactics, d.category, d.lastSeenTime " +
           "FROM UpwindThreatDetection d WHERE d.cloudAccountId IN :accountIds " +
           "AND d.upwindId <> 'uwnd-smoke'")
    List<Object[]> findDashboardFacets(@Param("accountIds") List<String> accountIds);

    /** Distinct resources carrying at least one detection — the funnel's resource stage. */
    @Query("SELECT COUNT(DISTINCT d.resourceId) FROM UpwindThreatDetection d " +
           "WHERE d.cloudAccountId IN :accountIds AND d.upwindId <> 'uwnd-smoke' " +
           "AND d.resourceId IS NOT NULL")
    long countDistinctResources(@Param("accountIds") List<String> accountIds);

    /** Detections against one resource — powers the unified resource view. */
    @Query("SELECT d FROM UpwindThreatDetection d WHERE d.cloudAccountId IN :accountIds " +
           "AND d.resourceId = :resourceId AND d.upwindId <> 'uwnd-smoke' " +
           "ORDER BY d.lastSeenTime DESC")
    List<UpwindThreatDetection> findByResource(@Param("accountIds") List<String> accountIds,
                                              @Param("resourceId") String resourceId);
}
