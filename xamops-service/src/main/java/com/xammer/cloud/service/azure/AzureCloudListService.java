package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AzureCloudListService {

    private static final Logger log = LoggerFactory.getLogger(AzureCloudListService.class);
    private final AzureClientProvider clientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    public AzureCloudListService(AzureClientProvider clientProvider, CloudAccountRepository cloudAccountRepository) {
        this.clientProvider = clientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    public List<DashboardData.ServiceGroupDto> getAzureResources(String accountId) {
        log.info("Fetching cloudlist data for Azure account ID: {}", accountId);
        try {
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Azure account not found for Subscription ID: " + accountId));
            AzureResourceManager azure = clientProvider.getAzureClient(accountId);

            List<ResourceDto> allResources = new ArrayList<>();

            // Virtual Machines
            azure.virtualMachines().list().forEach(vm -> {
                ResourceDto dto = new ResourceDto();
                dto.setId(vm.id());
                dto.setName(vm.name());
                dto.setType("Virtual Machines");
                dto.setRegion(vm.regionName());
                dto.setState(vm.powerState().toString());
                allResources.add(dto);
            });

            // Storage Accounts
            azure.storageAccounts().list().forEach(sa -> {
                ResourceDto dto = new ResourceDto();
                dto.setId(sa.id());
                dto.setName(sa.name());
                dto.setType("Storage Accounts");
                dto.setRegion(sa.regionName());
                dto.setState(sa.innerModel().provisioningState().toString());
                allResources.add(dto);
            });

            // App Services (Web Apps)
            azure.webApps().list().forEach(app -> {
                ResourceDto dto = new ResourceDto();
                dto.setId(app.id());
                dto.setName(app.name());
                dto.setType("App Services");
                dto.setRegion(app.regionName());
                dto.setState(app.state());
                allResources.add(dto);
            });

            // Kubernetes Services (AKS)
            azure.kubernetesClusters().list().forEach(aks -> {
                ResourceDto dto = new ResourceDto();
                dto.setId(aks.id());
                dto.setName(aks.name());
                dto.setType("Kubernetes Services");
                dto.setRegion(aks.regionName());
                dto.setState(aks.innerModel().provisioningState().toString());
                allResources.add(dto);
            });


            // Group resources by service type
            Map<String, List<ResourceDto>> groupedResources = allResources.stream()
                    .collect(Collectors.groupingBy(ResourceDto::getType));

            // Convert to ServiceGroupDto
            return groupedResources.entrySet().stream()
                    .map(entry -> {
                        DashboardData.ServiceGroupDto groupDto = new DashboardData.ServiceGroupDto();
                        groupDto.setServiceType(entry.getKey());
                        groupDto.setResources(entry.getValue());
                        return groupDto;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get cloudlist data for account {}", accountId, e);
            throw new RuntimeException("Failed to get cloudlist data", e);
        }
    }
}