package com.xammer.cloud.service.calculator.aws.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class ComputePricingService {

    private static final Logger logger = LoggerFactory.getLogger(ComputePricingService.class);
    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final ObjectMapper objectMapper;

    public ComputePricingService(AwsClientProvider awsClientProvider,
                                 CloudAccountRepository cloudAccountRepository,
                                 ObjectMapper objectMapper) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getEc2InstancePrices(String awsAccountId, String region, String operatingSystem) {
        PricingClient pricingClient = awsClientProvider.getPricingClient();
        List<Map<String, Object>> instances = new ArrayList<>();

        try {
            List<Filter> filters = new ArrayList<>();
            filters.add(Filter.builder().field("serviceCode").type(FilterType.TERM_MATCH).value("AmazonEC2").build());
            filters.add(Filter.builder().field("location").type(FilterType.TERM_MATCH).value(regionToLocation(region)).build());
            filters.add(Filter.builder().field("operatingSystem").type(FilterType.TERM_MATCH).value(operatingSystem).build());
            filters.add(Filter.builder().field("preInstalledSw").type(FilterType.TERM_MATCH).value("NA").build());
            filters.add(Filter.builder().field("tenancy").type(FilterType.TERM_MATCH).value("Shared").build());
            filters.add(Filter.builder().field("capacitystatus").type(FilterType.TERM_MATCH).value("Used").build());

            GetProductsResponse response = pricingClient.getProducts(GetProductsRequest.builder()
                    .serviceCode("AmazonEC2")
                    .filters(filters)
                    .formatVersion("aws_v1")
                    .maxResults(100)
                    .build());

            for (String priceJson : response.priceList()) {
                Map<String, Object> parsed = simplifyEc2PriceJson(priceJson);
                if (parsed != null) instances.add(parsed);
            }
        } catch (Exception e) {
            logger.error("❌ AWS API Error for {}. Using fallback.", region);
            return getFallbackInstanceTypes(region, operatingSystem);
        }

        if (instances.isEmpty()) return getFallbackInstanceTypes(region, operatingSystem);

        Map<String, Object> result = new HashMap<>();
        result.put("instances", instances);
        result.put("source", "AWS Pricing API");
        return result;
    }

    public Map<String, Object> getComprehensiveEc2Estimate(
            String awsAccountId, String region, String instanceType, String operatingSystem,
            int instanceCount, int hoursPerMonth, int storageGB, String storageType, int dataTransferOutGB) {

        double instanceHourlyRate = getInstanceHourlyRateFromAPI(awsAccountId, region, instanceType, operatingSystem);
        double computeMonthly = instanceHourlyRate * instanceCount * hoursPerMonth;
        
        Map<String, Object> storageCosts = calculateEbsStorageCost(region, storageType, storageGB);
        double storageMonthly = ((Number) storageCosts.get("totalMonthly")).doubleValue() * instanceCount;
        double transferMonthly = calculateDataTransferCost(dataTransferOutGB);

        Map<String, Object> result = new HashMap<>();
        result.put("totalMonthly", round(computeMonthly + storageMonthly + transferMonthly, 2));
        
        Map<String, Object> compute = new HashMap<>();
        compute.put("instanceType", instanceType);
        compute.put("monthlyCost", round(computeMonthly, 2));
        compute.put("details", instanceType + " x" + instanceCount);
        result.put("compute", compute);

        Map<String, Object> storage = new HashMap<>();
        storage.put("gb", (double) storageGB);
        storage.put("monthlyCost", round(storageMonthly, 2));
        result.put("storage", storage);

        result.put("pricingModels", calculatePricingModels(computeMonthly, storageMonthly, transferMonthly, hoursPerMonth));
        return result;
    }

    private double getInstanceHourlyRateFromAPI(String acc, String reg, String type, String os) {
        try {
            Map<String, Object> prices = getEc2InstancePrices(acc, reg, os);
            List<Map<String, Object>> insts = (List<Map<String, Object>>) prices.get("instances");
            return insts.stream()
                    .filter(i -> type.equals(i.get("instanceType")))
                    .findFirst()
                    .map(i -> (Double) i.get("pricePerHour"))
                    .orElse(0.10); // Safe fallback
        } catch (Exception e) { return 0.10; }
    }

    private String regionToLocation(String region) {
        Map<String, String> map = new HashMap<>();
        map.put("us-east-1", "US East (N. Virginia)");
        map.put("us-east-2", "US East (Ohio)");
        map.put("ap-south-1", "Asia Pacific (Mumbai)");
        map.put("ap-southeast-1", "Asia Pacific (Singapore)");
        map.put("eu-central-1", "EU (Frankfurt)");
        map.put("eu-west-1", "EU (Ireland)");
        return map.getOrDefault(region, "US East (N. Virginia)");
    }

    private Map<String, Object> simplifyEc2PriceJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode attr = root.path("product").path("attributes");
            String type = attr.path("instanceType").asText("");
            if (type.isEmpty()) return null;

            Map<String, Object> map = new HashMap<>();
            map.put("instanceType", type);
            map.put("description", type + " - " + attr.path("vcpu").asText() + " vCPU, " + attr.path("memory").asText());
            
            JsonNode od = root.path("terms").path("OnDemand");
            String firstKey = od.fieldNames().next();
            JsonNode priceDim = od.path(firstKey).path("priceDimensions");
            String priceKey = priceDim.fieldNames().next();
            map.put("pricePerHour", priceDim.path(priceKey).path("pricePerUnit").path("USD").asDouble());
            return map;
        } catch (Exception e) { return null; }
    }

    private Map<String, Object> getFallbackInstanceTypes(String region, String os) {
        List<Map<String, Object>> fallbacks = new ArrayList<>();
        String[] types = {"t3.medium", "m5.large", "c5.large"};
        for (String type : types) {
            Map<String, Object> map = new HashMap<>();
            map.put("instanceType", type);
            map.put("description", type + " (Standard Fallback)");
            map.put("pricePerHour", 0.05);
            fallbacks.add(map);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("instances", fallbacks);
        return result;
    }

    private Map<String, Object> calculateEbsStorageCost(String region, String type, int gb) {
        double rate = type.equals("gp3") ? 0.08 : 0.10;
        Map<String, Object> res = new HashMap<>();
        res.put("totalMonthly", gb * rate);
        res.put("pricePerGB", rate);
        return res;
    }

    private double calculateDataTransferCost(int gb) { return gb * 0.09; }

    private List<Map<String, Object>> calculatePricingModels(double c, double s, double t, int h) {
        List<Map<String, Object>> models = new ArrayList<>();
        Map<String, Object> od = new HashMap<>();
        od.put("name", "On-Demand");
        od.put("monthlyTotal", round(c + s + t, 2));
        od.put("savings", "0%");
        models.add(od);
        return models;
    }

    private double round(double value, int places) {
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }
}