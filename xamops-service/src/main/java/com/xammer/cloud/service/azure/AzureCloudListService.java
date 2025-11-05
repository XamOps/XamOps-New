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
            try {
                log.debug("Fetching Virtual Machines for account {}", accountId);
                azure.virtualMachines().list().forEach(vm -> {
                    try {
                        ResourceDto dto = new ResourceDto();
                        dto.setId(vm.id());
                        dto.setName(vm.name());
                        dto.setType("Virtual Machines");
                        dto.setRegion(vm.regionName());
                        dto.setState(vm.powerState() != null ? vm.powerState().toString() : "Unknown");
                        allResources.add(dto);
                    } catch (Exception e) {
                        log.warn("Failed to fetch VM {}: {}", vm.name(), e.getMessage());
                    }
                });
                log.debug("Found {} Virtual Machines", allResources.stream().filter(r -> "Virtual Machines".equals(r.getType())).count());
            } catch (Exception e) {
                log.error("Failed to list Virtual Machines: {}", e.getMessage());
            }

            // Storage Accounts
            try {
                log.debug("Fetching Storage Accounts for account {}", accountId);
                azure.storageAccounts().list().forEach(sa -> {
                    try {
                        ResourceDto dto = new ResourceDto();
                        dto.setId(sa.id());
                        dto.setName(sa.name());
                        dto.setType("Storage Accounts");
                        dto.setRegion(sa.regionName());
                        dto.setState(sa.innerModel().provisioningState() != null ? 
                                    sa.innerModel().provisioningState().toString() : "Unknown");
                        allResources.add(dto);
                    } catch (Exception e) {
                        log.warn("Failed to fetch Storage Account {}: {}", sa.name(), e.getMessage());
                    }
                });
                log.debug("Found {} Storage Accounts", allResources.stream().filter(r -> "Storage Accounts".equals(r.getType())).count());
            } catch (Exception e) {
                log.error("Failed to list Storage Accounts: {}", e.getMessage());
            }

            // App Services (Web Apps)
            try {
                log.debug("Fetching App Services for account {}", accountId);
                azure.webApps().list().forEach(app -> {
                    try {
                        ResourceDto dto = new ResourceDto();
                        dto.setId(app.id());
                        dto.setName(app.name());
                        dto.setType("App Services");
                        dto.setRegion(app.regionName());
                        dto.setState(app.state() != null ? app.state() : "Unknown");
                        allResources.add(dto);
                    } catch (Exception e) {
                        log.warn("Failed to fetch App Service {}: {}", app.name(), e.getMessage());
                    }
                });
                log.debug("Found {} App Services", allResources.stream().filter(r -> "App Services".equals(r.getType())).count());
            } catch (Exception e) {
                log.error("Failed to list App Services: {}", e.getMessage());
            }

            // Kubernetes Services (AKS)
            try {
                log.debug("Fetching Kubernetes Services for account {}", accountId);
                azure.kubernetesClusters().list().forEach(aks -> {
                    try {
                        ResourceDto dto = new ResourceDto();
                        dto.setId(aks.id());
                        dto.setName(aks.name());
                        dto.setType("Kubernetes Services");
                        dto.setRegion(aks.regionName());
                        dto.setState(aks.innerModel().provisioningState() != null ? 
                                    aks.innerModel().provisioningState().toString() : "Unknown");
                        allResources.add(dto);
                    } catch (Exception e) {
                        log.warn("Failed to fetch AKS cluster {}: {}", aks.name(), e.getMessage());
                    }
                });
                log.debug("Found {} Kubernetes Services", allResources.stream().filter(r -> "Kubernetes Services".equals(r.getType())).count());
            } catch (Exception e) {
                log.error("Failed to list Kubernetes Services: {}", e.getMessage());
            }

            // SQL Databases
            try {
                log.debug("Fetching SQL Databases for account {}", accountId);
                azure.sqlServers().list().forEach(server -> {
                    try {
                        server.databases().list().forEach(db -> {
                            try {
                                if (!"master".equalsIgnoreCase(db.name())) { // Skip system database
                                    ResourceDto dto = new ResourceDto();
                                    dto.setId(db.id());
                                    dto.setName(server.name() + "/" + db.name());
                                    dto.setType("SQL Databases");
                                    dto.setRegion(server.regionName());
                                    dto.setState(db.innerModel().status() != null ? 
                                                db.innerModel().status().toString() : "Unknown");
                                    allResources.add(dto);
                                }
                            } catch (Exception e) {
                                log.warn("Failed to fetch SQL Database {}: {}", db.name(), e.getMessage());
                            }
                        });
                    } catch (Exception e) {
                        log.warn("Failed to fetch databases for SQL Server {}: {}", server.name(), e.getMessage());
                    }
                });
                log.debug("Found {} SQL Databases", allResources.stream().filter(r -> "SQL Databases".equals(r.getType())).count());
            } catch (Exception e) {
                log.error("Failed to list SQL Databases: {}", e.getMessage());
            }

            // Virtual Networks
            try {
                log.debug("Fetching Virtual Networks for account {}", accountId);
                azure.networks().list().forEach(vnet -> {
                    try {
                        ResourceDto dto = new ResourceDto();
                        dto.setId(vnet.id());
                        dto.setName(vnet.name());
                        dto.setType("Virtual Networks");
                        dto.setRegion(vnet.regionName());
                        dto.setState(vnet.innerModel().provisioningState() != null ? 
                                    vnet.innerModel().provisioningState().toString() : "Unknown");
                        allResources.add(dto);
                    } catch (Exception e) {
                        log.warn("Failed to fetch Virtual Network {}: {}", vnet.name(), e.getMessage());
                    }
                });
                log.debug("Found {} Virtual Networks", allResources.stream().filter(r -> "Virtual Networks".equals(r.getType())).count());
            } catch (Exception e) {
                log.error("Failed to list Virtual Networks: {}", e.getMessage());
            }

            // Load Balancers
            try {
                log.debug("Fetching Load Balancers for account {}", accountId);
                azure.loadBalancers().list().forEach(lb -> {
                    try {
                        ResourceDto dto = new ResourceDto();
                        dto.setId(lb.id());
                        dto.setName(lb.name());
                        dto.setType("Load Balancers");
                        dto.setRegion(lb.regionName());
                        dto.setState(lb.innerModel().provisioningState() != null ? 
                                    lb.innerModel().provisioningState().toString() : "Unknown");
                        allResources.add(dto);
                    } catch (Exception e) {
                        log.warn("Failed to fetch Load Balancer {}: {}", lb.name(), e.getMessage());
                    }
                });
                log.debug("Found {} Load Balancers", allResources.stream().filter(r -> "Load Balancers".equals(r.getType())).count());
            } catch (Exception e) {
                log.error("Failed to list Load Balancers: {}", e.getMessage());
            }

            // Public IP Addresses
            try {
                log.debug("Fetching Public IP Addresses for account {}", accountId);
                azure.publicIpAddresses().list().forEach(ip -> {
                    try {
                        ResourceDto dto = new ResourceDto();
                        dto.setId(ip.id());
                        dto.setName(ip.name());
                        dto.setType("Public IP Addresses");
                        dto.setRegion(ip.regionName());
                        dto.setState(ip.innerModel().provisioningState() != null ? 
                                    ip.innerModel().provisioningState().toString() : "Unknown");
                        allResources.add(dto);
                    } catch (Exception e) {
                        log.warn("Failed to fetch Public IP {}: {}", ip.name(), e.getMessage());
                    }
                });
                log.debug("Found {} Public IP Addresses", allResources.stream().filter(r -> "Public IP Addresses".equals(r.getType())).count());
            } catch (Exception e) {
                log.error("Failed to list Public IP Addresses: {}", e.getMessage());
            }

            log.info("Total resources fetched for Azure account {}: {}", accountId, allResources.size());

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
                    .sorted((a, b) -> Integer.compare(b.getResources().size(), a.getResources().size())) // Sort by count descending
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get cloudlist data for account {}", accountId, e);
            throw new RuntimeException("Failed to get cloudlist data", e);
        }
    }
}
