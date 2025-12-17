package com.xammer.cloud.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/cloudformation")
@CrossOrigin(origins = "*")
public class CloudFormationController {

    private final CloudFormationClient cloudFormationClient;
    private final DynamoDbClient dynamoDbClient;
    private final StsClient stsClient;

    // XamOps/AutoSpotting Main Account Configuration
    private static final String MAIN_ACCOUNT_ID = "982534352845";
    private static final String MAIN_REGION = "ap-south-1";
    private static final String MAIN_STACK_NAME = "autospotting";

    // Host Role for checking Main Engine Status & DynamoDB Access
    private static final String HOST_ROLE_ARN = "arn:aws:iam::982534352845:role/XamOps-AutoSpotting-HostRole";

    // Lambda Execution Role for the Child Stack
    private static final String LAMBDA_EXECUTION_ROLE_ARN = "arn:aws:iam::982534352845:role/lambda/autospotting-LambdaExecutionRole-QLEgqMN3g4f1";

    // DynamoDB Table in the Main Account
    private static final String DYNAMODB_TABLE_NAME = "autospotting-CustomerAccounts";

    // Template S3 URL
    private static final String TEMPLATE_S3_URL = "https://xamops-cloudformation-templates.s3.amazonaws.com/autospotting-customer-account.yaml";

    // The stack name used in the customer's account
    private static final String CUSTOMER_STACK_NAME = "XamOps-AutoSpotting-Integration";

    // Default regions list
    private static final String DEFAULT_REGIONS = "ap-northeast-1,ap-northeast-2,ap-northeast-3,ap-south-1," +
            "ap-southeast-1,ap-southeast-2," +
            "ca-central-1,eu-central-1,eu-north-1," +
            "eu-west-1,eu-west-2,eu-west-3," +
            "sa-east-1," +
            "us-east-2,us-west-1,us-west-2";

    public CloudFormationController(
            CloudFormationClient cloudFormationClient,
            DynamoDbClient dynamoDbClient,
            StsClient stsClient) {
        this.cloudFormationClient = cloudFormationClient;
        this.dynamoDbClient = dynamoDbClient;
        this.stsClient = stsClient;
    }

    // ================= HELPER METHODS FOR CROSS-ACCOUNT ACCESS =================

    /**
     * Helper: Create a CloudFormation client that assumes the Host Role.
     */
    private CloudFormationClient getHostCloudFormationClient() {
        try {
            AssumeRoleRequest roleRequest = AssumeRoleRequest.builder()
                    .roleArn(HOST_ROLE_ARN)
                    .roleSessionName("XamOps-CF-Check")
                    .build();

            AssumeRoleResponse roleResponse = stsClient.assumeRole(roleRequest);

            AwsSessionCredentials credentials = AwsSessionCredentials.create(
                    roleResponse.credentials().accessKeyId(),
                    roleResponse.credentials().secretAccessKey(),
                    roleResponse.credentials().sessionToken());

            return CloudFormationClient.builder()
                    .region(Region.of(MAIN_REGION))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();

        } catch (Exception e) {
            log.warn("Failed to assume host role {}, falling back to default client: {}", HOST_ROLE_ARN,
                    e.getMessage());
            return this.cloudFormationClient;
        }
    }

    /**
     * Helper: Create a DynamoDB client that assumes the Host Role.
     * This allows the backend to write/read the table in the Main Account (9825...)
     */
    private DynamoDbClient getHostDynamoDbClient() {
        try {
            AssumeRoleRequest roleRequest = AssumeRoleRequest.builder()
                    .roleArn(HOST_ROLE_ARN)
                    .roleSessionName("XamOps-DDB-Access")
                    .build();

            AssumeRoleResponse roleResponse = stsClient.assumeRole(roleRequest);

            AwsSessionCredentials credentials = AwsSessionCredentials.create(
                    roleResponse.credentials().accessKeyId(),
                    roleResponse.credentials().secretAccessKey(),
                    roleResponse.credentials().sessionToken());

            return DynamoDbClient.builder()
                    .region(Region.of(MAIN_REGION)) // DynamoDB table is in ap-south-1
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();

        } catch (Exception e) {
            log.warn("Failed to assume host role {}, falling back to default client: {}", HOST_ROLE_ARN,
                    e.getMessage());
            return this.dynamoDbClient;
        }
    }

    // ================= API ENDPOINTS =================

    /**
     * Sync customer account to DynamoDB (Enable AutoSpotting)
     */
    @PostMapping("/sync-account")
    public ResponseEntity<Map<String, Object>> syncCustomerAccount(@RequestBody Map<String, String> request) {
        log.info("📋 Syncing customer account request received");

        try {
            String customerAccountId = request.get("accountId");
            String accountName = request.get("accountName");
            String regions = request.get("regions");

            if (customerAccountId == null || !customerAccountId.matches("^\\d{12}$")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid account ID",
                        "message", "AWS account ID must be exactly 12 digits"));
            }

            String customerRoleArn = String.format("arn:aws:iam::%s:role/AutoSpotting-Execution-Role",
                    customerAccountId);

            updateDynamoDb(customerAccountId, accountName, regions, customerRoleArn);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Account synced successfully",
                    "accountId", customerAccountId));

        } catch (Exception e) {
            log.error("❌ Error syncing account", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to sync account", "message", e.getMessage()));
        }
    }

    /**
     * Get all customer accounts from DynamoDB
     */
    @GetMapping("/accounts")
    public ResponseEntity<Map<String, Object>> listCustomerAccounts() {
        try {
            // ✅ Use Host Client for Scan
            DynamoDbClient client = getHostDynamoDbClient();
            ScanResponse scanResponse = client.scan(ScanRequest.builder().tableName(DYNAMODB_TABLE_NAME).build());

            return ResponseEntity.ok(Map.of(
                    "accounts", scanResponse.items().stream()
                            .map(item -> Map.of(
                                    "accountId", item.get("AccountId").s(),
                                    "accountName", item.containsKey("AccountName") ? item.get("AccountName").s() : "",
                                    "roleArn", item.containsKey("RoleArn") ? item.get("RoleArn").s() : "",
                                    "state", item.containsKey("State") ? item.get("State").s() : "unknown"))
                            .collect(Collectors.toList()),
                    "count", scanResponse.count()));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to fetch accounts", "message", e.getMessage()));
        }
    }

    /**
     * Delete/Disable a customer account from AutoSpotting
     */
    @DeleteMapping("/accounts/{accountId}")
    public ResponseEntity<Map<String, Object>> deleteCustomerAccount(@PathVariable String accountId) {
        log.info("🗑️ Deleting customer account: {}", accountId);

        try {
            // ✅ Use Host Client for Delete
            DynamoDbClient client = getHostDynamoDbClient();

            DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
                    .tableName(DYNAMODB_TABLE_NAME)
                    .key(Map.of("AccountId", AttributeValue.builder().s(accountId).build()))
                    .build();

            client.deleteItem(deleteRequest);

            log.info("✅ Account {} deleted successfully", accountId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Account deleted successfully",
                    "accountId", accountId));

        } catch (Exception e) {
            log.error("❌ Error deleting account", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to delete account",
                    "message", e.getMessage()));
        }
    }

    /**
     * Generate CloudFormation Quick Create URL
     */
    @GetMapping("/deploy-url")
    public ResponseEntity<Map<String, Object>> getDeployUrl(
            @RequestParam(required = false) String regions,
            @RequestParam(defaultValue = "us-east-1") String deployRegion,
            @RequestParam(required = false) String accountId) {

        log.info("🚀 Generating CloudFormation deploy URL for region: {}", deployRegion);

        try {
            // Bypass strict health check if needed, but we keep the logic here for logging
            // isMainStackHealthy();

            String targetRegions = getRegionsExcludingDeploymentRegion(regions, deployRegion);
            log.info("📍 StackSet regions (excluding deploy region): {}", targetRegions);

            String cfUrl = buildCloudFormationQuickCreateUrl(
                    CUSTOMER_STACK_NAME,
                    TEMPLATE_S3_URL,
                    MAIN_ACCOUNT_ID,
                    MAIN_REGION,
                    LAMBDA_EXECUTION_ROLE_ARN,
                    targetRegions,
                    deployRegion);

            Map<String, Object> response = new HashMap<>();
            response.put("url", cfUrl);
            response.put("stackName", CUSTOMER_STACK_NAME);
            response.put("deployRegion", deployRegion);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Error generating deploy URL", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to generate deploy URL",
                    "message", e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        boolean healthy = isMainStackHealthy();
        return ResponseEntity.ok(Map.of("status", healthy ? "healthy" : "unhealthy", "mainStack", MAIN_STACK_NAME));
    }

    @GetMapping("/main-stack-info")
    public ResponseEntity<Map<String, Object>> getMainStackInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("stackName", MAIN_STACK_NAME);
        info.put("dynamoDbTable", DYNAMODB_TABLE_NAME);
        info.put("lambdaRoleArn", LAMBDA_EXECUTION_ROLE_ARN);
        info.put("mainAccountId", MAIN_ACCOUNT_ID);
        info.put("mainRegion", MAIN_REGION);
        info.put("healthy", isMainStackHealthy());
        info.put("templateUrl", TEMPLATE_S3_URL);
        info.put("defaultRegions", DEFAULT_REGIONS);
        return ResponseEntity.ok(info);
    }

    // ================= INTERNAL METHODS =================

    private void updateDynamoDb(String accountId, String name, String regions, String roleArn) {
        log.info("Updating DynamoDB table '{}' for account {}", DYNAMODB_TABLE_NAME, accountId);

        // ✅ Use Host Client for PutItem
        DynamoDbClient client = getHostDynamoDbClient();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("AccountId", AttributeValue.builder().s(accountId).build());
        item.put("AccountName", AttributeValue.builder().s(name != null ? name : "Customer-" + accountId).build());
        item.put("RoleArn", AttributeValue.builder().s(roleArn).build());
        item.put("ExternalId", AttributeValue.builder().s(accountId).build());
        item.put("Regions",
                AttributeValue.builder().s(regions != null && !regions.isEmpty() ? regions : DEFAULT_REGIONS).build());
        item.put("State", AttributeValue.builder().s("enabled").build());
        item.put("LastUpdated", AttributeValue.builder().s(Instant.now().toString()).build());

        client.putItem(PutItemRequest.builder()
                .tableName(DYNAMODB_TABLE_NAME)
                .item(item)
                .build());
    }

    private boolean isMainStackHealthy() {
        try {
            CloudFormationClient clientToCheck = getHostCloudFormationClient();
            DescribeStacksResponse response = clientToCheck.describeStacks(DescribeStacksRequest.builder()
                    .stackName(MAIN_STACK_NAME)
                    .build());

            if (response.stacks().isEmpty())
                return false;

            String status = response.stacks().get(0).stackStatusAsString();
            // Allow CREATE_COMPLETE and UPDATE_COMPLETE
            return "CREATE_COMPLETE".equals(status) || "UPDATE_COMPLETE".equals(status);

        } catch (Exception e) {
            log.error("Error checking main stack health", e);
            return false;
        }
    }

    private String getRegionsExcludingDeploymentRegion(String regions, String deployRegion) {
        String regionsToProcess = (regions == null || regions.trim().isEmpty()) ? DEFAULT_REGIONS : regions;
        return java.util.Arrays.stream(regionsToProcess.split(","))
                .map(String::trim)
                .filter(region -> !region.isEmpty() && !region.equals(deployRegion))
                .collect(Collectors.joining(","));
    }

    private String buildCloudFormationQuickCreateUrl(
            String stackName, String templateUrl, String mainAccountId, String mainRegion,
            String lambdaRoleArn, String regions, String deployRegion) throws UnsupportedEncodingException {

        StringBuilder url = new StringBuilder();
        url.append("https://console.aws.amazon.com/cloudformation/home");
        url.append("?region=").append(deployRegion);
        url.append("#/stacks/quickcreate");
        url.append("?stackName=").append(URLEncoder.encode(stackName, StandardCharsets.UTF_8.toString()));
        url.append("&templateURL=").append(URLEncoder.encode(templateUrl, StandardCharsets.UTF_8.toString()));
        url.append("&param_MainAccountId=").append(mainAccountId);
        url.append("&param_MainRegion=").append(mainRegion);
        url.append("&param_AutoSpottingLambdaRoleArn=")
                .append(URLEncoder.encode(lambdaRoleArn, StandardCharsets.UTF_8.toString()));
        url.append("&param_Regions=").append(URLEncoder.encode(regions, StandardCharsets.UTF_8.toString()));
        url.append("&param_DeployRegionalStackSet=true");
        return url.toString();
    }
}