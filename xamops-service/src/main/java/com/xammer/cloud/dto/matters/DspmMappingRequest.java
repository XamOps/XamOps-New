package com.xammer.cloud.dto.matters;

import java.time.Instant;

/**
 * Operator input for creating or updating a client's Matters project mapping.
 *
 * @param mattersProjectId   required. The project this client's data comes from. Rejected if another
 *                           client already holds it — two clients on one project is the one way this
 *                           integration can leak data across tenants.
 * @param mattersProjectName optional label, so the admin list is readable without matching hex ids.
 * @param mattersAccountId   optional. The Matters account the project sits under.
 * @param baseUrl            required. Tenant host, e.g. {@code https://app.matters.ai}.
 * @param apiKey             <b>write-only.</b> Required on create. On update, <b>blank or null means
 *                           "keep the existing key"</b> — the admin form never renders a stored key
 *                           back, so there is nothing for the browser to resubmit, and treating an
 *                           empty field as "clear the credential" would wipe a working connection
 *                           every time someone corrected a typo in the project name.
 * @param apiKeyExpiresAt    optional. Matters caps key lifetime at 180 days; recording the expiry is
 *                           what lets the admin list warn before a customer notices.
 * @param enabled            operational switch, independent of the client's add-on entitlement.
 */
public record DspmMappingRequest(
        String mattersProjectId,
        String mattersProjectName,
        String mattersAccountId,
        String baseUrl,
        String apiKey,
        Instant apiKeyExpiresAt,
        Boolean enabled
) {

    /** True when the operator supplied a new key, as opposed to leaving the field untouched. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isEnabledOrDefault() {
        return enabled == null || enabled;
    }
}
