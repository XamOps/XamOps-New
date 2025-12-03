package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.autospotting.*;
import com.xammer.cloud.service.AutoSpottingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        log.info("AutoSpottingController.getCostData called with accountId={}", accountId);
        try {
            CostResponse costData = autoSpottingService.getCostData(accountId);
            return ResponseEntity.ok(costData);
        } catch (Exception e) {
            log.error("Failed to get cost data from AutoSpotting API: {}", e.getMessage(), e);
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
}
