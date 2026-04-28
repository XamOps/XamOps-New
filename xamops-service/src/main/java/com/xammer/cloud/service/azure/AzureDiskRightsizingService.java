package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.Disk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DiskPolicy;
import com.xammer.cloud.domain.EbsVolumeAction;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DiskPolicyRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class AzureDiskRightsizingService {

    private static final Logger logger = LoggerFactory.getLogger(AzureDiskRightsizingService.class);

    private static final String CACHE_KEY_DISKS  = "azure-disks-";
    private static final int    CACHE_TTL_MINUTES = 120;

    // Azure Managed Disk pricing: USD/GB/month (approximate)
    private static final Map<String, Double> DISK_COST_PER_GB = Map.of(
            "Premium_LRS",     0.135,
            "StandardSSD_LRS", 0.075,
            "Standard_LRS",    0.040,
            "UltraSSD_LRS",    0.125
    );
    private static final double DEFAULT_COST_PER_GB = 0.075;
    private static final double USD_TO_INR = 84.0;

    private static final double SHRINK_THRESHOLD_PCT = 25.0;
    private static final double EXPAND_THRESHOLD_PCT = 80.0;
    private static final int    MIN_DISK_SIZE_GB      = 10;

    private final CloudAccountRepository    cloudAccountRepository;
    private final DiskPolicyRepository      diskPolicyRepository;
    private final EbsVolumeActionRepository ebsVolumeActionRepository;
    private final RedisCacheService         redisCacheService;
    private final AzureClientProvider       azureClientProvider;

    // ── Public API ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getDisks(String accountId, boolean forceRefresh) {
        CloudAccount account = resolveAccount(accountId);
        String subscriptionId = account.getAzureSubscriptionId();
        if (subscriptionId == null) {
            throw new IllegalArgumentException("No Azure subscription ID for account " + accountId);
        }

        String cacheKey = CACHE_KEY_DISKS + accountId;
        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(
                    cacheKey, new TypeReference<List<Map<String, Object>>>() {});
            if (cached.isPresent()) {
                logger.info("[azure-disk] cache hit for account {}", accountId);
                return cached.get();
            }
        }

        List<Map<String, Object>> disks = listAllDisks(account, subscriptionId);
        redisCacheService.put(cacheKey, disks, CACHE_TTL_MINUTES);
        return disks;
    }

    public List<Map<String, Object>> getRecommendations(String accountId, boolean forceRefresh) {
        return getDisks(accountId, forceRefresh).stream()
                .filter(d -> !"NONE".equals(d.get("recommendation")))
                .collect(Collectors.toList());
    }

    public Map<String, Object> applyAction(String accountId, String diskName, String resourceGroup,
                                            String actionType, Integer targetSizeGb) {
        CloudAccount account = resolveAccount(accountId);
        String subscriptionId = account.getAzureSubscriptionId();

        EbsVolumeAction action = new EbsVolumeAction();
        action.setAccount(account);
        action.setVolumeId(diskName);
        action.setRegion(resourceGroup);
        action.setActionType(actionType);
        action.setStatus("IN_PROGRESS");
        action.setInitiatedAt(Instant.now());
        ebsVolumeActionRepository.save(action);

        try {
            switch (actionType) {
                case "EXPAND":
                    return applyExpand(subscriptionId, resourceGroup, diskName, targetSizeGb, action, account);
                case "SHRINK":
                    action.setStatus("FAILED");
                    action.setErrorMessage("Azure Managed Disks cannot be shrunk; create a new smaller disk and migrate data.");
                    action.setCompletedAt(Instant.now());
                    ebsVolumeActionRepository.save(action);
                    return Map.of("success", false, "error",
                            "Azure Managed Disks cannot be shrunk; create a new smaller disk and migrate data.");
                case "DELETE_UNATTACHED":
                    return applyDelete(subscriptionId, resourceGroup, diskName, action, account);
                default:
                    throw new IllegalArgumentException("Unsupported action: " + actionType);
            }
        } catch (Exception e) {
            action.setStatus("FAILED");
            action.setErrorMessage(e.getMessage());
            action.setCompletedAt(Instant.now());
            ebsVolumeActionRepository.save(action);
            redisCacheService.evict(CACHE_KEY_DISKS + accountId);
            logger.error("[azure-disk] action {} failed for disk {}: {}", actionType, diskName, e.getMessage(), e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    public List<DiskPolicy> getPolicies(String accountId) {
        CloudAccount account = resolveAccount(accountId);
        List<DiskPolicy> policies = diskPolicyRepository.findByAccountId(account.getId()).stream()
                .filter(p -> "AZURE".equals(p.getCloudProvider()))
                .collect(Collectors.toList());
        if (policies.isEmpty()) {
            DiskPolicy def = new DiskPolicy();
            def.setAccount(account);
            def.setPolicyName("Default Azure Disk Policy");
            def.setDesiredUtilization(70);
            def.setMinDiskSizeGb(10);
            def.setMaxDiskCount(100);
            def.setPolicyType("STANDARD");
            def.setCloudProvider("AZURE");
            def.setDefault(true);
            return List.of(def);
        }
        return policies;
    }

    public Map<String, Object> savePolicy(String accountId, DiskPolicy policy) {
        CloudAccount account = resolveAccount(accountId);
        policy.setAccount(account);
        policy.setCloudProvider("AZURE");
        diskPolicyRepository.save(policy);
        redisCacheService.evict(CACHE_KEY_DISKS + accountId);
        return Map.of("success", true);
    }

    public List<EbsVolumeAction> getActionHistory(String accountId) {
        return ebsVolumeActionRepository.findByAccountIdOrderByInitiatedAtDesc(resolveAccount(accountId).getId());
    }

    // ── Disk listing ──────────────────────────────────────────────────────────

    private List<Map<String, Object>> listAllDisks(CloudAccount account, String subscriptionId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            StreamSupport.stream(azure.disks().list().spliterator(), false)
                    .forEach(disk -> {
                        Map<String, Object> dto = buildDiskDto(disk);
                        enrichFromAgent(subscriptionId, disk, dto);
                        applyRecommendation(dto);
                        result.add(dto);
                    });
        } catch (Exception e) {
            logger.error("[azure-disk] failed to list disks for subscription {}: {}",
                    subscriptionId, e.getMessage(), e);
        }
        logger.info("[azure-disk] listed {} disks for subscription {}", result.size(), subscriptionId);
        return result;
    }

    private Map<String, Object> buildDiskDto(Disk disk) {
        String diskType = "Unknown";
        try {
            diskType = disk.sku() != null ? disk.sku().toString() : "Unknown";
        } catch (Exception e) {
            logger.debug("[azure-disk] could not determine disk SKU for {}: {}", disk.name(), e.getMessage());
        }

        boolean attached = disk.innerModel().managedBy() != null;
        String instanceName = null;
        if (attached) {
            String managedBy = disk.innerModel().managedBy();
            instanceName = managedBy.substring(managedBy.lastIndexOf('/') + 1);
        }

        int sizeGb = disk.sizeInGB();
        double costPerGbUsd = DISK_COST_PER_GB.getOrDefault(diskType, DEFAULT_COST_PER_GB);
        double monthlyCostInr = sizeGb * costPerGbUsd * USD_TO_INR;

        String diskState = "Unknown";
        try {
            diskState = disk.innerModel().diskState().toString();
        } catch (Exception e) {
            logger.debug("[azure-disk] could not determine disk state for {}: {}", disk.name(), e.getMessage());
        }

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("diskName",             disk.name());
        dto.put("resourceGroup",        disk.resourceGroupName());
        dto.put("region",               disk.region().name());
        dto.put("diskType",             diskType);
        dto.put("sizeGb",               sizeGb);
        dto.put("status",               diskState);
        dto.put("attached",             attached);
        dto.put("instanceName",         instanceName);
        dto.put("monthlyCost",          Math.round(monthlyCostInr * 100.0) / 100.0);
        dto.put("usedPercent",          null);
        dto.put("usedGb",               null);
        dto.put("freeGb",               null);
        dto.put("agentDeployed",        false);
        dto.put("recommendation",       "NONE");
        dto.put("recommendationReason", "");
        dto.put("estimatedSavings",     0.0);
        dto.put("recommendedSizeGb",    sizeGb);
        return dto;
    }

    // ── Agent enrichment ──────────────────────────────────────────────────────

    private void enrichFromAgent(String subscriptionId, Disk disk, Map<String, Object> dto) {
        String instanceName = (String) dto.get("instanceName");
        if (instanceName == null) return;

        String cacheKey = "azure-disk-agent:" + subscriptionId + ":" + instanceName;
        Optional<List<Map<String, Object>>> agentData = redisCacheService.get(
                cacheKey, new TypeReference<List<Map<String, Object>>>() {});
        logger.debug("[azure-disk] agent key={} found={}", cacheKey, agentData.isPresent());

        if (agentData.isEmpty()) {
            String seenKey = "azure-disk-agent-seen:" + subscriptionId + ":" + instanceName;
            Optional<Boolean> seen = redisCacheService.get(seenKey, new TypeReference<Boolean>() {});
            if (seen.isPresent()) {
                dto.put("agentDeployed", true);
            }
            return;
        }

        // Match mount: prefer disk-name device match, fall back to "/" root
        String diskName = disk.name();
        Map<String, Object> bestMount = null;
        for (Map<String, Object> mount : agentData.get()) {
            String device = (String) mount.get("device");
            String baseDevice = device != null
                    ? device.replaceAll("^/dev/", "").replaceAll("[0-9]+$", "")
                    : "";
            if (!baseDevice.isEmpty() && (diskName.contains(baseDevice) || baseDevice.contains(diskName))) {
                bestMount = mount;
                break;
            }
            if ("/".equals(mount.get("mountPoint")) && bestMount == null) {
                bestMount = mount;
            }
        }

        if (bestMount != null) {
            Number usedPct = (Number) bestMount.get("usedPercent");
            if (usedPct != null) {
                int sizeGb = (int) dto.get("sizeGb");
                double up = usedPct.doubleValue();
                dto.put("agentDeployed", true);
                dto.put("usedPercent",   Math.round(up * 10.0) / 10.0);
                dto.put("usedGb",        Math.round(sizeGb * up / 100.0 * 100.0) / 100.0);
                dto.put("freeGb",        Math.round(sizeGb * (1 - up / 100.0) * 100.0) / 100.0);
            }
        }
    }

    // ── Recommendation engine ─────────────────────────────────────────────────

    private void applyRecommendation(Map<String, Object> dto) {
        boolean attached = Boolean.TRUE.equals(dto.get("attached"));
        int sizeGb = (int) dto.get("sizeGb");
        String diskType = (String) dto.get("diskType");
        double costPerGbInr = DISK_COST_PER_GB.getOrDefault(diskType, DEFAULT_COST_PER_GB) * USD_TO_INR;

        if (!attached) {
            String diskState = (String) dto.get("status");
            if ("Unattached".equalsIgnoreCase(diskState)) {
                dto.put("recommendation",       "DELETE_UNATTACHED");
                dto.put("recommendationReason", "Disk is not attached to any instance");
                dto.put("estimatedSavings",     (double) dto.get("monthlyCost"));
            }
            return;
        }

        if (dto.get("usedPercent") == null) return;

        double usedPct = ((Number) dto.get("usedPercent")).doubleValue();

        if (usedPct > EXPAND_THRESHOLD_PCT) {
            double usedGb  = sizeGb * usedPct / 100.0;
            int targetSize = Math.max(MIN_DISK_SIZE_GB, (int) Math.ceil(usedGb / 0.65));
            dto.put("recommendation",       "EXPAND");
            dto.put("recommendedSizeGb",    targetSize);
            dto.put("recommendationReason",
                    String.format("Disk is %.0f%% full — expand to %d GB to avoid capacity issues", usedPct, targetSize));
            dto.put("estimatedSavings", 0.0);
        } else if (usedPct < SHRINK_THRESHOLD_PCT && sizeGb > MIN_DISK_SIZE_GB) {
            double usedGb  = sizeGb * usedPct / 100.0;
            int targetSize = Math.max(MIN_DISK_SIZE_GB, (int) Math.ceil(usedGb * 1.4));
            if (targetSize < sizeGb) {
                double saving = (sizeGb - targetSize) * costPerGbInr;
                dto.put("recommendation",       "SHRINK");
                dto.put("recommendedSizeGb",    targetSize);
                dto.put("recommendationReason",
                        String.format("Only %.0f%% used — shrink from %d GB to %d GB", usedPct, sizeGb, targetSize));
                dto.put("estimatedSavings", Math.round(saving * 100.0) / 100.0);
            }
        }
    }

    // ── Apply actions ─────────────────────────────────────────────────────────

    private Map<String, Object> applyExpand(String subscriptionId, String resourceGroup,
                                              String diskName, Integer targetSizeGb,
                                              EbsVolumeAction action, CloudAccount account) {
        if (targetSizeGb == null || targetSizeGb < MIN_DISK_SIZE_GB) {
            throw new IllegalArgumentException("targetSizeGb must be >= " + MIN_DISK_SIZE_GB);
        }

        AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
        Disk disk = azure.disks().getByResourceGroup(resourceGroup, diskName);
        if (disk == null) {
            throw new RuntimeException("Disk not found: " + diskName + " in resource group " + resourceGroup);
        }

        disk.update().withSizeInGB(targetSizeGb).apply();

        action.setNewSizeGb(targetSizeGb);
        action.setStatus("SUCCESS");
        action.setCompletedAt(Instant.now());
        ebsVolumeActionRepository.save(action);
        redisCacheService.evict(CACHE_KEY_DISKS + account.getId());
        return Map.of("success", true, "message",
                "Disk " + diskName + " expanded to " + targetSizeGb + " GB");
    }

    private Map<String, Object> applyDelete(String subscriptionId, String resourceGroup,
                                              String diskName, EbsVolumeAction action,
                                              CloudAccount account) {
        AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
        azure.disks().deleteByResourceGroup(resourceGroup, diskName);

        action.setStatus("SUCCESS");
        action.setCompletedAt(Instant.now());
        ebsVolumeActionRepository.save(action);
        redisCacheService.evict(CACHE_KEY_DISKS + account.getId());
        return Map.of("success", true, "message", "Disk " + diskName + " deleted");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CloudAccount resolveAccount(String accountId) {
        return cloudAccountRepository.findById(Long.parseLong(accountId))
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }
}
