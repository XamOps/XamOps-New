package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.ClusterIntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Controller for cluster integration management.
 *
 * <p>FR-10.1 lockdown (design §4.4): every endpoint resolves the config, finds its owning
 * account and checks the caller may access it. "Not found" and "not yours" return the same
 * 403 body so the endpoint is not an enumeration oracle. {@code GET /{id}/agent-install} is
 * retired (410) in favour of CloudK8s "Connect cluster".</p>
 */
@RestController
@RequestMapping("/api/v1/clusters")
public class ClusterIntegrationController {

    private static final Logger logger = LoggerFactory.getLogger(ClusterIntegrationController.class);

    private static final Map<String, Object> FORBIDDEN_BODY =
            Map.of("success", false, "error", "Cluster not found or access denied.");

    private final ClusterIntegrationService integrationService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final K8sAutomationAccessService accessService;
    private final CloudAccountAccessService cloudAccountAccessService;

    public ClusterIntegrationController(ClusterIntegrationService integrationService,
                                        KubernetesClusterConfigRepository clusterConfigRepository,
                                        K8sAutomationAccessService accessService,
                                        CloudAccountAccessService cloudAccountAccessService) {
        this.integrationService = integrationService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.accessService = accessService;
        this.cloudAccountAccessService = cloudAccountAccessService;
    }

    /**
     * Resolve the config and assert the caller owns it. Returns the config when access is
     * granted, otherwise empty (the caller responds 403 with the opaque body). Uses the same
     * message for "no such config" and "not entitled" to avoid an enumeration oracle.
     */
    private Optional<KubernetesClusterConfig> requireOwned(ClientUserDetails user, Long clusterConfigId) {
        if (user == null || clusterConfigId == null) {
            return Optional.empty();
        }
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository.findById(clusterConfigId);
        if (configOpt.isEmpty()) {
            return Optional.empty();
        }
        KubernetesClusterConfig config = configOpt.get();
        Optional<CloudAccount> owner = accessService.findOwningAccount(config);
        if (owner.isEmpty() || !cloudAccountAccessService.canAccess(user, owner.get())) {
            logger.warn("Denied cluster-integration access: user={} clusterConfigId={}",
                    user.getId(), clusterConfigId);
            return Optional.empty();
        }
        return configOpt;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(FORBIDDEN_BODY);
    }

    @PostMapping("/{clusterConfigId}/api-key")
    public ResponseEntity<Map<String, Object>> generateApiKey(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long clusterConfigId) {
        if (requireOwned(user, clusterConfigId).isEmpty()) {
            return forbidden();
        }
        logger.info("Generating API key for cluster config: {}", clusterConfigId);
        Map<String, Object> result = integrationService.generateApiKey(clusterConfigId);
        return (boolean) result.get("success") ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/api-key")
    public ResponseEntity<Map<String, Object>> generateApiKeyByName(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestParam Long cloudAccountId,
            @RequestParam String clusterName) {
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository
                .findByCloudAccountIdAndClusterName(cloudAccountId, clusterName);
        if (configOpt.isEmpty() || requireOwned(user, configOpt.get().getId()).isEmpty()) {
            return forbidden();
        }
        logger.info("Generating API key for account: {}, cluster: {}", cloudAccountId, clusterName);
        Map<String, Object> result = integrationService.generateApiKey(configOpt.get().getId());
        return (boolean) result.get("success") ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PutMapping("/{clusterConfigId}/api-key")
    public ResponseEntity<Map<String, Object>> regenerateApiKey(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long clusterConfigId) {
        if (requireOwned(user, clusterConfigId).isEmpty()) {
            return forbidden();
        }
        logger.info("Regenerating API key for cluster config: {}", clusterConfigId);
        Map<String, Object> result = integrationService.regenerateApiKey(clusterConfigId);
        return (boolean) result.get("success") ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @DeleteMapping("/{clusterConfigId}/api-key")
    public ResponseEntity<Map<String, Object>> revokeApiKey(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long clusterConfigId) {
        if (requireOwned(user, clusterConfigId).isEmpty()) {
            return forbidden();
        }
        logger.info("Revoking API key for cluster config: {}", clusterConfigId);
        Map<String, Object> result = integrationService.revokeApiKey(clusterConfigId);
        return (boolean) result.get("success") ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/{clusterConfigId}/integration-status")
    public ResponseEntity<Map<String, Object>> getIntegrationStatus(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long clusterConfigId) {
        if (requireOwned(user, clusterConfigId).isEmpty()) {
            return forbidden();
        }
        Map<String, Object> result = integrationService.getIntegrationStatus(clusterConfigId);
        return (boolean) result.get("success") ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PutMapping("/{clusterConfigId}/data-source-mode")
    public ResponseEntity<Map<String, Object>> setDataSourceMode(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long clusterConfigId,
            @RequestBody Map<String, String> body) {
        if (requireOwned(user, clusterConfigId).isEmpty()) {
            return forbidden();
        }
        String mode = body.get("mode");
        if (mode == null || mode.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Mode is required"));
        }
        logger.info("Setting data source mode to '{}' for cluster config: {}", mode, clusterConfigId);
        Map<String, Object> result = integrationService.setDataSourceMode(clusterConfigId, mode);
        return (boolean) result.get("success") ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    /**
     * Retired (design §4.4). The fake Helm/charts.xamops.com path is gone; use CloudK8s
     * "Connect cluster". Returns 410 Gone with a pointer. Still tenant-checked so it is not an
     * enumeration oracle.
     */
    @GetMapping("/{clusterConfigId}/agent-install")
    public ResponseEntity<Map<String, Object>> getAgentInstallCommand(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long clusterConfigId,
            @RequestParam(required = false) String serverUrl) {
        if (requireOwned(user, clusterConfigId).isEmpty()) {
            return forbidden();
        }
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "error", "GONE",
                "message", "Use Connect cluster in CloudK8s"));
    }
}
