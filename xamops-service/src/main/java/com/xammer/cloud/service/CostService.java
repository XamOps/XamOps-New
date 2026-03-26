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
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
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
                        AwsPricingService awsPricingService) {
                this.cloudAccountRepository = cloudAccountRepository;
                this.awsClientProvider = awsClientProvider;
                this.redisCache = redisCache;
                this.forecastingService = forecastingService;
                this.costMockService = costMockService;
                this.cachedDataRepository = cachedDataRepository;
                this.objectMapper = objectMapper;
                this.awsPricingService = awsPricingService;

                logger.info("CostService initialized (Mock Mode: {})", useMockData);
        }

        private CloudAccount getAccount(String accountId) {
                return cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst()
                                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
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
                                if (dbCached.isPresent()) {
                                        Double dbData = objectMapper.readValue(dbCached.get().getJsonData(), Double.class);
                                        if (dbData != null) {
                                                redisCache.put(cacheKey, dbData, 60);
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
                                        redisCache.put(cacheKey, total, 60);
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
                        redisCache.put(cacheKey, mockResult, 60);
                        return CompletableFuture.completedFuture(mockResult);
                }

                return CompletableFuture.supplyAsync(() -> {
                        try {
                                CloudAccount account = getAccount(accountId);
                                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                                LocalDate now = LocalDate.now();
                                LocalDate start = now.withDayOfMonth(1);
                                LocalDate end = now.plusDays(1); // End date is exclusive

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

                                redisCache.put(cacheKey, total, 60); // Cache for 1 hour
                                try {
                                        CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                                        snapshot.setCacheKey(dbKey);
                                        snapshot.setJsonData(objectMapper.writeValueAsString(total));
                                        snapshot.setLastUpdated(java.time.LocalDateTime.now());
                                        cachedDataRepository.save(snapshot);
                                } catch (Exception dbEx) {
                                        logger.warn("Failed to save MTD cost DB snapshot {}: {}", dbKey, dbEx.getMessage());
                                }
                                return total;
                        } catch (Exception e) {
                                logger.error("❌ Error fetching MTD cost for account {}: {}", accountId, e.getMessage());
                                return 0.0;
                        }
                });
        }

        /**
         * Get Last Month Total Spend
         */
        @Async("awsTaskExecutor")
        public CompletableFuture<Double> getLastMonthSpend(String accountId, boolean forceRefresh) {
                String cacheKey = "lastMonthCost-" + accountId;
                String dbKey = "AWS_LAST_MONTH_COST::" + accountId;
                if (!forceRefresh) {
                        Optional<Double> cached = redisCache.get(cacheKey, Double.class);
                        if (cached.isPresent()) {
                                logger.info("✅ Cache hit for Last Month cost: {}", cacheKey);
                                return CompletableFuture.completedFuture(cached.get());
                        }
                        try {
                                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                                if (dbCached.isPresent()) {
                                        Double dbData = objectMapper.readValue(dbCached.get().getJsonData(), Double.class);
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
                                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                                LocalDate now = LocalDate.now();
                                LocalDate start = now.minusMonths(1).withDayOfMonth(1);
                                LocalDate end = now.withDayOfMonth(1); // First day of current month (exclusive)

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

                                redisCache.put(cacheKey, total, 60 * 24); // Cache for 24 hours
                                try {
                                        CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                                        snapshot.setCacheKey(dbKey);
                                        snapshot.setJsonData(objectMapper.writeValueAsString(total));
                                        snapshot.setLastUpdated(java.time.LocalDateTime.now());
                                        cachedDataRepository.save(snapshot);
                                } catch (Exception dbEx) {
                                        logger.warn("Failed to save last month cost DB snapshot {}: {}", dbKey, dbEx.getMessage());
                                }
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

                String cacheKey = "costBreakdownServiceRegion-" + accountId + "-" + startDate + "-" + endDate;
                String dbKey = "AWS_COST_BREAKDOWN_SVC_REGION::" + accountId + "::" + startDate + "::" + endDate;

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
                                                redisCache.put(cacheKey, dbData, 60 * 6);
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
                                        .getMockCostBreakdownByServiceAndRegion(accountId, startDate, endDate);
                        redisCache.put(cacheKey, mockResult, 60 * 6);
                        return CompletableFuture.completedFuture(mockResult);
                }

                CloudAccount account = getAccount(accountId);
                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                return CompletableFuture.supplyAsync(() -> {
                        try {
                                DateInterval dateInterval = DateInterval.builder()
                                                .start(startDate.toString())
                                                .end(endDate.plusDays(1).toString()) // Cost Explorer is exclusive of
                                                                                     // end date
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

                                redisCache.put(cacheKey, costs, 60 * 6); // Cache for 6 hours
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
         * Get cost breakdown by dimension (SERVICE, REGION, INSTANCE_TYPE, etc.)
         */
        @Async("awsTaskExecutor")
        public CompletableFuture<List<CostDto>> getCostBreakdown(
                        String accountId, String groupBy, String tag, boolean forceRefresh, String startDate,
                        String endDate) {

                String cacheKey = "costBreakdown-" + accountId + "-" + groupBy + "-" + (tag != null ? tag : "") + "-"
                                + startDate + "-" + endDate;
                String dbKey = "AWS_COST_BREAKDOWN::" + accountId + "::" + groupBy + "::" + (tag != null ? tag : "")
                                + "::" + startDate + "::" + endDate;

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
                                        redisCache.put(cacheKey, dbData, 60); // re-warm Redis so next request skips DB
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
                        redisCache.put(cacheKey, mockResult, 60);
                        return CompletableFuture.completedFuture(mockResult);
                }

                CloudAccount account = getAccount(accountId);
                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                return CompletableFuture.supplyAsync(() -> {
                        try {
                                LocalDate parsedEndDate = LocalDate.parse(endDate);
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

                                redisCache.put(cacheKey, costs, 60);
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
                                        redisCache.put(cacheKey, dbResult, 60);
                                        return CompletableFuture.completedFuture(dbResult);
                                }
                        } catch (Exception e) {
                                logger.warn("DB snapshot load failed for historical cost {}: {}", accountId, e.getMessage());
                        }
                }

                // Return mock data if mock mode is enabled
                if (useMockData) {
                        HistoricalCostDto mockResult = costMockService.getMockHistoricalCost(accountId, serviceName,
                                        regionName, days);
                        redisCache.put(cacheKey, mockResult, 60);
                        return CompletableFuture.completedFuture(mockResult);
                }

                logger.info("📊 Fetching historical cost - Account: {}, Service: {}, Region: {}, Days: {}",
                                accountId, serviceName, regionName, days);

                CloudAccount account = getAccount(accountId);
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
                                redisCache.put(cacheKey, historicalCost, 60);
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
                                                redisCache.put(cacheKey, dbData, 60);
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
                        redisCache.put(cacheKey, mockResult, 60);
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
                                        labels.add(month.format(DateTimeFormatter.ofPattern("MMM yyyy")));

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
                                redisCache.put(cacheKey, historicalCost, 60);
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
                String cacheKey = "costLabelResources-" + accountId + "-" + idsHash + "-" + startDate + "-" + endDate;
                String dbKey = "AWS_COST_LABEL_RESOURCES::" + accountId + "::" + idsHash + "::" + startDate + "::" + endDate;

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
                                        redisCache.put(cacheKey, dbData, 60);
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
                                redisCache.put(cacheKey, result, 60);
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

                        // Remaining types fall through to service-level Cost Explorer (inaccurate for multi-resource accounts)
                        // Mutable — EC2/RDS are added back here if their per-instance API calls fail
                        List<String> serviceOnlyTypes = new ArrayList<>((resourceDetails == null
                                ? (resourceTypes == null ? Collections.<String>emptyList() : resourceTypes)
                                : resourceDetails.stream().map(r -> r.get("type")).filter(Objects::nonNull).collect(Collectors.toList()))
                                .stream()
                                .filter(t -> !"EC2 Instance".equalsIgnoreCase(t) && !"RDS Instance".equalsIgnoreCase(t))
                                .distinct()
                                .collect(Collectors.toList()));

                        List<CostDto> combinedCosts = new ArrayList<>();
                        boolean hasEc2Estimates = false;
                        boolean hasRdsEstimates = false;

                        // --- EC2 per-instance cost estimation ---
                        if (!ec2Details.isEmpty()) {
                                Map<String, List<Map<String, String>>> ec2ByRegion = ec2Details.stream()
                                        .collect(Collectors.groupingBy(r -> {
                                                String rgn = r.getOrDefault("region", "");
                                                return (rgn != null && !rgn.isEmpty()) ? rgn : "us-east-1";
                                        }));

                                for (Map.Entry<String, List<Map<String, String>>> entry : ec2ByRegion.entrySet()) {
                                        String region = entry.getKey();
                                        List<Map<String, String>> regionInstances = entry.getValue();
                                        List<String> instanceIds = regionInstances.stream()
                                                .map(r -> r.get("id")).filter(Objects::nonNull)
                                                .collect(Collectors.toList());
                                        try {
                                                Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
                                                Map<String, String> instanceTypeById = new HashMap<>();

                                                for (int i = 0; i < instanceIds.size(); i += 50) {
                                                        List<String> chunk = instanceIds.subList(i, Math.min(i + 50, instanceIds.size()));
                                                        try {
                                                                ec2.describeInstances(DescribeInstancesRequest.builder()
                                                                                .instanceIds(chunk).build())
                                                                        .reservations()
                                                                        .forEach(r -> r.instances()
                                                                                .forEach(inst -> instanceTypeById.put(
                                                                                        inst.instanceId(), inst.instanceTypeAsString())));
                                                        } catch (Exception chunkEx) {
                                                                logger.warn("⚠️ DescribeInstances chunk failed in {}: {}", region, chunkEx.getMessage());
                                                        }
                                                }

                                                for (Map<String, String> resource : regionInstances) {
                                                        String id = resource.get("id");
                                                        String name = resource.getOrDefault("name", id);
                                                        String instanceType = instanceTypeById.get(id);
                                                        double hourlyRate = awsPricingService.getHourlyCost(instanceType, region);
                                                        if (hourlyRate > 0) {
                                                                double estimatedCost = hourlyRate * totalHours;
                                                                String label = name + (instanceType != null ? " (" + instanceType + ")" : "");
                                                                combinedCosts.add(new CostDto(label, estimatedCost));
                                                                hasEc2Estimates = true;
                                                                logger.info("💡 EC2 estimate: {} [{}] in {} @ ${}/hr × {}hrs = ${}",
                                                                        name, instanceType, region, hourlyRate, totalHours,
                                                                        String.format("%.2f", estimatedCost));
                                                        } else {
                                                                logger.warn("⚠️ No EC2 pricing for {} type={} in {}", id, instanceType, region);
                                                        }
                                                }
                                        } catch (Exception ex) {
                                                logger.warn("⚠️ Could not get EC2 pricing for region {} — falling back to service-level: {}", region, ex.getMessage());
                                                if (!serviceOnlyTypes.contains("EC2 Instance")) serviceOnlyTypes.add("EC2 Instance");
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

                        // --- Service-level Cost Explorer for remaining types ---
                        // NOTE: This returns the full account bill for each AWS service, NOT just tagged resources.
                        // It is fundamentally inaccurate when the account has more resources of the same type.
                        List<String> approximationWarnings = new ArrayList<>();
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
                        redisCache.put(cacheKey, fallbackResult, 60);
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
}