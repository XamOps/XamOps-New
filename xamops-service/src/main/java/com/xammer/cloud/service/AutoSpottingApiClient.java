package com.xammer.cloud.service;

import com.xammer.cloud.dto.autospotting.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;

@Component
public class AutoSpottingApiClient {

    private static final Logger logger = LoggerFactory.getLogger(AutoSpottingApiClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public AutoSpottingApiClient(
            RestTemplate restTemplate,
            @Value("${autospotting.api.base-url:https://d2jp7dfepeuzw9.cloudfront.net/api}") String baseUrl,
            @Value("${autospotting.api.key:}") String apiKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @PostConstruct
    public void validateConfiguration() {
        logger.info("========================================");
        logger.info("AutoSpotting API Client Configuration");
        logger.info("========================================");
        logger.info("Base URL: {}", baseUrl);

        if (apiKey == null || apiKey.isEmpty()) {
            logger.error("❌ API KEY IS MISSING!");
        } else {
            logger.info("✓ API Key configured: YES (length={})", apiKey.length());
            logger.debug("API Key (first 10 chars): {}...", apiKey.substring(0, Math.min(10, apiKey.length())));
        }
        logger.info("========================================");
    }

    /**
     * Create headers with proper API key authentication
     * Using exact header format from working dashboard: X-Api-Key
     */
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (apiKey != null && !apiKey.isEmpty()) {
            // IMPORTANT: Use "X-Api-Key" not "X-API-Key" - exact match from network logs
            headers.set("X-Api-Key", apiKey);
            logger.debug("Added authentication header: X-Api-Key");
        } else {
            logger.warn("⚠️ No API key configured - request will likely fail");
        }

        return headers;
    }

    /**
     * Get current costs (GET /v1/costs)
     */
    public CostResponse getCurrentCosts(String accountId, String region) {
        logger.info("=== Calling AutoSpotting API: GET /v1/costs ===");
        logger.info("Account ID: {}, Region: {}", accountId, region != null ? region : "all");

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/costs")
                    .queryParam("account_id", accountId)
                    .queryParamIfPresent("region", java.util.Optional.ofNullable(region))
                    .toUriString();

            logger.debug("Request URL: {}", url);

            HttpHeaders headers = createHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.debug("Sending GET request...");

            ResponseEntity<CostResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    CostResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                CostResponse costResponse = response.getBody();
                logger.info("✓ API Response received successfully!");
                logger.info("  - Status: {}", response.getStatusCode());

                if (costResponse.getSummary() != null) {
                    CostResponse.CostSummary summary = costResponse.getSummary();
                    logger.info("  - Total ASGs: {}", summary.getTotalAsgCount());
                    logger.info("  - Enabled ASGs: {}", summary.getAutospottingEnabledCount());
                    logger.info("  - Current hourly cost: ${}/hr",
                            summary.getTotalCurrentHourlyCost() != null
                                    ? String.format("%.4f", summary.getTotalCurrentHourlyCost())
                                    : "0.0000");
                    logger.info("  - Actual savings: ${}/hr",
                            summary.getTotalActualSavings() != null
                                    ? String.format("%.4f", summary.getTotalActualSavings())
                                    : "0.0000");
                    logger.info("  - Potential savings: ${}/hr",
                            summary.getTotalPotentialSavings() != null
                                    ? String.format("%.4f", summary.getTotalPotentialSavings())
                                    : "0.0000");
                }

                logger.info("  - ASG Details: {}",
                        costResponse.getAsgs() != null ? costResponse.getAsgs().size() : 0);

                if (costResponse.getAsgs() != null && !costResponse.getAsgs().isEmpty()) {
                    logger.info("  - ASG breakdown:");
                    costResponse.getAsgs().forEach(asg -> logger.info(
                            "    → {} ({}) | Enabled: {} | Cost: ${}/hr | Savings: ${}/hr",
                            asg.getAsgName(),
                            asg.getRegion(),
                            asg.getAutospottingEnabled(),
                            asg.getCurrentHourlyCost() != null ? String.format("%.4f", asg.getCurrentHourlyCost())
                                    : "0.0000",
                            asg.getActualHourlySavings() != null ? String.format("%.4f", asg.getActualHourlySavings())
                                    : "0.0000"));
                }

                return costResponse;
            } else {
                logger.warn("Empty or invalid response from API: status={}", response.getStatusCode());
                return null;
            }

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            logger.error("❌ HTTP {} Error from AutoSpotting API", e.getStatusCode());
            logger.error("   Response body: {}", e.getResponseBodyAsString());
            logger.error("   Request URL: {}/v1/costs?account_id={}", baseUrl, accountId);
            throw new RuntimeException(
                    "AutoSpotting API request failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            logger.error("❌ Failed to call AutoSpotting API: {}", e.getMessage(), e);
            throw new RuntimeException("AutoSpotting API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get cost history (GET /v1/costs/history)
     */
    public HistoryResponse getCostsHistory(String accountId, String start, String end, String interval) {
        logger.info("Calling AutoSpotting API: GET /v1/costs/history");
        logger.info("Parameters: account={}, start={}, end={}, interval={}",
                accountId, start, end, interval);

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/costs/history")
                    .queryParam("account_id", accountId)
                    .queryParamIfPresent("start", java.util.Optional.ofNullable(start))
                    .queryParamIfPresent("end", java.util.Optional.ofNullable(end))
                    .queryParamIfPresent("interval", java.util.Optional.ofNullable(interval))
                    .toUriString();

            HttpHeaders headers = createHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<HistoryResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    HistoryResponse.class);

            logger.info("✓ Cost history retrieved: {} data points",
                    response.getBody() != null && response.getBody().getDataPoints() != null
                            ? response.getBody().getDataPoints().size()
                            : 0);

            return response.getBody();

        } catch (Exception e) {
            logger.error("Failed to call /v1/costs/history: {}", e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Enable ASG (POST /v1/asg/enable)
     */
    public SuccessResponse enableAsg(String asgName, String accountId, String region) {
        logger.info("Calling API: POST /v1/asg/enable for {} in {}", asgName, region);

        try {
            String url = baseUrl + "/v1/asg/enable";

            HttpHeaders headers = createHeaders();

            var requestBody = new java.util.HashMap<String, String>();
            requestBody.put("asg_name", asgName);
            requestBody.put("account_id", accountId);
            requestBody.put("region", region);

            HttpEntity<java.util.Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<SuccessResponse> response = restTemplate.postForEntity(
                    url,
                    entity,
                    SuccessResponse.class);

            logger.info("✓ ASG {} enabled successfully", asgName);
            return response.getBody();

        } catch (Exception e) {
            logger.error("Failed to enable ASG via API: {}", e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Disable ASG (POST /v1/asg/disable)
     */
    public SuccessResponse disableAsg(String asgName, String accountId, String region) {
        logger.info("Calling API: POST /v1/asg/disable for {} in {}", asgName, region);

        try {
            String url = baseUrl + "/v1/asg/disable";

            HttpHeaders headers = createHeaders();

            var requestBody = new java.util.HashMap<String, String>();
            requestBody.put("asg_name", asgName);
            requestBody.put("account_id", accountId);
            requestBody.put("region", region);

            HttpEntity<java.util.Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<SuccessResponse> response = restTemplate.postForEntity(
                    url,
                    entity,
                    SuccessResponse.class);

            logger.info("✓ ASG {} disabled successfully", asgName);
            return response.getBody();

        } catch (Exception e) {
            logger.error("Failed to disable ASG via API: {}", e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get ASG config (GET /v1/asg/config)
     */
    public ASGConfig getAsgConfig(String asgName, String accountId, String region) {
        logger.info("Calling API: GET /v1/asg/config for {} in {}", asgName, region);

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/asg/config")
                    .queryParam("asg_name", asgName)
                    .queryParam("account_id", accountId)
                    .queryParam("region", region)
                    .toUriString();

            HttpHeaders headers = createHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<ASGConfig> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    ASGConfig.class);

            logger.info("✓ ASG config retrieved for {}", asgName);
            return response.getBody();

        } catch (Exception e) {
            logger.error("Failed to get ASG config: {}", e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Update ASG config (PUT /v1/asg/config)
     */
    public ASGConfig updateAsgConfig(String asgName, String accountId, String region, ASGConfigUpdate config) {
        logger.info("Calling API: PUT /v1/asg/config for {} in {}", asgName, region);

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/asg/config")
                    .queryParam("asg_name", asgName)
                    .queryParam("account_id", accountId)
                    .queryParam("region", region)
                    .toUriString();

            HttpHeaders headers = createHeaders();
            HttpEntity<ASGConfigUpdate> entity = new HttpEntity<>(config, headers);

            ResponseEntity<ASGConfig> response = restTemplate.exchange(
                    url,
                    HttpMethod.PUT,
                    entity,
                    ASGConfig.class);

            logger.info("✓ ASG config updated for {}", asgName);
            return response.getBody();

        } catch (Exception e) {
            logger.error("Failed to update ASG config: {}", e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }
}
