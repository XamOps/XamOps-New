package com.xammer.cloud.service.matters;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.MattersProjectMapping;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.MattersProjectMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Turns a XamOps client id into a usable Matters credential — and is the entire tenancy boundary for
 * the DSPM add-on.
 *
 * <h2>Why this is the whole boundary</h2>
 * The Upwind integration establishes tenancy <em>after</em> fetching: one shared credential reads the
 * whole estate and {@code XamSecureService.resolveAccountIds} filters the results to the cloud
 * accounts a client owns. Matters cannot work that way. Its key is issued against one account and one
 * project, and there is no header or parameter that names either — verified against the live API on
 * 2026-09-25, where every candidate selector, including a deliberately nonexistent project id,
 * returned byte-identical results.
 *
 * So there is no post-fetch filter to apply here, and no need for one: selecting the right row is
 * selecting the right data. The corollary is that this class is the only place that mapping happens,
 * and it must never accept a client id from anywhere but the authenticated principal. Every caller
 * passes {@code ClientUserDetails.getClientId()}; none passes a request parameter.
 *
 * <h2>Order of checks</h2>
 * Entitlement is checked first, before the mapping is even looked up. An unentitled caller should not
 * be able to learn whether a project has been provisioned for them, and should never cause an
 * outbound request.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MattersCredentialResolver {

    private final MattersProjectMappingRepository mappingRepository;
    private final ClientRepository clientRepository;

    /**
     * Resolves the credential for a client, or throws a {@link MattersApiException} whose
     * {@link MattersApiException.Reason} says precisely what is wrong.
     *
     * The reasons are not interchangeable — see the enum. In particular "no key stored" and "key will
     * not decrypt" are different states with opposite remedies, and they are only distinguishable
     * because {@code api_key_last_four} is stored in the clear as a presence sentinel.
     *
     * @param clientId from the authenticated principal. Never from a request parameter.
     */
    @Transactional(readOnly = true)
    public MattersCredential resolve(Long clientId) {
        if (clientId == null) {
            // Matches the convention used elsewhere for a principal with no client context: this is a
            // 403, not an NPE and not an empty result.
            throw new MattersApiException(MattersApiException.Reason.NOT_ENTITLED,
                    "No client context on the request");
        }

        requireEntitled(clientId);

        MattersProjectMapping mapping = mappingRepository.findByClientId(clientId)
                .orElseThrow(() -> new MattersApiException(MattersApiException.Reason.NOT_CONFIGURED,
                        "No Matters project has been mapped to this organisation yet"));

        if (!mapping.isEnabled()) {
            throw new MattersApiException(MattersApiException.Reason.DISABLED,
                    "The Matters connection for this organisation is currently disabled");
        }

        // Before "is there a key": a stored-but-undecryptable key is an XamOps deployment fault, and
        // reporting it as "not configured" would hide it behind a customer-facing setup message
        // forever.
        if (mapping.isDecryptionFailed()) {
            log.error("[Matters] credential for client {} did not decrypt — check that "
                    + "app.encryption.secret-key matches the key this row was written with "
                    + "(keyId={}, lastFour={})",
                    clientId, mapping.getApiKeyId(), mapping.getApiKeyLastFour());
            throw new MattersApiException(MattersApiException.Reason.DECRYPT_FAILED,
                    "The stored Matters credential could not be decrypted");
        }

        String apiKey = mapping.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new MattersApiException(MattersApiException.Reason.NOT_CONFIGURED,
                    "No Matters API key has been recorded for this organisation yet");
        }

        // Closes the EncryptedStringConverter passthrough: it returns values lacking the 'enc:v1:'
        // prefix unchanged, as a migration affordance for legacy plaintext. This column has none, so
        // anything unprefixed got there by a route that bypassed encryption and should not be used.
        MattersApiKey parsed = MattersApiKey.parse(apiKey)
                .orElseThrow(() -> {
                    log.error("[Matters] stored credential for client {} is not a well-formed "
                            + "Matters key", clientId);
                    return new MattersApiException(MattersApiException.Reason.MALFORMED_KEY,
                            "The stored Matters credential is not a valid API key");
                });

        // Checked locally so the common end-of-life case reports as "rotate the key" rather than as a
        // generic 401. Every key is on a clock: Matters caps lifetime at 180 days.
        if (mapping.isKeyExpired()) {
            throw new MattersApiException(MattersApiException.Reason.KEY_EXPIRED,
                    "The Matters API key for this organisation expired on " + mapping.getApiKeyExpiresAt());
        }

        return new MattersCredential(
                clientId,
                normaliseBaseUrl(mapping.getBaseUrl()),
                apiKey,
                parsed.keyId(),
                mapping.getMattersProjectId());
    }

    /**
     * Resolves without throwing, for the status endpoint.
     *
     * The customer-facing status view needs to render each failure as its own explanatory state, so it
     * wants the reason as a value rather than as control flow.
     */
    @Transactional(readOnly = true)
    public Optional<MattersApiException.Reason> describeProblem(Long clientId) {
        try {
            resolve(clientId);
            return Optional.empty();
        } catch (MattersApiException e) {
            return Optional.of(e.getReason());
        }
    }

    /** True when the client holds the DSPM add-on. */
    @Transactional(readOnly = true)
    public boolean isEntitled(Long clientId) {
        return clientId != null
                && clientRepository.findById(clientId)
                        .map(Client::isMattersDspmEnabled)
                        .orElse(false);
    }

    /**
     * Enforces the add-on entitlement.
     *
     * A missing client row reads as <em>not</em> entitled — the opposite of
     * {@code UpwindOnboardingService.isAllowedForClient}, which treats a lookup failure as allowed to
     * match a column that defaults TRUE. This column defaults FALSE, so failing closed is what
     * matches the default, and for a paid add-on that is also the safer direction.
     */
    private void requireEntitled(Long clientId) {
        if (!isEntitled(clientId)) {
            throw new MattersApiException(MattersApiException.Reason.NOT_ENTITLED,
                    "This organisation does not have the DSPM add-on enabled");
        }
    }

    /**
     * Strips a trailing slash so callers can concatenate paths without producing a double slash.
     *
     * Matters tolerates {@code //api/v1/assets} today, but relying on that is how a working
     * integration breaks on a vendor's routing change.
     */
    private static String normaliseBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
