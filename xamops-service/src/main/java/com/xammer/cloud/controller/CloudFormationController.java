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

    // Host Role for checking Main Engine Status (Same as used for DynamoDB access)
    private static final String HOST_ROLE_ARN = "arn:aws:iam::982534352845:role/XamOps-AutoSpotting-HostRole";

    // ✅ Updated Lambda Role ARN with correct suffix
    private static final String LAMBDA_EXECUTION_ROLE_ARN = "arn:aws:iam::982534352845:role/lambda/autospotting-LambdaExecutionRole-QLEgqMN3g4f1";

    // ✅ Table Name matching your Main Stack
    private static final String DYNAMODB_TABLE_NAME = "autospotting-CustomerAccounts";

    // Template S3 URL - Updated template location
    private static final String TEMPLATE_S3_URL = "https://xamops-cloudformation-templates.s3.amazonaws.com/autospotting-customer-account.yaml";

    // The stack name used in the customer's account
    private static final String CUSTOMER_STACK_NAME = "XamOps-AutoSpotting-Integration";

    // ✅ UPDATED: Default regions list (16 standard regions, excluding us-east-1 by
    // default)
    // us-east-1 is typically the deployment region, so excluded from StackSet
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

    /**
     * ✅ UPDATED: Sync customer account to DynamoDB with corrected role ARN
     * This enables AutoSpotting to discover and manage ASGs in the customer account
     */
    @PostMapping("/sync-account")
    public ResponseEntity<Map<String, Object>> syncCustomerAccount(@RequestBody Map<String, String> request) {
        log.info("📋 Syncing customer account request received");

        try {
            String customerAccountId = request.get("accountId");
            String accountName = request.get("accountName");
            String regions = request.get("regions");

            // Validation
            if (customerAccountId == null || customerAccountId.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Account ID is required",
                        "message", "Please provide a valid AWS account ID"));
            }

            // Validate account ID format (exactly 12 digits, preserve leading zeros)
            if (!customerAccountId.matches("^\\d{12}$")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid account ID format",
                        "message", "AWS account ID must be exactly 12 digits"));
            }

            // ✅ Construct the correct role ARN for this customer account
            String customerRoleArn = String.format(
                    "arn:aws:iam::%s:role/AutoSpotting-Execution-Role",
                    customerAccountId);

            log.info("💾 Syncing account {} with role ARN: {}", customerAccountId, customerRoleArn);

            // ✅ Update DynamoDB (This enables AutoSpotting discovery)
            updateDynamoDb(customerAccountId, accountName, regions, customerRoleArn);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Account synced successfully to AutoSpotting Engine");
            response.put("accountId", customerAccountId);
            response.put("accountName", accountName != null ? accountName : "Customer-" + customerAccountId);
            response.put("roleArn", customerRoleArn);
            response.put("externalId", customerAccountId); // AWS Account ID is used as External ID

            return ResponseEntity.ok(response);

        } catch (DynamoDbException e) {
            log.error("❌ DynamoDB error syncing account", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to sync account to DynamoDB",
                    "message", e.awsErrorDetails().errorMessage()));
        } catch (Exception e) {
            log.error("❌ Error syncing account", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to sync account",
                    "message", e.getMessage()));
        }
    }

    /**
     * ✅ UPDATED: Store customer account in DynamoDB with role ARN and external ID
     */
    private void updateDynamoDb(String accountId, String name, String regions, String roleArn) {
        log.info("Updating DynamoDB table '{}' for account {}", DYNAMODB_TABLE_NAME, accountId);

        Map<String, AttributeValue> item = new HashMap<>();

        // Store as String to preserve leading zeros
        item.put("AccountId", AttributeValue.builder().s(accountId).build());
        item.put("AccountName", AttributeValue.builder()
                .s(name != null ? name : "Customer-" + accountId).build());

        // Store the role ARN that AutoSpotting will assume
        item.put("RoleArn", AttributeValue.builder().s(roleArn).build());

        // External ID for secure role assumption (using account ID as external ID)
        item.put("ExternalId", AttributeValue.builder().s(accountId).build());

        // Regions configuration
        item.put("Regions", AttributeValue.builder()
                .s(regions != null && !regions.trim().isEmpty() ? regions : DEFAULT_REGIONS).build());

        // Account state
        item.put("State", AttributeValue.builder().s("enabled").build());

        // Timestamp
        item.put("LastUpdated", AttributeValue.builder().s(Instant.now().toString()).build());

        PutItemRequest putItemRequest = PutItemRequest.builder()
                .tableName(DYNAMODB_TABLE_NAME)
                .item(item)
                .build();

        dynamoDbClient.putItem(putItemRequest);
        log.info("✅ DynamoDB updated successfully for account {}", accountId);
    }

    /**
     * ✅ Get all customer accounts from DynamoDB
     */
    @GetMapping("/accounts")
    public ResponseEntity<Map<String, Object>> listCustomerAccounts() {
        log.info("📋 Fetching all customer accounts from DynamoDB");
        try {
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(DYNAMODB_TABLE_NAME)
                    .build();

            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

            Map<String, Object> response = new HashMap<>();
            response.put("accounts", scanResponse.items().stream()
                    .map(item -> {
                        Map<String, Object> account = new HashMap<>();
                        account.put("accountId",
                                item.getOrDefault("AccountId", AttributeValue.builder().s("").build()).s());
                        account.put("accountName",
                                item.getOrDefault("AccountName", AttributeValue.builder().s("").build()).s());
                        account.put("roleArn",
                                item.getOrDefault("RoleArn", AttributeValue.builder().s("").build()).s());
                        account.put("regions",
                                item.getOrDefault("Regions", AttributeValue.builder().s("").build()).s());
                        account.put("state",
                                item.getOrDefault("State", AttributeValue.builder().s("").build()).s());
                        account.put("lastUpdated",
                                item.getOrDefault("LastUpdated", AttributeValue.builder().s("").build()).s());
                        return account;
                    })
                    .collect(Collectors.toList()));

            response.put("count", scanResponse.count());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Error fetching accounts", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to fetch accounts", "message", e.getMessage()));
        }
    }

    /**
     * ✅ UPDATED: Generate CloudFormation Quick Create URL for "Deploy to AWS"
     * button
     * This automatically excludes the deployment region from the StackSet regions
     * list
     * to prevent duplicate EventBridge rules
     */
    @GetMapping("/deploy-url")
    public ResponseEntity<Map<String, Object>> getDeployUrl(
            @RequestParam(required = false) String regions,
            @RequestParam(defaultValue = "us-east-1") String deployRegion,
            @RequestParam(required = false) String accountId) {

        log.info("🚀 Generating CloudFormation deploy URL for region: {}", deployRegion);

        try {
            // ✅ BYPASS HEALTH CHECK: Removed !isMainStackHealthy() check.
            // This allows the URL to be generated even if the backend cannot verify the
            // engine status.

            // ✅ FIX: Exclude deployment region from StackSet regions
            // This prevents creating duplicate EventBridge rules in the deployment region
            String targetRegions = getRegionsExcludingDeploymentRegion(regions, deployRegion);

            log.info("📍 Deploy region: {}", deployRegion);
            log.info("📍 StackSet regions (excluding deploy region): {}", targetRegions);

            // Build the AWS Console Quick Create URL
            String cfUrl = buildCloudFormationQuickCreateUrl(
                    CUSTOMER_STACK_NAME,
                    TEMPLATE_S3_URL,
                    MAIN_ACCOUNT_ID,
                    MAIN_REGION,
                    LAMBDA_EXECUTION_ROLE_ARN,
                    targetRegions, // ✅ Use filtered regions
                    deployRegion);

            Map<String, Object> response = new HashMap<>();
            response.put("url", cfUrl);
            response.put("stackName", CUSTOMER_STACK_NAME);
            response.put("deployRegion", deployRegion);
            response.put("stackSetRegions", targetRegions);
            response.put("templateUrl", TEMPLATE_S3_URL);
            response.put("method", "quick-create-url");
            response.put("description", "Click this URL to deploy the stack in AWS Console");
            response.put("note", "Deployment region is excluded from StackSet to prevent duplicate resources");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Error generating deploy URL", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to generate deploy URL",
                    "message", e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Get regions list excluding the deployment region
     * This prevents duplicate EventBridge rules in the deployment region
     * The main stack creates rules in the deployment region,
     * StackSet creates rules in all other regions
     */
    private String getRegionsExcludingDeploymentRegion(String regions, String deployRegion) {
        // Use default regions if not provided
        String regionsToProcess = (regions == null || regions.trim().isEmpty())
                ? DEFAULT_REGIONS
                : regions;

        // Split, filter out deployment region, rejoin
        String filteredRegions = java.util.Arrays.stream(regionsToProcess.split(","))
                .map(String::trim)
                .filter(region -> !region.isEmpty())
                .filter(region -> !region.equals(deployRegion))
                .collect(Collectors.joining(","));

        log.info("🔧 Original regions count: {}", regionsToProcess.split(",").length);
        log.info("🔧 Filtered regions count: {}", filteredRegions.isEmpty() ? 0 : filteredRegions.split(",").length);
        log.info("🔧 Excluded deployment region: {}", deployRegion);

        return filteredRegions;
    }

    /**
     * ✅ Build AWS Console Quick Create URL with pre-filled parameters
     * This is the BEST approach for user-friendly deployment
     */
    private String buildCloudFormationQuickCreateUrl(
            String stackName, String templateUrl, String mainAccountId, String mainRegion,
            String lambdaRoleArn, String regions, String deployRegion) throws UnsupportedEncodingException {

        StringBuilder url = new StringBuilder();

        // Base AWS Console CloudFormation URL
        url.append("https://console.aws.amazon.com/cloudformation/home");
        url.append("?region=").append(deployRegion);
        url.append("#/stacks/quickcreate");

        // Stack name
        url.append("?stackName=").append(URLEncoder.encode(stackName, StandardCharsets.UTF_8.toString()));

        // Template URL
        url.append("&templateURL=").append(URLEncoder.encode(templateUrl, StandardCharsets.UTF_8.toString()));

        // Parameters (pre-filled)
        url.append("&param_MainAccountId=").append(mainAccountId);
        url.append("&param_MainRegion=").append(mainRegion);
        url.append("&param_AutoSpottingLambdaRoleArn=")
                .append(URLEncoder.encode(lambdaRoleArn, StandardCharsets.UTF_8.toString()));
        url.append("&param_Regions=").append(URLEncoder.encode(regions, StandardCharsets.UTF_8.toString()));
        url.append("&param_DeployRegionalStackSet=true");

        return url.toString();
    }

    /**
     * ✅ Helper: Create a CloudFormation client that assumes the Host Role
     * This allows the backend (in a different account) to check the stack status in
     * the Main Account (9825...)
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
            return this.cloudFormationClient; // Fallback
        }
    }

    /**
     * ✅ Check if the main AutoSpotting stack is healthy
     */
    private boolean isMainStackHealthy() {
        try {
            // Use the cross-account client
            CloudFormationClient clientToCheck = getHostCloudFormationClient();

            DescribeStacksResponse response = clientToCheck
                    .describeStacks(DescribeStacksRequest.builder()
                            .stackName(MAIN_STACK_NAME)
                            .build());

            if (response.stacks().isEmpty()) {
                log.warn("Main stack '{}' not found in account {}", MAIN_STACK_NAME, MAIN_ACCOUNT_ID);
                return false;
            }

            String status = response.stacks().get(0).stackStatusAsString();
            log.info("Main stack '{}' status: {}", MAIN_STACK_NAME, status);

            // ✅ ALLOW BOTH CREATE_COMPLETE AND UPDATE_COMPLETE
            return "CREATE_COMPLETE".equals(status) || "UPDATE_COMPLETE".equals(status);

        } catch (CloudFormationException e) {
            log.error("Main stack '{}' not found or inaccessible in {}", MAIN_STACK_NAME, MAIN_REGION);
            return false;
        } catch (Exception e) {
            log.error("Error checking main stack health", e);
            return false;
        }
    }

    /**
     * ✅ Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        boolean mainStackHealthy = isMainStackHealthy();

        Map<String, Object> health = new HashMap<>();
        health.put("status", mainStackHealthy ? "healthy" : "unhealthy");
        health.put("mainStack", MAIN_STACK_NAME);
        health.put("mainStackHealthy", mainStackHealthy);
        health.put("dynamoDbTable", DYNAMODB_TABLE_NAME);
        health.put("lambdaRoleArn", LAMBDA_EXECUTION_ROLE_ARN);
        health.put("mainAccountId", MAIN_ACCOUNT_ID);
        health.put("mainRegion", MAIN_REGION);

        return ResponseEntity.ok(health);
    }

    /**
     * ✅ Get main stack information
     */
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

    /**
     * ✅ Delete/Disable a customer account from AutoSpotting
     */
    @DeleteMapping("/accounts/{accountId}")
    public ResponseEntity<Map<String, Object>> deleteCustomerAccount(@PathVariable String accountId) {
        log.info("🗑️ Deleting customer account: {}", accountId);

        try {
            // Validate account ID format
            if (!accountId.matches("^\\d{12}$")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid account ID format",
                        "message", "AWS account ID must be exactly 12 digits"));
            }

            // Delete from DynamoDB
            DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
                    .tableName(DYNAMODB_TABLE_NAME)
                    .key(Map.of("AccountId", AttributeValue.builder().s(accountId).build()))
                    .build();

            dynamoDbClient.deleteItem(deleteRequest);

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
}