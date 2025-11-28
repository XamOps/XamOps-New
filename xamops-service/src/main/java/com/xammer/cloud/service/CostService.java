package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.CostForecastDto;
import com.xammer.cloud.dto.DetailedCostDto;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class CostService {

        private static final Logger logger = LoggerFactory.getLogger(CostService.class);

        private final CloudAccountRepository cloudAccountRepository;
        private final AwsClientProvider awsClientProvider;
        private final RedisCacheService redisCache;
        private final ForecastingService forecastingService;

        @Autowired
        public CostService(CloudAccountRepository cloudAccountRepository,
                        AwsClientProvider awsClientProvider,
                        RedisCacheService redisCache,
                        @Lazy ForecastingService forecastingService) {
                this.cloudAccountRepository = cloudAccountRepository;
                this.awsClientProvider = awsClientProvider;
                this.redisCache = redisCache;
                this.forecastingService = forecastingService;
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

                if (!forceRefresh) {
                        Optional<List<CostDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
                        });
                        if (cachedData.isPresent()) {
                                logger.info("✅ Cache hit for cost breakdown: {}", cacheKey);
                                return CompletableFuture.completedFuture(cachedData.get());
                        }
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

                                List<CostDto> costs = ce.getCostAndUsage(request).resultsByTime().get(0).groups()
                                                .stream()
                                                .map(group -> new CostDto(
                                                                group.keys().get(0),
                                                                Double.parseDouble(group.metrics().get("UnblendedCost")
                                                                                .amount())))
                                                .filter(cost -> cost.getAmount() > 0.01)
                                                .sorted((a, b) -> Double.compare(b.getAmount(), a.getAmount()))
                                                .collect(Collectors.toList());

                                redisCache.put(cacheKey, costs, 10);
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

                if (!forceRefresh) {
                        Optional<HistoricalCostDto> cachedData = redisCache.get(cacheKey, HistoricalCostDto.class);
                        if (cachedData.isPresent()) {
                                logger.info("✅ Cache hit for historical cost: {}", cacheKey);
                                return CompletableFuture.completedFuture(cachedData.get());
                        }
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
                                redisCache.put(cacheKey, historicalCost, 10);

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
         * Get historical cost for specific dimension value
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
                                redisCache.put(cacheKey, historicalCost, 10);

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