package com.xammer.cloud.dto.autospotting.v2;

import com.xammer.cloud.domain.autospotting.SpotPlan;

import java.time.Instant;
import java.util.UUID;

/**
 * The plan returned by {@code POST /plans} (201) and {@code GET /plans/{planId}} (design-core.md
 * §7.1, §7.3; plan-phase-11.md task 11.5). A DTO over {@link SpotPlan}, never the entity (FR-SEC-02,
 * AC-SEC-04). {@code body}, {@code warningIds} and {@code feeBasis} are the plan's JSON documents as
 * stored (already safe text — {@code createPlan} never puts a raw provider message in them).
 *
 * @param planId        the plan id
 * @param groupId       the group the plan is for
 * @param kind          the plan kind, as text
 * @param status        OPEN | CONFIRMED | EXPIRED | SUPERSEDED | STALE, as text
 * @param version       the plan version the client echoes on confirm
 * @param liveFingerprint the managed-field hash the confirm re-read compares (§7.4 step 8)
 * @param body          the PlanBody JSON (current/target/diff, eligibility, rollout, estimates, ...)
 * @param requiresFullSpotOptin whether confirm needs {@code fullSpotOptIn} (§7.4 step 6)
 * @param warningIds    the ack ids confirm must echo (§7.4 step 5)
 * @param dryRun        whether a confirm produces a dry-run op
 * @param expiresAt     the 15-minute plan expiry ({@code autospotting.plan.ttl})
 * @param createdAt     when the plan was created
 * @param batchId       the "Revert all" batch id, or null (§7.5)
 */
public record PlanDto(
        UUID planId,
        UUID groupId,
        String kind,
        String status,
        int version,
        String liveFingerprint,
        String body,
        boolean requiresFullSpotOptin,
        String warningIds,
        boolean dryRun,
        Instant expiresAt,
        Instant createdAt,
        UUID batchId) {

    /** Project a {@link SpotPlan} entity to the DTO (in the controller, never the entity out). */
    public static PlanDto from(SpotPlan plan) {
        return new PlanDto(
                plan.getId(),
                plan.getGroupId(),
                plan.getKind() == null ? null : plan.getKind().name(),
                plan.getStatus() == null ? null : plan.getStatus().name(),
                plan.getVersion(),
                plan.getLiveFingerprint(),
                plan.getBody(),
                plan.isRequiresFullSpotOptin(),
                plan.getWarningIds(),
                plan.isDryRun(),
                plan.getExpiresAt(),
                plan.getCreatedAt(),
                plan.getBatchId());
    }
}
