package com.xammer.cloud.service;

import com.xammer.cloud.service.EksTokenGenerator; // Ensure this exists
import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.K8sClusterInfo;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.repository.CloudAccountRepository;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Cluster;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class EksService {

    private static final Logger logger = LoggerFactory.getLogger(EksService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService;
    private final RedisCacheService redisCacheService;
    private final String configuredRegion;

    @Autowired
    private EksTokenGenerator eksTokenGenerator;

    @Autowired
    public EksService(CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            @Lazy CloudListService cloudListService,
            RedisCacheService redisCacheService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.cloudListService = cloudListService;
        this.redisCacheService = redisCacheService;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    @Async
    public CompletableFuture<List<K8sClusterInfo>> getEksClusterInfo(String accountId, boolean forceRefresh) {
        // --- DEBUG: Log method entry ---
        logger.info("DEBUG K8S: getEksClusterInfo called for accountId: {}, forceRefresh: {}", accountId, forceRefresh);

        String cacheKey = "eksClusters::" + accountId;

        if (forceRefresh) {
            logger.info("DEBUG K8S: Force refresh is true. Evicting cache for key: {}", cacheKey);
            redisCacheService.evict(cacheKey);
        } else {
            logger.info("DEBUG K8S: Checking cache for key: {}", cacheKey);
            Optional<List<K8sClusterInfo>> cachedData = redisCacheService.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                // --- DEBUG: Log cache hit ---
                logger.info("DEBUG K8S: Cache hit for key: {}. Returning cached data.", cacheKey);
                return CompletableFuture.completedFuture(cachedData.get());
            }
            // --- DEBUG: Log cache miss ---
            logger.info("DEBUG K8S: Cache miss for key: {}.", cacheKey);
        }

        CloudAccount account = getAccount(accountId);
        logger.info("DEBUG K8S: Fetching EKS cluster list from AWS for account {}...", account.getAwsAccountId());

        return cloudListService.getRegionStatusForAccount(account, forceRefresh).thenCompose(activeRegions -> {
            // --- DEBUG: Log active regions ---
            logger.info("DEBUG K8S: Found {} active regions: {}", activeRegions.size(),
                    activeRegions.stream().map(r -> r.getRegionId()).collect(Collectors.toList()));

            List<CompletableFuture<List<K8sClusterInfo>>> futures = activeRegions.stream()
                    .map(region -> CompletableFuture.supplyAsync(() -> {
                        try {
                            EksClient eks = awsClientProvider.getEksClient(account, region.getRegionId());
                            // --- DEBUG: Log before listing clusters in a region ---
                            logger.info("DEBUG K8S: Listing clusters in region: {}", region.getRegionId());
                            List<K8sClusterInfo> clustersInRegion = eks.listClusters().clusters().stream().map(name -> {
                                try {
                                    Cluster cluster = eks.describeCluster(b -> b.name(name)).cluster();
                                    boolean isConnected = checkContainerInsightsStatus(account, name,
                                            region.getRegionId());
                                    return new K8sClusterInfo(name, cluster.statusAsString(), cluster.version(),
                                            region.getRegionId(), isConnected);
                                } catch (Exception e) {
                                    logger.error("DEBUG K8S: Failed to describe EKS cluster {} in region {}", name,
                                            region.getRegionId(), e);
                                    return null;
                                }
                            }).filter(Objects::nonNull).collect(Collectors.toList());
                            // --- DEBUG: Log after listing clusters in a region ---
                            logger.info("DEBUG K8S: Found {} clusters in region {}: {}", clustersInRegion.size(),
                                    region.getRegionId(), clustersInRegion.stream().map(K8sClusterInfo::getName)
                                            .collect(Collectors.toList()));
                            return clustersInRegion;

                        } catch (Exception e) {
                            logger.error("DEBUG K8S: Could not list EKS clusters in region {} for account {}",
                                    region.getRegionId(), account.getAwsAccountId(), e);
                            return Collections.<K8sClusterInfo>emptyList();
                        }
                    }))
                    .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        List<K8sClusterInfo> clusterInfos = futures.stream()
                                .map(CompletableFuture::join)
                                .flatMap(List::stream)
                                .collect(Collectors.toList());

                        // --- DEBUG: Log final combined cluster list ---
                        logger.info("DEBUG K8S: Total clusters found: {}. Names: {}", clusterInfos.size(),
                                clusterInfos.stream().map(K8sClusterInfo::getName).collect(Collectors.toList()));

                        if (!clusterInfos.isEmpty()) {
                            // --- DEBUG: Log caching action ---
                            logger.info("DEBUG K8S: Saving {} clusters to cache with key: {}", clusterInfos.size(),
                                    cacheKey);
                            redisCacheService.put(cacheKey, clusterInfos, 3600); // Cache for 1 hour
                        } else {
                            logger.warn("DEBUG K8S: No clusters found. Not caching an empty list.");
                        }

                        return clusterInfos;
                    });
        });
    }

    // --- No changes needed below this line, but I'm including the full file for
    // completeness ---

    private KubernetesClient getClientFromKubeconfig(String kubeConfigYaml) {
        Config config = Config.fromKubeconfig(kubeConfigYaml);
        return new DefaultKubernetesClient(config);
    }

    @Async
    public CompletableFuture<List<K8sNodeInfo>> getK8sNodes(String accountId, String clusterName,
            boolean forceRefresh) {
        logger.info("Fetching nodes for cluster: {} in account: {}", clusterName, accountId);
        CloudAccount account = getAccount(accountId);
        String region;
        List<String> nodeNames;

        try {
            EksClient eks = awsClientProvider.getEksClient(account, configuredRegion);
            Cluster cluster = eks.describeCluster(c -> c.name(clusterName)).cluster();
            region = cluster.arn().split(":")[3];
            nodeNames = fetchNodeNames(account, clusterName, region);
        } catch (Exception e) {
            logger.error("Error fetching nodes for cluster {}: {}", clusterName, e.getMessage(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        List<K8sNodeInfo> nodeInfos = nodeNames.stream().map(nodeName -> {
            Map<String, Double> cpuMemory = fetchCpuMemoryMetrics(account, clusterName, nodeName, region);
            return new K8sNodeInfo(
                    nodeName, "Ready", "unknown", "unknown",
                    "unknown", "unknown",
                    formatPercentage(cpuMemory.getOrDefault("cpu", 0.0)),
                    formatPercentage(cpuMemory.getOrDefault("memory", 0.0)));
        }).collect(Collectors.toList());

        return CompletableFuture.completedFuture(nodeInfos);
    }

    private boolean checkContainerInsightsStatus(CloudAccount account, String clusterName, String region) {
        logger.info("Checking Container Insights status for cluster {} in region {}", clusterName, region);
        try {
            CloudWatchLogsClient logsClient = awsClientProvider.getCloudWatchLogsClient(account, region);
            String logGroupName = "/aws/containerinsights/" + clusterName + "/performance";
            logsClient.describeLogGroups(req -> req.logGroupNamePrefix(logGroupName));
            logger.info("Container Insights is CONNECTED for cluster {}", clusterName);
            return true;
        } catch (Exception e) {
            logger.info("Container Insights is NOT CONNECTED for cluster {}", clusterName);
            return false;
        }
    }

    private List<String> fetchNodeNames(CloudAccount account, String clusterName, String region) {
        try {
            CloudWatchClient cloudWatch = awsClientProvider.getCloudWatchClient(account, region);
            ListMetricsResponse metricsResponse = cloudWatch.listMetrics(b -> b
                    .namespace("ContainerInsights")
                    .metricName("node_cpu_utilization")
                    .dimensions(DimensionFilter.builder().name("ClusterName").value(clusterName).build()));
            return metricsResponse.metrics().stream()
                    .flatMap(metric -> metric.dimensions().stream())
                    .filter(dim -> "NodeName".equals(dim.name()))
                    .map(Dimension::value)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching node names from CloudWatch for cluster {}: {}", clusterName, e.getMessage(),
                    e);
            return Collections.emptyList();
        }
    }

    private Map<String, Double> fetchCpuMemoryMetrics(CloudAccount account, String clusterName, String nodeName,
            String region) {
        try {
            CloudWatchClient cloudWatch = awsClientProvider.getCloudWatchClient(account, region);
            Instant now = Instant.now();
            Instant start = now.minus(5, ChronoUnit.MINUTES);

            MetricDataQuery cpuQuery = MetricDataQuery.builder()
                    .id("cpu").metricStat(MetricStat.builder()
                            .metric(Metric.builder().namespace("ContainerInsights").metricName("node_cpu_utilization")
                                    .dimensions(Dimension.builder().name("ClusterName").value(clusterName).build(),
                                            Dimension.builder().name("NodeName").value(nodeName).build())
                                    .build())
                            .period(60).stat("Average").build())
                    .returnData(true).build();
            MetricDataQuery memQuery = MetricDataQuery.builder()
                    .id("memory").metricStat(MetricStat.builder()
                            .metric(Metric.builder().namespace("ContainerInsights")
                                    .metricName("node_memory_utilization")
                                    .dimensions(Dimension.builder().name("ClusterName").value(clusterName).build(),
                                            Dimension.builder().name("NodeName").value(nodeName).build())
                                    .build())
                            .period(60).stat("Average").build())
                    .returnData(true).build();

            GetMetricDataRequest request = GetMetricDataRequest.builder()
                    .startTime(start).endTime(now).metricDataQueries(cpuQuery, memQuery).build();
            GetMetricDataResponse response = cloudWatch.getMetricData(request);
            Map<String, Double> results = new HashMap<>();
            for (MetricDataResult result : response.metricDataResults()) {
                if (result.values() != null && !result.values().isEmpty()) {
                    results.put(result.id(), result.values().get(0));
                }
            }
            return results;
        } catch (Exception e) {
            logger.error("Error fetching CPU/Memory metrics from CloudWatch: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    private String formatPercentage(Double value) {
        if (value == null)
            return "0.00 %";
        return String.format("%.2f %%", value);
    }

    // --- NEW KARPENTER METHODS START ---

    /**
     * Get a Kubernetes client authenticated to a customer's EKS cluster.
     */
    public KubernetesClient getKubernetesClientForCustomerCluster(String accountId, String clusterName, String region) {
        logger.info("🔗 [EksService] Creating K8s client for cluster: {} in account: {} region: {}", clusterName,
                accountId, region);
        try {
            CloudAccount account = getAccount(accountId);
            EksClient eks = awsClientProvider.getEksClient(account, region);

            // Get API endpoint and CA certificate
            Cluster cluster = eks.describeCluster(b -> b.name(clusterName)).cluster();
            if (cluster == null || cluster.endpoint() == null) {
                throw new RuntimeException("EKS cluster not found or endpoint unavailable: " + clusterName);
            }

            String apiServer = cluster.endpoint();
            String caCertData = cluster.certificateAuthority().data();

            // Generate authentication token
            String token = eksTokenGenerator.generateTokenForCluster(accountId, clusterName, region);

            // Create K8s client
            return new com.xammer.cloud.service.K8sClientFactory().createFromComponents(apiServer, caCertData, token);

        } catch (Exception e) {
            logger.error("❌ Failed to create K8s client for cluster: {}", clusterName, e);
            throw new RuntimeException("Failed to create K8s client for cluster: " + clusterName, e);
        }
    }

    public boolean hasOIDCProvider(String accountId, String clusterName, String region) {
        try {
            CloudAccount account = getAccount(accountId);
            EksClient eks = awsClientProvider.getEksClient(account, region);
            Cluster cluster = eks.describeCluster(b -> b.name(clusterName)).cluster();

            if (cluster != null && cluster.identity() != null && cluster.identity().oidc() != null) {
                return cluster.identity().oidc().issuer() != null;
            }
            return false;
        } catch (Exception e) {
            logger.error("Error checking OIDC status", e);
            return false;
        }
    }

    public String getOIDCIssuerUrl(String accountId, String clusterName, String region) {
        try {
            CloudAccount account = getAccount(accountId);
            EksClient eks = awsClientProvider.getEksClient(account, region);
            return eks.describeCluster(b -> b.name(clusterName)).cluster().identity().oidc().issuer();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get OIDC issuer URL", e);
        }
    }
    // --- NEW KARPENTER METHODS END ---
}