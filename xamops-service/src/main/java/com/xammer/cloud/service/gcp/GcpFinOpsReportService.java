package com.xammer.cloud.service.gcp;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.*;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class GcpFinOpsReportService {

    private static final Logger logger = LoggerFactory.getLogger(GcpFinOpsReportService.class);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final GcpCostService gcpCostService;
    private final GcpOptimizationService gcpOptimizationService;
    private final GcpBudgetService gcpBudgetService;
    private final RedisCacheService redisCacheService;

    private static final String FINOPS_REPORT_CACHE_PREFIX = "gcp:finops-report:";

    public GcpFinOpsReportService(GcpCostService gcpCostService,
                                  GcpOptimizationService gcpOptimizationService,
                                  GcpBudgetService gcpBudgetService,
                                  RedisCacheService redisCacheService) {
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpBudgetService = gcpBudgetService;
        this.redisCacheService = redisCacheService;
    }

    /**
     * ✅ Async wrapper for FinOps report generation
     */
    public CompletableFuture<GcpFinOpsReportDto> generateFinOpsReport(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> generateFinOpsReportSync(gcpProjectId), executor);
    }

    /**
     * ✅ FIXED: Removed @Cacheable, using Redis manually to avoid ClassCastException
     */
    public GcpFinOpsReportDto generateFinOpsReportSync(String gcpProjectId) {
        String cacheKey = FINOPS_REPORT_CACHE_PREFIX + gcpProjectId;

        // Try to get from cache first
        Optional<GcpFinOpsReportDto> cached = redisCacheService.get(cacheKey, GcpFinOpsReportDto.class);
        if (cached.isPresent()) {
            logger.info("✅ Returning cached FinOps report for project {}", gcpProjectId);
            return cached.get();
        }

        logger.info("🔍 Generating fresh FinOps report for GCP project: {}", gcpProjectId);
        GcpFinOpsReportDto report = new GcpFinOpsReportDto();
        GcpFinOpsReportDto.Kpis kpis = new GcpFinOpsReportDto.Kpis();
        GcpFinOpsReportDto.CostBreakdown costBreakdown = new GcpFinOpsReportDto.CostBreakdown();

        try {
            // Asynchronously gather all data points with exception handling
            CompletableFuture<List<GcpCostDto>> costByServiceFuture = gcpCostService.getBillingSummary(gcpProjectId)
                    .exceptionally(ex -> {
                        logger.error("Failed to get cost by service for project {}", gcpProjectId, ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<GcpCostDto>> costByRegionFuture = gcpCostService.getCostByRegion(gcpProjectId)
                    .exceptionally(ex -> {
                        logger.error("Failed to get cost by region for project {}", gcpProjectId, ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<Double> mtdSpendFuture = gcpCostService.getUnfilteredMonthToDateSpend(gcpProjectId)
                    .exceptionally(ex -> {
                        logger.error("Failed to get MTD spend for project {}", gcpProjectId, ex);
                        return 0.0;
                    });

            CompletableFuture<Double> lastMonthSpendFuture = gcpCostService.getLastMonthSpend(gcpProjectId)
                    .exceptionally(ex -> {
                        logger.error("Failed to get last month spend for project {}", gcpProjectId, ex);
                        return 0.0;
                    });

            // ✅ These now return actual data from Redis cache or fresh data
            CompletableFuture<List<GcpWasteItem>> wasteFuture = CompletableFuture.supplyAsync(
                            () -> gcpOptimizationService.getWasteReport(gcpProjectId), executor)
                    .exceptionally(ex -> {
                        logger.error("Failed to get waste report for project {}", gcpProjectId, ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<GcpOptimizationRecommendation>> recommendationsFuture = CompletableFuture.supplyAsync(
                            () -> gcpOptimizationService.getRightsizingRecommendations(gcpProjectId), executor)
                    .exceptionally(ex -> {
                        logger.error("Failed to get rightsizing recommendations for project {}", gcpProjectId, ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<DashboardData.OptimizationSummary> optimizationSummaryFuture = CompletableFuture.supplyAsync(
                            () -> gcpOptimizationService.getOptimizationSummary(gcpProjectId), executor)
                    .exceptionally(ex -> {
                        logger.error("Failed to get optimization summary for project {}", gcpProjectId, ex);
                        return new DashboardData.OptimizationSummary(0.0, 0);
                    });

            CompletableFuture<List<GcpBudgetDto>> budgetsFuture = gcpBudgetService.getBudgets(gcpProjectId)
                    .exceptionally(ex -> {
                        logger.error("Failed to get budgets for project {}", gcpProjectId, ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.CostAnomaly>> costAnomaliesFuture = gcpCostService.getHistoricalCosts(gcpProjectId)
                    .thenApply(costs -> Collections.<DashboardData.CostAnomaly>emptyList()) // Placeholder for anomaly detection
                    .exceptionally(ex -> {
                        logger.error("Failed to detect cost anomalies for project {}", gcpProjectId, ex);
                        return Collections.emptyList();
                    });

            // Wait for all futures to complete
            CompletableFuture.allOf(
                    costByServiceFuture,
                    costByRegionFuture,
                    mtdSpendFuture,
                    lastMonthSpendFuture,
                    wasteFuture,
                    recommendationsFuture,
                    optimizationSummaryFuture,
                    budgetsFuture,
                    costAnomaliesFuture
            ).join();

            // Populate the report with the results
            double mtdSpend = mtdSpendFuture.join();
            kpis.setMonthToDateSpend(mtdSpend);
            kpis.setLastMonthSpend(lastMonthSpendFuture.join());
            kpis.setForecastedSpend(gcpCostService.calculateForecastedSpend(mtdSpend));
            kpis.setPotentialSavings(optimizationSummaryFuture.join().getPotentialSavings());
            report.setKpis(kpis);

            costBreakdown.setByService(costByServiceFuture.join());
            costBreakdown.setByRegion(costByRegionFuture.join());
            report.setCostBreakdown(costBreakdown);

            report.setWastedResources(wasteFuture.join());
            report.setRightsizingRecommendations(recommendationsFuture.join());
            report.setBudgets(budgetsFuture.join());
            report.setCostAnomalies(costAnomaliesFuture.join());

            // ✅ Cache the complete report
            redisCacheService.put(cacheKey, report, 10);
            logger.info("💾 Successfully generated and cached FinOps report for GCP project: {}", gcpProjectId);

            return report;

        } catch (CompletionException ce) {
            logger.error("A completion exception occurred while generating the FinOps report for project {}. " +
                            "This is likely due to an underlying issue in one of the async calls.",
                    gcpProjectId, ce.getCause());
        } catch (Exception e) {
            logger.error("An unexpected error occurred while generating the FinOps report for project {}",
                    gcpProjectId, e);
        }

        // Return an empty report in case of any failure
        logger.warn("⚠️ Returning empty FinOps report for project {} due to errors", gcpProjectId);
        return new GcpFinOpsReportDto();
    }

    /**
     * ✅ Force refresh - evict cache and regenerate
     */
    public CompletableFuture<GcpFinOpsReportDto> refreshFinOpsReport(String gcpProjectId) {
        String cacheKey = FINOPS_REPORT_CACHE_PREFIX + gcpProjectId;
        redisCacheService.evict(cacheKey);
        logger.info("🔄 Forced refresh of FinOps report for project {}", gcpProjectId);
        return generateFinOpsReport(gcpProjectId);
    }

    /**
     * ✅ Clear FinOps report cache for a specific project
     */
    public void clearFinOpsReportCache(String gcpProjectId) {
        String cacheKey = FINOPS_REPORT_CACHE_PREFIX + gcpProjectId;
        redisCacheService.evict(cacheKey);
        logger.info("🗑️ Cleared FinOps report cache for project {}", gcpProjectId);
    }

    /**
     * ✅ Get cached report without regeneration (returns empty if not cached)
     */
    public Optional<GcpFinOpsReportDto> getCachedFinOpsReport(String gcpProjectId) {
        String cacheKey = FINOPS_REPORT_CACHE_PREFIX + gcpProjectId;
        Optional<GcpFinOpsReportDto> cached = redisCacheService.get(cacheKey, GcpFinOpsReportDto.class);

        if (cached.isPresent()) {
            logger.info("✅ Found cached FinOps report for project {}", gcpProjectId);
        } else {
            logger.info("❌ No cached FinOps report found for project {}", gcpProjectId);
        }

        return cached;
    }
}
