package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.PauseReason;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The inputs {@link OperationStore} needs to insert a write-ahead {@code spot_operation} row
 * (design-core.md §4.5). Built with {@link #builder}; {@code prior_state}, {@code prior_pause_reason},
 * {@code compensation_target} and {@code target_config} come from the plan and the group's state at
 * creation, and {@code deadline_at} from the per-kind budget (default 6 h, {@link #DEFAULT_BUDGET}).
 *
 * <p>The op is always inserted {@code QUEUED} / {@code FORWARD} with {@code step_seq = 0} and
 * {@code first_mutation_seq = null}; the dispatcher and runner advance it in later phases.
 */
public record OperationRecord(
        UUID id,
        UUID groupId,
        Long cloudAccountId,
        UUID planId,
        OperationKind kind,
        GroupState priorState,
        PauseReason priorPauseReason,
        CompensationTarget compensationTarget,
        String compensationConfigJson,
        String targetConfigJson,
        String idempotencyKey,
        String requestSha256,
        String requestedBy,
        boolean dryRun,
        Instant deadlineAt) {

    /** The default per-kind operation wall-clock budget (design-core.md §4.3). */
    public static final Duration DEFAULT_BUDGET = Duration.ofHours(6);

    /**
     * The per-kind wall-clock budget (design-core.md §4.3, §4.5: "the per-kind budget, default 6 h").
     * Every kind uses the 6 h default today; a later phase can tune individual kinds without changing
     * callers.
     */
    public static Duration budgetFor(OperationKind kind) {
        return DEFAULT_BUDGET;
    }

    public static Builder builder(UUID id, UUID groupId, Long cloudAccountId, OperationKind kind) {
        return new Builder(id, groupId, cloudAccountId, kind);
    }

    public static final class Builder {
        private final UUID id;
        private final UUID groupId;
        private final Long cloudAccountId;
        private final OperationKind kind;
        private UUID planId;
        private GroupState priorState;
        private PauseReason priorPauseReason;
        private CompensationTarget compensationTarget;
        private String compensationConfigJson;
        private String targetConfigJson;
        private String idempotencyKey;
        private String requestSha256;
        private String requestedBy = "system";
        private boolean dryRun;
        private Instant deadlineAt;

        private Builder(UUID id, UUID groupId, Long cloudAccountId, OperationKind kind) {
            this.id = id;
            this.groupId = groupId;
            this.cloudAccountId = cloudAccountId;
            this.kind = kind;
        }

        public Builder planId(UUID v) { this.planId = v; return this; }
        public Builder priorState(GroupState v) { this.priorState = v; return this; }
        public Builder priorPauseReason(PauseReason v) { this.priorPauseReason = v; return this; }
        public Builder compensationTarget(CompensationTarget v) { this.compensationTarget = v; return this; }
        public Builder compensationConfigJson(String v) { this.compensationConfigJson = v; return this; }
        public Builder targetConfigJson(String v) { this.targetConfigJson = v; return this; }
        public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
        public Builder requestSha256(String v) { this.requestSha256 = v; return this; }
        public Builder requestedBy(String v) { this.requestedBy = v; return this; }
        public Builder dryRun(boolean v) { this.dryRun = v; return this; }
        public Builder deadlineAt(Instant v) { this.deadlineAt = v; return this; }

        public OperationRecord build() {
            if (id == null || groupId == null || cloudAccountId == null || kind == null) {
                throw new IllegalArgumentException("id, groupId, cloudAccountId and kind are required");
            }
            if (priorState == null) {
                throw new IllegalArgumentException("priorState is required");
            }
            if (compensationTarget == null) {
                throw new IllegalArgumentException("compensationTarget is required");
            }
            Instant deadline = deadlineAt != null ? deadlineAt : Instant.now().plus(budgetFor(kind));
            return new OperationRecord(id, groupId, cloudAccountId, planId, kind, priorState,
                    priorPauseReason, compensationTarget, compensationConfigJson, targetConfigJson,
                    idempotencyKey, requestSha256, requestedBy, dryRun, deadline);
        }
    }
}
