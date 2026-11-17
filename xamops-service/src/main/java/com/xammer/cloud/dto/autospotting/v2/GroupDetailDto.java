package com.xammer.cloud.dto.autospotting.v2;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The group detail: the row, its state transitions and the current operation, if any (design-core.md
 * §11). A DTO, never an entity (FR-SEC-02). Transitions are in order and carry the stable reason
 * code, never a raw provider message.
 */
public record GroupDetailDto(
        GroupRowDto group,
        String configMode,
        String driftStatus,
        String lastErrorCode,
        List<Transition> transitions,
        OperationDto currentOperation) {

    public GroupDetailDto {
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }

    /** One recorded state transition. */
    public record Transition(String fromState, String toState, String reasonCode, String actorType,
                             UUID operationId, Instant occurredAt) {
    }
}
