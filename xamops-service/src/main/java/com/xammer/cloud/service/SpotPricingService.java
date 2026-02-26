package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.SpotPrice;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * SpotPricingService
 *
 * Specialized service for AutoSpotting to fetch hourly On-Demand and Spot
 * prices.
 */
@Service
public class SpotPricingService {

    private static final Logger logger = LoggerFactory.getLogger(SpotPricingService.class);
    private final PricingClient pricingClient;
    private final AwsClientProvider awsClientProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpotPricingService(PricingClient pricingClient, AwsClientProvider awsClientProvider) {
        this.pricingClient = pricingClient;
        this.awsClientProvider = awsClientProvider;
    }

    /**
     * Gets the current hourly Spot price for an instance type.
     */
    @Cacheable(value = "hourlySpotPrice", key = "#instanceType + '-' + #region")
    public double getHourlySpotPrice(String instanceType, String region, CloudAccount account) {
        logger.debug("Fetching Spot price for {} in {}", instanceType, region);
        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);

            DescribeSpotPriceHistoryRequest request = DescribeSpotPriceHistoryRequest.builder()
                    .instanceTypes(InstanceType.fromValue(instanceType))
                    .productDescriptions("Linux/UNIX")
                    .startTime(Instant.now().minusSeconds(1800)) // Last 30 mins
                    .endTime(Instant.now())
                    .build();

            Optional<SpotPrice> spotPrice = ec2Client.describeSpotPriceHistory(request).spotPriceHistory().stream()
                    .max(Comparator.comparing(SpotPrice::timestamp));

            if (spotPrice.isPresent()) {
                double price = Double.parseDouble(spotPrice.get().spotPrice());
                logger.debug("Found Spot price for {} in {}: ${}/hr", instanceType, region, price);
                return price;
            }
            logger.warn("No Spot price found for {} in {}", instanceType, region);
        } catch (Exception e) {
            logger.error("Failed to fetch hourly spot price for {} in {}: {}", instanceType, region, e.getMessage());
        }
        return 0.0;
    }

    /**
     * Gets the current hourly On-Demand price for an instance type.
     */
    @Cacheable(value = "hourlyOnDemandPrice", key = "#instanceType + '-' + #region")
    public double getHourlyOnDemandPrice(String instanceType, String region) {
        String regionDescription = getRegionDescription(region);
        logger.debug("Fetching On-Demand price for {} in {} ({})", instanceType, region, regionDescription);

        Filter regionFilter = Filter.builder().field("location").value(regionDescription).type("TERM_MATCH")
                .build();
        Filter instanceTypeFilter = Filter.builder().field("instanceType").value(instanceType).type("TERM_MATCH")
                .build();
        Filter tenancyFilter = Filter.builder().field("tenancy").value("Shared").type("TERM_MATCH").build();
        Filter osFilter = Filter.builder().field("operatingSystem").value("Linux").type("TERM_MATCH").build();
        Filter capacityFilter = Filter.builder().field("capacitystatus").value("Used").type("TERM_MATCH").build();
        Filter preinstalledSwFilter = Filter.builder().field("preInstalledSw").value("NA").type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, instanceTypeFilter, tenancyFilter, osFilter, capacityFilter,
                        preinstalledSwFilter)
                .build();

        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (!response.priceList().isEmpty()) {
                JsonNode priceList = objectMapper.readTree(response.priceList().get(0));
                JsonNode onDemandTerms = priceList.path("terms").path("OnDemand");

                if (!onDemandTerms.isMissingNode() && onDemandTerms.elements().hasNext()) {
                    JsonNode priceDimensions = onDemandTerms.elements().next().path("priceDimensions");
                    if (priceDimensions.elements().hasNext()) {
                        double price = priceDimensions.elements().next().path("pricePerUnit").path("USD").asDouble(0.0);
                        logger.debug("Found On-Demand price for {} in {}: ${}/hr", instanceType, region, price);
                        return price;
                    }
                }
            }
            logger.warn("No On-Demand price found for {} in {} ({})", instanceType, region, regionDescription);
        } catch (Exception e) {
            logger.error("Failed to fetch On-Demand price for {} in {}: {}", instanceType, region, e.getMessage());
        }
        return 0.0;
    }

    /**
     * Calculates hourly savings.
     */
    public double calculateHourlySavings(String onDemandType, String spotType, String region, CloudAccount account) {
        double odPrice = getHourlyOnDemandPrice(onDemandType, region);
        double spotPrice = getHourlySpotPrice(spotType, region, account);
        return Math.max(0.0, odPrice - spotPrice);
    }

    private String getRegionDescription(String regionCode) {
        Map<String, String> regionMap = Map.ofEntries(
                // US Regions
                Map.entry("us-east-1", "US East (N. Virginia)"),
                Map.entry("us-east-2", "US East (Ohio)"),
                Map.entry("us-west-1", "US West (N. California)"),
                Map.entry("us-west-2", "US West (Oregon)"),
                // EU Regions
                Map.entry("eu-west-1", "EU (Ireland)"),
                Map.entry("eu-west-2", "EU (London)"),
                Map.entry("eu-west-3", "EU (Paris)"),
                Map.entry("eu-central-1", "EU (Frankfurt)"),
                Map.entry("eu-central-2", "EU (Zurich)"),
                Map.entry("eu-north-1", "EU (Stockholm)"),
                Map.entry("eu-south-1", "EU (Milan)"),
                Map.entry("eu-south-2", "EU (Spain)"),
                // Asia Pacific Regions
                Map.entry("ap-south-1", "Asia Pacific (Mumbai)"),
                Map.entry("ap-south-2", "Asia Pacific (Hyderabad)"),
                Map.entry("ap-southeast-1", "Asia Pacific (Singapore)"),
                Map.entry("ap-southeast-2", "Asia Pacific (Sydney)"),
                Map.entry("ap-southeast-3", "Asia Pacific (Jakarta)"),
                Map.entry("ap-southeast-4", "Asia Pacific (Melbourne)"),
                Map.entry("ap-northeast-1", "Asia Pacific (Tokyo)"),
                Map.entry("ap-northeast-2", "Asia Pacific (Seoul)"),
                Map.entry("ap-northeast-3", "Asia Pacific (Osaka)"),
                Map.entry("ap-east-1", "Asia Pacific (Hong Kong)"),
                // Other Regions
                Map.entry("ca-central-1", "Canada (Central)"),
                Map.entry("ca-west-1", "Canada (Calgary)"),
                Map.entry("sa-east-1", "South America (São Paulo)"),
                Map.entry("me-south-1", "Middle East (Bahrain)"),
                Map.entry("me-central-1", "Middle East (UAE)"),
                Map.entry("af-south-1", "Africa (Cape Town)"),
                Map.entry("il-central-1", "Israel (Tel Aviv)"));
        return regionMap.getOrDefault(regionCode, regionCode);
    }
}
