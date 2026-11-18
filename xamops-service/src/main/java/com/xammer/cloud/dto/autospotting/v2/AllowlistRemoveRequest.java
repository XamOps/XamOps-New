package com.xammer.cloud.dto.autospotting.v2;

/** {@code DELETE /allowlist/{cloudAccountId}} body: why the account leaves the allowlist (1 to 500 characters). */
public record AllowlistRemoveRequest(String reason) {
}
