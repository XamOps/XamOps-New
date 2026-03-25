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
                if (!forceRefresh) {
                        Optional<Double> cached = redisCache.get(cacheKey, Double.class);
                        if (cached.isPresent()) {
                                logger.info("✅ Cache hit for MTD cost: {}", cacheKey);
                                return CompletableFuture.completedFuture(cached.get());
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
                if (!forceRefresh) {
                        Optional<Double> cached = redisCache.get(cacheKey, Double.class);
                        if (cached.isPresent()) {
                                logger.info("✅ Cache hit for Last Month cost: {}", cacheKey);
                                return CompletableFuture.completedFuture(cached.get());
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

                if (!forceRefresh) {
                        Optional<List<DetailedCostDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
                        });
                        if (cachedData.isPresent()) {
                                logger.info("✅ Cache hit for detailed cost breakdown: {}", cacheKey);
                                return CompletableFuture.completedFuture(cachedData.get());
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

                if (!forceRefresh) {
                        Optional<HistoricalCostDto> cachedData = redisCache.get(cacheKey, HistoricalCostDto.class);
                        if (cachedData.isPresent()) {
                                logger.info("✅ Cache hit for historical cost dimension: {}", cacheKey);
                                return CompletableFuture.completedFuture(cachedData.get());
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
                                return result;

                        } catch (Exception resourceEx) {
                                logger.warn("⚠️ RESOURCE_ID filter unavailable — falling back to SERVICE filter. " +
                                                "Enable 'Hourly and Resource Level Data' in Cost Explorer Settings. Error: {}",
                                                resourceEx.getMessage());
                        }

                        // --- Fallback: EC2 per-instance estimate + SERVICE filter for other types ---

                        // Separate EC2 instances from other resource types using resourceDetails
                        List<Map<String, String>> ec2Details = (resourceDetails == null) ? Collections.emptyList() :
                                resourceDetails.stream()
                                        .filter(r -> "EC2 Instance".equalsIgnoreCase(r.get("type")) ||
                                                     (r.get("id") != null && r.get("id").startsWith("i-")))
                                        .collect(Collectors.toList());

                        // Mutable list — may have "EC2 Instance" added if DescribeInstances fails
                        List<String> nonEc2Types = new ArrayList<>((resourceDetails == null
                                ? (resourceTypes == null ? Collections.<String>emptyList() : resourceTypes)
                                : resourceDetails.stream().map(r -> r.get("type")).filter(Objects::nonNull).collect(Collectors.toList()))
                                .stream()
                                .filter(t -> !"EC2 Instance".equalsIgnoreCase(t))
                                .distinct()
                                .collect(Collectors.toList()));

                        List<CostDto> combinedCosts = new ArrayList<>();
                        boolean hasEc2Estimates = false;

                        // --- EC2 per-instance cost estimation ---
                        if (!ec2Details.isEmpty()) {
                                long daysBetween = ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(endDate)) + 1;
                                double totalHours = daysBetween * 24.0;

                                // Group by region to batch DescribeInstances calls
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
                                                                logger.warn("⚠️ No pricing data for {} instanceType={} in {}", id, instanceType, region);
                                                        }
                                                }
                                        } catch (Exception ex) {
                                                logger.warn("⚠️ Could not get EC2 pricing for region {} — using service-level fallback: {}", region, ex.getMessage());
                                                if (!nonEc2Types.contains("EC2 Instance")) nonEc2Types.add("EC2 Instance");
                                        }
                                }
                        }

                        // --- Service-level Cost Explorer for non-EC2 types (or EC2 fallback) ---
                        if (!nonEc2Types.isEmpty()) {
                                List<String> serviceNames = nonEc2Types.stream()
                                        .flatMap(t -> RESOURCE_TYPE_TO_SERVICE_NAMES
                                                .getOrDefault(t, List.of(t.split(" ")[0]))
                                                .stream())
                                        .distinct()
                                        .collect(Collectors.toList());

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
                                                                String name = g.keys().get(0);
                                                                double cost = Double.parseDouble(g.metrics().get("UnblendedCost").amount());
                                                                svcCostMap.merge(name, cost, Double::sum);
                                                        })
                                                );
                                                svcCostMap.forEach((name, cost) -> combinedCosts.add(new CostDto(name, cost)));
                                        } catch (Exception svcEx) {
                                                logger.error("❌ Error fetching service-level label cost: {}", svcEx.getMessage(), svcEx);
                                        }
                                }
                        }

                        List<CostDto> finalCosts = combinedCosts.stream()
                                .filter(c -> c.getAmount() > 0.01)
                                .sorted((a, b) -> Double.compare(b.getAmount(), a.getAmount()))
                                .collect(Collectors.toList());

                        Map<String, Object> fallbackResult = new HashMap<>();
                        fallbackResult.put("costs", finalCosts);
                        fallbackResult.put("resourceLevel", false);
                        fallbackResult.put("message", hasEc2Estimates
                                ? "Per-instance cost estimate based on on-demand pricing for the selected date range."
                                : "Approximate — showing all costs for services used by labeled resources. Enable 'Hourly and Resource Level Data' in Cost Explorer for per-resource accuracy.");
                        logger.info("✅ Label cost fallback: {} items ({} EC2 per-instance estimates)",
                                finalCosts.size(), hasEc2Estimates ? "with" : "no");
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