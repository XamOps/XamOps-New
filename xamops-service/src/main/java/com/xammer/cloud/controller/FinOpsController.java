package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData.BudgetDetails;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.service.CacheService;
import com.xammer.cloud.service.FinOpsRefreshService;
import com.xammer.cloud.service.FinOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.xammer.cloud.dto.DashboardData;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/finops")
public class FinOpsController {

    private static final Logger logger = LoggerFactory.getLogger(FinOpsController.class);

    private final FinOpsService finOpsService;
    private final CacheService cacheService;
    private final FinOpsRefreshService finOpsRefreshService;
    private final com.xammer.cloud.service.DashboardDataService dashboardDataService;

    public FinOpsController(FinOpsService finOpsService, CacheService cacheService,
            FinOpsRefreshService finOpsRefreshService,
            com.xammer.cloud.service.DashboardDataService dashboardDataService) {
        this.finOpsService = finOpsService;
        this.cacheService = cacheService;
        this.finOpsRefreshService = finOpsRefreshService;
        this.dashboardDataService = dashboardDataService;
    }

    @GetMapping("/report")
    public ResponseEntity<?> getFinOpsReport(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        try {
            // If date parameters are provided, include them in cache key
            String cacheKey = "finops-" + accountId;
            if (startDate != null && endDate != null) {
                cacheKey += "-" + startDate + "-" + endDate;
            }

            if (!forceRefresh) {
                // ⭐ NEW: Check database cache first (only if no custom dates)
                if (startDate == null && endDate == null) {
                    FinOpsReportDto cachedReport = dashboardDataService.getFinOpsSnapshot("finops-" + accountId);
                    if (cachedReport != null) {
                        logger.info("Serving FinOps report from database cache for account {}", accountId);
                        return ResponseEntity.ok(cachedReport);
                    }
                }
            }

            // Fallback: Fetch fresh data
            if (startDate != null && endDate != null) {
                logger.info("Fetching FinOps report for account {} with date range: {} to {}", accountId, startDate,
                        endDate);
            } else {
                logger.info("Fetching fresh FinOps report for account {}", accountId);
            }

            FinOpsReportDto report = finOpsService.getFinOpsReport(accountId, forceRefresh, startDate, endDate).join();

            if (report == null) {
                logger.warn("FinOps report returned null for account {} (dates: {} to {})", accountId, startDate,
                        endDate);
                return ResponseEntity.status(HttpStatus.NO_CONTENT)
                        .body(Map.of("error", "No data available for the specified date range."));
            }

            logger.info("Successfully fetched FinOps report for account {} (dates: {} to {})", accountId, startDate,
                    endDate);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            logger.error("Error fetching FinOps report for account {} (dates: {} to {}): {}",
                    accountId, startDate, endDate, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve FinOps report.",
                            "message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                            "type", e.getClass().getSimpleName()));
        }
    }

    @GetMapping("/cost-by-tag")
    public CompletableFuture<ResponseEntity<List<Map<String, Object>>>> getCostByTag(
            @RequestParam String accountId,
            @RequestParam String tagKey,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return finOpsService.getCostByTag(accountId, tagKey, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching cost by tag for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @PostMapping("/budgets")
    public ResponseEntity<BudgetDetails> createBudget(@RequestParam String accountId,
            @RequestBody BudgetDetails budgetDetails, Principal principal) {
        try {
            // Capture the returned DTO from the service
            BudgetDetails createdBudget = finOpsService.createBudget(accountId, budgetDetails, principal.getName());
            // Return 201 Created with the new budget object as JSON
            return ResponseEntity.status(HttpStatus.CREATED).body(createdBudget);
        } catch (Exception e) {
            logger.error("Error creating budget for account {}", accountId, e);
            // Return an error (body can be null or an error map)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @DeleteMapping("/budgets")
    public ResponseEntity<Void> deleteBudget(@RequestParam String accountId, @RequestParam String budgetName) {
        try {
            finOpsService.deleteBudget(accountId, budgetName);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Error deleting budget '{}' for account {}", budgetName, accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}