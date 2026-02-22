package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.azure.AzureBillingDataIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

@Service
public class AzureDashboardService {

    private static final Logger log = LoggerFactory.getLogger(AzureDashboardService.class);

    // Database cache key prefix (same pattern as AWS)
    private static final String AZURE_DASHBOARD_DB_CACHE_PREFIX = "AZURE_DASHBOARD::";

    private final AzureClientProvider clientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final CachedDataRepository cachedDataRepository;
    private final RedisCacheService redisCache;
    private final AzureBillingDataIngestionService billingIngestionService;
    private final ObjectMapper objectMapper;

    public AzureDashboardService(AzureClientProvider clientProvider,
            CloudAccountRepository cloudAccountRepository,
            CachedDataRepository cachedDataRepository,
            RedisCacheService redisCache,
            AzureBillingDataIngestionService billingIngestionService,
            ObjectMapper objectMapper) {
        this.clientProvider = clientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cachedDataRepository = cachedDataRepository;
        this.redisCache = redisCache;
        this.billingIngestionService = billingIngestionService;
        this.objectMapper = objectMapper;
    }

    public AzureDashboardData getDashboardData(String accountId, boolean force) {
        log.info("Fetching dashboard data for Azure account ID: {}", accountId);
        String dbCacheKey = AZURE_DASHBOARD_DB_CACHE_PREFIX + accountId;

        // ALWAYS return from database cache for normal requests (force=false)
        // Live data is ONLY fetched during nightly scheduled jobs (force=true)
        if (!force) {
            AzureDashboardData dbCached = getDashboardFromDatabase(dbCacheKey);
            if (dbCached != null) {
                log.info("✅ Returning cached Azure dashboard data from DATABASE for subscription: {}", accountId);
                return dbCached;
            }

            // If no cache exists, return empty data (user needs to wait for nightly
            // refresh)
            log.warn(
                    "⚠️ No cached Azure dashboard data found for subscription: {}. Data will be available after nightly refresh.",
                    accountId);
            AzureDashboardData emptyData = createEmptyAzureDashboardData();
            emptyData.setLastUpdated("No data cached yet - will be available after nightly refresh");
            return emptyData;
        }

        // force=true - ONLY used by scheduled nightly job
        log.info("🔍 Fetching LIVE Azure dashboard data for subscription {} (scheduled refresh)", accountId);
        try {
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(accountId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Azure account not found for Subscription ID: " + accountId));

            AzureResourceManager azure = clientProvider.getAzureClient(accountId);
            AzureDashboardData dashboardData = new AzureDashboardData();

            // Run data fetching in parallel
            CompletableFuture<Void> inventoryFuture = CompletableFuture
                    .runAsync(() -> getAndSetResourceInventory(azure, dashboardData));
            CompletableFuture<Void> costFuture = CompletableFuture
                    .runAsync(() -> getAndSetCostHistoryAndBilling(dashboardData, account, force));
            CompletableFuture<Void> regionFuture = CompletableFuture
                    .runAsync(() -> getAndSetRegionStatus(azure, dashboardData));
            CompletableFuture<Void> optimizationFuture = CompletableFuture
                    .runAsync(() -> getAndSetOptimizationSummary(azure, dashboardData, accountId));
            CompletableFuture<Void> iamFuture = CompletableFuture
                    .runAsync(() -> getAndSetIamDetails(azure, dashboardData, accountId));

            CompletableFuture.allOf(inventoryFuture, costFuture, regionFuture, optimizationFuture, iamFuture).join();

            // Ensure all lists are initialized
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

            // ✅ Save to DATABASE (persistent - same as AWS pattern)
            saveDashboardToDatabase(dbCacheKey, dashboardData);

            return dashboardData;
        } catch (Exception e) {
            log.error("Failed to get dashboard data for account {}", accountId, e);
            throw new RuntimeException("Failed to get dashboard data", e);
        }
    }

    // ============================================================================================
    // DATABASE CACHING METHODS (Same pattern as AWS DashboardDataService)
    // ============================================================================================

    /**
     * Save Azure Dashboard data to database cache (persistent storage)
     * Same pattern as AWS: DashboardDataService.saveSnapshot()
     */
    public void saveDashboardToDatabase(String cacheKey, AzureDashboardData data) {
        try {
            CachedData cachedData = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
            cachedData.setCacheKey(cacheKey);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(cachedData);
            log.info("✅ Saved Azure dashboard snapshot to DATABASE: {}", cacheKey);
        } catch (Exception e) {
            log.error("❌ Failed to save Azure dashboard snapshot to database: {}", cacheKey, e);
        }
    }

    /**
     * Get Azure Dashboard data from database cache
     * Same pattern as AWS: DashboardDataService.getSnapshot()
     */
    public AzureDashboardData getDashboardFromDatabase(String cacheKey) {
        try {
            Optional<CachedData> cached = cachedDataRepository.findById(cacheKey);
            if (cached.isPresent()) {
                AzureDashboardData data = objectMapper.readValue(cached.get().getJsonData(), AzureDashboardData.class);
                // Add last updated timestamp to data
                if (cached.get().getLastUpdated() != null) {
                    data.setLastUpdated(cached.get().getLastUpdated()
                            .format(DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
                }
                return data;
            }
        } catch (Exception e) {
            log.error("❌ Failed to deserialize Azure dashboard from database: {}", cacheKey, e);
        }
        return null;
    }

    /**
     * Force refresh Azure dashboard data and save to database
     * Called by scheduled jobs (same as AWS ProactiveCacheRefreshService)
     */
    public AzureDashboardData refreshAndCacheDashboard(String subscriptionId) {
        log.info("🔄 Force refreshing Azure dashboard for subscription: {}", subscriptionId);
        try {
            // Force refresh data
            AzureDashboardData data = getDashboardData(subscriptionId, true);
            // Save to database
            String dbCacheKey = AZURE_DASHBOARD_DB_CACHE_PREFIX + subscriptionId;
            saveDashboardToDatabase(dbCacheKey, data);
            log.info("✅ Azure dashboard refreshed and cached for subscription: {}", subscriptionId);
            return data;
        } catch (Exception e) {
            log.error("❌ Failed to refresh Azure dashboard for subscription: {}", subscriptionId, e);
            return null;
        }
    }

    /**
     * Create empty Azure dashboard data when no cache exists
     * User will see this until the nightly job populates the cache
     */
    private AzureDashboardData createEmptyAzureDashboardData() {
        AzureDashboardData data = new AzureDashboardData();
        data.setResourceInventory(new AzureDashboardData.ResourceInventory());
        data.setBillingSummary(Collections.emptyList());
        AzureDashboardData.CostHistory costHistory = new AzureDashboardData.CostHistory();
        costHistory.setLabels(Collections.emptyList());
        costHistory.setCosts(Collections.emptyList());
        costHistory.setAnomalies(Collections.emptyList());
        data.setCostHistory(costHistory);
        data.setOptimizationSummary(new DashboardData.OptimizationSummary());
        data.setRegionStatus(Collections.emptyList());
        data.setCostAnomalies(Collections.emptyList());
        data.setMonthToDateSpend(0.0);
        data.setForecastedSpend(0.0);
        data.setLastMonthSpend(0.0);
        data.setVmRecommendations(Collections.emptyList());
        data.setDiskRecommendations(Collections.emptyList());
        data.setFunctionRecommendations(Collections.emptyList());
        data.setIamDetails(new DashboardData.IamDetail(Collections.emptyList(), Collections.emptyList()));
        return data;
    }

    private void getAndSetResourceInventory(AzureResourceManager azure, AzureDashboardData dashboardData) {
        try {
            AzureDashboardData.ResourceInventory inventory = new AzureDashboardData.ResourceInventory();
            inventory.setVirtualMachines(azure.virtualMachines().list().stream().count());
            inventory.setStorageAccounts(azure.storageAccounts().list().stream().count());
            inventory.setSqlDatabases(
                    azure.sqlServers().list().stream().mapToLong(s -> s.databases().list().stream().count()).sum());
            inventory.setVirtualNetworks(azure.networks().list().stream().count());
            inventory.setFunctions(azure.functionApps().list().stream().count());
            inventory.setDisks(azure.disks().list().stream().count());
            inventory.setDnsZones(azure.dnsZones().list().stream().count());
            inventory.setLoadBalancers(azure.loadBalancers().list().stream().count());
            inventory.setContainerInstances(azure.containerGroups().list().stream().count());
            inventory.setKubernetesServices(azure.kubernetesClusters().list().stream().count());
            inventory.setAppServices(azure.webApps().list().stream().count());
            inventory.setStaticWebApps(0);
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
            log.info(
                    "Force refresh requested for Azure billing data for account {}. Evicting cache and triggering ingestion.",
                    accountId);
            try {
                redisCache.evict(billingCacheKey);
                redisCache.evict(historyCacheKey);
                log.info("Cache evicted for {}.", accountId);

                billingIngestionService.ingestDataForAccount(account);
                log.info("Force ingestion complete for {}.", accountId);
            } catch (Exception e) {
                log.error(
                        "Failed to perform force refresh for account {}. Will proceed with (possibly stale) cached data.",
                        accountId, e);
            }
        }

        // 1. Get Billing Summary from Cache (Cost by Service)
        Optional<List<AzureDashboardData.BillingSummary>> billingSummary = redisCache.get(billingCacheKey,
                new TypeReference<>() {
                });

        if (billingSummary.isPresent()) {
            dashboardData.setBillingSummary(billingSummary.get());
        } else {
            log.warn("No cached billing summary found for {}. Data will be missing until next ingestion.", accountId);
            dashboardData.setBillingSummary(Collections.emptyList());
        }

        // 1b. Get Cost by Region from Cache
        String regionCacheKey = AzureBillingDataIngestionService.AZURE_COST_BY_REGION_CACHE_PREFIX + accountId;
        Optional<java.util.Map<String, Double>> costByRegion = redisCache.get(regionCacheKey,
                new TypeReference<java.util.Map<String, Double>>() {});
        dashboardData.setCostByRegion(costByRegion.orElse(new java.util.HashMap<>()));

        // 2. Get Cost History from Cache
        Optional<AzureDashboardData.CostHistory> costHistory = redisCache.get(historyCacheKey,
                AzureDashboardData.CostHistory.class);

        if (costHistory.isPresent()) {
            dashboardData.setCostHistory(costHistory.get());

            AzureDashboardData.CostHistory history = costHistory.get();
            List<String> labels = history.getLabels();
            List<Double> costs = history.getCosts();
            List<Boolean> anomalies = history.getAnomalies();

            if (labels != null && costs != null && anomalies != null && labels.size() == costs.size()) {
                // Look up costs by month label — MTD = current month only, Last Month = previous month only
                String currentMonthLabel = java.time.LocalDate.now()
                        .format(DateTimeFormatter.ofPattern("MMM yyyy"));
                String lastMonthLabel = java.time.LocalDate.now().minusMonths(1)
                        .format(DateTimeFormatter.ofPattern("MMM yyyy"));

                double mtdSpend = 0.0;
                double lastMonthSpend = 0.0;
                double forecastedSpend = 0.0;

                for (int i = 0; i < labels.size(); i++) {
                    String label = labels.get(i);
                    double cost = costs.get(i);
                    boolean isForecast = Boolean.TRUE.equals(anomalies.get(i));

                    if (isForecast) {
                        forecastedSpend += cost;
                    } else if (label.equals(currentMonthLabel)) {
                        mtdSpend = cost;
                    } else if (label.equals(lastMonthLabel)) {
                        lastMonthSpend = cost;
                    }
                }

                dashboardData.setMonthToDateSpend(mtdSpend);
                dashboardData.setForecastedSpend(forecastedSpend);
                dashboardData.setLastMonthSpend(lastMonthSpend);

                log.info("Dashboard KPIs for {} — MTD: {}, Last Month: {}, Forecasted: {}",
                        accountId, mtdSpend, lastMonthSpend, forecastedSpend);
            } else {
                dashboardData.setMonthToDateSpend(0.0);
                dashboardData.setForecastedSpend(0.0);
                dashboardData.setLastMonthSpend(0.0);
            }
        } else {
            log.warn("No cached cost history found for {}. Data will be missing until next ingestion.", accountId);
            dashboardData.setCostHistory(new AzureDashboardData.CostHistory());
            dashboardData.setMonthToDateSpend(0.0);
            dashboardData.setForecastedSpend(0.0);
            dashboardData.setLastMonthSpend(0.0);
        }
    }

    private void getAndSetRegionStatus(AzureResourceManager azure, AzureDashboardData dashboardData) {
        try {
            // Mock implementation but try to use azure.providers().list() or similar if
            // possible, or just static list
            // For now static list of popular regions is fine or we can assume active based
            // on resources
            List<AzureDashboardData.RegionStatus> statuses = new ArrayList<>();
            // We could iterate resources to find regions, but for now stick to simple mock
            // or existing logic
            statuses.add(new AzureDashboardData.RegionStatus("eastus", 38.0, -78.0, "ACTIVE"));
            statuses.add(new AzureDashboardData.RegionStatus("westus", 37.0, -122.0, "SUSTAINABLE"));
            statuses.add(new AzureDashboardData.RegionStatus("westeurope", 52.3, 4.8, "ACTIVE"));
            dashboardData.setRegionStatus(statuses);
        } catch (Exception e) {
            log.error("Error fetching region statuses: {}", e.getMessage());
            dashboardData.setRegionStatus(Collections.emptyList());
        }
    }

    private void getAndSetOptimizationSummary(AzureResourceManager azure, AzureDashboardData dashboardData,
            String accountId) {
        try {
            DashboardData.OptimizationSummary summary = new DashboardData.OptimizationSummary();
            summary.setTotalPotentialSavings(0.0);
            summary.setCriticalAlertsCount(0);
            dashboardData.setOptimizationSummary(summary);
        } catch (Exception e) {
            log.error("Error fetching optimization summary for account {}: {}", accountId, e.getMessage());
            dashboardData.setOptimizationSummary(new DashboardData.OptimizationSummary());
        }
    }

    private void getAndSetIamDetails(AzureResourceManager azure, AzureDashboardData dashboardData, String accountId) {
        try {
            List<DashboardData.IamUserDetail> users = new ArrayList<>();
            List<DashboardData.IamRoleDetail> roles = new ArrayList<>();

            // 1. Fetch Role Definitions (Roles)
            // Lists all built-in and custom roles. Might be large, so maybe limit or
            // filter?
            // Azure has a lot of built-in roles.
            try {
                azure.accessManagement().roleDefinitions().listByScope(accountId).stream().limit(50).forEach(role -> {
                    DashboardData.IamRoleDetail r = new DashboardData.IamRoleDetail();
                    r.setRoleName(role.roleName());
                    r.setRoleId(role.id());
                    r.setArn(role.id());
                    r.setCreateDate(null);
                    roles.add(r);
                });
            } catch (Exception e) {
                log.warn("Failed to list role definitions for account {}: {}", accountId, e.getMessage());
            }

            // 2. Fetch Role Assignments (Users/Principals)
            // This gives us who has access.
            try {
                azure.accessManagement().roleAssignments().listByScope(accountId).stream().limit(50)
                        .forEach(assignment -> {
                            DashboardData.IamUserDetail user = new DashboardData.IamUserDetail();
                            // Principal Name often not available in assignment, only ID.
                            // We would need Graph API to resolve ID to Name.
                            // For now use Principal ID as name fallback.
                            user.setUserName(assignment.principalId());
                            user.setUserId(assignment.principalId());
                            user.setArn(assignment.id());
                            user.setCreateDate(null);
                            user.setPasswordLastUsed(null);
                            // Add role name to attached policies/groups if possible
                            DashboardData.IamPolicyDetail policy = new DashboardData.IamPolicyDetail();
                            policy.setPolicyName(assignment.roleDefinitionId()); // ID of the role definition
                            user.setAttachedPolicies(List.of(policy));

                            users.add(user);
                        });
            } catch (Exception e) {
                log.warn("Failed to list role assignments for account {}: {}", accountId, e.getMessage());
            }

            dashboardData.setIamDetails(new DashboardData.IamDetail(users, roles));

        } catch (Exception e) {
            log.error("Error fetching IAM details for account {}: {}", accountId, e.getMessage());
            dashboardData.setIamDetails(new DashboardData.IamDetail(Collections.emptyList(), Collections.emptyList()));
        }
    }
}
