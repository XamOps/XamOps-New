package com.xammer.cloud.dto.autospotting.v2;

import com.xammer.cloud.domain.autospotting.AutoSpottingAudit;

import java.time.Instant;
import java.util.UUID;

/**
 * One Activity row (plan-phase-14.md task 14.2): a customer-safe projection of an
 * {@code autospotting_audit} row. It carries what happened and the outcome, not the user ids or the
 * redacted before and after documents (AC-SEC-04). A DTO, never the entity.
 */
public record AuditEntryDto(Long id, Instant occurredAt, String eventType, String actorType, String actorRole,
                            UUID operationId, UUID planId, UUID groupId, String groupKey, String action,
                            String outcome, String errorCode) {

    public static AuditEntryDto from(AutoSpottingAudit a) {
        return new AuditEntryDto(a.getId(), a.getOccurredAt(), a.getEventType(), a.getActorType(),
                a.getActorRole(), a.getOperationId(), a.getPlanId(), a.getGroupId(), a.getGroupKey(),
                a.getAction(), a.getOutcome(), a.getErrorCode());
    }
}
