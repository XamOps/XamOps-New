package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.SpotPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Finders and guarded writes for {@link SpotPlan} (plan-phase-11.md tasks 11.1-11.3; design-core.md
 * §7.3, §7.4, §3.7). Plan creation inserts an OPEN row through {@link JpaRepository#save}; confirm
 * flips OPEN → CONFIRMED with the conditional {@link #confirmIfOpen} guarded on
 * {@code status='OPEN' AND version=:v} (§7.4 "Idempotent confirm"); a superseding plan marks the
 * prior OPEN plan SUPERSEDED; the reconciler expiry sweep (§7.4 step 6) marks past-expiry OPEN plans
 * EXPIRED.
 */
@Repository
public interface SpotPlanRepository extends JpaRepository<SpotPlan, UUID> {

    /** The single OPEN plan for a group (the partial index {@code ux_spot_plan_open} keeps at most one). */
    @Query(value = "SELECT * FROM spot_plan WHERE group_id = :groupId AND status = 'OPEN'", nativeQuery = true)
    Optional<SpotPlan> findOpenByGroup(@Param("groupId") UUID groupId);

    /** A plan by its idempotency key within an account (§3.7). */
    Optional<SpotPlan> findByCloudAccountIdAndIdempotencyKey(Long cloudAccountId, String idempotencyKey);

    /** Every OPEN plan of a "Revert all" batch (§7.5). */
    @Query(value = "SELECT * FROM spot_plan WHERE batch_id = :batchId", nativeQuery = true)
    List<SpotPlan> findByBatchId(@Param("batchId") UUID batchId);

    /**
     * Put a plan into a "Revert all" batch (§7.5). Idempotent: a plan already in this batch stays, and a plan in
     * another batch is never moved. Returns rows updated.
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE spot_plan SET batch_id = :batchId WHERE id = :id"
            + " AND (batch_id IS NULL OR batch_id = :batchId)", nativeQuery = true)
    int assignBatch(@Param("id") UUID id, @Param("batchId") UUID batchId);

    /**
     * Flip a plan OPEN → CONFIRMED, guarded on {@code status='OPEN' AND version=:version} (§7.4). Bumps
     * nothing but the status and the confirm columns; the op insert relies on {@code plan_id UNIQUE},
     * so a second confirm reads the winner's op id back. Returns rows updated (1 on win, 0 when the
     * plan already moved or the version mismatched).
     */
    @Modifying
    @Query(value = "UPDATE spot_plan"
            + "   SET status = 'CONFIRMED', confirmed_by = :confirmedBy, confirmed_at = :confirmedAt,"
            + "       confirm_acks = CAST(:confirmAcks AS jsonb), confirm_reason = :confirmReason"
            + " WHERE id = :id AND status = 'OPEN' AND version = :version",
            nativeQuery = true)
    int confirmIfOpen(@Param("id") UUID id, @Param("version") int version,
                      @Param("confirmedBy") String confirmedBy, @Param("confirmedAt") Instant confirmedAt,
                      @Param("confirmAcks") String confirmAcks, @Param("confirmReason") String confirmReason);

    /** Mark a plan STALE (a live fingerprint differed from {@code live_fingerprint}, §7.4 step 8). */
    @Modifying
    @Query(value = "UPDATE spot_plan SET status = 'STALE' WHERE id = :id AND status = 'OPEN'", nativeQuery = true)
    int markStaleIfOpen(@Param("id") UUID id);

    /** Mark the group's current OPEN plan SUPERSEDED (a new plan supersedes the old one, §7.4). */
    @Modifying
    @Query(value = "UPDATE spot_plan SET status = 'SUPERSEDED' WHERE group_id = :groupId AND status = 'OPEN'",
            nativeQuery = true)
    int supersedeOpenForGroup(@Param("groupId") UUID groupId);

    /** OPEN plans whose {@code expires_at} has passed (the reconciler step-6 expiry sweep, §7.4). */
    @Query(value = "SELECT * FROM spot_plan WHERE status = 'OPEN' AND expires_at <= :now", nativeQuery = true)
    List<SpotPlan> findExpiredOpen(@Param("now") Instant now);

    /** Mark one OPEN plan EXPIRED (expiry sweep). Returns rows updated. */
    @Modifying
    @Query(value = "UPDATE spot_plan SET status = 'EXPIRED' WHERE id = :id AND status = 'OPEN'", nativeQuery = true)
    int expireIfOpen(@Param("id") UUID id);
}
