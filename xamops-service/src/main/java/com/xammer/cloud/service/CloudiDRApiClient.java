package com.xammer.cloud.service;

import com.xammer.cloud.dto.flexcompute.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;

@Component
public class CloudiDRApiClient {

    private static final Logger logger = LoggerFactory.getLogger(CloudiDRApiClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public CloudiDRApiClient(
            RestTemplate restTemplate,
            @Value("${cloudidr.api.base-url:https://api.cloudidr.com}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @PostConstruct
    public void validateConfiguration() {
        logger.info("========================================");
        logger.info("CloudiDR API Client Configuration");
        logger.info("========================================");
        logger.info("Base URL: {}", baseUrl);
        logger.info("========================================");
    }

    /**
     * Create headers with Bearer token authentication
     */
    private HttpHeaders createAuthHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(bearerToken);
        return headers;
    }

    /**
     * Create headers without authentication (for sign-in)
     */
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        return headers;
    }

    /**
     * POST /external/api/v1/sign-in - Authenticate with CloudiDR
     */
    public CloudiDRSignInResponse signIn(String email, String password) {
        logger.info("CloudiDR API: POST /external/api/v1/sign-in for email: {}", email);

        try {
            String url = baseUrl + "/external/api/v1/sign-in";
            logger.debug("CloudiDR sign-in URL: {}", url);

            HttpHeaders headers = createHeaders();
            CloudiDRSignInRequest requestBody = new CloudiDRSignInRequest(email, password);
            HttpEntity<CloudiDRSignInRequest> entity = new HttpEntity<>(requestBody, headers);

            logger.debug("CloudiDR sign-in request - username: {}", email);

            ResponseEntity<CloudiDRSignInResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, CloudiDRSignInResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                logger.info("CloudiDR sign-in successful for email: {}", email);
                CloudiDRSignInResponse body = response.getBody();
                logger.info("CloudiDR response - accessToken present: {}, refreshToken present: {}, expiresIn: {}",
                    body.getAccessToken() != null, body.getRefreshToken() != null, body.getExpiresIn());
                logger.debug("Full CloudiDR response: {}", body);
                return body;
            } else {
                logger.warn("CloudiDR sign-in returned unexpected status: {}", response.getStatusCode());
                return null;
            }

        } catch (HttpClientErrorException e) {
            String errorBody = e.getResponseBodyAsString();
            logger.error("CloudiDR sign-in failed: HTTP {} - {}", e.getStatusCode(), errorBody);

            // Parse error message from CloudiDR response if available
            String errorMessage = "Invalid credentials";
            if (errorBody != null && errorBody.contains("description")) {
                try {
                    // Try to extract description from error response
                    int descStart = errorBody.indexOf("\"description\"") + 15;
                    int descEnd = errorBody.indexOf("\"", descStart);
                    if (descStart > 15 && descEnd > descStart) {
                        errorMessage = errorBody.substring(descStart, descEnd);
                    }
                } catch (Exception parseError) {
                    logger.debug("Could not parse CloudiDR error message");
                }
            }

            throw new RuntimeException("CloudiDR: " + errorMessage, e);
        } catch (Exception e) {
            logger.error("CloudiDR sign-in error: {}", e.getMessage(), e);
            throw new RuntimeException("CloudiDR authentication failed: " + e.getMessage(), e);
        }
    }

    /**
     * GET /external/api/v1/flexcompute/search - Search available capacity
     */
    public SearchCapacityResponse searchCapacity(String token, SearchCapacityRequest request) {
        logger.info("CloudiDR API: GET /external/api/v1/flexcompute/search for instance type: {}", request.getInstanceType());

        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/external/api/v1/flexcompute/search");

            // Required parameters
            if (request.getInstanceType() != null) builder.queryParam("instanceType", request.getInstanceType());
            if (request.getZoneId() != null) builder.queryParam("zoneId", request.getZoneId());
            if (request.getStartDate() != null) builder.queryParam("startDate", request.getStartDate());
            if (request.getEndDate() != null) builder.queryParam("endDate", request.getEndDate());
            if (request.getUtcOffsetHours() != null) builder.queryParam("utcOffsetHours", request.getUtcOffsetHours());

            // Optional parameters
            if (request.getPlatform() != null) builder.queryParam("platform", request.getPlatform());

            String url = builder.encode().toUriString();

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<SearchCapacityResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, SearchCapacityResponse.class);

            logger.info("CloudiDR search-capacity successful, capacity_id: {}",
                    response.getBody() != null ? response.getBody().getCapacityId() : "null");
            return response.getBody();

        } catch (HttpClientErrorException e) {
            logger.error("CloudiDR search-capacity failed: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Search capacity failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            logger.error("CloudiDR search-capacity error: {}", e.getMessage(), e);
            throw new RuntimeException("Search capacity failed: " + e.getMessage(), e);
        }
    }

    /**
     * POST /external/api/v1/flexcompute/reserve - Reserve capacity
     * Note: This endpoint requires full reservation details, not just a capacity ID
     */
    public SearchCapacityResponse reserveCapacity(String token, ReserveCapacityRequest request) {
        logger.info("CloudiDR API: POST /external/api/v1/flexcompute/reserve for instance type: {}", request.getInstanceType());

        try {
            String url = baseUrl + "/external/api/v1/flexcompute/reserve";

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<ReserveCapacityRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<SearchCapacityResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, SearchCapacityResponse.class);

            logger.info("CloudiDR reserve-capacity successful");
            return response.getBody();

        } catch (HttpClientErrorException e) {
            logger.error("CloudiDR reserve-capacity failed: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Reserve capacity failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            logger.error("CloudiDR reserve-capacity error: {}", e.getMessage(), e);
            throw new RuntimeException("Reserve capacity failed: " + e.getMessage(), e);
        }
    }

    /**
     * PUT /external/api/v1/flexcompute/capacity-configuration - Configure capacity
     */
    public SearchCapacityResponse configureCapacity(String token, ConfigureCapacityRequest request) {
        logger.info("CloudiDR API: PUT /external/api/v1/flexcompute/capacity-configuration for id: {}", request.getCidrId());

        try {
            String url = baseUrl + "/external/api/v1/flexcompute/capacity-configuration";

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<ConfigureCapacityRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<SearchCapacityResponse> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, SearchCapacityResponse.class);

            logger.info("CloudiDR configure-capacity successful for id: {}", request.getCidrId());
            return response.getBody();

        } catch (HttpClientErrorException e) {
            logger.error("CloudiDR configure-capacity failed: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Configure capacity failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            logger.error("CloudiDR configure-capacity error: {}", e.getMessage(), e);
            throw new RuntimeException("Configure capacity failed: " + e.getMessage(), e);
        }
    }

    /**
     * POST /external/api/v1/flexcompute/purchase - Purchase capacity
     */
    public SearchCapacityResponse purchaseCapacity(String token, PurchaseCapacityRequest request) {
        logger.info("CloudiDR API: POST /external/api/v1/flexcompute/purchase for id: {}", request.getCidrId());

        try {
            String url = baseUrl + "/external/api/v1/flexcompute/purchase";

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<PurchaseCapacityRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<SearchCapacityResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, SearchCapacityResponse.class);

            logger.info("CloudiDR purchase-capacity successful for id: {}", request.getCidrId());
            return response.getBody();

        } catch (HttpClientErrorException e) {
            logger.error("CloudiDR purchase-capacity failed: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Purchase capacity failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            logger.error("CloudiDR purchase-capacity error: {}", e.getMessage(), e);
            throw new RuntimeException("Purchase capacity failed: " + e.getMessage(), e);
        }
    }

    /**
     * GET /external/api/v1/flexcompute/capacities - List all capacities
     */
    public CapacityListResponse describeCapacity(String token, Integer page, Integer pageSize, String sort, List<String> statuses) {
        logger.info("CloudiDR API: GET /external/api/v1/flexcompute/capacities");

        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/external/api/v1/flexcompute/capacities");

            // Optional parameters
            if (page != null) builder.queryParam("page", page);
            if (pageSize != null) builder.queryParam("pageSize", pageSize);
            if (sort != null) builder.queryParam("sort", sort);
            if (statuses != null && !statuses.isEmpty()) {
                builder.queryParam("statuses", String.join(",", statuses));
            }

            String url = builder.encode().toUriString();

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            // First get raw response as String to see what CloudiDR actually returns
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            logger.info("CloudiDR raw capacities response: {}", rawResponse.getBody());

            // Now parse it properly
            ResponseEntity<CapacityListResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, CapacityListResponse.class);

            CapacityListResponse capacityList = response.getBody();
            logger.info("CloudiDR capacities returned {} items",
                    capacityList != null && capacityList.getData() != null ? capacityList.getData().size() : 0);
            return capacityList;

        } catch (HttpClientErrorException e) {
            logger.error("CloudiDR describe-capacity failed: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Describe capacity failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            logger.error("CloudiDR describe-capacity error: {}", e.getMessage(), e);
            throw new RuntimeException("Describe capacity failed: " + e.getMessage(), e);
        }
    }

    /**
     * GET /external/api/v1/my-linked-accounts - Get linked cloud accounts
     */
    public List<LinkedAccountResponse> getLinkedAccounts(String token) {
        logger.info("CloudiDR API: GET /external/api/v1/my-linked-accounts");

        try {
            String url = baseUrl + "/external/api/v1/my-linked-accounts";

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<LinkedAccountResponse[]> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, LinkedAccountResponse[].class);

            List<LinkedAccountResponse> accounts = response.getBody() != null
                    ? java.util.Arrays.asList(response.getBody())
                    : Collections.emptyList();

            logger.info("CloudiDR linked accounts returned {} items", accounts.size());

            // Debug: Log each account to see what we're getting
            for (int i = 0; i < accounts.size(); i++) {
                LinkedAccountResponse acc = accounts.get(i);
                logger.debug("Account {}: accountId={}, accountName={}, provider={}, status={}",
                        i, acc.getAccountId(), acc.getAccountName(), acc.getProvider(), acc.getStatus());
            }

            return accounts;

        } catch (HttpClientErrorException e) {
            logger.error("CloudiDR get-linked-accounts failed: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Get linked accounts failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            logger.error("CloudiDR get-linked-accounts error: {}", e.getMessage(), e);
            throw new RuntimeException("Get linked accounts failed: " + e.getMessage(), e);
        }
    }
}
