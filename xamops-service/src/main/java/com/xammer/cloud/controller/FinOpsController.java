package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.FinOpsRefreshService;
import com.xammer.cloud.service.FinOpsService;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/xamops/finops")
public class FinOpsController {

    private static final Logger logger = LoggerFactory.getLogger(FinOpsController.class);

    private final FinOpsService finOpsService;
    private final RedisCacheService cacheService;
    private final FinOpsRefreshService finOpsRefreshService;
    private final DashboardDataService dashboardDataService;

    public FinOpsController(FinOpsService finOpsService,
            RedisCacheService cacheService,
            FinOpsRefreshService finOpsRefreshService,
            DashboardDataService dashboardDataService) {
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

        // Include dates in cache key so date-specific requests use a matching cache entry
        String cacheKey = (startDate != null && endDate != null)
                ? "finops-" + accountId + "-" + startDate + "-" + endDate
                : "finops-" + accountId;

        String stableKey = "finops-" + accountId;

        if (!forceRefresh) {
            // 1. Check DB snapshot first (survives container restarts)
            FinOpsReportDto dbCached = dashboardDataService.getFinOpsSnapshot(cacheKey);
            if (dbCached != null) {
                logger.info("[FinOps] Serving from DB snapshot for account {}", accountId);
                return ResponseEntity.ok(dbCached);
            }

            // 1b. Fallback: try stable key populated by nightly warm-up
            if (!cacheKey.equals(stableKey)) {
                FinOpsReportDto stableCached = dashboardDataService.getFinOpsSnapshot(stableKey);
                if (stableCached != null) {
                    logger.info("[FinOps] Serving from stable DB snapshot for account {}", accountId);
                    return ResponseEntity.ok(stableCached);
                }
            }

            // 2. Check Redis cache
            Optional<FinOpsReportDto> cachedReport = cacheService.get(cacheKey, FinOpsReportDto.class);
            if (cachedReport.isPresent()) {
                logger.info("[FinOps] Serving from Redis cache for account {}", accountId);
                return ResponseEntity.ok(cachedReport.get());
            }
        }

        logger.info("[FinOps] Fetching live report for account {}", accountId);

        try {
            FinOpsReportDto report = finOpsService
                    .getFinOpsReport(accountId, forceRefresh, startDate, endDate)
                    .get(120, TimeUnit.SECONDS); // Increased from 50s — 8 parallel AWS calls need headroom

            if (report == null) {
                return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
            }

            // Save to Redis (60 min) and DB (permanent)
            cacheService.put(cacheKey, report, 60);
            dashboardDataService.saveFinOpsSnapshot(cacheKey, report);
            return ResponseEntity.ok(report);
        } catch (TimeoutException e) {
            logger.warn("[FinOps] Report timed out for account {}", accountId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "FinOps report timed out. Please try again."));
        } catch (Exception e) {
            logger.error("[FinOps] Error fetching report for account {}", accountId, e);
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

    /**
     * Lightweight budget list endpoint — used by the global TopBar notification hook.
     * Returns only budget data (1 AWS Budgets API call, Redis-cached) instead of the
     * full FinOps report (8 parallel AWS calls). Prevents notification polling from
     * starving the awsTaskExecutor pool and cascading 504s to other pages.
     */
    @GetMapping("/budgets")
    public CompletableFuture<ResponseEntity<List<DashboardData.BudgetDetails>>> getBudgets(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return finOpsService.getBudgets(accountId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        logger.warn("[FinOps] budgets timed out for account {}", accountId);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Collections.emptyList());
                    }
                    logger.error("Error fetching budgets for account {}", accountId, ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    // -------------------------------------------------------------------------
    // Individual section endpoints — each fetches only one AWS data source.
    // The FinOps page calls these in parallel so slow sections (tagging, wasted)
    // don't block fast sections (billing-summary, budgets).
    // -------------------------------------------------------------------------

    @GetMapping("/billing-summary")
    public CompletableFuture<ResponseEntity<List<DashboardData.BillingSummary>>> getBillingSummary(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        logger.info("[FinOps] billing-summary START — account={} tenant={} thread={}",
                accountId, MDC.get("tenant"), Thread.currentThread().getName());
        final long startMs = System.currentTimeMillis();
        return finOpsService.getBillingSummaryById(accountId, forceRefresh, startDate, endDate)
                .orTimeout(55, TimeUnit.SECONDS)
                .thenApply(data -> {
                    logger.info("[FinOps] billing-summary OK — account={} tenant={} durationMs={}",
                            accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        logger.warn("[FinOps] billing-summary TIMEOUT — account={} tenant={} durationMs={}",
                                accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Collections.emptyList());
                    }
                    logger.error("[FinOps] billing-summary ERROR — account={} tenant={}", accountId, MDC.get("tenant"), ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    @GetMapping("/cost-history")
    public CompletableFuture<ResponseEntity<DashboardData.CostHistory>> getCostHistory(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        logger.info("[FinOps] cost-history START — account={} tenant={} thread={}",
                accountId, MDC.get("tenant"), Thread.currentThread().getName());
        final long startMs = System.currentTimeMillis();
        return finOpsService.getCostHistoryById(accountId, forceRefresh, startDate, endDate)
                .orTimeout(55, TimeUnit.SECONDS)
                .thenApply(data -> {
                    logger.info("[FinOps] cost-history OK — account={} tenant={} durationMs={}",
                            accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        logger.warn("[FinOps] cost-history TIMEOUT — account={} tenant={} durationMs={}",
                                accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(new DashboardData.CostHistory(
                                        Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
                    }
                    logger.error("[FinOps] cost-history ERROR — account={} tenant={}", accountId, MDC.get("tenant"), ex);
                    return ResponseEntity.ok(new DashboardData.CostHistory(
                            Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
                });
    }

    @GetMapping("/cost-by-region")
    public CompletableFuture<ResponseEntity<List<Map<String, Object>>>> getCostByRegion(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        logger.info("[FinOps] cost-by-region START — account={} tenant={} thread={}",
                accountId, MDC.get("tenant"), Thread.currentThread().getName());
        final long startMs = System.currentTimeMillis();
        return finOpsService.getCostByRegionById(accountId, forceRefresh, startDate, endDate)
                .orTimeout(55, TimeUnit.SECONDS)
                .thenApply(data -> {
                    logger.info("[FinOps] cost-by-region OK — account={} tenant={} durationMs={}",
                            accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        logger.warn("[FinOps] cost-by-region TIMEOUT — account={} tenant={} durationMs={}",
                                accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Collections.emptyList());
                    }
                    logger.error("[FinOps] cost-by-region ERROR — account={} tenant={}", accountId, MDC.get("tenant"), ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    @GetMapping("/cost-anomalies")
    public CompletableFuture<ResponseEntity<List<DashboardData.CostAnomaly>>> getCostAnomalies(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        logger.info("[FinOps] cost-anomalies START — account={} tenant={} thread={}",
                accountId, MDC.get("tenant"), Thread.currentThread().getName());
        final long startMs = System.currentTimeMillis();
        return finOpsService.getCostAnomaliesById(accountId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .thenApply(data -> {
                    logger.info("[FinOps] cost-anomalies OK — account={} tenant={} durationMs={}",
                            accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        logger.warn("[FinOps] cost-anomalies TIMEOUT — account={} tenant={} durationMs={}",
                                accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Collections.emptyList());
                    }
                    logger.error("[FinOps] cost-anomalies ERROR — account={} tenant={}", accountId, MDC.get("tenant"), ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    @GetMapping("/tagging-compliance")
    public CompletableFuture<ResponseEntity<DashboardData.TaggingCompliance>> getTaggingCompliance(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        logger.info("[FinOps] tagging-compliance START — account={} tenant={} thread={}",
                accountId, MDC.get("tenant"), Thread.currentThread().getName());
        final long startMs = System.currentTimeMillis();
        return finOpsService.getTaggingComplianceById(accountId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .thenApply(data -> {
                    logger.info("[FinOps] tagging-compliance OK — account={} tenant={} durationMs={}",
                            accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        logger.warn("[FinOps] tagging-compliance TIMEOUT — account={} tenant={} durationMs={}",
                                accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(new DashboardData.TaggingCompliance(0.0, 0, 0, Collections.emptyList()));
                    }
                    logger.error("[FinOps] tagging-compliance ERROR — account={} tenant={}", accountId, MDC.get("tenant"), ex);
                    return ResponseEntity.ok(new DashboardData.TaggingCompliance(0.0, 0, 0, Collections.emptyList()));
                });
    }

    @GetMapping("/wasted-resources")
    public CompletableFuture<ResponseEntity<List<DashboardData.WastedResource>>> getWastedResources(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        logger.info("[FinOps] wasted-resources START — account={} tenant={} thread={}",
                accountId, MDC.get("tenant"), Thread.currentThread().getName());
        final long startMs = System.currentTimeMillis();
        return finOpsService.getWastedResourcesById(accountId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .thenApply(data -> {
                    logger.info("[FinOps] wasted-resources OK — account={} tenant={} durationMs={}",
                            accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        logger.warn("[FinOps] wasted-resources TIMEOUT — account={} tenant={} durationMs={}",
                                accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Collections.emptyList());
                    }
                    logger.error("[FinOps] wasted-resources ERROR — account={} tenant={}", accountId, MDC.get("tenant"), ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    @GetMapping("/rightsizing")
    public CompletableFuture<ResponseEntity<List<DashboardData.OptimizationRecommendation>>> getRightsizing(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        logger.info("[FinOps] rightsizing START — account={} tenant={} thread={}",
                accountId, MDC.get("tenant"), Thread.currentThread().getName());
        final long startMs = System.currentTimeMillis();
        return finOpsService.getRightsizingById(accountId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .thenApply(data -> {
                    logger.info("[FinOps] rightsizing OK — account={} tenant={} durationMs={}",
                            accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        logger.warn("[FinOps] rightsizing TIMEOUT — account={} tenant={} durationMs={}",
                                accountId, MDC.get("tenant"), System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Collections.emptyList());
                    }
                    logger.error("[FinOps] rightsizing ERROR — account={} tenant={}", accountId, MDC.get("tenant"), ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }
}
