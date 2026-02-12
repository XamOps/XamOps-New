package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData.BudgetDetails;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.service.FinOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final com.xammer.cloud.service.DashboardDataService dashboardDataService;

    public FinOpsController(FinOpsService finOpsService,
            com.xammer.cloud.service.DashboardDataService dashboardDataService) {
        this.finOpsService = finOpsService;
        this.dashboardDataService = dashboardDataService;
    }

    @GetMapping("/report")
    public ResponseEntity<Object> getFinOpsReport(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        // 1. Check database cache first if no custom dates and not forcing refresh
        if (!forceRefresh && startDate == null && endDate == null) {
            try {
                FinOpsReportDto cachedReport = dashboardDataService.getFinOpsSnapshot("finops-" + accountId);
                if (cachedReport != null) {
                    logger.info("[FinOpsController] 📦 Serving report from database cache for account {}", accountId);
                    return ResponseEntity.ok(cachedReport);
                }
            } catch (Exception e) {
                logger.warn("[FinOpsController] ⚠️ Failed to fetch from database cache: {}", e.getMessage());
                // Fall through to live fetch
            }
        }

        // 2. Fetch live data
        long startTime = System.currentTimeMillis();
        logger.info(
                "[FinOpsController] 📩 Incoming live report request: accountId={}, forceRefresh={}, range=[{} to {}]",
                accountId, forceRefresh, startDate, endDate);

        try {
            FinOpsReportDto report = finOpsService.getFinOpsReport(accountId, forceRefresh, startDate, endDate).join();

            if (report == null) {
                logger.warn("[FinOpsController] ⚠️ FinOps report generation returned null for account {}", accountId);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error",
                                "Failed to generate FinOps report. This may be due to a temporary system overload or missing data.",
                                "accountId", accountId));
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("[FinOpsController] ✅ Successfully served live report for account {} in {}ms", accountId,
                    duration);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            logger.error("[FinOpsController] ❌ Error handling live report request for account {}: {}", accountId,
                    e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve FinOps report.",
                            "message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                            "type", e.getClass().getSimpleName(),
                            "accountId", accountId));
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