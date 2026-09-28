package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.MattersAccessAudit;
import com.xammer.cloud.domain.MattersProjectMapping;
import com.xammer.cloud.dto.matters.DspmClientOption;
import com.xammer.cloud.dto.matters.DspmMappingAdminView;
import com.xammer.cloud.dto.matters.DspmMappingRequest;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.MattersProjectMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Operator-side management of the DSPM add-on: the org → project mappings, the credentials, and the
 * entitlement.
 *
 * <p>Separate from {@link MattersDspmService}, which only reads. Writes here are rare, privileged, and
 * every one of them is audited — a different enough shape to keep apart from a read path that runs on
 * every page load.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MattersAdminService {

    private final MattersProjectMappingRepository mappingRepository;
    private final ClientRepository clientRepository;
    private final MattersApiClient apiClient;
    private final MattersAuditService auditService;
    private final MattersDspmService dspmService;

    // ── read ─────────────────────────────────────────────────────────────────────────────────────

    /** Every mapping, for the admin table. Joins the client eagerly to avoid a query per row. */
    @Transactional(readOnly = true)
    public List<DspmMappingAdminView> listMappings() {
        List<DspmMappingAdminView> views = new ArrayList<>();
        for (MattersProjectMapping mapping : mappingRepository.findAllWithClient()) {
            Client client = mapping.getClient();
            views.add(DspmMappingAdminView.from(mapping, client != null && client.isMattersDspmEnabled()));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public Optional<DspmMappingAdminView> getMapping(Long clientId) {
        return mappingRepository.findByClientId(clientId)
                .map(mapping -> DspmMappingAdminView.from(
                        mapping, mapping.getClient() != null && mapping.getClient().isMattersDspmEnabled()));
    }

    /**
     * Every client, for the admin picker — id, name, entitlement and whether a mapping exists.
     *
     * Two queries, deliberately, rather than reusing the Accounts screen's client list, which fans out
     * to Cost Explorer plus the GCP and Azure cost APIs to build each row. Populating a dropdown should
     * not touch a billing API.
     *
     * Sorted by name so the picker is scannable; clients already mapped are flagged rather than removed,
     * so an operator can reach an existing mapping from the same control.
     */
    @Transactional(readOnly = true)
    public List<DspmClientOption> listClientOptions() {
        Set<Long> mapped = mappingRepository.findAll().stream()
                .map(mapping -> mapping.getClient() == null ? null : mapping.getClient().getId())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        return clientRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(
                        client -> client.getName() == null ? "" : client.getName().toLowerCase()))
                .map(client -> new DspmClientOption(
                        client.getId(),
                        client.getName(),
                        client.isMattersDspmEnabled(),
                        mapped.contains(client.getId())))
                .toList();
    }

    // ── create / update ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates or replaces a client's mapping.
     *
     * <p>Upsert rather than separate create and update endpoints, because there is at most one mapping
     * per client — a unique constraint enforces it — so "create" and "update" are the same operation
     * seen from different starting points, and splitting them would only invite a 409 on a retried save.
     *
     * <p>Three transitions are audited distinctly rather than all as a generic update, because they are
     * the ones with security consequences:
     * <ul>
     *   <li>{@code KEY_ROTATED} — the stored credential changed.</li>
     *   <li>{@code PROJECT_REPOINTED} — the customer's entire dataset changed. This is the shape of a
     *       cross-tenant misconfiguration and must be legible after the fact.</li>
     *   <li>{@code MAPPING_CREATED} / {@code MAPPING_UPDATED} — everything else.</li>
     * </ul>
     */
    @Transactional
    public DspmMappingAdminView saveMapping(Long clientId, DspmMappingRequest request, String operator) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Client not found: " + clientId));

        String projectId = required(request.mattersProjectId(), "mattersProjectId");
        String baseUrl = required(request.baseUrl(), "baseUrl");

        // The leak guard. The unique index is the real enforcement; this turns a constraint-violation
        // stack trace into a message that names the conflicting customer.
        mappingRepository.findByMattersProjectId(projectId).ifPresent(existing -> {
            if (!existing.getClient().getId().equals(clientId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Matters project " + projectId + " is already mapped to client "
                                + existing.getClient().getId() + " (" + existing.getClient().getName()
                                + "). One project cannot serve two customers.");
            }
        });

        Optional<MattersProjectMapping> existing = mappingRepository.findByClientId(clientId);
        boolean isNew = existing.isEmpty();
        MattersProjectMapping mapping = existing.orElseGet(() -> {
            MattersProjectMapping fresh = new MattersProjectMapping();
            fresh.setClient(client);
            fresh.setCreatedAt(Instant.now());
            return fresh;
        });

        String previousProjectId = mapping.getMattersProjectId();
        boolean repointed = !isNew && previousProjectId != null && !previousProjectId.equals(projectId);

        mapping.setMattersProjectId(projectId);
        mapping.setMattersProjectName(blankToNull(request.mattersProjectName()));
        mapping.setMattersAccountId(blankToNull(request.mattersAccountId()));
        mapping.setBaseUrl(baseUrl);
        mapping.setEnabled(request.isEnabledOrDefault());
        mapping.setApiKeyExpiresAt(request.apiKeyExpiresAt());
        mapping.setUpdatedBy(operator);
        mapping.setUpdatedAt(Instant.now());

        boolean rotated = false;
        if (request.hasApiKey()) {
            applyKey(mapping, request.apiKey().trim(), baseUrl);
            rotated = !isNew;
        } else if (isNew) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An API key is required when creating a mapping");
        }

        // Any change to the credential, the host or the project invalidates the previous verdict.
        // Leaving a stale CONNECTED next to a freshly pasted key is how a broken mapping looks healthy.
        if (rotated || repointed || isNew) {
            mapping.setConnectionStatus(MattersProjectMapping.STATUS_UNVERIFIED);
            mapping.setLastVerifiedAt(null);
            mapping.setLastError(null);
            mapping.setLastErrorAt(null);
        }

        MattersProjectMapping saved = mappingRepository.save(mapping);

        // Drop cached responses before returning. Repointing a live customer without this would keep
        // serving the previous project's assets until each TTL lapsed — on a data-security page that
        // means showing one customer another customer's inventory.
        dspmService.evictClient(clientId);

        if (isNew) {
            auditService.recordMutation(clientId, MattersAccessAudit.ACTION_MAPPING_CREATED, projectId,
                    "Mapped to project " + projectId + " at " + baseUrl);
        } else {
            if (repointed) {
                auditService.recordMutation(clientId, MattersAccessAudit.ACTION_PROJECT_REPOINTED,
                        projectId, "Repointed from " + previousProjectId + " to " + projectId);
            }
            if (rotated) {
                auditService.recordMutation(clientId, MattersAccessAudit.ACTION_KEY_ROTATED, projectId,
                        "API key replaced (keyId " + saved.getApiKeyId() + ")");
            }
            if (!repointed && !rotated) {
                auditService.recordMutation(clientId, MattersAccessAudit.ACTION_MAPPING_UPDATED,
                        projectId, "Mapping details updated");
            }
        }

        return DspmMappingAdminView.from(saved, client.isMattersDspmEnabled());
    }

    /**
     * Validates and stores a new key, deriving the non-secret metadata from it.
     *
     * The environment check catches the most likely paste error: a key from one Matters region against
     * another region's host. The vendor answers that with a distinct 401, and the fix is to correct the
     * URL rather than rotate the key — so it is worth failing here, in front of the operator who just
     * pasted it, rather than later as an apparently-invalid credential.
     */
    private void applyKey(MattersProjectMapping mapping, String rawKey, String baseUrl) {
        MattersApiKey parsed = MattersApiKey.parse(rawKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That does not look like a Matters API key. Expected the form "
                                + "mts_<env>_<key_id>_<secret>."));

        if (!parsed.matchesBaseUrl(baseUrl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The key is for the '" + parsed.env() + "' environment but the base URL points "
                            + "somewhere else. Matters rejects mismatched environments.");
        }

        // Encrypted on write by EncryptedStringConverter; the metadata below is stored in the clear so
        // the admin UI and the audit trail never need to decrypt.
        mapping.setApiKey(rawKey);
        mapping.setApiKeyEnv(parsed.env());
        mapping.setApiKeyId(parsed.keyId());
        mapping.setApiKeyLastFour(parsed.lastFour());
    }

    // ── delete ───────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public void deleteMapping(Long clientId) {
        MattersProjectMapping mapping = mappingRepository.findByClientId(clientId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No mapping for client " + clientId));
        String projectId = mapping.getMattersProjectId();

        mappingRepository.delete(mapping);
        dspmService.evictClient(clientId);

        auditService.recordMutation(clientId, MattersAccessAudit.ACTION_MAPPING_DELETED, projectId,
                "Mapping removed");
    }

    // ── verify ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Calls Matters with the stored credential and records the verdict.
     *
     * Not optional in practice. The key field is write-only, so a mistyped or truncated paste is
     * otherwise invisible until a customer reports an empty page — this is what makes a bad key fail in
     * front of the operator who entered it.
     *
     * Runs outside the credential resolver's entitlement gate on purpose: an operator must be able to
     * verify a connection before granting the add-on, and after withdrawing it.
     */
    @Transactional
    public DspmMappingAdminView verifyMapping(Long clientId) {
        MattersProjectMapping mapping = mappingRepository.findByClientId(clientId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No mapping for client " + clientId));

        if (mapping.isDecryptionFailed()) {
            return recordVerifyFailure(mapping, MattersApiException.Reason.DECRYPT_FAILED,
                    "The stored key could not be decrypted. Check that app.encryption.secret-key "
                            + "matches the key this row was written with — the Matters key itself is "
                            + "probably fine.");
        }
        if (!mapping.isCredentialStored() || mapping.getApiKey() == null) {
            return recordVerifyFailure(mapping, MattersApiException.Reason.NOT_CONFIGURED,
                    "No API key has been stored for this mapping.");
        }

        MattersApiKey parsed = MattersApiKey.parse(mapping.getApiKey()).orElse(null);
        if (parsed == null) {
            return recordVerifyFailure(mapping, MattersApiException.Reason.MALFORMED_KEY,
                    "The stored value is not a well-formed Matters API key.");
        }

        MattersCredential credential = new MattersCredential(
                clientId, mapping.getBaseUrl(), mapping.getApiKey(),
                parsed.keyId(), mapping.getMattersProjectId());

        try {
            JsonNode body = apiClient.verify(credential);
            long total = body.path("pagination").path("total").asLong(0L);

            mapping.setConnectionStatus(MattersProjectMapping.STATUS_CONNECTED);
            mapping.setLastVerifiedAt(Instant.now());
            mapping.setLastError(null);
            mapping.setLastErrorAt(null);
            MattersProjectMapping saved = mappingRepository.save(mapping);

            auditService.recordMutation(clientId, MattersAccessAudit.ACTION_MAPPING_VERIFIED,
                    mapping.getMattersProjectId(), "Connected; project reports " + total + " assets");

            return DspmMappingAdminView.from(saved, isEntitled(mapping));

        } catch (MattersApiException e) {
            return recordVerifyFailure(mapping, e.getReason(), e.getMessage());
        }
    }

    /**
     * Persists a failed verification.
     *
     * The stored status comes from {@link MattersApiException.Reason#connectionStatus()}, which is null
     * for failures that carry no verdict on the credential — a rate limit or a vendor outage leaves the
     * previous status alone rather than marking a working key as broken.
     */
    private DspmMappingAdminView recordVerifyFailure(MattersProjectMapping mapping,
                                                     MattersApiException.Reason reason,
                                                     String message) {
        String status = reason.connectionStatus();
        if (status != null) {
            mapping.setConnectionStatus(status);
        }
        mapping.setLastError(reason.name() + ": " + message);
        mapping.setLastErrorAt(Instant.now());
        MattersProjectMapping saved = mappingRepository.save(mapping);

        auditService.recordMutationFailure(mapping.getClient().getId(),
                MattersAccessAudit.ACTION_MAPPING_VERIFIED, mapping.getMattersProjectId(),
                reason.name(), message);

        log.warn("[DSPM] verification failed for client {}: {} — {}",
                mapping.getClient().getId(), reason, message);

        return DspmMappingAdminView.from(saved, isEntitled(mapping));
    }

    // ── entitlement ──────────────────────────────────────────────────────────────────────────────

    /**
     * Grants or withdraws the add-on.
     *
     * Takes the intended state rather than toggling, matching {@code /xamsecure-onboarding} and
     * {@code /notification-channel}: this gates a paid capability, and a toggle makes a double-submitted
     * request silently reverse the decision. Sending the intended value makes a retry idempotent.
     *
     * Withdrawing blocks reads immediately — {@link MattersCredentialResolver} checks entitlement first —
     * but deliberately leaves the mapping and its credential in place. Tearing those down from an admin
     * switch would mean a re-grant needs the whole provisioning dance again, and would destroy the only
     * copy of a key that Matters will not reissue.
     */
    @Transactional
    public void setEntitlement(Long clientId, boolean enabled) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Client not found: " + clientId));

        client.setMattersDspmEnabled(enabled);
        clientRepository.save(client);

        // Entitlement is checked before the cache is consulted, so a withdrawal already blocks reads.
        // Evicted anyway so a re-grant cannot serve anything from before the gap.
        dspmService.evictClient(clientId);

        String projectId = mappingRepository.findByClientId(clientId)
                .map(MattersProjectMapping::getMattersProjectId)
                .orElse(null);

        auditService.recordMutation(clientId,
                enabled ? MattersAccessAudit.ACTION_ENTITLEMENT_GRANTED
                        : MattersAccessAudit.ACTION_ENTITLEMENT_WITHDRAWN,
                projectId,
                "DSPM add-on " + (enabled ? "granted" : "withdrawn"));

        log.info("[DSPM] client {} mattersDspmEnabled={}", clientId, enabled);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private boolean isEntitled(MattersProjectMapping mapping) {
        return mapping.getClient() != null && mapping.getClient().isMattersDspmEnabled();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
