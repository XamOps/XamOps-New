package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AwsCloudResource;
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
 * Reads and writes for the shared AWS inventory plane.
 *
 * <p>
 * The paged and projection queries here are the point of the whole table: today
 * {@code CloudListService.getAllResourcesPaginated} deserialises every resource in the
 * account and calls {@code subList()}, so page 20 costs the same as page 1. These push
 * that work into Postgres.
 */
@Repository
public interface AwsCloudResourceRepository extends JpaRepository<AwsCloudResource, Long> {

    Optional<AwsCloudResource> findByCloudAccountIdAndArn(Long cloudAccountId, String arn);

    // ── Reads for page consumers ────────────────────────────────────────────
    //
    // Every read is scoped by cloudAccountId (the cloud_account row = one tenant's
    // connection), never by awsAccountId. The distinction is load-bearing: a single AWS
    // account can own several cloud_account rows, either because two tenants connected
    // it or because a customer disconnected and reconnected, which mints a new row and
    // leaves the old one's resources behind. Scoping reads by awsAccountId then returns
    // one ARN once per connection, silently double-counting the whole estate.

    @Query("SELECT r FROM AwsCloudResource r "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.status = 'ACTIVE' "
            + "ORDER BY r.displayType, r.resourceId")
    List<AwsCloudResource> findActiveByCloudAccountId(@Param("cloudAccountId") Long cloudAccountId);

    @Query("SELECT r FROM AwsCloudResource r "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.status = 'ACTIVE'")
    Page<AwsCloudResource> findActiveByCloudAccountId(@Param("cloudAccountId") Long cloudAccountId,
            Pageable pageable);

    /**
     * Used by consumers that only want one slice — {@code XamOpsRightsizingService}
     * wanting EC2 instances, {@code CloudMapService} wanting VPCs. Those currently pull
     * the whole account and filter in the JVM.
     */
    @Query("SELECT r FROM AwsCloudResource r "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.status = 'ACTIVE' "
            + "AND r.displayType = :displayType")
    List<AwsCloudResource> findActiveByCloudAccountIdAndDisplayType(
            @Param("cloudAccountId") Long cloudAccountId,
            @Param("displayType") String displayType);

    @Query("SELECT r.displayType, COUNT(r) FROM AwsCloudResource r "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.status = 'ACTIVE' "
            + "GROUP BY r.displayType ORDER BY r.displayType")
    List<Object[]> countActiveByDisplayType(@Param("cloudAccountId") Long cloudAccountId);

    /**
     * Active regions derived from what actually exists, replacing the
     * describeRegions + five probe calls per region discovery pass.
     */
    @Query("SELECT DISTINCT r.region FROM AwsCloudResource r "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.status = 'ACTIVE' "
            + "AND r.region IS NOT NULL AND r.region <> ''")
    List<String> findActiveRegions(@Param("cloudAccountId") Long cloudAccountId);

    long countByCloudAccountIdAndStatus(Long cloudAccountId, String status);

    /** Backs the "new since" feed and the UI's new-resource badge. */
    @Query("SELECT r FROM AwsCloudResource r "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.status = 'ACTIVE' "
            + "AND r.firstSeenAt >= :since ORDER BY r.firstSeenAt DESC")
    List<AwsCloudResource> findRecentlyAdded(@Param("cloudAccountId") Long cloudAccountId,
            @Param("since") Instant since);

    // ── Reconciliation ─────────────────────────────────────────────────────

    /**
     * Mark-and-sweep tombstone. Anything the current generation did not touch is gone
     * from AWS, so it is marked deleted rather than removed — waste, cost and audit
     * views all need to reason about resources that existed yesterday.
     */
    @Modifying
    @Query("UPDATE AwsCloudResource r SET r.status = 'DELETED', r.deletedAt = :now "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.status = 'ACTIVE' "
            + "AND r.syncGeneration < :generation")
    int sweepStaleResources(@Param("cloudAccountId") Long cloudAccountId,
            @Param("generation") Long generation,
            @Param("now") Instant now);

    /**
     * Sweep that spares regions Resource Explorer cannot see into.
     *
     * <p>
     * Needed because Resource Explorer does not index AWS opt-in regions automatically. A
     * resource in such a region is missing from the search for a coverage reason, not
     * because it was deleted — sweeping it would delete correct data that the describe
     * fallback had found. Region is also allowed to be null so global resources, which
     * carry no region, are still swept normally.
     */
    @Modifying
    @Query("UPDATE AwsCloudResource r SET r.status = 'DELETED', r.deletedAt = :now "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.status = 'ACTIVE' "
            + "AND r.syncGeneration < :generation "
            + "AND (r.region IS NULL OR r.region NOT IN :protectedRegions)")
    int sweepStaleResourcesExcludingRegions(@Param("cloudAccountId") Long cloudAccountId,
            @Param("generation") Long generation,
            @Param("now") Instant now,
            @Param("protectedRegions") java.util.Collection<String> protectedRegions);

    /**
     * Purge long-dead rows so the table does not grow without bound. Kept as an
     * explicit call rather than a cascade so retention is a deliberate decision.
     */
    @Modifying
    @Query("DELETE FROM AwsCloudResource r WHERE r.status = 'DELETED' AND r.deletedAt < :before")
    int purgeDeletedBefore(@Param("before") Instant before);

    /**
     * Deletes rows whose owning cloud_account row is gone.
     *
     * <p>
     * Disconnecting an account deletes the cloud_account row but left its inventory
     * behind, and reconnecting mints a new row rather than reusing the old id. Those
     * orphans are unreachable by every scoped read and, critically, no scheduler runs for
     * them any more, so the mark-and-sweep that would normally retire them never fires
     * again. Left alone they accumulate for the lifetime of the database.
     */
    @Modifying
    @Query("DELETE FROM AwsCloudResource r WHERE r.cloudAccountId NOT IN "
            + "(SELECT a.id FROM CloudAccount a)")
    int deleteOrphanedByMissingAccount();
}
