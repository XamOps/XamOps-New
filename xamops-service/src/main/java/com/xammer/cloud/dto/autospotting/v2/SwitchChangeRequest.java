package com.xammer.cloud.dto.autospotting.v2;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of the operator {@code PUT /switches/{scopeKey}} (design-core.md §6.6): engage or release
 * a scope with a required reason and the {@code version} read from {@code GET /switches} for the
 * optimistic guard. The {@code reason} is 1 to 500 characters (the switch {@code reason} column
 * width, §6.1); a missing or oversized reason is 422 (AC-SEC-02).
 *
 * @param engaged         the new DB override
 * @param reason          why, 1 to 500 characters
 * @param expectedVersion the {@code version} the operator last read; guards against racing operators
 */
public record SwitchChangeRequest(
        @NotNull Boolean engaged,
        @NotNull @Size(min = 1, max = 500) String reason,
        @NotNull Long expectedVersion) {
}
