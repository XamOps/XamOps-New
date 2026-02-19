package com.xammer.cloud.controller;

import com.xammer.cloud.service.ClusterIntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for cluster integration management.
 *
 * Endpoints:
 * - POST /api/v1/clusters/{id}/api-key - Generate new API key
 * - PUT /api/v1/clusters/{id}/api-key - Regenerate API key
 * - DELETE /api/v1/clusters/{id}/api-key - Revoke API key
 * - GET /api/v1/clusters/{id}/integration-status - Get integration status
 * - PUT /api/v1/clusters/{id}/data-source-mode - Set data source mode
 * - GET /api/v1/clusters/{id}/agent-install - Get agent install command
 */
@RestController
@RequestMapping("/api/v1/clusters")
public class ClusterIntegrationController {

    private static final Logger logger = LoggerFactory.getLogger(ClusterIntegrationController.class);

    private final ClusterIntegrationService integrationService;

    @Value("${xamops.server.url:https://api.xamops.com}")
    private String xamopsServerUrl;

    public ClusterIntegrationController(ClusterIntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    /**
     * Generate a new API key for a cluster.
     *
     * POST /api/v1/clusters/{clusterConfigId}/api-key
     */
    @PostMapping("/{clusterConfigId}/api-key")
    public ResponseEntity<Map<String, Object>> generateApiKey(@PathVariable Long clusterConfigId) {
        logger.info("Generating API key for cluster config: {}", clusterConfigId);
        Map<String, Object> result = integrationService.generateApiKey(clusterConfigId);

        if (!(boolean) result.get("success")) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Generate API key by account and cluster name.
     *
     * POST /api/v1/clusters/api-key?cloudAccountId=xxx&clusterName=xxx
     */
    @PostMapping("/api-key")
    public ResponseEntity<Map<String, Object>> generateApiKeyByName(
            @RequestParam Long cloudAccountId,
            @RequestParam String clusterName) {
        logger.info("Generating API key for account: {}, cluster: {}", cloudAccountId, clusterName);
        Map<String, Object> result = integrationService.generateApiKey(cloudAccountId, clusterName);

        if (!(boolean) result.get("success")) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Regenerate API key for a cluster.
     * This invalidates the old key immediately.
     *
     * PUT /api/v1/clusters/{clusterConfigId}/api-key
     */
    @PutMapping("/{clusterConfigId}/api-key")
    public ResponseEntity<Map<String, Object>> regenerateApiKey(@PathVariable Long clusterConfigId) {
        logger.info("Regenerating API key for cluster config: {}", clusterConfigId);
        Map<String, Object> result = integrationService.regenerateApiKey(clusterConfigId);

        if (!(boolean) result.get("success")) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Revoke API key for a cluster.
     *
     * DELETE /api/v1/clusters/{clusterConfigId}/api-key
     */
    @DeleteMapping("/{clusterConfigId}/api-key")
    public ResponseEntity<Map<String, Object>> revokeApiKey(@PathVariable Long clusterConfigId) {
        logger.info("Revoking API key for cluster config: {}", clusterConfigId);
        Map<String, Object> result = integrationService.revokeApiKey(clusterConfigId);

        if (!(boolean) result.get("success")) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get integration status for a cluster.
     *
     * GET /api/v1/clusters/{clusterConfigId}/integration-status
     */
    @GetMapping("/{clusterConfigId}/integration-status")
    public ResponseEntity<Map<String, Object>> getIntegrationStatus(@PathVariable Long clusterConfigId) {
        Map<String, Object> result = integrationService.getIntegrationStatus(clusterConfigId);

        if (!(boolean) result.get("success")) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Set data source mode for a cluster.
     *
     * PUT /api/v1/clusters/{clusterConfigId}/data-source-mode
     * Body: { "mode": "kubeconfig" | "agent" | "hybrid" }
     */
    @PutMapping("/{clusterConfigId}/data-source-mode")
    public ResponseEntity<Map<String, Object>> setDataSourceMode(
            @PathVariable Long clusterConfigId,
            @RequestBody Map<String, String> body) {

        String mode = body.get("mode");
        if (mode == null || mode.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Mode is required"
            ));
        }

        logger.info("Setting data source mode to '{}' for cluster config: {}", mode, clusterConfigId);
        Map<String, Object> result = integrationService.setDataSourceMode(clusterConfigId, mode);

        if (!(boolean) result.get("success")) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get agent installation command for a cluster.
     *
     * GET /api/v1/clusters/{clusterConfigId}/agent-install
     */
    @GetMapping("/{clusterConfigId}/agent-install")
    public ResponseEntity<Map<String, Object>> getAgentInstallCommand(
            @PathVariable Long clusterConfigId,
            @RequestParam(required = false) String serverUrl) {

        String url = serverUrl != null ? serverUrl : xamopsServerUrl;
        Map<String, Object> result = integrationService.getAgentInstallCommand(clusterConfigId, url);

        if (!(boolean) result.get("success")) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }
}
