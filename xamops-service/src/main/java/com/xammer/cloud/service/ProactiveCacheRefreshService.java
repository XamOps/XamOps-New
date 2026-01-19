package com.xammer.cloud.service;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.JobExecutionHistory;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.JobExecutionHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

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

    private final JobExecutionHistoryRepository jobExecutionHistoryRepository;
    private final JdbcTemplate masterJdbcTemplate;

    // ADDED: Environment-aware configuration
    private final com.xammer.cloud.config.SchedulingEnvironmentConfig envConfig;

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
            JobExecutionHistoryRepository jobExecutionHistoryRepository,
            @Qualifier("masterDataSource") DataSource masterDataSource,
            com.xammer.cloud.config.SchedulingEnvironmentConfig envConfig) { // Added arg
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
        this.jobExecutionHistoryRepository = jobExecutionHistoryRepository;
        this.masterJdbcTemplate = new JdbcTemplate(masterDataSource);
        this.envConfig = envConfig;

        // Log configuration on startup
        envConfig.logConfiguration();
    }

    /**
     * Helper to fetch active tenants from Master DB - ENVIRONMENT AWARE
     * Only returns the tenant associated with the current environment:
     * - local -> customer1
     * - uat -> customer2
     * - prod -> customer3
     */
    private List<String> fetchActiveTenants() {
        try {
            if (!envConfig.isSchedulingEnabled()) {
                logger.info("⚠️ Scheduling is disabled in this environment");
                return Collections.emptyList();
            }

            String currentTenant = envConfig.getCurrentTenantId();
            logger.info("🔒 Environment filter active: Only processing tenant '{}' (environment: {})",
                    currentTenant, envConfig.getActiveProfile());

            // Only return the current environment's tenant
            List<String> tenants = masterJdbcTemplate.queryForList(
                    "SELECT tenant_id FROM tenant_config WHERE active = true AND tenant_id = ?",
                    String.class,
                    currentTenant);

            if (tenants.isEmpty()) {
                logger.warn("⚠️ No active tenant found for tenant_id: {}", currentTenant);
            } else {
                logger.info("✅ Found tenant '{}' for processing", currentTenant);
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

    // @Scheduled(cron = "0 0 2 * * ?")
    public void refreshDashboardAndFinOps_DISABLED() {
        logger.info("--- [2:00 AM] Starting Dashboard & FinOps Refresh ---");
        List<String> tenants = fetchActiveTenants();

        for (String tenantId : tenants) {
            JobExecutionHistory jobHistory = new JobExecutionHistory("REFRESH_DASHBOARD_FINOPS", tenantId,
                    LocalDateTime.now(), "RUNNING");

            try {
                TenantContext.setCurrentTenant(tenantId);
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);
                logger.info(">> Processing Tenant: {}", tenantId);

                int count = 0;
                for (CloudAccount account : getAllAccounts()) {
                    String accountId = getAccountId(account);
                    if (accountId == null)
                        continue;

                    try {
                        // 1. Dashboard (Now uses DB cache - forceRefresh=true will update DB
                        // automatically)
                        dashboardDataService.getDashboardData(accountId, true, null);

                        // 2. FinOps Report
                        if (cacheManager.getCache("finOpsReport") != null) {
                            cacheManager.getCache("finOpsReport").evict(accountId);
                        }
                        finOpsService.getFinOpsReport(accountId, true).join();
                        count++;
                    } catch (Exception e) {
                        logger.error("Failed Dashboard/FinOps refresh for {}", accountId, e);
                    }
                }

                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(count);
                jobHistory.setEndTime(LocalDateTime.now());
                jobExecutionHistoryRepository.save(jobHistory);

            } catch (Exception e) {
                logger.error("❌ Failed refresh for tenant {}", tenantId, e);
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
    @Scheduled(cron = "0 0 4 * * SUN")
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

                        // 1. Reservation Inventory
                        reservationService.getReservationInventory(account, regions, true).join();

                        // 2. Reservation Recommendations
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
}
