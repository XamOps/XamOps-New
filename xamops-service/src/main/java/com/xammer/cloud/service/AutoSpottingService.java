package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.autospotting.*;
import com.xammer.cloud.dto.autospotting.EventsResponse.EventsSummary;
import com.xammer.cloud.repository.CloudAccountRepository;
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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AutoSpottingService {

    private static final Logger logger = LoggerFactory.getLogger(AutoSpottingService.class);

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final AutoSpottingApiClient apiClient;

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
            AutoSpottingApiClient apiClient) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.apiClient = apiClient;
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
     */
    public void registerCustomerAccount(Long cloudAccountId) {
        logger.info("=== AutoSpotting Registration Started ===");
        logger.info("Customer AWS account ID to register: {}", cloudAccountId);

        CloudAccount account = getByAwsAccountId(cloudAccountId);
        logger.info("Resolved CloudAccount: id={}, awsAccountId={}, client={}",
                account.getId(), account.getAwsAccountId(),
                account.getClient() != null ? account.getClient().getName() : "null");

        try {
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
            logger.error("Failed to register account {} in AutoSpotting DB (table='{}'): {}",
                    cloudAccountId, tableName, e.getMessage(), e);
            throw new RuntimeException("Failed to enable AutoSpotting: " + e.getMessage(), e);
        }
    }

    // ================= COST & SAVINGS DATA (API-BASED, NO ATHENA)
    // =================

    /**
     * Get current cost data directly from AutoSpotting API
     * Uses GET /v1/costs endpoint (real-time data, no Athena)
     */
    public CostResponse getCostData(Long cloudAccountId) {
        logger.info("=== Fetching Cost Data ===");
        logger.info("CloudAccountId={}", cloudAccountId);

        CloudAccount account = getByAwsAccountId(cloudAccountId);
        logger.info("AWS Account ID: {}", account.getAwsAccountId());

        try {
            // Call the working API endpoint
            logger.info("Calling AutoSpotting API: GET /v1/costs?account_id={}", account.getAwsAccountId());
            CostResponse response = apiClient.getCurrentCosts(account.getAwsAccountId(), null);

            if (response != null && response.getAsgs() != null && response.getSummary() != null) {
                logger.info("✓ Retrieved cost data from API:");
                logger.info("  - ASGs: {}", response.getAsgs().size());
                logger.info("  - Total current cost: ${}/hr",
                        response.getSummary().getTotalCurrentHourlyCost() != null
                                ? String.format("%.4f", response.getSummary().getTotalCurrentHourlyCost())
                                : "0.0000");
                logger.info("  - Actual savings: ${}/hr",
                        response.getSummary().getTotalActualSavings() != null
                                ? String.format("%.4f", response.getSummary().getTotalActualSavings())
                                : "0.0000");
                logger.info("  - Potential savings: ${}/hr",
                        response.getSummary().getTotalPotentialSavings() != null
                                ? String.format("%.4f", response.getSummary().getTotalPotentialSavings())
                                : "0.0000");

                return response;
            } else {
                logger.warn("Empty or null response from AutoSpotting API");
                logger.warn("Falling back to SDK-based ASG listing");
                return buildCostResponseFromAwsSdk(account);
            }

        } catch (Exception e) {
            logger.error("❌ Failed to fetch cost data from AutoSpotting API: {}", e.getMessage());
            logger.error("Exception type: {}", e.getClass().getName());
            logger.error("Stack trace:", e);
            logger.info("Falling back to SDK-based ASG listing");
            return buildCostResponseFromAwsSdk(account);
        }
    }

    /**
     * Get historical cost data (uses Athena - skip for now if not configured)
     */
    public HistoryResponse getCostHistory(Long cloudAccountId, String start, String end, String interval) {
        logger.info("Fetching cost history from AutoSpotting API for cloudAccountId={}", cloudAccountId);
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            HistoryResponse response = apiClient.getCostsHistory(
                    account.getAwsAccountId(), start, end, interval);

            if (response != null && response.getDataPoints() != null) {
                logger.info("✓ Retrieved {} historical data points", response.getDataPoints().size());
                return response;
            } else {
                logger.warn("Empty history response from API");
                return createEmptyHistoryResponse();
            }

        } catch (Exception e) {
            logger.warn("Failed to fetch cost history from API: {}", e.getMessage());
            logger.info("Historical data requires Athena setup. Skipping for now.");
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
     * Lists ASGs with cost data from AutoSpotting API
     * Primary method - uses GET /v1/costs endpoint
     */
    public List<AutoSpottingGroupDto> listAsgsAllRegionsWithCosts(Long cloudAccountId) {
        logger.info("=== Listing ASGs with Cost Data ===");
        logger.info("CloudAccountId={}", cloudAccountId);

        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            // Get cost data from API
            logger.debug("Fetching cost data from API for account {}", account.getAwsAccountId());
            CostResponse costData = apiClient.getCurrentCosts(account.getAwsAccountId(), null);

            if (costData == null || costData.getAsgs() == null || costData.getAsgs().isEmpty()) {
                logger.warn("No ASG data from API, falling back to SDK method");
                return listAsgsAllRegions(cloudAccountId);
            }

            // Convert API response to DTOs with cost information
            List<AutoSpottingGroupDto> asgs = costData.getAsgs().stream()
                    .map(asg -> new AutoSpottingGroupDto(
                            asg.getAsgName(),
                            asg.getRegion(),
                            asg.getAutospottingEnabled() != null ? asg.getAutospottingEnabled() : false,
                            asg.getInstanceCount() != null ? asg.getInstanceCount() : 0,
                            0, // minSize - not in API response
                            0, // maxSize - not in API response
                            asg.getInstanceTypes() != null && !asg.getInstanceTypes().isEmpty()
                                    ? String.join(", ", asg.getInstanceTypes())
                                    : "N/A",
                            asg.getCurrentHourlyCost() != null ? asg.getCurrentHourlyCost() : 0.0,
                            asg.getActualHourlySavings() != null ? asg.getActualHourlySavings() : 0.0,
                            asg.getPotentialHourlySavings() != null ? asg.getPotentialHourlySavings() : 0.0,
                            asg.getSpotInstanceCount() != null ? asg.getSpotInstanceCount() : 0,
                            asg.getOndemandInstanceCount() != null ? asg.getOndemandInstanceCount() : 0))
                    .collect(Collectors.toList());

            logger.info("✓ Retrieved {} ASGs with cost data from API", asgs.size());
            logger.info("  - {} enabled for AutoSpotting",
                    asgs.stream().filter(a -> a.isEnabled).count());

            // Log each ASG for debugging
            asgs.forEach(asg -> logger.debug("  → {}: enabled={}, cost=${}/hr, savings=${}/hr",
                    asg.name, asg.isEnabled,
                    String.format("%.4f", asg.currentHourlyCost),
                    String.format("%.4f", asg.actualHourlySavings)));

            return asgs;

        } catch (Exception e) {
            logger.warn("API cost data not available, falling back to SDK-based listing: {}", e.getMessage());
            return listAsgsAllRegions(cloudAccountId);
        }
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
            logger.debug("Creating AutoSpotting ASG client for account {} region {}",
                    account.getAwsAccountId(), region);
            AutoScalingClient asgClient = awsClientProvider.getAutoSpottingAsgClient(
                    account.getAwsAccountId(), region);

            return asgClient.describeAutoScalingGroups().autoScalingGroups().stream()
                    .map(asg -> {
                        boolean isEnabled = asg.tags().stream()
                                .anyMatch(t -> t.key().equals(tagKey) && t.value().equals(tagValue));

                        long spotCount = asg.instances().stream()
                                .filter(i -> i.lifecycleStateAsString().equals("InService"))
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
                                (int) spotCount,
                                asg.instances().size() - (int) spotCount);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Failed to list ASGs in region {}: {}", region, e.getMessage());
            throw new RuntimeException("Could not connect to region " + region + ": " + e.getMessage(), e);
        }
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
     * Checks if AutoSpotting is enabled in a region
     */
    private boolean isRegionAutoSpottingEnabled(CloudAccount account, String region) {
        try {
            AutoScalingClient asgClient = awsClientProvider.getAutoSpottingAsgClient(
                    account.getAwsAccountId(), region);
            asgClient.describeAutoScalingGroups(req -> req.maxRecords(1));
            return true;
        } catch (Exception e) {
            logger.debug("✗ AutoSpotting not enabled in {}", region);
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
     * Enable AutoSpotting using API
     */
    public void enableAutoSpotting(Long cloudAccountId, String region, String asgName) {
        logger.info("Enabling AutoSpotting: account={} region={} asg={}",
                cloudAccountId, region, asgName);

        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            SuccessResponse response = apiClient.enableAsg(asgName, account.getAwsAccountId(), region);

            if (response.getSuccess()) {
                logger.info("✓ AutoSpotting enabled via API for {}", asgName);
            } else {
                throw new RuntimeException("API returned success=false: " + response.getMessage());
            }
        } catch (Exception e) {
            logger.warn("API enable failed, using fallback: {}", e.getMessage());
            toggleTag(cloudAccountId, region, asgName, tagKey, tagValue);
        }
    }

    /**
     * Disable AutoSpotting using API
     */
    public void disableAutoSpotting(Long cloudAccountId, String region, String asgName) {
        logger.info("Disabling AutoSpotting: account={} region={} asg={}",
                cloudAccountId, region, asgName);

        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            SuccessResponse response = apiClient.disableAsg(asgName, account.getAwsAccountId(), region);

            if (response.getSuccess()) {
                logger.info("✓ AutoSpotting disabled via API for {}", asgName);
            } else {
                throw new RuntimeException("API returned success=false: " + response.getMessage());
            }
        } catch (Exception e) {
            logger.warn("API disable failed, using fallback: {}", e.getMessage());
            removeTag(cloudAccountId, region, asgName, tagKey);
        }
    }

    /**
     * Legacy tag-based enable (fallback)
     */
    private void toggleTag(Long cloudAccountId, String region, String asgName, String key, String value) {
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            AutoScalingClient asgClient = awsClientProvider.getAutoSpottingAsgClient(
                    account.getAwsAccountId(), region);

            asgClient.createOrUpdateTags(req -> req.tags(
                    software.amazon.awssdk.services.autoscaling.model.Tag.builder()
                            .resourceId(asgName)
                            .resourceType("auto-scaling-group")
                            .key(key)
                            .value(value)
                            .propagateAtLaunch(false)
                            .build()));

            logger.info("✓ Enabled via direct tagging: {}", asgName);
        } catch (Exception e) {
            logger.error("Failed to add tag: {}", e.getMessage());
            throw new RuntimeException("Failed to enable AutoSpotting: " + e.getMessage(), e);
        }
    }

    /**
     * Legacy tag-based disable (fallback)
     */
    private void removeTag(Long cloudAccountId, String region, String asgName, String key) {
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            AutoScalingClient asgClient = awsClientProvider.getAutoSpottingAsgClient(
                    account.getAwsAccountId(), region);

            asgClient.deleteTags(req -> req.tags(
                    software.amazon.awssdk.services.autoscaling.model.Tag.builder()
                            .resourceId(asgName)
                            .resourceType("auto-scaling-group")
                            .key(key)
                            .build()));

            logger.info("✓ Disabled via direct tag removal: {}", asgName);
        } catch (Exception e) {
            logger.error("Failed to remove tag: {}", e.getMessage());
            throw new RuntimeException("Failed to disable AutoSpotting: " + e.getMessage(), e);
        }
    }

    // ================= ASG CONFIGURATION (API-BASED) =================

    /**
     * Get ASG configuration
     */
    public ASGConfig getAsgConfig(Long cloudAccountId, String region, String asgName) {
        logger.info("Fetching config for ASG {} in {}", asgName, region);
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            return apiClient.getAsgConfig(asgName, account.getAwsAccountId(), region);
        } catch (Exception e) {
            logger.error("Failed to fetch ASG config: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Update ASG configuration
     */
    public ASGConfig updateAsgConfig(Long cloudAccountId, String region, String asgName, ASGConfigUpdate config) {
        logger.info("Updating config for ASG {} in {}", asgName, region);
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            ASGConfig updated = apiClient.updateAsgConfig(asgName, account.getAwsAccountId(), region, config);
            logger.info("✓ Successfully updated config for {}", asgName);
            return updated;
        } catch (Exception e) {
            logger.error("Failed to update ASG config: {}", e.getMessage());
            throw new RuntimeException("Failed to update configuration: " + e.getMessage(), e);
        }
    }

    // ================= HELPER METHODS =================

    /**
     * Create empty cost response
     */
    private CostResponse createEmptyCostResponse() {
        CostResponse response = new CostResponse();
        response.setAsgs(Collections.emptyList());

        CostResponse.CostSummary summary = new CostResponse.CostSummary();
        summary.setTotalAsgCount(0);
        summary.setAutospottingEnabledCount(0);
        summary.setTotalCurrentHourlyCost(0.0);
        summary.setTotalOndemandHourlyCost(0.0);
        summary.setTotalActualSavings(0.0);
        summary.setTotalPotentialSavings(0.0);

        response.setSummary(summary);
        return response;
    }

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
     * Get events (actions history) from AutoSpotting API
     */
    public EventsResponse getEvents(Long cloudAccountId, String start, String end, String eventType, String asgName) {
        logger.info("=== Fetching Events History ===");
        logger.info("CloudAccountId={}, start={}, end={}, eventType={}, asgName={}",
                cloudAccountId, start, end, eventType, asgName);

        CloudAccount account = getByAwsAccountId(cloudAccountId);
        logger.info("AWS Account ID: {}", account.getAwsAccountId());

        try {
            // Call AutoSpotting API
            logger.info("Calling AutoSpotting Events API for account {}", account.getAwsAccountId());
            EventsResponse response = apiClient.getEvents(
                    account.getAwsAccountId(),
                    start,
                    end,
                    eventType,
                    asgName);

            if (response != null && response.getEvents() != null) {
                logger.info("✓ Retrieved {} events from API", response.getCount());

                if (response.getSummary() != null) {
                    logger.info("  - Replacements: {}", response.getSummary().getTotalReplacements());
                    logger.info("  - Interruptions: {}", response.getSummary().getTotalInterruptions());
                    logger.info("  - Total estimated savings: ${}/hr",
                            String.format("%.4f", response.getSummary().getTotalEstimatedSavings()));
                }

                return response;
            } else {
                logger.warn("Empty or null response from AutoSpotting Events API");
                return createEmptyEventsResponse(start, end);
            }

        } catch (Exception e) {
            logger.error("❌ Failed to fetch events from AutoSpotting API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch events: " + e.getMessage(), e);
        }
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
     * Get launch analytics from AutoSpotting API
     */
    public LaunchAnalyticsResponse getLaunchAnalytics(Long cloudAccountId, String start, String end) {
        logger.info("=== Fetching Launch Analytics ===");
        logger.info("CloudAccountId={}, start={}, end={}", cloudAccountId, start, end);

        CloudAccount account = getByAwsAccountId(cloudAccountId);
        logger.info("AWS Account ID: {}", account.getAwsAccountId());

        try {
            // Call AutoSpotting API
            LaunchAnalyticsResponse response = apiClient.getLaunchAnalytics(
                    account.getAwsAccountId(),
                    start,
                    end);

            if (response != null) {
                logger.info("✅ Launch analytics retrieved successfully");
                logger.info("  - Total attempts: {}", response.getTotalAttempts());
                logger.info("  - Success rate: {}%", response.getSuccessRate());
            }

            return response;

        } catch (Exception e) {
            logger.error("❌ Failed to fetch launch analytics from AutoSpotting API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch launch analytics: " + e.getMessage(), e);
        }
    }
}