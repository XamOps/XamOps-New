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
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
        ResponseEntity<String> resp = restTemplate.exchange(
                URI.create(apiBaseUrl + path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        try {
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("comp.ai response was not valid JSON for " + path, e);
        }
    }
}
