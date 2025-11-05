package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
// <-- 1. ADD THIS IMPORT
import com.xammer.cloud.dto.DashboardData; 
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.azure.AzureBillingDataIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class AzureDashboardService {

    private static final Logger log = LoggerFactory.getLogger(AzureDashboardService.class);
    private final AzureClientProvider clientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService redisCache;
    private final AzureBillingDataIngestionService billingIngestionService; 

    public AzureDashboardService(AzureClientProvider clientProvider, 
                                 CloudAccountRepository cloudAccountRepository,
                                 RedisCacheService redisCache,
                                 AzureBillingDataIngestionService billingIngestionService) { 
        this.clientProvider = clientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.redisCache = redisCache;
        this.billingIngestionService = billingIngestionService; 
    }

    public AzureDashboardData getDashboardData(String accountId, boolean force) {
        log.info("Fetching dashboard data for Azure account ID: {}", accountId);
        try {
            // Use the Azure Subscription ID to find the account
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Azure account not found for Subscription ID: " + accountId));
            
            AzureResourceManager azure = clientProvider.getAzureClient(accountId);
            AzureDashboardData dashboardData = new AzureDashboardData();

            // Run data fetching in parallel
            CompletableFuture<Void> inventoryFuture = CompletableFuture.runAsync(() -> getAndSetResourceInventory(azure, dashboardData));
            CompletableFuture<Void> costFuture = CompletableFuture.runAsync(() -> getAndSetCostHistoryAndBilling(dashboardData, account, force));
            CompletableFuture<Void> regionFuture = CompletableFuture.runAsync(() -> getAndSetRegionStatus(azure, dashboardData));
            CompletableFuture<Void> optimizationFuture = CompletableFuture.runAsync(() -> getAndSetOptimizationSummary(azure, dashboardData, accountId));

            CompletableFuture.allOf(inventoryFuture, costFuture, regionFuture, optimizationFuture).join();

            // Ensure recommendations and lists are initialized to avoid frontend errors
            if (dashboardData.getVmRecommendations() == null) {
                dashboardData.setVmRecommendations(Collections.emptyList());
            }
            if (dashboardData.getDiskRecommendations() == null) {
                dashboardData.setDiskRecommendations(Collections.emptyList());
            }
            if (dashboardData.getFunctionRecommendations() == null) {
                dashboardData.setFunctionRecommendations(Collections.emptyList());
            }
            if (dashboardData.getBillingSummary() == null) {
                dashboardData.setBillingSummary(Collections.emptyList());
            }
            if (dashboardData.getRegionStatus() == null) {
                dashboardData.setRegionStatus(Collections.emptyList());
            }
            if (dashboardData.getCostAnomalies() == null) {
                dashboardData.setCostAnomalies(Collections.emptyList());
            }
            if (dashboardData.getCostHistory() == null) {
                dashboardData.setCostHistory(new AzureDashboardData.CostHistory());
            }
            if (dashboardData.getCostHistory().getLabels() == null) {
                dashboardData.getCostHistory().setLabels(Collections.emptyList());
            }
            if (dashboardData.getCostHistory().getCosts() == null) {
                dashboardData.getCostHistory().setCosts(Collections.emptyList());
            }
            if (dashboardData.getCostHistory().getAnomalies() == null) {
                dashboardData.getCostHistory().setAnomalies(Collections.emptyList());
            }

            return dashboardData;
        } catch (Exception e) {
            log.error("Failed to get dashboard data for account {}", accountId, e);
            throw new RuntimeException("Failed to get dashboard data", e);
        }
    }

    private void getAndSetResourceInventory(AzureResourceManager azure, AzureDashboardData dashboardData) {
        try {
            AzureDashboardData.ResourceInventory inventory = new AzureDashboardData.ResourceInventory();
            inventory.setVirtualMachines(azure.virtualMachines().list().stream().count());
            inventory.setStorageAccounts(azure.storageAccounts().list().stream().count());
            inventory.setSqlDatabases(azure.sqlServers().list().stream().mapToLong(s -> s.databases().list().stream().count()).sum());
            inventory.setVirtualNetworks(azure.networks().list().stream().count());
            inventory.setFunctions(azure.functionApps().list().stream().count());
            inventory.setDisks(azure.disks().list().stream().count());
            inventory.setDnsZones(azure.dnsZones().list().stream().count());
            inventory.setLoadBalancers(azure.loadBalancers().list().stream().count());
            inventory.setContainerInstances(azure.containerGroups().list().stream().count());
            inventory.setKubernetesServices(azure.kubernetesClusters().list().stream().count());
            inventory.setAppServices(azure.webApps().list().stream().count());
            inventory.setStaticWebApps(0); // Static Web Apps client is different, mock for now
            dashboardData.setResourceInventory(inventory);
        } catch (Exception e) {
            log.error("Error fetching resource inventory: {}", e.getMessage());
            dashboardData.setResourceInventory(new AzureDashboardData.ResourceInventory());
        }
    }

    private void getAndSetCostHistoryAndBilling(AzureDashboardData dashboardData, CloudAccount account, boolean force) {
        
        String accountId = account.getAzureSubscriptionId();

        String billingCacheKey = AzureBillingDataIngestionService.AZURE_BILLING_SUMMARY_CACHE_PREFIX + accountId;
        String historyCacheKey = AzureBillingDataIngestionService.AZURE_COST_HISTORY_CACHE_PREFIX + accountId;

        if (force) {
            log.info("Force refresh requested for Azure billing data for account {}. Evicting cache and triggering ingestion.", accountId);
            try {
                // 1. Evict cache
                redisCache.evict(billingCacheKey);
                redisCache.evict(historyCacheKey);
                log.info("Cache evicted for {}.", accountId);

                // 2. Trigger ingestion
                billingIngestionService.ingestDataForAccount(account);
                log.info("Force ingestion complete for {}.", accountId);
                
            } catch (Exception e) {
                log.error("Failed to perform force refresh for account {}. Will proceed with (possibly stale) cached data.", accountId, e);
            }
        }

        // 1. Get Billing Summary from Cache
        Optional<List<AzureDashboardData.BillingSummary>> billingSummary = 
            redisCache.get(billingCacheKey, new TypeReference<>() {});
        
        if (billingSummary.isPresent()) {
            dashboardData.setBillingSummary(billingSummary.get());
        } else {
            log.warn("No cached billing summary found for {}. Data will be missing until next ingestion.", accountId);
            dashboardData.setBillingSummary(Collections.emptyList()); // Ensure it's not null
        }

        // 2. Get Cost History from Cache
        Optional<AzureDashboardData.CostHistory> costHistory = 
            redisCache.get(historyCacheKey, AzureDashboardData.CostHistory.class);

        if (costHistory.isPresent()) {
            dashboardData.setCostHistory(costHistory.get());
        } else {
            log.warn("No cached cost history found for {}. Data will be missing until next ingestion.", accountId);
            dashboardData.setCostHistory(new AzureDashboardData.CostHistory()); // Ensure it's not null
        }
    }

    private void getAndSetRegionStatus(AzureResourceManager azure, AzureDashboardData dashboardData) {
        try {
            // Mock implementation
            List<AzureDashboardData.RegionStatus> statuses = new ArrayList<>();
            statuses.add(new AzureDashboardData.RegionStatus("eastus", 38.0, -78.0, "ACTIVE"));
            statuses.add(new AzureDashboardData.RegionStatus("westus", 37.0, -122.0, "SUSTAINABLE"));
            dashboardData.setRegionStatus(statuses);
        } catch (Exception e) {
            log.error("Error fetching region statuses: {}", e.getMessage());
            dashboardData.setRegionStatus(Collections.emptyList());
        }
    }

    // <-- 2. FIX: THIS METHOD IS UPDATED
    private void getAndSetOptimizationSummary(AzureResourceManager azure, AzureDashboardData dashboardData, String accountId) {
        try {
            // Use the common DashboardData.OptimizationSummary
            DashboardData.OptimizationSummary summary = new DashboardData.OptimizationSummary();
            summary.setTotalPotentialSavings(0.0);
            summary.setCriticalAlertsCount(0); // Use the correct setter
            dashboardData.setOptimizationSummary(summary);
        } catch (Exception e) {
            log.error("Error fetching optimization summary for account {}: {}", accountId, e.getMessage());
            // Use the common DashboardData.OptimizationSummary
            dashboardData.setOptimizationSummary(new DashboardData.OptimizationSummary());
        }
    }
}