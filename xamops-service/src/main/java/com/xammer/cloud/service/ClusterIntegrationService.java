package com.xammer.cloud.service;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing cluster integration including API key generation.
 *
 * API keys are used by XamOps Agents to authenticate when pushing data.
 * Each cluster configuration gets a unique API key that can be regenerated.
 */
@Service
public class ClusterIntegrationService {

    private static final Logger logger = LoggerFactory.getLogger(ClusterIntegrationService.class);

    private static final int API_KEY_LENGTH = 32; // 32 bytes = 256 bits
    private static final String API_KEY_PREFIX = "xamops_"; // Prefix for easy identification

    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final SecureRandom secureRandom;

    public ClusterIntegrationService(KubernetesClusterConfigRepository clusterConfigRepository) {
        this.clusterConfigRepository = clusterConfigRepository;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generate a new API key for a cluster configuration.
     * If the cluster already has an API key, it will be replaced.
     *
     * @param clusterConfigId The cluster configuration ID
     * @return Map containing the API key and related info
     */
    @Transactional
    public Map<String, Object> generateApiKey(Long clusterConfigId) {
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository.findById(clusterConfigId);

        if (configOpt.isEmpty()) {
            logger.warn("Cluster config not found for ID: {}", clusterConfigId);
            return Map.of(
                    "success", false,
                    "error", "Cluster configuration not found"
            );
        }

        KubernetesClusterConfig config = configOpt.get();

        // Generate new API key
        String apiKey = generateSecureApiKey();

        // Update config
        config.setAgentApiKey(apiKey);
        config.setAgentStatus("pending"); // Reset status - waiting for agent to connect
        clusterConfigRepository.save(config);

        logger.info("Generated new API key for cluster: {} (ID: {})", config.getClusterName(), clusterConfigId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("apiKey", apiKey);
        result.put("clusterName", config.getClusterName());
        result.put("clusterConfigId", clusterConfigId);
        result.put("generatedAt", LocalDateTime.now().toString());

        return result;
    }

    /**
     * Generate a new API key for a cluster by cloud account ID and cluster name.
     *
     * @param cloudAccountId The cloud account ID
     * @param clusterName The cluster name
     * @return Map containing the API key and related info
     */
    @Transactional
    public Map<String, Object> generateApiKey(Long cloudAccountId, String clusterName) {
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository
                .findByCloudAccountIdAndClusterName(cloudAccountId, clusterName);

        if (configOpt.isEmpty()) {
            logger.warn("Cluster config not found for account: {} cluster: {}", cloudAccountId, clusterName);
            return Map.of(
                    "success", false,
                    "error", "Cluster configuration not found"
            );
        }

        return generateApiKey(configOpt.get().getId());
    }

    /**
     * Regenerate API key for an existing configuration.
     * This invalidates the old key immediately.
     *
     * @param clusterConfigId The cluster configuration ID
     * @return Map containing the new API key and related info
     */
    @Transactional
    public Map<String, Object> regenerateApiKey(Long clusterConfigId) {
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository.findById(clusterConfigId);

        if (configOpt.isEmpty()) {
            return Map.of(
                    "success", false,
                    "error", "Cluster configuration not found"
            );
        }

        KubernetesClusterConfig config = configOpt.get();

        String oldKeyPrefix = config.getAgentApiKey() != null ?
                config.getAgentApiKey().substring(0, Math.min(16, config.getAgentApiKey().length())) + "..." : "none";

        // Generate new key
        String newApiKey = generateSecureApiKey();
        config.setAgentApiKey(newApiKey);
        config.setAgentStatus("pending"); // Agent needs to reconnect with new key
        config.setLastAgentCheckin(null); // Clear last checkin
        clusterConfigRepository.save(config);

        logger.info("Regenerated API key for cluster: {} (old key prefix: {})",
                config.getClusterName(), oldKeyPrefix);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("apiKey", newApiKey);
        result.put("clusterName", config.getClusterName());
        result.put("clusterConfigId", clusterConfigId);
        result.put("regeneratedAt", LocalDateTime.now().toString());
        result.put("warning", "Old API key has been invalidated. Update your agent configuration.");

        return result;
    }

    /**
     * Revoke API key for a cluster.
     * This disables agent authentication for the cluster.
     *
     * @param clusterConfigId The cluster configuration ID
     * @return Success status
     */
    @Transactional
    public Map<String, Object> revokeApiKey(Long clusterConfigId) {
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository.findById(clusterConfigId);

        if (configOpt.isEmpty()) {
            return Map.of(
                    "success", false,
                    "error", "Cluster configuration not found"
            );
        }

        KubernetesClusterConfig config = configOpt.get();
        config.setAgentApiKey(null);
        config.setAgentStatus("disabled");
        clusterConfigRepository.save(config);

        logger.info("Revoked API key for cluster: {}", config.getClusterName());

        return Map.of(
                "success", true,
                "clusterName", config.getClusterName(),
                "message", "API key revoked. Agent will no longer be able to push data."
        );
    }

    /**
     * Get integration status for a cluster.
     *
     * @param clusterConfigId The cluster configuration ID
     * @return Integration status including agent info
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getIntegrationStatus(Long clusterConfigId) {
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository.findById(clusterConfigId);

        if (configOpt.isEmpty()) {
            return Map.of(
                    "success", false,
                    "error", "Cluster configuration not found"
            );
        }

        KubernetesClusterConfig config = configOpt.get();

        Map<String, Object> status = new HashMap<>();
        status.put("success", true);
        status.put("clusterName", config.getClusterName());
        status.put("cloudAccountId", config.getCloudAccountId());
        status.put("dataSourceMode", config.getDataSourceMode());
        status.put("hasApiKey", config.getAgentApiKey() != null && !config.getAgentApiKey().isEmpty());
        status.put("agentStatus", config.getAgentStatus());
        status.put("agentVersion", config.getAgentVersion());
        status.put("lastAgentCheckin", config.getLastAgentCheckin());
        status.put("isAgentActive", config.isAgentActive());
        status.put("hasKubeconfig", config.getKubeconfigYaml() != null && !config.getKubeconfigYaml().isEmpty());

        // Provide recommendation
        if (config.isAgentActive() && config.isKubeconfigEnabled()) {
            status.put("recommendation", "Hybrid mode - using both agent and kubeconfig data sources");
        } else if (config.isAgentActive()) {
            status.put("recommendation", "Agent mode - receiving data from XamOps Agent");
        } else if (config.isKubeconfigEnabled()) {
            status.put("recommendation", "Kubeconfig mode - fetching data directly from cluster");
        } else {
            status.put("recommendation", "No data source configured. Generate API key for agent or provide kubeconfig.");
        }

        return status;
    }

    /**
     * Update data source mode for a cluster.
     *
     * @param clusterConfigId The cluster configuration ID
     * @param mode The new mode: "kubeconfig", "agent", or "hybrid"
     * @return Success status
     */
    @Transactional
    public Map<String, Object> setDataSourceMode(Long clusterConfigId, String mode) {
        if (!mode.equals("kubeconfig") && !mode.equals("agent") && !mode.equals("hybrid")) {
            return Map.of(
                    "success", false,
                    "error", "Invalid mode. Must be 'kubeconfig', 'agent', or 'hybrid'"
            );
        }

        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository.findById(clusterConfigId);

        if (configOpt.isEmpty()) {
            return Map.of(
                    "success", false,
                    "error", "Cluster configuration not found"
            );
        }

        KubernetesClusterConfig config = configOpt.get();

        // Validate mode requirements
        if (mode.equals("agent") || mode.equals("hybrid")) {
            if (config.getAgentApiKey() == null || config.getAgentApiKey().isEmpty()) {
                return Map.of(
                        "success", false,
                        "error", "Cannot set agent/hybrid mode without API key. Generate an API key first."
                );
            }
        }

        if (mode.equals("kubeconfig") || mode.equals("hybrid")) {
            if (config.getKubeconfigYaml() == null || config.getKubeconfigYaml().isEmpty()) {
                return Map.of(
                        "success", false,
                        "error", "Cannot set kubeconfig/hybrid mode without kubeconfig. Provide kubeconfig first."
                );
            }
        }

        config.setDataSourceMode(mode);
        clusterConfigRepository.save(config);

        logger.info("Set data source mode to '{}' for cluster: {}", mode, config.getClusterName());

        return Map.of(
                "success", true,
                "clusterName", config.getClusterName(),
                "dataSourceMode", mode,
                "message", "Data source mode updated to: " + mode
        );
    }

    /**
     * Generate Helm install command for XamOps Agent.
     *
     * @param clusterConfigId The cluster configuration ID
     * @param xamopsServerUrl The XamOps server URL (e.g., https://api.xamops.com)
     * @return Map containing the Helm command and values
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAgentInstallCommand(Long clusterConfigId, String xamopsServerUrl) {
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository.findById(clusterConfigId);

        if (configOpt.isEmpty()) {
            return Map.of(
                    "success", false,
                    "error", "Cluster configuration not found"
            );
        }

        KubernetesClusterConfig config = configOpt.get();

        if (config.getAgentApiKey() == null || config.getAgentApiKey().isEmpty()) {
            return Map.of(
                    "success", false,
                    "error", "No API key found. Generate an API key first."
            );
        }

        String helmValues = String.format("""
            # xamops-agent-values.yaml
            agent:
              clusterName: "%s"
              apiKey: "%s"
              serverUrl: "%s"

              # Data collection intervals (in seconds)
              collectInterval: 300  # 5 minutes
              pushInterval: 300     # 5 minutes

              # Collectors to enable
              collectors:
                trivy: true
                kubescape: true
                opencost: true
                metrics: true

            resources:
              requests:
                cpu: 100m
                memory: 128Mi
              limits:
                cpu: 500m
                memory: 512Mi
            """, config.getClusterName(), config.getAgentApiKey(), xamopsServerUrl);

        String helmCommand = String.format("""
            # Add XamOps Helm repository
            helm repo add xamops https://charts.xamops.com
            helm repo update

            # Install XamOps Agent
            helm upgrade --install xamops-agent xamops/xamops-agent \\
              --namespace xamops-system \\
              --create-namespace \\
              --set agent.clusterName="%s" \\
              --set agent.apiKey="%s" \\
              --set agent.serverUrl="%s" \\
              --wait
            """, config.getClusterName(), config.getAgentApiKey(), xamopsServerUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("clusterName", config.getClusterName());
        result.put("helmValues", helmValues);
        result.put("helmCommand", helmCommand);
        result.put("apiKey", config.getAgentApiKey());
        result.put("note", "Save the values file as xamops-agent-values.yaml and run: helm upgrade --install xamops-agent xamops/xamops-agent -f xamops-agent-values.yaml -n xamops-system --create-namespace");

        return result;
    }

    /**
     * Generate a cryptographically secure API key.
     *
     * @return A URL-safe Base64 encoded API key with prefix
     */
    private String generateSecureApiKey() {
        byte[] randomBytes = new byte[API_KEY_LENGTH];
        secureRandom.nextBytes(randomBytes);

        // Use URL-safe Base64 encoding (no padding)
        String encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        return API_KEY_PREFIX + encodedKey;
    }

    /**
     * Validate API key format.
     *
     * @param apiKey The API key to validate
     * @return true if valid format, false otherwise
     */
    public boolean isValidApiKeyFormat(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return false;
        }

        if (!apiKey.startsWith(API_KEY_PREFIX)) {
            return false;
        }

        // Check the Base64 portion
        String keyPart = apiKey.substring(API_KEY_PREFIX.length());
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(keyPart);
            return decoded.length == API_KEY_LENGTH;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
