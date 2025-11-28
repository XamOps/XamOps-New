package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class AnythingLlmService {

    private static final Logger logger = LoggerFactory.getLogger(AnythingLlmService.class);

    private final String anythingLlmUrl;
    private final String anythingLlmApiKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public AnythingLlmService(
            @Value("${anythingllm.url}") String anythingLlmUrl,
            @Value("${anythingllm.api.key}") String anythingLlmApiKey,
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.anythingLlmUrl = anythingLlmUrl;
        this.anythingLlmApiKey = anythingLlmApiKey;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String getChatResponse(String userMessage) {
        try {
            logger.info("🤖 Sending request to AnythingLLM: {}", anythingLlmUrl);

            // 1. Prepare Headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + anythingLlmApiKey);

            // 2. Prepare Body (Standard AnythingLLM Chat Payload)
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("message", userMessage);
            requestBody.put("mode", "chat"); // Uses workspace context/documents

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            // 3. Execute Request
            ResponseEntity<String> response = restTemplate.exchange(
                    anythingLlmUrl,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            // 4. Parse Response (AnythingLLM returns { "textResponse": "...", ... })
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("textResponse")) {
                return root.get("textResponse").asText();
            } else {
                logger.warn("⚠️ Unexpected response format from AnythingLLM: {}", response.getBody());
                return "I received a response, but it was not in the expected format.";
            }

        } catch (Exception e) {
            logger.error("❌ Error calling AnythingLLM: {}", e.getMessage());
            return "⚠️ I cannot reach my brain (AnythingLLM). Please check if the container is running and the workspace 'xamops-global' exists.";
        }
    }
}