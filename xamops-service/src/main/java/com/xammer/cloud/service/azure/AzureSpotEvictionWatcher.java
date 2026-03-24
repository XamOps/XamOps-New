package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.PowerState;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.compute.models.VirtualMachinePriorityTypes;
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSet;
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSetVM;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.domain.VmssSpotConfig;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.repository.VmssSpotConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * AzureSpotEvictionWatcher
 *
 * Background component that monitors all AutoSpotting-enabled Azure VMSS every 5 minutes
 * and detects Spot VM evictions. When Azure reclaims a Spot VM (power state transitions
 * to Deallocated), this watcher:
 *
 *   1. Records an INTERRUPTED event in the DB (so the UI shows the eviction)
 *   2. Triggers replacement:
 *      - Flexible VMSS: calls enableAutoSpotting to create a new Spot VM in its place
 *      - Uniform VMSS:  scales VMSS up by 1 so Azure creates a new Spot VM from the profile
 *
 * This keeps the VMSS at full Spot capacity automatically after Azure-side evictions,
 * without any manual intervention from the client.
 *
 * Detection logic:
 *   A Spot VM is considered evicted when its power state is Deallocated AND:
 *   - For Flexible VMSS: the VM's priority is Spot (it was a Spot VM, now gone)
 *   - For Uniform VMSS:  the VMSS profile has Spot priority (all VMs should be Spot)
 *
 * Note: Azure provides a 30-second eviction warning via the Instance Metadata Scheduled
 * Events endpoint (http://169.254.169.254/metadata/scheduledevents). That endpoint is only
 * accessible from within the VM itself. This watcher uses polling from outside — sufficient
 * for recovery (not pre-eviction drain). For pre-eviction drain, install the Azure Metadata
 * Agent on the VM and have it call our webhook.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AzureSpotEvictionWatcher {

    private final AzureClientProvider        azureClientProvider;
    private final VmssSpotConfigRepository   vmssSpotConfigRepository;
    private final SpotOptimizationEventRepository eventRepository;
    private final AzureAutoSpottingService   autoSpottingService;

    /**
     * Run every 5 minutes across all enabled VMSS.
     * Fixed delay (not fixed rate) ensures the next run starts only after the current one finishes —
     * prevents overlap when Azure API calls are slow.
     */
    @Scheduled(fixedDelay = 300_000L)
    public void detectAndReplaceEvictedSpotVms() {
        log.debug("[EvictionWatcher] Starting eviction scan...");

        List<VmssSpotConfig> enabledConfigs;
        try {
            enabledConfigs = vmssSpotConfigRepository.findByEnabledTrue();
        } catch (Exception e) {
            log.error("[EvictionWatcher] Could not load enabled VMSS configs: {}", e.getMessage());
            return;
        }

        if (enabledConfigs.isEmpty()) {
            log.debug("[EvictionWatcher] No enabled VMSS found. Skipping scan.");
            return;
        }

        log.info("[EvictionWatcher] Scanning {} enabled VMSS for evicted Spot VMs...", enabledConfigs.size());
        int totalEvictions = 0;

        for (VmssSpotConfig config : enabledConfigs) {
            CloudAccount account = config.getAccount();
            if (account == null || account.getAzureSubscriptionId() == null) continue;

            // Skip VMSS currently being processed by the enable/disable loop —
            // prevents concurrent modification of the same VMSS
            String operationKey = config.getResourceGroup() + "/" + config.getVmssName();
            if (AzureAutoSpottingService.ACTIVE_OPERATIONS.contains(operationKey)) {
                log.debug("[EvictionWatcher] Skipping {} — enable operation in progress.", config.getVmssName());
                continue;
            }

            try {
                AzureResourceManager azure = azureClientProvider
                        .getAzureClient(account.getAzureSubscriptionId());
                VirtualMachineScaleSet vmss = azure.virtualMachineScaleSets()
                        .getByResourceGroup(config.getResourceGroup(), config.getVmssName());

                if (vmss == null) {
                    log.warn("[EvictionWatcher] VMSS {} not found — may have been deleted. Skipping.",
                            config.getVmssName());
                    continue;
                }

                // Determine orchestration mode
                boolean isFlexible = isFlexibleOrchestration(vmss);

                int evictions = isFlexible
                        ? scanFlexibleVmssForEvictions(azure, vmss, account, config)
                        : scanUniformVmssForEvictions(azure, vmss, account, config);

                totalEvictions += evictions;

            } catch (Exception e) {
                log.warn("[EvictionWatcher] Error scanning VMSS {}: {}", config.getVmssName(), e.getMessage());
            }
        }

        if (totalEvictions > 0) {
            log.info("[EvictionWatcher] Scan complete. Detected and triggered replacement for {} evicted Spot VM(s).",
                    totalEvictions);
        } else {
            log.debug("[EvictionWatcher] Scan complete. No evictions detected.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Flexible VMSS: check each VM with Spot priority for Deallocated state
    // ─────────────────────────────────────────────────────────────────────────────

    private int scanFlexibleVmssForEvictions(AzureResourceManager azure, VirtualMachineScaleSet vmss,
                                              CloudAccount account, VmssSpotConfig config) {
        int evictionsFound = 0;
        try {
            for (VirtualMachine vm : azure.virtualMachines().listByResourceGroup(config.getResourceGroup())) {
                // Only VMs belonging to this VMSS
                var vmssRef = vm.innerModel().virtualMachineScaleSet();
                if (vmssRef == null || !vmss.id().equalsIgnoreCase(vmssRef.id())) continue;

                // Only Spot VMs
                var priority = vm.innerModel().priority();
                if (priority == null || !VirtualMachinePriorityTypes.SPOT.equals(priority)) continue;

                // Eviction = Spot VM is now Deallocated (Azure reclaimed it)
                PowerState state = vm.powerState();
                if (PowerState.DEALLOCATED.equals(state) || PowerState.DEALLOCATING.equals(state)) {
                    log.warn("[EvictionWatcher] 🔴 Evicted Spot VM detected: {} in Flexible VMSS {} (state={})",
                            vm.name(), vmss.name(), state);

                    // Record the eviction event in the DB
                    recordEvictionEvent(account, vmss.name(), vmss.regionName(), vm.id(),
                            vm.size() != null ? vm.size().toString() : "unknown");
                    evictionsFound++;

                    // Trigger recovery: try to restart the Deallocated Spot VM first (cheapest).
                    // If Azure cannot re-allocate Spot capacity, the VM is deleted and a fresh
                    // Spot VM is created in its place.
                    // NOTE: enableAutoSpotting() is NOT used here — it only replaces Regular→Spot
                    // VMs and would find zero candidates (evicted VM still has Spot priority).
                    log.info("[EvictionWatcher] Triggering eviction recovery for Flexible VMSS {} VM {}.",
                            vmss.name(), vm.name());
                    autoSpottingService.recoverEvictedSpotVm(
                            account.getAzureSubscriptionId(),
                            config.getResourceGroup(),
                            config.getVmssName(),
                            vm.name());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[EvictionWatcher] Error scanning Flexible VMSS {} for evictions: {}",
                    vmss.name(), e.getMessage());
        }
        return evictionsFound;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Uniform VMSS: if profile is Spot, check for any Deallocated instances
    // ─────────────────────────────────────────────────────────────────────────────

    private int scanUniformVmssForEvictions(AzureResourceManager azure, VirtualMachineScaleSet vmss,
                                             CloudAccount account, VmssSpotConfig config) {
        // Only monitor Uniform VMSS that are confirmed to have Spot priority profile
        if (!isSpotProfile(vmss)) {
            // VMSS doesn't have Spot profile yet — not yet converted, skip eviction check
            return 0;
        }

        int evictionsFound = 0;
        try {
            for (VirtualMachineScaleSetVM vm : vmss.virtualMachines().list()) {
                PowerState state = vm.powerState();
                if (PowerState.DEALLOCATED.equals(state) || PowerState.DEALLOCATING.equals(state)) {
                    log.warn("[EvictionWatcher] 🔴 Evicted Spot instance detected: instance {} in Uniform VMSS {} (state={})",
                            vm.instanceId(), vmss.name(), state);

                    recordEvictionEvent(account, vmss.name(), vmss.regionName(), vm.instanceId(),
                            vmss.sku() != null ? vmss.sku().toString() : "unknown");
                    evictionsFound++;

                    // For Uniform VMSS recovery:
                    //   Step 1: Scale up by 1 — Azure creates a new Spot VM from the profile.
                    //   Step 2: Delete the Deallocated instance — prevents permanent capacity inflation (+1 cost).
                    // Both steps must happen; skipping Step 2 was a cost leak in the old code.
                    log.info("[EvictionWatcher] Scaling up Uniform VMSS {} by 1 to replace evicted Spot instance {}",
                            vmss.name(), vm.instanceId());
                    final String evictedInstanceId = vm.instanceId();
                    try {
                        int currentCapacity = Math.max(vmss.capacity(), 0);
                        vmss.update().withCapacity(currentCapacity + 1).apply();
                        log.info("[EvictionWatcher] Scaled up VMSS {} to capacity {}. Now removing evicted instance {}.",
                                vmss.name(), currentCapacity + 1, evictedInstanceId);
                        // Delete the Deallocated instance to restore original desired capacity
                        try {
                            vmss.virtualMachines().deleteInstances(
                                    java.util.Collections.singleton(evictedInstanceId), true);
                            log.info("[EvictionWatcher] Deleted evicted instance {} from Uniform VMSS {}. Capacity restored to {}.",
                                    evictedInstanceId, vmss.name(), currentCapacity);
                        } catch (Exception deleteEx) {
                            log.warn("[EvictionWatcher] Could not delete evicted instance {} from VMSS {} — " +
                                    "capacity is temporarily {} (extra VM cost). Manual cleanup may be needed. Error: {}",
                                    evictedInstanceId, vmss.name(), currentCapacity + 1, deleteEx.getMessage());
                        }
                    } catch (Exception scaleEx) {
                        log.error("[EvictionWatcher] Failed to scale up VMSS {} for eviction recovery: {}",
                                vmss.name(), scaleEx.getMessage());
                    }
                    // Handle one eviction per scan cycle — next 5-min cycle catches remaining evictions
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[EvictionWatcher] Error scanning Uniform VMSS {} for evictions: {}",
                    vmss.name(), e.getMessage());
        }
        return evictionsFound;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private void recordEvictionEvent(CloudAccount account, String vmssName, String region,
                                      String vmId, String vmType) {
        try {
            SpotOptimizationEvent event = new SpotOptimizationEvent();
            event.setAccount(account);
            event.setAsgName(vmssName);
            event.setRegion(region);
            event.setOldInstanceId(vmId);
            event.setOldInstanceType(vmType);
            event.setStatus("INTERRUPTED");
            event.setDetails("Spot VM evicted by Azure (power state = Deallocated). " +
                    "AutoSpotting is triggering automatic replacement.");
            event.setHourlySavings(0.0);
            eventRepository.save(event);
        } catch (Exception e) {
            log.error("[EvictionWatcher] Failed to record eviction event for VMSS {}: {}", vmssName, e.getMessage());
        }
    }

    private boolean isFlexibleOrchestration(VirtualMachineScaleSet vmss) {
        try {
            var orchMode = vmss.innerModel().orchestrationMode();
            if (orchMode != null && orchMode.toString().equalsIgnoreCase("Flexible")) {
                return true;
            }
        } catch (Exception ignored) { }
        // Fallback: Flexible VMSS often has no shared VM profile
        return vmss.innerModel().virtualMachineProfile() == null;
    }

    private boolean isSpotProfile(VirtualMachineScaleSet vmss) {
        try {
            var profile = vmss.innerModel().virtualMachineProfile();
            if (profile != null && profile.priority() != null) {
                String p = profile.priority().toString();
                return "Spot".equalsIgnoreCase(p) || "Low".equalsIgnoreCase(p);
            }
        } catch (Exception ignored) { }
        return false;
    }
}
