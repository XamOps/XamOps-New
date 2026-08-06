package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the Upwind CNAPP API (verified live 2026-07-03, see
 * docs/superpowers/specs/2026-07-02-xamsecure-upwind-design.md).
 *
 * Auth: OAuth2 client credentials against oauth.upwind.io; token TTL 24h,
 * cached in memory and refreshed 60s before expiry.
 * Pagination: RFC-5988 Link headers (rel="next").
 */
@Component
@Slf4j
public class UpwindApiClient {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Setter @Value("${upwind.client-id:}")
    private String clientId;

    @Setter @Value("${upwind.client-secret:}")
    private String clientSecret;

    @Setter @Value("${upwind.org-id:}")
    private String orgId;

    /**
     * Documented token host is auth.upwind.io (Management REST API v2 §1). oauth.upwind.io
     * also answers — both were verified live 2026-08-03 — but we follow the published host
     * so we don't depend on an alias Upwind never documented.
     */
    @Setter @Value("${upwind.token-url:https://auth.upwind.io/oauth/token}")
    private String tokenUrl;

    @Setter @Value("${upwind.api-base-url:https://api.upwind.io}")
    private String apiBaseUrl;

    @Setter @Value("${upwind.audience:https://api.upwind.io}")
    private String audience;

    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public UpwindApiClient(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /**
     * Startup diagnostic — surfaces whether Upwind creds resolved at runtime. This is the
     * fastest way to diagnose the "no data on UAT" class of issue: the live-fetch tabs
     * (Components/SBOM, APIs, Threat Policies, Workflows) short-circuit to empty when
     * {@link #isConfigured()} is false, even though the DB-backed tabs still show synced data.
     */
    @javax.annotation.PostConstruct
    void logConfigState() {
        log.info("[Upwind] client init — configured={} orgId={} apiBaseUrl={}",
                isConfigured(), (orgId == null || orgId.isEmpty() ? "<unset>" : orgId), apiBaseUrl);
        if (!isConfigured()) {
            log.warn("[Upwind] client-id/secret are EMPTY at runtime — live-fetch tabs "
                    + "(Components, APIs, Threat Policies, Workflows) will return no data. "
                    + "Check UPWIND_CLIENT_ID/UPWIND_CLIENT_SECRET env or application.properties defaults.");
        }
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isEmpty()
                && clientSecret != null && !clientSecret.isEmpty();
    }

    public List<JsonNode> fetchThreatDetections(int maxPages) {
        return fetchAll("/threat-detections", maxPages);
    }

    public List<JsonNode> fetchVulnerabilityFindings(int maxPages) {
        return fetchAll("/vulnerability-findings", maxPages);
    }

    /**
     * Configuration findings for ONE status.
     *
     * The endpoint defaults to {@code status=FAIL} when no status is given, which is what we
     * were doing — so PASS findings were never fetched and every per-framework pass count was
     * structurally zero. It rejects multiple statuses in one request (HTTP 400 for both
     * {@code status=FAIL&status=PASS} and {@code status=FAIL,PASS}), so each status needs its
     * own walk. Verified live 2026-08-04.
     */
    public FetchResult fetchConfigurationFindingsPaged(String status, int maxPages) {
        return fetchAllPaged("/configuration-findings?status=" + status, maxPages);
    }

    /** Paged variants that also report completeness — used by the reconciling full sync. */
    public FetchResult fetchVulnerabilityFindingsPaged(int maxPages) {
        return fetchAllPaged("/vulnerability-findings", maxPages);
    }

    /** Compliance framework definitions (title, version, status, last_scan_time, compliance score). */
    public List<JsonNode> fetchConfigurationFrameworks(int maxPages) {
        return fetchAll("/configuration-frameworks", maxPages);
    }

    /**
     * Outcome of a paged fetch. {@code truncated} means paging stopped at the page cap
     * while the API still advertised a next page, so {@code items} is an INCOMPLETE view
     * of the domain. Callers must not run mark-resolved reconciliation on a truncated
     * result — anything not re-fetched would be wrongly archived.
     */
    public static final class FetchResult {
        private final List<JsonNode> items;
        private final boolean truncated;

        FetchResult(List<JsonNode> items, boolean truncated) {
            this.items = items;
            this.truncated = truncated;
        }

        public List<JsonNode> items() { return items; }
        public boolean truncated() { return truncated; }
    }

    /**
     * GETs {apiBaseUrl}/v1/organizations/{orgId}{path}, following Link rel="next"
     * up to maxPages pages. Each page is a JSON array; items are concatenated.
     */
    public List<JsonNode> fetchAll(String path, int maxPages) {
        return fetchAllPaged(path, maxPages).items();
    }

    /**
     * Items per page. Upwind's default is 100 but honours up to 1000 (verified live
     * 2026-07-31), which cuts a full configuration-findings walk (~19.4k findings) from
     * ~196 requests to ~20 and keeps a wide margin under the caller's page cap.
     */
    static final int PAGE_SIZE = 1000;

    /** Outcome of a streaming walk: how many items were handed to the sink, and whether the
     *  page cap cut it short. Deliberately carries no items — that is the point. */
    public record StreamResult(int count, boolean truncated) { }

    /**
     * Walks a paged v1 collection and hands each item to {@code sink}, holding only one page in
     * memory at a time.
     *
     * {@link #fetchAllPaged} accumulates the entire result before returning, which does not
     * survive real volume: vulnerability-findings measured 161,424 items for a single account
     * on 2026-08-05, and the walk is org-wide. At 600 pages of 1000 that list can reach 600k
     * Jackson nodes, and an OutOfMemoryError is an Error rather than an Exception, so it slips
     * past the per-domain catch blocks in UpwindSyncService and kills the whole run — taking
     * the domains that come after it with it.
     *
     * Streaming bounds memory to one page whatever the total volume.
     */
    public StreamResult streamAllPaged(String path, int maxPages,
                                       java.util.function.Consumer<JsonNode> sink) {
        String url = apiBaseUrl + "/v1/organizations/" + orgId + path
                + (path.contains("?") ? "&" : "?") + "per-page=" + PAGE_SIZE;
        int pages = 0;
        int count = 0;
        while (url != null && pages < maxPages) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getToken());
            headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
            ResponseEntity<String> resp = exchangeWithRetry(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(headers));
            try {
                JsonNode body = objectMapper.readTree(resp.getBody());
                if (body.isArray()) {
                    for (JsonNode item : body) {
                        sink.accept(item);
                        count++;
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Upwind response was not valid JSON for " + path, e);
            }
            url = nextLink(resp.getHeaders());
            pages++;
        }
        boolean truncated = url != null;
        if (truncated) {
            log.warn("[Upwind] {} hit the {}-page cap with more pages available — streamed {} "
                    + "items; treating result as incomplete (skipping mark-resolved)",
                    path, maxPages, count);
        }
        return new StreamResult(count, truncated);
    }

    /** Streaming form of {@link #fetchVulnerabilityFindingsPaged}. */
    public StreamResult streamVulnerabilityFindings(int maxPages,
                                                    java.util.function.Consumer<JsonNode> sink) {
        return streamAllPaged("/vulnerability-findings", maxPages, sink);
    }

    /** Streaming form of {@link #fetchConfigurationFindingsPaged}. */
    public StreamResult streamConfigurationFindings(String status, int maxPages,
                                                    java.util.function.Consumer<JsonNode> sink) {
        return streamAllPaged("/configuration-findings?status=" + status, maxPages, sink);
    }

    /** As {@link #fetchAll}, but also reports whether the page cap cut the fetch short. */
    public FetchResult fetchAllPaged(String path, int maxPages) {
        List<JsonNode> items = new ArrayList<>();
        String url = apiBaseUrl + "/v1/organizations/" + orgId + path
                + (path.contains("?") ? "&" : "?") + "per-page=" + PAGE_SIZE;
        int pages = 0;
        while (url != null && pages < maxPages) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getToken());
            // Upwind's WAF rejects default library user agents (nginx 403)
            headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
            // Pass the URI through untouched. Link-header next URLs are already
            // percent-encoded and the String overload would re-encode them.
            //
            // That does NOT fail loudly: measured 2026-08-05, a double-encoded (or outright
            // garbage) page-token returns HTTP 200 with PAGE 1 and a fresh rel="next", so a
            // client that mangles the token silently loops page 1 -> page 2 -> page 1 for
            // ever, never advancing and never erroring. An earlier comment here claimed a
            // 400; that was wrong.
            ResponseEntity<String> resp = exchangeWithRetry(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(headers));
            try {
                JsonNode body = objectMapper.readTree(resp.getBody());
                if (body.isArray()) {
                    body.forEach(items::add);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Upwind response was not valid JSON for " + path, e);
            }
            url = nextLink(resp.getHeaders());
            pages++;
        }
        boolean truncated = url != null;
        if (truncated) {
            log.warn("[Upwind] {} hit the {}-page cap with more pages available — fetched {} items; "
                    + "treating result as incomplete (skipping mark-resolved)", path, maxPages, items.size());
        }
        return new FetchResult(items, truncated);
    }

    /** Documented ceiling for inventory catalog search (v2 spec: min 1, max 200, default 20). */
    static final int CATALOG_PAGE_SIZE = 200;

    /**
     * v2 inventory CATALOG search with cursor paging. {@code conditions} is the request
     * body's "conditions" array (which must be non-empty — the API rejects {@code []} with
     * 400 InvalidParameter).
     *
     * We call {@code /inventory/catalog/assets/search}, not {@code /inventory/assets/search}.
     * Both return 200, which is why the thinner one went unnoticed: it yields only
     * id/label/name/region/provider/account/resource-id, while the catalog adds
     * {@code vulnerability_risk}, {@code detection_risk}, {@code network_risk},
     * {@code high_privilege_risk.has_elevated_privilege}, {@code sensitive_data_at_rest},
     * {@code sensitive_data_in_transit} and {@code technologies} (with EOL/EOS dates) —
     * i.e. the privilege and data-classification signals the UI needs. Verified live
     * 2026-08-03.
     *
     * Field names differ between the two: the catalog uses {@code category}/
     * {@code sub_category}/{@code resource_type} where the flat search used
     * {@code asset_category}/{@code asset_sub_category}/{@code label}, so callers must read
     * both spellings.
     */
    public List<JsonNode> searchInventory(java.util.List<java.util.Map<String, Object>> conditions, int maxItems) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String base = apiBaseUrl + "/v2/organizations/" + orgId
                + "/inventory/catalog/assets/search?limit=" + CATALOG_PAGE_SIZE;
        String url = base;
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of("conditions", conditions));
            while (url != null && items.size() < maxItems) {
                HttpHeaders headers = authHeaders();
                ResponseEntity<String> resp = exchangeWithRetry(
                        URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers));
                JsonNode json = objectMapper.readTree(resp.getBody());
                for (JsonNode item : json.path("items")) items.add(item);
                String cursor = json.path("metadata").path("next_cursor").asText(null);
                url = (cursor != null && !cursor.isEmpty()) ? base + "&cursor=" + cursor : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Upwind inventory catalog search failed", e);
        }
        return items;
    }

    /**
     * v2 threat-stories search (cursor paging), newest first. {@code conditions} is the
     * request body's "conditions" array (e.g. a cloud_account_id filter for tenancy).
     * Returns the raw story objects.
     */
    public List<JsonNode> searchThreatStories(java.util.List<java.util.Map<String, Object>> conditions, int maxItems) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String base = apiBaseUrl + "/v2/organizations/" + orgId
                + "/threats/stories/search?limit=100&sort=create_time:desc";
        String url = base;
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of("conditions", conditions));
            while (url != null && items.size() < maxItems) {
                HttpHeaders headers = authHeaders();
                ResponseEntity<String> resp = exchangeWithRetry(
                        URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers));
                JsonNode json = objectMapper.readTree(resp.getBody());
                JsonNode arr = json.has("items") ? json.path("items") : json;
                if (arr.isArray()) arr.forEach(items::add);
                String cursor = json.path("metadata").path("next_cursor").asText(null);
                url = (cursor != null && !cursor.isEmpty()) ? base + "&cursor=" + cursor : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Upwind threat-stories search failed", e);
        }
        return items;
    }

    // NOTE: the v1 GET /apisecurity-endpoints fetchers were removed in favour of
    // searchApiEndpoints (v2). The v1 payload carries no sensitive_data or status_codes, so
    // keeping it around only invited a silent regression back to the thinner shape.

    /** Documented ceiling for the v2 search operations that cap at 100 (spec: min 1, max 100). */
    static final int SEARCH_PAGE_SIZE = 100;

    /**
     * v2 API-security endpoints search, cursor-paged, filtered to one cloud account.
     *
     * Replaces the v1 {@code /apisecurity-endpoints} GET. The v1 shape is not in the
     * published v2 reference and, more importantly, the v2 payload carries
     * {@code risk_overview.sensitive_data[]} (category + type, e.g. PII/Email) and
     * {@code status_codes[]} — the PII/secret exposure signals Upwind's own APIs dashboard
     * shows. Verified live 2026-08-03: 100 endpoints/page with a working next_cursor.
     *
     * @return items plus a truncation flag, so an incomplete walk can skip mark-resolved.
     */
    /**
     * Detection policies from the v2 search, which is a DIFFERENT set from the v1
     * {@code /threat-policies} list — not a replacement for it.
     *
     * Upwind's console splits them: "Classic Policies" is the v1 list (49 for this org) and
     * "Policies" is this one (32), and its Policies tab header counts both (81). Reading only
     * v1 therefore under-reported by the whole v2 set.
     *
     * Verified live 2026-08-05: an empty {@code conditions} array is required — omitting the
     * field returns 400 "Field 'conditions': must not be null".
     */
    public List<JsonNode> searchThreatPolicies(int maxItems) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String base = apiBaseUrl + "/v2/organizations/" + orgId
                + "/threats/policies/search?limit=" + SEARCH_PAGE_SIZE;
        String url = base;
        try {
            String body = objectMapper.writeValueAsString(
                    java.util.Map.of("conditions", java.util.List.of()));
            while (url != null && items.size() < maxItems) {
                HttpHeaders headers = authHeaders();
                ResponseEntity<String> resp = exchangeWithRetry(
                        URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers));
                JsonNode json = objectMapper.readTree(resp.getBody());
                for (JsonNode item : json.path("items")) items.add(item);
                String cursor = json.path("metadata").path("next_cursor").asText(null);
                url = (cursor != null && !cursor.isEmpty()) ? base + "&cursor=" + cursor : null;
            }
        } catch (Exception e) {
            // Non-fatal: the caller still has the v1 list, so a failure here narrows the
            // policy view rather than emptying it.
            log.warn("[Upwind] v2 threat policy search failed: {}", e.getMessage());
        }
        return items;
    }

    public FetchResult searchApiEndpoints(String cloudAccountId, int maxItems) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String base = apiBaseUrl + "/v2/organizations/" + orgId
                + "/api-security/endpoints/search?limit=" + SEARCH_PAGE_SIZE;
        String url = base;
        boolean truncated = false;
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of("conditions",
                    java.util.List.of(java.util.Map.of("field", "cloud_account_id",
                            "operator", "eq", "value", java.util.List.of(cloudAccountId)))));
            while (url != null) {
                HttpHeaders headers = authHeaders();
                ResponseEntity<String> resp = exchangeWithRetry(
                        URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers));
                JsonNode json = objectMapper.readTree(resp.getBody());
                for (JsonNode item : json.path("items")) items.add(item);
                String cursor = json.path("metadata").path("next_cursor").asText(null);
                url = (cursor != null && !cursor.isEmpty()) ? base + "&cursor=" + cursor : null;
                if (url != null && items.size() >= maxItems) {
                    truncated = true;
                    break;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Upwind API endpoint search failed", e);
        }
        if (truncated) {
            log.warn("[Upwind] api-security/endpoints for {} hit the {}-item cap with more pages "
                    + "available — fetched {}; treating as incomplete",
                    cloudAccountId, maxItems, items.size());
        }
        return new FetchResult(items, truncated);
    }

    // NOTE: v2 also documents POST /configurations/frameworks/search. It is deliberately not
    // used: it returns state/rollout-state/platforms but NOT compliance score or
    // last_scan_time, which the Compliance tab needs, whereas the v1
    // /configuration-frameworks list carries both. Migrating would lose data, not gain it.

    /** Registered webhook integrations, so registration can be made idempotent. */
    public List<JsonNode> searchWebhooks(int maxItems) {
        return searchAll("/integrations/webhooks/search",
                java.util.List.of(java.util.Map.of("field", "status", "operator", "in",
                        "value", java.util.List.of("enabled", "disabled"))),
                maxItems, "webhook search");
    }

    /** Shared cursor-paged POST /search walk for the v2 operations that cap limit at 100. */
    private List<JsonNode> searchAll(String path,
                                     java.util.List<java.util.Map<String, Object>> conditions,
                                     int maxItems, String what) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String base = apiBaseUrl + "/v2/organizations/" + orgId + path
                + "?limit=" + SEARCH_PAGE_SIZE;
        String url = base;
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of("conditions", conditions));
            while (url != null && items.size() < maxItems) {
                HttpHeaders headers = authHeaders();
                ResponseEntity<String> resp = exchangeWithRetry(
                        URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers));
                JsonNode json = objectMapper.readTree(resp.getBody());
                for (JsonNode item : json.path("items")) items.add(item);
                String cursor = json.path("metadata").path("next_cursor").asText(null);
                url = (cursor != null && !cursor.isEmpty()) ? base + "&cursor=" + cursor : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Upwind " + what + " failed", e);
        }
        return items;
    }

    /**
     * One threat story in full: {@code GET /v2/{org}/threats/stories/{id}}.
     *
     * The detail route adds {@code description} — a long markdown incident narrative with
     * Upwind's own inline markup — which the search/list routes omit. Everything else
     * (severity, status, timestamps, detection_ids) matches the list payload.
     */
    public JsonNode getThreatStory(String storyId) {
        String url = apiBaseUrl + "/v2/organizations/" + orgId + "/threats/stories/" + storyId;
        try {
            ResponseEntity<String> resp = exchangeWithRetry(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(authHeaders()));
            JsonNode json = objectMapper.readTree(resp.getBody());
            JsonNode items = json.path("items");
            return items.isArray() && items.size() > 0 ? items.get(0) : json;
        } catch (Exception e) {
            throw new IllegalStateException("Upwind threat-story fetch failed for " + storyId, e);
        }
    }

    /** v1 SBOM software packages, tenant-filtered by cloud-account-id query param, Link paging. */
    public List<JsonNode> fetchSbomPackages(String cloudAccountId, int maxPages) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String url = apiBaseUrl + "/v1/organizations/" + orgId
                + "/sbom-packages?cloud-account-id=" + cloudAccountId + "&per-page=100";
        int pages = 0;
        while (url != null && pages < maxPages) {
            HttpHeaders headers = authHeaders();
            ResponseEntity<String> resp = exchangeWithRetry(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(headers));
            try {
                JsonNode body = objectMapper.readTree(resp.getBody());
                if (body.isArray()) body.forEach(items::add);
            } catch (Exception e) {
                throw new IllegalStateException("Upwind sbom-packages response invalid", e);
            }
            url = nextLink(resp.getHeaders());
            pages++;
        }
        return items;
    }

    /**
     * v2 create webhook integration ({@code POST /v2/{org}/integrations/webhooks}).
     *
     * We previously posted to the v1 {@code /integration-webhooks} path with
     * {@code vendor: "CUSTOM"}. The documented contract is v2 with a LOWERCASE vendor
     * ({@code custom}) and the vendor-specific fields nested under {@code config}, whose
     * {@code authentication.type} is likewise lowercase ({@code bearer}).
     *
     * Returns the created object. The v2 response wraps it in {@code items[]}, so the first
     * element is unwrapped for callers that just want the new webhook's id.
     */
    public JsonNode createWebhook(JsonNode body) {
        String url = apiBaseUrl + "/v2/organizations/" + orgId + "/integrations/webhooks";
        try {
            HttpHeaders headers = authHeaders();
            ResponseEntity<String> resp = exchangeWithRetry(URI.create(url),
                    HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), headers));
            JsonNode json = objectMapper.readTree(resp.getBody());
            JsonNode items = json.path("items");
            return items.isArray() && items.size() > 0 ? items.get(0) : json;
        } catch (Exception e) {
            throw new IllegalStateException("Upwind webhook create failed", e);
        }
    }

    /** v2 PATCH a webhook's name/status ({@code enabled}/{@code disabled}). */
    public JsonNode updateWebhook(String webhookId, java.util.Map<String, Object> patch) {
        String url = apiBaseUrl + "/v2/organizations/" + orgId
                + "/integrations/webhooks/" + webhookId;
        try {
            HttpHeaders headers = authHeaders();
            ResponseEntity<String> resp = exchangeWithRetry(URI.create(url), HttpMethod.PATCH,
                    new HttpEntity<>(objectMapper.writeValueAsString(patch), headers));
            JsonNode json = objectMapper.readTree(resp.getBody());
            JsonNode items = json.path("items");
            return items.isArray() && items.size() > 0 ? items.get(0) : json;
        } catch (Exception e) {
            throw new IllegalStateException("Upwind webhook update failed", e);
        }
    }

    /** v2 DELETE a webhook (204 No Content on success). */
    public void deleteWebhook(String webhookId) {
        String url = apiBaseUrl + "/v2/organizations/" + orgId
                + "/integrations/webhooks/" + webhookId;
        try {
            exchangeWithRetry(URI.create(url), HttpMethod.DELETE, new HttpEntity<>(authHeaders()));
        } catch (Exception e) {
            throw new IllegalStateException("Upwind webhook delete failed", e);
        }
    }

    /** v1 PATCH a detection's status (e.g. "ARCHIVED"). */
    public void updateDetectionStatus(String detectionId, String status) {
        String url = apiBaseUrl + "/v1/organizations/" + orgId + "/threat-detections/" + detectionId;
        try {
            HttpHeaders headers = authHeaders();
            String body = objectMapper.writeValueAsString(java.util.Map.of("status", status));
            exchangeWithRetry(URI.create(url), HttpMethod.PATCH, new HttpEntity<>(body, headers));
        } catch (Exception e) {
            throw new IllegalStateException("Upwind detection update failed", e);
        }
    }

    // Retry tuning: total attempts and initial backoff (doubles each retry).
    @Setter @Value("${upwind.http.max-attempts:3}")
    private int maxAttempts;
    @Setter @Value("${upwind.http.retry-backoff-ms:1000}")
    private long retryBackoffMs;

    /**
     * Wraps restTemplate.exchange with retry-with-backoff for transient failures within a
     * single sync run: HTTP 5xx (e.g. Upwind's "503 upstream connect error"), HTTP 429
     * (every v2 operation documents a 429 "Rate Limit Exceeded" response) and I/O errors
     * (connection refused/reset). Other 4xx (auth/bad request) are NOT retried — they won't
     * fix themselves. Backoff starts at retryBackoffMs and doubles each attempt; on a 429 a
     * {@code Retry-After} header takes precedence over our own backoff, since the server
     * knows when the window reopens and guessing shorter just burns another rejection.
     */
    private ResponseEntity<String> exchangeWithRetry(URI uri, HttpMethod method, HttpEntity<?> entity) {
        int attempts = Math.max(1, maxAttempts);
        long backoff = Math.max(0, retryBackoffMs);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long wait = backoff;
            try {
                return restTemplate.exchange(uri, method, entity, String.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                last = e;
                Long retryAfter = retryAfterMs(e.getResponseHeaders());
                if (retryAfter != null) wait = retryAfter;
            } catch (HttpServerErrorException | ResourceAccessException e) {
                last = e;
            }
            if (attempt < attempts) {
                log.warn("[Upwind] {} {} failed (attempt {}/{}): {} — retrying in {}ms",
                        method, uri, attempt, attempts, last.getMessage(), wait);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff *= 2;
            }
        }
        throw last != null ? last : new IllegalStateException("Upwind request failed: " + uri);
    }

    /** {@code Retry-After} as milliseconds. Supports the delay-seconds form only. */
    private static Long retryAfterMs(HttpHeaders headers) {
        if (headers == null) return null;
        String v = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (v == null || v.isBlank()) return null;
        try {
            long seconds = Long.parseLong(v.trim());
            // Cap so a hostile/absurd value can't wedge the sync thread for hours.
            return Math.min(Math.max(seconds, 0) * 1000L, 60_000L);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getToken());
        headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * RFC-8288 Link header: {@code <url>; rel="next", <url>; rel="first"}. Only commas
     * OUTSIDE the angle brackets separate entries — Upwind's configuration-findings
     * page-token embeds a RAW comma in the URL, e.g.
     * {@code page-token=%5B1785448068963,%22fmw-…%22%5D}. Splitting the whole header on
     * ',' therefore tore that URL in half, the rel="next" fragment lost its '<', and
     * pagination silently stopped after page 1 (one 100-item page), which then let the
     * full sync's mark-resolved pass archive every finding it never re-fetched.
     * Matching {@code <…>} first keeps such URLs intact.
     */
    private String nextLink(HttpHeaders headers) {
        List<String> links = headers.get("Link");
        if (links == null) return null;
        for (String header : links) {
            if (header == null) continue;
            java.util.regex.Matcher m = LINK_ENTRY.matcher(header);
            while (m.find()) {
                String url = m.group(1);
                String params = m.group(2);
                if (REL_NEXT.matcher(params).find() && !url.isBlank()) {
                    return url.trim();
                }
            }
        }
        return null;
    }

    /** One Link entry: the bracketed URL plus its parameters up to the next entry. */
    private static final java.util.regex.Pattern LINK_ENTRY =
            java.util.regex.Pattern.compile("<([^>]*)>([^<]*)");

    /** {@code rel=next}, optionally quoted, as a whole token. */
    private static final java.util.regex.Pattern REL_NEXT =
            java.util.regex.Pattern.compile("(?i)\\brel\\s*=\\s*\"?next\"?");

    private synchronized String getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return cachedToken;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("audience", audience);
        ResponseEntity<String> resp = restTemplate.exchange(
                tokenUrl, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        try {
            JsonNode body = objectMapper.readTree(resp.getBody());
            cachedToken = body.get("access_token").asText();
            long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600;
            tokenExpiry = Instant.now().plusSeconds(expiresIn);
            log.info("[Upwind] Obtained API token, expires in {}s", expiresIn);
            return cachedToken;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Upwind token response", e);
        }
    }
}
