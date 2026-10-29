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
}
