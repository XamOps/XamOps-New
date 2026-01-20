package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.service.azure.AzureDashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/xamops/azure")
public class AzureDashboardController {

    private static final Logger log = LoggerFactory.getLogger(AzureDashboardController.class);

    @Autowired
    private AzureDashboardService azureDashboardService;

    /**
     * ✅ Get Azure dashboard data from DATABASE CACHE
     *
     * Data is pre-cached by nightly scheduled job (4:30 AM IST).
     * This endpoint ALWAYS returns cached data - no live API calls.
     * force parameter is ignored for security (only scheduled jobs can refresh).
     *
     * Example: GET /api/xamops/azure/dashboard-data?accountId=subscription-id
     */
    @GetMapping("/dashboard-data")
    public ResponseEntity<AzureDashboardData> getDashboardData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            // IGNORE force from user requests - always return cached data
            // Live data fetch is ONLY allowed via scheduled nightly jobs
            log.info("📊 Azure Dashboard request - Subscription: {} (returning cached data)", accountId);

            AzureDashboardData data = azureDashboardService.getDashboardData(accountId, false); // Always false - use cache only
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error fetching Azure dashboard data for accountId: {}", accountId, e);
            return ResponseEntity.status(500).build();
        }
    }
}