package com.xammer.cloud.service.gcp;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
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

    public GcpFinOpsReportService(GcpCostService gcpCostService,
                                  GcpOptimizationService gcpOptimizationService,
                                  GcpBudgetService gcpBudgetService) {
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpBudgetService = gcpBudgetService;
    }

    public CompletableFuture<GcpFinOpsReportDto> generateFinOpsReport(String gcpProjectId) {
        // This method asynchronously calls the synchronous report generation logic
        return CompletableFuture.supplyAsync(() -> generateFinOpsReportSync(gcpProjectId), executor);
    }

    @Cacheable(value = "gcpFinOpsReport", key = "#gcpProjectId")
    public GcpFinOpsReportDto generateFinOpsReportSync(String gcpProjectId) {
        logger.info("Generating FinOps report for GCP project: {}", gcpProjectId);
        GcpFinOpsReportDto report = new GcpFinOpsReportDto();
        GcpFinOpsReportDto.Kpis kpis = new GcpFinOpsReportDto.Kpis();
        GcpFinOpsReportDto.CostBreakdown costBreakdown = new GcpFinOpsReportDto.CostBreakdown();

        try {
            // Asynchronously gather all data points. Added exception handling to each future.
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

            CompletableFuture<List<GcpWasteItem>> wasteFuture = CompletableFuture.supplyAsync(() -> gcpOptimizationService.getWasteReport(gcpProjectId), executor)
                    .exceptionally(ex -> {
                        logger.error("Failed to get waste report for project {}", gcpProjectId, ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<GcpOptimizationRecommendation>> recommendationsFuture = CompletableFuture.supplyAsync(() -> gcpOptimizationService.getRightsizingRecommendations(gcpProjectId), executor)
                    .exceptionally(ex -> {
                        logger.error("Failed to get rightsizing recommendations for project {}", gcpProjectId, ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<DashboardData.OptimizationSummary> optimizationSummaryFuture = CompletableFuture.supplyAsync(() -> gcpOptimizationService.getOptimizationSummary(gcpProjectId), executor)
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
                    .thenApply(costs -> Collections.emptyList()); // Placeholder for anomaly detection

            // Wait for all futures to complete before proceeding
            CompletableFuture.allOf(costByServiceFuture, costByRegionFuture, mtdSpendFuture, lastMonthSpendFuture, wasteFuture, recommendationsFuture, optimizationSummaryFuture, budgetsFuture, costAnomaliesFuture).join();

            // Populate the report with the results of the futures
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

            logger.info("Successfully generated FinOps report for GCP project: {}", gcpProjectId);
            return report;

        } catch (CompletionException ce) {
            logger.error("A completion exception occurred while generating the FinOps report for project {}. This is likely due to an underlying ClassCastException.", gcpProjectId, ce.getCause());
        } catch (Exception e) {
            logger.error("An unexpected error occurred while generating the FinOps report for project {}", gcpProjectId, e);
        }

        // Return an empty report in case of any failure
        return new GcpFinOpsReportDto();
    }
}