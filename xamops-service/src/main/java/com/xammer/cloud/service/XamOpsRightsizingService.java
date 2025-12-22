package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import com.xammer.cloud.dto.ResourceDto;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypeOfferingsRequest;
import software.amazon.awssdk.services.ec2.model.InstanceTypeOffering;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class XamOpsRightsizingService {

    private static final Logger logger = LoggerFactory.getLogger(XamOpsRightsizingService.class);
    private static final String CSV_FILE_PATH = "/RightSizing-XamOps - Instances.csv";
    private static final String CACHE_KEY_PREFIX = "xamops-rightsizing-";

    private final CloudListService cloudListService;
    private final MetricsService metricsService;
    private final RedisCacheService redisCacheService;
    private final AwsClientProvider awsClientProvider; // Injected for AWS calls
    private final Map<String, List<XamOpsRightsizingRecommendation>> recommendationsMap;

    // Local cache to store available instance types per region to avoid repeated
    // API calls
    private final Map<String, Set<String>> regionAvailabilityCache = new ConcurrentHashMap<>();

    public XamOpsRightsizingService(
            CloudListService cloudListService,
            MetricsService metricsService,
            RedisCacheService redisCacheService,
            AwsClientProvider awsClientProvider) {
        this.cloudListService = cloudListService;
        this.metricsService = metricsService;
        this.redisCacheService = redisCacheService;
        this.awsClientProvider = awsClientProvider;
        this.recommendationsMap = loadRecommendationsFromCsv();
    }

    public List<XamOpsRightsizingRecommendation> getLiveRecommendations(String accountId, boolean forceRefresh) {
        String cacheKey = CACHE_KEY_PREFIX + accountId;

        if (!forceRefresh) {
            Optional<List<XamOpsRightsizingRecommendation>> cachedData = redisCacheService.get(cacheKey,
                    new TypeReference<List<XamOpsRightsizingRecommendation>>() {
                    });

            if (cachedData.isPresent()) {
                logger.info("✅ Retrieved {} XamOps recommendations for account {} from Redis cache",
                        cachedData.get().size(), accountId);
                return cachedData.get();
            }
        }

        logger.info("🔄 Generating fresh XamOps recommendations for account {}", accountId);

        // Get the account object once
        CloudAccount account = cloudListService.getAccount(accountId);

        List<ResourceDto> ec2Instances = cloudListService.getAllResources(account, false)
                .join()
                .stream()
                .filter(r -> "EC2 Instance".equalsIgnoreCase(r.getType()))
                .collect(Collectors.toList());

        if (ec2Instances.isEmpty()) {
            logger.info("No running EC2 instances found for account {}. No XamOps recommendations to generate.",
                    accountId);
            return Collections.emptyList();
        }

        // ✅ Use a Set to track processed instances and prevent duplicates
        Set<String> processedInstances = new HashSet<>();
        List<XamOpsRightsizingRecommendation> finalRecommendations = new ArrayList<>();

        for (ResourceDto instanceDto : ec2Instances) {
            String instanceId = instanceDto.getId();
            String region = instanceDto.getRegion();
            String instanceType = instanceDto.getDetails().get("Type");

            // ✅ Skip if already processed
            if (processedInstances.contains(instanceId)) {
                logger.debug("Instance {} already processed, skipping duplicate", instanceId);
                continue;
            }

            if (instanceType == null) {
                logger.warn("Instance type is null for instance {}. Skipping.", instanceId);
                continue;
            }

            Optional<Double> maxCpu = metricsService.getMaxCpuUtilization(accountId, instanceId, region, 14);

            if (maxCpu.isEmpty()) {
                logger.warn("Could not retrieve CPU utilization for instance {}. Skipping.", instanceId);
                continue;
            }

            double utilization = maxCpu.get();
            logger.info("Instance {} ({}) has max CPU utilization of {}%",
                    instanceId, instanceType, String.format("%.2f", utilization));

            // ✅ Find matching recommendation and VALIDATE AVAILABILITY
            findMatchingRecommendation(instanceType, utilization, instanceId, region)
                    .ifPresent(rec -> {
                        // Validate if the recommended types exist in this region
                        validateRecommendationAvailability(account, region, rec);

                        finalRecommendations.add(rec);
                        processedInstances.add(instanceId); // Mark as processed
                        logger.debug("Added recommendation for instance {}", instanceId);
                    });
        }

        // ✅ Additional deduplication check based on instanceId
        List<XamOpsRightsizingRecommendation> uniqueRecommendations = finalRecommendations.stream()
                .collect(Collectors.toMap(
                        XamOpsRightsizingRecommendation::getInstanceId,
                        rec -> rec,
                        (existing, replacement) -> existing // Keep first occurrence
                ))
                .values()
                .stream()
                .collect(Collectors.toList());

        logger.info("✅ Generated {} unique recommendations (removed {} duplicates)",
                uniqueRecommendations.size(),
                finalRecommendations.size() - uniqueRecommendations.size());

        if (!uniqueRecommendations.isEmpty()) {
            redisCacheService.put(cacheKey, uniqueRecommendations, 60);
            logger.info("✅ Cached {} XamOps recommendations for account {}",
                    uniqueRecommendations.size(), accountId);
        }

        return uniqueRecommendations;
    }

    /**
     * Checks if the recommended instances are available in the target AWS region.
     * Updates the recommendation object text if not available.
     */
    private void validateRecommendationAvailability(CloudAccount account, String region,
            XamOpsRightsizingRecommendation rec) {
        // Fetch available types for this region (cached)
        Set<String> availableTypes = getAvailableInstanceTypes(account, region);

        // Check Intel Recommendation
        String intelString = rec.getIntelRecommendation();
        if (intelString != null && !intelString.toLowerCase().startsWith("stay")) {
            String proposedType = extractInstanceType(intelString);
            if (proposedType != null && !availableTypes.contains(proposedType)) {
                rec.setIntelRecommendation("Not available in " + region);
            }
        }

        // Check AMD Recommendation
        String amdString = rec.getAmdRecommendation();
        if (amdString != null && !amdString.toLowerCase().startsWith("stay")) {
            String proposedType = extractInstanceType(amdString);
            if (proposedType != null && !availableTypes.contains(proposedType)) {
                rec.setAmdRecommendation("Not available in " + region);
            }
        }
    }

    /**
     * Extracts "t3.medium" from strings like "t3.medium ($0.04/h)" or "t3.medium"
     */
    private String extractInstanceType(String recommendationString) {
        if (recommendationString == null || recommendationString.isBlank())
            return null;
        // Split by space, comma or bracket to get the first clean token
        String[] parts = recommendationString.split("[\\s,(]+");
        if (parts.length > 0) {
            return parts[0].trim();
        }
        return null;
    }

    /**
     * Fetches all instance types offered in a specific region.
     * Uses a local cache to avoid spamming the AWS API for every instance.
     */
    private Set<String> getAvailableInstanceTypes(CloudAccount account, String region) {
        if (regionAvailabilityCache.containsKey(region)) {
            return regionAvailabilityCache.get(region);
        }

        logger.info("Fetching available instance types for region: {}", region);
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
            Set<String> availableTypes = new HashSet<>();
            String nextToken = null;

            do {
                var request = DescribeInstanceTypeOfferingsRequest.builder()
                        .locationType("region")
                        .filters(f -> f.name("location").values(region))
                        .nextToken(nextToken)
                        .build();

                var response = ec2.describeInstanceTypeOfferings(request);

                response.instanceTypeOfferings().stream()
                        .map(InstanceTypeOffering::instanceTypeAsString)
                        .forEach(availableTypes::add);

                nextToken = response.nextToken();
            } while (nextToken != null);

            regionAvailabilityCache.put(region, availableTypes);
            logger.info("Cached {} available instance types for region {}", availableTypes.size(), region);
            return availableTypes;

        } catch (Exception e) {
            logger.error("Failed to fetch instance type offerings for region {}", region, e);
            return Collections.emptySet(); // Fail safe
        }
    }

    public List<XamOpsRightsizingRecommendation> getLiveRecommendations(String accountId) {
        return getLiveRecommendations(accountId, false);
    }

    private Optional<XamOpsRightsizingRecommendation> findMatchingRecommendation(
            String instanceType, double utilization, String instanceId, String region) {

        if (!recommendationsMap.containsKey(instanceType.trim())) {
            logger.debug("No recommendations found for instance type: {}", instanceType);
            return Optional.empty();
        }

        // ✅ Return ONLY the first matching recommendation
        for (XamOpsRightsizingRecommendation rec : recommendationsMap.get(instanceType.trim())) {
            String[] range = rec.getLoadRange().replace("%", "").split("-");
            if (range.length == 2) {
                try {
                    double lowerBound = Double.parseDouble(range[0].trim());
                    double upperBound = Double.parseDouble(range[1].trim());

                    if (utilization >= lowerBound && utilization < upperBound) {
                        return Optional.of(XamOpsRightsizingRecommendation.builder()
                                .currentInstance(rec.getCurrentInstance())
                                .instanceId(instanceId)
                                .currentUtilization(String.format("%.2f%%", utilization))
                                .loadRange(rec.getLoadRange())
                                .intelRecommendation(rec.getIntelRecommendation())
                                .amdRecommendation(rec.getAmdRecommendation())
                                .projectedMaxUtil(rec.getProjectedMaxUtil())
                                .approxCostSavings(rec.getApproxCostSavings())
                                .reason(rec.getReason())
                                .build());
                    }
                } catch (NumberFormatException e) {
                    logger.error("Could not parse load range '{}' for instance type {}",
                            rec.getLoadRange(), instanceType, e);
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, List<XamOpsRightsizingRecommendation>> loadRecommendationsFromCsv() {
        try (InputStream inputStream = getClass().getResourceAsStream(CSV_FILE_PATH)) {
            if (inputStream == null) {
                logger.error("❌ XamOps recommendations file not found at classpath: {}", CSV_FILE_PATH);
                return Collections.emptyMap();
            }

            try (
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    CSVParser csvParser = new CSVParser(reader,
                            CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim())) {
                Map<String, List<XamOpsRightsizingRecommendation>> map = csvParser.getRecords().stream()
                        .map(this::parseRecordToRecommendation)
                        .filter(Objects::nonNull)
                        .collect(Collectors.groupingBy(XamOpsRightsizingRecommendation::getCurrentInstance));

                logger.info("✅ Loaded {} instance types with XamOps recommendations from CSV", map.size());
                return map;
            }
        } catch (Exception e) {
            logger.error("❌ Failed to read or parse XamOps recommendations CSV file.", e);
            return Collections.emptyMap();
        }
    }

    private XamOpsRightsizingRecommendation parseRecordToRecommendation(CSVRecord csvRecord) {
        try {
            return XamOpsRightsizingRecommendation.builder()
                    .currentInstance(csvRecord.get("Current Instance"))
                    .loadRange(csvRecord.get("Load Range (Max U%)"))
                    .intelRecommendation(csvRecord.get("Intel Recommendation"))
                    .amdRecommendation(csvRecord.get("AMD Recommendation"))
                    .projectedMaxUtil(csvRecord.get("Projected Max Util"))
                    .approxCostSavings(csvRecord.get("Approx. Cost Savings (Intel/AMD)"))
                    .reason(csvRecord.get("Reason (with AWS Best Practices)"))
                    .build();
        } catch (IllegalArgumentException e) {
            logger.warn("⚠️ Skipping record due to missing header. Record: {}", csvRecord.toString());
            return null;
        }
    }

    public void clearCache(String accountId) {
        String cacheKey = CACHE_KEY_PREFIX + accountId;
        redisCacheService.evict(cacheKey);
        logger.info("🗑️ Cleared XamOps recommendations cache for account {}", accountId);
    }
}