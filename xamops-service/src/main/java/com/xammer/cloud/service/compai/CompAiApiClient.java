package com.xammer.cloud.service.compai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

/**
 * Client for the self-hosted comp.ai instance's {@code apps/api} (NestJS), used
 * server-to-server only. Auth: org-scoped API key via the {@code X-API-Key}
 * header (comp.ai's {@code HybridAuthGuard} accepts this for "external
 * customers" — see docs/superpowers/specs/2026-07-24-soc2-compai-demo-design.md).
 * No comp.ai session/login is ever exposed to XamOps users.
 */
@Component
@Slf4j
public class CompAiApiClient {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Setter @Value("${compai.api-key:}")
    private String apiKey;

    @Setter @Value("${compai.api-base-url:}")
    private String apiBaseUrl;

    public CompAiApiClient(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    @javax.annotation.PostConstruct
    void logConfigState() {
        log.info("[CompAi] client init — configured={} apiBaseUrl={}", isConfigured(), apiBaseUrl);
        if (!isConfigured()) {
            log.warn("[CompAi] compai.api-key is EMPTY at runtime — the SOC2 Program tabs "
                    + "will return no data. Check COMPAI_API_KEY env var.");
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty()
                && apiBaseUrl != null && !apiBaseUrl.isEmpty();
    }

    /** GETs {@code apiBaseUrl + path}, parses the body as JSON. path must start with '/'. */
    public JsonNode getJson(String path) {
        return exchangeJson(HttpMethod.GET, path, null);
    }

    /** POSTs {@code body} (serialized as JSON, or {} if null) to {@code apiBaseUrl + path}. */
    public JsonNode postJson(String path, Object body) {
        return exchangeJson(HttpMethod.POST, path, body);
    }

    /** PATCHes {@code body} (serialized as JSON) to {@code apiBaseUrl + path}. */
    public JsonNode patchJson(String path, Object body) {
        return exchangeJson(HttpMethod.PATCH, path, body);
    }

    /** DELETEs {@code apiBaseUrl + path}. */
    public JsonNode deleteJson(String path) {
        return exchangeJson(HttpMethod.DELETE, path, null);
    }

    private JsonNode exchangeJson(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
        HttpEntity<?> entity;
        if (method == HttpMethod.GET) {
            entity = new HttpEntity<>(headers);
        } else {
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            try {
                String json = objectMapper.writeValueAsString(body != null ? body : java.util.Map.of());
                entity = new HttpEntity<>(json, headers);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize request body for " + path, e);
            }
        }
        ResponseEntity<String> resp = restTemplate.exchange(
                URI.create(apiBaseUrl + path), method, entity, String.class);
        try {
            String responseBody = resp.getBody();
            return (responseBody == null || responseBody.isEmpty())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("comp.ai response was not valid JSON for " + path, e);
        }
    }
}
