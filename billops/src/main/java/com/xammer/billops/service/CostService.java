package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final double ANOMALY_THRESHOLD = 1.20;
    private static final Logger logger = LoggerFactory.getLogger(CostService.class);

    public CostService(AwsClientProvider awsClientProvider, CloudAccountRepository cloudAccountRepository) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Get cost and usage data from AWS Cost Explorer
     * This method integrates with real AWS Cost Explorer API
     */
    public GetCostAndUsageResponse getCostAndUsage(String accountId, String startDate, String endDate, String granularity, String groupBy) {
        try {
            logger.info("Fetching cost and usage data from AWS for account: {} from {} to {}", accountId, startDate, endDate);

            // Get CloudAccount by accountId
            CloudAccount account = getCloudAccountById(accountId);
            if (account == null) {
                logger.warn("Account not found: {}, cannot fetch cost data", accountId);
                return null;
            }

            // Get AWS Cost Explorer client
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);

            // Build the request
            GetCostAndUsageRequest.Builder requestBuilder = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(startDate).end(endDate).build())
                    .metrics("UnblendedCost");

            // Set granularity
            if ("MONTHLY".equals(granularity)) {
                requestBuilder.granularity(Granularity.MONTHLY);
            } else if ("DAILY".equals(granularity)) {
                requestBuilder.granularity(Granularity.DAILY);
            }

            // Add grouping if specified
            if ("SERVICE".equals(groupBy)) {
                requestBuilder.groupBy(GroupDefinition.builder()
                        .type(GroupDefinitionType.DIMENSION)
                        .key("SERVICE")
                        .build());
            } else if ("REGION".equals(groupBy)) {
                requestBuilder.groupBy(GroupDefinition.builder()
                        .type(GroupDefinitionType.DIMENSION)
                        .key("REGION")
                        .build());
            }

            // Filter by specific account if multi-account setup
           /* if (account.getAccountId() != null && !account.getAccountId().isEmpty()) {
                requestBuilder.filter(Expression.builder()
                        .dimensions(DimensionValues.builder()
                                .key(Dimension.LINKED_ACCOUNT)
                                .values(account.getAccountId())
                                .build())
                        .build());
            }*/

            GetCostAndUsageRequest request = requestBuilder.build();
            GetCostAndUsageResponse response = client.getCostAndUsage(request);

            logger.info("Successfully retrieved cost data from AWS for account: {}", accountId);
            return response;

        } catch (CostExplorerException e) {
            logger.error("AWS Cost Explorer API error for account {}: {}", accountId, e.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Unexpected error fetching cost data for account {}: {}", accountId, e.getMessage());
            return null;
        }
    }

    /**
     * Get cost history with anomaly detection
     */
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
            logger.warn("Could not fetch cost history for account {}. Error: {}", account.getAccountName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get cost breakdown by dimension (SERVICE, REGION, etc.)
     */
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
                    .collect(Collectors.toList());
        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost by dimension for account {}. Error: {}", account.getAccountName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get cost for specific service in different regions
     */
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
                    .collect(Collectors.toList());

        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost for service '{}' in account {}. Error: {}", serviceName, account.getAccountName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Helper method to get CloudAccount by accountId
     */
    private CloudAccount getCloudAccountById(String accountId) {
        try {
            // Try to find by account ID string first
            Optional<CloudAccount> account = cloudAccountRepository.findByAwsAccountId(accountId);
            if (account.isPresent()) {
                return account.get();
            }

            // If not found, try to parse as Long and find by ID
            try {
                Long id = Long.parseLong(accountId);
                return cloudAccountRepository.findById(id).orElse(null);
            } catch (NumberFormatException e) {
                logger.warn("Could not parse accountId {} as Long", accountId);
                return null;
            }
        } catch (Exception e) {
            logger.error("Error finding account by ID {}: {}", accountId, e.getMessage());
            return null;
        }
    }
}
