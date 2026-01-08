package com.xammer.cloud.controller;

import com.xammer.cloud.dto.cloudidr.*;
import com.xammer.cloud.service.CloudIdrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/xamops/cloudidr")
@CrossOrigin(origins = "*")
public class CloudIdrController {

    @Autowired
    private CloudIdrService cloudIdrService;

    /**
     * Search for available compute instances
     * POST /api/xamops/cloudidr/search
     */
    @PostMapping("/search")
    public ResponseEntity<?> searchInstances(@Valid @RequestBody CloudIdrSearchRequest request) {
        try {
            log.info("📍 Search request - Account: {}, Instance: {}, Region: {}",
                    request.getAccountId(),
                    request.getInstanceType(),
                    request.getRegion());

            SearchResponse result = cloudIdrService.searchCompute(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ CloudIDR search failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Search failed: " + e.getMessage()));
        }
    }

    /**
     * Reserve capacity
     * POST /api/xamops/cloudidr/reserve
     */
    @PostMapping("/reserve")
    public ResponseEntity<?> reserveCapacity(@Valid @RequestBody CloudIdrReserveRequest request) {
        try {
            log.info("🚀 Reserve request - Policy: {}, Instance: {}, Region: {}, Qty: {}",
                    request.getPolicyName(),
                    request.getInstanceType(),
                    request.getRegion(),
                    request.getQuantity());

            ReserveResponse result = cloudIdrService.reserveCapacity(request);

            log.info("✅ Reservation successful - CIDR ID: {}", result.getCidrId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ CloudIDR reservation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Reservation failed: " + e.getMessage()));
        }
    }

    /**
     * Purchase capacity
     * POST /api/xamops/cloudidr/purchase
     */
    @PostMapping("/purchase")
    public ResponseEntity<?> purchaseCapacity(@RequestBody Map<String, String> payload) {
        try {
            String cidrId = payload.get("cidrId");

            if (cidrId == null || cidrId.trim().isEmpty()) {
                log.warn("⚠️ Purchase request missing cidrId");
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Missing or empty cidrId"));
            }

            log.info("💳 Purchase request for CIDR ID: {}", cidrId);
            PurchaseResponse result = cloudIdrService.purchaseCapacity(cidrId);

            log.info("✅ Purchase successful for CIDR ID: {}", cidrId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ CloudIDR purchase failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Purchase failed: " + e.getMessage()));
        }
    }

    /**
     * Get policy details
     * GET /api/xamops/cloudidr/policy/{cidrId}
     */
    @GetMapping("/policy/{cidrId}")
    public ResponseEntity<?> getPolicyDetails(@PathVariable String cidrId) {
        try {
            if (cidrId == null || cidrId.trim().isEmpty()) {
                log.warn("⚠️ Policy details request with invalid cidrId");
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Invalid cidrId"));
            }

            log.info("📋 Policy details request for CIDR ID: {}", cidrId);
            PolicyDetails result = cloudIdrService.getSchedulePolicy(cidrId);

            if (result == null) {
                log.warn("⚠️ Policy not found: {}", cidrId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(createErrorResponse("Policy not found: " + cidrId));
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ Failed to fetch policy details for: {}", cidrId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch policy: " + e.getMessage()));
        }
    }

    /**
     * Cancel policy
     * POST /api/xamops/cloudidr/cancel
     */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancelPolicy(@RequestBody Map<String, String> payload) {
        try {
            String cidrId = payload.get("cidrId");

            if (cidrId == null || cidrId.trim().isEmpty()) {
                log.warn("⚠️ Cancel request missing cidrId");
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Missing or empty cidrId"));
            }

            log.info("🚫 Cancel request for CIDR ID: {}", cidrId);
            CancelResponse result = cloudIdrService.cancelPolicy(cidrId);

            log.info("✅ Policy cancelled: {}", cidrId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ CloudIDR cancellation failed for: {}", payload.get("cidrId"), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Cancellation failed: " + e.getMessage()));
        }
    }

    /**
     * Get all scheduled capacities
     * GET /api/xamops/cloudidr/scheduled-capacities
     */
    @GetMapping("/scheduled-capacities")
    public ResponseEntity<?> getAllScheduledCapacities() {
        try {
            log.info("📦 Fetching all scheduled capacities");
            List<PolicyDetails> result = cloudIdrService.getAllScheduledCapacities();

            log.info("✅ Retrieved {} scheduled capacities", result.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ Failed to fetch scheduled capacities", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch capacities: " + e.getMessage()));
        }
    }

    /**
     * Get capacity summary
     * GET /api/xamops/cloudidr/summary
     */
    @GetMapping("/summary")
    public ResponseEntity<?> getCapacitySummary() {
        try {
            log.info("📊 Fetching capacity summary");
            Map<String, Object> result = cloudIdrService.getCapacitySummary();

            log.info("✅ Summary retrieved - Total Savings: {}, Total Cost: {}",
                    result.get("totalSavings"),
                    result.get("totalCost"));

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ Failed to calculate summary", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to calculate summary: " + e.getMessage()));
        }
    }

    /**
     * Test CloudIDR connection and authentication
     * GET /api/xamops/cloudidr/test-connection
     */
    @GetMapping("/test-connection")
    public ResponseEntity<?> testConnection() {
        try {
            log.info("🔍 Testing CloudIDR connection");
            Map<String, Object> result = cloudIdrService.testConnection();

            if ("success".equals(result.get("status"))) {
                log.info("✅ CloudIDR connection test successful");
                return ResponseEntity.ok(result);
            } else {
                log.warn("⚠️ CloudIDR connection test failed: {}", result.get("message"));
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
            }
        } catch (Exception e) {
            log.error("❌ CloudIDR connection test error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Connection test failed: " + e.getMessage()));
        }
    }

    /**
     * Health check endpoint
     * GET /api/xamops/cloudidr/health
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("service", "CloudIDR Integration");
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());

        log.debug("💚 Health check OK");
        return ResponseEntity.ok(health);
    }

    /**
     * Clear token cache (admin/testing endpoint)
     * POST /api/xamops/cloudidr/admin/clear-cache
     */
    @PostMapping("/admin/clear-cache")
    public ResponseEntity<?> clearCache() {
        try {
            log.info("🗑️ Clearing CloudIDR token cache");
            cloudIdrService.clearTokenCache();

            Map<String, String> response = new HashMap<>();
            response.put("message", "CloudIDR token cache cleared successfully");
            response.put("timestamp", String.valueOf(System.currentTimeMillis()));

            log.info("✅ Token cache cleared");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Failed to clear cache", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to clear cache: " + e.getMessage()));
        }
    }

    /**
     * Helper method to create error response
     */
    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("timestamp", System.currentTimeMillis());
        error.put("status", "error");
        return error;
    }

    /**
     * Link AWS account to CloudIDR
     * POST /api/xamops/cloudidr/link-account
     */
    @PostMapping("/link-account")
    public ResponseEntity<?> linkAwsAccount(@Valid @RequestBody CloudIdrLinkAccountRequest request) {
        try {
            log.info("🔗 Linking AWS account {} to CloudIDR", request.getAwsAccountId());

            // Validate Role ARN format
            if (!request.getRoleArn().matches("arn:aws:iam::\\d{12}:role/CloudidrRole")) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Invalid CloudIDR Role ARN format"));
            }

            CloudIdrLinkAccountResponse result = cloudIdrService.linkAccount(request);

            log.info("✅ Account linked successfully - CSP ID: {}", result.getCspAccountId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ Failed to link account to CloudIDR", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Account linking failed: " + e.getMessage()));
        }
    }

    /**
     * Get CloudFormation template for account linking
     * GET /api/xamops/cloudidr/cloudformation-template
     */
    @GetMapping("/cloudformation-template")
    public ResponseEntity<?> getCloudFormationTemplate() {
        try {
            // Read the template from resources or return as JSON
            String template = cloudIdrService.getCloudFormationTemplate();

            Map<String, Object> response = new HashMap<>();
            response.put("template", template);
            response.put("stackName", "CloudIDR-Integration-Stack");
            response.put("description", "Deploy this stack in your AWS account to enable CloudIDR integration");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Failed to fetch CloudFormation template", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch template: " + e.getMessage()));
        }
    }

    /**
     * Check account linking status
     * GET /api/xamops/cloudidr/account-status/{accountId}
     */
    @GetMapping("/account-status/{accountId}")
    public ResponseEntity<?> getAccountLinkStatus(@PathVariable String accountId) {
        try {
            log.info("📋 Checking CloudIDR link status for account: {}", accountId);
            Map<String, Object> status = cloudIdrService.getAccountLinkStatus(accountId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("❌ Failed to fetch account status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch status: " + e.getMessage()));
        }
    }

    /**
     * Get CloudFormation Quick-Create URL
     * GET /api/xamops/cloudidr/quickcreate-url/{accountId}
     */
    @GetMapping("/quickcreate-url/{accountId}")
    public ResponseEntity<?> getQuickCreateUrl(@PathVariable String accountId) {
        try {
            log.info("🚀 Generating CloudFormation Quick-Create URL for account: {}", accountId);

            String quickCreateUrl = cloudIdrService.generateQuickCreateUrl(accountId);

            Map<String, Object> response = new HashMap<>();
            response.put("accountId", accountId);
            response.put("quickCreateUrl", quickCreateUrl);
            response.put("stackName", "CloudIDR-Integration-Stack");
            response.put("message", "Click the URL to deploy CloudFormation stack in AWS Console");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Failed to generate Quick-Create URL", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to generate URL: " + e.getMessage()));
        }
    }

    /**
     * Deploy CloudFormation Stack programmatically
     * POST /api/xamops/cloudidr/deploy-stack
     */
    @PostMapping("/deploy-stack")
    public ResponseEntity<?> deployCloudFormationStack(@RequestBody Map<String, String> request) {
        try {
            String accountId = request.get("accountId");
            String region = request.getOrDefault("region", "us-east-1");

            log.info("🚀 Deploying CloudFormation stack for account: {} in region: {}", accountId, region);

            Map<String, Object> result = cloudIdrService.deployCloudFormationStack(accountId, region);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ Failed to deploy CloudFormation stack", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Stack deployment failed: " + e.getMessage()));
        }
    }

    /**
     * Check CloudFormation Stack Status
     * GET /api/xamops/cloudidr/stack-status/{accountId}
     */
    @GetMapping("/stack-status/{accountId}")
    public ResponseEntity<?> getStackStatus(@PathVariable String accountId) {
        try {
            log.info("📋 Checking CloudFormation stack status for account: {}", accountId);

            Map<String, Object> status = cloudIdrService.getStackDeploymentStatus(accountId);

            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("❌ Failed to check stack status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to check status: " + e.getMessage()));
        }
    }

    /**
     * Sync existing CloudIDR account (for accounts already linked externally)
     */
    @PostMapping("/sync-account")
    public ResponseEntity<?> syncExistingAccount(@RequestBody Map<String, String> request) {
        try {
            String awsAccountId = request.get("awsAccountId");
            String cspAccountId = request.get("cspAccountId"); // Optional - will generate if not provided

            log.info("🔄 Syncing existing CloudIDR account: {}", awsAccountId);

            // If CSP ID not provided, generate one
            if (cspAccountId == null || cspAccountId.isEmpty()) {
                cspAccountId = "CSP-" + awsAccountId;
            }

            // Store in database without calling CloudIDR API
            cloudAccountRepository.updateCloudIdrLink(
                    awsAccountId,
                    cspAccountId,
                    "arn:aws:iam::" + awsAccountId + ":role/CloudIDR-Role", // Placeholder
                    "LINKED",
                    Instant.now());

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Account synced successfully");
            response.put("awsAccountId", awsAccountId);
            response.put("cspAccountId", cspAccountId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Failed to sync account", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

}
