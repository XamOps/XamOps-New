package com.xammer.cloud.controller;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.k8s.KubescapeDashboard;
import com.xammer.cloud.dto.k8s.KarpenterDashboard;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.K8sClientFactory;
import com.xammer.cloud.service.KubescapeService;
import com.xammer.cloud.service.KarpenterService;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/k8s/security")
public class CloudK8sSecurityController {

    private static final Logger logger = LoggerFactory.getLogger(CloudK8sSecurityController.class);

    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final K8sClientFactory k8sClientFactory;
    private final KubescapeService kubescapeService;
    private final KarpenterService karpenterService;

    public CloudK8sSecurityController(
            KubernetesClusterConfigRepository clusterConfigRepository,
            K8sClientFactory k8sClientFactory,
            KubescapeService kubescapeService,
            KarpenterService karpenterService) {
        this.clusterConfigRepository = clusterConfigRepository;
        this.k8sClientFactory = k8sClientFactory;
        this.kubescapeService = kubescapeService;
        this.karpenterService = karpenterService;
    }

    @GetMapping("/kubescape")
    public CompletableFuture<ResponseEntity<KubescapeDashboard>> getKubescapeData(
            @RequestParam Long accountId,
            @RequestParam String clusterName) {

        logger.info("🔍 Fetching Kubescape data for cluster: {}", clusterName);

        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository
                        .findByCloudAccountIdAndClusterName(accountId, clusterName);

                if (configOpt.isEmpty()) {
                    logger.warn("⚠️ Cluster config not found: {}", clusterName);
                    return ResponseEntity.notFound().build();
                }

                KubernetesClusterConfig config = configOpt.get();

                if (!Boolean.TRUE.equals(config.getKubescapeEnabled())) {
                    logger.warn("⚠️ Kubescape not enabled for cluster: {}", clusterName);
                    return ResponseEntity.badRequest().build();
                }

                String kubeconfig = config.getKubeconfigYaml();
                KubernetesClient client = k8sClientFactory.createFromKubeconfig(kubeconfig);
                KubescapeDashboard dashboard = kubescapeService.fetchDashboardData(
                        client,
                        clusterName,
                        String.valueOf(accountId));
                client.close();
                logger.info("✅ Successfully fetched Kubescape data for cluster: {}", clusterName);
                return ResponseEntity.ok(dashboard);

            } catch (Exception e) {
                logger.error("❌ Error fetching Kubescape data", e);
                return ResponseEntity.status(500).build();
            }
        });
    }

    @GetMapping("/karpenter")
    public CompletableFuture<ResponseEntity<KarpenterDashboard>> getKarpenterData(
            @RequestParam Long accountId,
            @RequestParam String clusterName) {

        logger.info("🔍 Fetching Karpenter data for cluster: {}", clusterName);

        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository
                        .findByCloudAccountIdAndClusterName(accountId, clusterName);

                if (configOpt.isEmpty()) {
                    logger.warn("⚠️ Cluster config not found: {}", clusterName);
                    return ResponseEntity.notFound().build();
                }

                KubernetesClusterConfig config = configOpt.get();

                if (!Boolean.TRUE.equals(config.getKarpenterEnabled())) {
                    logger.warn("⚠️ Karpenter not enabled for cluster: {}", clusterName);
                    return ResponseEntity.badRequest().build();
                }

                String kubeconfig = config.getKubeconfigYaml();
                KubernetesClient client = k8sClientFactory.createFromKubeconfig(kubeconfig);
                KarpenterDashboard dashboard = karpenterService.fetchDashboardData(client);
                client.close();
                logger.info("✅ Successfully fetched Karpenter data for cluster: {}", clusterName);
                return ResponseEntity.ok(dashboard);

            } catch (Exception e) {
                logger.error("❌ Error fetching Karpenter data", e);
                return ResponseEntity.status(500).build();
            }
        });
    }

    @PostMapping("/configure")
    public ResponseEntity<String> configureCluster(
            @RequestParam Long accountId,
            @RequestParam String clusterName,
            @RequestParam String region,
            @RequestParam(defaultValue = "false") boolean kubescapeEnabled,
            @RequestParam(defaultValue = "false") boolean karpenterEnabled,
            @RequestBody String kubeconfigYaml) {

        logger.info("⚙️ Configuring cluster: {} for account: {}", clusterName, accountId);

        try {
            Optional<KubernetesClusterConfig> existingConfig = clusterConfigRepository
                    .findByCloudAccountIdAndClusterName(accountId, clusterName);

            KubernetesClusterConfig config;
            if (existingConfig.isPresent()) {
                config = existingConfig.get();
                logger.info("🔄 Updating existing cluster configuration");
            } else {
                config = new KubernetesClusterConfig();
                config.setCloudAccountId(accountId);
                config.setClusterName(clusterName);
                logger.info("🆕 Creating new cluster configuration");
            }

            config.setRegion(region);
            config.setKubeconfigYaml(kubeconfigYaml);
            config.setKubescapeEnabled(kubescapeEnabled);
            config.setKarpenterEnabled(karpenterEnabled);

            KubernetesClient testClient = k8sClientFactory.createFromKubeconfig(kubeconfigYaml);
            String version = testClient.getKubernetesVersion().getGitVersion();
            testClient.close();
            logger.info("✅ Successfully connected to cluster, version: {}", version);

            clusterConfigRepository.save(config);

            logger.info("✅ Cluster configured successfully: {}", clusterName);
            return ResponseEntity.ok("Cluster configured successfully. Kubernetes version: " + version);

        } catch (Exception e) {
            logger.error("❌ Failed to configure cluster", e);
            return ResponseEntity.status(500).body("Failed to configure cluster: " + e.getMessage());
        }
    }
}