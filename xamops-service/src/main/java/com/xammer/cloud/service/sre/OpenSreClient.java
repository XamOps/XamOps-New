package com.xammer.cloud.service.sre;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class OpenSreClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenSreClient.class);

    private final RestTemplate restTemplate;
    private final String openSreBaseUrl;

    public OpenSreClient(RestTemplate restTemplate,
                         @Value("${opensre.url:http://localhost:2024}") String openSreBaseUrl) {
        this.restTemplate = restTemplate;
        this.openSreBaseUrl = openSreBaseUrl;
    }

    public String createThread() {
        Map<?, ?> response = restTemplate.postForObject(openSreBaseUrl + "/threads", Map.of(), Map.class);
        if (response == null || !response.containsKey("thread_id")) {
            throw new RuntimeException("OpenSRE /threads returned no thread_id");
        }
        return (String) response.get("thread_id");
    }

    public void startRun(String threadId, Map<String, Object> input) {
        Map<String, Object> body = new HashMap<>();
        body.put("input", input);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        restTemplate.postForObject(
            openSreBaseUrl + "/threads/" + threadId + "/runs",
            request,
            Map.class
        );
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getState(String threadId) {
        Map<?, ?> response = restTemplate.getForObject(
            openSreBaseUrl + "/threads/" + threadId + "/state",
            Map.class
        );
        if (response == null) return Map.of();
        return (Map<String, Object>) response;
    }

    public boolean isCompleted(String threadId) {
        Map<String, Object> state = getState(threadId);
        return "success".equals(state.getOrDefault("status", ""));
    }

    public boolean isFailed(String threadId) {
        Map<String, Object> state = getState(threadId);
        return "error".equals(state.getOrDefault("status", ""));
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
