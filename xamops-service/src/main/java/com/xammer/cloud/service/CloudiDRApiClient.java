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
     * CloudiDR returns an array of results, we take the first one
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

            // CloudiDR returns an array of search results
            ResponseEntity<SearchCapacityResponse[]> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, SearchCapacityResponse[].class);

            if (response.getBody() == null || response.getBody().length == 0) {
                logger.warn("CloudiDR search-capacity returned no results");
                throw new RuntimeException("No capacity available for the specified criteria");
            }

            // Get the first result from CloudiDR (only contains title, status, startDateTime, endDateTime)
            SearchCapacityResponse result = response.getBody()[0];
            logger.info("CloudiDR search-capacity successful, found {} result(s), returning first: status={}",
                    response.getBody().length, result.getStatus());

            // Enrich the response with data from the original request
            // CloudiDR search response doesn't include these fields, so we populate them from the request
            result.setInstanceType(request.getInstanceType());
            result.setZoneId(request.getZoneId());
            result.setPlatform(request.getPlatform() != null ? request.getPlatform() : "Linux/UNIX");
            result.setStartDate(request.getStartDate());
            result.setEndDate(request.getEndDate());

            // Additional fields from request
            if (request.getQuantity() != null) {
                result.setQuantity(request.getQuantity());
            }
            if (request.getRegion() != null) {
                result.setRegion(request.getRegion());
            }
            if (request.getCapacityName() != null) {
                result.setCapacityName(request.getCapacityName());
            }
            result.setTenancy("Shared"); // Fixed value as per UI

            logger.info("Enriched search response with request parameters: instanceType={}, zoneId={}, platform={}, quantity={}, region={}",
                    result.getInstanceType(), result.getZoneId(), result.getPlatform(), result.getQuantity(), result.getRegion());

            return result;

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
     * Creates a temporary 15-minute reservation and returns cidrId + pricing
     */
    public ReserveCapacityResponse reserveCapacity(String token, ReserveCapacityRequest request) {
        logger.info("CloudiDR API: POST /external/api/v1/flexcompute/reserve for instance type: {}", request.getInstanceType());
        logger.debug("Reserve request details: accountId={}, zoneId={}, policyName={}, platform={}, quantity={}, startDate={}, endDate={}, startTime={}, endTime={}, utcOffsetHours={}",
                request.getAccountId(), request.getZoneId(), request.getPolicyName(), request.getPlatform(),
                request.getQuantity(), request.getStartDate(), request.getEndDate(),
                request.getStartTime(), request.getEndTime(), request.getUtcOffsetHours());

        try {
            String url = baseUrl + "/external/api/v1/flexcompute/reserve";

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<ReserveCapacityRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<ReserveCapacityResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, ReserveCapacityResponse.class);

            ReserveCapacityResponse result = response.getBody();
            if (result != null && result.getMyPolicy() != null) {
                logger.info("CloudiDR reserve-capacity successful: cidrId={}, status={}, ourPrice={}, savings={}",
                        result.getMyPolicy().getCidrId(),
                        result.getMyPolicy().getPolicyStatus(),
                        result.getMyPolicy().getPricing() != null ? result.getMyPolicy().getPricing().getOurPrice() : null,
                        result.getMyPolicy().getPricing() != null ? result.getMyPolicy().getPricing().getSavings() : null);
            }
            return result;

        } catch (HttpClientErrorException e) {
            String errorBody = e.getResponseBodyAsString();
            logger.error("CloudiDR reserve-capacity failed: HTTP {} - {}", e.getStatusCode(), errorBody);

            // Try to extract error message from CloudiDR response
            String errorMessage = "Reserve capacity failed: " + e.getStatusCode();
            if (errorBody != null && !errorBody.isEmpty()) {
                try {
                    // Try to parse error message from response body
                    if (errorBody.contains("message") || errorBody.contains("error")) {
                        errorMessage = "Reserve capacity failed: " + errorBody;
                    }
                } catch (Exception ex) {
                    // If parsing fails, use the raw error body
                    errorMessage = "Reserve capacity failed: " + errorBody;
                }
            }
            throw new RuntimeException(errorMessage, e);
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
                // Add each status as a separate query param: ?statuses=ACTIVE&statuses=COMPLETED
                for (String status : statuses) {
                    builder.queryParam("statuses", status);
                }
            }

            String url = builder.encode().toUriString();

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

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

    /**
     * PUT /external/api/v1/flexcompute/capacity/extend - Extend capacity end date
     */
    public void extendCapacity(String token, ExtendCapacityRequest request) {
        logger.info("CloudiDR API: PUT /external/api/v1/flexcompute/capacity/extend for cidrId={}", request.getCidrId());

        try {
            String url = baseUrl + "/external/api/v1/flexcompute/capacity/extend";

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<ExtendCapacityRequest> entity = new HttpEntity<>(request, headers);

            logger.info("CloudiDR extend-capacity request: cidrId={}, endDate={}, endTime={}, utcOffset={}",
                    request.getCidrId(), request.getEndDate(), request.getEndTime(), request.getUtcOffset());

            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);

            logger.info("CloudiDR extend-capacity successful for id: {}", request.getCidrId());

        } catch (HttpClientErrorException e) {
            logger.error("CloudiDR extend-capacity failed: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Extend capacity failed: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            logger.error("CloudiDR extend-capacity error: {}", e.getMessage(), e);
            throw new RuntimeException("Extend capacity failed: " + e.getMessage(), e);
        }
    }

    /**
     * DELETE /external/api/v1/flexcompute/capacity/{cidrId} - Cancel/Delete capacity
     */
    public void cancelCapacity(String token, String cidrId) {
        logger.info("CloudiDR API: DELETE /external/api/v1/flexcompute/capacity/{}", cidrId);

        try {
            String url = baseUrl + "/external/api/v1/flexcompute/capacity/" + cidrId;

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);

            logger.info("CloudiDR cancel-capacity successful for id: {}", cidrId);

        } catch (HttpClientErrorException e) {
            logger.error("CloudiDR cancel-capacity failed: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Cancel capacity failed: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            logger.error("CloudiDR cancel-capacity error: {}", e.getMessage(), e);
            throw new RuntimeException("Cancel capacity failed: " + e.getMessage(), e);
        }
    }

    /**
     * PUT /external/api/v1/flexcompute/capacity/auto-renewal - Update capacity auto-renewal settings
     */
    public void renewCapacity(String token, RenewCapacityRequest request) {
        logger.info("CloudiDR API: PUT /external/api/v1/flexcompute/capacity/auto-renewal for cidrId={}", request.getCidrId());

        try {
            String url = baseUrl + "/external/api/v1/flexcompute/capacity/auto-renewal";

            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<RenewCapacityRequest> entity = new HttpEntity<>(request, headers);

            logger.info("CloudiDR renew-capacity request: cidrId={}, autoRenewalEnabled={}, frequency={}",
                    request.getCidrId(), request.getAutoRenewalEnabled(), request.getFrequency());

            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);

            logger.info("CloudiDR renew-capacity successful for id: {}", request.getCidrId());

        } catch (HttpClientErrorException e) {
            logger.error("CloudiDR renew-capacity failed: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Update auto-renewal failed: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            logger.error("CloudiDR renew-capacity error: {}", e.getMessage(), e);
            throw new RuntimeException("Update auto-renewal failed: " + e.getMessage(), e);
        }
    }
}
