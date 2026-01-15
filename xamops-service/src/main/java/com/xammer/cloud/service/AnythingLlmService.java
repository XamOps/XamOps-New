package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AnythingLlmService {

    private static final Logger logger = LoggerFactory.getLogger(AnythingLlmService.class);

    // We keep these fields to avoid changing the constructor signature, 
    // even though they aren't used right now.
    private final String anythingLlmUrl;
    private final String anythingLlmApiKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Constructor preserved to prevent dependency injection failures in other parts of the app
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
        // --- 🔴 AI LOGIC DISABLED FOR HUMAN SUPPORT MIGRATION 🔴 ---
        
        /* // ORIGINAL AI CODE (COMMENTED OUT)
        try {
            logger.info("🤖 Sending request to AnythingLLM: {}", anythingLlmUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + anythingLlmApiKey);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("message", userMessage);
            requestBody.put("mode", "chat"); 

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    anythingLlmUrl,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("textResponse")) {
                return root.get("textResponse").asText();
            } else {
                return "I received a response, but it was not in the expected format.";
            }

        } catch (Exception e) {
            logger.error("❌ Error calling AnythingLLM: {}", e.getMessage());
            return "⚠️ I cannot reach my brain.";
        }
        */

        // --- NEW BEHAVIOR: Placeholder for Human Handover ---
        // This ensures the ChatbotController doesn't crash if it still accidentally calls this service.
        return "Message forwarded to support agent.";
    }
}