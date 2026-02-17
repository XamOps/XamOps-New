package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.service.FinOpsRefreshService;
import com.xammer.cloud.service.FinOpsService;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/finops")
public class FinOpsController {

    private static final Logger logger = LoggerFactory.getLogger(FinOpsController.class);

    private final FinOpsService finOpsService;
    private final RedisCacheService cacheService;
    private final FinOpsRefreshService finOpsRefreshService;

    public FinOpsController(FinOpsService finOpsService,
            RedisCacheService cacheService,
            FinOpsRefreshService finOpsRefreshService) {
        this.finOpsService = finOpsService;
        this.cacheService = cacheService;
        this.finOpsRefreshService = finOpsRefreshService;
    }

    @GetMapping("/report")
    public ResponseEntity<?> getFinOpsReport(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        String cacheKey = "finopsReport-" + accountId;
        if (startDate != null && endDate != null) {
            cacheKey += "-" + startDate + "-" + endDate;
        }

        if (!forceRefresh) {
            Optional<FinOpsReportDto> cachedReport = cacheService.get(cacheKey, FinOpsReportDto.class);
            if (cachedReport.isPresent()) {
                logger.info("Serving report from cache for account {}", accountId);
                return ResponseEntity.ok(cachedReport.get());
            }
        }

        logger.info("Fetching live report for account {}", accountId);

        try {
            FinOpsReportDto report = finOpsService.getFinOpsReport(accountId, forceRefresh, startDate, endDate).join();

            if (report == null) {
                return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
            }

            cacheService.put(cacheKey, report, 10);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            logger.error("Error fetching FinOps report for account {}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve FinOps report.",
                            "message", e.getMessage() != null ? e.getMessage() : "Unknown error"));
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
    public ResponseEntity<DashboardData.BudgetDetails> createBudget(@RequestParam String accountId,
            @RequestBody DashboardData.BudgetDetails budgetDetails, Principal principal) {
        try {
            DashboardData.BudgetDetails createdBudget = finOpsService.createBudget(accountId, budgetDetails,
                    principal.getName());
            return ResponseEntity.status(HttpStatus.CREATED).body(createdBudget);
        } catch (Exception e) {
            logger.error("Error creating budget for account {}", accountId, e);
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