package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.Disk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DiskPolicy;
import com.xammer.cloud.domain.EbsVolumeAction;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DiskPolicyRepository;
import com.xammer.cloud.repository.CachedDataRepository;
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

    // Defaults used only when no DiskPolicy is configured for the account.
    private static final double DEFAULT_SHRINK_THRESHOLD_PCT = 25.0;
    private static final double DEFAULT_EXPAND_THRESHOLD_PCT = 80.0;
    private static final int    DEFAULT_MIN_DISK_SIZE_GB     = 10;
    private static final int    DEFAULT_DESIRED_UTILIZATION  = 70;

    /**
     * Azure Managed Disks cannot be shrunk in place. Realizing a shrink means creating a
     * smaller disk, migrating the data, and swapping it in.
     */
    private static final String SHRINK_UNSUPPORTED_MESSAGE =
            "Azure Managed Disks cannot be shrunk in place. Create a smaller disk, "
                    + "migrate the data, then detach and delete the original.";

    private final CloudAccountRepository    cloudAccountRepository;
    private final DiskPolicyRepository      diskPolicyRepository;
    private final EbsVolumeActionRepository ebsVolumeActionRepository;
    private final RedisCacheService         redisCacheService;
    private final AzureClientProvider       azureClientProvider;
    private final CachedDataRepository      cachedDataRepository;
    private final AzureRetailPricingService retailPricingService;

    // ── Public API ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getDisks(String accountId, Long callerClientId, boolean forceRefresh) {
        CloudAccount account = resolveAccount(accountId, callerClientId);
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

    public List<Map<String, Object>> getRecommendations(String accountId, Long callerClientId, boolean forceRefresh) {
        return getDisks(accountId, callerClientId, forceRefresh).stream()
                .filter(d -> !"NONE".equals(d.get("recommendation")))
                .collect(Collectors.toList());
    }

    public Map<String, Object> applyAction(String accountId, Long callerClientId, String diskName, String resourceGroup,
                                            String actionType, Integer targetSizeGb) {
        CloudAccount account = resolveAccount(accountId, callerClientId);
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
                    action.setErrorMessage(SHRINK_UNSUPPORTED_MESSAGE);
                    action.setCompletedAt(Instant.now());
                    ebsVolumeActionRepository.save(action);
                    return Map.of("success", false, "error", SHRINK_UNSUPPORTED_MESSAGE);
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

    public List<DiskPolicy> getPolicies(String accountId, Long callerClientId) {
        CloudAccount account = resolveAccount(accountId, callerClientId);
        List<DiskPolicy> policies = diskPolicyRepository.findByAccountId(account.getId()).stream()
                .filter(p -> "AZURE".equals(p.getCloudProvider()))
                .collect(Collectors.toList());
        if (policies.isEmpty()) {
            // Persist the default so a later savePolicy updates a real row, and so the
            // engine and the UI read the same thresholds.
            DiskPolicy def = new DiskPolicy();
            def.setAccount(account);
            def.setPolicyName("Default Azure Disk Policy");
            def.setDesiredUtilization(DEFAULT_DESIRED_UTILIZATION);
            def.setMinDiskSizeGb(DEFAULT_MIN_DISK_SIZE_GB);
            def.setMaxDiskCount(100);
            def.setPolicyType("STANDARD");
            def.setCloudProvider("AZURE");
            def.setDefault(true);
            return List.of(diskPolicyRepository.save(def));
        }
        return policies;
    }

    public Map<String, Object> savePolicy(String accountId, Long callerClientId, DiskPolicy policy) {
        CloudAccount account = resolveAccount(accountId, callerClientId);
        policy.setAccount(account);
        policy.setCloudProvider("AZURE");
        diskPolicyRepository.save(policy);
        redisCacheService.evict(CACHE_KEY_DISKS + accountId);
        return Map.of("success", true);
    }

    public List<EbsVolumeAction> getActionHistory(String accountId, Long callerClientId) {
        return ebsVolumeActionRepository.findByAccountIdOrderByInitiatedAtDesc(
                resolveAccount(accountId, callerClientId).getId());
    }

    // ── Disk listing ──────────────────────────────────────────────────────────

    private List<Map<String, Object>> listAllDisks(CloudAccount account, String subscriptionId) {
        List<Map<String, Object>> result = new ArrayList<>();
        // Resolve the policy once per scan rather than per disk.
        EffectiveDiskPolicy policy = resolveEffectivePolicy(account);
        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            StreamSupport.stream(azure.disks().list().spliterator(), false)
                    .forEach(disk -> {
                        Map<String, Object> dto = buildDiskDto(disk);
                        enrichFromAgent(account, disk, dto);
                        applyRecommendation(dto, policy);
                        result.add(dto);
                    });
        } catch (Exception e) {
            logger.error("[azure-disk] failed to list disks for subscription {}: {}",
                    subscriptionId, e.getMessage(), e);
        }
        logger.info("[azure-disk] listed {} disks for subscription {}", result.size(), subscriptionId);
        return result;
    }

    /** Grace period before an unattached disk may be recommended for deletion. */
    private static final int DEFAULT_MIN_DETACH_AGE_DAYS = 7;

    /**
     * Tag keys that mark a disk as retained by a backup or replication policy.
     * Mirrors {@code AzureWasteService.hasProtectedTag}.
     */
    private static final List<String> PROTECTED_TAG_FRAGMENTS = List.of(
            "backup", "retain", "retention", "donotdelete", "do-not-delete",
            "asr-", "siterecovery", "recoveryservices");

    /**
     * Whether the disk is provisioned and reconciled by AKS.
     *
     * <p>Same rules as {@code AzureWasteService.isKubernetesManaged}: AKS names dynamically
     * provisioned disks {@code pvc-<uuid>}, places managed infrastructure in an {@code MC_}
     * node resource group, and writes ownership tags.
     */
    private boolean isKubernetesManaged(String resourceId, String name, String resourceGroup,
                                        Map<String, String> tags) {
        if (name != null && name.toLowerCase().startsWith("pvc-")) {
            return true;
        }
        if (resourceGroup != null && resourceGroup.toUpperCase().startsWith("MC_")) {
            return true;
        }
        if (resourceId != null && resourceId.toUpperCase().contains("/RESOURCEGROUPS/MC_")) {
            return true;
        }
        if (tags != null) {
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                String key = tag.getKey() == null ? "" : tag.getKey().toLowerCase();
                if (key.startsWith("kubernetes.io/") || key.startsWith("k8s.io/")
                        || key.startsWith("aks-managed")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a retention or backup tag marks this disk as deliberately kept. */
    private boolean hasProtectedTag(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        return tags.keySet().stream()
                .filter(java.util.Objects::nonNull)
                .map(String::toLowerCase)
                .anyMatch(key -> PROTECTED_TAG_FRAGMENTS.stream().anyMatch(key::contains));
    }

    /**
     * Whether the disk was detached recently enough that deletion would be premature.
     * An unknown detach time is treated as recent when the disk itself is new.
     */
    private boolean isRecentlyDetached(Disk disk) {
        try {
            java.time.OffsetDateTime cutoff =
                    java.time.OffsetDateTime.now().minusDays(DEFAULT_MIN_DETACH_AGE_DAYS);
            java.time.OffsetDateTime lastOwnership = disk.innerModel().lastOwnershipUpdateTime();
            if (lastOwnership != null) {
                return lastOwnership.isAfter(cutoff);
            }
            java.time.OffsetDateTime created = disk.innerModel().timeCreated();
            return created != null && created.isAfter(cutoff);
        } catch (Exception e) {
            logger.debug("[azure-disk] could not determine detach age for {}: {}",
                    disk.name(), e.getMessage());
            return false;
        }
    }

    /**
     * Whether the disk state indicates an in-flight operation that deletion would interrupt,
     * such as an active SAS export or an upload.
     */
    private boolean isReservedState(String diskState) {
        if (diskState == null) {
            return false;
        }
        String state = diskState.toLowerCase();
        return state.contains("activesas") || state.contains("activeupload")
                || state.contains("attached") || state.contains("reserved");
    }

    private Map<String, Object> buildDiskDto(Disk disk) {
        // accountType() yields the catalog name ("Premium_LRS"); DiskSkuTypes.toString() does
        // not, so keying the price table on it silently fell through to the default rate for
        // every disk. AzureWasteService already reads accountType() for the same disks.
        String diskType = "Unknown";
        try {
            if (disk.sku() != null && disk.sku().accountType() != null) {
                diskType = disk.sku().accountType().toString();
            }
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
        // USD, matching every other amount produced by the optimization services. Priced at the
        // disk's billed tier rather than per GB, so this agrees with the invoice and with
        // AzureWasteService, which reported a different figure for the same disk.
        double monthlyCostUsd = tierMonthlyCost(diskType, sizeGb,
                disk.region() != null ? disk.region().name() : null);

        String diskState = "Unknown";
        try {
            diskState = disk.innerModel().diskState().toString();
        } catch (Exception e) {
            logger.debug("[azure-disk] could not determine disk state for {}: {}", disk.name(), e.getMessage());
        }

        // Signals that must block a delete recommendation. AzureWasteService applies all three
        // to the same disks; this service applied none, so a Kubernetes PersistentVolume could
        // be offered for one-click deletion here while being correctly excluded there.
        boolean k8sManaged = isKubernetesManaged(disk.id(), disk.name(),
                disk.resourceGroupName(), disk.tags());
        boolean protectedByBackup = hasProtectedTag(disk.tags());
        boolean recentlyDetached = isRecentlyDetached(disk);
        boolean reserved = isReservedState(diskState);

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("diskName",             disk.name());
        dto.put("resourceGroup",        disk.resourceGroupName());
        dto.put("region",               disk.region().name());
        dto.put("kubernetesManaged",    k8sManaged);
        dto.put("protectedByBackup",    protectedByBackup);
        dto.put("recentlyDetached",     recentlyDetached);
        dto.put("reservedState",        reserved);
        dto.put("diskType",             diskType);
        dto.put("sizeGb",               sizeGb);
        dto.put("status",               diskState);
        dto.put("attached",             attached);
        dto.put("instanceName",         instanceName);
        dto.put("monthlyCost",          Math.round(monthlyCostUsd * 100.0) / 100.0);
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

    /**
     * Overlays agent-reported filesystem utilization onto a disk row.
     *
     * <p>The key is {@code azure-disk-agent:{cloudAccountId}:{vmName}}, matching what
     * {@code AgentController.handleDiskAgentPush} writes for {@code provider=AZURE}. This
     * previously keyed on the Azure subscription id while the controller wrote the cloud
     * account id under the AWS prefix, so no key ever matched and every disk kept a null
     * {@code usedPercent}.
     */
    private void enrichFromAgent(CloudAccount account, Disk disk, Map<String, Object> dto) {
        String instanceName = (String) dto.get("instanceName");
        if (instanceName == null) return;

        String agentAccountKey = String.valueOf(account.getId());
        String cacheKey = "azure-disk-agent:" + agentAccountKey + ":" + instanceName;
        Optional<List<Map<String, Object>>> agentData = redisCacheService.get(
                cacheKey, new TypeReference<List<Map<String, Object>>>() {});
        logger.debug("[azure-disk] agent key={} found={}", cacheKey, agentData.isPresent());

        if (agentData.isEmpty()) {
            String seenKey = "azure-disk-agent-seen:" + agentAccountKey + ":" + instanceName;
            Optional<Boolean> seen = redisCacheService.get(seenKey, new TypeReference<Boolean>() {});
            if (seen.isPresent()) {
                dto.put("agentDeployed", true);
            }
            return;
        }

        // Match the mount that belongs to this disk by device name. Unlike the previous
        // implementation there is no fallback to the root filesystem: inheriting the boot
        // disk's usedPercent produced confident-looking SHRINK/EXPAND sizing for the wrong
        // volume. If no device matches, utilization stays unknown.
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
        }

        if (bestMount == null) {
            // Agent is reporting, but we cannot attribute a mount to this specific disk.
            dto.put("agentDeployed", true);
            logger.debug("[azure-disk] no mount matched disk {}; leaving utilization unknown", diskName);
            return;
        }

        {
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

    /**
     * Derives a recommendation for a single managed disk.
     *
     * <p>All amounts are USD. Thresholds come from the account's {@link DiskPolicy} when
     * one is configured; previously the policy was persisted and shown in the UI while the
     * engine used hardcoded constants and a magic {@code 0.65}/{@code 1.4} target that
     * contradicted the policy's advertised 70% utilization.
     */
    private void applyRecommendation(Map<String, Object> dto, EffectiveDiskPolicy policy) {
        boolean attached = Boolean.TRUE.equals(dto.get("attached"));
        int sizeGb = (int) dto.get("sizeGb");
        String diskType = (String) dto.get("diskType");

        if (!attached) {
            String diskState = (String) dto.get("status");

            // Guards, matching AzureWasteService.detectUnattachedDisks. Without them this path
            // offered a one-click delete for disks that are in legitimate use: a pvc- disk reads
            // as unattached while its pod is rescheduled, and deleting it destroys the
            // PersistentVolume's data irreversibly.
            String blockedReason = null;
            if (Boolean.TRUE.equals(dto.get("kubernetesManaged"))) {
                blockedReason = "Managed by Kubernetes — backs a PersistentVolume and reads as "
                        + "unattached while its pod is rescheduled";
            } else if (Boolean.TRUE.equals(dto.get("protectedByBackup"))) {
                blockedReason = "Carries a retention or protection tag — managed by a backup policy";
            } else if (Boolean.TRUE.equals(dto.get("reservedState"))) {
                blockedReason = "Disk state is " + diskState + " — an export or upload is in progress";
            } else if (Boolean.TRUE.equals(dto.get("recentlyDetached"))) {
                blockedReason = "Detached within the last " + DEFAULT_MIN_DETACH_AGE_DAYS
                        + " days — may be mid-maintenance or mid-migration";
            }

            if (blockedReason != null) {
                dto.put("recommendation",       "NONE");
                dto.put("recommendationReason", blockedReason);
                dto.put("estimatedSavings",     0.0);
                return;
            }

            // Requiring an exact "Unattached" match meant a disk whose state lookup failed
            // produced no recommendation at all. "Unknown" is admitted, but only after the
            // guards above have cleared it.
            if ("Unattached".equalsIgnoreCase(diskState) || "Unknown".equalsIgnoreCase(diskState)) {
                dto.put("recommendation",       "DELETE_UNATTACHED");
                dto.put("recommendationReason", "Disk is not attached to any instance");
                dto.put("estimatedSavings",     (double) dto.get("monthlyCost"));
            }
            return;
        }

        if (dto.get("usedPercent") == null) return;

        double usedPct = ((Number) dto.get("usedPercent")).doubleValue();

        String region = (String) dto.get("region");

        if (usedPct > policy.expandThresholdPct()) {
            int targetSize = policy.targetSizeGb(sizeGb, usedPct);
            dto.put("recommendation",       "EXPAND");
            dto.put("recommendedSizeGb",    targetSize);
            dto.put("recommendationReason", String.format(
                    "Disk is %.0f%% full — expand to %d GB to avoid capacity issues", usedPct, targetSize));
            // An expand raises cost; report it as negative so it cannot be summed as a gain.
            double costIncrease = Math.max(0.0,
                    tierMonthlyCost(diskType, targetSize, region) - tierMonthlyCost(diskType, sizeGb, region));
            dto.put("estimatedSavings", -Math.round(costIncrease * 100.0) / 100.0);
            dto.put("estimatedMonthlyCostIncrease", Math.round(costIncrease * 100.0) / 100.0);
        } else if (usedPct < policy.shrinkThresholdPct() && sizeGb > policy.minDiskSizeGb()) {
            int targetSize = policy.targetSizeGb(sizeGb, usedPct);
            if (targetSize < sizeGb) {
                // Azure bills a managed disk by provisioned TIER, not by the gigabyte. A 512 GiB
                // Premium disk and a 400 GiB one are both billed as P20, so shrinking between
                // them saves nothing. The previous per-GB arithmetic advertised
                // (512-400) x 0.135 = $15.12/month for a change that would not alter the invoice
                // at all.
                String currentTier = retailPricingService.managedDiskTier(diskType, sizeGb);
                String targetTier  = retailPricingService.managedDiskTier(diskType, targetSize);

                if (currentTier != null && currentTier.equals(targetTier)) {
                    dto.put("recommendation", "NONE");
                    dto.put("recommendationReason", String.format(
                            "Only %.0f%% used, but %d GB and %d GB are both billed as %s — shrinking "
                                    + "within a tier does not reduce cost.",
                            usedPct, sizeGb, targetSize, currentTier));
                    dto.put("estimatedSavings", 0.0);
                    return;
                }

                double saving = Math.max(0.0,
                        tierMonthlyCost(diskType, sizeGb, region) - tierMonthlyCost(diskType, targetSize, region));
                dto.put("recommendation",       "SHRINK");
                dto.put("recommendedSizeGb",    targetSize);
                dto.put("recommendationReason", String.format(
                        "Only %.0f%% used — a %d GB disk would suffice (currently %d GB), moving from "
                                + "%s to %s. %s",
                        usedPct, targetSize, sizeGb,
                        currentTier == null ? "the current tier" : currentTier,
                        targetTier == null ? "a smaller tier" : targetTier,
                        SHRINK_UNSUPPORTED_MESSAGE));
                dto.put("estimatedSavings", Math.round(saving * 100.0) / 100.0);
                // Not applicable via the Azure API; realizing it needs a migration.
                dto.put("actionable", false);
                dto.put("requiresMigration", true);
            }
        }
    }

    /**
     * Monthly cost of a managed disk at its billed tier.
     *
     * <p>Resolves the live tier price where possible. Falls back to the per-GB table only when
     * the catalog lookup fails or the family is not tier-billed (Premium v2 and Ultra bill on
     * provisioned IOPS and throughput as well as capacity).
     */
    private double tierMonthlyCost(String diskType, int sizeGb, String region) {
        if (diskType != null && region != null) {
            try {
                Optional<Double> tierPrice =
                        retailPricingService.getManagedDiskMonthlyPriceUsd(diskType, sizeGb, region);
                if (tierPrice.isPresent() && tierPrice.get() > 0) {
                    return tierPrice.get();
                }
            } catch (Exception e) {
                logger.debug("[azure-disk] tier price lookup failed for {} {}GB in {}: {}",
                        diskType, sizeGb, region, e.getMessage());
            }
        }
        return sizeGb * DISK_COST_PER_GB.getOrDefault(diskType, DEFAULT_COST_PER_GB);
    }

    /**
     * Effective disk thresholds for an account, resolved from its {@link DiskPolicy}.
     *
     * @param shrinkThresholdPct utilization below which a disk is over-provisioned
     * @param expandThresholdPct utilization above which a disk needs headroom
     * @param minDiskSizeGb      floor for any recommended size
     * @param desiredUtilization utilization the recommended size should achieve
     */
    private record EffectiveDiskPolicy(
            double shrinkThresholdPct,
            double expandThresholdPct,
            int minDiskSizeGb,
            int desiredUtilization) {

        int targetSizeGb(int currentSizeGb, double usedPct) {
            double usedGb = currentSizeGb * usedPct / 100.0;
            double targetUtilisation = desiredUtilization / 100.0;
            return Math.max(minDiskSizeGb, (int) Math.ceil(usedGb / targetUtilisation));
        }
    }

    /** Loads the account's Azure disk policy, falling back to service defaults. */
    private EffectiveDiskPolicy resolveEffectivePolicy(CloudAccount account) {
        try {
            List<DiskPolicy> policies = diskPolicyRepository.findByAccountId(account.getId()).stream()
                    .filter(p -> "AZURE".equals(p.getCloudProvider()))
                    .collect(Collectors.toList());
            DiskPolicy policy = policies.stream()
                    .filter(DiskPolicy::isDefault)
                    .findFirst()
                    .orElse(policies.isEmpty() ? null : policies.get(0));
            if (policy == null) {
                return defaultPolicy();
            }
            int desired = policy.getDesiredUtilization() > 0
                    ? policy.getDesiredUtilization() : DEFAULT_DESIRED_UTILIZATION;
            int minSize = policy.getMinDiskSizeGb() > 0
                    ? policy.getMinDiskSizeGb() : DEFAULT_MIN_DISK_SIZE_GB;
            double shrinkThreshold = Math.max(1.0, desired * 0.5);
            return new EffectiveDiskPolicy(
                    shrinkThreshold, DEFAULT_EXPAND_THRESHOLD_PCT, minSize, desired);
        } catch (Exception e) {
            logger.warn("[azure-disk] Could not load disk policy for account {}; using defaults: {}",
                    account.getId(), e.getMessage());
            return defaultPolicy();
        }
    }

    private EffectiveDiskPolicy defaultPolicy() {
        return new EffectiveDiskPolicy(DEFAULT_SHRINK_THRESHOLD_PCT, DEFAULT_EXPAND_THRESHOLD_PCT,
                DEFAULT_MIN_DISK_SIZE_GB, DEFAULT_DESIRED_UTILIZATION);
    }

    // ── Apply actions ─────────────────────────────────────────────────────────

    private Map<String, Object> applyExpand(String subscriptionId, String resourceGroup,
                                              String diskName, Integer targetSizeGb,
                                              EbsVolumeAction action, CloudAccount account) {
        if (targetSizeGb == null || targetSizeGb < DEFAULT_MIN_DISK_SIZE_GB) {
            throw new IllegalArgumentException("targetSizeGb must be >= " + DEFAULT_MIN_DISK_SIZE_GB);
        }

        AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
        Disk disk = azure.disks().getByResourceGroup(resourceGroup, diskName);
        if (disk == null) {
            throw new RuntimeException("Disk not found: " + diskName + " in resource group " + resourceGroup);
        }

        if (targetSizeGb <= disk.sizeInGB()) {
            throw new IllegalArgumentException(String.format(
                    "targetSizeGb (%d) must exceed the current size (%d). Azure Managed Disks cannot shrink.",
                    targetSizeGb, disk.sizeInGB()));
        }

        action.setOldSizeGb(disk.sizeInGB());
        disk.update().withSizeInGB(targetSizeGb).apply();

        action.setNewSizeGb(targetSizeGb);
        action.setStatus("SUCCESS");
        action.setCompletedAt(Instant.now());
        ebsVolumeActionRepository.save(action);
        evictDiskCaches(account, subscriptionId);

        // The block device is larger, but the guest filesystem is not. Until it is grown
        // in-guest, usedPercent will not improve and the disk will keep being recommended
        // for expansion. Say so rather than reporting unqualified success.
        return Map.of(
                "success", true,
                "message", "Disk " + diskName + " expanded to " + targetSizeGb + " GB",
                "followUpRequired", true,
                "followUpAction", "Grow the filesystem inside the VM (for example "
                        + "'sudo growpart /dev/sdX 1 && sudo resize2fs /dev/sdX1' on Linux, or "
                        + "Extend Volume in Disk Management on Windows). Until then the guest still "
                        + "sees the old capacity.");
    }

    private Map<String, Object> applyDelete(String subscriptionId, String resourceGroup,
                                              String diskName, EbsVolumeAction action,
                                              CloudAccount account) {
        AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);

        // Re-validate against live state before deleting. The recommendation that triggered
        // this can be up to CACHE_TTL_MINUTES old, so the disk may have been reattached in
        // the meantime — previously it was deleted unconditionally.
        Disk disk = azure.disks().getByResourceGroup(resourceGroup, diskName);
        if (disk == null) {
            throw new IllegalArgumentException(
                    "Disk not found: " + diskName + " in resource group " + resourceGroup);
        }
        if (disk.isAttachedToVirtualMachine()) {
            throw new IllegalStateException(String.format(
                    "Refusing to delete disk %s: it is now attached to %s. The recommendation was stale.",
                    diskName, disk.innerModel().managedBy()));
        }
        // Record the size so the action history shows what was removed.
        action.setOldSizeGb(disk.sizeInGB());

        azure.disks().deleteByResourceGroup(resourceGroup, diskName);

        action.setStatus("SUCCESS");
        action.setCompletedAt(Instant.now());
        ebsVolumeActionRepository.save(action);
        evictDiskCaches(account, subscriptionId);
        return Map.of("success", true, "message", "Disk " + diskName + " deleted");
    }

    /**
     * Clears every cache that can still report a mutated disk.
     *
     * <p>Only {@code azure-disks-{accountId}} was evicted previously, so the waste report
     * caches ({@code azure:waste:{sub}} in Redis and {@code AZURE_WASTE::{sub}} in
     * PostgreSQL) kept listing a disk that had already been deleted.
     */
    private void evictDiskCaches(CloudAccount account, String subscriptionId) {
        redisCacheService.evict(CACHE_KEY_DISKS + account.getId());
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            redisCacheService.evict("azure:waste:" + subscriptionId);
            redisCacheService.evict("azure:rightsizing:" + subscriptionId);
            try {
                cachedDataRepository.deleteById("AZURE_WASTE::" + subscriptionId);
            } catch (Exception e) {
                logger.debug("[azure-disk] Could not clear waste DB snapshot for {}: {}",
                        subscriptionId, e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves a cloud account by primary key and verifies it belongs to the calling
     * tenant.
     *
     * <p>{@code accountId} arrives from a request path, so resolving by primary key alone
     * would let any authenticated user address another tenant's subscription by
     * enumerating ids. Every caller must supply the authenticated principal's client id.
     *
     * @param accountId      cloud account primary key, as supplied by the caller
     * @param callerClientId client id of the authenticated principal
     * @throws SecurityException        if the account belongs to a different tenant
     * @throws IllegalArgumentException if the id is malformed or unknown
     */
    private CloudAccount resolveAccount(String accountId, Long callerClientId) {
        if (callerClientId == null) {
            throw new SecurityException("Unauthenticated request: no client context available");
        }

        long parsedId;
        try {
            parsedId = Long.parseLong(accountId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid account id: " + accountId);
        }

        CloudAccount account = cloudAccountRepository.findById(parsedId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        if (account.getClient() == null || !callerClientId.equals(account.getClient().getId())) {
            // Deliberately vague message: do not confirm the account exists to a caller
            // from another tenant.
            logger.warn("[azure-disk] Tenant mismatch: client {} attempted to access account {}",
                    callerClientId, accountId);
            throw new SecurityException("Account not found: " + accountId);
        }
        return account;
    }
}
