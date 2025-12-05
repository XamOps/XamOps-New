package com.xammer.cloud.service;

import com.xammer.cloud.dto.autospotting.*;
import com.xammer.cloud.dto.autospotting.EventsResponse.EventsSummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.util.Optional;

@Component
public class AutoSpottingApiClient {

    private static final Logger logger = LoggerFactory.getLogger(AutoSpottingApiClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public AutoSpottingApiClient(
            RestTemplate restTemplate,
            @Value("${autospotting.api.base-url:https://do0ezmdybge0h.cloudfront.net/api}") String baseUrl,
            @Value("${autospotting.api.key:${AUTOSPOTTING_API_KEY:}}") String apiKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @PostConstruct
    public void validateConfiguration() {
        logger.info("========================================");
        logger.info("🚀 AutoSpotting API Client Configuration");
        logger.info("========================================");
        logger.info("🔗 Base URL: {}", baseUrl);
        logger.info("🔑 Config autospotting.api.key: '{}'",
                apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) + "..." : "NULL");
        logger.info("🔑 Environment AUTOSPOTTING_API_KEY: '{}'",
                System.getenv("AUTOSPOTTING_API_KEY") != null
                        ? System.getenv("AUTOSPOTTING_API_KEY").substring(0, 10) + "..."
                        : "NOT SET");

        if (apiKey == null || apiKey.trim().isEmpty()) {
            logger.error("❌ CRITICAL: API KEY IS MISSING! All API calls will fail!");
        } else {
            logger.info("✅ API Key configured: YES (length={}, first 10 chars: '{}')",
                    apiKey.length(), apiKey.substring(0, Math.min(10, apiKey.length())));
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
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            // Trim the API key to remove any whitespace
            String trimmedKey = apiKey.trim();

            // Use exact header name as mentioned in comment: X-Api-Key (not X-API-Key)
            headers.set("X-Api-Key", trimmedKey);

            logger.info("✅ X-Api-Key header added successfully (length={})", trimmedKey.length());
            logger.debug("🔑 Header name: 'X-Api-Key', value (first 10): {}...",
                    trimmedKey.substring(0, Math.min(10, trimmedKey.length())));
            logger.debug("🔑 Full API key for debugging: {}", trimmedKey);
        } else {
            logger.error("❌ API key is NULL or EMPTY - authentication will FAIL 401");
        }

        return headers;
    }

    /**
     * Get current costs (GET /v1/costs)
     */
    public CostResponse getCurrentCosts(String accountId, String region) {
        logger.info("🚀 === AUTO SPOTTING API CALL: GET /v1/costs ===");
        logger.info("📊 Account ID: {}, Region: {}", accountId, region != null ? region : "all");
        logger.info("🔑 Current API Key status: {} (length={})",
                apiKey != null ? "LOADED" : "MISSING", apiKey != null ? apiKey.length() : 0);

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/costs")
                    .queryParam("account_id", accountId)
                    .queryParamIfPresent("region", Optional.ofNullable(region))
                    .encode()
                    .toUriString();

            logger.info("🌐 Full Request URL: {}", url);

            HttpHeaders headers = createHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("📤 === SENDING REQUEST TO AUTOSPOTTING ===");
            logger.debug("📤 Complete headers: {}", headers);

            ResponseEntity<CostResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    CostResponse.class);

            logger.info("📥 === RESPONSE RECEIVED ===");
            logger.info("📥 HTTP Status: {} {}", response.getStatusCodeValue(), response.getStatusCode());

            // Log CORS headers for debugging
            if (response.getHeaders().containsKey("Access-Control-Allow-Origin")) {
                logger.info("📥 CORS Header: {}", response.getHeaders().get("Access-Control-Allow-Origin"));
            }

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                CostResponse costResponse = response.getBody();
                logger.info("✅ API SUCCESS! Parsed response:");
                logger.info("  📈 Total ASGs: {}",
                        costResponse.getSummary() != null ? costResponse.getSummary().getTotalAsgCount() : 0);
                logger.info("  ✅ Enabled ASGs: {}",
                        costResponse.getSummary() != null ? costResponse.getSummary().getAutospottingEnabledCount()
                                : 0);
                logger.info("  💰 Current cost: ${}",
                        costResponse.getSummary() != null
                                && costResponse.getSummary().getTotalCurrentHourlyCost() != null
                                        ? String.format("%.4f", costResponse.getSummary().getTotalCurrentHourlyCost())
                                        : "0.0000");
                logger.info("  💵 Actual savings: ${}",
                        costResponse.getSummary() != null && costResponse.getSummary().getTotalActualSavings() != null
                                ? String.format("%.4f", costResponse.getSummary().getTotalActualSavings())
                                : "0.0000");
                logger.info("  🎯 Potential savings: ${}",
                        costResponse.getSummary() != null
                                && costResponse.getSummary().getTotalPotentialSavings() != null
                                        ? String.format("%.4f", costResponse.getSummary().getTotalPotentialSavings())
                                        : "0.0000");

                if (costResponse.getAsgs() != null && !costResponse.getAsgs().isEmpty()) {
                    logger.info("  📋 ASG Details ({})", costResponse.getAsgs().size());
                    costResponse.getAsgs().forEach(asg -> logger.info(
                            "    → {} ({}) | Enabled: {} | Cost: ${}/hr | Savings: ${}/hr",
                            asg.getAsgName(),
                            asg.getRegion(),
                            asg.getAutospottingEnabled(),
                            asg.getCurrentHourlyCost() != null ? String.format("%.4f", asg.getCurrentHourlyCost())
                                    : "0.0000",
                            asg.getActualHourlySavings() != null ? String.format("%.4f", asg.getActualHourlySavings())
                                    : "0.0000"));
                } else {
                    logger.warn("  ⚠️ No ASGs in response");
                }

                logger.info("✅ === API CALL COMPLETE SUCCESS ===");
                return costResponse;
            } else {
                logger.warn("⚠️ Empty or non-200 response: status={}, body={}",
                        response.getStatusCode(), response.getBody());
                logger.info("✅ === API CALL COMPLETE (empty response) ===");
                return null;
            }

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            logger.error("❌ HTTP {} ERROR from AutoSpotting API", e.getStatusCode());
            logger.error("   📥 Response body: {}", e.getResponseBodyAsString());
            logger.error("   ❌ This is likely WRONG API KEY (401) or server error");
            throw new RuntimeException(
                    "AutoSpotting API request failed: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            logger.error("💥 UNEXPECTED EXCEPTION during API call: {}", e.getMessage(), e);
            throw new RuntimeException("AutoSpotting API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get cost history (GET /v1/costs/history)
     */
    public HistoryResponse getCostsHistory(String accountId, String start, String end, String interval) {
        logger.info("📈 Calling AutoSpotting API: GET /v1/costs/history");
        logger.info("📊 Parameters: account={}, start={}, end={}, interval={}", accountId, start, end, interval);

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/costs/history")
                    .queryParam("account_id", accountId)
                    .queryParamIfPresent("start", Optional.ofNullable(start))
                    .queryParamIfPresent("end", Optional.ofNullable(end))
                    .queryParamIfPresent("interval", Optional.ofNullable(interval))
                    .toUriString();

            HttpHeaders headers = createHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<HistoryResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, HistoryResponse.class);

            logger.info("✅ Cost history retrieved: {} data points",
                    response.getBody() != null && response.getBody().getDataPoints() != null
                            ? response.getBody().getDataPoints().size()
                            : 0);

            return response.getBody();

        } catch (Exception e) {
            logger.error("❌ Failed to call /v1/costs/history: {}", e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Enable ASG (POST /v1/asg/enable)
     */
    public SuccessResponse enableAsg(String asgName, String accountId, String region) {
        logger.info("🔛 Calling API: POST /v1/asg/enable for {} in {}", asgName, region);

        try {
            String url = baseUrl + "/v1/asg/enable";

            HttpHeaders headers = createHeaders();

            var requestBody = new java.util.HashMap<String, String>();
            requestBody.put("asg_name", asgName);
            requestBody.put("account_id", accountId);
            requestBody.put("region", region);

            HttpEntity<java.util.Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<SuccessResponse> response = restTemplate.postForEntity(
                    url, entity, SuccessResponse.class);

            logger.info("✅ ASG {} enabled successfully", asgName);
            return response.getBody();

        } catch (Exception e) {
            logger.error("❌ Failed to enable ASG via API: {}", e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Disable ASG (POST /v1/asg/disable)
     */
    public SuccessResponse disableAsg(String asgName, String accountId, String region) {
        logger.info("🔴 Calling API: POST /v1/asg/disable for {} in {}", asgName, region);

        try {
            String url = baseUrl + "/v1/asg/disable";

            HttpHeaders headers = createHeaders();

            var requestBody = new java.util.HashMap<String, String>();
            requestBody.put("asg_name", asgName);
            requestBody.put("account_id", accountId);
            requestBody.put("region", region);

            HttpEntity<java.util.Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<SuccessResponse> response = restTemplate.postForEntity(
                    url, entity, SuccessResponse.class);

            logger.info("✅ ASG {} disabled successfully", asgName);
            return response.getBody();

        } catch (Exception e) {
            logger.error("❌ Failed to disable ASG via API: {}", e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get ASG config (GET /v1/asg/config)
     */
    public ASGConfig getAsgConfig(String asgName, String accountId, String region) {
        logger.info("⚙️ Calling API: GET /v1/asg/config for {} in {}", asgName, region);

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/asg/config")
                    .queryParam("asg_name", asgName)
                    .queryParam("account_id", accountId)
                    .queryParam("region", region)
                    .toUriString();

            HttpHeaders headers = createHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<ASGConfig> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, ASGConfig.class);

            logger.info("✅ ASG config retrieved for {}", asgName);
            return response.getBody();

        } catch (Exception e) {
            logger.error("❌ Failed to get ASG config: {}", e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Update ASG config (PUT /v1/asg/config)
     */
    public ASGConfig updateAsgConfig(String asgName, String accountId, String region, ASGConfigUpdate config) {
        logger.info("🔧 Calling API: PUT /v1/asg/config for {} in {}", asgName, region);

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/asg/config")
                    .queryParam("asg_name", asgName)
                    .queryParam("account_id", accountId)
                    .queryParam("region", region)
                    .toUriString();

            HttpHeaders headers = createHeaders();
            HttpEntity<ASGConfigUpdate> entity = new HttpEntity<>(config, headers);

            ResponseEntity<ASGConfig> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, ASGConfig.class);

            logger.info("✅ ASG config updated for {}", asgName);
            return response.getBody();

        } catch (Exception e) {
            logger.error("❌ Failed to update ASG config: {}", e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get events/actions history (GET /v1/events)
     */
    public EventsResponse getEvents(String accountId, String start, String end, String eventType, String asgName) {
        logger.info("📋 Calling AutoSpotting API: GET /v1/events");
        logger.info("📊 Parameters: account={}, start={}, end={}, eventType={}, asgName={}",
                accountId, start, end, eventType, asgName);

        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/events")
                    .queryParam("account_id", accountId);

            if (start != null && !start.isEmpty())
                builder.queryParam("start", start);
            if (end != null && !end.isEmpty())
                builder.queryParam("end", end);
            if (eventType != null && !eventType.isEmpty())
                builder.queryParam("event_type", eventType);
            if (asgName != null && !asgName.isEmpty())
                builder.queryParam("asg_name", asgName);

            String url = builder.toUriString();
            logger.debug("🌐 Events Request URL: {}", url);

            HttpHeaders headers = createHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<EventsResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, EventsResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                EventsResponse eventsResponse = response.getBody();
                logger.info("✅ Events retrieved successfully!");
                logger.info("  📊 Total events: {}", eventsResponse.getCount());

                if (eventsResponse.getSummary() != null) {
                    logger.info("  🔄 Replacements: {}", eventsResponse.getSummary().getTotalReplacements());
                    logger.info("  ⏹️ Interruptions: {}", eventsResponse.getSummary().getTotalInterruptions());
                    logger.info("  💰 Total savings: ${}",
                            String.format("%.4f", eventsResponse.getSummary().getTotalEstimatedSavings()));
                }

                return eventsResponse;
            } else {
                logger.warn("⚠️ Empty events response from API");
                return createEmptyEventsResponse(start, end);
            }

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            logger.error("❌ HTTP {} Error from AutoSpotting Events API", e.getStatusCode());
            logger.error("   📥 Response body: {}", e.getResponseBodyAsString());
            throw new RuntimeException(
                    "AutoSpotting Events API request failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(),
                    e);
        } catch (Exception e) {
            logger.error("❌ Failed to call AutoSpotting Events API: {}", e.getMessage(), e);
            throw new RuntimeException("AutoSpotting Events API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Create empty events response when no data is available
     */
    private EventsResponse createEmptyEventsResponse(String start, String end) {
        EventsResponse response = new EventsResponse();
        response.setStart(start);
        response.setEnd(end);
        response.setCount(0);
        response.setEvents(new java.util.ArrayList<>());

        EventsSummary summary = new EventsSummary();
        summary.setTotalReplacements(0);
        summary.setTotalInterruptions(0);
        summary.setTotalEstimatedSavings(0.0);
        response.setSummary(summary);

        return response;
    }

    /**
     * Get launch analytics (GET /v1/analytics/launches)
     */
    public LaunchAnalyticsResponse getLaunchAnalytics(String accountId, String start, String end) {
        logger.info("📊 Calling AutoSpotting API: GET /v1/analytics/launches");
        logger.info("📊 Parameters: account={}, start={}, end={}", accountId, start, end);

        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/analytics/launches")
                    .queryParam("account_id", accountId);

            if (start != null && !start.isEmpty())
                builder.queryParam("start", start);
            if (end != null && !end.isEmpty())
                builder.queryParam("end", end);

            String url = builder.toUriString();
            logger.info("🌐 Request URL: {}", url);

            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<LaunchAnalyticsResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, LaunchAnalyticsResponse.class);

            logger.info("✅ Launch analytics retrieved successfully");
            if (response.getBody() != null) {
                logger.info("  - Total attempts: {}", response.getBody().getTotalAttempts());
                logger.info("  - Total successes: {}", response.getBody().getTotalSuccesses());
                logger.info("  - Success rate: {}%", response.getBody().getSuccessRate());
            }

            return response.getBody();

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            logger.error("❌ HTTP {} Error from AutoSpotting Analytics API", e.getStatusCode());
            logger.error("   📥 Response body: {}", e.getResponseBodyAsString());
            throw new RuntimeException(
                    "AutoSpotting Analytics API request failed: " + e.getStatusCode() + " "
                            + e.getResponseBodyAsString(),
                    e);
        } catch (Exception e) {
            logger.error("❌ Failed to call AutoSpotting Analytics API: {}", e.getMessage(), e);
            throw new RuntimeException("AutoSpotting Analytics API request failed: " + e.getMessage(), e);
        }
    }
}
