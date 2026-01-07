package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.cloudidr.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.*;

@Service
@Slf4j
public class CloudIdrService {

    private static final String DEFAULT_BASE_URL = "https://public-api.cloudidr.com/backend/api/v1";

    @Value("${cloudidr.api.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    @Value("${cloudidr.api.email:}")
    private String email;

    @Value("${cloudidr.api.password:}")
    private String password;

    @Value("${cloudidr.api.customer-id:}")
    private String configuredCustomerId;

    @Value("${cloudidr.api.enabled:false}")
    private boolean apiEnabled;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Thread-safe state
    private volatile String cachedAccessToken;
    private volatile String cachedUserId;
    private volatile List<String> cachedCookies;
    private volatile long tokenExpiryTime = 0;
    private final Object tokenLock = new Object();

    public CloudIdrService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        // CRITICAL FIXES for API Compatibility
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    @PostConstruct
    public void init() {
        if (apiEnabled) {
            // Force backend URL if external is configured mistakenly
            if (baseUrl.contains("external")) {
                baseUrl = DEFAULT_BASE_URL;
            }
            log.info("CloudIDR API Enabled. Target: {}", baseUrl);
        }
    }

    private CloudIdrAuthResponse signIn() {
        synchronized (tokenLock) {
            if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiryTime) {
                CloudIdrAuthResponse response = new CloudIdrAuthResponse();
                response.setAccessToken(cachedAccessToken);
                response.setUserId(cachedUserId);
                return response;
            }

            try {
                log.info("🔐 Authenticating with CloudIDR: {}", email);
                String signInUrl = baseUrl + "/sign-in";

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Origin", "https://console.cloudidr.com");
                headers.set("Referer", "https://console.cloudidr.com/");

                Map<String, String> body = new HashMap<>();
                body.put("email", email);
                body.put("password", password);

                HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
                ResponseEntity<Map> response = restTemplate.exchange(signInUrl, HttpMethod.POST, request, Map.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    Map<String, Object> respBody = response.getBody();
                    String token = (String) respBody.get("accessToken");
                    if (token == null)
                        token = (String) respBody.get("token");

                    // Extract User ID
                    String userId = String.valueOf(respBody.get("userId"));
                    if (userId == null || userId.equals("null"))
                        userId = String.valueOf(respBody.get("id"));

                    // Fallback to config if missing
                    if ((userId == null || userId.equals("null")) && configuredCustomerId != null) {
                        userId = configuredCustomerId;
                    }

                    List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);

                    if (token != null) {
                        CloudIdrAuthResponse auth = new CloudIdrAuthResponse();
                        auth.setAccessToken(token);
                        auth.setUserId(userId);

                        cachedAccessToken = token;
                        cachedUserId = userId;
                        cachedCookies = cookies;
                        tokenExpiryTime = System.currentTimeMillis() + (3600 * 1000L);

                        log.info("✅ Login successful. UserID: {}", userId);
                        return auth;
                    }
                }
                throw new RuntimeException("No access token in login response");
            } catch (Exception e) {
                log.error("❌ Login failed: {}", e.getMessage());
                throw new RuntimeException("CloudIDR Login Failed: " + e.getMessage());
            }
        }
    }

    private String getUserId() {
        if (cachedUserId != null && !cachedUserId.equals("null") && !cachedUserId.isEmpty())
            return cachedUserId;
        CloudIdrAuthResponse auth = signIn();
        if (auth.getUserId() != null)
            return auth.getUserId();
        return configuredCustomerId;
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Origin", "https://console.cloudidr.com");
        headers.set("Referer", "https://console.cloudidr.com/");

        if (cachedAccessToken == null || System.currentTimeMillis() >= tokenExpiryTime)
            signIn();
        if (cachedAccessToken != null)
            headers.setBearerAuth(cachedAccessToken);
        if (cachedCookies != null && !cachedCookies.isEmpty())
            headers.put(HttpHeaders.COOKIE, cachedCookies);

        return headers;
    }

    private void validateConfiguration() {
        if (!apiEnabled)
            throw new RuntimeException("CloudIDR API is not enabled.");
    }

    // ==================== DATA OPERATIONS ====================

    public List<PolicyDetails> getAllScheduledCapacities() {
        if (!apiEnabled)
            return Collections.emptyList();

        try {
            validateConfiguration();
            String userId = getUserId();

            if (userId == null) {
                log.error("❌ User ID is null. Cannot fetch capacities.");
                return Collections.emptyList();
            }

            String url = baseUrl + "/policies/schedule/" + userId + "/customer?page=1&pageSize=100";
            log.info("📦 Fetching capacities from: {}", url);

            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                String jsonBody = response.getBody();
                JsonNode root = objectMapper.readTree(jsonBody);

                if (root.has("values")) {
                    JsonNode values = root.get("values");
                    if (values.isArray()) {
                        try {
                            List<PolicyDetails> policies = Arrays
                                    .asList(objectMapper.treeToValue(values, PolicyDetails[].class));
                            log.info("✅ Retrieved {} policies", policies.size());
                            return policies;
                        } catch (Exception e) {
                            log.error("❌ DTO Mapping Error: Your PolicyDetails.java does not match the API response. " +
                                    "Check 'createdDate' type (should be String) and 'PolicyInstance' fields.", e);
                            return Collections.emptyList();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Error fetching capacities", e);
        }
        return Collections.emptyList();
    }

    public Map<String, Object> getCapacitySummary() {
        if (!apiEnabled)
            return getMockSummaryMap();
        try {
            validateConfiguration();
            String userId = getUserId();
            if (userId != null) {
                String url = baseUrl + "/dashboard/" + userId + "/total";
                ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET,
                        new HttpEntity<>(createHeaders()), Map.class);
                if (response.getBody() != null)
                    return response.getBody();
            }
        } catch (Exception e) {
            log.warn("⚠️ Failed to fetch dashboard total: {}", e.getMessage());
        }
        return calculateSummaryFromList(getAllScheduledCapacities());
    }

    // --- Standard Methods ---

    public SearchResponse searchCompute(CloudIdrSearchRequest request) {
        if (!apiEnabled)
            return getMockSearchResponse();
        try {
            validateConfiguration();
            String url = baseUrl + "/flexcompute/search";
            HttpEntity<CloudIdrSearchRequest> entity = new HttpEntity<>(request, createHeaders());
            ResponseEntity<SearchResponse> response = restTemplate.exchange(url, HttpMethod.POST, entity,
                    SearchResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Search failed", e);
            return getMockSearchResponse();
        }
    }

    public ReserveResponse reserveCapacity(CloudIdrReserveRequest request) {
        if (!apiEnabled)
            return getMockReserveResponse();
        try {
            validateConfiguration();
            String url = baseUrl + "/flexcompute/reserve";
            HttpEntity<CloudIdrReserveRequest> entity = new HttpEntity<>(request, createHeaders());
            ResponseEntity<ReserveResponse> response = restTemplate.exchange(url, HttpMethod.POST, entity,
                    ReserveResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Reserve failed", e);
            throw new RuntimeException("Reserve failed: " + e.getMessage());
        }
    }

    public PurchaseResponse purchaseCapacity(String cidrId) {
        if (!apiEnabled)
            return getMockPurchaseResponse(cidrId);
        try {
            validateConfiguration();
            String url = baseUrl + "/flexcompute/purchase";
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(Collections.singletonMap("cidrId", cidrId),
                    createHeaders());
            return restTemplate.postForObject(url, entity, PurchaseResponse.class);
        } catch (Exception e) {
            log.error("Purchase failed", e);
            throw new RuntimeException("Purchase failed: " + e.getMessage());
        }
    }

    public CancelResponse cancelPolicy(String cidrId) {
        if (!apiEnabled)
            return getMockCancelResponse();
        try {
            validateConfiguration();
            String url = baseUrl + "/policies/schedule/cancel";
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(Collections.singletonMap("cidrId", cidrId),
                    createHeaders());
            return restTemplate.postForObject(url, entity, CancelResponse.class);
        } catch (Exception e) {
            log.error("Cancel failed", e);
            throw new RuntimeException("Cancel failed: " + e.getMessage());
        }
    }

    public PolicyDetails getSchedulePolicy(String cidrId) {
        if (!apiEnabled)
            return null;
        try {
            validateConfiguration();
            String url = baseUrl + "/policies/schedule/" + cidrId + "/view";
            return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(createHeaders()), PolicyDetails.class)
                    .getBody();
        } catch (Exception e) {
            log.error("Fetch Policy failed", e);
            return null;
        }
    }

    public void clearTokenCache() {
        synchronized (tokenLock) {
            cachedAccessToken = null;
            cachedUserId = null;
            cachedCookies = null;
        }
    }

    public Map<String, Object> testConnection() {
        Map<String, Object> result = new HashMap<>();
        try {
            validateConfiguration();
            CloudIdrAuthResponse auth = signIn();
            result.put("status", "success");
            result.put("authenticated", true);
            result.put("userId", auth.getUserId());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    // --- Helpers ---
    private Map<String, Object> calculateSummaryFromList(List<PolicyDetails> policies) {
        double savings = 0, cost = 0;
        for (PolicyDetails p : policies) {
            if (p.getPricing() != null) {
                if (p.getPricing().getSavings() != null)
                    savings += p.getPricing().getSavings();
                if (p.getPricing().getTotalCost() != null)
                    cost += p.getPricing().getTotalCost();
            }
        }
        Map<String, Object> map = new HashMap<>();
        map.put("totalSavings", savings);
        map.put("totalCost", cost);
        map.put("activeCount", policies.size());
        return map;
    }

    private SearchResponse getMockSearchResponse() {
        return new SearchResponse();
    }

    private ReserveResponse getMockReserveResponse() {
        return new ReserveResponse();
    }

    private PurchaseResponse getMockPurchaseResponse(String cidrId) {
        return new PurchaseResponse();
    }

    private CancelResponse getMockCancelResponse() {
        return new CancelResponse();
    }

    private Map<String, Object> getMockSummaryMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("totalSavings", 0.0);
        return m;
    }
}