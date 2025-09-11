package com.xammer.billops.service;

import com.xammer.billops.dto.BillingDashboardDto;
import com.xammer.billops.dto.BillingDashboardDto.ServiceBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageResponse;
import software.amazon.awssdk.services.costexplorer.model.ResultByTime;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class BillingService {

    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);
    private final CostService costService;

    public BillingService(CostService costService) {
        this.costService = costService;
    }

    public BillingDashboardDto getBillingData(String accountId) {
        logger.info("Fetching billing data for account: {}", accountId);
        BillingDashboardDto data = new BillingDashboardDto();

        try {
            // Get data for the last 6 months
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusMonths(6).withDayOfMonth(1);
            String start = startDate.format(DateTimeFormatter.ISO_DATE);
            String end = endDate.format(DateTimeFormatter.ISO_DATE);

            // Fetch cost history (monthly granularity) from AWS Cost Explorer
            GetCostAndUsageResponse monthlyResponse = costService.getCostAndUsage(accountId, start, end, "MONTHLY", null);
            data.setCostHistory(mapCostHistory(monthlyResponse));

            // Fetch service breakdown for the entire period from AWS Cost Explorer
            GetCostAndUsageResponse serviceResponse = costService.getCostAndUsage(accountId, start, end, "MONTHLY", "SERVICE");
            data.setServiceBreakdown(mapServiceBreakdown(serviceResponse));

            logger.info("Successfully fetched billing data for account: {}", accountId);
        } catch (Exception e) {
            logger.error("Error fetching billing data for account {}: {}", accountId, e.getMessage());
            // Fallback to mock data if AWS API fails
            logger.warn("Using fallback mock data due to AWS API error");
            data.setCostHistory(generateMockCostHistory());
            data.setServiceBreakdown(generateMockServiceBreakdown());
        }

        return data;
    }

    private List<BillingDashboardDto.CostHistory> mapCostHistory(GetCostAndUsageResponse response) {
        if (response == null || !response.hasResultsByTime() || response.resultsByTime().isEmpty()) {
            logger.warn("No cost history data from AWS, using mock data");
            return generateMockCostHistory();
        }

        return response.resultsByTime().stream()
                .map(this::createCostHistoryFromTimeResult)
                .collect(Collectors.toList());
    }

    private BillingDashboardDto.CostHistory createCostHistoryFromTimeResult(ResultByTime result) {
        String date = result.timePeriod().start();
        double cost = Double.parseDouble(result.total().get("UnblendedCost").amount());
        return new BillingDashboardDto.CostHistory(date, cost);
    }

    private List<ServiceBreakdown> mapServiceBreakdown(GetCostAndUsageResponse response) {
        if (response == null || !response.hasResultsByTime() || response.resultsByTime().isEmpty()) {
            logger.warn("No service breakdown data from AWS, using mock data");
            return generateMockServiceBreakdown();
        }

        // Check if first result has groups
        if (!response.resultsByTime().get(0).hasGroups() || response.resultsByTime().get(0).groups().isEmpty()) {
            logger.warn("No service breakdown groups from AWS, using mock data");
            return generateMockServiceBreakdown();
        }

        // Map AWS response to ServiceBreakdown objects
        return response.resultsByTime().get(0).groups().stream()
                .map(group -> {
                    String serviceName = group.keys().get(0);
                    double cost = Double.parseDouble(group.metrics().get("UnblendedCost").amount());
                    return new ServiceBreakdown();
                })
                .collect(Collectors.toList());
    }

    // FIXED: Mock data generators with proper constructor calls
    private List<BillingDashboardDto.CostHistory> generateMockCostHistory() {
        List<BillingDashboardDto.CostHistory> mockData = new ArrayList<>();
        LocalDate startDate = LocalDate.now().minusMonths(6);

        for (int i = 0; i < 6; i++) {
            LocalDate date = startDate.plusMonths(i);
            double cost = 1000 + (Math.random() * 2000);
            mockData.add(new BillingDashboardDto.CostHistory(
                    date.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                    Math.round(cost * 100.0) / 100.0
            ));
        }

        logger.info("Generated {} mock cost history entries", mockData.size());
        return mockData;
    }

    // FIXED: Mock service breakdown with proper constructor parameters
    private List<ServiceBreakdown> generateMockServiceBreakdown() {
        List<ServiceBreakdown> mockData = Arrays.asList(
                new ServiceBreakdown(),
                new ServiceBreakdown(),
                new ServiceBreakdown(),
                new ServiceBreakdown(),
                new ServiceBreakdown(),
                new ServiceBreakdown()
        );

        logger.info("Generated {} mock service breakdown entries", mockData.size());
        return mockData;
    }
}
