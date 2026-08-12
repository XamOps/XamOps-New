package com.xammer.cloud.service;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.JobExecutionHistory;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.ReservationDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.JobExecutionHistoryRepository;
import com.xammer.cloud.service.azure.AzureBillingDataIngestionService;
import com.xammer.cloud.service.azure.AzureCloudListService;
import com.xammer.cloud.service.azure.AzureCostService;
import com.xammer.cloud.service.azure.AzureDashboardService;
import com.xammer.cloud.service.azure.AzurePerformanceService;
import com.xammer.cloud.service.azure.AzureRightsizingService;
import com.xammer.cloud.service.azure.AzureSecurityService;
import com.xammer.cloud.service.azure.AzureWasteService;
import com.xammer.cloud.service.gcp.GcpCloudGuardService;
import com.xammer.cloud.service.gcp.GcpCostDataSnapshotService;
import com.xammer.cloud.service.gcp.GcpCostService;
import com.xammer.cloud.service.gcp.GcpDataService;
import com.xammer.cloud.service.gcp.GcpFinOpsReportService;
import com.xammer.cloud.service.gcp.GcpOptimizationService;
import com.xammer.cloud.service.gcp.GcpPerformanceInsightsService;
import com.xammer.cloud.service.gcp.GcpSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class ProactiveCacheRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(ProactiveCacheRefreshService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final CacheManager cacheManager;
    private final CloudListService cloudListService; // Required to get active regions

    // Services for different pages
    private final DashboardDataService dashboardDataService;
    private final FinOpsService finOpsService;
    private final CostService costService;
    private final SecurityService securityService;
    private final PerformanceInsightsService performanceService;
    private final ReservationService reservationService;
    private final OptimizationService optimizationService;
    private final CloudGuardService cloudGuardService; // For alerts
    private final GcpDataService gcpDataService; // For GCP dashboard caching
    private final AzureDashboardService azureDashboardService; // For Azure dashboard caching

    // GCP page services (non-dashboard)
    private final GcpCostService gcpCostService;
    private final GcpSecurityService gcpSecurityService;
    private final GcpOptimizationService gcpOptimizationService;
    private final GcpPerformanceInsightsService gcpPerformanceInsightsService;
    private final GcpCloudGuardService gcpCloudGuardService;
    private final GcpFinOpsReportService gcpFinOpsReportService;

    // Azure page services (non-dashboard)
    private final AzureWasteService azureWasteService;
    private final AzureSecurityService azureSecurityService;
    private final AzureRightsizingService azureRightsizingService;
    private final AzurePerformanceService azurePerformanceService;
    private final AzureCloudListService azureCloudListService;
    private final AzureCostService azureCostService;
    private final AzureBillingDataIngestionService azureBillingDataIngestionService;

    private final JobExecutionHistoryRepository jobExecutionHistoryRepository;
    private final JdbcTemplate masterJdbcTemplate;

    // ADDED: Environment-aware configuration
    private final com.xammer.cloud.config.SchedulingEnvironmentConfig envConfig;

    // ADDED: SpotAutomation and XamOpsRightsizing caching
    private final AutoSpottingService autoSpottingService;
    private final XamOpsRightsizingService xamOpsRightsizingService;

    // ADDED: DbOps cost caching
    private final DbOpsCostService dbOpsCostService;

    // ADDED: Monthly cost snapshot service (pre-Billing-Conductor persistence)
    private final CostDataSnapshotService costDataSnapshotService;

    // GCP monthly cost snapshot service
    private final GcpCostDataSnapshotService gcpCostDataSnapshotService;

    @Autowired
    public ProactiveCacheRefreshService(
            CloudAccountRepository cloudAccountRepository,
            CacheManager cacheManager,
            CloudListService cloudListService,
            DashboardDataService dashboardDataService,
            FinOpsService finOpsService,
            CostService costService,
            SecurityService securityService,
            PerformanceInsightsService performanceService,
            ReservationService reservationService,
            OptimizationService optimizationService,
            CloudGuardService cloudGuardService,
            GcpDataService gcpDataService,
            AzureDashboardService azureDashboardService,
            GcpCostService gcpCostService,
            GcpSecurityService gcpSecurityService,
            GcpOptimizationService gcpOptimizationService,
            GcpPerformanceInsightsService gcpPerformanceInsightsService,
            GcpCloudGuardService gcpCloudGuardService,
            GcpFinOpsReportService gcpFinOpsReportService,
            AzureWasteService azureWasteService,
            AzureSecurityService azureSecurityService,
            AzureRightsizingService azureRightsizingService,
            AzurePerformanceService azurePerformanceService,
            AzureCloudListService azureCloudListService,
            AzureCostService azureCostService,
            AzureBillingDataIngestionService azureBillingDataIngestionService,
            JobExecutionHistoryRepository jobExecutionHistoryRepository,
            @Qualifier("masterDataSource") DataSource masterDataSource,
            com.xammer.cloud.config.SchedulingEnvironmentConfig envConfig,
            AutoSpottingService autoSpottingService,
            XamOpsRightsizingService xamOpsRightsizingService,
            DbOpsCostService dbOpsCostService,
            CostDataSnapshotService costDataSnapshotService,
            GcpCostDataSnapshotService gcpCostDataSnapshotService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.cacheManager = cacheManager;
        this.cloudListService = cloudListService;
        this.dashboardDataService = dashboardDataService;
        this.finOpsService = finOpsService;
        this.costService = costService;
        this.securityService = securityService;
        this.performanceService = performanceService;
        this.reservationService = reservationService;
        this.optimizationService = optimizationService;
        this.cloudGuardService = cloudGuardService;
        this.gcpDataService = gcpDataService;
        this.azureDashboardService = azureDashboardService;
        this.gcpCostService = gcpCostService;
        this.gcpSecurityService = gcpSecurityService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpPerformanceInsightsService = gcpPerformanceInsightsService;
        this.gcpCloudGuardService = gcpCloudGuardService;
        this.gcpFinOpsReportService = gcpFinOpsReportService;
        this.azureWasteService = azureWasteService;
        this.azureSecurityService = azureSecurityService;
        this.azureRightsizingService = azureRightsizingService;
        this.azurePerformanceService = azurePerformanceService;
        this.azureCloudListService = azureCloudListService;
        this.azureCostService = azureCostService;
        this.azureBillingDataIngestionService = azureBillingDataIngestionService;
        this.jobExecutionHistoryRepository = jobExecutionHistoryRepository;
        this.masterJdbcTemplate = new JdbcTemplate(masterDataSource);
        this.envConfig = envConfig;
        this.autoSpottingService = autoSpottingService;
        this.xamOpsRightsizingService = xamOpsRightsizingService;
        this.dbOpsCostService = dbOpsCostService;
        this.costDataSnapshotService = costDataSnapshotService;
        this.gcpCostDataSnapshotService = gcpCostDataSnapshotService;

        // Log configuration on startup
        envConfig.logConfiguration();
    }

    /**
     * On startup, seeds Redis from existing DB snapshots (forceRefresh=false).
     * This avoids hitting AWS APIs at startup — each service reads DB → warms Redis → returns fast.
     * The nightly @Scheduled refresh*() methods do the actual AWS refresh with forceRefresh=true.
     *
     * Previous approach called refresh*() with forceRefresh=true at startup, which bypassed all caches
     * and hit AWS APIs for every tenant × account simultaneously, causing 503/504 in production under load.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCacheOnStartup() {
        logger.info("[CacheWarmUp] Application ready — seeding Redis from DB snapshots (no API calls at startup)...");
        // All pages (AWS + GCP + Azure): read from DB snapshot → warm Redis. No live API calls.
        CompletableFuture.runAsync(() -> {
            try { seedRedisFromDb(); } catch (Exception e) { logger.warn("[CacheWarmUp] DB seed failed: {}", e.getMessage()); }
        });
        logger.info("[CacheWarmUp] Startup warm-up tasks submitted.");
    }

    /**
     * Seeds Redis from existing DB snapshots for all AWS accounts.
     * Uses forceRefresh=false: Redis miss → DB hit → warm Redis → return fast.
     * No AWS API calls are made unless both Redis and DB are cold (first-ever deployment).
     */
    private void seedRedisFromDb() {
        logger.info("[CacheWarmUp] Starting DB → Redis seed pass for all AWS accounts...");
        for (String tenantId : fetchActiveTenants()) {
            TenantContext.setCurrentTenant(tenantId);
            try {
                List<CloudAccount> accounts = getAllAccounts();
                for (CloudAccount account : accounts) {
                    if (!"AWS".equals(account.getProvider()) || account.getAwsAccountId() == null) continue;
                    String accountId = account.getAwsAccountId();
                    Long id = account.getId();
                    String startDate = LocalDate.now().withDayOfMonth(1).toString();
                    String endDate = LocalDate.now().toString();

                    try { finOpsService.getFinOpsReport(accountId, false, startDate, endDate).join(); }
                    catch (Exception e) { logger.debug("[Seed] FinOps skipped for {}: {}", accountId, e.getMessage()); }

                    try { costService.getCostBreakdown(accountId, "SERVICE", null, false, startDate, endDate).join(); }
                    catch (Exception e) { logger.debug("[Seed] Cost skipped for {}: {}", accountId, e.getMessage()); }

                    try { costService.getCreditsApplied(accountId, false).join(); }
                    catch (Exception e) { logger.debug("[Seed] Credits skipped for {}: {}", accountId, e.getMessage()); }

                    try { reservationService.getReservationPageData(accountId, false).join(); }
                    catch (Exception e) { logger.debug("[Seed] Reservations skipped for {}: {}", accountId, e.getMessage()); }

                    try { cloudGuardService.getAlerts(accountId, false).join(); }
                    catch (Exception e) { logger.debug("[Seed] Alerts skipped for {}: {}", accountId, e.getMessage()); }

                    try { optimizationService.getAllOptimizationRecommendations(accountId, false).join(); }
                    catch (Exception e) { logger.debug("[Seed] Optimization skipped for {}: {}", accountId, e.getMessage()); }

                    try { autoSpottingService.listAsgsAllRegions(id, false); }
                    catch (Exception e) { logger.debug("[Seed] SpotAuto skipped for {}: {}", accountId, e.getMessage()); }

                    try { xamOpsRightsizingService.getLiveRecommendations(accountId, false); }
                    catch (Exception e) { logger.debug("[Seed] Rightsizing skipped for {}: {}", accountId, e.getMessage()); }

                    try {
                        List<DashboardData.RegionStatus> regions = getActiveRegions(account);
                        securityService.getComprehensiveSecurityFindings(account, regions, false).join();
                    }
                    catch (Exception e) { logger.debug("[Seed] Security skipped for {}: {}", accountId, e.getMessage()); }

                    try { performanceService.getInsights(accountId, "ALL", false); }
                    catch (Exception e) { logger.debug("[Seed] Performance skipped for {}: {}", accountId, e.getMessage()); }
                }
                // Pre-warm all historical closed months (2025-01 → last month) into Redis from DB snapshots
                seedHistoricalCostMonths(accounts);
            } finally {
                TenantContext.clear();
            }
        }
        logger.info("[CacheWarmUp] AWS DB → Redis seed complete.");

        // ── GCP pages: DB → Redis seed (forceRefresh=false) ──────────────────────────
        logger.info("[CacheWarmUp] Seeding GCP non-dashboard pages from DB...");
        for (String tenantId : fetchActiveTenants()) {
            TenantContext.setCurrentTenant(tenantId);
            try {
                for (CloudAccount account : getAllAccounts()) {
                    if (!"GCP".equalsIgnoreCase(account.getProvider())) continue;
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;

                    try { gcpDataService.getDashboardData(gcpProjectId, false).join(); }
                    catch (Exception e) { logger.debug("[Seed] GCP Dashboard skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    try { gcpFinOpsReportService.generateFinOpsReportSync(gcpProjectId); }
                    catch (Exception e) { logger.debug("[Seed] GCP FinOps skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    // Warm the keys that /api/xamops/gcp/costs/breakdown actually reads
                    try { gcpCostService.getCostBreakdownSync(gcpProjectId, "SERVICE"); }
                    catch (Exception e) { logger.debug("[Seed] GCP Cost SERVICE skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    try { gcpCostService.getCostBreakdownSync(gcpProjectId, "REGION"); }
                    catch (Exception e) { logger.debug("[Seed] GCP Cost REGION skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    try { gcpCostService.getHistoricalCostsSync(gcpProjectId); }
                    catch (Exception e) { logger.debug("[Seed] GCP Cost History skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    try { gcpCostService.getDailyCostsForForecastSync(gcpProjectId, null, 30); }
                    catch (Exception e) { logger.debug("[Seed] GCP Cost Trend skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    // Gap 1: seed CloudList flat + grouped (DB → Redis, zero GCP API calls if DB is warm)
                    try { gcpDataService.getAllResources(gcpProjectId, false).join(); }
                    catch (Exception e) { logger.debug("[Seed] GCP CloudList skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    try { gcpSecurityService.getSecurityFindings(gcpProjectId, false).join(); }
                    catch (Exception e) { logger.debug("[Seed] GCP Security skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    try { gcpPerformanceInsightsService.getInsights(gcpProjectId, "ALL", false); }
                    catch (Exception e) { logger.debug("[Seed] GCP Performance skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    try { gcpCloudGuardService.getAlerts(gcpProjectId, false).join(); }
                    catch (Exception e) { logger.debug("[Seed] GCP Alerts skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    try { gcpOptimizationService.getWasteReport(gcpProjectId, false); }
                    catch (Exception e) { logger.debug("[Seed] GCP Waste skipped for {}: {}", gcpProjectId, e.getMessage()); }

                    try { gcpOptimizationService.getRightsizingRecommendations(gcpProjectId, false); }
                    catch (Exception e) { logger.debug("[Seed] GCP Rightsizing skipped for {}: {}", gcpProjectId, e.getMessage()); }
                }
            } finally {
                TenantContext.clear();
            }
        }
        logger.info("[CacheWarmUp] GCP DB → Redis seed complete.");

        // ── Azure pages: DB → Redis seed (forceRefresh=false) ────────────────────────
        logger.info("[CacheWarmUp] Seeding Azure non-dashboard pages from DB...");
        for (String tenantId : fetchActiveTenants()) {
            TenantContext.setCurrentTenant(tenantId);
            try {
                for (CloudAccount account : getAllAccounts()) {
                    if (!"Azure".equalsIgnoreCase(account.getProvider())) continue;
                    String subscriptionId = account.getAzureSubscriptionId();
                    if (subscriptionId == null || subscriptionId.isEmpty()) continue;

                    try { azureDashboardService.getDashboardData(subscriptionId, false); }
                    catch (Exception e) { logger.debug("[Seed] Azure Dashboard skipped for {}: {}", subscriptionId, e.getMessage()); }

                    try { azureCostService.getTotalMonthToDateCost(subscriptionId).join(); }
                    catch (Exception e) { logger.debug("[Seed] Azure Cost skipped for {}: {}", subscriptionId, e.getMessage()); }

                    try { azureWasteService.getWasteItems(subscriptionId, false); }
                    catch (Exception e) { logger.debug("[Seed] Azure Waste skipped for {}: {}", subscriptionId, e.getMessage()); }

                    try { azureSecurityService.getSecurityData(subscriptionId, false); }
                    catch (Exception e) { logger.debug("[Seed] Azure Security skipped for {}: {}", subscriptionId, e.getMessage()); }

                    try { azureRightsizingService.getRecommendations(subscriptionId, false); }
                    catch (Exception e) { logger.debug("[Seed] Azure Rightsizing skipped for {}: {}", subscriptionId, e.getMessage()); }

                    try { azurePerformanceService.getPerformanceInsights(subscriptionId, false); }
                    catch (Exception e) { logger.debug("[Seed] Azure Performance skipped for {}: {}", subscriptionId, e.getMessage()); }

                    try { azureCloudListService.getAzureResources(subscriptionId, false); }
                    catch (Exception e) { logger.debug("[Seed] Azure Cloudlist skipped for {}: {}", subscriptionId, e.getMessage()); }
                }
            } finally {
                TenantContext.clear();
            }
        }
        logger.info("[CacheWarmUp] DB → Redis seed complete. All AWS + GCP + Azure pages will serve cached data.");
    }

    /**
     * Pre-warms all historical closed months (SNAPSHOT_START → last month) into Redis for every AWS account.
     * Each call hits the existing CostService snapshot-read logic:
     *   Redis miss → DB snapshot hit → Redis cached → return   (no CE API call)
     * Only falls back to CE API if a month's DB snapshot is somehow missing.
     */
    private void seedHistoricalCostMonths(List<CloudAccount> accounts) {
        YearMonth startYm = YearMonth.of(2025, 1);
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        int warmed = 0;
        int skipped = 0;

        logger.info("[CacheWarmUp] Pre-warming historical cost months ({} → {}) into Redis...", startYm, lastMonth);

        for (CloudAccount account : accounts) {
            if (!"AWS".equals(account.getProvider()) || account.getAwsAccountId() == null) continue;
            String accountId = account.getAwsAccountId();

            for (YearMonth ym = startYm; !ym.isAfter(lastMonth); ym = ym.plusMonths(1)) {
                // Only warm months that have a complete DB snapshot — never fall back to CE API in warmup
                if (!costDataSnapshotService.isSnapshotComplete(accountId, ym)) {
                    skipped++;
                    continue;
                }

                String monthStart = ym.atDay(1).toString();
                String monthEnd   = ym.atEndOfMonth().toString();
                LocalDate startDate = ym.atDay(1);
                LocalDate endDate   = ym.atEndOfMonth();

                try { costService.getCostBreakdown(accountId, "SERVICE", null, false, monthStart, monthEnd).join(); }
                catch (Exception e) { logger.debug("[Seed] HistCost SVC skip {}/{}: {}", accountId, ym, e.getMessage()); }

                try { costService.getCostBreakdown(accountId, "REGION", null, false, monthStart, monthEnd).join(); }
                catch (Exception e) { logger.debug("[Seed] HistCost RGN skip {}/{}: {}", accountId, ym, e.getMessage()); }

                try { costService.getCostBreakdownByServiceAndRegion(accountId, startDate, endDate, false).join(); }
                catch (Exception e) { logger.debug("[Seed] HistCost SvcRgn skip {}/{}: {}", accountId, ym, e.getMessage()); }

                warmed++;
            }
        }
        logger.info("[CacheWarmUp] Historical cost months warm-up done — {} warmed, {} skipped (no snapshot in DB)", warmed, skipped);
    }

    /**
     * Helper to fetch active tenants from Master DB - ENVIRONMENT AWARE
     * - Local/UAT: Returns single configured tenant (uses local/uat DB)
     * - Prod: Returns ALL active tenants from master DB (tenant_config table)
     */
    private List<String> fetchActiveTenants() {
        try {
            if (!envConfig.isSchedulingEnabled()) {
                logger.info("⚠️ Scheduling is disabled in this environment");
                return Collections.emptyList();
            }

            String activeProfile = envConfig.getActiveProfile();
            logger.info("🔒 Environment: {}", activeProfile);

            // In local/UAT, there's no tenant_config table - just return the configured
            // tenant
            if ("local".equals(activeProfile) || "uat".equals(activeProfile) || "preprod".equals(activeProfile) || "prod".equals(activeProfile)) {
                String currentTenant = envConfig.getCurrentTenantId();
                logger.info("✅ Using configured tenant '{}' for {} environment (no tenant_config query)",
                        currentTenant, activeProfile);
                return Collections.singletonList(currentTenant);
            }

            // Fallback: query tenant_config from master DB - GET ALL ACTIVE TENANTS
            List<String> tenants = masterJdbcTemplate.queryForList(
                    "SELECT tenant_id FROM tenant_config WHERE active = true",
                    String.class);

            if (tenants.isEmpty()) {
                logger.warn("⚠️ No active tenants found in tenant_config table");
            } else {
                logger.info("✅ Found {} active tenants for processing: {}", tenants.size(), tenants);
            }

            return tenants;
        } catch (Exception e) {
            logger.error("❌ Failed to fetch tenants from Master DB", e);
            return Collections.emptyList();
        }
    }

    /**
     * Helper method to get all accounts
     */
    private List<CloudAccount> getAllAccounts() {
        return cloudAccountRepository.findAll();
    }

    private String getAccountId(CloudAccount account) {
        return "GCP".equals(account.getProvider()) ? account.getGcpProjectId() : account.getAwsAccountId();
    }

    /**
     * Helper to fetch active regions for an account.
     * Security, Optimization, and Reservation services require this list.
     */
    private List<DashboardData.RegionStatus> getActiveRegions(CloudAccount account) {
        try {
            // We use false for forceRefresh here to avoid re-scanning regions just for the
            // list
            return cloudListService.getRegionStatusForAccount(account, false).join();
        } catch (Exception e) {
            logger.warn("Could not fetch active regions for account {}. Defaulting to empty list.",
                    getAccountId(account));
            return List.of();
        }
    }

    // ===================================================================================
    // DISABLED: Redundant - The 5-minute job in DashboardDataService handles all
    // refreshes
    // ===================================================================================
    // ===================================================================================
    // 2:00 AM -> BillOps Dashboard Refresh (User & Admin)
    // ===================================================================================
    // @Scheduled(cron = "0 0 2 * * WED,SUN")
    // public void refreshBillOpsDashboards() {
    // logger.info("--- [2:00 AM] Starting BillOps Dashboard Refresh ---");

    // // 1. Refresh Global Admin Dashboard
    // try {
    // billOpsDashboardService.refreshAdminDashboard();
    // } catch (Exception e) {
    // logger.error("Failed to refresh Admin Dashboard", e);
    // }

    // // 2. Refresh User Dashboards for each active tenant
    // List<String> tenants = fetchActiveTenants();

    // for (String tenantId : tenants) {
    // JobExecutionHistory jobHistory = new
    // JobExecutionHistory("REFRESH_BILLOPS_DASHBOARD", tenantId,
    // LocalDateTime.now(), "RUNNING");
    // try {
    // TenantContext.setCurrentTenant(tenantId);
    // jobHistory = jobExecutionHistoryRepository.save(jobHistory);

    // billOpsDashboardService.refreshUserDashboardForTenant(tenantId);

    // jobHistory.setStatus("SUCCESS");
    // jobHistory.setEndTime(LocalDateTime.now());
    // jobExecutionHistoryRepository.save(jobHistory);

    // } catch (Exception e) {
    // logger.error("❌ Failed BillOps refresh for tenant {}", tenantId, e);
    // if (jobHistory.getId() != null) {
    // jobHistory.setStatus("FAILURE");
    // jobHistory.setErrorMessage(e.getMessage());
    // jobHistory.setEndTime(LocalDateTime.now());
    // jobExecutionHistoryRepository.save(jobHistory);
    // }
    // } finally {
    // TenantContext.clear();
    // }
    // }
    // }

    // ===================================================================================
    // 2:30 AM -> Cost Management (Data Intensive)
    // ===================================================================================
    @Scheduled(cron = "0 30 2 * * WED,SUN")
    public void refreshCostData() {
        logger.info("--- [2:30 AM] Starting Cost Data Refresh (cost.html) ---");
        String startDate = LocalDate.now().withDayOfMonth(1).toString(); // Start of month
        String endDate = LocalDate.now().toString(); // Today

        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_COST_DATA", tenantId, LocalDateTime.now(),
                    "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    String accountId = getAccountId(account);
                    if (accountId == null)
                        continue;
                    try {
                        // NOTE: getCostBreakdown("SERVICE") is intentionally omitted here — it is
                        // already called at 2:00 AM via refreshFinOps() → getFinOpsReport() →
                        // getBillingSummary() with forceRefresh=true. Calling it again 30 min later
                        // would be a duplicate CE API hit.
                        // NOTE: getTotalMonthToDateCost warms the AWS_MTD_COST key used by the
                        // legacy getDashboardData path. The granular KPI endpoint now derives MTD
                        // from getCostBreakdown("SERVICE") instead, so this is kept for backwards
                        // compatibility only.
                        costService.getTotalMonthToDateCost(accountId, true).join();
                        costService.getLastMonthSpend(accountId, true).join();
                        costService.getCostBreakdownByServiceAndRegion(
                                accountId, LocalDate.parse(startDate), LocalDate.parse(endDate), true).join();
                        costService.getHistoricalCost(accountId, null, null, 30, true).join();
                        costService.getCreditsApplied(accountId, true).join();
                        dbOpsCostService.getDbCosts(accountId, true).join();
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Cost refresh for {}", accountId, e);
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed cost refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 3:30 AM -> AWS Dashboard Snapshot Refresh
    // ===================================================================================
    @Scheduled(cron = "0 30 3 * * WED,SUN")
    public void refreshAwsDashboardData() {
        logger.info("--- [3:30 AM] Starting AWS Dashboard Snapshot Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_AWS_DASHBOARD", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    String accountId = account.getAwsAccountId();
                    if (accountId == null || "GCP".equals(account.getProvider()) || "Azure".equals(account.getProvider()))
                        continue;
                    try {
                        // Writes DASHBOARD_KPI, and (from the same fetch) DASHBOARD_COST and
                        // DASHBOARD_OPT — the keys the dashboard's cost and savings widgets read.
                        dashboardDataService.getGranularKpi(accountId, true, null);

                        // Inventory and region map are not part of the KPI fetch, so they need
                        // their own calls. Resource-listing APIs, not Cost Explorer.
                        // buildUnifiedSnapshots() at 5:10 AM reads DASHBOARD_INVENTORY too.
                        dashboardDataService.getGranularInventory(accountId, true, null);
                        dashboardDataService.getGranularMap(accountId, true, null);

                        count++;
                        logger.info("✅ Refreshed AWS Dashboard snapshots for account {}", accountId);
                    } catch (Exception e) {
                        logger.error("❌ Failed to refresh dashboard snapshot for account {}", accountId, e);
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed AWS dashboard refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 3:00 AM -> Security & Performance
    // ===================================================================================
    @Scheduled(cron = "0 0 3 * * WED,SUN")
    public void refreshSecurityAndPerformance() {
        logger.info("--- [3:00 AM] Starting Security & Performance Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_SEC_PERF", tenantId, LocalDateTime.now(),
                    "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    String accountId = getAccountId(account);
                    if (accountId == null)
                        continue;

                    try {
                        // 1. Security (Requires Account Object and Region List)
                        List<DashboardData.RegionStatus> regions = getActiveRegions(account);

                        if (cacheManager.getCache("securityFindings") != null) {
                            cacheManager.getCache("securityFindings").evict(accountId);
                        }

                        // ⭐ MODIFIED: Capture findings and save to database cache
                        List<DashboardData.SecurityFinding> findings = securityService
                                .getComprehensiveSecurityFindings(account, regions, true).join();

                        String securityKey = "security-" + accountId;
                        dashboardDataService.saveSecuritySnapshot(securityKey, findings);
                        logger.info("✓ Security cached for {} (database)", accountId);

                        // 2. Performance (Takes String ID)
                        if (cacheManager.getCache("performanceInsights") != null) {
                            cacheManager.getCache("performanceInsights").evict(accountId);
                        }
                        performanceService.getInsights(accountId, "ALL", true);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Security/Performance refresh for {}", accountId, e);
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed sec/perf refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 5:10 AM -> Build Unified Dashboard Snapshots (AWS + GCP + Azure) — Wed/Sun
    // Reads already-written per-account snapshots from DB — zero API calls, pure aggregation.
    // AWS:   reads DASHBOARD_KPI         (written 3:30 AM)
    // GCP:   reads GCP_DASHBOARD         (written 1:30 AM daily)
    // Azure: reads AZURE_DASHBOARD       (written 4:30 AM Wed/Sun)
    // ===================================================================================
    @Scheduled(cron = "0 10 5 * * WED,SUN")
    public void buildUnifiedSnapshots() {
        logger.info("--- [5:10 AM] Building Unified Dashboard Snapshots (AWS + GCP + Azure) ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("BUILD_UNIFIED_SNAPSHOTS", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                try {
                    dashboardDataService.buildAndSaveUnifiedAwsSnapshot();
                    logger.info("✅ Unified AWS snapshot built for tenant {}", tenantId);
                } catch (Exception e) {
                    logger.error("❌ Failed unified AWS snapshot for tenant {}: {}", tenantId, e.getMessage());
                }

                try {
                    dashboardDataService.buildAndSaveUnifiedGcpSnapshot();
                    logger.info("✅ Unified GCP snapshot built for tenant {}", tenantId);
                } catch (Exception e) {
                    logger.error("❌ Failed unified GCP snapshot for tenant {}: {}", tenantId, e.getMessage());
                }

                try {
                    dashboardDataService.buildAndSaveUnifiedAzureSnapshot();
                    logger.info("✅ Unified Azure snapshot built for tenant {}", tenantId);
                } catch (Exception e) {
                    logger.error("❌ Failed unified Azure snapshot for tenant {}: {}", tenantId, e.getMessage());
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed unified snapshots for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 4:00 AM Sunday -> Reservations (Can be slow)
    // OPTIMIZED: Weekly (Sundays only) — RI/SP data changes slowly, daily is wasteful
    // 8 CE calls/account/run × daily = 56/week → weekly = 8/week (saves 48 CE calls/week)
    // ===================================================================================
    @Scheduled(cron = "0 0 4 * * WED,SUN")
    public void refreshReservations() {
        logger.info("--- [4:00 AM] Starting Reservation Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_RESERVATIONS", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    String accountId = getAccountId(account);
                    if (accountId == null)
                        continue;

                    try {
                        List<DashboardData.RegionStatus> regions = getActiveRegions(account);

                        // 1. Reservation page data — fetch and save to DB snapshot
                        ReservationDto reservationData = reservationService
                                .getReservationPageData(accountId, true).join();
                        if (reservationData != null) {
                            dashboardDataService.saveReservationSnapshot("RESERVATION::" + accountId, reservationData);
                            logger.info("✓ Reservation cached for {} (DB snapshot)", accountId);
                        }

                        // 2. Reservation Recommendations (Redis only — data changes infrequently)
                        reservationService.getReservationPurchaseRecommendations(
                                account,
                                "ONE_YEAR",
                                "NO_UPFRONT",
                                "SEVEN_DAYS",
                                "STANDARD",
                                true).join();
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Reservation refresh for {}", accountId, e);
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed reservation refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 3:20 AM -> Alerts (Quota & Cost Anomalies)
    // ===================================================================================
    @Scheduled(cron = "0 20 3 * * WED,SUN")
    public void refreshAlerts() {
        logger.info("--- [3:20 AM] Starting Alerts Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_ALERTS", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    String accountId = getAccountId(account);
                    if (accountId == null)
                        continue;

                    try {
                        // Fetch alerts and cache them
                        var alertsFuture = cloudGuardService.getAlerts(accountId, true);
                        var alerts = alertsFuture.join();

                        // Save to database
                        String alertsKey = "alerts-" + accountId;
                        dashboardDataService.saveAlertsSnapshot(alertsKey, alerts);
                        int alertCount = alerts.getAlerts() != null ? alerts.getAlerts().size() : 0;
                        logger.info("✓ Alerts cached for {} (database) - {} alerts", accountId, alertCount);

                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Alerts refresh for {}", accountId, e);
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed alerts refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 4:30 AM -> Azure Dashboard Refresh (Daily)
    // Caches Azure dashboard data (cost, inventory, IAM) to database for fast access
    // ===================================================================================
    @Scheduled(cron = "0 30 4 * * WED,SUN")
    public void refreshAzureDashboardData() {
        logger.info("--- [4:30 AM] Starting Azure Dashboard Data Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_AZURE_DASHBOARD", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"Azure".equalsIgnoreCase(account.getProvider())) {
                        continue;
                    }

                    String subscriptionId = account.getAzureSubscriptionId();
                    if (subscriptionId == null || subscriptionId.isEmpty()) {
                        continue;
                    }

                    try {
                        azureDashboardService.refreshAndCacheDashboard(subscriptionId);
                        logger.info("✓ Azure Dashboard cached for subscription: {}", subscriptionId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Azure Dashboard refresh for subscription {}: {}", subscriptionId,
                                e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);
                logger.info("✅ Azure Dashboard refresh complete for tenant {} - {} subscriptions cached", tenantId,
                        count);

            } catch (Exception e) {
                logger.error("❌ Failed Azure dashboard refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 1:30 AM -> GCP Dashboard Refresh (Daily — every night)
    // Caches GCP dashboard data to database for fast access
    // ===================================================================================
    @Scheduled(cron = "0 30 1 * * *")
    public void refreshGcpDashboardData() {
        logger.info("--- [1:30 AM] Starting GCP Dashboard Data Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_GCP_DASHBOARD", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    // Only process GCP accounts
                    if (!"GCP".equalsIgnoreCase(account.getProvider())) {
                        continue;
                    }

                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) {
                        continue;
                    }

                    try {
                        // Force refresh GCP dashboard data and save to database
                        gcpDataService.refreshAndCacheGcpDashboard(gcpProjectId).join();
                        logger.info("✓ GCP Dashboard cached for project: {}", gcpProjectId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed GCP Dashboard refresh for project {}: {}", gcpProjectId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);
                logger.info("✅ GCP Dashboard refresh complete for tenant {} - {} projects cached", tenantId, count);

            } catch (Exception e) {
                logger.error("❌ Failed GCP dashboard refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 2:05 AM -> GCP FinOps Report Refresh (Daily — every night)
    // ===================================================================================
    @Scheduled(cron = "0 5 2 * * *")
    public void refreshGcpFinOpsData() {
        logger.info("--- [2:05 AM] Starting GCP FinOps Report Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_GCP_FINOPS", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"GCP".equalsIgnoreCase(account.getProvider())) continue;
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;
                    try {
                        gcpFinOpsReportService.refreshFinOpsReport(gcpProjectId).join();
                        logger.info("✓ GCP FinOps refreshed for project {}", gcpProjectId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed GCP FinOps refresh for {}: {}", gcpProjectId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed GCP FinOps refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 2:35 AM -> GCP Cost Data Refresh (Daily — every night)
    // ===================================================================================
    @Scheduled(cron = "0 35 2 * * *")
    public void refreshGcpCostData() {
        logger.info("--- [2:35 AM] Starting GCP Cost Data Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_GCP_COST", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"GCP".equalsIgnoreCase(account.getProvider())) continue;
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;
                    try {
                        gcpCostService.getUnfilteredMonthToDateSpendSync(gcpProjectId, true);
                        gcpCostService.getBillingSummarySync(gcpProjectId, true);
                        gcpCostService.getCostByRegionSync(gcpProjectId, true);
                        gcpCostService.getHistoricalCostsSync(gcpProjectId, true);
                        gcpCostService.getCostBreakdownSync(gcpProjectId, "SERVICE", true);
                        gcpCostService.getCostBreakdownSync(gcpProjectId, "REGION", true);
                        gcpCostService.getDailyCostsForForecastSync(gcpProjectId, null, 30, true);
                        logger.info("✓ GCP Cost refreshed for project {}", gcpProjectId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed GCP Cost refresh for {}: {}", gcpProjectId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed GCP Cost refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 3:05 AM -> GCP Security Refresh (Daily — every night)
    // ===================================================================================
    @Scheduled(cron = "0 5 3 * * *")
    public void refreshGcpSecurityData() {
        logger.info("--- [3:05 AM] Starting GCP Security Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_GCP_SECURITY", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"GCP".equalsIgnoreCase(account.getProvider())) continue;
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;
                    try {
                        // Gap E: trigger Prowler first so its findings are available when SCC merge runs
                        gcpSecurityService.triggerProwlerScan(gcpProjectId);

                        // Gap F: refreshSecurityFindings now guards against overwriting a good snapshot with empty
                        List<com.xammer.cloud.dto.gcp.GcpSecurityFinding> findings =
                                gcpSecurityService.refreshSecurityFindings(gcpProjectId).join();
                        logger.info("✓ GCP Security refreshed for project {} — {} findings", gcpProjectId, findings.size());

                        gcpSecurityService.getIamPolicyDrift(gcpProjectId, true).join();
                        gcpSecurityService.getContainerScanningResults(gcpProjectId, true).join();
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed GCP Security refresh for {}: {}", gcpProjectId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed GCP Security refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 3:08 AM -> GCP CloudList Refresh (Daily — every night, Gap 6)
    // ===================================================================================
    @Scheduled(cron = "0 8 3 * * *")
    public void refreshGcpCloudListData() {
        logger.info("--- [3:08 AM] Starting GCP CloudList Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_GCP_CLOUDLIST", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"GCP".equalsIgnoreCase(account.getProvider())) continue;
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;
                    try {
                        gcpDataService.getAllResources(gcpProjectId, true).join();
                        logger.info("✓ GCP CloudList refreshed for project {}", gcpProjectId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed GCP CloudList refresh for {}: {}", gcpProjectId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed GCP CloudList refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 3:10 AM -> GCP Performance Refresh (Daily — every night)
    // ===================================================================================
    @Scheduled(cron = "0 10 3 * * *")
    public void refreshGcpPerformanceData() {
        logger.info("--- [3:10 AM] Starting GCP Performance Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_GCP_PERFORMANCE", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"GCP".equalsIgnoreCase(account.getProvider())) continue;
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;
                    try {
                        gcpPerformanceInsightsService.getInsights(gcpProjectId, "ALL", true);
                        logger.info("✓ GCP Performance refreshed for project {}", gcpProjectId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed GCP Performance refresh for {}: {}", gcpProjectId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed GCP Performance refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 3:15 AM -> GCP Alerts Refresh (Daily — every night)
    // ===================================================================================
    @Scheduled(cron = "0 15 3 * * *")
    public void refreshGcpAlertsData() {
        logger.info("--- [3:15 AM] Starting GCP Alerts Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_GCP_ALERTS", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"GCP".equalsIgnoreCase(account.getProvider())) continue;
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;
                    try {
                        gcpCloudGuardService.getAlerts(gcpProjectId, true).join();
                        logger.info("✓ GCP Alerts refreshed for project {}", gcpProjectId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed GCP Alerts refresh for {}: {}", gcpProjectId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed GCP Alerts refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 3:35 AM -> GCP Waste Refresh (Daily — every night)
    // ===================================================================================
    @Scheduled(cron = "0 35 3 * * *")
    public void refreshGcpWasteData() {
        logger.info("--- [3:35 AM] Starting GCP Waste Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_GCP_WASTE", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"GCP".equalsIgnoreCase(account.getProvider())) continue;
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;
                    try {
                        gcpOptimizationService.getWasteReport(gcpProjectId, true);
                        logger.info("✓ GCP Waste refreshed for project {}", gcpProjectId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed GCP Waste refresh for {}: {}", gcpProjectId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed GCP Waste refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 3:40 AM -> GCP Rightsizing Refresh (Daily — every night)
    // ===================================================================================
    @Scheduled(cron = "0 40 3 * * *")
    public void refreshGcpRightsizingData() {
        logger.info("--- [3:40 AM] Starting GCP Rightsizing Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_GCP_RIGHTSIZING", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"GCP".equalsIgnoreCase(account.getProvider())) continue;
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;
                    try {
                        gcpOptimizationService.getRightsizingRecommendations(gcpProjectId, true);
                        logger.info("✓ GCP Rightsizing refreshed for project {}", gcpProjectId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed GCP Rightsizing refresh for {}: {}", gcpProjectId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed GCP Rightsizing refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 4:40 AM -> Azure Waste Refresh (Daily)
    // ===================================================================================
    @Scheduled(cron = "0 40 4 * * WED,SUN")
    public void refreshAzureWasteData() {
        logger.info("--- [4:40 AM] Starting Azure Waste Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_AZURE_WASTE", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"Azure".equalsIgnoreCase(account.getProvider())) continue;
                    String subscriptionId = account.getAzureSubscriptionId();
                    if (subscriptionId == null || subscriptionId.isEmpty()) continue;
                    try {
                        azureWasteService.getWasteItems(subscriptionId, true);
                        logger.info("✓ Azure Waste refreshed for subscription {}", subscriptionId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Azure Waste refresh for {}: {}", subscriptionId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed Azure Waste refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 4:45 AM -> Azure Security Refresh (Daily)
    // ===================================================================================
    @Scheduled(cron = "0 45 4 * * WED,SUN")
    public void refreshAzureSecurityData() {
        logger.info("--- [4:45 AM] Starting Azure Security Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_AZURE_SECURITY", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"Azure".equalsIgnoreCase(account.getProvider())) continue;
                    String subscriptionId = account.getAzureSubscriptionId();
                    if (subscriptionId == null || subscriptionId.isEmpty()) continue;
                    try {
                        azureSecurityService.getSecurityData(subscriptionId, true);
                        logger.info("✓ Azure Security refreshed for subscription {}", subscriptionId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Azure Security refresh for {}: {}", subscriptionId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed Azure Security refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 4:50 AM -> Azure Performance Refresh (Daily)
    // ===================================================================================
    @Scheduled(cron = "0 50 4 * * WED,SUN")
    public void refreshAzurePerformanceData() {
        logger.info("--- [4:50 AM] Starting Azure Performance Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_AZURE_PERFORMANCE", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"Azure".equalsIgnoreCase(account.getProvider())) continue;
                    String subscriptionId = account.getAzureSubscriptionId();
                    if (subscriptionId == null || subscriptionId.isEmpty()) continue;
                    try {
                        azurePerformanceService.getPerformanceInsights(subscriptionId, true);
                        logger.info("✓ Azure Performance refreshed for subscription {}", subscriptionId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Azure Performance refresh for {}: {}", subscriptionId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed Azure Performance refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 4:55 AM Mon/Wed/Fri -> Azure Rightsizing Refresh
    // ===================================================================================
    @Scheduled(cron = "0 55 4 * * WED,SUN")
    public void refreshAzureRightsizingData() {
        logger.info("--- [4:55 AM Mon/Wed/Fri] Starting Azure Rightsizing Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_AZURE_RIGHTSIZING", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"Azure".equalsIgnoreCase(account.getProvider())) continue;
                    String subscriptionId = account.getAzureSubscriptionId();
                    if (subscriptionId == null || subscriptionId.isEmpty()) continue;
                    try {
                        azureRightsizingService.getRecommendations(subscriptionId, true);
                        logger.info("✓ Azure Rightsizing refreshed for subscription {}", subscriptionId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Azure Rightsizing refresh for {}: {}", subscriptionId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed Azure Rightsizing refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 5:00 AM -> Azure Cloudlist Refresh (Daily)
    // ===================================================================================
    @Scheduled(cron = "0 0 5 * * WED,SUN")
    public void refreshAzureCloudListData() {
        logger.info("--- [5:00 AM] Starting Azure Cloudlist Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_AZURE_CLOUDLIST", tenantId,
                    LocalDateTime.now(), "RUNNING");
            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"Azure".equalsIgnoreCase(account.getProvider())) continue;
                    String subscriptionId = account.getAzureSubscriptionId();
                    if (subscriptionId == null || subscriptionId.isEmpty()) continue;
                    try {
                        azureCloudListService.getAzureResources(subscriptionId, true);
                        logger.info("✓ Azure Cloudlist refreshed for subscription {}", subscriptionId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Azure Cloudlist refresh for {}: {}", subscriptionId, e.getMessage());
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed Azure Cloudlist refresh for tenant {}", tenantId, e);
                if (jobHistory.getId() != null) {
                    jobHistory.setStatus("FAILURE");
                    jobHistory.setErrorMessage(e.getMessage());
                    jobHistory.setEndTime(LocalDateTime.now());
                    jobExecutionHistoryRepository.save(jobHistory);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // SpotAutomation Cache Refresh (Daily + Startup warmup)
    // Caches ASG list, cost data, and spot instances for the SpotAutomation page
    // ===================================================================================
    @Scheduled(cron = "0 0 2 * * WED,SUN")
    public void refreshSpotAutomation() {
        logger.info("--- [SpotAutomation] Starting SpotAutomation Cache Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            TenantContext.setCurrentTenant(tenantId);
            try {
                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    if (!"AWS".equals(account.getProvider()) || account.getAwsAccountId() == null) continue;
                    try {
                        // Force-refresh ASG list and cost data via the new overloads
                        autoSpottingService.listAsgsAllRegions(account.getId(), true);
                        autoSpottingService.getCostData(account.getId(), true);
                        autoSpottingService.getSpotInstances(account.getId(), true);
                        autoSpottingService.getRiSpCoverage(account.getId(), true);
                        logger.info("✓ SpotAutomation cached for account {} (cloudAccountId={})",
                                account.getAwsAccountId(), account.getId());
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed SpotAutomation refresh for account {}: {}",
                                account.getAwsAccountId(), e.getMessage());
                    }
                }
                logger.info("✅ SpotAutomation refresh complete for tenant {} - {} accounts cached", tenantId, count);
            } catch (Exception e) {
                logger.error("❌ Failed SpotAutomation refresh for tenant {}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // XamOpsRightsizing Cache Refresh (Daily + Startup warmup)
    // Caches XamOps-proprietary rightsizing recommendations for each AWS account
    // ===================================================================================
    @Scheduled(cron = "0 30 3 * * WED,SUN")
    public void refreshXamOpsRightsizing() {
        logger.info("--- [XamOpsRightsizing] Starting XamOpsRightsizing Cache Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            TenantContext.setCurrentTenant(tenantId);
            try {
                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    String accountId = account.getAwsAccountId();
                    if (!"AWS".equals(account.getProvider()) || accountId == null) continue;
                    try {
                        xamOpsRightsizingService.getLiveRecommendations(accountId, true);
                        logger.info("✓ XamOpsRightsizing cached for account {}", accountId);
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed XamOpsRightsizing refresh for account {}: {}",
                                accountId, e.getMessage());
                    }
                }
                logger.info("✅ XamOpsRightsizing refresh complete for tenant {} - {} accounts cached", tenantId, count);
            } catch (Exception e) {
                logger.error("❌ Failed XamOpsRightsizing refresh for tenant {}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ===================================================================================
    // 11:00 PM last day of month → capture final monthly cost snapshot before month closes
    // ===================================================================================

    /**
     * Runs at 11:00 PM on the last day of every month.
     * Captures the final cost numbers for the current calendar month and persists them
     * in cached_data with absolute month keys (e.g. AWS_MONTHLY_TOTAL::acct::2026-03).
     *
     * After this runs, CostService will serve closed-month data from these snapshots
     * without calling the CE API — critical for preserving pre-Billing-Conductor data.
     */
    @Scheduled(cron = "0 0 23 L * ?")
    public void snapshotEndOfMonthCost() {
        logger.info("--- [11 PM EOM] Starting end-of-month cost snapshot ---");
        if (!envConfig.isSchedulingEnabled()) {
            logger.info("⚠️ Scheduling disabled — skipping end-of-month snapshot");
            return;
        }

        JobExecutionHistory jobHistory = new JobExecutionHistory(
                "EOM_COST_SNAPSHOT", envConfig.getCurrentTenantId(), LocalDateTime.now(), "RUNNING");
        try {
            jobHistory = jobExecutionHistoryRepository.save(jobHistory);
            costDataSnapshotService.snapshotCurrentMonthFinal();
            gcpCostDataSnapshotService.snapshotCurrentMonthFinal();
            jobHistory.setStatus("SUCCESS");
            jobHistory.setEndTime(LocalDateTime.now());
            jobExecutionHistoryRepository.save(jobHistory);
            logger.info("✅ End-of-month cost snapshot complete");
        } catch (Exception e) {
            logger.error("❌ End-of-month cost snapshot failed: {}", e.getMessage(), e);
            if (jobHistory.getId() != null) {
                jobHistory.setStatus("FAILURE");
                jobHistory.setErrorMessage(e.getMessage());
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);
            }
        }
    }
}