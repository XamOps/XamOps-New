package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.dto.azure.AksClusterDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
@Service
@RequiredArgsConstructor
public class AksService {

    private final AzureClientProvider azureClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final KubernetesClusterRepository kubernetesClusterRepository;

    public List<AksClusterDto> getClusters(String subscriptionId) {
        try {
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(subscriptionId)
                    .orElseThrow(() -> new RuntimeException("Azure account not found: " + subscriptionId));

            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);

            Set<String> connectedNames = kubernetesClusterRepository
                    .findByCloudAccountId(account.getId())
                    .stream()
                    .filter(c -> c.getPrometheusUrl() != null)
                    .map(KubernetesCluster::getClusterName)
                    .collect(Collectors.toSet());

            return StreamSupport.stream(azure.kubernetesClusters().list().spliterator(), false)
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

                            return AksClusterDto.builder()
                                    .name(aks.name())
                                    .resourceGroup(aks.resourceGroupName())
                                    .location(aks.regionName())
                                    .kubernetesVersion(aks.version())
                                    .provisioningState(state)
                                    .nodeCount(nodeCount)
                                    .connected(connectedNames.contains(aks.name()))
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

        } catch (Exception e) {
            log.error("Failed to fetch AKS clusters for subscription {}: {}", subscriptionId, e.getMessage());
            return Collections.emptyList();
        }
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
