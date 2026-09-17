package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SecurityFindingResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes for user-asserted finding resolutions.
 *
 * <p>
 * Scoped by {@code cloudAccountId} throughout, never by the 12-digit AWS account id. On this
 * table the consequence of getting that wrong is the inverse of the findings table's: rather than
 * showing one tenant another tenant's vulnerabilities, it would <em>hide</em> a live finding from
 * one tenant because a different tenant dismissed the same control on the same shared account.
 */
@Repository
public interface SecurityFindingResolutionRepository
        extends JpaRepository<SecurityFindingResolution, Long> {

    /**
     * Every live resolution for one connection.
     *
     * <p>
     * The whole set is loaded per request and matched in memory rather than joined against the
     * findings. It has to be: two of the three finding sources never touch the database, so there
     * is nothing to join to, and the set is one row per finding a human has personally dismissed
     * — bounded by human effort, not by estate size.
     */
    @Query("SELECT r FROM SecurityFindingResolution r "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.retiredAt IS NULL")
    List<SecurityFindingResolution> findLive(@Param("cloudAccountId") Long cloudAccountId);

    @Query("SELECT r FROM SecurityFindingResolution r "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.findingKeyHash = :hash "
            + "AND r.retiredAt IS NULL")
    Optional<SecurityFindingResolution> findLiveByHash(
            @Param("cloudAccountId") Long cloudAccountId, @Param("hash") String hash);

    /** The audit trail, newest first — including resolutions that were retired. */
    @Query("SELECT r FROM SecurityFindingResolution r "
            + "WHERE r.cloudAccountId = :cloudAccountId ORDER BY r.resolvedAt DESC")
    List<SecurityFindingResolution> findHistory(@Param("cloudAccountId") Long cloudAccountId);

    /**
     * Retires the live resolutions for a set of keys.
     *
     * <p>
     * A bulk update rather than a load-and-save loop because this runs on the read path: a scan
     * that contradicts fifty dismissals should cost one statement, not fifty round trips while a
     * user waits for the page.
     */
    // flushAutomatically so a resolution written earlier in the same transaction is visible to
    // this statement's WHERE clause, and clearAutomatically so a caller reading the row back
    // afterwards sees the retirement rather than the pre-update copy still held in the
    // persistence context. A bulk UPDATE goes straight to SQL and does not otherwise touch
    // managed entities, which makes "I just retired it and it still looks live" the default.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SecurityFindingResolution r "
            + "SET r.retiredAt = :now, r.retiredReason = :reason "
            + "WHERE r.cloudAccountId = :cloudAccountId AND r.findingKeyHash IN :hashes "
            + "AND r.retiredAt IS NULL")
    int retire(@Param("cloudAccountId") Long cloudAccountId,
            @Param("hashes") List<String> hashes,
            @Param("reason") String reason,
            @Param("now") Instant now);

    /**
     * Deletes resolutions whose owning {@code cloud_account} row is gone.
     *
     * <p>
     * The table has {@code ON DELETE CASCADE}, so this is a backstop for the same reason
     * {@code CloudSecurityFindingRepository.deleteOrphanedByMissingAccount} is: rows written
     * before a constraint exists are not retrospectively covered by it, and orphans here are
     * unreachable by every scoped read above.
     */
    @Modifying
    @Query("DELETE FROM SecurityFindingResolution r WHERE r.cloudAccountId NOT IN "
            + "(SELECT a.id FROM CloudAccount a)")
    int deleteOrphanedByMissingAccount();
}
