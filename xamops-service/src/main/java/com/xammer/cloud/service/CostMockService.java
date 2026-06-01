package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.*;
import com.xammer.cloud.dto.DashboardData.BudgetDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.rds.model.DBInstance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Mock service for AWS Cost Explorer and Budgets data.
 * Generates deterministic mock data based on account ID for consistent local
 * development experience.
 * This service reduces AWS API costs during development while providing
 * realistic test data.
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
            "Amazon Simple Notification Service",
            "AWS CloudTrail",
            "Amazon Virtual Private Cloud");

    // AWS regions for mock data
    private static final List<String> AWS_REGIONS = Arrays.asList(
            "us-east-1",
            "us-west-2",
            "eu-west-1",
            "ap-south-1",
            "ap-southeast-1",
            "eu-central-1");

    /**
     * Generates a deterministic seed from account ID for consistent mock data.
     */
    private long getSeedFromAccountId(String accountId) {
        return accountId != null ? accountId.hashCode() : System.currentTimeMillis();
    }

    // ========================
    // FinOpsService Mock Methods
    // ========================

    /**
     * Generate mock billing summary data (cost by service).
     */
    public List<DashboardData.BillingSummary> getMockBillingSummary(CloudAccount account, String startDate,
            String endDate) {
        logger.info("[MOCK] Generating mock billing summary for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<DashboardData.BillingSummary> results = new ArrayList<>();

        // Calculate number of days for realistic cost calculation
        int days = 30;
        if (startDate != null && endDate != null) {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            days = (int) java.time.temporal.ChronoUnit.DAYS.between(start, end);
        }

        double baseDailyCost = 50 + (random.nextDouble() * 150); // $50-$200 per day base

        for (String service : AWS_SERVICES) {
            // Different services have different cost weights
            double serviceWeight = getServiceWeight(service, random);
            double monthlyCost = baseDailyCost * days * serviceWeight;

            if (monthlyCost > 0.01) {
                results.add(new DashboardData.BillingSummary(
                        service,
                        Math.round(monthlyCost * 100.0) / 100.0));
            }
        }

        // Sort by cost descending
        results.sort((a, b) -> Double.compare(b.getMonthToDateCost(), a.getMonthToDateCost()));
        return results;
    }

    /**
     * Generate mock cost by region data.
     */
    public List<Map<String, Object>> getMockCostByRegion(CloudAccount account, String startDate, String endDate) {
        logger.info("[MOCK] Generating mock cost by region for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<Map<String, Object>> results = new ArrayList<>();

        double totalCost = 2000 + (random.nextDouble() * 5000);

        for (int i = 0; i < AWS_REGIONS.size(); i++) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("region", AWS_REGIONS.get(i));

            // Primary region gets more cost
            double costPercentage = (i == 0) ? 0.35
                    : (i == 1) ? 0.25 : (i == 2) ? 0.15 : 0.05 + (random.nextDouble() * 0.05);
            double cost = totalCost * costPercentage;

            if (cost > 0.01) {
                entry.put("cost", Math.round(cost * 100.0) / 100.0);
                results.add(entry);
            }
        }

        return results;
    }

    /**
     * Generate mock cost history data.
     */
    public DashboardData.CostHistory getMockCostHistory(CloudAccount account, String startDate, String endDate) {
        logger.info("[MOCK] Generating mock cost history for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        List<Boolean> anomalies = new ArrayList<>();

        if (startDate != null && endDate != null) {
            // Daily breakdown for custom date range
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            double baseDailyCost = 100 + (random.nextDouble() * 200);

            LocalDate current = start;
            while (!current.isAfter(end)) {
                labels.add(current.format(DateTimeFormatter.ofPattern("MMM dd")));
                double variation = 0.7 + (random.nextDouble() * 0.6);
                double cost = baseDailyCost * variation;
                costs.add(Math.round(cost * 100.0) / 100.0);
                anomalies.add(false);
                current = current.plusDays(1);
            }
        } else {
            // Monthly breakdown for last 6 months
            double baseMonthlyCost = 2500 + (random.nextDouble() * 2500);

            for (int i = 5; i >= 0; i--) {
                LocalDate month = LocalDate.now().minusMonths(i);
                labels.add(month.format(DateTimeFormatter.ofPattern("MMM uuuu")));

                double variation = 0.8 + (random.nextDouble() * 0.4);
                double cost = baseMonthlyCost * variation;
                costs.add(Math.round(cost * 100.0) / 100.0);

                // Occasional anomaly
                boolean isAnomaly = false;
                if (costs.size() > 1) {
                    double previousCost = costs.get(costs.size() - 2);
                    if (previousCost > 100) {
                        double changePercent = ((cost - previousCost) / previousCost) * 100;
                        if (changePercent > 25) {
                            isAnomaly = true;
                        }
                    }
                }
                anomalies.add(isAnomaly);
            }
        }

        return new DashboardData.CostHistory(labels, costs, anomalies);
    }

    /**
     * Generate mock cost anomalies.
     */
    public List<DashboardData.CostAnomaly> getMockCostAnomalies(CloudAccount account) {
        logger.info("[MOCK] Generating mock cost anomalies for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<DashboardData.CostAnomaly> anomalies = new ArrayList<>();

        // Generate 0-3 random anomalies
        int numAnomalies = random.nextInt(4);

        for (int i = 0; i < numAnomalies; i++) {
            String service = AWS_SERVICES.get(random.nextInt(AWS_SERVICES.size()));
            double unexpectedSpend = 50 + (random.nextDouble() * 500);
            LocalDate startDate = LocalDate.now().minusDays(random.nextInt(30) + 1);
            LocalDate endDate = startDate.plusDays(random.nextInt(5) + 1);

            anomalies.add(new DashboardData.CostAnomaly(
                    "anomaly-" + UUID.randomUUID().toString().substring(0, 8),
                    service,
                    Math.round(unexpectedSpend * 100.0) / 100.0,
                    startDate,
                    endDate.isAfter(LocalDate.now()) ? LocalDate.now() : endDate));
        }

        return anomalies;
    }

    /**
     * Generate mock budgets.
     */
    public List<BudgetDetails> getMockAccountBudgets(CloudAccount account) {
        logger.info("[MOCK] Generating mock budgets for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<BudgetDetails> budgets = new ArrayList<>();

        // Generate 2-4 budgets
        String[] budgetNames = { "Monthly Infrastructure Budget", "Development Budget", "Production Budget",
                "Data Analytics Budget" };
        int numBudgets = 2 + random.nextInt(3);

        for (int i = 0; i < numBudgets && i < budgetNames.length; i++) {
            double budgetLimit = 1000 + (random.nextDouble() * 9000);
            double actualSpend = budgetLimit * (0.3 + random.nextDouble() * 0.6);
            double forecastedSpend = actualSpend * (1.1 + random.nextDouble() * 0.3);

            budgets.add(new BudgetDetails(
                    budgetNames[i],
                    BigDecimal.valueOf(Math.round(budgetLimit * 100.0) / 100.0),
                    "USD",
                    BigDecimal.valueOf(Math.round(actualSpend * 100.0) / 100.0),
                    BigDecimal.valueOf(Math.round(forecastedSpend * 100.0) / 100.0)));
        }

        return budgets;
    }

    /**
     * Generate mock cost by tag data.
     */
    public List<Map<String, Object>> getMockCostByTag(String accountId, String tagKey) {
        logger.info("[MOCK] Generating mock cost by tag '{}' for account: {}", tagKey, accountId);

        Random random = new Random(getSeedFromAccountId(accountId) + tagKey.hashCode());
        List<Map<String, Object>> results = new ArrayList<>();

        // Common tag values based on tag key
        List<String> tagValues;
        if ("Environment".equalsIgnoreCase(tagKey) || "env".equalsIgnoreCase(tagKey)) {
            tagValues = Arrays.asList("production", "staging", "development", "Untagged");
        } else if ("Project".equalsIgnoreCase(tagKey)) {
            tagValues = Arrays.asList("project-alpha", "project-beta", "infrastructure", "Untagged");
        } else if ("cost-center".equalsIgnoreCase(tagKey)) {
            tagValues = Arrays.asList("engineering", "marketing", "operations", "Untagged");
        } else {
            tagValues = Arrays.asList("value-1", "value-2", "value-3", "Untagged");
        }

        double totalCost = 2000 + (random.nextDouble() * 3000);

        for (int i = 0; i < tagValues.size(); i++) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("tagValue", tagValues.get(i));

            double costPercentage = (i == 0) ? 0.4 : (i == 1) ? 0.25 : 0.15 + (random.nextDouble() * 0.1);
            double cost = totalCost * costPercentage;

            if (cost > 0.01) {
                entry.put("cost", Math.round(cost * 100.0) / 100.0);
                results.add(entry);
            }
        }

        return results;
    }

    // ========================
    // CostService Mock Methods
    // ========================

    /**
     * Generate mock month-to-date total cost.
     */
    public Double getMockTotalMonthToDateCost(String accountId) {
        logger.info("[MOCK] Generating mock MTD cost for account: {}", accountId);

        Random random = new Random(getSeedFromAccountId(accountId));
        int dayOfMonth = LocalDate.now().getDayOfMonth();
        double baseDailyCost = 100 + (random.nextDouble() * 200);

        return Math.round(baseDailyCost * dayOfMonth * 100.0) / 100.0;
    }

    /**
     * Generate mock last month spend.
     */
    public Double getMockLastMonthSpend(String accountId) {
        logger.info("[MOCK] Generating mock last month spend for account: {}", accountId);

        Random random = new Random(getSeedFromAccountId(accountId));
        int daysInLastMonth = YearMonth.now().minusMonths(1).lengthOfMonth();
        double baseDailyCost = 100 + (random.nextDouble() * 200);

        return Math.round(baseDailyCost * daysInLastMonth * 100.0) / 100.0;
    }

    /**
     * Generate mock cost breakdown by service and region.
     */
    public List<DetailedCostDto> getMockCostBreakdownByServiceAndRegion(String accountId, LocalDate startDate,
            LocalDate endDate) {
        logger.info("[MOCK] Generating mock detailed cost breakdown for account: {}", accountId);

        Random random = new Random(getSeedFromAccountId(accountId));
        List<DetailedCostDto> results = new ArrayList<>();

        for (String service : AWS_SERVICES) {
            for (int i = 0; i < 3; i++) { // Top 3 regions per service
                String region = AWS_REGIONS.get(i);
                double cost = (100 + random.nextDouble() * 500) * getServiceWeight(service, random);

                if (cost > 0.01) {
                    results.add(new DetailedCostDto(service, region, Math.round(cost * 100.0) / 100.0));
                }
            }
        }

        results.sort((a, b) -> Double.compare(b.getCost(), a.getCost()));
        return results;
    }

    /**
     * Generate mock cost breakdown by dimension.
     */
    public List<CostDto> getMockCostBreakdown(String accountId, String groupBy, String tag, String startDate,
            String endDate) {
        logger.info("[MOCK] Generating mock cost breakdown by '{}' for account: {}", groupBy, accountId);

        Random random = new Random(getSeedFromAccountId(accountId) + groupBy.hashCode());
        List<CostDto> results = new ArrayList<>();

        List<String> values;
        if ("SERVICE".equalsIgnoreCase(groupBy)) {
            values = AWS_SERVICES;
        } else if ("REGION".equalsIgnoreCase(groupBy)) {
            values = AWS_REGIONS;
        } else if ("TAG".equalsIgnoreCase(groupBy)) {
            values = Arrays.asList(tag + ":production", tag + ":staging", tag + ":development", "Untagged");
        } else {
            values = Arrays.asList("Value-1", "Value-2", "Value-3", "Value-4");
        }

        double totalCost = 3000 + (random.nextDouble() * 5000);

        for (int i = 0; i < values.size(); i++) {
            double costPercentage = (i == 0) ? 0.35
                    : (i == 1) ? 0.25 : (i == 2) ? 0.15 : 0.05 + (random.nextDouble() * 0.05);
            double cost = totalCost * costPercentage;

            if (cost > 0.01) {
                results.add(new CostDto(values.get(i), Math.round(cost * 100.0) / 100.0));
            }
        }

        results.sort((a, b) -> Double.compare(b.getAmount(), a.getAmount()));
        return results;
    }

    /**
     * Generate mock historical cost data.
     */
    public HistoricalCostDto getMockHistoricalCost(String accountId, String serviceName, String regionName, int days) {
        logger.info("[MOCK] Generating mock historical cost ({} days) for account: {}", days, accountId);

        long seed = getSeedFromAccountId(accountId);
        if (serviceName != null)
            seed += serviceName.hashCode();
        if (regionName != null)
            seed += regionName.hashCode();

        Random random = new Random(seed);
        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        double baseCost = 50 + (random.nextDouble() * 150);
        if (serviceName != null && !"ALL".equalsIgnoreCase(serviceName)) {
            baseCost *= getServiceWeight(serviceName, random);
        }

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            labels.add(current.format(DateTimeFormatter.ISO_LOCAL_DATE));
            double variation = 0.7 + (random.nextDouble() * 0.6);
            costs.add(Math.round(baseCost * variation * 100.0) / 100.0);
            current = current.plusDays(1);
        }

        return new HistoricalCostDto(labels, costs);
    }

    /**
     * Generate mock historical cost for dimension.
     */
    public HistoricalCostDto getMockHistoricalCostForDimension(String accountId, String groupBy, String dimensionValue,
            String tagKey) {
        logger.info("[MOCK] Generating mock historical cost for dimension '{}' for account: {}", dimensionValue,
                accountId);

        long seed = getSeedFromAccountId(accountId) + dimensionValue.hashCode();
        Random random = new Random(seed);

        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();

        double baseCost = 500 + (random.nextDouble() * 1500);

        for (int i = 5; i >= 0; i--) {
            LocalDate month = LocalDate.now().minusMonths(i);
            labels.add(month.format(DateTimeFormatter.ofPattern("MMM yyyy")));

            double variation = 0.8 + (random.nextDouble() * 0.4);
            costs.add(Math.round(baseCost * variation * 100.0) / 100.0);
        }

        return new HistoricalCostDto(labels, costs);
    }

    /**
     * Generate mock cost forecast.
     */
    public CostForecastDto getMockCostForecast(String accountId, String serviceName, int periods) {
        logger.info("[MOCK] Generating mock cost forecast ({} periods) for account: {}", periods, accountId);

        long seed = getSeedFromAccountId(accountId);
        if (serviceName != null)
            seed += serviceName.hashCode();

        Random random = new Random(seed);
        List<String> dates = new ArrayList<>();
        List<Double> predictions = new ArrayList<>();
        List<Double> lowerBounds = new ArrayList<>();
        List<Double> upperBounds = new ArrayList<>();

        double baseCost = 100 + (random.nextDouble() * 200);
        double trend = 0.02 + (random.nextDouble() * 0.05); // 2-7% growth trend

        LocalDate current = LocalDate.now().plusDays(1);
        for (int i = 0; i < periods; i++) {
            dates.add(current.format(DateTimeFormatter.ISO_LOCAL_DATE));

            double prediction = baseCost * (1 + trend * i) * (0.9 + random.nextDouble() * 0.2);
            prediction = Math.round(prediction * 100.0) / 100.0;

            double uncertainty = prediction * (0.1 + random.nextDouble() * 0.1);
            double lower = Math.round((prediction - uncertainty) * 100.0) / 100.0;
            double upper = Math.round((prediction + uncertainty) * 100.0) / 100.0;

            predictions.add(prediction);
            lowerBounds.add(lower);
            upperBounds.add(upper);

            current = current.plusDays(1);
        }

        return new CostForecastDto(dates, predictions, lowerBounds, upperBounds);
    }

    // ========================
    // Helper Methods
    // ========================

    /**
     * Get a weight factor for service cost distribution.
     */
    private double getServiceWeight(String service, Random random) {
        if (service.toLowerCase().contains("ec2") || service.toLowerCase().contains("compute")) {
            return 0.25 + (random.nextDouble() * 0.15);
        } else if (service.toLowerCase().contains("rds") || service.toLowerCase().contains("database")) {
            return 0.15 + (random.nextDouble() * 0.1);
        } else if (service.toLowerCase().contains("s3") || service.toLowerCase().contains("storage")) {
            return 0.1 + (random.nextDouble() * 0.1);
        } else if (service.toLowerCase().contains("lambda")) {
            return 0.05 + (random.nextDouble() * 0.05);
        } else {
            return 0.02 + (random.nextDouble() * 0.05);
        }
    }

    // ========================
    // ReservationService Mock Methods
    // ========================

    /**
     * Generate mock reservation analysis data.
     */
    public DashboardData.ReservationAnalysis getMockReservationAnalysis(CloudAccount account) {
        logger.info("[MOCK] Generating mock reservation analysis for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        double utilization = 60 + (random.nextDouble() * 35); // 60-95%
        double coverage = 40 + (random.nextDouble() * 45); // 40-85%

        return new DashboardData.ReservationAnalysis(
                Math.round(utilization * 10.0) / 10.0,
                Math.round(coverage * 10.0) / 10.0);
    }

    /**
     * Generate mock historical reservation data.
     */
    public HistoricalReservationDataDto getMockHistoricalReservationData(CloudAccount account) {
        logger.info("[MOCK] Generating mock historical reservation data for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<String> labels = new ArrayList<>();
        List<Double> utilPercentages = new ArrayList<>();
        List<Double> covPercentages = new ArrayList<>();

        LocalDate current = LocalDate.now().withDayOfMonth(1).minusMonths(5);
        double baseUtil = 65 + (random.nextDouble() * 20);
        double baseCov = 45 + (random.nextDouble() * 20);

        for (int i = 0; i < 6; i++) {
            labels.add(current.format(DateTimeFormatter.ofPattern("MMM yyyy")));

            double utilVariation = 0.9 + (random.nextDouble() * 0.2);
            double covVariation = 0.9 + (random.nextDouble() * 0.2);

            utilPercentages.add(Math.round(baseUtil * utilVariation * 10.0) / 10.0);
            covPercentages.add(Math.round(baseCov * covVariation * 10.0) / 10.0);

            current = current.plusMonths(1);
        }

        return new HistoricalReservationDataDto(labels, utilPercentages, covPercentages);
    }

    /**
     * Generate mock reservation purchase recommendations.
     */
    public List<DashboardData.ReservationPurchaseRecommendation> getMockReservationPurchaseRecommendations(
            CloudAccount account, String term, String paymentOption) {
        logger.info("[MOCK] Generating mock reservation purchase recommendations for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<DashboardData.ReservationPurchaseRecommendation> recommendations = new ArrayList<>();

        String[] instanceTypes = {"m5.large", "c5.xlarge", "r5.2xlarge", "t3.medium", "m5.xlarge"};
        String[] families = {"m5", "c5", "r5", "t3", "m5"};
        String[] platforms = {"Linux/UNIX", "Linux/UNIX", "Linux/UNIX", "Linux/UNIX (Amazon VPC)", "Red Hat Enterprise Linux"};

        int numRecs = 2 + random.nextInt(4); // 2-5 recommendations

        for (int i = 0; i < numRecs && i < instanceTypes.length; i++) {
            double monthlySavings = 50 + (random.nextDouble() * 500);
            double monthlyOnDemandCost = monthlySavings * (2 + random.nextDouble() * 2);
            double estimatedMonthlyCost = monthlyOnDemandCost - monthlySavings;
            int instanceCount = 1 + random.nextInt(5);

            recommendations.add(new DashboardData.ReservationPurchaseRecommendation(
                    families[i],
                    String.valueOf(instanceCount),
                    String.valueOf(instanceCount * 4), // Normalized units
                    String.valueOf(instanceCount),
                    String.format("%.2f", monthlySavings),
                    String.format("%.2f", monthlyOnDemandCost),
                    String.format("%.2f", estimatedMonthlyCost),
                    term,
                    instanceTypes[i],
                    AWS_REGIONS.get(random.nextInt(AWS_REGIONS.size())),
                    platforms[i],
                    "Shared",
                    "Current",
                    random.nextBoolean()));
        }

        return recommendations;
    }

    /**
     * Generate mock reservation inventory.
     */
    public List<ReservationInventoryDto> getMockReservationInventory(CloudAccount account) {
        logger.info("[MOCK] Generating mock reservation inventory for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<ReservationInventoryDto> inventory = new ArrayList<>();

        String[] instanceTypes = {"m5.large", "c5.xlarge", "r5.large", "t3.medium"};
        String[] offeringTypes = {"All Upfront", "Partial Upfront", "No Upfront", "Convertible"};
        String[] states = {"active", "active", "active", "retired"};

        int numRIs = 2 + random.nextInt(5); // 2-6 reservations

        for (int i = 0; i < numRIs && i < instanceTypes.length; i++) {
            LocalDate start = LocalDate.now().minusMonths(random.nextInt(12));
            LocalDate end = start.plusYears(1);
            int instanceCount = 1 + random.nextInt(5);
            double utilization = 50 + (random.nextDouble() * 50); // 50-100%

            inventory.add(new ReservationInventoryDto(
                    "ri-" + UUID.randomUUID().toString().substring(0, 8),
                    offeringTypes[i],
                    instanceTypes[i],
                    "Region",
                    AWS_REGIONS.get(i % AWS_REGIONS.size()) + (random.nextBoolean() ? "a" : "b"),
                    31536000L, // 1 year in seconds
                    java.time.Instant.from(start.atStartOfDay(java.time.ZoneId.systemDefault())),
                    java.time.Instant.from(end.atStartOfDay(java.time.ZoneId.systemDefault())),
                    instanceCount,
                    states[i],
                    Math.round(utilization * 10.0) / 10.0));
        }

        return inventory;
    }

    /**
     * Generate mock reservation modification recommendations.
     */
    public List<ReservationModificationRecommendationDto> getMockReservationModificationRecommendations(CloudAccount account) {
        logger.info("[MOCK] Generating mock reservation modification recommendations for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<ReservationModificationRecommendationDto> recommendations = new ArrayList<>();

        // Only generate 0-2 modification recommendations
        int numRecs = random.nextInt(3);

        String[][] instancePairs = {
                {"m5.xlarge", "m5.large"},
                {"c5.2xlarge", "c5.xlarge"},
                {"r5.xlarge", "r5.large"}
        };

        for (int i = 0; i < numRecs && i < instancePairs.length; i++) {
            double utilization = 40 + (random.nextDouble() * 35); // 40-75% (low utilization)
            double estimatedSavings = 30 + (random.nextDouble() * 70);

            recommendations.add(new ReservationModificationRecommendationDto(
                    "ri-" + UUID.randomUUID().toString().substring(0, 8),
                    instancePairs[i][0],
                    instancePairs[i][1],
                    String.format("Low Utilization (%.1f%%)", utilization),
                    Math.round(estimatedSavings * 100.0) / 100.0));
        }

        return recommendations;
    }

    /**
     * Generate mock savings plans.
     */
    public List<SavingsPlanDto> getMockSavingsPlans(CloudAccount account) {
        logger.info("[MOCK] Generating mock savings plans for account: {}", account.getAwsAccountId());

        Random random = new Random(getSeedFromAccountId(account.getAwsAccountId()));
        List<SavingsPlanDto> savingsPlans = new ArrayList<>();

        int numPlans = random.nextInt(4); // 0-3 savings plans

        String[] types = {"Compute Savings Plans", "EC2 Instance Savings Plans", "SageMaker Savings Plans"};
        String[] paymentOptions = {"All Upfront", "Partial Upfront", "No Upfront"};
        String[] states = {"active", "active", "queued", "payment-failed"};

        for (int i = 0; i < numPlans && i < types.length; i++) {
            LocalDate start = LocalDate.now().minusMonths(random.nextInt(12));
            LocalDate end = start.plusYears(1);
            double commitment = 0.5 + (random.nextDouble() * 2); // $0.5-$2.5 per hour
            double upfront = paymentOptions[i].equals("All Upfront") ? commitment * 8760 * 0.9 :
                    paymentOptions[i].equals("Partial Upfront") ? commitment * 8760 * 0.45 : 0;

            savingsPlans.add(new SavingsPlanDto(
                    "sp-" + UUID.randomUUID().toString().substring(0, 8),
                    "arn:aws:savingsplans::" + account.getAwsAccountId() + ":savingsplan/sp-" + i,
                    "Mock Savings Plan " + (i + 1),
                    states[i % states.length],
                    types[i],
                    paymentOptions[i],
                    start.atStartOfDay().toString(),
                    end.atStartOfDay().toString(),
                    AWS_REGIONS.get(random.nextInt(AWS_REGIONS.size())),
                    String.format("%.2f", commitment),
                    String.format("%.2f", upfront),
                    "USD",
                    null, null, null, null));
        }

        return savingsPlans;
    }

    /**
     * Generate mock reservation cost by tag.
     */
    public List<CostByTagDto> getMockReservationCostByTag(String accountId, String tagKey) {
        logger.info("[MOCK] Generating mock reservation cost by tag '{}' for account: {}", tagKey, accountId);

        Random random = new Random(getSeedFromAccountId(accountId) + tagKey.hashCode());
        List<CostByTagDto> results = new ArrayList<>();

        List<String> tagValues;
        if ("Environment".equalsIgnoreCase(tagKey) || "env".equalsIgnoreCase(tagKey)) {
            tagValues = Arrays.asList("production", "staging", "development", "Untagged");
        } else if ("Project".equalsIgnoreCase(tagKey)) {
            tagValues = Arrays.asList("project-alpha", "project-beta", "infrastructure", "Untagged");
        } else {
            tagValues = Arrays.asList("value-1", "value-2", "Untagged");
        }

        double totalCost = 500 + (random.nextDouble() * 1500);

        for (int i = 0; i < tagValues.size(); i++) {
            double costPercentage = (i == 0) ? 0.45 : (i == 1) ? 0.30 : 0.12 + (random.nextDouble() * 0.08);
            double cost = totalCost * costPercentage;

            if (cost > 0.01) {
                results.add(new CostByTagDto(tagValues.get(i), Math.round(cost * 100.0) / 100.0));
            }
        }

        return results;
    }

    // ========================
    // PricingService Mock Methods
    // ========================

    /**
     * Generate mock spot instance price.
     */
    public double getMockSpotInstancePrice(String instanceType, String region) {
        logger.info("[MOCK] Generating mock spot price for {} in {}", instanceType, region);

        Random random = new Random((instanceType + region).hashCode());
        double basePrice = getInstanceBasePrice(instanceType);

        // Spot prices are typically 60-90% cheaper than on-demand
        return Math.round(basePrice * (0.1 + random.nextDouble() * 0.3) * 10000.0) / 10000.0;
    }

    /**
     * Generate mock EBS GB/month price.
     */
    public double getMockEbsGbMonthPrice(String region, String volumeType) {
        logger.info("[MOCK] Generating mock EBS price for type {} in {}", volumeType, region);

        Map<String, Double> ebsPrices = new HashMap<>();
        ebsPrices.put("gp3", 0.08);
        ebsPrices.put("gp2", 0.10);
        ebsPrices.put("io1", 0.125);
        ebsPrices.put("io2", 0.125);
        ebsPrices.put("st1", 0.045);
        ebsPrices.put("sc1", 0.025);
        ebsPrices.put("standard", 0.05);

        return ebsPrices.getOrDefault(volumeType.toLowerCase(), 0.10);
    }

    /**
     * Generate mock EC2 instance monthly price.
     */
    public double getMockEc2InstanceMonthlyPrice(String instanceType, String region) {
        logger.info("[MOCK] Generating mock EC2 monthly price for {} in {}", instanceType, region);

        double hourlyPrice = getInstanceBasePrice(instanceType);
        return Math.round(hourlyPrice * 24 * 30 * 100.0) / 100.0;
    }

    /**
     * Generate mock Elastic IP monthly price.
     */
    public double getMockElasticIpMonthlyPrice(String region) {
        logger.info("[MOCK] Generating mock Elastic IP price for {}", region);

        // AWS charges $0.005/hour for unused Elastic IPs
        return 0.005 * 24 * 30; // ~$3.60/month
    }

    /**
     * Generate mock RDS instance monthly price.
     */
    public double getMockRdsInstanceMonthlyPrice(DBInstance dbInstance, String region) {
        String instanceClass = dbInstance.dbInstanceClass();
        logger.info("[MOCK] Generating mock RDS price for {} in {}", instanceClass, region);

        double hourlyPrice = getRdsInstanceBasePrice(instanceClass);
        return Math.round(hourlyPrice * 24 * 30 * 100.0) / 100.0;
    }

    /**
     * Generate mock RDS instance monthly price (overload for instanceClass string).
     */
    public double getMockRdsInstanceMonthlyPrice(String instanceClass, String region) {
        logger.info("[MOCK] Generating mock RDS price for {} in {}", instanceClass, region);

        double hourlyPrice = getRdsInstanceBasePrice(instanceClass);
        return Math.round(hourlyPrice * 24 * 30 * 100.0) / 100.0;
    }

    // ========================
    // AwsPricingService Mock Methods
    // ========================

    /**
     * Generate mock EC2 instance prices list.
     */
    public Map<String, Object> getMockEc2InstancePrices(String region, String operatingSystem) {
        logger.info("[MOCK] Generating mock EC2 instance prices for {} ({})", region, operatingSystem);

        Random random = new Random((region + operatingSystem).hashCode());
        List<Map<String, Object>> instances = new ArrayList<>();

        String[][] instanceData = {
                {"t3.micro", "2 vCPU, 1 GiB"},
                {"t3.small", "2 vCPU, 2 GiB"},
                {"t3.medium", "2 vCPU, 4 GiB"},
                {"t3.large", "2 vCPU, 8 GiB"},
                {"m5.large", "2 vCPU, 8 GiB"},
                {"m5.xlarge", "4 vCPU, 16 GiB"},
                {"m5.2xlarge", "8 vCPU, 32 GiB"},
                {"c5.large", "2 vCPU, 4 GiB"},
                {"c5.xlarge", "4 vCPU, 8 GiB"},
                {"r5.large", "2 vCPU, 16 GiB"},
                {"r5.xlarge", "4 vCPU, 32 GiB"}
        };

        for (String[] data : instanceData) {
            Map<String, Object> instance = new HashMap<>();
            instance.put("instanceType", data[0]);
            instance.put("description", data[0] + " - " + data[1]);
            instance.put("pricePerHour", getInstanceBasePrice(data[0]));
            instances.add(instance);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("instances", instances);
        result.put("source", "Mock Data");
        return result;
    }

    /**
     * Generate mock AWS regions.
     */
    public List<Map<String, Object>> getMockAwsRegions() {
        logger.info("[MOCK] Generating mock AWS regions");

        List<Map<String, Object>> regions = new ArrayList<>();

        String[][] regionData = {
                {"us-east-1", "US East (N. Virginia)"},
                {"us-east-2", "US East (Ohio)"},
                {"us-west-1", "US West (N. California)"},
                {"us-west-2", "US West (Oregon)"},
                {"ap-south-1", "Asia Pacific (Mumbai)"},
                {"ap-southeast-1", "Asia Pacific (Singapore)"},
                {"ap-southeast-2", "Asia Pacific (Sydney)"},
                {"ap-northeast-1", "Asia Pacific (Tokyo)"},
                {"eu-central-1", "EU (Frankfurt)"},
                {"eu-west-1", "EU (Ireland)"},
                {"eu-west-2", "EU (London)"}
        };

        for (String[] data : regionData) {
            Map<String, Object> region = new HashMap<>();
            region.put("regionCode", data[0]);
            region.put("regionName", data[1]);
            regions.add(region);
        }

        return regions;
    }

    /**
     * Generate mock AWS services list.
     */
    public List<Map<String, Object>> getMockAllAwsServices() {
        logger.info("[MOCK] Generating mock AWS services list");

        List<Map<String, Object>> services = new ArrayList<>();

        String[][] serviceData = {
                {"AmazonEC2", "Amazon Elastic Compute Cloud"},
                {"AmazonS3", "Amazon Simple Storage Service"},
                {"AmazonRDS", "Amazon Relational Database Service"},
                {"AWSLambda", "AWS Lambda"},
                {"AmazonDynamoDB", "Amazon DynamoDB"},
                {"AmazonCloudWatch", "Amazon CloudWatch"},
                {"AmazonVPC", "Amazon Virtual Private Cloud"},
                {"AmazonRoute53", "Amazon Route 53"},
                {"AWSELB", "Elastic Load Balancing"},
                {"AmazonSNS", "Amazon Simple Notification Service"}
        };

        for (String[] data : serviceData) {
            Map<String, Object> service = new HashMap<>();
            service.put("serviceCode", data[0]);
            service.put("serviceName", data[1]);
            services.add(service);
        }

        return services;
    }

    /**
     * Generate mock comprehensive EC2 estimate.
     */
    public Map<String, Object> getMockComprehensiveEc2Estimate(String region, String instanceType,
            String operatingSystem, int instanceCount, int hoursPerMonth, int storageGB,
            String storageType, int dataTransferOutGB) {
        logger.info("[MOCK] Generating mock comprehensive EC2 estimate for {} x{}", instanceType, instanceCount);

        double hourlyRate = getInstanceBasePrice(instanceType);
        double computeMonthly = hourlyRate * instanceCount * hoursPerMonth;
        double storageMonthly = storageGB * getMockEbsGbMonthPrice(region, storageType) * instanceCount;
        double transferMonthly = dataTransferOutGB * 0.09;

        Map<String, Object> result = new HashMap<>();
        result.put("totalMonthly", Math.round((computeMonthly + storageMonthly + transferMonthly) * 100.0) / 100.0);

        Map<String, Object> compute = new HashMap<>();
        compute.put("instanceType", instanceType);
        compute.put("monthlyCost", Math.round(computeMonthly * 100.0) / 100.0);
        compute.put("details", instanceType + " x" + instanceCount);
        result.put("compute", compute);

        Map<String, Object> storage = new HashMap<>();
        storage.put("gb", (double) storageGB);
        storage.put("monthlyCost", Math.round(storageMonthly * 100.0) / 100.0);
        result.put("storage", storage);

        // Pricing models
        List<Map<String, Object>> pricingModels = new ArrayList<>();
        Map<String, Object> onDemand = new HashMap<>();
        onDemand.put("name", "On-Demand");
        onDemand.put("monthlyTotal", Math.round((computeMonthly + storageMonthly + transferMonthly) * 100.0) / 100.0);
        onDemand.put("savings", "0%");
        pricingModels.add(onDemand);

        Map<String, Object> reserved1yr = new HashMap<>();
        reserved1yr.put("name", "1-Year Reserved");
        reserved1yr.put("monthlyTotal", Math.round((computeMonthly * 0.6 + storageMonthly + transferMonthly) * 100.0) / 100.0);
        reserved1yr.put("savings", "40%");
        pricingModels.add(reserved1yr);

        Map<String, Object> reserved3yr = new HashMap<>();
        reserved3yr.put("name", "3-Year Reserved");
        reserved3yr.put("monthlyTotal", Math.round((computeMonthly * 0.4 + storageMonthly + transferMonthly) * 100.0) / 100.0);
        reserved3yr.put("savings", "60%");
        pricingModels.add(reserved3yr);

        result.put("pricingModels", pricingModels);
        return result;
    }

    /**
     * Generate mock comprehensive S3 estimate.
     */
    public Map<String, Object> getMockComprehensiveS3Estimate(String region, String storageClass,
            double storageGB, long requestsPerMonth, double dataTransferGB) {
        logger.info("[MOCK] Generating mock comprehensive S3 estimate for {} GB", storageGB);

        Map<String, Double> storageRates = new HashMap<>();
        storageRates.put("STANDARD", 0.023);
        storageRates.put("STANDARD_IA", 0.0125);
        storageRates.put("ONEZONE_IA", 0.01);
        storageRates.put("GLACIER", 0.004);
        storageRates.put("GLACIER_DEEP_ARCHIVE", 0.00099);

        double storageRate = storageRates.getOrDefault(storageClass, 0.023);
        double storageCost = storageGB * storageRate;
        double requestCost = (requestsPerMonth / 1000.0) * 0.005;
        double transferCost = dataTransferGB * 0.09;

        Map<String, Object> result = new HashMap<>();
        result.put("totalMonthly", Math.round((storageCost + requestCost + transferCost) * 100.0) / 100.0);
        result.put("storageCost", Math.round(storageCost * 100.0) / 100.0);
        result.put("requestCost", Math.round(requestCost * 100.0) / 100.0);
        result.put("transferCost", Math.round(transferCost * 100.0) / 100.0);
        result.put("storageClass", storageClass);
        result.put("pricePerGB", storageRate);

        return result;
    }

    /**
     * Generate mock comprehensive VPC estimate.
     */
    public Map<String, Object> getMockComprehensiveVpcEstimate(String region, boolean hasNatGateway,
            double dataProcessedGB, int vpnConnections) {
        logger.info("[MOCK] Generating mock comprehensive VPC estimate");

        double natGatewayCost = hasNatGateway ? (0.045 * 730 + dataProcessedGB * 0.045) : 0;
        double vpnCost = vpnConnections * 0.05 * 730;

        Map<String, Object> result = new HashMap<>();
        result.put("totalMonthly", Math.round((natGatewayCost + vpnCost) * 100.0) / 100.0);
        result.put("natGatewayCost", Math.round(natGatewayCost * 100.0) / 100.0);
        result.put("vpnCost", Math.round(vpnCost * 100.0) / 100.0);
        result.put("dataProcessingCost", Math.round(dataProcessedGB * 0.045 * 100.0) / 100.0);

        return result;
    }

    /**
     * Generate mock comprehensive ELB estimate.
     */
    public Map<String, Object> getMockComprehensiveElbEstimate(String region, String elbType,
            int hoursPerMonth, double dataProcessedGB, int newConnectionsPerSecond) {
        logger.info("[MOCK] Generating mock comprehensive ELB estimate for {}", elbType);

        Map<String, Double> elbRates = new HashMap<>();
        elbRates.put("application", 0.0225);
        elbRates.put("network", 0.0225);
        elbRates.put("classic", 0.025);

        double hourlyRate = elbRates.getOrDefault(elbType.toLowerCase(), 0.0225);
        double hourlyCost = hourlyRate * hoursPerMonth;
        double lcuCost = dataProcessedGB * 0.008; // Simplified LCU calculation

        Map<String, Object> result = new HashMap<>();
        result.put("totalMonthly", Math.round((hourlyCost + lcuCost) * 100.0) / 100.0);
        result.put("hourlyCost", Math.round(hourlyCost * 100.0) / 100.0);
        result.put("lcuCost", Math.round(lcuCost * 100.0) / 100.0);
        result.put("elbType", elbType);

        return result;
    }

    // ========================
    // Pricing Helper Methods
    // ========================

    /**
     * Get base hourly price for EC2 instance type.
     */
    private double getInstanceBasePrice(String instanceType) {
        Map<String, Double> prices = new HashMap<>();
        prices.put("t3.micro", 0.0104);
        prices.put("t3.small", 0.0208);
        prices.put("t3.medium", 0.0416);
        prices.put("t3.large", 0.0832);
        prices.put("t3.xlarge", 0.1664);
        prices.put("m5.large", 0.096);
        prices.put("m5.xlarge", 0.192);
        prices.put("m5.2xlarge", 0.384);
        prices.put("m5.4xlarge", 0.768);
        prices.put("c5.large", 0.085);
        prices.put("c5.xlarge", 0.17);
        prices.put("c5.2xlarge", 0.34);
        prices.put("r5.large", 0.126);
        prices.put("r5.xlarge", 0.252);
        prices.put("r5.2xlarge", 0.504);

        return prices.getOrDefault(instanceType, 0.10);
    }

    /**
     * Get base hourly price for RDS instance class.
     */
    private double getRdsInstanceBasePrice(String instanceClass) {
        Map<String, Double> prices = new HashMap<>();
        prices.put("db.t3.micro", 0.017);
        prices.put("db.t3.small", 0.034);
        prices.put("db.t3.medium", 0.068);
        prices.put("db.t3.large", 0.136);
        prices.put("db.m5.large", 0.171);
        prices.put("db.m5.xlarge", 0.342);
        prices.put("db.m5.2xlarge", 0.684);
        prices.put("db.r5.large", 0.24);
        prices.put("db.r5.xlarge", 0.48);

        return prices.getOrDefault(instanceClass, 0.10);
    }

    // ========================
    // Write Operation Mock Methods
    // ========================

    /**
     * Mock reservation modification (write operation)
     */
    public String getMockApplyReservationModification(String reservationId, String targetInstanceType, int instanceCount) {
        logger.info("[MOCK] Simulating reservation modification for RI: {} -> {} x{}",
            reservationId, targetInstanceType, instanceCount);
        return "mock-modification-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Mock budget creation (write operation)
     */
    public String getMockCreateBudget(String budgetName, double budgetAmount) {
        logger.info("[MOCK] Simulating budget creation: {} with amount ${}", budgetName, budgetAmount);
        return "Budget '" + budgetName + "' created successfully (MOCK)";
    }

    /**
     * Mock budget deletion (write operation)
     */
    public String getMockDeleteBudget(String budgetName) {
        logger.info("[MOCK] Simulating budget deletion: {}", budgetName);
        return "Budget '" + budgetName + "' deleted successfully (MOCK)";
    }

    /**
     * Mock single RI utilization fetch (performance optimization)
     */
    public Double getMockSingleRIUtilization(String riId) {
        // Use riId hashcode for deterministic mock data
        Random random = new Random(riId.hashCode());
        double utilization = 60.0 + random.nextDouble() * 35.0; // 60-95%
        logger.debug("[MOCK] Generating mock utilization for RI {}: {}%", riId, String.format("%.2f", utilization));
        return utilization;
    }
}
