package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpDashboardData;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/dashboard")
@CrossOrigin(origins = "*")
public class GcpDashboardController {

    private static final Logger logger = LoggerFactory.getLogger(GcpDashboardController.class);
    private final GcpDataService gcpDataService;
    private final RedisCacheService redisCacheService;
    private final DashboardDataService dashboardDataService;

    public GcpDashboardController(GcpDataService gcpDataService,
            RedisCacheService redisCacheService,
            DashboardDataService dashboardDataService) {
        this.gcpDataService = gcpDataService;
        this.redisCacheService = redisCacheService;
        this.dashboardDataService = dashboardDataService;
    }

    /**
     * GET /api/xamops/gcp/dashboard?accountId=my-gcp-project&forceRefresh=false
     *
     * Cache flow (same as AWS dashboard):
     *   forceRefresh=false → Redis (60 min) → DB snapshot → live GCP API calls → save both
     *   forceRefresh=true  → skip cache → live GCP API calls → overwrite Redis + DB
     *
     * Nightly scheduled job (1:30 AM Wed/Sun) calls with forceRefresh=true to keep data fresh.
     */
    @GetMapping
    public CompletableFuture<ResponseEntity<?>> getDashboardData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("📊 GCP Dashboard request - Project: {} forceRefresh: {}", accountId, forceRefresh);

        return gcpDataService.getDashboardData(accountId, forceRefresh)
                .<ResponseEntity<?>>thenApply(data -> {
                    logger.info("✅ GCP Dashboard served for project: {} (MTD: ${})",
                            accountId, data.getMonthToDateSpend());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ Failed to fetch GCP dashboard for project: {}", accountId, ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Failed to retrieve GCP dashboard data");
                    errorResponse.put("message", ex.getMessage());
                    errorResponse.put("timestamp", System.currentTimeMillis());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                });
    }

    /**
     * ✅ NEW: Get Multi-Account GCP Dashboard Data
     * Aggregates data from multiple GCP projects into a unified DashboardData
     * response.
     *
     * Example: GET
     * /api/xamops/gcp/dashboard/multi-account?accountIds=proj1,proj2&forceRefresh=false
     */
    @GetMapping("/multi-account")
    public ResponseEntity<?> getMultiAccountDashboardData(
            @RequestParam List<String> accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        logger.info("📊 GCP Multi-Account Dashboard request - Accounts: {}, ForceRefresh: {}", accountIds,
                forceRefresh);

        try {
            if (accountIds == null || accountIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Account list cannot be empty"));
            }

            if (accountIds.size() > 10) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Maximum 10 accounts can be selected"));
            }

            // Use the shared aggregation service to fetch and combine data
            DashboardData aggregatedData = dashboardDataService.getMultiAccountDashboardData(accountIds, forceRefresh,
                    userDetails);

            logger.info("✅ GCP Multi-Account data aggregated successfully for {} accounts", accountIds.size());
            return ResponseEntity.ok(aggregatedData);

        } catch (IllegalArgumentException ex) {
            // Validation errors (e.g., mixed providers, invalid account IDs)
            logger.warn("⚠️ Validation error for GCP multi-account request: {}", ex.getMessage());

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", ex.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception ex) {
            // Server errors
            logger.error("❌ Failed to fetch GCP multi-account dashboard data", ex);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to retrieve multi-account data");
            errorResponse.put("message", ex.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    // ============================================================================================
    // GRANULAR PER-WIDGET ENDPOINTS — mirrors /api/xamops/dashboard/granular/{kpi,…}
    // Each widget fetches only its own futures and stores in its own DB+Redis key.
    // ============================================================================================

    /**
     * GET /api/xamops/gcp/dashboard/granular/kpi?accountId=my-project&forceRefresh=false
     * Returns: monthToDateSpend, forecastedSpend, lastMonthSpend, costHistory (sparkline),
     *          optimizationSummary, savingsSummary
     */
    @GetMapping("/granular/kpi")
    public ResponseEntity<?> getGranularKpi(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(gcpDataService.getGranularKpi(accountId, forceRefresh));
        } catch (Exception ex) {
            logger.error("❌ GCP granular KPI failed for {}", accountId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch GCP KPI data", "message", ex.getMessage()));
        }
    }

    /**
     * GET /api/xamops/gcp/dashboard/granular/inventory?accountId=my-project&forceRefresh=false
     * Returns: resourceInventory, iamResources, iamDetails
     */
    @GetMapping("/granular/inventory")
    public ResponseEntity<?> getGranularInventory(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(gcpDataService.getGranularInventory(accountId, forceRefresh));
        } catch (Exception ex) {
            logger.error("❌ GCP granular Inventory failed for {}", accountId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch GCP Inventory data", "message", ex.getMessage()));
        }
    }

    /**
     * GET /api/xamops/gcp/dashboard/granular/cost?accountId=my-project&forceRefresh=false
     * Returns: monthToDateSpend, forecastedSpend, lastMonthSpend, costHistory, billingSummary
     */
    @GetMapping("/granular/cost")
    public ResponseEntity<?> getGranularCost(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(gcpDataService.getGranularCost(accountId, forceRefresh));
        } catch (Exception ex) {
            logger.error("❌ GCP granular Cost failed for {}", accountId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch GCP Cost data", "message", ex.getMessage()));
        }
    }

    /**
     * GET /api/xamops/gcp/dashboard/granular/optimization?accountId=my-project&forceRefresh=false
     * Returns: wastedResources, rightsizingRecommendations, optimizationSummary, savingsSummary
     */
    @GetMapping("/granular/optimization")
    public ResponseEntity<?> getGranularOptimization(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(gcpDataService.getGranularOptimization(accountId, forceRefresh));
        } catch (Exception ex) {
            logger.error("❌ GCP granular Optimization failed for {}", accountId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch GCP Optimization data", "message", ex.getMessage()));
        }
    }

    /**
     * GET /api/xamops/gcp/dashboard/granular/map?accountId=my-project&forceRefresh=false
     * Returns: regionStatus, securityScore, securityInsights
     */
    @GetMapping("/granular/map")
    public ResponseEntity<?> getGranularMap(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(gcpDataService.getGranularMap(accountId, forceRefresh));
        } catch (Exception ex) {
            logger.error("❌ GCP granular Map failed for {}", accountId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch GCP Map data", "message", ex.getMessage()));
        }
    }

    /**
     * ✅ Clear specific cache type
     *
     * Example: DELETE
     * /api/xamops/gcp/dashboard/cache?accountId=my-gcp-project&type=dashboard
     */
    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, Object>> clearCache(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "all") String type) {

        logger.info("🗑️ Clearing {} cache for GCP project: {}", type, accountId);

        switch (type.toLowerCase()) {
            case "dashboard":
                gcpDataService.clearDashboardCache(accountId);
                break;
            case "resources":
                gcpDataService.clearResourcesCache(accountId);
                break;
            case "all":
                gcpDataService.clearProjectCache(accountId);
                break;
            default:
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "Invalid cache type. Use: dashboard, resources, or all"));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", String.format("Cleared %s cache for project %s", type, accountId));
        response.put("projectId", accountId);
        response.put("cacheType", type);

        return ResponseEntity.ok(response);
    }

    /**
     * ✅ Health check endpoint
     *
     * Example: GET /api/xamops/gcp/dashboard/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "healthy");
        health.put("service", "GCP Dashboard API");
        health.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(health);
    }
}