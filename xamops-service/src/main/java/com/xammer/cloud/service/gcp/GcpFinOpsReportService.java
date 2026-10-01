package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.*;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
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
import java.util.concurrent.TimeUnit;

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
    private final CloudAccountRepository cloudAccountRepository;
    private final CspBillingIngestionService cspBillingIngestionService;

    private static final String FINOPS_REPORT_CACHE_PREFIX = "gcp:finops-report:";
    private static final String DB_CACHE_KEY_PREFIX = "GCP_FINOPS::";

    /** Redis TTL (minutes) for a report that actually carries cost figures. */
    private static final int CACHE_TTL_MINUTES = 60;

    /**
     * Redis TTL (minutes) for a report with no cost figures. Kept short on purpose: an
     * empty report is usually a transient failure (section timeout, missing billing
     * source) and must not be served for a full hour, but caching it briefly stops a
     * genuinely zero-spend project from re-querying BigQuery on every request.
     */
    private static final int EMPTY_CACHE_TTL_MINUTES = 5;

    public GcpFinOpsReportService(GcpCostService gcpCostService,
                                  GcpOptimizationService gcpOptimizationService,
                                  GcpBudgetService gcpBudgetService,
                                  RedisCacheService redisCacheService,
                                  CachedDataRepository cachedDataRepository,
                                  ObjectMapper objectMapper,
                                  CloudAccountRepository cloudAccountRepository,
                                  CspBillingIngestionService cspBillingIngestionService) {
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpBudgetService = gcpBudgetService;
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cspBillingIngestionService = cspBillingIngestionService;
    }

    /**
     * True when a report carries usable cost figures.
     * <p>
     * Every section future in the report is wrapped in {@code orTimeout(...).exceptionally(...)}
     * returning {@code 0.0} / {@code emptyList()}, so a timed-out assembly is
     * indistinguishable from a real result by type alone. This is the test that tells
     * them apart, and it gates both reads (don't serve a dud snapshot) and writes
     * (don't promote a transient timeout into a cached fact).
     */
    private boolean hasCostData(GcpFinOpsReportDto report) {
        if (report == null) {
            return false;
        }
        boolean hasBreakdown = report.getCostBreakdown() != null
                && report.getCostBreakdown().getByService() != null
                && !report.getCostBreakdown().getByService().isEmpty();
        boolean hasSpend = report.getKpis() != null
                && (report.getKpis().getMonthToDateSpend() > 0.0
                    || report.getKpis().getLastMonthSpend() > 0.0);
        return hasBreakdown || hasSpend;
    }

    /**
     * Cache an assembled report, but only persist a DB snapshot when it holds real data.
     * An empty report gets a short Redis TTL and no snapshot, so it expires quickly
     * instead of outliving the failure that produced it.
     */
    private void cacheReport(String cacheKey, String dbKey, GcpFinOpsReportDto report, String gcpProjectId) {
        if (hasCostData(report)) {
            redisCacheService.put(cacheKey, report, CACHE_TTL_MINUTES);
            saveToDb(dbKey, report);
            logger.info("💾 Successfully generated and cached FinOps report for GCP project: {}", gcpProjectId);
            return;
        }
        redisCacheService.put(cacheKey, report, EMPTY_CACHE_TTL_MINUTES);
        logger.warn("⚠️ FinOps report for {} has no cost data — caching for {} min only and skipping the DB snapshot "
                        + "so a transient failure does not persist",
                gcpProjectId, EMPTY_CACHE_TTL_MINUTES);
    }

    /**
     * The account behind a project, when it is reseller-billed (CSP-managed).
     * Empty for a normal project, or when the lookup fails.
     */
    private Optional<CloudAccount> findCspManagedAccount(String gcpProjectId) {
        try {
            return cloudAccountRepository.findByGcpProjectId(gcpProjectId)
                    .filter(CloudAccount::isCspManaged);
        } catch (Exception e) {
            logger.warn("Could not resolve account for project {} while checking CSP billing: {}",
                    gcpProjectId, e.getMessage());
            return Optional.empty();
        }
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
     * Delete a DB snapshot row only when it exists.
     * <p>
     * Spring Data's {@code deleteById} runs a {@code findById} first and throws
     * {@code EmptyResultDataAccessException} ("No class ... entity with id ... exists!")
     * when the row is absent. A project whose report was never snapshotted (e.g. a
     * zero-cost report that {@link #cacheReport} deliberately skips persisting) has no
     * row, so an unguarded delete on the refresh/clear path surfaces as a 500. Guarding
     * with {@code existsById} makes a missing row a harmless no-op — mirroring how the
     * Redis {@code evict} tolerates a missing key — and the try/catch keeps any other
     * failure from turning invalidation into an error, matching {@link #saveToDb}.
     */
    private void deleteDbSnapshotIfPresent(String dbKey) {
        try {
            if (cachedDataRepository.existsById(dbKey)) {
                cachedDataRepository.deleteById(dbKey);
            }
        } catch (Exception e) {
            logger.warn("Failed to delete GCP FinOps DB snapshot for key {}: {}", dbKey, e.getMessage());
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
                if (hasCostData(dbResult)) {
                    logger.info("✅ Returning DB-cached FinOps report for project {} — re-warming Redis", gcpProjectId);
                    redisCacheService.put(cacheKey, dbResult, CACHE_TTL_MINUTES);
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
            // 9 parallel futures — each with 55s per-future timeout (mirrors AWS FinOps section timeouts)
            CompletableFuture<List<GcpCostDto>> costByServiceFuture = gcpCostService.getBillingSummary(gcpProjectId)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] cost-by-service timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

            CompletableFuture<List<GcpCostDto>> costByRegionFuture = gcpCostService.getCostByRegion(gcpProjectId)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] cost-by-region timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

            CompletableFuture<Double> mtdSpendFuture = gcpCostService.getUnfilteredMonthToDateSpend(gcpProjectId)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] MTD spend timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return 0.0; });

            CompletableFuture<Double> lastMonthSpendFuture = gcpCostService.getLastMonthSpend(gcpProjectId)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] last-month spend timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return 0.0; });

            CompletableFuture<List<GcpWasteItem>> wasteFuture = CompletableFuture.supplyAsync(
                            () -> gcpOptimizationService.getWasteReport(gcpProjectId), executor)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] waste timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

            CompletableFuture<List<GcpOptimizationRecommendation>> recommendationsFuture = CompletableFuture.supplyAsync(
                            () -> gcpOptimizationService.getRightsizingRecommendations(gcpProjectId), executor)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] rightsizing timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

            CompletableFuture<DashboardData.OptimizationSummary> optimizationSummaryFuture = CompletableFuture.supplyAsync(
                            () -> gcpOptimizationService.getOptimizationSummary(gcpProjectId), executor)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] opt-summary timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return new DashboardData.OptimizationSummary(0.0, 0); });

            CompletableFuture<List<GcpBudgetDto>> budgetsFuture = gcpBudgetService.getBudgets(gcpProjectId)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] budgets timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

            CompletableFuture<List<DashboardData.CostAnomaly>> costAnomaliesFuture = gcpCostService.getCostAnomalies(gcpProjectId, false)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] anomalies timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

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
            //
            // GcpBudgetService already backfills these from the billing export. Overwriting here
            // reuses the MTD figure this report has just fetched in parallel, saving a second query —
            // so the reason string it may have attached has to be cleared, or a stale "spend
            // unavailable" note would ride alongside a figure that is now present.
            List<GcpBudgetDto> budgets = budgetsFuture.join();
            budgets.forEach(b -> {
                b.setActualSpend(mtdSpend);
                b.setForecastedSpend(kpis.getForecastedSpend());
                b.setSpendUnavailableReason(null);
            });
            report.setBudgets(budgets);

            report.setCostAnomalies(costAnomaliesFuture.join());

            // ✅ Cache the complete report — but only snapshot it if it has real figures
            cacheReport(cacheKey, dbKey, report, gcpProjectId);

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
        String dbKey    = DB_CACHE_KEY_PREFIX + gcpProjectId + ":" + startDate + ":" + endDate;

        if (!forceRefresh) {
            Optional<GcpFinOpsReportDto> cached = redisCacheService.get(cacheKey, GcpFinOpsReportDto.class);
            if (cached.isPresent()) {
                logger.info("✅ Returning Redis-cached FinOps report for project {} (dates: {} to {})", gcpProjectId, startDate, endDate);
                return cached.get();
            }
            // DB fallback — survives Redis restarts
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    GcpFinOpsReportDto dbResult = objectMapper.readValue(dbSnapshot.get().getJsonData(), GcpFinOpsReportDto.class);
                    if (hasCostData(dbResult)) {
                        logger.info("✅ Returning DB-cached FinOps report for project {} (dates {} to {}) — re-warming Redis", gcpProjectId, startDate, endDate);
                        redisCacheService.put(cacheKey, dbResult, CACHE_TTL_MINUTES);
                        return dbResult;
                    }
                }
            } catch (Exception e) {
                logger.warn("DB snapshot load failed for GCP FinOps date-range {}: {}", gcpProjectId, e.getMessage());
            }
            // Stable key fallback — try the nightly-warmed default key when date-range key misses
            try {
                Optional<CachedData> stableSnap = cachedDataRepository.findById(DB_CACHE_KEY_PREFIX + gcpProjectId);
                if (stableSnap.isPresent()) {
                    GcpFinOpsReportDto stable = objectMapper.readValue(stableSnap.get().getJsonData(), GcpFinOpsReportDto.class);
                    if (hasCostData(stable)) {
                        logger.info("✅ Stable DB snapshot fallback for project {} (date-range miss)", gcpProjectId);
                        redisCacheService.put(cacheKey, stable, CACHE_TTL_MINUTES);
                        return stable;
                    }
                }
            } catch (Exception e) {
                logger.warn("Stable key fallback failed for {}: {}", gcpProjectId, e.getMessage());
            }
        }

        logger.info("Generating FinOps report for GCP project {} with date range: {} to {}",
                gcpProjectId, startDate, endDate);

        GcpFinOpsReportDto report = new GcpFinOpsReportDto();
        GcpFinOpsReportDto.Kpis kpis = new GcpFinOpsReportDto.Kpis();
        GcpFinOpsReportDto.CostBreakdown costBreakdown = new GcpFinOpsReportDto.CostBreakdown();

        try {
            // 9 parallel futures — each with 55s per-future timeout
            CompletableFuture<List<GcpCostDto>> costByServiceFuture = gcpCostService
                    .getBillingSummaryWithDateRange(gcpProjectId, startDate, endDate, forceRefresh)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] cost-by-service timed out/failed for {} ({}-{}): {}", gcpProjectId, startDate, endDate, ex.getMessage()); return Collections.emptyList(); });

            CompletableFuture<List<GcpCostDto>> costByRegionFuture = gcpCostService
                    .getCostByRegionWithDateRange(gcpProjectId, startDate, endDate, forceRefresh)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] cost-by-region timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

            CompletableFuture<Double> mtdSpendFuture = gcpCostService.getUnfilteredMonthToDateSpend(gcpProjectId)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] MTD spend timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return 0.0; });

            CompletableFuture<Double> lastMonthSpendFuture = gcpCostService.getLastMonthSpend(gcpProjectId)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] last-month spend timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return 0.0; });

            CompletableFuture<List<GcpWasteItem>> wasteFuture = CompletableFuture.supplyAsync(
                            () -> gcpOptimizationService.getWasteReport(gcpProjectId), executor)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] waste timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

            CompletableFuture<List<GcpOptimizationRecommendation>> recommendationsFuture = CompletableFuture.supplyAsync(
                            () -> gcpOptimizationService.getRightsizingRecommendations(gcpProjectId), executor)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] rightsizing timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

            CompletableFuture<DashboardData.OptimizationSummary> optimizationSummaryFuture = CompletableFuture.supplyAsync(
                            () -> gcpOptimizationService.getOptimizationSummary(gcpProjectId), executor)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] opt-summary timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return new DashboardData.OptimizationSummary(0.0, 0); });

            CompletableFuture<List<GcpBudgetDto>> budgetsFuture = gcpBudgetService.getBudgets(gcpProjectId)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] budgets timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

            CompletableFuture<List<DashboardData.CostAnomaly>> costAnomaliesFuture = gcpCostService.getCostAnomalies(gcpProjectId, forceRefresh)
                    .orTimeout(55, TimeUnit.SECONDS)
                    .exceptionally(ex -> { logger.warn("[GcpFinOps] anomalies timed out/failed for {}: {}", gcpProjectId, ex.getMessage()); return Collections.emptyList(); });

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
            //
            // GcpBudgetService already backfills these from the billing export. Overwriting here
            // reuses the MTD figure this report has just fetched in parallel, saving a second query —
            // so the reason string it may have attached has to be cleared, or a stale "spend
            // unavailable" note would ride alongside a figure that is now present.
            List<GcpBudgetDto> budgets = budgetsFuture.join();
            budgets.forEach(b -> {
                b.setActualSpend(mtdSpend);
                b.setForecastedSpend(kpis.getForecastedSpend());
                b.setSpendUnavailableReason(null);
            });
            report.setBudgets(budgets);

            report.setCostAnomalies(costAnomaliesFuture.join());

            // Save to Redis AND DB — same as non-date-range path, and same guard: an
            // empty report is cached briefly but never snapshotted.
            cacheReport(cacheKey, dbKey, report, gcpProjectId);

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

        // Always safe: drop the assembled report so it gets rebuilt from its sections.
        // Guard the delete behind existsById (via deleteDbSnapshotIfPresent) exactly as
        // clearFinOpsReportCache does — an unguarded deleteById on a project that was never
        // snapshotted throws EmptyResultDataAccessException ("No ... CachedData entity with
        // id GCP_FINOPS::<project> exists!"), surfacing as a 500 on the refresh path.
        redisCacheService.evict(cacheKey);
        deleteDbSnapshotIfPresent(dbKey);

        Optional<CloudAccount> cspAccount = findCspManagedAccount(gcpProjectId);
        if (cspAccount.isPresent()) {
            // Reseller-billed project. The customer project has no billing export of its
            // own, so the direct BigQuery path finds no table and yields zeros — and the
            // section caches hold the ONLY copy of the reseller figures. Evicting them
            // here would delete live data and replace it with zeros, so refresh by
            // re-running the reseller ingestion instead. Mirrors
            // GcpDataService.refreshCspBillingIfManaged().
            logger.info("🔄 CSP-managed project {} — refreshing via reseller billing ingestion, "
                    + "skipping cost cache eviction", gcpProjectId);
            try {
                cspBillingIngestionService.ingestForAccountId(cspAccount.get().getId());
            } catch (Exception e) {
                logger.error("CSP billing ingestion failed on FinOps refresh for project {}: {}",
                        gcpProjectId, e.getMessage(), e);
            }
            return generateFinOpsReport(gcpProjectId);
        }

        // The report is assembled from per-section cost caches (billing summary, region,
        // MTD spend, history, anomalies, ...). Evicting only the report-level cache would
        // rebuild the report from those stale section caches, so clear them too. This is
        // what makes a manual force-refresh actually re-query BigQuery against the current
        // billing export table.
        gcpCostService.evictAllCostCaches(gcpProjectId);
        logger.info("Forced refresh of FinOps report for project {} — evicted report + section caches (Redis + DB)", gcpProjectId);
        return generateFinOpsReport(gcpProjectId);
    }

    /**
     * ✅ Clear FinOps report cache for a specific project
     */
    public void clearFinOpsReportCache(String gcpProjectId) {
        String cacheKey = FINOPS_REPORT_CACHE_PREFIX + gcpProjectId;
        String dbKey    = DB_CACHE_KEY_PREFIX + gcpProjectId;
        redisCacheService.evict(cacheKey);
        deleteDbSnapshotIfPresent(dbKey);
        logger.info("🗑️ Cleared FinOps report cache (Redis + DB) for project {}", gcpProjectId);
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
