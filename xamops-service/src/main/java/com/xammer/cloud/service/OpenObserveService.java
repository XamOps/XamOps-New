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
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    @Value("${openobserve.admin.email:root@example.com}")
    private String adminEmail;

    @Value("${openobserve.admin.password:Complexpass#123}")
    private String adminPassword;

    private String getExternalO2Url() {
        return (o2ExternalUrl != null && !o2ExternalUrl.isBlank()) ? o2ExternalUrl : o2Url;
    }

    /**
     * Returns OpenObserve connection details for agent self-configuration.
     * Resolves the correct org ID by looking up the provider account ID
     * (e.g. AWS account number, GCP project ID) in the observability config.
     * Falls back to "default" if the account has not been provisioned yet.
     */
    public Map<String, String> getAgentConfig(String providerAccountId) {
        String orgId = resolveOrgIdForAccount(providerAccountId);
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("url", getExternalO2Url());
        cfg.put("org", orgId);
        cfg.put("username", adminEmail);
        cfg.put("password", adminPassword);
        return cfg;
    }

    private String resolveOrgIdForAccount(String providerAccountId) {
        if (providerAccountId == null || providerAccountId.isBlank()) {
            return resolveClientLevelOrgId();
        }
        try {
            Optional<CloudAccount> account = cloudAccountRepo.findByProviderAccountId(providerAccountId);
            if (account.isEmpty()) return resolveClientLevelOrgId();

            CloudAccount ca = account.get();
            // Try account-scoped org first
            Optional<AccountObservabilityConfig> accountCfg =
                    accountConfigRepo.findByClientIdAndCloudAccountId(ca.getClient().getId(), ca.getId());
            if (accountCfg.isPresent() && accountCfg.get().getO2OrgId() != null) {
                return accountCfg.get().getO2OrgId();
            }
            // Fall back to client-level org
            Optional<ObservabilityConfig> clientCfg = configRepo.findByClientId(ca.getClient().getId());
            if (clientCfg.isPresent() && clientCfg.get().getO2OrgId() != null) {
                return clientCfg.get().getO2OrgId();
            }
        } catch (Exception e) {
            logger.warn("Could not resolve O2 org for account {}: {}", providerAccountId, e.getMessage());
        }
        return "default";
    }

    private String resolveClientLevelOrgId() {
        // If only one client exists (single-tenant dev setup), use that org
        List<ObservabilityConfig> all = configRepo.findAll();
        if (all.size() == 1 && all.get(0).getO2OrgId() != null) {
            return all.get(0).getO2OrgId();
        }
        return "default";
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
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    // ── Tenant Provisioning ───────────────────────────────────────────────────

    @Transactional
    public ObservabilityConfig provisionTenant(Client client) {
        Optional<ObservabilityConfig> existing = configRepo.findByClientId(client.getId());
        if (existing.isPresent()) {
            ObservabilityConfig cfg = existing.get();
            // Return immediately if already fully provisioned
            if (cfg.getO2OrgId() != null) return cfg;
            // Previous provisioning attempt saved the record but failed to create the O2 org —
            // re-attempt org creation and update the existing row in-place.
            logger.info("Re-provisioning O2 org for client {} (previous attempt left null orgId)", client.getId());
            try {
                String orgId = createO2OrgAndGetIdentifier(client.getName(), client.getId());
                String ingestionKey = createServiceAccount(orgId, "ingest-" + client.getId());
                String apiKey       = createServiceAccount(orgId, "api-" + client.getId());
                cfg.setO2OrgId(orgId);
                cfg.setO2IngestionKey(ingestionKey);
                cfg.setO2ApiKey(apiKey);
                ObservabilityConfig saved = configRepo.save(cfg);
                applyOrgDefaultRetention(orgId, saved.getRetentionDays());
                return saved;
            } catch (Exception e) {
                logger.error("Re-provisioning failed for client {}: {}", client.getId(), e.getMessage());
                throw new RuntimeException("Failed to re-provision observability: " + e.getMessage(), e);
            }
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

            // Apply org-level retention so agent-created streams don't inherit O2's 7-day default
            applyOrgDefaultRetention(orgId, saved.getRetentionDays());
            return saved;
        } catch (Exception e) {
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

    private String createServiceAccount(String orgId, String email) {
        HttpHeaders headers = buildAdminHeaders();
        Map<String, String> body = Map.of(
                "email", email + "@xamops.internal",
                "role", "member"
        );
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    o2Url + "/api/" + orgId + "/service_accounts",
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            if (resp.getBody() != null && resp.getBody().containsKey("token")) {
                return (String) resp.getBody().get("token");
            }
        } catch (Exception e) {
            logger.warn("Could not create service account {}: {}", email, e.getMessage());
        }
        // Fallback: use admin credentials (works for all O2 API calls)
        String credentials = adminEmail + ":" + adminPassword;
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    // ── Per-Account Provisioning ───────────────────────────────────────────────

    /**
     * Provisions (or returns existing) an O2 org for a specific cloud account.
     * Each cloud account gets its own isolated O2 org for log/metric isolation.
     */
    @Transactional
    public AccountObservabilityConfig provisionForAccount(Client client, String providerAccountId) {
        // Return existing config if already fully provisioned
        Optional<AccountObservabilityConfig> existing =
                accountConfigRepo.findByClientIdAndProviderAccountId(client.getId(), providerAccountId);
        if (existing.isPresent() && existing.get().getO2OrgId() != null) return existing.get();

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

        // Inherit retention from the client-level config so streams don't use O2's 7-day default
        configRepo.findByClientId(client.getId()).ifPresent(clientCfg ->
                applyOrgDefaultRetention(orgId, clientCfg.getRetentionDays()));
        return saved;
    }

    /**
     * Returns the O2 org ID to use for a query.
     * If providerAccountId is given and provisioned → use account-scoped org.
     * Otherwise fall back to the client-level org.
     */
    private String getEffectiveOrgId(Long clientId, String providerAccountId) {
        if (providerAccountId != null && !providerAccountId.isBlank()) {
            Optional<AccountObservabilityConfig> acc =
                    accountConfigRepo.findByClientIdAndProviderAccountId(clientId, providerAccountId);
            if (acc.isPresent()) return acc.get().getO2OrgId();
            // Account not provisioned yet — still return client-level org as fallback
            logger.warn("Account {} not yet provisioned for client {}, falling back to client org", providerAccountId, clientId);
        }
        return getConfig(clientId).getO2OrgId();
    }

    // ── Log Search ────────────────────────────────────────────────────────────

    public Map<String, Object> searchLogs(Long clientId, Map<String, Object> params) {
        String providerAccountId = (String) params.getOrDefault("providerAccountId", null);
        String orgId = getEffectiveOrgId(clientId, providerAccountId);

        String stream = (String) params.getOrDefault("stream", "default");
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
            sqlStr = rawSql;
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

        String stream = (String) params.getOrDefault("stream", "default");
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
        String stream = (String) params.getOrDefault("stream", "default");
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
        String stream = (String) params.getOrDefault("stream", "default");
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
        String stream = (String) params.getOrDefault("stream", "default");
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
        String stream = (String) params.getOrDefault("stream", "default");
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

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("orgId", orgId);
        stats.put("retentionDays", clientConfig.getRetentionDays());
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
        String ingestionKey = creds[1];

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
                "    Http_User       " + adminEmail + "\n" +
                "    Http_Passwd     " + new String(Base64.getDecoder().decode(ingestionKey), StandardCharsets.UTF_8).split(":")[1] + "\n" +
                "    tls             Off\n" +
                "    compress        gzip\n";
    }

    public String generateHelmValues(Long clientId, String providerAccountId) {
        String[] creds = getEffectiveOrgCredentials(clientId, providerAccountId);
        String orgId = creds[0];
        String ingestionKey = creds[1];

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
                "        Http_User        " + adminEmail + "\n" +
                "        Http_Passwd      " + extractPassword(ingestionKey) + "\n" +
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
        String ingestionKey = creds[1];

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
        String authBase64 = Base64.getEncoder()
                .encodeToString((adminEmail + ":" + adminPassword).getBytes(StandardCharsets.UTF_8));

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
                "  O2_EMAIL: \"" + adminEmail + "\"\n" +
                "  O2_PASSWORD: \"" + adminPassword + "\"\n" +
                "  O2_AUTH_BASE64: \"" + authBase64 + "\"\n";
    }

    /**
     * Generates the Helm install command for the OpenObserve collector on EKS.
     * All credentials and org ID are pre-filled from the provisioned config.
     */
    public String generateHelmEksCommand(Long clientId, String providerAccountId) {
        String[] creds = getEffectiveOrgCredentials(clientId, providerAccountId);
        String orgId = creds[0];
        String authBase64 = Base64.getEncoder()
                .encodeToString((adminEmail + ":" + adminPassword).getBytes(StandardCharsets.UTF_8));
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
        String authBase64 = Base64.getEncoder()
                .encodeToString((adminEmail + ":" + adminPassword).getBytes(StandardCharsets.UTF_8));
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
                "if command -v docker &>/dev/null; then\n" +
                "  sudo setfacl -Rdm u:otelcol-contrib:rX /var/lib/docker/containers/ 2>/dev/null || true\n" +
                "  sudo setfacl -Rm  u:otelcol-contrib:rX /var/lib/docker/containers/ 2>/dev/null || true\n" +
                "  echo 'Docker detected — container log access granted'\n" +
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
                "sudo cp \"$(find /tmp/beyla-extract -type f -name beyla | head -1)\" /usr/local/bin/beyla\n" +
                "sudo chmod +x /usr/local/bin/beyla\n\n" +
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
        String authBase64 = Base64.getEncoder()
                .encodeToString((adminEmail + ":" + adminPassword).getBytes(StandardCharsets.UTF_8));
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
                "# ── Step 4: Grant log file read access ──────────────────────────────────────\n" +
                "if ! command -v setfacl &>/dev/null; then\n" +
                "  sudo apt-get install -y acl 2>/dev/null || sudo yum install -y acl 2>/dev/null || true\n" +
                "fi\n" +
                "if [ -d /var/log/nginx ]; then\n" +
                "  sudo setfacl -Rm   u:otelcol-contrib:r  /var/log/nginx/ 2>/dev/null || true\n" +
                "  sudo setfacl -d -m u:otelcol-contrib:r  /var/log/nginx/ 2>/dev/null || true\n" +
                "fi\n" +
                "if command -v docker &>/dev/null; then\n" +
                "  sudo setfacl -Rdm u:otelcol-contrib:rX /var/lib/docker/containers/ 2>/dev/null || true\n" +
                "  sudo setfacl -Rm  u:otelcol-contrib:rX /var/lib/docker/containers/ 2>/dev/null || true\n" +
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

    @Transactional
    public ObservabilityConfig updateConfig(Long clientId, int retentionDays) {
        ObservabilityConfig config = getConfig(clientId);
        config.setRetentionDays(retentionDays);
        configRepo.save(config);

        // Apply retention to all existing streams and set the org-level default for new streams
        String orgId = config.getO2OrgId();
        applyOrgDefaultRetention(orgId, retentionDays);
        applyRetentionByType(orgId, "logs",    retentionDays);
        applyRetentionByType(orgId, "metrics", retentionDays);
        applyRetentionByType(orgId, "traces",  retentionDays);

        return config;
    }

    @SuppressWarnings("unchecked")
    private void applyRetentionByType(String orgId, String streamType, int retentionDays) {
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
    private void applyOrgDefaultRetention(String orgId, int retentionDays) {
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
        try {
            String decoded = new String(Base64.getDecoder().decode(base64Token), StandardCharsets.UTF_8);
            return decoded.contains(":") ? decoded.split(":", 2)[1] : base64Token;
        } catch (Exception e) {
            return base64Token;
        }
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
