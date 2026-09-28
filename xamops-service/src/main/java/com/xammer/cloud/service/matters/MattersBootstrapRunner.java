package com.xammer.cloud.service.matters;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.MattersProjectMapping;
import com.xammer.cloud.dto.matters.DspmMappingRequest;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.MattersProjectMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Seeds one DSPM mapping at startup, so an environment can be brought up already connected rather than
 * needing someone to click through the admin page first.
 *
 * <h2>Inert unless configured</h2>
 * Does nothing at all when {@code matters.bootstrap.api-key} is blank, which is the default in every
 * committed properties file. That is deliberate: this class is a convenience for the environments where
 * someone has deliberately supplied a key, not a behaviour that switches itself on.
 *
 * <h2>Why the key is not in a properties file</h2>
 * {@code application-local.properties} is tracked in git. A Matters key is a live production credential
 * that reads a real customer inventory, and Matters shows a key once at creation and stores only a hash —
 * so committing one would put an unrecoverable production secret in version control, and rotating it
 * would mean a commit. It is read from the environment instead:
 *
 * <pre>
 *   export MATTERS_BOOTSTRAP_API_KEY='mts_prod_..._...'
 * </pre>
 *
 * The project id, base URL and client name are not secrets and do live in properties.
 *
 * <h2>Idempotent</h2>
 * Re-running with the same key and project is a no-op. Without that check, every restart would look like
 * a key rotation to {@link MattersAdminService#saveMapping} and write an audit row saying so — which
 * would bury the real rotations the trail exists to record.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MattersBootstrapRunner {

    private final ClientRepository clientRepository;
    private final MattersProjectMappingRepository mappingRepository;
    private final MattersAdminService adminService;

    /** The credential. Blank disables the whole runner. Supplied via env, never committed. */
    @Value("${matters.bootstrap.api-key:}")
    private String apiKey;

    @Value("${matters.bootstrap.project-id:}")
    private String projectId;

    @Value("${matters.bootstrap.project-name:}")
    private String projectName;

    @Value("${matters.bootstrap.base-url:https://app.matters.ai}")
    private String baseUrl;

    /** Client to map, by name. Ignored when {@link #clientId} is set. */
    @Value("${matters.bootstrap.client-name:}")
    private String clientName;

    /** Client to map, by id. Takes precedence over the name, which may not be unique in practice. */
    @Value("${matters.bootstrap.client-id:}")
    private String clientId;

    /**
     * Whether to grant the add-on to the seeded client.
     *
     * Defaults true, because a mapping with no entitlement produces a customer-facing 403 and the whole
     * point of seeding is to get a working screen. Deliberately scoped to this one client — the column
     * default stays FALSE so no other customer is affected.
     */
    @Value("${matters.bootstrap.grant-entitlement:true}")
    private boolean grantEntitlement;

    /**
     * Runs after the context is ready rather than in {@code @PostConstruct}.
     *
     * The repositories and the converter's {@code EncryptionService} all need to be live, and
     * {@link ApplicationReadyEvent} is the first point where that is guaranteed. Every failure is caught
     * and logged: a seeding convenience must never prevent the application from starting.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (isBlank(apiKey)) {
            return;
        }

        try {
            if (isBlank(projectId)) {
                log.warn("[DSPM bootstrap] matters.bootstrap.api-key is set but project-id is not — "
                        + "skipping. Set matters.bootstrap.project-id to the Matters project this key "
                        + "belongs to.");
                return;
            }

            Optional<Client> target = resolveClient();
            if (target.isEmpty()) {
                // Lists what is actually there. A bare "no client matched" leaves someone guessing at the
                // exact spelling of a name they cannot see from the log, which is the whole reason this
                // step fails in the first place.
                String candidates = clientRepository.findAll().stream()
                        .limit(20)
                        .map(c -> c.getId() + "=" + c.getName())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("none");
                log.warn("[DSPM bootstrap] no client matched id='{}' name='{}' — skipping. Set "
                                + "matters.bootstrap.client-id (preferred) or "
                                + "matters.bootstrap.client-name. Existing clients: {}",
                        clientId, clientName, candidates);
                return;
            }
            Client client = target.get();

            if (alreadySeeded(client)) {
                log.info("[DSPM bootstrap] client {} ({}) is already mapped to project {} with this key "
                        + "— nothing to do.", client.getId(), client.getName(), projectId);
                ensureEntitlement(client);
                return;
            }

            adminService.saveMapping(client.getId(), new DspmMappingRequest(
                    projectId,
                    isBlank(projectName) ? null : projectName,
                    null,
                    baseUrl,
                    apiKey,
                    // No expiry recorded: we do not know when this key was issued, and inventing a date
                    // would produce a false countdown in the admin table. An operator can set the real
                    // one from the admin page.
                    null,
                    true), "bootstrap");

            ensureEntitlement(client);

            // Logs the key id, never the secret.
            log.info("[DSPM bootstrap] mapped client {} ({}) to Matters project {} at {} (key {}). "
                            + "Use the Verify action on the DSPM admin page to confirm connectivity.",
                    client.getId(), client.getName(), projectId, baseUrl,
                    MattersApiKey.parse(apiKey).map(MattersApiKey::keyId).orElse("unparsed"));

        } catch (Exception e) {
            // Never fatal. A bad bootstrap value should leave the app running and the mapping absent,
            // which the admin page can then fix — not block startup for everyone.
            log.error("[DSPM bootstrap] seeding failed: {}", e.getMessage());
        }
    }

    private Optional<Client> resolveClient() {
        if (!isBlank(clientId)) {
            try {
                return clientRepository.findById(Long.parseLong(clientId.trim()));
            } catch (NumberFormatException e) {
                log.warn("[DSPM bootstrap] matters.bootstrap.client-id '{}' is not a number", clientId);
                return Optional.empty();
            }
        }
        if (!isBlank(clientName)) {
            return clientRepository.findByName(clientName.trim());
        }
        return Optional.empty();
    }

    /**
     * True when this exact mapping is already in place.
     *
     * Compared on project id plus the key's last four characters rather than on the key itself, so the
     * check needs no decryption and still notices a genuinely different key.
     */
    private boolean alreadySeeded(Client client) {
        Optional<MattersProjectMapping> existing = mappingRepository.findByClientId(client.getId());
        if (existing.isEmpty()) {
            return false;
        }
        MattersProjectMapping mapping = existing.get();
        String incomingLastFour = MattersApiKey.parse(apiKey).map(MattersApiKey::lastFour).orElse(null);
        return projectId.equals(mapping.getMattersProjectId())
                && incomingLastFour != null
                && incomingLastFour.equals(mapping.getApiKeyLastFour());
    }

    private void ensureEntitlement(Client client) {
        if (grantEntitlement && !client.isMattersDspmEnabled()) {
            adminService.setEntitlement(client.getId(), true);
            log.info("[DSPM bootstrap] granted the DSPM add-on to client {} ({})",
                    client.getId(), client.getName());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
