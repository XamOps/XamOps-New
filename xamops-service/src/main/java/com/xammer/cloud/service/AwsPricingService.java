package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for retrieving AWS EC2 instance pricing information.
 * Uses real AWS pricing data for accurate cost calculations.
 */
@Service
public class AwsPricingService {
    private static final Logger logger = LoggerFactory.getLogger(AwsPricingService.class);

    // Real AWS EC2 On-Demand pricing for us-east-1 (as of 2024)
    // Prices are in USD per hour
    private static final Map<String, Double> US_EAST_1_PRICING = new HashMap<>();

    static {
        // T3 Family (Burstable)
        US_EAST_1_PRICING.put("t3.nano", 0.0052);
        US_EAST_1_PRICING.put("t3.micro", 0.0104);
        US_EAST_1_PRICING.put("t3.small", 0.0208);
        US_EAST_1_PRICING.put("t3.medium", 0.0416);
        US_EAST_1_PRICING.put("t3.large", 0.0832);
        US_EAST_1_PRICING.put("t3.xlarge", 0.1664);
        US_EAST_1_PRICING.put("t3.2xlarge", 0.3328);

        // T2 Family (Burstable)
        US_EAST_1_PRICING.put("t2.nano", 0.0058);
        US_EAST_1_PRICING.put("t2.micro", 0.0116);
        US_EAST_1_PRICING.put("t2.small", 0.023);
        US_EAST_1_PRICING.put("t2.medium", 0.0464);
        US_EAST_1_PRICING.put("t2.large", 0.0928);
        US_EAST_1_PRICING.put("t2.xlarge", 0.1856);
        US_EAST_1_PRICING.put("t2.2xlarge", 0.3712);

        // M5 Family (General Purpose)
        US_EAST_1_PRICING.put("m5.large", 0.096);
        US_EAST_1_PRICING.put("m5.xlarge", 0.192);
        US_EAST_1_PRICING.put("m5.2xlarge", 0.384);
        US_EAST_1_PRICING.put("m5.4xlarge", 0.768);
        US_EAST_1_PRICING.put("m5.8xlarge", 1.536);
        US_EAST_1_PRICING.put("m5.12xlarge", 2.304);
        US_EAST_1_PRICING.put("m5.16xlarge", 3.072);
        US_EAST_1_PRICING.put("m5.24xlarge", 4.608);

        // M6i Family (General Purpose - Latest Gen)
        US_EAST_1_PRICING.put("m6i.large", 0.096);
        US_EAST_1_PRICING.put("m6i.xlarge", 0.192);
        US_EAST_1_PRICING.put("m6i.2xlarge", 0.384);
        US_EAST_1_PRICING.put("m6i.4xlarge", 0.768);
        US_EAST_1_PRICING.put("m6i.8xlarge", 1.536);
        US_EAST_1_PRICING.put("m6i.12xlarge", 2.304);
        US_EAST_1_PRICING.put("m6i.16xlarge", 3.072);
        US_EAST_1_PRICING.put("m6i.24xlarge", 4.608);
        US_EAST_1_PRICING.put("m6i.32xlarge", 6.144);

        // C5 Family (Compute Optimized)
        US_EAST_1_PRICING.put("c5.large", 0.085);
        US_EAST_1_PRICING.put("c5.xlarge", 0.17);
        US_EAST_1_PRICING.put("c5.2xlarge", 0.34);
        US_EAST_1_PRICING.put("c5.4xlarge", 0.68);
        US_EAST_1_PRICING.put("c5.9xlarge", 1.53);
        US_EAST_1_PRICING.put("c5.12xlarge", 2.04);
        US_EAST_1_PRICING.put("c5.18xlarge", 3.06);
        US_EAST_1_PRICING.put("c5.24xlarge", 4.08);

        // R5 Family (Memory Optimized)
        US_EAST_1_PRICING.put("r5.large", 0.126);
        US_EAST_1_PRICING.put("r5.xlarge", 0.252);
        US_EAST_1_PRICING.put("r5.2xlarge", 0.504);
        US_EAST_1_PRICING.put("r5.4xlarge", 1.008);
        US_EAST_1_PRICING.put("r5.8xlarge", 2.016);
        US_EAST_1_PRICING.put("r5.12xlarge", 3.024);
        US_EAST_1_PRICING.put("r5.16xlarge", 4.032);
        US_EAST_1_PRICING.put("r5.24xlarge", 6.048);
    }

    // Regional pricing multipliers (relative to us-east-1)
    private static final Map<String, Double> REGIONAL_MULTIPLIERS = new HashMap<>();

    static {
        REGIONAL_MULTIPLIERS.put("us-east-1", 1.0);
        REGIONAL_MULTIPLIERS.put("us-east-2", 1.0);
        REGIONAL_MULTIPLIERS.put("us-west-1", 1.08);
        REGIONAL_MULTIPLIERS.put("us-west-2", 1.0);
        REGIONAL_MULTIPLIERS.put("ca-central-1", 1.05);
        REGIONAL_MULTIPLIERS.put("eu-west-1", 1.05);
        REGIONAL_MULTIPLIERS.put("eu-west-2", 1.08);
        REGIONAL_MULTIPLIERS.put("eu-west-3", 1.08);
        REGIONAL_MULTIPLIERS.put("eu-central-1", 1.08);
        REGIONAL_MULTIPLIERS.put("eu-north-1", 0.98);
        REGIONAL_MULTIPLIERS.put("ap-south-1", 1.03);
        REGIONAL_MULTIPLIERS.put("ap-southeast-1", 1.10);
        REGIONAL_MULTIPLIERS.put("ap-southeast-2", 1.12);
        REGIONAL_MULTIPLIERS.put("ap-northeast-1", 1.12);
        REGIONAL_MULTIPLIERS.put("ap-northeast-2", 1.10);
        REGIONAL_MULTIPLIERS.put("sa-east-1", 1.25);
    }

    /**
     * Get the hourly cost for an EC2 instance type in a specific region.
     * 
     * @param instanceType The EC2 instance type (e.g., "t3.medium")
     * @param region       The AWS region (e.g., "us-east-1")
     * @return The hourly cost in USD, or 0.0 if pricing is not available
     */
    public double getHourlyCost(String instanceType, String region) {
        if (instanceType == null || region == null) {
            logger.warn("Cannot calculate pricing: instanceType={}, region={}", instanceType, region);
            return 0.0;
        }

        // Get base price from us-east-1
        Double basePrice = US_EAST_1_PRICING.get(instanceType.toLowerCase());

        if (basePrice == null) {
            logger.warn("No pricing data available for instance type: {}", instanceType);
            // Return a conservative estimate based on instance size
            return estimatePricing(instanceType);
        }

        // Apply regional multiplier
        Double regionalMultiplier = REGIONAL_MULTIPLIERS.getOrDefault(region, 1.0);
        double finalPrice = basePrice * regionalMultiplier;

        logger.debug("Pricing for {} in {}: ${}/hour (base: ${}, multiplier: {})",
                instanceType, region, finalPrice, basePrice, regionalMultiplier);

        return finalPrice;
    }

    /**
     * Estimate pricing for unknown instance types based on naming patterns.
     */
    private double estimatePricing(String instanceType) {
        // Extract size from instance type (e.g., "xlarge" from "m5.xlarge")
        String lowerType = instanceType.toLowerCase();

        if (lowerType.contains("nano"))
            return 0.006;
        if (lowerType.contains("micro"))
            return 0.012;
        if (lowerType.contains("small"))
            return 0.024;
        if (lowerType.contains("medium"))
            return 0.048;
        if (lowerType.contains("24xlarge"))
            return 4.5;
        if (lowerType.contains("32xlarge"))
            return 6.0;
        if (lowerType.contains("16xlarge"))
            return 3.0;
        if (lowerType.contains("12xlarge"))
            return 2.3;
        if (lowerType.contains("8xlarge"))
            return 1.5;
        if (lowerType.contains("4xlarge"))
            return 0.75;
        if (lowerType.contains("2xlarge"))
            return 0.38;
        if (lowerType.contains("xlarge"))
            return 0.19;
        if (lowerType.contains("large"))
            return 0.095;

        logger.warn("Using fallback estimate for unknown instance type: {}", instanceType);
        return 0.10; // Default fallback
    }

    /**
     * Calculate monthly savings based on hours the instance will be stopped.
     * 
     * @param instanceType         The EC2 instance type
     * @param region               The AWS region
     * @param stoppedHoursPerMonth Number of hours the instance will be stopped per
     *                             month
     * @return The estimated monthly savings in USD
     */
    public double calculateMonthlySavings(String instanceType, String region, int stoppedHoursPerMonth) {
        double hourlyCost = getHourlyCost(instanceType, region);
        double savings = hourlyCost * stoppedHoursPerMonth;

        logger.debug("Monthly savings calculation: {} in {} - {} hours stopped = ${}/month",
                instanceType, region, stoppedHoursPerMonth, savings);

        return savings;
    }
}
