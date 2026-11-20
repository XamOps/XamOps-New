package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.SpotOperation;
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
 * Finders and the write-ahead insert for {@link SpotOperation} (plan-phase-03.md task 3.2,
 * plan-phase-06.md task 6.3). {@link #insertQueued} is the guarded native insert of a QUEUED op
 * (design-core.md §4.5); the runner's status/step advances arrive in Phase 07. Both write methods run
 * in the caller's transaction (no {@code @Transactional} here), which
 * {@link com.xammer.cloud.service.autospotting.core.ops.OperationStore} opens.
 */
@Repository
public interface SpotOperationRepository extends JpaRepository<SpotOperation, UUID> {

    List<SpotOperation> findByGroupId(UUID groupId);

    Optional<SpotOperation> findByPlanId(UUID planId);

    Optional<SpotOperation> findByCloudAccountIdAndIdempotencyKey(Long cloudAccountId, String idempotencyKey);

    /**
     * Insert a write-ahead operation row (§4.5): {@code status=QUEUED}, {@code phase=FORWARD},
     * {@code step_seq=0}, {@code first_mutation_seq=null}, {@code version=0}. The unique indexes
     * {@code ux_spot_op_running} and {@code ux_spot_op_idem} enforce one running op per group and
     * idempotency-key uniqueness. JSONB values are passed as text and cast with {@code ::jsonb}.
     */
    @Modifying
    @Query(value = "INSERT INTO spot_operation"
            + " (id, group_id, cloud_account_id, plan_id, kind, action_class, status, phase, step_seq,"
            + "  prior_state, prior_pause_reason, compensation_target, compensation_config, target_config,"
            + "  cancel_requested, compensate_requested, idempotency_key, request_sha256, requested_by,"
            + "  dry_run, deadline_at, created_at, updated_at, version)"
            + " VALUES (:id, :groupId, :cloudAccountId, :planId, :kind, :actionClass, 'QUEUED', 'FORWARD', 0,"
            + "  :priorState, :priorPauseReason, :compensationTarget, CAST(:compensationConfig AS jsonb),"
            + "  CAST(:targetConfig AS jsonb), false, false, :idempotencyKey, :requestSha256, :requestedBy,"
            + "  :dryRun, :deadlineAt, :createdAt, :updatedAt, 0)",
            nativeQuery = true)
    int insertQueued(@Param("id") UUID id,
                     @Param("groupId") UUID groupId,
                     @Param("cloudAccountId") Long cloudAccountId,
                     @Param("planId") UUID planId,
                     @Param("kind") String kind,
                     @Param("actionClass") String actionClass,
                     @Param("priorState") String priorState,
                     @Param("priorPauseReason") String priorPauseReason,
                     @Param("compensationTarget") String compensationTarget,
                     @Param("compensationConfig") String compensationConfig,
                     @Param("targetConfig") String targetConfig,
                     @Param("idempotencyKey") String idempotencyKey,
                     @Param("requestSha256") String requestSha256,
                     @Param("requestedBy") String requestedBy,
                     @Param("dryRun") boolean dryRun,
                     @Param("deadlineAt") Instant deadlineAt,
                     @Param("createdAt") Instant createdAt,
                     @Param("updatedAt") Instant updatedAt);

    /**
     * Set {@code first_mutation_seq} to {@code seq} the first time a mutating step records intent, and
     * never overwrite it (the {@code first_mutation_seq IS NULL} guard). Also advances {@code step_seq}
     * to at least {@code seq} and stamps {@code updated_at}. Returns rows updated (0 when already set).
     */
    @Modifying
    @Query(value = "UPDATE spot_operation"
            + "   SET first_mutation_seq = :seq, step_seq = GREATEST(step_seq, :seq), updated_at = :updatedAt"
            + " WHERE id = :id AND first_mutation_seq IS NULL",
            nativeQuery = true)
    int setFirstMutationSeq(@Param("id") UUID id, @Param("seq") int seq, @Param("updatedAt") Instant updatedAt);

    // ── dispatcher and runner advances (plan-phase-07.md tasks 7.1-7.3, design-core.md §4.1, §4.5) ──

    /**
     * The ids of the group's ops the dispatcher should consider claiming, in creation order
     * (design-core.md §4.1, §4.7): QUEUED ops, and WAITING ops that are due — either
     * {@code next_attempt_at} has passed, or {@code cancel_requested}/{@code compensate_requested}
     * is set (those are due at once whatever the wait, §4.7). One group's ops are taken in creation
     * order so a preempted-before-start queued op ends CANCELLED before its preemptor runs (§4.7).
     * Dry-run ops are excluded here (they are not driven by the dispatcher in this phase).
     */
    @Query(value = "SELECT id FROM spot_operation"
            + " WHERE group_id = :groupId AND NOT dry_run"
            + "   AND (status = 'QUEUED'"
            + "        OR (status = 'WAITING'"
            + "            AND (cancel_requested OR compensate_requested"
            + "                 OR next_attempt_at IS NULL OR next_attempt_at <= :now)))"
            + " ORDER BY created_at ASC, id ASC",
            nativeQuery = true)
    List<UUID> findDueOpIdsForGroup(@Param("groupId") UUID groupId, @Param("now") Instant now);

    /**
     * The distinct group ids with at least one due op (QUEUED, or WAITING that is due), so the
     * dispatcher polls only groups with work. Ordered by the oldest due op first, so no group
     * starves (design-core.md §4.1 "LIMIT free slots").
     */
    @Query(value = "SELECT group_id FROM spot_operation"
            + " WHERE NOT dry_run"
            + "   AND (status = 'QUEUED'"
            + "        OR (status = 'WAITING'"
            + "            AND (cancel_requested OR compensate_requested"
            + "                 OR next_attempt_at IS NULL OR next_attempt_at <= :now)))"
            + " GROUP BY group_id"
            + " ORDER BY MIN(created_at) ASC",
            nativeQuery = true)
    List<UUID> findGroupsWithDueOps(@Param("now") Instant now);

    /**
     * Claim a QUEUED or due-WAITING op into {@code RUNNING}, clearing the wait columns and bumping
     * {@code version} (design-core.md §4.1). Guarded on the current status so two dispatchers can't
     * both claim it. Returns rows updated (1 on claim, 0 when another instance won the race).
     */
    @Modifying
    @Query(value = "UPDATE spot_operation"
            + "   SET status = 'RUNNING', wait_reason = NULL, next_attempt_at = NULL,"
            + "       updated_at = :now, version = version + 1"
            + " WHERE id = :id AND status IN ('QUEUED','WAITING')",
            nativeQuery = true)
    int claimForRun(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Park a running op as {@code WAITING} with its {@code wait_reason} and {@code next_attempt_at},
     * advancing {@code step_seq} to at least {@code stepSeq} and storing {@code progress} (design-core.md
     * §4.1, §4.5). The lease is kept by the holder's heartbeat; the dispatcher requeues it when due.
     */
    @Modifying
    @Query(value = "UPDATE spot_operation"
            + "   SET status = 'WAITING', wait_reason = :waitReason, next_attempt_at = :nextAttemptAt,"
            + "       step_seq = GREATEST(step_seq, :stepSeq), progress = CAST(:progress AS jsonb),"
            + "       updated_at = :now, version = version + 1"
            + " WHERE id = :id",
            nativeQuery = true)
    int park(@Param("id") UUID id, @Param("waitReason") String waitReason,
             @Param("nextAttemptAt") Instant nextAttemptAt, @Param("stepSeq") int stepSeq,
             @Param("progress") String progress, @Param("now") Instant now);

    /** Advance {@code step_seq} to at least {@code stepSeq} on a running op (after a step completes). */
    @Modifying
    @Query(value = "UPDATE spot_operation"
            + "   SET step_seq = GREATEST(step_seq, :stepSeq), updated_at = :now, version = version + 1"
            + " WHERE id = :id",
            nativeQuery = true)
    int advanceStep(@Param("id") UUID id, @Param("stepSeq") int stepSeq, @Param("now") Instant now);

    /**
     * Switch the op to {@code phase=COMPENSATING} toward its {@code compensation_target} (§2.2.1,
     * §9.4). Resets {@code step_seq} to 0 so the compensation program runs from its first step.
     */
    @Modifying
    @Query(value = "UPDATE spot_operation"
            + "   SET phase = 'COMPENSATING', step_seq = :baseSeq, wait_reason = NULL, next_attempt_at = NULL,"
            + "       updated_at = :now, version = version + 1"
            + " WHERE id = :id AND phase = 'FORWARD'",
            nativeQuery = true)
    int beginCompensating(@Param("id") UUID id, @Param("baseSeq") int baseSeq, @Param("now") Instant now);

    /**
     * Finish an op in a terminal {@code status} ({@code SUCCEEDED}, {@code FAILED} or
     * {@code CANCELLED}), stamping {@code finished_at}, the error columns and the cancel reason
     * (design-core.md §4.5). Clears the wait columns. Guarded on the id only; the runner holds the
     * lease, so no concurrent writer races it.
     */
    @Modifying
    @Query(value = "UPDATE spot_operation"
            + "   SET status = :status, wait_reason = NULL, next_attempt_at = NULL,"
            + "       error_code = :errorCode, error_message = :errorMessage,"
            + "       cancel_reason = COALESCE(:cancelReason, cancel_reason),"
            + "       finished_at = :now, updated_at = :now, version = version + 1"
            + " WHERE id = :id",
            nativeQuery = true)
    int finish(@Param("id") UUID id, @Param("status") String status,
               @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
               @Param("cancelReason") String cancelReason, @Param("now") Instant now);

    /**
     * Mark a past-deadline op {@code wait_reason=STUCK} without changing its status (design-core.md
     * §4.6: keep the group state, set STUCK, alert — never FAILED just for the wall-clock cap, GCP-20).
     */
    @Modifying
    @Query(value = "UPDATE spot_operation"
            + "   SET wait_reason = 'STUCK', updated_at = :now, version = version + 1"
            + " WHERE id = :id AND status IN ('RUNNING','WAITING')",
            nativeQuery = true)
    int markStuck(@Param("id") UUID id, @Param("now") Instant now);

    /** Non-terminal ops ({@code RUNNING}/{@code WAITING}) for a group, newest first (startup recovery). */
    @Query(value = "SELECT * FROM spot_operation"
            + " WHERE group_id = :groupId AND status IN ('RUNNING','WAITING')"
            + " ORDER BY created_at DESC",
            nativeQuery = true)
    List<SpotOperation> findLiveByGroup(@Param("groupId") UUID groupId);

    /** The most recent op for a group (startup recovery of a group with no live op, §4.6). */
    @Query(value = "SELECT * FROM spot_operation WHERE group_id = :groupId"
            + " ORDER BY created_at DESC LIMIT 1",
            nativeQuery = true)
    Optional<SpotOperation> findLatestByGroup(@Param("groupId") UUID groupId);

    /**
     * Every real (not dry-run) FALLBACK_ENTER and FALLBACK_EXIT of a group, oldest first. The daily cap, the exit
     * backoff and the re-entry rule are all read from this history (design-aws.md §6.5), so no new column or
     * table is needed.
     */
    @Query(value = "SELECT * FROM spot_operation WHERE group_id = :groupId"
            + " AND kind IN ('FALLBACK_ENTER','FALLBACK_EXIT') AND dry_run = false ORDER BY created_at ASC",
            nativeQuery = true)
    List<SpotOperation> findFallbackOpsByGroup(@Param("groupId") UUID groupId);

    /** The CONFIG_APPLY / PROFILE_CHANGE a fallback preempted: its confirmed change is applied at exit (core §2.4). */
    @Query(value = "SELECT * FROM spot_operation WHERE preempted_by_op = :opId"
            + " AND kind IN ('CONFIG_APPLY','PROFILE_CHANGE') ORDER BY created_at DESC",
            nativeQuery = true)
    List<SpotOperation> findConfigOpsPreemptedBy(@Param("opId") UUID opId);

    // ── preemption (plan-phase-07.md task 7.3, design-core.md §4.7) ─────────────

    /**
     * The non-terminal ops for a group, under the preemption decision. Returned newest first so
     * {@link com.xammer.cloud.service.autospotting.core.ops.PreemptionService} inspects the op in
     * flight. (QUEUED/RUNNING/WAITING.)
     */
    @Query(value = "SELECT * FROM spot_operation"
            + " WHERE group_id = :groupId AND status IN ('QUEUED','RUNNING','WAITING')"
            + " ORDER BY created_at DESC",
            nativeQuery = true)
    List<SpotOperation> findNonTerminalByGroup(@Param("groupId") UUID groupId);

    /**
     * Conditionally request cancellation of a non-terminal op (§4.7 preemption). Sets
     * {@code cancel_requested=true}, {@code cancel_reason} and {@code preempted_by_op}; the dispatcher
     * then treats the op as due at once. Guarded on the op still being non-terminal and not already
     * cancel-requested, so the UPDATE is idempotent. Returns rows updated.
     */
    @Modifying
    @Query(value = "UPDATE spot_operation"
            + "   SET cancel_requested = true, cancel_reason = :reason, preempted_by_op = :preemptedBy,"
            + "       updated_at = :now, version = version + 1"
            + " WHERE id = :id AND status IN ('QUEUED','RUNNING','WAITING') AND NOT cancel_requested",
            nativeQuery = true)
    int requestCancel(@Param("id") UUID id, @Param("reason") String reason,
                      @Param("preemptedBy") UUID preemptedBy, @Param("now") Instant now);

    /**
     * Conditionally request compensation of a switch-paused op (§4.7, reconciler health pass). Sets
     * {@code compensate_requested=true} and {@code compensate_reason}; the dispatcher treats the op
     * as due. Guarded on a non-terminal, past-first-mutation op not already compensate-requested.
     */
    @Modifying
    @Query(value = "UPDATE spot_operation"
            + "   SET compensate_requested = true, compensate_reason = :reason,"
            + "       updated_at = :now, version = version + 1"
            + " WHERE id = :id AND status IN ('RUNNING','WAITING') AND first_mutation_seq IS NOT NULL"
            + "   AND NOT compensate_requested",
            nativeQuery = true)
    int requestCompensation(@Param("id") UUID id, @Param("reason") String reason, @Param("now") Instant now);

    // ── operator API (plan-phase-09.md task 9.4, design-core.md §6.6) ───────────

    /**
     * The operator {@code GET /operations?waitReason=&provider=} list (design-core.md §6.6): stuck
     * and long-waiting ops. When {@code waitReason} is null it returns every non-terminal op
     * (RUNNING/WAITING); when set it filters to that {@code wait_reason} (e.g. {@code STUCK}). When
     * {@code provider} is null it spans both clouds; when set it joins {@code spot_group} and keeps
     * ops of that provider. Paginated (the controller caps the page size at 200), oldest deadline
     * first so the most pressing surface at the top.
     */
    @Query(value = "SELECT o.* FROM spot_operation o"
            + " JOIN spot_group g ON g.id = o.group_id"
            + " WHERE o.status IN ('RUNNING','WAITING')"
            + "   AND (:waitReason IS NULL OR o.wait_reason = :waitReason)"
            + "   AND (:provider IS NULL OR g.provider = :provider)"
            + " ORDER BY o.deadline_at ASC, o.created_at ASC",
            countQuery = "SELECT count(*) FROM spot_operation o"
            + " JOIN spot_group g ON g.id = o.group_id"
            + " WHERE o.status IN ('RUNNING','WAITING')"
            + "   AND (:waitReason IS NULL OR o.wait_reason = :waitReason)"
            + "   AND (:provider IS NULL OR g.provider = :provider)",
            nativeQuery = true)
    org.springframework.data.domain.Page<SpotOperation> findOperatorOps(
            @Param("waitReason") String waitReason,
            @Param("provider") String provider,
            org.springframework.data.domain.Pageable pageable);
}
