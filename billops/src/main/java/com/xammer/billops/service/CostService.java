package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CostService {

    private final AwsClientProvider awsClientProvider;
    private static final double ANOMALY_THRESHOLD = 1.20;
    private static final Logger logger = LoggerFactory.getLogger(CostService.class);

    public CostService(AwsClientProvider awsClientProvider) {
        this.awsClientProvider = awsClientProvider;
    }

    public List<Map<String, Object>> getCostHistory(CloudAccount account) {
        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusMonths(6).withDayOfMonth(1);

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(start.toString()).end(end.toString()).build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .build();

            List<ResultByTime> results = client.getCostAndUsage(request).resultsByTime();

            double previousCost = -1;
            List<Map<String, Object>> costData = new ArrayList<>();

            for (ResultByTime result : results) {
                double currentCost = Double.parseDouble(result.total().get("UnblendedCost").amount());
                boolean isAnomaly = previousCost > 0 && currentCost > (previousCost * ANOMALY_THRESHOLD);

                Map<String, Object> dataPoint = new HashMap<>();
                dataPoint.put("date", result.timePeriod().start());
                dataPoint.put("cost", currentCost);
                dataPoint.put("isAnomaly", isAnomaly);
                costData.add(dataPoint);

                previousCost = currentCost;
            }
            return costData;
        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost history for account {}. This is expected for new accounts. Error: {}", account.getAwsAccountId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // --- METHOD UPDATED to accept year and month ---
    public List<Map<String, Object>> getCostByDimension(CloudAccount account, String dimension, Integer year, Integer month) {
        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate startDate;
            LocalDate endDate;

            if (year != null && month != null) {
                // Use the specified month
                YearMonth yearMonth = YearMonth.of(year, month);
                startDate = yearMonth.atDay(1);
                endDate = yearMonth.atEndOfMonth();
            } else {
                // Default to the current month-to-date
                endDate = LocalDate.now();
                startDate = endDate.withDayOfMonth(1);
            }

            DateInterval dateInterval = DateInterval.builder()
                    .start(startDate.toString())
                    .end(endDate.plusDays(1).toString()) // API is exclusive of end date
                    .build();

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(dateInterval)
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key(dimension).build())
                    .build();

            if (client.getCostAndUsage(request).resultsByTime().isEmpty()) {
                return Collections.emptyList();
            }

            return client.getCostAndUsage(request).resultsByTime().get(0).groups().stream()
                    .map(group -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", group.keys().get(0));
                        map.put("cost", Double.parseDouble(group.metrics().get("UnblendedCost").amount()));
                        return map;
                    })
                    .collect(Collectors.toList());
        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost by dimension for account {}. This is expected for new accounts. Error: {}", account.getAwsAccountId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // --- NEW METHOD ADDED for the drill-down ---
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

            // Filter by service, group by region
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

            if (client.getCostAndUsage(request).resultsByTime().isEmpty()) {
                return Collections.emptyList();
            }

            return client.getCostAndUsage(request).resultsByTime().get(0).groups().stream()
                    .map(group -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", group.keys().get(0));
                        map.put("cost", Double.parseDouble(group.metrics().get("UnblendedCost").amount()));
                        // You could add instance count here if needed by another service call
                        map.put("instanceCount", 10); // Placeholder
                        return map;
                    })
                    .collect(Collectors.toList());

        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost for service '{}' in account {}. Error: {}", serviceName, account.getAwsAccountId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    public double getForecastedSpend(CloudAccount account) {
        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate end = LocalDate.now().plusMonths(1).withDayOfMonth(1);
            LocalDate start = LocalDate.now().withDayOfMonth(1);

            GetCostForecastRequest request = GetCostForecastRequest.builder()
                .timePeriod(DateInterval.builder().start(start.toString()).end(end.toString()).build())
                .granularity(Granularity.MONTHLY)
                .metric(Metric.UNBLENDED_COST)
                .build();

            GetCostForecastResponse response = client.getCostForecast(request);
            return Double.parseDouble(response.total().amount());
        } catch (CostExplorerException e) {
            logger.warn("Could not get cost forecast for account {}. This is expected for new accounts. Error: {}", account.getAwsAccountId(), e.getMessage());
            return 0.0; // Return 0 if forecast fails
        }
    }
}