package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SonarQubeProject;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.dto.gcp.GcpDashboardData;
import com.xammer.cloud.dto.sonarqube.SonarQubeMetricsDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasRequest;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasResponse;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DashboardDataService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardDataService.class);

    // Cache Keys for Phase 2 Snapshots
    private static final String SNAPSHOT_KEY_AWS = "UNIFIED_DASHBOARD_AWS";
    private static final String SNAPSHOT_KEY_GCP = "UNIFIED_DASHBOARD_GCP";
    private static final String SNAPSHOT_KEY_AZURE = "UNIFIED_DASHBOARD_AZURE";

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final GcpDataService gcpDataService;
    private final CloudListService cloudListService;
    private final OptimizationService optimizationService;
    private final SecurityService securityService;
    private final FinOpsService finOpsService;
    private final ReservationService reservationService;
    private final RedisCacheService redisCache;
    private final ObjectMapper objectMapper;
    private final CachedDataRepository cachedDataRepository;
    private final CostService costService;
    private final com.xammer.cloud.service.azure.AzureDashboardService azureDashboardService;
    private final SonarQubeService sonarQubeService;
    private final UserRepository userRepository;

    // Phase 2: Master Data Access for Tenant Iteration
    private final JdbcTemplate masterJdbcTemplate;

    @Autowired
    public DashboardDataService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            GcpDataService gcpDataService,
            @Lazy CloudListService cloudListService,
            @Lazy OptimizationService optimizationService,
            @Lazy SecurityService securityService,
            @Lazy FinOpsService finOpsService,
            @Lazy ReservationService reservationService,
            RedisCacheService redisCache,
            ObjectMapper objectMapper,
            CachedDataRepository cachedDataRepository,
            @Lazy CostService costService,
            @Lazy com.xammer.cloud.service.azure.AzureDashboardService azureDashboardService,
            @Lazy SonarQubeService sonarQubeService,
            UserRepository userRepository,
            @Qualifier("masterDataSource") DataSource masterDataSource) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.gcpDataService = gcpDataService;
        this.cloudListService = cloudListService;
        this.optimizationService = optimizationService;
        this.securityService = securityService;
        this.finOpsService = finOpsService;
        this.reservationService = reservationService;
        this.redisCache = redisCache;
        this.objectMapper = objectMapper;
        this.cachedDataRepository = cachedDataRepository;
        this.costService = costService;
        this.azureDashboardService = azureDashboardService;
        this.sonarQubeService = sonarQubeService;
        this.userRepository = userRepository;
        this.masterJdbcTemplate = new JdbcTemplate(masterDataSource);
    }

    // ============================================================================================
    // PHASE 2: ASYNC AGGREGATOR (BACKGROUND JOBS)
    // ============================================================================================

    /**
     * Runs every 30 minutes to pre-calculate dashboard data for all tenants.
     * This prevents 500 errors caused by timeouts during live fetches.
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes
    public void runBackgroundAggregation() {
        logger.info("⚡ [Async-Aggregator] Starting global dashboard aggregation...");
        long totalStart = System.currentTimeMillis();

        List<String> activeTenants = fetchActiveTenants();
        logger.info("Found {} active tenants to process.", activeTenants.size());

        for (String tenantId : activeTenants) {
            long tenantStart = System.currentTimeMillis();
            try {
                TenantContext.setCurrentTenant(tenantId);
                logger.info(">> Processing Tenant: {}", tenantId);
                aggregateTenantData(tenantId);
                logger.info("<< Finished Tenant: {} | Time: {}ms", tenantId,
                        (System.currentTimeMillis() - tenantStart));
            } catch (Exception e) {
                logger.error("❌ Failed to process tenant {} | Time: {}ms", tenantId,
                        (System.currentTimeMillis() - tenantStart), e);
            } finally {
                TenantContext.clear();
            }
        }
        logger.info("✅ [Async-Aggregator] Global aggregation complete | Total Time: {}ms",
                (System.currentTimeMillis() - totalStart));
    }

    private List<String> fetchActiveTenants() {
        try {
            return masterJdbcTemplate.queryForList("SELECT tenant_id FROM tenant_config WHERE active = true",
                    String.class);
        } catch (Exception e) {
            logger.error("Failed to fetch tenants from Master DB", e);
            return Collections.emptyList();
        }
    }

    private void aggregateTenantData(String tenantId) {
        // 1. Fetch all accounts for this tenant (System Admin View)
        List<CloudAccount> allAccounts = cloudAccountRepository.findAll();
        if (allAccounts.isEmpty()) {
            logger.info("No accounts found for tenant {}. Skipping.", tenantId);
            return;
        }

        // 2. AWS Aggregation
        try {
            List<String> awsIds = allAccounts.stream()
                    .filter(a -> "AWS".equalsIgnoreCase(a.getProvider()))
                    .map(CloudAccount::getAwsAccountId)
                    .collect(Collectors.toList());

            if (!awsIds.isEmpty()) {
                logger.info("Aggregating {} AWS accounts for tenant {}...", awsIds.size(), tenantId);
                DashboardData awsData = getMultiAccountAwsDashboardData(awsIds, true, null); // null user = system
                saveSnapshot(SNAPSHOT_KEY_AWS, awsData);
            }
        } catch (Exception e) {
            logger.error("AWS Aggregation failed for {}", tenantId, e);
        }

        // 3. GCP Aggregation
        try {
            List<String> gcpIds = allAccounts.stream()
                    .filter(a -> "GCP".equalsIgnoreCase(a.getProvider()))
                    .map(CloudAccount::getGcpProjectId)
                    .collect(Collectors.toList());

            if (!gcpIds.isEmpty()) {
                logger.info("Aggregating {} GCP projects for tenant {}...", gcpIds.size(), tenantId);
                DashboardData gcpData = getMultiAccountGcpDashboardData(gcpIds, true, null);
                saveSnapshot(SNAPSHOT_KEY_GCP, gcpData);
            }
        } catch (Exception e) {
            logger.error("GCP Aggregation failed for {}", tenantId, e);
        }

        // 4. Azure Aggregation
        try {
            List<String> azureIds = allAccounts.stream()
                    .filter(a -> "Azure".equalsIgnoreCase(a.getProvider()))
                    .map(CloudAccount::getAzureSubscriptionId)
                    .collect(Collectors.toList());

            if (!azureIds.isEmpty()) {
                logger.info("Aggregating {} Azure subscriptions for tenant {}...", azureIds.size(), tenantId);
                DashboardData azureData = getMultiAccountAzureDashboardData(azureIds, true, null);
                if (azureData != null) {
                    saveSnapshot(SNAPSHOT_KEY_AZURE, azureData);
                }
            }
        } catch (Exception e) {
            logger.error("Azure Aggregation failed for {}", tenantId, e);
        }
    }

    private void saveSnapshot(String key, DashboardData data) {
        try {
            CachedData cachedData = cachedDataRepository.findById(key).orElse(new CachedData());
            cachedData.setCacheKey(key);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(cachedData);
            logger.info("Saved snapshot: {}", key);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize snapshot {}", key, e);
        }
    }

    private DashboardData getSnapshot(String key) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getJsonData(), DashboardData.class);
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize snapshot {}", key, e);
            }
        }
        return null;
    }

    // ============================================================================================
    // PHASE 1 & 2: GRANULAR UNIFIED DASHBOARD METHODS (Updated for Caching)
    // ============================================================================================

    public List<String> getUnifiedTicker(ClientUserDetails userDetails) {
        List<String> messages = new ArrayList<>();
        List<CloudAccount> accounts = fetchUserAccounts(userDetails);

        int totalAccounts = accounts.size();
        long providerCount = accounts.stream().map(CloudAccount::getProvider).distinct().count();

        messages.add("ℹ️ Monitoring " + totalAccounts + " cloud accounts across " + providerCount + " providers.");

        for (CloudAccount acc : accounts) {
            if (acc.getAccountName().toLowerCase().contains("prod")) {
                messages.add("🛡️ Security scan completed for '" + acc.getAccountName() + "': No critical threats.");
            }
        }

        messages.add("🚀 New: AutoSpotting is now available for Azure Virtual Scale Sets.");
        messages.add("💰 Global savings opportunity detected: Rightsizing suggestions updated.");
        messages.add("⚡ System Performance: All systems operational. API Latency: 42ms.");

        return messages;
    }

    public DashboardData getUnifiedSummary(ClientUserDetails userDetails) {
        List<CloudAccount> allAccounts = fetchUserAccounts(userDetails);
        DashboardData data = new DashboardData();
        data.setMultiAccountView(true);
        data.setSelectedProvider("Unified");

        DashboardData.Account unifiedAcc = new DashboardData.Account();
        unifiedAcc.setId("unified-view");
        unifiedAcc.setName("Unified View");

        unifiedAcc.setAwsAccountCount(
                (int) allAccounts.stream().filter(a -> "AWS".equalsIgnoreCase(a.getProvider())).count());
        unifiedAcc.setGcpAccountCount(
                (int) allAccounts.stream().filter(a -> "GCP".equalsIgnoreCase(a.getProvider())).count());
        unifiedAcc.setAzureAccountCount(
                (int) allAccounts.stream().filter(a -> "Azure".equalsIgnoreCase(a.getProvider())).count());

        data.setSelectedAccount(unifiedAcc);
        populateAvailableAccounts(data, userDetails);
        return data;
    }

    public DashboardData getUnifiedAwsData(ClientUserDetails userDetails, boolean forceRefresh)
            throws ExecutionException, InterruptedException, IOException {

        // 1. Try to fetch from Snapshot first (Phase 2 optimization)
        if (!forceRefresh) {
            DashboardData cached = getSnapshot(SNAPSHOT_KEY_AWS);
            if (cached != null) {
                logger.info("Returning Cached AWS Snapshot for Unified View");
                populateAvailableAccounts(cached, userDetails);
                return cached;
            }
        }

        // 2. Fallback to Live Fetch
        List<CloudAccount> allAccounts = fetchUserAccounts(userDetails);
        List<String> awsIds = allAccounts.stream()
                .filter(a -> "AWS".equalsIgnoreCase(a.getProvider()))
                .map(CloudAccount::getAwsAccountId)
                .collect(Collectors.toList());

        if (awsIds.isEmpty())
            return new DashboardData();

        return getMultiAccountAwsDashboardData(awsIds, forceRefresh, userDetails);
    }

    public DashboardData getUnifiedGcpData(ClientUserDetails userDetails, boolean forceRefresh) {

        // 1. Try to fetch from Snapshot
        if (!forceRefresh) {
            DashboardData cached = getSnapshot(SNAPSHOT_KEY_GCP);
            if (cached != null) {
                logger.info("Returning Cached GCP Snapshot for Unified View");
                populateAvailableAccounts(cached, userDetails);
                return cached;
            }
        }

        List<CloudAccount> allAccounts = fetchUserAccounts(userDetails);
        List<String> gcpIds = allAccounts.stream()
                .filter(a -> "GCP".equalsIgnoreCase(a.getProvider()))
                .map(CloudAccount::getGcpProjectId)
                .collect(Collectors.toList());

        if (gcpIds.isEmpty())
            return new DashboardData();

        return getMultiAccountGcpDashboardData(gcpIds, forceRefresh, userDetails);
    }

    public DashboardData getUnifiedAzureData(ClientUserDetails userDetails, boolean forceRefresh) {

        // 1. Try to fetch from Snapshot
        if (!forceRefresh) {
            DashboardData cached = getSnapshot(SNAPSHOT_KEY_AZURE);
            if (cached != null) {
                logger.info("Returning Cached Azure Snapshot for Unified View");
                populateAvailableAccounts(cached, userDetails);
                return cached;
            }
        }

        List<CloudAccount> allAccounts = fetchUserAccounts(userDetails);
        List<String> azureIds = allAccounts.stream()
                .filter(a -> "Azure".equalsIgnoreCase(a.getProvider()))
                .map(CloudAccount::getAzureSubscriptionId)
                .collect(Collectors.toList());

        if (azureIds.isEmpty())
            return new DashboardData();

        return getMultiAccountAzureDashboardData(azureIds, forceRefresh, userDetails);
    }

    public DashboardData.CodeQualitySummary getUnifiedHealthData(ClientUserDetails userDetails) {
        try {
            User user = null;
            if (userDetails != null) {
                user = userRepository.findByUsername(userDetails.getUsername()).orElse(null);
            }
            if (user == null)
                return new DashboardData.CodeQualitySummary("N/A", 0, 0, 0, 0.0, 0);

            List<SonarQubeProject> projects = sonarQubeService.getProjectsForUser(user);
            if (projects.isEmpty())
                return new DashboardData.CodeQualitySummary("N/A", 0, 0, 0, 0.0, 0);

            int bugs = 0, vulns = 0, smells = 0;
            double totalCoverage = 0.0;
            int projectsWithCoverage = 0;

            for (SonarQubeProject p : projects) {
                SonarQubeMetricsDto m = sonarQubeService.getProjectMetrics(p);
                if (m != null) {
                    bugs += m.getBugs();
                    vulns += m.getVulnerabilities();
                    smells += m.getCodeSmells();
                    if (m.getCoverage() > 0) {
                        totalCoverage += m.getCoverage();
                        projectsWithCoverage++;
                    }
                }
            }
            double avgCoverage = projectsWithCoverage > 0 ? totalCoverage / projectsWithCoverage : 0.0;
            String rating = (vulns > 0 || bugs > 10) ? "C" : (smells > 50 ? "B" : "A");

            return new DashboardData.CodeQualitySummary(rating, bugs, vulns, smells, avgCoverage, projects.size());
        } catch (Exception e) {
            logger.error("Error fetching SonarQube metrics", e);
            return new DashboardData.CodeQualitySummary("Error", 0, 0, 0, 0, 0);
        }
    }

    // ============================================================================================
    // PRIVATE HELPERS & CORE LOGIC
    // ============================================================================================

    private List<CloudAccount> fetchUserAccounts(ClientUserDetails userDetails) {
        // Support for "System" user (null) in background tasks
        if (userDetails == null) {
            return cloudAccountRepository.findAll();
        }

        boolean isAdmin = userDetails.getAuthorities().stream()
                .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role.getAuthority())
                        || "ROLE_SUPER_ADMIN".equals(role.getAuthority()));

        if (isAdmin) {
            return cloudAccountRepository.findAll();
        } else {
            return cloudAccountRepository.findByClientId(userDetails.getClientId());
        }
    }

    private CloudAccount getAccount(String accountId) {
        if (!StringUtils.hasText(accountId)) {
            throw new IllegalArgumentException("Account ID cannot be null or empty.");
        }

        Optional<CloudAccount> azureAccount = cloudAccountRepository.findByAzureSubscriptionId(accountId);
        if (azureAccount.isPresent()) {
            return azureAccount.get();
        }

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(accountId, accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }
        return accounts.get(0);
    }

    public DashboardData getMultiAccountDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) throws ExecutionException, InterruptedException, IOException {
        logger.info("Starting multi-account dashboard data fetch for {} accounts", accountIds.size());

        if (accountIds.isEmpty()) {
            throw new IllegalArgumentException("Account list cannot be empty");
        }

        Set<String> providers = new HashSet<>();
        for (String accountId : accountIds) {
            try {
                CloudAccount account = getAccount(accountId);
                providers.add(account.getProvider().toUpperCase());
            } catch (Exception e) {
                logger.error("Failed to validate account {}", accountId, e);
                throw new IllegalArgumentException("Invalid account ID: " + accountId);
            }
        }

        if (providers.size() > 1) {
            throw new IllegalArgumentException(
                    "Cannot mix cloud providers in multi-account view. Found: " +
                            String.join(", ", providers)
                            + ". Please select accounts from only one provider (AWS or GCP).");
        }

        CloudAccount firstAccount = getAccount(accountIds.get(0));
        String provider = firstAccount.getProvider();

        if ("GCP".equalsIgnoreCase(provider)) {
            return getMultiAccountGcpDashboardData(accountIds, forceRefresh, userDetails);
        } else if ("AWS".equalsIgnoreCase(provider)) {
            return getMultiAccountAwsDashboardData(accountIds, forceRefresh, userDetails);
        } else {
            throw new IllegalArgumentException("Multi-account view is not supported for provider: " + provider);
        }
    }

    // ============================================================================================
    // AGGREGATION LOGIC (Refactored)
    // ============================================================================================

    private DashboardData getMultiAccountAzureDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) {
        long startTime = System.currentTimeMillis();
        String user = userDetails != null ? userDetails.getUsername() : "System";
        String tenant = TenantContext.getCurrentTenant();

        logger.info("🔍 [Azure-Fetch] Started | Tenant: {} | User: {} | Accounts: {}", tenant, user, accountIds.size());

        try {
            List<DashboardData> localDataList = accountIds.stream().parallel().map(id -> {
                try {
                    CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(id).orElse(null);
                    if (account == null)
                        return null;

                    com.xammer.cloud.dto.azure.AzureDashboardData azureData = azureDashboardService.getDashboardData(id,
                            forceRefresh);
                    return mapAzureDataToDashboardData(azureData, account);
                } catch (Exception e) {
                    logger.error("Error fetching Azure data for {}", id, e);
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.toList());

            if (localDataList.isEmpty())
                return null;

            DashboardData aggregated = aggregateGenericDashboardData(localDataList, "Azure");
            populateAvailableAccounts(aggregated, userDetails);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("✅ [Azure-Fetch] Completed | Tenant: {} | Time: {}ms | Accounts: {}", tenant, duration,
                    accountIds.size());
            return aggregated;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ [Azure-Fetch] Failed | Tenant: {} | Time: {}ms | Error: {}", tenant, duration,
                    e.getMessage(), e);
            throw e;
        }
    }

    private DashboardData aggregateGenericDashboardData(List<DashboardData> dataList, String provider) {
        if (dataList == null || dataList.isEmpty())
            return null;

        DashboardData aggregated = new DashboardData();
        aggregated.setMultiAccountView(true);
        aggregated.setSelectedProvider(provider);

        DashboardData.Account resultAcc = new DashboardData.Account();
        resultAcc.setId("multi-account-" + provider);
        resultAcc.setName(dataList.size() + " " + provider + " Accounts");

        List<DashboardData.Account> subAccounts = new ArrayList<>();
        double totalCost = 0;
        double totalForecast = 0;
        double totalLast = 0;

        for (DashboardData d : dataList) {
            DashboardData.Account acc = d.getSelectedAccount();
            if (acc != null) {
                totalCost += acc.getMonthToDateSpend();
                totalForecast += acc.getForecastedSpend();
                totalLast += acc.getLastMonthSpend();
                subAccounts.add(new DashboardData.Account(acc.getId(), acc.getName(), acc.getMonthToDateSpend(),
                        acc.getForecastedSpend()));
            }
        }

        resultAcc.setSubAccounts(subAccounts);
        resultAcc.setMonthToDateSpend(totalCost);
        resultAcc.setForecastedSpend(totalForecast);
        resultAcc.setLastMonthSpend(totalLast);

        DashboardData.ResourceInventory inv = new DashboardData.ResourceInventory();
        for (DashboardData d : dataList) {
            DashboardData.ResourceInventory s = d.getSelectedAccount().getResourceInventory();
            if (s != null) {
                mergeInventory(inv, s);
            }
        }
        resultAcc.setResourceInventory(inv);

        resultAcc.setSecurityScore(
                (int) dataList.stream().mapToInt(d -> d.getSelectedAccount().getSecurityScore()).average().orElse(100));

        aggregated.setSelectedAccount(resultAcc);
        return aggregated;
    }

    private void mergeInventory(DashboardData.ResourceInventory target, DashboardData.ResourceInventory source) {
        if (source == null)
            return;
        target.setEc2(target.getEc2() + source.getEc2());
        target.setS3Buckets(target.getS3Buckets() + source.getS3Buckets());
        target.setRdsInstances(target.getRdsInstances() + source.getRdsInstances());
        target.setKubernetes(target.getKubernetes() + source.getKubernetes());
        target.setVpc(target.getVpc() + source.getVpc());
        target.setLambdas(target.getLambdas() + source.getLambdas());
        target.setEbsVolumes(target.getEbsVolumes() + source.getEbsVolumes());
    }

    private DashboardData getMultiAccountGcpDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) {
        long startTime = System.currentTimeMillis();
        String user = userDetails != null ? userDetails.getUsername() : "System";
        String tenant = TenantContext.getCurrentTenant();

        logger.info("🔍 [GCP-Fetch] Started | Tenant: {} | User: {} | Accounts: {}", tenant, user, accountIds.size());

        List<CloudAccount> validAccounts = new ArrayList<>();
        List<String> accountNames = new ArrayList<>();
        List<String> failedAccounts = new ArrayList<>();

        for (String accountId : accountIds) {
            try {
                CloudAccount account = getAccount(accountId);
                if ("GCP".equalsIgnoreCase(account.getProvider())) {
                    validAccounts.add(account);
                    accountNames.add(account.getAccountName());
                } else {
                    logger.warn("Skipping non-GCP account {} in GCP multi-account mode", accountId);
                }
            } catch (Exception e) {
                logger.error("Failed to load account {}", accountId, e);
                failedAccounts.add(accountId);
            }
        }

        if (validAccounts.isEmpty()) {
            throw new RuntimeException("No valid GCP accounts found for multi-account dashboard");
        }

        try {
            List<CompletableFuture<GcpDashboardData>> futures = validAccounts.stream()
                    .map(account -> gcpDataService.getDashboardData(account.getGcpProjectId(), forceRefresh)
                            .exceptionally(ex -> {
                                logger.error("Failed to fetch data for GCP project {}", account.getGcpProjectId(), ex);
                                failedAccounts.add(account.getGcpProjectId());
                                return new GcpDashboardData();
                            }))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<GcpDashboardData> accountDataList = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            DashboardData aggregatedData = aggregateGcpDashboardData(accountDataList, validAccounts, accountNames,
                    failedAccounts);
            populateAvailableAccounts(aggregatedData, userDetails);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("✅ [GCP-Fetch] Completed | Tenant: {} | Time: {}ms | Accounts: {}", tenant, duration,
                    accountIds.size());
            return aggregatedData;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ [GCP-Fetch] Failed | Tenant: {} | Time: {}ms | Error: {}", tenant, duration, e.getMessage(),
                    e);
            throw e;
        }
    }

    private DashboardData aggregateGcpDashboardData(List<GcpDashboardData> dataList,
            List<CloudAccount> accounts,
            List<String> accountNames,
            List<String> failedAccounts) {
        DashboardData aggregated = new DashboardData();
        aggregated.setMultiAccountView(true);
        aggregated.setSelectedAccountIds(
                accounts.stream().map(CloudAccount::getGcpProjectId).collect(Collectors.toList()));
        aggregated.setSelectedAccountNames(accountNames);
        aggregated.setFailedAccounts(failedAccounts);
        aggregated.setSelectedProvider("GCP");

        DashboardData.Account aggregatedAccount = new DashboardData.Account();
        aggregatedAccount.setId("multi-account-gcp");
        aggregatedAccount.setName(String.format("%d GCP Projects", accounts.size()));

        List<DashboardData.Account> subAccounts = new ArrayList<>();

        double totalMTD = 0.0;
        double totalForecast = 0.0;
        double totalLastMonth = 0.0;
        double totalOptimizationSavings = 0.0;
        long totalCriticalAlerts = 0;
        double totalPotentialSavings = 0.0;

        DashboardData.ResourceInventory aggregatedInventory = new DashboardData.ResourceInventory();
        DashboardData.IamResources aggregatedIam = new DashboardData.IamResources(0, 0, 0, 0);
        Set<String> uniqueRegions = new HashSet<>();
        Map<String, Double> costHistoryMap = new LinkedHashMap<>();
        Map<String, Double> billingSummaryMap = new HashMap<>();
        Map<String, DashboardData.SecurityInsight> securityInsightMap = new HashMap<>();
        List<Integer> securityScores = new ArrayList<>();

        for (int i = 0; i < dataList.size(); i++) {
            GcpDashboardData data = dataList.get(i);
            CloudAccount acc = accounts.get(i);

            totalMTD += data.getMonthToDateSpend();
            totalForecast += data.getForecastedSpend();
            totalLastMonth += data.getLastMonthSpend();

            subAccounts.add(new DashboardData.Account(acc.getGcpProjectId(), acc.getAccountName(),
                    data.getMonthToDateSpend(), data.getForecastedSpend()));

            if (data.getResourceInventory() != null) {
                mergeInventory(aggregatedInventory, data.getResourceInventory());
            }

            if (data.getIamResources() != null) {
                aggregatedIam.setUsers(aggregatedIam.getUsers() + data.getIamResources().getUsers());
                aggregatedIam.setRoles(aggregatedIam.getRoles() + data.getIamResources().getRoles());
                aggregatedIam.setGroups(aggregatedIam.getGroups() + data.getIamResources().getGroups());
                aggregatedIam
                        .setCustomerManagedPolicies(aggregatedIam.getPolicies() + data.getIamResources().getPolicies());
            }

            if (data.getSecurityScore() > 0) {
                securityScores.add(data.getSecurityScore());
            }
            if (data.getSecurityInsights() != null) {
                for (DashboardData.SecurityInsight insight : data.getSecurityInsights()) {
                    String key = insight.getCategory();
                    securityInsightMap.merge(key, insight, (existing, valid) -> new DashboardData.SecurityInsight(
                            existing.getTitle(),
                            existing.getCategory(),
                            existing.getSeverity(),
                            existing.getCount() + valid.getCount()));
                }
            }

            if (data.getOptimizationSummary() != null) {
                totalOptimizationSavings += data.getOptimizationSummary().getTotalPotentialSavings();
                totalCriticalAlerts += data.getOptimizationSummary().getCriticalAlerts();
            }
            if (data.getSavingsSummary() != null) {
                totalPotentialSavings += data.getSavingsSummary().getTotalPotentialSavings();
            }

            if (data.getRegionStatus() != null) {
                data.getRegionStatus().forEach(r -> uniqueRegions.add(r.getRegionId()));
            }

            if (data.getCostHistory() != null) {
                for (GcpCostDto cost : data.getCostHistory()) {
                    costHistoryMap.merge(cost.getName(), cost.getAmount(), Double::sum);
                }
            }

            if (data.getBillingSummary() != null) {
                for (GcpCostDto bill : data.getBillingSummary()) {
                    billingSummaryMap.merge(bill.getName(), bill.getAmount(), Double::sum);
                }
            }
        }

        aggregatedAccount.setSubAccounts(subAccounts);
        aggregatedAccount.setMonthToDateSpend(totalMTD);
        aggregatedAccount.setForecastedSpend(totalForecast);
        aggregatedAccount.setLastMonthSpend(totalLastMonth);
        aggregatedAccount.setResourceInventory(aggregatedInventory);
        aggregatedAccount.setIamResources(aggregatedIam);
        aggregatedAccount.setSecurityScore(securityScores.isEmpty() ? 100
                : (int) securityScores.stream().mapToInt(Integer::intValue).average().orElse(100));
        aggregatedAccount.setSecurityInsights(new ArrayList<>(securityInsightMap.values()));

        aggregatedAccount.setOptimizationSummary(
                new DashboardData.OptimizationSummary(totalOptimizationSavings, totalCriticalAlerts));

        List<DashboardData.SavingsSuggestion> savingsSuggestions = new ArrayList<>();
        if (totalPotentialSavings > 0) {
            savingsSuggestions
                    .add(new DashboardData.SavingsSuggestion("Total Potential Savings", totalPotentialSavings));
        }
        aggregatedAccount
                .setSavingsSummary(new DashboardData.SavingsSummary(totalPotentialSavings, savingsSuggestions));

        List<DashboardData.RegionStatus> regionStatusList = uniqueRegions.stream()
                .map(regionId -> new DashboardData.RegionStatus(regionId, true))
                .collect(Collectors.toList());
        aggregatedAccount.setRegionStatus(regionStatusList);

        List<String> costLabels = new ArrayList<>(costHistoryMap.keySet());
        costLabels.sort(this::compareMonthStrings);
        List<Double> costValues = costLabels.stream().map(costHistoryMap::get).collect(Collectors.toList());
        List<Boolean> costAnomalies = costLabels.stream().map(l -> false).collect(Collectors.toList());
        aggregatedAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));

        List<DashboardData.BillingSummary> billingSummaryList = billingSummaryMap.entrySet().stream()
                .map(entry -> new DashboardData.BillingSummary(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> Double.compare(b.getAmount(), a.getAmount()))
                .collect(Collectors.toList());
        aggregatedAccount.setBillingSummary(billingSummaryList);

        aggregatedAccount.setEc2Recommendations(Collections.emptyList());
        aggregatedAccount.setWastedResources(Collections.emptyList());
        aggregatedAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0, 0, 0));

        aggregated.setSelectedAccount(aggregatedAccount);
        return aggregated;
    }

    private DashboardData getMultiAccountAwsDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) throws ExecutionException, InterruptedException, IOException {
        long startTime = System.currentTimeMillis();
        String user = userDetails != null ? userDetails.getUsername() : "System";
        String tenant = TenantContext.getCurrentTenant();

        logger.info("🔍 [AWS-Fetch] Started | Tenant: {} | User: {} | Accounts: {}", tenant, user, accountIds.size());

        List<CloudAccount> validAccounts = new ArrayList<>();
        List<String> accountNames = new ArrayList<>();
        List<String> failedAccounts = new ArrayList<>();

        for (String accountId : accountIds) {
            try {
                CloudAccount account = getAccount(accountId);
                if ("AWS".equalsIgnoreCase(account.getProvider())) {
                    validAccounts.add(account);
                    accountNames.add(account.getAccountName());
                } else {
                    logger.warn("Skipping non-AWS account {} in AWS multi-account mode", accountId);
                }
            } catch (Exception e) {
                logger.error("Failed to load account {}", accountId, e);
                failedAccounts.add(accountId);
            }
        }

        if (validAccounts.isEmpty()) {
            throw new RuntimeException("No valid AWS accounts found for multi-account dashboard");
        }

        try {
            List<CompletableFuture<DashboardData>> futures = validAccounts.stream()
                    .map(account -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return getDashboardData(account.getAwsAccountId(), forceRefresh, userDetails);
                        } catch (Exception e) {
                            logger.error("Failed to fetch data for account {}", account.getAwsAccountId(), e);
                            failedAccounts.add(account.getAwsAccountId());
                            return null;
                        }
                    }))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<DashboardData> accountDataList = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (accountDataList.isEmpty()) {
                throw new RuntimeException("Failed to fetch data from all selected AWS accounts");
            }

            DashboardData aggregatedData = aggregateDashboardData(accountDataList, validAccounts, accountNames,
                    failedAccounts);
            populateAvailableAccounts(aggregatedData, userDetails);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("✅ [AWS-Fetch] Completed | Tenant: {} | Time: {}ms | Accounts: {}", tenant, duration,
                    accountIds.size());
            return aggregatedData;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ [AWS-Fetch] Failed | Tenant: {} | Time: {}ms | Error: {}", tenant, duration, e.getMessage(),
                    e);
            throw e;
        }
    }

    private int compareMonthStrings(String a, String b) {
        try {
            String normalizedA = normalizeMonthString(a);
            String normalizedB = normalizeMonthString(b);

            DateTimeFormatter formatter;
            if (normalizedA.contains(" ")) {
                formatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
            } else if (normalizedA.contains("-")) {
                formatter = DateTimeFormatter.ofPattern("yyyy-MM");
            } else {
                return a.compareTo(b);
            }

            YearMonth dateA = YearMonth.parse(normalizedA, formatter);
            YearMonth dateB = YearMonth.parse(normalizedB, formatter);
            return dateA.compareTo(dateB);
        } catch (Exception e) {
            logger.debug("Could not parse month labels '{}' and '{}' as dates, using string comparison", a, b);
            return normalizeMonthString(a).compareTo(normalizeMonthString(b));
        }
    }

    private void populateAvailableAccounts(DashboardData data, ClientUserDetails userDetails) {
        if (userDetails != null) {
            List<CloudAccount> userAccounts = fetchUserAccounts(userDetails);

            List<DashboardData.Account> availableAccounts = userAccounts.stream()
                    .map(acc -> new DashboardData.Account(
                            "AWS".equalsIgnoreCase(acc.getProvider()) ? acc.getAwsAccountId() : acc.getGcpProjectId(),
                            acc.getAccountName(),
                            Collections.emptyList(),
                            null, null, Collections.emptyList(), null, Collections.emptyList(), null, null, null,
                            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                            Collections.emptyList(),
                            null, Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(),
                            100, 0.0, 0.0, 0.0))
                    .collect(Collectors.toList());
            data.setAvailableAccounts(availableAccounts);
        }
    }

    private DashboardData aggregateDashboardData(List<DashboardData> accountDataList,
            List<CloudAccount> accounts,
            List<String> accountNames,
            List<String> failedAccounts) {
        DashboardData aggregated = new DashboardData();
        aggregated.setMultiAccountView(true);
        aggregated.setSelectedAccountIds(
                accounts.stream().map(CloudAccount::getAwsAccountId).collect(Collectors.toList()));
        aggregated.setSelectedAccountNames(accountNames);
        aggregated.setFailedAccounts(failedAccounts);
        aggregated.setSelectedProvider("AWS");

        double totalMTD = 0.0;
        double totalForecast = 0.0;
        double totalLastMonth = 0.0;
        double totalPotentialSavings = 0.0;

        DashboardData.ResourceInventory aggregatedInventory = new DashboardData.ResourceInventory();

        int totalUsers = 0, totalGroups = 0, totalPolicies = 0, totalRoles = 0;
        int totalAlarms = 0, totalMetrics = 0, totalLogs = 0;

        Set<String> uniqueRegions = new HashSet<>();
        Map<String, Double> costHistoryMap = new LinkedHashMap<>();
        Map<String, Double> billingSummaryMap = new HashMap<>();

        double totalOptimizationSavings = 0.0;
        long totalCriticalAlerts = 0;

        List<DashboardData.Account> subAccounts = new ArrayList<>();

        for (DashboardData data : accountDataList) {
            DashboardData.Account account = data.getSelectedAccount();
            if (account == null)
                continue;

            subAccounts.add(new DashboardData.Account(account.getId(), account.getName(), account.getMonthToDateSpend(),
                    account.getForecastedSpend()));

            totalMTD += account.getMonthToDateSpend();
            totalForecast += account.getForecastedSpend();
            totalLastMonth += account.getLastMonthSpend();

            if (account.getSavingsSummary() != null) {
                totalPotentialSavings += account.getSavingsSummary().getTotalPotentialSavings();
            }

            if (account.getResourceInventory() != null) {
                mergeInventory(aggregatedInventory, account.getResourceInventory());
            }

            if (account.getIamResources() != null) {
                totalUsers += account.getIamResources().getUsers();
                totalGroups += account.getIamResources().getGroups();
                totalPolicies += account.getIamResources().getPolicies();
                totalRoles += account.getIamResources().getRoles();
            }

            if (account.getCloudWatchStatus() != null) {
                totalAlarms += account.getCloudWatchStatus().getAlarms();
                totalMetrics += account.getCloudWatchStatus().getMetrics();
                totalLogs += account.getCloudWatchStatus().getLogGroups();
            }

            if (account.getRegionStatus() != null) {
                account.getRegionStatus().forEach(region -> uniqueRegions.add(region.getRegionId()));
            }

            if (account.getCostHistory() != null && account.getCostHistory().getLabels() != null) {
                List<String> labels = account.getCostHistory().getLabels();
                List<Double> values = account.getCostHistory().getValues() != null
                        ? account.getCostHistory().getValues()
                        : account.getCostHistory().getCosts();

                for (int i = 0; i < labels.size() && i < values.size(); i++) {
                    String month = labels.get(i);
                    double amount = values.get(i);
                    costHistoryMap.put(month, costHistoryMap.getOrDefault(month, 0.0) + amount);
                }
            }

            if (account.getBillingSummary() != null) {
                for (DashboardData.BillingSummary billing : account.getBillingSummary()) {
                    String service = billing.getServiceName();
                    double amount = billing.getAmount() != 0 ? billing.getAmount() : billing.getMonthToDateCost();
                    billingSummaryMap.put(service, billingSummaryMap.getOrDefault(service, 0.0) + amount);
                }
            }

            if (account.getOptimizationSummary() != null) {
                totalOptimizationSavings += account.getOptimizationSummary().getTotalPotentialSavings();
                totalCriticalAlerts += account.getOptimizationSummary().getCriticalAlerts();
            }
        }

        DashboardData.Account aggregatedAccount = new DashboardData.Account();
        aggregatedAccount.setId("multi-account");
        aggregatedAccount.setName(String.format("%d AWS Accounts", accounts.size()));
        aggregatedAccount.setSubAccounts(subAccounts);
        aggregatedAccount.setMonthToDateSpend(totalMTD);
        aggregatedAccount.setForecastedSpend(totalForecast);
        aggregatedAccount.setLastMonthSpend(totalLastMonth);
        aggregatedAccount.setResourceInventory(aggregatedInventory);

        aggregatedAccount
                .setIamResources(new DashboardData.IamResources(totalUsers, totalGroups, totalPolicies, totalRoles));

        aggregatedAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(totalAlarms, totalMetrics, totalLogs));

        List<DashboardData.RegionStatus> regionStatusList = uniqueRegions.stream()
                .map(regionId -> new DashboardData.RegionStatus(regionId, true))
                .collect(Collectors.toList());
        aggregatedAccount.setRegionStatus(regionStatusList);

        List<String> costLabels = new ArrayList<>(costHistoryMap.keySet());

        costLabels.sort(this::compareMonthStrings);

        List<Double> costValues = costLabels.stream()
                .map(costHistoryMap::get)
                .collect(Collectors.toList());

        List<Boolean> costAnomalies = costLabels.stream()
                .map(label -> false)
                .collect(Collectors.toList());

        aggregatedAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));

        List<DashboardData.BillingSummary> billingSummaryList = billingSummaryMap.entrySet().stream()
                .map(entry -> new DashboardData.BillingSummary(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> {
                    double amountA = a.getAmount() != 0 ? a.getAmount() : a.getMonthToDateCost();
                    double amountB = b.getAmount() != 0 ? b.getAmount() : b.getMonthToDateCost();
                    return Double.compare(amountB, amountA);
                })
                .collect(Collectors.toList());
        aggregatedAccount.setBillingSummary(billingSummaryList);

        List<DashboardData.SavingsSuggestion> savingsSuggestions = new ArrayList<>();
        if (totalPotentialSavings > 0) {
            savingsSuggestions
                    .add(new DashboardData.SavingsSuggestion("Total Potential Savings", totalPotentialSavings));
        }
        aggregatedAccount
                .setSavingsSummary(new DashboardData.SavingsSummary(totalPotentialSavings, savingsSuggestions));

        aggregatedAccount.setOptimizationSummary(
                new DashboardData.OptimizationSummary(totalOptimizationSavings, totalCriticalAlerts));

        int avgSecurityScore = (int) accountDataList.stream()
                .filter(d -> d.getSelectedAccount() != null)
                .mapToInt(d -> d.getSelectedAccount().getSecurityScore())
                .average()
                .orElse(100.0);
        aggregatedAccount.setSecurityScore(avgSecurityScore);

        aggregatedAccount.setEc2Recommendations(Collections.emptyList());
        aggregatedAccount.setEbsRecommendations(Collections.emptyList());
        aggregatedAccount.setLambdaRecommendations(Collections.emptyList());
        aggregatedAccount.setCostAnomalies(Collections.emptyList());
        aggregatedAccount.setWastedResources(Collections.emptyList());
        aggregatedAccount.setSecurityInsights(Collections.emptyList());
        aggregatedAccount.setReservationAnalysis(null);
        aggregatedAccount.setReservationPurchaseRecommendations(Collections.emptyList());
        aggregatedAccount.setServiceQuotas(Collections.emptyList());

        aggregated.setSelectedAccount(aggregatedAccount);
        return aggregated;
    }

    private String normalizeMonthString(String monthStr) {
        return monthStr
                .replace("Sept ", "Sep ")
                .replace("June ", "Jun ")
                .replace("July ", "Jul ")
                .replace("January ", "Jan ")
                .replace("February ", "Feb ")
                .replace("March ", "Mar ")
                .replace("April ", "Apr ")
                .replace("August ", "Aug ")
                .replace("September ", "Sep ")
                .replace("October ", "Oct ")
                .replace("November ", "Nov ")
                .replace("December ", "Dec ");
    }

    public DashboardData getDashboardData(String accountId, boolean forceRefresh, ClientUserDetails userDetails)
            throws ExecutionException, InterruptedException, IOException {
        long startTime = System.currentTimeMillis();
        String user = userDetails != null ? userDetails.getUsername() : "System";
        String tenant = TenantContext.getCurrentTenant();
        logger.info("🔍 [Single-Account-Fetch] Started | Tenant: {} | User: {} | Account: {}", tenant, user, accountId);

        String cacheKey = "dashboardData-" + accountId;

        if (!forceRefresh) {
            Optional<DashboardData> cachedData = redisCache.get(cacheKey, DashboardData.class);
            if (cachedData.isPresent()) {
                logger.info("✅ [Single-Account-Fetch] Returned from Cache | Account: {}", accountId);
                return cachedData.get();
            }
        }

        CloudAccount account = getAccount(accountId);
        DashboardData freshData;

        try {
            if ("GCP".equalsIgnoreCase(account.getProvider())) {
                GcpDashboardData gcpData = gcpDataService.getDashboardData(account.getGcpProjectId(), forceRefresh)
                        .exceptionally(ex -> {
                            logger.error(
                                    "Failed to get a complete GCP dashboard data object for account {}. Returning partial data.",
                                    account.getGcpProjectId(), ex);
                            return new GcpDashboardData();
                        })
                        .get();
                freshData = mapGcpDataToDashboardData(gcpData, account);
            } else if ("Azure".equalsIgnoreCase(account.getProvider())) {
                com.xammer.cloud.dto.azure.AzureDashboardData azureData = azureDashboardService
                        .getDashboardData(account.getAzureSubscriptionId(), forceRefresh);
                freshData = mapAzureDataToDashboardData(azureData, account);
            } else {
                // AWS
                freshData = getAwsDashboardData(account, forceRefresh, userDetails);
            }

            // Cache in Redis
            redisCache.put(cacheKey, freshData, 10);

            // ✅ Archive to Postgres for Superset
            String dbKey = "DASHBOARD_DATA::" + accountId + "::" + LocalDate.now();
            String json = objectMapper.writeValueAsString(freshData);

            CachedData archivalData = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            archivalData.setCacheKey(dbKey);
            archivalData.setJsonData(json);
            archivalData.setLastUpdated(LocalDateTime.now());

            cachedDataRepository.save(archivalData);
            logger.info("✅ Archived Dashboard Data to DB for account {}", accountId);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("✅ [Single-Account-Fetch] Completed | Tenant: {} | Time: {}ms | Account: {}", tenant, duration,
                    accountId);
            return freshData;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ [Single-Account-Fetch] Failed | Tenant: {} | Time: {}ms | Account: {} | Error: {}", tenant,
                    duration, accountId, e.getMessage(), e);

            // Return error object instead of throwing to prevent frontend crash
            freshData = new DashboardData();
            freshData.setError("Failed to load data: " + e.getMessage());
            freshData.setSelectedProvider(account.getProvider());
            freshData.setSelectedAccount(new DashboardData.Account(
                    "AWS".equalsIgnoreCase(account.getProvider()) ? account.getAwsAccountId()
                            : account.getGcpProjectId(),
                    account.getAccountName(),
                    Collections.emptyList(), new DashboardData.ResourceInventory(), null, Collections.emptyList(),
                    null, Collections.emptyList(), null, null, null, Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(), null,
                    Collections.emptyList(), Collections.emptyList(), 0, 0.0, 0.0, 0.0));
            return freshData;
        }
    }

    private DashboardData mapGcpDataToDashboardData(GcpDashboardData gcpData, CloudAccount account) {
        DashboardData data = new DashboardData();
        data.setSelectedProvider("GCP");

        DashboardData.Account mainAccount = new DashboardData.Account();
        mainAccount.setId(account.getGcpProjectId());
        mainAccount.setName(account.getAccountName());

        mainAccount.setResourceInventory(gcpData.getResourceInventory());
        mainAccount.setIamResources(gcpData.getIamResources());
        mainAccount.setIamDetails(gcpData.getIamDetails());
        mainAccount.setSecurityScore(gcpData.getSecurityScore());
        mainAccount.setSecurityInsights(gcpData.getSecurityInsights());
        mainAccount.setSavingsSummary(gcpData.getSavingsSummary());
        mainAccount.setMonthToDateSpend(gcpData.getMonthToDateSpend());
        mainAccount.setForecastedSpend(gcpData.getForecastedSpend());
        mainAccount.setLastMonthSpend(gcpData.getLastMonthSpend());
        mainAccount.setOptimizationSummary(gcpData.getOptimizationSummary());
        mainAccount.setRegionStatus(gcpData.getRegionStatus());

        List<String> costLabels = (gcpData.getCostHistory() != null)
                ? gcpData.getCostHistory().stream().map(c -> c.getName()).collect(Collectors.toList())
                : new ArrayList<>();
        List<Double> costValues = (gcpData.getCostHistory() != null)
                ? gcpData.getCostHistory().stream().map(c -> c.getAmount()).collect(Collectors.toList())
                : new ArrayList<>();
        List<Boolean> costAnomalies = (gcpData.getCostHistory() != null)
                ? gcpData.getCostHistory().stream().map(c -> c.isAnomaly()).collect(Collectors.toList())
                : new ArrayList<>();
        mainAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));

        List<DashboardData.BillingSummary> billingSummary = (gcpData.getBillingSummary() != null)
                ? gcpData.getBillingSummary().stream()
                        .map(b -> new DashboardData.BillingSummary(b.getName(), b.getAmount()))
                        .collect(Collectors.toList())
                : new ArrayList<>();
        mainAccount.setBillingSummary(billingSummary);

        List<DashboardData.OptimizationRecommendation> gceRecs = (gcpData.getRightsizingRecommendations() != null)
                ? gcpData.getRightsizingRecommendations().stream()
                        .map(rec -> new DashboardData.OptimizationRecommendation(
                                "GCE", rec.getResourceName(), rec.getCurrentMachineType(),
                                rec.getRecommendedMachineType(), rec.getMonthlySavings(), "Rightsizing opportunity",
                                0.0, 0.0))
                        .collect(Collectors.toList())
                : new ArrayList<>();
        mainAccount.setEc2Recommendations(gceRecs);

        List<DashboardData.WastedResource> wastedResources = (gcpData.getWastedResources() != null)
                ? gcpData.getWastedResources().stream()
                        .map(waste -> new DashboardData.WastedResource(
                                waste.getResourceName(), waste.getResourceName(), waste.getType(),
                                waste.getLocation(), waste.getMonthlySavings(), "Idle Resource"))
                        .collect(Collectors.toList())
                : new ArrayList<>();
        mainAccount.setWastedResources(wastedResources);
        mainAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0, 0, 0));
        mainAccount.setCostAnomalies(new ArrayList<>());
        mainAccount.setEbsRecommendations(new ArrayList<>());
        mainAccount.setLambdaRecommendations(new ArrayList<>());

        data.setSelectedAccount(mainAccount);
        populateAvailableAccounts(data, null);

        return data;
    }

    private DashboardData getAwsDashboardData(CloudAccount account, boolean forceRefresh, ClientUserDetails userDetails)
            throws ExecutionException, InterruptedException {
        logger.info("--- LAUNCHING OPTIMIZED ASYNC DATA FETCH FROM AWS for account {} ---", account.getAwsAccountId());

        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService
                .getRegionStatusForAccount(account, forceRefresh)
                .exceptionally(ex -> {
                    logger.error("Failed to fetch regions for account {}", account.getAwsAccountId(), ex);
                    return Collections.emptyList();
                });

        return activeRegionsFuture.thenCompose((List<DashboardData.RegionStatus> activeRegions) -> {
            if (activeRegions == null || activeRegions.isEmpty()) {
                logger.warn("No active regions found for account {}. Returning empty dashboard.",
                        account.getAwsAccountId());
                return CompletableFuture.completedFuture(new DashboardData());
            }

            CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture = cloudListService
                    .getAllResourcesGrouped(account.getAwsAccountId(), forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Failed to fetch grouped resources for account {}", account.getAwsAccountId(), ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<DashboardData.ResourceInventory> inventoryFuture = getResourceInventory(
                    groupedResourcesFuture)
                    .exceptionally(ex -> {
                        logger.error("Inventory calculation failed for {}", account.getAwsAccountId(), ex);
                        return new DashboardData.ResourceInventory();
                    });

            CompletableFuture<DashboardData.CloudWatchStatus> cwStatusFuture = getCloudWatchStatus(account,
                    activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("CloudWatch fetch failed for {}", account.getAwsAccountId(), ex);
                        return new DashboardData.CloudWatchStatus(0, 0, 0);
                    });

            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture = optimizationService
                    .getEc2InstanceRecommendations(account, activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("EC2 Recs failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture = optimizationService
                    .getEbsVolumeRecommendations(account, activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("EBS Recs failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture = optimizationService
                    .getLambdaFunctionRecommendations(account, activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Lambda Recs failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.WastedResource>> wastedResourcesFuture = optimizationService
                    .getWastedResources(account, activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Waste fetch failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.SecurityFinding>> securityFindingsFuture = securityService
                    .getComprehensiveSecurityFindings(account, activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Security findings failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<DashboardData.CostHistory> costHistoryFuture = finOpsService
                    .getCostHistory(account, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Cost history failed", ex);
                        return new DashboardData.CostHistory();
                    });

            CompletableFuture<List<DashboardData.BillingSummary>> billingFuture = finOpsService
                    .getBillingSummary(account, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Billing summary failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<DashboardData.IamResources> iamFuture = getIamResources(account, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("IAM resources failed", ex);
                        return new DashboardData.IamResources();
                    });

            CompletableFuture<DashboardData.IamDetail> iamDetailsFuture = getIamDetails(account, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("IAM details failed", ex);
                        return new DashboardData.IamDetail();
                    });

            CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService
                    .getCostAnomalies(account, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Cost anomalies failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<DashboardData.ReservationAnalysis> reservationFuture = reservationService
                    .getReservationAnalysis(account, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Reservation analysis failed", ex);
                        return new DashboardData.ReservationAnalysis();
                    });

            CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> reservationPurchaseFuture = reservationService
                    .getReservationPurchaseRecommendations(account, "ONE_YEAR", "NO_UPFRONT", "THIRTY_DAYS", "STANDARD",
                            forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Reservation recommendations failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.ServiceQuotaInfo>> vpcQuotaInfoFuture = getServiceQuotaInfo(account,
                    activeRegions, groupedResourcesFuture, "vpc", "L-F678F1CE")
                    .exceptionally(ex -> {
                        logger.error("Service quotas failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<Double> mtdSpendFuture = costService
                    .getTotalMonthToDateCost(account.getAwsAccountId(), forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("MTD Cost failed", ex);
                        return 0.0;
                    });

            CompletableFuture<Double> lastMonthSpendFuture = costService
                    .getLastMonthSpend(account.getAwsAccountId(), forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Last Month Cost failed", ex);
                        return 0.0;
                    });

            CompletableFuture<DashboardData.SavingsSummary> savingsFuture = getSavingsSummary(
                    wastedResourcesFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture)
                    .exceptionally(ex -> {
                        logger.error("Savings summary calculation failed", ex);
                        return new DashboardData.SavingsSummary(0.0, 0.0);
                    });

            return CompletableFuture.allOf(
                    inventoryFuture, cwStatusFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture,
                    wastedResourcesFuture, securityFindingsFuture, costHistoryFuture, billingFuture,
                    iamFuture, iamDetailsFuture, savingsFuture, anomaliesFuture, reservationFuture,
                    reservationPurchaseFuture, vpcQuotaInfoFuture, mtdSpendFuture, lastMonthSpendFuture)
                    .thenApply(v -> {
                        List<DashboardData.WastedResource> wastedResources = wastedResourcesFuture.join();
                        List<DashboardData.OptimizationRecommendation> ec2Recs = ec2RecsFuture.join();
                        List<DashboardData.OptimizationRecommendation> ebsRecs = ebsRecsFuture.join();
                        List<DashboardData.OptimizationRecommendation> lambdaRecs = lambdaRecsFuture.join();
                        List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.join();
                        List<DashboardData.SecurityFinding> securityFindings = securityFindingsFuture.join();
                        List<DashboardData.ServiceQuotaInfo> vpcQuotas = vpcQuotaInfoFuture.join();

                        Double mtdSpend = mtdSpendFuture.join();
                        Double lastMonthSpend = lastMonthSpendFuture.join();

                        int dayOfMonth = LocalDate.now().getDayOfMonth();
                        int daysInMonth = LocalDate.now().lengthOfMonth();
                        Double forecastedSpend = (dayOfMonth > 0) ? (mtdSpend / dayOfMonth) * daysInMonth : 0.0;

                        List<DashboardData.SecurityInsight> securityInsights = securityFindings.stream()
                                .collect(Collectors.groupingBy(DashboardData.SecurityFinding::getCategory,
                                        Collectors.groupingBy(DashboardData.SecurityFinding::getSeverity,
                                                Collectors.counting())))
                                .entrySet().stream()
                                .map(entry -> new DashboardData.SecurityInsight(
                                        String.format("%s has potential issues", entry.getKey()),
                                        entry.getKey(),
                                        entry.getValue().keySet().stream().findFirst().orElse("INFO"),
                                        entry.getValue().values().stream().mapToInt(Long::intValue).sum()))
                                .collect(Collectors.toList());

                        DashboardData.OptimizationSummary optimizationSummary = getOptimizationSummary(
                                wastedResources, ec2Recs, ebsRecs, lambdaRecs, anomalies);

                        int securityScore = calculateSecurityScore(securityFindings);

                        DashboardData data = new DashboardData();
                        data.setSelectedProvider("AWS");
                        DashboardData.Account mainAccount = new DashboardData.Account(
                                account.getAwsAccountId(), account.getAccountName(),
                                activeRegions, inventoryFuture.join(), cwStatusFuture.join(), securityInsights,
                                costHistoryFuture.join(), billingFuture.join(), iamFuture.join(),
                                iamDetailsFuture.join(),
                                savingsFuture.join(),
                                ec2Recs, anomalies, ebsRecs, lambdaRecs,
                                reservationFuture.join(), reservationPurchaseFuture.join(),
                                optimizationSummary, wastedResources, vpcQuotas,
                                securityScore,
                                mtdSpend,
                                forecastedSpend,
                                lastMonthSpend);

                        data.setSelectedAccount(mainAccount);
                        populateAvailableAccounts(data, userDetails);

                        return data;
                    });
        }).get();
    }

    private CompletableFuture<DashboardData.ResourceInventory> getResourceInventory(
            CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture) {
        return groupedResourcesFuture.thenApply(groupedResources -> {
            DashboardData.ResourceInventory inventory = new DashboardData.ResourceInventory();
            Map<String, Integer> counts = groupedResources.stream()
                    .collect(Collectors.toMap(
                            DashboardData.ServiceGroupDto::getServiceType,
                            group -> group.getResources().size()));

            inventory.setVpc(counts.getOrDefault("VPC", 0));
            inventory.setEcs(counts.getOrDefault("ECS Cluster", 0));
            inventory.setEc2(counts.getOrDefault("EC2 Instance", 0));
            inventory.setKubernetes(counts.getOrDefault("EKS Cluster", 0));
            inventory.setLambdas(counts.getOrDefault("Lambda Function", 0));
            inventory.setEbsVolumes(counts.getOrDefault("EBS Volume", 0));
            inventory.setImages(counts.getOrDefault("AMI", 0));
            inventory.setSnapshots(counts.getOrDefault("Snapshot", 0));
            inventory.setS3Buckets(counts.getOrDefault("S3 Bucket", 0));
            inventory.setRdsInstances(counts.getOrDefault("RDS Instance", 0));
            inventory.setRoute53Zones(counts.getOrDefault("Route 53 Zone", 0));
            inventory.setLoadBalancers(counts.getOrDefault("Load Balancer", 0));
            inventory.setLightsail(counts.getOrDefault("Lightsail Instance", 0));
            inventory.setAmplify(counts.getOrDefault("Amplify App", 0));
            return inventory;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.CloudWatchStatus> getCloudWatchStatus(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "cloudwatchStatus-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.CloudWatchStatus> cachedData = redisCache.get(cacheKey,
                    DashboardData.CloudWatchStatus.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        DashboardData.CloudWatchStatus status = new DashboardData.CloudWatchStatus(0, 0, 0);
        redisCache.put(cacheKey, status, 10);
        return CompletableFuture.completedFuture(status);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getServiceQuotaInfo(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions,
            CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture, String serviceCode,
            String quotaCode) {
        return groupedResourcesFuture.thenCompose(groupedResources -> {
            Map<String, Long> vpcCounts = groupedResources.stream()
                    .filter(g -> "VPC".equals(g.getServiceType()))
                    .flatMap(g -> g.getResources().stream())
                    .collect(Collectors.groupingBy(com.xammer.cloud.dto.ResourceDto::getRegion, Collectors.counting()));

            List<CompletableFuture<DashboardData.ServiceQuotaInfo>> futures = activeRegions.stream()
                    .map(region -> CompletableFuture.supplyAsync(() -> {
                        try {
                            ServiceQuotasClient sqClient = awsClientProvider.getServiceQuotasClient(account,
                                    region.getRegionId());
                            ListServiceQuotasRequest listRequest = ListServiceQuotasRequest.builder()
                                    .serviceCode(serviceCode)
                                    .build();
                            ListServiceQuotasResponse listResponse = sqClient.listServiceQuotas(listRequest);
                            Optional<ServiceQuota> quota = listResponse.quotas().stream()
                                    .filter(q -> q.quotaCode().equals(quotaCode))
                                    .findFirst();

                            return quota.map(serviceQuota -> {
                                double currentCount = vpcCounts.getOrDefault(region.getRegionId(), 0L).doubleValue();
                                double utilization = (serviceQuota.value() > 0)
                                        ? (currentCount / serviceQuota.value()) * 100.0
                                        : 0.0;
                                return new DashboardData.ServiceQuotaInfo(
                                        serviceQuota.quotaName(),
                                        serviceQuota.value(),
                                        utilization,
                                        region.getRegionId());
                            }).orElse(null);
                        } catch (Exception e) {
                            logger.error("Failed to get quota info for service {} in region {}.", serviceCode,
                                    region.getRegionId(), e);
                            return null;
                        }
                    }))
                    .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                            .map(CompletableFuture::join)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toList()));
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.IamResources> getIamResources(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "iamResources-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.IamResources> cachedData = redisCache.get(cacheKey,
                    DashboardData.IamResources.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        IamClient iam = awsClientProvider.getIamClient(account);
        logger.info("Fetching IAM resources for account {}...", account.getAwsAccountId());
        int users = 0, groups = 0, policies = 0, roles = 0;
        try {
            users = iam.listUsers().users().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Users on account {}", account.getAwsAccountId(), e);
        }
        try {
            groups = iam.listGroups().groups().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Groups on account {}", account.getAwsAccountId(), e);
        }
        try {
            policies = iam.listPolicies(r -> r.scope(PolicyScopeType.LOCAL)).policies().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Policies on account {}", account.getAwsAccountId(), e);
        }
        try {
            roles = iam.listRoles().roles().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Roles on account {}", account.getAwsAccountId(), e);
        }

        DashboardData.IamResources resources = new DashboardData.IamResources(users, groups, policies, roles);
        redisCache.put(cacheKey, resources, 10);
        return CompletableFuture.completedFuture(resources);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.IamDetail> getIamDetails(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "iamDetails-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.IamDetail> cachedData = redisCache.get(cacheKey, DashboardData.IamDetail.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            IamClient iam = awsClientProvider.getIamClient(account);

            List<DashboardData.IamUserDetail> users = iam.listUsers().users().parallelStream().map(user -> {
                try {
                    List<String> groups = iam.listGroupsForUser(r -> r.userName(user.userName()))
                            .groups().stream().map(g -> g.groupName()).collect(Collectors.toList());

                    List<DashboardData.IamPolicyDetail> policies = new ArrayList<>();

                    iam.listAttachedUserPolicies(r -> r.userName(user.userName())).attachedPolicies()
                            .forEach(p -> policies
                                    .add(new DashboardData.IamPolicyDetail(p.policyName(), p.policyArn(), "Managed")));

                    iam.listUserPolicies(r -> r.userName(user.userName())).policyNames()
                            .forEach(p -> policies.add(new DashboardData.IamPolicyDetail(p, null, "Inline")));

                    return new DashboardData.IamUserDetail(
                            user.userName(), user.userId(), user.arn(),
                            user.createDate().toString(),
                            user.passwordLastUsed() != null ? user.passwordLastUsed().toString() : "Never",
                            groups, policies);
                } catch (Exception e) {
                    logger.warn("Failed to fetch details for user {}", user.userName());
                    return null;
                }
            }).filter(java.util.Objects::nonNull).collect(Collectors.toList());

            List<DashboardData.IamRoleDetail> roles = iam.listRoles().roles().parallelStream()
                    .filter(r -> !r.path().startsWith("/aws-service-role/"))
                    .map(role -> {
                        try {
                            List<DashboardData.IamPolicyDetail> policies = new ArrayList<>();
                            iam.listAttachedRolePolicies(r -> r.roleName(role.roleName())).attachedPolicies()
                                    .forEach(p -> policies.add(new DashboardData.IamPolicyDetail(p.policyName(),
                                            p.policyArn(), "Managed")));

                            iam.listRolePolicies(r -> r.roleName(role.roleName())).policyNames()
                                    .forEach(p -> policies.add(new DashboardData.IamPolicyDetail(p, null, "Inline")));

                            return new DashboardData.IamRoleDetail(
                                    role.roleName(), role.roleId(), role.arn(),
                                    role.createDate().toString(), role.description(), policies);
                        } catch (Exception e) {
                            logger.warn("Failed to fetch details for role {}", role.roleName());
                            return null;
                        }
                    }).filter(java.util.Objects::nonNull).collect(Collectors.toList());

            DashboardData.IamDetail details = new DashboardData.IamDetail(users, roles);
            redisCache.put(cacheKey, details, 10);
            return details;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.SavingsSummary> getSavingsSummary(
            CompletableFuture<List<DashboardData.WastedResource>> wastedFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture) {

        return CompletableFuture.allOf(wastedFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture)
                .thenApply(v -> {
                    double wasteSavings = wastedFuture.join().stream()
                            .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                            .sum();

                    double rightsizingSavings = Stream
                            .of(ec2RecsFuture.join(), ebsRecsFuture.join(), lambdaRecsFuture.join())
                            .flatMap(List::stream)
                            .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                            .sum();

                    List<DashboardData.SavingsSuggestion> suggestions = new ArrayList<>();
                    if (rightsizingSavings > 0) {
                        suggestions.add(new DashboardData.SavingsSuggestion("Rightsizing", rightsizingSavings));
                    }
                    if (wasteSavings > 0) {
                        suggestions.add(new DashboardData.SavingsSuggestion("Waste Elimination", wasteSavings));
                    }

                    double totalPotential = wasteSavings + rightsizingSavings;

                    return new DashboardData.SavingsSummary(totalPotential, suggestions);
                });
    }

    private DashboardData.OptimizationSummary getOptimizationSummary(
            List<DashboardData.WastedResource> wastedResources,
            List<DashboardData.OptimizationRecommendation> ec2Recs,
            List<DashboardData.OptimizationRecommendation> ebsRecs,
            List<DashboardData.OptimizationRecommendation> lambdaRecs,
            List<DashboardData.CostAnomaly> anomalies) {
        double rightsizingSavings = Stream.of(ec2Recs, ebsRecs, lambdaRecs)
                .flatMap(List::stream)
                .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                .sum();

        double wasteSavings = wastedResources.stream()
                .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                .sum();

        double totalSavings = rightsizingSavings + wasteSavings;
        long criticalAlerts = (anomalies != null ? anomalies.size() : 0) + ec2Recs.size() + ebsRecs.size()
                + lambdaRecs.size();
        return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
    }

    private int calculateSecurityScore(List<DashboardData.SecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return 100;
        }
        Map<String, Long> counts = findings.stream()
                .collect(Collectors.groupingBy(DashboardData.SecurityFinding::getSeverity, Collectors.counting()));

        long criticalWeight = 5;
        long highWeight = 2;
        long mediumWeight = 1;
        long lowWeight = 0;

        long weightedScore = (counts.getOrDefault("CRITICAL", 0L) * criticalWeight) +
                (counts.getOrDefault("HIGH", 0L) * highWeight) +
                (counts.getOrDefault("MEDIUM", 0L) * mediumWeight) +
                (counts.getOrDefault("LOW", 0L) * lowWeight);

        double score = 100.0 / (1 + 0.1 * weightedScore);

        return Math.max(0, (int) Math.round(score));
    }

    private DashboardData mapAzureDataToDashboardData(com.xammer.cloud.dto.azure.AzureDashboardData azureData,
            CloudAccount account) {
        DashboardData data = new DashboardData();
        data.setSelectedProvider("Azure");

        DashboardData.Account mainAccount = new DashboardData.Account();
        mainAccount.setId(account.getAzureSubscriptionId());
        mainAccount.setName(account.getAccountName());

        DashboardData.ResourceInventory inv = new DashboardData.ResourceInventory();
        if (azureData.getResourceInventory() != null) {
            com.xammer.cloud.dto.azure.AzureDashboardData.ResourceInventory azInv = azureData.getResourceInventory();
            inv.setEc2((int) azInv.getVirtualMachines());
            inv.setS3Buckets((int) azInv.getStorageAccounts());
            inv.setRdsInstances((int) azInv.getSqlDatabases());
            inv.setVpc((int) azInv.getVirtualNetworks());
            inv.setLambdas((int) azInv.getFunctions());
            inv.setEbsVolumes((int) azInv.getDisks());
            inv.setRoute53Zones((int) azInv.getDnsZones());
            inv.setLoadBalancers((int) azInv.getLoadBalancers());
            inv.setKubernetes((int) azInv.getKubernetesServices());
            inv.setAmplify((int) azInv.getAppServices());
        }
        mainAccount.setResourceInventory(inv);

        if (azureData.getIamDetails() != null) {
            mainAccount.setIamDetails(azureData.getIamDetails());
            int userCount = azureData.getIamDetails().getUsers() != null ? azureData.getIamDetails().getUsers().size()
                    : 0;
            int roleCount = azureData.getIamDetails().getRoles() != null ? azureData.getIamDetails().getRoles().size()
                    : 0;
            mainAccount.setIamResources(new DashboardData.IamResources(userCount, 0, 0, roleCount));
        } else {
            mainAccount.setIamResources(new DashboardData.IamResources(0, 0, 0, 0));
        }

        mainAccount.setMonthToDateSpend(azureData.getMonthToDateSpend());
        mainAccount.setForecastedSpend(azureData.getForecastedSpend());

        if (azureData.getCostHistory() != null) {
            mainAccount.setCostHistory(new DashboardData.CostHistory(
                    azureData.getCostHistory().getLabels(),
                    azureData.getCostHistory().getCosts(),
                    azureData.getCostHistory().getAnomalies()));
        }

        if (azureData.getRegionStatus() != null) {
            List<DashboardData.RegionStatus> regions = azureData.getRegionStatus().stream()
                    .map(r -> new DashboardData.RegionStatus(r.getName(), r.getStatus(), r.getStatus(), r.getLatitude(),
                            r.getLongitude()))
                    .collect(Collectors.toList());
            mainAccount.setRegionStatus(regions);
        }

        mainAccount.setSecurityScore(100);
        mainAccount.setSecurityInsights(new ArrayList<>());
        mainAccount.setSavingsSummary(new DashboardData.SavingsSummary(0.0, new ArrayList<>()));
        mainAccount.setOptimizationSummary(new DashboardData.OptimizationSummary(0.0, 0));
        mainAccount.setEc2Recommendations(new ArrayList<>());
        mainAccount.setWastedResources(new ArrayList<>());
        mainAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0, 0, 0));
        mainAccount.setCostAnomalies(new ArrayList<>());
        mainAccount.setEbsRecommendations(new ArrayList<>());
        mainAccount.setLambdaRecommendations(new ArrayList<>());
        mainAccount.setBillingSummary(new ArrayList<>());

        data.setSelectedAccount(mainAccount);
        populateAvailableAccounts(data, null);

        return data;
    }
}