package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.k8s.ClusterUsageDto;
import com.xammer.cloud.dto.k8s.K8sClusterInfo;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.EksClusterUsageService;
import com.xammer.cloud.service.EksService;
import com.xammer.cloud.service.onboarding.CloudNodegroupsReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/xamops/k8s")
public class CloudK8sController {

    private static final Logger logger = LoggerFactory.getLogger(CloudK8sController.class);
    private static final Pattern CLUSTER_NAME = Pattern.compile("^[0-9A-Za-z][A-Za-z0-9_-]{0,99}$");

    private final EksService eksService;
    private final EksClusterUsageService usageService;
    private final K8sAutomationAccessService accessService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final CloudNodegroupsReader cloudNodegroupsReader;

    public CloudK8sController(EksService eksService, EksClusterUsageService usageService,
                              K8sAutomationAccessService accessService,
                              KubernetesClusterConfigRepository clusterConfigRepository,
                              CloudNodegroupsReader cloudNodegroupsReader) {
        this.eksService = eksService;
        this.usageService = usageService;
        this.accessService = accessService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.cloudNodegroupsReader = cloudNodegroupsReader;
    }

    /**
     * BP-16 (design §8.1): nodegroup scaling config from the data agent's {@code cloud-nodegroups}
     * row (fresh within 30 min). Session + tenant checked via {@code resolveAccounts} (403 when the
     * account is not owned). {@code source=AGENT} with the rows, or {@code source=NONE} when there
     * is no fresh row.
     */
    @GetMapping("/clusters/{clusterName}/nodegroup-scaling")
    public ResponseEntity<Map<String, Object>> nodegroupScaling(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable String clusterName,
            @RequestParam String accountId) {
        if (clusterName == null || !CLUSTER_NAME.matcher(clusterName).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST"));
        }
        List<CloudAccount> accounts;
        try {
            accounts = accessService.resolveAccounts(user, accountId);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "K8S_NO_ACCOUNT_ACCESS"));
        }
        if (accounts == null || accounts.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "K8S_NO_ACCOUNT_ACCESS"));
        }

        Optional<KubernetesClusterConfig> config = Optional.empty();
        for (CloudAccount a : accounts) {
            config = clusterConfigRepository.findByCloudAccountIdAndClusterName(a.getId(), clusterName);
            if (config.isPresent()) {
                break;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (config.isEmpty()) {
            body.put("source", "NONE");
            body.put("nodegroups", List.of());
            return ResponseEntity.ok(body);
        }
        Optional<CloudNodegroupsReader.Result> fresh =
                cloudNodegroupsReader.read(config.get(), Duration.ofMinutes(30));
        if (fresh.isEmpty()) {
            body.put("source", "NONE");
            body.put("nodegroups", List.of());
        } else {
            body.put("source", "AGENT");
            body.put("collectedAt", fresh.get().collectedAt());
            body.put("nodegroups", fresh.get().nodegroups());
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Fetches aggregated usage statistics (CPU, Memory, Counts) for a cluster using Prometheus.
     * Used to populate the "Cluster Details" header in the UI.
     */
    @GetMapping("/clusters/usage")
    public CompletableFuture<ResponseEntity<ClusterUsageDto>> getClusterUsage(
        @RequestParam String accountId,
        @RequestParam String clusterName,
        @RequestParam String region) {
        
        return usageService.getClusterUsage(accountId, clusterName, region)
            .thenApply(ResponseEntity::ok)
            .exceptionally(ex -> {
                logger.error("Error fetching cluster usage for {}: {}", clusterName, ex.getMessage());
                return ResponseEntity.status(500).body(new ClusterUsageDto());
            });
    }

    @GetMapping("/clusters")
    public CompletableFuture<ResponseEntity<List<K8sClusterInfo>>> getClusters(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        String accountIdToUse = accountIds.split(",")[0];

        return eksService.getEksClusterInfo(accountIdToUse, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching clusters: {}", ex.getMessage());
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/clusters/{clusterName}/nodes")
    public CompletableFuture<ResponseEntity<List<K8sNodeInfo>>> getNodes(
            @RequestParam String accountId,
            @PathVariable String clusterName) {
        // Note: This endpoint fetches metrics via EksService (legacy/hybrid). 
        // For the full node list with Prometheus metrics, the UI typically calls EksController.getClusterNodes
        return eksService.getK8sNodes(accountId, clusterName, false)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(500).body(Collections.emptyList()));
    }

    /**
     * Fetches the details for a single cluster, using the cache.
     * This is used by eks-details.html to get its initial data (Region, Status, Version).
     */
    @GetMapping("/{clusterName}/details")
    public CompletableFuture<ResponseEntity<K8sClusterInfo>> getClusterDetails(
            @PathVariable String clusterName,
            @RequestParam String accountId) {
        
        return eksService.getEksClusterInfo(accountId, false)
            .thenApply(clusters -> clusters.stream()
                .filter(c -> c.getName().equals(clusterName))
                .findFirst()
                .map(ResponseEntity::ok) 
                .orElse(ResponseEntity.notFound().build()))
            .exceptionally(ex -> {
                logger.error("Error fetching details for cluster {}: {}", clusterName, ex.getMessage());
                return ResponseEntity.status(500).build();
            });
    }
}