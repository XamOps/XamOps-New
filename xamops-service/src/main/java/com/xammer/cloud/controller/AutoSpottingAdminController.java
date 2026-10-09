package com.xammer.cloud.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xammer.cloud.service.AutoSpottingService;

import lombok.RequiredArgsConstructor;

/**
 * AutoSpottingAdminController
 *
 * Provides administrative insights and global metrics for the AutoSpotting
 * feature. Matches requirements for the BillOps Admin Dashboard.
 */
@RestController
@RequestMapping("/api/admin/autospotting")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
public class AutoSpottingAdminController {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingAdminController.class);
    private final AutoSpottingService autoSpottingService;

    /**
     * Get global KPI metrics for AutoSpotting GET
     * /api/admin/autospotting/total-savings
     */
    @GetMapping("/total-savings")
    public ResponseEntity<Map<String, Object>> getTotalSavings() {
        log.info("Fetching global AutoSpotting KPI metrics");
        return ResponseEntity.ok(autoSpottingService.getAdminDashboardMetrics());
    }

    /**
     * Get recent spot activity across all accounts GET
     * /api/admin/autospotting/recent-activity
     */
    @GetMapping("/recent-activity")
    public ResponseEntity<List<Map<String, Object>>> getRecentActivity(@RequestParam(defaultValue = "50") int limit) {
        log.info("Fetching recent spot activity (limit={})", limit);
        return ResponseEntity.ok(autoSpottingService.getAdminRecentActivity(limit));
    }

    /**
     * Get aggregated historical savings for the trend chart GET
     * /api/admin/autospotting/historical-savings
     */
    @GetMapping("/historical-savings")
    public ResponseEntity<List<Map<String, Object>>> getHistoricalSavings(@RequestParam(defaultValue = "30") int days) {
        log.info("Fetching aggregated historical savings for the last {} days", days);
        return ResponseEntity.ok(autoSpottingService.getAdminHistoricalSavings(days));
    }

    /**
     * Get breakdown of savings per account GET
     * /api/admin/autospotting/account-breakdown
     */
    @GetMapping("/account-breakdown")
    public ResponseEntity<List<Map<String, Object>>> getAccountBreakdown() {
        log.info("Fetching savings breakdown per account");
        return ResponseEntity.ok(autoSpottingService.getAdminAccountBreakdown());
    }

    /**
     * Get per-customer AutoSpotting savings broken down by provider (AWS/GCP).
     * Only includes customers with AutoSpotting enabled on at least one account.
     * GET /api/admin/autospotting/customer-savings
     */
    @GetMapping("/customer-savings")
    public ResponseEntity<List<Map<String, Object>>> getCustomerSavings() {
        log.info("Fetching per-customer AutoSpotting savings by provider");
        return ResponseEntity.ok(autoSpottingService.getCustomerSpotSavings());
    }
}
