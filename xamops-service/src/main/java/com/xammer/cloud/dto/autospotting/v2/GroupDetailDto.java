package com.xammer.cloud.dto.autospotting.v2;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The group detail: the row, its state transitions and the current operation, if any (design-core.md
 * §11). A DTO, never an entity (FR-SEC-02). Transitions are in order and carry the stable reason
 * code, never a raw provider message. {@code preemptions7d} is the GCP preemption count of the last 7 days from
 * {@code spot_group_event}, null when the cloud pushes its own events (AWS) or nothing is tracked yet.
 */
public record GroupDetailDto(
        GroupRowDto group,
        String configMode,
        String driftStatus,
        String lastErrorCode,
        List<Transition> transitions,
        OperationDto currentOperation,
        Long preemptions7d) {
    public GroupDetailDto {
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }
    /** A detail with no preemption figure (AWS, or a group with nothing tracked). */
    public GroupDetailDto(GroupRowDto group, String configMode, String driftStatus, String lastErrorCode,
                          List<Transition> transitions, OperationDto currentOperation) {
        this(group, configMode, driftStatus, lastErrorCode, transitions, currentOperation, null);
    }

    /** One recorded state transition. */
    public record Transition(String fromState, String toState, String reasonCode, String actorType,
                             UUID operationId, Instant occurredAt) {
    }
}
