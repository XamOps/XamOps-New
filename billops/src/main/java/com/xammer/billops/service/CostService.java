package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.AwsCreditDto;
import com.xammer.billops.repository.BillOpsCachedDataRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.cloud.domain.CachedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CostService {

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final CostMockService costMockService;
    private final RedisCacheService redisCacheService;
    private final BillOpsCachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(CostService.class);

    @Value("${aws.costexplorer.mock:false}")
    private boolean useMockData;

    @PostConstruct
    public void init() {
        logger.info("✅ CostService initialized (Mock Mode: {})", useMockData);
    }

    public CostService(AwsClientProvider awsClientProvider,
            CloudAccountRepository cloudAccountRepository,
            CostMockService costMockService,
            RedisCacheService redisCacheService,
            BillOpsCachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.costMockService = costMockService;
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Get AWS Credits for an account.
     * Returns mock data when aws.costexplorer.mock=true.
     */
    public AwsCreditDto getAwsCredits(CloudAccount account) {
        if (useMockData) {
            return costMockService.getMockAwsCredits(account);
        }

        logger.info("Fetching AWS credit data for account: {}", account.getAwsAccountId());

        // NOTE: Real implementation requires AWS Cost Explorer API for credits
        // For now, this returns the mock service data even when mock is disabled
        // TODO: Implement real AWS Credits API integration when available
        return costMockService.getMockAwsCredits(account);
    }

    public List<Map<String, Object>> getCostHistory(CloudAccount account, Integer year, Integer month) {
        return getCostHistory(account, year, month, false);
    }

    public List<Map<String, Object>> getCostHistory(CloudAccount account, Integer year, Integer month, boolean forceRefresh) {
        if (useMockData) {
            return costMockService.getMockCostHistory(account, year, month);
        }

        String cacheKey = String.format("BILLOPS_COST_HISTORY::%s::%s::%s",
                account.getAwsAccountId(), year, month);

        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(cacheKey);
                if (dbCached.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", cacheKey);
                        return dbData;
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", cacheKey, dbEx.getMessage());
            }
        }

        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate end = (year != null && month != null) ? YearMonth.of(year, month).atEndOfMonth()
                    : LocalDate.now();
            LocalDate start = end.minusMonths(5).withDayOfMonth(1);

            Expression accountFilter = Expression.builder()
                    .dimensions(DimensionValues.builder()
                            .key(Dimension.LINKED_ACCOUNT)
                            .values(account.getAwsAccountId())
                            .build())
                    .build();

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(start.toString()).end(end.plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .filter(accountFilter)
                    .build();

            List<ResultByTime> results = client.getCostAndUsage(request).resultsByTime();

            List<Map<String, Object>> result = results.stream().map(r -> {
                Map<String, Object> dataPoint = new HashMap<>();
                dataPoint.put("date", r.timePeriod().start());
                dataPoint.put("cost", Double.parseDouble(r.total().get("UnblendedCost").amount()));
                return dataPoint;
            }).collect(Collectors.toList());

            redisCacheService.put(cacheKey, result, 60);
            try {
                CachedData snapshot = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
                snapshot.setCacheKey(cacheKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", cacheKey, dbEx.getMessage());
            }
            return result;
        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost history for account {}. Error: {}", account.getAccountName(),
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getCostByDimension(CloudAccount account, String dimension, Integer year,
            Integer month) {
        return getCostByDimension(account, dimension, year, month, false);
    }

    public List<Map<String, Object>> getCostByDimension(CloudAccount account, String dimension, Integer year,
            Integer month, boolean forceRefresh) {
        if (useMockData) {
            return costMockService.getMockCostByDimension(account, dimension, year, month);
        }

        logger.info("[CostService] getCostByDimension called: account='{}' (awsId='{}'), dimension={}, year={}, month={}",
                account.getAccountName(), account.getAwsAccountId(), dimension, year, month);

        String cacheKey = String.format("BILLOPS_COST_DIMENSION::%s::%s::%s::%s",
                account.getAwsAccountId(), dimension, year, month);

        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(cacheKey);
                if (dbCached.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", cacheKey);
                        return dbData;
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", cacheKey, dbEx.getMessage());
            }
        }

        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate startDate;
            LocalDate endDate;

            if (year != null && month != null) {
                YearMonth yearMonth = YearMonth.of(year, month);
                startDate = yearMonth.atDay(1);
                endDate = yearMonth.atEndOfMonth();
            } else {
                endDate = LocalDate.now();
                startDate = endDate.withDayOfMonth(1);
            }

            DateInterval dateInterval = DateInterval.builder()
                    .start(startDate.toString())
                    .end(endDate.plusDays(1).toString())
                    .build();

            // No LINKED_ACCOUNT filter here — getCostByDimension returns org-wide service totals,
            // which correctly reflect the account's billed costs (including all linked accounts under
            // this payer). Filtering by LINKED_ACCOUNT here would only return direct management-account
            // charges (~$15) instead of the real consolidated total (~$2,072).
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(dateInterval)
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key(dimension).build())
                    .build();

            GetCostAndUsageResponse response = client.getCostAndUsage(request);

            if (response == null || !response.hasResultsByTime() || response.resultsByTime().isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> result = response.resultsByTime().get(0).groups().stream()
                    .map(group -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", group.keys().get(0));
                        map.put("cost", Double.parseDouble(group.metrics().get("UnblendedCost").amount()));
                        return map;
                    })
                    .filter(map -> (double) map.get("cost") > 0.01)
                    .collect(Collectors.toList());

            double totalCost = result.stream().mapToDouble(m -> (double) m.get("cost")).sum();
            logger.info("[CostService] getCostByDimension result for '{}': {} service(s), total cost = ${}",
                    account.getAwsAccountId(), result.size(), String.format("%.2f", totalCost));

            redisCacheService.put(cacheKey, result, 60);
            try {
                CachedData snapshot = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
                snapshot.setCacheKey(cacheKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", cacheKey, dbEx.getMessage());
            }
            return result;
        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost by dimension for account {}. Error: {}", account.getAccountName(),
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getCostForServiceInRegion(CloudAccount account, String serviceName, Integer year,
            Integer month) {
        return getCostForServiceInRegion(account, serviceName, year, month, false);
    }

    public List<Map<String, Object>> getCostForServiceInRegion(CloudAccount account, String serviceName, Integer year,
            Integer month, boolean forceRefresh) {
        if (useMockData) {
            return costMockService.getMockCostForServiceInRegion(account, serviceName, year, month);
        }

        String cacheKey = String.format("BILLOPS_COST_SVC_REGION::%s::%s::%s::%s",
                account.getAwsAccountId(), serviceName, year, month);

        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(cacheKey);
                if (dbCached.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", cacheKey);
                        return dbData;
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", cacheKey, dbEx.getMessage());
            }
        }

        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate startDate;
            LocalDate endDate;

            if (year != null && month != null) {
                YearMonth yearMonth = YearMonth.of(year, month);
                startDate = yearMonth.atDay(1);
                endDate = yearMonth.atEndOfMonth();
            } else {
                endDate = LocalDate.now();
                startDate = endDate.withDayOfMonth(1);
            }

            DateInterval dateInterval = DateInterval.builder()
                    .start(startDate.toString())
                    .end(endDate.plusDays(1).toString())
                    .build();

            Expression serviceFilter = Expression.builder()
                    .dimensions(DimensionValues.builder().key(Dimension.SERVICE).values(serviceName).build())
                    .build();
            Expression accountFilter = Expression.builder()
                    .dimensions(DimensionValues.builder()
                            .key(Dimension.LINKED_ACCOUNT)
                            .values(account.getAwsAccountId())
                            .build())
                    .build();
            Expression filter = Expression.builder().and(serviceFilter, accountFilter).build();

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(dateInterval)
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .filter(filter)
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("REGION").build())
                    .build();

            GetCostAndUsageResponse response = client.getCostAndUsage(request);

            if (response == null || !response.hasResultsByTime() || response.resultsByTime().isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> result = response.resultsByTime().get(0).groups().stream()
                    .map(group -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", group.keys().get(0));
                        map.put("cost", Double.parseDouble(group.metrics().get("UnblendedCost").amount()));
                        return map;
                    })
                    .filter(map -> (double) map.get("cost") > 0.01)
                    .collect(Collectors.toList());

            redisCacheService.put(cacheKey, result, 60);
            try {
                CachedData snapshot = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
                snapshot.setCacheKey(cacheKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", cacheKey, dbEx.getMessage());
            }
            return result;

        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost for service '{}' in account {}. Error: {}", serviceName,
                    account.getAccountName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getCostByResource(CloudAccount account, String serviceName, String region,
            Integer year, Integer month) {
        return getCostByResource(account, serviceName, region, year, month, false);
    }

    public List<Map<String, Object>> getCostByResource(CloudAccount account, String serviceName, String region,
            Integer year, Integer month, boolean forceRefresh) {
        if (useMockData) {
            return costMockService.getMockCostByResource(account, serviceName, region, year, month);
        }

        logger.info("Fetching resource costs for service '{}' in region '{}'", serviceName, region);

        String cacheKey = String.format("BILLOPS_COST_RESOURCE::%s::%s::%s::%s::%s",
                account.getAwsAccountId(), serviceName, region, year, month);

        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(cacheKey);
                if (dbCached.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", cacheKey);
                        return dbData;
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", cacheKey, dbEx.getMessage());
            }
        }

        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate startDate;
            LocalDate endDate;

            if (year != null && month != null) {
                YearMonth yearMonth = YearMonth.of(year, month);
                startDate = yearMonth.atDay(1);
                endDate = yearMonth.atEndOfMonth();
            } else {
                endDate = LocalDate.now();
                startDate = endDate.withDayOfMonth(1);
            }

            Expression serviceFilter = Expression.builder()
                    .dimensions(DimensionValues.builder().key(Dimension.SERVICE).values(serviceName).build())
                    .build();
            Expression accountFilter = Expression.builder()
                    .dimensions(DimensionValues.builder()
                            .key(Dimension.LINKED_ACCOUNT)
                            .values(account.getAwsAccountId())
                            .build())
                    .build();

            GetCostAndUsageRequest.Builder requestBuilder = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(startDate.toString()).end(endDate.plusDays(1).toString())
                            .build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost", "UsageQuantity")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("USAGE_TYPE").build());

            if (region != null && !region.isEmpty() && !"Global".equalsIgnoreCase(region)) {
                logger.info("Applying REGIONAL filter for region: {}", region);
                Expression regionFilter = Expression.builder()
                        .dimensions(DimensionValues.builder().key(Dimension.REGION).values(region).build())
                        .build();
                requestBuilder.filter(Expression.builder().and(serviceFilter, regionFilter, accountFilter).build());
            } else {
                logger.info("Applying GLOBAL (service-only) filter.");
                requestBuilder.filter(Expression.builder().and(serviceFilter, accountFilter).build());
            }

            GetCostAndUsageRequest request = requestBuilder.build();
            GetCostAndUsageResponse response = client.getCostAndUsage(request);

            if (response == null || !response.hasResultsByTime() || response.resultsByTime().isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> result = response.resultsByTime().get(0).groups().stream()
                    .map(group -> {
                        Map<String, Object> map = new HashMap<>();
                        String usageType = group.keys().get(0);
                        MetricValue costMetric = group.metrics().get("UnblendedCost");
                        MetricValue usageMetric = group.metrics().get("UsageQuantity");

                        map.put("name", usageType);
                        map.put("id", usageType);
                        map.put("cost", Double.parseDouble(costMetric.amount()));
                        // MODIFIED: Added quantity and unit
                        map.put("quantity", usageMetric != null ? Double.parseDouble(usageMetric.amount()) : 0.0);
                        map.put("unit", usageMetric != null ? usageMetric.unit() : "");
                        return map;
                    })
                    .filter(map -> (double) map.get("cost") > 0)
                    .collect(Collectors.toList());

            redisCacheService.put(cacheKey, result, 60);
            try {
                CachedData snapshot = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
                snapshot.setCacheKey(cacheKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", cacheKey, dbEx.getMessage());
            }
            return result;

        } catch (CostExplorerException e) {
            logger.warn("Could not fetch resource costs for service '{}' in region '{}'. Error: {}", serviceName,
                    region, e.getMessage());
            return Collections.emptyList();
        }
    }
}
