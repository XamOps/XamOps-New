package com.xammer.cloud.controller.azure;

import com.xammer.cloud.domain.AutoSpottingSettings;
import com.xammer.cloud.dto.autospotting.CostResponse;
import com.xammer.cloud.dto.autospotting.EventsResponse;
import com.xammer.cloud.dto.autospotting.HistoryResponse;
import com.xammer.cloud.dto.autospotting.LaunchAnalyticsResponse;
import com.xammer.cloud.dto.autospotting.RiSpCoverageResponse;
import com.xammer.cloud.dto.autospotting.SpotInstanceDto;
import com.xammer.cloud.service.azure.AzureAutoSpottingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Azure AutoSpotting REST Controller
 *
 * Exposes endpoints for managing Spot VM automation on Azure Virtual Machine Scale Sets (VMSS).
 * Base URL: /api/azure/autospotting
 */
@RestController
@RequestMapping("/api/azure/autospotting")
@RequiredArgsConstructor
@Slf4j
public class AzureAutoSpottingController {

    private final AzureAutoSpottingService azureAutoSpottingService;

    // =================== VMSS LISTING ===================

    /**
     * List all VMSS in the Azure subscription with Spot status and cost data.
     * GET /api/azure/autospotting/vmss/all-regions/{subscriptionId}
     */
    @GetMapping("/vmss/all-regions/{subscriptionId}")
    public ResponseEntity<?> listVmssAllRegions(@PathVariable String subscriptionId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        log.info("🚀 [Azure AutoSpotting] listVmssAllRegions called for subscriptionId: {}, refresh={}", subscriptionId, refresh);
        try {
            List<AzureAutoSpottingService.AzureAutoSpottingGroupDto> vmssList =
                    azureAutoSpottingService.listVmssAllRegionsWithCosts(subscriptionId, refresh);
            log.info("✅ [Azure AutoSpotting] Found {} VMSS for subscriptionId: {}", vmssList.size(), subscriptionId);
            return ResponseEntity.ok(vmssList);
        } catch (Exception e) {
            log.error("❌ [Azure AutoSpotting] Failed to list VMSS for {}: {}", subscriptionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to list VMSS: " + e.getMessage()));
        }
    }

    // =================== INSTANCE LISTING ===================

    /**
     * List all VM instances across VMSS with spot/on-demand status.
     * GET /api/azure/autospotting/instances/{subscriptionId}
     */
    @GetMapping("/instances/{subscriptionId}")
    public ResponseEntity<?> getSpotInstances(@PathVariable String subscriptionId) {
        log.info("Azure AutoSpottingController.getSpotInstances called with subscriptionId={}", subscriptionId);
        try {
            List<SpotInstanceDto> instances = azureAutoSpottingService.getSpotInstances(subscriptionId);
            return ResponseEntity.ok(instances);
        } catch (Exception e) {
            log.error("Failed to list Azure spot instances: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch instances: " + e.getMessage()));
        }
    }

    // =================== HISTORY ===================

    /**
     * Get historical savings data.
     * GET /api/azure/autospotting/history/{subscriptionId}?range=last7d
     */
    @GetMapping("/history/{subscriptionId}")
    public ResponseEntity<?> getHistory(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "last7d") String range) {
        log.info("Azure AutoSpottingController.getHistory called with subscriptionId={}, range={}", subscriptionId, range);
        try {
            HistoryResponse history = azureAutoSpottingService.getHistoryData(subscriptionId, range);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            log.error("Failed to get Azure AutoSpotting history: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =================== EVENTS ===================

    /**
     * Get events / actions history.
     * GET /api/azure/autospotting/events/{subscriptionId}
     *
     * Query params: start, end, eventType, vmssName
     */
    @GetMapping("/events/{subscriptionId}")
    public ResponseEntity<?> getEvents(
            @PathVariable String subscriptionId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String vmssName) {
        log.info("Azure AutoSpottingController.getEvents called with subscriptionId={}, start={}, end={}, eventType={}, vmssName={}",
                subscriptionId, start, end, eventType, vmssName);
        try {
            EventsResponse events = azureAutoSpottingService.getEvents(subscriptionId, start, end, eventType, vmssName);
            return ResponseEntity.ok(events);
        } catch (Exception e) {
            log.error("Failed to get Azure AutoSpotting events: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =================== ENABLE / DISABLE ===================

    /**
     * Enable AutoSpotting for a VMSS. Starts the controlled staged-replacement loop asynchronously.
     * POST /api/azure/autospotting/enable?subscriptionId=&resourceGroup=&vmssName=
     *
     * Returns 202 Accepted immediately; the control loop runs in the background.
     */
    @PostMapping("/enable")
    public ResponseEntity<?> enableAutoSpotting(
            @RequestParam String subscriptionId,
            @RequestParam String resourceGroup,
            @RequestParam String vmssName) {
        log.info("🚀 [Azure AutoSpotting] enableAutoSpotting called - subscriptionId: {}, resourceGroup: {}, vmssName: {}",
                subscriptionId, resourceGroup, vmssName);
        try {
            // @Async — returns immediately; control loop runs in background
            azureAutoSpottingService.enableAutoSpotting(subscriptionId, resourceGroup, vmssName);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "success", true,
                    "message", "Spot enablement started for VMSS " + vmssName +
                            ". The controlled replacement loop is running in the background."));
        } catch (Exception e) {
            log.error("❌ [Azure AutoSpotting] Failed to start enablement for {}: {}", vmssName, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    /**
     * Disable AutoSpotting for a VMSS.
     * POST /api/azure/autospotting/disable?subscriptionId=&resourceGroup=&vmssName=
     *
     * Returns 202 Accepted immediately; the operation runs in the background.
     */
    @PostMapping("/disable")
    public ResponseEntity<?> disableAutoSpotting(
            @RequestParam String subscriptionId,
            @RequestParam String resourceGroup,
            @RequestParam String vmssName) {
        log.info("🚀 [Azure AutoSpotting] disableAutoSpotting called - subscriptionId: {}, resourceGroup: {}, vmssName: {}",
                subscriptionId, resourceGroup, vmssName);
        try {
            // @Async — returns immediately
            azureAutoSpottingService.disableAutoSpotting(subscriptionId, resourceGroup, vmssName);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "success", true,
                    "message", "AutoSpotting disabled for VMSS " + vmssName + "."));
        } catch (Exception e) {
            log.error("❌ [Azure AutoSpotting] Failed to disable for {}: {}", vmssName, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    // =================== ACCOUNT REGISTRATION ===================

    /**
     * Register / validate an Azure subscription with AutoSpotting.
     * POST /api/azure/autospotting/register/{subscriptionId}
     */
    @PostMapping("/register/{subscriptionId}")
    public ResponseEntity<?> registerAccount(@PathVariable String subscriptionId) {
        log.info("[Azure AutoSpotting] registerAccount called for subscriptionId={}", subscriptionId);
        try {
            Map<String, Object> result = azureAutoSpottingService.registerAccount(subscriptionId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] registerAccount failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // =================== COST DATA ===================

    /**
     * Get per-VMSS cost breakdown with subscription-level summary.
     * GET /api/azure/autospotting/costs/{subscriptionId}
     */
    @GetMapping("/costs/{subscriptionId}")
    public ResponseEntity<?> getCostData(@PathVariable String subscriptionId) {
        log.info("[Azure AutoSpotting] getCostData called for subscriptionId={}", subscriptionId);
        try {
            CostResponse response = azureAutoSpottingService.getCostData(subscriptionId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] getCostData failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Get historical cost/savings data (alias for history endpoint, returns CostResponse shape).
     * GET /api/azure/autospotting/costs/history/{subscriptionId}?range=last7d
     */
    @GetMapping("/costs/history/{subscriptionId}")
    public ResponseEntity<?> getCostHistory(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "last7d") String range) {
        log.info("[Azure AutoSpotting] getCostHistory called for subscriptionId={}, range={}", subscriptionId, range);
        try {
            HistoryResponse response = azureAutoSpottingService.getHistoryData(subscriptionId, range);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] getCostHistory failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get high-level savings metrics summary.
     * GET /api/azure/autospotting/savings/{subscriptionId}
     */
    @GetMapping("/savings/{subscriptionId}")
    public ResponseEntity<?> getSavingsMetrics(@PathVariable String subscriptionId) {
        log.info("[Azure AutoSpotting] getSavingsMetrics called for subscriptionId={}", subscriptionId);
        try {
            Map<String, Object> metrics = azureAutoSpottingService.getSavingsMetrics(subscriptionId);
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] getSavingsMetrics failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =================== VMSS CONFIGURATION ===================

    /**
     * Get VM SKU diversification config for a specific VMSS.
     * GET /api/azure/autospotting/vmss/{subscriptionId}/sku-types?resourceGroup=&vmssName=
     */
    @GetMapping("/vmss/{subscriptionId}/sku-types")
    public ResponseEntity<?> getConfiguredVmSkus(
            @PathVariable String subscriptionId,
            @RequestParam String resourceGroup,
            @RequestParam String vmssName) {
        try {
            return ResponseEntity.ok(azureAutoSpottingService.getConfiguredVmSkus(subscriptionId, resourceGroup, vmssName));
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] getConfiguredVmSkus failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Save VM SKU diversification config for a specific VMSS.
     * PUT /api/azure/autospotting/vmss/{subscriptionId}/sku-types
     */
    @PutMapping("/vmss/{subscriptionId}/sku-types")
    public ResponseEntity<?> saveVmSkuConfig(
            @PathVariable String subscriptionId,
            @RequestParam String resourceGroup,
            @RequestParam String vmssName,
            @RequestBody Map<String, String> body) {
        try {
            String allowedVmSkus = body.getOrDefault("allowed_vm_skus", "");
            String strategy      = body.getOrDefault("spot_allocation_strategy", "mixed");
            return ResponseEntity.ok(azureAutoSpottingService.saveVmSkuConfig(subscriptionId, resourceGroup, vmssName, allowedVmSkus, strategy));
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] saveVmSkuConfig failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get Spot ratio configuration for a specific VMSS.
     * GET /api/azure/autospotting/vmss/{subscriptionId}/spot-config?resourceGroup=&vmssName=
     */
    @GetMapping("/vmss/{subscriptionId}/spot-config")
    public ResponseEntity<?> getSpotConfig(
            @PathVariable String subscriptionId,
            @RequestParam String resourceGroup,
            @RequestParam String vmssName) {
        try {
            return ResponseEntity.ok(azureAutoSpottingService.getSpotConfig(subscriptionId, resourceGroup, vmssName));
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] getSpotConfig failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Save Spot ratio configuration for a specific VMSS.
     * PUT /api/azure/autospotting/vmss/{subscriptionId}/spot-config
     */
    @PutMapping("/vmss/{subscriptionId}/spot-config")
    public ResponseEntity<?> saveSpotConfig(
            @PathVariable String subscriptionId,
            @RequestParam String resourceGroup,
            @RequestParam String vmssName,
            @RequestBody Map<String, Object> body) {
        try {
            Integer spotPercentage = body.containsKey("spot_percentage") && body.get("spot_percentage") != null
                    ? ((Number) body.get("spot_percentage")).intValue() : null;
            Integer onDemandCount = body.containsKey("on_demand_count") && body.get("on_demand_count") != null
                    ? ((Number) body.get("on_demand_count")).intValue() : null;
            return ResponseEntity.ok(azureAutoSpottingService.saveSpotConfig(subscriptionId, resourceGroup, vmssName, spotPercentage, onDemandCount));
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] saveSpotConfig failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get combined per-VMSS configuration.
     * GET /api/azure/autospotting/vmss/{subscriptionId}/config?resourceGroup=&vmssName=
     */
    @GetMapping("/vmss/{subscriptionId}/config")
    public ResponseEntity<?> getVmssConfig(
            @PathVariable String subscriptionId,
            @RequestParam String resourceGroup,
            @RequestParam String vmssName) {
        try {
            return ResponseEntity.ok(azureAutoSpottingService.getVmssConfig(subscriptionId, resourceGroup, vmssName));
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] getVmssConfig failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update combined per-VMSS configuration (partial update).
     * PUT /api/azure/autospotting/vmss/{subscriptionId}/config
     */
    @PutMapping("/vmss/{subscriptionId}/config")
    public ResponseEntity<?> updateVmssConfig(
            @PathVariable String subscriptionId,
            @RequestParam String resourceGroup,
            @RequestParam String vmssName,
            @RequestBody Map<String, Object> updates) {
        try {
            return ResponseEntity.ok(azureAutoSpottingService.updateVmssConfig(subscriptionId, resourceGroup, vmssName, updates));
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] updateVmssConfig failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =================== BACKFILL / SYNC ===================

    /**
     * Backfill SpotOptimizationEvents for enabled VMSS that have no records.
     * POST /api/azure/autospotting/vmss/{subscriptionId}/sync
     */
    @PostMapping("/vmss/{subscriptionId}/sync")
    public ResponseEntity<?> syncSpotEvents(@PathVariable String subscriptionId) {
        log.info("[Azure AutoSpotting] syncSpotEvents (backfill) called for subscriptionId={}", subscriptionId);
        try {
            Map<String, Object> result = azureAutoSpottingService.backfillSpotEvents(subscriptionId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] syncSpotEvents failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Force-refresh the VMSS cache for a subscription.
     * POST /api/azure/autospotting/vmss/{subscriptionId}/refresh-cache
     */
    @PostMapping("/vmss/{subscriptionId}/refresh-cache")
    public ResponseEntity<?> refreshRegionCache(@PathVariable String subscriptionId) {
        log.info("[Azure AutoSpotting] refreshRegionCache called for subscriptionId={}", subscriptionId);
        try {
            Map<String, Object> result = azureAutoSpottingService.refreshRegionCache(subscriptionId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] refreshRegionCache failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // =================== ANALYTICS ===================

    /**
     * Get Spot replacement launch analytics (success/failure rates, by SKU/region).
     * GET /api/azure/autospotting/analytics/launches/{subscriptionId}?range=last7d
     */
    @GetMapping("/analytics/launches/{subscriptionId}")
    public ResponseEntity<?> getLaunchAnalytics(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "last7d") String range) {
        log.info("[Azure AutoSpotting] getLaunchAnalytics called for subscriptionId={}, range={}", subscriptionId, range);
        try {
            LaunchAnalyticsResponse response = azureAutoSpottingService.getLaunchAnalytics(subscriptionId, range);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] getLaunchAnalytics failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get Azure Reserved Instance / Savings Plan coverage data.
     * GET /api/azure/autospotting/analytics/reservation-coverage/{subscriptionId}
     */
    @GetMapping("/analytics/reservation-coverage/{subscriptionId}")
    public ResponseEntity<?> getReservationCoverage(@PathVariable String subscriptionId) {
        log.info("[Azure AutoSpotting] getReservationCoverage called for subscriptionId={}", subscriptionId);
        try {
            RiSpCoverageResponse response = azureAutoSpottingService.getReservationCoverage(subscriptionId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] getReservationCoverage failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =================== SETTINGS ===================

    /**
     * Get AutoSpotting settings for an Azure subscription.
     * GET /api/azure/autospotting/settings/{subscriptionId}
     */
    @GetMapping("/settings/{subscriptionId}")
    public ResponseEntity<?> getSettings(@PathVariable String subscriptionId) {
        log.info("[Azure AutoSpotting] getSettings called for subscriptionId={}", subscriptionId);
        try {
            AutoSpottingSettings settings = azureAutoSpottingService.getSettings(subscriptionId);
            return ResponseEntity.ok(settings);
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] getSettings failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update the Reservation-respect settings for AutoSpotting.
     * PUT /api/azure/autospotting/settings/{subscriptionId}/reservation-respect
     */
    @PutMapping("/settings/{subscriptionId}/reservation-respect")
    public ResponseEntity<?> updateRispRespectSettings(
            @PathVariable String subscriptionId,
            @RequestBody Map<String, Object> body) {
        log.info("[Azure AutoSpotting] updateRispRespectSettings called for subscriptionId={}", subscriptionId);
        try {
            boolean enabled   = Boolean.TRUE.equals(body.get("enabled"));
            int threshold     = body.containsKey("threshold") ? ((Number) body.get("threshold")).intValue() : 90;
            AutoSpottingSettings settings = azureAutoSpottingService.updateRispRespectSettings(subscriptionId, enabled, threshold);
            return ResponseEntity.ok(settings);
        } catch (Exception e) {
            log.error("[Azure AutoSpotting] updateRispRespectSettings failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =================== HEALTH ===================

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Azure AutoSpotting Integration",
                "timestamp", System.currentTimeMillis()));
    }

    /**
     * Test Azure credential validity for a given subscription ID.
     * Attempts to obtain an Azure AD token and returns success/failure with error details.
     * Useful for diagnosing auth issues without waiting 60s for VMSS list to timeout.
     */
    @GetMapping("/test-auth/{subscriptionId}")
    public ResponseEntity<Map<String, Object>> testAuth(@PathVariable String subscriptionId) {
        log.info("Azure auth test requested for subscriptionId={}", subscriptionId);
        try {
            com.azure.core.credential.TokenCredential credential =
                    azureAutoSpottingService.getCredentialForSubscription(subscriptionId);

            com.azure.core.credential.TokenRequestContext ctx =
                    new com.azure.core.credential.TokenRequestContext()
                            .addScopes("https://management.azure.com/.default");

            com.azure.core.credential.AccessToken token = credential.getToken(ctx).block(
                    java.time.Duration.ofSeconds(15));

            if (token == null) {
                return ResponseEntity.status(500).body(Map.of(
                        "status", "FAIL",
                        "error", "getToken() returned null — credentials likely invalid"));
            }
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "message", "Azure AD token obtained successfully",
                    "expiresAt", token.getExpiresAt().toString()));
        } catch (Exception e) {
            log.error("Azure auth test failed for subscriptionId={}: {}", subscriptionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "status", "FAIL",
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    "cause", e.getCause() != null ? e.getCause().getMessage() : "none"));
        }
    }
}
