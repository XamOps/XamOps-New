package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.*;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeReservedInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeReservedInstancesResponse;
import software.amazon.awssdk.services.ec2.model.ModifyReservedInstancesRequest;
import software.amazon.awssdk.services.ec2.model.ModifyReservedInstancesResponse;
import software.amazon.awssdk.services.ec2.model.ReservedInstances;
import software.amazon.awssdk.services.ec2.model.ReservedInstancesConfiguration;
import software.amazon.awssdk.services.savingsplans.SavingsplansClient;
import software.amazon.awssdk.services.savingsplans.model.DescribeSavingsPlansRequest;
import software.amazon.awssdk.services.savingsplans.model.DescribeSavingsPlansResponse;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReservationService {

    private static final Logger logger = LoggerFactory.getLogger(ReservationService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService;
    private final List<String> instanceSizeOrder;
    private final String configuredRegion;
    private final RedisCacheService redisCache;
    private final CostMockService costMockService;
    private final java.util.concurrent.Executor awsTaskExecutor;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    @Value("${aws.costexplorer.mock:false}")
    private boolean useMockData;

    @Autowired
    public ReservationService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            @Lazy CloudListService cloudListService,
            @Value("${rightsizing.instance-size-order}") List<String> instanceSizeOrder,
            RedisCacheService redisCache,
            CostMockService costMockService,
            @Qualifier("awsTaskExecutor") java.util.concurrent.Executor awsTaskExecutor,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.cloudListService = cloudListService;
        this.instanceSizeOrder = instanceSizeOrder;
        this.redisCache = redisCache;
        this.costMockService = costMockService;
        this.awsTaskExecutor = awsTaskExecutor;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    private CloudAccount getAccount(String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }
        return accounts.get(0);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<ReservationDto> getReservationPageData(String accountId, boolean forceRefresh) {
        String cacheKey = "reservationPageData-" + accountId;
        String dbKey = "RESERVATION_PAGE::" + accountId;
        if (!forceRefresh) {
            Optional<ReservationDto> cachedData = redisCache.get(cacheKey, ReservationDto.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            // DB fallback on Redis miss — avoids fan-out CE calls on cold Redis
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    ReservationDto dbData = objectMapper.readValue(dbCached.get().getJsonData(), ReservationDto.class);
                    if (dbData != null) {
                        redisCache.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ Reservation page DB hit — repopulating Redis for account {}", accountId);
                        return CompletableFuture.completedFuture(dbData);
                    }
                }
            } catch (Exception e) {
                logger.warn("DB read failed for reservation page data {}: {}", accountId, e.getMessage());
            }
        }

        CloudAccount account = getAccount(accountId);

        return cloudListService.getAllOptedInRegions(account).thenCompose(activeRegions -> {

            if (activeRegions == null) {
                return CompletableFuture.completedFuture(new ReservationDto(null, null, null, null, null, null));
            }

            logger.info("--- LAUNCHING RESERVATION & SP SCAN for account {} (Scanning {} regions) ---",
                    account.getAwsAccountId(), activeRegions.size());

            CompletableFuture<DashboardData.ReservationAnalysis> analysisFuture = getReservationAnalysis(account,
                    forceRefresh);
            CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> purchaseRecsFuture = getReservationPurchaseRecommendations(
                    account, "ONE_YEAR", "NO_UPFRONT", "THIRTY_DAYS", "STANDARD", forceRefresh);
            CompletableFuture<List<ReservationInventoryDto>> inventoryFuture = getReservationInventory(account,
                    activeRegions, forceRefresh);
            CompletableFuture<HistoricalReservationDataDto> historicalDataFuture = getHistoricalReservationData(account,
                    forceRefresh);
            CompletableFuture<List<ReservationModificationRecommendationDto>> modificationRecsFuture = getReservationModificationRecommendations(
                    account, activeRegions, forceRefresh);

            CompletableFuture<List<SavingsPlanDto>> savingsPlansFuture = getSavingsPlans(account, activeRegions,
                    forceRefresh);

            return CompletableFuture.allOf(analysisFuture, purchaseRecsFuture, inventoryFuture, historicalDataFuture,
                    modificationRecsFuture, savingsPlansFuture).thenApply(v -> {
                        logger.info("--- RESERVATION PAGE DATA FETCH COMPLETE ---");
                        ReservationDto result = new ReservationDto(
                                analysisFuture.join(),
                                purchaseRecsFuture.join(),
                                inventoryFuture.join(),
                                historicalDataFuture.join(),
                                modificationRecsFuture.join(),
                                savingsPlansFuture.join());
                        try {
                            cachedDataRepository.save(new CachedData(dbKey,
                                    objectMapper.writeValueAsString(result), java.time.LocalDateTime.now()));
                        } catch (Exception e) {
                            logger.warn("DB save failed for reservation page data {}: {}", accountId, e.getMessage());
                        }
                        redisCache.put(cacheKey, result, 60 * 24);
                        return result;
                    });
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<SavingsPlanDto>> getSavingsPlans(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getSavingsPlans");
            return CompletableFuture.completedFuture(costMockService.getMockSavingsPlans(account));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                SavingsplansClient client = awsClientProvider.getSavingsplansClient(account, "us-east-1");
                DescribeSavingsPlansResponse response = client
                        .describeSavingsPlans(DescribeSavingsPlansRequest.builder().build());

                return response.savingsPlans().stream().map(sp -> new SavingsPlanDto(
                        sp.savingsPlanId(),
                        sp.savingsPlanArn(),
                        sp.description(),
                        sp.stateAsString(),
                        sp.savingsPlanTypeAsString(),
                        sp.paymentOptionAsString(),
                        sp.start(),
                        sp.end(),
                        sp.region(),
                        sp.commitment() + "/hr",
                        sp.upfrontPaymentAmount() != null ? sp.upfrontPaymentAmount() : "0.0",
                        sp.currencyAsString())).collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Failed to fetch Savings Plans for account {}: {}", account.getAwsAccountId(),
                        e.getMessage());
                return new ArrayList<>();
            }
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.ReservationAnalysis> getReservationAnalysis(CloudAccount account,
            boolean forceRefresh) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getReservationAnalysis");
            return CompletableFuture.completedFuture(costMockService.getMockReservationAnalysis(account));
        }

        String cacheKey = "reservationAnalysis-" + account.getAwsAccountId();
        String dbKey = "RESERVATION_ANALYSIS::" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.ReservationAnalysis> cachedData = redisCache.get(cacheKey,
                    DashboardData.ReservationAnalysis.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    DashboardData.ReservationAnalysis dbData = objectMapper.readValue(dbCached.get().getJsonData(), DashboardData.ReservationAnalysis.class);
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

        return CompletableFuture.supplyAsync(() -> {
            CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
            try {
                String today = LocalDate.now().toString();
                String thirtyDaysAgo = LocalDate.now().minusDays(30).toString();
                DateInterval last30Days = DateInterval.builder().start(thirtyDaysAgo).end(today).build();

                // 1. Fetch RI Metrics
                GetReservationUtilizationRequest riUtilReq = GetReservationUtilizationRequest.builder()
                        .timePeriod(last30Days).build();
                List<UtilizationByTime> riUtils = ce.getReservationUtilization(riUtilReq).utilizationsByTime();
                GetReservationCoverageRequest riCovReq = GetReservationCoverageRequest.builder()
                        .timePeriod(last30Days).build();
                List<CoverageByTime> riCovs = ce.getReservationCoverage(riCovReq).coveragesByTime();

                double riUtil = riUtils.isEmpty() || riUtils.get(0).total() == null ? 0.0
                        : Double.parseDouble(riUtils.get(0).total().utilizationPercentage());
                double riCov = riCovs.isEmpty() || riCovs.get(0).total() == null ? 0.0
                        : Double.parseDouble(riCovs.get(0).total().coverageHours().coverageHoursPercentage());

                // 2. Fetch Savings Plan Metrics
                GetSavingsPlansUtilizationRequest spUtilReq = GetSavingsPlansUtilizationRequest.builder()
                        .timePeriod(last30Days).build();
                List<SavingsPlansUtilizationByTime> spUtils = ce
                        .getSavingsPlansUtilization(spUtilReq).savingsPlansUtilizationsByTime();

                GetSavingsPlansCoverageRequest spCovReq = GetSavingsPlansCoverageRequest.builder()
                        .timePeriod(last30Days).build();
                List<SavingsPlansCoverage> spCovs = ce
                        .getSavingsPlansCoverage(spCovReq).savingsPlansCoverages();

                double spUtil = spUtils.isEmpty() || spUtils.get(0).utilization() == null ? 0.0
                        : Double.parseDouble(spUtils.get(0).utilization().utilizationPercentage());

                double spCov = spCovs.isEmpty() || spCovs.get(0).coverage() == null ? 0.0
                        : Double.parseDouble(spCovs.get(0).coverage().coveragePercentage());

                logger.info("Account {}: RI Util={}%, SP Util={}%", account.getAwsAccountId(), riUtil, spUtil);

                // 3. Merge Logic: Show max of either to represent total optimization effort
                double finalUtil = (riUtil > 0) ? riUtil : spUtil;
                double finalCov = (riCov > 0) ? riCov : spCov;

                DashboardData.ReservationAnalysis result = new DashboardData.ReservationAnalysis(finalUtil, finalCov);
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
                return result;
            } catch (Exception e) {
                logger.error("Error fetching analysis for account {}", account.getAwsAccountId(), e);
                return new DashboardData.ReservationAnalysis(0.0, 0.0);
            }
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<HistoricalReservationDataDto> getHistoricalReservationData(CloudAccount account,
            boolean forceRefresh) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getHistoricalReservationData");
            return CompletableFuture.completedFuture(costMockService.getMockHistoricalReservationData(account));
        }

        String cacheKey = "historicalReservationData-" + account.getAwsAccountId();
        String dbKey = "RESERVATION_HISTORICAL::" + account.getAwsAccountId();

        // ── Immutable-window check (runs regardless of forceRefresh) ──────────────
        // The 6-month window covers only closed months and shifts once per month.
        // If the DB snapshot was saved THIS month the window hasn't changed — serve
        // it directly, even when the nightly scheduler calls with forceRefresh=true,
        // saving 4 CE API calls per account per night.
        try {
            Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
            if (dbCached.isPresent() && dbCached.get().getLastUpdated() != null) {
                java.time.YearMonth snapMonth = java.time.YearMonth.from(dbCached.get().getLastUpdated());
                if (snapMonth.equals(java.time.YearMonth.now())) {
                    HistoricalReservationDataDto dbData = objectMapper.readValue(
                            dbCached.get().getJsonData(), HistoricalReservationDataDto.class);
                    if (dbData != null) {
                        redisCache.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ Reservation historical DB hit (current-month window) — skipped CE: {}", dbKey);
                        return CompletableFuture.completedFuture(dbData);
                    }
                }
            }
        } catch (Exception preEx) {
            logger.debug("Pre-check for reservation historical skipped: {}", preEx.getMessage());
        }

        if (!forceRefresh) {
            Optional<HistoricalReservationDataDto> cachedData = redisCache.get(cacheKey,
                    HistoricalReservationDataDto.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    HistoricalReservationDataDto dbData = objectMapper.readValue(dbCached.get().getJsonData(), HistoricalReservationDataDto.class);
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

        return CompletableFuture.supplyAsync(() -> {
            CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
            try {
                // Get 6 full months of historical data (not including current partial month)
                LocalDate endDate = LocalDate.now().withDayOfMonth(1); // Start of current month
                LocalDate startDate = endDate.minusMonths(6); // 6 months before current month
                DateInterval period = DateInterval.builder().start(startDate.toString()).end(endDate.toString())
                        .build();

                // 1. RI History
                GetReservationUtilizationRequest riUtilReq = GetReservationUtilizationRequest.builder()
                        .timePeriod(period).granularity(Granularity.MONTHLY).build();
                List<UtilizationByTime> riUtilList = ce.getReservationUtilization(riUtilReq).utilizationsByTime();

                GetReservationCoverageRequest riCovReq = GetReservationCoverageRequest.builder()
                        .timePeriod(period).granularity(Granularity.MONTHLY).build();
                List<CoverageByTime> riCovList = ce.getReservationCoverage(riCovReq).coveragesByTime();

                // 2. SP History
                GetSavingsPlansUtilizationRequest spUtilReq = GetSavingsPlansUtilizationRequest.builder()
                        .timePeriod(period).granularity(Granularity.MONTHLY).build();
                List<SavingsPlansUtilizationByTime> spUtilList = ce
                        .getSavingsPlansUtilization(spUtilReq).savingsPlansUtilizationsByTime();

                GetSavingsPlansCoverageRequest spCovReq = GetSavingsPlansCoverageRequest.builder()
                        .timePeriod(period).granularity(Granularity.MONTHLY).build();
                List<SavingsPlansCoverage> spCovList = ce
                        .getSavingsPlansCoverage(spCovReq).savingsPlansCoverages();

                // 3. Data Processing: Use a Map to aggregate and SORT by Date
                // Key: LocalDate (Start of month), Value: double[4] -> {RI Util, RI Cov, SP
                // Util, SP Cov}
                Map<LocalDate, double[]> dataMap = new TreeMap<>();

                // Helper to populate map
                for (UtilizationByTime u : riUtilList) {
                    if (u.timePeriod() != null && u.total() != null) {
                        try {
                            LocalDate date = LocalDate.parse(u.timePeriod().start());
                            dataMap.computeIfAbsent(date, k -> new double[4])[0] = Double
                                    .parseDouble(u.total().utilizationPercentage());
                        } catch (Exception ignored) {
                        }
                    }
                }
                for (CoverageByTime c : riCovList) {
                    if (c.timePeriod() != null && c.total() != null) {
                        try {
                            LocalDate date = LocalDate.parse(c.timePeriod().start());
                            dataMap.computeIfAbsent(date, k -> new double[4])[1] = Double
                                    .parseDouble(c.total().coverageHours().coverageHoursPercentage());
                        } catch (Exception ignored) {
                        }
                    }
                }
                for (SavingsPlansUtilizationByTime u : spUtilList) {
                    if (u.timePeriod() != null && u.utilization() != null) {
                        try {
                            LocalDate date = LocalDate.parse(u.timePeriod().start());
                            dataMap.computeIfAbsent(date, k -> new double[4])[2] = Double
                                    .parseDouble(u.utilization().utilizationPercentage());
                        } catch (Exception ignored) {
                        }
                    }
                }
                for (SavingsPlansCoverage c : spCovList) {
                    if (c.timePeriod() != null && c.coverage() != null) {
                        try {
                            LocalDate date = LocalDate.parse(c.timePeriod().start());
                            dataMap.computeIfAbsent(date, k -> new double[4])[3] = Double
                                    .parseDouble(c.coverage().coveragePercentage());
                        } catch (Exception ignored) {
                        }
                    }
                }

                // 4. Build Final Sorted Lists
                List<String> labels = new ArrayList<>();
                List<Double> utilPercentages = new ArrayList<>();
                List<Double> covPercentages = new ArrayList<>();

                for (Map.Entry<LocalDate, double[]> entry : dataMap.entrySet()) {
                    // Format Date: "MMM yyyy"
                    labels.add(entry.getKey().format(DateTimeFormatter.ofPattern("MMM yyyy")));

                    double[] vals = entry.getValue();
                    // Merge logic: max of RI vs SP
                    utilPercentages.add(Math.max(vals[0], vals[2]));
                    covPercentages.add(Math.max(vals[1], vals[3]));
                }

                HistoricalReservationDataDto result = new HistoricalReservationDataDto(labels, utilPercentages,
                        covPercentages);
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
                return result;
            } catch (Exception e) {
                logger.error("Error fetching historical data for account {}", account.getAwsAccountId(), e);
                return new HistoricalReservationDataDto(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            }
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> getReservationPurchaseRecommendations(
            CloudAccount account, String term, String paymentOption, String lookback, String offeringClass,
            boolean forceRefresh) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getReservationPurchaseRecommendations");
            return CompletableFuture.completedFuture(
                    costMockService.getMockReservationPurchaseRecommendations(account, term, paymentOption));
        }

        String cacheKey = String.format("reservationPurchaseRecs-%s-%s-%s-%s-%s", account.getAwsAccountId(), term,
                paymentOption, lookback, offeringClass);
        String dbKey = String.format("RESERVATION_PURCHASE_RECS::%s::%s::%s::%s::%s", account.getAwsAccountId(), term,
                paymentOption, lookback, offeringClass);
        if (!forceRefresh) {
            Optional<List<DashboardData.ReservationPurchaseRecommendation>> cachedData = redisCache.get(cacheKey,
                    new TypeReference<>() {
                    });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    List<DashboardData.ReservationPurchaseRecommendation> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
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

        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        try {
            GetReservationPurchaseRecommendationRequest request = GetReservationPurchaseRecommendationRequest.builder()
                    .lookbackPeriodInDays(LookbackPeriodInDays.fromValue(lookback))
                    .termInYears(TermInYears.fromValue(term))
                    .paymentOption(PaymentOption.fromValue(paymentOption))
                    .service("Amazon Elastic Compute Cloud - Compute").build();

            GetReservationPurchaseRecommendationResponse response = ce.getReservationPurchaseRecommendation(request);

            List<DashboardData.ReservationPurchaseRecommendation> result = response.recommendations().stream()
                    .filter(rec -> rec.recommendationDetails() != null && !rec.recommendationDetails().isEmpty())
                    .flatMap(rec -> rec.recommendationDetails().stream()
                            .map(details -> {
                                try {
                                    EC2InstanceDetails ec2Details = details.instanceDetails().ec2InstanceDetails();
                                    double monthlySavings = Double.parseDouble(details.estimatedMonthlySavingsAmount());
                                    double monthlyOnDemandCost = Double
                                            .parseDouble(details.estimatedMonthlyOnDemandCost());
                                    double estimatedRecurringMonthlyCost = monthlyOnDemandCost - monthlySavings;

                                    return new DashboardData.ReservationPurchaseRecommendation(
                                            ec2Details.family(),
                                            String.valueOf(details.recommendedNumberOfInstancesToPurchase()),
                                            String.valueOf(details.recommendedNormalizedUnitsToPurchase()),
                                            String.valueOf(details.minimumNumberOfInstancesUsedPerHour()),
                                            String.valueOf(monthlySavings),
                                            String.valueOf(monthlyOnDemandCost),
                                            String.valueOf(estimatedRecurringMonthlyCost),
                                            String.valueOf(rec.termInYears()),
                                            ec2Details.instanceType(),
                                            ec2Details.region(),
                                            ec2Details.platform(),
                                            ec2Details.tenancy(),
                                            ec2Details.currentGeneration() ? "Current" : "Previous",
                                            ec2Details.sizeFlexEligible());
                                } catch (Exception e) {
                                    return null;
                                }
                            }))
                    .filter(Objects::nonNull)
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
            logger.error("Could not fetch reservation purchase recommendations.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private double fetchSingleRIUtilization(CloudAccount account, String riId) {
        // Use mock data if enabled (performance optimization)
        if (useMockData) {
            return costMockService.getMockSingleRIUtilization(riId);
        }

        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        try {
            String today = LocalDate.now().toString();
            String thirtyDaysAgo = LocalDate.now().minusDays(30).toString();
            DateInterval last30Days = DateInterval.builder().start(thirtyDaysAgo).end(today).build();

            Expression filter = Expression.builder()
                    .dimensions(DimensionValues.builder()
                            .key(Dimension.RESERVATION_ID)
                            .values(riId)
                            .build())
                    .build();

            GetReservationUtilizationRequest utilRequest = GetReservationUtilizationRequest.builder()
                    .timePeriod(last30Days)
                    .granularity(Granularity.MONTHLY)
                    .filter(filter)
                    .build();

            List<UtilizationByTime> utilizationsByTime = ce.getReservationUtilization(utilRequest).utilizationsByTime();

            if (utilizationsByTime.isEmpty() || utilizationsByTime.get(0).total() == null) {
                return 0.0;
            }

            return Double.parseDouble(utilizationsByTime.get(0).total().utilizationPercentage());
        } catch (Exception e) {
            return 0.0;
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<ReservationInventoryDto>> getReservationInventory(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getReservationInventory");
            return CompletableFuture.completedFuture(costMockService.getMockReservationInventory(account));
        }

        String cacheKey = "reservationInventory-" + account.getAwsAccountId();
        String dbKey = "RESERVATION_INVENTORY::" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<ReservationInventoryDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    List<ReservationInventoryDto> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
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

        CompletableFuture<List<ReservationInventoryDto>> inventoryFuture = fetchAllRegionalResources(account,
                activeRegions, regionId -> {
                    try {
                        Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);

                        DescribeReservedInstancesRequest request = DescribeReservedInstancesRequest.builder().build();
                        DescribeReservedInstancesResponse response = ec2.describeReservedInstances(request);

                        if (response.hasReservedInstances() && !response.reservedInstances().isEmpty()) {
                            logger.info("🔍 Found {} RIs in region {} for account {}",
                                    response.reservedInstances().size(), regionId, account.getAwsAccountId());

                            return response.reservedInstances().stream()
                                    .map(ri -> new ReservationInventoryDto(
                                            ri.reservedInstancesId(), ri.offeringTypeAsString(),
                                            ri.instanceTypeAsString(),
                                            ri.scopeAsString(), ri.availabilityZone(), ri.duration(), ri.start(),
                                            ri.end(),
                                            ri.instanceCount(), ri.stateAsString(),
                                            0.0))
                                    .collect(Collectors.toList());
                        } else {
                            return Collections.emptyList();
                        }
                    } catch (Exception e) {
                        logger.error("Error fetching RIs in region {}: {}", regionId, e.getMessage());
                        return Collections.emptyList();
                    }
                }, "Reservation Inventory");

        return inventoryFuture.thenCompose(inventory -> {
            if (inventory.isEmpty()) {
                logger.info("🔍 DEBUG: Final inventory list is empty. AWS returned 0 RIs.");
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            logger.info("🔍 DEBUG: Proceeding to fetch utilization for {} RIs.", inventory.size());

            List<CompletableFuture<ReservationInventoryDto>> utilizationFutures = inventory.stream()
                    .map(ri -> CompletableFuture.supplyAsync(() -> {
                        double utilization = 0.0;
                        if ("active".equalsIgnoreCase(ri.getState())) {
                            utilization = fetchSingleRIUtilization(account, ri.getReservationId());
                        }
                        return new ReservationInventoryDto(
                                ri.getReservationId(), ri.getOfferingType(), ri.getInstanceType(),
                                ri.getScope(), ri.getAvailabilityZone(), ri.getDuration(),
                                ri.getStart(), ri.getEnd(), ri.getInstanceCount(), ri.getState(),
                                utilization);
                    }, awsTaskExecutor))
                    .collect(Collectors.toList());

            return CompletableFuture.allOf(utilizationFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        List<ReservationInventoryDto> updatedInventory = utilizationFutures.stream()
                                .map(CompletableFuture::join)
                                .collect(Collectors.toList());

                        redisCache.put(cacheKey, updatedInventory, 60 * 24);
                        try {
                            CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                            snapshot.setCacheKey(dbKey);
                            snapshot.setJsonData(objectMapper.writeValueAsString(updatedInventory));
                            snapshot.setLastUpdated(java.time.LocalDateTime.now());
                            cachedDataRepository.save(snapshot);
                        } catch (Exception dbEx) {
                            logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
                        }
                        return updatedInventory;
                    });
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<ReservationModificationRecommendationDto>> getReservationModificationRecommendations(
            CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getReservationModificationRecommendations");
            return CompletableFuture
                    .completedFuture(costMockService.getMockReservationModificationRecommendations(account));
        }

        String cacheKey = "reservationModificationRecs-" + account.getAwsAccountId();
        String dbKey = "RESERVATION_MOD_RECS::" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<ReservationModificationRecommendationDto>> cachedData = redisCache.get(cacheKey,
                    new TypeReference<>() {
                    });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    List<ReservationModificationRecommendationDto> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
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

        return getReservationInventory(account, activeRegions, forceRefresh).thenCompose(activeReservations -> {
            if (activeReservations.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            try {
                List<ReservationModificationRecommendationDto> recommendations = new ArrayList<>();
                for (ReservationInventoryDto ri : activeReservations) {
                    double utilizationPercentage = ri.getUtilizationPercentage();

                    if (utilizationPercentage < 80.0 && "Convertible".equalsIgnoreCase(ri.getOfferingType())
                            && "active".equalsIgnoreCase(ri.getState())) {
                        String currentType = ri.getInstanceType();
                        String recommendedType = suggestSmallerInstanceType(currentType);
                        if (recommendedType != null && !recommendedType.equals(currentType)) {
                            recommendations.add(new ReservationModificationRecommendationDto(
                                    ri.getReservationId(), currentType, recommendedType,
                                    String.format("Low Utilization (%.1f%%)", utilizationPercentage), 50.0));
                        }
                    }
                }
                redisCache.put(cacheKey, recommendations, 60 * 24);
                try {
                    CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                    snapshot.setCacheKey(dbKey);
                    snapshot.setJsonData(objectMapper.writeValueAsString(recommendations));
                    snapshot.setLastUpdated(java.time.LocalDateTime.now());
                    cachedDataRepository.save(snapshot);
                } catch (Exception dbEx) {
                    logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
                }
                return CompletableFuture.completedFuture(recommendations);
            } catch (Exception e) {
                logger.error("Could not generate reservation modification recommendations for account {}",
                        account.getAwsAccountId(), e);
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
        });
    }

    public String applyReservationModification(String accountId, ReservationModificationRequestDto request) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for applyReservationModification");
            return costMockService.getMockApplyReservationModification(
                    request.getReservationId(),
                    request.getTargetInstanceType(),
                    request.getInstanceCount());
        }

        CloudAccount account = getAccount(accountId);
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, configuredRegion);
        logger.info("Attempting to modify reservation {} for account {}", request.getReservationId(), accountId);

        DescribeReservedInstancesResponse riResponse = ec2
                .describeReservedInstances(r -> r.reservedInstancesIds(request.getReservationId()));
        if (riResponse.reservedInstances().isEmpty()) {
            throw new IllegalArgumentException("Reservation ID not found: " + request.getReservationId());
        }
        ReservedInstances originalRi = riResponse.reservedInstances().get(0);

        if (!"Convertible".equalsIgnoreCase(originalRi.offeringTypeAsString())) {
            throw new IllegalArgumentException("Cannot modify a non-convertible reservation.");
        }

        ReservedInstancesConfiguration targetConfig = ReservedInstancesConfiguration.builder()
                .instanceType(request.getTargetInstanceType())
                .instanceCount(request.getInstanceCount())
                .platform(originalRi.productDescriptionAsString())
                .availabilityZone(originalRi.availabilityZone())
                .build();

        ModifyReservedInstancesRequest modifyRequest = ModifyReservedInstancesRequest.builder()
                .clientToken(UUID.randomUUID().toString())
                .reservedInstancesIds(request.getReservationId())
                .targetConfigurations(targetConfig)
                .build();
        try {
            ModifyReservedInstancesResponse modifyResponse = ec2.modifyReservedInstances(modifyRequest);
            logger.info("Successfully submitted modification request for RI {}. Transaction ID: {}",
                    request.getReservationId(), modifyResponse.reservedInstancesModificationId());
            redisCache.evict("reservationModificationRecs-" + accountId);
            return modifyResponse.reservedInstancesModificationId();
        } catch (Exception e) {
            logger.error("Failed to execute RI modification for ID {}: {}", request.getReservationId(), e.getMessage());
            throw new RuntimeException("AWS API call to modify reservation failed.", e);
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<CostByTagDto>> getReservationCostByTag(String accountId, String tagKey,
            boolean forceRefresh) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getReservationCostByTag");
            return CompletableFuture.completedFuture(costMockService.getMockReservationCostByTag(accountId, tagKey));
        }

        String cacheKey = "reservationCostByTag-" + accountId + "-" + tagKey;
        String dbKey = "RESERVATION_COST_TAG::" + accountId + "::" + tagKey;
        if (!forceRefresh) {
            Optional<List<CostByTagDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    List<CostByTagDto> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
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

        CloudAccount account = getAccount(accountId);
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        if (tagKey == null || tagKey.isBlank()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try {
            LocalDate start = LocalDate.now().withDayOfMonth(1);
            LocalDate end = LocalDate.now().plusMonths(1).withDayOfMonth(1);
            DateInterval period = DateInterval.builder().start(start.toString()).end(end.toString()).build();

            Expression filter = Expression.builder().dimensions(DimensionValues.builder()
                    .key(Dimension.PURCHASE_TYPE).values("Reserved Instances").build()).build();
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(period).granularity(Granularity.MONTHLY).metrics("AmortizedCost")
                    .filter(filter)
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.TAG).key(tagKey).build()).build();

            List<ResultByTime> results = ce.getCostAndUsage(request).resultsByTime();
            List<CostByTagDto> resultList = results.stream()
                    .flatMap(r -> r.groups().stream())
                    .map(g -> {
                        String tagValue = g.keys().isEmpty() || g.keys().get(0).isEmpty() ? "Untagged"
                                : g.keys().get(0);
                        double cost = Double.parseDouble(g.metrics().get("AmortizedCost").amount());
                        return new CostByTagDto(tagValue, cost);
                    })
                    .filter(dto -> dto.getCost() > 0.01)
                    .collect(Collectors.toList());
            redisCache.put(cacheKey, resultList, 60 * 24);
            try {
                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                snapshot.setCacheKey(dbKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(resultList));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
            }
            return CompletableFuture.completedFuture(resultList);
        } catch (Exception e) {
            logger.error("Could not fetch reservation cost by tag key '{}' for account {}.", tagKey, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private String suggestSmallerInstanceType(String instanceType) {
        String[] parts = instanceType.split("\\.");
        if (parts.length != 2)
            return null;
        String family = parts[0];
        String size = parts[1];

        int currentIndex = this.instanceSizeOrder.indexOf(size);
        if (currentIndex > 0) {
            return family + "." + this.instanceSizeOrder.get(currentIndex - 1);
        }
        return null;
    }

    private <T> CompletableFuture<List<T>> fetchAllRegionalResources(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions, Function<String, List<T>> fetchFunction,
            String serviceName) {
        if (activeRegions == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        List<CompletableFuture<List<T>>> futures = activeRegions.stream()
                .map(regionStatus -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return fetchFunction.apply(regionStatus.getRegionId());
                    } catch (AwsServiceException e) {
                        logger.warn("Reservation task failed for account {}: {} in region {}. AWS Error: {}",
                                account.getAwsAccountId(), serviceName, regionStatus.getRegionId(),
                                e.awsErrorDetails().errorMessage());
                        return Collections.<T>emptyList();
                    } catch (Exception e) {
                        logger.error("Reservation task failed for account {}: {} in region {}.",
                                account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e);
                        return Collections.<T>emptyList();
                    }
                }, awsTaskExecutor))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(List::stream)
                        .collect(Collectors.toList()));
    }
}
