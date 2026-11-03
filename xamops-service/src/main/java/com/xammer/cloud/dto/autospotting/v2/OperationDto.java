package com.xammer.cloud.dto.autospotting.v2;

import com.xammer.cloud.domain.autospotting.SpotOperation;

import java.time.Instant;
import java.util.UUID;

/**
 * The async view of an operation returned by
 * {@code GET /api/autospotting/v2/accounts/{accountId}/operations/{operationId}} (design-core.md §11
 * "Async semantics", FR-SPN-05, AC-SPN-07). A DTO over {@link SpotOperation}, never the entity
 * (FR-SEC-02, AC-SEC-04); {@code errorMessage} is the already-redacted {@code error_message} column.
 *
 * @param operationId the op id
 * @param groupId     the group it acts on
 * @param kind        the operation kind, as text
 * @param status      the op status (QUEUED/RUNNING/WAITING/SUCCEEDED/FAILED/CANCELLED), as text
 * @param phase       FORWARD or COMPENSATING, as text
 * @param step        the current step sequence
 * @param waitReason  why it is WAITING (null unless WAITING), as text
 * @param progress    the op's progress JSON (already redacted on write), or null
 * @param errorCode   the stable error code when FAILED, or null
 * @param errorMessage the redacted error message when FAILED, or null
 * @param dryRun      whether this is a dry-run op
 * @param deadlineAt  the operation wall-clock deadline
 * @param finishedAt  when it finished, or null
 */
public record OperationDto(
        UUID operationId,
        UUID groupId,
        String kind,
        String status,
        String phase,
        int step,
        String waitReason,
        String progress,
        String errorCode,
        String errorMessage,
        boolean dryRun,
        Instant deadlineAt,
        Instant finishedAt) {

    /** Project a {@link SpotOperation} entity to the DTO (in the controller, never the entity out). */
    public static OperationDto from(SpotOperation op) {
        return new OperationDto(
                op.getId(),
                op.getGroupId(),
                op.getKind() == null ? null : op.getKind().name(),
                op.getStatus() == null ? null : op.getStatus().name(),
                op.getPhase() == null ? null : op.getPhase().name(),
                op.getStepSeq(),
                op.getWaitReason() == null ? null : op.getWaitReason().name(),
                op.getProgress(),
                op.getErrorCode(),
                op.getErrorMessage(),
                op.isDryRun(),
                op.getDeadlineAt(),
                op.getFinishedAt());
    }
}
