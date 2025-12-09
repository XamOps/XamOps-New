package com.xammer.cloud.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/cloudformation")
@CrossOrigin(origins = "*")
public class CloudFormationController {

    // XamOps/AutoSpotting Main Account Configuration
    private static final String MAIN_ACCOUNT_ID = "982534352845";
    private static final String MAIN_REGION = "ap-south-1";
    private static final String LAMBDA_ROLE_ARN = "arn:aws:iam::982534352845:role/lambda/autospotting-LambdaExecutionRole-Ore56PzHeFO7";

    // ✅ FIXED: Use region-agnostic S3 URL format
    private static final String TEMPLATE_S3_URL = "https://xamops-cloudformation-templates.s3.amazonaws.com/autospotting-customer-account.yaml";

    /**
     * Generates AWS CloudFormation Quick Create URL with pre-filled parameters
     * 
     * @param regions      Comma-separated list of AWS regions to deploy event
     *                     forwarding
     * @param deployRegion Region where customer will deploy the stack
     * @return Map containing CloudFormation console URL
     */
    @GetMapping("/deploy-url")
    public ResponseEntity<Map<String, String>> getDeployUrl(
            @RequestParam(required = false) String regions,
            @RequestParam(defaultValue = "us-east-1") String deployRegion) {

        log.info("🚀 Generating CloudFormation deploy URL for regions: {}", regions);
        log.info("📍 Deploy region: {}", deployRegion);

        try {
            String stackName = "XamOps-AutoSpotting-Integration";

            String cfUrl = buildCloudFormationQuickCreateUrl(
                    stackName,
                    TEMPLATE_S3_URL,
                    MAIN_ACCOUNT_ID,
                    MAIN_REGION,
                    LAMBDA_ROLE_ARN,
                    regions,
                    deployRegion);

            Map<String, String> response = new HashMap<>();
            response.put("url", cfUrl);
            response.put("templateUrl", TEMPLATE_S3_URL);
            response.put("stackName", stackName);
            response.put("deployRegion", deployRegion);
            response.put("mainAccountId", MAIN_ACCOUNT_ID);

            log.info("✅ CloudFormation URL generated successfully");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Error generating deploy URL", e);

            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate deploy URL");
            errorResponse.put("message", e.getMessage());

            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Builds the AWS CloudFormation Quick Create console URL
     */
    private String buildCloudFormationQuickCreateUrl(
            String stackName,
            String templateUrl,
            String mainAccountId,
            String mainRegion,
            String lambdaRoleArn,
            String regions,
            String deployRegion) throws UnsupportedEncodingException {

        StringBuilder url = new StringBuilder();

        // Base CloudFormation console URL
        url.append("https://console.aws.amazon.com/cloudformation/home");
        url.append("?region=").append(deployRegion);
        url.append("#/stacks/quickcreate");

        // Stack name
        url.append("?stackName=").append(URLEncoder.encode(stackName, "UTF-8"));

        // Template URL (S3 location)
        url.append("&templateURL=").append(URLEncoder.encode(templateUrl, "UTF-8"));

        // Parameters (pre-filled)
        url.append("&param_MainAccountId=").append(mainAccountId);
        url.append("&param_MainRegion=").append(mainRegion);
        url.append("&param_AutoSpottingLambdaRoleArn=").append(URLEncoder.encode(lambdaRoleArn, "UTF-8"));

        // Only include Regions parameter if provided, otherwise CloudFormation will use
        // template default
        if (regions != null && !regions.trim().isEmpty()) {
            url.append("&param_Regions=").append(URLEncoder.encode(regions, "UTF-8"));
        }

        url.append("&param_DeployRegionalStackSet=").append("true");

        return url.toString();
    }

    /**
     * Health check endpoint to verify CloudFormation integration
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "ok");
        health.put("mainAccountId", MAIN_ACCOUNT_ID);
        health.put("mainRegion", MAIN_REGION);
        health.put("lambdaRoleArn", LAMBDA_ROLE_ARN);
        health.put("templateUrl", TEMPLATE_S3_URL);
        health.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(health);
    }
}
