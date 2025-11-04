package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.fasterxml.jackson.core.type.TypeReference; // <-- ADD THIS
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService; // <-- ADD THIS
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional; // <-- ADD THIS
import java.util.concurrent.CompletableFuture;

@Service
public class AzureDashboardService {

    private static final Logger log = LoggerFactory.getLogger(AzureDashboardService.class);
    private final AzureClientProvider clientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService redisCache; // <-- ADD THIS

    public AzureDashboardService(AzureClientProvider clientProvider, 
                                 CloudAccountRepository cloudAccountRepository,
                                 RedisCacheService redisCache) { // <-- ADD THIS
        this.clientProvider = clientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.redisCache = redisCache; // <-- ADD THIS
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
            // --- THIS METHOD IS NOW MODIFIED ---
            CompletableFuture<Void> costFuture = CompletableFuture.runAsync(() -> getAndSetCostHistoryAndBilling(dashboardData, accountId, force));
            // ---
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

    // --- THIS IS THE UPDATED METHOD ---
    private void getAndSetCostHistoryAndBilling(AzureDashboardData dashboardData, String accountId, boolean force) {
        
        String billingCacheKey = AzureBillingDataIngestionService.AZURE_BILLING_SUMMARY_CACHE_PREFIX + accountId;
        String historyCacheKey = AzureBillingDataIngestionService.AZURE_COST_HISTORY_CACHE_PREFIX + accountId;

        // Note: A "force" request can't be fulfilled here, as the data comes from a scheduled job.
        // We just log a warning and return cached data if it exists.
        if (force) {
            log.warn("Force refresh for Azure billing data is not supported via dashboard load. Data is refreshed on a 6-hour schedule. Returning cached data if available.");
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
    // --- END OF UPDATED METHOD ---

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

    private void getAndSetOptimizationSummary(AzureResourceManager azure, AzureDashboardData dashboardData, String accountId) {
        try {
            AzureDashboardData.OptimizationSummary summary = new AzureDashboardData.OptimizationSummary();
            summary.setTotalPotentialSavings(0.0);
            summary.setCriticalAlertsCount(0);
            dashboardData.setOptimizationSummary(summary);
        } catch (Exception e) {
            log.error("Error fetching optimization summary for account {}: {}", accountId, e.getMessage());
            dashboardData.setOptimizationSummary(new AzureDashboardData.OptimizationSummary());
        }
    }
}