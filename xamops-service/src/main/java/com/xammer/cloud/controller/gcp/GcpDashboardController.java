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
     * ✅ MAIN ENDPOINT: Get GCP dashboard data from DATABASE CACHE
     *
     * Data is pre-cached by nightly scheduled job (4:00 AM IST).
     * This endpoint ALWAYS returns cached data - no live API calls.
     * forceRefresh parameter is ignored for security (only scheduled jobs can refresh).
     *
     * Example: GET /api/xamops/gcp/dashboard?accountId=my-gcp-project
     */
    @GetMapping
    public CompletableFuture<ResponseEntity<?>> getDashboardData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        // IGNORE forceRefresh from user requests - always return cached data
        // Live data fetch is ONLY allowed via scheduled nightly jobs
        logger.info("📊 GCP Dashboard request - Project: {} (returning cached data)", accountId);

        return gcpDataService.getDashboardData(accountId, false) // Always false - use cache only
                .<ResponseEntity<?>>thenApply(data -> {
                    logger.info("✅ GCP Dashboard data retrieved for project: {} (MonthToDate: ${})",
                            accountId, data.getMonthToDateSpend());

                    // ✅ Return data directly (no wrapper) to match frontend expectations
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ Failed to fetch GCP dashboard data for project: {}", accountId, ex);

                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Failed to retrieve GCP dashboard data");
                    errorResponse.put("message", ex.getMessage());
                    errorResponse.put("timestamp", System.currentTimeMillis());

                    return ResponseEntity
                            .status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(errorResponse);
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

    /**
     * ⚠️ DEPRECATED: Manual refresh is disabled.
     * Dashboard data is refreshed ONLY by nightly scheduled job (4:00 AM IST).
     * Users should wait for the nightly refresh to see updated data.
     *
     * Example: POST /api/xamops/gcp/dashboard/refresh?accountId=my-gcp-project
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshDashboard(@RequestParam String accountId) {
        logger.warn("⚠️ Manual refresh requested for GCP project: {} - DISABLED (use nightly job)", accountId);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "disabled");
        response.put("message", "Manual refresh is disabled. Dashboard data is updated automatically every night at 4:00 AM IST.");
        response.put("projectId", accountId);
        response.put("nextRefresh", "4:00 AM IST (daily)");

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
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