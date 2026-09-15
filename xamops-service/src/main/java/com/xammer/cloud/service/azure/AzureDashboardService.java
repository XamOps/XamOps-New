package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.dto.azure.AzureSecurityResponse;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.dto.azure.AzureWasteItem;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
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
    private final com.xammer.cloud.service.security.AzurePrivilegeAnalyzer azurePrivilegeAnalyzer;

    /**
     * Lazy because both services are @Service beans in the same package and the security service
     * is only needed once per nightly build; @Lazy keeps startup order irrelevant.
     */
    private final AzureSecurityService azureSecurityService;

    /**
     * Generic subscription-scoped ARM REST helper.
     *
     * <p>
     * Lives in the {@code service.war.azure} package because WAR needed it first, but nothing
     * about it is WAR-specific — it is a token-plus-GET wrapper. Reused here rather than adding a
     * second copy for the one resource type the fluent SDK's facade does not expose.
     */
    private final com.xammer.cloud.service.war.azure.AzureWarRest armRest;

    /** Trailing-average spike detector over the ingested daily cost series. */
    private final AzureCostAnomalyService costAnomalyService;

    public AzureDashboardService(AzureClientProvider clientProvider,
            CloudAccountRepository cloudAccountRepository,
            CachedDataRepository cachedDataRepository,
            RedisCacheService redisCache,
            AzureBillingDataIngestionService billingIngestionService,
            ObjectMapper objectMapper,
            com.xammer.cloud.service.security.AzurePrivilegeAnalyzer azurePrivilegeAnalyzer,
            @org.springframework.context.annotation.Lazy AzureSecurityService azureSecurityService,
            com.xammer.cloud.service.war.azure.AzureWarRest armRest,
            AzureCostAnomalyService costAnomalyService) {
        this.clientProvider = clientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cachedDataRepository = cachedDataRepository;
        this.redisCache = redisCache;
        this.billingIngestionService = billingIngestionService;
        this.objectMapper = objectMapper;
        this.azurePrivilegeAnalyzer = azurePrivilegeAnalyzer;
        this.azureSecurityService = azureSecurityService;
        this.armRest = armRest;
        this.costAnomalyService = costAnomalyService;
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
                    .runAsync(() -> getAndSetResourceInventory(azure, dashboardData, accountId));
            CompletableFuture<Void> costFuture = CompletableFuture
                    .runAsync(() -> getAndSetCostHistoryAndBilling(dashboardData, account, force));
            CompletableFuture<Void> regionFuture = CompletableFuture
                    .runAsync(() -> getAndSetRegionStatus(azure, dashboardData));
            CompletableFuture<Void> optimizationFuture = CompletableFuture
                    .runAsync(() -> getAndSetOptimizationSummary(azure, dashboardData, accountId));
            CompletableFuture<Void> iamFuture = CompletableFuture
                    .runAsync(() -> getAndSetIamDetails(azure, dashboardData, accountId));
            CompletableFuture<Void> securityFuture = CompletableFuture
                    .runAsync(() -> getAndSetSecurityPosture(dashboardData, accountId));

            CompletableFuture.allOf(inventoryFuture, costFuture, regionFuture, optimizationFuture, iamFuture,
                    securityFuture).join();

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
            if (dashboardData.getCostHistory().getForecast() == null) {
                dashboardData.getCostHistory().setForecast(Collections.emptyList());
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
            Optional<CachedData> existing = cachedDataRepository.findById(cacheKey);

            // Refuse to replace a populated snapshot with an empty one. The refresh path reads
            // cost figures from a Redis cache filled by a separate ingestion job; when that
            // cache is cold the builder produces zero spend and an empty cost history, and this
            // method used to write that straight over good data. The visible effect was that
            // pressing Refresh turned a dashboard showing real spend into an all-zero one,
            // permanently, because the database snapshot has no TTL.
            if (data != null && !data.isDataComplete() && data.isMateriallyEmpty() && existing.isPresent()) {
                AzureDashboardData previous = readSnapshot(existing.get());
                if (previous != null && !previous.isMateriallyEmpty()) {
                    log.error("❌ Refusing to overwrite the Azure dashboard snapshot {} with an empty "
                                    + "result. Warnings: {}. Keeping the previous snapshot so the UI does "
                                    + "not show zero spend for a subscription that has spend.",
                            cacheKey, data.getDataWarnings());
                    return;
                }
            }

            CachedData cachedData = existing.orElse(new CachedData());
            cachedData.setCacheKey(cacheKey);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(cachedData);
            if (data != null && !data.isDataComplete()) {
                log.warn("⚠️ Saved an INCOMPLETE Azure dashboard snapshot {}: {}",
                        cacheKey, data.getDataWarnings());
            } else {
                log.info("✅ Saved Azure dashboard snapshot to DATABASE: {}", cacheKey);
            }
        } catch (Exception e) {
            log.error("❌ Failed to save Azure dashboard snapshot to database: {}", cacheKey, e);
        }
    }

    /** Deserialises a stored snapshot, returning null when it cannot be read. */
    private AzureDashboardData readSnapshot(CachedData cached) {
        try {
            return objectMapper.readValue(cached.getJsonData(), AzureDashboardData.class);
        } catch (Exception e) {
            log.warn("Could not read existing Azure dashboard snapshot {}: {}",
                    cached.getCacheKey(), e.getMessage());
            return null;
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
        costHistory.setForecast(Collections.emptyList());
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

    private void getAndSetResourceInventory(AzureResourceManager azure, AzureDashboardData dashboardData,
            String subscriptionId) {
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
            inventory.setStaticWebApps(countStaticWebApps(subscriptionId, dashboardData));
            dashboardData.setResourceInventory(inventory);
        } catch (Exception e) {
            log.error("Error fetching resource inventory: {}", e.getMessage());
            dashboardData.addDataWarning("Resource inventory unavailable: " + e.getMessage());
            dashboardData.setResourceInventory(new AzureDashboardData.ResourceInventory());
        }
    }

    /**
     * Counts Azure Static Web Apps.
     *
     * <p>
     * Was hardcoded to {@code 0} while every neighbouring field was a live count, so a
     * subscription running Static Web Apps reported none. The fluent SDK's top-level facade has no
     * {@code staticSites()} collection, which is presumably why it was stubbed; the ARM REST
     * endpoint does list them subscription-wide in one call.
     *
     * <p>
     * A read failure records a data warning rather than returning a sentinel. Zero and "unknown"
     * still need to be distinguishable, but the count itself is rendered as a number, so a
     * {@code -1} would leak into the widget; the warning channel already carries this exact
     * distinction and is surfaced on the dashboard.
     */
    private long countStaticWebApps(String subscriptionId, AzureDashboardData dashboardData) {
        try {
            return armRest.listValues(subscriptionId,
                    "/providers/Microsoft.Web/staticSites?api-version=2022-03-01").size();
        } catch (Exception e) {
            log.warn("Could not count Azure Static Web Apps for {}: {}", subscriptionId, e.getMessage());
            dashboardData.addDataWarning("Static Web App count unavailable, so it is shown as zero rather "
                    + "than measured.");
            return 0;
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

        // Whether this subscription has a cost export at all, which is the difference between
        // "the numbers are not in yet" and "the numbers are never coming". Onboarding saves the
        // account even when export setup fails, so a connected subscription can legitimately have
        // no billing source — and every cost figure then reads a confident 0.00 that looks
        // measured. The container name is only set once setupCostExports succeeds, so its absence
        // is the signal.
        boolean costExportConfigured = account.getAzureBillingContainer() != null
                && !account.getAzureBillingContainer().isBlank();

        if (billingSummary.isPresent()) {
            dashboardData.setBillingSummary(billingSummary.get());
        } else {
            log.warn("No cached billing summary found for {}. Data will be missing until next ingestion.", accountId);
            dashboardData.addDataWarning(costExportConfigured
                    ? "Cost by service is unavailable: billing data has not been ingested for this "
                            + "subscription yet."
                    : "Cost reporting is not configured for this subscription. No Azure cost export was "
                            + "created during onboarding, so spend cannot be reported. Re-run the Azure "
                            + "connection with a billing resource group and storage account.");
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
            List<Boolean> forecastFlags = history.getForecast();

            if (labels != null && costs != null && forecastFlags != null && labels.size() == costs.size()) {
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
                    boolean isForecast = Boolean.TRUE.equals(forecastFlags.get(i));

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
            // These zeros are placeholders, not measurements. Flagging that here is what stops
            // saveDashboardToDatabase from writing them over a populated snapshot.
            log.warn("No cached cost history found for {}. Data will be missing until next ingestion.", accountId);
            dashboardData.addDataWarning(costExportConfigured
                    ? "Cost history and month-to-date spend unavailable: the billing ingestion cache is "
                            + "empty for this subscription."
                    : "Cost reporting is not configured for this subscription, so month-to-date and "
                            + "forecast spend are unavailable rather than zero.");
            dashboardData.setCostHistory(new AzureDashboardData.CostHistory());
            dashboardData.setMonthToDateSpend(0.0);
            dashboardData.setForecastedSpend(0.0);
            dashboardData.setLastMonthSpend(0.0);
        }

        // 3. Detect cost forecastFlags over the ingested daily series.
        //
        // This field was hardcoded to an empty list, so every Azure subscription reported no cost
        // anomalies — which the dashboard renders identically to a subscription that was checked and
        // found clean. AWS reads Cost Explorer's GetAnomalies and GCP runs a trailing-average query;
        // Azure now runs the same trailing-average test over the daily cost export.
        try {
            AzureCostAnomalyService.AnomalyOutcome outcome = costAnomalyService.detect(accountId, true);
            dashboardData.setCostAnomalies(outcome.anomalies());
            if (outcome.unavailableReason() != null) {
                // The reason path matters more than the exception path: detection legitimately cannot
                // run for the first week of a month on a month-to-date export, and that returns
                // normally. Handling only the exception left the common case looking clean.
                dashboardData.addDataWarning(outcome.unavailableReason());
            } else if (!outcome.anomalies().isEmpty()) {
                log.info("Detected {} Azure cost anomalies for {}", outcome.anomalies().size(), accountId);
            }
        } catch (Exception e) {
            // An empty list here is the same false-clean signal this change exists to remove, so the
            // failure is recorded as a data warning rather than passed off as "nothing found".
            log.error("Azure cost anomaly detection failed for {}: {}", accountId, e.getMessage(), e);
            dashboardData.setCostAnomalies(Collections.emptyList());
            dashboardData.addDataWarning("Cost anomaly detection did not run for this subscription, so "
                    + "an empty anomaly list does not mean spend is normal.");
        }
    }

    // Static coordinate map for all Azure regions
    private static final Map<String, double[]> AZURE_REGION_COORDINATES = new HashMap<>();
    static {
        AZURE_REGION_COORDINATES.put("eastus",             new double[]{37.3719, -79.8164});
        AZURE_REGION_COORDINATES.put("eastus2",            new double[]{36.6681, -78.3889});
        AZURE_REGION_COORDINATES.put("eastus3",            new double[]{33.4484, -112.074});
        AZURE_REGION_COORDINATES.put("westus",             new double[]{37.7833, -122.4167});
        AZURE_REGION_COORDINATES.put("westus2",            new double[]{47.233,  -119.852});
        AZURE_REGION_COORDINATES.put("westus3",            new double[]{33.4484, -112.074});
        AZURE_REGION_COORDINATES.put("centralus",          new double[]{41.5908, -93.6208});
        AZURE_REGION_COORDINATES.put("northcentralus",     new double[]{41.8819, -87.6278});
        AZURE_REGION_COORDINATES.put("southcentralus",     new double[]{29.4167, -98.5});
        AZURE_REGION_COORDINATES.put("westcentralus",      new double[]{40.890,  -110.234});
        AZURE_REGION_COORDINATES.put("northeurope",        new double[]{53.3478, -6.2597});
        AZURE_REGION_COORDINATES.put("westeurope",         new double[]{52.3667, 4.9});
        AZURE_REGION_COORDINATES.put("uksouth",            new double[]{50.941,  -0.799});
        AZURE_REGION_COORDINATES.put("ukwest",             new double[]{53.427,  -3.084});
        AZURE_REGION_COORDINATES.put("francecentral",      new double[]{46.3772, 2.3730});
        AZURE_REGION_COORDINATES.put("francesouth",        new double[]{43.8345, 2.1972});
        AZURE_REGION_COORDINATES.put("germanywestcentral", new double[]{50.1109, 8.6821});
        AZURE_REGION_COORDINATES.put("germanynorth",       new double[]{53.0736, 8.8064});
        AZURE_REGION_COORDINATES.put("switzerlandnorth",   new double[]{47.4515, 8.5646});
        AZURE_REGION_COORDINATES.put("switzerlandwest",    new double[]{46.2044, 6.1432});
        AZURE_REGION_COORDINATES.put("norwayeast",         new double[]{59.9139, 10.7522});
        AZURE_REGION_COORDINATES.put("norwaywest",         new double[]{58.9700, 5.7331});
        AZURE_REGION_COORDINATES.put("swedencentral",      new double[]{60.6749, 17.1413});
        AZURE_REGION_COORDINATES.put("eastasia",           new double[]{22.267,  114.188});
        AZURE_REGION_COORDINATES.put("southeastasia",      new double[]{1.283,   103.833});
        AZURE_REGION_COORDINATES.put("australiaeast",      new double[]{-33.86,  151.2094});
        AZURE_REGION_COORDINATES.put("australiasoutheast", new double[]{-37.8136, 144.9631});
        AZURE_REGION_COORDINATES.put("australiacentral",   new double[]{-35.3075, 149.1244});
        AZURE_REGION_COORDINATES.put("australiacentral2",  new double[]{-35.3075, 149.1244});
        AZURE_REGION_COORDINATES.put("japaneast",          new double[]{35.68,   139.77});
        AZURE_REGION_COORDINATES.put("japanwest",          new double[]{34.6939, 135.5022});
        AZURE_REGION_COORDINATES.put("koreacentral",       new double[]{37.5665, 126.9780});
        AZURE_REGION_COORDINATES.put("koreasouth",         new double[]{35.1796, 129.0756});
        AZURE_REGION_COORDINATES.put("centralindia",       new double[]{18.5822, 73.9197});
        AZURE_REGION_COORDINATES.put("westindia",          new double[]{19.088,  72.868});
        AZURE_REGION_COORDINATES.put("southindia",         new double[]{12.9822, 80.1636});
        AZURE_REGION_COORDINATES.put("jioindiawest",       new double[]{22.4707, 70.0577});
        AZURE_REGION_COORDINATES.put("jioindiacentral",    new double[]{21.1466, 79.0889});
        AZURE_REGION_COORDINATES.put("canadacentral",      new double[]{43.653,  -79.383});
        AZURE_REGION_COORDINATES.put("canadaeast",         new double[]{46.817,  -71.217});
        AZURE_REGION_COORDINATES.put("brazilsouth",        new double[]{-23.55,  -46.633});
        AZURE_REGION_COORDINATES.put("brazilsoutheast",    new double[]{-22.9028, -43.1729});
        AZURE_REGION_COORDINATES.put("southafricanorth",   new double[]{-25.7313, 28.2184});
        AZURE_REGION_COORDINATES.put("southafricawest",    new double[]{-34.0757, 18.8432});
        AZURE_REGION_COORDINATES.put("uaenorth",           new double[]{25.2667, 55.3167});
        AZURE_REGION_COORDINATES.put("uaecentral",         new double[]{24.4667, 54.3667});
        AZURE_REGION_COORDINATES.put("mexicocentral",      new double[]{20.5,    -99.0});
        AZURE_REGION_COORDINATES.put("israelcentral",      new double[]{31.5,    34.75});
        AZURE_REGION_COORDINATES.put("qatarcentral",       new double[]{25.551,  51.423});
        AZURE_REGION_COORDINATES.put("polandcentral",      new double[]{52.2297, 21.0122});
        AZURE_REGION_COORDINATES.put("italynorth",         new double[]{45.4654, 9.1865});
        AZURE_REGION_COORDINATES.put("spaincentral",       new double[]{40.4168, -3.7038});
        AZURE_REGION_COORDINATES.put("newzealandnorth",    new double[]{-36.8485, 174.7633});
        AZURE_REGION_COORDINATES.put("taiwannorth",        new double[]{25.0330, 121.5654});
        AZURE_REGION_COORDINATES.put("taiwannorthwest",    new double[]{24.9936, 121.3010});
        AZURE_REGION_COORDINATES.put("malaysiawest",       new double[]{3.1390,  101.6869});
    }

    private void getAndSetRegionStatus(AzureResourceManager azure, AzureDashboardData dashboardData) {
        try {
            // Collect all unique regions that have actual resources
            Set<String> activeRegions = azure.genericResources().list().stream()
                    .map(resource -> resource.region().name())
                    .filter(region -> region != null && !region.isBlank())
                    .collect(Collectors.toSet());

            log.info("Found {} active Azure regions with resources: {}", activeRegions.size(), activeRegions);

            List<AzureDashboardData.RegionStatus> statuses = new ArrayList<>();
            for (String regionName : activeRegions) {
                double[] coords = AZURE_REGION_COORDINATES.get(regionName);
                if (coords != null) {
                    statuses.add(new AzureDashboardData.RegionStatus(regionName, coords[0], coords[1], "ACTIVE"));
                } else {
                    log.warn("No coordinate mapping found for Azure region: {}", regionName);
                }
            }

            dashboardData.setRegionStatus(statuses);
        } catch (Exception e) {
            log.error("Error fetching region statuses: {}", e.getMessage());
            dashboardData.setRegionStatus(Collections.emptyList());
        }
    }

    /**
     * Sets the dashboard's optimization summary from the cached waste scan.
     *
     * <p>This previously hardcoded {@code 0.0}, so the Azure dashboard asserted "Potential
     * Savings ₹0.00 — available to reclaim" and "No waste detected" while the Waste page for
     * the same subscription showed seven findings worth ₹6,020 and the FinOps page showed
     * ₹7,078. A hardcoded zero is worse than an absent figure: it reads as a verified
     * all-clear.
     *
     * <p>Reads only from the waste cache so the dashboard does not trigger a live scan. When
     * the cache is cold the summary is left unset rather than zeroed, so the UI can show
     * "not yet scanned" instead of a false zero.
     */
    private void getAndSetOptimizationSummary(AzureResourceManager azure, AzureDashboardData dashboardData,
            String accountId) {
        try {
            Optional<List<AzureWasteItem>> cachedWaste = redisCache.get(
                    AzureWasteService.CACHE_PREFIX + accountId,
                    new TypeReference<List<AzureWasteItem>>() {});

            if (cachedWaste.isEmpty()) {
                log.debug("No cached Azure waste for {} — leaving optimization summary unset "
                        + "rather than reporting zero savings", accountId);
                dashboardData.setOptimizationSummary(null);
                return;
            }

            List<AzureWasteItem> waste = cachedWaste.get();
            double reclaimable = waste.stream()
                    .filter(AzureWasteItem::isBillableWaste)
                    .mapToDouble(AzureWasteItem::getMonthlySavings)
                    .sum();
            long billableCount = waste.stream().filter(AzureWasteItem::isBillableWaste).count();

            DashboardData.OptimizationSummary summary = new DashboardData.OptimizationSummary();
            summary.setTotalPotentialSavings(Math.round(reclaimable * 100.0) / 100.0);
            summary.setCriticalAlertsCount(billableCount);
            dashboardData.setOptimizationSummary(summary);
            log.info("Azure optimization summary for {}: {} reclaimable across {} billable findings",
                    accountId, reclaimable, billableCount);
        } catch (Exception e) {
            log.error("Error fetching optimization summary for account {}: {}", accountId, e.getMessage());
            dashboardData.setOptimizationSummary(null);
        }
    }

    /**
     * Copies the real Defender secure score and unhealthy assessments onto the dashboard.
     *
     * <p>
     * The unified dashboard used to hardcode a security score of 100 for every Azure
     * subscription, because this DTO had no security field to map from. Defender was computing a
     * genuine score all along — it was only ever shown on the standalone Azure Security page.
     * A hardcoded 100 is the worst possible default here: it reads as a verified all-clear.
     *
     * <p>
     * Reads through {@link AzureSecurityService}'s own Redis/DB cache rather than forcing a
     * refresh, so adding this to the nightly dashboard build does not add a Defender round trip
     * on every run.
     *
     * <p>
     * Defender reports points earned against a maximum scaled to the controls in scope, so the
     * score is converted to a percentage of that maximum. Anything other than a successful fetch
     * leaves the score at zero with a reason attached — never a number.
     */
    private void getAndSetSecurityPosture(AzureDashboardData dashboardData, String accountId) {
        try {
            AzureSecurityResponse security = azureSecurityService.getSecurityData(accountId, false);

            if (security == null || security.getStatus() == null) {
                // A cached response deserialized without a status would NPE on the switch below and be
                // swallowed by the catch, which reports a generic reason instead of this specific one.
                dashboardData.setSecurityScoreUnavailableReason(
                        "Defender for Cloud returned no usable response for this subscription.");
                return;
            }

            switch (security.getStatus()) {
                case PERMISSION_ERROR -> dashboardData.setSecurityScoreUnavailableReason(
                        "The service principal lacks the Security Reader role on this subscription, so "
                                + "Defender for Cloud cannot be read. Assign Security Reader to see a "
                                + "security score.");
                case API_ERROR -> dashboardData.setSecurityScoreUnavailableReason(
                        "Defender for Cloud could not be reached, so no security score is available.");
                case NO_DATA -> dashboardData.setSecurityScoreUnavailableReason(
                        "Defender for Cloud reports no assessments for this subscription. Enable a "
                                + "Defender plan to get a security score.");
                case OK -> {
                    // Guarded rather than assumed: a zero maximum would make this a divide-by-zero,
                    // and a zero score with OK status is indistinguishable from "unknown" once it
                    // reaches the dashboard, so both are treated as not-known.
                    if (security.getMaxScore() > 0 && security.getSecureScore() > 0) {
                        int percentage = (int) Math.round(
                                security.getSecureScore() * 100.0 / security.getMaxScore());
                        dashboardData.setSecurityScore(Math.max(0, Math.min(100, percentage)));
                    } else {
                        dashboardData.setSecurityScoreUnavailableReason(
                                "Defender for Cloud did not report a usable secure score for this "
                                        + "subscription.");
                    }
                }
            }

            dashboardData.setSecurityInsights(toSecurityInsights(security.getAssessments()));

        } catch (Exception e) {
            log.error("Failed to read Azure security posture for {}: {}", accountId, e.getMessage());
            dashboardData.setSecurityScoreUnavailableReason(
                    "Security posture could not be read for this subscription.");
        }
    }

    /**
     * Collapses unhealthy Defender assessments into dashboard insights.
     *
     * <p>
     * Grouped by category and severity to match how the AWS path builds the same widget, so the
     * unified dashboard renders one shape regardless of provider.
     */
    private List<DashboardData.SecurityInsight> toSecurityInsights(
            List<com.xammer.cloud.dto.azure.AzureSecurityAssessment> assessments) {

        if (assessments == null || assessments.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, DashboardData.SecurityInsight> byGroup = new java.util.LinkedHashMap<>();
        for (var assessment : assessments) {
            String category = assessment.getCategory() == null || assessment.getCategory().isBlank()
                    ? "Defender for Cloud"
                    : assessment.getCategory();
            String severity = com.xammer.cloud.service.security.SecuritySeverity
                    .normalize(assessment.getSeverity());
            String key = category + "|" + severity;

            DashboardData.SecurityInsight existing = byGroup.get(key);
            if (existing == null) {
                byGroup.put(key, new DashboardData.SecurityInsight(
                        category + " has potential issues", category, severity, 1));
            } else {
                existing.setCount(existing.getCount() + 1);
            }
        }
        return new ArrayList<>(byGroup.values());
    }

    /**
     * Upper bound on role assignments and definitions inspected per subscription.
     *
     * <p>
     * Replaces a bare {@code .limit(50)}. The number matters less than the fact that hitting it
     * now sets {@code permissionAnalysisDegraded}: the previous code truncated silently, so a
     * subscription with 60 assignments showed 50 and claimed to be showing all of them.
     */
    private static final int MAX_IAM_ENTRIES = 500;

    private void getAndSetIamDetails(AzureResourceManager azure, AzureDashboardData dashboardData, String accountId) {
        try {
            List<DashboardData.IamUserDetail> users = new ArrayList<>();
            List<DashboardData.IamRoleDetail> roles = new ArrayList<>();
            List<com.azure.resourcemanager.authorization.models.RoleAssignment> assignments = new ArrayList<>();

            // ARM identifies a subscription by the path "/subscriptions/<guid>", not by the bare
            // guid. listByScope interpolates this argument straight into the request URL, so the
            // previous bare-accountId form produced a malformed scope — and because both calls
            // below only warn on failure, that surfaced as a permanently empty Azure identity list
            // rather than as an error.
            String scope = "/subscriptions/" + accountId;
            boolean truncated = false;

            // 1. Role definitions. Azure ships hundreds of built-in roles; they are listed because
            // the Identity tab shows them, but only custom ones are scored as risky in their own
            // right (a built-in role is a Microsoft artifact, not a customer decision).
            try {
                for (var role : azure.accessManagement().roleDefinitions().listByScope(scope)) {
                    if (roles.size() >= MAX_IAM_ENTRIES) {
                        truncated = true;
                        break;
                    }
                    DashboardData.IamRoleDetail r = new DashboardData.IamRoleDetail();
                    r.setRoleName(role.roleName());
                    r.setRoleId(role.id());
                    r.setArn(role.id());
                    r.setDescription(role.description());
                    r.setCreateDate(null);
                    roles.add(r);
                }
            } catch (Exception e) {
                log.warn("Failed to list role definitions for account {}: {}", accountId, e.getMessage());
            }

            // 2. Role assignments — who actually has access.
            try {
                for (var assignment : azure.accessManagement().roleAssignments().listByScope(scope)) {
                    if (users.size() >= MAX_IAM_ENTRIES) {
                        truncated = true;
                        break;
                    }
                    assignments.add(assignment);

                    DashboardData.IamUserDetail user = new DashboardData.IamUserDetail();
                    // Display name is resolved below where Graph permissions allow it; the object
                    // id is the fallback so the identity is still addressable either way.
                    user.setUserName(assignment.principalId());
                    user.setUserId(assignment.principalId());
                    // The assignment id, not the principal id: AzurePrivilegeAnalyzer joins on this
                    // to find the assignment carrying the scope and condition.
                    user.setArn(assignment.id());
                    user.setCreateDate(createdOn(assignment));
                    user.setPasswordLastUsed(null);
                    user.setGroups(List.of());
                    users.add(user);
                }
            } catch (Exception e) {
                log.warn("Failed to list role assignments for account {}: {}", accountId, e.getMessage());
            }

            boolean namesResolved = resolvePrincipalNames(azure, users, assignments);

            DashboardData.IamDetail details = new DashboardData.IamDetail(users, roles);

            // Resolves each assignment's role definition and scores its actual permissions.
            // Wrapped because an audit failure must not cost the customer the inventory.
            try {
                var outcome = azurePrivilegeAnalyzer.analyze(
                        azure, users, roles, assignments, truncated, namesResolved);
                details.setPermissionAnalysisDegraded(outcome.degraded());
                details.setPermissionAnalysisNote(outcome.note());
            } catch (Exception e) {
                log.error("Azure privilege analysis failed for subscription {}; serving inventory "
                        + "without risk scoring.", accountId, e);
                details.setPermissionAnalysisDegraded(true);
                details.setPermissionAnalysisNote(
                        "Permission analysis did not complete for this subscription, so identities are "
                                + "shown without a risk assessment.");
            }

            dashboardData.setIamDetails(details);

        } catch (Exception e) {
            log.error("Error fetching IAM details for account {}: {}", accountId, e.getMessage());
            dashboardData.setIamDetails(new DashboardData.IamDetail(Collections.emptyList(), Collections.emptyList()));
        }
    }

    /**
     * Replaces principal object ids with display names where Microsoft Entra ID allows it.
     *
     * <p>
     * Best-effort by design. Reading directory objects needs Graph application permissions
     * ({@code Directory.Read.All}) that the Reader-scoped onboarding app registration usually does
     * not have, and a GUID-labelled identity that has been risk-scored is far more useful than no
     * identity at all. Failure is reported through the degraded note rather than thrown.
     *
     * @return false when at least one lookup failed, so the caller can say names are unresolved
     */
    private boolean resolvePrincipalNames(AzureResourceManager azure,
            List<DashboardData.IamUserDetail> users,
            List<com.azure.resourcemanager.authorization.models.RoleAssignment> assignments) {

        if (users.isEmpty()) {
            return true;
        }

        Map<String, com.azure.resourcemanager.authorization.models.PrincipalType> typeById = new HashMap<>();
        for (var assignment : assignments) {
            try {
                if (assignment.innerModel() != null && assignment.principalId() != null) {
                    typeById.put(assignment.principalId(), assignment.innerModel().principalType());
                }
            } catch (Exception ignored) {
                // Inner model absent; the identity keeps its object-id label.
            }
        }

        // Cached because the same principal commonly holds several assignments, and each lookup
        // is a Graph round trip.
        Map<String, String> nameCache = new HashMap<>();
        boolean allResolved = true;

        for (DashboardData.IamUserDetail user : users) {
            String principalId = user.getUserId();
            if (principalId == null || principalId.isBlank()) {
                continue;
            }
            if (nameCache.containsKey(principalId)) {
                String cached = nameCache.get(principalId);
                if (cached != null) {
                    user.setUserName(cached);
                }
                continue;
            }

            var type = typeById.get(principalId);
            String resolved = null;
            try {
                if (com.azure.resourcemanager.authorization.models.PrincipalType.SERVICE_PRINCIPAL.equals(type)) {
                    var sp = azure.accessManagement().servicePrincipals().getById(principalId);
                    resolved = sp == null ? null : sp.name();
                } else if (com.azure.resourcemanager.authorization.models.PrincipalType.GROUP.equals(type)) {
                    var group = azure.accessManagement().activeDirectoryGroups().getById(principalId);
                    resolved = group == null ? null : group.name();
                } else {
                    var adUser = azure.accessManagement().activeDirectoryUsers().getById(principalId);
                    resolved = adUser == null ? null : adUser.userPrincipalName();
                }
            } catch (Exception e) {
                allResolved = false;
                log.debug("Could not resolve Entra principal {}: {}", principalId, e.getMessage());
            }

            nameCache.put(principalId, resolved);
            if (resolved != null && !resolved.isBlank()) {
                user.setUserName(resolved);
            }
        }

        return allResolved;
    }

    /** Assignment creation time, which lives on the inner model. */
    private static String createdOn(com.azure.resourcemanager.authorization.models.RoleAssignment assignment) {
        try {
            if (assignment.innerModel() == null || assignment.innerModel().createdOn() == null) {
                return null;
            }
            return assignment.innerModel().createdOn().toInstant().toString();
        } catch (Exception e) {
            return null;
        }
    }
}
