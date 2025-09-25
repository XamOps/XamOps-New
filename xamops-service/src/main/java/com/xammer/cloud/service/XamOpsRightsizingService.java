package com.xammer.cloud.service;

import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import com.xammer.cloud.dto.ResourceDto; // ✅ CORRECTED: Imported the correct ResourceDto
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

    private final CloudListService cloudListService;
    private final MetricsService metricsService;
    private final Map<String, List<XamOpsRightsizingRecommendation>> recommendationsMap;

    public XamOpsRightsizingService(CloudListService cloudListService, MetricsService metricsService) {
        this.cloudListService = cloudListService;
        this.metricsService = metricsService;
        this.recommendationsMap = loadRecommendationsFromCsv();
    }

    /**
     * ✅ CORRECTED: Fully updated with the correct method and field names.
     */
    public List<XamOpsRightsizingRecommendation> getLiveRecommendations(String accountId) {
        // 1. Get all EC2 instances and filter for the correct service type
        List<ResourceDto> ec2Instances = cloudListService.getAllResources(cloudListService.getAccount(accountId), false).join()
                .stream()
                .filter(r -> "EC2 Instance".equalsIgnoreCase(r.getType())) // Assuming 'type' holds the service name
                .collect(Collectors.toList());

        if (ec2Instances.isEmpty()) {
            logger.info("No running EC2 instances found for account {}. No XamOps recommendations to generate.", accountId);
            return Collections.emptyList();
        }

        List<XamOpsRightsizingRecommendation> finalRecommendations = new ArrayList<>();

        // 2. For each instance, get utilization and find a matching recommendation
        for (ResourceDto instanceDto : ec2Instances) {
            String instanceId = instanceDto.getId(); // Use getId()
            String region = instanceDto.getRegion();
            // The instance type is in the 'details' map
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
            logger.info("Instance {} ({}) has max CPU utilization of {}%", instanceId, instanceType, String.format("%.2f", utilization));

            // 4. Find a matching recommendation from the CSV data
            findMatchingRecommendation(instanceType, utilization).ifPresent(finalRecommendations::add);
        }

        return finalRecommendations;
    }

    /**
     * Matches instance type and utilization against the pre-loaded CSV data.
     */
    private Optional<XamOpsRightsizingRecommendation> findMatchingRecommendation(String instanceType, double utilization) {
        if (!recommendationsMap.containsKey(instanceType.trim())) {
            return Optional.empty();
        }

        for (XamOpsRightsizingRecommendation rec : recommendationsMap.get(instanceType.trim())) {
            String[] range = rec.getLoadRange().replace("%", "").split("-");
            if (range.length == 2) {
                try {
                    double lowerBound = Double.parseDouble(range[0].trim());
                    double upperBound = Double.parseDouble(range[1].trim());
                    if (utilization >= lowerBound && utilization < upperBound) {
                        return Optional.of(rec);
                    }
                } catch (NumberFormatException e) {
                    logger.error("Could not parse load range '{}' for instance type {}", rec.getLoadRange(), instanceType);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Loads the entire CSV file into a Map for easy lookup.
     */
    private Map<String, List<XamOpsRightsizingRecommendation>> loadRecommendationsFromCsv() {
        try (InputStream inputStream = getClass().getResourceAsStream(CSV_FILE_PATH)) {
            if (inputStream == null) {
                logger.error("XamOps recommendations file not found at classpath: {}", CSV_FILE_PATH);
                return Collections.emptyMap();
            }

            try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim())
            ) {
                return csvParser.getRecords().stream()
                        .map(this::parseRecordToRecommendation)
                        .filter(Objects::nonNull)
                        .collect(Collectors.groupingBy(XamOpsRightsizingRecommendation::getCurrentInstance));
            }
        } catch (Exception e) {
            logger.error("Failed to read or parse XamOps recommendations CSV file.", e);
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
            logger.warn("Skipping record due to missing header. Record: {}", csvRecord.toString());
            return null;
        }
    }
}