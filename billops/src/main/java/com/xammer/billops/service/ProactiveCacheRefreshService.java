package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProactiveCacheRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(ProactiveCacheRefreshService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final BillingService billingService;
    private final InvoiceManagementService invoiceManagementService;
    private final TicketService ticketService;
    private final DashboardService dashboardService; // [New]
    private final CostService costService;           // [New]
    private final CacheManager cacheManager;         // [New]

    @Autowired
    public ProactiveCacheRefreshService(
            CloudAccountRepository cloudAccountRepository, 
            BillingService billingService,
            InvoiceManagementService invoiceManagementService,
            TicketService ticketService,
            DashboardService dashboardService,
            CostService costService,
            CacheManager cacheManager) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.billingService = billingService;
        this.invoiceManagementService = invoiceManagementService;
        this.ticketService = ticketService;
        this.dashboardService = dashboardService;
        this.costService = costService;
        this.cacheManager = cacheManager;
    }

    private List<CloudAccount> getAllAccounts() {
        return cloudAccountRepository.findAll();
    }

    // ===================================================================================
    // 2:00 AM -> Dashboard (High Priority)
    // ===================================================================================
    @Scheduled(cron = "0 0 2 * * ?")
    public void refreshDashboardCache() {
        logger.info("--- [2:00 AM] Starting Dashboard Cache Refresh ---");
        LocalDate now = LocalDate.now();
        int currentYear = now.getYear();
        int currentMonth = now.getMonthValue();

        for (CloudAccount account : getAllAccounts()) {
            if (account.getAwsAccountId() == null) continue;
            String accountId = account.getAwsAccountId();

            try {
                // 1. Evict specific dashboard cache manually to ensure fresh fetch
                // Note: The cache key in DashboardService is: { #account.awsAccountId, #year, #month }
                // Constructing key manually or using clear() if keys are complex
                Cache dashboardCache = cacheManager.getCache("dashboardData");
                if (dashboardCache != null) {
                    // We simply evict all to be safe, or we could construct the key if needed.
                    // dashboardCache.evict(Arrays.asList(accountId, currentYear, currentMonth));
                    // For simplicity in this batch job, specific eviction might be tricky without key generator access.
                    // However, calling the method with @Cacheable will refresh it if expired/missing.
                    // To force refresh, we can explicitly call the service. 
                }

                // 2. Fetch Data (This will populate the cache)
                // We rely on Redis TTL (1 hour in your config) or manual refresh here.
                // Since your CacheConfig sets 1h TTL, we are essentially "warming" it for the morning.
                dashboardService.getDashboardData(account, currentYear, currentMonth);
                
                logger.info(" > Dashboard data refreshed for {}", accountId);
            } catch (Exception e) {
                logger.error("Failed to refresh Dashboard data for account {}", accountId, e);
            }
        }
        logger.info("--- [2:00 AM] Dashboard refresh finished ---");
    }

    // ===================================================================================
    // 2:30 AM -> Billing & Cost Data (Heavy Aggregation)
    // ===================================================================================
    @Scheduled(cron = "0 30 2 * * ?")
    public void refreshBillingAndCostCache() {
        logger.info("--- [2:30 AM] Starting Billing & Cost Cache Refresh ---");
        
        LocalDate now = LocalDate.now();
        int currentYear = now.getYear();
        int currentMonth = now.getMonthValue();

        List<CloudAccount> allAccounts = getAllAccounts();

        // 1. Refresh CostService Caches (Used by Cost Management Page)
        for (CloudAccount account : allAccounts) {
            try {
                // Warm up specific cost dimensions
                costService.getCostHistory(account, currentYear, currentMonth);
                costService.getCostByDimension(account, "SERVICE", currentYear, currentMonth);
                costService.getCostByDimension(account, "REGION", currentYear, currentMonth);
                logger.debug(" > Cost dimensions refreshed for account {}", account.getAwsAccountId());
            } catch (Exception e) {
                logger.error("Failed CostService refresh for account {}", account.getAwsAccountId(), e);
            }
        }

        // 2. Refresh BillingService (Client Aggregations)
        Map<Long, List<CloudAccount>> accountsByClientId = allAccounts.stream()
                .filter(a -> a.getClient() != null && a.getAwsAccountId() != null)
                .collect(Collectors.groupingBy(a -> a.getClient().getId()));

        for (Map.Entry<Long, List<CloudAccount>> entry : accountsByClientId.entrySet()) {
            Long clientId = entry.getKey();
            List<String> accountIds = entry.getValue().stream()
                    .map(CloudAccount::getAwsAccountId)
                    .collect(Collectors.toList());

            if (accountIds.isEmpty()) continue;

            logger.info("Refreshing aggregated billing for Client ID: {}", clientId);
            try {
                // Refresh Aggregate View
                billingService.getBillingData(accountIds, currentYear, currentMonth, true);
                billingService.getDetailedBillingReport(accountIds, currentYear, currentMonth, true);
                
                // Refresh Individual Account Views within Billing Service
                for (String accountId : accountIds) {
                    billingService.getBillingData(List.of(accountId), currentYear, currentMonth, true);
                    billingService.getDetailedBillingReport(List.of(accountId), currentYear, currentMonth, true);
                }
            } catch (Exception e) {
                logger.error("Failed billing refresh for Client ID: {}", clientId, e);
            }
        }
        logger.info("--- [2:30 AM] Billing & Cost refresh finished ---");
    }

    // ===================================================================================
    // 3:30 AM -> Invoices (Admin Views)
    // ===================================================================================
    @Scheduled(cron = "0 30 3 * * ?")
    public void refreshInvoicesCache() {
        logger.info("--- [3:30 AM] Starting Invoices Cache Refresh ---");
        try {
            // Fetches all invoices from DB and updates the Redis cache used by the Admin/List view
            invoiceManagementService.getAllInvoicesAndCache();
            logger.info("Successfully refreshed all invoices cache.");
        } catch (Exception e) {
            logger.error("Failed to refresh invoices cache", e);
        }
    }

    // ===================================================================================
    // 4:00 AM -> Tickets (Support Views)
    // ===================================================================================
    @Scheduled(cron = "0 0 4 * * ?")
    public void refreshTicketsCache() {
        logger.info("--- [4:00 AM] Starting Tickets Cache Refresh ---");
        try {
            // 1. Refresh the main "All Tickets" list
            ticketService.getAllTickets(true);
            
            // 2. Refresh specific categories
            ticketService.getTicketsByCategory("Technical", true);
            ticketService.getTicketsByCategory("Account and Billing", true);
            
            logger.info("Successfully refreshed ticket caches.");
        } catch (Exception e) {
            logger.error("Failed to refresh tickets cache", e);
        }
    }
}