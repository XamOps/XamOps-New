package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.domain.autospotting.OperationStatus;
import com.xammer.cloud.domain.autospotting.StepStatus;
import com.xammer.cloud.domain.autospotting.WaitReason;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationStepRepository;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The write-ahead operation and step store (design-core.md §4.5, plan-phase-06.md task 6.3). It
 * owns:
 *
 * <ol>
 *   <li>Inserting the op row {@code QUEUED} with {@code prior_state}, {@code prior_pause_reason},
 *       {@code compensation_target} (from {@link CompensationTargets}), {@code target_config} and
 *       {@code deadline_at} from the per-kind budget (default 6 h, {@link OperationRecord});</li>
 *   <li>The fenced step protocol: {@link #recordIntent} writes {@code INTENT_RECORDED} <em>before</em>
 *       the (fake, for now) cloud call, then {@link #completeStep} writes {@code DONE}/{@code
 *       IN_PROGRESS}/{@code FAILED} with the request ids after it. Both run inside a {@link FencedTx}
 *       on the group lease, so a step write after lease loss throws {@code LeaseLostException} and
 *       rolls back;</li>
 *   <li>Setting {@code first_mutation_seq} from the first mutating step's intent (and never
 *       overwriting it), so it stays null when only read steps ran;</li>
 *   <li>Redacting {@code input}/{@code output} through {@link AuditRedactor} before they reach the row.</li>
 * </ol>
 *
 * <p>No dispatcher or runner drives this yet (plan-phase-06.md "Flag or rollout gate: None; no caller
 * yet"). It is exercised by {@code OperationStoreIT} on real PostgreSQL. The op insert runs in its own
 * transaction; the step writes run in the caller's fenced transaction.
 */
@Service
public class OperationStore {

    private static final Logger log = LoggerFactory.getLogger(OperationStore.class);

    private final SpotOperationRepository operations;
    private final SpotOperationStepRepository steps;
    private final FencedTx fencedTx;
    private final AuditRedactor redactor;

    /** Optional: a terminal operation evicts the group list cache so the list never shows a stale row (ux §9.8). */
    private com.xammer.cloud.service.autospotting.core.list.GroupListCache listCache;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGroupListCache(com.xammer.cloud.service.autospotting.core.list.GroupListCache listCache) {
        this.listCache = listCache;
    }

    public OperationStore(SpotOperationRepository operations, SpotOperationStepRepository steps,
                          FencedTx fencedTx, AuditRedactor redactor) {
        this.operations = operations;
        this.steps = steps;
        this.fencedTx = fencedTx;
        this.redactor = redactor;
    }

    /**
     * Insert the write-ahead op row {@code QUEUED} (§4.5). The {@code action_class} is derived from
     * the kind ({@link CompensationTargets#actionClass}). Returns the inserted op id. Runs in its own
     * transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public java.util.UUID insertQueued(OperationRecord record) {
        Instant now = Instant.now();
        int inserted = operations.insertQueued(
                record.id(), record.groupId(), record.cloudAccountId(), record.planId(),
                record.kind().name(),
                CompensationTargets.actionClass(record.kind()).name(),
                record.priorState().name(),
                record.priorPauseReason() == null ? null : record.priorPauseReason().name(),
                record.compensationTarget().name(),
                record.compensationConfigJson(),
                record.targetConfigJson(),
                record.idempotencyKey(),
                record.requestSha256(),
                record.requestedBy(),
                record.dryRun(),
                record.deadlineAt(),
                now, now);
        if (inserted != 1) {
            throw new IllegalStateException("insert of operation " + record.id() + " affected " + inserted + " rows");
        }
        if (log.isDebugEnabled()) {
            log.debug("op {} QUEUED kind={} group={} compensation={}", record.id(), record.kind(),
                    record.groupId(), record.compensationTarget());
        }
        return record.id();
    }

    /**
     * Step protocol (1): record the step's intent {@code INTENT_RECORDED} before any cloud call,
     * fenced on {@code handle}. When {@code mutating} is true this also sets the op's
     * {@code first_mutation_seq} to this {@code seq} the first time (never overwriting it). The
     * {@code inputJson} is redacted before it reaches the row.
     */
    public void recordIntent(LeaseHandle handle, java.util.UUID operationId, int seq, String name,
                             String idempotencyToken, String inputJson, boolean mutating) {
        Instant now = Instant.now();
        fencedTx.run(handle, () -> {
            steps.recordIntent(operationId, seq, name, idempotencyToken,
                    redactor.redactJson(inputJson), handle.generation(), now);
            if (mutating) {
                steps.flush();
                operations.setFirstMutationSeq(operationId, seq, now);
            }
        });
    }

    /** When step {@code seq} of the op recorded its intent: the instant the mutation was decided (billing end of a fallback). */
    public java.util.Optional<Instant> stepIntentAt(java.util.UUID operationId, int seq) {
        return steps.findByOperationIdAndSeq(operationId, seq)
                .map(com.xammer.cloud.domain.autospotting.SpotOperationStep::getIntentAt);
    }

    /**
     * Step protocol (3): complete the step to {@code status} with the cloud {@code requestIds} and
     * redacted {@code outputJson}, fenced on {@code handle}. {@code status} must be a terminal step
     * status ({@code DONE}, {@code IN_PROGRESS} or {@code FAILED}); {@code INTENT_RECORDED} and
     * {@code SKIPPED} are not completions.
     */
    public void completeStep(LeaseHandle handle, java.util.UUID operationId, int seq, StepStatus status,
                             List<String> requestIds, String outputJson) {
        if (status == StepStatus.INTENT_RECORDED || status == StepStatus.SKIPPED) {
            throw new IllegalArgumentException("completeStep needs a terminal step status, got " + status);
        }
        Instant now = Instant.now();
        String requestIdsJson = requestIdsJson(requestIds);
        fencedTx.run(handle, () ->
                steps.completeStep(operationId, seq, status.name(), requestIdsJson,
                        redactor.redactJson(outputJson), now));
    }

    // ── dispatcher and runner advances (plan-phase-07.md §4.1, §4.5) ────────────
    //
    // These wrap the guarded native statements on SpotOperationRepository so the runner and
    // dispatcher have one store surface. Each runs in its own transaction (REQUIRES_NEW): an op
    // status/step-seq write is bookkeeping on the op row, not a group-lifecycle write, so it is not
    // fenced here (the runner fences its spot_group writes through GroupLifecycleService). The runner
    // holds the group lease across these calls, and the fenced step writes (recordIntent/completeStep)
    // still roll back on lease loss.

    /** Claim a QUEUED or due-WAITING op into {@code RUNNING}; true when this caller won the claim. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimForRun(java.util.UUID operationId) {
        return operations.claimForRun(operationId, Instant.now()) == 1;
    }

    /** Park a running op as {@code WAITING(waitReason)} with {@code nextAttemptAt}, advancing step_seq. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void park(java.util.UUID operationId, WaitReason waitReason, Instant nextAttemptAt, int stepSeq,
                     String progressJson) {
        operations.park(operationId, waitReason.name(), nextAttemptAt, stepSeq,
                redactor.redactJson(progressJson), Instant.now());
    }

    /** Advance {@code step_seq} to at least {@code stepSeq} on a running op (after a step completes). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advanceStep(java.util.UUID operationId, int stepSeq) {
        operations.advanceStep(operationId, stepSeq, Instant.now());
    }

    /**
     * Switch the op to {@code phase=COMPENSATING} toward its compensation target (§2.2.1, §9.4),
     * setting {@code step_seq} to the compensation-leg base {@code baseSeq} so the compensation steps
     * persist at a disjoint seq range from the forward steps (§4.5 append-only rows).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void beginCompensating(java.util.UUID operationId, int baseSeq) {
        operations.beginCompensating(operationId, baseSeq, Instant.now());
    }

    /** Re-open a step for a fresh attempt (TRANSIENT/THROTTLED retry), keeping its {@code intent_at}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reopenStepForRetry(java.util.UUID operationId, int seq, long leaseGeneration) {
        steps.reopenForRetry(operationId, seq, leaseGeneration);
    }

    /** Finish an op {@code SUCCEEDED}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishSucceeded(java.util.UUID operationId) {
        operations.finish(operationId, OperationStatus.SUCCEEDED.name(), null, null, null, Instant.now());
        evictList(operationId);
    }

    /** Finish an op {@code FAILED} with its redacted error code and message. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishFailed(java.util.UUID operationId, String errorCode, String errorMessage) {
        operations.finish(operationId, OperationStatus.FAILED.name(), errorCode, errorMessage, null, Instant.now());
        evictList(operationId);
    }

    /**
     * Finish an op {@code FAILED} and run {@code auditInTx} in the same transaction, so the audit row and the
     * finish commit or roll back together. For an op with no group row, which has no lease to fence on.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishFailedWithAudit(java.util.UUID operationId, String errorCode, String errorMessage,
                                      Runnable auditInTx) {
        operations.finish(operationId, OperationStatus.FAILED.name(), errorCode, errorMessage, null, Instant.now());
        auditInTx.run();
        evictList(operationId);
    }

    /** Finish an op {@code CANCELLED} with its cancel reason (§4.7). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishCancelled(java.util.UUID operationId, String cancelReason) {
        operations.finish(operationId, OperationStatus.CANCELLED.name(), null, null, cancelReason, Instant.now());
        evictList(operationId);
    }

    /** Mark a past-deadline op {@code wait_reason=STUCK} (startup recovery, §4.6; never FAILED, GCP-20). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStuck(java.util.UUID operationId) {
        operations.markStuck(operationId, Instant.now());
    }

    /** The recorded {@code attempts} of the step at {@code (operationId, seq)}, or 0 when absent (§9.4). */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public int stepAttempts(java.util.UUID operationId, int seq) {
        return steps.findByOperationIdAndSeq(operationId, seq)
                .map(com.xammer.cloud.domain.autospotting.SpotOperationStep::getAttempts)
                .orElse(0);
    }

    /** The names of the steps of {@code operationId} that ended DONE (D-23b-01: what a revert actually did). */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public java.util.Set<String> doneStepNames(java.util.UUID operationId) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (com.xammer.cloud.domain.autospotting.SpotOperationStep s : steps.findByOperationIdOrderBySeqAsc(operationId)) {
            if (s.getStatus() == StepStatus.DONE) {
                out.add(s.getName());
            }
        }
        return out;
    }

    private static String requestIdsJson(List<String> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < requestIds.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(requestIds.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /** Drop the account's cached group list after an op reaches a terminal status. Never throws. */
    private void evictList(java.util.UUID operationId) {
        if (listCache == null) {
            return;
        }
        try {
            operations.findById(operationId).ifPresent(op -> {
                Long accountId = op.getCloudAccountId();
                if (org.springframework.transaction.support.TransactionSynchronizationManager
                        .isSynchronizationActive()) {
                    // After commit, so a concurrent list can't re-cache the pre-commit state.
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    listCache.evict(accountId);
                                }
                            });
                } else {
                    listCache.evict(accountId);
                }
            });
        } catch (RuntimeException e) {
            log.debug("group list eviction skipped for op {}", operationId);
        }
    }
}
