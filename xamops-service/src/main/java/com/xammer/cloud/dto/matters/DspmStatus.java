package com.xammer.cloud.dto.matters;

import java.time.Instant;

/**
 * Connection state for the customer-facing DSPM surface.
 *
 * <p>Exists so the page can render each failure as its own explanation instead of one generic error.
 * The states are genuinely different and need different words and different audiences:
 *
 * <ul>
 *   <li><b>not entitled</b> — offer the Request access action.</li>
 *   <li><b>entitled, not yet mapped</b> — "being set up". The normal gap between a contract being
 *       signed and an operator creating the project in Matters. Not an error.</li>
 *   <li><b>key expired or revoked</b> — needs an operator, not the customer. Every key is on a
 *       180-day clock, so this is the expected end of every key's life rather than an exception.</li>
 *   <li><b>key lacks the PLATFORM scope</b> — also an operator fix, and a different one.</li>
 *   <li><b>credential will not decrypt</b> — an XamOps deployment fault. Must never be shown as
 *       "being set up", which would hide it indefinitely.</li>
 *   <li><b>Matters unreachable</b> — transient; invite a retry.</li>
 * </ul>
 *
 * @param ready      true when reads will work. The only field the happy path needs.
 * @param entitled   whether the org holds the add-on. Distinct from {@link #configured}: a client can be
 *                   entitled with no mapping yet.
 * @param configured whether a mapping with a credential exists.
 * @param reason     {@code MattersApiException.Reason} name when {@link #ready} is false, else null.
 *                   The machine-readable code is the contract the frontend branches on — branching on
 *                   {@link #message} would break the first time someone improved the wording.
 * @param message    human-readable explanation, safe to show a customer.
 * @param projectName the mapped project's display name, or null. Deliberately <b>not</b> the project id:
 *                    the id is an internal vendor identifier a customer has no use for, and this view is
 *                    customer-visible.
 */
public record DspmStatus(
        boolean ready,
        boolean entitled,
        boolean configured,
        String reason,
        String message,
        String projectName,
        Instant lastVerifiedAt
) {

    public static DspmStatus ready(String projectName, Instant lastVerifiedAt) {
        return new DspmStatus(true, true, true, null, null, projectName, lastVerifiedAt);
    }
}
