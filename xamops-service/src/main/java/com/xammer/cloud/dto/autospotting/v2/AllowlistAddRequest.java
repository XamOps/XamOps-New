package com.xammer.cloud.dto.autospotting.v2;

/**
 * {@code POST /allowlist} body (design-core.md §6.1, §6.6). Validated in
 * {@code AllowlistOperatorService}, because a missing basis or a {@code NO_FEE} without a reason is a
 * 409 {@code ALLOWLIST_NEEDS_BILLING_BASIS}, not a field error.
 */
public record AllowlistAddRequest(Long cloudAccountId, String billingBasis, String reason) {
}
