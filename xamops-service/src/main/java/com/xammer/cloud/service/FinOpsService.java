package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.DashboardData.BudgetDetails;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xammer.cloud.config.multitenancy.TenantContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.budgets.BudgetsClient;
import software.amazon.awssdk.services.budgets.model.*;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.Anomaly;
import software.amazon.awssdk.services.costexplorer.model.AnomalyDateInterval;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.Dimension;
import software.amazon.awssdk.services.costexplorer.model.DimensionValues;
import software.amazon.awssdk.services.costexplorer.model.Expression;
import software.amazon.awssdk.services.costexplorer.model.GetAnomaliesRequest;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinition;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinitionType;
import software.amazon.awssdk.services.costexplorer.model.RootCause;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class FinOpsService {

    private static final Logger logger = LoggerFactory.getLogger(FinOpsService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService;
    private final OptimizationService optimizationService;
    private final List<String> requiredTags;
    private final RedisCacheService redisCache;
    private final UserRepository userRepository;
    private final CostMockService costMockService;
    private final Executor backgroundTaskExecutor;
    private final DashboardDataService dashboardDataService;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final CostService costService;
    private final CostDataSnapshotService snapshotService;
    private final EmailService emailService;
    private final BudgetEmailBuilder budgetEmailBuilder;

    @Value("${aws.costexplorer.mock:false}")
    private boolean useMockData;

    @Autowired
    public FinOpsService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            @Lazy CloudListService cloudListService,
            @Lazy OptimizationService optimizationService,
            @Value("${tagging.compliance.required-tags}") List<String> requiredTags,
            RedisCacheService redisCache,
            UserRepository userRepository,
            CostMockService costMockService,
            @Qualifier("backgroundTaskExecutor") Executor backgroundTaskExecutor,
            @Lazy DashboardDataService dashboardDataService,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            @Lazy CostService costService,
            CostDataSnapshotService snapshotService,
            EmailService emailService,
            BudgetEmailBuilder budgetEmailBuilder) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.cloudListService = cloudListService;
        this.optimizationService = optimizationService;
        this.requiredTags = requiredTags;
        this.redisCache = redisCache;
        this.userRepository = userRepository;
        this.costMockService = costMockService;
        this.backgroundTaskExecutor = backgroundTaskExecutor;
        this.dashboardDataService = dashboardDataService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.costService = costService;
        this.snapshotService = snapshotService;
        this.emailService = emailService;
        this.budgetEmailBuilder = budgetEmailBuilder;

        logger.info("FinOpsService initialized (Mock Mode: {})", useMockData);
    }

    private CloudAccount getAccount(String accountId) {
        // MODIFIED: Handle list of accounts to prevent crash
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }
        return accounts.get(0); // Return the first one found
    }

    /** Returns true if the date string falls within the current calendar month. */
    private boolean isCurrentMonth(String dateStr) {
        try {
            return YearMonth.from(LocalDate.parse(dateStr)).equals(YearMonth.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<FinOpsReportDto> getFinOpsReport(String accountId, boolean forceRefresh, String startDate,
            String endDate) {
        // Build cache key including date range if provided
        // Normalize endDate to end-of-month for current month so key stays stable all month
        final String normalizedEndFinops = (endDate != null && isCurrentMonth(endDate))
                ? YearMonth.from(LocalDate.parse(endDate)).atEndOfMonth().toString()
                : endDate;
        final String cacheKey = (startDate != null && normalizedEndFinops != null)
                ? "finopsReport-" + accountId + "-" + startDate + "-" + normalizedEndFinops
                : "finopsReport-" + accountId;
        final String dbKey = "finops-" + accountId; // stable key used by ProactiveCacheRefreshService

        if (!forceRefresh) {
            // 1. Redis first — fast, no DB connection consumed
            Optional<FinOpsReportDto> cachedData = redisCache.get(cacheKey, FinOpsReportDto.class);
            if (cachedData.isPresent()) {
                logger.info("✅ Redis hit for FinOps report: {}", accountId);
                return CompletableFuture.completedFuture(cachedData.get());
            }
            // 2. DB fallback — only for default (no custom date range) requests
            if (startDate == null && endDate == null) {
                FinOpsReportDto dbData = dashboardDataService.getFinOpsSnapshot(dbKey);
                if (dbData != null) {
                    logger.info("✅ DB hit for FinOps report — repopulating Redis: {}", accountId);
                    redisCache.put(cacheKey, dbData, 60 * 24);
                    return CompletableFuture.completedFuture(dbData);
                }
            }
        }

        CloudAccount account = getAccount(accountId);
        logger.info("Starting FinOps report generation for account {} (dates: {} to {})", accountId, startDate,
                endDate);

        return cloudListService.getRegionStatusForAccount(account, false).thenComposeAsync(activeRegions -> {
            if (activeRegions == null || activeRegions.isEmpty()) {
                logger.warn("[FinOps] ⚠️ No active regions found for account {}. Report may be empty.", accountId);
                return CompletableFuture.completedFuture(null);
            }
            logger.info("[FinOps] 🔍 Found {} active regions for account {}: {}", activeRegions.size(), accountId,
                    activeRegions);

            if (startDate != null && endDate != null) {
                logger.info(
                        "--- LAUNCHING ASYNC DATA FETCH FOR FINOPS REPORT for account {} with date range {} to {} ---",
                        account.getAwsAccountId(), startDate, endDate);
            } else {
                logger.info("--- LAUNCHING ASYNC DATA FETCH FOR FINOPS REPORT for account {}---",
                        account.getAwsAccountId());
            }

            // Per-future timeout: each sub-call gets max 30s.
            // If one slow future (e.g. TaggingCompliance scanning 1000+ resources) misses its
            // deadline it returns an empty/default value instead of blocking the entire allOf().
            final int FUTURE_TIMEOUT_SECONDS = 30;

            CompletableFuture<List<DashboardData.BillingSummary>> billingSummaryFuture = getBillingSummary(account,
                    forceRefresh, startDate, endDate)
                    .orTimeout(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        logger.warn("[FinOps] ⚠️ BillingSummary timed out/failed for {}: {}", accountId, ex.getMessage());
                        return Collections.emptyList();
                    });
            CompletableFuture<List<DashboardData.WastedResource>> wastedResourcesFuture = optimizationService
                    .getWastedResources(account, activeRegions, forceRefresh)
                    .orTimeout(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        logger.warn("[FinOps] ⚠️ WastedResources timed out/failed for {}: {}", accountId, ex.getMessage());
                        return Collections.emptyList();
                    });
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> rightsizingFuture = optimizationService
                    .getAllOptimizationRecommendations(accountId, forceRefresh)
                    .orTimeout(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        logger.warn("[FinOps] ⚠️ Rightsizing timed out/failed for {}: {}", accountId, ex.getMessage());
                        return Collections.emptyList();
                    });
            CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = getCostAnomalies(account,
                    forceRefresh)
                    .orTimeout(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        logger.warn("[FinOps] ⚠️ CostAnomalies timed out/failed for {}: {}", accountId, ex.getMessage());
                        return Collections.emptyList();
                    });
            CompletableFuture<DashboardData.CostHistory> costHistoryFuture = getCostHistory(account, forceRefresh,
                    startDate, endDate)
                    .orTimeout(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        logger.warn("[FinOps] ⚠️ CostHistory timed out/failed for {}: {}", accountId, ex.getMessage());
                        return new DashboardData.CostHistory(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                    });
            CompletableFuture<DashboardData.TaggingCompliance> taggingComplianceFuture = getTaggingCompliance(account,
                    forceRefresh)
                    .orTimeout(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        logger.warn("[FinOps] ⚠️ TaggingCompliance timed out/failed for {}: {}", accountId, ex.getMessage());
                        return new DashboardData.TaggingCompliance(0.0, 0, 0, Collections.emptyList());
                    });
            CompletableFuture<List<BudgetDetails>> budgetsFuture = getAccountBudgets(account, forceRefresh)
                    .orTimeout(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        logger.warn("[FinOps] ⚠️ Budgets timed out/failed for {}: {}", accountId, ex.getMessage());
                        return Collections.emptyList();
                    });
            CompletableFuture<List<Map<String, Object>>> costByRegionFuture = getCostByRegion(account, forceRefresh,
                    startDate, endDate)
                    .orTimeout(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        logger.warn("[FinOps] ⚠️ CostByRegion timed out/failed for {}: {}", accountId, ex.getMessage());
                        return Collections.emptyList();
                    });

            return CompletableFuture
                    .allOf(billingSummaryFuture, wastedResourcesFuture, rightsizingFuture, anomaliesFuture,
                            costHistoryFuture, taggingComplianceFuture, budgetsFuture, costByRegionFuture)
                    .thenApply(v -> {
                        logger.info("[FinOps] 🏁 --- ALL ASYNC DATA FETCHES COMPLETE FOR ACCOUNT {} ---", accountId);

                        // Debugging sub-task completion state
                        logger.debug(
                                "[FinOps] Task Status - Billing: {}, Wasted: {}, Rightsizing: {}, Anomalies: {}, CostHistory: {}, Tagging: {}, Budgets: {}, CostByRegion: {}",
                                billingSummaryFuture.isDone(), wastedResourcesFuture.isDone(),
                                rightsizingFuture.isDone(),
                                anomaliesFuture.isDone(), costHistoryFuture.isDone(), taggingComplianceFuture.isDone(),
                                budgetsFuture.isDone(), costByRegionFuture.isDone());

                        List<DashboardData.BillingSummary> billingSummary = billingSummaryFuture.join();
                        DashboardData.CostHistory costHistory = costHistoryFuture.join();

                        double mtdSpend = billingSummary.stream()
                                .mapToDouble(DashboardData.BillingSummary::getMonthToDateCost).sum();
                        double lastMonthSpend = 0.0;
                        if (costHistory.getLabels() != null && costHistory.getLabels().size() > 1) {
                            int lastMonthIndex = costHistory.getLabels().size() - 2;
                            if (lastMonthIndex >= 0 && lastMonthIndex < costHistory.getCosts().size()) {
                                lastMonthSpend = costHistory.getCosts().get(lastMonthIndex);
                            }
                        }
                        double daysInMonth = YearMonth.now().lengthOfMonth();
                        double currentDayOfMonth = LocalDate.now().getDayOfMonth();
                        double forecastedSpend = (currentDayOfMonth > 0) ? (mtdSpend / currentDayOfMonth) * daysInMonth
                                : 0;
                        double rightsizingSavings = rightsizingFuture.join().stream()
                                .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                                .sum();
                        double wasteSavings = wastedResourcesFuture.join().stream()
                                .filter(DashboardData.WastedResource::isBillableWaste)
                                .mapToDouble(DashboardData.WastedResource::getMonthlySavings).sum();
                        double totalPotentialSavings = rightsizingSavings + wasteSavings;

                        FinOpsReportDto.Kpis kpis = new FinOpsReportDto.Kpis(mtdSpend, lastMonthSpend, forecastedSpend,
                                totalPotentialSavings);

                        List<Map<String, Object>> costByService = billingSummary.stream()
                                .sorted(Comparator.comparingDouble(DashboardData.BillingSummary::getMonthToDateCost)
                                        .reversed())
                                .limit(10).map(s -> Map.<String, Object>of("service", s.getServiceName(), "cost",
                                        s.getMonthToDateCost()))
                                .collect(Collectors.toList());

                        FinOpsReportDto.CostBreakdown costBreakdown = new FinOpsReportDto.CostBreakdown(costByService,
                                costByRegionFuture.join());

                        FinOpsReportDto report = new FinOpsReportDto(kpis, costBreakdown, rightsizingFuture.join(),
                                wastedResourcesFuture.join(), anomaliesFuture.join(), taggingComplianceFuture.join(),
                                budgetsFuture.join());
                        redisCache.put(cacheKey, report, 60 * 24);
                        // Save to DB for default (no custom date range) requests — warm for post-deploy cold starts
                        if (startDate == null && endDate == null) {
                            dashboardDataService.saveFinOpsSnapshot(dbKey, report);
                        }
                        logger.info(
                                "[FinOps] ✅ Report generated and cached successfully for account {} (Spend: ${}, Forecast: ${})",
                                accountId, String.format("%.2f", mtdSpend), String.format("%.2f", forecastedSpend));
                        return report;
                    });
        }, backgroundTaskExecutor).exceptionally(ex -> {
            logger.error("Failed to generate FinOps report for account {} (dates: {} to {}): {}",
                    accountId, startDate, endDate, ex.getMessage(), ex);
            return null;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<BudgetDetails>> getAccountBudgets(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "budgets-" + account.getAwsAccountId();
        String dbKey = "FINOPS_BUDGETS::" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<BudgetDetails>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    List<BudgetDetails> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCache.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", dbKey);
                        return CompletableFuture.completedFuture(dbData);
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", dbKey, dbEx.getMessage());
            }
        }

        // Return mock data if mock mode is enabled
        if (useMockData) {
            List<BudgetDetails> mockResult = costMockService.getMockAccountBudgets(account);
            redisCache.put(cacheKey, mockResult, 60 * 24);
            return CompletableFuture.completedFuture(mockResult);
        }

        BudgetsClient budgetsClient = awsClientProvider.getBudgetsClient(account);
        logger.info("FinOps Scan: Fetching account budgets for account {}...", account.getAwsAccountId());
        try {
            DescribeBudgetsRequest request = DescribeBudgetsRequest.builder().accountId(account.getAwsAccountId())
                    .build();
            List<Budget> budgets = budgetsClient.describeBudgets(request).budgets();
            List<BudgetDetails> result = budgets.stream().map(b -> {
                BudgetDetails details = new BudgetDetails(
                    b.budgetName(), b.budgetLimit().amount(), b.budgetLimit().unit(),
                    b.calculatedSpend() != null ? b.calculatedSpend().actualSpend().amount() : BigDecimal.ZERO,
                    b.calculatedSpend() != null && b.calculatedSpend().forecastedSpend() != null
                            ? b.calculatedSpend().forecastedSpend().amount()
                            : BigDecimal.ZERO);
                // Extract service filter if present
                if (b.hasCostFilters() && b.costFilters().containsKey("Service")) {
                    List<String> services = b.costFilters().get("Service");
                    if (services != null && !services.isEmpty()) {
                        details.setServiceName(services.get(0));
                    }
                }
                return details;
            }).collect(Collectors.toList());
            redisCache.put(cacheKey, result, 60 * 24);
            try {
                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                snapshot.setCacheKey(dbKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
            }
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Failed to fetch AWS Budgets for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    /**
     * Lightweight budget lookup by accountId string — for notification endpoints.
     * Handles account lookup internally and delegates to the cached getAccountBudgets().
     * Much cheaper than the full FinOps report (1 AWS call vs 8 parallel calls).
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.BudgetDetails>> getBudgets(
            String accountId, boolean forceRefresh) {
        return getAccountBudgets(getAccount(accountId), forceRefresh);
    }

    // -------------------------------------------------------------------------
    // Per-section public wrappers — take accountId string, used by individual
    // controller endpoints so each FinOps page section can be fetched independently.
    // -------------------------------------------------------------------------

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.BillingSummary>> getBillingSummaryById(
            String accountId, boolean forceRefresh, String startDate, String endDate) {
        return getBillingSummary(getAccount(accountId), forceRefresh, startDate, endDate);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.CostHistory> getCostHistoryById(
            String accountId, boolean forceRefresh, String startDate, String endDate) {
        return getCostHistory(getAccount(accountId), forceRefresh, startDate, endDate);
    }

    /**
     * Returns the previous calendar month's total spend, independent of any
     * date filter applied to the Cost Explorer view. Delegates to the same
     * CostService method used by the Account Dashboard so both pages report the
     * same "Last Month Spend" value.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<Double> getLastMonthSpendById(String accountId, boolean forceRefresh) {
        CloudAccount account = getAccount(accountId);
        return costService.getLastMonthSpend(account.getAwsAccountId(), forceRefresh);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<Map<String, Object>>> getCostByRegionById(
            String accountId, boolean forceRefresh, String startDate, String endDate) {
        return getCostByRegion(getAccount(accountId), forceRefresh, startDate, endDate);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.CostAnomaly>> getCostAnomaliesById(
            String accountId, boolean forceRefresh) {
        return getCostAnomalies(getAccount(accountId), forceRefresh);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.TaggingCompliance> getTaggingComplianceById(
            String accountId, boolean forceRefresh) {
        return getTaggingCompliance(getAccount(accountId), forceRefresh);
    }

    /**
     * Wasted resources wrapper — resolves active regions first, then scans.
     * Both steps are already individually Redis-cached so warm calls are fast.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.WastedResource>> getWastedResourcesById(
            String accountId, boolean forceRefresh) {
        CloudAccount account = getAccount(accountId);
        return cloudListService.getRegionStatusForAccount(account, forceRefresh)
                .thenComposeAsync(activeRegions -> {
                    logger.debug("[FinOps] wasted-resources compose callback — account={} tenant={} thread={} regions={}",
                            accountId,
                            org.slf4j.MDC.get("tenant"),
                            Thread.currentThread().getName(),
                            activeRegions != null ? activeRegions.size() : 0);
                    return optimizationService.getWastedResources(account, activeRegions, forceRefresh);
                }, TenantContextUtil.tenantAware(backgroundTaskExecutor));
    }

    /** Rightsizing wrapper — delegates directly to the cached OptimizationService method. */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getRightsizingById(
            String accountId, boolean forceRefresh) {
        return optimizationService.getAllOptimizationRecommendations(accountId, forceRefresh);
    }

    /**
     * Creates a budget, optionally adding email notifications if the user has an
     * email configured.
     *
     * @return The created BudgetDetails object, populated with spends as 0.
     */
    public BudgetDetails createBudget(String accountId, DashboardData.BudgetDetails budgetDetails, String username) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for createBudget");
            String mockResult = costMockService.getMockCreateBudget(
                    budgetDetails.getBudgetName(),
                    budgetDetails.getBudgetLimit().doubleValue());
            logger.info("[MOCK] {}", mockResult);

            // Return the DTO with zero spend for mock
            budgetDetails.setActualSpend(BigDecimal.ZERO);
            budgetDetails.setForecastedSpend(BigDecimal.ZERO);

            // Evict caches to force a refresh on next load
            redisCache.evict("budgets-" + accountId);
            redisCache.evict("finopsReport-" + accountId);

            return budgetDetails;
        }

        CloudAccount account = getAccount(accountId);
        BudgetsClient budgetsClient = awsClientProvider.getBudgetsClient(account);

        // Find the user in the database to get their email.
        // Users log in with email as username, so fall back to username itself if email field is blank.
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        String userEmail = (user.getEmail() != null && !user.getEmail().isBlank())
                ? user.getEmail() : username;

        try {
            Instant startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Budget.Builder budgetBuilder = Budget.builder()
                    .budgetName(budgetDetails.getBudgetName()).budgetType(BudgetType.COST).timeUnit(software.amazon.awssdk.services.budgets.model.TimeUnit.MONTHLY)
                    .timePeriod(TimePeriod.builder().start(startOfMonth).build())
                    .budgetLimit(Spend.builder().amount(budgetDetails.getBudgetLimit())
                            .unit(budgetDetails.getBudgetUnit()).build());

            // Add service filter if a specific service is requested
            if (budgetDetails.getServiceName() != null && !budgetDetails.getServiceName().isBlank()) {
                budgetBuilder.costFilters(Map.of("Service", Collections.singletonList(budgetDetails.getServiceName())));
                logger.info("Creating per-service budget '{}' for service '{}' on account {}",
                        budgetDetails.getBudgetName(), budgetDetails.getServiceName(), account.getAwsAccountId());
            }

            Budget budget = budgetBuilder.build();

            CreateBudgetRequest.Builder requestBuilder = CreateBudgetRequest.builder()
                    .accountId(account.getAwsAccountId())
                    .budget(budget);

            // Only add notifications if the user's email is present and not blank
            if (userEmail != null && !userEmail.isBlank()) {
                logger.info("Creating new budget: {} for account {} (with notifications to {})",
                        budgetDetails.getBudgetName(), account.getAwsAccountId(), userEmail);

                Subscriber emailSubscriber = Subscriber.builder()
                        .subscriptionType(SubscriptionType.EMAIL)
                        .address(userEmail)
                        .build();

                // Actual spend thresholds: 50%, 80%, 100%
                List<NotificationWithSubscribers> notifications = new java.util.ArrayList<>();
                for (double threshold : new double[]{50.0, 80.0, 100.0}) {
                    notifications.add(NotificationWithSubscribers.builder()
                            .notification(Notification.builder()
                                    .notificationType(NotificationType.ACTUAL)
                                    .comparisonOperator(ComparisonOperator.GREATER_THAN)
                                    .threshold(threshold)
                                    .thresholdType(ThresholdType.PERCENTAGE)
                                    .build())
                            .subscribers(emailSubscriber)
                            .build());
                }

                // Forecasted spend threshold: 100% — alerts before you actually overspend
                notifications.add(NotificationWithSubscribers.builder()
                        .notification(Notification.builder()
                                .notificationType(NotificationType.FORECASTED)
                                .comparisonOperator(ComparisonOperator.GREATER_THAN)
                                .threshold(100.0)
                                .thresholdType(ThresholdType.PERCENTAGE)
                                .build())
                        .subscribers(emailSubscriber)
                        .build());

                requestBuilder.notificationsWithSubscribers(notifications);
            } else {
                // If no email, just log the creation without notification info
                logger.info("Creating new budget: {} for account {} (no notification email found for user {})",
                        budgetDetails.getBudgetName(), account.getAwsAccountId(), username);
            }

            budgetsClient.createBudget(requestBuilder.build());

            // Evict caches to force a refresh on next load
            redisCache.evict("budgets-" + accountId);
            redisCache.evict("finopsReport-" + accountId);

            // Frontend computes actualSpend from existing cost breakdown data,
            // so no need for an extra Cost Explorer call here.
            budgetDetails.setActualSpend(BigDecimal.ZERO);
            budgetDetails.setForecastedSpend(BigDecimal.ZERO);

            // Send XamOps confirmation email with real spend values
            if (userEmail != null && !userEmail.isBlank()) {
                try {
                    String accountName = account.getAccountName() != null ? account.getAccountName() : account.getAwsAccountId();
                    String html = budgetEmailBuilder.buildBudgetCreatedEmail(budgetDetails, accountName, accountId);
                    emailService.sendHtmlEmail(userEmail,
                            "Budget Created: " + budgetDetails.getBudgetName(), html);
                    logger.info("Budget creation confirmation email sent to {}", userEmail);
                } catch (Exception emailEx) {
                    logger.warn("Budget created but confirmation email failed for {}: {}", userEmail, emailEx.getMessage());
                }
            }

            return budgetDetails;

        } catch (software.amazon.awssdk.services.budgets.model.DuplicateRecordException dupEx) {
            logger.warn("Budget '{}' already exists for account {} — returning existing budget info",
                    budgetDetails.getBudgetName(), account.getAwsAccountId());
            // Frontend computes actualSpend from existing cost data — no CE call needed
            budgetDetails.setActualSpend(BigDecimal.ZERO);
            budgetDetails.setForecastedSpend(BigDecimal.ZERO);
            return budgetDetails;
        } catch (software.amazon.awssdk.services.budgets.model.AccessDeniedException accessEx) {
            logger.warn("AWS Budget write permission denied for account {} — falling back to local budget storage. " +
                    "The IAM role does not have budgets:ModifyBudget permission.", account.getAwsAccountId());
            // Fall back: return the budget DTO with zero spend (local-only budget)
            budgetDetails.setActualSpend(BigDecimal.ZERO);
            budgetDetails.setForecastedSpend(BigDecimal.ZERO);

            // Evict caches so next load picks up the change
            redisCache.evict("budgets-" + accountId);
            redisCache.evict("finopsReport-" + accountId);

            return budgetDetails;
        } catch (Exception e) {
            logger.error("Failed to create AWS Budget '{}' for account {}", budgetDetails.getBudgetName(),
                    account.getAwsAccountId(), e);
            throw new RuntimeException("Failed to create budget", e);
        }
    }

    public void deleteBudget(String accountId, String budgetName) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for deleteBudget");
            String mockResult = costMockService.getMockDeleteBudget(budgetName);
            logger.info("[MOCK] {}", mockResult);

            // Evict caches to force a refresh on next load
            redisCache.evict("budgets-" + accountId);
            redisCache.evict("finopsReport-" + accountId);
            return;
        }

        CloudAccount account = getAccount(accountId);
        BudgetsClient budgetsClient = awsClientProvider.getBudgetsClient(account);
        logger.info("Deleting budget: {} for account {}", budgetName, accountId);
        try {
            DeleteBudgetRequest request = DeleteBudgetRequest.builder()
                    .accountId(accountId)
                    .budgetName(budgetName)
                    .build();
            budgetsClient.deleteBudget(request);
        } catch (NotFoundException e) {
            logger.warn("Budget '{}' not found for account {}. It may have been already deleted.", budgetName,
                    accountId);
        } catch (Exception e) {
            logger.error("Failed to delete budget '{}' for account {}", budgetName, accountId, e);
            throw new RuntimeException("Failed to delete budget", e);
        } finally {
            redisCache.evict("budgets-" + accountId);
            redisCache.evict("finopsReport-" + accountId);
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.TaggingCompliance> getTaggingCompliance(CloudAccount account,
            boolean forceRefresh) {
        String cacheKey = "taggingCompliance-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.TaggingCompliance> cachedData = redisCache.get(cacheKey,
                    DashboardData.TaggingCompliance.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        logger.info("FinOps Scan: Checking tagging compliance for account {}...", account.getAwsAccountId());
        return cloudListService.getAllResources(account, forceRefresh).thenApply(allResources -> {
            List<DashboardData.UntaggedResource> untaggedList = new ArrayList<>();
            int taggedCount = 0;

            for (ResourceDto resource : allResources) {
                List<String> missingTags = new ArrayList<>(this.requiredTags);
                Map<String, String> resourceTags = new HashMap<>();
                if (resource.getDetails() != null) {
                    resource.getDetails().forEach((key, value) -> {
                        if (key.toLowerCase().startsWith("tag:")) {
                            resourceTags.put(key.substring(4), value);
                        }
                    });
                }
                missingTags.removeAll(resourceTags.keySet());
                if (missingTags.isEmpty()) {
                    taggedCount++;
                } else {
                    untaggedList.add(new DashboardData.UntaggedResource(resource.getId(), resource.getType(),
                            resource.getRegion(), missingTags));
                }
            }

            int totalScanned = allResources.size();
            double percentage = (totalScanned > 0) ? ((double) taggedCount / totalScanned) * 100.0 : 100.0;
            DashboardData.TaggingCompliance result = new DashboardData.TaggingCompliance(percentage, totalScanned,
                    untaggedList.size(), untaggedList);
            redisCache.put(cacheKey, result, 60 * 24);
            return result;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<Map<String, Object>>> getCostByTag(String accountId, String tagKey,
            boolean forceRefresh) {
        String cacheKey = "costByTag-" + accountId + "-" + tagKey;
        String dbKey = "FINOPS_COST_TAG::" + accountId + "::" + tagKey;
        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCache.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", dbKey);
                        return CompletableFuture.completedFuture(dbData);
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", dbKey, dbEx.getMessage());
            }
        }

        if (tagKey == null || tagKey.isBlank())
            return CompletableFuture.completedFuture(Collections.emptyList());

        // Return mock data if mock mode is enabled
        if (useMockData) {
            List<Map<String, Object>> mockResult = costMockService.getMockCostByTag(accountId, tagKey);
            redisCache.put(cacheKey, mockResult, 60 * 24);
            return CompletableFuture.completedFuture(mockResult);
        }

        CloudAccount account = getAccount(accountId);
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching month-to-date cost by tag: {} for account {}", tagKey, accountId);
        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString())
                            .end(LocalDate.now().plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY).metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.TAG).key(tagKey).build()).build();

            List<Map<String, Object>> result = ce.getCostAndUsage(request).resultsByTime()
                    .stream().flatMap(r -> r.groups().stream())
                    .map(g -> {
                        String tagValue = g.keys().get(0).isEmpty() ? "Untagged" : g.keys().get(0);
                        double cost = Double.parseDouble(g.metrics().get("UnblendedCost").amount());
                        return Map.<String, Object>of("tagValue", tagValue, "cost", cost);
                    })
                    .filter(map -> (double) map.get("cost") > 0.01)
                    .collect(Collectors.toList());
            redisCache.put(cacheKey, result, 60 * 24);
            try {
                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                snapshot.setCacheKey(dbKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
            }
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error(
                    "Could not fetch cost by tag key '{}' for account {}. This tag may not be activated in the billing console.",
                    tagKey, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<Map<String, Object>>> getCostByRegion(CloudAccount account, boolean forceRefresh,
            String startDate, String endDate) {
        boolean isMtdOrFullMonthQuery = startDate == null
                || LocalDate.parse(startDate).getDayOfMonth() == 1;
        String normalizedEndRegion = (endDate != null && isCurrentMonth(endDate) && isMtdOrFullMonthQuery)
                ? YearMonth.from(LocalDate.parse(endDate)).atEndOfMonth().toString()
                : endDate;
        String cacheKey = "costByRegion-" + account.getAwsAccountId();
        String dbKey = "FINOPS_COST_REGION::" + account.getAwsAccountId();
        if (startDate != null && normalizedEndRegion != null) {
            cacheKey += "-" + startDate + "-" + normalizedEndRegion;
            dbKey += "::" + startDate + "::" + normalizedEndRegion;
        }

        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCache.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", dbKey);
                        return CompletableFuture.completedFuture(dbData);
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", dbKey, dbEx.getMessage());
            }
        }

        // Return mock data if mock mode is enabled
        if (useMockData) {
            List<Map<String, Object>> mockResult = costMockService.getMockCostByRegion(account, startDate, endDate);
            redisCache.put(cacheKey, mockResult, 60 * 24);
            return CompletableFuture.completedFuture(mockResult);
        }

        // ── Closed-month snapshot: use AWS_MONTHLY_BREAKDOWN_RGN ─────────────────
        // When the request covers exactly one closed month, serve region breakdown
        // from the permanent monthly snapshot instead of calling CE API.
        if (startDate != null && endDate != null) {
            try {
                YearMonth startYm = YearMonth.from(LocalDate.parse(startDate));
                YearMonth endYm   = YearMonth.from(LocalDate.parse(endDate));
                if (startYm.equals(endYm) && CostDataSnapshotService.isClosedMonth(startYm)) {
                    Optional<List<CostDto>> snap = snapshotService.readBreakdownRgn(account.getAwsAccountId(), startYm);
                    if (snap.isPresent()) {
                        List<Map<String, Object>> result = snap.get().stream()
                                .map(c -> {
                                    Map<String, Object> m = new HashMap<>();
                                    m.put("region", c.getName());
                                    m.put("cost", c.getAmount());
                                    return m;
                                })
                                .filter(m -> (double) m.get("cost") > 0.01)
                                .collect(Collectors.toList());
                        logger.info("✅ getCostByRegion snapshot hit for {} / {} — {} regions",
                                account.getAwsAccountId(), startYm, result.size());
                        redisCache.put(cacheKey, result, 60 * 24);
                        return CompletableFuture.completedFuture(result);
                    }
                }
            } catch (Exception snapEx) {
                logger.debug("getCostByRegion snapshot check failed: {}", snapEx.getMessage());
            }
        }

        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

        // Determine date range and granularity
        String start, end;
        Granularity granularity;
        if (startDate != null && endDate != null) {
            start = startDate;
            end = LocalDate.parse(endDate).plusDays(1).toString(); // Add one day to include end date
            granularity = Granularity.DAILY; // Use DAILY for custom date ranges
            logger.info("Fetching cost by region for account {} from {} to {} (DAILY)...", account.getAwsAccountId(),
                    start, end);
        } else {
            start = LocalDate.now().withDayOfMonth(1).toString();
            end = LocalDate.now().plusDays(1).toString();
            granularity = Granularity.MONTHLY; // Use MONTHLY for month-to-date
            logger.info("Fetching month-to-date cost by region for account {} (MONTHLY)...", account.getAwsAccountId());
        }

        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(start).end(end).build())
                    .granularity(granularity)
                    .metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("REGION").build())
                    .build();

            // Aggregate results across all time periods and group by region
            Map<String, Double> regionCostMap = new HashMap<>();
            ce.getCostAndUsage(request).resultsByTime().forEach(timePeriod -> {
                timePeriod.groups().forEach(group -> {
                    String region = group.keys().get(0).isEmpty() ? "Unknown" : group.keys().get(0);
                    double cost = Double.parseDouble(group.metrics().get("UnblendedCost").amount());
                    regionCostMap.merge(region, cost, Double::sum);
                });
            });

            List<Map<String, Object>> result = regionCostMap.entrySet().stream()
                    .map(e -> Map.<String, Object>of("region", e.getKey(), "cost", e.getValue()))
                    .filter(map -> (double) map.get("cost") > 0.01)
                    .collect(Collectors.toList());

            logger.info("Fetched {} regions with costs for account {}", result.size(), account.getAwsAccountId());
            redisCache.put(cacheKey, result, 60 * 24);
            try {
                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                snapshot.setCacheKey(dbKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
            }
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Could not fetch cost by region for account {} (dates: {} to {}): {}",
                    account.getAwsAccountId(), start, end, e.getMessage(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.BillingSummary>> getBillingSummary(CloudAccount account,
            boolean forceRefresh, String startDate, String endDate) {
        // Normalise current-month end date to end-of-month for cache-key stability, but ONLY
        // for the default MTD view (startDate null) or a standard month-start query (startDate = 1st).
        // Comparison queries pass a mid-month startDate (e.g. Jun 8) and must NOT be stretched
        // to end-of-month, otherwise they silently return 3+ weeks of data instead of 7 days.
        boolean isMtdOrFullMonthQuery = startDate == null
                || LocalDate.parse(startDate).getDayOfMonth() == 1;
        String normalizedEnd = (endDate != null && isCurrentMonth(endDate) && isMtdOrFullMonthQuery)
                ? YearMonth.from(LocalDate.parse(endDate)).atEndOfMonth().toString()
                : endDate;

        // Use end-of-month as the effective end so the shared getCostBreakdown cache key
        // is stable for the whole month regardless of when the request arrives.
        String effectiveStartDate = startDate != null ? startDate : LocalDate.now().withDayOfMonth(1).toString();
        String effectiveEndDate = normalizedEnd != null ? normalizedEnd : YearMonth.now().atEndOfMonth().toString();

        if (useMockData) {
            return CompletableFuture.completedFuture(
                    costMockService.getMockBillingSummary(account, startDate, endDate));
        }

        // Single source of truth: getCostBreakdown("SERVICE") owns the cache
        // (Redis key "costBreakdown-{id}-SERVICE--{start}-{end}", DB key
        // "AWS_COST_BREAKDOWN::{id}::SERVICE::..."). Dashboard, Cost, and FinOps pages all
        // read from the same key, so a force-refresh or nightly warm-up on any one page
        // keeps all three in sync automatically.
        logger.info("[BillingSummary] Delegating to getCostBreakdown for account={} ({} to {})",
                account.getAwsAccountId(), effectiveStartDate, effectiveEndDate);

        try {
            List<CostDto> breakdown = costService
                    .getCostBreakdown(account.getAwsAccountId(), "SERVICE", null, forceRefresh,
                            effectiveStartDate, effectiveEndDate)
                    .join();

            List<DashboardData.BillingSummary> result = breakdown.stream()
                    .map(dto -> new DashboardData.BillingSummary(dto.getName(), dto.getAmount()))
                    .collect(Collectors.toList());

            logger.info("[BillingSummary] {} services returned for account={}",
                    result.size(), account.getAwsAccountId());
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("[BillingSummary] Could not fetch for account={}: {}",
                    account.getAwsAccountId(), e.getMessage(), e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.CostHistory> getCostHistory(CloudAccount account, boolean forceRefresh,
            String startDate, String endDate) {
        boolean isMtdOrFullMonthQuery = startDate == null
                || LocalDate.parse(startDate).getDayOfMonth() == 1;
        String normalizedEndHistory = (endDate != null && isCurrentMonth(endDate) && isMtdOrFullMonthQuery)
                ? YearMonth.from(LocalDate.parse(endDate)).atEndOfMonth().toString()
                : endDate;
        String cacheKey = "costHistory-" + account.getAwsAccountId();
        String dbKey = "FINOPS_COST_HISTORY::" + account.getAwsAccountId();
        if (startDate != null && normalizedEndHistory != null) {
            cacheKey += "-" + startDate + "-" + normalizedEndHistory;
            dbKey += "::" + startDate + "::" + normalizedEndHistory;
        }

        if (!forceRefresh) {
            Optional<DashboardData.CostHistory> cachedData = redisCache.get(cacheKey, DashboardData.CostHistory.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    DashboardData.CostHistory dbData = objectMapper.readValue(dbCached.get().getJsonData(), DashboardData.CostHistory.class);
                    if (dbData != null) {
                        redisCache.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", dbKey);
                        return CompletableFuture.completedFuture(dbData);
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", dbKey, dbEx.getMessage());
            }
        }

        // Return mock data if mock mode is enabled
        if (useMockData) {
            DashboardData.CostHistory mockResult = costMockService.getMockCostHistory(account, startDate, endDate);
            redisCache.put(cacheKey, mockResult, 60 * 24);
            return CompletableFuture.completedFuture(mockResult);
        }

        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        List<Boolean> anomalies = new ArrayList<>();

        try {
            // If custom date range is provided, show daily breakdown; otherwise show
            // monthly history
            if (startDate != null && endDate != null) {
                logger.info("Fetching daily cost history for account {} from {} to {}...", account.getAwsAccountId(),
                        startDate, endDate);

                LocalDate start = LocalDate.parse(startDate);
                LocalDate end = LocalDate.parse(endDate);

                GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                        .timePeriod(
                                DateInterval.builder().start(start.toString()).end(end.plusDays(1).toString()).build())
                        .granularity(Granularity.DAILY)
                        .metrics("UnblendedCost")
                        .build();

                ce.getCostAndUsage(req).resultsByTime().forEach(result -> {
                    labels.add(
                            LocalDate.parse(result.timePeriod().start()).format(DateTimeFormatter.ofPattern("MMM dd")));
                    double cost = Double.parseDouble(result.total().get("UnblendedCost").amount());
                    costs.add(cost);
                    anomalies.add(false);
                });
            } else {
                // ── Snapshot-first: compose 5 closed months from DB, only hit CE for current ──
                boolean snapResolved = false;
                try {
                    String acctId = account.getAwsAccountId();
                    YearMonth currentYm = YearMonth.now();
                    List<Double> snapMonthCosts = new ArrayList<>();
                    boolean allPresent = true;
                    for (int i = 5; i >= 1; i--) {
                        YearMonth ym = currentYm.minusMonths(i);
                        Optional<Double> snapTotal = snapshotService.readMonthlyTotal(acctId, ym);
                        if (snapTotal.isPresent()) {
                            snapMonthCosts.add(snapTotal.get());
                        } else {
                            allPresent = false;
                            break;
                        }
                    }
                    if (allPresent && snapMonthCosts.size() == 5) {
                        // Fetch only current (open) month from CE API
                        double[] curCostArr = {0.0};
                        try {
                            LocalDate curStart = currentYm.atDay(1);
                            LocalDate curEnd   = currentYm.atEndOfMonth().plusDays(1);
                            GetCostAndUsageRequest curReq = GetCostAndUsageRequest.builder()
                                    .timePeriod(DateInterval.builder()
                                            .start(curStart.toString()).end(curEnd.toString()).build())
                                    .granularity(Granularity.MONTHLY).metrics("UnblendedCost").build();
                            ce.getCostAndUsage(curReq).resultsByTime().forEach(r ->
                                    curCostArr[0] += Double.parseDouble(
                                            r.total().get("UnblendedCost").amount()));
                        } catch (Exception ceEx) {
                            logger.warn("[FinOps] CE current-month fetch failed, using 0.0: {}", ceEx.getMessage());
                        }
                        // Build labels: months -5 … -1 then current
                        for (int i = 5; i >= 1; i--) {
                            labels.add(currentYm.minusMonths(i).atDay(1)
                                    .format(DateTimeFormatter.ofPattern("MMM uuuu")));
                        }
                        labels.add(currentYm.atDay(1).format(DateTimeFormatter.ofPattern("MMM uuuu")));
                        List<Double> allCosts2 = new ArrayList<>(snapMonthCosts);
                        allCosts2.add(curCostArr[0]);
                        for (int i = 0; i < allCosts2.size(); i++) {
                            double cur = allCosts2.get(i);
                            costs.add(cur);
                            boolean isAnomaly = false;
                            if (i > 0 && allCosts2.get(i - 1) > 100) {
                                double changePercent = ((cur - allCosts2.get(i - 1)) / allCosts2.get(i - 1)) * 100;
                                if (changePercent > 20) isAnomaly = true;
                            }
                            anomalies.add(isAnomaly);
                        }
                        snapResolved = true;
                        logger.info("✅ getCostHistory snapshot-composed for account={}", acctId);
                    }
                } catch (Exception snapEx) {
                    logger.warn("[FinOps] Snapshot composition for getCostHistory failed, falling back to CE: {}",
                            snapEx.getMessage());
                }

                if (!snapResolved) {
                    logger.info("Fetching 6-month cost history for account {} (single batched call)...",
                            account.getAwsAccountId());
                    LocalDate startRange = LocalDate.now().minusMonths(5).withDayOfMonth(1);
                    LocalDate endRange = LocalDate.now().plusDays(1);
                    GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                            .timePeriod(DateInterval.builder()
                                    .start(startRange.toString()).end(endRange.toString()).build())
                            .granularity(Granularity.MONTHLY).metrics("UnblendedCost").build();

                    List<Double> allCosts = new ArrayList<>();
                    ce.getCostAndUsage(req).resultsByTime().forEach(result -> {
                        LocalDate monthStart = LocalDate.parse(result.timePeriod().start());
                        labels.add(monthStart.format(DateTimeFormatter.ofPattern("MMM uuuu")));
                        allCosts.add(Double.parseDouble(result.total().get("UnblendedCost").amount()));
                    });

                    for (int i = 0; i < allCosts.size(); i++) {
                        double currentCost = allCosts.get(i);
                        costs.add(currentCost);
                        boolean isAnomaly = false;
                        if (i > 0) {
                            double previousCost = allCosts.get(i - 1);
                            if (previousCost > 100) {
                                double changePercent = ((currentCost - previousCost) / previousCost) * 100;
                                if (changePercent > 20) isAnomaly = true;
                            }
                        }
                        anomalies.add(isAnomaly);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Could not fetch cost history for account {}", account.getAwsAccountId(), e);
        }

        DashboardData.CostHistory result = new DashboardData.CostHistory(labels, costs, anomalies);
        redisCache.put(cacheKey, result, 60 * 24);
        try {
            CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            snapshot.setCacheKey(dbKey);
            snapshot.setJsonData(objectMapper.writeValueAsString(result));
            snapshot.setLastUpdated(java.time.LocalDateTime.now());
            cachedDataRepository.save(snapshot);
        } catch (Exception dbEx) {
            logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
        }
        return CompletableFuture.completedFuture(result);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.CostAnomaly>> getCostAnomalies(CloudAccount account,
            boolean forceRefresh) {
        String cacheKey = "costAnomalies-" + account.getAwsAccountId();
        String dbKey = "FINOPS_ANOMALIES::" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.CostAnomaly>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    List<DashboardData.CostAnomaly> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCache.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", dbKey);
                        return CompletableFuture.completedFuture(dbData);
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", dbKey, dbEx.getMessage());
            }
        }

        // Return mock data if mock mode is enabled
        if (useMockData) {
            List<DashboardData.CostAnomaly> mockResult = costMockService.getMockCostAnomalies(account);
            redisCache.put(cacheKey, mockResult, 60 * 24);
            return CompletableFuture.completedFuture(mockResult);
        }

        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching cost anomalies for account {}...", account.getAwsAccountId());
        try {
            AnomalyDateInterval dateInterval = AnomalyDateInterval.builder()
                    .startDate(LocalDate.now().minusDays(60).toString())
                    .endDate(LocalDate.now().minusDays(1).toString()).build();
            GetAnomaliesRequest request = GetAnomaliesRequest.builder().dateInterval(dateInterval).build();
            List<Anomaly> anomalies = ce.getAnomalies(request).anomalies();
            List<DashboardData.CostAnomaly> result = anomalies.stream()
                    .map(a -> new DashboardData.CostAnomaly(
                            a.anomalyId(), getServiceNameFromAnomaly(a), a.impact().totalImpact(),
                            LocalDate.parse(a.anomalyStartDate().substring(0, 10)),
                            a.anomalyEndDate() != null ? LocalDate.parse(a.anomalyEndDate().substring(0, 10))
                                    : LocalDate.now()))
                    .collect(Collectors.toList());
            redisCache.put(cacheKey, result, 60 * 24);
            try {
                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                snapshot.setCacheKey(dbKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
            }
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error(
                    "Could not fetch Cost Anomalies for account {}. This might be a permissions issue or the service is not enabled.",
                    account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private String getServiceNameFromAnomaly(Anomaly anomaly) {
        if (anomaly.rootCauses() != null && !anomaly.rootCauses().isEmpty()) {
            RootCause rootCause = anomaly.rootCauses().get(0);
            if (rootCause.service() != null)
                return rootCause.service();
        }
        return "Unknown Service";
    }
}