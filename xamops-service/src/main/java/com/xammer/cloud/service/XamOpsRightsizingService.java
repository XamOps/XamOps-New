package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import com.xammer.cloud.dto.ResourceDto;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class XamOpsRightsizingService {

    private static final Logger logger = LoggerFactory.getLogger(XamOpsRightsizingService.class);
    private static final String CSV_FILE_PATH = "/RightSizing-XamOps - Instances.csv";
    private static final String CACHE_KEY_PREFIX = "xamops-rightsizing-";

    private final CloudListService cloudListService;
    private final MetricsService metricsService;
    private final RedisCacheService redisCacheService;
    private final Map<String, List<XamOpsRightsizingRecommendation>> recommendationsMap;

    public XamOpsRightsizingService(
            CloudListService cloudListService,
            MetricsService metricsService,
            RedisCacheService redisCacheService) {
        this.cloudListService = cloudListService;
        this.metricsService = metricsService;
        this.redisCacheService = redisCacheService;
        this.recommendationsMap = loadRecommendationsFromCsv();
    }

    /**
     * Get XamOps rightsizing recommendations with Redis caching.
     */
    public List<XamOpsRightsizingRecommendation> getLiveRecommendations(String accountId, boolean forceRefresh) {
        String cacheKey = CACHE_KEY_PREFIX + accountId;

        // Try to get from cache if not forcing refresh
        if (!forceRefresh) {
            Optional<List<XamOpsRightsizingRecommendation>> cachedData =
                    redisCacheService.get(cacheKey, new TypeReference<List<XamOpsRightsizingRecommendation>>() {});

            if (cachedData.isPresent()) {
                logger.info("✅ Retrieved {} XamOps recommendations for account {} from Redis cache",
                        cachedData.get().size(), accountId);
                return cachedData.get();
            }
        }

        logger.info("🔄 Generating fresh XamOps recommendations for account {}", accountId);

        // 1. Get all EC2 instances
        List<ResourceDto> ec2Instances = cloudListService.getAllResources(
                        cloudListService.getAccount(accountId), false)
                .join()
                .stream()
                .filter(r -> "EC2 Instance".equalsIgnoreCase(r.getType()))
                .collect(Collectors.toList());

        if (ec2Instances.isEmpty()) {
            logger.info("No running EC2 instances found for account {}. No XamOps recommendations to generate.", accountId);
            return Collections.emptyList();
        }

        List<XamOpsRightsizingRecommendation> finalRecommendations = new ArrayList<>();

        // 2. For each instance, get utilization and find matching recommendation
        for (ResourceDto instanceDto : ec2Instances) {
            String instanceId = instanceDto.getId();
            String region = instanceDto.getRegion();
            String instanceType = instanceDto.getDetails().get("Type");

            if (instanceType == null) {
                logger.warn("Instance type is null for instance {}. Skipping.", instanceId);
                continue;
            }

            // 3. Get max CPU utilization over the last 14 days
            Optional<Double> maxCpu = metricsService.getMaxCpuUtilization(accountId, instanceId, region, 14);

            if (maxCpu.isEmpty()) {
                logger.warn("Could not retrieve CPU utilization for instance {}. Skipping.", instanceId);
                continue;
            }

            double utilization = maxCpu.get();
            logger.info("Instance {} ({}) has max CPU utilization of {}%",
                    instanceId, instanceType, String.format("%.2f", utilization));

            // 4. Find matching recommendation
            findMatchingRecommendation(instanceType, utilization, instanceId, region)
                    .ifPresent(finalRecommendations::add);
        }

        // 5. Cache the results
        if (!finalRecommendations.isEmpty()) {
            redisCacheService.put(cacheKey, finalRecommendations);
            logger.info("✅ Cached {} XamOps recommendations for account {}",
                    finalRecommendations.size(), accountId);
        }

        return finalRecommendations;
    }

    /**
     * Overloaded method for backward compatibility
     */
    public List<XamOpsRightsizingRecommendation> getLiveRecommendations(String accountId) {
        return getLiveRecommendations(accountId, false);
    }

    /**
     * Find matching recommendation from CSV data and enrich with instance details
     */
    private Optional<XamOpsRightsizingRecommendation> findMatchingRecommendation(
            String instanceType, double utilization, String instanceId, String region) {

        if (!recommendationsMap.containsKey(instanceType.trim())) {
            logger.debug("No recommendations found for instance type: {}", instanceType);
            return Optional.empty();
        }

        for (XamOpsRightsizingRecommendation rec : recommendationsMap.get(instanceType.trim())) {
            String[] range = rec.getLoadRange().replace("%", "").split("-");
            if (range.length == 2) {
                try {
                    double lowerBound = Double.parseDouble(range[0].trim());
                    double upperBound = Double.parseDouble(range[1].trim());

                    if (utilization >= lowerBound && utilization < upperBound) {
                        // Create enriched recommendation with instance details
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

    /**
     * Load CSV recommendations into memory at startup
     */
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
                            CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim())
            ) {
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

    /**
     * Parse CSV record to recommendation object
     */
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

    /**
     * Clear cache for a specific account
     */
    public void clearCache(String accountId) {
        String cacheKey = CACHE_KEY_PREFIX + accountId;
        redisCacheService.evict(cacheKey);
        logger.info("🗑️ Cleared XamOps recommendations cache for account {}", accountId);
    }
}
