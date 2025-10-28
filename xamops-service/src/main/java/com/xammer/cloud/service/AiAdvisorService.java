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
import java.util.List;
import java.util.Map;

@Service
public class AiAdvisorService {

    private static final Logger logger = LoggerFactory.getLogger(AiAdvisorService.class);
    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";

    private final String apiKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public AiAdvisorService(@Value("${groq.api.key}") String apiKey,
                            RestTemplate restTemplate,
                            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        logger.info("✅ AI Advisor Service initialized with Groq API");
    }

    public String getRightsizingRecommendations(Object rightsizingData) {
        String prompt = "Analyze this AWS resource and provide a BRIEF recommendation (max 150 words):\n\n" +
                rightsizingData.toString() + "\n\n" +
                "Format:\n" +
                "💡 **Quick Summary**: [1 sentence]\n" +
                "💰 **Savings**: [Amount and %]\n" +
                "⚡ **Action**: [2-3 bullet points only]\n" +
                "⚠️ **Risk**: [1 sentence]";
        return getAiResponse(prompt);
    }

    public String getSecurityRecommendations(Object securityData) {
        String prompt = "Analyze this security finding and provide a BRIEF fix (max 150 words):\n\n" +
                securityData.toString() + "\n\n" +
                "Format:\n" +
                "🔒 **Issue**: [1 sentence]\n" +
                "🚨 **Risk Level**: [Critical/High/Medium/Low]\n" +
                "✅ **Quick Fix**: [2-3 steps only]\n" +
                "📋 **Command**: [1 AWS CLI command if applicable]";
        return getAiResponse(prompt);
    }

    private String getAiResponse(String prompt) {
        try {
            logger.info("📤 Sending request to Groq AI...");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama-3.1-8b-instant");
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", "You are a concise AWS expert. Keep responses under 150 words. Use emojis and bullet points. Be direct and actionable."),
                    Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("temperature", 0.5);
            requestBody.put("max_tokens", 300); // Reduced from 2000

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    GROQ_API_URL,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            String aiResponse = jsonResponse.get("choices")
                    .get(0)
                    .get("message")
                    .get("content")
                    .asText();

            logger.info("✅ Successfully received AI response");
            return aiResponse;

        } catch (Exception e) {
            logger.error("❌ Error calling Groq AI: {}", e.getMessage());
            return "⚠️ **Error**: Could not get AI recommendation.\n\nPlease check your API key and network connection.";
        }
    }
}
