package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpDashboardData;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/dashboard")
@CrossOrigin(origins = "*")
public class GcpDashboardController {

    private static final Logger logger = LoggerFactory.getLogger(GcpDashboardController.class);
    private final GcpDataService gcpDataService;
    private final RedisCacheService redisCacheService;

    public GcpDashboardController(GcpDataService gcpDataService,
                                  RedisCacheService redisCacheService) {
        this.gcpDataService = gcpDataService;
        this.redisCacheService = redisCacheService;
    }

    /**
     * ✅ MAIN ENDPOINT: Get GCP dashboard data
     *
     * Example: GET /api/xamops/gcp/dashboard?accountId=my-gcp-project&forceRefresh=false
     */
    @GetMapping
    public CompletableFuture<ResponseEntity<?>> getDashboardData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("📊 GCP Dashboard request - Project: {}, ForceRefresh: {}", accountId, forceRefresh);

        return gcpDataService.getDashboardData(accountId, forceRefresh)
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
     * ✅ Force refresh dashboard (clears cache and fetches fresh data)
     *
     * Example: POST /api/xamops/gcp/dashboard/refresh?accountId=my-gcp-project
     */
    @PostMapping("/refresh")
    public CompletableFuture<ResponseEntity<?>> refreshDashboard(@RequestParam String accountId) {
        logger.info("🔄 Force refresh GCP dashboard for project: {}", accountId);

        // Clear all caches for this project
        gcpDataService.clearProjectCache(accountId);

        // Fetch fresh data
        return getDashboardData(accountId, true);
    }

    /**
     * ✅ Clear specific cache type
     *
     * Example: DELETE /api/xamops/gcp/dashboard/cache?accountId=my-gcp-project&type=dashboard
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
                        "message", "Invalid cache type. Use: dashboard, resources, or all"
                ));
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
