package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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
            OptimizationService optimizationService) {
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
            // We use false for forceRefresh here to avoid re-scanning regions just for the list
            return cloudListService.getRegionStatusForAccount(account, false).join();
        } catch (Exception e) {
            logger.warn("Could not fetch active regions for account {}. Defaulting to empty list.", getAccountId(account));
            return List.of();
        }
    }

    // ===================================================================================
    // 2:00 AM -> Dashboard & FinOps (High Priority)
    // ===================================================================================
    @Scheduled(cron = "0 0 2 * * ?") 
    public void refreshDashboardAndFinOps() {
        logger.info("--- [2:00 AM] Starting Dashboard & FinOps Refresh ---");
        for (CloudAccount account : getAllAccounts()) {
            String accountId = getAccountId(account);
            if (accountId == null) continue;

            try {
                // 1. Dashboard
                if (cacheManager.getCache("dashboardData") != null) {
                    cacheManager.getCache("dashboardData").evict(accountId);
                }
                dashboardDataService.getDashboardData(accountId, true, null);
                
                // 2. FinOps Report
                if (cacheManager.getCache("finOpsReport") != null) {
                    cacheManager.getCache("finOpsReport").evict(accountId);
                }
                finOpsService.getFinOpsReport(accountId, true).join();
                
                logger.info(" > Dashboard & FinOps refreshed for {}", accountId);
            } catch (Exception e) {
                logger.error("Failed Dashboard/FinOps refresh for {}", accountId, e);
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

        for (CloudAccount account : getAllAccounts()) {
            String accountId = getAccountId(account);
            if (accountId == null) continue;

            try {
                // Refresh generic cost breakdown (SERVICE level)
                costService.getCostBreakdown(accountId, "SERVICE", null, true, startDate, endDate).join();
                logger.info(" > Cost data refreshed for {}", accountId);
            } catch (Exception e) {
                logger.error("Failed Cost refresh for {}", accountId, e);
            }
        }
    }

    // ===================================================================================
    // 3:00 AM -> Security & Performance
    // ===================================================================================
    @Scheduled(cron = "0 0 3 * * ?") 
    public void refreshSecurityAndPerformance() {
        logger.info("--- [3:00 AM] Starting Security & Performance Refresh ---");
        for (CloudAccount account : getAllAccounts()) {
            String accountId = getAccountId(account);
            if (accountId == null) continue;

            try {
                // 1. Security (Requires Account Object and Region List)
                List<DashboardData.RegionStatus> regions = getActiveRegions(account);
                
                if (cacheManager.getCache("securityFindings") != null) {
                    cacheManager.getCache("securityFindings").evict(accountId); // Assuming simple key, actual key in service is complex
                }
                // Correct Method: getComprehensiveSecurityFindings(CloudAccount, List<RegionStatus>, boolean)
                securityService.getComprehensiveSecurityFindings(account, regions, true).join(); 
                
                // 2. Performance (Takes String ID)
                if (cacheManager.getCache("performanceInsights") != null) {
                    cacheManager.getCache("performanceInsights").evict(accountId);
                }
                // Correct Method: getInsights(String accountId, String severity, boolean forceRefresh)
                performanceService.getInsights(accountId, "ALL", true);

                logger.info(" > Security & Performance refreshed for {}", accountId);
            } catch (Exception e) {
                logger.error("Failed Security/Performance refresh for {}", accountId, e);
            }
        }
    }

    // ===================================================================================
    // 3:30 AM -> Optimization (Waste & Rightsizing)
    // ===================================================================================
    @Scheduled(cron = "0 30 3 * * ?") 
    public void refreshOptimization() {
        logger.info("--- [3:30 AM] Starting Optimization Refresh (Waste & Rightsizing) ---");
        for (CloudAccount account : getAllAccounts()) {
            String accountId = getAccountId(account);
            if (accountId == null) continue;

            try {
                List<DashboardData.RegionStatus> regions = getActiveRegions(account);

                // 1. Rightsizing (OptimizationService)
                // Correct Method: getAllOptimizationRecommendations(String accountId, boolean forceRefresh)
                optimizationService.getAllOptimizationRecommendations(accountId, true).join();

                // 2. Waste (OptimizationService)
                // Correct Method: getWastedResources(CloudAccount, List<RegionStatus>, boolean)
                optimizationService.getWastedResources(account, regions, true).join();

                logger.info(" > Optimization (Waste/Rightsizing) refreshed for {}", accountId);
            } catch (Exception e) {
                logger.error("Failed Optimization refresh for {}", accountId, e);
            }
        }
    }

    // ===================================================================================
    // 4:00 AM -> Reservations (Can be slow)
    // ===================================================================================
    @Scheduled(cron = "0 0 4 * * ?") 
    public void refreshReservations() {
        logger.info("--- [4:00 AM] Starting Reservation Refresh ---");
        for (CloudAccount account : getAllAccounts()) {
            String accountId = getAccountId(account);
            if (accountId == null) continue;

            try {
                List<DashboardData.RegionStatus> regions = getActiveRegions(account);

                // 1. Reservation Inventory
                // Correct Method: getReservationInventory(CloudAccount, List<RegionStatus>, boolean)
                reservationService.getReservationInventory(account, regions, true).join();

                // 2. Reservation Recommendations
                // Correct Method: getReservationPurchaseRecommendations(...)
                // Using standard defaults: 1 Year, No Upfront, 7 Days lookback, Standard Class
                reservationService.getReservationPurchaseRecommendations(
                    account, 
                    "ONE_YEAR", 
                    "NO_UPFRONT", 
                    "SEVEN_DAYS", // Ensure this matches LookbackPeriodInDays enum or valid string
                    "STANDARD", 
                    true
                ).join();

                logger.info(" > Reservations refreshed for {}", accountId);
            } catch (Exception e) {
                logger.error("Failed Reservation refresh for {}", accountId, e);
            }
        }
    }
}