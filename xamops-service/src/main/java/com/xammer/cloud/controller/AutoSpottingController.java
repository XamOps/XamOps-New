package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.autospotting.*;
import com.xammer.cloud.service.AutoSpottingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/autospotting")
@RequiredArgsConstructor
public class AutoSpottingController {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingController.class);

    private final AutoSpottingService autoSpottingService;

    // ================= ACCOUNT REGISTRATION =================

    /**
     * Register customer account in AutoSpotting DynamoDB
     * POST /api/autospotting/register/{accountId}
     */
    @PostMapping("/register/{accountId}")
    public ResponseEntity<?> registerAccount(@PathVariable Long accountId) {
        log.info("AutoSpottingController.registerAccount called with accountId={}", accountId);
        try {
            autoSpottingService.registerCustomerAccount(accountId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Account registered successfully in AutoSpotting DynamoDB"));
        } catch (Exception e) {
            log.error("Failed to register account: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    // ================= COST DATA (API-BASED) =================

    /**
     * Get current cost data with ASG details from AutoSpotting API
     * GET /api/autospotting/costs/{accountId}
     */
    @GetMapping("/costs/{accountId}")
    public ResponseEntity<?> getCostData(@PathVariable Long accountId) {
        log.info("getCostData called: accountId={}", accountId);
        try {
            CostResponse costData = autoSpottingService.getCostData(accountId);
            log.info("Cost data retrieved: {} ASGs, ${}/hr",
                    costData.getAsgs() != null ? costData.getAsgs().size() : 0,
                    costData.getSummary() != null ? costData.getSummary().getTotalCurrentHourlyCost() : 0.0);
            return ResponseEntity.ok(costData);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("401")) {
                log.error("❌ API authentication failed - check API key");
            }
            log.error("Failed to get cost data: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch cost data: " + e.getMessage()));
        }
    }

    /**
     * Get historical cost and savings data from AutoSpotting API
     * GET /api/autospotting/costs/history/{accountId}
     */
    @GetMapping("/costs/history/{accountId}")
    public ResponseEntity<?> getCostHistory(
            @PathVariable Long accountId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "daily") String interval) {

        log.info("AutoSpottingController.getCostHistory called with accountId={}, start={}, end={}, interval={}",
                accountId, start, end, interval);

        try {
            HistoryResponse history = autoSpottingService.getCostHistory(accountId, start, end, interval);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            log.error("Failed to get cost history from AutoSpotting API: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch cost history: " + e.getMessage()));
        }
    }

    /**
     * Get monthly savings summary (uses API with CloudWatch fallback)
     * GET /api/autospotting/savings/{accountId}
     */
    @GetMapping("/savings/{accountId}")
    public ResponseEntity<?> getSavings(@PathVariable Long accountId) {
        log.info("AutoSpottingController.getSavings called with accountId={}", accountId);
        try {
            DashboardData.SavingsSummary savings = autoSpottingService.getSavingsMetrics(accountId);
            return ResponseEntity.ok(savings);
        } catch (Exception e) {
            log.error("Failed to get savings metrics: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch savings: " + e.getMessage()));
        }
    }

    // ================= ASG LISTING =================

    /**
     * List ASGs across ALL AutoSpotting-enabled regions with cost data (API-based)
     * GET /api/autospotting/asgs/all-regions/{accountId}
     */
    @GetMapping("/asgs/all-regions/{accountId}")
    public ResponseEntity<?> listAsgsAllRegions(@PathVariable Long accountId) {
        log.info("AutoSpottingController.listAsgsAllRegions called with accountId={}", accountId);
        try {
            List<AutoSpottingService.AutoSpottingGroupDto> asgs = autoSpottingService
                    .listAsgsAllRegionsWithCosts(accountId);
            return ResponseEntity.ok(asgs);
        } catch (Exception e) {
            log.error("Failed to list ASGs across all regions: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to list ASGs: " + e.getMessage()));
        }
    }

    /**
     * List ASGs in a specific region (legacy endpoint, kept for backward
     * compatibility)
     * GET /api/autospotting/asgs/{accountId}?region=us-east-1
     */
    @GetMapping("/asgs/{accountId}")
    public ResponseEntity<?> listAsgs(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "us-east-1") String region) {
        log.info("AutoSpottingController.listAsgs called with accountId={}, region={}", accountId, region);
        try {
            List<AutoSpottingService.AutoSpottingGroupDto> asgs = autoSpottingService.listAsgs(accountId, region);
            return ResponseEntity.ok(asgs);
        } catch (Exception e) {
            log.error("Failed to list ASGs in region {}: {}", region, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to list ASGs: " + e.getMessage()));
        }
    }

    /**
     * Force refresh of region discovery cache
     * POST /api/autospotting/asgs/{accountId}/refresh-regions
     */
    @PostMapping("/asgs/{accountId}/refresh-regions")
    public ResponseEntity<Map<String, Object>> refreshRegionCache(@PathVariable Long accountId) {
        log.info("AutoSpottingController.refreshRegionCache called with accountId={}", accountId);

        try {
            autoSpottingService.refreshRegionCache(accountId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Region cache refreshed successfully. Next ASG fetch will re-scan all regions."));
        } catch (Exception e) {
            log.error("Error refreshing region cache: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    // ================= ASG CONTROL (ENABLE/DISABLE) =================

    /**
     * Enable AutoSpotting for an ASG (uses API with fallback to direct tag
     * manipulation)
     * POST
     * /api/autospotting/asgs/{accountId}/enable?region=us-east-1&asgName=my-asg
     */
    @PostMapping("/asgs/{accountId}/enable")
    public ResponseEntity<?> enableAutoSpotting(
            @PathVariable Long accountId,
            @RequestParam String region,
            @RequestParam String asgName) {

        log.info("AutoSpottingController.enableAutoSpotting called with accountId={}, region={}, asgName={}",
                accountId, region, asgName);

        try {
            autoSpottingService.enableAutoSpotting(accountId, region, asgName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "AutoSpotting enabled successfully for " + asgName));
        } catch (Exception e) {
            log.error("Failed to enable AutoSpotting: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to enable: " + e.getMessage()));
        }
    }

    // ================= MANUAL SYNC / BACKFILL =================

    /**
     * Manually sync (backfill) SUCCESS events for Spot instances currently running
     * in the given ASG that are not yet recorded in the database.
     * POST /api/autospotting/asgs/{accountId}/sync?region=ap-south-1&asgName=my-asg
     */
    @PostMapping("/asgs/{accountId}/sync")
    public ResponseEntity<?> syncSpotEvents(
            @PathVariable Long accountId,
            @RequestParam String region,
            @RequestParam String asgName) {

        log.info("AutoSpottingController.syncSpotEvents called: accountId={} region={} asgName={}",
                accountId, region, asgName);
        try {
            int backfilled = autoSpottingService.backfillSpotEvents(accountId, region, asgName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "backfilled", backfilled,
                    "message", backfilled > 0
                            ? backfilled + " Spot event(s) synced to Actions History."
                            : "No new Spot instances found to sync."));
        } catch (Exception e) {
            log.error("Failed to sync Spot events: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    // ================= INSTANCE TYPE CONFIGURATION =================

    /**
     * Get configured instance types for an ASG (for Spot diversification)
     * GET
     * /api/autospotting/asgs/{accountId}/instance-types?region=ap-south-1&asgName=my-asg
     */
    @GetMapping("/asgs/{accountId}/instance-types")
    public ResponseEntity<?> getInstanceTypeConfig(
            @PathVariable Long accountId,
            @RequestParam String region,
            @RequestParam String asgName) {

        log.info("AutoSpottingController.getInstanceTypeConfig called: accountId={} region={} asgName={}",
                accountId, region, asgName);
        try {
            List<String> types = autoSpottingService.getConfiguredInstanceTypes(accountId, region, asgName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "allowedInstanceTypes", types));
        } catch (Exception e) {
            log.error("Failed to get instance type config: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    /**
     * Update configured instance types for an ASG
     * PUT
     * /api/autospotting/asgs/{accountId}/instance-types?region=ap-south-1&asgName=my-asg&apply=true
     * Body: { "instanceTypes": ["t3.micro", "t3a.micro", "t3.small"] }
     */
    @PutMapping("/asgs/{accountId}/instance-types")
    public ResponseEntity<?> updateInstanceTypeConfig(
            @PathVariable Long accountId,
            @RequestParam String region,
            @RequestParam String asgName,
            @RequestParam(defaultValue = "true") boolean apply,
            @RequestBody Map<String, Object> body) {

        log.info("AutoSpottingController.updateInstanceTypeConfig: accountId={} region={} asgName={} apply={}",
                accountId, region, asgName, apply);
        try {
            @SuppressWarnings("unchecked")
            List<String> types = (List<String>) body.get("instanceTypes");
            autoSpottingService.saveInstanceTypeConfig(accountId, region, asgName,
                    types != null ? types : Collections.emptyList(),
                    apply);

            String message = apply
                ? "Instance type configuration saved and applied successfully. Instance refresh started."
                : "Instance type configuration saved successfully. Enable AutoSpotting to apply.";

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", message,
                    "instanceTypes", types != null ? types : Collections.emptyList(),
                    "applied", apply));
        } catch (IllegalStateException e) {
            // AutoSpotting not enabled - config saved but not applied
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Configuration saved but not applied: " + e.getMessage(),
                    "applied", false));
        } catch (Exception e) {
            log.error("Failed to update/apply instance type config: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    // ================= SPOT/ON-DEMAND CONFIGURATION =================

    /**
     * Get Spot/On-Demand configuration for an ASG
     * GET /api/autospotting/asgs/{accountId}/spot-config?region=ap-south-1&asgName=my-asg
     * Returns: { "configMode": "percentage", "spotPercentage": 75 }
     *       OR { "configMode": "count", "onDemandCount": 2 }
     */
    @GetMapping("/asgs/{accountId}/spot-config")
    public ResponseEntity<?> getSpotConfig(
            @PathVariable Long accountId,
            @RequestParam String region,
            @RequestParam String asgName) {

        log.info("AutoSpottingController.getSpotConfig called: accountId={} region={} asgName={}",
                accountId, region, asgName);
        try {
            Map<String, Object> config = autoSpottingService.getSpotConfig(accountId, region, asgName);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("Failed to get spot config: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    /**
     * Save Spot/On-Demand configuration for an ASG
     * PUT /api/autospotting/asgs/{accountId}/spot-config?region=ap-south-1&asgName=my-asg&apply=true
     * Body: { "spotPercentage": 75 } OR { "onDemandCount": 2 }
     */
    @PutMapping("/asgs/{accountId}/spot-config")
    public ResponseEntity<?> saveSpotConfig(
            @PathVariable Long accountId,
            @RequestParam String region,
            @RequestParam String asgName,
            @RequestParam(defaultValue = "true") boolean apply,
            @RequestBody Map<String, Object> body) {

        log.info("AutoSpottingController.saveSpotConfig: accountId={} region={} asgName={} apply={} body={}",
                accountId, region, asgName, apply, body);
        try {
            Integer spotPercentage = body.containsKey("spotPercentage")
                    ? ((Number) body.get("spotPercentage")).intValue()
                    : null;
            Integer onDemandCount = body.containsKey("onDemandCount")
                    ? ((Number) body.get("onDemandCount")).intValue()
                    : null;

            autoSpottingService.saveSpotConfig(accountId, region, asgName,
                                              spotPercentage, onDemandCount,
                                              apply);

            String message = apply
                ? "Spot configuration saved and applied successfully. Instance refresh started."
                : "Spot configuration saved successfully. Enable AutoSpotting to apply.";

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", message,
                    "applied", apply));
        } catch (IllegalStateException e) {
            // AutoSpotting not enabled - config saved but not applied
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Configuration saved but not applied: " + e.getMessage(),
                    "applied", false));
        } catch (Exception e) {
            log.error("Failed to save/apply spot config: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    // ================= INSTANCE LISTING =================

    /**
     * Get list of all instances in enabled ASGs with Spot/On-Demand status
     * GET /api/autospotting/instances/{accountId}?asgName=xxx&region=xxx
     */
    @GetMapping("/instances/{accountId}")
    public ResponseEntity<?> getSpotInstances(
            @PathVariable Long accountId,
            @RequestParam(required = false) String asgName,
            @RequestParam(required = false) String region) {
        log.info("AutoSpottingController.getSpotInstances called with accountId={}, asgName={}, region={}",
                accountId, asgName, region);
        try {
            List<SpotInstanceDto> instances = autoSpottingService.getSpotInstances(accountId);

            // Filter by asgName and region if provided
            if (asgName != null && !asgName.isEmpty()) {
                instances = instances.stream()
                        .filter(i -> asgName.equals(i.getAsgName()))
                        .collect(java.util.stream.Collectors.toList());
            }
            if (region != null && !region.isEmpty()) {
                instances = instances.stream()
                        .filter(i -> region.equals(i.getRegion()))
                        .collect(java.util.stream.Collectors.toList());
            }

            return ResponseEntity.ok(instances);
        } catch (Exception e) {
            log.error("Failed to list spot instances: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to list spot instances: " + e.getMessage()));
        }
    }

    /**
     * Disable AutoSpotting for an ASG (uses API with fallback to direct tag
     * manipulation)
     * POST
     * /api/autospotting/asgs/{accountId}/disable?region=us-east-1&asgName=my-asg
     */
    @PostMapping("/asgs/{accountId}/disable")
    public ResponseEntity<?> disableAutoSpotting(
            @PathVariable Long accountId,
            @RequestParam String region,
            @RequestParam String asgName) {

        log.info("AutoSpottingController.disableAutoSpotting called with accountId={}, region={}, asgName={}",
                accountId, region, asgName);

        try {
            autoSpottingService.disableAutoSpotting(accountId, region, asgName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "AutoSpotting disabled successfully for " + asgName));
        } catch (Exception e) {
            log.error("Failed to disable AutoSpotting: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to disable: " + e.getMessage()));
        }
    }

    // ================= ASG CONFIGURATION (API-BASED) =================

    /**
     * Get AutoSpotting configuration for a specific ASG
     * GET /api/autospotting/asgs/{accountId}/config?region=us-east-1&asgName=my-asg
     */
    @GetMapping("/asgs/{accountId}/config")
    public ResponseEntity<?> getAsgConfig(
            @PathVariable Long accountId,
            @RequestParam String region,
            @RequestParam String asgName) {

        log.info("AutoSpottingController.getAsgConfig called with accountId={}, region={}, asgName={}",
                accountId, region, asgName);

        try {
            ASGConfig config = autoSpottingService.getAsgConfig(accountId, region, asgName);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("Failed to get ASG config: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to get config: " + e.getMessage()));
        }
    }

    /**
     * Update AutoSpotting configuration for a specific ASG
     * PUT /api/autospotting/asgs/{accountId}/config?region=us-east-1&asgName=my-asg
     * Body: ASGConfigUpdate JSON
     */
    @PutMapping("/asgs/{accountId}/config")
    public ResponseEntity<?> updateAsgConfig(
            @PathVariable Long accountId,
            @RequestParam String region,
            @RequestParam String asgName,
            @RequestBody ASGConfigUpdate config) {

        log.info("AutoSpottingController.updateAsgConfig called with accountId={}, region={}, asgName={}",
                accountId, region, asgName);
        log.debug("Config update payload: {}", config);

        try {
            ASGConfig updated = autoSpottingService.updateAsgConfig(accountId, region, asgName, config);
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            log.error("Failed to update ASG config: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to update config: " + e.getMessage()));
        }
    }

    // ================= HEALTH CHECK =================

    /**
     * Health check endpoint to verify AutoSpotting integration
     * GET /api/autospotting/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        log.debug("AutoSpottingController.healthCheck called");
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "AutoSpotting Integration",
                "timestamp", System.currentTimeMillis()));
    }

    // ================= EVENTS/ACTIONS HISTORY =================

    /**
     * Get events/actions history
     * GET /api/autospotting/events/{accountId}
     * 
     * Query params:
     * - start: ISO date-time (default: 7 days ago)
     * - end: ISO date-time (default: now)
     * - eventType: filter by type (instance_replacement, spot_interruption,
     * rebalance_event)
     * - asgName: filter by ASG name
     */
    @GetMapping("/events/{accountId}")
    public ResponseEntity<?> getEvents(
            @PathVariable Long accountId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String asgName) {

        log.info(
                "AutoSpottingController.getEvents called with accountId={}, start={}, end={}, eventType={}, asgName={}",
                accountId, start, end, eventType, asgName);

        try {
            // Default to last 7 days if not provided
            if (start == null || start.isEmpty()) {
                start = java.time.Instant.now()
                        .minus(7, java.time.temporal.ChronoUnit.DAYS)
                        .toString();
            }
            if (end == null || end.isEmpty()) {
                end = java.time.Instant.now().toString();
            }

            EventsResponse events = autoSpottingService.getEvents(accountId, start, end, eventType, asgName);
            return ResponseEntity.ok(events);

        } catch (Exception e) {
            log.error("Failed to get events from AutoSpotting API: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch events: " + e.getMessage()));
        }
    }

    // ================= LAUNCH ANALYTICS =================

    /**
     * Get launch analytics
     * GET /api/autospotting/analytics/launches/{accountId}
     * 
     * Query params:
     * - start: Date in YYYY-MM-DD format (default: 30 days ago)
     * - end: Date in YYYY-MM-DD format (default: today)
     */
    @GetMapping("/analytics/launches/{accountId}")
    public ResponseEntity<?> getLaunchAnalytics(
            @PathVariable Long accountId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        log.info("AutoSpottingController.getLaunchAnalytics called with accountId={}, start={}, end={}",
                accountId, start, end);

        try {
            // Default to last 30 days if not provided
            if (start == null || start.isEmpty()) {
                start = java.time.LocalDate.now()
                        .minusDays(30)
                        .toString();
            }
            if (end == null || end.isEmpty()) {
                end = java.time.LocalDate.now().toString();
            }

            LaunchAnalyticsResponse analytics = autoSpottingService.getLaunchAnalytics(accountId, start, end);
            return ResponseEntity.ok(analytics);

        } catch (Exception e) {
            log.error("Failed to get launch analytics from AutoSpotting API: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch launch analytics: " + e.getMessage()));
        }
    }

    @GetMapping("/analytics/risp-coverage/{accountId}")
    public ResponseEntity<?> getRiSpCoverage(@PathVariable Long accountId) {
        log.info("AutoSpottingController.getRiSpCoverage called with accountId={}", accountId);

        try {
            RiSpCoverageResponse coverage = autoSpottingService.getRiSpCoverage(accountId);
            return ResponseEntity.ok(coverage);

        } catch (Exception e) {
            log.error("Failed to get RI/SP coverage from AutoSpotting API: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch RI/SP coverage: " + e.getMessage()));
        }
    }

    /**
     * Get AutoSpotting settings including RI/SP respect configuration
     */
    @GetMapping("/settings/{accountId}")
    public ResponseEntity<?> getSettings(@PathVariable Long accountId) {
        log.info("AutoSpottingController.getSettings called with accountId={}", accountId);

        try {
            Map<String, Object> settings = autoSpottingService.getSettings(accountId);
            return ResponseEntity.ok(settings);

        } catch (Exception e) {
            log.error("Failed to get AutoSpotting settings: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch settings: " + e.getMessage()));
        }
    }

    /**
     * Update RI/SP respect configuration
     * 
     * @param accountId The cloud account ID
     * @param request JSON body with:
     *   - respectRiSpCommitments (boolean)
     *   - riSpUtilizationThreshold (integer, 0-100)
     */
    @PutMapping("/settings/{accountId}/risp-respect")
    public ResponseEntity<?> updateRispRespectSettings(
            @PathVariable Long accountId,
            @RequestBody Map<String, Object> request) {

        log.info("AutoSpottingController.updateRispRespectSettings called with accountId={}, request={}", 
                accountId, request);

        try {
            Boolean respectRiSp = (Boolean) request.get("respectRiSpCommitments");
            Integer threshold = (Integer) request.get("riSpUtilizationThreshold");

            autoSpottingService.updateRispRespectSettings(accountId, respectRiSp, threshold);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "RI/SP respect settings updated successfully",
                    "respectRiSpCommitments", respectRiSp != null ? respectRiSp : false,
                    "riSpUtilizationThreshold", threshold != null ? threshold : 90));

        } catch (Exception e) {
            log.error("Failed to update RI/SP respect settings: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to update settings: " + e.getMessage()));
        }
    }

}
