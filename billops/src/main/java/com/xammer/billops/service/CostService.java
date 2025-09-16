package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CostService {

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private static final Logger logger = LoggerFactory.getLogger(CostService.class);

    public CostService(AwsClientProvider awsClientProvider, CloudAccountRepository cloudAccountRepository) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    public List<Map<String, Object>> getCostHistory(CloudAccount account, Integer year, Integer month) {
        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate end = (year != null && month != null) ? YearMonth.of(year, month).atEndOfMonth() : LocalDate.now();
            LocalDate start = end.minusMonths(5).withDayOfMonth(1);

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(start.toString()).end(end.plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .build();

            List<ResultByTime> results = client.getCostAndUsage(request).resultsByTime();

            return results.stream().map(result -> {
                Map<String, Object> dataPoint = new HashMap<>();
                dataPoint.put("date", result.timePeriod().start());
                dataPoint.put("cost", Double.parseDouble(result.total().get("UnblendedCost").amount()));
                return dataPoint;
            }).collect(Collectors.toList());
        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost history for account {}. Error: {}", account.getAccountName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getCostByDimension(CloudAccount account, String dimension, Integer year, Integer month) {
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

            return response.resultsByTime().get(0).groups().stream()
                    .map(group -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", group.keys().get(0));
                        map.put("cost", Double.parseDouble(group.metrics().get("UnblendedCost").amount()));
                        return map;
                    })
                    .filter(map -> (double) map.get("cost") > 0.01)
                    .collect(Collectors.toList());
        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost by dimension for account {}. Error: {}", account.getAccountName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getCostForServiceInRegion(CloudAccount account, String serviceName, Integer year, Integer month) {
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

            Expression filter = Expression.builder()
                    .dimensions(DimensionValues.builder().key(Dimension.SERVICE).values(serviceName).build())
                    .build();

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

            return response.resultsByTime().get(0).groups().stream()
                    .map(group -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", group.keys().get(0));
                        map.put("cost", Double.parseDouble(group.metrics().get("UnblendedCost").amount()));
                        return map;
                    })
                    .filter(map -> (double) map.get("cost") > 0.01)
                    .collect(Collectors.toList());

        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost for service '{}' in account {}. Error: {}", serviceName, account.getAccountName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getCostByResource(CloudAccount account, String serviceName, String region, Integer year, Integer month) {
        logger.info("Fetching resource costs for service '{}' in region '{}'", serviceName, region);
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

            GetCostAndUsageRequest.Builder requestBuilder = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(startDate.toString()).end(endDate.plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost", "UsageQuantity") // MODIFIED: Added UsageQuantity
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("USAGE_TYPE").build());

            if (region != null && !region.isEmpty() && !"Global".equalsIgnoreCase(region)) {
                logger.info("Applying REGIONAL filter for region: {}", region);
                Expression regionFilter = Expression.builder()
                        .dimensions(DimensionValues.builder().key(Dimension.REGION).values(region).build())
                        .build();
                requestBuilder.filter(Expression.builder().and(serviceFilter, regionFilter).build());
            } else {
                logger.info("Applying GLOBAL (service-only) filter.");
                requestBuilder.filter(serviceFilter);
            }

            GetCostAndUsageRequest request = requestBuilder.build();
            GetCostAndUsageResponse response = client.getCostAndUsage(request);

            if (response == null || !response.hasResultsByTime() || response.resultsByTime().isEmpty()) {
                return Collections.emptyList();
            }

            return response.resultsByTime().get(0).groups().stream()
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

        } catch (CostExplorerException e) {
            logger.warn("Could not fetch resource costs for service '{}' in region '{}'. Error: {}", serviceName, region, e.getMessage());
            return Collections.emptyList();
        }
    }
}