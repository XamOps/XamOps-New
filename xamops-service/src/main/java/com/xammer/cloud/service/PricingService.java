package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.SpotPrice;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;
import software.amazon.awssdk.services.rds.model.DBInstance;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PricingService {

    private static final Logger logger = LoggerFactory.getLogger(PricingService.class);
    private final PricingClient pricingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AwsClientProvider awsClientProvider;
    private final CostMockService costMockService;

    @Value("${aws.costexplorer.mock:false}")
    private boolean useMockData;

    @Value("${pricing.ebs.fallback-price:0.10}")
    private double ebsFallbackPrice;

    public PricingService(PricingClient pricingClient, AwsClientProvider awsClientProvider,
            CostMockService costMockService) {
        this.pricingClient = pricingClient;
        this.awsClientProvider = awsClientProvider;
        this.costMockService = costMockService;
    }

    @Cacheable(value = "spotPrice", key = "#account.awsAccountId + '-' + #region + '-' + #instanceType")
    public double getSpotInstancePrice(String instanceType, String region, CloudAccount account) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getSpotInstancePrice");
            return costMockService.getMockSpotInstancePrice(instanceType, region);
        }

        logger.info("Fetching spot price for instance type: {} in region: {}", instanceType, region);
        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);

            DescribeSpotPriceHistoryRequest request = DescribeSpotPriceHistoryRequest.builder()
                    .instanceTypes(InstanceType.fromValue(instanceType))
                    .productDescriptions("Linux/UNIX")
                    .startTime(Instant.now().minusSeconds(3600))
                    .endTime(Instant.now())
                    .build();

            Optional<SpotPrice> spotPrice = ec2Client.describeSpotPriceHistory(request).spotPriceHistory().stream()
                    .max(Comparator.comparing(SpotPrice::timestamp));

            if (spotPrice.isPresent()) {
                return Double.parseDouble(spotPrice.get().spotPrice());
            }
        } catch (Exception e) {
            logger.error("Failed to fetch spot price for instance type {} in region {}", instanceType, region, e);
        }
        return 0.0;
    }

    @Cacheable(value = "ebsPrice", key = "#region + '-' + #volumeType")
    public double getEbsGbMonthPrice(String region, String volumeType) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getEbsGbMonthPrice");
            return costMockService.getMockEbsGbMonthPrice(region, volumeType);
        }

        logger.info("Fetching live EBS price for region: {}, type: {}", region, volumeType);
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH").build();
        Filter volumeApiNameFilter = Filter.builder().field("volumeApiName").value(volumeType).type("TERM_MATCH")
                .build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, volumeApiNameFilter)
                .build();

        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (response.priceList().isEmpty()) {
                logger.warn("No price list found for EBS volume type {} in region {}. Using fallback price.",
                        volumeType, region);
                return ebsFallbackPrice;
            }

            JsonNode priceList = objectMapper.readTree(response.priceList().get(0));
            JsonNode terms = priceList.path("terms").path("OnDemand");
            if (terms.isMissingNode() || terms.isEmpty()) {
                return ebsFallbackPrice;
            }

            JsonNode priceDimensions = terms.elements().next().path("priceDimensions");
            if (priceDimensions.isMissingNode() || priceDimensions.isEmpty()) {
                return ebsFallbackPrice;
            }

            JsonNode pricePerUnit = priceDimensions.elements().next().path("pricePerUnit").path("USD");
            return pricePerUnit.asDouble(ebsFallbackPrice);

        } catch (IOException e) {
            logger.error("Failed to parse pricing JSON for EBS", e);
            return ebsFallbackPrice;
        } catch (Exception e) {
            logger.error("Failed to fetch price for EBS volume type {} in region {}", volumeType, region, e);
            return ebsFallbackPrice;
        }
    }

    @Cacheable(value = "ec2Price", key = "#region + '-' + #instanceType")
    public double getEc2InstanceMonthlyPrice(String instanceType, String region) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getEc2InstanceMonthlyPrice");
            return costMockService.getMockEc2InstanceMonthlyPrice(instanceType, region);
        }

        logger.info("Fetching live EC2 price for region: {}, type: {}", region, instanceType);
        // Use regionCode (the raw region id) instead of location (human-readable description)
        // so that all regions — including newer ones not in any hardcoded map — work correctly.
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH").build();
        Filter instanceTypeFilter = Filter.builder().field("instanceType").value(instanceType).type("TERM_MATCH").build();
        Filter tenancyFilter = Filter.builder().field("tenancy").value("Shared").type("TERM_MATCH").build();
        Filter osFilter = Filter.builder().field("operatingSystem").value("Linux").type("TERM_MATCH").build();
        Filter capacityFilter = Filter.builder().field("capacitystatus").value("Used").type("TERM_MATCH").build();
        Filter preinstalledSwFilter = Filter.builder().field("preInstalledSw").value("NA").type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, instanceTypeFilter, tenancyFilter, osFilter, capacityFilter, preinstalledSwFilter)
                .build();

        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            List<String> priceList = response.priceList();

            // Graviton/ARM instances occasionally have no preInstalledSw/capacitystatus entry —
            // retry with essential filters only before giving up.
            if (priceList.isEmpty()) {
                logger.warn("No price found for {} in {} with strict filters, retrying with relaxed filters", instanceType, region);
                priceList = pricingClient.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonEC2")
                        .filters(regionFilter, instanceTypeFilter, tenancyFilter, osFilter)
                        .build()).priceList();
            }

            if (priceList.isEmpty()) {
                logger.warn("No price list found for EC2 instance type {} in region {}", instanceType, region);
                return 0.0;
            }

            JsonNode priceListNode = objectMapper.readTree(priceList.get(0));
            JsonNode onDemandTerms = priceListNode.path("terms").path("OnDemand");

            Iterator<JsonNode> elements = onDemandTerms.elements();
            if (elements.hasNext()) {
                JsonNode priceDimensions = elements.next().path("priceDimensions");
                Iterator<JsonNode> dimensions = priceDimensions.elements();
                if (dimensions.hasNext()) {
                    JsonNode pricePerUnit = dimensions.next().path("pricePerUnit").path("USD");
                    double hourlyPrice = pricePerUnit.asDouble(0.0);
                    return hourlyPrice * 24 * 30;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to fetch or parse price for EC2 instance type {} in region {}", instanceType, region, e);
        }
        return 0.0;
    }

    @Cacheable(value = "elasticIpPrice", key = "#region")
    public double getElasticIpMonthlyPrice(String region) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getElasticIpMonthlyPrice");
            return costMockService.getMockElasticIpMonthlyPrice(region);
        }

        logger.info("Fetching live Elastic IP price for region: {}", region);

        // *** CHANGED: Using more specific filters for Elastic IPs ***
        Filter regionFilter = Filter.builder().field("location").value(getRegionDescription(region)).type("TERM_MATCH")
                .build();
        Filter productFamilyFilter = Filter.builder().field("productFamily").value("IP Address").type("TERM_MATCH")
                .build();
        // This targets unattached (idle) public IPv4 addresses, which is what AWS
        // charges for.
        Filter usageTypeFilter = Filter.builder().field("usagetype").value(region + "-UnusedAddr").type("TERM_MATCH")
                .build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                // *** CHANGED: Using the new, more reliable filters ***
                .filters(regionFilter, productFamilyFilter, usageTypeFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (response.priceList().isEmpty()) {
                logger.warn("No price list found for Elastic IP in region {}", region);
                return 0.005 * 720; // Fallback to a common hourly price ($0.005) * 720 hours
            }
            JsonNode priceList = objectMapper.readTree(response.priceList().get(0));
            JsonNode onDemand = priceList.path("terms").path("OnDemand");
            JsonNode priceDimensions = onDemand.elements().next().path("priceDimensions");
            JsonNode pricePerUnit = priceDimensions.elements().next().path("pricePerUnit").path("USD");
            double hourlyPrice = pricePerUnit.asDouble(0.005);
            return hourlyPrice * 24 * 30; // Calculate monthly cost
        } catch (Exception e) {
            logger.error("Failed to fetch price for Elastic IP in region {}", region, e);
            return 0.005 * 720; // Fallback
        }
    }

    @Cacheable(value = "rdsPrice", key = "#region + '-' + #dbInstance.dbInstanceClass()")
    public double getRdsInstanceMonthlyPrice(DBInstance dbInstance, String region) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getRdsInstanceMonthlyPrice");
            return costMockService.getMockRdsInstanceMonthlyPrice(dbInstance, region);
        }

        logger.info("Fetching live RDS price for region: {}, type: {}", region, dbInstance.dbInstanceClass());
        Filter regionFilter = Filter.builder().field("location").value(getRegionDescription(region)).type("TERM_MATCH")
                .build();
        Filter instanceClassFilter = Filter.builder().field("instanceType").value(dbInstance.dbInstanceClass())
                .type("TERM_MATCH").build();
        Filter engineFilter = Filter.builder().field("databaseEngine").value(dbInstance.engine()).type("TERM_MATCH")
                .build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonRDS")
                .filters(regionFilter, instanceClassFilter, engineFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (response.priceList().isEmpty()) {
                logger.warn("No price list found for RDS instance type {} in region {}", dbInstance.dbInstanceClass(),
                        region);
                return 20.0; // Fallback
            }
            JsonNode priceList = objectMapper.readTree(response.priceList().get(0));
            JsonNode onDemand = priceList.path("terms").path("OnDemand");
            JsonNode priceDimensions = onDemand.elements().next().path("priceDimensions");
            JsonNode pricePerUnit = priceDimensions.elements().next().path("pricePerUnit").path("USD");
            double hourlyPrice = pricePerUnit.asDouble(0.02);
            return hourlyPrice * 24 * 30;
        } catch (Exception e) {
            logger.error("Failed to fetch price for RDS instance {} in region {}", dbInstance.dbInstanceIdentifier(),
                    region, e);
            return 20.0; // Fallback
        }
    }

    @Cacheable(value = "natGatewayPrice", key = "#region")
    public double getNatGatewayHourlyPrice(String region) {
        double fallback = 0.045;
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live NAT Gateway price for region: {}", region);
        Filter regionFilter = Filter.builder().field("location").value(getRegionDescription(region)).type("TERM_MATCH")
                .build();
        Filter groupFilter = Filter.builder().field("group").value("NAT Gateway").type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonVPC")
                .filters(regionFilter, groupFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            for (String priceJson : response.priceList()) {
                JsonNode priceListNode = objectMapper.readTree(priceJson);
                String usageType = priceListNode.path("product").path("attributes").path("usagetype").asText("");
                if (usageType.contains("NatGateway-Hours")) {
                    double price = extractOnDemandPrice(priceListNode);
                    if (price > 0) {
                        return price;
                    }
                }
            }
            logger.warn("No NAT Gateway hourly price found for region {}, using fallback", region);
        } catch (Exception e) {
            logger.error("Failed to fetch NAT Gateway price for region {}", region, e);
        }
        return fallback;
    }

    @Cacheable(value = "elbPrice", key = "#region + '-' + #lbType")
    public double getElbHourlyPrice(String region, String lbType) {
        double fallback = getElbHourlyFallback(lbType);
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live ELB price for region: {}, type: {}", region, lbType);
        Filter regionFilter = Filter.builder().field("location").value(getRegionDescription(region)).type("TERM_MATCH")
                .build();
        Filter usageTypeFilter = Filter.builder().field("usagetype").value("LoadBalancerUsage").type("TERM_MATCH")
                .build();
        Filter operationFilter = Filter.builder().field("operation").value(mapElbOperation(lbType)).type("TERM_MATCH")
                .build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AWSELB")
                .filters(regionFilter, usageTypeFilter, operationFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            for (String priceJson : response.priceList()) {
                double price = extractOnDemandPrice(objectMapper.readTree(priceJson));
                if (price > 0) {
                    return price;
                }
            }
            logger.warn("No ELB price found for type {} in region {}, using fallback", lbType, region);
        } catch (Exception e) {
            logger.error("Failed to fetch ELB price for type {} in region {}", lbType, region, e);
        }
        return fallback;
    }

    private String mapElbOperation(String lbType) {
        if (lbType == null) {
            return "LoadBalancing:Application";
        }
        switch (lbType.toLowerCase()) {
            case "network":
                return "LoadBalancing:Network";
            case "gateway":
                return "LoadBalancing:Gateway";
            case "classic":
                return "LoadBalancing";
            default:
                return "LoadBalancing:Application";
        }
    }

    private double getElbHourlyFallback(String lbType) {
        if (lbType == null) {
            return 0.0225;
        }
        switch (lbType.toLowerCase()) {
            case "gateway":
                return 0.0125;
            case "classic":
                return 0.025;
            default:
                return 0.0225;
        }
    }

    @Cacheable(value = "ebsSnapshotPrice", key = "#region")
    public double getEbsSnapshotGbMonthPrice(String region) {
        double fallback = 0.05;
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live EBS snapshot price for region: {}", region);
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH").build();
        Filter productFamilyFilter = Filter.builder().field("productFamily").value("Storage Snapshot")
                .type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, productFamilyFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (!response.priceList().isEmpty()) {
                double price = extractOnDemandPrice(objectMapper.readTree(response.priceList().get(0)));
                if (price > 0) {
                    return price;
                }
            }
            logger.warn("No EBS snapshot price found for region {}, using fallback", region);
        } catch (Exception e) {
            logger.error("Failed to fetch EBS snapshot price for region {}", region, e);
        }
        return fallback;
    }

    @Cacheable(value = "rdsSnapshotPrice", key = "#region + '-' + #engine")
    public double getRdsSnapshotGbMonthPrice(String region, String engine) {
        double fallback = 0.095;
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live RDS snapshot price for region: {}, engine: {}", region, engine);
        Filter regionFilter = Filter.builder().field("location").value(getRegionDescription(region)).type("TERM_MATCH")
                .build();
        Filter productFamilyFilter = Filter.builder().field("productFamily").value("Storage Snapshot")
                .type("TERM_MATCH").build();
        Filter engineFilter = Filter.builder().field("databaseEngine").value(engine).type("TERM_MATCH").build();

        try {
            GetProductsResponse response = pricingClient.getProducts(GetProductsRequest.builder()
                    .serviceCode("AmazonRDS")
                    .filters(regionFilter, productFamilyFilter, engineFilter)
                    .build());
            List<String> priceList = response.priceList();
            if (priceList.isEmpty()) {
                // Snapshot storage pricing is often engine-agnostic; retry without the engine filter.
                priceList = pricingClient.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonRDS")
                        .filters(regionFilter, productFamilyFilter)
                        .build()).priceList();
            }
            if (!priceList.isEmpty()) {
                double price = extractOnDemandPrice(objectMapper.readTree(priceList.get(0)));
                if (price > 0) {
                    return price;
                }
            }
            logger.warn("No RDS snapshot price found for region {}, engine {}, using fallback", region, engine);
        } catch (Exception e) {
            logger.error("Failed to fetch RDS snapshot price for region {}, engine {}", region, engine, e);
        }
        return fallback;
    }

    @Cacheable(value = "s3StoragePrice", key = "#region + '-' + #storageClass")
    public double getS3StorageClassGbMonthPrice(String region, String storageClass) {
        double fallback = "Standard-IA".equals(storageClass) ? 0.0125 : 0.023;
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live S3 {} price for region: {}", storageClass, region);
        String volumeType = "Standard-IA".equals(storageClass) ? "Standard - Infrequent Access" : "Standard";
        Filter regionFilter = Filter.builder().field("location").value(getRegionDescription(region)).type("TERM_MATCH")
                .build();
        Filter volumeTypeFilter = Filter.builder().field("volumeType").value(volumeType).type("TERM_MATCH").build();
        Filter usageTypeFilter = Filter.builder().field("usagetype").value("TimedStorage-ByteHrs").type("TERM_MATCH")
                .build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonS3")
                .filters(regionFilter, volumeTypeFilter, usageTypeFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (!response.priceList().isEmpty()) {
                double price = extractOnDemandPrice(objectMapper.readTree(response.priceList().get(0)));
                if (price > 0) {
                    return price;
                }
            }
            logger.warn("No S3 {} price found for region {}, using fallback", storageClass, region);
        } catch (Exception e) {
            logger.error("Failed to fetch S3 {} price for region {}", storageClass, region, e);
        }
        return fallback;
    }

    @Cacheable(value = "elastiCachePrice", key = "#region + '-' + #nodeType")
    public double getElastiCacheNodeHourlyPrice(String region, String nodeType) {
        double fallback = 0.034;
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live ElastiCache price for region: {}, node type: {}", region, nodeType);
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH").build();
        Filter instanceTypeFilter = Filter.builder().field("instanceType").value(nodeType).type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonElastiCache")
                .filters(regionFilter, instanceTypeFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (!response.priceList().isEmpty()) {
                double price = extractOnDemandPrice(objectMapper.readTree(response.priceList().get(0)));
                if (price > 0) {
                    return price;
                }
            }
            logger.warn("No ElastiCache price found for node type {} in region {}, using fallback", nodeType, region);
        } catch (Exception e) {
            logger.error("Failed to fetch ElastiCache price for node type {} in region {}", nodeType, region, e);
        }
        return fallback;
    }

    /**
     * Extracts the first OnDemand price-per-unit (USD) from a Pricing API price-list entry.
     */
    private double extractOnDemandPrice(JsonNode priceListNode) {
        JsonNode onDemandTerms = priceListNode.path("terms").path("OnDemand");
        Iterator<JsonNode> termElements = onDemandTerms.elements();
        if (termElements.hasNext()) {
            JsonNode priceDimensions = termElements.next().path("priceDimensions");
            Iterator<JsonNode> dimElements = priceDimensions.elements();
            if (dimElements.hasNext()) {
                return dimElements.next().path("pricePerUnit").path("USD").asDouble(0.0);
            }
        }
        return 0.0;
    }

    private String getRegionDescription(String regionCode) {
        Map<String, String> regionMap = Map.ofEntries(
                Map.entry("us-east-1", "US East (N. Virginia)"),
                Map.entry("us-east-2", "US East (Ohio)"),
                Map.entry("us-west-1", "US West (N. California)"),
                Map.entry("us-west-2", "US West (Oregon)"),
                Map.entry("ap-south-1", "Asia Pacific (Mumbai)"),
                Map.entry("ap-northeast-2", "Asia Pacific (Seoul)"),
                Map.entry("ap-southeast-1", "Asia Pacific (Singapore)"),
                Map.entry("ap-southeast-2", "Asia Pacific (Sydney)"),
                Map.entry("ap-northeast-1", "Asia Pacific (Tokyo)"),
                Map.entry("ca-central-1", "Canada (Central)"),
                Map.entry("eu-central-1", "EU (Frankfurt)"),
                Map.entry("eu-west-1", "EU (Ireland)"),
                Map.entry("eu-west-2", "EU (London)"),
                Map.entry("eu-west-3", "EU (Paris)"),
                Map.entry("eu-north-1", "EU (Stockholm)"),
                Map.entry("sa-east-1", "South America (Sao Paulo)"));
        return regionMap.getOrDefault(regionCode, regionCode);
    }
}