package com.xammer.cloud.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

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

    // XamOps/AutoSpotting Main Account Configuration
    private static final String MAIN_ACCOUNT_ID = "982534352845";
    private static final String MAIN_REGION = "ap-south-1";
    private static final String MAIN_STACK_NAME = "autospotting";

    // ✅ Table Name matching your Main Stack
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
            "us-east-1,us-east-2,us-west-1,us-west-2";

    public CloudFormationController(
            CloudFormationClient cloudFormationClient,
            DynamoDbClient dynamoDbClient) {
        this.cloudFormationClient = cloudFormationClient;
        this.dynamoDbClient = dynamoDbClient;
    }

    /**
     * ✅ UPDATED: Sync customer account ONLY to DynamoDB (SQL is handled elsewhere)
     */
    @PostMapping("/sync-account")
    public ResponseEntity<Map<String, Object>> syncCustomerAccount(@RequestBody Map<String, String> request) {
        log.info("📋 Syncing customer account request received");

        try {
            String customerAccountId = request.get("accountId");
            String accountName = request.get("accountName");
            String regions = request.get("regions");

            if (customerAccountId == null || customerAccountId.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Account ID is required",
                        "message", "Please provide a valid AWS account ID"));
            }

            // Validate account ID format
            if (!customerAccountId.matches("^[0-9]{12}$")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid account ID format",
                        "message", "AWS account ID must be exactly 12 digits"));
            }

            // ✅ Update DynamoDB (This enables AutoSpotting)
            updateDynamoDb(customerAccountId, accountName, regions);

            // Removed updateSqlRepository() as requested.

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Account synced successfully to AutoSpotting Engine");
            response.put("accountId", customerAccountId);
            response.put("accountName", accountName != null ? accountName : "Customer-" + customerAccountId);

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

    private void updateDynamoDb(String accountId, String name, String regions) {
        log.info("Updating DynamoDB table '{}' for account {}", DYNAMODB_TABLE_NAME, accountId);

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("AccountId", AttributeValue.builder().s(accountId).build());
        item.put("AccountName", AttributeValue.builder().s(name != null ? name : "Customer-" + accountId).build());
        item.put("Regions", AttributeValue.builder().s(regions != null ? regions : "all").build());
        item.put("State", AttributeValue.builder().s("enabled").build());
        item.put("LastUpdated", AttributeValue.builder().s(Instant.now().toString()).build());

        PutItemRequest putItemRequest = PutItemRequest.builder()
                .tableName(DYNAMODB_TABLE_NAME)
                .item(item)
                .build();

        dynamoDbClient.putItem(putItemRequest);
        log.info("✅ DynamoDB updated successfully");
    }

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
                        account.put("regions",
                                item.getOrDefault("Regions", AttributeValue.builder().s("").build()).s());
                        account.put("state", item.getOrDefault("State", AttributeValue.builder().s("").build()).s());
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

    @GetMapping("/deploy-url")
    public ResponseEntity<Map<String, Object>> getDeployUrl(
            @RequestParam(required = false) String regions,
            @RequestParam(defaultValue = "us-east-1") String deployRegion) {

        log.info("🚀 Generating CloudFormation deploy URL");

        try {
            if (!isMainStackHealthy()) {
                return ResponseEntity.status(503).body(Map.of("error", "Main AutoSpotting stack is not healthy"));
            }

            String lambdaRoleArn = getLambdaExecutionRoleArn();

            if (regions == null || regions.trim().isEmpty()) {
                regions = DEFAULT_REGIONS;
            }

            String cfUrl = buildCloudFormationQuickCreateUrl(
                    CUSTOMER_STACK_NAME,
                    TEMPLATE_S3_URL,
                    MAIN_ACCOUNT_ID,
                    MAIN_REGION,
                    lambdaRoleArn,
                    regions,
                    deployRegion);

            Map<String, Object> response = new HashMap<>();
            response.put("url", cfUrl);
            response.put("stackName", CUSTOMER_STACK_NAME);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Error generating deploy URL", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String buildCloudFormationQuickCreateUrl(
            String stackName, String templateUrl, String mainAccountId, String mainRegion,
            String lambdaRoleArn, String regions, String deployRegion) throws UnsupportedEncodingException {

        StringBuilder url = new StringBuilder();
        url.append("https://console.aws.amazon.com/cloudformation/home?region=").append(deployRegion);
        url.append("#/stacks/quickcreate");
        url.append("?stackName=").append(URLEncoder.encode(stackName, StandardCharsets.UTF_8.toString()));
        url.append("&templateURL=").append(URLEncoder.encode(templateUrl, StandardCharsets.UTF_8.toString()));
        url.append("&param_MainAccountId=").append(mainAccountId);
        url.append("&param_MainRegion=").append(mainRegion);
        url.append("&param_AutoSpottingLambdaRoleArn=")
                .append(URLEncoder.encode(lambdaRoleArn, StandardCharsets.UTF_8.toString()));

        if (regions != null && !regions.trim().isEmpty()) {
            url.append("&param_Regions=").append(URLEncoder.encode(regions, StandardCharsets.UTF_8.toString()));
        }
        url.append("&param_DeployRegionalStackSet=true");

        return url.toString();
    }

    private String getLambdaExecutionRoleArn() {
        try {
            DescribeStacksResponse response = cloudFormationClient
                    .describeStacks(DescribeStacksRequest.builder().stackName(MAIN_STACK_NAME).build());
            if (response.stacks().isEmpty())
                return "arn:aws:iam::" + MAIN_ACCOUNT_ID + ":role/lambda/autospotting-LambdaExecutionRole";

            return response.stacks().get(0).outputs().stream()
                    .filter(output -> "LambdaExecutionRoleARN".equals(output.outputKey()))
                    .findFirst()
                    .map(Output::outputValue)
                    .orElse("arn:aws:iam::" + MAIN_ACCOUNT_ID + ":role/lambda/autospotting-LambdaExecutionRole");
        } catch (Exception e) {
            log.warn("Could not fetch Lambda Role ARN, using constructed default");
            return "arn:aws:iam::" + MAIN_ACCOUNT_ID + ":role/lambda/autospotting-LambdaExecutionRole";
        }
    }

    private boolean isMainStackHealthy() {
        try {
            DescribeStacksResponse response = cloudFormationClient
                    .describeStacks(DescribeStacksRequest.builder().stackName(MAIN_STACK_NAME).build());
            return !response.stacks().isEmpty() &&
                    response.stacks().get(0).stackStatusAsString().contains("COMPLETE");
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "mainStack", MAIN_STACK_NAME,
                "dynamoDbTable", DYNAMODB_TABLE_NAME));
    }

    @GetMapping("/main-stack-info")
    public ResponseEntity<Map<String, Object>> getMainStackInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("stackName", MAIN_STACK_NAME);
        info.put("dynamoDbTable", DYNAMODB_TABLE_NAME);
        info.put("healthy", isMainStackHealthy());
        return ResponseEntity.ok(info);
    }
}