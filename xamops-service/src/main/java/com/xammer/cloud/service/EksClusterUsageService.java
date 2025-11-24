package com.xammer.cloud.service;

import com.xammer.cloud.dto.k8s.ClusterUsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class EksClusterUsageService {

    private static final Logger logger = LoggerFactory.getLogger(EksClusterUsageService.class);
    
    private final PrometheusService prometheusService;

    public EksClusterUsageService(PrometheusService prometheusService) {
        this.prometheusService = prometheusService;
    }

    /**
     * Fetches aggregated cluster usage statistics from Prometheus.
     * * @param accountId   Legacy parameter (unused in Prometheus impl but kept for interface compatibility)
     * @param clusterName The name of the EKS cluster
     * @param region      Legacy parameter (unused in Prometheus impl)
     * @return CompletableFuture containing the Usage DTO
     */
    public CompletableFuture<ClusterUsageDto> getClusterUsage(String accountId, String clusterName, String region) {
        logger.info("Fetching EKS cluster usage from Prometheus for cluster {}", clusterName);

        return CompletableFuture.supplyAsync(() -> {
            ClusterUsageDto dto = new ClusterUsageDto();
            try {
                // 1. CPU Usage %
                double cpuUsage = prometheusService.getClusterCpuUsage(clusterName);
                dto.setCpuUsage(cpuUsage);

                // 2. Memory Usage %
                double memUsage = prometheusService.getClusterMemoryUsage(clusterName);
                dto.setMemoryUsage(memUsage);

                // 3. Node Count
                int nodeCount = prometheusService.getClusterNodeCount(clusterName);
                dto.setNodeCount(nodeCount);

                // 4. Pod Count
                // Currently set to 0 as we don't have a direct 'kube_pod_info' metric mapped yet.
                // The frontend list will still populate individual pods via EksAutomationService.
                dto.setPodCount(0); 

                // Set placeholders for fields not currently tracked in Prometheus
                dto.setCpuTotal(0);
                dto.setCpuRequests(0);
                dto.setCpuLimits(0);
                dto.setMemoryTotal(0);
                dto.setMemoryRequests(0);
                dto.setMemoryLimits(0);

            } catch (Exception e) {
                logger.error("Failed to fetch cluster usage from Prometheus for cluster {}", clusterName, e);
                // Return empty DTO on failure to avoid breaking the UI
            }
            return dto;
        });
    }
}