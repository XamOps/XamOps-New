// File: KarpenterLifecycleController.java
// Location: src/main/java/com/xammer/cloud/controller/KarpenterLifecycleController.java
// 
// ⚠️ CREATE THIS NEW FILE in the controller package

package com.xammer.cloud.controller;

import com.xammer.cloud.dto.karpenter.KarpenterConfigDto;
import com.xammer.cloud.service.EksService;
import com.xammer.cloud.service.KarpenterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * KarpenterLifecycleController: REST API for Karpenter installation and
 * configuration.
 *
 * ⭐ THREE ENDPOINTS:
 * 1. GET /api/karpenter/status - Check if Karpenter can be installed
 * 2. POST /api/karpenter/install - Install Karpenter on cluster
 * 3. POST /api/karpenter/configure - Configure NodePool and EC2NodeClass
 *
 * This controller bridges the frontend (eks-details.html) and backend services.
 *
 * Error Handling:
 * - 400 Bad Request: Missing or invalid parameters
 * - 403 Forbidden: User doesn't have permission
 * - 404 Not Found: Cluster or account not found
 * - 409 Conflict: Karpenter already installed
 * - 500 Internal Server Error: Installation failed
 *
 * Security:
 * ⚠️ TODO: Add permission checks (@PreAuthorize)
 * Currently assumes authenticated user; add role-based access control
 */
@Slf4j
@RestController
@RequestMapping("/api/karpenter")
@CrossOrigin(origins = "*")
public class KarpenterLifecycleController {

    @Autowired
    private KarpenterService karpenterService;

    @Autowired
    private EksService eksService;

    // ============= ENDPOINT 1: Status Check =============

    /**
     * GET /api/karpenter/status
     *
     * Check if Karpenter can be installed on a cluster.
     *
     * Query Parameters:
     * - accountId (required): Customer's AWS account ID
     * - clusterName (required): EKS cluster name
     * - region (required): AWS region
     *
     * Response (200 OK):
     * {
     * "oidcReady": true,
     * "cloudFormationReady": true,
     * "userHasPermission": true,
     * "karpenterInstalled": false,
     * "message": "Ready to install",
     * "details": {
     * "oidcIssuerUrl": "https://oidc.eks.us-east-1.amazonaws.com/id/ABC123..."
     * }
     * }
     *
     * Response (400 Bad Request):
     * {
     * "error": "Missing required parameters",
     * "message": "accountId and clusterName are required"
     * }
     *
     * Response (500 Internal Server Error):
     * {
     * "error": "Failed to check status",
     * "message": "..."
     * }
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getKarpenterStatus(
            @RequestParam String accountId,
            @RequestParam String clusterName,
            @RequestParam String region) {

        log.info("🔍 [KarpenterController] GET /status - accountId: {} clusterName: {}", accountId, clusterName);

        try {
            // Validate parameters
            if (accountId == null || accountId.isEmpty() ||
                    clusterName == null || clusterName.isEmpty() ||
                    region == null || region.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Missing required parameters",
                        "message", "accountId, clusterName, and region are required"));
            }

            Map<String, Object> response = new HashMap<>();

            // Check 1: OIDC provider
            boolean oidcReady = eksService.hasOIDCProvider(accountId, clusterName, region);
            response.put("oidcReady", oidcReady);

            // Check 2: CloudFormation stack (simplified - assume ready if role format is
            // valid)
            // TODO: Query DynamoDB to verify stack completion
            boolean cloudFormationReady = true; // Placeholder
            response.put("cloudFormationReady", cloudFormationReady);

            // Check 3: User permission (simplified - assume authenticated user has
            // permission)
            // TODO: Add @PreAuthorize annotation with role check
            boolean userHasPermission = true; // Placeholder
            response.put("userHasPermission", userHasPermission);

            // Check 4: Is Karpenter already installed?
            boolean karpenterInstalled = karpenterService.isKarpenterInstalled(accountId, clusterName, region);
            response.put("karpenterInstalled", karpenterInstalled);

            // Build message
            String message;
            if (!oidcReady) {
                message = "❌ OIDC provider not enabled on cluster";
            } else if (!cloudFormationReady) {
                message = "⏳ CloudFormation stack not finished - click 'Deploy to AWS' first";
            } else if (!userHasPermission) {
                message = "❌ Your account lacks karpenter.install permission";
            } else if (karpenterInstalled) {
                message = "✅ Karpenter is installed - go to Configuration";
            } else {
                message = "✅ Ready to install Karpenter";
            }
            response.put("message", message);

            // Add optional details
            if (oidcReady) {
                Map<String, String> details = new HashMap<>();
                try {
                    String oidcUrl = eksService.getOIDCIssuerUrl(accountId, clusterName, region);
                    details.put("oidcIssuerUrl", oidcUrl);
                } catch (Exception e) {
                    log.warn("Could not fetch OIDC URL: {}", e.getMessage());
                }
                response.put("details", details);
            }

            log.info("✅ Status check completed: {}", message);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Error checking Karpenter status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Failed to check Karpenter status",
                    "message", e.getMessage()));
        }
    }

    // ============= ENDPOINT 2: Install =============

    /**
     * POST /api/karpenter/install
     *
     * Install Karpenter deployment on a cluster.
     *
     * Request Body:
     * {
     * "accountId": "123456789012",
     * "clusterName": "prod-cluster-1",
     * "region": "us-east-1",
     * "roleArn":
     * "arn:aws:iam::123456789012:role/KarpenterControllerRole-prod-cluster-1"
     * }
     *
     * Response (200 OK):
     * {
     * "success": true,
     * "message": "Karpenter installed successfully",
     * "namespace": "karpenter",
     * "clusterName": "prod-cluster-1"
     * }
     *
     * Response (400 Bad Request):
     * {
     * "error": "Missing required fields",
     * "message": "accountId, clusterName, region, roleArn are required"
     * }
     *
     * Response (409 Conflict):
     * {
     * "error": "Karpenter already installed",
     * "message": "Cannot install Karpenter twice"
     * }
     *
     * Response (500 Internal Server Error):
     * {
     * "error": "Installation failed",
     * "message": "..."
     * }
     */
    @PostMapping("/install")
    public ResponseEntity<Map<String, Object>> installKarpenter(
            @RequestBody Map<String, String> request) {

        log.info("🚀 [KarpenterController] POST /install");

        try {
            // Validate request
            String accountId = request.get("accountId");
            String clusterName = request.get("clusterName");
            String region = request.get("region");
            String roleArn = request.get("roleArn");

            if (accountId == null || accountId.isEmpty() ||
                    clusterName == null || clusterName.isEmpty() ||
                    region == null || region.isEmpty() ||
                    roleArn == null || roleArn.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Missing required fields",
                        "message", "accountId, clusterName, region, and roleArn are required"));
            }

            // Check if already installed
            if (karpenterService.isKarpenterInstalled(accountId, clusterName, region)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "Karpenter already installed",
                        "message", "Cannot install Karpenter twice on the same cluster"));
            }

            // Install Karpenter
            karpenterService.installKarpenter(accountId, clusterName, region, roleArn);

            log.info("✅ Karpenter installed successfully");
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Karpenter installed successfully",
                    "namespace", "karpenter",
                    "clusterName", clusterName));

        } catch (RuntimeException e) {
            log.error("❌ Installation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Installation failed",
                    "message", e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Unexpected error during installation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Unexpected error",
                    "message", e.getMessage()));
        }
    }

    // ============= ENDPOINT 3: Configure =============

    /**
     * POST /api/karpenter/configure
     *
     * Configure NodePool and EC2NodeClass based on user input.
     *
     * Request Body (KarpenterConfigDto):
     * {
     * "accountId": "123456789012",
     * "clusterId": "prod-cluster-1",
     * "region": "us-east-1",
     * "useSpot": true,
     * "instanceFamilies": ["c5", "m5", "r5"],
     * "ttlSecondsAfterEmpty": 30,
     * "ttlSecondsUntilExpired": 604800,
     * "consolidationEnabled": true,
     * "nodePoolName": "spot-saver",
     * "ec2NodeClassName": "default"
     * }
     *
     * ⚠️ NOTE: instanceFamilies will be overridden to ["t3"] due to POC budget
     * constraint
     *
     * Response (200 OK):
     * {
     * "success": true,
     * "message": "NodePool configured successfully",
     * "nodePoolName": "spot-saver",
     * "ec2NodeClassName": "default",
     * "clusterName": "prod-cluster-1"
     * }
     *
     * Response (400 Bad Request):
     * {
     * "error": "Missing required fields",
     * "message": "accountId, clusterId, and region are required"
     * }
     *
     * Response (500 Internal Server Error):
     * {
     * "error": "Configuration failed",
     * "message": "..."
     * }
     */
    @PostMapping("/configure")
    public ResponseEntity<Map<String, Object>> configureNodePool(
            @RequestBody KarpenterConfigDto config) {

        log.info("⚙️ [KarpenterController] POST /configure - cluster: {}", config.getClusterId());

        try {
            // Validate request
            if (config.getAccountId() == null || config.getAccountId().isEmpty() ||
                    config.getClusterId() == null || config.getClusterId().isEmpty() ||
                    config.getRegion() == null || config.getRegion().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Missing required fields",
                        "message", "accountId, clusterId, and region are required"));
            }

            // Set defaults if not provided
            if (config.getNodePoolName() == null || config.getNodePoolName().isEmpty()) {
                config.setNodePoolName("spot-saver");
            }
            if (config.getEc2NodeClassName() == null || config.getEc2NodeClassName().isEmpty()) {
                config.setEc2NodeClassName("default");
            }
            if (config.getUseSpot() == null) {
                config.setUseSpot(true);
            }
            if (config.getTtlSecondsAfterEmpty() == null) {
                config.setTtlSecondsAfterEmpty(30);
            }
            if (config.getTtlSecondsUntilExpired() == null) {
                config.setTtlSecondsUntilExpired(2592000); // 30 days
            }
            if (config.getConsolidationEnabled() == null) {
                config.setConsolidationEnabled(true);
            }

            // Configure NodePool
            karpenterService.configureNodePool(config);

            log.info("✅ NodePool configured successfully");
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "NodePool configured successfully",
                    "nodePoolName", config.getNodePoolName(),
                    "ec2NodeClassName", config.getEc2NodeClassName(),
                    "clusterName", config.getClusterId(),
                    "pocConstraint", "Instance families limited to [t3] for POC phase"));

        } catch (RuntimeException e) {
            log.error("❌ Configuration failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Configuration failed",
                    "message", e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Unexpected error during configuration", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Unexpected error",
                    "message", e.getMessage()));
        }
    }
}