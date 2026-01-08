package com.xammer.cloud.service;

import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.cloudidr.*;
import com.xammer.cloud.repository.CloudAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

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

    @Autowired(required = false)
    private AwsClientProvider awsClientProvider;

    @Autowired(required = false)
    private CloudAccountRepository cloudAccountRepository;

    // Thread-safe state
    private volatile String cachedAccessToken;
    private volatile String cachedUserId;
    private volatile List<String> cachedCookies;
    private volatile long tokenExpiryTime = 0;
    private final Object tokenLock = new Object();

    public CloudIdrService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    @PostConstruct
    public void init() {
        if (apiEnabled) {
            if (baseUrl.contains("external")) {
                baseUrl = DEFAULT_BASE_URL;
            }
            log.info("CloudIDR API Enabled. Target: {}", baseUrl);
        }
    }

    // ==================== AUTHENTICATION ====================

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

                    String userId = String.valueOf(respBody.get("userId"));
                    if (userId == null || userId.equals("null"))
                        userId = String.valueOf(respBody.get("id"));

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

    private HttpHeaders createAuthHeaders() {
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

    // ==================== CLOUDFORMATION DEPLOYMENT ====================

    /**
     * Generate AWS CloudFormation Quick-Create URL
     */
    public String generateQuickCreateUrl(String accountId) {
        try {
            log.info("🚀 Generating CloudFormation Quick-Create URL for account: {}", accountId);

            // Read template from resources
            String template = getCloudFormationTemplate();

            // Get account region preference (default to us-east-1)
            String region = cloudAccountRepository.getDefaultRegion(accountId);
            if (region == null || region.isEmpty()) {
                region = "us-east-1";
            }

            // Build Quick-Create URL
            String stackName = "CloudIDR-Integration-Stack";
            String encodedTemplate = URLEncoder.encode(template, StandardCharsets.UTF_8);

            String quickCreateUrl = String.format(
                    "https://console.aws.amazon.com/cloudformation/home?region=%s#/stacks/quickcreate?" +
                            "stackName=%s&templateBody=%s",
                    region,
                    stackName,
                    encodedTemplate);

            log.info("✅ Generated Quick-Create URL for account: {}", accountId);
            return quickCreateUrl;

        } catch (Exception e) {
            log.error("❌ Failed to generate Quick-Create URL", e);
            throw new RuntimeException("Failed to generate URL: " + e.getMessage());
        }
    }

    /**
     * Deploy CloudFormation Stack programmatically using AWS SDK
     */
    public Map<String, Object> deployCloudFormationStack(String accountId, String region) {
        try {
            log.info("🚀 Starting CloudFormation stack deployment for account: {} in region: {}", accountId, region);

            // Get CloudFormation client
            CloudFormationClient cfClient = awsClientProvider.createCloudFormationClient(accountId, region);

            // Read template
            String template = getCloudFormationTemplate();

            // Create stack request
            String stackName = "CloudIDR-Integration-Stack";
            CreateStackRequest createRequest = CreateStackRequest.builder()
                    .stackName(stackName)
                    .templateBody(template)
                    .capabilities(Capability.CAPABILITY_NAMED_IAM) // Required for IAM role creation
                    .tags(
                            Tag.builder().key("ManagedBy").value("XamOps").build(),
                            Tag.builder().key("Purpose").value("CloudIDR-Integration").build(),
                            Tag.builder().key("AccountId").value(accountId).build())
                    .build();

            // Deploy stack
            CreateStackResponse result = cfClient.createStack(createRequest);
            String stackId = result.stackId();

            log.info("✅ Stack deployment initiated - Stack ID: {}", stackId);

            // Update database
            cloudAccountRepository.updateCloudFormationStackId(accountId, stackId, "CREATE_IN_PROGRESS");

            // Wait for stack creation (async)
            CompletableFuture.runAsync(() -> waitForStackCreation(cfClient, stackName, accountId, region));

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("stackId", stackId);
            response.put("stackName", stackName);
            response.put("status", "CREATE_IN_PROGRESS");
            response.put("region", region);
            response.put("accountId", accountId);
            response.put("message", "Stack deployment initiated. It will take 1-2 minutes to complete.");

            return response;

        } catch (Exception e) {
            log.error("❌ Failed to deploy CloudFormation stack", e);
            throw new RuntimeException("Stack deployment failed: " + e.getMessage());
        }
    }

    /**
     * Wait for stack creation and auto-link account
     */
    private void waitForStackCreation(CloudFormationClient cfClient, String stackName, String accountId,
            String region) {
        try {
            log.info("⏳ Waiting for stack creation to complete: {}", stackName);

            // Poll stack status every 10 seconds
            for (int i = 0; i < 60; i++) { // Max 10 minutes
                Thread.sleep(10000);

                DescribeStacksRequest describeRequest = DescribeStacksRequest.builder()
                        .stackName(stackName)
                        .build();
                DescribeStacksResponse describeResult = cfClient.describeStacks(describeRequest);

                if (describeResult.stacks().isEmpty()) {
                    log.error("❌ Stack not found: {}", stackName);
                    return;
                }

                Stack stack = describeResult.stacks().get(0);
                StackStatus status = stack.stackStatus();

                log.info("📊 Stack status: {}", status);

                if (StackStatus.CREATE_COMPLETE.equals(status)) {
                    log.info("✅ Stack creation completed successfully");

                    // Extract Role ARN from outputs
                    String roleArn = null;
                    for (Output output : stack.outputs()) {
                        if ("RoleArn".equals(output.outputKey())) {
                            roleArn = output.outputValue();
                            break;
                        }
                    }

                    if (roleArn != null) {
                        // Auto-link account to CloudIDR
                        log.info("🔗 Auto-linking account with Role ARN: {}", roleArn);
                        CloudIdrLinkAccountRequest linkRequest = new CloudIdrLinkAccountRequest();
                        linkRequest.setAwsAccountId(accountId);
                        linkRequest.setRoleArn(roleArn);
                        linkRequest.setAccountName("AWS-" + accountId);

                        try {
                            linkAccount(linkRequest);
                        } catch (Exception e) {
                            log.error("❌ Failed to auto-link account: {}", e.getMessage());
                        }
                    }

                    // Update database
                    cloudAccountRepository.updateCloudFormationStackStatus(accountId, "CREATE_COMPLETE");
                    return;

                } else if (status.toString().contains("FAILED") || status.toString().contains("ROLLBACK")) {
                    log.error("❌ Stack creation failed with status: {}", status);
                    cloudAccountRepository.updateCloudFormationStackStatus(accountId, status.toString());
                    return;
                }
            }

            log.warn("⚠️ Stack creation timeout after 10 minutes");

        } catch (Exception e) {
            log.error("❌ Error while waiting for stack creation", e);
        }
    }

    /**
     * Get stack deployment status
     */
    public Map<String, Object> getStackDeploymentStatus(String accountId) {
        try {
            String stackId = cloudAccountRepository.getCloudFormationStackId(accountId);
            String stackStatus = cloudAccountRepository.getCloudFormationStackStatus(accountId);

            Map<String, Object> status = new HashMap<>();
            status.put("accountId", accountId);
            status.put("stackDeployed", stackId != null && !stackId.isEmpty());
            status.put("stackId", stackId);
            status.put("stackStatus", stackStatus != null ? stackStatus : "NOT_DEPLOYED");

            // If stack exists, get real-time status from AWS
            if (stackId != null && !stackId.isEmpty()) {
                try {
                    String region = cloudAccountRepository.getDefaultRegion(accountId);
                    if (region == null || region.isEmpty())
                        region = "us-east-1";

                    CloudFormationClient cfClient = awsClientProvider.createCloudFormationClient(accountId, region);
                    DescribeStacksRequest request = DescribeStacksRequest.builder().stackName(stackId).build();
                    DescribeStacksResponse result = cfClient.describeStacks(request);

                    if (!result.stacks().isEmpty()) {
                        Stack stack = result.stacks().get(0);
                        status.put("stackStatus", stack.stackStatus().toString());
                        status.put("stackStatusReason", stack.stackStatusReason());

                        // Extract Role ARN from outputs
                        for (Output output : stack.outputs()) {
                            if ("RoleArn".equals(output.outputKey())) {
                                status.put("roleArn", output.outputValue());
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("⚠️ Could not fetch real-time stack status: {}", e.getMessage());
                }
            }

            return status;

        } catch (Exception e) {
            log.error("❌ Failed to get stack deployment status", e);
            throw new RuntimeException("Failed to get status: " + e.getMessage());
        }
    }

    /**
     * Get CloudFormation template as string
     */
    public String getCloudFormationTemplate() {
        try {
            // Read from classpath resources
            ClassPathResource resource = new ClassPathResource("cloudformation/CloudFormationTemplate.json");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("❌ Failed to read CloudFormation template", e);
            throw new RuntimeException("Failed to read template: " + e.getMessage());
        }
    }

    // ==================== ACCOUNT LINKING ====================

    /**
     * Link AWS account to CloudIDR
     */
    public CloudIdrLinkAccountResponse linkAccount(CloudIdrLinkAccountRequest request) {
        try {
            log.info("🔗 Initiating CloudIDR account linking for AWS account: {}", request.getAwsAccountId());

            // Build link payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("awsAccountId", request.getAwsAccountId());
            payload.put("roleArn", request.getRoleArn());
            payload.put("accountName",
                    request.getAccountName() != null ? request.getAccountName() : "AWS-" + request.getAwsAccountId());

            log.info("📤 Sending account link request to CloudIDR API");

            String cspAccountId = null;

            try {
                // Call CloudIDR API to register account
                HttpHeaders headers = createAuthHeaders();
                ResponseEntity<Map> response = restTemplate.exchange(
                        baseUrl + "/accounts/link", // Replace with actual CloudIDR endpoint
                        HttpMethod.POST,
                        new HttpEntity<>(payload, headers),
                        Map.class);

                Map<String, Object> responseBody = response.getBody();
                if (responseBody != null) {
                    cspAccountId = (String) responseBody.get("cspAccountId");
                }
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                log.warn("⚠️ CloudIDR API not available or returned error: {}", e.getMessage());
            }

            // If CloudIDR doesn't provide cspAccountId, generate one temporarily
            if (cspAccountId == null || cspAccountId.isEmpty()) {
                cspAccountId = "CSP-" + request.getAwsAccountId();
                log.warn("⚠️ CloudIDR didn't return cspAccountId, using fallback: {}", cspAccountId);
            }

            // Store in database with Instant timestamp
            cloudAccountRepository.updateCloudIdrLink(
                    request.getAwsAccountId(),
                    cspAccountId,
                    request.getRoleArn(),
                    "LINKED",
                    Instant.now()); // ✅ Added Instant parameter

            log.info("✅ Account linked successfully - CSP ID: {}", cspAccountId);

            // Build response
            CloudIdrLinkAccountResponse linkResponse = new CloudIdrLinkAccountResponse();
            linkResponse.setCspAccountId(cspAccountId);
            linkResponse.setAwsAccountId(request.getAwsAccountId());
            linkResponse.setRoleArn(request.getRoleArn());
            linkResponse.setStatus("LINKED");
            linkResponse.setMessage("Account successfully linked to CloudIDR");
            linkResponse.setLinkedAt(Instant.now().toString());

            return linkResponse;

        } catch (Exception e) {
            log.error("❌ Failed to link account to CloudIDR", e);
            throw new RuntimeException("Account linking failed: " + e.getMessage());
        }
    }

    /**
     * Get CloudIDR CSP Account ID for AWS Account
     */
    public String getCspAccountId(String awsAccountId) {
        String cspAccountId = cloudAccountRepository.getCloudIdrCspId(awsAccountId);

        if (cspAccountId == null || cspAccountId.isEmpty()) {
            throw new RuntimeException("AWS Account " + awsAccountId +
                    " is not linked to CloudIDR. Please link the account first via Settings > CloudIDR Integration.");
        }

        return cspAccountId;
    }

    /**
     * Get account link status
     */
    public Map<String, Object> getAccountLinkStatus(String awsAccountId) {
        Map<String, Object> status = new HashMap<>();

        String cspAccountId = cloudAccountRepository.getCloudIdrCspId(awsAccountId);
        String roleArn = cloudAccountRepository.getCloudIdrRoleArn(awsAccountId);
        String linkStatus = cloudAccountRepository.getCloudIdrStatus(awsAccountId);

        status.put("awsAccountId", awsAccountId);
        status.put("isLinked", cspAccountId != null && !cspAccountId.isEmpty());
        status.put("cspAccountId", cspAccountId);
        status.put("roleArn", roleArn);
        status.put("status", linkStatus != null ? linkStatus : "NOT_LINKED");

        return status;
    }

    // ==================== DATE/TIME FORMATTING ====================

    private String formatToCloudIdrDateTime(String dateTimeStr, String timezone) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }

        Map<String, String> timezoneOffsets = new HashMap<>();
        timezoneOffsets.put("America/New_York", "-05:00");
        timezoneOffsets.put("America/Chicago", "-06:00");
        timezoneOffsets.put("America/Denver", "-07:00");
        timezoneOffsets.put("America/Phoenix", "-07:00");
        timezoneOffsets.put("America/Los_Angeles", "-08:00");
        timezoneOffsets.put("America/Anchorage", "-09:00");
        timezoneOffsets.put("Pacific/Honolulu", "-10:00");

        String offset = timezoneOffsets.getOrDefault(timezone, "-08:00");

        if (!dateTimeStr.contains(":00:00")) {
            dateTimeStr = dateTimeStr + ":00";
        }

        return dateTimeStr + offset;
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

            HttpEntity<Void> entity = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("values")) {
                    JsonNode values = root.get("values");
                    if (values.isArray()) {
                        try {
                            List<PolicyDetails> policies = Arrays
                                    .asList(objectMapper.treeToValue(values, PolicyDetails[].class));
                            log.info("✅ Retrieved {} policies", policies.size());
                            return policies;
                        } catch (Exception e) {
                            log.error("❌ JSON Mapping Error: {}", e.getMessage());
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
                String statsUrl = baseUrl + "/policies/schedule/" + userId + "/stats";
                try {
                    ResponseEntity<Map> response = restTemplate.exchange(statsUrl, HttpMethod.GET,
                            new HttpEntity<>(createAuthHeaders()), Map.class);
                    if (response.getBody() != null)
                        return response.getBody();
                } catch (Exception ignored) {
                }

                String url = baseUrl + "/dashboard/" + userId + "/total";
                ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET,
                        new HttpEntity<>(createAuthHeaders()), Map.class);
                if (response.getBody() != null)
                    return response.getBody();
            }
        } catch (Exception e) {
            log.warn("⚠️ Failed to fetch summary stats, calculating locally: {}", e.getMessage());
        }
        return calculateSummaryFromList(getAllScheduledCapacities());
    }

    // ==================== ACTION METHODS ====================

    public SearchResponse searchCompute(CloudIdrSearchRequest request) {
        if (!apiEnabled)
            return getMockSearchResponse();
        try {
            validateConfiguration();
            String url = baseUrl + "/flexcompute/search";
            HttpEntity<CloudIdrSearchRequest> entity = new HttpEntity<>(request, createAuthHeaders());
            return restTemplate.postForObject(url, entity, SearchResponse.class);
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

            // STEP 1: Get CloudIDR CSP Account ID from database
            String cspAccountId = getCspAccountId(request.getAccountId());

            log.info("🔗 Using CloudIDR CSP Account: {} for AWS Account: {}",
                    cspAccountId, request.getAccountId());

            // STEP 2: Parse region/AZ from format: "us-west-2/usw2-az1"
            String[] regionParts = request.getRegion().split("/");
            String regionCode = regionParts[0];
            String availabilityZoneId = regionParts.length > 1 ? regionParts[1] : "";

            // STEP 3: Format dates to ISO with timezone offset
            String launchDateTime = formatToCloudIdrDateTime(request.getStartDateTime(), request.getTimezone());
            String endDateTime = formatToCloudIdrDateTime(request.getEndDateTime(), request.getTimezone());

            // STEP 4: Build CloudIDR API payload with CSP Account ID
            Map<String, Object> payload = new HashMap<>();
            payload.put("cspAccount", cspAccountId); // ✅ Use CloudIDR's CSP ID instead of AWS account ID
            payload.put("policyName", request.getPolicyName());
            payload.put("instanceType", request.getInstanceType());
            payload.put("platform", "Linux/UNIX");
            payload.put("tenancy", "DEDICATED");
            payload.put("quantity", request.getQuantity());
            payload.put("regionCode", regionCode);
            payload.put("availabilityZoneId", availabilityZoneId);
            payload.put("launchDateTime", launchDateTime);
            payload.put("endDateTime", endDateTime);
            payload.put("autoRenewalEnabled", "Yes".equalsIgnoreCase(request.getAutoRenewal()));
            payload.put("vpc", "default");
            payload.put("securityGroups", "none");
            payload.put("amiImage", "default");
            payload.put("sshPairKey", "none");
            payload.put("ebsVolumeSize", 10);

            // Log the exact payload for debugging
            String payloadJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            log.info("📤 SENDING TO CLOUDIDR:\n{}", payloadJson);

            String url = baseUrl + "/policies/schedule";
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, createAuthHeaders());

            // Use the proper DTO for response
            ResponseEntity<CloudIdrReserveResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    CloudIdrReserveResponse.class);

            CloudIdrReserveResponse apiResponse = response.getBody();

            if (apiResponse != null && apiResponse.getMyPolicy() != null) {
                log.info("✅ CloudIDR Response - CIDR ID: {}, Status: {}",
                        apiResponse.getMyPolicy().getCidrId(),
                        apiResponse.getMyPolicy().getPolicyStatus());

                ReserveResponse rr = new ReserveResponse();
                rr.setCidrId(apiResponse.getMyPolicy().getCidrId());
                rr.setStatus(apiResponse.getMyPolicy().getPolicyStatus());
                rr.setPolicyName(request.getPolicyName());
                return rr;
            }

            throw new RuntimeException("Empty or invalid response from CloudIDR API");

        } catch (HttpServerErrorException e) {
            log.error("❌ CloudIDR 500 Error Response: {}", e.getResponseBodyAsString());
            throw new RuntimeException("Reservation failed: " + e.getMessage());
        } catch (HttpClientErrorException.NotFound e) {
            log.error("❌ Reserve Endpoint Not Found: {}", e.getMessage());
            throw new RuntimeException("Reserve API not found at " + baseUrl + "/policies/schedule");
        } catch (HttpClientErrorException.BadRequest e) {
            log.error("❌ CloudIDR 400 Bad Request: {}", e.getResponseBodyAsString());
            throw new RuntimeException("Reservation failed: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("❌ Reserve failed", e);
            throw new RuntimeException("Reservation failed: " + e.getMessage());
        }
    }

    public PurchaseResponse purchaseCapacity(String cidrId) {
        if (!apiEnabled)
            return getMockPurchaseResponse(cidrId);
        try {
            validateConfiguration();
            String url = baseUrl + "/policies/schedule/purchase";

            Map<String, String> body = new HashMap<>();
            body.put("cidrId", cidrId);

            log.info("💳 Purchasing capacity: {}", cidrId);

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, createAuthHeaders());
            ResponseEntity<PurchaseResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    PurchaseResponse.class);

            log.info("✅ Purchase successful for: {}", cidrId);
            return response.getBody();
        } catch (Exception e) {
            log.error("❌ Purchase failed for {}: {}", cidrId, e.getMessage());
            throw new RuntimeException("Purchase failed: " + e.getMessage());
        }
    }

    public CancelResponse cancelPolicy(String cidrId) {
        if (!apiEnabled)
            return getMockCancelResponse();
        try {
            validateConfiguration();
            String url = baseUrl + "/policies/schedule/cancel";

            log.info("🚫 Cancelling policy: {}", cidrId);

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(
                    Collections.singletonMap("cidrId", cidrId),
                    createAuthHeaders());

            ResponseEntity<CancelResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    CancelResponse.class);

            log.info("✅ Policy cancelled: {}", cidrId);
            return response.getBody();
        } catch (Exception e) {
            log.error("❌ Cancel failed for {}: {}", cidrId, e.getMessage());
            throw new RuntimeException("Cancel failed: " + e.getMessage());
        }
    }

    public PolicyDetails getSchedulePolicy(String cidrId) {
        if (!apiEnabled)
            return null;
        try {
            validateConfiguration();
            String url = baseUrl + "/policies/schedule/" + cidrId + "/view";
            return restTemplate
                    .exchange(url, HttpMethod.GET, new HttpEntity<>(createAuthHeaders()), PolicyDetails.class)
                    .getBody();
        } catch (Exception e) {
            log.error("Fetch Policy failed", e);
            return null;
        }
    }

    // ==================== UTILITY METHODS ====================

    public void clearTokenCache() {
        synchronized (tokenLock) {
            cachedAccessToken = null;
            cachedUserId = null;
            cachedCookies = null;
            tokenExpiryTime = 0;
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

    // ==================== HELPERS ====================

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
        SearchResponse r = new SearchResponse();
        r.setInstanceTypeOfferings(Collections.emptyList());
        return r;
    }

    private ReserveResponse getMockReserveResponse() {
        ReserveResponse r = new ReserveResponse();
        r.setCidrId("mock-" + System.currentTimeMillis());
        r.setStatus("MOCK");
        return r;
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
        m.put("totalCost", 0.0);
        m.put("activeCount", 0);
        return m;
    }
}
