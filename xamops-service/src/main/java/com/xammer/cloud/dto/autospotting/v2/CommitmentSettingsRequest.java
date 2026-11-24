package com.xammer.cloud.dto.autospotting.v2;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/autospotting/v2/accounts/{accountId}/commitments/settings/risp-respect}
 * (plan-phase-14.md task 14.1). Both fields are required so a save is always an explicit, complete value.
 */
public record CommitmentSettingsRequest(
        @NotNull Boolean respectRiSpCommitments,
        @NotNull @Min(0) @Max(100) Integer riSpUtilizationThreshold) {
}
