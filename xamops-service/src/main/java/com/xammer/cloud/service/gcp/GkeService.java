package com.xammer.cloud.service.gcp;

import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.container.v1.Cluster;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.dto.gcp.GkeClusterDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
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

    private final GcpClientProvider gcpClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final KubernetesClusterRepository kubernetesClusterRepository;

    public GkeService(GcpClientProvider gcpClientProvider,
                      CloudAccountRepository cloudAccountRepository,
                      KubernetesClusterRepository kubernetesClusterRepository) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.kubernetesClusterRepository = kubernetesClusterRepository;
    }

    /**
     * Fetches all GKE clusters for a given GCP project and marks each as
     * connected/disconnected based on whether Prometheus monitoring is registered.
     *
     * @param gcpProjectId The GCP project ID (e.g. "xamops")
     * @param forceRefresh Ignored for now; reserved for future cache busting
     * @return List of GkeClusterDto ready to be returned to the frontend
     */
    public List<GkeClusterDto> getGkeClusters(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching GKE clusters for project: {}", gcpProjectId);

        Optional<ClusterManagerClient> clientOpt = gcpClientProvider.getClusterManagerClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.warn("Could not obtain ClusterManagerClient for project: {}", gcpProjectId);
            return Collections.emptyList();
        }

        // Determine which cluster names are "connected" (have monitoring registered)
        Set<String> connectedClusterNames = getConnectedClusterNames(gcpProjectId);

        try (ClusterManagerClient client = clientOpt.get()) {
            String parent = "projects/" + gcpProjectId + "/locations/-";
            List<Cluster> clusters = client.listClusters(parent).getClustersList();
            log.info("Found {} GKE cluster(s) for project: {}", clusters.size(), gcpProjectId);

            return clusters.stream()
                    .map(c -> mapToDto(c, connectedClusterNames))
                    .collect(Collectors.toList());
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

        // Split location into zone vs region
        String location = cluster.getLocation();
        if (location != null && location.matches(".*-[a-z]$")) {
            dto.setZone(location);
        } else {
            dto.setRegion(location);
        }

        return dto;
    }
}
