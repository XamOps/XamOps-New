package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.AwsCreditDto;
import com.xammer.billops.dto.CreditDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Mock service for AWS Cost Explorer data.
 * Generates deterministic mock data based on account ID for consistent local development experience.
 * This service reduces AWS API costs during development while providing realistic test data.
 */
@Service
public class CostMockService {

    private static final Logger logger = LoggerFactory.getLogger(CostMockService.class);

    // Common AWS services for mock data generation
    private static final List<String> AWS_SERVICES = Arrays.asList(
            "Amazon Elastic Compute Cloud - Compute",
            "Amazon Simple Storage Service",
            "Amazon Relational Database Service",
            "AWS Lambda",
            "Amazon CloudWatch",
            "Amazon DynamoDB",
            "Amazon EC2 Container Service",
            "AWS Key Management Service",
            "Amazon Route 53",
            "Amazon Simple Notification Service"
    );

    // AWS regions for mock data
    private static final List<String> AWS_REGIONS = Arrays.asList(
            "us-east-1",
            "us-west-2",
            "eu-west-1",
            "ap-south-1",
            "ap-southeast-1"
    );

    /**
     * Generates a deterministic seed from account ID for consistent mock data.
     */
    private long getSeedFromAccountId(String accountId) {
        return accountId != null ? accountId.hashCode() : System.currentTimeMillis();
    }

    /**
     * Generate mock AWS credits data.
     */
    public AwsCreditDto getMockAwsCredits(CloudAccount account) {
        logger.info("[MOCK] Generating mock AWS credit data for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));

        List<CreditDetailDto> mockCredits = Arrays.asList(
                new CreditDetailDto(
                        "Xammer_PvC_Credits",
                        LocalDate.now().plusMonths(6),
                        new BigDecimal(String.format("%.2f", 50 + random.nextDouble() * 100)),
                        new BigDecimal(String.format("%.2f", 4000 + random.nextDouble() * 1000))),
                new CreditDetailDto(
                        "AWS_Activate_Credits",
                        LocalDate.now().plusMonths(12),
                        new BigDecimal(String.format("%.2f", 20 + random.nextDouble() * 50)),
                        new BigDecimal(String.format("%.2f", 2000 + random.nextDouble() * 500)))
        );

        BigDecimal totalUsed = mockCredits.stream()
                .map(CreditDetailDto::getAmountUsed)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRemaining = mockCredits.stream()
                .map(CreditDetailDto::getAmountRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new AwsCreditDto(totalRemaining, totalUsed, mockCredits);
    }

    /**
     * Generate mock cost history data (last 6 months by default).
     */
    public List<Map<String, Object>> getMockCostHistory(CloudAccount account, Integer year, Integer month) {
        logger.info("[MOCK] Generating mock cost history for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<Map<String, Object>> results = new ArrayList<>();

        LocalDate endDate = (year != null && month != null)
                ? YearMonth.of(year, month).atEndOfMonth()
                : LocalDate.now();
        LocalDate startDate = endDate.minusMonths(5).withDayOfMonth(1);

        // Base cost that varies by account
        double baseCost = 1000 + (random.nextDouble() * 4000);

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            Map<String, Object> dataPoint = new HashMap<>();
            dataPoint.put("date", current.withDayOfMonth(1).toString());

            // Add some variation to make the data realistic
            double variation = 0.8 + (random.nextDouble() * 0.4); // 80% to 120% of base
            double monthlyCost = baseCost * variation;

            dataPoint.put("cost", Math.round(monthlyCost * 100.0) / 100.0);
            results.add(dataPoint);

            current = current.plusMonths(1);
        }

        return results;
    }

    /**
     * Generate mock cost breakdown by dimension (SERVICE, REGION, etc.).
     */
    public List<Map<String, Object>> getMockCostByDimension(CloudAccount account, String dimension, Integer year, Integer month) {
        logger.info("[MOCK] Generating mock cost by dimension '{}' for account: {}", dimension, account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()) + dimension.hashCode());
        List<Map<String, Object>> results = new ArrayList<>();

        List<String> dimensionValues;
        if ("SERVICE".equalsIgnoreCase(dimension)) {
            dimensionValues = AWS_SERVICES;
        } else if ("REGION".equalsIgnoreCase(dimension)) {
            dimensionValues = AWS_REGIONS;
        } else {
            dimensionValues = Arrays.asList("Value1", "Value2", "Value3", "Value4", "Value5");
        }

        double totalCost = 2000 + (random.nextDouble() * 3000);
        double remaining = totalCost;

        for (int i = 0; i < dimensionValues.size() && remaining > 0.01; i++) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", dimensionValues.get(i));

            // Distribute costs with larger services getting more
            double costPercentage = (i == 0) ? 0.35 : (i == 1) ? 0.25 : (i == 2) ? 0.15 : 0.05 + (random.nextDouble() * 0.05);
            double cost = Math.min(totalCost * costPercentage, remaining);
            cost = Math.round(cost * 100.0) / 100.0;

            if (cost > 0.01) {
                entry.put("cost", cost);
                results.add(entry);
                remaining -= cost;
            }
        }

        return results;
    }

    /**
     * Generate mock cost for a specific service by region.
     */
    public List<Map<String, Object>> getMockCostForServiceInRegion(CloudAccount account, String serviceName, Integer year, Integer month) {
        logger.info("[MOCK] Generating mock cost for service '{}' by region for account: {}", serviceName, account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()) + serviceName.hashCode());
        List<Map<String, Object>> results = new ArrayList<>();

        double totalServiceCost = 500 + (random.nextDouble() * 1500);
        double remaining = totalServiceCost;

        for (int i = 0; i < AWS_REGIONS.size() && remaining > 0.01; i++) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", AWS_REGIONS.get(i));

            // Primary region gets more cost
            double costPercentage = (i == 0) ? 0.4 : (i == 1) ? 0.25 : 0.1 + (random.nextDouble() * 0.05);
            double cost = Math.min(totalServiceCost * costPercentage, remaining);
            cost = Math.round(cost * 100.0) / 100.0;

            if (cost > 0.01) {
                entry.put("cost", cost);
                results.add(entry);
                remaining -= cost;
            }
        }

        return results;
    }

    /**
     * Generate mock cost breakdown by resource/usage type.
     */
    public List<Map<String, Object>> getMockCostByResource(CloudAccount account, String serviceName, String region, Integer year, Integer month) {
        logger.info("[MOCK] Generating mock cost by resource for service '{}' in region '{}' for account: {}",
                serviceName, region, account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()) + serviceName.hashCode() + (region != null ? region.hashCode() : 0));
        List<Map<String, Object>> results = new ArrayList<>();

        // Mock usage types based on service
        List<String> usageTypes;
        if (serviceName.toLowerCase().contains("ec2") || serviceName.toLowerCase().contains("compute")) {
            usageTypes = Arrays.asList(
                    "BoxUsage:t3.medium",
                    "BoxUsage:t3.large",
                    "BoxUsage:m5.xlarge",
                    "EBS:VolumeUsage.gp3",
                    "DataTransfer-Out-Bytes"
            );
        } else if (serviceName.toLowerCase().contains("s3") || serviceName.toLowerCase().contains("storage")) {
            usageTypes = Arrays.asList(
                    "TimedStorage-ByteHrs",
                    "Requests-Tier1",
                    "Requests-Tier2",
                    "DataTransfer-Out-Bytes"
            );
        } else if (serviceName.toLowerCase().contains("rds") || serviceName.toLowerCase().contains("database")) {
            usageTypes = Arrays.asList(
                    "InstanceUsage:db.t3.medium",
                    "InstanceUsage:db.r5.large",
                    "Storage:gp2",
                    "BackupStorage"
            );
        } else {
            usageTypes = Arrays.asList(
                    "Usage-Type-1",
                    "Usage-Type-2",
                    "Usage-Type-3"
            );
        }

        double totalCost = 200 + (random.nextDouble() * 800);
        double remaining = totalCost;

        for (int i = 0; i < usageTypes.size() && remaining > 0; i++) {
            Map<String, Object> entry = new HashMap<>();
            String usageType = usageTypes.get(i);
            entry.put("name", usageType);
            entry.put("id", usageType);

            double costPercentage = (i == 0) ? 0.45 : (i == 1) ? 0.25 : 0.1 + (random.nextDouble() * 0.05);
            double cost = Math.min(totalCost * costPercentage, remaining);
            cost = Math.round(cost * 100.0) / 100.0;

            double quantity = (random.nextDouble() * 1000) + 100;
            quantity = Math.round(quantity * 100.0) / 100.0;

            entry.put("cost", cost);
            entry.put("quantity", quantity);
            entry.put("unit", getUnitForUsageType(usageType));

            results.add(entry);
            remaining -= cost;
        }

        return results;
    }

    /**
     * Get appropriate unit for usage type.
     */
    private String getUnitForUsageType(String usageType) {
        if (usageType.contains("BoxUsage") || usageType.contains("InstanceUsage")) {
            return "Hrs";
        } else if (usageType.contains("Storage") || usageType.contains("ByteHrs")) {
            return "GB-Mo";
        } else if (usageType.contains("Requests")) {
            return "Requests";
        } else if (usageType.contains("DataTransfer")) {
            return "GB";
        }
        return "Count";
    }
}
