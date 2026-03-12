package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.container.v1.Cluster;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.dto.gcp.GkeClusterDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GkeService {

    private static final Logger log = LoggerFactory.getLogger(GkeService.class);
    private static final String CACHE_PREFIX = "GKE_CLUSTERS::";
    private static final int CACHE_TTL_MINUTES = 60;

    private final GcpClientProvider gcpClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final KubernetesClusterRepository kubernetesClusterRepository;
    private final RedisCacheService redisCacheService;

    public GkeService(GcpClientProvider gcpClientProvider,
                      CloudAccountRepository cloudAccountRepository,
                      KubernetesClusterRepository kubernetesClusterRepository,
                      RedisCacheService redisCacheService) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.kubernetesClusterRepository = kubernetesClusterRepository;
        this.redisCacheService = redisCacheService;
    }

    /**
     * Fetches all GKE clusters for a given GCP project.
     * Results are Redis-cached for 60 minutes.
     *
     * @param gcpProjectId The GCP project ID (e.g. "my-project")
     * @param forceRefresh When true, bypasses the Redis cache and fetches from GCP API.
     */
    public List<GkeClusterDto> getGkeClusters(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + gcpProjectId;

        if (forceRefresh) {
            redisCacheService.evict(cacheKey);
        } else {
            Optional<List<GkeClusterDto>> cached = redisCacheService.get(
                    cacheKey, new TypeReference<List<GkeClusterDto>>() {});
            if (cached.isPresent()) {
                log.info("Redis cache HIT for GKE clusters: project={}", gcpProjectId);
                return cached.get();
            }
        }

        log.info("Fetching GKE clusters from GCP API for project: {}", gcpProjectId);

        Optional<ClusterManagerClient> clientOpt = gcpClientProvider.getClusterManagerClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.warn("Could not obtain ClusterManagerClient for project: {}", gcpProjectId);
            return Collections.emptyList();
        }

        Set<String> connectedClusterNames = getConnectedClusterNames(gcpProjectId);

        try (ClusterManagerClient client = clientOpt.get()) {
            String parent = "projects/" + gcpProjectId + "/locations/-";
            List<Cluster> clusters = client.listClusters(parent).getClustersList();
            log.info("Found {} GKE cluster(s) for project: {}", clusters.size(), gcpProjectId);

            List<GkeClusterDto> result = clusters.stream()
                    .map(c -> mapToDto(c, connectedClusterNames))
                    .collect(Collectors.toList());

            redisCacheService.put(cacheKey, result, CACHE_TTL_MINUTES);
            return result;

        } catch (Exception e) {
            log.error("Error fetching GKE clusters for project: {}", gcpProjectId, e);
            return Collections.emptyList();
        }
    }

    private Set<String> getConnectedClusterNames(String gcpProjectId) {
        return cloudAccountRepository.findByGcpProjectId(gcpProjectId)
                .map(CloudAccount::getId)
                .map(kubernetesClusterRepository::findByCloudAccountId)
                .orElse(Collections.emptyList())
                .stream()
                .map(KubernetesCluster::getClusterName)
                .collect(Collectors.toSet());
    }

    private GkeClusterDto mapToDto(Cluster cluster, Set<String> connectedClusterNames) {
        GkeClusterDto dto = new GkeClusterDto();
        dto.setName(cluster.getName());
        dto.setLocation(cluster.getLocation());
        dto.setStatus(cluster.getStatus().name());
        dto.setVersion(cluster.getCurrentMasterVersion());
        dto.setCurrentNodeVersion(cluster.getCurrentNodeVersion());
        dto.setEndpoint(cluster.getEndpoint());
        dto.setLoggingService(cluster.getLoggingService());
        dto.setMonitoringService(cluster.getMonitoringService());
        dto.setNodeCount(cluster.getCurrentNodeCount());
        dto.setMasterAuthorizedNetworksEnabled(
                cluster.hasMasterAuthorizedNetworksConfig()
                && cluster.getMasterAuthorizedNetworksConfig().getEnabled());
        dto.setConnected(connectedClusterNames.contains(cluster.getName()));

        String location = cluster.getLocation();
        if (location != null && location.matches(".*-[a-z]$")) {
            dto.setZone(location);
        } else {
            dto.setRegion(location);
        }

        return dto;
    }
}
