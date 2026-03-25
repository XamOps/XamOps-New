package com.xammer.cloud.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AutoSpottingSettings;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.autospotting.ASGConfig;
import com.xammer.cloud.dto.autospotting.ASGConfigUpdate;
import com.xammer.cloud.dto.autospotting.ASGCostData;
import com.xammer.cloud.dto.autospotting.CostResponse;
import com.xammer.cloud.dto.autospotting.EventRecord;
import com.xammer.cloud.dto.autospotting.EventsResponse;
import com.xammer.cloud.dto.autospotting.EventsResponse.EventsSummary;
import com.xammer.cloud.dto.autospotting.HistoryResponse;
import com.xammer.cloud.dto.autospotting.LaunchAnalyticsResponse;
import com.xammer.cloud.dto.autospotting.RiSpCoverageResponse;
import com.xammer.cloud.dto.autospotting.SpotInstanceDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;

import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetReservationCoverageResponse;
import software.amazon.awssdk.services.costexplorer.model.GetSavingsPlansCoverageResponse;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

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
    private final com.xammer.cloud.repository.AsgSpotConfigRepository asgSpotConfigRepository;
    private final RedisCacheService redisCacheService;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    // Redis cache key prefixes for SpotAutomation page data
    private static final String CACHE_KEY_ASG_LIST          = "autospotting-asgs-";
    private static final String CACHE_KEY_COST               = "autospotting-cost-";
    private static final String CACHE_KEY_INSTANCES          = "autospotting-instances-";
    private static final String CACHE_KEY_CUSTOMER_SAVINGS   = "autospotting-admin-customer-savings";
    private static final int    CACHE_TTL_MINUTES            = 240; // 4 hours
    private static final int    CACHE_TTL_ADMIN_MINUTES      = 30;  // 30 min for admin aggregates

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
            com.xammer.cloud.repository.AutoSpottingSettingsRepository settingsRepository,
            com.xammer.cloud.repository.AsgSpotConfigRepository asgSpotConfigRepository,
            RedisCacheService redisCacheService,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.spotPricingService = spotPricingService;
        this.eventRepository = eventRepository;
        this.savingsRepository = savingsRepository;
        this.settingsRepository = settingsRepository;
        this.asgSpotConfigRepository = asgSpotConfigRepository;
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        logger.info("AutoSpottingService initialized with table={} tagKey={} tagValue={} namespace={}",
                tableName, tagKey, tagValue, metricNamespace);
    }

    // ================= HELPER METHODS =================
    /**
     * Resolve CloudAccount by AWS account ID ✅ UPDATED: Handles leading zero
     * mismatch (e.g. input 157... finds DB 0157...)
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
     * Registers the customer AWS account ID into the Main Account's DynamoDB
     * table. AND updates the local PostgreSQL settings to enabled=true.
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
     * Get current cost data using AWS SDK + SpotPricingService (in-house).
     * Convenience overload — reads from Redis cache.
     */
    public CostResponse getCostData(Long cloudAccountId) {
        return getCostData(cloudAccountId, false);
    }

    /**
     * Get current cost data using AWS SDK + SpotPricingService (in-house).
     * @param forceRefresh when true, bypasses Redis cache and fetches live from AWS.
     */
    public CostResponse getCostData(Long cloudAccountId, boolean forceRefresh) {
        String cacheKey = CACHE_KEY_COST + cloudAccountId;
        String dbKey = "AUTOSPOTTING_COST::" + cloudAccountId;

        if (!forceRefresh) {
            Optional<CostResponse> cached = redisCacheService.get(cacheKey, CostResponse.class);
            if (cached.isPresent()) {
                logger.info("--- AUTOSPOTTING COST: loaded from Redis for cloudAccountId={} ---", cloudAccountId);
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    CostResponse dbData = objectMapper.readValue(dbCached.get().getJsonData(), CostResponse.class);
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, CACHE_TTL_MINUTES);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", dbKey);
                        return dbData;
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", dbKey, dbEx.getMessage());
            }
        }

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

            redisCacheService.put(CACHE_KEY_COST + cloudAccountId, response, CACHE_TTL_MINUTES);
            try {
                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                snapshot.setCacheKey(dbKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(response));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
            }
            return response;
        } catch (Exception e) {
            logger.error("Failed to build cost data from SDK: {}", e.getMessage(), e);
            CostResponse fallback = buildCostResponseFromAwsSdk(account);
            if (fallback != null) {
                redisCacheService.put(CACHE_KEY_COST + cloudAccountId, fallback, CACHE_TTL_MINUTES);
                try {
                    CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                    snapshot.setCacheKey(dbKey);
                    snapshot.setJsonData(objectMapper.writeValueAsString(fallback));
                    snapshot.setLastUpdated(java.time.LocalDateTime.now());
                    cachedDataRepository.save(snapshot);
                } catch (Exception dbEx) {
                    logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
                }
            }
            return fallback;
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

            // If DailySpotSavings is empty, fall back to SpotOptimizationEvent grouped by date.
            // This avoids any AWS API calls (especially Cost Explorer) and still gives a
            // meaningful chart from day one.
            if (dataPoints.isEmpty()) {
                try {
                    Instant iStart = startDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                    Instant iEnd = endDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                    List<SpotOptimizationEvent> spotEvents
                            = eventRepository.findByAccountIdAndEventTimeBetweenAndStatusOrderByEventTimeDesc(
                                    account.getId(), iStart, iEnd, "SUCCESS");

                    java.util.Map<LocalDate, Double> savingsByDate = spotEvents.stream()
                            .filter(e -> e.getHourlySavings() != null && e.getHourlySavings() > 0)
                            .collect(Collectors.groupingBy(
                                    e -> e.getEventTime().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                                    Collectors.summingDouble(SpotOptimizationEvent::getHourlySavings)
                            ));

                    savingsByDate.entrySet().stream()
                            .sorted(java.util.Map.Entry.comparingByKey())
                            .forEach(entry -> {
                                HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
                                dp.setTimestamp(entry.getKey().toString());
                                dp.setActualHourlySavings(entry.getValue());
                                dp.setPotentialHourlySavings(0.0);
                                dp.setCurrentHourlyCost(0.0);
                                dp.setOndemandHourlyCost(0.0);
                                dp.setSpotInstanceCount(0);
                                dp.setOndemandInstanceCount(0);
                                dataPoints.add(dp);
                            });

                    if (!dataPoints.isEmpty()) {
                        logger.info("Built {} history data point(s) from SpotOptimizationEvent table", dataPoints.size());
                    }
                } catch (Exception ex) {
                    logger.warn("Could not build history from SpotOptimizationEvent: {}", ex.getMessage());
                }
            }

            // Fetch real-time savings rate once — used for today AND to fill historical gaps
            // where the scheduler didn't run (e.g. dev server was down at 11:59 PM).
            double activeSavingsRate = 0.0;
            double activePotentialRate = 0.0;
            int spotCount = 0;
            double currentCost = 0.0;
            double onDemandCost = 0.0;
            try {
                CostResponse currentCosts = getCostData(cloudAccountId);
                if (currentCosts != null && currentCosts.getSummary() != null) {
                    activeSavingsRate = currentCosts.getSummary().getTotalActualSavings();
                    activePotentialRate = currentCosts.getSummary().getTotalPotentialSavings();
                    spotCount = currentCosts.getSummary().getAutospottingEnabledCount();
                    currentCost = currentCosts.getSummary().getTotalCurrentHourlyCost();
                    onDemandCost = currentCosts.getSummary().getTotalOndemandHourlyCost();
                }
            } catch (Exception e) {
                logger.warn("Failed to fetch real-time cost data for graph: {}", e.getMessage());
            }

            // Each data point represents DAILY savings (hourlyRate × 24) so the chart
            // shows meaningful dollar amounts instead of tiny per-hour fractions.
            // The scheduler already writes hourlyRate×24 to DailySpotSavings.amountSaved,
            // so we apply the same conversion to real-time gap-fills for consistency.
            double activeDailySavings = activeSavingsRate * 24;
            double activeDailyPotential = activePotentialRate * 24;

            // Gap-fill: for days with no DB record, use the current live daily savings if the
            // ASG is actively saving (scheduler may not have run those days, e.g. dev server down).
            LocalDate today = LocalDate.now();
            long rangeDays = startDate.until(endDate, java.time.temporal.ChronoUnit.DAYS) + 1;
            if (rangeDays <= 90) {
                java.util.Set<LocalDate> covered = dataPoints.stream()
                        .map(dp -> LocalDate.parse(dp.getTimestamp().substring(0, 10)))
                        .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
                LocalDate cur = startDate;
                while (!cur.isAfter(endDate)) {
                    if (!covered.contains(cur)) {
                        HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
                        dp.setTimestamp(cur.toString());
                        boolean isPastGap = cur.isBefore(today) && activeDailySavings > 0;
                        dp.setActualHourlySavings(isPastGap ? activeDailySavings : 0.0);
                        dp.setPotentialHourlySavings(isPastGap ? activeDailyPotential : 0.0);
                        dp.setCurrentHourlyCost(isPastGap ? currentCost * 24 : 0.0);
                        dp.setOndemandHourlyCost(isPastGap ? onDemandCost * 24 : 0.0);
                        dp.setSpotInstanceCount(isPastGap ? spotCount : 0);
                        dp.setOndemandInstanceCount(0);
                        dataPoints.add(dp);
                    }
                    cur = cur.plusDays(1);
                }
                dataPoints.sort(java.util.Comparator.comparing(dp -> LocalDate.parse(dp.getTimestamp().substring(0, 10))));
            }

            // Override today with live data converted to daily savings
            if (!endDate.isBefore(today)) {
                HistoryResponse.DataPoint todayDp = new HistoryResponse.DataPoint();
                todayDp.setTimestamp(today.toString());
                todayDp.setActualHourlySavings(activeDailySavings);
                todayDp.setPotentialHourlySavings(activeDailyPotential);
                todayDp.setSpotInstanceCount(spotCount);
                todayDp.setCurrentHourlyCost(currentCost * 24);
                todayDp.setOndemandHourlyCost(onDemandCost * 24);
                todayDp.setOndemandInstanceCount(0);

                dataPoints.removeIf(dp -> dp.getTimestamp().startsWith(today.toString()));
                dataPoints.add(todayDp);
                dataPoints.sort(java.util.Comparator.comparing(dp -> LocalDate.parse(dp.getTimestamp().substring(0, 10))));
            }

            response.setDataPoints(dataPoints);

            // totalSavings = sum of daily savings across the period (period savings card)
            // avg/peak are converted back to $/hr for the "Avg Hourly" and "Peak /hr" cards
            HistoryResponse.HistorySummary summary = new HistoryResponse.HistorySummary();
            double totalSavings = dataPoints.stream()
                    .mapToDouble(dp -> dp.getActualHourlySavings() != null ? dp.getActualHourlySavings() : 0.0)
                    .sum();
            double peakDailySavings = dataPoints.stream()
                    .mapToDouble(dp -> dp.getActualHourlySavings() != null ? dp.getActualHourlySavings() : 0.0)
                    .max().orElse(0.0);
            double totalPotential = dataPoints.stream()
                    .mapToDouble(dp -> dp.getPotentialHourlySavings() != null ? dp.getPotentialHourlySavings() : 0.0)
                    .sum();
            // averageHourlySavings and peakSavings displayed with "/hr" label — divide daily back to hourly
            double avgHourlySavings = dataPoints.isEmpty() ? 0.0 : (totalSavings / dataPoints.size()) / 24.0;
            double peakHourlySavings = peakDailySavings / 24.0;

            summary.setTotalActualSavings(totalSavings);
            summary.setTotalPotentialSavings(totalPotential);
            summary.setAverageHourlyCost(0.0);
            summary.setAverageHourlySavings(avgHourlySavings);
            summary.setPeakSavings(peakHourlySavings);
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
     * Lists ASGs across regions where AutoSpotting member stack is deployed.
     * Convenience overload — reads from Redis cache.
     */
    public List<AutoSpottingGroupDto> listAsgsAllRegions(Long cloudAccountId) {
        return listAsgsAllRegions(cloudAccountId, false);
    }

    /**
     * Lists ASGs across regions where AutoSpotting member stack is deployed.
     * @param forceRefresh when true, bypasses Redis cache and fetches live from AWS.
     */
    public List<AutoSpottingGroupDto> listAsgsAllRegions(Long cloudAccountId, boolean forceRefresh) {
        String cacheKey = CACHE_KEY_ASG_LIST + cloudAccountId;

        if (!forceRefresh) {
            Optional<List<AutoSpottingGroupDto>> cached = redisCacheService.get(cacheKey,
                    new com.fasterxml.jackson.core.type.TypeReference<List<AutoSpottingGroupDto>>() {});
            if (cached.isPresent()) {
                logger.info("--- AUTOSPOTTING ASGS: loaded from Redis for cloudAccountId={} ---", cloudAccountId);
                return cached.get();
            }
        }

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

            if (!allAsgs.isEmpty()) {
                redisCacheService.put(CACHE_KEY_ASG_LIST + cloudAccountId, allAsgs, CACHE_TTL_MINUTES);
            }
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

                        // Detect EKS-managed ASGs:
                        // Primary: AWS automatically tags EKS node group ASGs with "eks:cluster-name"
                        //          or "kubernetes.io/cluster/<name>=owned"
                        // Fallback: name prefix "eks-" (unreliable for custom-named node groups)
                        boolean eksManaged = asg.tags().stream().anyMatch(t ->
                                t.key().startsWith("eks:") ||
                                t.key().startsWith("kubernetes.io/cluster/") ||
                                t.key().equals("eks:nodegroup-name"))
                                || (asg.autoScalingGroupName() != null
                                        && asg.autoScalingGroupName().toLowerCase().startsWith("eks-"));

                        long spotCount = asg.instances().stream()
                                .filter(i -> i.lifecycleStateAsString().equals("InService"))
                                .filter(i -> i.instanceId().startsWith("i-")) // Basic check
                                // Note: We can't determine if it's spot vs OD just from ASG API details safely
                                // without describing instances
                                // But for now we count all as generic instances, spot determination happens in
                                // getSpotInstances
                                .count();

                        AutoSpottingGroupDto dto = new AutoSpottingGroupDto(
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
                        dto.eksManaged = eksManaged;
                        return dto;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Failed to list ASGs in region {}: {}", region, e.getMessage());
            throw new RuntimeException("Could not connect to region " + region + ": " + e.getMessage(), e);
        }
    }

    // ================= INSTANCE LISTING =================
    /**
     * Get list of all instances in AutoSpotting-enabled ASGs This methods
     * performs DescribeInstances calls to get details like lifecycle
     * (Spot/On-Demand)
     */
    /** Convenience overload — reads from Redis cache. */
    public List<SpotInstanceDto> getSpotInstances(Long cloudAccountId) {
        return getSpotInstances(cloudAccountId, false);
    }

    /**
     * @param forceRefresh when true, bypasses Redis cache and fetches live from AWS.
     */
    public List<SpotInstanceDto> getSpotInstances(Long cloudAccountId, boolean forceRefresh) {
        String cacheKey = CACHE_KEY_INSTANCES + cloudAccountId;

        if (!forceRefresh) {
            Optional<List<SpotInstanceDto>> cached = redisCacheService.get(cacheKey,
                    new com.fasterxml.jackson.core.type.TypeReference<List<SpotInstanceDto>>() {});
            if (cached.isPresent()) {
                logger.info("--- AUTOSPOTTING INSTANCES: loaded from Redis for cloudAccountId={} ---", cloudAccountId);
                return cached.get();
            }
        }

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
                                if (instance.state().nameAsString().equalsIgnoreCase("terminated")
                                        || instance.state().nameAsString().equalsIgnoreCase("shutting-down")) {
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
                                        .vpcId(instance.vpcId())
                                        .subnetId(instance.subnetId())
                                        .imageId(instance.imageId())
                                        .privateIpAddress(instance.privateIpAddress())
                                        .publicIpAddress(instance.publicIpAddress())
                                        .securityGroups(instance.securityGroups().stream()
                                                .map(sg -> SpotInstanceDto.SecurityGroupInfo.builder()
                                                        .groupId(sg.groupId())
                                                        .groupName(sg.groupName())
                                                        .build())
                                                .collect(Collectors.toList()))
                                        .build());
                            }
                        }
                    }

                } catch (Exception e) {
                    logger.error("Error scanning region {}: {}", region, e.getMessage());
                }
            }

            if (!allInstances.isEmpty()) {
                redisCacheService.put(CACHE_KEY_INSTANCES + cloudAccountId, allInstances, CACHE_TTL_MINUTES);
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
     * Checks if the account's cross-account role can access AutoScaling in the
     * given region.
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
     * Enable AutoSpotting: 1. Adds spot-enabled tag as a marker 2. Updates ASG
     * MixedInstancesPolicy so new/replacement instances launch as Spot 3.
     * Starts an instance refresh to gradually replace existing OD instances
     */
    public void enableAutoSpotting(Long cloudAccountId, String region, String asgName) {
        logger.info("Enabling AutoSpotting: account={} region={} asg={}", cloudAccountId, region, asgName);

        CloudAccount account = getByAwsAccountId(cloudAccountId);

        // Guard: EKS-managed ASGs cannot be converted — same lock as GKE MIGs and AKS VMSS
        // Primary: check AWS tags (eks:cluster-name, kubernetes.io/cluster/*, eks:nodegroup-name)
        // Fallback: name prefix "eks-"
        try {
            AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(account, region);
            var describeResp = asgClient.describeAutoScalingGroups(r -> r.autoScalingGroupNames(asgName));
            if (!describeResp.autoScalingGroups().isEmpty()) {
                var tags = describeResp.autoScalingGroups().get(0).tags();
                boolean eksManaged = tags.stream().anyMatch(t ->
                        t.key().startsWith("eks:") ||
                        t.key().startsWith("kubernetes.io/cluster/") ||
                        t.key().equals("eks:nodegroup-name"))
                        || (asgName.toLowerCase().startsWith("eks-"));
                if (eksManaged) {
                    logger.warn("⛔ [AutoSpotting] Skipping EKS-managed ASG {} — EKS controls its lifecycle. " +
                            "To use Spot, create a Spot node group in the EKS console.", asgName);
                    return;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not verify EKS status for ASG {}, proceeding: {}", asgName, e.getMessage());
        }
        // NEW FEATURE: Check RI/SP commitment utilization if feature is enabled
        AutoSpottingSettings settings = settingsRepository.findById(account.getId()).orElse(null);
        if (settings != null && settings.isRespectRiSpCommitments()) {
            logger.info("RI/SP Respect is ENABLED for account {} - checking utilization before enabling Spot", cloudAccountId);
            
            try {
                RiSpCoverageResponse rispCoverage = getRiSpCoverage(cloudAccountId, false);
                double riUtilization = rispCoverage.getTotalRiUtilizationPercent() != null 
                    ? rispCoverage.getTotalRiUtilizationPercent() : 0.0;
                double spUtilization = rispCoverage.getTotalSpUtilizationPercent() != null 
                    ? rispCoverage.getTotalSpUtilizationPercent() : 0.0;
                double avgUtilization = (riUtilization + spUtilization) / 2.0;
                
                int threshold = settings.getRiSpUtilizationThreshold();
                
                logger.info("RI/SP Utilization Check: RI={}%, SP={}%, Average={}%, Threshold={}%", 
                    riUtilization, spUtilization, avgUtilization, threshold);
                
                if (avgUtilization < threshold) {
                    String message = String.format(
                        "Cannot enable Spot for ASG '%s': RI/SP commitments are underutilized (%.1f%% < %d%% threshold). " +
                        "Using On-Demand to maximize commitment value. To override, disable 'Respect RI/SP Commitments' in settings.",
                        asgName, avgUtilization, threshold
                    );
                    logger.warn(message);
                    throw new RuntimeException(message);
                }
                
                logger.info("✓ RI/SP utilization check passed ({}% >= {}%) - proceeding with Spot conversion", 
                    avgUtilization, threshold);
                    
            } catch (Exception e) {
                if (e.getMessage().contains("Cannot enable Spot")) {
                    throw e; // Re-throw our own exception
                }
                logger.warn("Failed to check RI/SP coverage, proceeding anyway: {}", e.getMessage());
            }
        } else {
            logger.info("RI/SP Respect is DISABLED for account {} - proceeding with Spot conversion without RI/SP check", 
                cloudAccountId);
        }


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

            // Get configured instance types for Spot diversification
            List<String> configuredTypes = getConfiguredInstanceTypes(cloudAccountId, region, asgName);
            List<software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides> overrides = null;
            if (!configuredTypes.isEmpty()) {
                overrides = configuredTypes.stream()
                        .map(type -> software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides.builder()
                        .instanceType(type)
                        .build())
                        .collect(Collectors.toList());
                logger.info("Using configured instance types for {}: {}", asgName, String.join(", ", configuredTypes));
            }

            // Get Spot/On-Demand configuration
            Map<String, Object> spotConfig = getSpotConfig(cloudAccountId, region, asgName);
            int onDemandBase = 0;
            int onDemandPercentage = 0;

            String configMode = (String) spotConfig.get("configMode");
            if ("percentage".equals(configMode)) {
                int spotPercentage = (Integer) spotConfig.get("spotPercentage");
                onDemandPercentage = 100 - spotPercentage;
                logger.info("Using percentage mode for {}: {}% Spot ({}% On-Demand)",
                        asgName, spotPercentage, onDemandPercentage);
            } else if ("count".equals(configMode)) {
                onDemandBase = (Integer) spotConfig.get("onDemandCount");
                logger.info("Using count mode for {}: {} On-Demand base (rest Spot)",
                        asgName, onDemandBase);
            }

            software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy mixedPolicy = null;

            if (asg.mixedInstancesPolicy() != null && asg.mixedInstancesPolicy().launchTemplate() != null) {
                // ASG already has a MixedInstancesPolicy — update instancesDistribution ONLY.
                // Do NOT re-specify the launch template: AWS preserves the existing one,
                // and omitting it avoids the ec2:UseLaunchTemplate IAM check.
                final int finalOnDemandBase = onDemandBase;
                final int finalOnDemandPercentage = onDemandPercentage;
                mixedPolicy = software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy.builder()
                        .instancesDistribution(d -> d
                        .onDemandBaseCapacity(finalOnDemandBase)
                        .onDemandPercentageAboveBaseCapacity(finalOnDemandPercentage)
                        .spotAllocationStrategy("capacity-optimized"))
                        .build();

            } else if (asg.launchTemplate() != null) {
                // Has a standalone LaunchTemplate — wrap it in MixedInstancesPolicy
                var lt = asg.launchTemplate();
                final var finalOverrides = overrides;
                final int finalOnDemandBase = onDemandBase;
                final int finalOnDemandPercentage = onDemandPercentage;
                mixedPolicy = software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy.builder()
                        .launchTemplate(ltp -> ltp
                        .launchTemplateSpecification(lts -> lts
                        .launchTemplateId(lt.launchTemplateId())
                        .version(lt.version() != null ? lt.version() : "$Default"))
                        .overrides(finalOverrides != null ? finalOverrides : Collections.emptyList()))
                        .instancesDistribution(d -> d
                        .onDemandBaseCapacity(finalOnDemandBase)
                        .onDemandPercentageAboveBaseCapacity(finalOnDemandPercentage)
                        .spotAllocationStrategy("capacity-optimized"))
                        .build();

            } else {
                logger.warn("ASG {} uses a LaunchConfiguration (not LaunchTemplate). "
                        + "Cannot configure MixedInstancesPolicy. Tag was applied but Spot NOT enabled.", asgName);
                return;
            }

            // 3. Apply the updated MixedInstancesPolicy
            final var finalPolicy = mixedPolicy;
            asgClient.updateAutoScalingGroup(req -> req
                    .autoScalingGroupName(asgName)
                    .mixedInstancesPolicy(finalPolicy));
            logger.info("✓ MixedInstancesPolicy updated for {}: OnDemandBase={}, OnDemandPercentage={}%, strategy=capacity-optimized",
                    asgName, onDemandBase, onDemandPercentage);

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
                                // Calculate real savings: OD price minus current Spot price
                                double odPrice = 0.0;
                                double spotPrice = 0.0;
                                try {
                                    odPrice = spotPricingService.getHourlyOnDemandPrice(itype, region);
                                    spotPrice = spotPricingService.getHourlySpotPrice(itype, region, account);
                                } catch (Exception priceEx) {
                                    logger.debug("Could not fetch prices for {}: {}", itype, priceEx.getMessage());
                                }
                                double savings = Math.max(0.0, odPrice - spotPrice);
                                SpotOptimizationEvent spotEv = new SpotOptimizationEvent();
                                spotEv.setAccount(account);
                                spotEv.setAsgName(asgName);
                                spotEv.setRegion(region);
                                spotEv.setNewInstanceId(iid);
                                spotEv.setNewInstanceType(itype);
                                spotEv.setStatus("SUCCESS");
                                spotEv.setHourlySavings(savings);
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

            // Evict ASG list and cost caches so next fetch reflects updated enabled state
            redisCacheService.evict(CACHE_KEY_ASG_LIST + cloudAccountId);
            redisCacheService.evict(CACHE_KEY_COST + cloudAccountId);

        } catch (Exception e) {
            logger.error("Failed to enable AutoSpotting for {}: {}", asgName, e.getMessage(), e);
            throw new RuntimeException("Failed to enable AutoSpotting: " + e.getMessage(), e);
        }
    }

    /**
     * Backfill SUCCESS events for any Spot instances currently running in the
     * ASG that are not yet recorded in the database.
     *
     * This is a non-disruptive, user-triggered operation that fills the gap
     * when instances launched during instance refresh (after enable) before the
     * EventBridge pipeline was fully working.
     *
     * @return number of new events backfilled
     */
    public int backfillSpotEvents(Long cloudAccountId, String region, String asgName) {
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(account, region);
        var asgResponse = asgClient.describeAutoScalingGroups(req -> req.autoScalingGroupNames(asgName));
        if (asgResponse.autoScalingGroups().isEmpty()) {
            throw new RuntimeException("ASG not found: " + asgName);
        }

        var asg = asgResponse.autoScalingGroups().get(0);
        List<String> instanceIds = asg.instances().stream()
                .map(software.amazon.awssdk.services.autoscaling.model.Instance::instanceId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toList());

        if (instanceIds.isEmpty()) {
            return 0;
        }

        Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);
        var describeResp = ec2Client.describeInstances(req -> req.instanceIds(instanceIds));

        int backfilled = 0;
        for (var reservation : describeResp.reservations()) {
            for (var inst : reservation.instances()) {
                if (!software.amazon.awssdk.services.ec2.model.InstanceLifecycleType.SPOT
                        .equals(inst.instanceLifecycle())) {
                    continue;
                }

                String instanceId = inst.instanceId();
                if (eventRepository.existsByAccountIdAndNewInstanceId(account.getId(), instanceId)) {
                    continue; // already recorded, skip
                }

                String instanceType = inst.instanceTypeAsString();
                double savings = 0.0;
                try {
                    double odPrice = spotPricingService.getHourlyOnDemandPrice(instanceType, region);
                    double spotPrice = spotPricingService.getHourlySpotPrice(instanceType, region, account);
                    savings = Math.max(0.0, odPrice - spotPrice);
                } catch (Exception e) {
                    logger.debug("Could not fetch prices for {}: {}", instanceType, e.getMessage());
                }

                SpotOptimizationEvent event = new SpotOptimizationEvent();
                event.setAccount(account);
                event.setAsgName(asgName);
                event.setRegion(region);
                event.setNewInstanceId(instanceId);
                event.setNewInstanceType(instanceType);
                event.setHourlySavings(savings);
                event.setStatus("SUCCESS");
                event.setDetails("Spot instance detected via manual sync: " + instanceId
                        + " (" + instanceType + "). Savings: $"
                        + String.format("%.4f", savings) + "/hr.");
                eventRepository.save(event);
                backfilled++;

                logger.info("✓ Sync backfilled Spot instance {} ({}) in ASG {} [account={} region={}]",
                        instanceId, instanceType, asgName, account.getAwsAccountId(), region);
            }
        }

        logger.info("Sync complete: {} new Spot event(s) backfilled for ASG {} [account={} region={}]",
                backfilled, asgName, account.getAwsAccountId(), region);
        return backfilled;
    }

    // ================= INSTANCE TYPE CONFIGURATION =================
    /**
     * Get configured instance types for an ASG. Returns alternative instance
     * types for Spot diversification.
     */
    public List<String> getConfiguredInstanceTypes(Long cloudAccountId, String region, String asgName) {
        CloudAccount account = getByAwsAccountId(cloudAccountId);
        return asgSpotConfigRepository
                .findByAccountIdAndRegionAndAsgName(account.getId(), region, asgName)
                .map(config -> {
                    if (config.getAllowedInstanceTypes() != null && !config.getAllowedInstanceTypes().isEmpty()) {
                        return Arrays.asList(config.getAllowedInstanceTypes().split(","));
                    }
                    return Collections.<String>emptyList();
                })
                .orElse(Collections.<String>emptyList());
    }

    /**
     * Save instance type configuration for an ASG. Stores alternative instance
     * types that will be used when enabling AutoSpotting.
     * @param applyImmediately if true, applies config to running ASG (if AutoSpotting enabled)
     */
    public void saveInstanceTypeConfig(Long cloudAccountId, String region, String asgName, List<String> instanceTypes,
                                       boolean applyImmediately) {
        CloudAccount account = getByAwsAccountId(cloudAccountId);
        com.xammer.cloud.domain.AsgSpotConfig config = asgSpotConfigRepository
                .findByAccountIdAndRegionAndAsgName(account.getId(), region, asgName)
                .orElse(new com.xammer.cloud.domain.AsgSpotConfig());

        // Capture old instance types for audit log
        String oldTypes = config.getAllowedInstanceTypes();
        String newTypes = instanceTypes != null ? String.join(",", instanceTypes) : null;

        config.setAccount(account);
        config.setRegion(region);
        config.setAsgName(asgName);
        config.setAllowedInstanceTypes(newTypes);
        asgSpotConfigRepository.save(config);

        logger.info("✓ Instance type config saved for ASG {} in {}: types={}",
                asgName, region, config.getAllowedInstanceTypes());

        // Record audit event for instance type configuration change
        try {
            String changeDescription;
            if (oldTypes == null || oldTypes.isEmpty()) {
                changeDescription = String.format("CONFIG_SAVE: Instance types configured: [%s]", newTypes != null ? newTypes : "none");
            } else if (newTypes == null || newTypes.isEmpty()) {
                changeDescription = String.format("CONFIG_SAVE: Instance types removed (was: [%s])", oldTypes);
            } else if (!oldTypes.equals(newTypes)) {
                changeDescription = String.format("CONFIG_SAVE: Instance types updated: [%s]→[%s]", oldTypes, newTypes);
            } else {
                changeDescription = "CONFIG_SAVE: Instance types unchanged";
            }

            SpotOptimizationEvent ev = new SpotOptimizationEvent();
            ev.setAccount(account);
            ev.setAsgName(asgName);
            ev.setRegion(region);
            ev.setStatus("SUCCESS");
            ev.setDetails(changeDescription + (applyImmediately ? " (Applied immediately)" : " (Not applied - save only)"));
            ev.setEventTime(java.time.Instant.now());
            eventRepository.save(ev);
        } catch (Exception e) {
            logger.error("Failed to record instance type config event: {}", e.getMessage());
        }

        // Apply to running ASG if requested — catch ALL exceptions so the DB save
        // is never rolled back by an AWS API failure during apply.
        if (applyImmediately) {
            try {
                applySpotConfiguration(cloudAccountId, region, asgName);
            } catch (IllegalStateException e) {
                logger.info("Config saved but not applied - AutoSpotting not enabled: {}", e.getMessage());
            } catch (Exception e) {
                logger.warn("Config saved but apply failed (AWS API error): {}", e.getMessage());
            }
        }
    }

    // ================= SPOT/ON-DEMAND CONFIGURATION =================

    /**
     * Get Spot/On-Demand configuration for an ASG.
     * Returns spotPercentage (0-100) or onDemandCount (fixed number).
     */
    public Map<String, Object> getSpotConfig(Long cloudAccountId, String region, String asgName) {
        CloudAccount account = getByAwsAccountId(cloudAccountId);
        return asgSpotConfigRepository
                .findByAccountIdAndRegionAndAsgName(account.getId(), region, asgName)
                .map(config -> {
                    Map<String, Object> result = new HashMap<>();
                    if (config.getSpotPercentage() != null) {
                        result.put("configMode", "percentage");
                        result.put("spotPercentage", config.getSpotPercentage());
                    } else if (config.getOnDemandCount() != null) {
                        result.put("configMode", "count");
                        result.put("onDemandCount", config.getOnDemandCount());
                    } else {
                        // Default: 100% Spot (percentage mode)
                        result.put("configMode", "percentage");
                        result.put("spotPercentage", 100);
                    }
                    return result;
                })
                .orElseGet(() -> {
                    // No config found - default to 100% Spot
                    Map<String, Object> result = new HashMap<>();
                    result.put("configMode", "percentage");
                    result.put("spotPercentage", 100);
                    return result;
                });
    }

    /**
     * Save Spot/On-Demand configuration for an ASG.
     * Either spotPercentage (0-100) OR onDemandCount (fixed number) should be provided.
     * @param applyImmediately if true, applies config to running ASG (if AutoSpotting enabled)
     */
    public void saveSpotConfig(Long cloudAccountId, String region, String asgName,
                                Integer spotPercentage, Integer onDemandCount,
                                boolean applyImmediately) {
        CloudAccount account = getByAwsAccountId(cloudAccountId);
        com.xammer.cloud.domain.AsgSpotConfig config = asgSpotConfigRepository
                .findByAccountIdAndRegionAndAsgName(account.getId(), region, asgName)
                .orElse(new com.xammer.cloud.domain.AsgSpotConfig());

        // Capture old values for audit log
        Integer oldSpotPercentage = config.getSpotPercentage();
        Integer oldOnDemandCount = config.getOnDemandCount();

        config.setAccount(account);
        config.setRegion(region);
        config.setAsgName(asgName);

        // Clear both fields first (mutually exclusive)
        config.setSpotPercentage(null);
        config.setOnDemandCount(null);

        // Set the appropriate field
        String changeDescription;
        if (spotPercentage != null) {
            config.setSpotPercentage(Math.max(0, Math.min(100, spotPercentage)));
            logger.info("✓ Spot config saved for ASG {} in {}: spotPercentage={}%",
                    asgName, region, spotPercentage);

            // Build change description
            if (oldSpotPercentage != null && !oldSpotPercentage.equals(spotPercentage)) {
                changeDescription = String.format("CONFIG_SAVE: Spot percentage changed: %d%%→%d%%", oldSpotPercentage, spotPercentage);
            } else if (oldOnDemandCount != null) {
                changeDescription = String.format("CONFIG_SAVE: Changed from count mode (OnDemand=%d) to percentage mode (Spot=%d%%)", oldOnDemandCount, spotPercentage);
            } else {
                changeDescription = String.format("CONFIG_SAVE: Spot percentage set to %d%%", spotPercentage);
            }
        } else if (onDemandCount != null) {
            config.setOnDemandCount(Math.max(0, onDemandCount));
            logger.info("✓ Spot config saved for ASG {} in {}: onDemandCount={}",
                    asgName, region, onDemandCount);

            // Build change description
            if (oldOnDemandCount != null && !oldOnDemandCount.equals(onDemandCount)) {
                changeDescription = String.format("CONFIG_SAVE: OnDemand count changed: %d→%d", oldOnDemandCount, onDemandCount);
            } else if (oldSpotPercentage != null) {
                changeDescription = String.format("CONFIG_SAVE: Changed from percentage mode (Spot=%d%%) to count mode (OnDemand=%d)", oldSpotPercentage, onDemandCount);
            } else {
                changeDescription = String.format("CONFIG_SAVE: OnDemand count set to %d", onDemandCount);
            }
        } else {
            changeDescription = "CONFIG_SAVE: Spot configuration cleared";
        }

        asgSpotConfigRepository.save(config);

        // Record audit event for Spot/On-Demand configuration change
        try {
            SpotOptimizationEvent ev = new SpotOptimizationEvent();
            ev.setAccount(account);
            ev.setAsgName(asgName);
            ev.setRegion(region);
            ev.setStatus("SUCCESS");
            ev.setDetails(changeDescription + (applyImmediately ? " (Applied immediately)" : " (Not applied - save only)"));
            ev.setEventTime(java.time.Instant.now());
            eventRepository.save(ev);
        } catch (Exception e) {
            logger.error("Failed to record spot config event: {}", e.getMessage());
        }

        // Apply to running ASG if requested — catch ALL exceptions so the DB save
        // is never rolled back by an AWS API failure during apply.
        if (applyImmediately) {
            try {
                applySpotConfiguration(cloudAccountId, region, asgName);
            } catch (IllegalStateException e) {
                logger.info("Config saved but not applied - AutoSpotting not enabled: {}", e.getMessage());
            } catch (Exception e) {
                logger.warn("Config saved but apply failed (AWS API error): {}", e.getMessage());
            }
        }
    }

    /**
     * Apply the saved Spot/On-Demand configuration to a running ASG.
     * Only works if AutoSpotting is already enabled for this ASG.
     * Updates the MixedInstancesPolicy and triggers instance refresh.
     */
    public void applySpotConfiguration(Long cloudAccountId, String region, String asgName) {
        logger.info("Applying Spot configuration to running ASG: account={} region={} asg={}",
                cloudAccountId, region, asgName);
        CloudAccount account = getByAwsAccountId(cloudAccountId);

        try {
            AutoScalingClient asgClient = awsClientProvider.getAutoSpottingAsgClient(
                    account.getAwsAccountId(), region);

            // 1. Fetch current ASG configuration
            var asgResponse = asgClient.describeAutoScalingGroups(req -> req.autoScalingGroupNames(asgName));
            if (asgResponse.autoScalingGroups().isEmpty()) {
                logger.error("ASG {} not found", asgName);
                throw new IllegalArgumentException("ASG not found: " + asgName);
            }
            var asg = asgResponse.autoScalingGroups().get(0);

            // 2. Check if AutoSpotting is enabled by verifying MixedInstancesPolicy exists
            // When AutoSpotting is enabled, it creates a MixedInstancesPolicy
            // If ASG only has a standalone LaunchTemplate, AutoSpotting isn't enabled yet
            if (asg.mixedInstancesPolicy() == null || asg.mixedInstancesPolicy().launchTemplate() == null) {
                if (asg.launchTemplate() != null) {
                    logger.warn("Cannot apply config - AutoSpotting not enabled for ASG: {} (no MixedInstancesPolicy)", asgName);
                    throw new IllegalStateException(
                            "AutoSpotting must be enabled before applying configuration changes. " +
                            "Please enable AutoSpotting first by toggling it ON.");
                } else {
                    logger.error("ASG {} uses LaunchConfiguration (not LaunchTemplate)", asgName);
                    throw new IllegalStateException(
                            "ASG must use a LaunchTemplate to apply Spot configuration. " +
                            "LaunchConfiguration is not supported.");
                }
            }

            // 3. Get configured instance types for Spot diversification
            List<String> configuredTypes = getConfiguredInstanceTypes(cloudAccountId, region, asgName);
            List<software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides> overrides = null;
            if (!configuredTypes.isEmpty()) {
                overrides = configuredTypes.stream()
                        .map(type -> software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides.builder()
                                .instanceType(type)
                                .build())
                        .collect(Collectors.toList());
                logger.info("Using configured instance types for {}: {}", asgName, String.join(", ", configuredTypes));
            }

            // 4. Get Spot/On-Demand configuration
            Map<String, Object> spotConfig = getSpotConfig(cloudAccountId, region, asgName);
            int onDemandBase = 0;
            int onDemandPercentage = 0;

            String configMode = (String) spotConfig.get("configMode");
            if ("percentage".equals(configMode)) {
                int spotPercentage = (Integer) spotConfig.get("spotPercentage");
                onDemandPercentage = 100 - spotPercentage;
                logger.info("Applying percentage mode for {}: {}% Spot ({}% On-Demand)",
                        asgName, spotPercentage, onDemandPercentage);
            } else if ("count".equals(configMode)) {
                onDemandBase = (Integer) spotConfig.get("onDemandCount");
                logger.info("Applying count mode for {}: {} On-Demand base (rest Spot)",
                        asgName, onDemandBase);
            }

            // 5. Capture existing configuration for audit logging
            var existingPolicy = asg.mixedInstancesPolicy();
            var existingLaunchTemplate = existingPolicy.launchTemplate();
            var existingLtSpec = existingLaunchTemplate.launchTemplateSpecification();

            // Capture old values for change tracking
            int oldOnDemandBase = 0;
            int oldOnDemandPercentage = 0;
            List<String> oldInstanceTypes = new java.util.ArrayList<>();

            if (existingPolicy.instancesDistribution() != null) {
                var oldDistribution = existingPolicy.instancesDistribution();
                oldOnDemandBase = oldDistribution.onDemandBaseCapacity() != null ? oldDistribution.onDemandBaseCapacity() : 0;
                oldOnDemandPercentage = oldDistribution.onDemandPercentageAboveBaseCapacity() != null
                    ? oldDistribution.onDemandPercentageAboveBaseCapacity() : 0;
            }

            if (existingLaunchTemplate.overrides() != null && !existingLaunchTemplate.overrides().isEmpty()) {
                oldInstanceTypes = existingLaunchTemplate.overrides().stream()
                    .map(o -> o.instanceType())
                    .filter(t -> t != null && !t.isEmpty())
                    .collect(Collectors.toList());
            }

            final var finalOverrides = overrides;
            final int finalOnDemandBase = onDemandBase;
            final int finalOnDemandPercentage = onDemandPercentage;

            // 6. Build updated MixedInstancesPolicy

            // Build LaunchTemplateSpecification - AWS requires EITHER ID or Name, not both
            var ltSpecBuilder = software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification.builder();
            if (existingLtSpec.launchTemplateId() != null) {
                ltSpecBuilder.launchTemplateId(existingLtSpec.launchTemplateId());
            } else if (existingLtSpec.launchTemplateName() != null) {
                ltSpecBuilder.launchTemplateName(existingLtSpec.launchTemplateName());
            }
            ltSpecBuilder.version(existingLtSpec.version() != null ? existingLtSpec.version() : "$Default");
            var ltSpec = ltSpecBuilder.build();

            software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy mixedPolicy =
                    software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy.builder()
                            .launchTemplate(ltp -> ltp
                                    .launchTemplateSpecification(ltSpec)
                                    .overrides(finalOverrides != null && !finalOverrides.isEmpty()
                                            ? finalOverrides
                                            : existingLaunchTemplate.overrides())) // Preserve existing if no new ones
                            .instancesDistribution(d -> d
                                    .onDemandBaseCapacity(finalOnDemandBase)
                                    .onDemandPercentageAboveBaseCapacity(finalOnDemandPercentage)
                                    .spotAllocationStrategy("capacity-optimized"))
                            .build();

            // 7. Apply the updated MixedInstancesPolicy
            final var finalPolicy = mixedPolicy;
            asgClient.updateAutoScalingGroup(req -> req
                    .autoScalingGroupName(asgName)
                    .mixedInstancesPolicy(finalPolicy));
            logger.info("✓ MixedInstancesPolicy updated for {}: OnDemandBase={}, OnDemandPercentage={}%",
                    asgName, onDemandBase, onDemandPercentage);

            // 8. Start instance refresh to apply changes
            try {
                asgClient.startInstanceRefresh(req -> req
                        .autoScalingGroupName(asgName)
                        .preferences(p -> p
                                .minHealthyPercentage(80)
                                .instanceWarmup(300)));
                logger.info("✓ Instance refresh started for {} to apply new Spot configuration", asgName);
            } catch (Exception e) {
                // Instance refresh failure is non-fatal - policy is already updated
                logger.warn("Could not start instance refresh for {} (policy already updated): {}",
                       asgName, e.getMessage());
            }

            // 9. Record detailed audit event with before/after changes
            try {
                StringBuilder changeLog = new StringBuilder("CONFIG_UPDATE: Configuration applied");

                // Track distribution changes
                if (oldOnDemandBase != onDemandBase || oldOnDemandPercentage != onDemandPercentage) {
                    changeLog.append(" | Spot/On-Demand: ");
                    if (oldOnDemandBase != onDemandBase) {
                        changeLog.append(String.format("OnDemandBase %d→%d", oldOnDemandBase, onDemandBase));
                    }
                    if (oldOnDemandPercentage != onDemandPercentage) {
                        if (oldOnDemandBase != onDemandBase) changeLog.append(", ");
                        int oldSpotPct = 100 - oldOnDemandPercentage;
                        int newSpotPct = 100 - onDemandPercentage;
                        changeLog.append(String.format("Spot %d%%→%d%%", oldSpotPct, newSpotPct));
                    }
                }

                // Track instance type changes
                if (configuredTypes != null && !configuredTypes.isEmpty()) {
                    boolean typesChanged = !oldInstanceTypes.equals(configuredTypes);
                    if (typesChanged) {
                        changeLog.append(" | Instance Types: ");
                        if (oldInstanceTypes.isEmpty()) {
                            changeLog.append(String.format("Added [%s]", String.join(", ", configuredTypes)));
                        } else {
                            changeLog.append(String.format("[%s]→[%s]",
                                String.join(", ", oldInstanceTypes),
                                String.join(", ", configuredTypes)));
                        }
                    }
                }

                SpotOptimizationEvent ev = new SpotOptimizationEvent();
                ev.setAccount(account);
                ev.setAsgName(asgName);
                ev.setRegion(region);
                ev.setStatus("SUCCESS");
                ev.setDetails(changeLog.toString());
                ev.setEventTime(java.time.Instant.now());
                eventRepository.save(ev);
                logger.info("✓ Audit event recorded: {}", changeLog.toString());
            } catch (Exception e) {
                logger.error("Failed to record config update event: {}", e.getMessage());
            }

        } catch (Exception e) {
            logger.error("Failed to apply Spot configuration for {}: {}", asgName, e.getMessage(), e);

            // Record failure event
            try {
                SpotOptimizationEvent ev = new SpotOptimizationEvent();
                ev.setAccount(account);
                ev.setAsgName(asgName);
                ev.setRegion(region);
                ev.setStatus("FAILED");
                ev.setDetails("CONFIG_UPDATE: Failed to update - " + e.getMessage());
                ev.setEventTime(java.time.Instant.now());
                eventRepository.save(ev);
            } catch (Exception auditError) {
                logger.error("Failed to record config update failure event: {}", auditError.getMessage());
            }

            throw e;
        }
    }

    /**
     * Disable AutoSpotting: 1. Removes the spot-enabled tag 2. Sets
     * OnDemandPercentageAboveBaseCapacity=100 so new instances launch as OD
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
                if (asg.mixedInstancesPolicy() != null) {
                    // Update instancesDistribution ONLY back to 100% OD.
                    // Do NOT re-specify the launch template: AWS preserves the existing one,
                    // and omitting it avoids the ec2:UseLaunchTemplate IAM check.
                    asgClient.updateAutoScalingGroup(req -> req
                            .autoScalingGroupName(asgName)
                            .mixedInstancesPolicy(
                                    software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy.builder()
                                            .instancesDistribution(d -> d
                                            .onDemandBaseCapacity(0)
                                            .onDemandPercentageAboveBaseCapacity(100))
                                            .build()));
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

            // Evict ASG list and cost caches so next fetch reflects updated disabled state
            redisCacheService.evict(CACHE_KEY_ASG_LIST + cloudAccountId);
            redisCacheService.evict(CACHE_KEY_COST + cloudAccountId);

        } catch (Exception e) {
            logger.error("Failed to disable AutoSpotting for {}: {}", asgName, e.getMessage(), e);
            throw new RuntimeException("Failed to disable AutoSpotting: " + e.getMessage(), e);
        }
    }

    /**
     * Handles a scaling event from EventBridge/SQS. This is the entry point for
     * the centralized AutoSpotting engine logic.
     */
    public void handleScalingEvent(CloudAccount account, String region, JsonNode eventDetail) {
        // CloudFormation stacks send EC2 Instance State-change Notification events.
        // Format: { "instance-id": "i-...", "state": "running", "instance-lifecycle": "spot" }
        // (NOT the old ASG lifecycle hook format with "Event" / "EC2InstanceId" fields)
        String state = eventDetail.path("state").asText();
        String instanceId = eventDetail.path("instance-id").asText();

        logger.info("AutoSpotting: Handling state-change event state={} instance={} account={}",
                state, instanceId, account.getAwsAccountId());

        if ("running".equals(state)) {
            processLaunchEvent(account, region, eventDetail);
        } else if ("terminated".equals(state) || "stopped".equals(state)) {
            processTerminateEvent(eventDetail);
        } else {
            logger.debug("Ignoring state-change event with state={}", state);
        }
    }

    /**
     * Handles an EC2 Spot Instance Interruption Warning (2-minute notice).
     *
     * AWS sends this event via EventBridge → SQS before reclaiming a Spot
     * instance. The ASG automatically replaces the terminated instance (with
     * another Spot or falls back to On-Demand if no Spot capacity is
     * available), so no manual replacement action is needed here. We simply log
     * and persist the event for auditing and interruption-rate analytics.
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
        // EC2 state-change event format uses "instance-id" (not "EC2InstanceId")
        String instanceId = detail.path("instance-id").asText();
        if (instanceId.isEmpty()) {
            logger.warn("processLaunchEvent: missing instance-id in event detail");
            return;
        }

        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);
            var describeResponse = ec2Client.describeInstances(req -> req.instanceIds(instanceId));

            if (!describeResponse.reservations().isEmpty()
                    && !describeResponse.reservations().get(0).instances().isEmpty()) {
                var instance = describeResponse.reservations().get(0).instances().get(0);
                String instanceType = instance.instanceTypeAsString();

                // Resolve ASG name from the instance's aws:autoscaling:groupName tag
                String asgName = instance.tags().stream()
                        .filter(t -> ASG_NAME_KEY.equals(t.key()))
                        .map(software.amazon.awssdk.services.ec2.model.Tag::value)
                        .findFirst()
                        .orElse("unknown");

                // Check if it's a spot instance
                if (instance.instanceLifecycleAsString().equalsIgnoreCase("spot")) {
                    double hourlySavings = spotPricingService.calculateHourlySavings(
                            instanceType, instanceType, region, account);

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
                } else if ("unknown".equals(asgName)) {
                    // OD instance not in an ASG — not related to AutoSpotting, ignore
                    logger.debug("OD instance {} not in an ASG, skipping", instanceId);
                } else {
                    // OD instance launched in an AutoSpotting-enabled ASG — Spot was unavailable (fallback)
                    logger.warn("⚠️  OD FALLBACK — Spot unavailable for ASG {}, launched OD instance {} ({})",
                            asgName, instanceId, instanceType);
                    SpotOptimizationEvent failedEvent = new SpotOptimizationEvent();
                    failedEvent.setAccount(account);
                    failedEvent.setAsgName(asgName);
                    failedEvent.setRegion(region);
                    failedEvent.setNewInstanceId(instanceId);
                    failedEvent.setNewInstanceType(instanceType);
                    failedEvent.setHourlySavings(0.0);
                    failedEvent.setStatus("FAILED");
                    failedEvent.setDetails("Spot unavailable — On-Demand fallback launched (" + instanceType
                            + "). ASG will retry Spot when capacity is available.");
                    eventRepository.save(failedEvent);
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
            var asgResponse = asgClient.describeAutoScalingGroups(req
                    -> req.autoScalingGroupNames(asgName));

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

                if (asg.mixedInstancesPolicy() != null
                        && asg.mixedInstancesPolicy().launchTemplate() != null
                        && asg.mixedInstancesPolicy().launchTemplate().overrides() != null) {
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
     * Build cost response from AWS SDK when API fails This creates a response
     * with ASG data but WITHOUT cost information
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
     * Builds cost data for a single ASG by describing its instances and using
     * SpotPricingService.
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

            var asgResponse = asgClient.describeAutoScalingGroups(req
                    -> req.autoScalingGroupNames(asgDto.name));

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
                                if ("terminated".equalsIgnoreCase(instance.state().nameAsString())
                                        || "shutting-down".equalsIgnoreCase(instance.state().nameAsString())) {
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
    public RiSpCoverageResponse getRiSpCoverage(Long cloudAccountId, boolean forceRefresh) {
        String cacheKey = "AUTOSPOTTING_RISP::" + cloudAccountId;
        String dbKey = "AUTOSPOTTING_RISP::" + cloudAccountId;
        final int RISP_CACHE_TTL = 240;

        if (!forceRefresh) {
            Optional<RiSpCoverageResponse> cached = redisCacheService.get(cacheKey, RiSpCoverageResponse.class);
            if (cached.isPresent()) {
                logger.info("✅ Cache hit for RI/SP coverage: {}", cacheKey);
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(dbKey);
                if (dbCached.isPresent()) {
                    RiSpCoverageResponse dbData = objectMapper.readValue(
                            dbCached.get().getJsonData(), RiSpCoverageResponse.class);
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, RISP_CACHE_TTL);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", dbKey);
                        return dbData;
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", dbKey, dbEx.getMessage());
            }
        }

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

            redisCacheService.put(cacheKey, response, RISP_CACHE_TTL);
            try {
                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                snapshot.setCacheKey(dbKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(response));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", dbKey, dbEx.getMessage());
            }

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
        public boolean eksManaged; // true if managed by EKS (cannot be toggled)
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
                String details = event.getDetails();

                // Check for CONFIG_SAVE events first (they have status="SUCCESS" but details starts with "CONFIG_SAVE:")
                if ("SUCCESS".equals(evStatus) && details != null && details.startsWith("CONFIG_SAVE:")) {
                    record.setEventType("configuration_change");
                    record.setResult("Saved");
                    record.setReason(details.replace("CONFIG_SAVE:", "").trim());
                } else if ("SUCCESS".equals(evStatus)) {
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
                    // Populate per-row pricing for the UI (uses @Cacheable, so no repeated API calls)
                    if (event.getNewInstanceType() != null && event.getRegion() != null) {
                        try {
                            double odPrice = spotPricingService.getHourlyOnDemandPrice(event.getNewInstanceType(), event.getRegion());
                            double spotPrice = Math.max(0.0, odPrice - (event.getHourlySavings() != null ? event.getHourlySavings() : 0.0));
                            record.setOldHourlyPrice(odPrice);
                            record.setNewHourlyPrice(spotPrice);
                        } catch (Exception priceEx) {
                            logger.debug("Could not fetch prices for event {}: {}", event.getId(), priceEx.getMessage());
                        }
                    }
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
                } else if ("FAILED".equals(evStatus)) {
                    record.setEventType("launch_failure");
                    record.setResult("Failed");
                    record.setNewInstanceId(event.getNewInstanceId());
                    record.setNewInstanceType(event.getNewInstanceType());
                    record.setNewLifecycle("on-demand");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "Spot unavailable — On-Demand fallback launched.");
                } else {
                    record.setEventType("launch_failure");
                    record.setResult("Failed");
                    record.setOldInstanceId(event.getOldInstanceId());
                    record.setOldInstanceType(event.getOldInstanceType());
                    record.setReason(event.getDetails() != null ? event.getDetails()
                            : "Spot launch or replacement failed.");
                }

                return record;
            }).collect(Collectors.toList());

            // If filtering by eventType, ensure we only return matching event types
            // (necessary because configuration_change and instance_replacement both map to SUCCESS status)
            if (eventType != null && !eventType.isEmpty()) {
                final String targetEventType = eventType.toLowerCase();
                eventRecords = eventRecords.stream()
                        .filter(rec -> targetEventType.equals(rec.getEventType()))
                        .collect(Collectors.toList());
            }

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
        if ("instance_replacement".equalsIgnoreCase(eventType)) {
            return "SUCCESS";
        }
        if ("configuration_change".equalsIgnoreCase(eventType)) {
            return "SUCCESS";
        }
        if ("spot_interruption".equalsIgnoreCase(eventType)) {
            return "INTERRUPTED";
        }
        if ("asg_enabled".equalsIgnoreCase(eventType)) {
            return "ENABLED";
        }
        if ("asg_disabled".equalsIgnoreCase(eventType)) {
            return "DISABLED";
        }
        if ("launch_failure".equalsIgnoreCase(eventType)) {
            return "FAILED";
        }
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
                    s.setAttempts(0);
                    s.setSuccesses(0);
                    s.setFailures(0);
                    return s;
                });
                typeStats.setAttempts(typeStats.getAttempts() + 1);
                if (isSuccess) {
                    typeStats.setSuccesses(typeStats.getSuccesses() + 1);
                } else {
                    typeStats.setFailures(typeStats.getFailures() + 1);
                }
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
                    s.setAttempts(0);
                    s.setSuccesses(0);
                    s.setFailures(0);
                    return s;
                });
                regionStats.setAttempts(regionStats.getAttempts() + 1);
                if (isSuccess) {
                    regionStats.setSuccesses(regionStats.getSuccesses() + 1);
                } else {
                    regionStats.setFailures(regionStats.getFailures() + 1);
                }
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

    // ================= ADMIN DASHBOARD METRICS =================
    /**
     * Get aggregated historical savings for all accounts (Admin Dashboard)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAdminHistoricalSavings(int days) {
        LocalDate startDate = LocalDate.now().minusDays(days);
        List<Object[]> results = savingsRepository.getAggregatedHistory(startDate);

        List<Map<String, Object>> history = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", row[0].toString());
            point.put("savings", row[1]);
            point.put("instanceCount", row[2]);
            history.add(point);
        }

        // Add today's real-time aggregated data
        LocalDate today = LocalDate.now();
        Instant todayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant now = Instant.now();

        List<SpotOptimizationEvent> todayEvents = eventRepository.findByEventTimeBetweenAndStatusOrderByEventTimeDesc(
                todayStart, now, "SUCCESS");

        double todaySavings = todayEvents.stream()
                .mapToDouble(e -> e.getHourlySavings() != null ? e.getHourlySavings() : 0.0)
                .sum();

        Map<String, Object> todayDp = new HashMap<>();
        todayDp.put("date", today.toString());
        todayDp.put("savings", todaySavings);
        todayDp.put("instanceCount", todayEvents.size());

        history.removeIf(p -> p.get("date").equals(today.toString()));
        history.add(todayDp);
        history.sort((a, b) -> ((String) a.get("date")).compareTo((String) b.get("date")));

        return history;
    }

    /**
     * Get global savings metrics for Admin Dashboard
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAdminDashboardMetrics() {
        Double totalSavings = savingsRepository.getTotalSavings();
        if (totalSavings == null) {
            totalSavings = 0.0;
        }

        // Add today's real-time data to total
        LocalDate today = LocalDate.now();
        Instant todayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<SpotOptimizationEvent> todayEvents = eventRepository.findByEventTimeBetweenAndStatusOrderByEventTimeDesc(
                todayStart, Instant.now(), "SUCCESS");

        double todaySavings = todayEvents.stream()
                .mapToDouble(e -> e.getHourlySavings() != null ? e.getHourlySavings() : 0.0)
                .sum();

        totalSavings += todaySavings;

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalSavings", totalSavings);
        metrics.put("monthlySavings", totalSavings / 12.0); // Simple estimate
        metrics.put("dailySavings", todaySavings);
        metrics.put("activeSpotInstances", todayEvents.size()); // As approximate proxy
        metrics.put("conversionRate", 85.0); // Dummy for UI

        return metrics;
    }

    /**
     * Get account-by-account breakdown for Admin Dashboard
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAdminAccountBreakdown() {
        List<CloudAccount> accounts = cloudAccountRepository.findAll();
        List<Map<String, Object>> breakdown = new ArrayList<>();

        for (CloudAccount acc : accounts) {
            String accId = acc.getAwsAccountId();
            if (accId == null) {
                continue;
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("accountId", accId);
            entry.put("accountName", acc.getAccountName());

            // Total savings for this account
            Double accSavings = eventRepository.findByAccountIdOrderByEventTimeDesc(acc.getId()).stream()
                    .mapToDouble(e -> e.getHourlySavings() != null ? e.getHourlySavings() : 0.0)
                    .sum();

            entry.put("totalSavings", accSavings);
            entry.put("instanceCount", eventRepository.findByAccountIdOrderByEventTimeDesc(acc.getId()).size());
            breakdown.add(entry);
        }

        return breakdown;
    }

    /**
     * Get per-customer AutoSpotting savings breakdown by provider (AWS/GCP/Azure).
     * Only includes customers who have AutoSpotting enabled on at least one account.
     * Groups savings by client (customer), summing across all their cloud accounts per provider.
     * Result is cached in Redis for CACHE_TTL_ADMIN_MINUTES minutes.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCustomerSpotSavings() {
        // Try Redis cache first
        Optional<List<Map<String, Object>>> cached = redisCacheService.get(
                CACHE_KEY_CUSTOMER_SAVINGS,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        if (cached.isPresent()) {
            logger.info("--- CUSTOMER SAVINGS: loaded from Redis cache ---");
            return cached.get();
        }

        List<com.xammer.cloud.domain.AutoSpottingSettings> enabledSettings =
                settingsRepository.findAllByEnabled(true);

        // clientId → { clientName, awsSavings, gcpSavings, azureSavings }
        Map<Long, Map<String, Object>> clientMap = new java.util.LinkedHashMap<>();

        for (com.xammer.cloud.domain.AutoSpottingSettings settings : enabledSettings) {
            CloudAccount account = settings.getAccount();
            if (account == null) {
                account = cloudAccountRepository.findById(settings.getAccountId()).orElse(null);
            }
            if (account == null || account.getClient() == null) continue;

            com.xammer.cloud.domain.Client client = account.getClient();
            Long clientId = client.getId();
            String provider = account.getProvider(); // AWS, GCP, Azure

            // Sum all-time savings from DailySpotSavings for this account
            double savings = savingsRepository
                    .findByAccountIdAndDateAfterOrderByDateAsc(account.getId(), java.time.LocalDate.of(2000, 1, 1))
                    .stream()
                    .mapToDouble(d -> d.getAmountSaved() != null ? d.getAmountSaved() : 0.0)
                    .sum();

            Map<String, Object> entry = clientMap.computeIfAbsent(clientId, k -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("clientId", clientId);
                m.put("clientName", client.getName());
                m.put("awsSavings", 0.0);
                m.put("gcpSavings", 0.0);
                m.put("azureSavings", 0.0);
                return m;
            });

            if ("AWS".equals(provider)) {
                entry.put("awsSavings", (Double) entry.get("awsSavings") + savings);
            } else if ("GCP".equals(provider)) {
                entry.put("gcpSavings", (Double) entry.get("gcpSavings") + savings);
            } else if ("Azure".equals(provider)) {
                entry.put("azureSavings", (Double) entry.get("azureSavings") + savings);
            }
        }

        // Add total and sort descending
        List<Map<String, Object>> result = clientMap.values().stream()
                .map(entry -> {
                    double total = (Double) entry.get("awsSavings")
                            + (Double) entry.get("gcpSavings")
                            + (Double) entry.get("azureSavings");
                    entry.put("totalSavings", total);
                    return entry;
                })
                .sorted((a, b) -> Double.compare((Double) b.get("totalSavings"), (Double) a.get("totalSavings")))
                .collect(Collectors.toList());

        // Save to Redis cache
        redisCacheService.put(CACHE_KEY_CUSTOMER_SAVINGS, result, CACHE_TTL_ADMIN_MINUTES);
        logger.info("--- CUSTOMER SAVINGS: cached {} customer entries in Redis (TTL: {} min) ---",
                result.size(), CACHE_TTL_ADMIN_MINUTES);

        return result;
    }

    /**
     * Get recent activity for Admin Dashboard
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAdminRecentActivity(int limit) {
        List<SpotOptimizationEvent> events = limit <= 10
                ? eventRepository.findTop10ByOrderByEventTimeDesc()
                : eventRepository.findTop50ByOrderByEventTimeDesc();

        return events.stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.getId());
            m.put("timestamp", e.getEventTime().toString());
            m.put("accountId", e.getAccount().getAwsAccountId());
            m.put("accountName", e.getAccount().getAccountName());
            m.put("instanceId", e.getNewInstanceId());
            m.put("instanceType", e.getOldInstanceType());
            m.put("spotInstanceType", e.getNewInstanceType());
            m.put("eventType", e.getStatus().equals("SUCCESS") ? "LAUNCH" : "FAILED");
            m.put("hourlySavings", e.getHourlySavings());
            m.put("onDemandPrice", 0.0); // To be filled if needed
            m.put("spotPrice", 0.0);
            m.put("region", e.getRegion());
            return m;
        }).collect(Collectors.toList());
    }
    /**
     * Get AutoSpotting settings for an account
     */
    public Map<String, Object> getSettings(Long cloudAccountId) {
        CloudAccount account = getByAwsAccountId(cloudAccountId);
        AutoSpottingSettings settings = settingsRepository.findById(account.getId())
                .orElseGet(() -> {
                    AutoSpottingSettings newSettings = new AutoSpottingSettings(account);
                    return settingsRepository.save(newSettings);
                });

        Map<String, Object> result = new HashMap<>();
        result.put("enabled", settings.isEnabled());
        result.put("tagFilters", settings.getTagFilters());
        result.put("excludedInstances", settings.getExcludedInstances());
        result.put("maxSpotPercentage", settings.getMaxSpotPercentage());
        result.put("minOnDemandNumber", settings.getMinOnDemandNumber());
        result.put("respectRiSpCommitments", settings.isRespectRiSpCommitments());
        result.put("riSpUtilizationThreshold", settings.getRiSpUtilizationThreshold());

        return result;
    }

    /**
     * Update RI/SP respect settings for an account
     */
    public void updateRispRespectSettings(Long cloudAccountId, Boolean respectRiSp, Integer threshold) {
        CloudAccount account = getByAwsAccountId(cloudAccountId);
        AutoSpottingSettings settings = settingsRepository.findById(account.getId())
                .orElseGet(() -> new AutoSpottingSettings(account));

        if (respectRiSp != null) {
            settings.setRespectRiSpCommitments(respectRiSp);
            logger.info("Updated respectRiSpCommitments to {} for account {}", respectRiSp, cloudAccountId);
        }

        if (threshold != null) {
            if (threshold < 0 || threshold > 100) {
                throw new IllegalArgumentException("Threshold must be between 0 and 100");
            }
            settings.setRiSpUtilizationThreshold(threshold);
            logger.info("Updated riSpUtilizationThreshold to {}% for account {}", threshold, cloudAccountId);
        }

        settingsRepository.save(settings);
        logger.info("RI/SP respect settings saved successfully for account {}", cloudAccountId);
    }

}
