package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.SpotOperationStep;
import com.xammer.cloud.domain.autospotting.SpotOperationStepId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finders and the fenced step-protocol writes for {@link SpotOperationStep} (plan-phase-03.md task
 * 3.2, plan-phase-06.md task 6.3), keyed by {@link SpotOperationStepId}. The protocol order of §4.5
 * is: (1) {@link #recordIntent} the step {@code INTENT_RECORDED}, (2) {@code assertHeld} + the cloud
 * call, (3) {@link #completeStep} to {@code DONE}/{@code IN_PROGRESS}/{@code FAILED} with the request
 * ids. All three writes run inside the caller's fenced transaction ({@link
 * com.xammer.cloud.service.autospotting.core.lease.FencedTx}), so a lost lease rolls them back.
 */
@Repository
public interface SpotOperationStepRepository extends JpaRepository<SpotOperationStep, SpotOperationStepId> {

    List<SpotOperationStep> findByOperationIdOrderBySeqAsc(UUID operationId);

    /**
     * Step protocol (1): insert the step {@code INTENT_RECORDED} with its deterministic
     * {@code idempotency_token} and the holder's {@code lease_generation}, before any cloud call
     * (§4.5). {@code attempts} starts at 1. A re-issue after a crash uses the same {@code (op, seq)}
     * key; the insert is attempted once per step creation.
     */
    @Modifying
    @Query(value = "INSERT INTO spot_operation_step"
            + " (operation_id, seq, name, status, idempotency_token, input, lease_generation, attempts, intent_at)"
            + " VALUES (:operationId, :seq, :name, 'INTENT_RECORDED', :idempotencyToken, CAST(:input AS jsonb),"
            + "  :leaseGeneration, 1, :intentAt)",
            nativeQuery = true)
    int recordIntent(@Param("operationId") UUID operationId,
                     @Param("seq") int seq,
                     @Param("name") String name,
                     @Param("idempotencyToken") String idempotencyToken,
                     @Param("input") String input,
                     @Param("leaseGeneration") long leaseGeneration,
                     @Param("intentAt") Instant intentAt);

    /**
     * Step protocol (3): update the step to its terminal {@code status} ({@code DONE},
     * {@code IN_PROGRESS} or {@code FAILED}) with the cloud {@code request_ids} and redacted
     * {@code output}, stamping {@code completed_at} (§4.5). Guarded on {@code (operation_id, seq)}.
     * Returns rows updated.
     */
    @Modifying
    @Query(value = "UPDATE spot_operation_step"
            + "   SET status = :status, cloud_request_ids = CAST(:cloudRequestIds AS jsonb),"
            + "       output = CAST(:output AS jsonb), completed_at = :completedAt"
            + " WHERE operation_id = :operationId AND seq = :seq",
            nativeQuery = true)
    int completeStep(@Param("operationId") UUID operationId,
                     @Param("seq") int seq,
                     @Param("status") String status,
                     @Param("cloudRequestIds") String cloudRequestIds,
                     @Param("output") String output,
                     @Param("completedAt") Instant completedAt);

    /** The step row at {@code (operationId, seq)}, or empty when the step has not been recorded yet. */
    java.util.Optional<SpotOperationStep> findByOperationIdAndSeq(UUID operationId, int seq);

    /**
     * Re-open a step for a fresh attempt without changing its {@code intent_at}: back to
     * {@code INTENT_RECORDED}, incrementing {@code attempts}, clearing the previous terminal result
     * (design-core.md §9.4 "up to 5 step attempts"; the retry re-issues the same deterministic token).
     * Used by the runner when a TRANSIENT/THROTTLED retry re-runs the step at the next due poll.
     */
    @Modifying
    @Query(value = "UPDATE spot_operation_step"
            + "   SET status = 'INTENT_RECORDED', attempts = attempts + 1,"
            + "       cloud_request_ids = NULL, output = NULL, completed_at = NULL, lease_generation = :leaseGeneration"
            + " WHERE operation_id = :operationId AND seq = :seq",
            nativeQuery = true)
    int reopenForRetry(@Param("operationId") UUID operationId, @Param("seq") int seq,
                       @Param("leaseGeneration") long leaseGeneration);
}
