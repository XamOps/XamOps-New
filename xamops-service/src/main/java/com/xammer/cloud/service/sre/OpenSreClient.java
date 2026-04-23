package com.xammer.cloud.service.sre;

import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class OpenSreClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenSreClient.class);

    private final RestTemplate restTemplate;
    private final String openSreBaseUrl;
    private final String langsmithApiKey;

    @Autowired
    public OpenSreClient(@Value("${opensre.url:http://localhost:2024}") String openSreBaseUrl,
                         @Value("${opensre.langsmith.api-key:}") String langsmithApiKey) {
        this.openSreBaseUrl = openSreBaseUrl;
        this.langsmithApiKey = langsmithApiKey;
        // Dedicated RestTemplate with short timeouts — OpenSRE runs locally and should respond in seconds
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
                HttpClients.createDefault());
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** Package-private constructor for unit tests only. */
    OpenSreClient(RestTemplate restTemplate, String openSreBaseUrl, String langsmithApiKey) {
        this.restTemplate = restTemplate;
        this.openSreBaseUrl = openSreBaseUrl;
        this.langsmithApiKey = langsmithApiKey;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (langsmithApiKey != null && !langsmithApiKey.isBlank()) {
            headers.set("Authorization", "Bearer " + langsmithApiKey);
        }
        return headers;
    }

    public String createThread() {
        HttpEntity<Map<?, ?>> request = new HttpEntity<>(Map.of(), authHeaders());
        ResponseEntity<Map> response = restTemplate.exchange(
            openSreBaseUrl + "/threads", HttpMethod.POST, request, Map.class);
        Map<?, ?> body = response.getBody();
        if (body == null || !body.containsKey("thread_id")) {
            throw new RuntimeException("OpenSRE /threads returned no thread_id");
        }
        return (String) body.get("thread_id");
    }

    public void startRun(String threadId, Map<String, Object> input) {
        Map<String, Object> body = new HashMap<>();
        body.put("assistant_id", "agent");
        body.put("input", input);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, authHeaders());
        restTemplate.exchange(
            openSreBaseUrl + "/threads/" + threadId + "/runs",
            HttpMethod.POST, request, Map.class
        );
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getState(String threadId) {
        HttpEntity<?> request = new HttpEntity<>(authHeaders());
        // GET /threads/{id} returns both "status" and "values" in one call.
        // GET /threads/{id}/state returns only checkpoint state (no "status" field).
        ResponseEntity<Map> response = restTemplate.exchange(
            openSreBaseUrl + "/threads/" + threadId,
            HttpMethod.GET, request, Map.class);
        Map<?, ?> body = response.getBody();
        if (body == null) return Map.of();
        return (Map<String, Object>) body;
    }

    public String getStatus(String threadId) {
        Map<String, Object> state = getState(threadId);
        return (String) state.getOrDefault("status", "unknown");
    }

    public boolean isCompleted(String threadId) {
        Map<String, Object> state = getState(threadId);
        String status = (String) state.getOrDefault("status", "");
        // LangGraph thread status is "idle" when a run finishes; "values" is populated with results
        if ("idle".equals(status)) {
            Object values = state.get("values");
            return values instanceof Map && !((Map<?, ?>) values).isEmpty();
        }
        return false;
    }

    public boolean isFailed(String threadId) {
        Map<String, Object> state = getState(threadId);
        String status = (String) state.getOrDefault("status", "");
        return "error".equals(status) || "interrupted".equals(status);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getValues(String threadId) {
        Map<String, Object> state = getState(threadId);
        Object values = state.get("values");
        if (values instanceof Map) return (Map<String, Object>) values;
        return Map.of();
    }

    public boolean isHealthy() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(openSreBaseUrl + "/ok", Map.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.warn("OpenSRE health check failed: {}", e.getMessage());
            return false;
        }
    }
}
