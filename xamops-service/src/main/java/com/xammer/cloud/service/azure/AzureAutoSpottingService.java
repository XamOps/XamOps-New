package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.PowerState;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.compute.models.VirtualMachineEvictionPolicyTypes;
import com.azure.resourcemanager.compute.models.VirtualMachinePriorityTypes;
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSet;
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSetVM;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.AutoSpottingSettings;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.domain.VmssSpotConfig;
import com.xammer.cloud.dto.autospotting.ASGCostData;
import com.xammer.cloud.dto.autospotting.CostResponse;
import com.xammer.cloud.dto.autospotting.EventRecord;
import com.xammer.cloud.dto.autospotting.EventsResponse;
import com.xammer.cloud.dto.autospotting.HistoryResponse;
import com.xammer.cloud.dto.autospotting.LaunchAnalyticsResponse;
import com.xammer.cloud.dto.autospotting.RiSpCoverageResponse;
import com.xammer.cloud.dto.autospotting.SpotInstanceDto;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.repository.VmssSpotConfigRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Azure AutoSpotting Service
 *
 * Implements the AutoSpotting control loop for Azure Virtual Machine Scale Sets (VMSS).
 * Follows the same patterns as GcpAutoSpottingService (for MIGs) and AutoSpottingService (for AWS ASGs).
 *
 * The control loop:
 * 1. Discovery   — scan all VMSS in subscription
 * 2. Candidacy   — check eligibility (min instances, health probe)
 * 3. Analysis    — evaluate spot availability and cost savings
 * 4. Replacement — staged: add Spot capacity → wait healthy → drain Regular → delete
 */
@Service
@Slf4j
public class AzureAutoSpottingService {

    private final AzureClientProvider azureClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final SpotOptimizationEventRepository eventRepository;
    private final DailySpotSavingsRepository savingsRepository;
    private final RedisCacheService redisCacheService;
    private final VmssSpotConfigRepository vmssSpotConfigRepository;
    private final AutoSpottingSettingsRepository settingsRepository;

    // Redis cache keys
    private static final String CACHE_KEY_VMSS      = "azure-autospotting-vmss-";
    private static final String CACHE_KEY_INSTANCES  = "azure-autospotting-instances-";
    private static final int    CACHE_TTL_MINUTES    = 240; // 4 hours

    // Approximate on-demand hourly prices for common Azure VM SKUs (USD/hr)
    // Used for savings estimation only — not for billing
    private static final Map<String, Double> VM_ONDEMAND_PRICES = new HashMap<>();
    static {
        VM_ONDEMAND_PRICES.put("Standard_B1s",      0.013);
        VM_ONDEMAND_PRICES.put("Standard_B2s",      0.052);
        VM_ONDEMAND_PRICES.put("Standard_B4ms",     0.208);
        VM_ONDEMAND_PRICES.put("Standard_D2s_v3",   0.113);
        VM_ONDEMAND_PRICES.put("Standard_D4s_v3",   0.226);
        VM_ONDEMAND_PRICES.put("Standard_D8s_v3",   0.452);
        VM_ONDEMAND_PRICES.put("Standard_D2s_v5",   0.104);
        VM_ONDEMAND_PRICES.put("Standard_D4s_v5",   0.208);
        VM_ONDEMAND_PRICES.put("Standard_D8s_v5",   0.416);
        VM_ONDEMAND_PRICES.put("Standard_E2s_v3",   0.146);
        VM_ONDEMAND_PRICES.put("Standard_E4s_v3",   0.292);
        VM_ONDEMAND_PRICES.put("Standard_F2s_v2",   0.099);
        VM_ONDEMAND_PRICES.put("Standard_F4s_v2",   0.198);
        VM_ONDEMAND_PRICES.put("Standard_NC6",      0.900);
    }
    // Azure Spot is typically 60-80% cheaper; use 70% savings as estimate
    private static final double SPOT_SAVINGS_RATIO = 0.70;

    public AzureAutoSpottingService(
            AzureClientProvider azureClientProvider,
            CloudAccountRepository cloudAccountRepository,
            SpotOptimizationEventRepository eventRepository,
            DailySpotSavingsRepository savingsRepository,
            RedisCacheService redisCacheService,
            VmssSpotConfigRepository vmssSpotConfigRepository,
            AutoSpottingSettingsRepository settingsRepository) {
        this.azureClientProvider = azureClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.eventRepository = eventRepository;
        this.savingsRepository = savingsRepository;
        this.redisCacheService = redisCacheService;
        this.vmssSpotConfigRepository = vmssSpotConfigRepository;
        this.settingsRepository = settingsRepository;
    }

    // =================== DTO ===================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AzureAutoSpottingGroupDto {
        public String name;
        public String resourceGroup;
        public String region;
        public boolean isEnabled;           // true when VMSS priority == Spot
        public boolean aksManaged;          // true if managed by AKS (cannot be toggled)
        public int instanceCount;
        public int spotInstanceCount;
        public int ondemandInstanceCount;
        public String vmSku;               // e.g. "Standard_D2s_v5"
        public double currentHourlyCost;
        public double potentialHourlyCost;
        public double actualHourlySavings;
        public double potentialHourlySavings;
    }

    // =================== ACCOUNT RESOLUTION ===================

    public CloudAccount resolveAccount(String subscriptionId) {
        log.debug("Resolving Azure account for subscriptionId: {}", subscriptionId);
        return cloudAccountRepository.findByAzureSubscriptionId(subscriptionId)
                .or(() -> {
                    try {
                        return cloudAccountRepository.findById(Long.parseLong(subscriptionId));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                })
                .orElseThrow(() -> {
                    log.error("Azure account not found for subscriptionId: {}", subscriptionId);
                    return new RuntimeException("Azure account not found for subscriptionId: " + subscriptionId);
                });
    }

    public com.azure.core.credential.TokenCredential getCredentialForSubscription(String subscriptionId) {
        CloudAccount account = resolveAccount(subscriptionId);
        return azureClientProvider.getCredential(account);
    }

    // =================== VMSS LISTING ===================

    public List<AzureAutoSpottingGroupDto> listVmssAllRegionsWithCosts(String subscriptionId) {
        return listVmssAllRegionsWithCosts(subscriptionId, false);
    }

    public List<AzureAutoSpottingGroupDto> listVmssAllRegionsWithCosts(String subscriptionId, boolean forceRefresh) {
        String cacheKey = CACHE_KEY_VMSS + subscriptionId;

        if (!forceRefresh) {
            Optional<List<AzureAutoSpottingGroupDto>> cached = redisCacheService.get(
                    cacheKey, new TypeReference<List<AzureAutoSpottingGroupDto>>() {});
            if (cached.isPresent()) {
                log.info("--- AZURE AUTOSPOTTING VMSS: loaded from Redis for subscriptionId={} ---", subscriptionId);
                return cached.get();
            }
        }

        List<AzureAutoSpottingGroupDto> result = fetchVmssFromAzure(subscriptionId);

        if (!result.isEmpty()) {
            redisCacheService.put(cacheKey, result, CACHE_TTL_MINUTES);
        }

        return result;
    }

    private List<AzureAutoSpottingGroupDto> fetchVmssFromAzure(String subscriptionId) {
        List<AzureAutoSpottingGroupDto> allVmss = new ArrayList<>();
        try {
            // Resolve account first — subscriptionId may be a numeric DB id or the Azure UUID
            CloudAccount account = resolveAccount(subscriptionId);
            String actualSubscriptionId = account.getAzureSubscriptionId();
            AzureResourceManager azure = azureClientProvider.getAzureClient(actualSubscriptionId);

            // Load DB-tracked enabled state for all VMSS in this account (one query)
            Map<String, Boolean> dbEnabledMap;
            try {
                dbEnabledMap = vmssSpotConfigRepository.findByAccountId(account.getId()).stream()
                        .collect(Collectors.toMap(
                                c -> (c.getResourceGroup() + "/" + c.getVmssName()).toLowerCase(),
                                VmssSpotConfig::isEnabled,
                                (a, b) -> b));
            } catch (Exception e) {
                log.warn("Could not load DB-enabled state for VMSS (schema may be out of date): {}", e.getMessage());
                dbEnabledMap = Collections.emptyMap();
            }

            // Wrap Azure API call in a Future with 60-second timeout to prevent indefinite hang
            final Map<String, Boolean> finalDbEnabledMap = dbEnabledMap;
            log.info("Azure VMSS: calling virtualMachineScaleSets().list() for subscriptionId={}", actualSubscriptionId);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    for (VirtualMachineScaleSet vmss : azure.virtualMachineScaleSets().list()) {
                        try {
                            AzureAutoSpottingGroupDto dto = mapToDto(vmss, finalDbEnabledMap);
                            enrichCostData(dto);
                            allVmss.add(dto);
                        } catch (Exception ex) {
                            log.warn("Failed to map/enrich VMSS {}: {}", vmss.name(), ex.getMessage());
                        }
                    }
                } catch (Exception azureEx) {
                    log.error("Azure VMSS SDK error during list(): {} — cause: {}",
                            azureEx.getMessage(),
                            azureEx.getCause() != null ? azureEx.getCause().getMessage() : "none",
                            azureEx);
                }
            });
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                log.warn("Azure VMSS listing timed out after 60s for subscriptionId={}, returning {} partial results",
                        subscriptionId, allVmss.size());
                future.cancel(true);
            } catch (java.util.concurrent.ExecutionException ee) {
                log.error("Azure VMSS listing ExecutionException for subscriptionId={}: {}",
                        subscriptionId, ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage(), ee);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Azure VMSS listing interrupted for subscriptionId={}", subscriptionId);
            }

            log.info("Azure AUTOSPOTTING: found {} VMSS for subscriptionId={}", allVmss.size(), subscriptionId);
        } catch (Exception e) {
            log.error("Failed to list VMSS for subscriptionId={}: {}", subscriptionId, e.getMessage(), e);
        }
        return allVmss;
    }

    private AzureAutoSpottingGroupDto mapToDto(VirtualMachineScaleSet vmss, Map<String, Boolean> dbEnabledMap) {
        // Detect spot priority from Azure VMSS profile
        boolean azureSpotPriority = false;
        String vmSku = vmss.sku() != null ? vmss.sku().toString() : "Unknown";

        try {
            var profile = vmss.innerModel().virtualMachineProfile();
            if (profile != null && profile.priority() != null) {
                String priority = profile.priority().toString();
                azureSpotPriority = "Spot".equalsIgnoreCase(priority) || "Low".equalsIgnoreCase(priority);
            }
        } catch (Exception e) {
            log.debug("Could not read priority for VMSS {}: {}", vmss.name(), e.getMessage());
        }

        // Detect AKS-managed VMSS: name starts with "aks-" OR resource group starts with "MC_"
        // Same pattern as GCP GKE detection (name starts with "gke-")
        boolean aksManaged = (vmss.name() != null && vmss.name().toLowerCase().startsWith("aks-"))
                || (vmss.resourceGroupName() != null && vmss.resourceGroupName().toUpperCase().startsWith("MC_"));

        // Use DB-tracked enabled state as source of truth (user intent persists across reloads)
        String dbKey = (vmss.resourceGroupName() + "/" + vmss.name()).toLowerCase();
        boolean dbEnabled = dbEnabledMap.getOrDefault(dbKey, false);
        boolean isSpotEnabled = azureSpotPriority || dbEnabled;

        int instanceCount = vmss.capacity();

        return AzureAutoSpottingGroupDto.builder()
                .name(vmss.name())
                .resourceGroup(vmss.resourceGroupName())
                .region(vmss.regionName())
                .isEnabled(isSpotEnabled)
                .aksManaged(aksManaged)
                .instanceCount(instanceCount)
                .spotInstanceCount(azureSpotPriority ? instanceCount : 0)
                .ondemandInstanceCount(azureSpotPriority ? 0 : instanceCount)
                .vmSku(vmSku)
                .build();
    }

    private void enrichCostData(AzureAutoSpottingGroupDto dto) {
        double onDemandPrice = VM_ONDEMAND_PRICES.getOrDefault(dto.vmSku, 0.10);
        double spotPrice = onDemandPrice * (1.0 - SPOT_SAVINGS_RATIO);

        dto.currentHourlyCost = (dto.ondemandInstanceCount * onDemandPrice)
                              + (dto.spotInstanceCount * spotPrice);
        dto.potentialHourlyCost = dto.instanceCount * spotPrice;

        if (dto.isEnabled) {
            // When enabled, calculate savings for all instances (spot-price-based)
            // This covers both actual Spot VMs and Regular VMs being managed by AutoSpotting
            int effectiveSpotCount = dto.spotInstanceCount > 0 ? dto.spotInstanceCount : dto.instanceCount;
            dto.actualHourlySavings = effectiveSpotCount * (onDemandPrice - spotPrice);
        } else {
            dto.actualHourlySavings = 0.0;
        }
        dto.potentialHourlySavings = dto.ondemandInstanceCount * (onDemandPrice - spotPrice);
    }

    // =================== ENABLE / DISABLE (Full Control Loop) ===================

    /**
     * Enable AutoSpotting for a VMSS using the full staged-replacement control loop:
     *  1. Validate candidacy (min 2 instances)
     *  2. Update VMSS profile to Spot priority + eviction policy
     *  3. Rolling replacement: for each Regular VM → scale-up (new Spot VM) → wait healthy → delete Regular
     *  4. Record events and update daily savings
     */
    @Async
    public void enableAutoSpotting(String subscriptionId, String resourceGroup, String vmssName) {
        log.info("🚀 [Azure AutoSpotting] Starting Spot enablement for VMSS {} in RG {} (subscription {})",
                vmssName, resourceGroup, subscriptionId);

        // Guard: AKS-managed VMSS cannot have Spot priority changed — same lock as GKE MIGs
        boolean isAksManaged = (vmssName != null && vmssName.toLowerCase().startsWith("aks-"))
                || (resourceGroup != null && resourceGroup.toUpperCase().startsWith("MC_"));
        if (isAksManaged) {
            log.warn("⛔ [Azure AutoSpotting] Skipping AKS-managed VMSS {} in RG {} — Azure controls its lifecycle. " +
                    "To use Spot, create a Spot node pool in the AKS console.", vmssName, resourceGroup);
            return;
        }

        CloudAccount account = resolveAccount(subscriptionId);

        // Persist enabled=true immediately so reloads show correct state
        VmssSpotConfig spotConfig = vmssSpotConfigRepository
                .findByAccountIdAndResourceGroupAndVmssName(account.getId(), resourceGroup, vmssName)
                .orElseGet(() -> {
                    VmssSpotConfig c = new VmssSpotConfig();
                    c.setAccount(account);
                    c.setResourceGroup(resourceGroup);
                    c.setVmssName(vmssName);
                    c.setRegion("");
                    return c;
                });
        spotConfig.setEnabled(true);
        vmssSpotConfigRepository.save(spotConfig);
        invalidateCaches(subscriptionId);

        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(account.getAzureSubscriptionId());
            VirtualMachineScaleSet vmss = azure.virtualMachineScaleSets()
                    .getByResourceGroup(resourceGroup, vmssName);

            if (vmss == null) {
                log.error("VMSS {} not found in resource group {}", vmssName, resourceGroup);
                return;
            }

            // Step 1 — Validate candidacy
            int currentCapacity = vmss.capacity();
            if (currentCapacity < 1) {
                log.warn("VMSS {} has 0 instances. Skipping — nothing to replace.", vmssName);
                recordEvent(account, vmssName, vmss.regionName(), "FAILED",
                        "Candidacy check failed: VMSS has no running instances.", 0.0);
                return;
            }
            // Single-instance VMSS is safe: we scale up first (1→2), wait for new Spot VM,
            // then delete the old Regular VM, returning to 1 Spot instance with no downtime.

            // Check already Spot-enabled
            if (isSpotEnabled(vmss)) {
                log.info("VMSS {} is already running with Spot priority. Recording as enabled.", vmssName);
                recordEvent(account, vmssName, vmss.regionName(), "ENABLED",
                        "VMSS already configured with Spot priority.", estimateSavings(vmss));
                invalidateCaches(subscriptionId);
                return;
            }

            // Step 2 — Update VMSS profile priority to Spot so newly added VMs are Spot
            // Works for Uniform orchestration VMSS. Flexible mode requires per-VM replacement.
            boolean priorityChanged = false;
            var vmProfile = vmss.innerModel().virtualMachineProfile();

            // Detect Flexible orchestration mode:
            // Primary: check orchestrationMode field (most reliable)
            // Fallback: vmProfile == null (some Flexible VMSS still return a profile object)
            boolean isFlexible = false;
            try {
                var orchMode = vmss.innerModel().orchestrationMode();
                if (orchMode != null && orchMode.toString().equalsIgnoreCase("Flexible")) {
                    isFlexible = true;
                }
            } catch (Exception e) {
                log.debug("Could not read orchestrationMode for VMSS {}: {}", vmssName, e.getMessage());
            }
            if (!isFlexible && vmProfile == null) {
                isFlexible = true;
            }

            if (isFlexible) {
                // Flexible orchestration mode: no shared VM profile for priority.
                // Use per-VM replacement — same as Spot.io/Elastigroup:
                //   create new Spot VM (same image + network + SSH) → wait healthy → delete Regular VM
                log.info("Step 2: VMSS {} uses Flexible orchestration mode. Starting per-VM Spot replacement...", vmssName);
                replaceFlexibleVmssWithSpot(azure, vmss, account, subscriptionId);
                return;
            }
            try {
                log.info("Step 2: Updating VMSS {} profile priority to Spot...", vmssName);
                vmProfile.withPriority(VirtualMachinePriorityTypes.SPOT)
                         .withEvictionPolicy(VirtualMachineEvictionPolicyTypes.DEALLOCATE);
                vmss.update().apply();
                // Re-fetch to get updated profile state
                vmss = azure.virtualMachineScaleSets().getByResourceGroup(resourceGroup, vmssName);
                priorityChanged = true;
                log.info("Step 2: VMSS {} profile updated to Spot priority successfully.", vmssName);
            } catch (Exception e) {
                log.warn("Step 2: Could not update VMSS {} priority to Spot (may be AKS-managed or policy-restricted): {}. " +
                        "AutoSpotting is tracked in DB — staged replacement skipped.", vmssName, e.getMessage());
                recordEvent(account, vmssName, vmss.regionName(), "ENABLED",
                        "AutoSpotting enabled. Note: VMSS priority could not be updated to Spot in-place " +
                        "(likely AKS-managed). Cost tracking is active.", 0.0);
                invalidateCaches(subscriptionId);
                return;
            }

            if (!priorityChanged) {
                return;
            }

            // Step 3 — Controlled replacement loop: Replace each Regular VM with a Spot VM
            // Now that the VMSS profile is Spot, scaling up creates Spot VMs
            log.info("Step 3: Starting controlled replacement loop for VMSS {}", vmssName);
            List<VirtualMachineScaleSetVM> regularVms = getRegularVms(vmss);
            log.info("Found {} Regular (on-demand) VMs to replace in VMSS {}", regularVms.size(), vmssName);

            double vmOnDemandPrice = VM_ONDEMAND_PRICES.getOrDefault(
                    vmss.sku() != null ? vmss.sku().toString() : "", 0.10);
            double vmSpotPrice = vmOnDemandPrice * (1.0 - SPOT_SAVINGS_RATIO);

            int replaced = 0;
            for (VirtualMachineScaleSetVM regularVm : regularVms) {
                try {
                    replaced += replaceWithSpotVm(azure, vmss, regularVm, account, vmOnDemandPrice, vmSpotPrice);
                } catch (Exception e) {
                    log.error("Failed to replace VM {} in VMSS {}: {}", regularVm.instanceId(), vmssName, e.getMessage());
                }
            }

            // Step 4 — Cleanup
            invalidateCaches(subscriptionId);
            double totalHourlySavings = replaced * (vmOnDemandPrice - vmSpotPrice);
            recordEvent(account, vmssName, vmss.regionName(), "ENABLED",
                    String.format("AutoSpotting enabled. Replaced %d Regular VM(s) with Spot VMs. Hourly savings: $%.4f",
                            replaced, totalHourlySavings),
                    totalHourlySavings);
            updateDailySavings(account, vmssName, vmss.regionName(), totalHourlySavings);

            log.info("✅ [Azure AutoSpotting] Successfully enabled Spot for VMSS {} ({} replacements, ${}/hr savings)",
                    vmssName, replaced, String.format("%.4f", totalHourlySavings));

        } catch (Exception e) {
            log.error("❌ [Azure AutoSpotting] Failed to enable Spot for VMSS {}: {}", vmssName, e.getMessage(), e);
            CloudAccount account2 = resolveAccount(subscriptionId);
            recordEvent(account2, vmssName, resourceGroup, "FAILED",
                    "AutoSpotting enablement failed: " + e.getMessage(), 0.0);
        }
    }

    /**
     * Performs the individual VM replacement step:
     *  a) Scale VMSS up by 1 (Azure adds a new Spot VM with the updated profile)
     *  b) Poll until a new Running instance appears
     *  c) Delete the old Regular VM
     * Returns 1 if successful, 0 if failed.
     */
    private int replaceWithSpotVm(AzureResourceManager azure, VirtualMachineScaleSet vmss,
                                   VirtualMachineScaleSetVM regularVm,
                                   CloudAccount account, double onDemandPrice, double spotPrice) throws Exception {

        String vmssName = vmss.name();
        String instanceId = regularVm.instanceId();
        int currentCapacity = Math.max(vmss.capacity(), 1);

        log.info("Replacing Regular VM {} in VMSS {} (capacity: {})", instanceId, vmssName, currentCapacity);

        // a) Scale up by 1 — Azure provisions a new Spot VM using updated profile
        vmss.update().withCapacity(currentCapacity + 1).apply();
        log.info("Scaled VMSS {} up to {} (waiting for new Spot VM to become healthy)", vmssName, currentCapacity + 1);

        // b) Poll for new healthy instance (timeout: 10 minutes)
        boolean newVmHealthy = waitForNewSpotInstance(vmss, currentCapacity, 600_000L, 30_000L);
        if (!newVmHealthy) {
            log.warn("Timed out waiting for new Spot VM in VMSS {}. Rolling back scale-up.", vmssName);
            vmss.update().withCapacity(currentCapacity).apply();
            return 0;
        }

        // c) Delete the old Regular VM
        log.info("Deleting Regular VM {} from VMSS {}", instanceId, vmssName);
        azure.virtualMachineScaleSets().getByResourceGroup(vmss.resourceGroupName(), vmssName)
                .virtualMachines()
                .deleteInstances(Collections.singleton(instanceId), true);

        // Record the individual replacement event
        SpotOptimizationEvent event = new SpotOptimizationEvent();
        event.setAccount(account);
        event.setAsgName(vmssName);
        event.setRegion(vmss.regionName());
        event.setOldInstanceId(instanceId);
        event.setOldInstanceType(vmss.sku() != null ? vmss.sku().toString() : "unknown");
        event.setNewInstanceType(vmss.sku() != null ? vmss.sku().toString() : "unknown");
        event.setHourlySavings(onDemandPrice - spotPrice);
        event.setStatus("SUCCESS");
        event.setDetails("Regular VM replaced with Spot VM via AutoSpotting controlled replacement.");
        eventRepository.save(event);

        log.info("✅ Successfully replaced VM {} with Spot VM in VMSS {}", instanceId, vmssName);
        return 1;
    }

    /**
     * Poll VMSS until a new Running instance appears beyond the previous count.
     */
    private boolean waitForNewSpotInstance(VirtualMachineScaleSet vmss, int previousCount,
                                            long timeoutMs, long pollIntervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollIntervalMs);
                // Re-fetch VMSS to get fresh instance list
                long runningCount = vmss.virtualMachines().list().stream()
                        .filter(vm -> "PowerState/running".equalsIgnoreCase(
                                vm.powerState() != null ? vm.powerState().toString() : ""))
                        .count();
                if (runningCount > previousCount) {
                    log.info("New Spot instance is healthy in VMSS {} (running count: {})", vmss.name(), runningCount);
                    return true;
                }
                log.debug("Waiting for new Spot instance in VMSS {} (running: {}/{})", vmss.name(), runningCount, previousCount + 1);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("Error polling VMSS {}: {}", vmss.name(), e.getMessage());
            }
        }
        return false;
    }

    // =================== FLEXIBLE VMSS — PER-VM SPOT REPLACEMENT ===================

    /**
     * Flexible orchestration VMSS: replace each Regular VM with a new Spot VM.
     *
     * Spot.io / Elastigroup algorithm:
     *   For each Regular VM in the Flexible VMSS:
     *     1. Create a new Spot VM (same image, size, network, SSH key) attached to the Flexible VMSS
     *     2. Wait for the new Spot VM to reach Running state
     *     3. Delete the old Regular VM
     *
     * This is required because Flexible VMSS has no shared VM profile to set Spot priority on.
     * Uniform VMSS sets priority at the profile level; Flexible sets it per-VM at creation time.
     */
    private void replaceFlexibleVmssWithSpot(AzureResourceManager azure, VirtualMachineScaleSet vmss,
                                              CloudAccount account, String subscriptionId) {
        String resourceGroup = vmss.resourceGroupName();
        String region = vmss.regionName();

        // Find Regular VMs belonging to this Flexible VMSS via their inner model VMSS reference
        List<VirtualMachine> regularVms = new ArrayList<>();
        try {
            for (VirtualMachine vm : azure.virtualMachines().listByResourceGroup(resourceGroup)) {
                var vmssRef = vm.innerModel().virtualMachineScaleSet();
                if (vmssRef == null || !vmss.id().equalsIgnoreCase(vmssRef.id())) continue;
                var priority = vm.innerModel().priority();
                // null priority means Regular (on-demand)
                if (priority == null || !VirtualMachinePriorityTypes.SPOT.equals(priority)) {
                    regularVms.add(vm);
                }
            }
        } catch (Exception e) {
            log.error("Failed to list VMs for Flexible VMSS {}: {}", vmss.name(), e.getMessage(), e);
            recordEvent(account, vmss.name(), region, "FAILED",
                    "Could not list VMs in Flexible VMSS: " + e.getMessage(), 0.0);
            return;
        }

        log.info("Found {} Regular VMs to replace in Flexible VMSS {}", regularVms.size(), vmss.name());

        if (regularVms.isEmpty()) {
            recordEvent(account, vmss.name(), region, "ENABLED",
                    "AutoSpotting enabled (Flexible VMSS). No Regular VMs found — already Spot or empty.", 0.0);
            return;
        }

        double onDemandPrice = VM_ONDEMAND_PRICES.getOrDefault(
                vmss.sku() != null ? vmss.sku().toString() : "", 0.10);
        double spotPrice = onDemandPrice * (1.0 - SPOT_SAVINGS_RATIO);

        int replaced = 0;
        for (VirtualMachine regularVm : regularVms) {
            try {
                replaced += replaceFlexibleVmWithSpot(azure, vmss, regularVm, account, onDemandPrice, spotPrice);
            } catch (Exception e) {
                log.error("Failed to replace Flexible VM {} in VMSS {}: {}",
                        regularVm.name(), vmss.name(), e.getMessage());
            }
        }

        invalidateCaches(subscriptionId);
        double totalHourlySavings = replaced * (onDemandPrice - spotPrice);
        recordEvent(account, vmss.name(), region, "ENABLED",
                String.format("AutoSpotting enabled (Flexible VMSS). Replaced %d Regular VM(s) with Spot VMs. Hourly savings: $%.4f",
                        replaced, totalHourlySavings),
                totalHourlySavings);
        if (totalHourlySavings > 0) {
            updateDailySavings(account, vmss.name(), region, totalHourlySavings);
        }
    }

    /**
     * Replace a single Regular VM in a Flexible VMSS with a new Spot VM.
     *
     * Steps:
     *   a) Read existing VM config: image reference, VM size, subnet, SSH public key
     *   b) Create new Spot VM attached to the same Flexible VMSS
     *   c) Wait for new VM to reach Running state (10 min timeout)
     *   d) Delete the old Regular VM
     *
     * Only Linux VMs with SSH public key auth are supported (password-based and Windows are skipped).
     *
     * @return 1 if replacement succeeded, 0 if skipped or failed
     */
    private int replaceFlexibleVmWithSpot(AzureResourceManager azure, VirtualMachineScaleSet vmss,
                                           VirtualMachine regularVm, CloudAccount account,
                                           double onDemandPrice, double spotPrice) throws InterruptedException {
        String resourceGroup = vmss.resourceGroupName();
        String region = vmss.regionName();
        // Spot VM name: "spot-<vmss-prefix>-<hex-timestamp>"
        String newVmName = "spot-" + vmss.name().substring(0, Math.min(6, vmss.name().length()))
                         + "-" + Long.toHexString(System.currentTimeMillis()).substring(4);

        log.info("Replacing Regular VM {} with Spot VM {} in Flexible VMSS {}",
                regularVm.name(), newVmName, vmss.name());

        // a) Read existing VM config
        var primaryNic = regularVm.getPrimaryNetworkInterface();
        if (primaryNic == null) {
            log.warn("VM {} has no primary NIC. Skipping.", regularVm.name());
            return 0;
        }
        var ipConfig  = primaryNic.primaryIPConfiguration();
        var network   = ipConfig.getNetwork();
        var subnetName = ipConfig.subnetName();

        var imageRef = regularVm.storageProfile().imageReference();
        if (imageRef == null || imageRef.publisher() == null) {
            log.warn("VM {} has no marketplace image reference (may be custom image). Skipping.", regularVm.name());
            return 0;
        }

        var osProfile = regularVm.innerModel().osProfile();
        if (osProfile == null) {
            log.warn("VM {} has no OS profile. Skipping.", regularVm.name());
            return 0;
        }
        var linuxConfig = osProfile.linuxConfiguration();
        if (linuxConfig == null || linuxConfig.ssh() == null
                || linuxConfig.ssh().publicKeys() == null
                || linuxConfig.ssh().publicKeys().isEmpty()) {
            log.warn("VM {} has no SSH public key (Windows or missing SSH config). Skipping Spot replacement.",
                    regularVm.name());
            return 0;
        }
        String adminUser = osProfile.adminUsername();
        String sshKey    = linuxConfig.ssh().publicKeys().get(0).keyData();

        // b) Create new Spot VM attached to the Flexible VMSS
        log.info("Creating Spot VM {} (image: {}/{}, size: {})",
                newVmName, imageRef.publisher(), imageRef.offer(), regularVm.size());
        VirtualMachine newVm;
        try {
            newVm = azure.virtualMachines()
                    .define(newVmName)
                    .withRegion(region)
                    .withExistingResourceGroup(resourceGroup)
                    .withExistingPrimaryNetwork(network)
                    .withSubnet(subnetName)
                    .withPrimaryPrivateIPAddressDynamic()
                    .withoutPrimaryPublicIPAddress()
                    .withSpecificLinuxImageVersion(imageRef)
                    .withRootUsername(adminUser)
                    .withSsh(sshKey)
                    .withSize(regularVm.size())
                    .withSpotPriority()   // eviction policy defaults to DEALLOCATE for individual Spot VMs
                    .withExistingVirtualMachineScaleSet(vmss)
                    .create();
        } catch (Exception e) {
            log.error("Failed to create Spot VM {} for Flexible VMSS {}: {}",
                    newVmName, vmss.name(), e.getMessage());
            return 0;
        }

        // c) Wait for new VM to reach Running state (timeout: 10 min)
        boolean healthy = waitForVmRunning(azure, resourceGroup, newVmName, 600_000L, 30_000L);
        if (!healthy) {
            log.warn("Spot VM {} did not reach Running state. Rolling back — deleting it.", newVmName);
            try { azure.virtualMachines().deleteByResourceGroup(resourceGroup, newVmName, true); }
            catch (Exception ignored) { log.debug("Cleanup of failed VM {} ignored", newVmName); }
            return 0;
        }

        // d) Delete old Regular VM
        log.info("Deleting Regular VM {} from Flexible VMSS {}", regularVm.name(), vmss.name());
        azure.virtualMachines().deleteByResourceGroup(resourceGroup, regularVm.name(), true);

        // Record replacement event
        SpotOptimizationEvent evt = new SpotOptimizationEvent();
        evt.setAccount(account);
        evt.setAsgName(vmss.name());
        evt.setRegion(region);
        evt.setOldInstanceId(regularVm.id());
        evt.setOldInstanceType(regularVm.size() != null ? regularVm.size().toString() : "unknown");
        evt.setNewInstanceId(newVm.id());
        evt.setNewInstanceType(newVm.size() != null ? newVm.size().toString() : "unknown");
        evt.setHourlySavings(onDemandPrice - spotPrice);
        evt.setStatus("SUCCESS");
        evt.setDetails("Regular VM replaced with Spot VM in Flexible VMSS (per-VM replacement).");
        eventRepository.save(evt);

        log.info("✅ Replaced Regular VM {} → Spot VM {} in Flexible VMSS {}",
                regularVm.name(), newVmName, vmss.name());
        return 1;
    }

    /**
     * Poll until a VM reaches Running power state.
     */
    private boolean waitForVmRunning(AzureResourceManager azure, String resourceGroup,
                                      String vmName, long timeoutMs, long pollIntervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollIntervalMs);
                VirtualMachine vm = azure.virtualMachines().getByResourceGroup(resourceGroup, vmName);
                if (vm != null && PowerState.RUNNING.equals(vm.powerState())) {
                    log.info("VM {} is now Running", vmName);
                    return true;
                }
                log.debug("Waiting for VM {} to reach Running state (current: {})", vmName,
                        vm != null ? vm.powerState() : "unknown");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("Error polling VM {}: {}", vmName, e.getMessage());
            }
        }
        return false;
    }

    /**
     * Disable AutoSpotting for a VMSS using the staged replacement loop (Spot → Regular).
     */
    @Async
    public void disableAutoSpotting(String subscriptionId, String resourceGroup, String vmssName) {
        log.info("🚀 [Azure AutoSpotting] Starting Spot disablement for VMSS {} in RG {}", vmssName, resourceGroup);

        CloudAccount account = resolveAccount(subscriptionId);

        // Persist enabled=false immediately so reloads show correct state
        vmssSpotConfigRepository
                .findByAccountIdAndResourceGroupAndVmssName(account.getId(), resourceGroup, vmssName)
                .ifPresent(c -> {
                    c.setEnabled(false);
                    vmssSpotConfigRepository.save(c);
                });
        invalidateCaches(subscriptionId);

        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(account.getAzureSubscriptionId());
            VirtualMachineScaleSet vmss = azure.virtualMachineScaleSets()
                    .getByResourceGroup(resourceGroup, vmssName);

            if (vmss == null) {
                log.error("VMSS {} not found in resource group {}", vmssName, resourceGroup);
                return;
            }

            if (!isSpotEnabled(vmss)) {
                log.info("VMSS {} already using Regular priority. Nothing to disable.", vmssName);
                return;
            }

            // For disabling, we log the action — full priority revert requires creating a new Regular VMSS
            // in Azure since priority cannot always be changed in-place.
            log.warn("Note: Azure does not support changing VMSS priority from Spot to Regular in-place. " +
                    "The VMSS {} will be marked as disabled in our tracking. " +
                    "To fully revert, create a new Regular VMSS and migrate workloads.", vmssName);

            invalidateCaches(subscriptionId);
            recordEvent(account, vmssName, vmss.regionName(), "DISABLED",
                    "AutoSpotting disabled for VMSS. Note: Azure VMSS priority cannot be changed in-place from Spot to Regular. " +
                            "Create a new Regular VMSS to fully revert.", 0.0);

            log.info("✅ [Azure AutoSpotting] Disabled tracking for VMSS {}", vmssName);

        } catch (Exception e) {
            log.error("❌ [Azure AutoSpotting] Failed to disable Spot for VMSS {}: {}", vmssName, e.getMessage(), e);
        }
    }

    // =================== INSTANCE LISTING ===================

    public List<SpotInstanceDto> getSpotInstances(String subscriptionId) {
        return getSpotInstances(subscriptionId, false);
    }

    public List<SpotInstanceDto> getSpotInstances(String subscriptionId, boolean forceRefresh) {
        String cacheKey = CACHE_KEY_INSTANCES + subscriptionId;

        if (!forceRefresh) {
            Optional<List<SpotInstanceDto>> cached = redisCacheService.get(
                    cacheKey, new TypeReference<List<SpotInstanceDto>>() {});
            if (cached.isPresent()) {
                log.info("--- AZURE AUTOSPOTTING INSTANCES: loaded from Redis for subscriptionId={} ---", subscriptionId);
                return cached.get();
            }
        }

        List<SpotInstanceDto> instances = new ArrayList<>();
        try {
            CloudAccount account = resolveAccount(subscriptionId);
            AzureResourceManager azure = azureClientProvider.getAzureClient(account.getAzureSubscriptionId());

            for (VirtualMachineScaleSet vmss : azure.virtualMachineScaleSets().list()) {
                boolean vmssIsSpot = isSpotEnabled(vmss);
                String vmSku = vmss.sku() != null ? vmss.sku().toString() : "unknown";

                try {
                    for (VirtualMachineScaleSetVM vm : vmss.virtualMachines().list()) {
                        String powerState = vm.powerState() != null ? vm.powerState().toString() : "unknown";
                        String lifecycle = vmssIsSpot ? "spot" : "on-demand";
                        List<String> zones = vm.innerModel().zones();
                        String zone = (zones != null && !zones.isEmpty()) ? zones.get(0) : vmss.regionName();

                        instances.add(SpotInstanceDto.builder()
                                .instanceId(vm.instanceId())
                                .instanceType(vmSku)
                                .availabilityZone(zone)
                                .lifecycle(lifecycle)
                                .state(powerState.contains("/") ? powerState.substring(powerState.lastIndexOf('/') + 1) : powerState)
                                .asgName(vmss.name())
                                .region(vmss.regionName())
                                .build());
                    }
                } catch (Exception e) {
                    log.warn("Failed to list VMs for VMSS {}: {}", vmss.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to list spot instances for subscriptionId={}: {}", subscriptionId, e.getMessage(), e);
        }

        if (!instances.isEmpty()) {
            redisCacheService.put(cacheKey, instances, CACHE_TTL_MINUTES);
        }

        return instances;
    }

    // =================== HISTORY ===================

    public HistoryResponse getHistoryData(String subscriptionId, String range) {
        log.info("📊 [Azure] Fetching history data for subscriptionId={}, range={}", subscriptionId, range);
        CloudAccount account = resolveAccount(subscriptionId);

        LocalDate startDate;
        LocalDate endDate = LocalDate.now();

        switch (range) {
            case "last24h":
                startDate = LocalDate.now().minusDays(1);
                break;
            case "last30d":
                startDate = LocalDate.now().minusDays(30);
                break;
            case "last7d":
            default:
                startDate = LocalDate.now().minusDays(7);
                break;
        }

        List<DailySpotSavings> findings = savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(
                account.getId(), startDate, endDate);

        HistoryResponse response = new HistoryResponse();
        List<HistoryResponse.DataPoint> dataPoints = new ArrayList<>();
        double totalSaved = 0;

        for (DailySpotSavings saving : findings) {
            HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
            dp.setTimestamp(saving.getDate().toString());
            dp.setActualHourlySavings(saving.getAmountSaved());
            dp.setPotentialHourlySavings(saving.getAmountSaved() * 0.2);
            dataPoints.add(dp);
            totalSaved += saving.getAmountSaved();
        }

        // Fallback: build from SpotOptimizationEvent if no daily savings recorded yet
        if (dataPoints.isEmpty()) {
            try {
                log.info("📊 [Azure] No daily savings found, falling back to SpotOptimizationEvent table");
                Instant iStart = startDate.atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant iEnd = endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

                List<SpotOptimizationEvent> spotEvents = eventRepository
                        .findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(account.getId(), iStart, iEnd);

                Map<LocalDate, Double> savingsByDate = spotEvents.stream()
                        .filter(e -> e.getHourlySavings() != null && e.getHourlySavings() > 0)
                        .collect(Collectors.groupingBy(
                                e -> e.getEventTime().atZone(ZoneOffset.UTC).toLocalDate(),
                                Collectors.summingDouble(SpotOptimizationEvent::getHourlySavings)));

                List<Map.Entry<LocalDate, Double>> sortedEntries = new ArrayList<>(savingsByDate.entrySet());
                sortedEntries.sort(Map.Entry.comparingByKey());

                for (Map.Entry<LocalDate, Double> entry : sortedEntries) {
                    HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
                    dp.setTimestamp(entry.getKey().toString());
                    dp.setActualHourlySavings(entry.getValue());
                    dp.setPotentialHourlySavings(entry.getValue() * 0.2);
                    dataPoints.add(dp);
                    totalSaved += entry.getValue();
                }
            } catch (Exception ex) {
                log.warn("⚠️ [Azure] Could not build history from SpotOptimizationEvent: {}", ex.getMessage());
            }
        }

        // Gap-fill missing dates
        long rangeDays = startDate.until(endDate, ChronoUnit.DAYS) + 1;
        if (rangeDays <= 90) {
            java.util.Set<LocalDate> covered = dataPoints.stream()
                    .map(dp -> LocalDate.parse(dp.getTimestamp().substring(0, 10)))
                    .collect(Collectors.toCollection(java.util.HashSet::new));

            LocalDate cur = startDate;
            while (!cur.isAfter(endDate)) {
                if (!covered.contains(cur)) {
                    HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
                    dp.setTimestamp(cur.toString());
                    dp.setActualHourlySavings(0.0);
                    dp.setPotentialHourlySavings(0.0);
                    dataPoints.add(dp);
                }
                cur = cur.plusDays(1);
            }
            dataPoints.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
        }

        response.setDataPoints(dataPoints);

        HistoryResponse.HistorySummary summary = new HistoryResponse.HistorySummary();
        summary.setTotalActualSavings(totalSaved);
        summary.setAverageHourlySavings(!dataPoints.isEmpty() ? totalSaved / dataPoints.size() : 0);
        double peakSavings = dataPoints.stream()
                .mapToDouble(dp -> dp.getActualHourlySavings() != null ? dp.getActualHourlySavings() : 0.0)
                .max().orElse(0.0);
        summary.setPeakSavings(peakSavings);
        response.setSummary(summary);

        log.info("✅ [Azure] Returning {} data points, total savings: ${}", dataPoints.size(), totalSaved);
        return response;
    }

    // =================== EVENTS ===================

    public EventsResponse getEvents(String subscriptionId, String start, String end,
                                     String eventType, String vmssName) {
        log.info("📋 [Azure] Fetching events for subscriptionId={}", subscriptionId);
        CloudAccount account = resolveAccount(subscriptionId);

        try {
            Instant startTime = (start != null && !start.isEmpty())
                    ? Instant.parse(start)
                    : Instant.now().minus(7, ChronoUnit.DAYS);
            Instant endTime = (end != null && !end.isEmpty())
                    ? Instant.parse(end)
                    : Instant.now();

            List<SpotOptimizationEvent> events;
            if (vmssName != null && !vmssName.isEmpty()) {
                events = eventRepository.findByAccountIdAndAsgNameAndEventTimeBetweenOrderByEventTimeDesc(
                        account.getId(), vmssName, startTime, endTime);
            } else if (eventType != null && !eventType.isEmpty()) {
                String status = mapEventTypeToStatus(eventType);
                events = eventRepository.findByAccountIdAndEventTimeBetweenAndStatusOrderByEventTimeDesc(
                        account.getId(), startTime, endTime, status);
            } else {
                events = eventRepository.findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(
                        account.getId(), startTime, endTime);
            }

            List<EventRecord> eventRecords = events.stream().map(event -> {
                EventRecord record = new EventRecord();
                record.setTimestamp(event.getEventTime().toString());
                record.setAccountId(subscriptionId);
                record.setRegion(event.getRegion());
                record.setAsgName(event.getAsgName());
                record.setEstimatedSavings(event.getHourlySavings());

                String status = event.getStatus();
                if ("SUCCESS".equals(status)) {
                    record.setEventType("instance_replacement");
                    record.setResult("Success");
                    record.setOldInstanceId(event.getOldInstanceId());
                    record.setOldInstanceType(event.getOldInstanceType());
                    record.setOldLifecycle("on-demand");
                    record.setNewInstanceId(event.getNewInstanceId());
                    record.setNewInstanceType(event.getNewInstanceType());
                    record.setNewLifecycle("spot");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "Regular VM replaced with Spot VM. Hourly savings: $"
                            + String.format("%.4f", event.getHourlySavings() != null ? event.getHourlySavings() : 0.0));
                } else if ("INTERRUPTED".equals(status)) {
                    record.setEventType("spot_interruption");
                    record.setResult("Interrupted");
                    record.setOldInstanceId(event.getOldInstanceId());
                    record.setOldLifecycle("spot");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "Azure reclaimed this Spot VM. VMSS will automatically replace it.");
                } else if ("ENABLED".equals(status)) {
                    record.setEventType("vmss_enabled");
                    record.setResult("Enabled");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "AutoSpotting enabled for VMSS by user.");
                } else if ("DISABLED".equals(status)) {
                    record.setEventType("vmss_disabled");
                    record.setResult("Disabled");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "AutoSpotting disabled for VMSS by user.");
                } else {
                    record.setEventType("launch_failure");
                    record.setResult("Failed");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "Spot launch or replacement failed.");
                }
                return record;
            }).collect(Collectors.toList());

            int totalReplacements = (int) eventRecords.stream()
                    .filter(e -> "instance_replacement".equals(e.getEventType())).count();
            int totalInterruptions = (int) eventRecords.stream()
                    .filter(e -> "spot_interruption".equals(e.getEventType())).count();
            double totalSavings = eventRecords.stream()
                    .filter(e -> e.getEstimatedSavings() != null)
                    .mapToDouble(EventRecord::getEstimatedSavings).sum();

            EventsResponse response = new EventsResponse();
            response.setStart(startTime.toString());
            response.setEnd(endTime.toString());
            response.setCount(eventRecords.size());
            response.setEvents(eventRecords);

            EventsResponse.EventsSummary summary = new EventsResponse.EventsSummary();
            summary.setTotalReplacements(totalReplacements);
            summary.setTotalInterruptions(totalInterruptions);
            summary.setTotalEstimatedSavings(totalSavings);
            response.setSummary(summary);

            log.info("✅ [Azure] Retrieved {} events", eventRecords.size());
            return response;

        } catch (Exception e) {
            log.error("❌ [Azure] Failed to fetch events: {}", e.getMessage(), e);
            return createEmptyEventsResponse();
        }
    }

    // =================== HELPERS ===================

    private boolean isSpotEnabled(VirtualMachineScaleSet vmss) {
        try {
            var profile = vmss.innerModel().virtualMachineProfile();
            if (profile != null && profile.priority() != null) {
                String priority = profile.priority().toString();
                return "Spot".equalsIgnoreCase(priority) || "Low".equalsIgnoreCase(priority);
            }
        } catch (Exception e) {
            log.debug("Could not check priority for VMSS {}: {}", vmss.name(), e.getMessage());
        }
        return false;
    }

    private List<VirtualMachineScaleSetVM> getRegularVms(VirtualMachineScaleSet vmss) {
        // In a standard VMSS, all VMs share the same priority.
        // If the VMSS is Regular priority, all its VMs are Regular VMs.
        // After updating the profile to Spot, newly added VMs will be Spot.
        List<VirtualMachineScaleSetVM> vms = new ArrayList<>();
        try {
            for (VirtualMachineScaleSetVM vm : vmss.virtualMachines().list()) {
                String state = vm.powerState() != null ? vm.powerState().toString() : "";
                if (state.contains("running") || state.contains("starting")) {
                    vms.add(vm);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list VMs for VMSS {}: {}", vmss.name(), e.getMessage());
        }
        return vms;
    }

    private double estimateSavings(VirtualMachineScaleSet vmss) {
        String sku = vmss.sku() != null ? vmss.sku().toString() : "";
        double onDemandPrice = VM_ONDEMAND_PRICES.getOrDefault(sku, 0.10);
        int capacity = vmss.capacity();
        return capacity * onDemandPrice * SPOT_SAVINGS_RATIO;
    }

    private void recordEvent(CloudAccount account, String vmssName, String region,
                              String status, String details, double hourlySavings) {
        try {
            SpotOptimizationEvent event = new SpotOptimizationEvent();
            event.setAccount(account);
            event.setAsgName(vmssName);
            event.setRegion(region);
            event.setStatus(status);
            event.setDetails(details);
            event.setHourlySavings(hourlySavings);
            eventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to record event for VMSS {}: {}", vmssName, e.getMessage());
        }
    }

    private void updateDailySavings(CloudAccount account, String vmssName, String region, double hourlySavings) {
        try {
            if (hourlySavings <= 0) return;
            LocalDate today = LocalDate.now();
            DailySpotSavings daily = savingsRepository.findByAccountIdAndDate(account.getId(), today)
                    .orElseGet(() -> new DailySpotSavings(account, today));
            daily.setAmountSaved((daily.getAmountSaved() != null ? daily.getAmountSaved() : 0.0) + hourlySavings * 24);
            daily.setSwapCount((daily.getSwapCount() != null ? daily.getSwapCount() : 0) + 1);
            savingsRepository.save(daily);
        } catch (Exception e) {
            log.error("Failed to update daily savings for account {}: {}", account.getId(), e.getMessage());
        }
    }

    private void invalidateCaches(String subscriptionId) {
        redisCacheService.evict(CACHE_KEY_VMSS + subscriptionId);
        redisCacheService.evict(CACHE_KEY_INSTANCES + subscriptionId);
        log.debug("Invalidated Redis caches for Azure subscriptionId={}", subscriptionId);
    }

    private String mapEventTypeToStatus(String eventType) {
        switch (eventType) {
            case "instance_replacement": return "SUCCESS";
            case "spot_interruption":   return "INTERRUPTED";
            case "vmss_enabled":        return "ENABLED";
            case "vmss_disabled":       return "DISABLED";
            case "launch_failure":      return "FAILED";
            default:                    return "";
        }
    }

    private EventsResponse createEmptyEventsResponse() {
        EventsResponse response = new EventsResponse();
        response.setEvents(Collections.emptyList());
        response.setCount(0);
        EventsResponse.EventsSummary summary = new EventsResponse.EventsSummary();
        summary.setTotalReplacements(0);
        summary.setTotalInterruptions(0);
        summary.setTotalEstimatedSavings(0.0);
        response.setSummary(summary);
        return response;
    }

    // =================== ACCOUNT REGISTRATION ===================

    /**
     * Register / validate an Azure subscription with XamOps AutoSpotting.
     * Creates default settings if they don't exist and verifies connectivity.
     */
    public Map<String, Object> registerAccount(String subscriptionId) {
        log.info("[Azure AutoSpotting] registerAccount called for subscriptionId={}", subscriptionId);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            CloudAccount account = resolveAccount(subscriptionId);
            // Ensure settings row exists
            settingsRepository.findByAccountId(account.getId()).orElseGet(() -> {
                AutoSpottingSettings settings = new AutoSpottingSettings(account);
                return settingsRepository.save(settings);
            });
            // Quick connectivity check
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            long vmssCount = azure.virtualMachineScaleSets().list().stream().count();
            result.put("success", true);
            result.put("subscriptionId", subscriptionId);
            result.put("accountId", account.getId());
            result.put("vmssDiscovered", vmssCount);
            result.put("message", "Azure subscription registered successfully with AutoSpotting.");
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] registerAccount failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // =================== COST DATA ===================

    /**
     * Returns per-VMSS cost breakdown and subscription-level summary.
     * Azure equivalent of getCostData() from AutoSpottingService.
     */
    public CostResponse getCostData(String subscriptionId) {
        log.info("[Azure AutoSpotting] getCostData for subscriptionId={}", subscriptionId);
        List<AzureAutoSpottingGroupDto> vmssList = listVmssAllRegionsWithCosts(subscriptionId);

        List<ASGCostData> costItems = new ArrayList<>();
        double totalCurrentHourly = 0, totalOndemandHourly = 0, totalActualSavings = 0, totalPotentialSavings = 0;
        int enabledCount = 0;

        for (AzureAutoSpottingGroupDto vmss : vmssList) {
            double onDemandPrice = VM_ONDEMAND_PRICES.getOrDefault(vmss.vmSku, 0.10);
            double spotPrice = onDemandPrice * (1.0 - SPOT_SAVINGS_RATIO);

            ASGCostData item = new ASGCostData();
            item.setAccountId(subscriptionId);
            item.setAsgName(vmss.name);
            item.setRegion(vmss.region);
            item.setAutospottingEnabled(vmss.isEnabled);
            item.setInstanceCount(vmss.instanceCount);
            item.setOndemandInstanceCount(vmss.ondemandInstanceCount);
            item.setSpotInstanceCount(vmss.spotInstanceCount);
            item.setCurrentHourlyCost(vmss.currentHourlyCost);
            item.setOndemandHourlyCost(vmss.instanceCount * onDemandPrice);
            item.setSpotHourlyCost(vmss.instanceCount * spotPrice);
            item.setActualHourlySavings(vmss.actualHourlySavings);
            item.setPotentialHourlySavings(vmss.potentialHourlySavings);
            item.setInstanceTypes(Collections.singletonList(vmss.vmSku));
            costItems.add(item);

            totalCurrentHourly   += vmss.currentHourlyCost;
            totalOndemandHourly  += vmss.instanceCount * onDemandPrice;
            totalActualSavings   += vmss.actualHourlySavings;
            totalPotentialSavings += vmss.potentialHourlySavings;
            if (vmss.isEnabled) enabledCount++;
        }

        CostResponse.CostSummary summary = new CostResponse.CostSummary();
        summary.setTotalAsgCount(vmssList.size());
        summary.setAutospottingEnabledCount(enabledCount);
        summary.setTotalCurrentHourlyCost(totalCurrentHourly);
        summary.setTotalOndemandHourlyCost(totalOndemandHourly);
        summary.setTotalActualSavings(totalActualSavings);
        summary.setTotalPotentialSavings(totalPotentialSavings);

        CostResponse response = new CostResponse();
        response.setAsgs(costItems);
        response.setSummary(summary);
        response.setTimestamp(Instant.now().toString());
        return response;
    }

    /**
     * Returns a savings metrics summary map for dashboards.
     */
    public Map<String, Object> getSavingsMetrics(String subscriptionId) {
        log.info("[Azure AutoSpotting] getSavingsMetrics for subscriptionId={}", subscriptionId);
        List<AzureAutoSpottingGroupDto> vmssList = listVmssAllRegionsWithCosts(subscriptionId);

        double totalActualHourly = vmssList.stream().mapToDouble(v -> v.actualHourlySavings).sum();
        double totalPotentialHourly = vmssList.stream().mapToDouble(v -> v.potentialHourlySavings).sum();
        long enabledCount = vmssList.stream().filter(v -> v.isEnabled).count();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("actual_hourly_savings",      totalActualHourly);
        metrics.put("actual_monthly_savings",     totalActualHourly * 730);
        metrics.put("potential_hourly_savings",   totalPotentialHourly);
        metrics.put("potential_monthly_savings",  totalPotentialHourly * 730);
        metrics.put("enabled_vmss_count",         enabledCount);
        metrics.put("total_vmss_count",           vmssList.size());
        metrics.put("timestamp",                  Instant.now().toString());
        return metrics;
    }

    // =================== VMSS / ASG CONFIGURATION ===================

    /**
     * Get configured VM SKUs for a specific VMSS (Spot diversification config).
     */
    public Map<String, Object> getConfiguredVmSkus(String subscriptionId, String resourceGroup, String vmssName) {
        CloudAccount account = resolveAccount(subscriptionId);
        Optional<VmssSpotConfig> configOpt = vmssSpotConfigRepository
                .findByAccountIdAndResourceGroupAndVmssName(account.getId(), resourceGroup, vmssName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vmss_name", vmssName);
        result.put("resource_group", resourceGroup);
        if (configOpt.isPresent()) {
            VmssSpotConfig config = configOpt.get();
            result.put("allowed_vm_skus",          config.getAllowedVmSkus() != null ? config.getAllowedVmSkus() : "");
            result.put("spot_allocation_strategy", config.getSpotAllocationStrategy());
        } else {
            result.put("allowed_vm_skus",          "");
            result.put("spot_allocation_strategy", "mixed");
        }
        return result;
    }

    /**
     * Save VM SKU diversification config for a VMSS.
     */
    public Map<String, Object> saveVmSkuConfig(String subscriptionId, String resourceGroup,
                                                String vmssName, String allowedVmSkus, String strategy) {
        CloudAccount account = resolveAccount(subscriptionId);
        VmssSpotConfig config = vmssSpotConfigRepository
                .findByAccountIdAndResourceGroupAndVmssName(account.getId(), resourceGroup, vmssName)
                .orElseGet(() -> {
                    VmssSpotConfig c = new VmssSpotConfig();
                    c.setAccount(account);
                    c.setResourceGroup(resourceGroup);
                    c.setVmssName(vmssName);
                    c.setRegion("");
                    return c;
                });
        config.setAllowedVmSkus(allowedVmSkus);
        if (strategy != null && !strategy.isEmpty()) {
            config.setSpotAllocationStrategy(strategy);
        }
        vmssSpotConfigRepository.save(config);
        invalidateCaches(subscriptionId);
        return Map.of("success", true, "vmss_name", vmssName, "allowed_vm_skus", allowedVmSkus);
    }

    /**
     * Get Spot ratio configuration for a specific VMSS.
     */
    public Map<String, Object> getSpotConfig(String subscriptionId, String resourceGroup, String vmssName) {
        CloudAccount account = resolveAccount(subscriptionId);
        Optional<VmssSpotConfig> configOpt = vmssSpotConfigRepository
                .findByAccountIdAndResourceGroupAndVmssName(account.getId(), resourceGroup, vmssName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vmss_name", vmssName);
        result.put("resource_group", resourceGroup);
        if (configOpt.isPresent()) {
            VmssSpotConfig config = configOpt.get();
            result.put("spot_percentage", config.getSpotPercentage());
            result.put("on_demand_count", config.getOnDemandCount());
            result.put("spot_allocation_strategy", config.getSpotAllocationStrategy());
        } else {
            result.put("spot_percentage", null);
            result.put("on_demand_count", null);
            result.put("spot_allocation_strategy", "mixed");
        }
        return result;
    }

    /**
     * Save Spot ratio configuration for a VMSS.
     */
    public Map<String, Object> saveSpotConfig(String subscriptionId, String resourceGroup,
                                               String vmssName, Integer spotPercentage, Integer onDemandCount) {
        CloudAccount account = resolveAccount(subscriptionId);
        VmssSpotConfig config = vmssSpotConfigRepository
                .findByAccountIdAndResourceGroupAndVmssName(account.getId(), resourceGroup, vmssName)
                .orElseGet(() -> {
                    VmssSpotConfig c = new VmssSpotConfig();
                    c.setAccount(account);
                    c.setResourceGroup(resourceGroup);
                    c.setVmssName(vmssName);
                    c.setRegion("");
                    return c;
                });
        config.setSpotPercentage(spotPercentage);
        config.setOnDemandCount(onDemandCount);
        vmssSpotConfigRepository.save(config);
        return Map.of("success", true, "vmss_name", vmssName,
                "spot_percentage", String.valueOf(spotPercentage),
                "on_demand_count", String.valueOf(onDemandCount));
    }

    /**
     * Get combined per-VMSS configuration (SKU types + Spot ratio).
     */
    public Map<String, Object> getVmssConfig(String subscriptionId, String resourceGroup, String vmssName) {
        CloudAccount account = resolveAccount(subscriptionId);
        Optional<VmssSpotConfig> configOpt = vmssSpotConfigRepository
                .findByAccountIdAndResourceGroupAndVmssName(account.getId(), resourceGroup, vmssName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vmss_name", vmssName);
        result.put("resource_group", resourceGroup);
        result.put("subscription_id", subscriptionId);
        if (configOpt.isPresent()) {
            VmssSpotConfig config = configOpt.get();
            result.put("allowed_vm_skus",          config.getAllowedVmSkus() != null ? config.getAllowedVmSkus() : "");
            result.put("spot_allocation_strategy", config.getSpotAllocationStrategy());
            result.put("spot_percentage",          config.getSpotPercentage());
            result.put("on_demand_count",          config.getOnDemandCount());
        } else {
            result.put("allowed_vm_skus",          "");
            result.put("spot_allocation_strategy", "mixed");
            result.put("spot_percentage",          null);
            result.put("on_demand_count",          null);
        }
        return result;
    }

    /**
     * Update per-VMSS configuration (supports partial updates).
     */
    public Map<String, Object> updateVmssConfig(String subscriptionId, String resourceGroup,
                                                  String vmssName, Map<String, Object> updates) {
        CloudAccount account = resolveAccount(subscriptionId);
        VmssSpotConfig config = vmssSpotConfigRepository
                .findByAccountIdAndResourceGroupAndVmssName(account.getId(), resourceGroup, vmssName)
                .orElseGet(() -> {
                    VmssSpotConfig c = new VmssSpotConfig();
                    c.setAccount(account);
                    c.setResourceGroup(resourceGroup);
                    c.setVmssName(vmssName);
                    c.setRegion("");
                    return c;
                });

        if (updates.containsKey("allowed_vm_skus")) {
            config.setAllowedVmSkus((String) updates.get("allowed_vm_skus"));
        }
        if (updates.containsKey("spot_allocation_strategy")) {
            config.setSpotAllocationStrategy((String) updates.get("spot_allocation_strategy"));
        }
        if (updates.containsKey("spot_percentage")) {
            Object val = updates.get("spot_percentage");
            config.setSpotPercentage(val != null ? ((Number) val).intValue() : null);
        }
        if (updates.containsKey("on_demand_count")) {
            Object val = updates.get("on_demand_count");
            config.setOnDemandCount(val != null ? ((Number) val).intValue() : null);
        }

        vmssSpotConfigRepository.save(config);
        invalidateCaches(subscriptionId);
        return Map.of("success", true, "vmss_name", vmssName, "updated_fields", updates.keySet());
    }

    // =================== BACKFILL / SYNC ===================

    /**
     * Scan all currently-enabled VMSS and backfill SpotOptimizationEvents for ones missing records.
     * Equivalent to syncSpotEvents / backfillSpotEvents in the AWS service.
     */
    public Map<String, Object> backfillSpotEvents(String subscriptionId) {
        log.info("[Azure AutoSpotting] backfillSpotEvents for subscriptionId={}", subscriptionId);
        CloudAccount account = resolveAccount(subscriptionId);
        List<AzureAutoSpottingGroupDto> vmssList = listVmssAllRegionsWithCosts(subscriptionId, true);

        int backfilled = 0;
        for (AzureAutoSpottingGroupDto vmss : vmssList) {
            if (!vmss.isEnabled) continue;
            // Check if we have any event for this VMSS
            try {
                Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
                List<SpotOptimizationEvent> existing = eventRepository
                        .findByAccountIdAndAsgNameAndEventTimeBetweenOrderByEventTimeDesc(
                                account.getId(), vmss.name, since, Instant.now());
                if (existing.isEmpty()) {
                    recordEvent(account, vmss.name, vmss.region, "ENABLED",
                            "Backfilled: VMSS found with Spot priority active.", vmss.actualHourlySavings);
                    backfilled++;
                }
            } catch (Exception e) {
                log.warn("[Azure AutoSpotting] Backfill check failed for VMSS {}: {}", vmss.name, e.getMessage());
            }
        }
        log.info("[Azure AutoSpotting] Backfill complete: {} records created", backfilled);
        return Map.of("success", true, "backfilled_count", backfilled, "total_vmss_scanned", vmssList.size());
    }

    /**
     * Force-refresh the VMSS cache for a subscription.
     */
    public Map<String, Object> refreshRegionCache(String subscriptionId) {
        log.info("[Azure AutoSpotting] refreshRegionCache for subscriptionId={}", subscriptionId);
        invalidateCaches(subscriptionId);
        List<AzureAutoSpottingGroupDto> vmssList = listVmssAllRegionsWithCosts(subscriptionId, true);
        return Map.of("success", true, "vmss_refreshed", vmssList.size(),
                "message", "VMSS cache refreshed for subscription " + subscriptionId);
    }

    // =================== LAUNCH ANALYTICS ===================

    /**
     * Returns Spot replacement analytics: success rates, failures by type/region/zone.
     * Azure equivalent of getLaunchAnalytics() from AutoSpottingService.
     */
    public LaunchAnalyticsResponse getLaunchAnalytics(String subscriptionId, String range) {
        log.info("[Azure AutoSpotting] getLaunchAnalytics for subscriptionId={}, range={}", subscriptionId, range);
        CloudAccount account = resolveAccount(subscriptionId);

        LocalDate startDate;
        switch (range) {
            case "last24h": startDate = LocalDate.now().minusDays(1); break;
            case "last30d": startDate = LocalDate.now().minusDays(30); break;
            case "last90d": startDate = LocalDate.now().minusDays(90); break;
            default:        startDate = LocalDate.now().minusDays(7); break;
        }
        Instant iStart = startDate.atStartOfDay(ZoneOffset.UTC).toInstant();

        List<SpotOptimizationEvent> events = eventRepository
                .findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(account.getId(), iStart, Instant.now());

        int totalAttempts  = (int) events.stream().filter(e -> "SUCCESS".equals(e.getStatus()) || "FAILED".equals(e.getStatus())).count();
        int totalSuccesses = (int) events.stream().filter(e -> "SUCCESS".equals(e.getStatus())).count();
        int totalFailures  = totalAttempts - totalSuccesses;
        double successRate = totalAttempts > 0 ? (double) totalSuccesses / totalAttempts * 100.0 : 0.0;

        // Breakdown by VM SKU (instance type)
        Map<String, LaunchAnalyticsResponse.LaunchStats> byInstanceType = new HashMap<>();
        Map<String, LaunchAnalyticsResponse.LaunchStats> byAz = new HashMap<>();
        Map<String, LaunchAnalyticsResponse.LaunchStats> byRegion = new HashMap<>();
        Map<String, Integer> failureReasonMap = new HashMap<>();

        for (SpotOptimizationEvent evt : events) {
            if (!"SUCCESS".equals(evt.getStatus()) && !"FAILED".equals(evt.getStatus())) continue;
            boolean success = "SUCCESS".equals(evt.getStatus());

            // By instance type
            String iType = evt.getNewInstanceType() != null ? evt.getNewInstanceType() : "unknown";
            updateLaunchStats(byInstanceType, iType, success);

            // By region
            String region = evt.getRegion() != null ? evt.getRegion() : "unknown";
            updateLaunchStats(byRegion, region, success);

            // Failure reasons
            if (!success && evt.getDetails() != null && !evt.getDetails().isEmpty()) {
                String reason = evt.getDetails().length() > 80 ? evt.getDetails().substring(0, 80) : evt.getDetails();
                failureReasonMap.merge(reason, 1, (a, b) -> a + b);
            }
        }

        // Compute success rates for each breakdown
        byInstanceType.forEach((k, v) -> v.setSuccessRate(v.getAttempts() > 0 ? (double) v.getSuccesses() / v.getAttempts() * 100.0 : 0.0));
        byAz.forEach((k, v) -> v.setSuccessRate(v.getAttempts() > 0 ? (double) v.getSuccesses() / v.getAttempts() * 100.0 : 0.0));
        byRegion.forEach((k, v) -> v.setSuccessRate(v.getAttempts() > 0 ? (double) v.getSuccesses() / v.getAttempts() * 100.0 : 0.0));

        // Top failure reasons
        List<LaunchAnalyticsResponse.FailureReason> topFailureReasons = failureReasonMap.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(5)
                .map(e -> {
                    LaunchAnalyticsResponse.FailureReason fr = new LaunchAnalyticsResponse.FailureReason();
                    fr.setReason(e.getKey());
                    fr.setCount(e.getValue());
                    fr.setPercentage(totalFailures > 0 ? (double) e.getValue() / totalFailures * 100.0 : 0.0);
                    return fr;
                }).collect(Collectors.toList());

        // Recommended VM SKUs (highest success rate with min 3 attempts)
        List<LaunchAnalyticsResponse.RecommendedType> recommendedTypes = byInstanceType.entrySet().stream()
                .filter(e -> e.getValue().getAttempts() >= 3)
                .sorted((a, b) -> Double.compare(b.getValue().getSuccessRate(), a.getValue().getSuccessRate()))
                .limit(5)
                .map(e -> {
                    LaunchAnalyticsResponse.RecommendedType rt = new LaunchAnalyticsResponse.RecommendedType();
                    rt.setInstanceType(e.getKey());
                    rt.setRegion(subscriptionId);
                    rt.setSuccessRate(e.getValue().getSuccessRate());
                    rt.setTotalLaunches(e.getValue().getAttempts());
                    rt.setAvgLaunchTime("~5-10 min");
                    rt.setScore(e.getValue().getSuccessRate() * Math.log(1 + e.getValue().getAttempts()));
                    return rt;
                }).collect(Collectors.toList());

        LaunchAnalyticsResponse response = new LaunchAnalyticsResponse();
        response.setTotalAttempts(totalAttempts);
        response.setTotalSuccesses(totalSuccesses);
        response.setTotalFailures(totalFailures);
        response.setSuccessRate(successRate);
        response.setByInstanceType(byInstanceType);
        response.setByAz(byAz);
        response.setByRegion(byRegion);
        response.setTopFailureReasons(topFailureReasons);
        response.setRecommendedTypes(recommendedTypes);
        return response;
    }

    private void updateLaunchStats(Map<String, LaunchAnalyticsResponse.LaunchStats> map, String key, boolean success) {
        map.computeIfAbsent(key, k -> {
            LaunchAnalyticsResponse.LaunchStats s = new LaunchAnalyticsResponse.LaunchStats();
            s.setAttempts(0); s.setSuccesses(0); s.setFailures(0); s.setSuccessRate(0.0);
            return s;
        });
        LaunchAnalyticsResponse.LaunchStats s = map.get(key);
        s.setAttempts(s.getAttempts() + 1);
        if (success) s.setSuccesses(s.getSuccesses() + 1);
        else         s.setFailures(s.getFailures() + 1);
    }

    // =================== RESERVATION / COMMITMENT COVERAGE ===================

    /**
     * Returns Azure Reserved Instance / Savings Plan coverage data.
     * Equivalent to getRiSpCoverage() from AutoSpottingService.
     * Uses static/derived data since Azure Reservation APIs require billing scope access.
     */
    public RiSpCoverageResponse getReservationCoverage(String subscriptionId) {
        log.info("[Azure AutoSpotting] getReservationCoverage for subscriptionId={}", subscriptionId);
        resolveAccount(subscriptionId); // validates account exists

        RiSpCoverageResponse response = new RiSpCoverageResponse();
        response.setAccountId(subscriptionId);
        response.setCollectedAt(Instant.now().toString());
        response.setHasBillingAccess(false); // Would require Azure Cost Management API with billing reader role

        // Derive coverage estimates from VMSS data and savings history
        List<AzureAutoSpottingGroupDto> vmssList = listVmssAllRegionsWithCosts(subscriptionId);
        long enabledVmss = vmssList.stream().filter(v -> v.isEnabled).count();
        double utilPct = vmssList.isEmpty() ? 0.0 : (double) enabledVmss / vmssList.size() * 100.0;

        response.setTotalRiUtilizationPercent(utilPct);
        response.setTotalSpUtilizationPercent(0.0); // Azure Savings Plans not directly tracked here

        // Per-SKU coverage entries
        List<RiSpCoverageResponse.RiCoverage> riCoverages = vmssList.stream()
                .filter(v -> v.isEnabled)
                .map(v -> {
                    RiSpCoverageResponse.RiCoverage cov = new RiSpCoverageResponse.RiCoverage();
                    cov.setInstanceType(v.vmSku);
                    cov.setInstanceFamily(v.vmSku.contains("_") ? v.vmSku.substring(0, v.vmSku.indexOf('_', v.vmSku.indexOf('_') + 1)) : v.vmSku);
                    cov.setRegion(v.region);
                    cov.setAvailabilityZone(v.region);
                    cov.setScope("subscription");
                    cov.setCoveragePercent(100.0); // Spot-enabled VMSS = 100% spot coverage
                    return cov;
                }).collect(Collectors.toList());

        response.setRiCoverages(riCoverages);
        response.setSpCoverages(Collections.emptyList());
        response.setRiUtilizations(Collections.emptyList());
        response.setSpUtilizations(Collections.emptyList());
        return response;
    }

    // =================== SETTINGS ===================

    /**
     * Get AutoSpotting settings for an Azure subscription.
     */
    public AutoSpottingSettings getSettings(String subscriptionId) {
        log.info("[Azure AutoSpotting] getSettings for subscriptionId={}", subscriptionId);
        CloudAccount account = resolveAccount(subscriptionId);
        return settingsRepository.findByAccountId(account.getId())
                .orElseGet(() -> {
                    AutoSpottingSettings settings = new AutoSpottingSettings(account);
                    return settingsRepository.save(settings);
                });
    }

    /**
     * Update the Reservation/Savings-Plan respect settings for AutoSpotting.
     */
    public AutoSpottingSettings updateRispRespectSettings(String subscriptionId,
                                                           boolean enabled, int threshold) {
        log.info("[Azure AutoSpotting] updateRispRespectSettings subscriptionId={}, enabled={}, threshold={}",
                subscriptionId, enabled, threshold);
        CloudAccount account = resolveAccount(subscriptionId);
        AutoSpottingSettings settings = settingsRepository.findByAccountId(account.getId())
                .orElseGet(() -> new AutoSpottingSettings(account));
        settings.setRespectRiSpCommitments(enabled);
        settings.setRiSpUtilizationThreshold(threshold);
        return settingsRepository.save(settings);
    }
}
