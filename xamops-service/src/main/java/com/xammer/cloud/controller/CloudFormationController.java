package com.xammer.cloud.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CloudFormation quick-create links for customer accounts, plus a health probe for the
 * AutoSpotting host stack.
 *
 * <p>The legacy DynamoDB registry endpoints (account sync, account list and delete, and the host
 * stack info) were removed. They had no tenant or role checks: any signed-in user could list every
 * tenant's AWS account ID, name and role ARN, add or delete the registry entry of any account ID,
 * and read the host stack's details. Nothing in the product called them.
 */
@Slf4j
@RestController
@RequestMapping("/api/cloudformation")
@CrossOrigin(origins = "*")
public class CloudFormationController {

    private final CloudFormationClient cloudFormationClient;
    private final StsClient stsClient;

    /**
     * Hosted location of xamops-cur-report.yml, per environment.
     *
     * <p>Configured rather than a constant like the AutoSpotting template above,
     * because each environment points at its own copy in the template bucket —
     * the same arrangement as cloudformation.template.s3.url for the role stacks.
     * Empty means the option is not offered rather than producing a broken link.
     */
    @org.springframework.beans.factory.annotation.Value("${cloudformation.template.s3.cur.url:}")
    private String curTemplateUrl;

    // XamOps/AutoSpotting Main Account Configuration
    private static final String MAIN_ACCOUNT_ID = "982534352845";
    private static final String MAIN_REGION = "ap-south-1";
    private static final String MAIN_STACK_NAME = "autospotting";

    // Host Role for checking Main Engine Status
    private static final String HOST_ROLE_ARN = "arn:aws:iam::982534352845:role/XamOps-AutoSpotting-HostRole";

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
            StsClient stsClient) {
        this.cloudFormationClient = cloudFormationClient;
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

    // ================= API ENDPOINTS =================

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
            String cfUrl = buildCloudFormationQuickCreateUrl(
                    CUSTOMER_STACK_NAME,
                    TEMPLATE_S3_URL,
                    MAIN_ACCOUNT_ID,
                    MAIN_REGION,
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

    /**
     * Quick-create URL for the Cost and Usage Report stack.
     *
     * <p>Separate from {@code /deploy-url} because this stack cannot be deployed
     * anywhere else: {@code AWS::CUR::ReportDefinition} is only supported in
     * us-east-1, so the region is pinned rather than taken from a parameter. The
     * role-stack URL builders deliberately leave the region open, and reusing one
     * here would drop the customer into whichever region their console last used,
     * where stack creation fails with an unhelpful error.
     *
     * <p>Only needed for accounts that keep their own report. An account under a
     * XamOps payer account is already covered by that payer's report and deploys
     * nothing.
     */
    @GetMapping("/cur-deploy-url")
    public ResponseEntity<Map<String, Object>> getCurDeployUrl(
            @RequestParam(required = false) String bucketName) {

        if (curTemplateUrl == null || curTemplateUrl.isBlank()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "No CUR template is configured",
                    "message", "Set cloudformation.template.s3.cur.url to the hosted "
                            + "xamops-cur-report.yml before offering this option."));
        }

        try {
            StringBuilder url = new StringBuilder();
            url.append("https://us-east-1.console.aws.amazon.com/cloudformation/home");
            url.append("?region=us-east-1");
            url.append("#/stacks/create/review");
            url.append("?templateURL=")
               .append(URLEncoder.encode(curTemplateUrl, StandardCharsets.UTF_8));
            url.append("&stackName=XamOps-Cost-Reporting");
            if (bucketName != null && !bucketName.isBlank()) {
                url.append("&param_BucketName=")
                   .append(URLEncoder.encode(bucketName, StandardCharsets.UTF_8));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("url", url.toString());
            response.put("stackName", "XamOps-Cost-Reporting");
            response.put("deployRegion", "us-east-1");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Error generating CUR deploy URL", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to generate CUR deploy URL",
                    "message", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        boolean healthy = isMainStackHealthy();
        return ResponseEntity.ok(Map.of("status", healthy ? "healthy" : "unhealthy", "mainStack", MAIN_STACK_NAME));
    }

    // ================= INTERNAL METHODS =================

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
            String deployRegion) throws UnsupportedEncodingException {

        String sqsQueueUrl = "https://sqs." + mainRegion + ".amazonaws.com/" + mainAccountId + "/autospotting-events.fifo";

        StringBuilder url = new StringBuilder();
        url.append("https://console.aws.amazon.com/cloudformation/home");
        url.append("?region=").append(deployRegion);
        url.append("#/stacks/quickcreate");
        url.append("?stackName=").append(URLEncoder.encode(stackName, StandardCharsets.UTF_8.toString()));
        url.append("&templateURL=").append(URLEncoder.encode(templateUrl, StandardCharsets.UTF_8.toString()));
        url.append("&param_XammerAccountId=").append(mainAccountId);
        url.append("&param_XammerSQSQueueUrl=").append(URLEncoder.encode(sqsQueueUrl, StandardCharsets.UTF_8.toString()));
        url.append("&param_XammerRegion=").append(mainRegion);
        url.append("&param_EnableAutoSpotting=true");
        return url.toString();
    }
}