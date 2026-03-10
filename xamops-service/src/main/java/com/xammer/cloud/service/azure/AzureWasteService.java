package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.Disk;
import com.azure.resourcemanager.compute.models.PowerState;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.network.models.NetworkInterface;
import com.azure.resourcemanager.network.models.PublicIpAddress;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.azure.AzureWasteItem;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Detects idle and wasteful Azure resources using Azure Resource Manager SDK.
 *
 * Detected categories:
 *  - Unattached Managed Disks (!disk.isAttachedToVirtualMachine())
 *  - Stopped VMs           (PowerState.STOPPED — still incurring compute charges)
 *  - Deallocated VMs       (PowerState.DEALLOCATED — compute stopped, storage still billed)
 *  - Orphaned Public IPs   (!pip.hasAssignedNetworkInterface() && !pip.hasAssignedLoadBalancer())
 *  - Unattached NICs       (nic.innerModel().virtualMachine() == null — NIC exists but no VM attached)
 *
 * Results are cached in Redis (60-min TTL) + persisted to DB for cross-restart resilience.
 */
@Service
public class AzureWasteService {

    private static final Logger log = LoggerFactory.getLogger(AzureWasteService.class);
    private static final String CACHE_PREFIX = "azure:waste:";
    private static final String DB_CACHE_PREFIX = "AZURE_WASTE::";
    private static final int CACHE_TTL_MINUTES = 60;

    // Flat monthly savings estimates in INR
    private static final double SAVINGS_UNATTACHED_DISK = 620.0;
    private static final double SAVINGS_STOPPED_VM = 1500.0;
    private static final double SAVINGS_DEALLOCATED_VM = 300.0;
    private static final double SAVINGS_ORPHANED_IP = 280.0;
    private static final double SAVINGS_UNATTACHED_NIC = 0.0;

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureWasteService(AzureClientProvider clientProvider,
                             RedisCacheService redisCache,
                             CachedDataRepository cachedDataRepository) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
    }

    /**
     * Returns a list of waste items for the given subscription.
     * Cache strategy: Redis (60-min TTL) → DB snapshot → Azure API.
     * Pass forceRefresh=true to bypass all caches.
     */
    public List<AzureWasteItem> getWasteItems(String subscriptionId, boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + subscriptionId;
        String dbKey = DB_CACHE_PREFIX + subscriptionId;

        if (!forceRefresh) {
            // 1. Try Redis first
            try {
                Optional<Object> cached = redisCache.get(cacheKey, Object.class);
                if (cached.isPresent()) {
                    List<AzureWasteItem> items = objectMapper.convertValue(
                            cached.get(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, AzureWasteItem.class));
                    log.info("Returning Redis-cached waste items for {} ({} items)", subscriptionId, items.size());
                    return items;
                }
            } catch (Exception e) {
                log.warn("Redis deserialization failed for waste items for {}: {}", subscriptionId, e.getMessage());
            }

            // 2. Try DB snapshot
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    List<AzureWasteItem> items = objectMapper.readValue(
                            dbSnapshot.get().getJsonData(),
                            new TypeReference<List<AzureWasteItem>>() {});
                    log.info("Returning DB-cached waste items for {} ({} items) — re-warming Redis", subscriptionId, items.size());
                    redisCache.put(cacheKey, items, CACHE_TTL_MINUTES);
                    return items;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for waste items for {}: {}", subscriptionId, e.getMessage());
            }
        }

        // 3. Fetch from Azure API
        List<AzureWasteItem> items = detectWaste(subscriptionId);
        redisCache.put(cacheKey, items, CACHE_TTL_MINUTES);
        saveToDb(dbKey, items);
        return items;
    }

    private void saveToDb(String dbKey, List<AzureWasteItem> items) {
        try {
            String json = objectMapper.writeValueAsString(items);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
            log.info("✅ DB snapshot saved for Azure waste: {}", dbKey);
        } catch (Exception e) {
            log.warn("Failed to save DB snapshot for Azure waste {}: {}", dbKey, e.getMessage());
        }
    }

    private List<AzureWasteItem> detectWaste(String subscriptionId) {
        List<AzureWasteItem> result = new ArrayList<>();
        try {
            AzureResourceManager azure = clientProvider.getAzureClient(subscriptionId);

            detectUnattachedDisks(azure, result);
            detectVirtualMachines(azure, result);
            detectOrphanedPublicIps(azure, result);
            detectUnattachedNics(azure, result);

        } catch (Exception e) {
            log.error("Failed to detect waste for subscription {}: {}", subscriptionId, e.getMessage(), e);
        }

        // Sort by monthly savings descending (highest potential savings first)
        result.sort(Comparator.comparingDouble(AzureWasteItem::getMonthlySavings).reversed());
        log.info("Waste detection complete for {}: {} items found", subscriptionId, result.size());
        return result;
    }

    private void detectUnattachedDisks(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (Disk disk : azure.disks().list()) {
                try {
                    if (!disk.isAttachedToVirtualMachine()) {
                        result.add(new AzureWasteItem(
                                disk.id(),
                                disk.name(),
                                "Unattached Disk",
                                disk.regionName() != null ? disk.regionName() : "",
                                "Managed disk has no VM attachment — incurring storage cost with no workload",
                                SAVINGS_UNATTACHED_DISK
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing disk {}: {}", disk.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list disks for waste detection: {}", e.getMessage());
        }
    }

    private void detectVirtualMachines(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (VirtualMachine vm : azure.virtualMachines().list()) {
                try {
                    PowerState powerState = vm.powerState();
                    if (PowerState.STOPPED.equals(powerState)) {
                        result.add(new AzureWasteItem(
                                vm.id(),
                                vm.name(),
                                "Stopped VM",
                                vm.regionName() != null ? vm.regionName() : "",
                                "VM is stopped but still incurring compute charges — deallocate to stop billing",
                                SAVINGS_STOPPED_VM
                        ));
                    } else if (PowerState.DEALLOCATED.equals(powerState)) {
                        result.add(new AzureWasteItem(
                                vm.id(),
                                vm.name(),
                                "Deallocated VM",
                                vm.regionName() != null ? vm.regionName() : "",
                                "VM is deallocated — compute stopped but storage (OS disk) is still billed",
                                SAVINGS_DEALLOCATED_VM
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing VM {}: {}", vm.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list VMs for waste detection: {}", e.getMessage());
        }
    }

    private void detectOrphanedPublicIps(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (PublicIpAddress pip : azure.publicIpAddresses().list()) {
                try {
                    if (!pip.hasAssignedNetworkInterface() && !pip.hasAssignedLoadBalancer()) {
                        result.add(new AzureWasteItem(
                                pip.id(),
                                pip.name(),
                                "Orphaned Public IP",
                                pip.regionName() != null ? pip.regionName() : "",
                                "Static public IP reserved but not attached to any resource — incurring idle IP cost",
                                SAVINGS_ORPHANED_IP
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing public IP {}: {}", pip.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list public IPs for waste detection: {}", e.getMessage());
        }
    }

    private void detectUnattachedNics(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (NetworkInterface nic : azure.networkInterfaces().list()) {
                try {
                    if (nic.innerModel().virtualMachine() == null) {
                        result.add(new AzureWasteItem(
                                nic.id(),
                                nic.name(),
                                "Unattached NIC",
                                nic.regionName() != null ? nic.regionName() : "",
                                "Network interface not attached to any virtual machine — resource hygiene issue",
                                SAVINGS_UNATTACHED_NIC
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing NIC {}: {}", nic.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list NICs for waste detection: {}", e.getMessage());
        }
    }
}
