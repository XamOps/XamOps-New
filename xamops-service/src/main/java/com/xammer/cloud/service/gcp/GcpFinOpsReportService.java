package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.*;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    private static final String FINOPS_REPORT_CACHE_PREFIX = "gcp:finops-report:";
    private static final String DB_CACHE_KEY_PREFIX = "GCP_FINOPS::";

    public GcpFinOpsReportService(GcpCostService gcpCostService,
                                  GcpOptimizationService gcpOptimizationService,
                                  GcpBudgetService gcpBudgetService,
                                  RedisCacheService redisCacheService,
                                  CachedDataRepository cachedDataRepository,
                                  ObjectMapper objectMapper) {
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpBudgetService = gcpBudgetService;
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    private void saveToDb(String dbKey, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
        } catch (Exception e) {
            logger.warn("Failed to save GCP FinOps DB snapshot for key {}: {}", dbKey, e.getMessage());
        }
    }

    /**
     * ✅ Async wrapper for FinOps report generation
     */
    public CompletableFuture<GcpFinOpsReportDto> generateFinOpsReport(String gcpProjectId) {
        return generateFinOpsReport(gcpProjectId, false);
    }

    public CompletableFuture<GcpFinOpsReportDto> generateFinOpsReport(String gcpProjectId, boolean forceRefresh) {
        if (forceRefresh) {
            return refreshFinOpsReport(gcpProjectId);
        }
        return CompletableFuture.supplyAsync(() -> generateFinOpsReportSync(gcpProjectId), executor);
    }

    /**
     * ✅ FIXED: Removed @Cacheable, using Redis manually to avoid ClassCastException
     */
    public GcpFinOpsReportDto generateFinOpsReportSync(String gcpProjectId) {
        String cacheKey = FINOPS_REPORT_CACHE_PREFIX + gcpProjectId;
        String dbKey = DB_CACHE_KEY_PREFIX + gcpProjectId;

        // 1. Try Redis cache
        Optional<GcpFinOpsReportDto> cached = redisCacheService.get(cacheKey, GcpFinOpsReportDto.class);
        if (cached.isPresent()) {
            logger.info("✅ Returning Redis-cached FinOps report for project {}", gcpProjectId);
            return cached.get();
        }

        // 2. Try DB snapshot — only use if cost breakdown has actual data
        try {
            Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
            if (dbSnapshot.isPresent()) {
                GcpFinOpsReportDto dbResult = objectMapper.readValue(
                        dbSnapshot.get().getJsonData(), GcpFinOpsReportDto.class);
                boolean hasCostData = dbResult.getCostBreakdown() != null
                        && dbResult.getCostBreakdown().getByService() != null
                        && !dbResult.getCostBreakdown().getByService().isEmpty();
                if (hasCostData) {
                    logger.info("✅ Returning DB-cached FinOps report for project {} — re-warming Redis", gcpProjectId);
                    redisCacheService.put(cacheKey, dbResult, 60);
                    return dbResult;
                }
                logger.info("⚠️ DB snapshot for project {} has empty cost breakdown — regenerating fresh", gcpProjectId);
            }
        } catch (Exception e) {
            logger.warn("DB snapshot load failed for GCP FinOps {}: {}", gcpProjectId, e.getMessage());
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

            CompletableFuture<List<DashboardData.CostAnomaly>> costAnomaliesFuture = gcpCostService.getCostAnomalies(gcpProjectId)
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

            // GCP Budget API doesn't expose per-budget actual spend.
            // Each budget is project-scoped, so actual spend = total project MTD spend (same as AWS behaviour).
            List<GcpBudgetDto> budgets = budgetsFuture.join();
            budgets.forEach(b -> {
                b.setActualSpend(mtdSpend);
                b.setForecastedSpend(kpis.getForecastedSpend());
            });
            report.setBudgets(budgets);

            report.setCostAnomalies(costAnomaliesFuture.join());

            // ✅ Cache the complete report
            redisCacheService.put(cacheKey, report, 60);
            saveToDb(dbKey, report);
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
     * Generate FinOps report with custom date range filtering.
     */
    public CompletableFuture<GcpFinOpsReportDto> generateFinOpsReportWithDateRange(
            String gcpProjectId, String startDate, String endDate, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() ->
                generateFinOpsReportWithDateRangeSync(gcpProjectId, startDate, endDate, forceRefresh), executor);
    }

    /**
     * Generate FinOps report with custom date range - synchronous version.
     */
    public GcpFinOpsReportDto generateFinOpsReportWithDateRangeSync(
            String gcpProjectId, String startDate, String endDate, boolean forceRefresh) {

        String cacheKey = FINOPS_REPORT_CACHE_PREFIX + gcpProjectId + ":" + startDate + ":" + endDate;

        if (!forceRefresh) {
            Optional<GcpFinOpsReportDto> cached = redisCacheService.get(cacheKey, GcpFinOpsReportDto.class);
            if (cached.isPresent()) {
                logger.info("Returning cached FinOps report for project {} (dates: {} to {})",
                        gcpProjectId, startDate, endDate);
                return cached.get();
            }
        }

        logger.info("Generating FinOps report for GCP project {} with date range: {} to {}",
                gcpProjectId, startDate, endDate);

        GcpFinOpsReportDto report = new GcpFinOpsReportDto();
        GcpFinOpsReportDto.Kpis kpis = new GcpFinOpsReportDto.Kpis();
        GcpFinOpsReportDto.CostBreakdown costBreakdown = new GcpFinOpsReportDto.CostBreakdown();

        try {
            // Fetch data with date range filtering
            CompletableFuture<List<GcpCostDto>> costByServiceFuture = gcpCostService
                    .getBillingSummaryWithDateRange(gcpProjectId, startDate, endDate)
                    .exceptionally(ex -> {
                        logger.error("Failed to get cost by service for project {} (dates: {} to {})",
                                gcpProjectId, startDate, endDate, ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<GcpCostDto>> costByRegionFuture = gcpCostService
                    .getCostByRegionWithDateRange(gcpProjectId, startDate, endDate)
                    .exceptionally(ex -> {
                        logger.error("Failed to get cost by region for project {} (dates: {} to {})",
                                gcpProjectId, startDate, endDate, ex);
                        return Collections.emptyList();
                    });

            // MTD and last month spend are not filtered by date range
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

            // Optimization data
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

            CompletableFuture<List<DashboardData.CostAnomaly>> costAnomaliesFuture = gcpCostService.getCostAnomalies(gcpProjectId)
                    .exceptionally(ex -> {
                        logger.error("Failed to detect cost anomalies for project {}", gcpProjectId, ex);
                        return Collections.emptyList();
                    });

            // Wait for all futures
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

            // Populate the report
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

            // GCP Budget API doesn't expose per-budget actual spend.
            // Each budget is project-scoped, so actual spend = total project MTD spend (same as AWS behaviour).
            List<GcpBudgetDto> budgets = budgetsFuture.join();
            budgets.forEach(b -> {
                b.setActualSpend(mtdSpend);
                b.setForecastedSpend(kpis.getForecastedSpend());
            });
            report.setBudgets(budgets);

            report.setCostAnomalies(costAnomaliesFuture.join());

            // Cache the report
            redisCacheService.put(cacheKey, report, 60);
            logger.info("Successfully generated and cached FinOps report for GCP project {} (dates: {} to {})",
                    gcpProjectId, startDate, endDate);

            return report;

        } catch (CompletionException ce) {
            logger.error("Completion exception while generating FinOps report for project {} (dates: {} to {})",
                    gcpProjectId, startDate, endDate, ce.getCause());
        } catch (Exception e) {
            logger.error("Unexpected error while generating FinOps report for project {} (dates: {} to {})",
                    gcpProjectId, startDate, endDate, e);
        }

        return new GcpFinOpsReportDto();
    }

    /**
     * Force refresh - evict cache and regenerate
     */
    public CompletableFuture<GcpFinOpsReportDto> refreshFinOpsReport(String gcpProjectId) {
        String cacheKey = FINOPS_REPORT_CACHE_PREFIX + gcpProjectId;
        String dbKey = DB_CACHE_KEY_PREFIX + gcpProjectId;
        redisCacheService.evict(cacheKey);
        cachedDataRepository.deleteById(dbKey);
        logger.info("Forced refresh of FinOps report for project {} — evicted Redis + both DB snapshots", gcpProjectId);
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
