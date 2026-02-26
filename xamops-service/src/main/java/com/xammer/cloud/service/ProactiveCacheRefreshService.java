package com.xammer.cloud.service;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.JobExecutionHistory;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.ReservationDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.JobExecutionHistoryRepository;
import com.xammer.cloud.service.azure.AzureDashboardService;
import com.xammer.cloud.service.gcp.GcpDataService;
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

    private final JobExecutionHistoryRepository jobExecutionHistoryRepository;
    private final JdbcTemplate masterJdbcTemplate;

    // ADDED: Environment-aware configuration
    private final com.xammer.cloud.config.SchedulingEnvironmentConfig envConfig;

    // ADDED: SpotAutomation and XamOpsRightsizing caching
    private final AutoSpottingService autoSpottingService;
    private final XamOpsRightsizingService xamOpsRightsizingService;

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
            JobExecutionHistoryRepository jobExecutionHistoryRepository,
            @Qualifier("masterDataSource") DataSource masterDataSource,
            com.xammer.cloud.config.SchedulingEnvironmentConfig envConfig,
            AutoSpottingService autoSpottingService,
            XamOpsRightsizingService xamOpsRightsizingService) {
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
        this.jobExecutionHistoryRepository = jobExecutionHistoryRepository;
        this.masterJdbcTemplate = new JdbcTemplate(masterDataSource);
        this.envConfig = envConfig;
        this.autoSpottingService = autoSpottingService;
        this.xamOpsRightsizingService = xamOpsRightsizingService;

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
        logger.info("[CacheWarmUp] Application ready — seeding Redis from DB snapshots (no AWS calls at startup)...");
        // AWS pages: read from DB snapshot → warm Redis. No live AWS calls.
        CompletableFuture.runAsync(() -> {
            try { seedRedisFromDb(); } catch (Exception e) { logger.warn("[CacheWarmUp] DB seed failed: {}", e.getMessage()); }
        });
        // Azure and GCP: their refresh methods write to DB first, then serve from DB — keep as-is
        CompletableFuture.runAsync(() -> {
            try { refreshAzureDashboardData(); } catch (Exception e) { logger.warn("[CacheWarmUp] Azure failed: {}", e.getMessage()); }
        });
        CompletableFuture.runAsync(() -> {
            try { refreshGcpDashboardData(); } catch (Exception e) { logger.warn("[CacheWarmUp] GCP failed: {}", e.getMessage()); }
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
                }
            } finally {
                TenantContext.clear();
            }
        }
        logger.info("[CacheWarmUp] DB → Redis seed complete. All AWS pages will serve cached data.");
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
            if ("local".equals(activeProfile) || "uat".equals(activeProfile)) {
                String currentTenant = envConfig.getCurrentTenantId();
                logger.info("✅ Using configured tenant '{}' for {} environment (no tenant_config query)",
                        currentTenant, activeProfile);
                return Collections.singletonList(currentTenant);
            }

            // In prod, query tenant_config from master DB - GET ALL ACTIVE TENANTS
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
    // @Scheduled(cron = "0 0 2 * * ?")
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

    @Scheduled(cron = "0 0 2 * * ?")
    public void refreshFinOps() {
        logger.info("--- [2:00 AM] Starting FinOps Refresh ---");
        String startDate = LocalDate.now().withDayOfMonth(1).toString();
        String endDate = LocalDate.now().toString();
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_FINOPS", tenantId,
                    LocalDateTime.now(), "RUNNING");

            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);
                logger.info(">> Processing Tenant: {}", tenantId);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    String accountId = getAccountId(account);
                    if (accountId == null || !"AWS".equals(account.getProvider()))
                        continue;

                    try {
                        FinOpsReportDto report = finOpsService
                                .getFinOpsReport(accountId, true, startDate, endDate).join();
                        if (report != null) {
                            String key = "finops-" + accountId;
                            dashboardDataService.saveFinOpsSnapshot(key, report);
                            logger.info("✓ FinOps cached for {} (DB snapshot)", accountId);
                        }
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed FinOps refresh for {}", accountId, e);
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed FinOps refresh for tenant {}", tenantId, e);
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
    // 2:30 AM -> Cost Management (Data Intensive)
    // ===================================================================================
    @Scheduled(cron = "0 30 2 * * ?")
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
                        costService.getCostBreakdown(accountId, "SERVICE", null, true, startDate, endDate).join();
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
    // 3:00 AM -> Security & Performance
    // ===================================================================================
    @Scheduled(cron = "0 0 3 * * ?")
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
    // 3:30 AM -> Optimization (Waste & Rightsizing)
    // OPTIMIZED: Changed from daily to Mon/Wed/Fri to reduce costs
    // ===================================================================================
    @Scheduled(cron = "0 30 3 * * MON,WED,FRI")
    public void refreshOptimization() {
        logger.info("--- [3:30 AM] Starting Optimization Refresh (Waste & Rightsizing) ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_OPTIMIZATION", tenantId,
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

                        // 1. Rightsizing
                        optimizationService.getAllOptimizationRecommendations(accountId, true).join();

                        // 2. Waste
                        List<DashboardData.WastedResource> wasteData = optimizationService
                                .getWastedResources(account, regions, true).join();

                        String wasteKey = "waste-" + accountId;
                        dashboardDataService.saveWasteSnapshot(wasteKey, wasteData);
                        logger.info("✓ Waste cached for {} (database)", accountId);

                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Optimization refresh for {}", accountId, e);
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed optimization refresh for tenant {}", tenantId, e);
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
    // 4:00 AM -> Reservations (Can be slow)
    // OPTIMIZED: Changed from daily to weekly (Sundays) to reduce costs
    // ===================================================================================
    @Scheduled(cron = "0 0 4 * * ?")
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
    // 3:00 AM -> Alerts (Quota & Cost Anomalies)
    // SCHEDULED: Tuesday, Thursday, Saturday
    // ===================================================================================
    @Scheduled(cron = "0 0 3 * * TUE,THU,SAT")
    public void refreshAlerts() {
        logger.info("--- [3:00 AM] Starting Alerts Refresh (Tue/Thu/Sat) ---");
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
    @Scheduled(cron = "0 30 4 * * ?")
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
    // 1:30 AM -> GCP Dashboard Refresh (Daily)
    // Caches GCP dashboard data to database for fast access
    // ===================================================================================
    @Scheduled(cron = "0 30 1 * * ?")
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
    // SpotAutomation Cache Refresh (Daily + Startup warmup)
    // Caches ASG list, cost data, and spot instances for the SpotAutomation page
    // ===================================================================================
    @Scheduled(cron = "0 0 2 * * ?")
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
    @Scheduled(cron = "0 30 3 * * MON,WED,FRI")
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
}