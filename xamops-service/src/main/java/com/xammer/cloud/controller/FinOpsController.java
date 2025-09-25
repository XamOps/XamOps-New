package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData.BudgetDetails;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.service.CacheService;
import com.xammer.cloud.service.FinOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public FinOpsController(FinOpsService finOpsService, CacheService cacheService) {
        this.finOpsService = finOpsService;
        this.cacheService = cacheService;
    }

    @GetMapping("/report")
    public CompletableFuture<ResponseEntity<FinOpsReportDto>> getFinOpsReport(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        if (forceRefresh) {
            cacheService.evictFinOpsReportCache(accountId);
        }
        return finOpsService.getFinOpsReport(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching FinOps report for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(null);
                });
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
    public ResponseEntity<Void> createBudget(@RequestParam String accountId, @RequestBody BudgetDetails budgetDetails) {
        try {
            finOpsService.createBudget(accountId, budgetDetails);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception e) {
            logger.error("Error creating budget for account {}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
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