package com.xammer.cloud.dto.matters;

import com.xammer.cloud.domain.MattersProjectMapping;

import java.time.Duration;
import java.time.Instant;

/**
 * One mapping as the DSPM admin page sees it.
 *
 * <p><b>Contains no credential.</b> Not the key, and not a reversible fragment of one — only
 * {@link #apiKeyEnv}, {@link #apiKeyId} and {@link #apiKeyLastFour}, which together identify
 * <em>which</em> key is in use without being usable. That is the whole design of the admin surface:
 * an operator needs to confirm they configured the key they meant to and to find it in Matters'
 * console for rotation, neither of which requires reading it back.
 *
 * @param entitled          whether the client holds the add-on. Separate from {@link #enabled}: a
 *                          mapping can be working while the entitlement is withdrawn, and vice versa,
 *                          and an operator looking at an empty customer page needs to tell which.
 * @param credentialStored  whether a key was ever stored, from the plaintext last-four sentinel. With
 *                          {@link #connectionStatus} {@code DECRYPT_FAILED} this is what distinguishes
 *                          "never configured" from "configured but unreadable".
 * @param daysUntilExpiry   null when no expiry was recorded. Negative once past.
 * @param expiryState       {@link #EXPIRY_NONE}, {@link #EXPIRY_OK}, {@link #EXPIRY_WARNING} or
 *                          {@link #EXPIRY_EXPIRED}. Precomputed so the admin table can sort and colour
 *                          on it without every client re-deriving the threshold.
 */
public record DspmMappingAdminView(
        Long id,
        Long clientId,
        String clientName,
        String mattersProjectId,
        String mattersProjectName,
        String mattersAccountId,
        String baseUrl,
        String apiKeyEnv,
        String apiKeyId,
        String apiKeyLastFour,
        Instant apiKeyExpiresAt,
        Long daysUntilExpiry,
        String expiryState,
        boolean enabled,
        boolean entitled,
        boolean credentialStored,
        String connectionStatus,
        Instant lastVerifiedAt,
        String lastError,
        Instant lastErrorAt,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {

    public static final String EXPIRY_NONE = "NONE";
    public static final String EXPIRY_OK = "OK";
    public static final String EXPIRY_WARNING = "WARNING";
    public static final String EXPIRY_EXPIRED = "EXPIRED";

    /**
     * How long before expiry an operator should be warned.
     *
     * Fourteen days because rotation is not instant: someone has to mint a new key in Matters' console
     * and record it here, and with one key per customer these arrive continuously — at fifty DSPM
     * customers a key expires every three days on average. A shorter window would routinely be missed.
     */
    public static final int EXPIRY_WARNING_DAYS = 14;

    public static DspmMappingAdminView from(MattersProjectMapping mapping, boolean entitled) {
        Instant expiresAt = mapping.getApiKeyExpiresAt();

        Long days = null;
        String state = EXPIRY_NONE;
        if (expiresAt != null) {
            // Truncated toward zero, so "expires in 20 hours" reports 0 days rather than 1. Under-
            // reporting is the safe direction for a warning.
            days = Duration.between(Instant.now(), expiresAt).toDays();
            if (days < 0) {
                state = EXPIRY_EXPIRED;
            } else if (days <= EXPIRY_WARNING_DAYS) {
                state = EXPIRY_WARNING;
            } else {
                state = EXPIRY_OK;
            }
        }

        return new DspmMappingAdminView(
                mapping.getId(),
                mapping.getClient() == null ? null : mapping.getClient().getId(),
                mapping.getClient() == null ? null : mapping.getClient().getName(),
                mapping.getMattersProjectId(),
                mapping.getMattersProjectName(),
                mapping.getMattersAccountId(),
                mapping.getBaseUrl(),
                mapping.getApiKeyEnv(),
                mapping.getApiKeyId(),
                mapping.getApiKeyLastFour(),
                expiresAt,
                days,
                state,
                mapping.isEnabled(),
                entitled,
                mapping.isCredentialStored(),
                mapping.getConnectionStatus(),
                mapping.getLastVerifiedAt(),
                mapping.getLastError(),
                mapping.getLastErrorAt(),
                mapping.getUpdatedBy(),
                mapping.getCreatedAt(),
                mapping.getUpdatedAt());
    }
}
