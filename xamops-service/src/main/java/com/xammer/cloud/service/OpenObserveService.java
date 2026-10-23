package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AccountObservabilityConfig;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.domain.ObservabilityConfig;
import com.xammer.cloud.repository.AccountObservabilityConfigRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OpenObserveService {

    private static final Logger logger = LoggerFactory.getLogger(OpenObserveService.class);

    @Value("${openobserve.url:http://localhost:5080}")
    private String o2Url;

    /** Public-facing URL for browser links and external access. Falls back to o2Url. */
    @Value("${openobserve.external.url:}")
    private String o2ExternalUrl;

    /**
     * No default. These previously fell back to the demo credentials published in
     * OpenObserve's own quickstart, baked into the source, which meant a deployment that forgot
     * to set them silently came up authenticating with a password anyone could look up. Every
     * profile sets both properties explicitly, so an empty value now means misconfiguration and
     * is reported as such by {@link #verifyCredentialsConfigured()} rather than being papered
     * over.
     */
    @Value("${openobserve.admin.email:}")
    private String adminEmail;

    @Value("${openobserve.admin.password:}")
    private String adminPassword;

    /**
     * Fails fast at startup instead of on the first user query. Without this the missing
     * credentials surfaced as a 401 from O2 buried inside a log-search failure, which reads
     * as "observability is broken" rather than "this deployment is misconfigured".
     */
    @jakarta.annotation.PostConstruct
    void verifyCredentialsConfigured() {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException(
                    "openobserve.admin.email and openobserve.admin.password must be configured "
                            + "(e.g. via OPENOBSERVE_ADMIN_EMAIL / OPENOBSERVE_ADMIN_PASSWORD). "
                            + "Observability cannot authenticate to OpenObserve without them.");
        }
    }

    private String getExternalO2Url() {
        return (o2ExternalUrl != null && !o2ExternalUrl.isBlank()) ? o2ExternalUrl : o2Url;
    }

    /**
     * Returns OpenObserve connection details for agent self-configuration.
     *
     * The credential is the org-scoped ingestion service account, never the admin account.
     * This endpoint previously answered with {@code adminEmail}/{@code adminPassword} — the
     * OpenObserve root credential — to any caller holding the shared agent API key. Since root
     * is global and the org is only a URL path segment, every agent host was handed the ability
     * to read and write every tenant's telemetry. Throws when no scoped credential exists,
     * because the alternative is re-introducing exactly that.
     */
    public Map<String, String> getAgentConfig(String providerAccountId) {
        String[] resolved = resolveOrgCredentialForAccount(providerAccountId);
        String orgId = resolved[0];
        String[] basic = decodeBasic(requireIngestionCredential(orgId, resolved[1]));

        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("url", getExternalO2Url());
        cfg.put("org", orgId);
        cfg.put("username", basic[0]);
        cfg.put("password", basic[1]);
        return cfg;
    }

    /**
     * Resolves {orgId, ingestionKey} for a provider account.
     *
     * Returns nulls rather than defaulting to the {@code "default"} org when nothing is
     * provisioned. The old {@code "default"} fallback meant an unprovisioned account's agent
     * was pointed at a shared org — mixing one customer's logs into a namespace other
     * customers also wrote to — and it made "not provisioned" indistinguishable from
     * "provisioned as default".
     */
    private String[] resolveOrgCredentialForAccount(String providerAccountId) {
        if (providerAccountId == null || providerAccountId.isBlank()) {
            return resolveClientLevelOrgCredential();
        }
        try {
            Optional<CloudAccount> account = cloudAccountRepo.findByProviderAccountId(providerAccountId);
            if (account.isEmpty()) return resolveClientLevelOrgCredential();

            CloudAccount ca = account.get();
            // Try account-scoped org first
            Optional<AccountObservabilityConfig> accountCfg =
                    accountConfigRepo.findByClientIdAndCloudAccountId(ca.getClient().getId(), ca.getId());
            if (accountCfg.isPresent() && accountCfg.get().getO2OrgId() != null) {
                return new String[]{ accountCfg.get().getO2OrgId(), accountCfg.get().getO2IngestionKey() };
            }
            // Fall back to client-level org
            Optional<ObservabilityConfig> clientCfg = configRepo.findByClientId(ca.getClient().getId());
            if (clientCfg.isPresent() && clientCfg.get().getO2OrgId() != null) {
                return new String[]{ clientCfg.get().getO2OrgId(), clientCfg.get().getO2IngestionKey() };
            }
        } catch (Exception e) {
            logger.warn("Could not resolve O2 org for account {}: {}", providerAccountId, e.getMessage());
        }
        return new String[]{ null, null };
    }

    private String[] resolveClientLevelOrgCredential() {
        // Single-tenant dev setup: if exactly one config row exists, use its org.
        //
        // Fetches two rows rather than findAll() — this runs on the agent-config path and
        // only needs to distinguish "exactly one" from "more than one", so loading every
        // tenant's config into memory to check size() == 1 was wasted work that grew with
        // the customer count.
        List<ObservabilityConfig> candidates =
                configRepo.findAll(PageRequest.of(0, 2)).getContent();
        if (candidates.size() == 1 && candidates.get(0).getO2OrgId() != null) {
            return new String[]{ candidates.get(0).getO2OrgId(), candidates.get(0).getO2IngestionKey() };
        }
        return new String[]{ null, null };
    }

    private final ObservabilityConfigRepository configRepo;
    private final AccountObservabilityConfigRepository accountConfigRepo;
    private final CloudAccountRepository cloudAccountRepo;
    private final KubernetesClusterConfigRepository clusterConfigRepo;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** Short-lived cache for O2 stream listings, keyed by "orgId|streamType". */
    private final TtlCache<List<Map<String, Object>>> streamCache = new TtlCache<>(30_000);

    /**
     * Minimal time-boxed cache. Entries are read far more often than they are
     * written and the map only ever holds a handful of org/type keys, so a plain
     * ConcurrentHashMap with per-entry expiry is enough — no eviction policy needed.
     */
    private static final class TtlCache<V> {
        private record Entry<V>(V value, long expiresAt) {}

        private final Map<String, Entry<V>> entries = new ConcurrentHashMap<>();
        private final long ttlMs;

        TtlCache(long ttlMs) { this.ttlMs = ttlMs; }

        V get(String key) {
            Entry<V> e = entries.get(key);
            if (e == null) return null;
            if (System.currentTimeMillis() >= e.expiresAt()) {
                entries.remove(key, e);
                return null;
            }
            return e.value();
        }

        void put(String key, V value) {
            entries.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMs));
        }

        /** Drops every entry under a key prefix, for invalidating one client's keys at once. */
        void invalidateWithPrefix(String prefix) {
            entries.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    public OpenObserveService(ObservabilityConfigRepository configRepo,
                              AccountObservabilityConfigRepository accountConfigRepo,
                              CloudAccountRepository cloudAccountRepo,
                              KubernetesClusterConfigRepository clusterConfigRepo,
                              @Qualifier("openObserveRestTemplate") RestTemplate restTemplate) {
        this.configRepo = configRepo;
        this.accountConfigRepo = accountConfigRepo;
        this.cloudAccountRepo = cloudAccountRepo;
        this.clusterConfigRepo = clusterConfigRepo;
        // Injected, timeout-bounded template. Do NOT replace with `new RestTemplate()`:
        // the default has no connect/read timeout, so a slow O2 hangs request threads
        // forever and starves the JDBC pool.
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    // ── Tenant Provisioning ───────────────────────────────────────────────────

    /**
     * Backoff for failed provisioning attempts, keyed by client/account.
     *
     * Without this, a half-provisioned row (record saved but o2OrgId still null) makes
     * every single GET /api/observability/config re-attempt the full O2 org creation.
     * The Observability page calls /config on every mount, so an unreachable O2 turned
     * into a permanent retry storm of blocking HTTP calls.
     */
    /**
     * Retention applied when no client-level config exists yet. Must match the default on
     * {@link ObservabilityConfig#getRetentionDays()} so a per-account org provisioned before
     * its parent client config doesn't silently fall back to OpenObserve's 7-day default.
     */
    static final int DEFAULT_RETENTION_DAYS = 30;

    private static final long REPROVISION_BACKOFF_MS = 120_000L;
    private final Map<String, Long> provisionFailedAt = new ConcurrentHashMap<>();

    private boolean inProvisionBackoff(String key) {
        Long failedAt = provisionFailedAt.get(key);
        if (failedAt == null) return false;
        if (System.currentTimeMillis() - failedAt < REPROVISION_BACKOFF_MS) return true;
        provisionFailedAt.remove(key);
        return false;
    }

    private void recordProvisionFailure(String key) {
        provisionFailedAt.put(key, System.currentTimeMillis());
    }

    /**
     * Drops the backoff entries for a client (and optionally one of its accounts) so the next
     * provisioning call re-attempts immediately.
     *
     * The backoff exists to stop {@code GET /config} from hammering a down OpenObserve on every
     * page load, but it also swallows a deliberate retry: a user who has just fixed the
     * underlying problem had no way to ask for another attempt and had to wait out the window
     * or restart the service. This is the explicit, user-initiated escape hatch — see
     * {@code POST /api/observability/reprovision}. The automatic paths still back off.
     */
    public void clearProvisionBackoff(Long clientId, String providerAccountId) {
        provisionFailedAt.remove("client:" + clientId);
        provisionFailedAt.remove("repair-client:" + clientId);
        if (providerAccountId != null && !providerAccountId.isBlank()) {
            provisionFailedAt.remove("account:" + clientId + ":" + providerAccountId);
            provisionFailedAt.remove("repair-account:" + clientId + ":" + providerAccountId);
        }
        // A previously-failed resolution may have cached a stale or client-level org id.
        invalidateOrgIdCache(clientId);
    }

    /**
     * Drops cached org IDs for a client after (re)provisioning, so queries don't keep hitting
     * a stale or previously-missing org for up to the cache TTL.
     */
    private void invalidateOrgIdCache(Long clientId) {
        orgIdCache.invalidateWithPrefix(clientId + ":");
    }

    /**
     * Deliberately NOT @Transactional.
     *
     * This method makes up to 5 sequential HTTP calls to OpenObserve. Wrapping it in a
     * transaction held one of the 20 Hikari connections open for the full duration of
     * those calls, so a slow O2 drained the pool and every unrelated endpoint started
     * failing with "Unable to acquire JDBC Connection".
     *
     * The external O2 side effects cannot be rolled back anyway, so the transaction
     * bought no real atomicity. Each `configRepo.save(...)` below runs in its own
     * short transaction (Spring Data manages it), which is a single-row write.
     */
    public ObservabilityConfig provisionTenant(Client client) {
        Optional<ObservabilityConfig> existing = configRepo.findByClientId(client.getId());
        if (existing.isPresent()) {
            ObservabilityConfig cfg = existing.get();
            if (cfg.getO2OrgId() != null) {
                // The org exists, but its ingestion key may be unusable: either never created,
                // or the admin credential the old fallback persisted. Mint replacements against
                // the existing org rather than returning a row whose key every generator will
                // then refuse. Without this the only remedy was a manual UPDATE, because this
                // method returned early on a non-null orgId.
                if (needsIngestionKeyRepair(cfg.getO2IngestionKey())) {
                    return repairIngestionKeys(cfg, client.getId());
                }
                return cfg;
            }
            // Previous provisioning attempt saved the record but failed to create the O2 org —
            // re-attempt org creation and update the existing row in-place, but not on
            // every request: back off so a down O2 can't be hammered by page loads.
            String backoffKey = "client:" + client.getId();
            if (inProvisionBackoff(backoffKey)) {
                logger.debug("Skipping re-provision for client {} — in backoff window", client.getId());
                return cfg;
            }
            logger.info("Re-provisioning O2 org for client {} (previous attempt left null orgId)", client.getId());
            try {
                String orgId = createO2OrgAndGetIdentifier(client.getName(), client.getId());
                String ingestionKey = createServiceAccount(orgId, "ingest-" + client.getId());
                String apiKey       = createServiceAccount(orgId, "api-" + client.getId());
                cfg.setO2OrgId(orgId);
                cfg.setO2IngestionKey(ingestionKey);
                cfg.setO2ApiKey(apiKey);
                ObservabilityConfig saved = configRepo.save(cfg);
                invalidateOrgIdCache(client.getId());
                applyOrgDefaultRetention(orgId, saved.getRetentionDays());
                return saved;
            } catch (Exception e) {
                recordProvisionFailure(backoffKey);
                logger.error("Re-provisioning failed for client {}: {}", client.getId(), e.getMessage());
                throw new RuntimeException("Failed to re-provision observability: " + e.getMessage(), e);
            }
        }

        String backoffKey = "client:" + client.getId();
        if (inProvisionBackoff(backoffKey)) {
            throw new RuntimeException("Observability provisioning recently failed; retrying shortly");
        }

        logger.info("Provisioning O2 org for client {}", client.getId());

        try {
            // O2 generates its own identifier — we must read it from the response
            String orgId = createO2OrgAndGetIdentifier(client.getName(), client.getId());
            logger.info("O2 org created for client {} → orgId={}", client.getId(), orgId);

            String ingestionKey = createServiceAccount(orgId, "ingest-" + client.getId());
            String apiKey       = createServiceAccount(orgId, "api-" + client.getId());

            ObservabilityConfig config = new ObservabilityConfig();
            config.setClient(client);
            config.setO2OrgId(orgId);          // actual O2-assigned identifier
            config.setO2IngestionKey(ingestionKey);
            config.setO2ApiKey(apiKey);
            ObservabilityConfig saved = configRepo.save(config);
            invalidateOrgIdCache(client.getId());

            // Apply org-level retention so agent-created streams don't inherit O2's 7-day default
            applyOrgDefaultRetention(orgId, saved.getRetentionDays());
            return saved;
        } catch (Exception e) {
            recordProvisionFailure(backoffKey);
            logger.error("Failed to provision O2 org for client {}: {}", client.getId(), e.getMessage());
            throw new RuntimeException("Failed to provision observability: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String createO2OrgAndGetIdentifier(String clientName, Long clientId) {
        String rawName = clientName != null ? clientName : "tenant_" + clientId;
        String name = rawName.replaceAll("[^a-zA-Z0-9 ]", "_").replaceAll("_+", "_").trim();
        HttpHeaders headers = buildAdminHeaders();

        // Step 1: Check if an org with this name already exists (avoid creating duplicates)
        try {
            ResponseEntity<Map> listResp = restTemplate.exchange(
                    o2Url + "/api/organizations",
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class
            );
            if (listResp.getBody() != null && listResp.getBody().containsKey("data")) {
                List<Map<String, Object>> orgs = (List<Map<String, Object>>) listResp.getBody().get("data");
                Optional<String> existing = orgs.stream()
                        .filter(o -> name.equals(o.get("name")) && !"default".equals(o.get("identifier")))
                        .map(o -> (String) o.get("identifier"))
                        .findFirst();
                if (existing.isPresent()) {
                    logger.info("Reusing existing O2 org '{}' → identifier={}", name, existing.get());
                    return existing.get();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not list O2 orgs before creation: {}", e.getMessage());
        }

        // Step 2: No existing org found — create one
        Map<String, String> body = Map.of("name", name);
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    o2Url + "/api/organizations",
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            if (resp.getBody() != null && resp.getBody().containsKey("identifier")) {
                return (String) resp.getBody().get("identifier");
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                logger.warn("O2 org '{}' already exists (409), looking up identifier", name);
            } else if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                logger.error("O2 rejected org creation for '{}' with 400: {}", name, e.getResponseBodyAsString());
                throw e;
            } else {
                throw e;
            }
        }
        // Final fallback: find by name in org list
        return findOrgIdentifierByName(name);
    }

    @SuppressWarnings("unchecked")
    private String findOrgIdentifierByName(String name) {
        HttpHeaders headers = buildAdminHeaders();
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    o2Url + "/api/organizations",
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class
            );
            if (resp.getBody() != null && resp.getBody().containsKey("data")) {
                List<Map<String, Object>> orgs = (List<Map<String, Object>>) resp.getBody().get("data");
                return orgs.stream()
                        .filter(o -> name.equals(o.get("name")))
                        .map(o -> (String) o.get("identifier"))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Org not found with name: " + name));
            }
        } catch (Exception e) {
            logger.error("Failed to find org by name '{}': {}", name, e.getMessage());
        }
        throw new RuntimeException("Could not resolve O2 org identifier for name: " + name);
    }

    /**
     * Creates an org-scoped service account ({@code role: member}) and returns its credential.
     *
     * Returns null when OpenObserve won't issue one. It used to fall back to
     * {@code base64(adminEmail:adminPassword)}, which meant a single failed HTTP call silently
     * turned a tenant-scoped ingestion key into the global root credential — and because the
     * result is persisted as {@code o2_ingestion_key}, that downgrade was permanent and
     * invisible. Provisioning now records the absence, and the generators refuse to issue a
     * collector config rather than substituting root.
     *
     * <p>Recreates the account when OpenObserve reports it already exists. A service account
     * token is revealed only in the response to the call that creates it — there is no API to
     * read it back — so an earlier provisioning run that created the account but failed to
     * persist its token left the org permanently unusable: this method only ever POSTed, and
     * that POST then answered {@code 400 User already exists} forever. The account was stuck
     * with no stored credential, the generators kept refusing, and the one retry path the UI
     * offers could never clear it. Deleting the stale identity and creating it again is the
     * only way to obtain a usable token, and it is safe here because every caller has already
     * established (via {@link #needsIngestionKeyRepair}) that no working credential is stored.
     */
    private String createServiceAccount(String orgId, String email) {
        String fullEmail = email + "@xamops.internal";
        try {
            String token = postServiceAccount(orgId, fullEmail);
            if (token != null) return token;
            logger.error("OpenObserve returned no token for service account {} in org {} — "
                    + "collectors for this org cannot be configured until this succeeds", fullEmail, orgId);
            return null;
        } catch (HttpClientErrorException e) {
            if (!isAlreadyExists(e)) {
                logger.error("Could not create service account {} in org {}: {} — collectors for this "
                        + "org cannot be configured until this succeeds", fullEmail, orgId, e.getMessage());
                return null;
            }
            // Falls through to the recreate path below.
        } catch (Exception e) {
            logger.error("Could not create service account {} in org {}: {} — collectors for this "
                    + "org cannot be configured until this succeeds", fullEmail, orgId, e.getMessage());
            return null;
        }

        logger.warn("Service account {} already exists in org {} but its token was never persisted "
                + "and cannot be read back; recreating it to mint a usable credential", fullEmail, orgId);
        try {
            // Built as a URI, not a string. The String overload of exchange() treats its
            // argument as a URI template and encodes it, so a pre-encoded '@' (%40) went out
            // as %2540 and O2 looked up a user whose name literally contained "%40" — a 404
            // that read as "the account isn't there" when it was.
            java.net.URI deleteUri = UriComponentsBuilder
                    .fromUriString(o2Url)
                    .pathSegment("api", orgId, "service_accounts", fullEmail)
                    .build()
                    .encode()
                    .toUri();
            restTemplate.exchange(
                    deleteUri,
                    HttpMethod.DELETE,
                    new HttpEntity<>(buildAdminHeaders()),
                    Map.class
            );
            String token = postServiceAccount(orgId, fullEmail);
            if (token == null) {
                logger.error("Recreated service account {} in org {} but OpenObserve returned no token",
                        fullEmail, orgId);
            }
            return token;
        } catch (Exception e) {
            logger.error("Could not recreate service account {} in org {}: {} — collectors for this "
                    + "org cannot be configured until this succeeds", fullEmail, orgId, e.getMessage());
            return null;
        }
    }

    /**
     * One POST to O2's service-account API, returning the issued credential.
     *
     * Lets the HTTP error escape rather than logging and returning null, so the caller can
     * tell "this name is taken" apart from "O2 is unreachable" — the first is recoverable by
     * recreating the account, the second must not delete anything.
     */
    @SuppressWarnings("unchecked")
    private String postServiceAccount(String orgId, String fullEmail) {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                o2Url + "/api/" + orgId + "/service_accounts",
                new HttpEntity<>(Map.of("email", fullEmail, "role", "member"), buildAdminHeaders()),
                Map.class
        );
        return resp.getBody() != null ? (String) resp.getBody().get("token") : null;
    }

    /**
     * True when O2 rejected a service-account creation because the identity already exists.
     *
     * Matched on the response body as well as the status: O2 answers this particular conflict
     * with 400 rather than 409, and a bare 400 also covers genuinely malformed requests, which
     * must not trigger a delete.
     */
    private static boolean isAlreadyExists(HttpClientErrorException e) {
        if (e.getStatusCode() != HttpStatus.BAD_REQUEST && e.getStatusCode() != HttpStatus.CONFLICT) {
            return false;
        }
        String body = e.getResponseBodyAsString();
        return body != null && body.toLowerCase().contains("already exists");
    }

    /**
     * Replaces an existing org's unusable ingestion/api credentials with fresh scoped service
     * accounts, leaving the org and its data alone.
     *
     * Backed off like the other provisioning paths: this runs from GET /config, which the
     * Observability page calls on every mount, so a failing OpenObserve must not be retried on
     * each page load. When it cannot succeed the row is returned unchanged and the generators
     * keep refusing — a visible failure with an actionable message, rather than a silent
     * downgrade back to admin credentials.
     */
    /** Per-account equivalent of {@link #repairIngestionKeys}. */
    private AccountObservabilityConfig repairAccountIngestionKey(
            AccountObservabilityConfig acc, Long clientId, String providerAccountId) {
        String backoffKey = "repair-account:" + clientId + ":" + providerAccountId;
        if (inProvisionBackoff(backoffKey)) {
            logger.debug("Skipping ingestion key repair for account {} — in backoff window", providerAccountId);
            return acc;
        }
        logger.warn("Ingestion credential for account {} org {} is missing or is the admin "
                + "credential; minting a scoped service account to replace it",
                providerAccountId, acc.getO2OrgId());
        try {
            String ingestionKey = createServiceAccount(
                    acc.getO2OrgId(), "ingest-acc-" + acc.getCloudAccountId());
            if (ingestionKey == null) {
                recordProvisionFailure(backoffKey);
                logger.error("Could not mint a scoped ingestion credential for account {} org {}",
                        providerAccountId, acc.getO2OrgId());
                return acc;
            }
            acc.setO2IngestionKey(ingestionKey);
            AccountObservabilityConfig saved = accountConfigRepo.save(acc);
            invalidateOrgIdCache(clientId);
            logger.info("Replaced ingestion credential for account {} org {}",
                    providerAccountId, acc.getO2OrgId());
            return saved;
        } catch (Exception e) {
            recordProvisionFailure(backoffKey);
            logger.error("Ingestion key repair failed for account {}: {}", providerAccountId, e.getMessage());
            return acc;
        }
    }

    private ObservabilityConfig repairIngestionKeys(ObservabilityConfig cfg, Long clientId) {
        String backoffKey = "repair-client:" + clientId;
        if (inProvisionBackoff(backoffKey)) {
            logger.debug("Skipping ingestion key repair for client {} — in backoff window", clientId);
            return cfg;
        }
        logger.warn("Ingestion credential for client {} org {} is missing or is the admin "
                + "credential; minting scoped service accounts to replace it",
                clientId, cfg.getO2OrgId());
        try {
            String ingestionKey = createServiceAccount(cfg.getO2OrgId(), "ingest-" + clientId);
            String apiKey       = createServiceAccount(cfg.getO2OrgId(), "api-" + clientId);
            if (ingestionKey == null) {
                recordProvisionFailure(backoffKey);
                logger.error("Could not mint a scoped ingestion credential for client {} org {} — "
                        + "collector configs stay unavailable until OpenObserve accepts the "
                        + "service account request", clientId, cfg.getO2OrgId());
                return cfg;
            }
            cfg.setO2IngestionKey(ingestionKey);
            if (apiKey != null) cfg.setO2ApiKey(apiKey);
            ObservabilityConfig saved = configRepo.save(cfg);
            invalidateOrgIdCache(clientId);
            logger.info("Replaced ingestion credential for client {} org {}", clientId, cfg.getO2OrgId());
            return saved;
        } catch (Exception e) {
            recordProvisionFailure(backoffKey);
            logger.error("Ingestion key repair failed for client {}: {}", clientId, e.getMessage());
            return cfg;
        }
    }

    // ── Per-Account Provisioning ───────────────────────────────────────────────

    /**
     * Provisions (or returns existing) an O2 org for a specific cloud account.
     * Each cloud account gets its own isolated O2 org for log/metric isolation.
     */
    // Not @Transactional — see provisionTenant. Holds no JDBC connection across the
    // O2 org/service-account HTTP calls.
    public AccountObservabilityConfig provisionForAccount(Client client, String providerAccountId) {
        // Return existing config if already fully provisioned
        Optional<AccountObservabilityConfig> existing =
                accountConfigRepo.findByClientIdAndProviderAccountId(client.getId(), providerAccountId);
        if (existing.isPresent() && existing.get().getO2OrgId() != null) {
            // Same repair as the client-level path: an existing org whose ingestion key is
            // missing, or is the admin credential left by the old fallback, is fixed here so
            // opening the Observability tab is enough. Otherwise this returned early and the
            // only remedy was editing the database by hand.
            AccountObservabilityConfig acc = existing.get();
            if (needsIngestionKeyRepair(acc.getO2IngestionKey())) {
                return repairAccountIngestionKey(acc, client.getId(), providerAccountId);
            }
            return acc;
        }

        // Don't re-attempt O2 provisioning on every page load when it just failed.
        String backoffKey = "account:" + client.getId() + ":" + providerAccountId;
        if (inProvisionBackoff(backoffKey)) {
            if (existing.isPresent()) return existing.get();
            throw new RuntimeException("Account observability provisioning recently failed; retrying shortly");
        }

        try {
            // Look up the CloudAccount entity
            CloudAccount account = cloudAccountRepo.findByProviderAccountId(providerAccountId)
                    .orElseThrow(() -> new RuntimeException("Cloud account not found: " + providerAccountId));

            if (!account.getClient().getId().equals(client.getId())) {
                throw new RuntimeException("Account does not belong to this client");
            }

            logger.info("Provisioning O2 org for account {} ({})", account.getAccountName(), providerAccountId);

            // Use account name as O2 org name — O2 allows only alphanumeric, spaces, underscores
            String orgName = "acct_" + account.getAccountName()
                    .replaceAll("[^a-zA-Z0-9]", "_").toLowerCase().replaceAll("_+", "_");
            String orgId = createO2OrgAndGetIdentifier(orgName, account.getId());

            String ingestionKey = createServiceAccount(orgId, "ingest-acc-" + account.getId());

            AccountObservabilityConfig config = new AccountObservabilityConfig();
            config.setClientId(client.getId());
            config.setCloudAccountId(account.getId());
            config.setProviderAccountId(providerAccountId);
            config.setAccountName(account.getAccountName());
            config.setO2OrgId(orgId);
            config.setO2IngestionKey(ingestionKey);
            AccountObservabilityConfig saved = accountConfigRepo.save(config);
            invalidateOrgIdCache(client.getId());

            // Inherit retention from the client-level config so streams don't use O2's 7-day
            // default.
            //
            // This used to be an ifPresent() over the client config, which silently did
            // nothing when the client-level row didn't exist yet. On a brand-new tenant that
            // was always the case, so the per-account org — the org that actually holds the
            // data the UI queries — kept OpenObserve's built-in 7-day retention forever, and
            // every metric older than a week was quietly deleted while the Overview tab
            // reported "30d". Always apply a value now, falling back to the default.
            int retentionDays = configRepo.findByClientId(client.getId())
                    .map(ObservabilityConfig::getRetentionDays)
                    .orElse(DEFAULT_RETENTION_DAYS);
            applyOrgDefaultRetention(orgId, retentionDays);
            return saved;
        } catch (Exception e) {
            recordProvisionFailure(backoffKey);
            throw e;
        }
    }

    /**
     * Cache of resolved org IDs.
     *
     * Every query endpoint resolves the org before talking to O2, and the metrics dashboard
     * issues ~19 queries per page load plus the same again every 30s. That was ~19 identical
     * indexed lookups per load against the same connection pool that was failing under load.
     * Org IDs effectively never change for a provisioned account, so a short TTL removes the
     * repetition while still picking up re-provisioning within a minute.
     */
    private final TtlCache<String> orgIdCache = new TtlCache<>(60_000);

    /**
     * Returns the O2 org ID to use for a query.
     * If providerAccountId is given and provisioned → use account-scoped org.
     * Otherwise fall back to the client-level org.
     */
    private String getEffectiveOrgId(Long clientId, String providerAccountId) {
        String cacheKey = clientId + ":" + (providerAccountId == null ? "" : providerAccountId);
        String cached = orgIdCache.get(cacheKey);
        if (cached != null) return cached;

        String resolved = resolveEffectiveOrgId(clientId, providerAccountId);
        // Only cache real resolutions; a failure should be retried, not remembered.
        if (resolved != null) orgIdCache.put(cacheKey, resolved);
        return resolved;
    }

    private String resolveEffectiveOrgId(Long clientId, String providerAccountId) {
        if (providerAccountId != null && !providerAccountId.isBlank()) {
            Optional<AccountObservabilityConfig> acc =
                    accountConfigRepo.findByClientIdAndProviderAccountId(clientId, providerAccountId);
            // A row with a null org id is a half-finished provisioning attempt. Returning it
            // built request URLs like /api/null/_search, so the query failed with a confusing
            // O2 error instead of falling back to something that works.
            if (acc.isPresent() && acc.get().getO2OrgId() != null) return acc.get().getO2OrgId();
            // Account not provisioned yet — still return client-level org as fallback
            logger.warn("Account {} not yet provisioned for client {}, falling back to client org", providerAccountId, clientId);
        }
        return getConfig(clientId).getO2OrgId();
    }

    // ── Log Search ────────────────────────────────────────────────────────────

    public Map<String, Object> searchLogs(Long clientId, Map<String, Object> params) {
        String providerAccountId = (String) params.getOrDefault("providerAccountId", null);
        String orgId = getEffectiveOrgId(clientId, providerAccountId);

        String stream = sqlIdentifier((String) params.getOrDefault("stream", "default"));
        String keyword = (String) params.getOrDefault("keyword", null);
        String level = (String) params.getOrDefault("level", null);
        String sourceFilter = (String) params.getOrDefault("sourceFilter", null);
        long startTime = toLong(params.getOrDefault("startTime", System.currentTimeMillis() - 3600_000L));
        long endTime = toLong(params.getOrDefault("endTime", System.currentTimeMillis()));
        int size = (int) params.getOrDefault("size", 50);
        int from = (int) params.getOrDefault("from", 0);

        // Support raw SQL from query editor, fallback to filter-based SQL
        String rawSql = (String) params.getOrDefault("rawSql", null);
        String sqlStr;
        if (rawSql != null && !rawSql.isBlank()) {
            sqlStr = requireReadOnlySelect(rawSql);
        } else {
            StringBuilder sql = new StringBuilder("SELECT * FROM \"").append(stream).append("\"");
            List<String> conditions = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                conditions.add("match_all('" + keyword.replace("'", "''") + "')");
            }
            if (level != null && !level.isBlank() && !level.equalsIgnoreCase("ALL")) {
                // Full-text match instead of a `level` column equality: OTel journald/docker
                // streams have no populated level column (severity=0) — the level only exists
                // inside the log body. match_all uses the inverted index and never fails on
                // streams that lack the column.
                conditions.add("match_all('" + level.toLowerCase().replace("'", "''") + "')");
            }
            // Source filter: match the host directly on the host_name column.
            // We must NOT use match_all() here: host_name is a resource attribute, not a
            // full-text-indexed body field, so match_all('ip-10-30-21-26') never matches it
            // (it returns 0 even for valid hosts). LIKE on host_name filters correctly.
            if (sourceFilter != null && !sourceFilter.isBlank()) {
                String sf = sourceFilter.replace("'", "''");
                conditions.add("host_name LIKE '%" + sf + "%'");
            }
            if (!conditions.isEmpty()) {
                sql.append(" WHERE ").append(String.join(" AND ", conditions));
            }
            sql.append(" ORDER BY _timestamp DESC LIMIT ").append(size);
            sqlStr = sql.toString();
        }

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("sql", sqlStr);
        query.put("start_time", startTime * 1000); // O2 expects microseconds
        query.put("end_time", endTime * 1000);
        query.put("size", size);
        query.put("from", from);

        Map<String, Object> body = Map.of("query", query);

        HttpHeaders headers = buildAdminHeaders();
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    o2Url + "/api/" + orgId + "/_search?type=logs",
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            return resp.getBody() != null ? resp.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            logger.error("O2 log search failed for client {}: {}", clientId, e.getMessage());
            return Map.of("hits", Collections.emptyList(), "total", 0, "error", e.getMessage());
        }
    }

    // ── Log Histogram (volume bar chart) ──────────────────────────────────────

    public Map<String, Object> getLogHistogram(Long clientId, Map<String, Object> params) {
        String providerAccountId = (String) params.getOrDefault("providerAccountId", null);
        String orgId = getEffectiveOrgId(clientId, providerAccountId);

        String stream = sqlIdentifier((String) params.getOrDefault("stream", "default"));
        long startTime = toLong(params.getOrDefault("startTime", System.currentTimeMillis() - 3600_000L));
        long endTime = toLong(params.getOrDefault("endTime", System.currentTimeMillis()));

        // Bucket width follows the selected window (sent by the UI) so a 7-day view
        // spans 7 days instead of maxing out at 288 five-minute buckets (= 24h).
        // Whitelisted pattern only — the value is interpolated into SQL.
        String interval = (String) params.getOrDefault("interval", "5 minute");
        if (interval == null || !interval.matches("\\d{1,3} (second|minute|hour|day)s?")) {
            interval = "5 minute";
        }
        // Optional full-text condition (e.g. "error") — used by the UI to overlay an
        // error-only series on the volume chart. Index-based, safe on any stream schema.
        String match = (String) params.getOrDefault("match", null);
        String where = (match != null && !match.isBlank())
                ? " WHERE match_all('" + match.replace("'", "''") + "')"
                : "";
        String sql = "SELECT histogram(_timestamp, '" + interval + "') AS key, COUNT(*) AS num" +
                " FROM \"" + stream + "\"" + where + " GROUP BY key ORDER BY key";

        Map<String, Object> query = Map.of(
                "sql", sql,
                "start_time", startTime * 1000,
                "end_time", endTime * 1000,
                "size", 288
        );

        HttpHeaders headers = buildAdminHeaders();
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    o2Url + "/api/" + orgId + "/_search?type=logs",
                    new HttpEntity<>(Map.of("query", query), headers),
                    Map.class
            );
            return resp.getBody() != null ? resp.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            logger.error("O2 histogram failed for client {}: {}", clientId, e.getMessage());
            return Map.of("hits", Collections.emptyList());
        }
    }

    // ── Host List (for the log explorer host dropdown) ─────────────────────────

    /**
     * Returns the distinct host names sending logs to a stream, newest-volume first.
     * Powers the Host/Source dropdown so users can pick a host instead of typing it.
     */
    @SuppressWarnings("unchecked")
    public List<String> listLogHosts(Long clientId, Map<String, Object> params) {
        String providerAccountId = (String) params.getOrDefault("providerAccountId", null);
        String orgId = getEffectiveOrgId(clientId, providerAccountId);
        String stream = sqlIdentifier((String) params.getOrDefault("stream", "default"));
        long endTime = toLong(params.getOrDefault("endTime", System.currentTimeMillis()));
        long startTime = toLong(params.getOrDefault("startTime", endTime - 24 * 3600_000L));

        String sql = "SELECT host_name, COUNT(*) AS num FROM \"" + stream + "\""
                + " WHERE host_name IS NOT NULL GROUP BY host_name ORDER BY num DESC";
        Map<String, Object> query = Map.of(
                "sql", sql,
                "start_time", startTime * 1000,
                "end_time", endTime * 1000,
                "size", 200
        );
        HttpHeaders headers = buildAdminHeaders();
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    o2Url + "/api/" + orgId + "/_search?type=logs",
                    new HttpEntity<>(Map.of("query", query), headers),
                    Map.class
            );
            List<String> hosts = new ArrayList<>();
            if (resp.getBody() != null && resp.getBody().get("hits") instanceof List) {
                for (Object h : (List<Object>) resp.getBody().get("hits")) {
                    if (h instanceof Map) {
                        Object hn = ((Map<String, Object>) h).get("host_name");
                        if (hn != null && !String.valueOf(hn).isBlank()) {
                            hosts.add(String.valueOf(hn));
                        }
                    }
                }
            }
            return hosts;
        } catch (Exception e) {
            // Stream may lack a host_name column (e.g. K8s-only streams) — degrade gracefully.
            logger.warn("listLogHosts failed for client {} stream {}: {}", clientId, stream, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Streams List ──────────────────────────────────────────────────────────

    /** Returns only logs-type streams — used by the log explorer stream selector. */
    public List<Map<String, Object>> listStreams(Long clientId, String providerAccountId) {
        return listStreams(clientId, providerAccountId, "logs");
    }

    /** Returns streams of the given type, or all stream types when streamType is null. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listStreams(Long clientId, String providerAccountId, String streamType) {
        String orgId = getEffectiveOrgId(clientId, providerAccountId);

        // The stream list is the hot path on the Observability screens — Overview
        // asks for every type and Logs asks for the logs subset on the same page
        // load, and O2 recomputes per-stream storage stats on each call. The set of
        // streams only changes when an agent starts shipping a new one, so a short
        // TTL keeps the numbers fresh while collapsing repeat calls to one.
        String cacheKey = orgId + "|" + (streamType == null ? "*" : streamType);
        List<Map<String, Object>> cached = streamCache.get(cacheKey);
        if (cached != null) return cached;

        HttpHeaders headers = buildAdminHeaders();
        try {
            String url = o2Url + "/api/" + orgId + "/streams";
            if (streamType != null && !streamType.isBlank()) {
                url += "?type=" + streamType;
            }
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );
            if (resp.getBody() != null && resp.getBody().containsKey("list")) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("list");
                streamCache.put(cacheKey, list);
                return list;
            }
        } catch (Exception e) {
            logger.error("O2 list streams failed for client {}: {}", clientId, e.getMessage());
        }
        return Collections.emptyList();
    }

    // ── Metrics Query (Prometheus-compatible) ─────────────────────────────────

    public Map<String, Object> queryMetrics(Long clientId, String providerAccountId, String promQuery, long start, long end, String step) {
        String orgId = getEffectiveOrgId(clientId, providerAccountId);
        HttpHeaders headers = buildAdminHeaders();

        // Manually encode the PromQL query so curly braces {state="idle"} are
        // percent-encoded and never treated as URI template variables by RestTemplate.
        java.net.URI uri;
        try {
            String encodedQuery = java.net.URLEncoder.encode(promQuery, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            String urlStr = o2Url + "/api/" + orgId + "/prometheus/api/v1/query_range"
                    + "?query=" + encodedQuery
                    + "&start=" + (start / 1000)
                    + "&end=" + (end / 1000)
                    + "&step=" + step;
            uri = new java.net.URI(urlStr);
        } catch (java.net.URISyntaxException e) {
            logger.error("Invalid URI for metrics query: {}", e.getMessage());
            return Collections.emptyMap();
        }

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class
            );
            return resp.getBody() != null ? resp.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            logger.error("O2 metrics query failed for client {}: {}", clientId, e.getMessage());
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ── Traces ────────────────────────────────────────────────────────────────

    /** Escapes a value for interpolation into a single-quoted SQL string literal. */
    private static String sqlEscape(String v) {
        return v.replace("'", "''");
    }

    // ── SQL safety ────────────────────────────────────────────────────────────

    /**
     * Stream names arrive from the browser and are interpolated into SQL as a quoted
     * identifier ({@code FROM "<stream>"}). Nothing escaped them, so a name containing a
     * double quote closed the identifier early and the remainder was parsed as SQL.
     *
     * Validated rather than escaped: OpenObserve stream names are created by the agents from
     * a restricted character set, so anything outside it is a malformed request and should be
     * rejected outright instead of silently rewritten into a different stream name.
     */
    private static final java.util.regex.Pattern STREAM_NAME_RE =
            java.util.regex.Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.:\\-]{0,127}");

    static String sqlIdentifier(String stream) {
        String s = (stream == null || stream.isBlank()) ? "default" : stream.trim();
        if (!STREAM_NAME_RE.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "Invalid stream name. Stream names may contain letters, digits, '_', '.', ':' and '-' only.");
        }
        return s;
    }

    private static final int MAX_RAW_SQL_LEN = 4000;

    /**
     * Keywords that have no place in a console whose only job is reading log data.
     * OpenObserve's search API is read-oriented, but this is the boundary we control and it
     * turns "whatever O2 happens to reject" into an explicit, documented contract.
     */
    private static final java.util.regex.Pattern FORBIDDEN_SQL_RE = java.util.regex.Pattern.compile(
            "(?i)\\b(insert|update|delete|drop|create|alter|truncate|grant|revoke|attach|detach|copy|merge|call|execute|vacuum|pragma)\\b");

    /**
     * Validates a user-supplied query from the Logs tab's SQL editor.
     *
     * The editor previously handed {@code rawSql} straight to OpenObserve untouched. This
     * enforces three things: a single statement, a read-only one, and a bounded length.
     *
     * String literals are blanked before the keyword scan so that searching your logs for the
     * word "delete" or "drop" — an entirely reasonable thing to do in a log console — is not
     * mistaken for a destructive statement. Only keywords appearing as SQL are rejected.
     */
    static String requireReadOnlySelect(String rawSql) {
        String sql = rawSql.trim();
        // Tolerate a single trailing semicolon; anything else is statement chaining.
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.isEmpty()) {
            throw new IllegalArgumentException("Query is empty.");
        }
        if (sql.length() > MAX_RAW_SQL_LEN) {
            throw new IllegalArgumentException("Query is too long (limit " + MAX_RAW_SQL_LEN + " characters).");
        }

        // Blank out single-quoted literals so their contents are not scanned as SQL.
        String scrubbed = sql.replaceAll("'([^']|'')*'", "''");

        if (scrubbed.contains(";")) {
            throw new IllegalArgumentException("Only a single statement is allowed.");
        }
        if (scrubbed.contains("--") || scrubbed.contains("/*")) {
            throw new IllegalArgumentException("Comments are not allowed in queries.");
        }
        if (!scrubbed.regionMatches(true, 0, "SELECT", 0, 6)
                && !scrubbed.regionMatches(true, 0, "WITH", 0, 4)) {
            throw new IllegalArgumentException("Only SELECT queries are allowed.");
        }
        java.util.regex.Matcher forbidden = FORBIDDEN_SQL_RE.matcher(scrubbed);
        if (forbidden.find()) {
            throw new IllegalArgumentException(
                    "'" + forbidden.group(1).toUpperCase() + "' is not allowed — the query editor is read-only.");
        }
        return sql;
    }

    /** Runs a SQL query against the org's trace streams and returns the hits. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> o2TraceQuery(String orgId, String sql, long startMs, long endMs, int size) {
        Map<String, Object> query = Map.of(
                "sql", sql,
                "start_time", startMs * 1000, // O2 expects microseconds
                "end_time", endMs * 1000,
                "size", size
        );
        HttpHeaders headers = buildAdminHeaders();
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                o2Url + "/api/" + orgId + "/_search?type=traces",
                new HttpEntity<>(Map.of("query", query), headers),
                Map.class
        );
        if (resp.getBody() != null && resp.getBody().get("hits") instanceof List) {
            return (List<Map<String, Object>>) resp.getBody().get("hits");
        }
        return Collections.emptyList();
    }

    /**
     * Builds the WHERE conditions shared by trace search + stats.
     * Root spans have no parent reference — one row per trace.
     */
    private List<String> traceConditions(Map<String, Object> params, boolean rootsOnly) {
        List<String> conditions = new ArrayList<>();
        if (rootsOnly) conditions.add("reference_parent_span_id IS NULL");
        String keyword = (String) params.getOrDefault("keyword", null);
        if (keyword != null && !keyword.isBlank()) {
            conditions.add("operation_name LIKE '%" + sqlEscape(keyword.trim()) + "%'");
        }
        String service = (String) params.getOrDefault("service", null);
        if (service != null && !service.isBlank()) {
            conditions.add("service_name = '" + sqlEscape(service) + "'");
        }
        // Host filter — spans carry the originating EC2 as service_host_name.
        String host = (String) params.getOrDefault("host", null);
        if (host != null && !host.isBlank()) {
            conditions.add("service_host_name LIKE '%" + sqlEscape(host) + "%'");
        }
        long minDurationMs = toLong(params.getOrDefault("minDurationMs", 0));
        if (minDurationMs > 0) {
            conditions.add("duration >= " + (minDurationMs * 1000)); // duration is stored in µs
        }
        if (Boolean.TRUE.equals(params.get("errorsOnly"))) {
            conditions.add("span_status = 'ERROR'");
        }
        return conditions;
    }

    /**
     * POST body: { stream?, keyword?, service?, minDurationMs?, errorsOnly?,
     *              startTime, endTime, size?, providerAccountId? }
     * Returns the newest root spans (one per trace) enriched with per-trace
     * rollups (span count, error count, service count).
     */
    public Map<String, Object> searchTraces(Long clientId, Map<String, Object> params) {
        String orgId = getEffectiveOrgId(clientId, (String) params.getOrDefault("providerAccountId", null));
        String stream = sqlIdentifier((String) params.getOrDefault("stream", "default"));
        long endTime = toLong(params.getOrDefault("endTime", System.currentTimeMillis()));
        long startTime = toLong(params.getOrDefault("startTime", endTime - 3600_000L));
        int size = (int) params.getOrDefault("size", 100);

        List<String> conditions = traceConditions(params, true);
        // NOTE: select only columns guaranteed to exist across trace sources. eBPF
        // (Beyla) span streams do NOT have http_status_code / http_method columns, and
        // OpenObserve fails the whole query on any unknown column — which previously made
        // the traces list come up empty even though data existed. HTTP status is conveyed
        // via span_status (OK/ERROR) instead.
        String sql = "SELECT trace_id, operation_name, service_name, duration, start_time,"
                + " span_status, status_message"
                + " FROM \"" + stream + "\" WHERE " + String.join(" AND ", conditions)
                + " ORDER BY start_time DESC LIMIT " + size;
        try {
            List<Map<String, Object>> roots = o2TraceQuery(orgId, sql, startTime, endTime, size);

            // Second pass: per-trace rollups for the traces on this page.
            if (!roots.isEmpty()) {
                List<String> ids = new ArrayList<>();
                for (Map<String, Object> r : roots) {
                    Object id = r.get("trace_id");
                    if (id != null) ids.add("'" + sqlEscape(String.valueOf(id)) + "'");
                }
                String rollupSql = "SELECT trace_id, count(*) AS span_count,"
                        + " sum(CASE WHEN span_status = 'ERROR' THEN 1 ELSE 0 END) AS error_count,"
                        + " count(distinct service_name) AS service_count"
                        + " FROM \"" + stream + "\" WHERE trace_id IN (" + String.join(",", ids) + ")"
                        + " GROUP BY trace_id";
                Map<String, Map<String, Object>> byId = new HashMap<>();
                for (Map<String, Object> h : o2TraceQuery(orgId, rollupSql, startTime, endTime, size)) {
                    byId.put(String.valueOf(h.get("trace_id")), h);
                }
                for (Map<String, Object> r : roots) {
                    Map<String, Object> roll = byId.get(String.valueOf(r.get("trace_id")));
                    if (roll != null) {
                        r.put("span_count", roll.get("span_count"));
                        r.put("error_count", roll.get("error_count"));
                        r.put("service_count", roll.get("service_count"));
                    }
                }
            }
            return Map.of("traces", roots, "total", roots.size());
        } catch (Exception e) {
            logger.error("O2 trace search failed for client {}: {}", clientId, e.getMessage());
            return Map.of("traces", Collections.emptyList(), "total", 0, "error", e.getMessage());
        }
    }

    /**
     * POST body: { stream?, keyword?, service?, minDurationMs?, errorsOnly?,
     *              startTime, endTime, interval?, providerAccountId? }
     * Returns summary (count / error / latency percentiles), a volume histogram,
     * the top operations, and the service list for the filter dropdown.
     */
    public Map<String, Object> getTraceStats(Long clientId, Map<String, Object> params) {
        String orgId = getEffectiveOrgId(clientId, (String) params.getOrDefault("providerAccountId", null));
        String stream = sqlIdentifier((String) params.getOrDefault("stream", "default"));
        long endTime = toLong(params.getOrDefault("endTime", System.currentTimeMillis()));
        long startTime = toLong(params.getOrDefault("startTime", endTime - 3600_000L));

        // Whitelisted pattern only — the value is interpolated into SQL.
        String interval = (String) params.getOrDefault("interval", "5 minute");
        if (interval == null || !interval.matches("\\d{1,3} (second|minute|hour|day)s?")) {
            interval = "5 minute";
        }

        String where = String.join(" AND ", traceConditions(params, true));
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String summarySql = "SELECT count(*) AS n,"
                    + " sum(CASE WHEN span_status = 'ERROR' THEN 1 ELSE 0 END) AS errors,"
                    + " avg(duration) AS avg_us,"
                    + " approx_percentile_cont(duration, 0.50) AS p50,"
                    + " approx_percentile_cont(duration, 0.95) AS p95,"
                    + " approx_percentile_cont(duration, 0.99) AS p99"
                    + " FROM \"" + stream + "\" WHERE " + where;
            List<Map<String, Object>> summary = o2TraceQuery(orgId, summarySql, startTime, endTime, 1);
            out.put("summary", summary.isEmpty() ? Map.of("n", 0) : summary.get(0));

            String histSql = "SELECT histogram(_timestamp, '" + interval + "') AS key, count(*) AS total,"
                    + " sum(CASE WHEN span_status = 'ERROR' THEN 1 ELSE 0 END) AS errors"
                    + " FROM \"" + stream + "\" WHERE " + where + " GROUP BY key ORDER BY key";
            out.put("histogram", o2TraceQuery(orgId, histSql, startTime, endTime, 288));

            String opsSql = "SELECT operation_name, service_name, count(*) AS n, avg(duration) AS avg_us,"
                    + " approx_percentile_cont(duration, 0.95) AS p95,"
                    + " sum(CASE WHEN span_status = 'ERROR' THEN 1 ELSE 0 END) AS errors"
                    + " FROM \"" + stream + "\" WHERE " + where
                    + " GROUP BY operation_name, service_name ORDER BY n DESC";
            out.put("operations", o2TraceQuery(orgId, opsSql, startTime, endTime, 10));

            // Service dropdown source — all spans, unfiltered, so the list is stable.
            String svcSql = "SELECT service_name, count(*) AS n FROM \"" + stream + "\""
                    + " GROUP BY service_name ORDER BY n DESC";
            out.put("services", o2TraceQuery(orgId, svcSql, startTime, endTime, 100));

            // Host dropdown source — the originating EC2 of each span. Lets users
            // filter traces down to a single host when several report to one org.
            String hostSql = "SELECT service_host_name, count(*) AS n FROM \"" + stream + "\""
                    + " WHERE service_host_name IS NOT NULL"
                    + " GROUP BY service_host_name ORDER BY n DESC";
            out.put("hosts", o2TraceQuery(orgId, hostSql, startTime, endTime, 100));
            return out;
        } catch (Exception e) {
            logger.error("O2 trace stats failed for client {}: {}", clientId, e.getMessage());
            out.put("summary", Map.of("n", 0));
            out.put("histogram", Collections.emptyList());
            out.put("operations", Collections.emptyList());
            out.put("services", Collections.emptyList());
            out.put("hosts", Collections.emptyList());
            out.put("error", e.getMessage());
            return out;
        }
    }

    /**
     * POST body: { traceId, stream?, startTime?, endTime?, providerAccountId? }
     * Returns every span of one trace, oldest first — powers the waterfall.
     */
    public Map<String, Object> getTraceSpans(Long clientId, Map<String, Object> params) {
        String orgId = getEffectiveOrgId(clientId, (String) params.getOrDefault("providerAccountId", null));
        String stream = sqlIdentifier((String) params.getOrDefault("stream", "default"));
        String traceId = (String) params.getOrDefault("traceId", "");
        if (traceId.isBlank()) return Map.of("spans", Collections.emptyList(), "error", "traceId is required");
        long endTime = toLong(params.getOrDefault("endTime", System.currentTimeMillis()));
        long startTime = toLong(params.getOrDefault("startTime", endTime - 24 * 3600_000L));

        String sql = "SELECT * FROM \"" + stream + "\" WHERE trace_id = '" + sqlEscape(traceId) + "'"
                + " ORDER BY start_time ASC LIMIT 2000";
        try {
            return Map.of("spans", o2TraceQuery(orgId, sql, startTime, endTime, 2000));
        } catch (Exception e) {
            logger.error("O2 trace spans failed for client {} trace {}: {}", clientId, traceId, e.getMessage());
            return Map.of("spans", Collections.emptyList(), "error", e.getMessage());
        }
    }

    // ── Overview Stats ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> getOverviewStats(Long clientId, String providerAccountId) {
        ObservabilityConfig clientConfig = getConfig(clientId);
        String orgId = getEffectiveOrgId(clientId, providerAccountId);
        List<Map<String, Object>> streams = listStreams(clientId, providerAccountId, null);

        long logStreamCount    = streams.stream().filter(s -> "logs".equals(s.get("stream_type"))).count();
        long metricStreamCount = streams.stream().filter(s -> "metrics".equals(s.get("stream_type"))).count();
        long traceStreamCount  = streams.stream().filter(s -> "traces".equals(s.get("stream_type"))).count();

        // Aggregate storage stats from each stream's stats object
        long   totalEvents       = 0;
        double ingestedSizeMb    = 0;
        double compressedSizeMb  = 0;
        double indexSizeMb       = 0;
        for (Map<String, Object> stream : streams) {
            Map<String, Object> st = (Map<String, Object>) stream.get("stats");
            if (st == null) continue;
            // OpenObserve uses "doc_num" (not "doc_count") for event count
            Object rawDocCount = st.get("doc_num");
            totalEvents      += toLong(rawDocCount != null ? rawDocCount : 0);
            ingestedSizeMb   += toDouble(st.getOrDefault("storage_size", 0));
            compressedSizeMb += toDouble(st.getOrDefault("compressed_size", 0));
            indexSizeMb      += toDouble(st.getOrDefault("index_size", 0));
        }

        // Report the retention O2 is really enforcing on the org we just queried, not the
        // value in our database. Where they disagree, flag it so the UI can warn instead of
        // presenting a configured-but-unapplied number as fact.
        Integer actualRetention = fetchOrgRetentionDays(orgId);
        int configuredRetention = clientConfig.getRetentionDays();

        // Whether these numbers describe the selected account or the whole organisation.
        // When an account isn't provisioned the query silently falls back to the client-level
        // org, which used to be a log line only — the user saw organisation-wide totals
        // attributed to the account they had selected.
        boolean accountScoped = providerAccountId != null
                && !providerAccountId.isBlank()
                && !orgId.equals(clientConfig.getO2OrgId());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("orgId", orgId);
        stats.put("scope", accountScoped ? "account" : "client");
        stats.put("accountProvisioned",
                providerAccountId == null || providerAccountId.isBlank() || accountScoped);
        stats.put("retentionDays", actualRetention != null ? actualRetention : configuredRetention);
        stats.put("configuredRetentionDays", configuredRetention);
        stats.put("retentionMismatch", actualRetention != null && actualRetention != configuredRetention);
        stats.put("streamCount", streams.size());
        stats.put("logStreamCount", logStreamCount);
        stats.put("metricStreamCount", metricStreamCount);
        stats.put("traceStreamCount", traceStreamCount);
        stats.put("totalEvents", totalEvents);
        stats.put("ingestedSizeMb", Math.round(ingestedSizeMb * 100.0) / 100.0);
        stats.put("compressedSizeMb", Math.round(compressedSizeMb * 100.0) / 100.0);
        stats.put("indexSizeMb", Math.round(indexSizeMb * 100.0) / 100.0);
        stats.put("streams", streams);
        stats.put("provisionedAt", clientConfig.getCreatedAt());
        return stats;
    }

    private double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private String[] getEffectiveOrgCredentials(Long clientId, String providerAccountId) {
        if (providerAccountId != null && !providerAccountId.isBlank()) {
            Optional<AccountObservabilityConfig> acc =
                    accountConfigRepo.findByClientIdAndProviderAccountId(clientId, providerAccountId);
            if (acc.isPresent()) {
                return new String[]{ acc.get().getO2OrgId(), acc.get().getO2IngestionKey() };
            }
        }
        ObservabilityConfig config = getConfig(clientId);
        return new String[]{ config.getO2OrgId(), config.getO2IngestionKey() };
    }

    // ── Collector Config Generators ───────────────────────────────────────────

    public String generateFluentBitConfig(Long clientId, String providerAccountId) {
        String[] creds = getEffectiveOrgCredentials(clientId, providerAccountId);
        String orgId = creds[0];
        // Http_User and Http_Passwd must be the two halves of the same credential. This paired
        // the admin email with the service account's password, a combination that cannot
        // authenticate as either identity, so the generated config never shipped a single log.
        String[] basic = decodeBasic(requireIngestionCredential(orgId, creds[1]));

        return "[SERVICE]\n" +
                "    Flush        5\n" +
                "    Daemon       Off\n" +
                "    Log_Level    info\n\n" +
                "[INPUT]\n" +
                "    Name              tail\n" +
                "    Tag               kube.*\n" +
                "    Path              /var/log/containers/*.log\n" +
                "    Parser            docker\n" +
                "    Refresh_Interval  5\n" +
                "    Mem_Buf_Limit     50MB\n" +
                "    Skip_Long_Lines   On\n\n" +
                "[FILTER]\n" +
                "    Name                kubernetes\n" +
                "    Match               kube.*\n" +
                "    Kube_URL            https://kubernetes.default.svc:443\n" +
                "    Merge_Log           On\n" +
                "    Keep_Log            Off\n\n" +
                "[OUTPUT]\n" +
                "    Name            http\n" +
                "    Match           *\n" +
                "    Host            " + extractHost(o2Url) + "\n" +
                "    Port            " + extractPort(o2Url) + "\n" +
                "    URI             /api/" + orgId + "/kubernetes/_json\n" +
                "    Format          json\n" +
                "    Http_User       " + basic[0] + "\n" +
                "    Http_Passwd     " + basic[1] + "\n" +
                "    tls             Off\n" +
                "    compress        gzip\n";
    }

    public String generateHelmValues(Long clientId, String providerAccountId) {
        String[] creds = getEffectiveOrgCredentials(clientId, providerAccountId);
        String orgId = creds[0];
        String[] basic = decodeBasic(requireIngestionCredential(orgId, creds[1]));

        return "# XamOps Observability — Fluent Bit Helm values\n" +
                "# Deploy: helm repo add fluent https://fluent.github.io/helm-charts\n" +
                "#         helm install fluent-bit fluent/fluent-bit -f values.yaml\n\n" +
                "config:\n" +
                "  outputs: |\n" +
                "    [OUTPUT]\n" +
                "        Name             http\n" +
                "        Match            *\n" +
                "        Host             " + extractHost(o2Url) + "\n" +
                "        Port             " + extractPort(o2Url) + "\n" +
                "        URI              /api/" + orgId + "/kubernetes/_json\n" +
                "        Format           json\n" +
                "        Http_User        " + basic[0] + "\n" +
                "        Http_Passwd      " + basic[1] + "\n" +
                "        tls              Off\n" +
                "        compress         gzip\n\n" +
                "  filters: |\n" +
                "    [FILTER]\n" +
                "        Name             kubernetes\n" +
                "        Match            kube.*\n" +
                "        Merge_Log        On\n" +
                "        Keep_Log         Off\n\n" +
                "tolerations:\n" +
                "  - operator: Exists\n\n" +
                "daemonset:\n" +
                "  enabled: true\n";
    }

    public String generateOtelCollectorConfig(Long clientId, String providerAccountId) {
        String[] creds = getEffectiveOrgCredentials(clientId, providerAccountId);
        String orgId = creds[0];
        String ingestionKey = requireIngestionCredential(orgId, creds[1]);

        return "# XamOps Observability — OpenTelemetry Collector config\n\n" +
                "receivers:\n" +
                "  hostmetrics:\n" +
                "    collection_interval: 60s\n" +
                "    scrapers:\n" +
                "      cpu:\n" +
                "      memory:\n" +
                "      disk:\n" +
                "      network:\n" +
                "  otlp:\n" +
                "    protocols:\n" +
                "      grpc:\n" +
                "        endpoint: 0.0.0.0:4317\n" +
                "      http:\n" +
                "        endpoint: 0.0.0.0:4318\n\n" +
                "processors:\n" +
                "  batch:\n" +
                "    timeout: 10s\n\n" +
                "exporters:\n" +
                "  otlphttp/logs:\n" +
                "    endpoint: " + o2Url + "/api/" + orgId + "\n" +
                "    headers:\n" +
                "      Authorization: Basic " + ingestionKey + "\n\n" +
                "service:\n" +
                "  pipelines:\n" +
                "    logs:\n" +
                "      receivers: [otlp]\n" +
                "      processors: [batch]\n" +
                "      exporters: [otlphttp/logs]\n" +
                "    metrics:\n" +
                "      receivers: [hostmetrics, otlp]\n" +
                "      processors: [batch]\n" +
                "      exporters: [otlphttp/logs]\n";
    }

    // ── Agent Manifest Generator ──────────────────────────────────────────────

    /**
     * Generates a ready-to-apply Kubernetes Secret YAML with the org ID,
     * O2 URL, and credentials pre-filled.  The user pastes this into kubectl.
     */
    public String generateAgentSecret(Long clientId, String providerAccountId) {
        String[] creds = getEffectiveOrgCredentials(clientId, providerAccountId);
        String orgId = creds[0];
        String authBase64 = requireIngestionCredential(orgId, creds[1]);
        String[] basic = decodeBasic(authBase64);

        // In-cluster OpenObserve URL (K8s service DNS — works once OpenObserve is deployed in EKS)
        String inClusterHost = "openobserve.xamops.svc.cluster.local";
        String inClusterPort = "5080";
        String inClusterUrl  = "http://" + inClusterHost + ":" + inClusterPort;

        return "# ── XamOps Observability Agent Secret ───────────────────────────────────────\n" +
                "# Apply with:  kubectl apply -f agent-secret.yaml\n" +
                "#\n" +
                "# O2_HOST / O2_PORT / O2_TLS are used by Fluent Bit (HTTP output).\n" +
                "# O2_URL  / O2_AUTH_BASE64 are used by OTel Collector (OTLP HTTP).\n" +
                "#\n" +
                "# If using ngrok instead of in-cluster OpenObserve, replace the values\n" +
                "# below with your ngrok hostname, port 443, and TLS On.\n" +
                "apiVersion: v1\n" +
                "kind: Secret\n" +
                "metadata:\n" +
                "  name: o2-agent-credentials\n" +
                "  namespace: xamops-system\n" +
                "type: Opaque\n" +
                "stringData:\n" +
                "  # Full URL (used by OTel Collector)\n" +
                "  O2_URL: \"" + inClusterUrl + "\"\n" +
                "  # Separate host/port/TLS (used by Fluent Bit HTTP output)\n" +
                "  O2_HOST: \"" + inClusterHost + "\"\n" +
                "  O2_PORT: \"" + inClusterPort + "\"\n" +
                "  O2_TLS: \"Off\"\n" +
                "  # Credentials\n" +
                "  O2_ORG_ID: \"" + orgId + "\"\n" +
                "  O2_EMAIL: \"" + basic[0] + "\"\n" +
                "  O2_PASSWORD: \"" + basic[1] + "\"\n" +
                "  O2_AUTH_BASE64: \"" + authBase64 + "\"\n";
    }

    /**
     * Generates the Helm install command for the OpenObserve collector on EKS.
     * All credentials and org ID are pre-filled from the provisioned config.
     */
    public String generateHelmEksCommand(Long clientId, String providerAccountId) {
        String[] creds = getEffectiveOrgCredentials(clientId, providerAccountId);
        String orgId = creds[0];
        String authBase64 = requireIngestionCredential(orgId, creds[1]);
        // EKS Helm runs inside the cluster — prefer in-cluster URL, fall back to external
        String eksUrl = (o2ExternalUrl != null && !o2ExternalUrl.isBlank())
                ? "http://openobserve.xamops.svc.cluster.local:5080"
                : o2Url;

        return "# ── Step 1: Add OpenObserve Helm repo ───────────────────────────────────────\n" +
                "helm repo add openobserve https://charts.openobserve.ai\n" +
                "helm repo update\n\n" +
                "# ── Step 2: Install the collector (one command does everything) ────────────\n" +
                "# Deploys: DaemonSet (logs + node metrics) + Deployment (cluster metrics)\n" +
                "# RBAC, ServiceAccount, ConfigMaps all created automatically.\n" +
                "helm install o2c openobserve/openobserve-collector \\\n" +
                "  --namespace xamops-system \\\n" +
                "  --create-namespace \\\n" +
                "  --set openobserve.endpoint=\"" + eksUrl + "\" \\\n" +
                "  --set openobserve.organizationIdentifier=\"" + orgId + "\" \\\n" +
                "  --set openobserve.authorizationHeader=\"Basic " + authBase64 + "\"\n\n" +
                "# ── Step 3: Verify pods are running ─────────────────────────────────────────\n" +
                "kubectl get pods -n xamops-system\n\n" +
                "# ── Upgrade later ────────────────────────────────────────────────────────────\n" +
                "# helm upgrade o2c openobserve/openobserve-collector -n xamops-system\n\n" +
                "# ── Uninstall ─────────────────────────────────────────────────────────────────\n" +
                "# helm uninstall o2c -n xamops-system\n";
    }

    /**
     * Generates the OTel Collector config + install script for a Linux EC2 instance.
     * Credentials and org ID are inlined so no environment variables are needed.
     */
    public String generateEc2OtelConfig(Long clientId, String providerAccountId) {
        String[] creds = getEffectiveOrgCredentials(clientId, providerAccountId);
        String orgId = creds[0];
        String authBase64 = requireIngestionCredential(orgId, creds[1]);
        // Use external URL — customer EC2s may be in a different VPC than OpenObserve
        String o2Endpoint = getExternalO2Url() + "/api/" + orgId;

        return "# ════════════════════════════════════════════════════════════════════════════\n" +
                "# XamOps Observability — EC2 / Linux VM Setup  (production-grade)\n" +
                "# Collects: structured logs (journald + nginx + app + docker) +\n" +
                "#           full host metrics (CPU / mem / disk / net / process / paging) +\n" +
                "#           application traces — Grafana Beyla (eBPF, zero-code) + OTLP receiver\n" +
                "# ════════════════════════════════════════════════════════════════════════════\n\n" +
                "# ── Step 1: Install OTel Collector Contrib ───────────────────────────────────\n" +
                "# Auto-detect CPU architecture — x86_64 (amd64) vs Graviton/ARM (arm64).\n" +
                "OTEL_VER=0.103.0\n" +
                "case \"$(uname -m)\" in\n" +
                "  x86_64)        OTEL_ARCH=amd64 ;;\n" +
                "  aarch64|arm64) OTEL_ARCH=arm64 ;;\n" +
                "  *)             OTEL_ARCH=amd64 ;;\n" +
                "esac\n" +
                "OTEL_BASE=https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v${OTEL_VER}\n" +
                "if command -v rpm &>/dev/null; then\n" +
                "  # Amazon Linux 2023 / RHEL / CentOS\n" +
                "  curl -sLO \"${OTEL_BASE}/otelcol-contrib_${OTEL_VER}_linux_${OTEL_ARCH}.rpm\"\n" +
                "  sudo rpm -Uvh --force \"otelcol-contrib_${OTEL_VER}_linux_${OTEL_ARCH}.rpm\"\n" +
                "else\n" +
                "  # Ubuntu / Debian\n" +
                "  curl -sLO \"${OTEL_BASE}/otelcol-contrib_${OTEL_VER}_linux_${OTEL_ARCH}.deb\"\n" +
                "  sudo dpkg -i \"otelcol-contrib_${OTEL_VER}_linux_${OTEL_ARCH}.deb\"\n" +
                "fi\n\n" +
                "# ── Step 2: Add otelcol-contrib to systemd-journal group (for journald access) ─\n" +
                "sudo usermod -aG systemd-journal otelcol-contrib 2>/dev/null || true\n\n" +
                "# ── Step 3: Write production-grade config ────────────────────────────────────\n" +
                "sudo mkdir -p /etc/otelcol-contrib\n" +
                "sudo tee /etc/otelcol-contrib/config.yaml > /dev/null << 'EOF'\n" +
                "receivers:\n" +
                "  # ── Journald receiver — works on AL2023 / RHEL9 / Ubuntu (systemd) ──────────\n" +
                "  # Replaces filelog/system and filelog/auth which need /var/log/messages (AL2 only)\n" +
                "  # No 'directory:' — let journalctl read the DEFAULT journal (persistent\n" +
                "  # /var/log/journal when present, else runtime /run/log/journal). Pinning to\n" +
                "  # /run/log/journal misses logs on hosts with persistent journald storage.\n" +
                "  journald:\n" +
                "    start_at: end\n" +
                "    operators:\n" +
                "      - type: severity_parser\n" +
                "        parse_from: body.PRIORITY\n" +
                "        mapping:\n" +
                "          fatal: \"0\"\n" +
                "          error: [\"1\", \"2\", \"3\"]\n" +
                "          warn: \"4\"\n" +
                "          info: [\"5\", \"6\"]\n" +
                "          debug: \"7\"\n" +
                "      # Journald puts the log text in body.MESSAGE (a map field). Move it to\n" +
                "      # body so OpenObserve stores + full-text indexes the actual message.\n" +
                "      - type: move\n" +
                "        from: body.MESSAGE\n" +
                "        to: body\n\n" +
                "  # Nginx access + error logs with structured field extraction\n" +
                "  filelog/nginx:\n" +
                "    include:\n" +
                "      - /var/log/nginx/access.log\n" +
                "      - /var/log/nginx/error.log\n" +
                "    start_at: end\n" +
                "    operators:\n" +
                "      - type: regex_parser\n" +
                "        regex: '^(?P<client_ip>\\S+)\\s+\\S+\\s+\\S+\\s+\\[(?P<timestamp>[^\\]]+)\\]\\s+\"(?P<method>\\w+)\\s+(?P<path>\\S+)\\s+\\S+\"\\s+(?P<status>\\d{3})\\s+(?P<bytes>\\d+)'\n" +
                "        timestamp:\n" +
                "          parse_from: attributes.timestamp\n" +
                "          layout_type: gotime\n" +
                "          layout: '02/Jan/2006:15:04:05 -0700'\n" +
                "        on_error: send\n" +
                "      - type: add\n" +
                "        field: attributes.service.name\n" +
                "        value: nginx\n\n" +
                "  # Application logs — JSON-first with plain-text fallback\n" +
                "  filelog/app:\n" +
                "    include:\n" +
                "      - /var/log/app/*.log\n" +
                "      - /opt/app/logs/*.log\n" +
                "    start_at: end\n" +
                "    multiline:\n" +
                "      line_start_pattern: '^({|\\d{4}-\\d{2}-\\d{2})'\n" +
                "    operators:\n" +
                "      - type: json_parser\n" +
                "        if: 'body matches \"^{\"'\n" +
                "        timestamp:\n" +
                "          parse_from: attributes.timestamp\n" +
                "          layout_type: strptime\n" +
                "          layout: '%Y-%m-%dT%H:%M:%S'\n" +
                "        on_error: send\n" +
                "      - type: severity_parser\n" +
                "        parse_from: attributes.level\n" +
                "        preset: default\n" +
                "        on_error: send\n\n" +
                "  # Docker container logs — stdout/stderr from ALL running containers\n" +
                "  # Requires ACL step at the bottom of this script if Docker is installed\n" +
                "  filelog/docker:\n" +
                "    include:\n" +
                "      - /var/lib/docker/containers/*/*.log\n" +
                "    include_file_path: true\n" +
                "    include_file_name: false\n" +
                "    start_at: end\n" +
                "    operators:\n" +
                "      - type: json_parser\n" +
                "        timestamp:\n" +
                "          parse_from: attributes.time\n" +
                "          layout_type: gotime\n" +
                "          layout: '2006-01-02T15:04:05.999999999Z07:00'\n" +
                "        on_error: send\n" +
                "      - type: move\n" +
                "        from: attributes.log\n" +
                "        to: body\n" +
                "      - type: add\n" +
                "        field: attributes.source\n" +
                "        value: docker\n\n" +
                "  # ── Metrics Receiver ────────────────────────────────────────────────────────\n" +
                "  hostmetrics:\n" +
                "    collection_interval: 30s\n" +
                "    scrapers:\n" +
                "      cpu:\n" +
                "        metrics:\n" +
                "          system.cpu.utilization:\n" +
                "            enabled: true\n" +
                "      memory:\n" +
                "        metrics:\n" +
                "          system.memory.utilization:\n" +
                "            enabled: true\n" +
                "      disk: {}\n" +
                "      network: {}\n" +
                "      load: {}\n" +
                "      filesystem:\n" +
                "        exclude_mount_points:\n" +
                "          mount_points: [/proc, /sys, /dev, /run, /snap]\n" +
                "          match_type: strict\n" +
                "      process:\n" +
                "        mute_process_name_error: true\n" +
                "        mute_process_exe_error: true\n" +
                "        mute_process_io_error: true\n" +
                "        mute_process_user_error: true\n" +
                "      paging: {}\n\n" +
                "  # ── OTLP Receiver — application traces & custom spans ───────────────────────\n" +
                "  otlp:\n" +
                "    protocols:\n" +
                "      grpc:\n" +
                "        endpoint: 0.0.0.0:4317\n" +
                "      http:\n" +
                "        endpoint: 0.0.0.0:4318\n\n" +
                "processors:\n" +
                "  # Must be first in every pipeline — protects the host from OOM\n" +
                "  memory_limiter:\n" +
                "    check_interval: 5s\n" +
                "    limit_mib: 512        # 128=t3.micro  256=t3.small  512=t3.medium+\n" +
                "    spike_limit_mib: 128\n\n" +
                "  # Auto-detect EC2 instance metadata (instance-id, region, instance-type, ami-id)\n" +
                "  resourcedetection:\n" +
                "    detectors: [ec2, system]\n" +
                "    timeout: 5s\n" +
                "    override: false\n\n" +
                "  # Static tags + derive service.instance.id from host.id\n" +
                "  resource:\n" +
                "    attributes:\n" +
                "      - key: service.name\n" +
                "        value: ec2-host\n" +
                "        action: upsert\n" +
                "      - key: source\n" +
                "        value: ec2\n" +
                "        action: upsert\n" +
                "      - key: deployment.environment\n" +
                "        value: prod\n" +
                "        action: upsert\n" +
                "      - key: service.instance.id\n" +
                "        from_attribute: host.id\n" +
                "        action: upsert\n\n" +
                "  # Drop load-balancer health probes — reduce noise & ingestion cost\n" +
                "  filter/healthcheck:\n" +
                "    logs:\n" +
                "      exclude:\n" +
                "        match_type: regexp\n" +
                "        bodies:\n" +
                "          - '.*GET /health.*'\n" +
                "          - '.*GET /healthz.*'\n" +
                "          - '.*GET /readyz.*'\n" +
                "          - '.*GET /livez.*'\n" +
                "          - '.*ELB-HealthChecker.*'\n" +
                "          - '.*kube-probe.*'\n\n" +
                "  # Drop trace-span noise: NTP time-sync (port 123) and EC2 metadata (IMDS)\n" +
                "  # connections. Beyla captures these but they aren't real application calls —\n" +
                "  # dropping them keeps the traces list clean and the error rate honest.\n" +
                "  filter/tracenoise:\n" +
                "    error_mode: ignore\n" +
                "    traces:\n" +
                "      span:\n" +
                "        - 'attributes[\"server.port\"] == 123'\n" +
                "        - 'attributes[\"server.address\"] == \"169.254.169.254\"'\n" +
                "        - 'attributes[\"server.address\"] == \"169.254.169.123\"'\n\n" +
                "  # Small timeout keeps latency low; caps individual payload size\n" +
                "  batch:\n" +
                "    timeout: 5s\n" +
                "    send_batch_size: 512\n" +
                "    send_batch_max_size: 1024\n\n" +
                "exporters:\n" +
                "  otlphttp:\n" +
                "    endpoint: \"" + o2Endpoint + "\"\n" +
                "    headers:\n" +
                "      Authorization: \"Basic " + authBase64 + "\"\n" +
                "      ngrok-skip-browser-warning: \"true\"\n" +
                "    compression: gzip\n" +
                "    retry_on_failure:\n" +
                "      enabled: true\n" +
                "      initial_interval: 5s\n" +
                "      max_interval: 30s\n" +
                "      max_elapsed_time: 300s\n" +
                "    sending_queue:\n" +
                "      enabled: true\n" +
                "      queue_size: 1000\n" +
                "      num_consumers: 4\n\n" +
                "extensions:\n" +
                "  health_check:\n" +
                "    endpoint: 127.0.0.1:13133  # Local only — not exposed to the network\n\n" +
                "service:\n" +
                "  telemetry:\n" +
                "    metrics:\n" +
                "      address: 127.0.0.1:8888  # Agent self-metrics (Prometheus format)\n" +
                "  extensions: [health_check]\n" +
                "  pipelines:\n" +
                "    logs:\n" +
                "      receivers: [journald, filelog/nginx, filelog/app, filelog/docker]\n" +
                "      processors: [memory_limiter, resourcedetection, resource, filter/healthcheck, batch]\n" +
                "      exporters: [otlphttp]\n" +
                "    metrics:\n" +
                "      receivers: [hostmetrics]\n" +
                "      processors: [memory_limiter, resourcedetection, resource, batch]\n" +
                "      exporters: [otlphttp]\n" +
                "    traces:\n" +
                "      receivers: [otlp]\n" +
                "      processors: [memory_limiter, resourcedetection, resource, filter/tracenoise, batch]\n" +
                "      exporters: [otlphttp]\n" +
                "EOF\n\n" +
                "# This file carries the OpenObserve Authorization header in cleartext, and `tee`\n" +
                "# leaves it world-readable (0644) — any local user could read the credential out\n" +
                "# of it. Lock it to the service account's group.\n" +
                "#\n" +
                "# NOT 0600 root:root: the collector runs as the unprivileged 'otelcol-contrib'\n" +
                "# user, so a root-only config makes it fail to start with a permission error on\n" +
                "# its own configuration. 0640 root:otelcol-contrib keeps it off-limits to other\n" +
                "# local users while remaining readable by the process that needs it.\n" +
                "sudo chown root:otelcol-contrib /etc/otelcol-contrib/config.yaml 2>/dev/null \\\n" +
                "  || sudo chown root:root /etc/otelcol-contrib/config.yaml\n" +
                "sudo chmod 640 /etc/otelcol-contrib/config.yaml\n\n" +
                "# ── Step 4: Grant log file read access ──────────────────────────────────────\n" +
                "# Nginx log access (if nginx is installed)\n" +
                "if ! command -v setfacl &>/dev/null; then\n" +
                "  sudo apt-get install -y acl 2>/dev/null || sudo yum install -y acl 2>/dev/null || true\n" +
                "fi\n" +
                "if [ -d /var/log/nginx ]; then\n" +
                "  sudo setfacl -Rm   u:otelcol-contrib:r  /var/log/nginx/ 2>/dev/null || true\n" +
                "  sudo setfacl -d -m u:otelcol-contrib:r  /var/log/nginx/ 2>/dev/null || true\n" +
                "fi\n\n" +
                "# Docker container logs (auto-applied if Docker is installed)\n" +
                "#\n" +
                "# An ACL on /var/lib/docker/containers alone is not enough: the parent\n" +
                "# /var/lib/docker is mode 0710 root:root, so a non-root process cannot traverse\n" +
                "# into it and the receiver fails with \"open .: permission denied\" even though the\n" +
                "# ACL below applied cleanly. The traverse bit on the parent has to be granted too.\n" +
                "# Failures are reported rather than swallowed — this previously printed\n" +
                "# \"container log access granted\" whether or not anything had been granted.\n" +
                "if command -v docker &>/dev/null; then\n" +
                "  docker_acl_ok=true\n" +
                "  sudo setfacl -m  u:otelcol-contrib:x  /var/lib/docker           || docker_acl_ok=false\n" +
                "  sudo setfacl -Rdm u:otelcol-contrib:rX /var/lib/docker/containers/ || docker_acl_ok=false\n" +
                "  sudo setfacl -Rm  u:otelcol-contrib:rX /var/lib/docker/containers/ || docker_acl_ok=false\n" +
                "  if [ \"$docker_acl_ok\" = true ]; then\n" +
                "    echo 'Docker detected — container log access granted'\n" +
                "  else\n" +
                "    echo 'WARNING: Docker detected but log access could NOT be granted.'\n" +
                "    echo '         Container logs will be missing. Check that the acl package is'\n" +
                "    echo '         installed and that the filesystem is mounted with acl support.'\n" +
                "  fi\n" +
                "fi\n\n" +
                "# ── Step 5: Configure auto-restart on failure ───────────────────────────────\n" +
                "# Creates a systemd drop-in so the collector restarts automatically if it crashes\n" +
                "sudo mkdir -p /etc/systemd/system/otelcol-contrib.service.d\n" +
                "sudo tee /etc/systemd/system/otelcol-contrib.service.d/restart.conf > /dev/null << 'EOF'\n" +
                "[Service]\n" +
                "Restart=on-failure\n" +
                "RestartSec=10\n" +
                "EOF\n" +
                "sudo systemctl daemon-reload\n\n" +
                "# ── Step 6: Enable on boot and start ─────────────────────────────────────────\n" +
                "sudo systemctl enable otelcol-contrib\n" +
                "sudo systemctl restart otelcol-contrib\n" +
                "sudo systemctl status otelcol-contrib\n\n" +
                "# ── Step 7: Application traces — Grafana Beyla (eBPF, zero-code) ─────────────\n" +
                "# Beyla auto-instruments HTTP / gRPC / SQL / Redis calls of every process on\n" +
                "# this host WITHOUT any app changes, and ships spans to the local collector\n" +
                "# (:4318) which forwards them to OpenObserve. This is what makes the Traces\n" +
                "# tab populate — the collector alone only *receives* traces, it can't create them.\n" +
                "# Requires a modern kernel (>= 5.8; AL2023 / Ubuntu 22.04+ are fine).\n" +
                "BEYLA_VER=v3.28.0\n" +
                "case \"$(uname -m)\" in\n" +
                "  x86_64)        BEYLA_ARCH=amd64 ;;\n" +
                "  aarch64|arm64) BEYLA_ARCH=arm64 ;;\n" +
                "  *)             BEYLA_ARCH=amd64 ;;\n" +
                "esac\n" +
                "curl -sSL -o /tmp/beyla.tar.gz \\\n" +
                "  \"https://github.com/grafana/beyla/releases/download/${BEYLA_VER}/beyla-linux-${BEYLA_ARCH}-${BEYLA_VER}.tar.gz\"\n" +
                "sudo rm -rf /tmp/beyla-extract && sudo mkdir -p /tmp/beyla-extract\n" +
                "sudo tar -xzf /tmp/beyla.tar.gz -C /tmp/beyla-extract\n" +
                "# Stop the running Beyla first. Copying over a live executable fails with\n" +
                "# \"Text file busy\", and because the copy is not checked the script carried on and\n" +
                "# reported success while leaving the OLD binary in place — so re-running this to\n" +
                "# pick up a new version silently did nothing.\n" +
                "sudo systemctl stop beyla 2>/dev/null || true\n" +
                "beyla_src=\"$(find /tmp/beyla-extract -type f -name beyla | head -1)\"\n" +
                // No `exit` here: these steps are pasted into an interactive root shell, and an
                // exit would close the operator's session rather than skipping a step.
                "if [ -n \"$beyla_src\" ]; then\n" +
                "  sudo cp \"$beyla_src\" /usr/local/bin/beyla\n" +
                "  sudo chmod +x /usr/local/bin/beyla\n" +
                "  echo \"Beyla installed from $beyla_src\"\n" +
                "else\n" +
                "  echo 'ERROR: beyla binary not found in the downloaded archive — traces will not work.'\n" +
                "fi\n\n" +
                "# Beyla config: instrument every service listening on any TCP port, export OTLP\n" +
                "# to the local collector. host.name / EC2 metadata are added by the collector.\n" +
                "sudo mkdir -p /etc/beyla\n" +
                "sudo tee /etc/beyla/beyla.env > /dev/null << 'EOF'\n" +
                "BEYLA_OPEN_PORT=1-65535\n" +
                "OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318\n" +
                "OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf\n" +
                "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=http/protobuf\n" +
                // Distributed tracing: propagate the W3C traceparent at network level so an
                // inbound request and the DB/HTTP calls it triggers stitch into ONE trace
                // (full waterfall) instead of fragmented single spans.
                "BEYLA_BPF_CONTEXT_PROPAGATION=all\n" +
                "EOF\n\n" +
                "sudo tee /etc/systemd/system/beyla.service > /dev/null << 'EOF'\n" +
                "[Unit]\n" +
                "Description=Grafana Beyla — eBPF auto-instrumentation (traces)\n" +
                "After=otelcol-contrib.service\n" +
                "Wants=otelcol-contrib.service\n\n" +
                "[Service]\n" +
                "EnvironmentFile=/etc/beyla/beyla.env\n" +
                "ExecStart=/usr/local/bin/beyla\n" +
                "Restart=on-failure\n" +
                "RestartSec=10\n" +
                "User=root\n\n" +
                "[Install]\n" +
                "WantedBy=multi-user.target\n" +
                "EOF\n" +
                "sudo systemctl daemon-reload\n" +
                "sudo systemctl enable beyla\n" +
                "sudo systemctl restart beyla\n" +
                "sudo systemctl status beyla --no-pager | head -5\n\n" +
                "# ── Verify data is flowing ───────────────────────────────────────────────────\n" +
                "# Stream collector logs\n" +
                "sudo journalctl -u otelcol-contrib -f\n\n" +
                "# Beyla logs (should show 'instrumenting process' as your apps take traffic)\n" +
                "sudo journalctl -u beyla -f\n\n" +
                "# Health check (should return {\\\"status\\\":\\\"Server available\\\"})\n" +
                "curl -s http://127.0.0.1:13133/\n\n" +
                "# View agent self-metrics (queue depth, drop rate, etc.)\n" +
                "curl -s http://127.0.0.1:8888/metrics | grep -E 'otelcol_(exporter|processor|receiver)'\n";
    }

    /**
     * Generates the OTel Collector config + install script for a Linux GCE instance.
     * Same as generateEc2OtelConfig but uses GCE resource detection and GCP labels.
     */
    public String generateGceOtelConfig(Long clientId, String providerAccountId) {
        String[] creds = getEffectiveOrgCredentials(clientId, providerAccountId);
        String orgId = creds[0];
        String authBase64 = requireIngestionCredential(orgId, creds[1]);
        // Use external URL — customer GCE VMs may be in a different VPC/project than OpenObserve
        String o2Endpoint = getExternalO2Url() + "/api/" + orgId;

        return "# ════════════════════════════════════════════════════════════════════════════\n" +
                "# XamOps Observability — GCE / Linux VM Setup  (production-grade)\n" +
                "# Collects: structured logs (journald + nginx + app + docker) +\n" +
                "#           full host metrics (CPU / mem / disk / net / process / paging) +\n" +
                "#           application traces via OTLP receiver\n" +
                "# ════════════════════════════════════════════════════════════════════════════\n\n" +
                "# ── Step 1: Install OTel Collector Contrib ───────────────────────────────────\n" +
                "if command -v rpm &>/dev/null; then\n" +
                "  # RHEL / CentOS\n" +
                "  curl -sLO https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.103.0/otelcol-contrib_0.103.0_linux_amd64.rpm\n" +
                "  sudo rpm -ivh otelcol-contrib_0.103.0_linux_amd64.rpm\n" +
                "else\n" +
                "  # Ubuntu / Debian (default on GCE)\n" +
                "  curl -sLO https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.103.0/otelcol-contrib_0.103.0_linux_amd64.deb\n" +
                "  sudo dpkg -i otelcol-contrib_0.103.0_linux_amd64.deb\n" +
                "fi\n\n" +
                "# ── Step 2: Add otelcol-contrib to systemd-journal group (for journald access) ─\n" +
                "sudo usermod -aG systemd-journal otelcol-contrib 2>/dev/null || true\n\n" +
                "# ── Step 3: Write production-grade config ────────────────────────────────────\n" +
                "sudo mkdir -p /etc/otelcol-contrib\n" +
                "sudo tee /etc/otelcol-contrib/config.yaml > /dev/null << 'EOF'\n" +
                "receivers:\n" +
                "  # ── Journald receiver — works on AL2023 / RHEL9 / Ubuntu (systemd) ──────────\n" +
                "  # No 'directory:' — read the DEFAULT journal (persistent /var/log/journal\n" +
                "  # when present, else runtime). Pinning to /run/log/journal misses logs on\n" +
                "  # hosts with persistent journald storage.\n" +
                "  journald:\n" +
                "    start_at: end\n" +
                "    operators:\n" +
                "      - type: severity_parser\n" +
                "        parse_from: body.PRIORITY\n" +
                "        mapping:\n" +
                "          fatal: \"0\"\n" +
                "          error: [\"1\", \"2\", \"3\"]\n" +
                "          warn: \"4\"\n" +
                "          info: [\"5\", \"6\"]\n" +
                "          debug: \"7\"\n" +
                "      # Journald puts the log text in body.MESSAGE (a map field). Move it to\n" +
                "      # body so OpenObserve stores + full-text indexes the actual message.\n" +
                "      - type: move\n" +
                "        from: body.MESSAGE\n" +
                "        to: body\n\n" +
                "  filelog/nginx:\n" +
                "    include:\n" +
                "      - /var/log/nginx/access.log\n" +
                "      - /var/log/nginx/error.log\n" +
                "    start_at: end\n" +
                "    operators:\n" +
                "      - type: regex_parser\n" +
                "        regex: '^(?P<client_ip>\\S+)\\s+\\S+\\s+\\S+\\s+\\[(?P<timestamp>[^\\]]+)\\]\\s+\"(?P<method>\\w+)\\s+(?P<path>\\S+)\\s+\\S+\"\\s+(?P<status>\\d{3})\\s+(?P<bytes>\\d+)'\n" +
                "        timestamp:\n" +
                "          parse_from: attributes.timestamp\n" +
                "          layout_type: gotime\n" +
                "          layout: '02/Jan/2006:15:04:05 -0700'\n" +
                "        on_error: send\n" +
                "      - type: add\n" +
                "        field: attributes.service.name\n" +
                "        value: nginx\n\n" +
                "  filelog/app:\n" +
                "    include:\n" +
                "      - /var/log/app/*.log\n" +
                "      - /opt/app/logs/*.log\n" +
                "    start_at: end\n" +
                "    multiline:\n" +
                "      line_start_pattern: '^({|\\d{4}-\\d{2}-\\d{2})'\n" +
                "    operators:\n" +
                "      - type: json_parser\n" +
                "        if: 'body matches \"^{\"'\n" +
                "        timestamp:\n" +
                "          parse_from: attributes.timestamp\n" +
                "          layout_type: strptime\n" +
                "          layout: '%Y-%m-%dT%H:%M:%S'\n" +
                "        on_error: send\n" +
                "      - type: severity_parser\n" +
                "        parse_from: attributes.level\n" +
                "        preset: default\n" +
                "        on_error: send\n\n" +
                "  filelog/docker:\n" +
                "    include:\n" +
                "      - /var/lib/docker/containers/*/*.log\n" +
                "    include_file_path: true\n" +
                "    include_file_name: false\n" +
                "    start_at: end\n" +
                "    operators:\n" +
                "      - type: json_parser\n" +
                "        timestamp:\n" +
                "          parse_from: attributes.time\n" +
                "          layout_type: gotime\n" +
                "          layout: '2006-01-02T15:04:05.999999999Z07:00'\n" +
                "        on_error: send\n" +
                "      - type: move\n" +
                "        from: attributes.log\n" +
                "        to: body\n" +
                "      - type: add\n" +
                "        field: attributes.source\n" +
                "        value: docker\n\n" +
                "  hostmetrics:\n" +
                "    collection_interval: 30s\n" +
                "    scrapers:\n" +
                "      cpu:\n" +
                "        metrics:\n" +
                "          system.cpu.utilization:\n" +
                "            enabled: true\n" +
                "      memory:\n" +
                "        metrics:\n" +
                "          system.memory.utilization:\n" +
                "            enabled: true\n" +
                "      disk: {}\n" +
                "      network: {}\n" +
                "      load: {}\n" +
                "      filesystem:\n" +
                "        exclude_mount_points:\n" +
                "          mount_points: [/proc, /sys, /dev, /run, /snap]\n" +
                "          match_type: strict\n" +
                "      process:\n" +
                "        mute_process_name_error: true\n" +
                "        mute_process_exe_error: true\n" +
                "        mute_process_io_error: true\n" +
                "        mute_process_user_error: true\n" +
                "      paging: {}\n\n" +
                "  otlp:\n" +
                "    protocols:\n" +
                "      grpc:\n" +
                "        endpoint: 0.0.0.0:4317\n" +
                "      http:\n" +
                "        endpoint: 0.0.0.0:4318\n\n" +
                "processors:\n" +
                "  memory_limiter:\n" +
                "    check_interval: 5s\n" +
                "    limit_mib: 512\n" +
                "    spike_limit_mib: 128\n\n" +
                "  # Auto-detect GCE instance metadata (instance-id, zone, project-id, machine-type)\n" +
                "  resourcedetection:\n" +
                "    detectors: [gce, system]\n" +
                "    timeout: 5s\n" +
                "    override: false\n\n" +
                "  resource:\n" +
                "    attributes:\n" +
                "      - key: service.name\n" +
                "        value: gce-host\n" +
                "        action: upsert\n" +
                "      - key: source\n" +
                "        value: gcp\n" +
                "        action: upsert\n" +
                "      - key: deployment.environment\n" +
                "        value: prod\n" +
                "        action: upsert\n" +
                "      - key: service.instance.id\n" +
                "        from_attribute: host.id\n" +
                "        action: upsert\n\n" +
                "  filter/healthcheck:\n" +
                "    logs:\n" +
                "      exclude:\n" +
                "        match_type: regexp\n" +
                "        bodies:\n" +
                "          - '.*GET /health.*'\n" +
                "          - '.*GET /healthz.*'\n" +
                "          - '.*GET /readyz.*'\n" +
                "          - '.*GET /livez.*'\n" +
                "          - '.*GoogleHC.*'\n" +
                "          - '.*kube-probe.*'\n\n" +
                "  # Drop trace-span noise: NTP time-sync (port 123) and GCE metadata server\n" +
                "  # connections. Beyla captures these but they aren't real application calls.\n" +
                "  filter/tracenoise:\n" +
                "    error_mode: ignore\n" +
                "    traces:\n" +
                "      span:\n" +
                "        - 'attributes[\"server.port\"] == 123'\n" +
                "        - 'attributes[\"server.address\"] == \"169.254.169.254\"'\n" +
                "        - 'attributes[\"server.address\"] == \"metadata.google.internal\"'\n\n" +
                "  batch:\n" +
                "    timeout: 5s\n" +
                "    send_batch_size: 512\n" +
                "    send_batch_max_size: 1024\n\n" +
                "exporters:\n" +
                "  otlphttp:\n" +
                "    endpoint: \"" + o2Endpoint + "\"\n" +
                "    headers:\n" +
                "      Authorization: \"Basic " + authBase64 + "\"\n" +
                "      ngrok-skip-browser-warning: \"true\"\n" +
                "    compression: gzip\n" +
                "    retry_on_failure:\n" +
                "      enabled: true\n" +
                "      initial_interval: 5s\n" +
                "      max_interval: 30s\n" +
                "      max_elapsed_time: 300s\n" +
                "    sending_queue:\n" +
                "      enabled: true\n" +
                "      queue_size: 1000\n" +
                "      num_consumers: 4\n\n" +
                "extensions:\n" +
                "  health_check:\n" +
                "    endpoint: 127.0.0.1:13133\n\n" +
                "service:\n" +
                "  telemetry:\n" +
                "    metrics:\n" +
                "      address: 127.0.0.1:8888\n" +
                "  extensions: [health_check]\n" +
                "  pipelines:\n" +
                "    logs:\n" +
                "      receivers: [journald, filelog/nginx, filelog/app, filelog/docker]\n" +
                "      processors: [memory_limiter, resourcedetection, resource, filter/healthcheck, batch]\n" +
                "      exporters: [otlphttp]\n" +
                "    metrics:\n" +
                "      receivers: [hostmetrics]\n" +
                "      processors: [memory_limiter, resourcedetection, resource, batch]\n" +
                "      exporters: [otlphttp]\n" +
                "    traces:\n" +
                "      receivers: [otlp]\n" +
                "      processors: [memory_limiter, resourcedetection, resource, filter/tracenoise, batch]\n" +
                "      exporters: [otlphttp]\n" +
                "EOF\n\n" +
                "# The config carries the OpenObserve Authorization header in cleartext and `tee`\n" +
                "# leaves it world-readable. 0640 root:otelcol-contrib — not 0600 root:root, which\n" +
                "# the unprivileged collector process could not read, so it would refuse to start.\n" +
                "sudo chown root:otelcol-contrib /etc/otelcol-contrib/config.yaml 2>/dev/null \\\n" +
                "  || sudo chown root:root /etc/otelcol-contrib/config.yaml\n" +
                "sudo chmod 640 /etc/otelcol-contrib/config.yaml\n\n" +
                "# ── Step 4: Grant log file read access ──────────────────────────────────────\n" +
                "if ! command -v setfacl &>/dev/null; then\n" +
                "  sudo apt-get install -y acl 2>/dev/null || sudo yum install -y acl 2>/dev/null || true\n" +
                "fi\n" +
                "if [ -d /var/log/nginx ]; then\n" +
                "  sudo setfacl -Rm   u:otelcol-contrib:r  /var/log/nginx/ 2>/dev/null || true\n" +
                "  sudo setfacl -d -m u:otelcol-contrib:r  /var/log/nginx/ 2>/dev/null || true\n" +
                "fi\n" +
                "# /var/lib/docker is 0710 root:root, so an ACL on containers/ alone leaves the\n" +
                "# receiver unable to traverse into it (\"open .: permission denied\"). Grant the\n" +
                "# traverse bit on the parent too, and say so when it could not be done.\n" +
                "if command -v docker &>/dev/null; then\n" +
                "  docker_acl_ok=true\n" +
                "  sudo setfacl -m  u:otelcol-contrib:x  /var/lib/docker           || docker_acl_ok=false\n" +
                "  sudo setfacl -Rdm u:otelcol-contrib:rX /var/lib/docker/containers/ || docker_acl_ok=false\n" +
                "  sudo setfacl -Rm  u:otelcol-contrib:rX /var/lib/docker/containers/ || docker_acl_ok=false\n" +
                "  if [ \"$docker_acl_ok\" != true ]; then\n" +
                "    echo 'WARNING: Docker detected but log access could NOT be granted — container'\n" +
                "    echo '         logs will be missing. Check the acl package and mount options.'\n" +
                "  fi\n" +
                "fi\n\n" +
                "# ── Step 5: Configure auto-restart on failure ───────────────────────────────\n" +
                "sudo mkdir -p /etc/systemd/system/otelcol-contrib.service.d\n" +
                "sudo tee /etc/systemd/system/otelcol-contrib.service.d/restart.conf > /dev/null << 'EOF'\n" +
                "[Service]\n" +
                "Restart=on-failure\n" +
                "RestartSec=10\n" +
                "EOF\n" +
                "sudo systemctl daemon-reload\n\n" +
                "# ── Step 6: Enable on boot and start ─────────────────────────────────────────\n" +
                "sudo systemctl enable otelcol-contrib\n" +
                "sudo systemctl restart otelcol-contrib\n" +
                "sudo systemctl status otelcol-contrib\n\n" +
                "# ── Verify data is flowing ───────────────────────────────────────────────────\n" +
                "sudo journalctl -u otelcol-contrib -f\n" +
                "curl -s http://127.0.0.1:13133/\n" +
                "curl -s http://127.0.0.1:8888/metrics | grep -E 'otelcol_(exporter|processor|receiver)'\n";
    }

    // ── Cluster Discovery ─────────────────────────────────────────────────────

    /**
     * Returns EKS clusters registered under the active cloud account (or all accounts
     * for this client when providerAccountId is null), each with clusterName + region.
     */
    public List<Map<String, String>> getClusters(Long clientId, String providerAccountId) {
        List<CloudAccount> accounts;
        if (providerAccountId != null && !providerAccountId.isBlank()) {
            CloudAccount acc = cloudAccountRepo.findByProviderAccountId(providerAccountId).orElse(null);
            accounts = (acc != null && acc.getClient().getId().equals(clientId))
                    ? List.of(acc) : List.of();
        } else {
            accounts = cloudAccountRepo.findByClientId(clientId);
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (CloudAccount acc : accounts) {
            for (KubernetesClusterConfig c : clusterConfigRepo.findByCloudAccountId(acc.getId())) {
                Map<String, String> m = new HashMap<>();
                m.put("clusterName", c.getClusterName());
                m.put("region", c.getRegion() != null ? c.getRegion() : "");
                result.add(m);
            }
        }
        return result;
    }

    // ── Config helpers ────────────────────────────────────────────────────────

    public ObservabilityConfig getConfig(Long clientId) {
        return configRepo.findByClientId(clientId)
                .orElseThrow(() -> new RuntimeException("Observability not provisioned for this tenant"));
    }

    /**
     * Not @Transactional — applyRetentionByType issues one PUT per stream in a loop,
     * which previously ran inside the transaction and could hold a JDBC connection for
     * minutes on a tenant with many streams. The DB write is committed first, then the
     * O2 calls are made with no connection held.
     */
    public ObservabilityConfig updateConfig(Long clientId, int retentionDays) {
        ObservabilityConfig config = getConfig(clientId);
        config.setRetentionDays(retentionDays);
        config = configRepo.save(config);

        // Apply to the client-level org and to every per-account org belonging to this
        // client.
        //
        // Account orgs were previously ignored here, which made the retention setting
        // largely cosmetic: agent data lands in the per-account org, so changing retention
        // in the UI updated an org that holds little or nothing while the org actually
        // storing the metrics kept whatever it was created with (often O2's 7-day default).
        // The caller is responsible for pushing the value to O2 (see
        // ObservabilityRetentionReconciler). That work is hundreds of sequential HTTP calls
        // for a tenant with several accounts and many streams, so it must not run on the
        // request thread — the DB value is saved here and the UI can confirm immediately.
        return config;
    }

    /**
     * Every O2 org that holds data for a client: the client-level org plus one per cloud
     * account. Retention has to be applied to all of them — agent data lands in the
     * per-account orgs, so updating only the client-level org left the orgs that actually
     * store the metrics on whatever they were created with.
     */
    public List<String> listOrgIdsForClient(Long clientId) {
        List<String> orgIds = new ArrayList<>();
        configRepo.findByClientId(clientId)
                .map(ObservabilityConfig::getO2OrgId)
                .filter(Objects::nonNull)
                .ifPresent(orgIds::add);
        accountConfigRepo.findByClientId(clientId).stream()
                .map(AccountObservabilityConfig::getO2OrgId)
                .filter(Objects::nonNull)
                .filter(id -> !orgIds.contains(id))
                .forEach(orgIds::add);
        return orgIds;
    }

    // Package-private so ObservabilityRetentionReconciler can drive it off the request thread.
    @SuppressWarnings("unchecked")
    void applyRetentionByType(String orgId, String streamType, int retentionDays) {
        HttpHeaders headers = buildAdminHeaders();
        try {
            String listUrl = o2Url + "/api/" + orgId + "/streams?type=" + streamType;
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    listUrl, HttpMethod.GET, new HttpEntity<>(headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            if (resp.getBody() == null) return;

            List<Map<String, Object>> streams =
                    (List<Map<String, Object>>) resp.getBody().getOrDefault("list", List.of());

            for (Map<String, Object> stream : streams) {
                String name = (String) stream.get("name");
                if (name == null) continue;
                try {
                    String settingsUrl = o2Url + "/api/" + orgId + "/streams/" + name
                            + "/settings?type=" + streamType;
                    Map<String, Object> body = Map.of("data_retention", retentionDays);
                    restTemplate.exchange(settingsUrl, HttpMethod.PUT,
                            new HttpEntity<>(body, headers),
                            new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
                    logger.debug("Set retention={}d on stream {}/{} ({})", retentionDays, orgId, name, streamType);
                } catch (Exception e) {
                    logger.warn("Could not update retention for stream {}/{}: {}", orgId, name, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("applyRetentionByType failed for org={} type={}: {}", orgId, streamType, e.getMessage());
        }
    }

    /**
     * Sets the org-level default data retention in OpenObserve.
     * All streams created after this call (e.g. by the OTel collector) will inherit this value
     * instead of OpenObserve's built-in 7-day default.
     */
    /**
     * Reads the retention OpenObserve is actually enforcing on an org.
     *
     * The Overview tab used to display the retention value stored in our own database, which
     * is only what we intended, not what O2 applied. Those can disagree — a per-account org
     * created before its client config existed silently kept O2's 7-day default while the UI
     * confidently reported 30 days. Returns null when the value can't be determined, so
     * callers can fall back rather than invent a number.
     */
    @SuppressWarnings("unchecked")
    Integer fetchOrgRetentionDays(String orgId) {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    o2Url + "/api/" + orgId + "/settings",
                    HttpMethod.GET,
                    new HttpEntity<>(buildAdminHeaders()),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            Map<String, Object> body = resp.getBody();
            if (body == null) return null;

            // O2 has returned this both flat and nested under "data" across versions.
            Object retention = body.get("data_retention");
            if (retention == null && body.get("data") instanceof Map) {
                retention = ((Map<String, Object>) body.get("data")).get("data_retention");
            }
            if (retention instanceof Number) return ((Number) retention).intValue();
            if (retention != null) return Integer.parseInt(String.valueOf(retention));
        } catch (Exception e) {
            logger.warn("Could not read retention for org={}: {}", orgId, e.getMessage());
        }
        return null;
    }

    // Package-private so ObservabilityRetentionReconciler can drive it off the request thread.
    void applyOrgDefaultRetention(String orgId, int retentionDays) {
        HttpHeaders headers = buildAdminHeaders();
        try {
            Map<String, Object> body = Map.of("data_retention", retentionDays);
            restTemplate.exchange(
                    o2Url + "/api/" + orgId + "/settings",
                    HttpMethod.PUT,
                    new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            logger.info("Set org-level default retention={}d for org={}", retentionDays, orgId);
        } catch (Exception e) {
            logger.warn("applyOrgDefaultRetention failed for org={}: {}", orgId, e.getMessage());
        }
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listAlerts(Long clientId, String providerAccountId) {
        String orgId = getEffectiveOrgId(clientId, providerAccountId);
        HttpHeaders headers = buildAdminHeaders();
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    o2Url + "/api/" + orgId + "/alerts",
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (resp.getBody() != null && resp.getBody().containsKey("list")) {
                return (List<Map<String, Object>>) resp.getBody().get("list");
            }
        } catch (Exception e) {
            logger.error("listAlerts failed for client {}: {}", clientId, e.getMessage());
        }
        return Collections.emptyList();
    }

    public Map<String, Object> createAlert(Long clientId, String providerAccountId, Map<String, Object> alertConfig) {
        String orgId = getEffectiveOrgId(clientId, providerAccountId);
        HttpHeaders headers = buildAdminHeaders();
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    o2Url + "/api/" + orgId + "/alerts",
                    new HttpEntity<>(alertConfig, headers), Map.class);
            return resp.getBody() != null ? resp.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            logger.error("createAlert failed for client {}: {}", clientId, e.getMessage());
            throw new RuntimeException("Failed to create alert: " + e.getMessage());
        }
    }

    public void deleteAlert(Long clientId, String providerAccountId, String streamType, String streamName, String alertName) {
        String orgId = getEffectiveOrgId(clientId, providerAccountId);
        HttpHeaders headers = buildAdminHeaders();
        try {
            restTemplate.exchange(
                    o2Url + "/api/" + orgId + "/alerts/" + streamType + "/" + streamName + "/" + alertName,
                    HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);
        } catch (Exception e) {
            logger.error("deleteAlert failed for client {}: {}", clientId, e.getMessage());
            throw new RuntimeException("Failed to delete alert: " + e.getMessage());
        }
    }

    public void toggleAlert(Long clientId, String providerAccountId,
                            String streamType, String streamName, String alertName, boolean enable) {
        String orgId = getEffectiveOrgId(clientId, providerAccountId);
        HttpHeaders headers = buildAdminHeaders();
        try {
            restTemplate.exchange(
                    o2Url + "/api/" + orgId + "/alerts/" + streamType + "/" + streamName + "/" + alertName
                            + "/enable?value=" + enable,
                    HttpMethod.PUT, new HttpEntity<>(headers), Map.class);
        } catch (Exception e) {
            logger.error("toggleAlert failed for client {}: {}", clientId, e.getMessage());
            throw new RuntimeException("Failed to toggle alert: " + e.getMessage());
        }
    }

    // ── Alert Destinations ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listDestinations(Long clientId, String providerAccountId) {
        String orgId = getEffectiveOrgId(clientId, providerAccountId);
        HttpHeaders headers = buildAdminHeaders();
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    o2Url + "/api/" + orgId + "/alert_destinations",
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (resp.getBody() != null && resp.getBody().containsKey("list")) {
                return (List<Map<String, Object>>) resp.getBody().get("list");
            }
        } catch (Exception e) {
            logger.error("listDestinations failed for client {}: {}", clientId, e.getMessage());
        }
        return Collections.emptyList();
    }

    public Map<String, Object> saveDestination(Long clientId, String providerAccountId, Map<String, Object> destConfig) {
        String orgId = getEffectiveOrgId(clientId, providerAccountId);
        HttpHeaders headers = buildAdminHeaders();
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    o2Url + "/api/" + orgId + "/alert_destinations",
                    new HttpEntity<>(destConfig, headers), Map.class);
            return resp.getBody() != null ? resp.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            logger.error("saveDestination failed for client {}: {}", clientId, e.getMessage());
            throw new RuntimeException("Failed to save destination: " + e.getMessage());
        }
    }

    public void deleteDestination(Long clientId, String providerAccountId, String destName) {
        String orgId = getEffectiveOrgId(clientId, providerAccountId);
        HttpHeaders headers = buildAdminHeaders();
        try {
            restTemplate.exchange(
                    o2Url + "/api/" + orgId + "/alert_destinations/" + destName,
                    HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);
        } catch (Exception e) {
            logger.error("deleteDestination failed for client {}: {}", clientId, e.getMessage());
            throw new RuntimeException("Failed to delete destination: " + e.getMessage());
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private HttpHeaders buildAdminHeaders() {
        String credentials = adminEmail + ":" + adminPassword;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String extractHost(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private String extractPort(String url) {
        try {
            int port = new java.net.URL(url).getPort();
            return port == -1 ? "5080" : String.valueOf(port);
        } catch (Exception e) {
            return "5080";
        }
    }

    private String extractPassword(String base64Token) {
        return decodeBasic(base64Token)[1];
    }

    /**
     * Splits a base64 {@code Basic} credential into {user, pass}.
     *
     * Ingestion keys are stored as {@code base64(identifier:token)} — the shape every collector
     * runtime needs, whether it wants a whole header (OTel) or the halves separately
     * (Fluent Bit's Http_User/Http_Passwd, the Go agent's username/password).
     *
     * Returns {@code {"", token}} for a value that isn't in that shape, so callers still fail
     * on a missing username rather than silently pairing it with the wrong account.
     */
    private static String[] decodeBasic(String base64Token) {
        try {
            String decoded = new String(Base64.getDecoder().decode(base64Token), StandardCharsets.UTF_8);
            int sep = decoded.indexOf(':');
            if (sep < 0) return new String[]{ "", decoded };
            return new String[]{ decoded.substring(0, sep), decoded.substring(sep + 1) };
        } catch (Exception e) {
            return new String[]{ "", base64Token };
        }
    }

    /**
     * The org-scoped ingestion credential for a tenant/account, or a hard failure.
     *
     * Every collector config handed to a customer used to embed
     * {@code base64(adminEmail:adminPassword)} — the OpenObserve <em>root</em> credential. In
     * OpenObserve the org is only a path segment and root is global, so any customer holding
     * one of those configs could swap the org in the URL and read or write every other
     * tenant's logs, metrics and traces.
     *
     * Per-org service accounts with {@code role: member} are already provisioned at tenant and
     * account setup ({@code createServiceAccount}); this is the accessor that makes the
     * generators use them. It throws rather than degrading, because the only fallback
     * available is the credential this method exists to stop distributing.
     */
    private String requireIngestionCredential(Long clientId, String providerAccountId) {
        String[] creds = getEffectiveOrgCredentials(clientId, providerAccountId);
        return requireIngestionCredential(creds[0], creds[1]);
    }

    private String requireIngestionCredential(String orgId, String ingestionKey) {
        if (orgId == null || orgId.isBlank()) {
            throw new IllegalStateException(
                    "Observability is not provisioned for this account yet, so no collector "
                            + "configuration can be issued. Open the Observability tab to provision it, "
                            + "then retry.");
        }
        if (ingestionKey == null || ingestionKey.isBlank()) {
            throw new IllegalStateException(
                    "No org-scoped ingestion credential exists for org '" + orgId + "'. A collector "
                            + "configuration cannot be issued without one — issuing admin credentials "
                            + "instead would grant this host access to every tenant's data. "
                            + "Re-provision observability for this account to create the service account.");
        }
        // Rows provisioned before the admin fallback was removed have the *admin* credential
        // stored in o2_ingestion_key: createServiceAccount used to return
        // base64(adminEmail:adminPassword) on any failure, and the caller persisted it. Removing
        // that fallback stopped new rows from being poisoned but left existing ones intact, so
        // without this check every already-provisioned tenant would keep being issued collector
        // configs containing the root credential — and the check above would report success.
        //
        // Treated as absent rather than repaired in place: minting a replacement needs a call to
        // OpenObserve, which belongs in provisioning, not in a config generator.
        if (isAdminCredential(ingestionKey)) {
            throw new IllegalStateException(
                    "The stored ingestion credential for org '" + orgId + "' is the OpenObserve admin "
                            + "credential, left behind by an earlier provisioning fallback. Issuing it "
                            + "would give this host access to every tenant's data. Re-provision "
                            + "observability for this account to replace it with a scoped service account.");
        }
        return ingestionKey;
    }

    /** True when a stored credential is really the shared admin one wearing an ingestion key's clothes. */
    private boolean isAdminCredential(String base64Token) {
        String[] parts = decodeBasic(base64Token);
        return adminEmail != null && adminEmail.equalsIgnoreCase(parts[0]);
    }

    /**
     * True when a stored ingestion key cannot be issued and must be replaced.
     *
     * Covers both a key that was never created and one that is the admin credential persisted by
     * the old provisioning fallback. Provisioning uses this to repair an existing row, which is
     * what makes the problem fixable from the UI instead of requiring a manual database update.
     */
    private boolean needsIngestionKeyRepair(String ingestionKey) {
        return ingestionKey == null || ingestionKey.isBlank() || isAdminCredential(ingestionKey);
    }

    private long toLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) {
            try { return Long.parseLong((String) val); }
            catch (Exception ignored) {
                try { return (long) Double.parseDouble((String) val); }
                catch (Exception e) { return 0L; }
            }
        }
        return 0L;
    }
}
