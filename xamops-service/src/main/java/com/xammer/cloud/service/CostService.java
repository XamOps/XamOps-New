package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class CostService {

    private static final Logger logger = LoggerFactory.getLogger(CostService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final RedisCacheService redisCache;

    @Autowired
    public CostService(CloudAccountRepository cloudAccountRepository, AwsClientProvider awsClientProvider, RedisCacheService redisCache) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.redisCache = redisCache;
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<CostDto>> getCostBreakdown(String accountId, String groupBy, String tag, boolean forceRefresh) {
        String cacheKey = "costBreakdown-" + accountId + "-" + groupBy + "-" + (tag != null ? tag : "");
        if (!forceRefresh) {
            Optional<List<CostDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

        return CompletableFuture.supplyAsync(() -> {
            try {
                DateInterval dateInterval = DateInterval.builder()
                        .start(LocalDate.now().withDayOfMonth(1).toString())
                        .end(LocalDate.now().plusDays(1).toString())
                        .build();

                GroupDefinition groupDefinition = "TAG".equalsIgnoreCase(groupBy)
                        ? GroupDefinition.builder().type(GroupDefinitionType.TAG).key(tag).build()
                        : GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key(groupBy).build();

                GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                        .timePeriod(dateInterval)
                        .granularity(Granularity.MONTHLY)
                        .metrics("UnblendedCost")
                        .groupBy(groupDefinition)
                        .build();

                List<CostDto> costs = ce.getCostAndUsage(request).resultsByTime().get(0).groups().stream()
                        .map(group -> new CostDto(
                                group.keys().get(0),
                                Double.parseDouble(group.metrics().get("UnblendedCost").amount())
                        ))
                        .collect(Collectors.toList());

                redisCache.put(cacheKey, costs);
                return costs;
            } catch (Exception e) {
                logger.error("Error fetching cost breakdown for account {}: {}", accountId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<HistoricalCostDto> getHistoricalCost(
            String accountId, String serviceName, String regionName, int days, boolean forceRefresh) {
        logger.info("Fetching historical cost for account {}, service: {}, region: {}, days: {}",
                accountId, serviceName, regionName, days);
        CloudAccount account = getAccount(accountId);
        CostExplorerClient ceClient = awsClientProvider.getCostExplorerClient(account);

        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();

        try {
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(days);
            
            Expression.Builder filterBuilder = Expression.builder();
            List<Expression> andExpressions = new ArrayList<>();

            if (serviceName != null && !serviceName.isBlank()) {
                andExpressions.add(Expression.builder()
                        .dimensions(DimensionValues.builder().key(Dimension.SERVICE).values(serviceName).build())
                        .build());
            }
            if (regionName != null && !regionName.isBlank()) {
                andExpressions.add(Expression.builder()
                        .dimensions(DimensionValues.builder().key(Dimension.REGION).values(regionName).build())
                        .build());
            }

            Expression finalFilter = null;
            if (!andExpressions.isEmpty()) {
                finalFilter = andExpressions.size() == 1 ? andExpressions.get(0) : Expression.builder().and(andExpressions).build();
            }

            GetCostAndUsageRequest.Builder requestBuilder = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(startDate.toString()).end(endDate.plusDays(1).toString()).build())
                    .granularity(Granularity.DAILY)
                    .metrics("UnblendedCost");

            if (finalFilter != null) {
                requestBuilder.filter(finalFilter);
            }
            GetCostAndUsageRequest request = requestBuilder.build();

            for (ResultByTime result : ceClient.getCostAndUsage(request).resultsByTime()) {
                labels.add(LocalDate.parse(result.timePeriod().start()).format(DateTimeFormatter.ISO_LOCAL_DATE));
                double cost = Double.parseDouble(result.total().get("UnblendedCost").amount());
                costs.add(cost);
            }

        } catch (Exception e) {
            logger.error("Could not fetch historical cost for account {}. Service: {}, Region: {}, Days: {}",
                    accountId, serviceName, regionName, days, e);
        }

        HistoricalCostDto result = new HistoricalCostDto(labels, costs);
        return CompletableFuture.completedFuture(result);
    }


    @Async("awsTaskExecutor")
    public CompletableFuture<HistoricalCostDto> getHistoricalCostForDimension(String accountId, String groupBy, String dimensionValue, String tagKey, boolean forceRefresh) {
        String cacheKey = "historicalCost-" + accountId + "-" + groupBy + "-" + dimensionValue + "-" + (tagKey != null ? tagKey : "");
        if (!forceRefresh) {
            Optional<HistoricalCostDto> cachedData = redisCache.get(cacheKey, HistoricalCostDto.class);
            if (cachedData.isPresent()) {
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

                    Expression filter = Expression.builder().dimensions(DimensionValues.builder().key(groupBy).values(dimensionValue).build()).build();

                    GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                            .timePeriod(dateInterval)
                            .granularity(Granularity.MONTHLY)
                            .metrics("UnblendedCost")
                            .filter(filter)
                            .build();

                    double cost = Double.parseDouble(ce.getCostAndUsage(request).resultsByTime().get(0).total().get("UnblendedCost").amount());
                    costs.add(cost);
                }

                HistoricalCostDto historicalCost = new HistoricalCostDto(labels, costs);
                redisCache.put(cacheKey, historicalCost);
                return historicalCost;
            } catch (Exception e) {
                logger.error("Error fetching historical cost for account {}: {}", accountId, e.getMessage());
                return new HistoricalCostDto(Collections.emptyList(), Collections.emptyList());
            }
        });
    }
}