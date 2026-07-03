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
import org.springframework.web.client.RestTemplate;

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

    /**
     * GETs {apiBaseUrl}/v1/organizations/{orgId}{path}, following Link rel="next"
     * up to maxPages pages. Each page is a JSON array; items are concatenated.
     */
    public List<JsonNode> fetchAll(String path, int maxPages) {
        List<JsonNode> items = new ArrayList<>();
        String url = apiBaseUrl + "/v1/organizations/" + orgId + path;
        int pages = 0;
        while (url != null && pages < maxPages) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getToken());
            // Upwind's WAF rejects default library user agents (nginx 403)
            headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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
        return items;
    }

    private String nextLink(HttpHeaders headers) {
        List<String> links = headers.get("Link");
        if (links == null) return null;
        for (String header : links) {
            for (String part : header.split(",")) {
                if (part.contains("rel=\"next\"")) {
                    int start = part.indexOf('<');
                    int end = part.indexOf('>');
                    if (start >= 0 && end > start) {
                        return part.substring(start + 1, end);
                    }
                }
            }
        }
        return null;
    }

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
