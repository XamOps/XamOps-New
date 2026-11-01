package com.xammer.cloud.dto.autospotting.v2;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The body of {@code POST /api/autospotting/v2/accounts/{accountId}/plans/{planId}/confirm}
 * (design-core.md §7.4; plan-phase-11.md task 11.5). An {@code Idempotency-Key} header is required on
 * the POST (checked by the controller, §3.7).
 *
 * @param planVersion            the plan version the client read; a mismatch is 409
 *                               {@code PLAN_VERSION_MISMATCH} (§7.4 step 4)
 * @param acknowledgedWarningIds the warning ids the client acknowledges; every warning must be echoed
 *                               or confirm is 422 {@code ACK_REQUIRED} (§7.4 step 5)
 * @param fullSpotOptIn          the 100%-Spot opt-in for a PROGRESS plan (§7.4 step 6)
 * @param reason                 required when a platform admin (or an impersonated/enable confirm in
 *                               PO14_RECOMMENDED) confirms (§11, PO-14); otherwise optional
 */
public record ConfirmRequest(
        @NotNull Integer planVersion,
        List<@Size(max = 128) String> acknowledgedWarningIds,
        boolean fullSpotOptIn,
        @Size(max = 500) String reason) {

    public ConfirmRequest {
        acknowledgedWarningIds = acknowledgedWarningIds == null ? List.of() : List.copyOf(acknowledgedWarningIds);
    }
}
