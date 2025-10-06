package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.*;
import com.xammer.cloud.repository.CloudAccountRepository;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class EksService {

    private static final Logger logger = LoggerFactory.getLogger(EksService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService;
    private final String configuredRegion;

    @Autowired
    public EksService(CloudAccountRepository cloudAccountRepository, AwsClientProvider awsClientProvider, @Lazy CloudListService cloudListService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.cloudListService = cloudListService;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    private KubernetesClient getClientFromKubeconfig(String kubeConfigYaml) {
        Config config = Config.fromKubeconfig(kubeConfigYaml);
        return new DefaultKubernetesClient(config);
    }

    @Async
    @Cacheable(value = "eksClusters", key = "#accountId", unless = "#result.get().isEmpty()")
    public CompletableFuture<List<K8sClusterInfo>> getEksClusterInfo(String accountId, boolean forceRefresh) {
        CloudAccount account = getAccount(accountId);
        logger.info("Fetching EKS cluster list across all active regions for account {}...", account.getAwsAccountId());

        return cloudListService.getRegionStatusForAccount(account, forceRefresh).thenCompose(activeRegions -> {
            List<CompletableFuture<List<K8sClusterInfo>>> futures = activeRegions.stream()
                .map(region -> CompletableFuture.supplyAsync(() -> {
                    try {
                        EksClient eks = awsClientProvider.getEksClient(account, region.getRegionId());
                        return eks.listClusters().clusters().stream().map(name -> {
                            try {
                                Cluster cluster = eks.describeCluster(b -> b.name(name)).cluster();
                                boolean isConnected = checkContainerInsightsStatus(account, name, region.getRegionId());
                                return new K8sClusterInfo(name, cluster.statusAsString(), cluster.version(), region.getRegionId(), isConnected);
                            } catch (Exception e) {
                                logger.error("Failed to describe EKS cluster {} in region {}", name, region.getRegionId(), e);
                                return null;
                            }
                        }).filter(Objects::nonNull).collect(Collectors.toList());
                    } catch (Exception e) {
                        logger.error("Could not list EKS clusters in region {} for account {}", region.getRegionId(), account.getAwsAccountId(), e);
                        return Collections.<K8sClusterInfo>emptyList();
                    }
                }))
                .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .collect(Collectors.toList()));
        });
    }

    @Async
    @Cacheable(value = "k8sNodes", key = "{#accountId, #clusterName}", unless = "#result.get().isEmpty()")
    public CompletableFuture<List<K8sNodeInfo>> getK8sNodes(String accountId, String clusterName, boolean forceRefresh) {
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
                formatPercentage(cpuMemory.getOrDefault("memory", 0.0))
            );
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
                    .dimensions(DimensionFilter.builder().name("ClusterName").value(clusterName).build())
            );
            return metricsResponse.metrics().stream()
                    .flatMap(metric -> metric.dimensions().stream())
                    .filter(dim -> "NodeName".equals(dim.name()))
                    .map(Dimension::value)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching node names from CloudWatch for cluster {}: {}", clusterName, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private Map<String, Double> fetchCpuMemoryMetrics(CloudAccount account, String clusterName, String nodeName, String region) {
        try {
            CloudWatchClient cloudWatch = awsClientProvider.getCloudWatchClient(account, region);
            Instant now = Instant.now();
            Instant start = now.minus(5, ChronoUnit.MINUTES);

            MetricDataQuery cpuQuery = MetricDataQuery.builder()
                    .id("cpu").metricStat(MetricStat.builder()
                        .metric(Metric.builder().namespace("ContainerInsights").metricName("node_cpu_utilization")
                                .dimensions(Dimension.builder().name("ClusterName").value(clusterName).build(),
                                        Dimension.builder().name("NodeName").value(nodeName).build()).build())
                        .period(60).stat("Average").build()).returnData(true).build();
            MetricDataQuery memQuery = MetricDataQuery.builder()
                    .id("memory").metricStat(MetricStat.builder()
                        .metric(Metric.builder().namespace("ContainerInsights").metricName("node_memory_utilization")
                                .dimensions(Dimension.builder().name("ClusterName").value(clusterName).build(),
                                        Dimension.builder().name("NodeName").value(nodeName).build()).build())
                        .period(60).stat("Average").build()).returnData(true).build();

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
        if (value == null) return "0.00 %";
        return String.format("%.2f %%", value);
    }
}