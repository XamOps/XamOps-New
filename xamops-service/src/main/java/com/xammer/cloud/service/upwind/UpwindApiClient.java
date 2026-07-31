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

    @Setter @Value("${upwind.token-url:https://oauth.upwind.io/oauth/token}")
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

    public List<JsonNode> fetchConfigurationFindings(int maxPages) {
        return fetchAll("/configuration-findings", maxPages);
    }

    /** Paged variants that also report completeness — used by the reconciling full sync. */
    public FetchResult fetchVulnerabilityFindingsPaged(int maxPages) {
        return fetchAllPaged("/vulnerability-findings", maxPages);
    }

    public FetchResult fetchConfigurationFindingsPaged(int maxPages) {
        return fetchAllPaged("/configuration-findings", maxPages);
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
            // Link-header next URLs are already percent-encoded; the String overload
            // would re-encode them (double encoding -> Upwind 400)
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

    /** v2 inventory search with cursor paging. conditions is the request body's "conditions" array. */
    public List<JsonNode> searchInventory(java.util.List<java.util.Map<String, Object>> conditions, int maxItems) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String base = apiBaseUrl + "/v2/organizations/" + orgId + "/inventory/assets/search?limit=100";
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
            throw new IllegalStateException("Upwind inventory search failed", e);
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

    /** v1 API-security endpoints, tenant-filtered by cloud-account-id query param, Link paging.
     *  per-page=1000 keeps the full-endpoint fetch (thousands of endpoints) to a handful of
     *  round-trips so the APIs dashboard counts are complete without dozens of requests. */
    public List<JsonNode> fetchApiSecurityEndpoints(String cloudAccountId, int maxPages) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String url = apiBaseUrl + "/v1/organizations/" + orgId
                + "/apisecurity-endpoints?cloud-account-id=" + cloudAccountId + "&per-page=1000";
        int pages = 0;
        while (url != null && pages < maxPages) {
            HttpHeaders headers = authHeaders();
            ResponseEntity<String> resp = exchangeWithRetry(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(headers));
            try {
                JsonNode body = objectMapper.readTree(resp.getBody());
                if (body.isArray()) body.forEach(items::add);
            } catch (Exception e) {
                throw new IllegalStateException("Upwind apisecurity response invalid", e);
            }
            url = nextLink(resp.getHeaders());
            pages++;
        }
        return items;
    }

    /**
     * As {@link #fetchApiSecurityEndpoints}, but reports whether the page cap cut the walk
     * short so the sync can skip mark-resolved on an incomplete result.
     */
    public FetchResult fetchApiSecurityEndpointsPaged(String cloudAccountId, int maxPages) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String url = apiBaseUrl + "/v1/organizations/" + orgId
                + "/apisecurity-endpoints?cloud-account-id=" + cloudAccountId
                + "&per-page=" + PAGE_SIZE;
        int pages = 0;
        while (url != null && pages < maxPages) {
            HttpHeaders headers = authHeaders();
            ResponseEntity<String> resp = exchangeWithRetry(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(headers));
            try {
                JsonNode body = objectMapper.readTree(resp.getBody());
                if (body.isArray()) body.forEach(items::add);
            } catch (Exception e) {
                throw new IllegalStateException("Upwind apisecurity response invalid", e);
            }
            url = nextLink(resp.getHeaders());
            pages++;
        }
        boolean truncated = url != null;
        if (truncated) {
            log.warn("[Upwind] apisecurity-endpoints for {} hit the {}-page cap with more pages "
                    + "available — fetched {} endpoints; treating as incomplete",
                    cloudAccountId, maxPages, items.size());
        }
        return new FetchResult(items, truncated);
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

    /** v1 create webhook integration. Returns the created object. */
    public JsonNode createWebhook(JsonNode body) {
        String url = apiBaseUrl + "/v1/organizations/" + orgId + "/integration-webhooks";
        try {
            HttpHeaders headers = authHeaders();
            ResponseEntity<String> resp = exchangeWithRetry(URI.create(url),
                    HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), headers));
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Upwind webhook create failed", e);
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
     * single sync run: HTTP 5xx (e.g. Upwind's "503 upstream connect error") and I/O errors
     * (connection refused/reset). 4xx (auth/bad request) are NOT retried — they won't fix
     * themselves. Backoff starts at retryBackoffMs and doubles each attempt.
     */
    private ResponseEntity<String> exchangeWithRetry(URI uri, HttpMethod method, HttpEntity<?> entity) {
        int attempts = Math.max(1, maxAttempts);
        long backoff = Math.max(0, retryBackoffMs);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return restTemplate.exchange(uri, method, entity, String.class);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                last = e;
                if (attempt < attempts) {
                    log.warn("[Upwind] {} {} failed (attempt {}/{}): {} — retrying in {}ms",
                            method, uri, attempt, attempts, e.getMessage(), backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoff *= 2;
                }
            }
        }
        throw last != null ? last : new IllegalStateException("Upwind request failed: " + uri);
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
