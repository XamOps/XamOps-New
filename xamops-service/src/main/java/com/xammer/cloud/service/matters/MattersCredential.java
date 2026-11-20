package com.xammer.cloud.service.matters;

/**
 * A resolved, validated credential for one XamOps client's Matters project.
 *
 * Produced only by {@link MattersCredentialResolver}, which is the single place a client id is turned
 * into a usable key. Passing this record around rather than the {@code MattersProjectMapping} entity
 * means the HTTP client never sees the JPA entity, cannot lazily load a {@code Client}, and cannot
 * accidentally persist anything.
 *
 * <p>Note what this carries and what it does not: {@link #apiKey} is what actually scopes every response,
 * verified against the live service. The project id labels caches and audit rows, and only the four
 * Data Lineage exposure routes ({@code /api/v1/exposure/…}) send it, as the {@code project-id} header
 * they require; no other route takes a project selector.
 *
 * @param clientId  XamOps client this credential belongs to. Used for cache keys and audit rows.
 * @param baseUrl   tenant host, without a trailing slash.
 * @param apiKey    the full {@code X-API-Key} value. Never log this.
 * @param keyId     non-secret key-id segment, for the audit trail.
 * @param projectId the Matters project the key resolves to. Sent only as the exposure routes'
 *                  {@code project-id} header.
 */
public record MattersCredential(Long clientId, String baseUrl, String apiKey,
                                String keyId, String projectId) {

    /**
     * Redacted, for the same reason as {@link MattersApiKey#toString()} — the generated record
     * {@code toString} would print {@link #apiKey} in full into any log line that interpolated it.
     */
    @Override
    public String toString() {
        return "MattersCredential[clientId=" + clientId + ", baseUrl=" + baseUrl
                + ", keyId=" + keyId + ", projectId=" + projectId + ", apiKey=****]";
    }
}
