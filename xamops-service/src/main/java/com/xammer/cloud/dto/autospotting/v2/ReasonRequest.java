package com.xammer.cloud.dto.autospotting.v2;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of the operator pause/resume POSTs (design-core.md §6.6): a required {@code reason} of
 * 1 to 500 characters (the switch and allowlist {@code reason} width, §6.1). A missing or oversized
 * reason is 422 with a field error (AC-SEC-02).
 *
 * @param reason why the operator paused or resumed the group
 */
public record ReasonRequest(
        @NotNull @Size(min = 1, max = 500) String reason) {
}
