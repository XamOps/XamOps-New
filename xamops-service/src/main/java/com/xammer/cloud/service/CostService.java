package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.CostForecastDto;
import com.xammer.cloud.dto.DetailedCostDto;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.cur.CurAutoRegistrationService;
import com.xammer.cloud.service.cur.CurCostDataSource;
import com.xammer.cloud.service.cur.CurSourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeAddressesRequest;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class CostService {

        private static final Logger logger = LoggerFactory.getLogger(CostService.class);

        private final CloudAccountRepository cloudAccountRepository;
        private final AwsClientProvider awsClientProvider;
        private final RedisCacheService redisCache;
        private final ForecastingService forecastingService;
        private final CostMockService costMockService;
        private final CachedDataRepository cachedDataRepository;
        private final ObjectMapper objectMapper;
        private final AwsPricingService awsPricingService;
        private final CostDataSnapshotService snapshotService;
        private final CurSourceResolver curSourceResolver;
        private final CurCostDataSource curCostDataSource;
        private final CurAutoRegistrationService curAutoRegistrationService;

        @Value("${aws.costexplorer.mock:false}")
        private boolean useMockData;

        @Autowired
        public CostService(CloudAccountRepository cloudAccountRepository,
                        AwsClientProvider awsClientProvider,
                        RedisCacheService redisCache,
                        @Lazy ForecastingService forecastingService,
                        CostMockService costMockService,
                        CachedDataRepository cachedDataRepository,
                        ObjectMapper objectMapper,
                        AwsPricingService awsPricingService,
                        CostDataSnapshotService snapshotService,
                        CurSourceResolver curSourceResolver,
                        CurCostDataSource curCostDataSource,
                        CurAutoRegistrationService curAutoRegistrationService) {
                this.cloudAccountRepository = cloudAccountRepository;
                this.awsClientProvider = awsClientProvider;
                this.redisCache = redisCache;
                this.forecastingService = forecastingService;
                this.costMockService = costMockService;
                this.cachedDataRepository = cachedDataRepository;
                this.objectMapper = objectMapper;
                this.awsPricingService = awsPricingService;
                this.snapshotService = snapshotService;
                this.curSourceResolver = curSourceResolver;
                this.curCostDataSource = curCostDataSource;
                this.curAutoRegistrationService = curAutoRegistrationService;

                logger.info("CostService initialized (Mock Mode: {})", useMockData);
        }

        private CloudAccount getAccount(String accountId) {
                return cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst()
                                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        }

        /**
         * Normalises a String endDate to the last day of the month when the date
         * falls in the current calendar month. This keeps the cache key stable for the
         * entire billing cycle so data written by the WED/SUN nightly scheduler remains
         * a cache hit on Mon/Tue/Thu/Fri. CE API returns only completed-day data
         * regardless of a future end date, so using end-of-month is safe.
         */
        private String normalizeEndDate(String endDate) {
                return normalizeEndDate(null, endDate);
        }

        /**
         * Normalises endDate to end-of-month for cache-key stability, but ONLY when the
         * query is a default MTD or full-month view (startDate null or 1st of month).
         * Mid-month startDates (comparison periods like Jun 8–15) must NOT be stretched to
         * end-of-month, otherwise the cache returns 3+ weeks of data instead of 7 days.
         *
         * <p>Nor is an end date the caller chose. An end date earlier than today was picked
         * deliberately, and stretching it answers a different question than the one asked:
         * a FinOps range of 1–14 September returned the whole month, and since the widened
         * dates also form the cache key, every range starting on the 1st collapsed onto one
         * entry and reported an identical total. Today or later is the genuine to-date case
         * and is still widened, which is what keeps period-spanning fee rows — reservation
         * charges, support, tax — in the current month's figure.
         */
        private String normalizeEndDate(String startDate, String endDate) {
                if (endDate == null) return null;
                try {
                        LocalDate end = LocalDate.parse(endDate);
                        YearMonth endYm = YearMonth.from(end);
                        if (endYm.equals(YearMonth.now()) && !end.isBefore(LocalDate.now())) {
                                boolean isMtdOrFullMonth = startDate == null
                                        || LocalDate.parse(startDate).getDayOfMonth() == 1;
                                if (isMtdOrFullMonth) {
                                        return endYm.atEndOfMonth().toString();
                                }
                        }
                } catch (Exception ignored) {}
                return endDate;
        }

        /** LocalDate overload — same semantics as the String version above. */
        private LocalDate normalizeEndDate(LocalDate endDate) {
                if (endDate == null) return null;
                YearMonth endYm = YearMonth.from(endDate);
                return endYm.equals(YearMonth.now()) && !endDate.isBefore(LocalDate.now())
                        ? endYm.atEndOfMonth()
                        : endDate;
        }

        // --- NEW METHODS ADDED TO FIX COMPILATION ERROR ---

        /**
         * Get Total Month-to-Date Spend
         */
        @Async("awsTaskExecutor")
        public CompletableFuture<Double> getTotalMonthToDateCost(String accountId, boolean forceRefresh) {
                String cacheKey = "totalMtdCost-" + accountId;
                String dbKey = "AWS_MTD_COST::" + accountId;
                if (!forceRefresh) {
                        Optional<Double> cached = redisCache.get(cacheKey, Double.class);
                        if (cached.isPresent()) {
                                logger.info("✅ Cache hit for MTD cost: {}", cacheKey);
                                return CompletableFuture.completedFuture(cached.get());
                        }
                        try {
                                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                                String dbJson = dbCached.map(snapshotService::resolvePayload).orElse(null);
                                if (dbJson != null) {
                                        Double dbData = objectMapper.readValue(dbJson, Double.class);
                                        if (dbData != null) {
                                                redisCache.put(cacheKey, dbData, 60 * 24);
                                                logger.info("✅ DB snapshot hit for MTD cost — re-warmed Redis: {}", dbKey);
                                                return CompletableFuture.completedFuture(dbData);
                                        }
                                }
                        } catch (Exception e) {
                                logger.warn("DB cache miss for {}: {}", dbKey, e.getMessage());
                        }

                        // Derive from getCostBreakdown(SERVICE) Redis cache — avoids a redundant CE call
                        // when cost.html has already populated the breakdown cache.
                        LocalDate cbNow = LocalDate.now();
                        String cbStart = cbNow.withDayOfMonth(1).toString();
                        String cbEnd = cbNow.toString();
                        String breakdownCacheKey = "costBreakdown-" + accountId + "-SERVICE--" + cbStart + "-" + cbEnd;
                        try {
                                Optional<List<CostDto>> breakdownOpt = redisCache.get(breakdownCacheKey,
                                                new TypeReference<List<CostDto>>() {});
                                if (breakdownOpt.isPresent()) {
                                        double total = breakdownOpt.get().stream()
                                                        .mapToDouble(CostDto::getAmount).sum();
                                        redisCache.put(cacheKey, total, 60 * 24);
                                        try {
                                                CachedData snap = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                                                snap.setCacheKey(dbKey);
                                                snap.setJsonData(objectMapper.writeValueAsString(total));
                                                snap.setLastUpdated(java.time.LocalDateTime.now());
                                                cachedDataRepository.save(snap);
                                        } catch (Exception dbEx) {
                                                logger.warn("Failed to save MTD cost DB snapshot {}: {}", dbKey, dbEx.getMessage());
                                        }
                                        logger.info("✅ MTD cost derived from getCostBreakdown Redis cache for {}", accountId);
                                        return CompletableFuture.completedFuture(total);
                                }
                        } catch (Exception e) {
                                logger.warn("Could not derive MTD cost from breakdown cache for {}: {}", accountId, e.getMessage());
                        }
                }

                // Return mock data if mock mode is enabled
                if (useMockData) {
                        Double mockResult = costMockService.getMockTotalMonthToDateCost(accountId);
                        redisCache.put(cacheKey, mockResult, 60 * 24);
                        return CompletableFuture.completedFuture(mockResult);
                }

                return CompletableFuture.supplyAsync(() -> {
                        try {
                                CloudAccount account = getAccount(accountId);

                                LocalDate now = LocalDate.now();
                                LocalDate start = now.withDayOfMonth(1);
                                LocalDate end = now.plusDays(1); // End date is exclusive

                                // CUR first. Same per-query fallback as getCostBreakdown — a CUR that
                                // has not delivered yet for a freshly onboarded account resolves to
                                // empty and this drops through to Cost Explorer.
                                Optional<CurSource> curSource = curSourceResolver.resolve(account, start, now);
                                if (curSource.isPresent()) {
                                        try {
                                                double curTotal = curCostDataSource.getTotalCost(
                                                                curSource.get(), account, start, now);
                                                cacheMtd(cacheKey, dbKey, curTotal);
                                                return curTotal;
                                        } catch (Exception curEx) {
                                                logger.warn("[CUR] MTD failed for account {} — falling back to Cost Explorer: {}",
                                                                accountId, curEx.getMessage());
                                        }
                                }

                                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                                DateInterval interval = DateInterval.builder()
                                                .start(start.toString())
                                                .end(end.toString())
                                                .build();

                                GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                                                .timePeriod(interval)
                                                .granularity(Granularity.MONTHLY)
                                                .metrics("UnblendedCost")
                                                .build();

                                GetCostAndUsageResponse response = ce.getCostAndUsage(request);

                                double total = response.resultsByTime().stream()
                                                .map(r -> r.total().get("UnblendedCost").amount())
                                                .mapToDouble(Double::parseDouble)
                                                .sum();

                                cacheMtd(cacheKey, dbKey, total);
                                return total;
                        } catch (Exception e) {
                                logger.error("❌ Error fetching MTD cost for account {}: {}", accountId, e.getMessage());
                                return 0.0;
                        }
                });
        }

        /** Writes an MTD total to Redis and the DB snapshot, shared by the CUR and CE paths. */
        private void cacheMtd(String cacheKey, String dbKey, double total) {
                redisCache.put(cacheKey, total, 60 * 24); // Cache for 24 hours
                try {
                        CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                        snapshot.setCacheKey(dbKey);
                        snapshot.setJsonData(objectMapper.writeValueAsString(total));
                        snapshot.setLastUpdated(java.time.LocalDateTime.now());
                        cachedDataRepository.save(snapshot);
                } catch (Exception dbEx) {
                        logger.warn("Failed to save MTD cost DB snapshot {}: {}", dbKey, dbEx.getMessage());
                }
        }

        /**
         * Get Last Month Total Spend
         */
        @Async("awsTaskExecutor")
        public CompletableFuture<Double> getLastMonthSpend(String accountId, boolean forceRefresh) {
                String cacheKey = "lastMonthCost-" + accountId;
                String dbKey = "AWS_LAST_MONTH_COST::" + accountId;

                // ── Closed-month snapshot check (permanent, no TTL) ──────────────────────
                // Last month is ALWAYS closed — snapshot is immutable. Check regardless of
                // forceRefresh so the nightly scheduler never wastes a CE call for this.
                YearMonth lastMonth = YearMonth.now().minusMonths(1);
                Optional<Double> snap = snapshotService.readMonthlyTotal(accountId, lastMonth);
                if (snap.isPresent()) {
                        logger.info("✅ Snapshot hit for last month cost ({}) — account {}", lastMonth, accountId);
                        redisCache.put(cacheKey, snap.get(), 60 * 24);
                        return CompletableFuture.completedFuture(snap.get());
                }

                if (!forceRefresh) {
                        Optional<Double> cached = redisCache.get(cacheKey, Double.class);
                        if (cached.isPresent()) {
                                logger.info("✅ Cache hit for Last Month cost: {}", cacheKey);
                                return CompletableFuture.completedFuture(cached.get());
                        }
                        try {
                                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                                String dbJson = dbCached.map(snapshotService::resolvePayload).orElse(null);
                                if (dbJson != null) {
                                        Double dbData = objectMapper.readValue(dbJson, Double.class);
                                        if (dbData != null) {
                                                redisCache.put(cacheKey, dbData, 60 * 24);
                                                logger.info("✅ DB snapshot hit for last month cost — re-warmed Redis: {}", dbKey);
                                                return CompletableFuture.completedFuture(dbData);
                                        }
                                }
                        } catch (Exception e) {
                                logger.warn("DB cache miss for {}: {}", dbKey, e.getMessage());
                        }
                }

                // Return mock data if mock mode is enabled
                if (useMockData) {
                        Double mockResult = costMockService.getMockLastMonthSpend(accountId);
                        redisCache.put(cacheKey, mockResult, 60 * 24);
                        return CompletableFuture.completedFuture(mockResult);
                }

                return CompletableFuture.supplyAsync(() -> {
                        try {
                                CloudAccount account = getAccount(accountId);

                                LocalDate now = LocalDate.now();
                                LocalDate start = now.minusMonths(1).withDayOfMonth(1);
                                LocalDate end = now.withDayOfMonth(1); // First day of current month (exclusive)

                                // CUR first. Last month is closed, so this is the one figure where the
                                // two sources should agree exactly — it is what the reconciliation
                                // endpoint compares.
                                Optional<CurSource> curSource = curSourceResolver.resolve(
                                                account, start, end.minusDays(1));
                                if (curSource.isPresent()) {
                                        try {
                                                double curTotal = curCostDataSource.getTotalCost(
                                                                curSource.get(), account, start, end.minusDays(1));
                                                cacheMtd(cacheKey, dbKey, curTotal);
                                                return curTotal;
                                        } catch (Exception curEx) {
                                                logger.warn("[CUR] Last month spend failed for {} — falling back to CE: {}",
                                                                accountId, curEx.getMessage());
                                        }
                                }

                                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                                DateInterval interval = DateInterval.builder()
                                                .start(start.toString())
                                                .end(end.toString())
                                                .build();

                                GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                                                .timePeriod(interval)
                                                .granularity(Granularity.MONTHLY)
                                                .metrics("UnblendedCost")
                                                .build();

                                GetCostAndUsageResponse response = ce.getCostAndUsage(request);

                                double total = response.resultsByTime().stream()
                                                .map(r -> r.total().get("UnblendedCost").amount())
                                                .mapToDouble(Double::parseDouble)
                                                .sum();

                                cacheMtd(cacheKey, dbKey, total);
                                return total;
                        } catch (Exception e) {
                                logger.error("❌ Error fetching Last Month cost for account {}: {}", accountId,
                                                e.getMessage());
                                return 0.0;
                        }
                });
        }

        // --- END OF NEW METHODS ---

        @Async("awsTaskExecutor")
        public CompletableFuture<List<DetailedCostDto>> getCostBreakdownByServiceAndRegion(
                        String accountId, LocalDate startDate, LocalDate endDate, boolean forceRefresh) {

                // Stable cache key: use end-of-month for current-month queries.
                LocalDate normalizedEnd = normalizeEndDate(endDate);
                YearMonth startYm = YearMonth.from(startDate);
                YearMonth endYm   = YearMonth.from(endDate);

                // Normalize startDate to first-of-month when the entire query is within the
                // current month. Collapses "yesterday→yesterday" (daily email scheduler) and
                // "first-of-month→today" (Wed/Sun scheduler) onto the same cache key and CE
                // query, eliminating a guaranteed daily CE hit from the email scheduler.
                LocalDate effectiveStart = (startYm.equals(endYm) && startYm.equals(YearMonth.now()))
                        ? startYm.atDay(1)
                        : startDate;

                String cacheKey = "costBreakdownServiceRegion-" + accountId + "-" + effectiveStart + "-" + normalizedEnd;
                String dbKey = "AWS_COST_BREAKDOWN_SVC_REGION::" + accountId + "::" + effectiveStart + "::" + normalizedEnd;

                // ── Closed-month snapshot check ──────────────────────────────────────────
                // When the entire date range falls within a single closed month, serve from
                // the permanent monthly snapshot. Immutable — check regardless of forceRefresh
                // so the nightly scheduler never wastes a CE call here.
                if (startYm.equals(endYm) && CostDataSnapshotService.isClosedMonth(startYm)) {
                        Optional<List<DetailedCostDto>> snap = snapshotService.readSvcRegion(accountId, startYm);
                        if (snap.isPresent()) {
                                logger.info("✅ Monthly snapshot hit for svc/region breakdown ({}) — account {}",
                                        startYm, accountId);
                                redisCache.put(cacheKey, snap.get(), 60 * 24);
                                return CompletableFuture.completedFuture(snap.get());
                        }
                }

                if (!forceRefresh) {
                        Optional<List<DetailedCostDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
                        });
                        if (cachedData.isPresent()) {
                                logger.info("✅ Cache hit for detailed cost breakdown: {}", cacheKey);
                                return CompletableFuture.completedFuture(cachedData.get());
                        }
                        try {
                                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                                if (dbCached.isPresent()) {
                                        List<DetailedCostDto> dbData = objectMapper.readValue(
                                                        dbCached.get().getJsonData(), new TypeReference<List<DetailedCostDto>>() {});
                                        if (dbData != null) {
                                                redisCache.put(cacheKey, dbData, 60 * 24);
                                                logger.info("✅ DB snapshot hit for cost breakdown svc/region — re-warmed Redis: {}", dbKey);
                                                return CompletableFuture.completedFuture(dbData);
                                        }
                                }
                        } catch (Exception e) {
                                logger.warn("DB cache miss for {}: {}", dbKey, e.getMessage());
                        }
                }

                // Return mock data if mock mode is enabled
                if (useMockData) {
                        List<DetailedCostDto> mockResult = costMockService
                                        .getMockCostBreakdownByServiceAndRegion(accountId, effectiveStart, normalizedEnd);
                        redisCache.put(cacheKey, mockResult, 60 * 24);
                        return CompletableFuture.completedFuture(mockResult);
                }

                CloudAccount account = getAccount(accountId);

                // CUR first. Grouping by two dimensions at once is a single GROUP BY here,
                // where CE charges for the same nested-group request.
                List<DetailedCostDto> curCosts = tryCurServiceRegionBreakdown(account, effectiveStart, normalizedEnd);
                if (curCosts != null) {
                        redisCache.put(cacheKey, curCosts, 60 * 24);
                        try {
                                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                                snapshot.setCacheKey(dbKey);
                                snapshot.setJsonData(objectMapper.writeValueAsString(curCosts));
                                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                                cachedDataRepository.save(snapshot);
                        } catch (Exception dbEx) {
                                logger.warn("Failed to save cost svc/region DB snapshot {}: {}", dbKey, dbEx.getMessage());
                        }
                        return CompletableFuture.completedFuture(curCosts);
                }

                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                return CompletableFuture.supplyAsync(() -> {
                        try {
                                DateInterval dateInterval = DateInterval.builder()
                                                .start(effectiveStart.toString())
                                                .end(normalizedEnd.plusDays(1).toString()) // CE is exclusive of end date
                                                .build();

                                List<GroupDefinition> groupBy = List.of(
                                                GroupDefinition.builder().type(GroupDefinitionType.DIMENSION)
                                                                .key("SERVICE").build(),
                                                GroupDefinition.builder().type(GroupDefinitionType.DIMENSION)
                                                                .key("REGION").build());

                                GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                                                .timePeriod(dateInterval)
                                                .granularity(Granularity.MONTHLY) // Aggregate over the period
                                                .metrics("UnblendedCost")
                                                .groupBy(groupBy)
                                                .build();

                                List<DetailedCostDto> costs = ce.getCostAndUsage(request).resultsByTime().stream()
                                                .flatMap(r -> r.groups().stream())
                                                .map(group -> new DetailedCostDto(
                                                                group.keys().get(0), // Service
                                                                group.keys().get(1), // Region
                                                                Double.parseDouble(group.metrics().get("UnblendedCost")
                                                                                .amount())))
                                                .filter(cost -> cost.getCost() > 0.01) // Filter out zero/negligible
                                                                                       // costs
                                                .sorted((a, b) -> Double.compare(b.getCost(), a.getCost()))
                                                .collect(Collectors.toList());

                                redisCache.put(cacheKey, costs, 60 * 24); // Cache for 6 hours
                                try {
                                        CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                                        snapshot.setCacheKey(dbKey);
                                        snapshot.setJsonData(objectMapper.writeValueAsString(costs));
                                        snapshot.setLastUpdated(java.time.LocalDateTime.now());
                                        cachedDataRepository.save(snapshot);
                                } catch (Exception dbEx) {
                                        logger.warn("Failed to save cost svc/region DB snapshot {}: {}", dbKey, dbEx.getMessage());
                                }
                                logger.info("✅ Detailed cost breakdown fetched: {} items for account {}", costs.size(),
                                                accountId);
                                return costs;

                        } catch (Exception e) {
                                logger.error("❌ Error fetching detailed cost breakdown for account {}: {}", accountId,
                                                e.getMessage(), e);
                                return Collections.emptyList();
                        }
                });
        }

        /**
         * Service-and-region breakdown from a CUR, or null for Cost Explorer.
         */
        private List<DetailedCostDto> tryCurServiceRegionBreakdown(CloudAccount account,
                        LocalDate start, LocalDate end) {
                try {
                        Optional<CurSource> source = curSourceResolver.resolve(account, start, end);
                        if (source.isEmpty()) {
                                return null;
                        }
                        return curCostDataSource.getCostByServiceAndRegion(source.get(), account, start, end)
                                        .stream()
                                        .map(c -> new DetailedCostDto(c.service(), c.region(), c.cost()))
                                        .collect(Collectors.toList());
                } catch (Exception e) {
                        logger.warn("[CUR] Service/region breakdown failed for {} — falling back to CE: {}",
                                        account.getAwsAccountId(), e.getMessage());
                        return null;
                }
        }

        /**
         * Get cost breakdown by dimension (SERVICE, REGION, INSTANCE_TYPE, etc.)
         */
        @Async("awsTaskExecutor")
        public CompletableFuture<List<CostDto>> getCostBreakdown(
                        String accountId, String groupBy, String tag, boolean forceRefresh, String startDate,
                        String endDate) {

                // Stable cache key: use end-of-month for current-month queries so the key
                // doesn't change daily between nightly scheduler runs.
                // Pass startDate so mid-month comparison ranges (e.g. Jun 8–15) are not stretched.
                String normalizedEnd = normalizeEndDate(startDate, endDate);
                String cacheKey = "costBreakdown-" + accountId + "-" + groupBy + "-" + (tag != null ? tag : "") + "-"
                                + startDate + "-" + normalizedEnd;
                String dbKey = "AWS_COST_BREAKDOWN::" + accountId + "::" + groupBy + "::" + (tag != null ? tag : "")
                                + "::" + startDate + "::" + normalizedEnd;

                // ── Closed-month snapshot check ──────────────────────────────────────────
                // For SERVICE or REGION groupBy over a single closed calendar month, serve
                // directly from the permanent monthly snapshot — zero CE API calls.
                if (!forceRefresh && tag == null && startDate != null && endDate != null) {
                        try {
                                YearMonth startYm = YearMonth.from(LocalDate.parse(startDate));
                                YearMonth endYm   = YearMonth.from(LocalDate.parse(endDate));
                                if (startYm.equals(endYm) && CostDataSnapshotService.isClosedMonth(startYm)) {
                                        Optional<List<CostDto>> snap = "SERVICE".equalsIgnoreCase(groupBy)
                                                ? snapshotService.readBreakdownSvc(accountId, startYm)
                                                : "REGION".equalsIgnoreCase(groupBy)
                                                        ? snapshotService.readBreakdownRgn(accountId, startYm)
                                                        : Optional.empty();
                                        if (snap.isPresent()) {
                                                logger.info("✅ Monthly snapshot hit for {} breakdown ({}) — account {}",
                                                        groupBy, startYm, accountId);
                                                redisCache.put(cacheKey, snap.get(), 60 * 24);
                                                return CompletableFuture.completedFuture(snap.get());
                                        }
                                }
                        } catch (Exception e) {
                                logger.debug("Snapshot check skipped for getCostBreakdown ({}): {}", accountId, e.getMessage());
                        }
                }

                if (!forceRefresh) {
                        // 1. Check Redis first (fast, no DB connection consumed)
                        Optional<List<CostDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
                        });
                        if (cachedData.isPresent()) {
                                logger.info("✅ Redis cache hit for cost breakdown: {}", cacheKey);
                                return CompletableFuture.completedFuture(cachedData.get());
                        }

                        // 2. DB fallback — only used when Redis is cold (after restart/deployment)
                        Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                        if (dbCached.isPresent()) {
                                try {
                                        List<CostDto> dbData = objectMapper.readValue(dbCached.get().getJsonData(),
                                                        new TypeReference<List<CostDto>>() {});
                                        logger.info("✅ DB snapshot hit for cost breakdown — repopulating Redis: {}", dbKey);
                                        redisCache.put(cacheKey, dbData, 60 * 24); // re-warm Redis so next request skips DB
                                        return CompletableFuture.completedFuture(dbData);
                                } catch (Exception e) {
                                        logger.warn("Failed to deserialize cost DB snapshot {}: {}", dbKey, e.getMessage());
                                }
                        }
                }

                // Return mock data if mock mode is enabled
                if (useMockData) {
                        List<CostDto> mockResult = costMockService.getMockCostBreakdown(accountId, groupBy, tag,
                                        startDate, endDate);
                        redisCache.put(cacheKey, mockResult, 60 * 24);
                        return CompletableFuture.completedFuture(mockResult);
                }

                CloudAccount account = getAccount(accountId);

                // ── CUR first, Cost Explorer as fallback ────────────────────────────────
                // This is the single swap point for the cost story: the Dashboard cost card,
                // the Cost page breakdown and the FinOps billing summary all read through
                // here (see FinOpsService.getBillingSummary), so moving this one call moves
                // all three pages together.
                //
                // The fallback is per query, not per account. An account backed by a CUR
                // still routes to CE for ranges the report cannot cover — history before the
                // report existed, or a granularity it was not created with — and the
                // resolver decides that, not the caller.
                List<CostDto> curCosts = tryCurBreakdown(account, groupBy, tag, startDate, normalizedEnd);
                if (curCosts != null) {
                        cacheBreakdown(cacheKey, dbKey, curCosts);
                        return CompletableFuture.completedFuture(curCosts);
                }

                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                return CompletableFuture.supplyAsync(() -> {
                        try {
                                LocalDate parsedEndDate = LocalDate.parse(normalizedEnd);
                                DateInterval dateInterval = DateInterval.builder()
                                                .start(startDate)
                                                .end(parsedEndDate.plusDays(1).toString())
                                                .build();

                                GroupDefinition groupDefinition = "TAG".equalsIgnoreCase(groupBy)
                                                ? GroupDefinition.builder().type(GroupDefinitionType.TAG).key(tag)
                                                                .build()
                                                : GroupDefinition.builder().type(GroupDefinitionType.DIMENSION)
                                                                .key(groupBy).build();

                                GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                                                .timePeriod(dateInterval)
                                                .granularity(Granularity.MONTHLY)
                                                .metrics("UnblendedCost")
                                                .groupBy(groupDefinition)
                                                .build();

                                // Aggregate costs across all time periods (fixes data mismatch with FinOps)
                                Map<String, Double> costMap = new HashMap<>();
                                ce.getCostAndUsage(request).resultsByTime().forEach(timePeriod -> {
                                        timePeriod.groups().forEach(group -> {
                                                String name = group.keys().get(0);
                                                if (name == null || name.isBlank()) {
                                                        name = "Untagged";
                                                }
                                                double cost = Double.parseDouble(group.metrics().get("UnblendedCost").amount());
                                                costMap.merge(name, cost, Double::sum);
                                        });
                                });

                                List<CostDto> costs = costMap.entrySet().stream()
                                                .map(e -> new CostDto(e.getKey(), e.getValue()))
                                                .filter(cost -> cost.getAmount() > 0.01)
                                                .sorted((a, b) -> Double.compare(b.getAmount(), a.getAmount()))
                                                .collect(Collectors.toList());

                                cacheBreakdown(cacheKey, dbKey, costs);
                                logger.info("✅ Cost breakdown fetched: {} items for account {}", costs.size(),
                                                accountId);
                                return costs;

                        } catch (Exception e) {
                                logger.error("❌ Error fetching cost breakdown for account {}: {}", accountId,
                                                e.getMessage(), e);
                                return Collections.emptyList();
                        }
                });
        }

        /**
         * Attempts the breakdown from a CUR, returning null when Cost Explorer should
         * be used instead.
         *
         * <p>Null rather than an empty list on purpose: an empty result is a real
         * answer — an account genuinely spent nothing — and collapsing the two would
         * make every zero-spend account fall through to a second, redundant CE call.
         *
         * <p>A CUR read that throws also returns null, so a broken Athena setup
         * degrades to the previous behaviour rather than emptying the cost pages.
         */
        private List<CostDto> tryCurBreakdown(CloudAccount account, String groupBy, String tag,
                        String startDate, String normalizedEnd) {
                if (startDate == null || normalizedEnd == null) {
                        return null;
                }
                try {
                        LocalDate start = LocalDate.parse(startDate);
                        LocalDate end = LocalDate.parse(normalizedEnd);

                        Optional<CurSource> source = curSourceResolver.resolve(account, start, end);
                        if (source.isEmpty()) {
                                return null;
                        }
                        return curCostDataSource.getCostBreakdown(source.get(), account, groupBy, tag, start, end);
                } catch (Exception e) {
                        logger.warn("[CUR] Breakdown failed for account {} — falling back to Cost Explorer: {}",
                                        account.getAwsAccountId(), e.getMessage());
                        return null;
                }
        }

        /**
         * Writes a breakdown to Redis and the DB snapshot.
         *
         * <p>Shared by both the CUR and Cost Explorer paths so a result is cached
         * identically however it was produced — the cache is read by the Dashboard,
         * Cost and FinOps pages without knowing which source filled it.
         */
        /**
         * Refreshes this month's cost data from the CUR only, refusing to fall back.
         *
         * <p>Exists because the normal refresh cannot answer "is this account actually
         * reading from the report?". That path tries the CUR first and silently drops to
         * Cost Explorer on any problem — correct for serving a page, useless for
         * verifying a setup, because a working fallback looks exactly like a working CUR.
         *
         * <p>So this one throws where the normal path would degrade. Every reason a query
         * would have fallen back becomes a message naming it.
         *
         * <p>Writes into the same Redis and DB keys the Dashboard, Cost and FinOps pages
         * read, so a caller can refetch the dashboard afterwards and see genuinely
         * CUR-derived numbers rather than a separate preview.
         */
        public Map<String, Object> refreshFromCurOnly(String accountId) {
                CloudAccount account = getAccount(accountId);

                LocalDate start = LocalDate.now().withDayOfMonth(1);
                // End of month, not today, so this matches exactly what the dashboard asks
                // for — normalizeEndDate stretches a current-month range the same way. A
                // narrower range here made the button disagree with the page it refreshes
                // ($964 against $2,254), because the period-spanning fee rows that are
                // already on the bill fall outside a to-date window.
                LocalDate today = YearMonth.now().atEndOfMonth();

                // Finish whatever setup is outstanding rather than telling the caller to go
                // and do it by hand. Onboarding already asked where cost data comes from;
                // requiring three further API calls to act on that answer is not a
                // reasonable thing to put in front of someone pressing a refresh button.
                // Idempotent — an account already wired up just gets its existing source.
                CurSource source = curAutoRegistrationService.ensureRegistered(account);

                if (curSourceResolver.resolve(account, start, today).isEmpty()) {
                        // Registered and ACTIVE, yet the resolver still declines. The reason is
                        // never the setup at this point — it is the flag being off, or the range
                        // falling outside what the report covers.
                        throw new IllegalStateException(
                                        "The report for account " + accountId + " is connected but cannot serve "
                                        + YearMonth.from(start) + ". Check that xamops.cur.enabled is true, and that"
                                        + " the report covers this period (it starts at "
                                        + source.getEarliestBillingPeriod() + ").");
                }

                // Deliberately not wrapped in try/catch: an Athena failure must surface to
                // the caller with its own message, which is the whole point of this method.
                List<CostDto> breakdown = curCostDataSource.getCostBreakdown(
                                source, account, "SERVICE", null, start, today);
                double total = breakdown.stream().mapToDouble(CostDto::getAmount).sum();

                // Same keys the shared cache uses, so the pages pick this up. End-of-month
                // matches normalizeEndDate's behaviour for a current-month range — a
                // different key here would write a cache entry nothing ever reads.
                String endKey = YearMonth.now().atEndOfMonth().toString();
                cacheBreakdown(
                                "costBreakdown-" + accountId + "-SERVICE--" + start + "-" + endKey,
                                "AWS_COST_BREAKDOWN::" + accountId + "::SERVICE::::" + start + "::" + endKey,
                                breakdown);
                cacheMtd("mtdCost-" + accountId, "AWS_MTD_COST::" + accountId, total);

                logger.info("✅ [CUR] Forced CUR-only refresh for {} — {} services, total {}",
                                accountId, breakdown.size(), total);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("source", "CUR");
                result.put("curSourceId", source.getId());
                result.put("s3Bucket", source.getS3Bucket());
                result.put("timeUnit", source.getTimeUnit());
                result.put("monthToDateCost", total);
                result.put("serviceCount", breakdown.size());
                result.put("services", breakdown);
                result.put("period", start + " to " + today);
                return result;
        }

        private void cacheBreakdown(String cacheKey, String dbKey, List<CostDto> costs) {
                redisCache.put(cacheKey, costs, 60 * 24);
                try {
                        CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                        snapshot.setCacheKey(dbKey);
                        snapshot.setJsonData(objectMapper.writeValueAsString(costs));
                        snapshot.setLastUpdated(java.time.LocalDateTime.now());
                        cachedDataRepository.save(snapshot);
                        logger.info("✅ Cost breakdown saved to DB snapshot: {}", dbKey);
                } catch (Exception e) {
                        logger.warn("Failed to save cost DB snapshot {}: {}", dbKey, e.getMessage());
                }
        }

        /**
         * Builds the daily historical series from a CUR, or null for Cost Explorer.
         *
         * <p>Requires a daily-or-finer report, so the resolver is asked with
         * {@code requiresDaily}: a MONTHLY CUR cannot produce this shape at all and
         * routes to CE rather than returning a series with one point per month.
         */
        private HistoricalCostDto tryCurHistoricalCost(CloudAccount account, String serviceName,
                        String regionName, int days) {
                try {
                        LocalDate end = LocalDate.now();
                        LocalDate start = end.minusDays(days);

                        Optional<CurSource> source = curSourceResolver.resolve(account, start, end, true);
                        if (source.isEmpty()) {
                                return null;
                        }

                        Map<LocalDate, Double> daily = curCostDataSource.getDailyTrend(
                                        source.get(), account, start, end, serviceName, regionName);

                        // Walk the calendar, not the rows: a day with no usage has no rows in the
                        // CUR, and skipping it would shorten the series and shift every later point.
                        // CE cannot hit this because it returns a bucket per day regardless.
                        List<String> labels = new ArrayList<>();
                        List<Double> costs = new ArrayList<>();
                        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                                labels.add(d.format(DateTimeFormatter.ISO_LOCAL_DATE));
                                costs.add(daily.getOrDefault(d, 0.0));
                        }
                        logger.info("✅ [CUR] Historical cost ({} days) for account {}", days,
                                        account.getAwsAccountId());
                        return new HistoricalCostDto(labels, costs);
                } catch (Exception e) {
                        logger.warn("[CUR] Historical cost failed for account {} — falling back to Cost Explorer: {}",
                                        account.getAwsAccountId(), e.getMessage());
                        return null;
                }
        }

        /**
         * Get historical cost data with flexible filtering
         */
        @Async("awsTaskExecutor")
        public CompletableFuture<HistoricalCostDto> getHistoricalCost(
                        String accountId, String serviceName, String regionName, int days, boolean forceRefresh) {

                String cacheKey = String.format("historicalCost-%s-%s-%s-%d",
                                accountId,
                                serviceName != null ? serviceName : "ALL",
                                regionName != null ? regionName : "ALL",
                                days);
                String dbKey = String.format("AWS_COST_HISTORY::%s::%s::%s::%d",
                                accountId,
                                serviceName != null ? serviceName : "ALL",
                                regionName != null ? regionName : "ALL",
                                days);

                if (!forceRefresh) {
                        Optional<HistoricalCostDto> cachedData = redisCache.get(cacheKey, HistoricalCostDto.class);
                        if (cachedData.isPresent()) {
                                logger.info("✅ Redis cache hit for historical cost: {}", cacheKey);
                                return CompletableFuture.completedFuture(cachedData.get());
                        }
                        // DB fallback
                        try {
                                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                                if (dbSnapshot.isPresent()) {
                                        HistoricalCostDto dbResult = objectMapper.readValue(
                                                        dbSnapshot.get().getJsonData(), HistoricalCostDto.class);
                                        logger.info("✅ Historical cost loaded from DB for {} — re-warming Redis", accountId);
                                        redisCache.put(cacheKey, dbResult, 60 * 24);
                                        return CompletableFuture.completedFuture(dbResult);
                                }
                        } catch (Exception e) {
                                logger.warn("DB snapshot load failed for historical cost {}: {}", accountId, e.getMessage());
                        }
                }

                // ── Compose from daily snapshots for closed months ────────────────
                // Only for the all-services (no filter) case — service/region filtered
                // requests fall through to CE API as before.
                if (serviceName == null && regionName == null) {
                        try {
                                LocalDate today    = LocalDate.now();
                                LocalDate rangeEnd = today;
                                LocalDate rangeStart = today.minusDays(days);

                                List<String> composedLabels = new ArrayList<>();
                                List<Double> composedCosts  = new ArrayList<>();
                                boolean allClosedMonthsCovered = true;

                                // Iterate day by day; serve closed months from snapshot, skip current
                                YearMonth currentYm = YearMonth.now();
                                for (LocalDate d = rangeStart; !d.isAfter(rangeEnd); d = d.plusDays(1)) {
                                        YearMonth dYm = YearMonth.from(d);
                                        if (!dYm.isBefore(currentYm)) {
                                                // Current month — cannot serve from snapshot
                                                allClosedMonthsCovered = false;
                                                break;
                                        }
                                        Optional<List<CostDataSnapshotService.DailyCostEntry>> dailySnap =
                                                snapshotService.readDailyCost(accountId, dYm);
                                        if (!dailySnap.isPresent()) {
                                                allClosedMonthsCovered = false;
                                                break;
                                        }
                                        String dateStr = d.toString();
                                        double amount = dailySnap.get().stream()
                                                .filter(e -> dateStr.equals(e.getDate()))
                                                .mapToDouble(CostDataSnapshotService.DailyCostEntry::getAmount)
                                                .sum();
                                        composedLabels.add(dateStr);
                                        composedCosts.add(amount);
                                }

                                if (allClosedMonthsCovered && !composedLabels.isEmpty()) {
                                        HistoricalCostDto result = new HistoricalCostDto(composedLabels, composedCosts);
                                        redisCache.put(cacheKey, result, 60 * 24);
                                        logger.info("✅ Historical cost ({} days) composed from daily snapshots — account {}",
                                                days, accountId);
                                        return CompletableFuture.completedFuture(result);
                                }
                        } catch (Exception e) {
                                logger.debug("Daily snapshot composition skipped for {}: {}", accountId, e.getMessage());
                        }
                }

                // ── Per-service: proportional approximation from snapshots ──────────
                // Distributes service monthly total across days proportionally:
                //   dayServiceCost = dailyTotal × (serviceMonthlyTotal / monthlyTotal)
                if (serviceName != null && !serviceName.isBlank() && !"ALL".equalsIgnoreCase(serviceName)) {
                        try {
                                LocalDate today      = LocalDate.now();
                                LocalDate rangeEnd   = today;
                                LocalDate rangeStart = today.minusDays(days);

                                List<String> svcLabels = new ArrayList<>();
                                List<Double> svcCosts  = new ArrayList<>();
                                boolean allSvcCovered  = true;

                                for (LocalDate d = rangeStart; !d.isAfter(rangeEnd); d = d.plusDays(1)) {
                                        YearMonth dYm = YearMonth.from(d);
                                        if (!dYm.isBefore(YearMonth.now())) {
                                                allSvcCovered = false;
                                                break;
                                        }
                                        Optional<List<CostDataSnapshotService.DailyCostEntry>> dailySnap =
                                                snapshotService.readDailyCost(accountId, dYm);
                                        Optional<Double> monthTotal =
                                                snapshotService.readMonthlyTotal(accountId, dYm);
                                        Optional<List<CostDto>> svcBreakdown =
                                                snapshotService.readBreakdownSvc(accountId, dYm);

                                        if (!dailySnap.isPresent() || !monthTotal.isPresent() || !svcBreakdown.isPresent()) {
                                                allSvcCovered = false;
                                                break;
                                        }

                                        double serviceMonthlyTotal = svcBreakdown.get().stream()
                                                .filter(c -> serviceName.equals(c.getName()))
                                                .mapToDouble(CostDto::getAmount)
                                                .sum();
                                        double monthlyTotal = monthTotal.get();
                                        String dateStr = d.toString();
                                        double dayTotal = dailySnap.get().stream()
                                                .filter(e -> dateStr.equals(e.getDate()))
                                                .mapToDouble(CostDataSnapshotService.DailyCostEntry::getAmount)
                                                .sum();
                                        double dayCostForService = monthlyTotal > 0
                                                ? dayTotal * (serviceMonthlyTotal / monthlyTotal)
                                                : 0.0;
                                        svcLabels.add(dateStr);
                                        svcCosts.add(dayCostForService);
                                }

                                if (allSvcCovered && !svcLabels.isEmpty()) {
                                        HistoricalCostDto result = new HistoricalCostDto(svcLabels, svcCosts);
                                        redisCache.put(cacheKey, result, 60 * 24);
                                        logger.info("✅ Service '{}' historical cost ({} days) approximated from snapshots — account {}",
                                                serviceName, days, accountId);
                                        return CompletableFuture.completedFuture(result);
                                }
                        } catch (Exception e) {
                                logger.debug("Service snapshot approximation skipped for {}/{}: {}",
                                        accountId, serviceName, e.getMessage());
                        }
                }

                // Return mock data if mock mode is enabled
                if (useMockData) {
                        HistoricalCostDto mockResult = costMockService.getMockHistoricalCost(accountId, serviceName,
                                        regionName, days);
                        redisCache.put(cacheKey, mockResult, 60 * 24);
                        return CompletableFuture.completedFuture(mockResult);
                }

                logger.info("📊 Fetching historical cost - Account: {}, Service: {}, Region: {}, Days: {}",
                                accountId, serviceName, regionName, days);

                CloudAccount account = getAccount(accountId);

                // CUR first. This endpoint is the one the snapshot layer above can only
                // partially serve — a service- or region-filtered request always fell
                // through to a CE call, and CUR answers those at the same cost as any other.
                HistoricalCostDto curHistory = tryCurHistoricalCost(account, serviceName, regionName, days);
                if (curHistory != null) {
                        redisCache.put(cacheKey, curHistory, 60 * 24);
                        try {
                                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                                snapshot.setCacheKey(dbKey);
                                snapshot.setJsonData(objectMapper.writeValueAsString(curHistory));
                                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                                cachedDataRepository.save(snapshot);
                        } catch (Exception dbEx) {
                                logger.warn("Failed to save historical cost DB snapshot {}: {}", dbKey, dbEx.getMessage());
                        }
                        return CompletableFuture.completedFuture(curHistory);
                }

                CostExplorerClient ceClient = awsClientProvider.getCostExplorerClient(account);

                return CompletableFuture.supplyAsync(() -> {
                        List<String> labels = new ArrayList<>();
                        List<Double> costs = new ArrayList<>();

                        try {
                                LocalDate endDate = LocalDate.now();
                                LocalDate startDate = endDate.minusDays(days);

                                // Build filter expressions
                                List<Expression> andExpressions = new ArrayList<>();

                                if (serviceName != null && !serviceName.isBlank()
                                                && !"ALL".equalsIgnoreCase(serviceName)) {
                                        andExpressions.add(Expression.builder()
                                                        .dimensions(DimensionValues.builder()
                                                                        .key(Dimension.SERVICE)
                                                                        .values(serviceName)
                                                                        .build())
                                                        .build());
                                }

                                if (regionName != null && !regionName.isBlank()
                                                && !"ALL".equalsIgnoreCase(regionName)) {
                                        andExpressions.add(Expression.builder()
                                                        .dimensions(DimensionValues.builder()
                                                                        .key(Dimension.REGION)
                                                                        .values(regionName)
                                                                        .build())
                                                        .build());
                                }

                                Expression finalFilter = null;
                                if (!andExpressions.isEmpty()) {
                                        finalFilter = andExpressions.size() == 1
                                                        ? andExpressions.get(0)
                                                        : Expression.builder().and(andExpressions).build();
                                }

                                // Build request
                                GetCostAndUsageRequest.Builder requestBuilder = GetCostAndUsageRequest.builder()
                                                .timePeriod(DateInterval.builder()
                                                                .start(startDate.toString())
                                                                .end(endDate.plusDays(1).toString())
                                                                .build())
                                                .granularity(Granularity.DAILY)
                                                .metrics("UnblendedCost");

                                if (finalFilter != null) {
                                        requestBuilder.filter(finalFilter);
                                }

                                GetCostAndUsageRequest request = requestBuilder.build();

                                // Fetch data
                                for (ResultByTime result : ceClient.getCostAndUsage(request).resultsByTime()) {
                                        labels.add(LocalDate.parse(result.timePeriod().start())
                                                        .format(DateTimeFormatter.ISO_LOCAL_DATE));
                                        double cost = Double.parseDouble(result.total().get("UnblendedCost").amount());
                                        costs.add(cost);
                                }

                                HistoricalCostDto historicalCost = new HistoricalCostDto(labels, costs);
                                redisCache.put(cacheKey, historicalCost, 60 * 24);
                                try {
                                        CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                                        snapshot.setCacheKey(dbKey);
                                        snapshot.setJsonData(objectMapper.writeValueAsString(historicalCost));
                                        snapshot.setLastUpdated(java.time.LocalDateTime.now());
                                        cachedDataRepository.save(snapshot);
                                        logger.info("✅ Historical cost saved to DB snapshot: {}", dbKey);
                                } catch (Exception e) {
                                        logger.warn("Failed to save historical cost DB snapshot {}: {}", dbKey, e.getMessage());
                                }

                                logger.info("✅ Historical cost fetched: {} days for account {}", labels.size(),
                                                accountId);
                                return historicalCost;

                        } catch (Exception e) {
                                logger.error("❌ Error fetching historical cost for account {}: {}", accountId,
                                                e.getMessage(), e);
                                return new HistoricalCostDto(Collections.emptyList(), Collections.emptyList());
                        }
                });
        }

        /**
         */
        @Async("awsTaskExecutor")
        public CompletableFuture<HistoricalCostDto> getHistoricalCostForDimension(
                        String accountId, String groupBy, String dimensionValue, String tagKey, boolean forceRefresh) {

                String cacheKey = "historicalCostDim-" + accountId + "-" + groupBy + "-" + dimensionValue + "-" +
                                (tagKey != null ? tagKey : "");
                String dbKey = "AWS_HIST_COST_DIM::" + accountId + "::" + groupBy + "::" + dimensionValue + "::"
                                + (tagKey != null ? tagKey : "");

                if (!forceRefresh) {
                        Optional<HistoricalCostDto> cachedData = redisCache.get(cacheKey, HistoricalCostDto.class);
                        if (cachedData.isPresent()) {
                                logger.info("✅ Cache hit for historical cost dimension: {}", cacheKey);
                                return CompletableFuture.completedFuture(cachedData.get());
                        }
                        try {
                                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                                if (dbCached.isPresent()) {
                                        HistoricalCostDto dbData = objectMapper.readValue(
                                                        dbCached.get().getJsonData(), HistoricalCostDto.class);
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
                        HistoricalCostDto mockResult = costMockService.getMockHistoricalCostForDimension(accountId,
                                        groupBy, dimensionValue, tagKey);
                        redisCache.put(cacheKey, mockResult, 60 * 24);
                        return CompletableFuture.completedFuture(mockResult);
                }

                CloudAccount account = getAccount(accountId);
                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                return CompletableFuture.supplyAsync(() -> {
                        try {
                                List<String> labels = new ArrayList<>();
                                List<Double> costs = new ArrayList<>();

                                for (int i = 5; i >= 0; i--) {
                                        LocalDate month = LocalDate.now().minusMonths(i);
                                        YearMonth ym = YearMonth.from(month);
                                        labels.add(month.format(DateTimeFormatter.ofPattern("MMM yyyy")));

                                        // ── Use snapshot for closed months ─────────────────────────────────
                                        if (CostDataSnapshotService.isClosedMonth(ym)) {
                                                Optional<List<CostDto>> snapSvc = "SERVICE".equalsIgnoreCase(groupBy)
                                                        ? snapshotService.readBreakdownSvc(accountId, ym)
                                                        : Optional.empty();
                                                Optional<List<CostDto>> snapRgn = "REGION".equalsIgnoreCase(groupBy)
                                                        ? snapshotService.readBreakdownRgn(accountId, ym)
                                                        : Optional.empty();
                                                Optional<List<CostDto>> snapToUse = snapSvc.isPresent() ? snapSvc : snapRgn;

                                                if (snapToUse.isPresent()) {
                                                        double cost = snapToUse.get().stream()
                                                                .filter(c -> dimensionValue.equals(c.getName()))
                                                                .mapToDouble(CostDto::getAmount)
                                                                .sum();
                                                        costs.add(cost);
                                                        logger.debug("✅ Snapshot used for {} / {} / {}", accountId, ym, dimensionValue);
                                                        continue;
                                                }
                                        }

                                        // ── Fall through to CE API (current month or snapshot missing) ─────
                                        DateInterval dateInterval = DateInterval.builder()
                                                        .start(month.withDayOfMonth(1).toString())
                                                        .end(month.plusMonths(1).withDayOfMonth(1).toString())
                                                        .build();

                                        Expression filter = Expression.builder()
                                                        .dimensions(DimensionValues.builder()
                                                                        .key(groupBy)
                                                                        .values(dimensionValue)
                                                                        .build())
                                                        .build();

                                        GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                                                        .timePeriod(dateInterval)
                                                        .granularity(Granularity.MONTHLY)
                                                        .metrics("UnblendedCost")
                                                        .filter(filter)
                                                        .build();

                                        double cost = Double.parseDouble(
                                                        ce.getCostAndUsage(request)
                                                                        .resultsByTime()
                                                                        .get(0)
                                                                        .total()
                                                                        .get("UnblendedCost")
                                                                        .amount());
                                        costs.add(cost);
                                }

                                HistoricalCostDto historicalCost = new HistoricalCostDto(labels, costs);
                                redisCache.put(cacheKey, historicalCost, 60 * 24);
                                try {
                                        CachedData snapshot = cachedDataRepository.findById(dbKey)
                                                        .orElse(new CachedData());
                                        snapshot.setCacheKey(dbKey);
                                        snapshot.setJsonData(objectMapper.writeValueAsString(historicalCost));
                                        snapshot.setLastUpdated(java.time.LocalDateTime.now());
                                        cachedDataRepository.save(snapshot);
                                } catch (Exception dbEx) {
                                        logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
                                }

                                logger.info("✅ Historical cost for dimension fetched: 6 months for {}", dimensionValue);
                                return historicalCost;

                        } catch (Exception e) {
                                logger.error("❌ Error fetching historical cost for dimension {}: {}", dimensionValue,
                                                e.getMessage(), e);
                                return new HistoricalCostDto(Collections.emptyList(), Collections.emptyList());
                        }
                });
        }

        @Async("awsTaskExecutor")
        public CompletableFuture<CostForecastDto> getCostForecast(
                        String accountId, String serviceName, String regionName, int periods, boolean forceRefresh) {

                // Return mock data if mock mode is enabled
                if (useMockData) {
                        CostForecastDto mockResult = costMockService.getMockCostForecast(accountId, serviceName,
                                        periods);
                        return CompletableFuture.completedFuture(mockResult);
                }

                logger.info("🔮 Generating cost forecast - Account: {}, Service: {}, Region: {}, Periods: {}",
                                accountId, serviceName, regionName, periods);

                return forecastingService.getCostForecast(accountId, serviceName, periods)
                                .thenApply(forecastDtoList -> {
                                        List<String> dates = forecastDtoList.stream()
                                                        .map(ForecastDto::getDs)
                                                        .collect(Collectors.toList());

                                        List<Double> predictions = forecastDtoList.stream()
                                                        .map(ForecastDto::getYhat)
                                                        .collect(Collectors.toList());

                                        List<Double> lowerBounds = forecastDtoList.stream()
                                                        .map(ForecastDto::getYhatLower)
                                                        .collect(Collectors.toList());

                                        List<Double> upperBounds = forecastDtoList.stream()
                                                        .map(ForecastDto::getYhatUpper)
                                                        .collect(Collectors.toList());

                                        double totalForecast = predictions.stream().mapToDouble(Double::doubleValue)
                                                        .sum();
                                        double avgForecast = predictions.isEmpty() ? 0
                                                        : totalForecast / predictions.size();

                                        logger.info("✅ Forecast: {} FUTURE days, Total=${}, Avg=${}/day",
                                                        dates.size(),
                                                        String.format("%.2f", totalForecast),
                                                        String.format("%.2f", avgForecast));

                                        return new CostForecastDto(dates, predictions, lowerBounds, upperBounds);
                                })
                                .exceptionally(ex -> {
                                        logger.error("❌ Forecast error for account {}: {}", accountId, ex.getMessage(),
                                                        ex);
                                        return new CostForecastDto(
                                                        Collections.emptyList(),
                                                        Collections.emptyList(),
                                                        Collections.emptyList(),
                                                        Collections.emptyList());
                                });
        }

        // -----------------------------------------------------------------------
        // Per-label resource cost (used by ResourceLabelController)
        // -----------------------------------------------------------------------

        /**
         * Maps XamOps resource type strings to the corresponding AWS service names
         * as they appear in Cost Explorer results.
         */
        private static final Map<String, List<String>> RESOURCE_TYPE_TO_SERVICE_NAMES = Map.ofEntries(
                Map.entry("EC2 Instance",          List.of("Amazon Elastic Compute Cloud - Compute", "EC2 - Other")),
                Map.entry("EBS Volume",             List.of("EC2 - Other")),
                Map.entry("Auto Scaling Group",     List.of("Amazon Elastic Compute Cloud - Compute")),
                Map.entry("Load Balancer",          List.of("Amazon Elastic Load Balancing", "EC2 - Other")),
                Map.entry("RDS Instance",           List.of("Amazon Relational Database Service")),
                Map.entry("Lambda Function",        List.of("AWS Lambda")),
                Map.entry("S3 Bucket",              List.of("Amazon Simple Storage Service")),
                Map.entry("EKS Cluster",            List.of("Amazon Elastic Kubernetes Service")),
                Map.entry("ECS Cluster",            List.of("Amazon Elastic Container Service")),
                Map.entry("CloudWatch Log Group",   List.of("Amazon CloudWatch")),
                Map.entry("Route 53 Zone",          List.of("Amazon Route 53")),
                Map.entry("DynamoDB Table",         List.of("Amazon DynamoDB")),
                Map.entry("SNS Topic",              List.of("Amazon Simple Notification Service")),
                Map.entry("SQS Queue",              List.of("Amazon Simple Queue Service")),
                Map.entry("ElastiCache Cluster",    List.of("Amazon ElastiCache")),
                Map.entry("API Gateway",            List.of("Amazon API Gateway")),
                Map.entry("CloudFront",             List.of("Amazon CloudFront")),
                Map.entry("KMS",                    List.of("AWS Key Management Service")),
                Map.entry("NAT Gateway",            List.of("Amazon Virtual Private Cloud"))
        );

        /**
         * Fetches cost breakdown for a specific set of resource IDs.
         *
         * Attempts AWS Cost Explorer RESOURCE_ID dimension filtering first (requires
         * "Hourly and Resource Level Data" to be enabled in Cost Explorer Settings).
         * Falls back to SERVICE-level filtering grouped by the resource types if
         * resource-level data is not available.
         *
         * Returns a Map with:
         *   "costs"         → List<CostDto>
         *   "resourceLevel" → Boolean (true = resource-level, false = service-level approximation)
         *   "message"       → String (optional note about approximation)
         */
        @Async("awsTaskExecutor")
        public CompletableFuture<Map<String, Object>> getCostForLabelResources(
                        String accountId,
                        List<String> resourceIds,
                        List<String> resourceTypes,
                        List<Map<String, String>> resourceDetails,
                        String startDate,
                        String endDate) {

                // Build a deterministic cache key from sorted resource IDs
                String sortedIds = (resourceIds != null)
                        ? resourceIds.stream().sorted().collect(Collectors.joining(","))
                        : "empty";
                int idsHash = sortedIds.hashCode();
                String normalizedEndLabel = normalizeEndDate(endDate);
                String cacheKey = "costLabelResources-" + accountId + "-" + idsHash + "-" + startDate + "-" + normalizedEndLabel;
                String dbKey = "AWS_COST_LABEL_RESOURCES::" + accountId + "::" + idsHash + "::" + startDate + "::" + normalizedEndLabel;

                Optional<Map<String, Object>> cached = redisCache.get(cacheKey, new TypeReference<>() {});
                if (cached.isPresent()) {
                        logger.info("✅ Redis cache hit for label resources cost: {}", cacheKey);
                        return CompletableFuture.completedFuture(cached.get());
                }
                try {
                        Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                        if (dbCached.isPresent()) {
                                Map<String, Object> dbData = objectMapper.readValue(
                                        dbCached.get().getJsonData(), new TypeReference<Map<String, Object>>() {});
                                if (dbData != null) {
                                        redisCache.put(cacheKey, dbData, 60 * 24);
                                        logger.info("✅ DB snapshot hit for label resources cost — re-warmed Redis: {}", dbKey);
                                        return CompletableFuture.completedFuture(dbData);
                                }
                        }
                } catch (Exception e) {
                        logger.warn("DB cache miss for {}: {}", dbKey, e.getMessage());
                }

                if (useMockData) {
                        // In mock mode, return the full service breakdown as-is
                        return getCostBreakdown(accountId, "SERVICE", null, false, startDate, endDate)
                                        .thenApply(costs -> {
                                                Map<String, Object> result = new HashMap<>();
                                                result.put("costs", costs);
                                                result.put("resourceLevel", false);
                                                result.put("message", "Mock data — showing all service costs");
                                                return result;
                                        });
                }

                if (resourceIds == null || resourceIds.isEmpty()) {
                        Map<String, Object> empty = new HashMap<>();
                        empty.put("costs", Collections.emptyList());
                        empty.put("resourceLevel", false);
                        empty.put("message", "No resources assigned to this label");
                        return CompletableFuture.completedFuture(empty);
                }

                CloudAccount account = getAccount(accountId);
                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                return CompletableFuture.supplyAsync(() -> {
                        LocalDate parsedEnd = LocalDate.parse(endDate);
                        DateInterval interval = DateInterval.builder()
                                        .start(startDate)
                                        .end(parsedEnd.plusDays(1).toString())
                                        .build();

                        // --- Attempt 1: RESOURCE_ID dimension filter ---
                        try {
                                Expression resourceFilter = Expression.builder()
                                                .dimensions(DimensionValues.builder()
                                                                .key(Dimension.RESOURCE_ID)
                                                                .values(resourceIds)
                                                                .build())
                                                .build();

                                GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                                                .timePeriod(interval)
                                                .granularity(Granularity.MONTHLY)
                                                .metrics("UnblendedCost")
                                                .groupBy(GroupDefinition.builder()
                                                                .type(GroupDefinitionType.DIMENSION)
                                                                .key("SERVICE")
                                                                .build())
                                                .filter(resourceFilter)
                                                .build();

                                Map<String, Double> costMap = new HashMap<>();
                                ce.getCostAndUsage(req).resultsByTime().forEach(t ->
                                        t.groups().forEach(g -> {
                                                String name = g.keys().get(0);
                                                double cost = Double.parseDouble(g.metrics().get("UnblendedCost").amount());
                                                costMap.merge(name, cost, Double::sum);
                                        })
                                );

                                List<CostDto> costs = costMap.entrySet().stream()
                                                .map(e -> new CostDto(e.getKey(), e.getValue()))
                                                .filter(c -> c.getAmount() > 0.01)
                                                .sorted((a, b) -> Double.compare(b.getAmount(), a.getAmount()))
                                                .collect(Collectors.toList());

                                Map<String, Object> result = new HashMap<>();
                                result.put("costs", costs);
                                result.put("resourceLevel", true);
                                logger.info("✅ Resource-level label cost fetched: {} items for {} resources",
                                                costs.size(), resourceIds.size());
                                redisCache.put(cacheKey, result, 60 * 24);
                                try {
                                        CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                                        snapshot.setCacheKey(dbKey);
                                        snapshot.setJsonData(objectMapper.writeValueAsString(result));
                                        snapshot.setLastUpdated(java.time.LocalDateTime.now());
                                        cachedDataRepository.save(snapshot);
                                } catch (Exception dbEx) {
                                        logger.warn("Failed to save label resources cost DB snapshot {}: {}", dbKey, dbEx.getMessage());
                                }
                                return result;

                        } catch (Exception resourceEx) {
                                logger.warn("⚠️ RESOURCE_ID filter unavailable — falling back to SERVICE filter. " +
                                                "Enable 'Hourly and Resource Level Data' in Cost Explorer Settings. Error: {}",
                                                resourceEx.getMessage());
                        }

                        // --- Fallback: per-instance estimate for EC2 + RDS, service-level for everything else ---

                        long daysBetween = ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(endDate)) + 1;
                        double totalHours = daysBetween * 24.0;

                        // Separate EC2 instances
                        List<Map<String, String>> ec2Details = (resourceDetails == null) ? Collections.emptyList() :
                                resourceDetails.stream()
                                        .filter(r -> "EC2 Instance".equalsIgnoreCase(r.get("type")) ||
                                                     (r.get("id") != null && r.get("id").startsWith("i-")))
                                        .collect(Collectors.toList());

                        // Separate RDS instances
                        List<Map<String, String>> rdsDetails = (resourceDetails == null) ? Collections.emptyList() :
                                resourceDetails.stream()
                                        .filter(r -> "RDS Instance".equalsIgnoreCase(r.get("type")))
                                        .collect(Collectors.toList());

                        // Separate EBS volumes
                        List<Map<String, String>> ebsDetails = (resourceDetails == null) ? Collections.emptyList() :
                                resourceDetails.stream()
                                        .filter(r -> "EBS Volume".equalsIgnoreCase(r.get("type")) ||
                                                     (r.get("id") != null && r.get("id").startsWith("vol-")))
                                        .collect(Collectors.toList());

                        // Separate Load Balancers
                        List<Map<String, String>> lbDetails = (resourceDetails == null) ? Collections.emptyList() :
                                resourceDetails.stream()
                                        .filter(r -> "Load Balancer".equalsIgnoreCase(r.get("type")))
                                        .collect(Collectors.toList());

                        // Separate Elastic IPs
                        List<Map<String, String>> eipDetails = (resourceDetails == null) ? Collections.emptyList() :
                                resourceDetails.stream()
                                        .filter(r -> "Elastic IP".equalsIgnoreCase(r.get("type")))
                                        .collect(Collectors.toList());

                        // Remaining types fall through to service-level Cost Explorer (inaccurate for multi-resource accounts)
                        // Mutable — EC2/RDS/EBS/LB/EIP are added back here if their per-resource API calls fail
                        // Security Groups are always free in AWS — excluded permanently, never queried
                        List<String> serviceOnlyTypes = new ArrayList<>((resourceDetails == null
                                ? (resourceTypes == null ? Collections.<String>emptyList() : resourceTypes)
                                : resourceDetails.stream().map(r -> r.get("type")).filter(Objects::nonNull).collect(Collectors.toList()))
                                .stream()
                                .filter(t -> !"EC2 Instance".equalsIgnoreCase(t)
                                          && !"RDS Instance".equalsIgnoreCase(t)
                                          && !"EBS Volume".equalsIgnoreCase(t)
                                          && !"Load Balancer".equalsIgnoreCase(t)
                                          && !"Elastic IP".equalsIgnoreCase(t)
                                          && !"Security Group".equalsIgnoreCase(t))
                                .distinct()
                                .collect(Collectors.toList()));

                        List<CostDto> combinedCosts = new ArrayList<>();
                        boolean hasEc2Estimates = false;
                        boolean hasRdsEstimates = false;
                        List<String> approximationWarnings = new ArrayList<>();

                        // --- EC2 per-instance cost estimation ---
                        if (!ec2Details.isEmpty()) {
                                Map<String, List<Map<String, String>>> ec2ByRegion = ec2Details.stream()
                                        .collect(Collectors.groupingBy(r -> {
                                                String rgn = r.getOrDefault("region", "");
                                                return (rgn != null && !rgn.isEmpty()) ? rgn : "us-east-1";
                                        }));

                                // Collect stopped/terminated instance IDs across all regions.
                                // These need actual billing data from Cost Explorer — full-period estimate would be wrong.
                                List<String> nonRunningIds = new ArrayList<>();

                                for (Map.Entry<String, List<Map<String, String>>> entry : ec2ByRegion.entrySet()) {
                                        String region = entry.getKey();
                                        List<Map<String, String>> regionInstances = entry.getValue();
                                        List<String> instanceIds = regionInstances.stream()
                                                .map(r -> r.get("id")).filter(Objects::nonNull)
                                                .collect(Collectors.toList());
                                        try {
                                                Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
                                                Map<String, String> instanceTypeById = new HashMap<>();
                                                Map<String, String> instanceStateById = new HashMap<>();

                                                for (int i = 0; i < instanceIds.size(); i += 50) {
                                                        List<String> chunk = instanceIds.subList(i, Math.min(i + 50, instanceIds.size()));
                                                        try {
                                                                ec2.describeInstances(DescribeInstancesRequest.builder()
                                                                                .instanceIds(chunk).build())
                                                                        .reservations()
                                                                        .forEach(r -> r.instances()
                                                                                .forEach(inst -> {
                                                                                        instanceTypeById.put(inst.instanceId(), inst.instanceTypeAsString());
                                                                                        instanceStateById.put(inst.instanceId(), inst.state().nameAsString());
                                                                                }));
                                                        } catch (Exception chunkEx) {
                                                                logger.warn("⚠️ DescribeInstances chunk failed in {}: {}", region, chunkEx.getMessage());
                                                        }
                                                }

                                                for (Map<String, String> resource : regionInstances) {
                                                        String id = resource.get("id");
                                                        String name = resource.getOrDefault("name", id);
                                                        String instanceType = instanceTypeById.get(id);
                                                        String state = instanceStateById.getOrDefault(id, "unknown");

                                                        if ("terminated".equalsIgnoreCase(state)) {
                                                                // Terminated: ran for an unknown fraction of the period.
                                                                // hourlyRate × fullPeriod would wildly overestimate. Use actual CE data.
                                                                nonRunningIds.add(id);
                                                                logger.info("⏹️ EC2 {} is terminated — queuing for actual CE cost", id);
                                                                continue;
                                                        }

                                                        if ("stopped".equalsIgnoreCase(state)) {
                                                                // Stopped: may have been stopped partway through the period.
                                                                // hourlyRate × fullPeriod would overestimate. Use actual CE data.
                                                                nonRunningIds.add(id);
                                                                logger.info("⏸️ EC2 {} is stopped — queuing for actual CE cost (avoid overestimate)", id);
                                                                continue;
                                                        }

                                                        // Running: full-period on-demand estimate is reasonable
                                                        double hourlyRate = awsPricingService.getHourlyCost(instanceType, region);
                                                        if (hourlyRate > 0) {
                                                                double estimatedCost = hourlyRate * totalHours;
                                                                String label = name + (instanceType != null ? " (" + instanceType + ")" : "");
                                                                combinedCosts.add(new CostDto(label, estimatedCost));
                                                                hasEc2Estimates = true;
                                                                logger.info("💡 EC2 estimate: {} [{}] {} @ ${}/hr × {}hrs = ${}",
                                                                        name, instanceType, state, hourlyRate, totalHours,
                                                                        String.format("%.2f", estimatedCost));
                                                        } else {
                                                                logger.warn("⚠️ No EC2 pricing for {} type={} in {}", id, instanceType, region);
                                                        }
                                                }
                                        } catch (Exception ex) {
                                                logger.warn("⚠️ Could not get EC2 info for region {} — falling back to service-level: {}", region, ex.getMessage());
                                                if (!serviceOnlyTypes.contains("EC2 Instance")) serviceOnlyTypes.add("EC2 Instance");
                                        }
                                }

                                // --- Actual Cost Explorer query for stopped / terminated instances ---
                                // A single RESOURCE_ID-filtered query for all non-running IDs across all regions.
                                if (!nonRunningIds.isEmpty()) {
                                        try {
                                                Expression nonRunningFilter = Expression.builder()
                                                        .dimensions(DimensionValues.builder()
                                                                .key(Dimension.RESOURCE_ID)
                                                                .values(nonRunningIds)
                                                                .build())
                                                        .build();

                                                GetCostAndUsageRequest ceReq = GetCostAndUsageRequest.builder()
                                                        .timePeriod(interval)
                                                        .granularity(Granularity.MONTHLY)
                                                        .metrics("UnblendedCost")
                                                        .groupBy(GroupDefinition.builder()
                                                                .type(GroupDefinitionType.DIMENSION)
                                                                .key("RESOURCE_ID")
                                                                .build())
                                                        .filter(nonRunningFilter)
                                                        .build();

                                                Map<String, Double> actualCostMap = new HashMap<>();
                                                ce.getCostAndUsage(ceReq).resultsByTime().forEach(t ->
                                                        t.groups().forEach(g -> {
                                                                String resId = g.keys().get(0);
                                                                double cost = Double.parseDouble(g.metrics().get("UnblendedCost").amount());
                                                                actualCostMap.merge(resId, cost, Double::sum);
                                                        })
                                                );
                                                actualCostMap.forEach((resId, cost) ->
                                                        combinedCosts.add(new CostDto(resId + " (actual)", cost))
                                                );
                                                if (!actualCostMap.isEmpty()) {
                                                        hasEc2Estimates = true;
                                                }
                                                logger.info("✅ Actual CE cost fetched for {}/{} stopped/terminated EC2 instances",
                                                        actualCostMap.size(), nonRunningIds.size());
                                        } catch (Exception ceEx) {
                                                // Resource-level CE not enabled — cannot determine actual cost for partial-period instances.
                                                // Do NOT fall back to full-period estimate (that would overestimate).
                                                // Report $0 and surface the warning to the caller.
                                                approximationWarnings.add("stopped/terminated EC2 instances (enable 'Hourly and Resource Level Data' in Cost Explorer to see their actual cost)");
                                                logger.warn("⚠️ Could not fetch actual CE cost for stopped/terminated EC2 {} — " +
                                                        "reporting $0 to avoid overestimate. Enable 'Hourly and Resource Level Data'. Error: {}",
                                                        nonRunningIds, ceEx.getMessage());
                                        }
                                }
                        }

                        // --- RDS per-instance cost estimation ---
                        if (!rdsDetails.isEmpty()) {
                                Map<String, List<Map<String, String>>> rdsByRegion = rdsDetails.stream()
                                        .collect(Collectors.groupingBy(r -> {
                                                String rgn = r.getOrDefault("region", "");
                                                return (rgn != null && !rgn.isEmpty()) ? rgn : "us-east-1";
                                        }));

                                for (Map.Entry<String, List<Map<String, String>>> entry : rdsByRegion.entrySet()) {
                                        String region = entry.getKey();
                                        List<Map<String, String>> regionInstances = entry.getValue();
                                        try {
                                                RdsClient rdsClient = awsClientProvider.getRdsClient(account, region);

                                                // Fetch all DB instances in this region; filter locally by identifier
                                                Map<String, String> instanceClassById = new HashMap<>();
                                                String marker = null;
                                                do {
                                                        DescribeDbInstancesRequest.Builder reqBuilder =
                                                                DescribeDbInstancesRequest.builder().maxRecords(100);
                                                        if (marker != null) reqBuilder.marker(marker);
                                                        DescribeDbInstancesResponse resp = rdsClient.describeDBInstances(reqBuilder.build());
                                                        resp.dbInstances().forEach(db ->
                                                                instanceClassById.put(db.dbInstanceIdentifier(), db.dbInstanceClass()));
                                                        marker = resp.marker();
                                                } while (marker != null);

                                                for (Map<String, String> resource : regionInstances) {
                                                        String id = resource.get("id");
                                                        String name = resource.getOrDefault("name", id);
                                                        // Strip ARN prefix if stored as ARN (arn:aws:rds:...:db:identifier)
                                                        String identifier = (id != null && id.contains(":"))
                                                                ? id.substring(id.lastIndexOf(':') + 1) : id;
                                                        String dbClass = instanceClassById.get(identifier);
                                                        double hourlyRate = awsPricingService.getRdsHourlyCost(dbClass, region);
                                                        if (hourlyRate > 0) {
                                                                double estimatedCost = hourlyRate * totalHours;
                                                                String label = name + (dbClass != null ? " (" + dbClass + ")" : "");
                                                                combinedCosts.add(new CostDto(label, estimatedCost));
                                                                hasRdsEstimates = true;
                                                                logger.info("💡 RDS estimate: {} [{}] in {} @ ${}/hr × {}hrs = ${}",
                                                                        name, dbClass, region, hourlyRate, totalHours,
                                                                        String.format("%.2f", estimatedCost));
                                                        } else {
                                                                logger.warn("⚠️ No RDS pricing for {} class={} in {}", id, dbClass, region);
                                                        }
                                                }
                                        } catch (Exception ex) {
                                                logger.warn("⚠️ Could not get RDS pricing for region {} — falling back to service-level: {}", region, ex.getMessage());
                                                if (!serviceOnlyTypes.contains("RDS Instance")) serviceOnlyTypes.add("RDS Instance");
                                        }
                                }
                        }

                        // --- EBS per-volume cost estimation ---
                        // pricePerGbMonth × sizeGb × (days/30) — exact per-volume cost, no account-level bleed
                        if (!ebsDetails.isEmpty()) {
                                Map<String, List<Map<String, String>>> ebsByRegion = ebsDetails.stream()
                                        .collect(Collectors.groupingBy(r -> {
                                                String rgn = r.getOrDefault("region", "");
                                                return (rgn != null && !rgn.isEmpty()) ? rgn : "us-east-1";
                                        }));

                                for (Map.Entry<String, List<Map<String, String>>> entry : ebsByRegion.entrySet()) {
                                        String region = entry.getKey();
                                        List<Map<String, String>> regionVolumes = entry.getValue();
                                        List<String> volumeIds = regionVolumes.stream()
                                                .map(r -> r.get("id")).filter(Objects::nonNull)
                                                .collect(Collectors.toList());
                                        try {
                                                Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
                                                Map<String, Integer> sizeById = new HashMap<>();
                                                Map<String, String> typeById  = new HashMap<>();

                                                for (int i = 0; i < volumeIds.size(); i += 200) {
                                                        List<String> chunk = volumeIds.subList(i, Math.min(i + 200, volumeIds.size()));
                                                        ec2.describeVolumes(DescribeVolumesRequest.builder().volumeIds(chunk).build())
                                                                .volumes()
                                                                .forEach(vol -> {
                                                                        sizeById.put(vol.volumeId(), vol.size());
                                                                        typeById.put(vol.volumeId(), vol.volumeTypeAsString());
                                                                });
                                                }

                                                for (Map<String, String> resource : regionVolumes) {
                                                        String id   = resource.get("id");
                                                        String name = resource.getOrDefault("name", id);
                                                        Integer sizeGb   = sizeById.get(id);
                                                        String  volType  = typeById.get(id);
                                                        if (sizeGb != null && sizeGb > 0) {
                                                                double cost = awsPricingService.getEbsCostForPeriod(volType, sizeGb, region, daysBetween);
                                                                combinedCosts.add(new CostDto(name + " (" + sizeGb + "GiB " + volType + ")", cost));
                                                                logger.info("💡 EBS estimate: {} {} GiB {} in {} = ${}", name, sizeGb, volType, region,
                                                                        String.format("%.2f", cost));
                                                        } else {
                                                                logger.warn("⚠️ EBS size unknown for {} — skipping", id);
                                                        }
                                                }
                                        } catch (Exception ex) {
                                                logger.warn("⚠️ Could not get EBS info for region {} — falling back to service-level: {}", region, ex.getMessage());
                                                if (!serviceOnlyTypes.contains("EBS Volume")) serviceOnlyTypes.add("EBS Volume");
                                        }
                                }
                        }

                        // --- Load Balancer base cost estimation ---
                        // Hourly fixed rate × hours (does not include LCU charges — requires traffic metrics)
                        if (!lbDetails.isEmpty()) {
                                Map<String, List<Map<String, String>>> lbByRegion = lbDetails.stream()
                                        .collect(Collectors.groupingBy(r -> {
                                                String rgn = r.getOrDefault("region", "");
                                                return (rgn != null && !rgn.isEmpty()) ? rgn : "us-east-1";
                                        }));

                                for (Map.Entry<String, List<Map<String, String>>> entry : lbByRegion.entrySet()) {
                                        String region = entry.getKey();
                                        List<Map<String, String>> regionLbs = entry.getValue();
                                        List<String> lbArns = regionLbs.stream()
                                                .map(r -> r.get("id")).filter(Objects::nonNull)
                                                .collect(Collectors.toList());
                                        try {
                                                ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, region);
                                                Map<String, String> lbTypeByArn = new HashMap<>();

                                                elbv2.describeLoadBalancers(DescribeLoadBalancersRequest.builder()
                                                        .loadBalancerArns(lbArns).build())
                                                        .loadBalancers()
                                                        .forEach(lb -> lbTypeByArn.put(lb.loadBalancerArn(), lb.typeAsString()));

                                                for (Map<String, String> resource : regionLbs) {
                                                        String id   = resource.get("id");
                                                        String name = resource.getOrDefault("name", id);
                                                        String lbType = lbTypeByArn.getOrDefault(id, "application");
                                                        double hourlyRate = awsPricingService.getElbHourlyRate(lbType);
                                                        double cost = hourlyRate * totalHours;
                                                        combinedCosts.add(new CostDto(name + " (" + lbType + " LB, base only)", cost));
                                                        logger.info("💡 LB estimate: {} [{}] @ ${}/hr × {}hrs = ${}",
                                                                name, lbType, hourlyRate, totalHours, String.format("%.2f", cost));
                                                }
                                        } catch (Exception ex) {
                                                logger.warn("⚠️ Could not get LB info for region {} — falling back to service-level: {}", region, ex.getMessage());
                                                if (!serviceOnlyTypes.contains("Load Balancer")) serviceOnlyTypes.add("Load Balancer");
                                        }
                                }
                        }

                        // --- Elastic IP cost estimation ---
                        // $0.005/hr when not associated; $0 when associated with a running instance
                        if (!eipDetails.isEmpty()) {
                                Map<String, List<Map<String, String>>> eipByRegion = eipDetails.stream()
                                        .collect(Collectors.groupingBy(r -> {
                                                String rgn = r.getOrDefault("region", "");
                                                return (rgn != null && !rgn.isEmpty()) ? rgn : "us-east-1";
                                        }));

                                for (Map.Entry<String, List<Map<String, String>>> entry : eipByRegion.entrySet()) {
                                        String region = entry.getKey();
                                        List<Map<String, String>> regionEips = entry.getValue();
                                        try {
                                                Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);

                                                List<String> allocationIds = regionEips.stream()
                                                        .map(r -> r.get("id")).filter(id -> id != null && id.startsWith("eipalloc-"))
                                                        .collect(Collectors.toList());
                                                List<String> publicIps = regionEips.stream()
                                                        .map(r -> r.get("id")).filter(id -> id != null && !id.startsWith("eipalloc-"))
                                                        .collect(Collectors.toList());

                                                // associationId is null when the EIP is not associated (billable)
                                                Map<String, String> associationByAllocationId = new HashMap<>();
                                                Map<String, String> associationByPublicIp     = new HashMap<>();

                                                if (!allocationIds.isEmpty()) {
                                                        ec2.describeAddresses(DescribeAddressesRequest.builder()
                                                                .allocationIds(allocationIds).build())
                                                                .addresses()
                                                                .forEach(a -> associationByAllocationId.put(
                                                                        a.allocationId(), a.associationId()));
                                                }
                                                if (!publicIps.isEmpty()) {
                                                        ec2.describeAddresses(DescribeAddressesRequest.builder()
                                                                .publicIps(publicIps).build())
                                                                .addresses()
                                                                .forEach(a -> associationByPublicIp.put(
                                                                        a.publicIp(), a.associationId()));
                                                }

                                                for (Map<String, String> resource : regionEips) {
                                                        String id   = resource.get("id");
                                                        String name = resource.getOrDefault("name", id);
                                                        String assoc = id != null && id.startsWith("eipalloc-")
                                                                ? associationByAllocationId.get(id)
                                                                : associationByPublicIp.get(id);

                                                        if (assoc != null) {
                                                                // Associated with a running instance — no charge
                                                                combinedCosts.add(new CostDto(name + " (EIP, associated)", 0.0));
                                                                logger.info("💡 EIP {} is associated — $0 cost", id);
                                                        } else {
                                                                // Unassociated — $0.005/hr
                                                                double cost = 0.005 * totalHours;
                                                                combinedCosts.add(new CostDto(name + " (EIP, unassociated)", cost));
                                                                logger.info("💡 EIP {} is unassociated @ $0.005/hr × {}hrs = ${}", id, totalHours,
                                                                        String.format("%.2f", cost));
                                                        }
                                                }
                                        } catch (Exception ex) {
                                                logger.warn("⚠️ Could not get EIP info for region {} — skipping (EIPs are low-cost): {}", region, ex.getMessage());
                                        }
                                }
                        }

                        // --- Service-level Cost Explorer for remaining types ---
                        // NOTE: This returns the full account bill for each AWS service, NOT just tagged resources.
                        // It is fundamentally inaccurate when the account has more resources of the same type.
                        if (!serviceOnlyTypes.isEmpty()) {
                                List<String> serviceNames = serviceOnlyTypes.stream()
                                        .flatMap(t -> RESOURCE_TYPE_TO_SERVICE_NAMES
                                                .getOrDefault(t, List.of(t.split(" ")[0]))
                                                .stream())
                                        .distinct()
                                        .collect(Collectors.toList());

                                approximationWarnings.addAll(serviceOnlyTypes);

                                if (!serviceNames.isEmpty()) {
                                        try {
                                                Expression serviceFilter = Expression.builder()
                                                        .dimensions(DimensionValues.builder()
                                                                .key(Dimension.SERVICE)
                                                                .values(serviceNames)
                                                                .build())
                                                        .build();

                                                GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                                                        .timePeriod(interval)
                                                        .granularity(Granularity.MONTHLY)
                                                        .metrics("UnblendedCost")
                                                        .groupBy(GroupDefinition.builder()
                                                                .type(GroupDefinitionType.DIMENSION)
                                                                .key("SERVICE")
                                                                .build())
                                                        .filter(serviceFilter)
                                                        .build();

                                                Map<String, Double> svcCostMap = new HashMap<>();
                                                ce.getCostAndUsage(req).resultsByTime().forEach(t ->
                                                        t.groups().forEach(g -> {
                                                                String svcName = g.keys().get(0);
                                                                double cost = Double.parseDouble(g.metrics().get("UnblendedCost").amount());
                                                                svcCostMap.merge(svcName, cost, Double::sum);
                                                        })
                                                );
                                                svcCostMap.forEach((svcName, cost) -> combinedCosts.add(new CostDto(svcName, cost)));
                                        } catch (Exception svcEx) {
                                                logger.error("❌ Error fetching service-level label cost: {}", svcEx.getMessage(), svcEx);
                                        }
                                }
                        }

                        List<CostDto> finalCosts = combinedCosts.stream()
                                .filter(c -> c.getAmount() > 0.01)
                                .sorted((a, b) -> Double.compare(b.getAmount(), a.getAmount()))
                                .collect(Collectors.toList());

                        // Build message parts
                        List<String> messageParts = new ArrayList<>();
                        if (hasEc2Estimates || hasRdsEstimates) {
                                messageParts.add("Per-instance cost estimate based on on-demand pricing for the selected date range.");
                        }
                        if (!approximationWarnings.isEmpty()) {
                                messageParts.add("Service-level cost (full account bill) shown for: " +
                                        String.join(", ", approximationWarnings) +
                                        " — not per-resource. Enable 'Hourly and Resource Level Data' in Cost Explorer for accuracy.");
                        }
                        if (messageParts.isEmpty()) {
                                messageParts.add("Approximate — Enable 'Hourly and Resource Level Data' in Cost Explorer for per-resource accuracy.");
                        }

                        Map<String, Object> fallbackResult = new HashMap<>();
                        fallbackResult.put("costs", finalCosts);
                        fallbackResult.put("resourceLevel", false);
                        fallbackResult.put("message", String.join(" ", messageParts));
                        if (!approximationWarnings.isEmpty()) {
                                fallbackResult.put("approximationWarning",
                                        "Service-level cost shown for: " + String.join(", ", approximationWarnings) +
                                        ". This is the total account cost for these services, not just tagged resources.");
                        }
                        logger.info("✅ Label cost fallback: {} items (EC2={}, RDS={}, service-level={})",
                                finalCosts.size(),
                                hasEc2Estimates ? "estimated" : "none",
                                hasRdsEstimates ? "estimated" : "none",
                                approximationWarnings.isEmpty() ? "none" : approximationWarnings);
                        redisCache.put(cacheKey, fallbackResult, 60 * 24);
                        try {
                                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                                snapshot.setCacheKey(dbKey);
                                snapshot.setJsonData(objectMapper.writeValueAsString(fallbackResult));
                                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                                cachedDataRepository.save(snapshot);
                        } catch (Exception dbEx) {
                                logger.warn("Failed to save label resources fallback DB snapshot {}: {}", dbKey, dbEx.getMessage());
                        }
                        return fallbackResult;
                });
        }

        @Async("awsTaskExecutor")
        public CompletableFuture<Map<String, Object>> getCostSummary(
                        String accountId,
                        int days,
                        boolean forceRefresh) {

                logger.info("📋 Cost summary request - Account: {}", accountId);

                String startDate = LocalDate.now().withDayOfMonth(1).toString();
                String endDate = LocalDate.now().toString();

                CompletableFuture<List<CostDto>> serviceBreakdown = getCostBreakdown(accountId, "SERVICE", null,
                                forceRefresh, startDate, endDate);

                CompletableFuture<List<CostDto>> regionBreakdown = getCostBreakdown(accountId, "REGION", null,
                                forceRefresh, startDate, endDate);

                CompletableFuture<HistoricalCostDto> historicalCost = getHistoricalCost(accountId, null, null, days,
                                forceRefresh);

                return CompletableFuture.allOf(serviceBreakdown, regionBreakdown, historicalCost)
                                .thenApply(v -> {
                                        Map<String, Object> summary = new HashMap<>();
                                        summary.put("serviceBreakdown", serviceBreakdown.join());
                                        summary.put("regionBreakdown", regionBreakdown.join());
                                        summary.put("historicalTrend", historicalCost.join());
                                        summary.put("accountId", accountId);
                                        summary.put("days", days);

                                        logger.info("✅ Cost summary generated for account: {}", accountId);
                                        return summary;
                                })
                                .exceptionally(ex -> {
                                        logger.error("❌ Error generating cost summary", ex);
                                        return Collections.emptyMap();
                                });
        }

        /**
         * Fetch AWS credits applied to the account in the current month.
         * Credits appear in Cost Explorer as RECORD_TYPE=Credit with negative amounts.
         * Returns the absolute value (positive number representing dollars saved via credits).
         * Returns 0.0 on any error or if no credits were applied.
         */
        @Async("awsTaskExecutor")
        public CompletableFuture<Double> getCreditsApplied(String accountId) {
                return getCreditsApplied(accountId, false);
        }

        @Async("awsTaskExecutor")
        public CompletableFuture<Double> getCreditsApplied(String accountId, boolean forceRefresh) {
                String cacheKey = "creditsApplied-" + accountId;
                String dbKey = "AWS_CREDITS_APPLIED::" + accountId + "::" + YearMonth.now();
                if (!forceRefresh) {
                        Optional<Double> cached = redisCache.get(cacheKey, Double.class);
                        if (cached.isPresent()) {
                                return CompletableFuture.completedFuture(cached.get());
                        }
                        try {
                                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                                String snapJson = dbSnapshot.map(snapshotService::resolvePayload).orElse(null);
                                if (snapJson != null) {
                                        Double dbResult = objectMapper.readValue(snapJson, Double.class);
                                        if (dbResult != null) {
                                                redisCache.put(cacheKey, dbResult, 60 * 24);
                                                logger.info("✅ Credits applied loaded from DB for {} — re-warming Redis", accountId);
                                                return CompletableFuture.completedFuture(dbResult);
                                        }
                                }
                        } catch (Exception e) {
                                logger.warn("DB snapshot load failed for credits {}: {}", accountId, e.getMessage());
                        }
                }

                if (useMockData) {
                        return CompletableFuture.completedFuture(0.0);
                }

                return CompletableFuture.supplyAsync(() -> {
                        try {
                                CloudAccount account = getAccount(accountId);

                                LocalDate now = LocalDate.now();
                                LocalDate start = now.withDayOfMonth(1);
                                LocalDate end = now.plusDays(1);

                                // CUR first. Credits are a line item type, so this is a WHERE clause
                                // rather than the separate filtered CE request below.
                                Optional<CurSource> curSource = curSourceResolver.resolve(account, start, now);
                                if (curSource.isPresent()) {
                                        try {
                                                double curCredits = curCostDataSource.getCreditsApplied(
                                                                curSource.get(), account, start, now);
                                                cacheMtd(cacheKey, dbKey, curCredits);
                                                return curCredits;
                                        } catch (Exception curEx) {
                                                logger.warn("[CUR] Credits applied failed for {} — falling back to CE: {}",
                                                                accountId, curEx.getMessage());
                                        }
                                }

                                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                                Expression creditFilter = Expression.builder()
                                        .dimensions(DimensionValues.builder()
                                                .key(Dimension.RECORD_TYPE)
                                                .values("Credit")
                                                .build())
                                        .build();

                                GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                                        .timePeriod(DateInterval.builder()
                                                .start(start.toString())
                                                .end(end.toString())
                                                .build())
                                        .granularity(Granularity.MONTHLY)
                                        .metrics("UnblendedCost")
                                        .filter(creditFilter)
                                        .build();

                                double credits = ce.getCostAndUsage(request).resultsByTime().stream()
                                        .map(r -> r.total().getOrDefault("UnblendedCost",
                                                MetricValue.builder().amount("0").unit("USD").build()).amount())
                                        .mapToDouble(Double::parseDouble)
                                        .map(Math::abs)
                                        .sum();

                                redisCache.put(cacheKey, credits, 60 * 24);
                                try {
                                        CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                                        snapshot.setCacheKey(dbKey);
                                        snapshot.setJsonData(objectMapper.writeValueAsString(credits));
                                        snapshot.setLastUpdated(java.time.LocalDateTime.now());
                                        cachedDataRepository.save(snapshot);
                                } catch (Exception dbEx) {
                                        logger.warn("Failed to save credits DB snapshot {}: {}", dbKey, dbEx.getMessage());
                                }
                                return credits;
                        } catch (Exception e) {
                                logger.warn("Could not fetch credits for account {}: {}", accountId, e.getMessage());
                                return 0.0;
                        }
                });
        }
}