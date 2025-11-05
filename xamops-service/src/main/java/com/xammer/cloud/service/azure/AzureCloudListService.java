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

            // Fetch all Azure resources
            fetchVirtualMachines(azure, accountId, allResources);
            fetchStorageAccounts(azure, accountId, allResources);
            fetchAppServices(azure, accountId, allResources);
            fetchKubernetesServices(azure, accountId, allResources);
            fetchSqlDatabases(azure, accountId, allResources);
            fetchVirtualNetworks(azure, accountId, allResources);
            fetchLoadBalancers(azure, accountId, allResources);
            fetchPublicIpAddresses(azure, accountId, allResources);
            fetchNetworkSecurityGroups(azure, accountId, allResources);
            fetchDisks(azure, accountId, allResources);
            fetchSnapshots(azure, accountId, allResources);
            fetchContainerRegistries(azure, accountId, allResources);
            fetchKeyVaults(azure, accountId, allResources);
            fetchRedisCaches(azure, accountId, allResources);
            fetchCosmosDbAccounts(azure, accountId, allResources);
            fetchDnsZones(azure, accountId, allResources);
            fetchApplicationGateways(azure, accountId, allResources);
            fetchVpnGateways(azure, accountId, allResources);
            fetchExpressRouteCircuits(azure, accountId, allResources);
            fetchNetworkInterfaces(azure, accountId, allResources);
            fetchVirtualMachineScaleSets(azure, accountId, allResources);
            fetchServiceBusNamespaces(azure, accountId, allResources);
            fetchEventHubs(azure, accountId, allResources);
            fetchContainerGroups(azure, accountId, allResources);
            fetchAvailabilitySets(azure, accountId, allResources);
            fetchSearchServices(azure, accountId, allResources);
            fetchTrafficManagerProfiles(azure, accountId, allResources);

            log.info("Total resources fetched for Azure account {}: {}", accountId, allResources.size());

            // Group resources by service type
            Map<String, List<ResourceDto>> groupedResources = allResources.stream()
                    .collect(Collectors.groupingBy(ResourceDto::getType));

            // Convert to ServiceGroupDto and sort by resource count
            return groupedResources.entrySet().stream()
                    .map(entry -> {
                        DashboardData.ServiceGroupDto groupDto = new DashboardData.ServiceGroupDto();
                        groupDto.setServiceType(entry.getKey());
                        groupDto.setResources(entry.getValue());
                        return groupDto;
                    })
                    .sorted((a, b) -> Integer.compare(b.getResources().size(), a.getResources().size()))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get cloudlist data for account {}", accountId, e);
            throw new RuntimeException("Failed to get cloudlist data", e);
        }
    }

    // ================================================================================
    // FETCH METHODS FOR EACH SERVICE TYPE
    // ================================================================================

    private void fetchVirtualMachines(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
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
    }

    private void fetchStorageAccounts(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
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
    }

    private void fetchAppServices(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
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
    }

    private void fetchKubernetesServices(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
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
    }

    private void fetchSqlDatabases(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching SQL Databases for account {}", accountId);
            azure.sqlServers().list().forEach(server -> {
                try {
                    server.databases().list().forEach(db -> {
                        try {
                            if (!"master".equalsIgnoreCase(db.name())) {
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
    }

    private void fetchVirtualNetworks(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
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
    }

    private void fetchLoadBalancers(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
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
    }

    private void fetchPublicIpAddresses(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
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
    }

    private void fetchNetworkSecurityGroups(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Network Security Groups for account {}", accountId);
            azure.networkSecurityGroups().list().forEach(nsg -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(nsg.id());
                    dto.setName(nsg.name());
                    dto.setType("Network Security Groups");
                    dto.setRegion(nsg.regionName());
                    dto.setState(nsg.innerModel().provisioningState() != null ? 
                                nsg.innerModel().provisioningState().toString() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch NSG {}: {}", nsg.name(), e.getMessage());
                }
            });
            log.debug("Found {} Network Security Groups", allResources.stream().filter(r -> "Network Security Groups".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Network Security Groups: {}", e.getMessage());
        }
    }

    private void fetchDisks(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Disks for account {}", accountId);
            azure.disks().list().forEach(disk -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(disk.id());
                    dto.setName(disk.name());
                    dto.setType("Disks");
                    dto.setRegion(disk.regionName());
                    dto.setState(disk.innerModel().provisioningState() != null ? 
                                disk.innerModel().provisioningState() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Disk {}: {}", disk.name(), e.getMessage());
                }
            });
            log.debug("Found {} Disks", allResources.stream().filter(r -> "Disks".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Disks: {}", e.getMessage());
        }
    }

    private void fetchSnapshots(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Snapshots for account {}", accountId);
            azure.snapshots().list().forEach(snapshot -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(snapshot.id());
                    dto.setName(snapshot.name());
                    dto.setType("Snapshots");
                    dto.setRegion(snapshot.regionName());
                    dto.setState(snapshot.innerModel().provisioningState() != null ? 
                                snapshot.innerModel().provisioningState() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Snapshot {}: {}", snapshot.name(), e.getMessage());
                }
            });
            log.debug("Found {} Snapshots", allResources.stream().filter(r -> "Snapshots".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Snapshots: {}", e.getMessage());
        }
    }

    private void fetchContainerRegistries(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Container Registries for account {}", accountId);
            azure.containerRegistries().list().forEach(acr -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(acr.id());
                    dto.setName(acr.name());
                    dto.setType("Container Registries");
                    dto.setRegion(acr.regionName());
                    dto.setState(acr.innerModel().provisioningState() != null ? 
                                acr.innerModel().provisioningState().toString() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch ACR {}: {}", acr.name(), e.getMessage());
                }
            });
            log.debug("Found {} Container Registries", allResources.stream().filter(r -> "Container Registries".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Container Registries: {}", e.getMessage());
        }
    }

    private void fetchKeyVaults(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Key Vaults for account {}", accountId);
            // Key Vaults need to be fetched via resource groups
            azure.resourceGroups().list().forEach(rg -> {
                try {
                    azure.genericResources().listByResourceGroup(rg.name()).stream()
                        .filter(res -> res.type() != null && res.type().equalsIgnoreCase("Microsoft.KeyVault/vaults"))
                        .forEach(vault -> {
                            try {
                                ResourceDto dto = new ResourceDto();
                                dto.setId(vault.id());
                                dto.setName(vault.name());
                                dto.setType("Key Vaults");
                                dto.setRegion(vault.regionName());
                                dto.setState("Active");
                                allResources.add(dto);
                            } catch (Exception e) {
                                log.warn("Failed to fetch Key Vault {}: {}", vault.name(), e.getMessage());
                            }
                        });
                } catch (Exception e) {
                    log.warn("Failed to list resources in resource group {}: {}", rg.name(), e.getMessage());
                }
            });
            log.debug("Found {} Key Vaults", allResources.stream().filter(r -> "Key Vaults".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Key Vaults: {}", e.getMessage());
        }
    }
    
    

    private void fetchRedisCaches(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Redis Caches for account {}", accountId);
            azure.redisCaches().list().forEach(redis -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(redis.id());
                    dto.setName(redis.name());
                    dto.setType("Redis Cache");
                    dto.setRegion(redis.regionName());
                    dto.setState(redis.innerModel().provisioningState() != null ? 
                                redis.innerModel().provisioningState().toString() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Redis Cache {}: {}", redis.name(), e.getMessage());
                }
            });
            log.debug("Found {} Redis Caches", allResources.stream().filter(r -> "Redis Cache".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Redis Caches: {}", e.getMessage());
        }
    }

    private void fetchCosmosDbAccounts(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching CosmosDB Accounts for account {}", accountId);
            azure.cosmosDBAccounts().list().forEach(cosmos -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(cosmos.id());
                    dto.setName(cosmos.name());
                    dto.setType("CosmosDB");
                    dto.setRegion(cosmos.regionName());
                    dto.setState("Active");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch CosmosDB {}: {}", cosmos.name(), e.getMessage());
                }
            });
            log.debug("Found {} CosmosDB Accounts", allResources.stream().filter(r -> "CosmosDB".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list CosmosDB Accounts: {}", e.getMessage());
        }
    }

    private void fetchDnsZones(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching DNS Zones for account {}", accountId);
            azure.dnsZones().list().forEach(dns -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(dns.id());
                    dto.setName(dns.name());
                    dto.setType("DNS Zones");
                    dto.setRegion("Global");
                    dto.setState("Active");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch DNS Zone {}: {}", dns.name(), e.getMessage());
                }
            });
            log.debug("Found {} DNS Zones", allResources.stream().filter(r -> "DNS Zones".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list DNS Zones: {}", e.getMessage());
        }
    }

    private void fetchApplicationGateways(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Application Gateways for account {}", accountId);
            azure.applicationGateways().list().forEach(appGw -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(appGw.id());
                    dto.setName(appGw.name());
                    dto.setType("Application Gateways");
                    dto.setRegion(appGw.regionName());
                    dto.setState(appGw.innerModel().provisioningState() != null ? 
                                appGw.innerModel().provisioningState().toString() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Application Gateway {}: {}", appGw.name(), e.getMessage());
                }
            });
            log.debug("Found {} Application Gateways", allResources.stream().filter(r -> "Application Gateways".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Application Gateways: {}", e.getMessage());
        }
    }

    private void fetchVpnGateways(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching VPN Gateways for account {}", accountId);
            azure.virtualNetworkGateways().list().forEach(vpn -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(vpn.id());
                    dto.setName(vpn.name());
                    dto.setType("VPN Gateways");
                    dto.setRegion(vpn.regionName());
                    dto.setState(vpn.innerModel().provisioningState() != null ? 
                                vpn.innerModel().provisioningState().toString() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch VPN Gateway {}: {}", vpn.name(), e.getMessage());
                }
            });
            log.debug("Found {} VPN Gateways", allResources.stream().filter(r -> "VPN Gateways".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list VPN Gateways: {}", e.getMessage());
        }
    }

    private void fetchExpressRouteCircuits(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching ExpressRoute Circuits for account {}", accountId);
            azure.expressRouteCircuits().list().forEach(circuit -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(circuit.id());
                    dto.setName(circuit.name());
                    dto.setType("ExpressRoute Circuits");
                    dto.setRegion(circuit.regionName());
                    dto.setState(circuit.innerModel().provisioningState() != null ? 
                                circuit.innerModel().provisioningState().toString() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch ExpressRoute Circuit {}: {}", circuit.name(), e.getMessage());
                }
            });
            log.debug("Found {} ExpressRoute Circuits", allResources.stream().filter(r -> "ExpressRoute Circuits".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list ExpressRoute Circuits: {}", e.getMessage());
        }
    }

    private void fetchNetworkInterfaces(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Network Interfaces for account {}", accountId);
            azure.networkInterfaces().list().forEach(nic -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(nic.id());
                    dto.setName(nic.name());
                    dto.setType("Network Interfaces");
                    dto.setRegion(nic.regionName());
                    dto.setState(nic.innerModel().provisioningState() != null ? 
                                nic.innerModel().provisioningState().toString() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Network Interface {}: {}", nic.name(), e.getMessage());
                }
            });
            log.debug("Found {} Network Interfaces", allResources.stream().filter(r -> "Network Interfaces".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Network Interfaces: {}", e.getMessage());
        }
    }

    private void fetchVirtualMachineScaleSets(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Virtual Machine Scale Sets for account {}", accountId);
            azure.virtualMachineScaleSets().list().forEach(vmss -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(vmss.id());
                    dto.setName(vmss.name());
                    dto.setType("Virtual Machine Scale Sets");
                    dto.setRegion(vmss.regionName());
                    dto.setState(vmss.innerModel().provisioningState() != null ? 
                                vmss.innerModel().provisioningState() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch VMSS {}: {}", vmss.name(), e.getMessage());
                }
            });
            log.debug("Found {} Virtual Machine Scale Sets", allResources.stream().filter(r -> "Virtual Machine Scale Sets".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Virtual Machine Scale Sets: {}", e.getMessage());
        }
    }

    private void fetchServiceBusNamespaces(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Service Bus Namespaces for account {}", accountId);
            azure.serviceBusNamespaces().list().forEach(sb -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(sb.id());
                    dto.setName(sb.name());
                    dto.setType("Service Bus");
                    dto.setRegion(sb.regionName());
                    dto.setState(sb.innerModel().provisioningState() != null ? 
                                sb.innerModel().provisioningState() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Service Bus {}: {}", sb.name(), e.getMessage());
                }
            });
            log.debug("Found {} Service Bus Namespaces", allResources.stream().filter(r -> "Service Bus".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Service Bus Namespaces: {}", e.getMessage());
        }
    }

    private void fetchEventHubs(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Event Hubs for account {}", accountId);
            azure.eventHubNamespaces().list().forEach(eh -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(eh.id());
                    dto.setName(eh.name());
                    dto.setType("Event Hubs");
                    dto.setRegion(eh.regionName());
                    dto.setState(eh.innerModel().provisioningState() != null ? 
                                eh.innerModel().provisioningState() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Event Hub {}: {}", eh.name(), e.getMessage());
                }
            });
            log.debug("Found {} Event Hubs", allResources.stream().filter(r -> "Event Hubs".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Event Hubs: {}", e.getMessage());
        }
    }

    private void fetchContainerGroups(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Container Groups for account {}", accountId);
            azure.containerGroups().list().forEach(group -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(group.id());
                    dto.setName(group.name());
                    dto.setType("Container Instances");
                    dto.setRegion(group.regionName());
                    dto.setState(group.innerModel().provisioningState() != null ? 
                                group.innerModel().provisioningState() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Container Group {}: {}", group.name(), e.getMessage());
                }
            });
            log.debug("Found {} Container Groups", allResources.stream().filter(r -> "Container Instances".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Container Groups: {}", e.getMessage());
        }
    }

    private void fetchAvailabilitySets(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Availability Sets for account {}", accountId);
            azure.availabilitySets().list().forEach(avset -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(avset.id());
                    dto.setName(avset.name());
                    dto.setType("Availability Sets");
                    dto.setRegion(avset.regionName());
                    dto.setState("Active");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Availability Set {}: {}", avset.name(), e.getMessage());
                }
            });
            log.debug("Found {} Availability Sets", allResources.stream().filter(r -> "Availability Sets".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Availability Sets: {}", e.getMessage());
        }
    }

    private void fetchSearchServices(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Search Services for account {}", accountId);
            azure.searchServices().list().forEach(search -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(search.id());
                    dto.setName(search.name());
                    dto.setType("Search Services");
                    dto.setRegion(search.regionName());
                    dto.setState(search.innerModel().provisioningState() != null ? 
                                search.innerModel().provisioningState().toString() : "Unknown");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Search Service {}: {}", search.name(), e.getMessage());
                }
            });
            log.debug("Found {} Search Services", allResources.stream().filter(r -> "Search Services".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Search Services: {}", e.getMessage());
        }
    }

    private void fetchTrafficManagerProfiles(AzureResourceManager azure, String accountId, List<ResourceDto> allResources) {
        try {
            log.debug("Fetching Traffic Manager Profiles for account {}", accountId);
            azure.trafficManagerProfiles().list().forEach(tm -> {
                try {
                    ResourceDto dto = new ResourceDto();
                    dto.setId(tm.id());
                    dto.setName(tm.name());
                    dto.setType("Traffic Manager");
                    dto.setRegion("Global");
                    dto.setState(tm.isEnabled() ? "Enabled" : "Disabled");
                    allResources.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to fetch Traffic Manager {}: {}", tm.name(), e.getMessage());
                }
            });
            log.debug("Found {} Traffic Manager Profiles", allResources.stream().filter(r -> "Traffic Manager".equals(r.getType())).count());
        } catch (Exception e) {
            log.error("Failed to list Traffic Manager Profiles: {}", e.getMessage());
        }
    }
}
