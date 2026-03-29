package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.calculator.aws.AwsServiceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.DescribeServicesRequest;
import software.amazon.awssdk.services.pricing.model.DescribeServicesResponse;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;
import software.amazon.awssdk.services.pricing.model.PricingException;

import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for retrieving AWS EC2 instance pricing information.
 * Merges manual static pricing (for fast calculator) and real AWS API pricing.
 */
@Service
public class AwsPricingService {

    private static final Logger logger = LoggerFactory.getLogger(AwsPricingService.class);

    // --- TEAM DEPENDENCIES (Required for API Calls) ---
    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final ObjectMapper objectMapper;
    private final CostMockService costMockService;

    @Value("${aws.costexplorer.mock:false}")
    private boolean useMockData;

    // --- YOUR STATIC DATA (Required for Calculator) ---
    // Real AWS EC2 On-Demand pricing for us-east-1 (as of 2024)
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

    // RDS MySQL/PostgreSQL on-demand, single-AZ pricing for us-east-1 (2024)
    private static final Map<String, Double> RDS_US_EAST_1_PRICING = new HashMap<>();

    static {
        RDS_US_EAST_1_PRICING.put("db.t3.micro",    0.017);
        RDS_US_EAST_1_PRICING.put("db.t3.small",    0.034);
        RDS_US_EAST_1_PRICING.put("db.t3.medium",   0.068);
        RDS_US_EAST_1_PRICING.put("db.t3.large",    0.136);
        RDS_US_EAST_1_PRICING.put("db.t3.xlarge",   0.272);
        RDS_US_EAST_1_PRICING.put("db.t3.2xlarge",  0.544);
        RDS_US_EAST_1_PRICING.put("db.t4g.micro",   0.016);
        RDS_US_EAST_1_PRICING.put("db.t4g.small",   0.032);
        RDS_US_EAST_1_PRICING.put("db.t4g.medium",  0.065);
        RDS_US_EAST_1_PRICING.put("db.t4g.large",   0.130);
        RDS_US_EAST_1_PRICING.put("db.m5.large",    0.171);
        RDS_US_EAST_1_PRICING.put("db.m5.xlarge",   0.342);
        RDS_US_EAST_1_PRICING.put("db.m5.2xlarge",  0.684);
        RDS_US_EAST_1_PRICING.put("db.m5.4xlarge",  1.368);
        RDS_US_EAST_1_PRICING.put("db.m5.8xlarge",  2.736);
        RDS_US_EAST_1_PRICING.put("db.m6g.large",   0.162);
        RDS_US_EAST_1_PRICING.put("db.m6g.xlarge",  0.325);
        RDS_US_EAST_1_PRICING.put("db.m6g.2xlarge", 0.650);
        RDS_US_EAST_1_PRICING.put("db.m6g.4xlarge", 1.300);
        RDS_US_EAST_1_PRICING.put("db.r5.large",    0.240);
        RDS_US_EAST_1_PRICING.put("db.r5.xlarge",   0.480);
        RDS_US_EAST_1_PRICING.put("db.r5.2xlarge",  0.960);
        RDS_US_EAST_1_PRICING.put("db.r5.4xlarge",  1.920);
        RDS_US_EAST_1_PRICING.put("db.r5.8xlarge",  3.840);
        RDS_US_EAST_1_PRICING.put("db.r6g.large",   0.228);
        RDS_US_EAST_1_PRICING.put("db.r6g.xlarge",  0.456);
        RDS_US_EAST_1_PRICING.put("db.r6g.2xlarge", 0.912);
        RDS_US_EAST_1_PRICING.put("db.r6g.4xlarge", 1.824);
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

    // --- CONSTRUCTOR (Team's structure required for Injection) ---
    public AwsPricingService(AwsClientProvider awsClientProvider,
                             CloudAccountRepository cloudAccountRepository,
                             ObjectMapper objectMapper,
                             CostMockService costMockService) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.objectMapper = objectMapper;
        this.costMockService = costMockService;
    }

    // ==========================================
    //       SECTION 1: YOUR CALCULATOR CODE
    // ==========================================

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
     * Get the hourly cost for an RDS DB instance class in a specific region.
     * Uses MySQL/PostgreSQL single-AZ on-demand pricing as the baseline.
     *
     * @param dbInstanceClass The RDS DB instance class (e.g., "db.t3.medium")
     * @param region          The AWS region (e.g., "us-east-1")
     * @return The hourly cost in USD, or 0.0 if pricing is not available
     */
    public double getRdsHourlyCost(String dbInstanceClass, String region) {
        if (dbInstanceClass == null || region == null) {
            logger.warn("Cannot calculate RDS pricing: dbInstanceClass={}, region={}", dbInstanceClass, region);
            return 0.0;
        }
        Double basePrice = RDS_US_EAST_1_PRICING.get(dbInstanceClass.toLowerCase());
        if (basePrice == null) {
            logger.warn("No RDS pricing data for instance class: {} — using size-based estimate", dbInstanceClass);
            return estimateRdsPricing(dbInstanceClass);
        }
        double multiplier = REGIONAL_MULTIPLIERS.getOrDefault(region, 1.0);
        return basePrice * multiplier;
    }

    private double estimateRdsPricing(String dbInstanceClass) {
        String lower = dbInstanceClass.toLowerCase();
        if (lower.contains("micro"))   return 0.02;
        if (lower.contains("small"))   return 0.04;
        if (lower.contains("medium"))  return 0.08;
        if (lower.contains("8xlarge")) return 2.80;
        if (lower.contains("4xlarge")) return 1.40;
        if (lower.contains("2xlarge")) return 0.70;
        if (lower.contains("xlarge"))  return 0.35;
        if (lower.contains("large"))   return 0.18;
        return 0.20;
    }

    // EBS storage pricing per GB-month (us-east-1, 2024)
    private static final Map<String, Double> EBS_PRICING_PER_GB = Map.of(
            "gp3",      0.08,
            "gp2",      0.10,
            "io1",      0.125,
            "io2",      0.125,
            "st1",      0.045,
            "sc1",      0.015,
            "standard", 0.05
    );

    /**
     * Estimates the cost for an EBS volume over a given number of days.
     * Formula: pricePerGbMonth × sizeGb × (days / 30.0) × regionalMultiplier
     */
    public double getEbsCostForPeriod(String volumeType, int sizeGb, String region, long days) {
        if (volumeType == null || sizeGb <= 0 || region == null || days <= 0) return 0.0;
        double pricePerGb = EBS_PRICING_PER_GB.getOrDefault(volumeType.toLowerCase(), 0.08);
        double multiplier = REGIONAL_MULTIPLIERS.getOrDefault(region, 1.0);
        return pricePerGb * sizeGb * (days / 30.0) * multiplier;
    }

    /**
     * Returns the base hourly rate for a load balancer by type.
     * Does not include LCU/NLCU because that requires live traffic metrics.
     */
    public double getElbHourlyRate(String lbType) {
        if (lbType == null) return 0.0225;
        switch (lbType.toLowerCase()) {
            case "gateway": return 0.0125;
            case "classic": return 0.025;
            default:        return 0.0225; // application / network
        }
    }

    /**
     * Calculate monthly savings based on hours the instance will be stopped.
     *
     * @param instanceType         The EC2 instance type
     * @param region               The AWS region
     * @param stoppedHoursPerMonth Number of hours the instance will be stopped per
     * month
     * @return The estimated monthly savings in USD
     */
    public double calculateMonthlySavings(String instanceType, String region, int stoppedHoursPerMonth) {
        double hourlyCost = getHourlyCost(instanceType, region);
        double savings = hourlyCost * stoppedHoursPerMonth;

        logger.debug("Monthly savings calculation: {} in {} - {} hours stopped = ${}/month",
                instanceType, region, stoppedHoursPerMonth, savings);

        return savings;
    }

    // ==========================================
    //       SECTION 2: TEAM'S API CODE
    // ==========================================

    /**
     * Get all AWS regions dynamically from AWS API
     */
    public Map<String, Object> getAwsRegions(String awsAccountId) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getAwsRegions");
            List<Map<String, Object>> regions = costMockService.getMockAwsRegions();
            Map<String, Object> result = new HashMap<>();
            result.put("regions", regions);
            result.put("count", regions.size());
            return result;
        }

        logger.info("Fetching AWS regions for account: {}", awsAccountId);

        CloudAccount account = resolveAwsAccount(awsAccountId);
        if (account == null) {
            logger.warn("Account not found: {}, returning fallback regions", awsAccountId);
            return getFallbackRegions();
        }

        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, "us-east-1");

            DescribeRegionsRequest request = DescribeRegionsRequest.builder()
                    .allRegions(true)
                    .build();

            DescribeRegionsResponse response = ec2.describeRegions(request);

            List<Map<String, String>> regions = response.regions().stream()
                    .map(region -> {
                        Map<String, String> regionMap = new HashMap<>();
                        regionMap.put("code", region.regionName());
                        regionMap.put("name", getRegionDisplayName(region.regionName()));
                        return regionMap;
                    })
                    .sorted(Comparator.comparing(a -> a.get("code")))
                    .collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("regions", regions);
            result.put("count", regions.size());

            logger.info("Found {} AWS regions for account {}", regions.size(), awsAccountId);
            return result;

        } catch (Exception e) {
            logger.error("Failed to fetch AWS regions for account: {}", awsAccountId, e);
            return getFallbackRegions();
        }
    }

    private String getRegionDisplayName(String regionCode) {
        Map<String, String> regionNames = new HashMap<>();
        regionNames.put("us-east-1", "US East (N. Virginia)");
        regionNames.put("us-east-2", "US East (Ohio)");
        regionNames.put("us-west-1", "US West (N. California)");
        regionNames.put("us-west-2", "US West (Oregon)");
        regionNames.put("eu-west-1", "EU (Ireland)");
        regionNames.put("eu-west-2", "EU (London)");
        regionNames.put("eu-west-3", "EU (Paris)");
        regionNames.put("eu-central-1", "EU (Frankfurt)");
        regionNames.put("eu-central-2", "EU (Zurich)");
        regionNames.put("eu-north-1", "EU (Stockholm)");
        regionNames.put("eu-south-1", "EU (Milan)");
        regionNames.put("eu-south-2", "EU (Spain)");
        regionNames.put("ap-south-1", "Asia Pacific (Mumbai)");
        regionNames.put("ap-south-2", "Asia Pacific (Hyderabad)");
        regionNames.put("ap-southeast-1", "Asia Pacific (Singapore)");
        regionNames.put("ap-southeast-2", "Asia Pacific (Sydney)");
        regionNames.put("ap-southeast-3", "Asia Pacific (Jakarta)");
        regionNames.put("ap-southeast-4", "Asia Pacific (Melbourne)");
        regionNames.put("ap-northeast-1", "Asia Pacific (Tokyo)");
        regionNames.put("ap-northeast-2", "Asia Pacific (Seoul)");
        regionNames.put("ap-northeast-3", "Asia Pacific (Osaka)");
        regionNames.put("ap-east-1", "Asia Pacific (Hong Kong)");
        regionNames.put("ca-central-1", "Canada (Central)");
        regionNames.put("ca-west-1", "Canada (Calgary)");
        regionNames.put("sa-east-1", "South America (São Paulo)");
        regionNames.put("me-south-1", "Middle East (Bahrain)");
        regionNames.put("me-central-1", "Middle East (UAE)");
        regionNames.put("af-south-1", "Africa (Cape Town)");
        regionNames.put("il-central-1", "Israel (Tel Aviv)");

        return regionNames.getOrDefault(regionCode, regionCode);
    }

    private Map<String, Object> getFallbackRegions() {
        List<Map<String, String>> fallback = new ArrayList<>();
        String[] regions = {
                "us-east-1", "us-east-2", "us-west-1", "us-west-2",
                "eu-west-1", "eu-central-1", "ap-south-1", "ap-southeast-1"
        };

        for (String region : regions) {
            Map<String, String> regionMap = new HashMap<>();
            regionMap.put("code", region);
            regionMap.put("name", getRegionDisplayName(region));
            fallback.add(regionMap);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("regions", fallback);
        result.put("count", fallback.size());
        return result;
    }

    // Inside src/main/java/com/xammer/cloud/service/AwsPricingService.java

public Map<String, Object> getAllAwsServices(AwsServiceRegistry registry) {
    // Use mock data if enabled
    if (useMockData) {
        logger.info("[MOCK] Using mock data for getAllAwsServices");
        List<Map<String, Object>> services = costMockService.getMockAllAwsServices();
        return Map.of("services", services);
    }

    // Get the list of service codes you have actually defined in your registry
    List<String> definedCodes = registry.getServicesAsList().stream()
            .map(s -> (String) s.get("code"))
            .collect(Collectors.toList());

    PricingClient pricingClient = awsClientProvider.getPricingClient();
    try {
        DescribeServicesResponse response = pricingClient.describeServices(
                DescribeServicesRequest.builder().maxResults(100).build());
        
        List<Map<String, String>> services = response.services().stream()
                // ONLY keep services that are in your Registry
                .filter(s -> definedCodes.contains(s.serviceCode())) 
                .map(s -> {
                    Map<String, String> svc = new HashMap<>();
                    String code = s.serviceCode();
                    svc.put("code", code);
                    
                    // Get the clean name from your registry instead of AWS discovery
                    String cleanName = registry.getServiceConfig(code).getServiceName();
                    svc.put("name", cleanName);
                    return svc;
                }).collect(Collectors.toList());
        
        return Map.of("services", services);
    } catch (Exception e) {
        // Fallback: Just return your registry list if AWS discovery fails
        return Map.of("services", registry.getServicesAsList());
    }
}

    private String deriveFriendlyName(String serviceCode) {
        Map<String, String> names = new HashMap<>();
        // Mappings to ensure your ConfigService keys match official codes
        names.put("AmazonEC2", "Amazon EC2 (Compute)");
        names.put("AmazonS3", "Amazon S3 (Simple Storage Service)");
        names.put("AmazonRDS", "Amazon RDS (Relational Database Service)");
        names.put("AWSLambda", "AWS Lambda (Serverless)");
        names.put("AmazonEKS", "Amazon EKS (Kubernetes)");
        names.put("AWSELB", "Elastic Load Balancing");
        names.put("AmazonVPC", "Amazon Virtual Private Cloud (VPC)");

        return names.getOrDefault(serviceCode, serviceCode);
    }

    private Map<String, Object> getFallbackServicesMap() {
        List<Map<String, String>> fallback = new ArrayList<>();
        Map<String, String> codeToName = new HashMap<>();
        codeToName.put("AmazonEC2", "Amazon Elastic Compute Cloud (EC2)");
        codeToName.put("AmazonS3", "Amazon Simple Storage Service (S3)");
        codeToName.put("AmazonRDS", "Amazon Relational Database Service (RDS)");
        codeToName.put("AmazonDynamoDB", "Amazon DynamoDB");
        codeToName.put("AWSLambda", "AWS Lambda");
        codeToName.put("AmazonCloudFront", "Amazon CloudFront");
        codeToName.put("AmazonECS", "Amazon Elastic Container Service");
        codeToName.put("AmazonEKS", "Amazon Elastic Kubernetes Service");

        codeToName.forEach((code, name) -> {
            Map<String, String> service = new HashMap<>();
            service.put("code", code);
            service.put("name", name);
            fallback.add(service);
        });

        Map<String, Object> result = new HashMap<>();
        result.put("services", fallback);
        result.put("count", fallback.size());
        return result;
    }

    /**
     * ✨ NEW: Get comprehensive VPC pricing estimate
     */
    public Map<String, Object> getComprehensiveVpcEstimate(
            String awsAccountId,
            String region,
            int siteToSiteVpnConnections,
            int siteToSiteVpnHoursPerDay,
            int clientVpnSubnets,
            int clientVpnActiveConnections,
            int clientVpnHoursPerDay,
            int clientVpnWorkingDaysPerMonth,
            int natGatewayCount,
            int natGatewayDataProcessedGB,
            int interfaceEndpoints,
            int gatewayEndpoints) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getComprehensiveVpcEstimate");
            return costMockService.getMockComprehensiveVpcEstimate(region, natGatewayCount > 0,
                    natGatewayDataProcessedGB, siteToSiteVpnConnections);
        }

        logger.info("Calculating VPC estimate for region: {}", region);

        // ✅ Fetch prices from AWS Pricing API based on region
        double siteToSiteVpnHourlyRate = 0.05;
        double clientVpnEndpointHourlyRate = 0.10;
        double clientVpnConnectionHourlyRate = 0.05;
        double natGatewayHourlyRate = 0.045;
        double natGatewayDataRate = 0.045;
        double interfaceEndpointHourlyRate = 0.01;

        // TODO: Integrate AWS Pricing API to fetch region-specific prices
        // For now using defaults (will be implemented in next phase)

        // 1. Site-to-Site VPN Pricing
        double siteToSiteVpnHoursPerMonth = siteToSiteVpnHoursPerDay * 30;
        double siteToSiteVpnMonthlyCost = siteToSiteVpnConnections * siteToSiteVpnHourlyRate * siteToSiteVpnHoursPerMonth;

        // 2. Client VPN Pricing
        double clientVpnEndpointHours = clientVpnSubnets * clientVpnHoursPerDay * clientVpnWorkingDaysPerMonth;
        double clientVpnEndpointCost = clientVpnEndpointHours * clientVpnEndpointHourlyRate;

        double clientVpnConnectionHours = clientVpnActiveConnections * clientVpnHoursPerDay * clientVpnWorkingDaysPerMonth;
        double clientVpnConnectionCost = clientVpnConnectionHours * clientVpnConnectionHourlyRate;

        double clientVpnTotalCost = clientVpnEndpointCost + clientVpnConnectionCost;

        // 3. NAT Gateway Pricing
        double natGatewayHoursCost = natGatewayCount * natGatewayHourlyRate * 730;
        double natGatewayDataCost = natGatewayDataProcessedGB * natGatewayDataRate;
        double natGatewayTotalCost = natGatewayHoursCost + natGatewayDataCost;

        // 4. VPC Endpoints Pricing
        double interfaceEndpointMonthlyCost = interfaceEndpoints * interfaceEndpointHourlyRate * 730;
        double gatewayEndpointCost = 0.0;
        double endpointsTotalCost = interfaceEndpointMonthlyCost + gatewayEndpointCost;

        // Total monthly cost
        double totalMonthly = siteToSiteVpnMonthlyCost + clientVpnTotalCost + natGatewayTotalCost + endpointsTotalCost;

        // Build response
        Map<String, Object> result = new HashMap<>();

        // Site-to-Site VPN details
        Map<String, Object> siteToSiteVpn = new HashMap<>();
        siteToSiteVpn.put("connections", siteToSiteVpnConnections);
        siteToSiteVpn.put("hoursPerDay", siteToSiteVpnHoursPerDay);
        siteToSiteVpn.put("hoursPerMonth", siteToSiteVpnHoursPerMonth);
        siteToSiteVpn.put("hourlyRate", siteToSiteVpnHourlyRate);
        siteToSiteVpn.put("monthlyTotal", round(siteToSiteVpnMonthlyCost, 2));
        result.put("siteToSiteVpn", siteToSiteVpn);

        // Client VPN details
        Map<String, Object> clientVpn = new HashMap<>();
        clientVpn.put("subnets", clientVpnSubnets);
        clientVpn.put("activeConnections", clientVpnActiveConnections);
        clientVpn.put("hoursPerDay", clientVpnHoursPerDay);
        clientVpn.put("workingDaysPerMonth", clientVpnWorkingDaysPerMonth);
        clientVpn.put("endpointCost", round(clientVpnEndpointCost, 2));
        clientVpn.put("connectionCost", round(clientVpnConnectionCost, 2));
        clientVpn.put("monthlyTotal", round(clientVpnTotalCost, 2));
        result.put("clientVpn", clientVpn);

        // NAT Gateway details
        Map<String, Object> natGateway = new HashMap<>();
        natGateway.put("count", natGatewayCount);
        natGateway.put("dataProcessedGB", natGatewayDataProcessedGB);
        natGateway.put("hoursCost", round(natGatewayHoursCost, 2));
        natGateway.put("dataCost", round(natGatewayDataCost, 2));
        natGateway.put("monthlyTotal", round(natGatewayTotalCost, 2));
        result.put("natGateway", natGateway);

        // VPC Endpoints details
        Map<String, Object> vpcEndpoints = new HashMap<>();
        vpcEndpoints.put("interfaceEndpoints", interfaceEndpoints);
        vpcEndpoints.put("gatewayEndpoints", gatewayEndpoints);
        vpcEndpoints.put("interfaceEndpointCost", round(interfaceEndpointMonthlyCost, 2));
        vpcEndpoints.put("gatewayEndpointCost", round(gatewayEndpointCost, 2));
        vpcEndpoints.put("monthlyTotal", round(endpointsTotalCost, 2));
        result.put("vpcEndpoints", vpcEndpoints);

        // Cost optimization suggestions
        List<Map<String, Object>> optimizations = new ArrayList<>();
        if (natGatewayCount > 0) {
            Map<String, Object> opt1 = new HashMap<>();
            opt1.put("title", "Consider VPC Endpoints");
            opt1.put("description", "Use VPC Gateway Endpoints for S3/DynamoDB (FREE) instead of NAT Gateway");
            opt1.put("potentialSavings", round(natGatewayTotalCost * 0.3, 2) + " USD/month");
            optimizations.add(opt1);
        }
        if (clientVpnActiveConnections > 10) {
            Map<String, Object> opt2 = new HashMap<>();
            opt2.put("title", "Optimize Client VPN Usage");
            opt2.put("description", "Consider AWS Systems Manager Session Manager for some use cases (FREE)");
            opt2.put("potentialSavings", round(clientVpnTotalCost * 0.2, 2) + " USD/month");
            optimizations.add(opt2);
        }
        if (siteToSiteVpnConnections > 2) {
            Map<String, Object> opt3 = new HashMap<>();
            opt3.put("title", "Consider Transit Gateway");
            opt3.put("description", "For 3+ VPN connections, Transit Gateway may be more cost-effective");
            opt3.put("potentialSavings", "Depends on architecture");
            optimizations.add(opt3);
        }
        result.put("optimizations", optimizations);

        result.put("totalMonthly", round(totalMonthly, 2));
        result.put("region", region);
        result.put("currency", "USD");

        logger.info("VPC estimate calculated: ${}", round(totalMonthly, 2));
        return result;
    }

    /**
     * ✨ NEW: Get comprehensive ELB pricing estimate
     */
    public Map<String, Object> getComprehensiveElbEstimate(
            String awsAccountId,
            String region,
            String loadBalancerType,
            int numberOfLoadBalancers,
            int processedBytesGB,
            int newConnectionsPerSecond,
            int activeConnections,
            int ruleEvaluations) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getComprehensiveElbEstimate");
            return costMockService.getMockComprehensiveElbEstimate(region, loadBalancerType, 730, processedBytesGB, newConnectionsPerSecond);
        }

        logger.info("Calculating ELB estimate for region: {}, type: {}", region, loadBalancerType);

        double hourlyRate = 0.0;
        double lcuHourlyRate = 0.0;
        double dataProcessingRate = 0.0;

        // Pricing based on load balancer type
        switch (loadBalancerType.toUpperCase()) {
            case "APPLICATION":
            case "ALB":
                hourlyRate = 0.0225; // $0.0225 per ALB-hour
                lcuHourlyRate = 0.008; // $0.008 per LCU-hour
                break;
            case "NETWORK":
            case "NLB":
                hourlyRate = 0.0225; // $0.0225 per NLB-hour
                lcuHourlyRate = 0.006; // $0.006 per NLCU-hour
                break;
            case "GATEWAY":
            case "GWLB":
                hourlyRate = 0.0125; // $0.0125 per GWLB-hour
                lcuHourlyRate = 0.004; // $0.004 per GWLCU-hour
                break;
            case "CLASSIC":
            case "CLB":
                hourlyRate = 0.025; // $0.025 per CLB-hour
                dataProcessingRate = 0.008; // $0.008 per GB
                break;
            default:
                hourlyRate = 0.0225;
                lcuHourlyRate = 0.008;
        }

        // Calculate LCU consumption (for ALB/NLB/GWLB)
        double lcuFromBytes = processedBytesGB / 1.0; // 1 GB = 1 LCU dimension
        double lcuFromConnections = newConnectionsPerSecond / 25.0; // 25 new conn/sec = 1 LCU
        double lcuFromActiveConnections = activeConnections / 3000.0; // 3000 active = 1 LCU
        double lcuFromRules = ruleEvaluations / 1000.0; // 1000 rule evals = 1 LCU

        // Max LCU dimension (billing is based on highest dimension)
        double maxLCU = Math.max(Math.max(lcuFromBytes, lcuFromConnections),
                Math.max(lcuFromActiveConnections, lcuFromRules));

        // Monthly costs
        double hoursPerMonth = 730;
        double lbHourlyCost = numberOfLoadBalancers * hourlyRate * hoursPerMonth;

        double lcuMonthlyCost = 0.0;
        double dataProcessingCost = 0.0;

        if ("CLASSIC".equalsIgnoreCase(loadBalancerType) || "CLB".equalsIgnoreCase(loadBalancerType)) {
            // Classic LB uses data processing pricing
            dataProcessingCost = processedBytesGB * dataProcessingRate;
        } else {
            // ALB/NLB/GWLB use LCU pricing
            lcuMonthlyCost = maxLCU * lcuHourlyRate * hoursPerMonth * numberOfLoadBalancers;
        }

        double totalMonthly = lbHourlyCost + lcuMonthlyCost + dataProcessingCost;

        // Build response
        Map<String, Object> result = new HashMap<>();

        // Load Balancer details
        Map<String, Object> loadBalancer = new HashMap<>();
        loadBalancer.put("type", loadBalancerType);
        loadBalancer.put("count", numberOfLoadBalancers);
        loadBalancer.put("hourlyRate", hourlyRate);
        loadBalancer.put("monthlyTotal", round(lbHourlyCost, 2));
        result.put("loadBalancer", loadBalancer);

        // LCU/Data Processing details
        if (!"CLASSIC".equalsIgnoreCase(loadBalancerType) && !"CLB".equalsIgnoreCase(loadBalancerType)) {
            Map<String, Object> lcu = new HashMap<>();
            lcu.put("processedBytesGB", processedBytesGB);
            lcu.put("newConnectionsPerSecond", newConnectionsPerSecond);
            lcu.put("activeConnections", activeConnections);
            lcu.put("ruleEvaluations", ruleEvaluations);
            lcu.put("lcuFromBytes", round(lcuFromBytes, 2));
            lcu.put("lcuFromConnections", round(lcuFromConnections, 2));
            lcu.put("lcuFromActiveConnections", round(lcuFromActiveConnections, 2));
            lcu.put("lcuFromRules", round(lcuFromRules, 2));
            lcu.put("maxLCU", round(maxLCU, 2));
            lcu.put("lcuHourlyRate", lcuHourlyRate);
            lcu.put("monthlyTotal", round(lcuMonthlyCost, 2));
            result.put("lcu", lcu);
        } else {
            Map<String, Object> dataProcessing = new HashMap<>();
            dataProcessing.put("processedGB", processedBytesGB);
            dataProcessing.put("ratePerGB", dataProcessingRate);
            dataProcessing.put("monthlyTotal", round(dataProcessingCost, 2));
            result.put("dataProcessing", dataProcessing);
        }

        // Cost comparison between LB types
        List<Map<String, Object>> comparisons = new ArrayList<>();

        Map<String, Object> alb = new HashMap<>();
        alb.put("type", "Application Load Balancer (ALB)");
        alb.put("monthlyTotal", round(calculateElbCost("ALB", numberOfLoadBalancers, processedBytesGB,
                newConnectionsPerSecond, activeConnections, ruleEvaluations), 2));
        alb.put("description", "Best for HTTP/HTTPS traffic, advanced routing");
        comparisons.add(alb);

        Map<String, Object> nlb = new HashMap<>();
        nlb.put("type", "Network Load Balancer (NLB)");
        nlb.put("monthlyTotal", round(calculateElbCost("NLB", numberOfLoadBalancers, processedBytesGB,
                newConnectionsPerSecond, activeConnections, ruleEvaluations), 2));
        nlb.put("description", "Best for TCP/UDP traffic, ultra-low latency");
        comparisons.add(nlb);

        Map<String, Object> clb = new HashMap<>();
        clb.put("type", "Classic Load Balancer (CLB)");
        clb.put("monthlyTotal", round(calculateElbCost("CLB", numberOfLoadBalancers, processedBytesGB,
                newConnectionsPerSecond, activeConnections, ruleEvaluations), 2));
        clb.put("description", "Previous generation (not recommended)");
        comparisons.add(clb);

        result.put("comparisons", comparisons);
        result.put("totalMonthly", round(totalMonthly, 2));
        result.put("region", region);
        result.put("currency", "USD");

        logger.info("ELB estimate calculated: ${}", round(totalMonthly, 2));

        return result;
    }

    private double calculateElbCost(String type, int count, int bytesGB, int newConn, int activeConn, int rules) {
        double hourlyRate = 0.0225;
        double lcuRate = 0.008;
        double dataRate = 0.008;

        if ("NLB".equalsIgnoreCase(type)) {
            lcuRate = 0.006;
        } else if ("CLB".equalsIgnoreCase(type)) {
            hourlyRate = 0.025;
            return (count * hourlyRate * 730) + (bytesGB * dataRate);
        }

        double maxLCU = Math.max(Math.max(bytesGB / 1.0, newConn / 25.0),
                Math.max(activeConn / 3000.0, rules / 1000.0));

        return (count * hourlyRate * 730) + (maxLCU * lcuRate * 730 * count);
    }

    public Map<String, Object> getEc2InstancePrices(String awsAccountId,
                                                    String region,
                                                    String operatingSystem) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getEc2InstancePrices");
            return costMockService.getMockEc2InstancePrices(region, operatingSystem);
        }

        CloudAccount account = null;
        if (awsAccountId != null && !awsAccountId.equals("null")) {
            account = resolveAwsAccount(awsAccountId);
        }

        PricingClient pricingClient = awsClientProvider.getPricingClient();
        List<Map<String, Object>> instances = new ArrayList<>();

        try {
            DescribeServicesRequest describeServicesRequest = DescribeServicesRequest.builder()
                    .serviceCode("AmazonEC2")
                    .build();
            DescribeServicesResponse servicesResponse = pricingClient.describeServices(describeServicesRequest);
            logger.debug("AWS Pricing services for EC2 returned {} services", servicesResponse.services().size());
        } catch (PricingException e) {
            logger.warn("Error describing EC2 pricing services: {}", e.getMessage());
        }

        try {
            List<Filter> filters = new ArrayList<>();
            filters.add(Filter.builder()
                    .field("serviceCode")
                    .type(FilterType.TERM_MATCH)
                    .value("AmazonEC2")
                    .build());
            filters.add(Filter.builder()
                    .field("location")
                    .type(FilterType.TERM_MATCH)
                    .value(regionToLocation(region))
                    .build());
            filters.add(Filter.builder()
                    .field("operatingSystem")
                    .type(FilterType.TERM_MATCH)
                    .value(operatingSystem)
                    .build());
            filters.add(Filter.builder()
                    .field("preInstalledSw")
                    .type(FilterType.TERM_MATCH)
                    .value("NA")
                    .build());
            filters.add(Filter.builder()
                    .field("tenancy")
                    .type(FilterType.TERM_MATCH)
                    .value("Shared")
                    .build());
            filters.add(Filter.builder()
                    .field("capacitystatus")
                    .type(FilterType.TERM_MATCH)
                    .value("Used")
                    .build());

            String nextToken = null;
            int maxPages = 5;
            int page = 0;

            do {
                GetProductsRequest.Builder reqBuilder = GetProductsRequest.builder()
                        .serviceCode("AmazonEC2")
                        .filters(filters)
                        .formatVersion("aws_v1")
                        .maxResults(100);

                if (nextToken != null) {
                    reqBuilder.nextToken(nextToken);
                }

                GetProductsResponse response = pricingClient.getProducts(reqBuilder.build());
                List<String> priceList = response.priceList();

                for (String priceJson : priceList) {
                    Map<String, Object> parsed = simplifyEc2PriceJson(priceJson);
                    if (parsed != null) {
                        instances.add(parsed);
                    }
                }

                nextToken = response.nextToken();
                page++;
            } while (nextToken != null && page < maxPages);

        } catch (Exception e) {
            logger.error("Error fetching EC2 pricing for account {} region {} OS {}",
                    awsAccountId, region, operatingSystem, e);
            throw new RuntimeException("Failed to fetch EC2 pricing from AWS Pricing API", e);
        }

        Map<String, Map<String, Object>> byType = instances.stream()
                .filter(m -> m.get("instanceType") != null)
                .collect(Collectors.toMap(
                        m -> m.get("instanceType").toString(),
                        m -> m,
                        (a, b) -> a
                ));

        Map<String, Object> result = new HashMap<>();
        result.put("accountId", awsAccountId);
        result.put("region", region);
        result.put("os", operatingSystem);
        result.put("currency", "USD");
        result.put("instances", new ArrayList<>(byType.values()));
        return result;
    }

    /**
     * ✨ Get comprehensive EC2 estimate with storage, data transfer, and pricing models
     */
    public Map<String, Object> getComprehensiveEc2Estimate(
            String awsAccountId,
            String region,
            String instanceType,
            String operatingSystem,
            int instanceCount,
            int hoursPerMonth,
            int storageGB,
            String storageType,
            int dataTransferOutGB) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getComprehensiveEc2Estimate");
            return costMockService.getMockComprehensiveEc2Estimate(region, instanceType, operatingSystem,
                    instanceCount, hoursPerMonth, storageGB, storageType, dataTransferOutGB);
        }

        logger.info("Getting comprehensive EC2 estimate for {} in {}", instanceType, region);

        CloudAccount account = resolveAwsAccount(awsAccountId);
        if (account == null) {
            throw new RuntimeException("CloudAccount not found for id: " + awsAccountId);
        }

        double instanceHourlyRate = getInstanceHourlyRate(region, instanceType, operatingSystem);
        double computeHourly = instanceHourlyRate * instanceCount;
        double computeMonthly = computeHourly * hoursPerMonth;

        Map<String, Object> storageCosts = calculateEbsStorageCost(region, storageType, storageGB);
        double storageMonthly = ((Number) storageCosts.get("totalMonthly")).doubleValue() * instanceCount;

        double dataTransferMonthly = calculateDataTransferCost(dataTransferOutGB);

        List<Map<String, Object>> pricingModels = calculatePricingModels(
                computeMonthly, storageMonthly, dataTransferMonthly, hoursPerMonth);
                // List<Map<String, Object>> pricingModels = new ArrayList<>();


        Map<String, Object> result = new HashMap<>();

        Map<String, Object> compute = new HashMap<>();
        compute.put("instanceType", instanceType);
        compute.put("instanceCount", instanceCount);
        compute.put("hoursPerMonth", hoursPerMonth);
        compute.put("hourlyRate", round(instanceHourlyRate, 4));
        compute.put("monthlyTotal", round(computeMonthly, 2));
        result.put("compute", compute);

        Map<String, Object> storage = new HashMap<>();
        storage.put("type", storageType);
        storage.put("sizeGB", storageGB);
        storage.put("pricePerGB", storageCosts.get("pricePerGB"));
        storage.put("monthlyTotal", round(storageMonthly, 2));
        result.put("storage", storage);

        Map<String, Object> dataTransfer = new HashMap<>();
        dataTransfer.put("outboundGB", dataTransferOutGB);
        dataTransfer.put("monthlyTotal", round(dataTransferMonthly, 2));
        result.put("dataTransfer", dataTransfer);

        result.put("pricingModels", pricingModels);

        double grandTotal = computeMonthly + storageMonthly + dataTransferMonthly;
        result.put("totalMonthly", round(grandTotal, 2));
        result.put("totalHourly", round(grandTotal / hoursPerMonth, 4));

        result.put("region", region);
        result.put("currency", "USD");

        logger.info("Comprehensive estimate: Compute=${}, Storage=${}, Transfer=${}, Total=${}",
                round(computeMonthly, 2), round(storageMonthly, 2),
                round(dataTransferMonthly, 2), round(grandTotal, 2));

        return result;
    }

    /**
     * ✨ NEW: Get comprehensive S3 pricing estimate
     */
    public Map<String, Object> getComprehensiveS3Estimate(
            String awsAccountId,
            String region,
            int storageGB,
            String storageClass,
            int dataTransferOutGB,
            int putRequests,
            int getRequests) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getComprehensiveS3Estimate");
            return costMockService.getMockComprehensiveS3Estimate(region, storageClass, storageGB,
                    putRequests + getRequests, dataTransferOutGB);
        }

        logger.info("Calculating S3 estimate for region: {}, storage: {}GB, class: {}",
                region, storageGB, storageClass);

        // S3 Storage Pricing (per GB-month)
        double storagePricePerGB = 0.023;
        switch (storageClass.toUpperCase()) {
            case "STANDARD":
                storagePricePerGB = 0.023;
                break;
            case "INTELLIGENT_TIERING":
                storagePricePerGB = 0.023;
                break;
            case "STANDARD_IA":
                storagePricePerGB = 0.0125;
                break;
            case "ONE_ZONE_IA":
                storagePricePerGB = 0.01;
                break;
            case "GLACIER_INSTANT":
                storagePricePerGB = 0.004;
                break;
            case "GLACIER_FLEXIBLE":
                storagePricePerGB = 0.0036;
                break;
            case "GLACIER_DEEP_ARCHIVE":
                storagePricePerGB = 0.00099;
                break;
            default:
                storagePricePerGB = 0.023;
        }

        double storageMonthlyCost = storageGB * storagePricePerGB;

        // Data Transfer Out pricing (tiered)
        double dataTransferCost = 0;
        if (dataTransferOutGB > 0) {
            if (dataTransferOutGB <= 10240) {
                dataTransferCost = Math.max(0, dataTransferOutGB - 1) * 0.09;
            } else {
                dataTransferCost = (10240 * 0.09) + ((dataTransferOutGB - 10240) * 0.085);
            }
        }

        // Request pricing
        double putRequestCost = (putRequests / 1000.0) * 0.005;
        double getRequestCost = (getRequests / 1000.0) * 0.0004;
        double totalRequestCost = putRequestCost + getRequestCost;

        double totalMonthly = storageMonthlyCost + dataTransferCost + totalRequestCost;

        Map<String, Object> result = new HashMap<>();

        Map<String, Object> storage = new HashMap<>();
        storage.put("sizeGB", storageGB);
        storage.put("storageClass", storageClass);
        storage.put("pricePerGB", storagePricePerGB);
        storage.put("monthlyTotal", round(storageMonthlyCost, 2));
        result.put("storage", storage);

        Map<String, Object> dataTransfer = new HashMap<>();
        dataTransfer.put("outboundGB", dataTransferOutGB);
        dataTransfer.put("monthlyTotal", round(dataTransferCost, 2));
        result.put("dataTransfer", dataTransfer);

        Map<String, Object> requests = new HashMap<>();
        requests.put("putRequests", putRequests);
        requests.put("getRequests", getRequests);
        requests.put("putCost", round(putRequestCost, 2));
        requests.put("getCost", round(getRequestCost, 2));
        requests.put("monthlyTotal", round(totalRequestCost, 2));
        result.put("requests", requests);

        List<Map<String, Object>> pricingModels = new ArrayList<>();

        pricingModels.add(createS3PricingModel("S3 Standard", storageGB, 0.023,
                dataTransferOutGB, putRequests, getRequests,
                "Best for frequently accessed data", "0%"));

        pricingModels.add(createS3PricingModel("S3 Intelligent-Tiering", storageGB, 0.023,
                dataTransferOutGB, putRequests, getRequests,
                "Auto-moves data between tiers", "~40%"));

        pricingModels.add(createS3PricingModel("S3 Standard-IA", storageGB, 0.0125,
                dataTransferOutGB, putRequests, getRequests,
                "Infrequent access, lower storage", "~45%"));

        pricingModels.add(createS3PricingModel("S3 Glacier Flexible", storageGB, 0.0036,
                dataTransferOutGB, putRequests, getRequests,
                "Archive, retrieval in mins-hours", "~84%"));

        result.put("pricingModels", pricingModels);
        result.put("totalMonthly", round(totalMonthly, 2));
        result.put("region", region);
        result.put("currency", "USD");

        logger.info("S3 estimate calculated: ${}", round(totalMonthly, 2));

        return result;
    }

    private Map<String, Object> createS3PricingModel(String name, int storageGB,
                                                     double storagePricePerGB, int dataTransferGB, int putReq, int getReq,
                                                     String description, String savings) {

        double storageCost = storageGB * storagePricePerGB;

        double dataTransferCost = 0;
        if (dataTransferGB > 0) {
            dataTransferCost = Math.max(0, dataTransferGB - 1) * 0.09;
        }

        double putCost = (putReq / 1000.0) * 0.005;
        double getCost = (getReq / 1000.0) * 0.0004;

        double total = storageCost + dataTransferCost + putCost + getCost;

        Map<String, Object> model = new HashMap<>();
        model.put("name", name);
        model.put("monthlyTotal", round(total, 2));
        model.put("storageCost", round(storageCost, 2));
        model.put("description", description);
        model.put("savings", savings);

        return model;
    }

    private double getInstanceHourlyRate(String region, String instanceType, String os) {
        Map<String, Object> prices = getEc2InstancePrices("default", region, os);
        List<Map<String, Object>> instances = (List<Map<String, Object>>) prices.get("instances");

        return instances.stream()
                .filter(inst -> instanceType.equals(inst.get("instanceType")))
                .findFirst()
                .map(inst -> ((Number) inst.get("pricePerHour")).doubleValue())
                .orElseThrow(() -> new RuntimeException("Instance type " + instanceType + " not found"));
    }

    private Map<String, Object> calculateEbsStorageCost(String region, String storageType, int storageGB) {
        Map<String, Double> storagePricing = new HashMap<>();
        storagePricing.put("gp3", 0.08);
        storagePricing.put("gp2", 0.10);
        storagePricing.put("io2", 0.125);
        storagePricing.put("io1", 0.125);
        storagePricing.put("st1", 0.045);
        storagePricing.put("sc1", 0.015);

        double pricePerGB = storagePricing.getOrDefault(storageType, 0.08);
        double totalMonthly = storageGB * pricePerGB;

        Map<String, Object> result = new HashMap<>();
        result.put("pricePerGB", pricePerGB);
        result.put("totalMonthly", totalMonthly);
        result.put("storageType", storageType);

        return result;
    }

    private double calculateDataTransferCost(int dataTransferOutGB) {
        if (dataTransferOutGB == 0) {
            return 0.0;
        }

        double cost = 0.0;
        int remaining = dataTransferOutGB;

        if (remaining > 0) {
            int tier1 = Math.min(remaining, 100);
            cost += tier1 * 0.09;
            remaining -= tier1;
        }

        if (remaining > 0) {
            int tier2 = Math.min(remaining, 9900);
            cost += tier2 * 0.085;
            remaining -= tier2;
        }

        if (remaining > 0) {
            cost += remaining * 0.07;
        }

        return cost;
    }

    private List<Map<String, Object>> calculatePricingModels(
            double computeMonthly, double storageMonthly, double dataTransferMonthly, int hoursPerMonth) {

        List<Map<String, Object>> models = new ArrayList<>();
        double baseTotal = computeMonthly + storageMonthly + dataTransferMonthly;

        Map<String, Object> onDemand = new HashMap<>();
        onDemand.put("name", "On-Demand");
        onDemand.put("upfront", 0);
        onDemand.put("monthlyTotal", round(baseTotal, 2));
        onDemand.put("hourlyRate", round(baseTotal / hoursPerMonth, 4));
        onDemand.put("savings", "0%");
        onDemand.put("description", "Pay as you go, no commitment");
        models.add(onDemand);

        Map<String, Object> reserved1yr = new HashMap<>();
        double reserved1yrMonthly = computeMonthly * 0.65 + storageMonthly + dataTransferMonthly;
        reserved1yr.put("name", "Reserved 1-Year");
        reserved1yr.put("upfront", 0);
        reserved1yr.put("monthlyTotal", round(reserved1yrMonthly, 2));
        reserved1yr.put("hourlyRate", round(reserved1yrMonthly / hoursPerMonth, 4));
        reserved1yr.put("savings", "~35%");
        reserved1yr.put("description", "1-year commitment, no upfront");
        models.add(reserved1yr);

        Map<String, Object> reserved3yr = new HashMap<>();
        double reserved3yrMonthly = computeMonthly * 0.50 + storageMonthly + dataTransferMonthly;
        reserved3yr.put("name", "Reserved 3-Year");
        reserved3yr.put("upfront", 0);
        reserved3yr.put("monthlyTotal", round(reserved3yrMonthly, 2));
        reserved3yr.put("hourlyRate", round(reserved3yrMonthly / hoursPerMonth, 4));
        reserved3yr.put("savings", "~50%");
        reserved3yr.put("description", "3-year commitment, no upfront");
        models.add(reserved3yr);

        Map<String, Object> spot = new HashMap<>();
        double spotMonthly = computeMonthly * 0.40 + storageMonthly + dataTransferMonthly;
        spot.put("name", "Spot Instances");
        spot.put("upfront", 0);
        spot.put("monthlyTotal", round(spotMonthly, 2));
        spot.put("hourlyRate", round(spotMonthly / hoursPerMonth, 4));
        spot.put("savings", "~60%");
        spot.put("description", "Variable pricing, interruptible");
        models.add(spot);

        return models;
    }

    private String regionToLocation(String region) {
        Map<String, String> map = new HashMap<>();

        map.put("us-east-1", "US East (N. Virginia)");
        map.put("us-east-2", "US East (Ohio)");
        map.put("us-west-1", "US West (N. California)");
        map.put("us-west-2", "US West (Oregon)");
        map.put("eu-west-1", "EU (Ireland)");
        map.put("eu-west-2", "EU (London)");
        map.put("eu-west-3", "EU (Paris)");
        map.put("eu-central-1", "EU (Frankfurt)");
        map.put("eu-central-2", "EU (Zurich)");
        map.put("eu-north-1", "EU (Stockholm)");
        map.put("eu-south-1", "EU (Milan)");
        map.put("eu-south-2", "EU (Spain)");
        map.put("ap-south-1", "Asia Pacific (Mumbai)");
        map.put("ap-south-2", "Asia Pacific (Hyderabad)");
        map.put("ap-southeast-1", "Asia Pacific (Singapore)");
        map.put("ap-southeast-2", "Asia Pacific (Sydney)");
        map.put("ap-southeast-3", "Asia Pacific (Jakarta)");
        map.put("ap-southeast-4", "Asia Pacific (Melbourne)");
        map.put("ap-northeast-1", "Asia Pacific (Tokyo)");
        map.put("ap-northeast-2", "Asia Pacific (Seoul)");
        map.put("ap-northeast-3", "Asia Pacific (Osaka)");
        map.put("ap-east-1", "Asia Pacific (Hong Kong)");
        map.put("ca-central-1", "Canada (Central)");
        map.put("ca-west-1", "Canada (Calgary)");
        map.put("sa-east-1", "South America (Sao Paulo)");
        map.put("me-south-1", "Middle East (Bahrain)");
        map.put("me-central-1", "Middle East (UAE)");
        map.put("af-south-1", "Africa (Cape Town)");
        map.put("il-central-1", "Israel (Tel Aviv)");

        String location = map.get(region);

        if (location == null) {
            logger.warn("⚠️ Region '{}' not found in mapping!", region);
            return region;
        }

        logger.debug("✅ Mapped region '{}' to location '{}'", region, location);
        return location;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> simplifyEc2PriceJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null) {
                return null;
            }

            JsonNode product = root.path("product");
            JsonNode attributes = product.path("attributes");
            String instanceType = attributes.path("instanceType").asText(null);
            if (instanceType == null || instanceType.isEmpty()) {
                return null;
            }

            String vcpu = attributes.path("vcpu").asText("");
            String memory = attributes.path("memory").asText("");
            String family = attributes.path("instanceFamily").asText("");

            StringBuilder descBuilder = new StringBuilder();
            if (!family.isEmpty()) {
                descBuilder.append(family);
            }
            if (!vcpu.isEmpty()) {
                if (descBuilder.length() > 0) {
                    descBuilder.append(", ");
                }
                descBuilder.append(vcpu).append(" vCPU");
            }
            if (!memory.isEmpty()) {
                if (descBuilder.length() > 0) {
                    descBuilder.append(", ");
                }
                descBuilder.append(memory);
            }
            String description = descBuilder.toString();

            JsonNode terms = root.path("terms").path("OnDemand");
            Iterator<String> termKeys = terms.fieldNames();
            BigDecimal pricePerHour = null;

            while (termKeys.hasNext() && pricePerHour == null) {
                String termKey = termKeys.next();
                JsonNode termNode = terms.path(termKey).path("priceDimensions");
                Iterator<String> dimKeys = termNode.fieldNames();
                while (dimKeys.hasNext() && pricePerHour == null) {
                    String dimKey = dimKeys.next();
                    JsonNode dimNode = termNode.path(dimKey).path("pricePerUnit").path("USD");
                    if (!dimNode.isMissingNode()) {
                        String priceStr = dimNode.asText();
                        try {
                            pricePerHour = new BigDecimal(priceStr);
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            }

            if (pricePerHour == null) {
                return null;
            }

            Map<String, Object> map = new HashMap<>();
            map.put("instanceType", instanceType);
            map.put("description", description);
            map.put("pricePerHour", pricePerHour.doubleValue());
            return map;

        } catch (Exception e) {
            logger.debug("Failed to parse EC2 pricing JSON product", e);
            return null;
        }
    }

    private CloudAccount resolveAwsAccount(String awsAccountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(awsAccountId);
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        return accounts.stream()
                .filter(a -> "AWS".equalsIgnoreCase(a.getProvider()))
                .findFirst()
                .orElse(accounts.get(0));
    }

    private double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();

        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }
}