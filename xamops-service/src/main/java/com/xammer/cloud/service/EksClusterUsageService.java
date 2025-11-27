package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.dto.k8s.ClusterUsageDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class EksClusterUsageService {

    private static final Logger logger = LoggerFactory.getLogger(EksClusterUsageService.class);
    
    private final PrometheusService prometheusService;
    private final CloudAccountRepository cloudAccountRepository;
    private final KubernetesClusterRepository kubernetesClusterRepository;

    public EksClusterUsageService(PrometheusService prometheusService,
                                  CloudAccountRepository cloudAccountRepository,
                                  KubernetesClusterRepository kubernetesClusterRepository) {
        this.prometheusService = prometheusService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.kubernetesClusterRepository = kubernetesClusterRepository;
    }

    public CompletableFuture<ClusterUsageDto> getClusterUsage(String accountId, String clusterName, String region) {
        logger.info("Fetching EKS cluster usage from Prometheus for cluster {}", clusterName);

        return CompletableFuture.supplyAsync(() -> {
            ClusterUsageDto dto = new ClusterUsageDto();
            try {
                // 1. Resolve Prometheus URL dynamically (just like AutomationService does)
                String prometheusUrl = resolvePrometheusUrl(accountId, clusterName);

                // 2. Fetch Metrics using the URL
                double cpuUsage = prometheusService.getClusterCpuUsage(prometheusUrl, clusterName);
                dto.setCpuUsage(cpuUsage);

                double memUsage = prometheusService.getClusterMemoryUsage(prometheusUrl, clusterName);
                dto.setMemoryUsage(memUsage);

                int nodeCount = prometheusService.getClusterNodeCount(prometheusUrl, clusterName);
                dto.setNodeCount(nodeCount);

                // Defaults
                dto.setPodCount(0); 
                dto.setCpuTotal(0);
                dto.setCpuRequests(0);
                dto.setCpuLimits(0);
                dto.setMemoryTotal(0);
                dto.setMemoryRequests(0);
                dto.setMemoryLimits(0);

            } catch (Exception e) {
                logger.error("Failed to fetch cluster usage from Prometheus for cluster {}", clusterName, e);
            }
            return dto;
        });
    }

    private String resolvePrometheusUrl(String awsAccountId, String clusterName) {
        // Find account 
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(awsAccountId)
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Cloud Account not found for ID: " + awsAccountId));

        // Find cluster configuration
        return kubernetesClusterRepository.findByCloudAccountAndClusterName(account, clusterName)
                .map(KubernetesCluster::getPrometheusUrl)
                .orElseThrow(() -> new RuntimeException("Prometheus URL configuration not found for cluster: " + clusterName));
    }
}