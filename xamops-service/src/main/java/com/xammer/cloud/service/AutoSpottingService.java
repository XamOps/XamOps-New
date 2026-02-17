package com.xammer.cloud.service;

import com.xammer.cloud.domain.AutoSpottingSettings;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.autospotting.*;
import com.xammer.cloud.dto.autospotting.EventsResponse.EventsSummary;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.GetReservationCoverageResponse;
import software.amazon.awssdk.services.costexplorer.model.GetSavingsPlansCoverageResponse;

@Service
public class AutoSpottingService {

    private static final Logger logger = LoggerFactory.getLogger(AutoSpottingService.class);
    private static final String ASG_NAME_KEY = "AutoScalingGroupName";

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final SpotPricingService spotPricingService;
    private final SpotOptimizationEventRepository eventRepository;
    private final DailySpotSavingsRepository savingsRepository;
    private final com.xammer.cloud.repository.AutoSpottingSettingsRepository settingsRepository;

    // Cache for region discovery
    private final Map<String, CachedRegionInfo> regionCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MILLIS = 15 * 60 * 1000; // 15 minutes

    @Value("${autospotting.dynamodb.table-name}")
    private String tableName;

    @Value("${autospotting.tag.key:spot-enabled}")
    private String tagKey;

    @Value("${autospotting.tag.value:true}")
    private String tagValue;

    @Value("${autospotting.metric.namespace:AutoSpotting}")
    private String metricNamespace;

    @Autowired
    public AutoSpottingService(
            AwsClientProvider awsClientProvider,
            CloudAccountRepository cloudAccountRepository,
            SpotPricingService spotPricingService,
            SpotOptimizationEventRepository eventRepository,
            DailySpotSavingsRepository savingsRepository,
            com.xammer.cloud.repository.AutoSpottingSettingsRepository settingsRepository) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.spotPricingService = spotPricingService;
        this.eventRepository = eventRepository;
        this.savingsRepository = savingsRepository;
        this.settingsRepository = settingsRepository;
        logger.info("AutoSpottingService initialized with table={} tagKey={} tagValue={} namespace={}",
                tableName, tagKey, tagValue, metricNamespace);
    }

    // ================= HELPER METHODS =================

    /**
     * Resolve CloudAccount by AWS account ID
     * ✅ UPDATED: Handles leading zero mismatch (e.g. input 157... finds DB 0157...)
     */
    private CloudAccount getByAwsAccountId(Long awsAccountId) {
        String idStr = String.valueOf(awsAccountId);
        logger.debug("Resolving CloudAccount for awsAccountId={}", idStr);

        // 1. Try finding by the direct numeric string (e.g., "15764906357")
        Optional<CloudAccount> account = cloudAccountRepository.findByAwsAccountId(idStr).stream().findFirst();

        if (account.isPresent()) {
            return account.get();
        }

        // 2. If not found, try padding with leading zero to 12 digits (e.g.,
        // "015764906357")
        // AWS Account IDs are always 12 digits.
        String paddedId = String.format("%012d", awsAccountId);
        if (!paddedId.equals(idStr)) {
            logger.debug("Account not found as '{}'. Trying padded ID: '{}'", idStr, paddedId);
            account = cloudAccountRepository.findByAwsAccountId(paddedId).stream().findFirst();
            if (account.isPresent()) {
                logger.debug("✓ Found account using padded ID: {}", paddedId);
                return account.get();
            }
        }

        // 3. Fail if neither is found
        logger.error("No CloudAccount found for awsAccountId={} or padded ID={}", idStr, paddedId);
        throw new RuntimeException("Account not found for AWS account " + idStr);
    }

    // ================= REGISTRATION =================

    /**
     * Registers the customer AWS account ID into the Main Account's DynamoDB table.
     * AND updates the local PostgreSQL settings to enabled=true.
     */
    public void registerCustomerAccount(Long cloudAccountId) {
        logger.info("=== AutoSpotting Registration Started ===");
        logger.info("Customer AWS account ID to register: {}", cloudAccountId);

        CloudAccount account = getByAwsAccountId(cloudAccountId);
        logger.info("Resolved CloudAccount: id={}, awsAccountId={}, client={}",
                account.getId(), account.getAwsAccountId(),
                account.getClient() != null ? account.getClient().getName() : "null");

        try {
            // 1. Update PostgreSQL Settings (Critical for Listener)
            AutoSpottingSettings settings = settingsRepository.findByAccountId(account.getId())
                    .orElse(new AutoSpottingSettings(account));
            settings.setEnabled(true);
            settingsRepository.save(settings);
            logger.info("✓ Updated PostgreSQL settings: enabled=true for account {}", account.getAwsAccountId());

            // 2. Legacy DynamoDB Registration (Optional but kept for compatibility)
            String hostAccount = awsClientProvider.getHostAccount();
            logger.info("Host/Main AWS account ID: {}", hostAccount);
            logger.info("Target DynamoDB table name: '{}'", tableName);

            logger.info("Obtaining HOST DynamoDB client...");
            DynamoDbClient ddb = awsClientProvider.getHostDynamoDbClient();
            logger.info("Successfully obtained HOST DynamoDB client");

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("AccountId", AttributeValue.builder().s(account.getAwsAccountId()).build());
            item.put("RegisteredAt", AttributeValue.builder().s(Instant.now().toString()).build());
            if (account.getClient() != null && account.getClient().getName() != null) {
                item.put("ClientName", AttributeValue.builder().s(account.getClient().getName()).build());
            }

            logger.info("Calling DynamoDB PutItem on table '{}' in host account {}", tableName, hostAccount);
            ddb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());

            logger.info("✓ Successfully registered account {} in AutoSpotting DynamoDB table '{}'",
                    account.getAwsAccountId(), tableName);
            logger.info("=== AutoSpotting Registration Complete ===");
        } catch (Exception e) {
            logger.error("=== AutoSpotting Registration FAILED ===");
            logger.error("Failed to register account {} in AutoSpotting DB: {}",
                    cloudAccountId, e.getMessage(), e);
            throw new RuntimeException("Failed to enable AutoSpotting: " + e.getMessage(), e);
        }
    }

    // ================= COST & SAVINGS DATA (API-BASED, NO ATHENA)
    // =================

    /**
     * Get current cost data using AWS SDK + SpotPricingService (in-house)
     */
    public CostResponse getCostData(Long cloudAccountId) {
        logger.info("=== Fetching Cost Data (In-House SDK) ===");
        logger.info("CloudAccountId={}", cloudAccountId);

        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            List<AutoSpottingGroupDto> asgs = listAsgsAllRegions(cloudAccountId);

            List<ASGCostData> asgCostDataList = new ArrayList<>();
            double totalCurrentHourlyCost = 0.0;
            double totalOnDemandHourlyCost = 0.0;
            double totalActualSavings = 0.0;
            double totalPotentialSavings = 0.0;

            for (AutoSpottingGroupDto asgDto : asgs) {
                ASGCostData costData = buildAsgCostData(account, asgDto);
                asgCostDataList.add(costData);

                totalCurrentHourlyCost += costData.getCurrentHourlyCost() != null ? costData.getCurrentHourlyCost() : 0.0;
                totalOnDemandHourlyCost += costData.getOndemandHourlyCost() != null ? costData.getOndemandHourlyCost() : 0.0;
                totalActualSavings += costData.getActualHourlySavings() != null ? costData.getActualHourlySavings() : 0.0;
                totalPotentialSavings += costData.getPotentialHourlySavings() != null ? costData.getPotentialHourlySavings() : 0.0;
            }

            CostResponse response = new CostResponse();
            response.setAsgs(asgCostDataList);
            response.setTimestamp(Instant.now().toString());

            CostResponse.CostSummary summary = new CostResponse.CostSummary();
            summary.setTotalAsgCount(asgs.size());
            summary.setAutospottingEnabledCount((int) asgs.stream().filter(a -> a.isEnabled).count());
            summary.setTotalCurrentHourlyCost(totalCurrentHourlyCost);
            summary.setTotalOndemandHourlyCost(totalOnDemandHourlyCost);
            summary.setTotalActualSavings(totalActualSavings);
            summary.setTotalPotentialSavings(totalPotentialSavings);
            response.setSummary(summary);

            logger.info("Cost data built: {} ASGs, ${}/hr current, ${}/hr savings",
                    asgs.size(),
                    String.format("%.4f", totalCurrentHourlyCost),
                    String.format("%.4f", totalActualSavings));

            return response;
        } catch (Exception e) {
            logger.error("Failed to build cost data from SDK: {}", e.getMessage(), e);
            return buildCostResponseFromAwsSdk(account);
        }
    }

    /**
     * Get historical cost data from PostgreSQL DailySpotSavings
     */
    public HistoryResponse getCostHistory(Long cloudAccountId, String start, String end, String interval) {
        logger.info("Fetching cost history from PostgreSQL for cloudAccountId={}", cloudAccountId);
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            LocalDate startDate = (start != null && !start.isEmpty())
                    ? LocalDate.parse(start.substring(0, 10))
                    : LocalDate.now().minusDays(30);
            LocalDate endDate = (end != null && !end.isEmpty())
                    ? LocalDate.parse(end.substring(0, 10))
                    : LocalDate.now();

            List<DailySpotSavings> savingsHistory = savingsRepository
                    .findByAccountIdAndDateBetweenOrderByDateAsc(account.getId(), startDate, endDate);

            HistoryResponse response = new HistoryResponse();
            response.setInterval(interval != null ? interval : "daily");

            List<HistoryResponse.DataPoint> dataPoints = new java.util.ArrayList<>(savingsHistory.stream()
                    .map(savings -> {
                        HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
                        dp.setTimestamp(savings.getDate().toString());
                        dp.setActualHourlySavings(savings.getAmountSaved());
                        dp.setSpotInstanceCount(savings.getSwapCount());
                        dp.setCurrentHourlyCost(0.0);
                        dp.setOndemandHourlyCost(0.0);
                        dp.setPotentialHourlySavings(0.0);
                        dp.setOndemandInstanceCount(0);
                        return dp;
                    })
                    .collect(Collectors.toList()));

            // If no DB history yet, synthesize a data point from live AWS SDK cost data
            // so the chart shows something meaningful right from the start.
            if (dataPoints.isEmpty()) {
                try {
                    CostResponse liveCost = getCostData(cloudAccountId);
                    if (liveCost != null && liveCost.getSummary() != null) {
                        CostResponse.CostSummary s = liveCost.getSummary();
                        double actualSavings = s.getTotalActualSavings() != null ? s.getTotalActualSavings() : 0.0;
                        double potentialSavings = s.getTotalPotentialSavings() != null ? s.getTotalPotentialSavings() : 0.0;
                        double currentCost = s.getTotalCurrentHourlyCost() != null ? s.getTotalCurrentHourlyCost() : 0.0;
                        if (actualSavings > 0 || potentialSavings > 0) {
                            HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
                            dp.setTimestamp(java.time.LocalDate.now().toString());
                            dp.setActualHourlySavings(actualSavings);
                            dp.setPotentialHourlySavings(potentialSavings);
                            dp.setCurrentHourlyCost(currentCost);
                            dp.setOndemandHourlyCost(0.0);
                            dp.setSpotInstanceCount(0);
                            dp.setOndemandInstanceCount(0);
                            dataPoints.add(dp);
                            logger.info("Synthesized 1 history data point from live cost data (actualSavings={})", actualSavings);
                        }
                    }
                } catch (Exception ex) {
                    logger.warn("Could not synthesize history from live cost data: {}", ex.getMessage());
                }
            }

            response.setDataPoints(dataPoints);

            HistoryResponse.HistorySummary summary = new HistoryResponse.HistorySummary();
            double totalSavings = dataPoints.stream()
                    .mapToDouble(dp -> dp.getActualHourlySavings() != null ? dp.getActualHourlySavings() : 0.0)
                    .sum();
            double avgSavings = dataPoints.isEmpty() ? 0.0 : totalSavings / dataPoints.size();
            double totalPotential = dataPoints.stream()
                    .mapToDouble(dp -> dp.getPotentialHourlySavings() != null ? dp.getPotentialHourlySavings() : 0.0)
                    .sum();
            summary.setTotalActualSavings(totalSavings);
            summary.setTotalPotentialSavings(totalPotential);
            summary.setAverageHourlyCost(0.0);
            summary.setAverageHourlySavings(avgSavings);
            response.setSummary(summary);

            logger.info("Retrieved {} historical data points (DB: {})", dataPoints.size(), savingsHistory.size());
            return response;

        } catch (Exception e) {
            logger.warn("Failed to fetch cost history from PostgreSQL: {}", e.getMessage());
            return createEmptyHistoryResponse();
        }
    }

    /**
     * Get monthly savings summary using API data
     */
    public DashboardData.SavingsSummary getSavingsMetrics(Long cloudAccountId) {
        logger.info("=== Calculating Savings Metrics ===");
        logger.info("CloudAccountId={}", cloudAccountId);

        try {
            // Get current cost data from API
            CostResponse costData = getCostData(cloudAccountId);

            if (costData == null || costData.getSummary() == null) {
                logger.warn("No cost data available, returning zeros");
                return createEmptySavingsSummary();
            }

            CostResponse.CostSummary summary = costData.getSummary();

            // Null-safe extraction of cost values
            double currentHourlyCost = summary.getTotalCurrentHourlyCost() != null
                    ? summary.getTotalCurrentHourlyCost()
                    : 0.0;
            double onDemandHourlyCost = summary.getTotalOndemandHourlyCost() != null
                    ? summary.getTotalOndemandHourlyCost()
                    : 0.0;
            double actualHourlySavings = summary.getTotalActualSavings() != null
                    ? summary.getTotalActualSavings()
                    : 0.0;
            double potentialHourlySavings = summary.getTotalPotentialSavings() != null
                    ? summary.getTotalPotentialSavings()
                    : 0.0;

            // Calculate monthly values (multiply hourly by 730 hours/month)
            double currentMonthlyCost = currentHourlyCost * 730;
            double onDemandMonthlyCost = onDemandHourlyCost * 730;
            double actualMonthlySavings = actualHourlySavings * 730;
            double potentialMonthlySavings = potentialHourlySavings * 730;

            logger.info("✓ Savings calculated:");
            logger.info("  - Current cost: ${}/hr → ${}/mo",
                    String.format("%.4f", currentHourlyCost),
                    String.format("%.2f", currentMonthlyCost));
            logger.info("  - Actual savings: ${}/hr → ${}/mo",
                    String.format("%.4f", actualHourlySavings),
                    String.format("%.2f", actualMonthlySavings));
            logger.info("  - Potential savings: ${}/hr → ${}/mo",
                    String.format("%.4f", potentialHourlySavings),
                    String.format("%.2f", potentialMonthlySavings));

            return DashboardData.SavingsSummary.builder()
                    .currentMonthlyCost(currentMonthlyCost)
                    .onDemandMonthlyCost(onDemandMonthlyCost)
                    .actualMonthlySavings(actualMonthlySavings)
                    .potentialMonthlySavings(potentialMonthlySavings)
                    .totalAsgs(summary.getTotalAsgCount() != null ? summary.getTotalAsgCount() : 0)
                    .enabledAsgs(
                            summary.getAutospottingEnabledCount() != null ? summary.getAutospottingEnabledCount() : 0)
                    .build();

        } catch (Exception e) {
            logger.error("Failed to calculate savings metrics: {}", e.getMessage(), e);
            return createEmptySavingsSummary();
        }
    }

    // ================= ASG LISTING (API + AWS SDK HYBRID) =================

    /**
     * Lists ASGs with cost data using AWS SDK + SpotPricingService (in-house)
     */
    public List<AutoSpottingGroupDto> listAsgsAllRegionsWithCosts(Long cloudAccountId) {
        logger.info("=== Listing ASGs with Cost Data (In-House SDK) ===");
        logger.info("CloudAccountId={}", cloudAccountId);

        CloudAccount account = getByAwsAccountId(cloudAccountId);

        List<AutoSpottingGroupDto> asgs = listAsgsAllRegions(cloudAccountId);

        for (AutoSpottingGroupDto asg : asgs) {
            try {
                ASGCostData costData = buildAsgCostData(account, asg);
                asg.currentHourlyCost = costData.getCurrentHourlyCost();
                asg.actualHourlySavings = costData.getActualHourlySavings();
                asg.potentialHourlySavings = costData.getPotentialHourlySavings();
                asg.spotInstanceCount = costData.getSpotInstanceCount();
                asg.ondemandInstanceCount = costData.getOndemandInstanceCount();
            } catch (Exception e) {
                logger.warn("Failed to enrich cost data for ASG {}: {}", asg.name, e.getMessage());
            }
        }

        logger.info("Retrieved {} ASGs with cost data via SDK", asgs.size());
        logger.info("  - {} enabled for AutoSpotting",
                asgs.stream().filter(a -> a.isEnabled).count());

        return asgs;
    }

    /**
     * Lists ASGs across regions where AutoSpotting member stack is deployed
     * Fallback method when API is unavailable
     */
    public List<AutoSpottingGroupDto> listAsgsAllRegions(Long cloudAccountId) {
        logger.info("=== Listing ASGs via AWS SDK (Fallback) ===");
        logger.info("CloudAccountId={}", cloudAccountId);

        CloudAccount account = getByAwsAccountId(cloudAccountId);
        List<AutoSpottingGroupDto> allAsgs = new ArrayList<>();

        try {
            // Discover regions where AutoSpotting is deployed
            Set<String> enabledRegions = discoverAutoSpottingRegions(account);

            if (enabledRegions.isEmpty()) {
                logger.warn("⚠️ No regions found with AutoSpotting member stack");
                return Collections.emptyList();
            }

            logger.info("✓ Scanning {} AutoSpotting-enabled region(s): {}",
                    enabledRegions.size(), enabledRegions);

            // Collect ASGs from all enabled regions
            for (String region : enabledRegions) {
                try {
                    logger.debug("→ Scanning region {}", region);
                    List<AutoSpottingGroupDto> regionAsgs = listAsgsInRegion(account, region);
                    allAsgs.addAll(regionAsgs);
                    logger.debug("✓ Found {} ASG(s) in {}", regionAsgs.size(), region);
                } catch (Exception e) {
                    logger.error("✗ Failed to scan region {}: {}", region, e.getMessage());
                    invalidateRegionInCache(account.getAwsAccountId(), region);
                }
            }

            long enabledCount = allAsgs.stream().filter(a -> a.isEnabled).count();
            logger.info("=== Total: {} ASG(s) found, {} enabled ===", allAsgs.size(), enabledCount);

            return allAsgs;
        } catch (Exception e) {
            logger.error("Failed to list ASGs: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to scan ASGs: " + e.getMessage(), e);
        }
    }

    /**
     * Lists ASGs in a specific region
     */
    public List<AutoSpottingGroupDto> listAsgs(Long cloudAccountId, String region) {
        logger.info("Listing ASGs for cloudAccountId={} region={}", cloudAccountId, region);
        CloudAccount account = getByAwsAccountId(cloudAccountId);
        return listAsgsInRegion(account, region);
    }

    /**
     * Internal helper to list ASGs in a specific region
     */
    private List<AutoSpottingGroupDto> listAsgsInRegion(CloudAccount account, String region) {
        try {
            logger.debug("Creating ASG client for account {} region {}",
                    account.getAwsAccountId(), region);
            AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(account, region);

            return asgClient.describeAutoScalingGroups().autoScalingGroups().stream()
                    .map(asg -> {
                        boolean isEnabled = asg.tags().stream()
                                .anyMatch(t -> t.key().equals(tagKey) && t.value().equals(tagValue));

                        long spotCount = asg.instances().stream()
                                .filter(i -> i.lifecycleStateAsString().equals("InService"))
                                .filter(i -> i.instanceId().startsWith("i-")) // Basic check
                                // Note: We can't determine if it's spot vs OD just from ASG API details safely
                                // without describing instances
                                // But for now we count all as generic instances, spot determination happens in
                                // getSpotInstances
                                .count();

                        return new AutoSpottingGroupDto(
                                asg.autoScalingGroupName(),
                                region,
                                isEnabled,
                                asg.instances().size(),
                                asg.minSize(),
                                asg.maxSize(),
                                asg.mixedInstancesPolicy() != null
                                        ? "MixedInstancesPolicy"
                                        : "LaunchTemplate",
                                null, // Cost data from API
                                null,
                                null,
                                0, // Placeholder, accurate count requires describeInstances
                                asg.instances().size());
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Failed to list ASGs in region {}: {}", region, e.getMessage());
            throw new RuntimeException("Could not connect to region " + region + ": " + e.getMessage(), e);
        }
    }

    // ================= INSTANCE LISTING =================

    /**
     * Get list of all instances in AutoSpotting-enabled ASGs
     * This methods performs DescribeInstances calls to get details like lifecycle
     * (Spot/On-Demand)
     */
    public List<SpotInstanceDto> getSpotInstances(Long cloudAccountId) {
        logger.info("=== Listing Spot Instances ===");
        logger.info("CloudAccountId={}", cloudAccountId);

        CloudAccount account = getByAwsAccountId(cloudAccountId);
        List<SpotInstanceDto> allInstances = new ArrayList<>();

        try {
            // 1. Get enabled regions
            Set<String> enabledRegions = discoverAutoSpottingRegions(account);

            // 2. Iterate each region
            for (String region : enabledRegions) {
                try {
                    // a. Get all ASGs in region
                    AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(account, region);

                    var asgResponse = asgClient.describeAutoScalingGroups();

                    // Filter for AutoSpotting enabled ASGs
                    var enabledAsgs = asgResponse.autoScalingGroups().stream()
                            .filter(asg -> asg.tags().stream()
                                    .anyMatch(t -> t.key().equals(tagKey) && t.value().equals(tagValue)))
                            .collect(Collectors.toList());

                    if (enabledAsgs.isEmpty()) {
                        continue;
                    }

                    // b. Collect instance IDs mapped to ASG names
                    Map<String, String> instanceToAsgMap = new HashMap<>();
                    List<String> instanceIds = new ArrayList<>();

                    for (var asg : enabledAsgs) {
                        for (var instance : asg.instances()) {
                            if (instance.instanceId() != null) {
                                instanceIds.add(instance.instanceId());
                                instanceToAsgMap.put(instance.instanceId(), asg.autoScalingGroupName());
                            }
                        }
                    }

                    if (instanceIds.isEmpty()) {
                        continue;
                    }

                    // c. Describe instances in batches (max 1000 per call, simpler to just start
                    // with one batch)
                    // For production with >1000 instances, this needs pagination/partitioning
                    Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);

                    // Partition into chunks of 100 to be safe
                    List<List<String>> chunks = partition(instanceIds, 100);

                    for (List<String> chunk : chunks) {
                        var describeResp = ec2Client.describeInstances(req -> req.instanceIds(chunk));

                        for (Reservation reservation : describeResp.reservations()) {
                            for (Instance instance : reservation.instances()) {
                                // Ignore terminated instances
                                if (instance.state().nameAsString().equalsIgnoreCase("terminated") ||
                                        instance.state().nameAsString().equalsIgnoreCase("shutting-down")) {
                                    continue;
                                }

                                String lifecycle = instance.instanceLifecycleAsString(); // "spot" or null (on-demand)
                                if (lifecycle == null || lifecycle.isEmpty()) {
                                    lifecycle = "on-demand";
                                }

                                // Calculate savings if Spot
                                Double estimatedSavings = 0.0;
                                if ("spot".equalsIgnoreCase(lifecycle)) {
                                    try {
                                        // Compare Spot Price vs On-Demand Price of SAME instance type
                                        estimatedSavings = spotPricingService.calculateHourlySavings(
                                                instance.instanceTypeAsString(),
                                                instance.instanceTypeAsString(),
                                                region, account);
                                    } catch (Exception e) {
                                        logger.warn("Failed to calculate savings for {}: {}", instance.instanceId(),
                                                e.getMessage());
                                    }
                                }

                                allInstances.add(SpotInstanceDto.builder()
                                        .instanceId(instance.instanceId())
                                        .instanceType(instance.instanceTypeAsString())
                                        .availabilityZone(instance.placement().availabilityZone())
                                        .lifecycle(lifecycle)
                                        .state(instance.state().nameAsString())
                                        .asgName(instanceToAsgMap.get(instance.instanceId()))
                                        .region(region)
                                        .estimatedHourlySavings(estimatedSavings)
                                        .launchTime(instance.launchTime().toString())
                                        .build());
                            }
                        }
                    }

                } catch (Exception e) {
                    logger.error("Error scanning region {}: {}", region, e.getMessage());
                }
            }

            return allInstances;

        } catch (Exception e) {
            logger.error("Failed to list spot instances: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch spot instances: " + e.getMessage(), e);
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    // ================= REGION DISCOVERY =================

    /**
     * Discovers regions where AutoSpotting member stack is deployed
     */
    private Set<String> discoverAutoSpottingRegions(CloudAccount account) {
        String accountId = account.getAwsAccountId();

        // Check cache first
        CachedRegionInfo cached = regionCache.get(accountId);
        if (cached != null && !cached.isExpired()) {
            logger.debug("Using cached regions: {}", cached.enabledRegions);
            return new HashSet<>(cached.enabledRegions);
        }

        logger.info("Discovering AutoSpotting regions for account {}", accountId);

        Set<String> allRegions = getAllAwsRegions(account);
        Set<String> enabledRegions = new HashSet<>();

        for (String region : allRegions) {
            if (isRegionAutoSpottingEnabled(account, region)) {
                enabledRegions.add(region);
                logger.debug("✓ Region {} has AutoSpotting", region);
            }
        }

        regionCache.put(accountId, new CachedRegionInfo(enabledRegions));
        return enabledRegions;
    }

    /**
     * Checks if the account's cross-account role can access AutoScaling in the given region.
     */
    private boolean isRegionAutoSpottingEnabled(CloudAccount account, String region) {
        try {
            AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(account, region);
            asgClient.describeAutoScalingGroups(req -> req.maxRecords(1));
            return true;
        } catch (Exception e) {
            logger.warn("✗ Region {} not accessible for account {}: {}", region, account.getAwsAccountId(), e.getMessage());
            return false;
        }
    }

    /**
     * Gets all available AWS regions
     */
    private Set<String> getAllAwsRegions(CloudAccount account) {
        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, "us-east-1");
            return ec2Client.describeRegions().regions().stream()
                    .filter(r -> r.optInStatus() == null || !r.optInStatus().equals("not-opted-in"))
                    .map(r -> r.regionName())
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            logger.warn("Failed to get regions dynamically, using defaults");
            return new HashSet<>(Arrays.asList(
                    "us-east-1", "us-west-1", "ap-south-1", "ap-south-2", "eu-north-1"));
        }
    }

    /**
     * Invalidates cached region
     */
    private void invalidateRegionInCache(String accountId, String region) {
        CachedRegionInfo cached = regionCache.get(accountId);
        if (cached != null) {
            cached.enabledRegions.remove(region);
            logger.info("Removed region {} from cache", region);
        }
    }

    /**
     * Force refresh region cache
     */
    public void refreshRegionCache(Long cloudAccountId) {
        CloudAccount account = getByAwsAccountId(cloudAccountId);
        regionCache.remove(account.getAwsAccountId());
        logger.info("✓ Cleared region cache for account {}", account.getAwsAccountId());
    }

    // ================= ASG CONTROL (API-BASED) =================

    /**
     * Enable AutoSpotting:
     * 1. Adds spot-enabled tag as a marker
     * 2. Updates ASG MixedInstancesPolicy so new/replacement instances launch as Spot
     * 3. Starts an instance refresh to gradually replace existing OD instances
     */
    public void enableAutoSpotting(Long cloudAccountId, String region, String asgName) {
        logger.info("Enabling AutoSpotting: account={} region={} asg={}", cloudAccountId, region, asgName);
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            AutoScalingClient asgClient = awsClientProvider.getAutoSpottingAsgClient(
                    account.getAwsAccountId(), region);

            // 1. Add the marker tag
            asgClient.createOrUpdateTags(req -> req.tags(
                    software.amazon.awssdk.services.autoscaling.model.Tag.builder()
                            .resourceId(asgName)
                            .resourceType("auto-scaling-group")
                            .key(tagKey)
                            .value(tagValue)
                            .propagateAtLaunch(false)
                            .build()));
            logger.info("✓ Tag '{}={}' added to ASG {}", tagKey, tagValue, asgName);

            // 2. Fetch current ASG config to build MixedInstancesPolicy
            var asgResponse = asgClient.describeAutoScalingGroups(req -> req.autoScalingGroupNames(asgName));
            if (asgResponse.autoScalingGroups().isEmpty()) {
                logger.warn("ASG {} not found — tag applied but Spot policy NOT updated", asgName);
                return;
            }
            var asg = asgResponse.autoScalingGroups().get(0);

            software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy mixedPolicy = null;

            if (asg.mixedInstancesPolicy() != null && asg.mixedInstancesPolicy().launchTemplate() != null) {
                // Already has MixedInstancesPolicy — rebuild LaunchTemplateSpecification from scratch.
                // Do NOT reuse the describe response object directly; its overrides list may contain
                // entries that lack launchTemplateId/launchTemplateName, causing AWS HTTP 400.
                var ltSpec = asg.mixedInstancesPolicy().launchTemplate().launchTemplateSpecification();
                mixedPolicy = software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy.builder()
                        .launchTemplate(ltp -> ltp.launchTemplateSpecification(s -> {
                            if (ltSpec.launchTemplateId() != null && !ltSpec.launchTemplateId().isEmpty()) {
                                s.launchTemplateId(ltSpec.launchTemplateId());
                            } else if (ltSpec.launchTemplateName() != null && !ltSpec.launchTemplateName().isEmpty()) {
                                s.launchTemplateName(ltSpec.launchTemplateName());
                            }
                            if (ltSpec.version() != null) {
                                s.version(ltSpec.version());
                            }
                        }))
                        .instancesDistribution(d -> d
                                .onDemandBaseCapacity(0)
                                .onDemandPercentageAboveBaseCapacity(0)
                                .spotAllocationStrategy("capacity-optimized"))
                        .build();

            } else if (asg.launchTemplate() != null) {
                // Has a standalone LaunchTemplate — wrap it in MixedInstancesPolicy
                var lt = asg.launchTemplate();
                mixedPolicy = software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy.builder()
                        .launchTemplate(ltp -> ltp
                                .launchTemplateSpecification(lts -> lts
                                        .launchTemplateId(lt.launchTemplateId())
                                        .version(lt.version() != null ? lt.version() : "$Default")))
                        .instancesDistribution(d -> d
                                .onDemandBaseCapacity(0)
                                .onDemandPercentageAboveBaseCapacity(0)
                                .spotAllocationStrategy("capacity-optimized"))
                        .build();

            } else {
                logger.warn("ASG {} uses a LaunchConfiguration (not LaunchTemplate). " +
                        "Cannot configure MixedInstancesPolicy. Tag was applied but Spot NOT enabled.", asgName);
                return;
            }

            // 3. Apply the updated MixedInstancesPolicy
            final var finalPolicy = mixedPolicy;
            asgClient.updateAutoScalingGroup(req -> req
                    .autoScalingGroupName(asgName)
                    .mixedInstancesPolicy(finalPolicy));
            logger.info("✓ MixedInstancesPolicy updated (OnDemandPercentage=0, strategy=capacity-optimized) for {}", asgName);

            // 4. Start an instance refresh so existing OD instances are gradually replaced with Spot
            try {
                asgClient.startInstanceRefresh(req -> req
                        .autoScalingGroupName(asgName)
                        .preferences(p -> p
                                .minHealthyPercentage(80)
                                .instanceWarmup(300)));
                logger.info("✓ Instance refresh started for {} — existing OD instances will be replaced with Spot", asgName);
            } catch (Exception e) {
                // Instance refresh failure is non-fatal — policy is already updated
                logger.warn("Could not start instance refresh for {} (policy already updated): {}", asgName, e.getMessage());
            }

            // 5. Record the enable action for audit / history
            try {
                SpotOptimizationEvent ev = new SpotOptimizationEvent();
                ev.setAccount(account);
                ev.setAsgName(asgName);
                ev.setRegion(region);
                ev.setStatus("ENABLED");
                ev.setDetails("AutoSpotting enabled by user. MixedInstancesPolicy set to 100% Spot "
                        + "(capacity-optimized). Instance refresh started to replace existing On-Demand instances.");
                ev.setHourlySavings(0.0);
                eventRepository.save(ev);
            } catch (Exception e) {
                logger.warn("Failed to record ENABLED event for ASG {}: {}", asgName, e.getMessage());
            }

            // 6. Backfill SUCCESS events for any Spot instances already running in the ASG.
            //    This ensures the Actions History shows current Spot instances even before
            //    EventBridge events arrive. Only records instances not already in the DB.
            try {
                List<String> instanceIds = asg.instances().stream()
                        .map(software.amazon.awssdk.services.autoscaling.model.Instance::instanceId)
                        .filter(id -> id != null && !id.isEmpty())
                        .collect(Collectors.toList());

                if (!instanceIds.isEmpty()) {
                    Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);
                    var describeResp = ec2Client.describeInstances(req -> req.instanceIds(instanceIds));
                    int backfilled = 0;
                    for (var reservation : describeResp.reservations()) {
                        for (var inst : reservation.instances()) {
                            if (software.amazon.awssdk.services.ec2.model.InstanceLifecycleType.SPOT
                                    .equals(inst.instanceLifecycle())) {
                                String iid = inst.instanceId();
                                String itype = inst.instanceTypeAsString();
                                SpotOptimizationEvent spotEv = new SpotOptimizationEvent();
                                spotEv.setAccount(account);
                                spotEv.setAsgName(asgName);
                                spotEv.setRegion(region);
                                spotEv.setNewInstanceId(iid);
                                spotEv.setNewInstanceType(itype);
                                spotEv.setStatus("SUCCESS");
                                spotEv.setHourlySavings(0.0);
                                spotEv.setDetails("Spot instance already running at enable time: "
                                        + iid + " (" + itype + "). Instance refresh will replace any remaining On-Demand instances.");
                                eventRepository.save(spotEv);
                                backfilled++;
                            }
                        }
                    }
                    if (backfilled > 0) {
                        logger.info("✓ Backfilled {} Spot instance event(s) for ASG {}", backfilled, asgName);
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not backfill Spot instance events for ASG {}: {}", asgName, e.getMessage());
            }

        } catch (Exception e) {
            logger.error("Failed to enable AutoSpotting for {}: {}", asgName, e.getMessage(), e);
            throw new RuntimeException("Failed to enable AutoSpotting: " + e.getMessage(), e);
        }
    }

    /**
     * Disable AutoSpotting:
     * 1. Removes the spot-enabled tag
     * 2. Sets OnDemandPercentageAboveBaseCapacity=100 so new instances launch as OD
     */
    public void disableAutoSpotting(Long cloudAccountId, String region, String asgName) {
        logger.info("Disabling AutoSpotting: account={} region={} asg={}", cloudAccountId, region, asgName);
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            AutoScalingClient asgClient = awsClientProvider.getAutoSpottingAsgClient(
                    account.getAwsAccountId(), region);

            // 1. Remove the marker tag
            asgClient.deleteTags(req -> req.tags(
                    software.amazon.awssdk.services.autoscaling.model.Tag.builder()
                            .resourceId(asgName)
                            .resourceType("auto-scaling-group")
                            .key(tagKey)
                            .build()));
            logger.info("✓ Tag '{}' removed from ASG {}", tagKey, asgName);

            // 2. Revert MixedInstancesPolicy to 100% OD if it exists
            var asgResponse = asgClient.describeAutoScalingGroups(req -> req.autoScalingGroupNames(asgName));
            if (!asgResponse.autoScalingGroups().isEmpty()) {
                var asg = asgResponse.autoScalingGroups().get(0);
                if (asg.mixedInstancesPolicy() != null && asg.mixedInstancesPolicy().launchTemplate() != null) {
                    // Rebuild LaunchTemplateSpecification from scratch — do NOT reuse the describe
                    // response object directly because its overrides list may contain entries that
                    // lack launchTemplateId/launchTemplateName, causing AWS to return HTTP 400.
                    var ltSpec = asg.mixedInstancesPolicy().launchTemplate().launchTemplateSpecification();
                    var revertedPolicy = software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy.builder()
                            .launchTemplate(ltp -> ltp.launchTemplateSpecification(s -> {
                                if (ltSpec.launchTemplateId() != null && !ltSpec.launchTemplateId().isEmpty()) {
                                    s.launchTemplateId(ltSpec.launchTemplateId());
                                } else if (ltSpec.launchTemplateName() != null && !ltSpec.launchTemplateName().isEmpty()) {
                                    s.launchTemplateName(ltSpec.launchTemplateName());
                                }
                                if (ltSpec.version() != null) {
                                    s.version(ltSpec.version());
                                }
                            }))
                            .instancesDistribution(d -> d
                                    .onDemandBaseCapacity(0)
                                    .onDemandPercentageAboveBaseCapacity(100))
                            .build();

                    asgClient.updateAutoScalingGroup(req -> req
                            .autoScalingGroupName(asgName)
                            .mixedInstancesPolicy(revertedPolicy));
                    logger.info("✓ MixedInstancesPolicy reverted to OnDemand-only for {}", asgName);
                }
            }

            // Record the disable action for audit / history
            try {
                SpotOptimizationEvent ev = new SpotOptimizationEvent();
                ev.setAccount(account);
                ev.setAsgName(asgName);
                ev.setRegion(region);
                ev.setStatus("DISABLED");
                ev.setDetails("AutoSpotting disabled by user. MixedInstancesPolicy reverted to 100% On-Demand. "
                        + "New instances will launch as On-Demand until re-enabled.");
                ev.setHourlySavings(0.0);
                eventRepository.save(ev);
            } catch (Exception e) {
                logger.warn("Failed to record DISABLED event for ASG {}: {}", asgName, e.getMessage());
            }

        } catch (Exception e) {
            logger.error("Failed to disable AutoSpotting for {}: {}", asgName, e.getMessage(), e);
            throw new RuntimeException("Failed to disable AutoSpotting: " + e.getMessage(), e);
        }
    }


    /**
     * Handles a scaling event from EventBridge/SQS.
     * This is the entry point for the centralized AutoSpotting engine logic.
     */
    public void handleScalingEvent(CloudAccount account, String region, JsonNode eventDetail) {
        String eventName = eventDetail.path("Event").asText();
        String asgName = eventDetail.path(ASG_NAME_KEY).asText();

        logger.info("AutoSpotting: Handling event {} for ASG {} in account {}", eventName, asgName,
                account.getAwsAccountId());

        // For now, we only care about successful launches to record savings
        // and terminations to stop recording savings (or just track swaps)
        if ("EC2 Instance Launch Successful".equals(eventName)) {
            processLaunchEvent(account, region, eventDetail);
        } else if ("EC2 Instance Terminate Successful".equals(eventName)) {
            processTerminateEvent(eventDetail);
        }
    }

    /**
     * Handles an EC2 Spot Instance Interruption Warning (2-minute notice).
     *
     * AWS sends this event via EventBridge → SQS before reclaiming a Spot instance.
     * The ASG automatically replaces the terminated instance (with another Spot or
     * falls back to On-Demand if no Spot capacity is available), so no manual
     * replacement action is needed here. We simply log and persist the event for
     * auditing and interruption-rate analytics.
     */
    public void handleSpotInterruption(CloudAccount account, String region, JsonNode detail) {
        String instanceId = detail.path("instance-id").asText();
        String instanceAction = detail.path("instance-action").asText("terminate");

        logger.warn("⚠️  SPOT INTERRUPTION WARNING — instance {} ({}) will be {} in ~2 minutes [account={} region={}]",
                instanceId, instanceAction, instanceAction, account.getAwsAccountId(), region);

        // Identify which ASG this instance belongs to (best-effort)
        String asgName = "unknown";
        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);
            var describeResp = ec2Client.describeInstances(req -> req.instanceIds(instanceId));
            if (!describeResp.reservations().isEmpty()
                    && !describeResp.reservations().get(0).instances().isEmpty()) {
                var instance = describeResp.reservations().get(0).instances().get(0);
                asgName = instance.tags().stream()
                        .filter(t -> ASG_NAME_KEY.equals(t.key()))
                        .map(t -> t.value())
                        .findFirst()
                        .orElse("unknown");
            }
        } catch (Exception e) {
            logger.warn("Could not resolve ASG name for interrupted instance {}: {}", instanceId, e.getMessage());
        }

        // Persist the interruption event for history / analytics
        try {
            SpotOptimizationEvent event = new SpotOptimizationEvent();
            event.setAccount(account);
            event.setRegion(region);
            event.setAsgName(asgName);
            event.setOldInstanceId(instanceId);
            event.setStatus("INTERRUPTED");
            event.setDetails("Spot interruption warning received. Action=" + instanceAction
                    + ". ASG will automatically replace the instance.");
            event.setHourlySavings(0.0);
            eventRepository.save(event);
            logger.info("✓ Interruption event persisted for instance {} in ASG {}", instanceId, asgName);
        } catch (Exception e) {
            logger.error("Failed to persist interruption event for {}: {}", instanceId, e.getMessage());
        }

        // Note: No explicit replacement action is required.
        // The ASG's health-check mechanism detects the terminated instance and
        // automatically launches a replacement using the MixedInstancesPolicy —
        // preferring Spot with `capacity-optimized` strategy, falling back to
        // On-Demand if no Spot capacity is currently available in the AZ.
        logger.info("ASG {} will automatically handle instance replacement for {}", asgName, instanceId);
    }

    private void processLaunchEvent(CloudAccount account, String region, JsonNode detail) {
        String asgName = detail.path(ASG_NAME_KEY).asText();
        String instanceId = detail.path("EC2InstanceId").asText();

        // In a real swap, we'd know the old instance type.
        // For simplicity in this centralized version, we check if the new instance is a
        // SPOT instance
        // and calculate savings based on what a standard instance of that type would
        // cost vs spot.

        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);
            var describeResponse = ec2Client.describeInstances(req -> req.instanceIds(instanceId));

            if (!describeResponse.reservations().isEmpty()
                    && !describeResponse.reservations().get(0).instances().isEmpty()) {
                var instance = describeResponse.reservations().get(0).instances().get(0);
                String instanceType = instance.instanceTypeAsString();

                // Check if it's a spot instance
                if (instance.instanceLifecycleAsString().equalsIgnoreCase("spot")) {
                    double hourlySavings = spotPricingService.calculateHourlySavings(instanceType, instanceType, region,
                            account);

                    logger.info("✓ Detected new SPOT instance {} ({}) in ASG {}. Hourly savings: ${}",
                            instanceId, instanceType, asgName, String.format("%.4f", hourlySavings));

                    // Record Event
                    SpotOptimizationEvent event = new SpotOptimizationEvent();
                    event.setAccount(account);
                    event.setAsgName(asgName);
                    event.setRegion(region);
                    event.setNewInstanceId(instanceId);
                    event.setNewInstanceType(instanceType);
                    event.setHourlySavings(hourlySavings);
                    event.setStatus("SUCCESS");
                    event.setDetails("Spot instance launched successfully (" + instanceType
                            + "). On-Demand replaced. Hourly savings: $"
                            + String.format("%.4f", hourlySavings));
                    eventRepository.save(event);

                    // Update Daily Savings Summary
                    updateDailySavings(account, hourlySavings);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to process launch event for instance {}: {}", instanceId, e.getMessage());
        }
    }

    private void processTerminateEvent(JsonNode detail) {
        // termination logic here if needed for tracking
        logger.info("Instance terminated in ASG {}. Processing complete.",
                detail.path(ASG_NAME_KEY).asText());
    }

    private void updateDailySavings(CloudAccount account, double hourlySavings) {
        java.time.LocalDate today = java.time.LocalDate.now();
        DailySpotSavings savings = savingsRepository.findByAccountIdAndDate(account.getId(), today)
                .orElse(new DailySpotSavings(account, today));

        // Add 1 hour worth of savings for the event (simplified)
        // In a more complex model, we'd track active instances per hour.
        savings.setAmountSaved(savings.getAmountSaved() + hourlySavings);
        savings.setSwapCount(savings.getSwapCount() + 1);
        savingsRepository.save(savings);
    }

    /**
     * Legacy tag-based disable (fallback)
     */

    // ================= ASG CONFIGURATION (API-BASED) =================

    /**
     * Get ASG configuration from PostgreSQL settings + ASG tags
     */
    public ASGConfig getAsgConfig(Long cloudAccountId, String region, String asgName) {
        logger.info("Fetching config for ASG {} in {} (PostgreSQL + ASG tags)", asgName, region);
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            Optional<AutoSpottingSettings> settingsOpt = settingsRepository.findByAccountId(account.getId());

            AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(account, region);
            var asgResponse = asgClient.describeAutoScalingGroups(req ->
                    req.autoScalingGroupNames(asgName));

            ASGConfig config = new ASGConfig();
            config.setAsgName(asgName);
            config.setRegion(region);

            if (settingsOpt.isPresent()) {
                AutoSpottingSettings settings = settingsOpt.get();
                config.setEnabled(settings.isEnabled());
                config.setMinOnDemand(settings.getMinOnDemandNumber());
                config.setDisallowedInstanceTypes(settings.getExcludedInstances());
                config.setOnDemandPercentageAboveBaseCapacity(100 - settings.getMaxSpotPercentage());
            }

            if (!asgResponse.autoScalingGroups().isEmpty()) {
                var asg = asgResponse.autoScalingGroups().get(0);
                boolean tagEnabled = asg.tags().stream()
                        .anyMatch(t -> t.key().equals(tagKey) && t.value().equals(tagValue));
                config.setEnabled(tagEnabled);

                if (asg.mixedInstancesPolicy() != null &&
                        asg.mixedInstancesPolicy().launchTemplate() != null &&
                        asg.mixedInstancesPolicy().launchTemplate().overrides() != null) {
                    String types = asg.mixedInstancesPolicy().launchTemplate().overrides().stream()
                            .map(o -> o.instanceType())
                            .filter(t -> t != null)
                            .collect(Collectors.joining(","));
                    config.setAllowedInstanceTypes(types);
                }
            }

            logger.info("Config retrieved for ASG {}: enabled={}", asgName, config.getEnabled());
            return config;

        } catch (Exception e) {
            logger.error("Failed to fetch ASG config: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Update ASG configuration in PostgreSQL + ASG tags
     */
    public ASGConfig updateAsgConfig(Long cloudAccountId, String region, String asgName, ASGConfigUpdate configUpdate) {
        logger.info("Updating config for ASG {} in {} (PostgreSQL + ASG tags)", asgName, region);
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            AutoSpottingSettings settings = settingsRepository.findByAccountId(account.getId())
                    .orElse(new AutoSpottingSettings(account));

            if (configUpdate.getEnabled() != null) {
                settings.setEnabled(configUpdate.getEnabled());
            }
            if (configUpdate.getMinOnDemandNumber() != null) {
                settings.setMinOnDemandNumber(configUpdate.getMinOnDemandNumber());
            }
            if (configUpdate.getDisallowedInstanceTypes() != null) {
                settings.setExcludedInstances(String.join(",", configUpdate.getDisallowedInstanceTypes()));
            }
            if (configUpdate.getMinOnDemandPercentage() != null) {
                settings.setMaxSpotPercentage(100 - configUpdate.getMinOnDemandPercentage().intValue());
            }
            settingsRepository.save(settings);
            logger.info("PostgreSQL settings updated for account {}", account.getAwsAccountId());

            if (configUpdate.getEnabled() != null) {
                if (configUpdate.getEnabled()) {
                    enableAutoSpotting(cloudAccountId, region, asgName);
                } else {
                    disableAutoSpotting(cloudAccountId, region, asgName);
                }
            }

            return getAsgConfig(cloudAccountId, region, asgName);

        } catch (Exception e) {
            logger.error("Failed to update ASG config: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to update configuration: " + e.getMessage(), e);
        }
    }

    // ================= HELPER METHODS =================

    /**
     * Create empty history response
     */
    private HistoryResponse createEmptyHistoryResponse() {
        HistoryResponse response = new HistoryResponse();
        response.setDataPoints(Collections.emptyList());

        HistoryResponse.HistorySummary summary = new HistoryResponse.HistorySummary();
        summary.setTotalActualSavings(0.0);
        summary.setTotalPotentialSavings(0.0);

        response.setSummary(summary);
        return response;
    }

    /**
     * Create empty savings summary
     */
    private DashboardData.SavingsSummary createEmptySavingsSummary() {
        return DashboardData.SavingsSummary.builder()
                .currentMonthlyCost(0.0)
                .onDemandMonthlyCost(0.0)
                .actualMonthlySavings(0.0)
                .potentialMonthlySavings(0.0)
                .totalAsgs(0)
                .enabledAsgs(0)
                .build();
    }

    /**
     * Build cost response from AWS SDK when API fails
     * This creates a response with ASG data but WITHOUT cost information
     */
    private CostResponse buildCostResponseFromAwsSdk(CloudAccount account) {
        logger.info("Building cost response from AWS SDK (fallback mode - no cost data available)");

        List<AutoSpottingGroupDto> asgs = listAsgsAllRegions(Long.parseLong(account.getAwsAccountId()));

        CostResponse response = new CostResponse();

        // Convert DTOs to API format (but with null costs since SDK doesn't provide
        // them)
        List<ASGCostData> asgCostDataList = asgs.stream()
                .map(this::convertDtoToApiFormat)
                .collect(Collectors.toList());

        response.setAsgs(asgCostDataList);

        // Calculate summary with proper null-safe defaults
        CostResponse.CostSummary summary = new CostResponse.CostSummary();
        summary.setTotalAsgCount(asgs.size());
        summary.setAutospottingEnabledCount((int) asgs.stream().filter(a -> a.isEnabled).count());

        // Set cost values to 0.0 instead of null
        summary.setTotalCurrentHourlyCost(0.0);
        summary.setTotalOndemandHourlyCost(0.0);
        summary.setTotalActualSavings(0.0);
        summary.setTotalPotentialSavings(0.0);

        response.setSummary(summary);

        logger.warn("⚠️ Cost data not available from API. Using SDK-only data with zero costs.");
        return response;
    }

    /**
     * Builds cost data for a single ASG by describing its instances and using SpotPricingService.
     */
    private ASGCostData buildAsgCostData(CloudAccount account, AutoSpottingGroupDto asgDto) {
        ASGCostData cost = new ASGCostData();
        cost.setAccountId(account.getAwsAccountId());
        cost.setAsgName(asgDto.name);
        cost.setRegion(asgDto.region);
        cost.setAutospottingEnabled(asgDto.isEnabled);
        cost.setInstanceCount(asgDto.instanceCount);

        double currentHourlyCost = 0.0;
        double onDemandHourlyCost = 0.0;
        int spotCount = 0;
        int onDemandCount = 0;
        List<String> instanceTypes = new ArrayList<>();

        try {
            AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(account, asgDto.region);

            var asgResponse = asgClient.describeAutoScalingGroups(req ->
                    req.autoScalingGroupNames(asgDto.name));

            if (!asgResponse.autoScalingGroups().isEmpty()) {
                var asg = asgResponse.autoScalingGroups().get(0);
                List<String> instanceIds = asg.instances().stream()
                        .map(i -> i.instanceId())
                        .collect(Collectors.toList());

                if (!instanceIds.isEmpty()) {
                    Ec2Client ec2Client = awsClientProvider.getEc2Client(account, asgDto.region);

                    for (List<String> chunk : partition(instanceIds, 100)) {
                        var describeResp = ec2Client.describeInstances(req -> req.instanceIds(chunk));

                        for (Reservation reservation : describeResp.reservations()) {
                            for (Instance instance : reservation.instances()) {
                                if ("terminated".equalsIgnoreCase(instance.state().nameAsString()) ||
                                        "shutting-down".equalsIgnoreCase(instance.state().nameAsString())) {
                                    continue;
                                }

                                String instanceType = instance.instanceTypeAsString();
                                String lifecycle = instance.instanceLifecycleAsString();
                                boolean isSpot = "spot".equalsIgnoreCase(lifecycle);

                                if (!instanceTypes.contains(instanceType)) {
                                    instanceTypes.add(instanceType);
                                }

                                double odPrice = spotPricingService.getHourlyOnDemandPrice(instanceType, asgDto.region);
                                onDemandHourlyCost += odPrice;

                                if (isSpot) {
                                    spotCount++;
                                    double spotPrice = spotPricingService.getHourlySpotPrice(instanceType, asgDto.region, account);
                                    currentHourlyCost += spotPrice;
                                } else {
                                    onDemandCount++;
                                    currentHourlyCost += odPrice;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to get instance details for ASG {} in {}: {}",
                    asgDto.name, asgDto.region, e.getMessage());
        }

        double actualSavings = onDemandHourlyCost - currentHourlyCost;
        double potentialSavings = 0.0;
        if (onDemandCount > 0 && !instanceTypes.isEmpty()) {
            for (String iType : instanceTypes) {
                double odPrice = spotPricingService.getHourlyOnDemandPrice(iType, asgDto.region);
                double spPrice = spotPricingService.getHourlySpotPrice(iType, asgDto.region, account);
                potentialSavings += Math.max(0, odPrice - spPrice);
            }
            potentialSavings = potentialSavings / instanceTypes.size() * onDemandCount;
        }

        cost.setCurrentHourlyCost(currentHourlyCost);
        cost.setOndemandHourlyCost(onDemandHourlyCost);
        cost.setActualHourlySavings(Math.max(0, actualSavings));
        cost.setPotentialHourlySavings(Math.max(0, potentialSavings));
        cost.setSpotInstanceCount(spotCount);
        cost.setOndemandInstanceCount(onDemandCount);
        cost.setInstanceTypes(instanceTypes);

        return cost;
    }

    /**
     * Convert DTO to API format
     */
    private ASGCostData convertDtoToApiFormat(AutoSpottingGroupDto dto) {
        ASGCostData cost = new ASGCostData();
        cost.setAsgName(dto.name);
        cost.setRegion(dto.region);
        cost.setAutospottingEnabled(dto.isEnabled);
        cost.setInstanceCount(dto.instanceCount);
        cost.setCurrentHourlyCost(dto.currentHourlyCost != null ? dto.currentHourlyCost : 0.0);
        cost.setActualHourlySavings(dto.actualHourlySavings != null ? dto.actualHourlySavings : 0.0);
        cost.setPotentialHourlySavings(dto.potentialHourlySavings != null ? dto.potentialHourlySavings : 0.0);
        cost.setSpotInstanceCount(dto.spotInstanceCount != null ? dto.spotInstanceCount : 0);
        cost.setOndemandInstanceCount(dto.ondemandInstanceCount != null ? dto.ondemandInstanceCount : 0);
        return cost;
    }

    // ================= CACHE HELPER CLASS =================

    private static class CachedRegionInfo {
        private final Set<String> enabledRegions;
        private final long cacheTime;

        CachedRegionInfo(Set<String> enabledRegions) {
            this.enabledRegions = new HashSet<>(enabledRegions);
            this.cacheTime = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - cacheTime) > CACHE_TTL_MILLIS;
        }
    }

    /**
     * Get RI/SP Coverage via AWS Cost Explorer (in-house)
     */
    public RiSpCoverageResponse getRiSpCoverage(Long cloudAccountId) {
        logger.info("Fetching RI/SP coverage via AWS Cost Explorer for cloudAccountId={}", cloudAccountId);
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            CostExplorerClient ceClient = awsClientProvider.getCostExplorerClient(account);

            String startDate = LocalDate.now().minusDays(30).toString();
            String endDate = LocalDate.now().toString();

            DateInterval dateInterval = DateInterval.builder()
                    .start(startDate)
                    .end(endDate)
                    .build();

            RiSpCoverageResponse response = new RiSpCoverageResponse();
            response.setAccountId(account.getAwsAccountId());
            response.setCollectedAt(Instant.now().toString());
            response.setHasBillingAccess(true);

            // RI Coverage
            try {
                GetReservationCoverageResponse riCoverage = ceClient.getReservationCoverage(req -> req
                        .timePeriod(dateInterval)
                        .granularity(Granularity.MONTHLY));

                if (riCoverage.total() != null && riCoverage.total().coverageHours() != null) {
                    String coveragePct = riCoverage.total().coverageHours().coverageHoursPercentage();
                    response.setTotalRiUtilizationPercent(
                            coveragePct != null ? Double.parseDouble(coveragePct) : 0.0);
                }
            } catch (Exception e) {
                logger.warn("Could not fetch RI coverage: {}", e.getMessage());
                response.setTotalRiUtilizationPercent(0.0);
            }
            response.setRiCoverages(new ArrayList<>());

            // SP Coverage
            try {
                GetSavingsPlansCoverageResponse spCoverage = ceClient.getSavingsPlansCoverage(req -> req
                        .timePeriod(dateInterval)
                        .granularity(Granularity.MONTHLY));

                if (spCoverage.savingsPlansCoverages() != null && !spCoverage.savingsPlansCoverages().isEmpty()) {
                    var firstPeriod = spCoverage.savingsPlansCoverages().get(0);
                    if (firstPeriod.coverage() != null) {
                        String spPct = firstPeriod.coverage().coveragePercentage();
                        response.setTotalSpUtilizationPercent(
                                spPct != null ? Double.parseDouble(spPct) : 0.0);
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not fetch SP coverage: {}", e.getMessage());
                response.setTotalSpUtilizationPercent(0.0);
            }
            response.setSpCoverages(new ArrayList<>());

            response.setRiUtilizations(new ArrayList<>());
            response.setSpUtilizations(new ArrayList<>());

            logger.info("RI/SP coverage retrieved: RI={}%, SP={}%",
                    response.getTotalRiUtilizationPercent(),
                    response.getTotalSpUtilizationPercent());

            return response;

        } catch (Exception e) {
            logger.error("Failed to fetch RI/SP coverage: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch RI/SP coverage: " + e.getMessage(), e);
        }
    }

    // ================= DTO CLASS =================

    /**
     * Enhanced DTO with cost information
     */
    public static class AutoSpottingGroupDto {
        public String name;
        public String region;
        public boolean isEnabled;
        public int instanceCount;
        public int minSize;
        public int maxSize;
        public String type;
        public Double currentHourlyCost;
        public Double actualHourlySavings;
        public Double potentialHourlySavings;
        public Integer spotInstanceCount;
        public Integer ondemandInstanceCount;

        // Legacy constructor (backward compatibility)
        public AutoSpottingGroupDto(String name, String region, boolean isEnabled,
                int instanceCount, int min, int max, String type) {
            this.name = name;
            this.region = region;
            this.isEnabled = isEnabled;
            this.instanceCount = instanceCount;
            this.minSize = min;
            this.maxSize = max;
            this.type = type;
        }

        // Enhanced constructor with cost data
        public AutoSpottingGroupDto(String name, String region, boolean isEnabled,
                int instanceCount, int min, int max, String type,
                Double currentHourlyCost, Double actualHourlySavings,
                Double potentialHourlySavings, Integer spotCount, Integer ondemandCount) {
            this(name, region, isEnabled, instanceCount, min, max, type);
            this.currentHourlyCost = currentHourlyCost;
            this.actualHourlySavings = actualHourlySavings;
            this.potentialHourlySavings = potentialHourlySavings;
            this.spotInstanceCount = spotCount;
            this.ondemandInstanceCount = ondemandCount;
        }
    }

    /**
     * Get events (actions history) from PostgreSQL
     */
    public EventsResponse getEvents(Long cloudAccountId, String start, String end, String eventType, String asgName) {
        logger.info("=== Fetching Events from PostgreSQL ===");
        logger.info("CloudAccountId={}, start={}, end={}, eventType={}, asgName={}",
                cloudAccountId, start, end, eventType, asgName);

        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            Instant startTime = (start != null && !start.isEmpty()) ? Instant.parse(start) : Instant.now().minus(7, ChronoUnit.DAYS);
            Instant endTime = (end != null && !end.isEmpty()) ? Instant.parse(end) : Instant.now();

            List<SpotOptimizationEvent> events;

            if (asgName != null && !asgName.isEmpty()) {
                events = eventRepository.findByAccountIdAndAsgNameAndEventTimeBetweenOrderByEventTimeDesc(
                        account.getId(), asgName, startTime, endTime);
            } else if (eventType != null && !eventType.isEmpty()) {
                String status = mapEventTypeToStatus(eventType);
                events = eventRepository.findByAccountIdAndEventTimeBetweenAndStatusOrderByEventTimeDesc(
                        account.getId(), startTime, endTime, status);
            } else {
                events = eventRepository.findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(
                        account.getId(), startTime, endTime);
            }

            List<EventRecord> eventRecords = events.stream().map(event -> {
                EventRecord record = new EventRecord();
                record.setTimestamp(event.getEventTime().toString());
                record.setAccountId(account.getAwsAccountId());
                record.setRegion(event.getRegion());
                record.setAsgName(event.getAsgName());
                record.setEstimatedSavings(event.getHourlySavings());

                // Map status → event_type, result label, instance fields, and reason
                String evStatus = event.getStatus();
                if ("SUCCESS".equals(evStatus)) {
                    record.setEventType("instance_replacement");
                    record.setResult("Success");
                    record.setOldInstanceId(event.getOldInstanceId());
                    record.setOldInstanceType(event.getOldInstanceType());
                    record.setOldLifecycle("on-demand");
                    record.setNewInstanceId(event.getNewInstanceId());
                    record.setNewInstanceType(event.getNewInstanceType());
                    record.setNewLifecycle("spot");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "On-Demand instance replaced with Spot. Hourly savings: $"
                            + String.format("%.4f", event.getHourlySavings() != null ? event.getHourlySavings() : 0.0));
                } else if ("INTERRUPTED".equals(evStatus)) {
                    record.setEventType("spot_interruption");
                    record.setResult("Interrupted");
                    record.setOldInstanceId(event.getOldInstanceId());
                    record.setOldLifecycle("spot");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "AWS reclaimed this Spot instance. ASG will automatically replace it.");
                } else if ("ENABLED".equals(evStatus)) {
                    record.setEventType("asg_enabled");
                    record.setResult("Enabled");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "AutoSpotting enabled by user.");
                } else if ("DISABLED".equals(evStatus)) {
                    record.setEventType("asg_disabled");
                    record.setResult("Disabled");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "AutoSpotting disabled by user.");
                } else {
                    record.setEventType("spot_interruption");
                    record.setResult("Failed");
                    record.setOldInstanceId(event.getOldInstanceId());
                    record.setOldInstanceType(event.getOldInstanceType());
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "Spot launch or replacement failed.");
                }

                return record;
            }).collect(Collectors.toList());

            long replacements = events.stream().filter(e -> "SUCCESS".equals(e.getStatus())).count();
            long interruptions = events.stream().filter(e -> "FAILED".equals(e.getStatus()) || "INTERRUPTED".equals(e.getStatus())).count();
            double totalSavings = events.stream()
                    .filter(e -> e.getHourlySavings() != null)
                    .mapToDouble(SpotOptimizationEvent::getHourlySavings)
                    .sum();

            EventsSummary summary = new EventsSummary();
            summary.setTotalReplacements((int) replacements);
            summary.setTotalInterruptions((int) interruptions);
            summary.setTotalEstimatedSavings(totalSavings);

            EventsResponse response = new EventsResponse();
            response.setStart(startTime.toString());
            response.setEnd(endTime.toString());
            response.setCount(eventRecords.size());
            response.setEvents(eventRecords);
            response.setSummary(summary);

            logger.info("Retrieved {} events from PostgreSQL", eventRecords.size());
            return response;

        } catch (Exception e) {
            logger.error("Failed to fetch events from PostgreSQL: {}", e.getMessage(), e);
            return createEmptyEventsResponse(start, end);
        }
    }

    private String mapEventTypeToStatus(String eventType) {
        if ("instance_replacement".equalsIgnoreCase(eventType)) return "SUCCESS";
        if ("spot_interruption".equalsIgnoreCase(eventType)) return "INTERRUPTED";
        if ("asg_enabled".equalsIgnoreCase(eventType)) return "ENABLED";
        if ("asg_disabled".equalsIgnoreCase(eventType)) return "DISABLED";
        if ("launch_failure".equalsIgnoreCase(eventType)) return "FAILED";
        return eventType.toUpperCase();
    }

    /**
     * Create empty events response
     */
    private EventsResponse createEmptyEventsResponse(String start, String end) {
        EventsResponse response = new EventsResponse();
        response.setStart(start);
        response.setEnd(end);
        response.setCount(0);
        response.setEvents(new java.util.ArrayList<>());

        EventsSummary summary = new EventsSummary();
        summary.setTotalReplacements(0);
        summary.setTotalInterruptions(0);
        summary.setTotalEstimatedSavings(0.0);
        response.setSummary(summary);

        return response;
    }

    /**
     * Get launch analytics from PostgreSQL event data
     */
    public LaunchAnalyticsResponse getLaunchAnalytics(Long cloudAccountId, String start, String end) {
        logger.info("=== Fetching Launch Analytics from PostgreSQL ===");
        logger.info("CloudAccountId={}, start={}, end={}", cloudAccountId, start, end);

        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            Instant startTime = (start != null && !start.isEmpty())
                    ? Instant.parse(start.contains("T") ? start : start + "T00:00:00Z")
                    : Instant.now().minus(30, ChronoUnit.DAYS);
            Instant endTime = (end != null && !end.isEmpty())
                    ? Instant.parse(end.contains("T") ? end : end + "T23:59:59Z")
                    : Instant.now();

            // Only count Spot launch events — exclude ENABLED/DISABLED/INTERRUPTED
            List<SpotOptimizationEvent> allEvents = eventRepository
                    .findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(
                            account.getId(), startTime, endTime);

            List<SpotOptimizationEvent> launchEvents = allEvents.stream()
                    .filter(e -> "SUCCESS".equals(e.getStatus()) || "FAILED".equals(e.getStatus()))
                    .collect(Collectors.toList());

            LaunchAnalyticsResponse response = new LaunchAnalyticsResponse();

            int totalAttempts = launchEvents.size();
            long totalSuccesses = launchEvents.stream().filter(e -> "SUCCESS".equals(e.getStatus())).count();
            long totalFailures = launchEvents.stream().filter(e -> "FAILED".equals(e.getStatus())).count();

            response.setTotalAttempts(totalAttempts);
            response.setTotalSuccesses((int) totalSuccesses);
            response.setTotalFailures((int) totalFailures);
            response.setSuccessRate(totalAttempts > 0 ? (totalSuccesses * 100.0 / totalAttempts) : 0.0);

            // Maps: instanceType → LaunchStats, region → LaunchStats
            Map<String, LaunchAnalyticsResponse.LaunchStats> byType = new HashMap<>();
            Map<String, LaunchAnalyticsResponse.LaunchStats> byRegion = new HashMap<>();
            // Track which region had the most successes for each instance type (for recommendations)
            Map<String, Map<String, Integer>> typeRegionSuccesses = new HashMap<>();
            // Failure reason counts
            Map<String, Integer> failureReasonCounts = new HashMap<>();

            for (SpotOptimizationEvent event : launchEvents) {
                String iType = event.getNewInstanceType() != null ? event.getNewInstanceType() : "unknown";
                String eventRegion = event.getRegion() != null ? event.getRegion() : "unknown";
                boolean isSuccess = "SUCCESS".equals(event.getStatus());

                // Aggregate by instance type
                LaunchAnalyticsResponse.LaunchStats typeStats = byType.computeIfAbsent(iType, k -> {
                    LaunchAnalyticsResponse.LaunchStats s = new LaunchAnalyticsResponse.LaunchStats();
                    s.setAttempts(0); s.setSuccesses(0); s.setFailures(0);
                    return s;
                });
                typeStats.setAttempts(typeStats.getAttempts() + 1);
                if (isSuccess) typeStats.setSuccesses(typeStats.getSuccesses() + 1);
                else typeStats.setFailures(typeStats.getFailures() + 1);
                typeStats.setSuccessRate(typeStats.getAttempts() > 0
                        ? (typeStats.getSuccesses() * 100.0 / typeStats.getAttempts()) : 0.0);

                // Track region successes per instance type (for best-region recommendation)
                if (isSuccess) {
                    typeRegionSuccesses
                            .computeIfAbsent(iType, k -> new HashMap<>())
                            .merge(eventRegion, 1, Integer::sum);
                }

                // Aggregate by region
                LaunchAnalyticsResponse.LaunchStats regionStats = byRegion.computeIfAbsent(eventRegion, k -> {
                    LaunchAnalyticsResponse.LaunchStats s = new LaunchAnalyticsResponse.LaunchStats();
                    s.setAttempts(0); s.setSuccesses(0); s.setFailures(0);
                    return s;
                });
                regionStats.setAttempts(regionStats.getAttempts() + 1);
                if (isSuccess) regionStats.setSuccesses(regionStats.getSuccesses() + 1);
                else regionStats.setFailures(regionStats.getFailures() + 1);
                regionStats.setSuccessRate(regionStats.getAttempts() > 0
                        ? (regionStats.getSuccesses() * 100.0 / regionStats.getAttempts()) : 0.0);

                // Collect failure reasons from details field
                if (!isSuccess && event.getDetails() != null && !event.getDetails().isEmpty()) {
                    String reason = event.getDetails().length() > 80
                            ? event.getDetails().substring(0, 80) + "..." : event.getDetails();
                    failureReasonCounts.merge(reason, 1, Integer::sum);
                }
            }

            response.setByInstanceType(byType);
            response.setByRegion(byRegion);
            response.setByAz(new HashMap<>());

            // Build top failure reasons (sorted by count, top 5)
            int totalFailed = (int) totalFailures;
            List<LaunchAnalyticsResponse.FailureReason> failureReasons = failureReasonCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(5)
                    .map(entry -> {
                        LaunchAnalyticsResponse.FailureReason fr = new LaunchAnalyticsResponse.FailureReason();
                        fr.setReason(entry.getKey());
                        fr.setCount(entry.getValue());
                        fr.setPercentage(totalFailed > 0 ? (entry.getValue() * 100.0 / totalFailed) : 0.0);
                        return fr;
                    })
                    .collect(Collectors.toList());
            response.setTopFailureReasons(failureReasons);

            // Build recommendations — include instance types with at least 1 success
            List<LaunchAnalyticsResponse.RecommendedType> recommended = byType.entrySet().stream()
                    .filter(e -> e.getValue().getSuccesses() >= 1 && !"unknown".equals(e.getKey()))
                    .sorted((a, b) -> Double.compare(b.getValue().getSuccessRate(), a.getValue().getSuccessRate()))
                    .limit(5)
                    .map(e -> {
                        String bestRegion = typeRegionSuccesses.getOrDefault(e.getKey(), new HashMap<>())
                                .entrySet().stream()
                                .max(Map.Entry.comparingByValue())
                                .map(Map.Entry::getKey)
                                .orElse("Global");
                        LaunchAnalyticsResponse.RecommendedType rt = new LaunchAnalyticsResponse.RecommendedType();
                        rt.setInstanceType(e.getKey());
                        rt.setRegion(bestRegion);
                        rt.setSuccessRate(e.getValue().getSuccessRate());
                        rt.setTotalLaunches(e.getValue().getAttempts());
                        rt.setAvgLaunchTime("< 2 min");
                        rt.setScore(e.getValue().getSuccessRate() * Math.log(e.getValue().getAttempts() + 1));
                        return rt;
                    })
                    .collect(Collectors.toList());
            response.setRecommendedTypes(recommended);

            logger.info("Launch analytics built: {} launch attempts ({} success, {} failed), {}% success rate",
                    totalAttempts, totalSuccesses, totalFailures,
                    String.format("%.1f", response.getSuccessRate()));

            return response;

        } catch (Exception e) {
            logger.error("Failed to build launch analytics: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch launch analytics: " + e.getMessage(), e);
        }
    }
}