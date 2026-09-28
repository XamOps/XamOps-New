package com.xammer.cloud.dto.matters;

import com.xammer.cloud.domain.MattersAccessAudit;

import java.time.Instant;

/**
 * One audit row for the admin viewer.
 *
 * <p>A near-passthrough of the entity, with {@code clientName} joined in so the viewer is readable, and
 * with nothing removed — the entity never held a credential in the first place, only the non-secret
 * {@link #apiKeyId} segment.
 *
 * @param actorType    {@code CUSTOMER}, {@code OPERATOR} or {@code SYSTEM}. The field to scan first: a
 *                     customer reading their own inventory is routine, an operator reading it is the
 *                     event worth looking at.
 * @param impersonated whether the read happened through a super-admin impersonated session.
 */
public record DspmAuditView(
        Long id,
        Long clientId,
        String clientName,
        Long actorUserId,
        String actorUsername,
        String actorRole,
        String actorType,
        boolean impersonated,
        String action,
        String apiKeyId,
        String mattersProjectId,
        String endpoint,
        String outcome,
        String failureReason,
        String detail,
        String ipAddress,
        String userAgent,
        Integer durationMs,
        Instant createdAt
) {

    public static DspmAuditView from(MattersAccessAudit row, String clientName) {
        return new DspmAuditView(
                row.getId(),
                row.getClientId(),
                clientName,
                row.getActorUserId(),
                row.getActorUsername(),
                row.getActorRole(),
                row.getActorType(),
                row.isImpersonated(),
                row.getAction(),
                row.getApiKeyId(),
                row.getMattersProjectId(),
                row.getEndpoint(),
                row.getOutcome(),
                row.getFailureReason(),
                row.getDetail(),
                row.getIpAddress(),
                row.getUserAgent(),
                row.getDurationMs(),
                row.getCreatedAt());
    }
}
