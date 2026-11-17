package com.xammer.cloud.dto.autospotting.v2;

/**
 * The RI/SP respect setting of an account (plan-phase-14.md task 14.1, NO-06): the same two stored
 * values the legacy {@code GET /api/autospotting/settings/{accountId}} returns for the Commitments
 * view. A DTO, never an entity (AC-SEC-04).
 *
 * @param respectRiSpCommitments whether AutoSpotting leaves instances covered by an RI or Savings Plan alone
 * @param riSpUtilizationThreshold utilization percent (0 to 100) below which a commitment no longer counts as covering
 */
public record CommitmentSettingsDto(boolean respectRiSpCommitments, int riSpUtilizationThreshold) {
}
