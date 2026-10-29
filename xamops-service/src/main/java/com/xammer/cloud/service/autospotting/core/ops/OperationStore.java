package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.domain.autospotting.StepStatus;
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
}
