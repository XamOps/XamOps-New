package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.dto.azure.AksClusterDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import com.xammer.cloud.service.AgentDataTransformerService;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
@Service
public class AksService {

    private static final String CACHE_PREFIX = "AKS_CLUSTERS::";
    private static final int CACHE_TTL_MINUTES = 60;

    private final AzureClientProvider azureClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final KubernetesClusterRepository kubernetesClusterRepository;
    private final RedisCacheService redisCacheService;
    private final KubernetesClusterConfigRepository kubernetesClusterConfigRepository;
    private final AgentDataTransformerService agentDataTransformerService;

    public AksService(AzureClientProvider azureClientProvider,
                      CloudAccountRepository cloudAccountRepository,
                      KubernetesClusterRepository kubernetesClusterRepository,
                      RedisCacheService redisCacheService,
                      KubernetesClusterConfigRepository kubernetesClusterConfigRepository,
                      AgentDataTransformerService agentDataTransformerService) {
        this.azureClientProvider = azureClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.kubernetesClusterRepository = kubernetesClusterRepository;
        this.redisCacheService = redisCacheService;
        this.kubernetesClusterConfigRepository = kubernetesClusterConfigRepository;
        this.agentDataTransformerService = agentDataTransformerService;
    }

    /**
     * Returns all AKS clusters for a given Azure subscription.
     * Results are Redis-cached for 60 minutes.
     *
     * @param subscriptionId Azure subscription ID
     * @param forceRefresh   When true, bypasses the Redis cache.
     */
    public List<AksClusterDto> getClusters(String subscriptionId, boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + subscriptionId;

        if (forceRefresh) {
            redisCacheService.evict(cacheKey);
        } else {
            Optional<List<AksClusterDto>> cached = redisCacheService.get(
                    cacheKey, new TypeReference<List<AksClusterDto>>() {});
            if (cached.isPresent()) {
                log.info("Redis cache HIT for AKS clusters: subscription={}", subscriptionId);
                return cached.get();
            }
        }

        log.info("Fetching AKS clusters from Azure API for subscription: {}", subscriptionId);

        CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Azure account not found: " + subscriptionId));

        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);

            // A cluster counts as connected if EITHER data source is live.
            //
            // 1. prometheus_url set — the kubeconfig-style path, where the XamOps server
            //    queries the cluster's Prometheus directly.
            // 2. the agent has pushed data — the only path AKS onboarding actually creates.
            //
            // Checking only (1) meant every agent-onboarded cluster showed "Deploy Agent"
            // forever, even while its agent was pushing every interval, because nothing
            // in the guide or the UI ever sets prometheus_url for AKS.
            Set<String> connectedNames = kubernetesClusterRepository
                    .findByCloudAccountId(account.getId())
                    .stream()
                    .filter(c -> c.getPrometheusUrl() != null && !c.getPrometheusUrl().isBlank())
                    .map(KubernetesCluster::getClusterName)
                    .collect(Collectors.toCollection(HashSet::new));

            // Agent data rows are kept for 14 days, so their mere existence said "Connected" for two
            // weeks after an agent stopped. Connected now also needs a check-in within 15 minutes,
            // the same liveness rule the EKS side uses (KubernetesClusterConfig.isAgentActive).
            Set<String> staleNames = new HashSet<>();
            kubernetesClusterConfigRepository.findByCloudAccountId(account.getId())
                    .forEach(cfg -> {
                        try {
                            if (agentDataTransformerService.hasAgentData(cfg.getId())) {
                                if (cfg.isAgentActive()) {
                                    connectedNames.add(cfg.getClusterName());
                                } else {
                                    staleNames.add(cfg.getClusterName());
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Could not check agent data for cluster {}: {}",
                                    cfg.getClusterName(), e.getMessage());
                        }
                    });

            List<AksClusterDto> result = StreamSupport
                    .stream(azure.kubernetesClusters().list().spliterator(), false)
                    .map(aks -> {
                        try {
                            int nodeCount = aks.agentPools().values().stream()
                                    .mapToInt(pool -> pool.count())
                                    .sum();

                            String state = "Unknown";
                            try {
                                if (aks.innerModel().provisioningState() != null) {
                                    state = aks.innerModel().provisioningState().toString();
                                }
                            } catch (Exception ignored) {}

                            String power = null;
                            try {
                                power = AksDashboardService.powerStateCode(aks.powerState());
                            } catch (Exception ignored) {}

                            boolean connected = connectedNames.contains(aks.name());
                            return AksClusterDto.builder()
                                    .name(aks.name())
                                    .resourceGroup(aks.resourceGroupName())
                                    .location(aks.regionName())
                                    .kubernetesVersion(aks.version())
                                    .provisioningState(state)
                                    .powerState(power)
                                    .nodeCount(nodeCount)
                                    .connected(connected)
                                    .agentStale(!connected && staleNames.contains(aks.name()))
                                    .build();
                        } catch (Exception e) {
                            log.warn("Failed to map AKS cluster {}: {}", aks.name(), e.getMessage());
                            return AksClusterDto.builder()
                                    .name(aks.name())
                                    .resourceGroup(aks.resourceGroupName())
                                    .location(aks.regionName())
                                    .provisioningState("Unknown")
                                    .nodeCount(0)
                                    .connected(false)
                                    .build();
                        }
                    })
                    .collect(Collectors.toList());

            redisCacheService.put(cacheKey, result, CACHE_TTL_MINUTES);
            return result;

        } catch (Exception e) {
            // Returning an empty list here made a permissions or network failure read as
            // "No AKS clusters found". Fail instead, so the page can say the list could not be read.
            log.error("Failed to fetch AKS clusters for subscription {}: {}", subscriptionId, e.getMessage());
            throw new IllegalStateException("Could not list AKS clusters from Azure: " + e.getMessage(), e);
        }
    }

    /** Drops the cached cluster list, e.g. after a cluster turned out to be deleted in Azure. */
    public void evictClusters(String subscriptionId) {
        redisCacheService.evict(CACHE_PREFIX + subscriptionId);
    }

    public void savePrometheusUrl(String subscriptionId, String clusterName, String prometheusUrl) {
        CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Azure account not found: " + subscriptionId));

        Optional<KubernetesCluster> existing = kubernetesClusterRepository
                .findByCloudAccount_IdAndClusterName(account.getId(), clusterName);

        KubernetesCluster cluster = existing.orElse(new KubernetesCluster());
        cluster.setClusterName(clusterName);
        cluster.setCloudAccount(account);
        cluster.setPrometheusUrl(prometheusUrl);
        kubernetesClusterRepository.save(cluster);
        log.info("Saved prometheusUrl for AKS cluster {} (subscription {})", clusterName, subscriptionId);
    }
}
