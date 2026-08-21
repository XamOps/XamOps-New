package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.util.RightsizingOptionApplicability;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class XamOpsRightsizingService {

    private static final Logger logger = LoggerFactory.getLogger(XamOpsRightsizingService.class);
    private static final String CSV_FILE_PATH = "/RightSizing-XamOps - Instances.csv";
    private static final String CACHE_KEY_PREFIX = "xamops-rightsizing-v2-";

    /** CloudWatch lookback window for the peak-CPU signal. */
    private static final int LOOKBACK_DAYS = 14;

    /**
     * Minimum number of daily datapoints required before a resize is recommended.
     * Half the window, matching the coverage gate CloudSitter applies.
     */
    private static final int MIN_METRIC_DAYS = 7;

    private final CloudListService cloudListService;
    private final MetricsService metricsService;
    private final RedisCacheService redisCacheService;
    private final AwsClientProvider awsClientProvider; // Injected for AWS calls
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, List<XamOpsRightsizingRecommendation>> recommendationsMap;
    private final SnapshotFreshnessService snapshotFreshnessService;
    private final PricingService pricingService;

    // Local cache to store available instance types per region to avoid repeated
    // API calls
    private final Map<String, Set<String>> regionAvailabilityCache = new ConcurrentHashMap<>();

    /**
     * Optional: absent when the inventory plane is disabled, in which case the candidate
     * list keeps coming from {@code getAllResources} unchanged.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.xammer.cloud.service.inventory.InventoryProjectionService inventoryProjection;

    public XamOpsRightsizingService(
            CloudListService cloudListService,
            MetricsService metricsService,
            RedisCacheService redisCacheService,
            AwsClientProvider awsClientProvider,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            SnapshotFreshnessService snapshotFreshnessService,
            PricingService pricingService) {
        this.cloudListService = cloudListService;
        this.metricsService = metricsService;
        this.redisCacheService = redisCacheService;
        this.awsClientProvider = awsClientProvider;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.snapshotFreshnessService = snapshotFreshnessService;
        this.pricingService = pricingService;
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

            // 2. Check DB snapshot (survives Redis restarts)
            try {
                Optional<CachedData> dbSnapshot = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(cacheKey), cacheKey);
                if (dbSnapshot.isPresent()) {
                    List<XamOpsRightsizingRecommendation> dbData = objectMapper.readValue(
                            dbSnapshot.get().getJsonData(),
                            new TypeReference<List<XamOpsRightsizingRecommendation>>() {});
                    if (dbData != null && !dbData.isEmpty()) {
                        logger.info("✅ Retrieved {} XamOps recommendations from DB cache for account {}",
                                dbData.size(), accountId);
                        redisCacheService.put(cacheKey, dbData, 60);
                        return dbData;
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to read XamOps rightsizing from DB cache: {}", e.getMessage());
            }
        }

        logger.info("🔄 Generating fresh XamOps recommendations for account {}", accountId);

        // Get the account object once
        CloudAccount account = cloudListService.getAccount(accountId);

        List<ResourceDto> ec2Instances = ec2InstancesFromPlane(account);
        if (ec2Instances == null) {
            ec2Instances = cloudListService.getAllResources(account, false)
                    .join()
                    .stream()
                    .filter(r -> "EC2 Instance".equalsIgnoreCase(r.getType()))
                    .collect(Collectors.toList());
        }

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

            Optional<MetricsService.CpuUtilizationSample> sample =
                    metricsService.getMaxCpuUtilizationSample(accountId, instanceId, region, LOOKBACK_DAYS);

            if (sample.isEmpty()) {
                logger.warn("Could not retrieve CPU utilization for instance {}. Skipping.", instanceId);
                continue;
            }

            // Require real observation coverage before recommending a downsize. Without this
            // an instance launched an hour ago yields a single datapoint that reads as a
            // 14-day peak, and a quiet first hour becomes a downsize recommendation.
            if (!sample.get().hasSufficientData(MIN_METRIC_DAYS)) {
                logger.info("Skipping instance {}: only {} of {} days of CPU data ({}% coverage), "
                                + "need at least {} days before recommending a resize.",
                        instanceId, sample.get().dataPoints(), LOOKBACK_DAYS,
                        Math.round(sample.get().coverage() * 100), MIN_METRIC_DAYS);
                continue;
            }

            double utilization = sample.get().maxCpuPercent();
            logger.info("Instance {} ({}) has max CPU utilization of {}% over {} days",
                    instanceId, instanceType, String.format("%.2f", utilization), sample.get().dataPoints());

            // ✅ Find matching recommendation and VALIDATE AVAILABILITY
            String instanceName = instanceDto.getName();
            findMatchingRecommendation(instanceType, utilization, instanceId, region)
                    .ifPresent(rec -> {
                        // Set the human-readable instance name
                        if (instanceName != null && !instanceName.isBlank()) {
                            rec.setInstanceName(instanceName);
                        }
                        // Validate if the recommended types exist in this region
                        validateRecommendationAvailability(account, region, rec);

                        // Replace the CSV's embedded prices with live regional ones and compute
                        // a real dollar saving.
                        repriceRecommendation(rec, instanceType, region);

                        // Decide which columns can actually be applied in place. Must run after
                        // the two steps above because both rewrite the column text.
                        markApplicability(rec, instanceType);

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

        // Save to DB so subsequent requests survive Redis restarts
        try {
            CachedData snapshot = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
            snapshot.setCacheKey(cacheKey);
            snapshot.setJsonData(objectMapper.writeValueAsString(uniqueRecommendations));
            snapshot.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(snapshot);
            logger.info("✅ Saved XamOps rightsizing snapshot to DB for account {}", accountId);
        } catch (Exception e) {
            logger.warn("Failed to save XamOps rightsizing to DB: {}", e.getMessage());
        }

        return uniqueRecommendations;
    }

    /**
     * EC2 instances from the inventory plane, or null when it cannot answer.
     *
     * <p>
     * One indexed query instead of materialising the whole account to filter it down to
     * instances. Requires {@code "Type"} to be present in the details map, because the loop
     * below skips any instance whose type is unknown — an instance-type-less list is not a
     * short list, it is an empty Rightsizing page. The reconciler harvests that attribute
     * from the {@code describeInstances} call it already makes for instance state, so a plane
     * that is fresh has it; the check below is a guard against that changing silently rather
     * than an expected condition.
     *
     * <p>
     * Event-created rows are a deliberate exception: they carry no attributes until the next
     * reconcile, so a just-launched instance is briefly absent here. That costs nothing real,
     * since a resize needs {@value #MIN_METRIC_DAYS} days of CPU history before it can be
     * recommended at all.
     */
    private List<ResourceDto> ec2InstancesFromPlane(CloudAccount account) {
        if (inventoryProjection == null || account.getId() == null) {
            return null;
        }
        try {
            if (!inventoryProjection.isFresh(account.getId())) {
                return null;
            }
            List<ResourceDto> instances = inventoryProjection.byType(account.getId(), "EC2 Instance");
            if (instances.isEmpty()) {
                return null;
            }
            long withType = instances.stream()
                    .filter(r -> r.getDetails() != null && r.getDetails().get("Type") != null)
                    .count();
            if (withType == 0) {
                logger.warn("[Inventory] Plane returned {} EC2 instance(s) for account {} but none carry a "
                        + "\"Type\" detail — falling back to describes so recommendations are not silently "
                        + "dropped. Check AwsInventoryReconciler's instance attribute capture.",
                        instances.size(), account.getAwsAccountId());
                return null;
            }
            logger.info("✅ Rightsizing candidates served from inventory plane for account {} "
                    + "({} instances, {} with instance type, 0 AWS calls)",
                    account.getAwsAccountId(), instances.size(), withType);
            return instances;
        } catch (Exception e) {
            logger.warn("[Inventory] EC2 plane read failed for account {} — falling back: {}",
                    account.getAwsAccountId(), e.getMessage());
            return null;
        }
    }

    /**
     * Replaces the CSV's embedded prices with live regional prices and computes a real
     * dollar saving.
     *
     * <p>The CSV carries prices inside its recommendation text, e.g.
     * {@code "t3.medium ($0.0416/h)"}. Those are static us-east-1 rates from when the sheet
     * was authored, and they were displayed verbatim to customers running in any region.
     * Its {@code Approx. Cost Savings} column is likewise a percentage range string such as
     * {@code "~87% / ~88%"}, which cannot be summed into any total.
     *
     * <p>This keeps the CSV as the curated decision matrix — which instance to move to at
     * which CPU band is legitimate domain knowledge — but sources every number from the
     * Pricing API for the instance's actual region.
     */
    private void repriceRecommendation(XamOpsRightsizingRecommendation rec, String currentInstanceType,
                                        String region) {
        try {
            double currentMonthly = pricingService.getEc2InstanceMonthlyPrice(currentInstanceType, region);
            rec.setCurrentMonthlyCost(PricingService.isPriceAvailable(currentMonthly) ? currentMonthly : null);

            // Intel and AMD columns are independent options; price whichever are actionable.
            Double intelSaving = repriceOption(rec.getIntelRecommendation(), currentMonthly, region,
                    rec::setIntelRecommendation, rec::setIntelMonthlyCost);
            Double amdSaving = repriceOption(rec.getAmdRecommendation(), currentMonthly, region,
                    rec::setAmdRecommendation, rec::setAmdMonthlyCost);

            // Headline saving is the better of the two realizable options.
            Double best = null;
            if (intelSaving != null) best = intelSaving;
            if (amdSaving != null && (best == null || amdSaving > best)) best = amdSaving;

            if (best != null) {
                rec.setEstimatedMonthlySavings(Math.round(best * 100.0) / 100.0);
                rec.setSavingsUnknown(false);
            } else {
                rec.setEstimatedMonthlySavings(0.0);
                rec.setSavingsUnknown(true);
            }
            rec.setCurrency("USD");
        } catch (Exception e) {
            logger.warn("Could not reprice recommendation for {} in {}: {}",
                    currentInstanceType, region, e.getMessage());
            rec.setSavingsUnknown(true);
        }
    }

    /**
     * Rewrites one recommendation column with a live price and returns the monthly saving.
     *
     * @param optionText     the CSV cell, e.g. {@code "t3.medium ($0.0416/h)"} or {@code "Stay t3.large"}
     * @param currentMonthly current instance monthly cost, or {@link PricingService#PRICE_UNAVAILABLE}
     * @param setText        writes the rewritten display text back onto the recommendation
     * @param setCost        writes the resolved monthly cost back onto the recommendation
     * @return the monthly saving, or null when this option is not an actionable resize
     */
    private Double repriceOption(String optionText, double currentMonthly, String region,
                                  java.util.function.Consumer<String> setText,
                                  java.util.function.Consumer<Double> setCost) {
        if (optionText == null || optionText.isBlank()) {
            return null;
        }
        // "Stay ..." and "Not available in ..." are not resizes.
        String lower = optionText.toLowerCase();
        if (lower.startsWith("stay") || lower.startsWith("not available")) {
            return null;
        }

        String targetType = extractInstanceType(optionText);
        if (targetType == null || !targetType.matches("^[a-z0-9]+\\.[a-z0-9]+$")) {
            return null;
        }

        double targetMonthly = pricingService.getEc2InstanceMonthlyPrice(targetType, region);
        if (!PricingService.isPriceAvailable(targetMonthly)) {
            // Strip the stale embedded price rather than presenting it as current.
            setText.accept(targetType + " (price unavailable)");
            return null;
        }

        setCost.accept(targetMonthly);
        // Show the live monthly figure for the instance's own region.
        setText.accept(String.format("%s ($%.2f/mo)", targetType, targetMonthly));

        if (!PricingService.isPriceAvailable(currentMonthly)) {
            return null;
        }
        double saving = currentMonthly - targetMonthly;
        return saving > 0 ? saving : null;
    }

    /**
     * Flags whether each column can be applied with a stop / modify-instance-type / start
     * sequence, and why not when it cannot.
     *
     * <p>Without this the UI offered an Apply button for every column, including ones that
     * are not resizes at all. Two cases failed in distinct ways:
     *
     * <ul>
     *   <li>A column reading {@code "Not available in ap-south-2"} was parsed for its first
     *       token, so Apply submitted the literal instance type {@code "Not"}.</li>
     *   <li>The curated matrix pairs some Graviton sources with x86 targets, e.g.
     *       {@code t4g.small -> t3.nano}. Changing architecture needs an AMI built for the
     *       target, so the resize is rejected by the apply-path preflight. The saving is
     *       genuine, but only after the image is rebuilt — hence
     *       {@link XamOpsRightsizingRecommendation#isRequiresArchitectureMigration()} rather
     *       than dropping the recommendation.</li>
     * </ul>
     */
    private void markApplicability(XamOpsRightsizingRecommendation rec, String currentInstanceType) {
        RightsizingOptionApplicability.Verdict intel =
                RightsizingOptionApplicability.evaluate(rec.getIntelRecommendation(), currentInstanceType);
        rec.setIntelApplicable(intel.applicable());
        rec.setIntelBlockedReason(intel.reason());

        RightsizingOptionApplicability.Verdict amd =
                RightsizingOptionApplicability.evaluate(rec.getAmdRecommendation(), currentInstanceType);
        rec.setAmdApplicable(amd.applicable());
        rec.setAmdBlockedReason(amd.reason());

        rec.setRequiresArchitectureMigration(intel.architectureChange() || amd.architectureChange());
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
                                .region(region)
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