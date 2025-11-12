package com.xammer.cloud.controller;

import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.CostForecastDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.service.CostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate; // <-- IMPORT ADDED
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops")
public class CostController {

    private static final Logger logger = LoggerFactory.getLogger(CostController.class);

    private final CostService costService;

    public CostController(CostService costService) {
        this.costService = costService;
    }

    /**
     * Get cost breakdown by dimension (SERVICE, REGION, etc.)
     *
     * Example: /api/xamops/costs/breakdown?accountId=123456789&groupBy=SERVICE&startDate=2025-11-01&endDate=2025-11-12
     */
    @GetMapping("/costs/breakdown")
    public CompletableFuture<ResponseEntity<List<CostDto>>> getCostBreakdown(
            @RequestParam String accountId,
            @RequestParam String groupBy,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam String startDate, // <-- ADDED
            @RequestParam String endDate) { // <-- ADDED

        // UPDATED Logger to include new date parameters
        logger.info("📊 Cost breakdown request - Account: {}, GroupBy: {}, Tag: {}, Start: {}, End: {}",
                accountId, groupBy, tag, startDate, endDate);

        // UPDATED Service call to pass new date parameters
        return costService.getCostBreakdown(accountId, groupBy, tag, forceRefresh, startDate, endDate) 
                .thenApply(costs -> {
                    logger.info("✅ Cost breakdown retrieved: {} items", costs.size());
                    return ResponseEntity.ok(costs);
                })
                .exceptionally(ex -> {
                    logger.error("❌ Error fetching cost breakdown", ex);
                    // --- FIX: Specify generic type for .build() ---
                    return ResponseEntity.<List<CostDto>>status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    /**
     * Get historical cost data for a specific dimension
     *
     * Example: /api/xamops/costs/historical?accountId=123&groupBy=SERVICE&dimensionValue=EC2
     */
    @GetMapping("/costs/historical")
    public CompletableFuture<ResponseEntity<HistoricalCostDto>> getHistoricalCost(
            @RequestParam String accountId,
            @RequestParam String groupBy,
            @RequestParam String dimensionValue,
            @RequestParam(required = false) String tagKey,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("📈 Historical cost request - Account: {}, Dimension: {}",
                accountId, dimensionValue);

        return costService.getHistoricalCostForDimension(accountId, groupBy, dimensionValue, tagKey, forceRefresh)
                .thenApply(historicalData -> {
                    logger.info("✅ Historical cost data retrieved: {} data points",
                            historicalData.getLabels().size());
                    return ResponseEntity.ok(historicalData);
                })
                .exceptionally(ex -> {
                    logger.error("❌ Error fetching historical cost", ex);
                    // --- FIX: Specify generic type for .build() ---
                    return ResponseEntity.<HistoricalCostDto>status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    /**
     * Get historical cost data with flexible parameters
     *
     * Example: /api/xamops/costs/historical-trend?accountId=123&serviceName=EC2&days=30
     */
    @GetMapping("/costs/historical-trend")
    public CompletableFuture<ResponseEntity<HistoricalCostDto>> getHistoricalCostTrend(
            @RequestParam String accountId,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String regionName,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("📊 Historical trend request - Account: {}, Service: {}, Region: {}, Days: {}",
                accountId, serviceName, regionName, days);

        return costService.getHistoricalCost(accountId, serviceName, regionName, days, forceRefresh)
                .thenApply(historicalData -> {
                    logger.info("✅ Historical trend retrieved: {} days", historicalData.getLabels().size());
                    // --- FIX: Fixed typo (historical -> historicalData) ---
                    return ResponseEntity.ok(historicalData);
                })
                .exceptionally(ex -> {
                    logger.error("❌ Error fetching historical trend", ex);
                    // --- FIX: Specify generic type for .build() ---
                    return ResponseEntity.<HistoricalCostDto>status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    /**
     * ✅ NEW: Get cost forecast using ML predictions
     *
     * Example: /api/xamops/forecast/cost?accountId=123&serviceName=EC2&periods=30
     */
    @GetMapping("/forecast/cost")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getCostForecast(
            @RequestParam String accountId,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String regionName,
            @RequestParam(defaultValue = "30") int periods,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("🔮 Cost forecast request - Account: {}, Service: {}, Periods: {}",
                accountId, serviceName, periods);

        // Validate periods
        if (periods < 1 || periods > 90) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Periods must be between 1 and 90 days");
            return CompletableFuture.completedFuture(
                    ResponseEntity.badRequest().body(errorResponse)
            );
        }

        return costService.getCostForecast(accountId, serviceName, regionName, periods, forceRefresh)
                .thenApply(forecast -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("status", "success");
                    response.put("forecast", forecast);
                    response.put("metadata", Map.of(
                            "accountId", accountId,
                            "service", serviceName != null ? serviceName : "ALL",
                            "region", regionName != null ? regionName : "ALL",
                            "forecastPeriods", periods,
                            "totalDataPoints", forecast.getDates().size()
                    ));

                    logger.info("✅ Cost forecast generated: {} data points", forecast.getDates().size());
                    return ResponseEntity.ok(response);
                })
                .exceptionally(ex -> {
                    logger.error("❌ Error generating cost forecast", ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("status", "error");
                    errorResponse.put("message", "Failed to generate forecast: " + ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                });
    }

    /**
     * Get comprehensive cost summary
     *
     * Example: /api/xamops/costs/summary?accountId=123
     */
    @GetMapping("/costs/summary")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getCostSummary(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false) String startDate, // <-- ADDED FOR COMPATIBILITY
            @RequestParam(required = false) String endDate) { // <-- ADDED FOR COMPATIBILITY

        logger.info("📋 Cost summary request - Account: {}", accountId);

        // --- FIX: Provide safe defaults for dates if they are null ---
        // This prevents passing null or "DEFAULT_START_DATE" to the service
        String start = (startDate != null) ? startDate : LocalDate.now().withDayOfMonth(1).toString();
        String end = (endDate != null) ? endDate : LocalDate.now().toString();

        // Fetch multiple data points concurrently
        CompletableFuture<List<CostDto>> serviceBreakdown =
                costService.getCostBreakdown(accountId, "SERVICE", null, forceRefresh, start, end); // Pass dates

        CompletableFuture<List<CostDto>> regionBreakdown =
                costService.getCostBreakdown(accountId, "REGION", null, forceRefresh, start, end); // Pass dates

        CompletableFuture<HistoricalCostDto> historicalCost =
                costService.getHistoricalCost(accountId, null, null, days, forceRefresh);

        return CompletableFuture.allOf(serviceBreakdown, regionBreakdown, historicalCost)
                .thenApply(v -> {
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("serviceBreakdown", serviceBreakdown.join());
                    summary.put("regionBreakdown", regionBreakdown.join());
                    summary.put("historicalTrend", historicalCost.join());
                    summary.put("accountId", accountId);
                    summary.put("days", days);

                    logger.info("✅ Cost summary generated for account: {}", accountId);
                    return ResponseEntity.ok(summary);
                })
                .exceptionally(ex -> {
                    logger.error("❌ Error generating cost summary", ex);
                    // --- FIX: Specify generic type for .build() ---
                    return ResponseEntity.<Map<String, Object>>status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    /**
     * Health check endpoint for cost APIs
     */
    @GetMapping("/costs/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "healthy");
        health.put("service", "Cost Management API");
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }
}