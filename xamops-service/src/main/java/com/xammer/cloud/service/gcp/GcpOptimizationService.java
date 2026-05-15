package com.xammer.cloud.service.gcp;

import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.BackupRun;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.api.services.sqladmin.model.InstancesListResponse;
import com.google.cloud.bigquery.*;
import com.google.cloud.compute.v1.*;
import com.google.cloud.recommender.v1.Recommendation;
import com.google.cloud.recommender.v1.RecommenderClient;
import com.google.cloud.recommender.v1.RecommenderName;
import com.google.cloud.resourcemanager.v3.Project;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.type.Money;
import com.google.cloud.billing.v1.CloudCatalogClient;
import com.google.cloud.billing.v1.ListSkusRequest;
import com.google.cloud.billing.v1.Sku;
import com.google.cloud.billing.v1.PricingExpression;
import com.google.pubsub.v1.ProjectName;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpCommittedUseDiscountDto;
import com.xammer.cloud.dto.gcp.GcpCudUtilizationDto;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.dto.gcp.GcpReservationPageDto;
import com.xammer.cloud.dto.gcp.GcpWasteItem;
import com.xammer.cloud.dto.gcp.GcpWasteReport;
import com.xammer.cloud.repository.CachedDataRepository;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.container.v1.ClusterManagerClient;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpOptimizationService {

    private final GcpClientProvider gcpClientProvider;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final GcpDataService gcpDataService;
    private final CloudAccountService cloudAccountService;
    private final GcpMetricsService gcpMetricsService;
    private final RedisCacheService redisCacheService;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    // Utilization thresholds for validation (similar to AWS approach)
    private static final double HIGH_CPU_THRESHOLD = 80.0; // Recommend upgrade if CPU > 80% (Increased from 50.0)
    private static final double LOW_CPU_THRESHOLD = 20.0; // Recommend downgrade if CPU < 20%
    private static final double HIGH_MEMORY_THRESHOLD = 85.0; // Recommend upgrade if Memory > 85% (Increased from 70.0)
    private static final double LOW_MEMORY_THRESHOLD = 30.0; // Recommend downgrade if Memory < 30%
    private static final int LOOKBACK_DAYS = 14; // Look back 14 days for metrics

    // Full GCP region-to-location mapping (as of Oct 2025; source:
    // cloud.google.com/compute/docs/regions-zones)
    private static final Map<String, String> REGION_TO_LOCATION = Map.ofEntries(
            Map.entry("africa-south1", "Johannesburg, South Africa"),
            Map.entry("asia-east1", "Changhua County, Taiwan, APAC"),
            Map.entry("asia-east2", "Hong Kong, APAC"),
            Map.entry("asia-northeast1", "Tokyo, Japan, APAC"),
            Map.entry("asia-northeast2", "Osaka, Japan, APAC"),
            Map.entry("asia-northeast3", "Seoul, South Korea, APAC"),
            Map.entry("asia-south1", "Mumbai, India, APAC"),
            Map.entry("asia-south2", "Delhi, India, APAC"),
            Map.entry("asia-southeast1", "Jurong West, Singapore, APAC"),
            Map.entry("asia-southeast2", "Jakarta, Indonesia, APAC"),
            Map.entry("australia-southeast1", "Sydney, Australia, APAC"),
            Map.entry("australia-southeast2", "Melbourne, Australia, APAC"),
            Map.entry("europe-north1", "Hamina, Finland, Europe"),
            Map.entry("europe-north2", "Stockholm, Sweden, Europe"),
            Map.entry("europe-central2", "Warsaw, Poland, Europe"),
            Map.entry("europe-southwest1", "Madrid, Spain, Europe"),
            Map.entry("europe-west1", "St. Ghislain, Belgium, Europe"),
            Map.entry("europe-west2", "London, England, Europe"),
            Map.entry("europe-west3", "Frankfurt, Germany, Europe"),
            Map.entry("europe-west4", "Eemshaven, Netherlands, Europe"),
            Map.entry("europe-west6", "Zurich, Switzerland, Europe"),
            Map.entry("europe-west8", "Milan, Italy, Europe"),
            Map.entry("europe-west9", "Paris, France, Europe"),
            Map.entry("europe-west10", "Berlin, Germany, Europe"),
            Map.entry("europe-west12", "Turin, Italy, Europe"),
            Map.entry("me-central1", "Doha, Qatar, Middle East"),
            Map.entry("me-central2", "Dammam, Saudi Arabia, Middle East"),
            Map.entry("me-west1", "Tel Aviv, Israel, Middle East"),
            Map.entry("northamerica-northeast1", "Montréal, Québec, North America"),
            Map.entry("northamerica-northeast2", "Toronto, Ontario, North America"),
            Map.entry("northamerica-south1", "Queretaro, Mexico, North America"),
            Map.entry("southamerica-east1", "Osasco, São Paulo, Brazil, South America"),
            Map.entry("southamerica-west1", "Santiago, Chile, South America"),
            Map.entry("us-central1", "Council Bluffs, Iowa, North America"),
            Map.entry("us-east1", "Moncks Corner, South Carolina, North America"),
            Map.entry("us-east4", "Ashburn, Virginia, North America"),
            Map.entry("us-east5", "Columbus, Ohio, North America"),
            Map.entry("us-south1", "Dallas, Texas, North America"),
            Map.entry("us-west1", "The Dalles, Oregon, North America"),
            Map.entry("us-west2", "Los Angeles, California, North America"),
            Map.entry("us-west3", "Salt Lake City, Utah, North America"),
            Map.entry("us-west4", "Las Vegas, Nevada, North America"));

    private static final String CLOUD_SQL_SERVICE = "services/9662-B51E-5089";
    private static final int HOURS_PER_MONTH = 730; // Standard estimate; configurable if needed
    private static final double EST_IMAGE_STORAGE_COST_USD_PER_GB = 0.05; // Configurable
    private static final double EST_BACKUP_COST_USD = 0.01; // Nominal; configurable
    private static final double EST_PD_COST_USD_PER_GB = 0.10; // Will query catalog below
    private static final double USD_TO_INR_RATE = 83.0; // Define conversion rate

    // Regex for custom tier: db-custom-<vCPU>-<RAM_MB>
    private static final Pattern CUSTOM_TIER_PATTERN = Pattern.compile("db-custom-(\\d+)-(\\d+)");

    public GcpOptimizationService(
            GcpClientProvider gcpClientProvider,
            @Lazy GcpDataService gcpDataService,
            CloudAccountService cloudAccountService,
            GcpMetricsService gcpMetricsService,
            RedisCacheService redisCacheService,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper) {
        this.gcpClientProvider = gcpClientProvider;
        this.gcpDataService = gcpDataService;
        this.cloudAccountService = cloudAccountService;
        this.gcpMetricsService = gcpMetricsService;
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    private void saveToDb(String dbKey, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to save GCP optimization DB snapshot for key {}: {}", dbKey, e.getMessage());
        }
    }

    public List<GcpCommittedUseDiscountDto> getCommittedUseDiscountsSync(String gcpProjectId) {
        return getCommittedUseDiscountsSync(gcpProjectId, false);
    }

    public List<GcpCommittedUseDiscountDto> getCommittedUseDiscountsSync(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = "gcp:cud-inventory:" + gcpProjectId;
        String dbKey = "GCP_CUD_INVENTORY::" + gcpProjectId;

        if (!forceRefresh) {
            Optional<List<GcpCommittedUseDiscountDto>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                log.info("✅ Redis hit for CUD inventory project={}", gcpProjectId);
                return cached.get();
            }
            try {
                Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                if (dbSnap.isPresent()) {
                    List<GcpCommittedUseDiscountDto> dbResult = objectMapper.readValue(
                            dbSnap.get().getJsonData(), new TypeReference<>() {});
                    log.info("✅ DB hit for CUD inventory project={} — re-warming Redis", gcpProjectId);
                    redisCacheService.put(cacheKey, dbResult, 60);
                    return dbResult;
                }
            } catch (Exception e) {
                log.warn("DB load failed for CUD inventory {}: {}", gcpProjectId, e.getMessage());
            }
        }

        log.info("🔍 Fetching CUD inventory from API for project={}", gcpProjectId);
        List<GcpCommittedUseDiscountDto> discounts = new ArrayList<>();
        Optional<RegionCommitmentsClient> clientOpt = gcpClientProvider.getRegionCommitmentsClient(gcpProjectId);

        if (clientOpt.isEmpty()) {
            log.warn("RegionCommitmentsClient not available for project {}.", gcpProjectId);
            return discounts;
        }

        try (RegionCommitmentsClient client = clientOpt.get()) {
            for (var entry : client.aggregatedList(gcpProjectId).iterateAll()) {
                if (entry.getValue().getCommitmentsCount() > 0) {
                    entry.getValue().getCommitmentsList().forEach(commitment -> {
                        GcpCommittedUseDiscountDto dto = new GcpCommittedUseDiscountDto();
                        dto.setId(commitment.getName());
                        dto.setName(commitment.getName());
                        dto.setDescription(commitment.getDescription());
                        dto.setRegion(entry.getKey());
                        dto.setStatus(commitment.getStatus());
                        dto.setPlan(commitment.getPlan());
                        dto.setTerm(commitment.getPlan());
                        if (commitment.getResourcesCount() > 0) {
                            dto.setCommitmentAmount(new BigDecimal(commitment.getResources(0).getAmount()));
                            dto.setCommitmentUnit(commitment.getResources(0).getType());
                        }
                        discounts.add(dto);
                    });
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch CUD inventory for project {}:", gcpProjectId, e);
        }

        redisCacheService.put(cacheKey, discounts, 60);
        saveToDb(dbKey, discounts);
        return discounts;
    }

    public CompletableFuture<List<GcpCommittedUseDiscountDto>> getCommittedUseDiscounts(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> getCommittedUseDiscountsSync(gcpProjectId), executor);
    }

    /** Real CUD utilization — derives from commitment status and plan data. */
    public GcpCudUtilizationDto getCudUtilizationSync(String gcpProjectId, String cudId) {
        String cacheKey = "gcp:cud-utilization:" + gcpProjectId + ":" + cudId;
        Optional<GcpCudUtilizationDto> cached = redisCacheService.get(cacheKey, GcpCudUtilizationDto.class);
        if (cached.isPresent()) return cached.get();

        GcpCudUtilizationDto dto = new GcpCudUtilizationDto();
        Optional<RegionCommitmentsClient> clientOpt = gcpClientProvider.getRegionCommitmentsClient(gcpProjectId);
        if (clientOpt.isEmpty()) return dto;

        try (RegionCommitmentsClient client = clientOpt.get()) {
            for (var entry : client.aggregatedList(gcpProjectId).iterateAll()) {
                for (var commitment : entry.getValue().getCommitmentsList()) {
                    if (commitment.getName().equals(cudId) || commitment.getName().endsWith("/" + cudId)) {
                        dto.setCudName(commitment.getName());
                        dto.setAnalysisPeriod(commitment.getPlan());
                        // ACTIVE commitments are considered fully utilized (GCP doesn't expose slot-level usage without BigQuery)
                        String status = commitment.getStatus();
                        dto.setUtilizationPercentage("ACTIVE".equalsIgnoreCase(status) ? 100.0 : 0.0);
                        // Realized savings: monthly commitment amount × estimated 30% CUD discount vs on-demand
                        double amount = commitment.getResourcesCount() > 0
                                ? (double) commitment.getResources(0).getAmount() : 0.0;
                        dto.setRealizedSavings(amount * 0.30 / 12.0);
                        redisCacheService.put(cacheKey, dto, 60);
                        return dto;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch CUD utilization for project={} cudId={}: {}", gcpProjectId, cudId, e.getMessage());
        }
        return dto;
    }

    public CompletableFuture<GcpCudUtilizationDto> getCudUtilization(String gcpProjectId, String cudId) {
        return CompletableFuture.supplyAsync(() -> getCudUtilizationSync(gcpProjectId, cudId), executor);
    }

    /** Returns aggregated utilization summary across all active CUDs. */
    public Map<String, Object> getCudUtilizationSummary(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = "gcp:cud-util-summary:" + gcpProjectId;
        String dbKey = "GCP_CUD_UTIL_SUMMARY::" + gcpProjectId;

        if (!forceRefresh) {
            Optional<Map<String, Object>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) return cached.get();
            try {
                Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                if (dbSnap.isPresent()) {
                    Map<String, Object> dbResult = objectMapper.readValue(dbSnap.get().getJsonData(), new TypeReference<>() {});
                    redisCacheService.put(cacheKey, dbResult, 60);
                    return dbResult;
                }
            } catch (Exception e) {
                log.warn("DB load failed for CUD util summary {}: {}", gcpProjectId, e.getMessage());
            }
        }

        List<GcpCommittedUseDiscountDto> inventory = getCommittedUseDiscountsSync(gcpProjectId, forceRefresh);
        long total = inventory.size();
        long active = inventory.stream().filter(d -> "ACTIVE".equalsIgnoreCase(d.getStatus())).count();
        long expired = inventory.stream().filter(d -> "EXPIRED".equalsIgnoreCase(d.getStatus())).count();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalCuds", total);
        summary.put("activeCuds", active);
        summary.put("expiredCuds", expired);
        summary.put("overallUtilizationPct", total > 0 ? (active * 100.0 / total) : 0.0);
        summary.put("byRegion", inventory.stream().collect(Collectors.groupingBy(
                d -> d.getRegion() != null ? d.getRegion() : "global", Collectors.counting())));
        summary.put("byPlan", inventory.stream().collect(Collectors.groupingBy(
                d -> d.getPlan() != null ? d.getPlan() : "UNKNOWN", Collectors.counting())));

        redisCacheService.put(cacheKey, summary, 60);
        saveToDb(dbKey, summary);
        return summary;
    }

    public List<GcpOptimizationRecommendation> getRightsizingRecommendations(String gcpProjectId) {
        return getRightsizingRecommendations(gcpProjectId, false);
    }

    public List<GcpOptimizationRecommendation> getRightsizingRecommendations(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = "gcp:rightsizing-recommendations:" + gcpProjectId;
        String dbKey = "GCP_RIGHTSIZING::" + gcpProjectId;

        if (!forceRefresh) {
            // 1. Try Redis
            Optional<List<GcpOptimizationRecommendation>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                log.info("✅ Returning Redis-cached rightsizing recommendations for project {}", gcpProjectId);
                return cached.get();
            }

            // 2. Try DB snapshot
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    List<GcpOptimizationRecommendation> dbResult = objectMapper.readValue(
                            dbSnapshot.get().getJsonData(), new TypeReference<>() {});
                    log.info("✅ Returning DB-cached rightsizing recommendations for project {} — re-warming Redis", gcpProjectId);
                    redisCacheService.put(cacheKey, dbResult, 60);
                    return dbResult;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for rightsizing {}: {}", gcpProjectId, e.getMessage());
            }
        }

        log.info("🔍 Fetching rightsizing recommendations for GCP project: {}", gcpProjectId);

        // Gap 3: Fetch resources once — derive active regions + build CE name→ID map
        List<com.xammer.cloud.dto.gcp.GcpResourceDto> resources = Collections.emptyList();
        try {
            resources = gcpDataService.getAllResources(gcpProjectId, false).join();
        } catch (Exception e) {
            log.warn("[GcpRightsizing] Could not fetch resources for region/ID derivation for {}: {}", gcpProjectId, e.getMessage());
        }

        // Active regions derived from actual resource locations only (avoids scanning 40+ empty regions)
        Set<String> regionSet = resources.stream()
                .map(com.xammer.cloud.dto.gcp.GcpResourceDto::getLocation)
                .filter(loc -> loc != null && !loc.isEmpty() && !"global".equals(loc))
                .map(loc -> { String[] p = loc.split("-"); return p.length > 2 ? p[0] + "-" + p[1] : loc; })
                .filter(REGION_TO_LOCATION::containsKey)
                .collect(Collectors.toSet());

        List<String> locations = regionSet.isEmpty() ? getDynamicRegions(gcpProjectId) : new ArrayList<>(regionSet);
        if (regionSet.isEmpty()) {
            log.warn("[GcpRightsizing] No active regions from resources — falling back to getDynamicRegions() for {}", gcpProjectId);
        } else {
            log.info("[GcpRightsizing] Using {} active regions from resources for project {}", locations.size(), gcpProjectId);
        }

        // Gap 2: Build instance name → numeric ID map for Compute Engine enrichment
        Map<String, String> instanceNameToId = resources.stream()
                .filter(r -> "Compute Engine".equals(r.getType()) && r.getName() != null && r.getId() != null)
                .collect(Collectors.toMap(
                        com.xammer.cloud.dto.gcp.GcpResourceDto::getName,
                        com.xammer.cloud.dto.gcp.GcpResourceDto::getId,
                        (a, b) -> a));
        log.info("[GcpRightsizing] Built CE name→ID map with {} instances for project {}", instanceNameToId.size(), gcpProjectId);

        List<CompletableFuture<List<GcpOptimizationRecommendation>>> futures = new ArrayList<>();

        // Compute Engine rightsizing (global scope — covers all zones)
        futures.add(getRecommendationsForRecommender(
                gcpProjectId, "google.compute.instance.MachineTypeRecommender", "global",
                this::mapToRightsizingDto, true));
        futures.add(getRecommendationsForRecommender(
                gcpProjectId, "google.compute.instanceGroupManager.MachineTypeRecommender", "global",
                this::mapToRightsizingDto, true));

        // Cloud SQL rightsizing — active regions only (Gap 3)
        for (String location : locations) {
            futures.add(getRecommendationsForRecommender(
                    gcpProjectId, "google.cloudsql.instance.OverprovisionedRecommender",
                    location, this::mapToSqlRightsizingDto, true));
            futures.add(getRecommendationsForRecommender(
                    gcpProjectId, "google.cloudsql.instance.UnderprovisionedRecommender",
                    location, this::mapToSqlRightsizingDto, true));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<GcpOptimizationRecommendation> results = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        // Deduplication — keep highest savings for duplicate resource names
        List<GcpOptimizationRecommendation> uniqueRecommendations = results.stream()
                .collect(Collectors.toMap(
                        GcpOptimizationRecommendation::getResourceName,
                        rec -> rec,
                        (existing, replacement) ->
                                existing.getMonthlySavings() >= replacement.getMonthlySavings() ? existing : replacement))
                .values().stream()
                .collect(Collectors.toList());

        int duplicateCount = results.size() - uniqueRecommendations.size();
        if (duplicateCount > 0) {
            log.info("✅ Removed {} duplicate recommendations (kept highest savings)", duplicateCount);
        }

        enhanceCloudSqlRecommendationsWithHistoricalCost(gcpProjectId, uniqueRecommendations);

        // Filter and validate with utilization metrics (Gap 2: CE enrichment now active)
        List<GcpOptimizationRecommendation> validatedRecommendations = filterAndValidateRecommendations(
                gcpProjectId, uniqueRecommendations, instanceNameToId);

        log.info("✅ Total validated rightsizing recommendations found: {}", validatedRecommendations.size());
        redisCacheService.put("gcp:rightsizing-recommendations:" + gcpProjectId, validatedRecommendations, 60);
        saveToDb("GCP_RIGHTSIZING::" + gcpProjectId, validatedRecommendations);
        return validatedRecommendations;
    }

    // Dynamic region fetcher
    private List<String> getDynamicRegions(String gcpProjectId) {
        Optional<RegionsClient> clientOpt = gcpClientProvider.getRegionsClient(gcpProjectId);
        if (clientOpt.isPresent()) {
            try (RegionsClient client = clientOpt.get()) {
                return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                        .map(region -> region.getName().substring(region.getName().lastIndexOf('/') + 1)) // Extract
                                                                                                          // region ID
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("Failed to fetch dynamic regions for {}: {}. Using static fallback.", gcpProjectId,
                        e.getMessage());
            }
        }
        // Fallback to all known regions
        return new ArrayList<>(REGION_TO_LOCATION.keySet());
    }

    /**
     * Finds the base custom SKU for vCPU or RAM in the given region.
     * 
     * @param resourceType "CPU" or "RAM"
     * @param region       Region code (e.g., "us-central1")
     * @return Optional<Sku> matching SKU
     */
    /**
     * Finds the base custom SKU for vCPU or RAM in the given region by filtering
     * after listing.
     * 
     * @param resourceType "CPU" or "RAM"
     * @param region       Region code (e.g., "us-central1")
     * @return Optional<Sku> matching SKU
     */
    private Optional<Sku> findCloudSqlSku(String gcpProjectId, String resourceType, String region) {
        String locationName = REGION_TO_LOCATION.get(region);
        if (locationName == null) {
            log.warn("Unknown region: {}. Cannot map to location name for SKU filter.", region);
            return Optional.empty();
        }

        Optional<CloudCatalogClient> clientOpt = gcpClientProvider.getCloudCatalogClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.warn("CloudCatalogClient not available for project {}", gcpProjectId);
            return Optional.empty();
        }

        try (CloudCatalogClient client = clientOpt.get()) {
            // Define filter criteria
            String descriptionPrefix = "Custom Instance %s running in %s".formatted(
                    "CPU".equals(resourceType) ? "Core" : "RAM", locationName);
            String expectedResourceGroup = resourceType; // "CPU" or "RAM"

            log.info(
                    "Querying Billing Catalog for ALL Cloud SQL SKUs and filtering for: Type={}, Region={}, DescPrefix='{}'",
                    resourceType, region, descriptionPrefix);

            ListSkusRequest request = ListSkusRequest.newBuilder()
                    .setParent(CLOUD_SQL_SERVICE)
                    // No setFilter here
                    .build();

            // Iterate ALL SKUs for the service and filter manually
            for (Sku sku : client.listSkus(request).iterateAll()) {
                boolean regionMatch = sku.getServiceRegionsList().contains(region);
                boolean descriptionMatch = sku.getDescription().contains(descriptionPrefix);
                // Resource Group might not always be present or exactly "CPU"/"RAM", adjust if
                // needed
                boolean resourceGroupMatch = expectedResourceGroup
                        .equalsIgnoreCase(sku.getCategory().getResourceGroup());

                if (regionMatch && descriptionMatch && resourceGroupMatch) {
                    log.debug("Found matching SKU: {} - Description: {}", sku.getSkuId(), sku.getDescription());
                    // Additional check: Ensure it's the base custom SKU (doesn't specify vCPU/RAM
                    // amount)
                    // This logic might need refinement based on actual SKU descriptions.
                    if (!sku.getDescription().matches(".*\\d+\\s+(vCPU|GB).*")) {
                        log.info("Confirmed base SKU match: {}", sku.getSkuId());
                        return Optional.of(sku);
                    } else {
                        log.debug("SKU {} matched filters but appears specific, skipping.", sku.getSkuId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to query/filter Billing Catalog for Cloud SQL {} SKU (Region: {}): {}", resourceType,
                    region, e.getMessage(), e);
        }

        log.warn("Could not find matching SKU for Cloud SQL {} in Region: {} after filtering.", resourceType, region);
        return Optional.empty();
    }

    /**
     * Extracts price per unit from SKU (assumes USD, first tier, On-Demand).
     * 
     * @param sku               The matching SKU
     * @param expectedUsageUnit Expected "h" for vCPU, "GiBy.mo" for RAM
     * @return Price per unit (e.g., per hour or per GiB-month)
     */
    private double getPricePerUnit(Sku sku, String expectedUsageUnit) {
        if (sku == null || sku.getPricingInfoCount() == 0) {
            return 0.0;
        }

        PricingExpression pricingExpression = sku.getPricingInfo(0).getPricingExpression();
        if (pricingExpression.getTieredRatesCount() == 0) {
            log.warn("No tiered rates found for SKU: {}", sku.getSkuId());
            return 0.0;
        }

        PricingExpression.TierRate tieredRate = pricingExpression.getTieredRates(0); // Base tier
        Money unitPrice = tieredRate.getUnitPrice();
        if (!"USD".equals(unitPrice.getCurrencyCode())) {
            log.warn("Non-USD currency for SKU {}: {}", sku.getSkuId(), unitPrice.getCurrencyCode());
            // Consider adding conversion logic if needed, otherwise return 0
            return 0.0;
        }

        String actualUsageUnit = pricingExpression.getUsageUnit();
        if (!expectedUsageUnit.equalsIgnoreCase(actualUsageUnit)) {
            log.warn("Unexpected usage unit for SKU {}: {} (expected: {})", sku.getSkuId(), actualUsageUnit,
                    expectedUsageUnit);
            return 0.0;
        }

        // Parse price: units.nanos (e.g., 0.040400000 for $0.0404)
        BigDecimal price = new BigDecimal(unitPrice.getUnits())
                .add(new BigDecimal(unitPrice.getNanos()).movePointLeft(9));
        double pricePerUnit = price.doubleValue();

        log.debug("SKU {} Price: ${} per {} (Currency: {})", sku.getSkuId(), pricePerUnit, actualUsageUnit,
                unitPrice.getCurrencyCode());

        return pricePerUnit;
    }

    /**
     * Calculate Cloud SQL monthly price based on tier using Billing Catalog API
     * (for dedicated/custom).
     * Falls back to hardcoded for shared core types.
     * Prices returned in USD.
     */
    private double calculateCloudSqlPrice(String gcpProjectId, String tier, String region) {
        if (tier == null || "Unknown".equals(tier) || region == null) {
            return 0.0;
        }

        // Handle shared core types with hardcoded (as they have fixed SKUs/prices)
        if ("db-f1-micro".equals(tier)) {
            log.debug("Using hardcoded price for shared core db-f1-micro: $0.0105/hour");
            return 0.0105 * HOURS_PER_MONTH; // Monthly USD
        } else if ("db-g1-small".equals(tier)) {
            log.debug("Using hardcoded price for shared core db-g1-small: $0.035/hour");
            return 0.035 * HOURS_PER_MONTH; // Monthly USD
        }

        // For dedicated (custom or n1-standard): Parse vCPU and RAM, query catalog
        int vcpus;
        double ramGb;
        if (tier.startsWith("db-custom-")) {
            // Parse custom: db-custom-<vCPU>-<RAM_MB>
            Matcher matcher = CUSTOM_TIER_PATTERN.matcher(tier);
            if (!matcher.matches()) {
                log.warn("Could not parse custom tier: {}", tier);
                return 0.0;
            }
            vcpus = Integer.parseInt(matcher.group(1));
            ramGb = Double.parseDouble(matcher.group(2)) / 1024.0; // MB to GB
        } else if (tier.startsWith("db-n1-standard-")) {
            // Parse standard: db-n1-standard-<vCPU>
            Pattern standardPattern = Pattern.compile("db-n1-standard-(\\d+)");
            Matcher standardMatcher = standardPattern.matcher(tier);
            if (!standardMatcher.matches()) {
                log.warn("Could not parse standard tier: {}", tier);
                return 0.0;
            }
            vcpus = Integer.parseInt(standardMatcher.group(1));
            // Standard ratio: 3.75 GB per vCPU (this might vary slightly, Catalog is
            // better)
            ramGb = vcpus * 3.75;
        } else {
            log.warn("Unsupported or unrecognized tier type for price calculation: {}", tier);
            return 0.0;
        }

        log.info("Parsed tier '{}': {} vCPUs, {:.2f} GB RAM in region '{}'", tier, vcpus, ramGb, region);

        // Find SKUs using Billing Catalog API
        Optional<Sku> cpuSku = findCloudSqlSku(gcpProjectId, "CPU", region);
        Optional<Sku> ramSku = findCloudSqlSku(gcpProjectId, "RAM", region);

        if (cpuSku.isEmpty() || ramSku.isEmpty()) {
            log.warn(
                    "Could not find CPU or RAM SKUs via Billing Catalog for region: {}. Falling back to hardcoded estimate.",
                    region);
            // Fallback to approximate hardcoded estimate (USD)
            double fallbackHourly = (vcpus * 0.059) + (ramGb * 0.01); // Based on old us-central1 rates
            return fallbackHourly * HOURS_PER_MONTH;
        }

        // Get prices (hourly for CPU, monthly for RAM) from SKUs
        double cpuPricePerHour = getPricePerUnit(cpuSku.orElse(null), "h");
        double ramPricePerGiBMonth = getPricePerUnit(ramSku.orElse(null), "GiBy.mo");

        // Calculate monthly USD
        double cpuMonthly = cpuPricePerHour * vcpus * HOURS_PER_MONTH;
        double ramMonthly = ramPricePerGiBMonth * ramGb;

        double totalMonthlyUsd = cpuMonthly + ramMonthly;
        log.info("Cloud SQL Monthly Estimate from Catalog ({}): CPU ${:.2f} + RAM ${:.2f} = ${:.2f}", tier, cpuMonthly,
                ramMonthly, totalMonthlyUsd);

        return totalMonthlyUsd; // Return USD
    }

    /**
     * Build a human-readable tier description with vCPU, memory specs, and price IN
     * RUPEES.
     *
     * ✅ FIX 2025-10-28 (v3): Changed currency symbol to ₹ and expect INR price.
     */
    private String buildReadableTierDescription(String tier, double monthlyPriceInr) {
        if (tier == null || "Unknown".equals(tier)) {
            return "Unknown";
        }

        String specs = "";
        String displayTier = tier; // Use original tier name by default

        if (tier.startsWith("db-custom-")) {
            Matcher matcher = CUSTOM_TIER_PATTERN.matcher(tier);
            if (matcher.matches()) {
                String vcpus = matcher.group(1);
                double memoryMb = Double.parseDouble(matcher.group(2));
                double memoryGb = memoryMb / 1024.0;
                specs = String.format("%s vCPU, %.2f GB", vcpus, memoryGb);
            }
        } else if ("db-f1-micro".equals(tier)) {
            specs = "shared vCPU, 0.6 GB";
        } else if ("db-g1-small".equals(tier)) {
            specs = "shared vCPU, 1.7 GB";
        } else if (tier.startsWith("db-n1-standard-")) {
            // Extract vCPU for specs
            Pattern standardPattern = Pattern.compile("db-n1-standard-(\\d+)");
            Matcher matcher = standardPattern.matcher(tier);
            if (matcher.find()) {
                int vcpu = Integer.parseInt(matcher.group(1));
                // Standard ratio: 3.75 GB per vCPU (approximation)
                specs = String.format("%d vCPU, %.2f GB", vcpu, vcpu * 3.75);
            } else {
                specs = "N1 standard"; // Fallback
            }
        } else if (tier.contains("year") && tier.contains("Commitment")) { // Handle CUD descriptions
            // Extract specs if possible, otherwise use tier name
            specs = tier;
            displayTier = "Commitment"; // Generic tier name for CUD
        }
        // Add more else if blocks here if other tier patterns need specific spec
        // parsing

        // Use Rupee symbol and INR price
        return String.format("%s (%s, ₹%.2f/mo)", displayTier, specs, monthlyPriceInr);
    }

    /**
     * Infer recommended tier based on recommendation type
     */
    private String inferRecommendedTier(String currentTier, String recommendationType) {
        // This logic remains the same, it just suggests a tier name
        if ("PERFORMANCE_IMPROVEMENT".equals(recommendationType)) {
            // Underprovisioned - needs upgrade
            if ("db-f1-micro".equals(currentTier)) {
                return "db-custom-1-3840"; // Standard upgrade path
            } else if ("db-g1-small".equals(currentTier)) {
                return "db-custom-2-7680";
            } else if (currentTier.startsWith("db-custom-")) {
                Matcher matcher = CUSTOM_TIER_PATTERN.matcher(currentTier);
                if (matcher.matches()) {
                    int currentVcpu = Integer.parseInt(matcher.group(1));
                    int currentMemMb = Integer.parseInt(matcher.group(2));
                    // Simple doubling as an example, adjust logic as needed
                    return String.format("db-custom-%d-%d", currentVcpu * 2, currentMemMb * 2);
                }
            } else if (currentTier.startsWith("db-n1-standard-")) {
                Pattern standardPattern = Pattern.compile("db-n1-standard-(\\d+)");
                Matcher matcher = standardPattern.matcher(currentTier);
                if (matcher.matches()) {
                    int currentVcpu = Integer.parseInt(matcher.group(1));
                    int nextVcpu = currentVcpu * 2; // Simple doubling
                    // Find the next standard tier or suggest custom
                    if (Arrays.asList(1, 2, 4, 8, 16, 32, 64).contains(nextVcpu)) {
                        return String.format("db-n1-standard-%d", nextVcpu);
                    } else {
                        // Suggest custom if next standard doesn't exist or is too large
                        return String.format("db-custom-%d-%d", nextVcpu, (int) (nextVcpu * 3.75 * 1024));
                    }
                }
            }
        } else if ("COST_SAVINGS".equals(recommendationType)) {
            // Overprovisioned - can downgrade
            if (currentTier.startsWith("db-custom-")) {
                Matcher matcher = CUSTOM_TIER_PATTERN.matcher(currentTier);
                if (matcher.matches()) {
                    int currentVcpu = Integer.parseInt(matcher.group(1));
                    int currentMemMb = Integer.parseInt(matcher.group(2));
                    int newVcpu = Math.max(1, currentVcpu / 2); // Halve, minimum 1
                    int newMemMb = Math.max(3840, currentMemMb / 2); // Halve, minimum standard custom memory
                    return String.format("db-custom-%d-%d", newVcpu, newMemMb);
                }
            } else if (currentTier.startsWith("db-n1-standard-")) {
                Pattern standardPattern = Pattern.compile("db-n1-standard-(\\d+)");
                Matcher matcher = standardPattern.matcher(currentTier);
                if (matcher.matches()) {
                    int currentVcpu = Integer.parseInt(matcher.group(1));
                    int prevVcpu = Math.max(1, currentVcpu / 2); // Halve, minimum 1
                    // Suggest smaller standard or custom/small shared
                    if (prevVcpu == 1 && currentVcpu > 1)
                        return "db-n1-standard-1";
                    if (currentVcpu == 1)
                        return "db-g1-small"; // Example downgrade path
                    // Add more logic for other standard tiers
                    return String.format("db-n1-standard-%d", prevVcpu); // If prev standard exists
                }
            }
        }

        log.warn("Could not infer recommended tier for current tier '{}' and type '{}'", currentTier,
                recommendationType);
        return "Unknown"; // Fallback
    }

    /** Legacy single-return used internally (e.g. getSavingsSummary). */
    public List<GcpWasteItem> getWasteReport(String gcpProjectId) {
        return getWasteReport(gcpProjectId, false).getItems();
    }

    /**
     * Returns a GcpWasteReport with items + partial flag.
     * Fast group (existing detectors + Tier-1 compute) waits up to 45 s.
     * Slow group (Functions, GKE, BigQuery, Storage, Artifact, Scheduler) waits up to 40 s
     * independently — if it times out, partial=true is set and fast results are returned.
     */
    public com.xammer.cloud.dto.gcp.GcpWasteReport getWasteReport(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = "gcp:waste-report:" + gcpProjectId;
        String dbKey = "GCP_WASTE::" + gcpProjectId;

        if (!forceRefresh) {
            Optional<com.xammer.cloud.dto.gcp.GcpWasteReport> cached =
                    redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                log.info("✅ Returning Redis-cached waste report for project {}", gcpProjectId);
                return cached.get();
            }
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    com.xammer.cloud.dto.gcp.GcpWasteReport dbResult = objectMapper.readValue(
                            dbSnapshot.get().getJsonData(),
                            new TypeReference<com.xammer.cloud.dto.gcp.GcpWasteReport>() {});
                    log.info("✅ Returning DB-cached waste report for project {} — re-warming Redis", gcpProjectId);
                    redisCacheService.put(cacheKey, dbResult, 60);
                    return dbResult;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for waste report {}: {}", gcpProjectId, e.getMessage());
            }
        }

        log.info("[GcpWaste] Starting waste scan for project: {}", gcpProjectId);

        // Derive active locations
        List<String> locations;
        try {
            List<com.xammer.cloud.dto.gcp.GcpResourceDto> resources =
                    gcpDataService.getAllResources(gcpProjectId, false).join();
            Set<String> regionSet = resources.stream()
                    .map(com.xammer.cloud.dto.gcp.GcpResourceDto::getLocation)
                    .filter(loc -> loc != null && !loc.isEmpty() && !"global".equals(loc))
                    .map(loc -> { String[] p = loc.split("-"); return p.length > 2 ? p[0] + "-" + p[1] : loc; })
                    .filter(REGION_TO_LOCATION::containsKey)
                    .collect(Collectors.toSet());
            locations = regionSet.isEmpty() ? getDynamicRegions(gcpProjectId) : new ArrayList<>(regionSet);
            log.info("[GcpWaste] Scanning {} active locations for project {}", locations.size(), gcpProjectId);
        } catch (Exception e) {
            log.warn("[GcpWaste] Could not derive active regions from CloudList, falling back: {}", e.getMessage());
            locations = getDynamicRegions(gcpProjectId);
        }

        // ── Fast group (existing detectors + Tier-1 compute) ──
        List<CompletableFuture<List<GcpWasteItem>>> fastFutures = new ArrayList<>();
        for (String location : locations) {
            fastFutures.add(findIdleResources(gcpProjectId, "google.compute.disk.IdleResourceRecommender",
                    "Idle Persistent Disk", location));
            fastFutures.add(findIdleResources(gcpProjectId, "google.compute.address.IdleResourceRecommender",
                    "Unused IP Address", location));
            fastFutures.add(findIdleResources(gcpProjectId, "google.compute.instance.IdleResourceRecommender",
                    "Idle VM Instance", location));
            fastFutures.add(findIdleResources(gcpProjectId, "google.cloudsql.instance.IdleRecommender",
                    "Idle Cloud SQL Instance", location));
        }
        fastFutures.add(findUnattachedDisks(gcpProjectId));
        fastFutures.add(findUnusedCustomImages(gcpProjectId));
        fastFutures.add(findUnusedFirewallRules(gcpProjectId));
        fastFutures.add(findOldSqlSnapshots(gcpProjectId));
        fastFutures.add(findOldComputeSnapshots(gcpProjectId));
        fastFutures.add(findReservedUnusedIps(gcpProjectId));
        fastFutures.add(findLongStoppedInstances(gcpProjectId));
        fastFutures.add(findIdlePubSubTopics(gcpProjectId));
        // Tier-1 compute — same clients, just new detectors
        fastFutures.add(findEmptyInstanceGroups(gcpProjectId));
        fastFutures.add(findUnusedSslCertificates(gcpProjectId));
        fastFutures.add(findDownVpnTunnels(gcpProjectId));
        fastFutures.add(findEmptyTargetPools(gcpProjectId));

        List<CompletableFuture<List<GcpWasteItem>>> safeFast = fastFutures.stream()
                .map(f -> f.exceptionally(e -> {
                    log.warn("[GcpWaste] Fast detector failed: {}", e.getMessage());
                    return Collections.<GcpWasteItem>emptyList();
                }))
                .collect(Collectors.toList());

        List<GcpWasteItem> fastResults;
        try {
            CompletableFuture.allOf(safeFast.toArray(new CompletableFuture[0]))
                    .get(45, java.util.concurrent.TimeUnit.SECONDS);
            fastResults = safeFast.stream().map(CompletableFuture::join)
                    .flatMap(List::stream).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[GcpWaste] Fast group timed out — collecting completed futures: {}", e.getMessage());
            fastResults = safeFast.stream()
                    .filter(f -> f.isDone() && !f.isCompletedExceptionally())
                    .map(CompletableFuture::join)
                    .flatMap(List::stream).collect(Collectors.toList());
        }

        // ── Slow group (higher-latency clients) ──
        List<CompletableFuture<List<GcpWasteItem>>> slowFutures = List.of(
                findFunctionsWithNoInvocations(gcpProjectId, locations),
                findIdleGkeClusters(gcpProjectId),
                findBigQueryDatasetsNotQueried(gcpProjectId),
                findStorageBucketsWithNoReads(gcpProjectId),
                findStaleArtifactRepos(gcpProjectId, locations),
                findAlwaysFailingSchedulerJobs(gcpProjectId, locations)
        );

        List<CompletableFuture<List<GcpWasteItem>>> safeSlow = slowFutures.stream()
                .map(f -> f.exceptionally(e -> {
                    log.warn("[GcpWaste] Slow detector failed: {}", e.getMessage());
                    return Collections.<GcpWasteItem>emptyList();
                }))
                .collect(Collectors.toList());

        boolean partial = false;
        List<GcpWasteItem> slowResults;
        try {
            CompletableFuture.allOf(safeSlow.toArray(new CompletableFuture[0]))
                    .get(40, java.util.concurrent.TimeUnit.SECONDS);
            slowResults = safeSlow.stream().map(CompletableFuture::join)
                    .flatMap(List::stream).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[GcpWaste] Slow group timed out — returning partial results: {}", e.getMessage());
            partial = true;
            slowResults = safeSlow.stream()
                    .filter(f -> f.isDone() && !f.isCompletedExceptionally())
                    .map(CompletableFuture::join)
                    .flatMap(List::stream).collect(Collectors.toList());
        }

        List<GcpWasteItem> allWaste = new ArrayList<>(fastResults);
        allWaste.addAll(slowResults);
        log.info("[GcpWaste] Total: {} items (partial={}) for project {}", allWaste.size(), partial, gcpProjectId);

        saveToDb("WASTE::" + gcpProjectId + "::" + java.time.LocalDate.now(), allWaste);

        com.xammer.cloud.dto.gcp.GcpWasteReport report =
                new com.xammer.cloud.dto.gcp.GcpWasteReport(allWaste, partial);
        redisCacheService.put(cacheKey, report, 60);
        saveToDb(dbKey, report);
        return report;
    }

    //
    // -------------------- ⬇️ FIX 1: CACHE ANNOTATION REMOVED ⬇️
    // --------------------
    //
    // @Cacheable(value = "gcpSavingsSummary", key = "'gcp:savings-summary:' +
    // #gcpProjectId")
    public DashboardData.SavingsSummary getSavingsSummary(String gcpProjectId) {
        List<GcpWasteItem> waste = getWasteReport(gcpProjectId);
        List<GcpOptimizationRecommendation> rightsizing = getRightsizingRecommendations(gcpProjectId);

        double wasteSavings = waste.stream().mapToDouble(GcpWasteItem::getMonthlySavings).sum();
        double rightsizingSavings = rightsizing.stream()
                .filter(r -> r.getMonthlySavings() >= 0) // Only count actual savings
                .mapToDouble(GcpOptimizationRecommendation::getMonthlySavings)
                .sum();

        List<DashboardData.SavingsSuggestion> suggestions = new ArrayList<>();
        if (rightsizingSavings > 0)
            suggestions.add(new DashboardData.SavingsSuggestion("Rightsizing", rightsizingSavings));
        if (wasteSavings > 0)
            suggestions.add(new DashboardData.SavingsSuggestion("Waste Elimination", wasteSavings));

        // Return values in INR
        // Use the three-argument constructor: (realized, potential, suggestions)
        // These are potential savings from recommendations, so realized = 0
        return new DashboardData.SavingsSummary(0, wasteSavings + rightsizingSavings, suggestions);
    }

    //
    // -------------------- ⬇️ FIX 2: CACHE ANNOTATION REMOVED ⬇️
    // --------------------
    //
    // @Cacheable(value = "gcpOptimizationSummary", key =
    // "'gcp:optimization-summary:' + #gcpProjectId")
    public DashboardData.OptimizationSummary getOptimizationSummary(String gcpProjectId) {
        List<GcpWasteItem> waste = getWasteReport(gcpProjectId);
        List<GcpOptimizationRecommendation> rightsizing = getRightsizingRecommendations(gcpProjectId);

        double totalSavings = waste.stream().mapToDouble(GcpWasteItem::getMonthlySavings).sum()
                + rightsizing.stream()
                        .filter(r -> r.getMonthlySavings() >= 0) // Only count actual savings
                        .mapToDouble(GcpOptimizationRecommendation::getMonthlySavings)
                        .sum();
        long criticalAlerts = rightsizing.size(); // Count all recommendations as alerts

        // Return savings in INR
        return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
    }

    public CompletableFuture<List<GcpOptimizationRecommendation>> getCudRecommendations(String gcpProjectId) {
        log.info("[GcpCUD] Fetching CUD recommendations for project={}", gcpProjectId);

        CloudAccount account = cloudAccountService.findByGcpProjectId(gcpProjectId);
        if (account == null) {
            log.error("[GcpCUD] No CloudAccount for project={}", gcpProjectId);
            return CompletableFuture.completedFuture(List.of());
        }
        String billingAccountId = account.getGcpBillingAccountId();

        // Gap 13: derive active regions from resource inventory instead of scanning all 40+ regions
        List<com.xammer.cloud.dto.gcp.GcpResourceDto> resources = Collections.emptyList();
        try {
            resources = gcpDataService.getAllResources(gcpProjectId, false).join();
        } catch (Exception e) {
            log.warn("[GcpCUD] Could not fetch resources for region derivation for {}: {}", gcpProjectId, e.getMessage());
        }
        Set<String> regionSet = resources.stream()
                .map(com.xammer.cloud.dto.gcp.GcpResourceDto::getLocation)
                .filter(loc -> loc != null && !loc.isEmpty() && !"global".equals(loc))
                .map(loc -> { String[] p = loc.split("-"); return p.length > 2 ? p[0] + "-" + p[1] : loc; })
                .filter(REGION_TO_LOCATION::containsKey)
                .collect(Collectors.toSet());
        List<String> locations = regionSet.isEmpty() ? getDynamicRegions(gcpProjectId) : new ArrayList<>(regionSet);
        log.info("[GcpCUD] Scanning {} regions for project={}", locations.size(), gcpProjectId);

        List<CompletableFuture<List<GcpOptimizationRecommendation>>> futures = new ArrayList<>();

        if (billingAccountId != null && !billingAccountId.isEmpty()) {
            log.info("[GcpCUD] Adding billing-account scope for billingAccount={}", billingAccountId);
            for (String location : locations) {
                futures.add(getRecommendationsForRecommender(
                        billingAccountId,
                        "google.compute.commitment.UsageCommitmentRecommender",
                        location,
                        this::mapToCudRecommendationDto,
                        false));
            }
        } else {
            log.warn("[GcpCUD] No billing account for project={} — project-level only", gcpProjectId);
        }

        futures.addAll(getProjectLevelCudRecommendationsFutures(gcpProjectId, locations));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, GcpOptimizationRecommendation> unique = new LinkedHashMap<>();
                    futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .forEach(rec -> unique.putIfAbsent(rec.getRecommendationId(), rec));
                    List<GcpOptimizationRecommendation> result = new ArrayList<>(unique.values());
                    log.info("[GcpCUD] {} unique CUD recommendations for project={}", result.size(), gcpProjectId);
                    return result;
                });
    }

    public List<GcpOptimizationRecommendation> getCudRecommendationsSync(String gcpProjectId) {
        return getCudRecommendationsSync(gcpProjectId, false);
    }

    public List<GcpOptimizationRecommendation> getCudRecommendationsSync(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = "gcp:cud-recommendations:" + gcpProjectId;
        String dbKey = "GCP_CUD_RECS::" + gcpProjectId;

        if (!forceRefresh) {
            Optional<List<GcpOptimizationRecommendation>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                log.info("✅ Redis hit for CUD recs project={}", gcpProjectId);
                return cached.get();
            }
            try {
                Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                if (dbSnap.isPresent()) {
                    List<GcpOptimizationRecommendation> dbResult = objectMapper.readValue(
                            dbSnap.get().getJsonData(), new TypeReference<>() {});
                    log.info("✅ DB hit for CUD recs project={} — re-warming Redis", gcpProjectId);
                    redisCacheService.put(cacheKey, dbResult, 60);
                    return dbResult;
                }
            } catch (Exception e) {
                log.warn("DB load failed for CUD recs {}: {}", gcpProjectId, e.getMessage());
            }
        }

        List<GcpOptimizationRecommendation> result = getCudRecommendations(gcpProjectId).join();
        redisCacheService.put(cacheKey, result, 60);
        saveToDb(dbKey, result);
        // Archive monthly snapshot for historical chart
        saveToDb("GCP_CUD_SNAPSHOT::" + gcpProjectId + "::" + YearMonth.now(), result);
        return result;
    }

    private List<CompletableFuture<List<GcpOptimizationRecommendation>>> getProjectLevelCudRecommendationsFutures(
            String gcpProjectId, List<String> locations) {
        return locations.stream()
                .map(location -> getRecommendationsForRecommender(
                        gcpProjectId,
                        "google.compute.commitment.UsageCommitmentRecommender",
                        location,
                        this::mapToCudRecommendationDto,
                        true))
                .collect(Collectors.toList());
    }

    /**
     * Gap 5: Historical CUD data — up to 6 monthly snapshots from DB.
     * Immutable-window: if a snapshot for this calendar month already exists, return it without re-fetching.
     */
    public List<Map<String, Object>> getHistoricalCudData(String gcpProjectId, boolean forceRefresh) {
        String thisMonthKey = "GCP_CUD_HISTORICAL::" + gcpProjectId + "::" + YearMonth.now();

        if (!forceRefresh) {
            try {
                Optional<CachedData> existing = cachedDataRepository.findById(thisMonthKey);
                if (existing.isPresent()) {
                    List<Map<String, Object>> cached = objectMapper.readValue(
                            existing.get().getJsonData(), new TypeReference<>() {});
                    log.info("[GcpCUD] Returning immutable-window historical data for project={}", gcpProjectId);
                    return cached;
                }
            } catch (Exception e) {
                log.warn("[GcpCUD] Failed to load historical snapshot for {}: {}", gcpProjectId, e.getMessage());
            }
        }

        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth month = YearMonth.now().minusMonths(i);
            String snapshotKey = "GCP_CUD_SNAPSHOT::" + gcpProjectId + "::" + month;
            try {
                Optional<CachedData> snap = cachedDataRepository.findById(snapshotKey);
                if (snap.isPresent()) {
                    List<GcpOptimizationRecommendation> recs = objectMapper.readValue(
                            snap.get().getJsonData(), new TypeReference<>() {});
                    double totalSavings = recs.stream().mapToDouble(GcpOptimizationRecommendation::getMonthlySavings).sum();
                    Map<String, Object> point = new HashMap<>();
                    point.put("month", month.toString());
                    point.put("totalRecommendations", recs.size());
                    point.put("totalMonthlySavings", totalSavings);
                    history.add(point);
                }
            } catch (Exception e) {
                log.warn("[GcpCUD] Could not load snapshot for month={}: {}", month, e.getMessage());
            }
        }

        if (!history.isEmpty()) {
            saveToDb(thisMonthKey, history);
        }
        return history;
    }

    /**
     * Gap 6: Modification recommendations — derived from CUD inventory (expire soon → renew)
     * and recommendation data (large savings still uncovered → add new CUD).
     */
    public List<Map<String, Object>> getCudModificationRecommendations(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = "gcp:cud-mod-recs:" + gcpProjectId;
        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) return cached.get();
        }

        List<GcpCommittedUseDiscountDto> inventory = getCommittedUseDiscountsSync(gcpProjectId, forceRefresh);
        List<GcpOptimizationRecommendation> recs = getCudRecommendationsSync(gcpProjectId, forceRefresh);
        List<Map<String, Object>> suggestions = new ArrayList<>();

        // Suggest renewing EXPIRED CUDs
        inventory.stream()
                .filter(cud -> "EXPIRED".equalsIgnoreCase(cud.getStatus()))
                .forEach(cud -> {
                    Map<String, Object> s = new HashMap<>();
                    s.put("type", "RENEW");
                    s.put("cudId", cud.getId());
                    s.put("cudName", cud.getName());
                    s.put("region", cud.getRegion());
                    s.put("currentPlan", cud.getPlan());
                    s.put("suggestedPlan", "TWELVE_MONTH");
                    s.put("reason", "CUD has expired — renewing would immediately restore discount coverage.");
                    suggestions.add(s);
                });

        // Suggest purchasing new CUDs for top recommendation opportunities
        recs.stream()
                .filter(r -> r.getMonthlySavings() > 50)
                .limit(5)
                .forEach(r -> {
                    Map<String, Object> s = new HashMap<>();
                    s.put("type", "NEW_PURCHASE");
                    s.put("location", r.getLocation());
                    s.put("service", r.getService());
                    s.put("estimatedMonthlySavings", r.getMonthlySavings());
                    s.put("description", r.getCurrentMachineType());
                    s.put("reason", "Recommender API identified an uncovered commitment opportunity with significant savings.");
                    suggestions.add(s);
                });

        redisCacheService.put(cacheKey, suggestions, 60);
        return suggestions;
    }

    /**
     * Gap 1: Page-bundle endpoint — 4-way parallel fan-out, returns all reservation domains in one shot.
     */
    public GcpReservationPageDto getReservationPageBundle(String gcpProjectId, boolean forceRefresh) {
        String dbKey = "RESERVATION_PAGE::" + gcpProjectId;

        if (!forceRefresh) {
            try {
                Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                if (dbSnap.isPresent()) {
                    GcpReservationPageDto page = objectMapper.readValue(dbSnap.get().getJsonData(), GcpReservationPageDto.class);
                    log.info("[GcpReservation] Returning DB-cached page bundle for project={}", gcpProjectId);
                    return page;
                }
            } catch (Exception e) {
                log.warn("[GcpReservation] DB page bundle load failed for {}: {}", gcpProjectId, e.getMessage());
            }
        }

        log.info("[GcpReservation] Building page bundle for project={}", gcpProjectId);

        CompletableFuture<List<GcpOptimizationRecommendation>> cudRecsFuture =
                CompletableFuture.supplyAsync(() -> getCudRecommendationsSync(gcpProjectId, forceRefresh), executor)
                        .exceptionally(e -> { log.warn("[GcpReservation] CUD recs failed: {}", e.getMessage()); return Collections.emptyList(); });

        CompletableFuture<List<GcpCommittedUseDiscountDto>> inventoryFuture =
                CompletableFuture.supplyAsync(() -> getCommittedUseDiscountsSync(gcpProjectId, forceRefresh), executor)
                        .exceptionally(e -> { log.warn("[GcpReservation] Inventory failed: {}", e.getMessage()); return Collections.emptyList(); });

        CompletableFuture<Map<String, Object>> summaryFuture =
                CompletableFuture.supplyAsync(() -> getCudUtilizationSummary(gcpProjectId, forceRefresh), executor)
                        .exceptionally(e -> { log.warn("[GcpReservation] Summary failed: {}", e.getMessage()); return Collections.emptyMap(); });

        CompletableFuture<List<Map<String, Object>>> historicalFuture =
                CompletableFuture.supplyAsync(() -> getHistoricalCudData(gcpProjectId, forceRefresh), executor)
                        .exceptionally(e -> { log.warn("[GcpReservation] Historical failed: {}", e.getMessage()); return Collections.emptyList(); });

        CompletableFuture.allOf(cudRecsFuture, inventoryFuture, summaryFuture, historicalFuture).join();

        List<Map<String, Object>> modRecs = getCudModificationRecommendations(gcpProjectId, forceRefresh);

        GcpReservationPageDto page = new GcpReservationPageDto(
                cudRecsFuture.join(),
                inventoryFuture.join(),
                summaryFuture.join(),
                historicalFuture.join(),
                modRecs
        );

        saveToDb(dbKey, page);
        return page;
    }

    private String getProjectNumber(String gcpProjectId) {
        try {
            Optional<ProjectsClient> clientOpt = gcpClientProvider.getProjectsClient(gcpProjectId);
            if (clientOpt.isPresent()) {
                try (ProjectsClient client = clientOpt.get()) {
                    Project project = client.getProject("projects/" + gcpProjectId);
                    // Project name format is "projects/{project_number}"
                    String projectName = project.getName();
                    if (projectName.contains("/")) {
                        return projectName.split("/")[1];
                    }
                    // Fallback, though typically the format includes the slash
                    return projectName;
                }
            }
        } catch (Exception e) {
            log.warn("Could not get project number for {}: {}", gcpProjectId, e.getMessage());
        }
        return null;
    }

    private <T> CompletableFuture<List<T>> getRecommendationsForRecommender(
            String identifier,
            String recommenderId,
            String location,
            Function<Recommendation, T> mapper,
            boolean isProjectScope) {

        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = isProjectScope
                    ? gcpClientProvider.getRecommenderClient(identifier)
                    : gcpClientProvider.getRecommenderClientForBillingAccount(identifier);

            if (clientOpt.isEmpty()) {
                log.warn("Recommender client not available for {} '{}'. Skipping recommendations for {} in {}.",
                        isProjectScope ? "project" : "billing account",
                        identifier, recommenderId, location);
                return List.of();
            }

            try (RecommenderClient client = clientOpt.get()) {
                String parent;
                if (isProjectScope) {
                    parent = String.format("projects/%s/locations/%s/recommenders/%s",
                            identifier, location, recommenderId);
                } else {
                    parent = String.format("billingAccounts/%s/locations/%s/recommenders/%s",
                            identifier, location, recommenderId);
                }

                log.info("Querying recommender '{}' for {} {} in location {}",
                        recommenderId,
                        isProjectScope ? "project" : "billing account",
                        identifier,
                        location);

                List<Recommendation> recommendations = StreamSupport.stream(
                        client.listRecommendations(parent).iterateAll().spliterator(),
                        false)
                        .collect(Collectors.toList());

                log.info("Raw recommendations count: {} for parent: {}", recommendations.size(), parent);

                // ✅ Filter by recommendation state - only include ACTIVE and CLAIMED
                List<Recommendation> filteredRecommendations = recommendations.stream()
                        .filter(rec -> {
                            String state = rec.getStateInfo().getState().name();
                            boolean isValid = "ACTIVE".equals(state) || "CLAIMED".equals(state);
                            if (!isValid) {
                                log.debug("Filtering out recommendation {} with state: {}", rec.getName(), state);
                            }
                            return isValid;
                        })
                        .collect(Collectors.toList());

                int filteredCount = recommendations.size() - filteredRecommendations.size();
                if (filteredCount > 0) {
                    log.info("✅ Filtered {} recommendations by state (keeping only ACTIVE/CLAIMED)", filteredCount);
                }

                if (!filteredRecommendations.isEmpty()) {
                    Recommendation firstRec = filteredRecommendations.get(0);
                    log.debug("Sample recommendation - Name: {}, Description: {}, State: {}", // Changed to debug
                            firstRec.getName(),
                            firstRec.getDescription(),
                            firstRec.getStateInfo().getState());
                } else {
                    log.info("No active recommendations found for parent: {}", parent);
                }

                return filteredRecommendations.stream()
                        .map(mapper)
                        .filter(Objects::nonNull) // Ensure mapper doesn't return null
                        .collect(Collectors.toList());

            } catch (com.google.api.gax.rpc.PermissionDeniedException e) {
                log.error(
                        "Permission denied querying recommender {} for {} {} in location {}. Ensure Recommender API is enabled and permissions are correct.",
                        recommenderId, isProjectScope ? "project" : "billing account", identifier, location, e);
                return List.of();
            } catch (Exception e) {
                log.error("Failed to get recommendations from {} for {} {} in location {}",
                        recommenderId,
                        isProjectScope ? "project" : "billing account",
                        identifier,
                        location,
                        e);
                return List.of();
            }
        }, executor);
    }

    private CompletableFuture<List<GcpWasteItem>> findOldSqlSnapshots(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Checking for old Cloud SQL backups for project: {}", gcpProjectId);
            Optional<SQLAdmin> clientOpt = gcpClientProvider.getSqlAdminClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("SQLAdmin client not available for project {}. Skipping check.", gcpProjectId);
                return List.of();
            }

            try {
                List<GcpWasteItem> wasteItems = new ArrayList<>();
                SQLAdmin sqlAdmin = clientOpt.get();
                InstancesListResponse instances = sqlAdmin.instances().list(gcpProjectId).execute();

                if (instances.getItems() == null || instances.getItems().isEmpty()) {
                    log.info("No Cloud SQL instances found for project {}.", gcpProjectId);
                    return List.of();
                }

                for (DatabaseInstance instance : instances.getItems()) {
                    String instanceName = instance.getName();
                    try {
                        List<BackupRun> backups = sqlAdmin.backupRuns()
                                .list(gcpProjectId, instanceName)
                                .execute()
                                .getItems();
                        Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);

                        if (backups != null) {
                            backups.stream()
                                    .filter(backup -> {
                                        try {
                                            if (backup.getWindowStartTime() == null)
                                                return false;
                                            Instant backupTime = Instant.parse(backup.getWindowStartTime());
                                            return backupTime.isBefore(ninetyDaysAgo);
                                        } catch (Exception ex) {
                                            log.warn("Failed to parse backup window start time for instance {}: {}",
                                                    instanceName, backup.getWindowStartTime(), ex);
                                            return false;
                                        }
                                    })
                                    .map(backup -> new GcpWasteItem(
                                            backup.getId().toString(),
                                            instanceName + "/backup-" + backup.getId(),
                                            "Old Cloud SQL Backup (>90 days)",
                                            instance.getRegion(),
                                            EST_BACKUP_COST_USD,
                                            "Backup is older than 90 days"))
                                    .forEach(wasteItems::add);
                        }
                    } catch (Exception backupEx) {
                        log.error("Failed to list backups for Cloud SQL instance {}: {}", instanceName,
                                backupEx.getMessage());
                        // Continue to the next instance
                    }
                }
                log.info("Found {} old Cloud SQL backups for project {}.", wasteItems.size(), gcpProjectId);
                return wasteItems;
            } catch (Exception e) {
                log.error("Failed to list Cloud SQL instances for project {}:", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }

    private CompletableFuture<List<GcpWasteItem>> findUnattachedDisks(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Checking for unattached Persistent Disks for project: {}", gcpProjectId);
            Optional<DisksClient> clientOpt = gcpClientProvider.getDisksClient(gcpProjectId);
            if (clientOpt.isEmpty())
                return List.of();

            try (DisksClient client = clientOpt.get()) {
                return StreamSupport.stream(
                        client.aggregatedList(gcpProjectId).iterateAll().spliterator(),
                        false)
                        .flatMap(entry -> entry.getValue().getDisksList().stream())
                        .filter(disk -> disk.getUsersList().isEmpty())
                        .map(disk -> {
                            String zoneUrl = disk.getZone();
                            String zone = zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1);
                            return new GcpWasteItem(
                                    disk.getName(),
                                    disk.getName(),
                                    "Unattached Persistent Disk",
                                    zone,
                                    calculateDiskCostUsd(disk.getSizeGb()),
                                    "Disk has no users attached"
                            );
                        })
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to list unattached disks for project {}:", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }

    private CompletableFuture<List<GcpWasteItem>> findUnusedCustomImages(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Checking for unused custom images for project: {}", gcpProjectId);
            Optional<ImagesClient> clientOpt = gcpClientProvider.getImagesClient(gcpProjectId);
            if (clientOpt.isEmpty())
                return List.of();

            try (ImagesClient client = clientOpt.get()) {
                return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                        .filter(image -> image.hasDeprecated() && "DEPRECATED".equals(image.getDeprecated().getState()))
                        .map(image -> new GcpWasteItem(
                                image.getName(),
                                image.getName(),
                                "Deprecated Custom Image",
                                "global",
                                EST_IMAGE_STORAGE_COST_USD_PER_GB * image.getDiskSizeGb(),
                                "Image is in DEPRECATED state"))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to list custom images for project {}:", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }

    private CompletableFuture<List<GcpWasteItem>> findUnusedFirewallRules(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Checking for disabled firewall rules for project: {}", gcpProjectId);
            Optional<FirewallsClient> clientOpt = gcpClientProvider.getFirewallsClient(gcpProjectId);
            if (clientOpt.isEmpty())
                return List.of();

            try (FirewallsClient client = clientOpt.get()) {
                return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                        .filter(Firewall::getDisabled)
                        .map(firewall -> new GcpWasteItem(
                                firewall.getName(),
                                firewall.getName(),
                                "Disabled Firewall Rule",
                                "global",
                                0.0,
                                "Firewall rule is explicitly disabled"))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to list firewall rules for project {}:", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }

    // Gap 3: Old Compute Engine snapshots (>90 days)
    private CompletableFuture<List<GcpWasteItem>> findOldComputeSnapshots(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<SnapshotsClient> clientOpt = gcpClientProvider.getSnapshotsClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (SnapshotsClient client = clientOpt.get()) {
                Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
                return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                        .filter(snap -> {
                            try {
                                return Instant.parse(snap.getCreationTimestamp()).isBefore(cutoff);
                            } catch (Exception e) { return false; }
                        })
                        .map(snap -> new GcpWasteItem(
                                snap.getName(),
                                snap.getName(),
                                "Old Compute Snapshot",
                                "global",
                                snap.getDiskSizeGb() * 0.026,
                                "Snapshot is older than 90 days"))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("[GcpWaste] findOldComputeSnapshots failed for {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    // Gap 3: Reserved (unassigned) external IP addresses
    private CompletableFuture<List<GcpWasteItem>> findReservedUnusedIps(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<AddressesClient> clientOpt = gcpClientProvider.getAddressesClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (AddressesClient client = clientOpt.get()) {
                return StreamSupport.stream(
                        client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(entry -> entry.getValue().getAddressesList().stream())
                        .filter(addr -> "RESERVED".equals(addr.getStatus()))
                        .map(addr -> new GcpWasteItem(
                                addr.getName(),
                                addr.getAddress(),
                                "Reserved Unused IP",
                                addr.getRegion().contains("/") ? addr.getRegion().substring(addr.getRegion().lastIndexOf('/') + 1) : addr.getRegion(),
                                7.30,
                                "Static IP is RESERVED but not assigned to any resource"))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("[GcpWaste] findReservedUnusedIps failed for {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    // Gap 3: GCE instances stopped for more than 30 days (still incur disk costs)
    private CompletableFuture<List<GcpWasteItem>> findLongStoppedInstances(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (InstancesClient client = clientOpt.get()) {
                Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
                List<GcpWasteItem> items = new ArrayList<>();
                for (var entry : client.aggregatedList(gcpProjectId).iterateAll()) {
                    for (com.google.cloud.compute.v1.Instance inst : entry.getValue().getInstancesList()) {
                        if (!"TERMINATED".equals(inst.getStatus())) continue;
                        try {
                            String lastStop = inst.getLastStopTimestamp();
                            if (lastStop == null || lastStop.isEmpty()) continue;
                            if (Instant.parse(lastStop).isBefore(cutoff)) {
                                String zoneUrl = inst.getZone();
                                String zone = zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1);
                                double diskCost = inst.getDisksList().size() * EST_PD_COST_USD_PER_GB * 50;
                                items.add(new GcpWasteItem(
                                        inst.getName(),
                                        inst.getName(),
                                        "Long-Stopped VM Instance",
                                        zone,
                                        diskCost,
                                        "Instance has been stopped for more than 30 days"));
                            }
                        } catch (Exception e) {
                            log.debug("[GcpWaste] Could not parse stop time for instance {}: {}", inst.getName(), e.getMessage());
                        }
                    }
                }
                return items;
            } catch (Exception e) {
                log.warn("[GcpWaste] findLongStoppedInstances failed for {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    // Gap 3: Pub/Sub topics with no subscriptions
    private CompletableFuture<List<GcpWasteItem>> findIdlePubSubTopics(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.cloud.pubsub.v1.TopicAdminClient> topicOpt = gcpClientProvider.getPubSubTopicClient(gcpProjectId);
            Optional<com.google.cloud.pubsub.v1.SubscriptionAdminClient> subOpt = gcpClientProvider.getPubSubSubscriptionClient(gcpProjectId);
            if (topicOpt.isEmpty() || subOpt.isEmpty()) return List.of();
            try (com.google.cloud.pubsub.v1.TopicAdminClient topicClient = topicOpt.get();
                 com.google.cloud.pubsub.v1.SubscriptionAdminClient subClient = subOpt.get()) {
                String projectPath = ProjectName.of(gcpProjectId).toString();
                List<GcpWasteItem> items = new ArrayList<>();
                for (com.google.pubsub.v1.Topic topic : topicClient.listTopics(projectPath).iterateAll()) {
                    String topicName = topic.getName();
                    boolean hasSubscribers = topicClient.listTopicSubscriptions(topicName).iterateAll().iterator().hasNext();
                    if (!hasSubscribers) {
                        String shortName = topicName.substring(topicName.lastIndexOf('/') + 1);
                        items.add(new GcpWasteItem(
                                topicName,
                                shortName,
                                "Idle Pub/Sub Topic",
                                "global",
                                0.0,
                                "Topic has no subscriptions"));
                    }
                }
                return items;
            } catch (Exception e) {
                log.warn("[GcpWaste] findIdlePubSubTopics failed for {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    private CompletableFuture<List<GcpWasteItem>> findIdleResources(
            String gcpProjectId,
            String recommenderId,
            String wasteType,
            String location) {

        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty())
                return new ArrayList<>();

            try (RecommenderClient client = clientOpt.get()) {
                // ✅ Use provided location instead of hardcoded "global"
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, location, recommenderId);
                log.info("Querying recommender '{}' for project {} in location {}", recommenderId, gcpProjectId,
                        location);

                return StreamSupport.stream(
                        client.listRecommendations(recommenderName).iterateAll().spliterator(),
                        false)
                        .map(rec -> mapToWasteDto(rec, wasteType))
                        .filter(Objects::nonNull) // Filter out nulls if mapping fails
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to get waste report for recommender {} in project {} at location {}",
                        recommenderId, gcpProjectId, location, e);
                return new ArrayList<>();
            }
        }, executor);
    }

    private GcpWasteItem mapToWasteDto(Recommendation rec, String wasteType) {
        String resourceName = "N/A";
        // Attempt to extract resource name more robustly
        var overviewFields = rec.getContent().getOverview().getFieldsMap();
        if (overviewFields.containsKey("resourceName")) {
            resourceName = overviewFields.get("resourceName").getStringValue();
            // Sometimes resourceName is just the UUID, try getting from 'resource' if so
            if (isUuid(resourceName) && overviewFields.containsKey("resource")) {
                String fullPath = overviewFields.get("resource").getStringValue();
                resourceName = fullPath.substring(fullPath.lastIndexOf('/') + 1);
            }
        } else if (overviewFields.containsKey("resource")) {
            resourceName = overviewFields.get("resource").getStringValue();
            resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }

        double monthlySavingsUsd = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            Money costMoney = rec.getPrimaryImpact().getCostProjection().getCost();
            double costImpact = costMoney.getUnits() + (costMoney.getNanos() / 1_000_000_000.0);
            if (costImpact < 0) {
                monthlySavingsUsd = Math.abs(costImpact);
            }
        }

        String reason = rec.getDescription().isEmpty() ? wasteType + " identified by GCP Recommender" : rec.getDescription();

        return new GcpWasteItem(resourceName, resourceName, wasteType, extractLocation(rec.getName()), monthlySavingsUsd, reason);
    }

    private GcpOptimizationRecommendation mapToRightsizingDto(Recommendation rec) {
        String resourceName = extractResourceName(rec);
        String currentMachineType = "N/A";
        String recommendedMachineType = "N/A";

        // Extract machine types from description
        if (rec.getDescription().contains(" to ")) {
            String[] parts = rec.getDescription().split(" to ");
            // Example: "Change machine type from n1-standard-4 to n1-standard-2"
            // More robust extraction
            Pattern fromPattern = Pattern.compile("from\\s+([\\w-]+)");
            Matcher fromMatcher = fromPattern.matcher(parts[0]);
            if (fromMatcher.find()) {
                currentMachineType = fromMatcher.group(1).trim();
            }
            recommendedMachineType = parts[1].trim();
        }

        double monthlySavingsUsd = 0;
        String currencyCode = "USD";
        if (rec.getPrimaryImpact().hasCostProjection()) {
            Money costMoney = rec.getPrimaryImpact().getCostProjection().getCost();
            if (costMoney.getCurrencyCode() != null && !costMoney.getCurrencyCode().isEmpty()) {
                currencyCode = costMoney.getCurrencyCode();
            }
            double costImpact = costMoney.getUnits() + (costMoney.getNanos() / 1_000_000_000.0);

            if (costImpact < 0) {
                monthlySavingsUsd = Math.abs(costImpact); // Savings are positive
            } else {
                monthlySavingsUsd = -costImpact; // Cost increase is negative
            }
        }

        // Convert to INR
        double monthlySavingsInr = monthlySavingsUsd;
        if ("USD".equalsIgnoreCase(currencyCode)) {
            monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE;
        } else if (!"INR".equalsIgnoreCase(currencyCode)) {
            log.warn("Unsupported currency {} in rightsizing recommendation {}, assuming USD for conversion.",
                    currencyCode, rec.getName());
            monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE; // Fallback to USD conversion
        }

        String location = extractLocation(rec.getName());
        String recommendationType = monthlySavingsInr >= 0 ? "COST_SAVINGS" : "PERFORMANCE_IMPROVEMENT";
        String description = rec.getDescription();

        return new GcpOptimizationRecommendation(
                resourceName,
                currentMachineType,
                recommendedMachineType,
                monthlySavingsInr, // Use INR value
                "Compute Engine", // Assuming this mapper is for Compute Engine
                location,
                rec.getName(),
                recommendationType,
                description);
    }

    /**
     * Maps Cloud SQL recommendation to DTO, returns savings/cost in INR.
     */
    private GcpOptimizationRecommendation mapToSqlRightsizingDto(Recommendation rec) {
        String description = rec.getDescription();
        log.info("📊 Processing Cloud SQL Recommendation: {}", rec.getName());
        log.info("Description: {}", description);

        // Extract instance name
        String resourceName = extractResourceName(rec);
        log.info("Instance name: {}", resourceName);

        // Determine recommendation type
        String recommenderName = rec.getName();
        boolean isUnderprovisioned = recommenderName.contains("UnderprovisionedRecommender");
        boolean isOverprovisioned = recommenderName.contains("OverprovisionedRecommender");

        String recommendationType;
        if (isUnderprovisioned) {
            recommendationType = "PERFORMANCE_IMPROVEMENT";
        } else if (isOverprovisioned) {
            recommendationType = "COST_SAVINGS";
        } else {
            recommendationType = "OPTIMIZATION";
        }
        log.info("Recommendation type: {}", recommendationType);

        // Extract CURRENT tier from description (best effort)
        String currentTier = extractCurrentTierFromDescription(description);
        log.info("Current tier (from description): {}", currentTier);

        // Extract recommended tier from description
        String recommendedTier = extractRecommendedTierFromDescription(description);
        log.info("Recommended tier: {}", recommendedTier);

        // Get cost projection from Recommender API (USD)
        double monthlySavingsUsd = 0.0;
        String currencyCode = "USD";
        if (rec.getPrimaryImpact().hasCostProjection()) {
            Money costMoney = rec.getPrimaryImpact().getCostProjection().getCost();
            if (costMoney.getCurrencyCode() != null && !costMoney.getCurrencyCode().isEmpty()) {
                currencyCode = costMoney.getCurrencyCode();
            }
            double costImpactUsd = costMoney.getUnits() + (costMoney.getNanos() / 1_000_000_000.0);

            if (costImpactUsd < 0) {
                // Negative costImpact from API means savings
                monthlySavingsUsd = Math.abs(costImpactUsd);
                log.info("Recommender API savings ({}): {}", currencyCode, String.format("%.2f", monthlySavingsUsd));
            } else if (costImpactUsd > 0 && isUnderprovisioned) {
                // Positive costImpact for underprovisioned means cost increase
                monthlySavingsUsd = -costImpactUsd; // Store as negative
                log.info("Recommender API cost increase ({}): {}", currencyCode, String.format("%.2f", costImpactUsd));
            }
            // If costImpactUsd is 0, monthlySavingsUsd remains 0.0
        }

        // Convert to INR
        double monthlySavingsInr = monthlySavingsUsd;
        if ("USD".equalsIgnoreCase(currencyCode)) {
            monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE;
        } else if (!"INR".equalsIgnoreCase(currencyCode)) {
            log.warn("Unsupported currency {} in SQL rightsizing recommendation {}, assuming USD for conversion.",
                    currencyCode, rec.getName());
            monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE; // Fallback to USD conversion
        }

        String location = extractLocation(rec.getName());

        return new GcpOptimizationRecommendation(
                resourceName,
                currentTier, // Use tier name extracted from description initially
                recommendedTier,
                monthlySavingsInr, // Store INR value
                "Cloud SQL",
                location,
                rec.getName(),
                recommendationType,
                description);
    }

    /**
     * Extract instance name from Cloud SQL recommendation description
     * Pattern: "Instance: basic-mysql has had..." (WITH COLON)
     */
    private String extractInstanceNameFromDescription(String description) {
        if (description == null || description.isEmpty()) {
            log.warn("Description is null or empty, cannot extract instance name.");
            return "Unknown";
        }

        log.debug("Attempting to extract instance name from: {}", description);

        try {
            // Pattern 1: "Instance: [name] has had..."
            Pattern pattern1 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*[a-z0-9])\\s+has\\s+had",
                    Pattern.CASE_INSENSITIVE);
            Matcher matcher1 = pattern1.matcher(description);
            if (matcher1.find()) {
                String instanceName = matcher1.group(1);
                log.debug("✓ Extracted instance name '{}' (pattern 1)", instanceName);
                return instanceName;
            }

            // Pattern 2: "Instance: [name] may..."
            Pattern pattern2 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*[a-z0-9])\\s+may",
                    Pattern.CASE_INSENSITIVE);
            Matcher matcher2 = pattern2.matcher(description);
            if (matcher2.find()) {
                String instanceName = matcher2.group(1);
                log.debug("✓ Extracted instance name '{}' (pattern 2)", instanceName);
                return instanceName;
            }

            // Pattern 3: "Instance [name] has had..." (without colon)
            Pattern pattern3 = Pattern.compile("Instance\\s+([a-z0-9][-a-z0-9]*[a-z0-9])\\s+has\\s+had",
                    Pattern.CASE_INSENSITIVE);
            Matcher matcher3 = pattern3.matcher(description);
            if (matcher3.find()) {
                String instanceName = matcher3.group(1);
                log.debug("✓ Extracted instance name '{}' (pattern 3)", instanceName);
                return instanceName;
            }

            // Pattern 4: Broadest fallback "Instance: [name]"
            Pattern pattern4 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*)", Pattern.CASE_INSENSITIVE);
            Matcher matcher4 = pattern4.matcher(description);
            if (matcher4.find()) {
                String instanceName = matcher4.group(1);
                // Simple filter for common words
                if (!instanceName.matches("(?i)(has|had|may|the|and|with|this|perform|better)")) {
                    log.debug("✓ Extracted instance name '{}' (pattern 4)", instanceName);
                    return instanceName;
                }
            }

        } catch (Exception e) {
            log.error("ERROR while extracting instance name from description '{}': {}", description, e.getMessage(), e);
        }

        log.warn("✗ FAILED to extract instance name from description: {}", description);
        return "Unknown";
    }

    /**
     * Extract recommended machine type from description
     * Pattern: "...resources: 1 (+0) vCPUs and 3.75 (+3.15) GB memory" ->
     * db-custom-1-3840
     */
    private String extractRecommendedTierFromDescription(String description) {
        if (description == null || description.isEmpty()) {
            return "Unknown";
        }

        try {
            // Updated pattern to be more flexible with surrounding text
            Pattern pattern = Pattern.compile(
                    "(\\d+)(?:\\s*\\([^)]*\\))?\\s*vCPUs?\\s+and\\s+([\\d.]+)(?:\\s*\\([^)]*\\))?\\s*GB(?:\\s+memory)?",
                    Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(description);

            if (matcher.find()) {
                int vcpus = Integer.parseInt(matcher.group(1));
                double memoryGb = Double.parseDouble(matcher.group(2));
                int memoryMb = (int) Math.round(memoryGb * 1024); // Round to nearest MB

                // Check for standard tiers first based on vCPU (common ones)
                if (vcpus == 1 && Math.abs(memoryGb - 3.75) < 0.1)
                    return "db-n1-standard-1";
                if (vcpus == 2 && Math.abs(memoryGb - 7.5) < 0.1)
                    return "db-n1-standard-2";
                if (vcpus == 4 && Math.abs(memoryGb - 15) < 0.1)
                    return "db-n1-standard-4";
                // Add more standard checks if needed

                // If not standard, construct custom tier name
                String tier = String.format("db-custom-%d-%d", vcpus, memoryMb);
                log.info("✓ Constructed recommended tier '{}' from description ({} vCPUs, {} GB)", tier, vcpus,
                        memoryGb);
                return tier;
            }
        } catch (Exception e) {
            log.warn("Failed to parse recommended machine type from description '{}': {}", description, e.getMessage());
        }

        log.warn("✗ Could not extract recommended tier from description: {}", description);
        return "Unknown";
    }

    /**
     * Extract resource name from recommendation using multiple strategies
     * Prioritize structured fields, fall back to Regex parsing description.
     */
    private String extractResourceName(Recommendation rec) {
        var fieldsMap = rec.getContent().getOverview().getFieldsMap();

        // Method 1: Direct resourceName field (HIGHEST PRIORITY)
        if (fieldsMap.containsKey("resourceName")) {
            String resourceName = fieldsMap.get("resourceName").getStringValue();
            if (resourceName != null && !resourceName.isEmpty() && !isUuid(resourceName)) {
                log.debug("Using resourceName from fields: {}", resourceName);
                return resourceName;
            } else {
                log.debug("Skipping empty, null, or UUID resourceName: '{}'", resourceName);
            }
        }

        // Method 2: Resource field (full path)
        if (fieldsMap.containsKey("resource")) {
            String fullPath = fieldsMap.get("resource").getStringValue();
            if (fullPath != null && !fullPath.isEmpty() && fullPath.contains("/")) {
                String extracted = fullPath.substring(fullPath.lastIndexOf('/') + 1);
                if (!isUuid(extracted)) {
                    log.debug("Extracted resource name '{}' from resource path: {}", extracted, fullPath);
                    return extracted;
                } else {
                    log.debug("Skipping UUID '{}' from resource path: {}", extracted, fullPath);
                }
            } else {
                log.debug("Skipping invalid resource path: '{}'", fullPath);
            }
        }

        // Method 3: Parse instance name from description (FALLBACK, especially for
        // Cloud SQL)
        String description = rec.getDescription();
        if (description != null && !description.isEmpty() && description.toLowerCase().contains("instance:")) {
            String extracted = extractInstanceNameFromDescription(description);
            if (!"Unknown".equals(extracted)) {
                log.debug("Extracted resource name '{}' from description as fallback", extracted);
                return extracted;
            }
        }

        log.warn("Could not extract valid resource name from recommendation: {}. Overview Fields: {}", rec.getName(),
                fieldsMap.keySet());
        return "Unknown";
    }

    /**
     * Check if a string looks like a UUID
     */
    private boolean isUuid(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        // Basic UUID pattern check
        return str.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    /**
     * Extract location from recommendation name
     */
    private String extractLocation(String recommendationName) {
        // Format: projects/{project}/locations/{location}/...
        // or billingAccounts/{billing}/locations/{location}/...
        if (recommendationName == null || recommendationName.isEmpty())
            return "global";
        try {
            Pattern pattern = Pattern.compile("/locations/([^/]+)/");
            Matcher matcher = pattern.matcher(recommendationName);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.warn("Failed to extract location from recommendation name '{}': {}", recommendationName,
                    e.getMessage());
        }
        log.debug("Could not extract location from name '{}', defaulting to global.", recommendationName);
        return "global"; // Default if pattern fails or name is invalid
    }

    private GcpOptimizationRecommendation mapToCudRecommendationDto(Recommendation rec) {
        String resourceName = "Committed Use Discount"; // Generic name for CUD recs
        String description = rec.getDescription();

        log.debug("🔍 Full CUD recommendation details: Name={}, Desc={}", rec.getName(), description);

        double monthlySavingsUsd = 0.0;
        String currencyCode = "USD"; // Default

        if (rec.getPrimaryImpact().hasCostProjection() && rec.getPrimaryImpact().getCostProjection().hasCost()) {
            Money costMoney = rec.getPrimaryImpact().getCostProjection().getCost();
            currencyCode = costMoney.getCurrencyCode() != null && !costMoney.getCurrencyCode().isEmpty()
                    ? costMoney.getCurrencyCode()
                    : "USD";

            try {
                double totalCostImpact = costMoney.getUnits() + (costMoney.getNanos() / 1_000_000_000.0);
                if (totalCostImpact < 0) {
                    monthlySavingsUsd = Math.abs(totalCostImpact); // Savings = negative impact
                }
                log.debug("Raw CUD savings: {} {}", String.format("%.2f", monthlySavingsUsd), currencyCode);
            } catch (Exception e) {
                log.error("Failed to parse CUD cost for {}: {}", rec.getName(), e.getMessage());
            }
        } else {
            log.warn("CUD recommendation {} has no cost projection.", rec.getName());
        }

        // Convert to INR
        double monthlySavingsInr = monthlySavingsUsd;
        if ("USD".equalsIgnoreCase(currencyCode)) {
            monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE;
            log.info("💱 Converted CUD savings from USD to INR: ${} → ₹{}", String.format("%.2f", monthlySavingsUsd),
                    String.format("%.2f", monthlySavingsInr));
        } else if (!"INR".equalsIgnoreCase(currencyCode)) {
            log.warn("Unsupported currency {} in CUD recommendation {}, assuming USD for conversion.", currencyCode,
                    rec.getName());
            monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE; // Fallback to USD conversion
        }

        log.info("💰 Final CUD savings: ₹{} (Original Currency: {})", String.format("%.2f", monthlySavingsInr),
                currencyCode);

        // Extract commitment details from description
        String commitmentType = "Unknown Commitment"; // Default
        if (description != null) {
            // Try to extract term and resource type
            String term = "Unknown";
            if (description.contains("3 year"))
                term = "3-year";
            else if (description.contains("1 year"))
                term = "1-year";

            // More robust resource type extraction
            Pattern resourcePattern = Pattern.compile("for\\s+([\\w\\s-]+(?:\\s+\\(.+\\))?)", Pattern.CASE_INSENSITIVE);
            Matcher resourceMatcher = resourcePattern.matcher(description);
            String resourceDetails = "";
            if (resourceMatcher.find()) {
                resourceDetails = resourceMatcher.group(1).trim();
                // Clean up potential extra words if needed, e.g., "new resource-based CUD"
                resourceDetails = resourceDetails.replace("new resource-based CUD for ", "");
            }

            if (!"Unknown".equals(term) && !resourceDetails.isEmpty()) {
                commitmentType = term + " " + resourceDetails;
            } else if (!resourceDetails.isEmpty()) {
                commitmentType = resourceDetails; // Use resource if term not found
            } else {
                commitmentType = description; // Fallback to full description
            }
        }

        String location = extractLocation(rec.getName());

        return new GcpOptimizationRecommendation(
                resourceName, // Use the generic name
                commitmentType, // Current type/term being recommended
                "Purchase Commitment", // Recommended action
                monthlySavingsInr, // INR savings
                "Commitment", // Service category
                location,
                rec.getName(),
                "COST_SAVINGS",
                description // Full original description as reason
        );
    }

    /**
     * Extracts commitment amount (e.g., "1 GB") if present in the description.
     * 
     * @deprecated More robust parsing integrated into mapToCudRecommendationDto.
     */
    @Deprecated
    private String extractCommitmentAmountFromDescription(String description) {
        // Kept for reference
        return "N/A";
    }

    /**
     * HELPER: Extract location from recommendation name.
     * 
     * @deprecated Replaced by the general `extractLocation` method.
     */
    @Deprecated
    private String extractLocationFromRecommendationName(String recommendationName) {
        return extractLocation(recommendationName);
    }

    /**
     * Enriches a recommendation with actual utilization metrics from Cloud Monitoring.
     * instanceNameToId: Compute Engine name → numeric instance ID (needed for Cloud Monitoring filter).
     */
    private void enrichWithUtilizationMetrics(String gcpProjectId,
            GcpOptimizationRecommendation recommendation,
            Map<String, String> instanceNameToId) {
        try {
            String resourceName = recommendation.getResourceName();
            String service = recommendation.getService();

            if ("Cloud SQL".equals(service)) {
                Double avgCpu    = gcpMetricsService.getCloudSqlAverageCpuUtilization(gcpProjectId, resourceName, LOOKBACK_DAYS);
                Double maxCpu    = gcpMetricsService.getCloudSqlMaxCpuUtilization(gcpProjectId, resourceName, LOOKBACK_DAYS);
                Double avgMemory = gcpMetricsService.getCloudSqlAverageMemoryUtilization(gcpProjectId, resourceName, LOOKBACK_DAYS);
                Double maxMemory = gcpMetricsService.getCloudSqlMaxMemoryUtilization(gcpProjectId, resourceName, LOOKBACK_DAYS);

                recommendation.setAverageCpuUtilization(avgCpu);
                recommendation.setMaxCpuUtilization(maxCpu);
                recommendation.setAverageMemoryUtilization(avgMemory);
                recommendation.setMaxMemoryUtilization(maxMemory);
                recommendation.setUtilizationBasedReason(buildUtilizationReason(avgCpu, maxCpu, avgMemory, maxMemory));

            } else if ("Compute Engine".equals(service)) {
                // Gap 2: Use name→ID map to resolve the numeric instance ID needed for Cloud Monitoring
                String instanceId = instanceNameToId.get(resourceName);
                if (instanceId == null) {
                    log.debug("[GcpRightsizing] No numeric ID for CE instance '{}' in name→ID map — skipping enrichment", resourceName);
                    return;
                }
                Double avgCpu    = gcpMetricsService.getAverageCpuUtilization(gcpProjectId, instanceId, LOOKBACK_DAYS);
                Double maxCpu    = gcpMetricsService.getMaxCpuUtilization(gcpProjectId, instanceId, LOOKBACK_DAYS);
                // Memory requires Ops Agent; returns null gracefully if agent not installed
                Double avgMemory = gcpMetricsService.getAverageMemoryUtilization(gcpProjectId, instanceId, LOOKBACK_DAYS);

                recommendation.setAverageCpuUtilization(avgCpu);
                recommendation.setMaxCpuUtilization(maxCpu);
                recommendation.setAverageMemoryUtilization(avgMemory);
                recommendation.setUtilizationBasedReason(buildUtilizationReason(avgCpu, maxCpu, avgMemory, null));
                log.info("[GcpRightsizing] CE enrichment for '{}' (id={}): avg CPU={}% max CPU={}% avg mem={}%",
                        resourceName, instanceId, avgCpu, maxCpu, avgMemory);
            }
        } catch (Exception e) {
            log.error("[GcpRightsizing] Failed to enrich recommendation for resource {}: {}",
                    recommendation.getResourceName(), e.getMessage());
        }
    }

    /**
     * Applies GCE rightsizing: stop → modify machine type → start.
     * Validates read-write access before proceeding.
     * Runs synchronously in the calling thread — caller should wrap in @Async.
     */
    public void applyRightsizing(String gcpProjectId, String zone, String instanceName, String targetMachineType) {
        CloudAccount account = cloudAccountService.findByGcpProjectId(gcpProjectId);
        if (account == null) {
            throw new IllegalArgumentException("Account not found for GCP project: " + gcpProjectId);
        }
        if (!"read-write".equals(account.getAccessType())) {
            throw new SecurityException("Account " + gcpProjectId + " does not have read-write access. Rightsizing is not permitted.");
        }

        log.info("[GcpRightsizing] Apply START — project={} zone={} instance={} target={}",
                gcpProjectId, zone, instanceName, targetMachineType);

        Optional<com.google.cloud.compute.v1.InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            throw new RuntimeException("Could not obtain InstancesClient for project: " + gcpProjectId);
        }

        try (com.google.cloud.compute.v1.InstancesClient instancesClient = clientOpt.get()) {

            // Step 1: Stop — OperationFuture.get() polls until DONE
            log.info("[GcpRightsizing] Stopping instance {} in zone {}", instanceName, zone);
            instancesClient.stopAsync(gcpProjectId, zone, instanceName).get(10, TimeUnit.MINUTES);

            // Step 2: Modify machine type
            log.info("[GcpRightsizing] Resizing {} to {}", instanceName, targetMachineType);
            String machineTypeUrl = String.format("zones/%s/machineTypes/%s", zone, targetMachineType);
            instancesClient.setMachineTypeAsync(
                    gcpProjectId, zone, instanceName,
                    com.google.cloud.compute.v1.InstancesSetMachineTypeRequest.newBuilder()
                            .setMachineType(machineTypeUrl).build()
            ).get(5, TimeUnit.MINUTES);

            // Step 3: Start
            log.info("[GcpRightsizing] Starting instance {} with new type {}", instanceName, targetMachineType);
            instancesClient.startAsync(gcpProjectId, zone, instanceName).get(5, TimeUnit.MINUTES);

            log.info("[GcpRightsizing] Apply SUCCESS — instance {} is now starting as {} in zone {}",
                    instanceName, targetMachineType, zone);
        } catch (SecurityException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GcpRightsizing] Apply FAILED for instance {} in project {}: {}", instanceName, gcpProjectId, e.getMessage(), e);
            throw new RuntimeException("Failed to apply GCE rightsizing: " + e.getMessage(), e);
        }
    }

    /**
     * Builds a detailed utilization-based reason text
     */
    private String buildUtilizationReason(Double avgCpu, Double maxCpu, Double avgMemory, Double maxMemory) {
        StringBuilder reason = new StringBuilder();
        reason.append("Performance metrics (last 14 days): ");

        if (avgCpu != null && maxCpu != null) {
            reason.append(String.format("CPU: avg %.1f%%, max %.1f%%", avgCpu, maxCpu));
        } else {
            reason.append("CPU: metrics unavailable");
        }

        if (avgMemory != null && maxMemory != null) {
            reason.append(String.format(" | Memory: avg %.1f%%, max %.1f%%", avgMemory, maxMemory));
        } else {
            reason.append(" | Memory: metrics unavailable");
        }

        return reason.toString();
    }

    /**
     * Validates if a recommendation should be shown based on actual utilization.
     * Returns true if the recommendation is valid, false otherwise.
     */
    private boolean isRecommendationValid(GcpOptimizationRecommendation recommendation) {
        String recommendationType = recommendation.getRecommendationType();
        Double avgCpu = recommendation.getAverageCpuUtilization();
        Double maxCpu = recommendation.getMaxCpuUtilization();
        Double avgMemory = recommendation.getAverageMemoryUtilization();
        Double maxMemory = recommendation.getMaxMemoryUtilization();

        // If we don't have utilization data, follow GCP's recommendation for
        // COST_SAVINGS
        // but reject PERFORMANCE_IMPROVEMENT to avoid "illogical" expensive upgrades
        // without evidence.
        if (avgCpu == null && avgMemory == null) {
            if ("PERFORMANCE_IMPROVEMENT".equals(recommendationType)) {
                log.info(
                        "Filtering out upgrade recommendation for {}: No utilization metrics available to justify high cost performance improvement",
                        recommendation.getResourceName());
                return false;
            }
            log.debug("No utilization metrics available for {}. Allowing recommendation (benefit of the doubt).",
                    recommendation.getResourceName());
            return true;
        }

        if ("COST_SAVINGS".equals(recommendationType)) {
            // Downgrade recommendation - validate that resource is actually underutilized
            if (avgCpu != null && avgCpu > LOW_CPU_THRESHOLD) {
                log.info(
                        "Filtering out downgrade recommendation for {}: CPU utilization ({:.1f}%) is above threshold ({:.1f}%)",
                        recommendation.getResourceName(), avgCpu, LOW_CPU_THRESHOLD);
                return false;
            }
            if (avgMemory != null && avgMemory > LOW_MEMORY_THRESHOLD) {
                log.info(
                        "Filtering out downgrade recommendation for {}: Memory utilization ({:.1f}%) is above threshold ({:.1f}%)",
                        recommendation.getResourceName(), avgMemory, LOW_MEMORY_THRESHOLD);
                return false;
            }
            // Valid downgrade recommendation
            return true;

        } else if ("PERFORMANCE_IMPROVEMENT".equals(recommendationType)) {
            // Upgrade recommendation - validate that resource is actually overutilized
            boolean cpuOverutilized = avgCpu != null && avgCpu > HIGH_CPU_THRESHOLD;
            boolean memoryOverutilized = avgMemory != null && avgMemory > HIGH_MEMORY_THRESHOLD;

            if (!cpuOverutilized && !memoryOverutilized) {
                log.info(
                        "Filtering out upgrade recommendation for {}: CPU ({:.1f}%) and Memory ({:.1f}%) are below thresholds",
                        recommendation.getResourceName(),
                        avgCpu != null ? avgCpu : 0.0,
                        avgMemory != null ? avgMemory : 0.0);
                return false;
            }
            // Valid upgrade recommendation
            return true;
        }

        // For other types (OPTIMIZATION, etc.), allow by default
        return true;
    }

    /**
     * Filters and validates a list of recommendations based on utilization metrics.
     * instanceNameToId: Compute Engine instance name → numeric ID for Cloud Monitoring lookups.
     */
    private List<GcpOptimizationRecommendation> filterAndValidateRecommendations(
            String gcpProjectId,
            List<GcpOptimizationRecommendation> recommendations,
            Map<String, String> instanceNameToId) {

        log.info("[GcpRightsizing] Validating {} recommendations with utilization metrics for project {}",
                recommendations.size(), gcpProjectId);

        List<GcpOptimizationRecommendation> validRecommendations = new ArrayList<>();

        for (GcpOptimizationRecommendation recommendation : recommendations) {
            enrichWithUtilizationMetrics(gcpProjectId, recommendation, instanceNameToId);
            if (isRecommendationValid(recommendation)) {
                validRecommendations.add(recommendation);
            } else {
                log.info("[GcpRightsizing] Filtered out recommendation for resource: {} (utilization mismatch)",
                        recommendation.getResourceName());
            }
        }

        log.info("[GcpRightsizing] {} valid out of {} total recommendations for project {}",
                validRecommendations.size(), recommendations.size(), gcpProjectId);
        return validRecommendations;
    }

    /** Calculates estimated monthly disk cost in INR */
    private double calculateDiskCost(long sizeGb) {
        // Standard PD cost: $0.10 per GB/month (us-central1, estimate)
        double costUsd = sizeGb * EST_PD_COST_USD_PER_GB;
        return costUsd * USD_TO_INR_RATE;
    }

    private double calculateDiskCostUsd(long sizeGb) {
        return sizeGb * EST_PD_COST_USD_PER_GB;
    }

    /**
     * Attempts to extract the current tier name from the recommendation description
     */
    private String extractCurrentTierFromDescription(String description) {
        if (description == null || description.isEmpty()) {
            return "Unknown";
        }
        try {
            // Pattern: "Instance: [name] uses the [tier-name] machine type"
            Pattern pattern = Pattern.compile("uses the ([a-z0-9-]+) machine type", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(description);
            if (matcher.find()) {
                String tier = matcher.group(1);
                log.debug("✅ Extracted current tier '{}' from description", tier);
                return tier;
            }
        } catch (Exception e) {
            log.error("❌ Error extracting current tier from description '{}': {}", description, e.getMessage());
        }
        log.warn("⚠️ Could not extract current tier from description using pattern: {}", description);
        return "Unknown";
    }

    /**
     * Enhances Cloud SQL recommendations with historical cost (INR) and formatted
     * descriptions.
     * Calculates fallback savings/cost increase (INR) if API data is missing.
     */
    private void enhanceCloudSqlRecommendationsWithHistoricalCost(
            String gcpProjectId,
            List<GcpOptimizationRecommendation> results) {

        Optional<SQLAdmin> clientOpt = gcpClientProvider.getSqlAdminClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.warn("SQLAdmin client not available for project {}. Skipping Cloud SQL enhancement.", gcpProjectId);
            return;
        }

        SQLAdmin sqlAdmin = clientOpt.get();

        for (GcpOptimizationRecommendation dto : results) {
            // Skip non-Cloud SQL or if already processed/failed previously
            if (!"Cloud SQL".equals(dto.getService()) || dto.getResourceName() == null)
                continue;

            String instanceName = dto.getResourceName();
            if ("Unknown".equals(instanceName) || isUuid(instanceName)) {
                log.warn("Skipping enhancement for invalid/UUID resource name: {}", instanceName);
                continue;
            }

            // Extract raw recommended tier name before it gets formatted
            String rawRecommendedTier = dto.getRecommendedMachineType();

            try {
                log.info("🔍 Enhancing Cloud SQL recommendation for instance: {}", instanceName);
                DatabaseInstance instance = sqlAdmin.instances()
                        .get(gcpProjectId, instanceName)
                        .execute();

                String actualCurrentTier = instance.getSettings().getTier();
                String region = instance.getRegion();
                dto.setLocation(region); // Update location from instance data

                // Correct current tier if extraction failed or seems wrong
                if ("Unknown".equals(dto.getCurrentMachineType()) || !dto.getCurrentMachineType().startsWith("db-")) {
                    log.info("Updating current tier for '{}' from '{}' (extracted) to '{}' (actual)",
                            instanceName, dto.getCurrentMachineType(), actualCurrentTier);
                    dto.setCurrentMachineType(actualCurrentTier);
                } else if (!dto.getCurrentMachineType().equals(actualCurrentTier)) {
                    log.warn(
                            "Mismatch between extracted tier ('{}') and actual tier ('{}') for instance {}. Using actual tier.",
                            dto.getCurrentMachineType(), actualCurrentTier, instanceName);
                    dto.setCurrentMachineType(actualCurrentTier);
                }

                // Calculate historical 30-day cost (INR)
                double historicalCostInr = calculateCloudSqlHistoricalCost(gcpProjectId, instanceName);
                if (historicalCostInr >= 0) { // Allow 0 cost, but not negative error codes
                    dto.setLast30DayCost(historicalCostInr);
                    log.info("💰 Instance '{}': Historical 30-day cost = ₹{}",
                            instanceName, String.format("%.2f", historicalCostInr));
                } else {
                    log.warn("Could not retrieve valid historical cost for instance '{}'. last30DayCost set to null.",
                            instanceName);
                    dto.setLast30DayCost(null);
                }

                // Calculate estimated monthly prices in USD using Catalog/Fallback
                double currentPriceUsd = calculateCloudSqlPrice(gcpProjectId, actualCurrentTier, region);
                double recommendedPriceUsd = ("Unknown".equals(rawRecommendedTier) || rawRecommendedTier == null)
                        ? 0.0
                        : calculateCloudSqlPrice(gcpProjectId, rawRecommendedTier, region);

                // Fallback savings/cost calculation (INR)
                // If Recommender API gave no savings (INR value is 0.0), calculate from prices.
                if (dto.getMonthlySavings() == 0.0 && currentPriceUsd > 0 && recommendedPriceUsd > 0) {
                    log.info(
                            "Recommender API provided 0 savings for '{}', calculating fallback based on price estimates.",
                            instanceName);
                    if ("COST_SAVINGS".equals(dto.getRecommendationType())) {
                        double calculatedSavingsUsd = currentPriceUsd - recommendedPriceUsd;
                        if (calculatedSavingsUsd > 0) {
                            double calculatedSavingsInr = calculatedSavingsUsd * USD_TO_INR_RATE;
                            dto.setMonthlySavings(calculatedSavingsInr);
                            log.info("Populated 'monthlySavings' from price diff: ₹{} ({} -> {})",
                                    String.format("%.2f", calculatedSavingsInr), actualCurrentTier, rawRecommendedTier);
                        } else {
                            log.warn(
                                    "Fallback price difference for COST_SAVINGS is not positive (Current: ${}, Rec: ${}). Keeping savings as 0.",
                                    String.format("%.2f", currentPriceUsd), String.format("%.2f", recommendedPriceUsd));
                        }
                    } else if ("PERFORMANCE_IMPROVEMENT".equals(dto.getRecommendationType())) {
                        double calculatedCostIncreaseUsd = recommendedPriceUsd - currentPriceUsd;
                        if (calculatedCostIncreaseUsd > 0) {
                            double calculatedCostIncreaseInr = calculatedCostIncreaseUsd * USD_TO_INR_RATE;
                            dto.setMonthlySavings(-calculatedCostIncreaseInr); // Store as negative INR
                            log.info("Populated 'monthlySavings' (cost increase) from price diff: ₹{} ({} -> {})",
                                    String.format("%.2f", -calculatedCostIncreaseInr), actualCurrentTier,
                                    rawRecommendedTier);
                        } else {
                            log.warn(
                                    "Fallback price difference for PERFORMANCE_IMPROVEMENT is not positive (Current: ${}, Rec: ${}). Keeping cost increase as 0.",
                                    String.format("%.2f", currentPriceUsd), String.format("%.2f", recommendedPriceUsd));
                        }
                    }
                } else if (dto.getMonthlySavings() != 0.0) {
                    log.info("Using monthly savings/cost value derived from Recommender API for '{}': ₹{}",
                            instanceName, String.format("%.2f", dto.getMonthlySavings()));
                }

                // Build readable descriptions using calculated INR prices
                double currentPriceInr = currentPriceUsd * USD_TO_INR_RATE;
                double recommendedPriceInr = recommendedPriceUsd * USD_TO_INR_RATE;
                dto.setCurrentMachineType(buildReadableTierDescription(actualCurrentTier, currentPriceInr)); // Pass INR
                dto.setRecommendedMachineType(buildReadableTierDescription(rawRecommendedTier, recommendedPriceInr)); // Pass
                                                                                                                      // INR

                log.info("✅ Enhanced recommendation for '{}': Savings/Cost: ₹{}/mo, Last 30d Cost: ₹{}/mo",
                        instanceName,
                        String.format("%.2f", dto.getMonthlySavings()),
                        dto.getLast30DayCost() != null ? String.format("%.2f", dto.getLast30DayCost()) : "N/A");

            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                if (e.getStatusCode() == 404) {
                    log.error("Cloud SQL instance '{}' not found in project {}. It may have been deleted.",
                            instanceName, gcpProjectId);
                } else if (e.getStatusCode() == 403) {
                    log.error(
                            "Permission denied enhancing Cloud SQL instance '{}' in project {}. Check SQL Admin API permissions.",
                            instanceName, gcpProjectId);
                } else {
                    log.error("API error enhancing Cloud SQL instance '{}' in project {}: {} - {}",
                            instanceName, gcpProjectId, e.getStatusCode(), e.getMessage());
                }
                // Clear potentially misleading data on error
                dto.setLast30DayCost(null);
                dto.setCurrentMachineType(buildReadableTierDescription(dto.getCurrentMachineType(), 0.0)); // Use raw
                                                                                                           // tier,
                                                                                                           // clear
                                                                                                           // price
                dto.setRecommendedMachineType(buildReadableTierDescription(rawRecommendedTier, 0.0)); // Use raw tier,
                                                                                                      // clear price
            } catch (Exception e) {
                log.error("❌ Unexpected error enhancing instance '{}' in project {}: {}", instanceName, gcpProjectId,
                        e.getMessage(), e);
                // Clear potentially misleading data on error
                dto.setLast30DayCost(null);
                dto.setCurrentMachineType(buildReadableTierDescription(dto.getCurrentMachineType(), 0.0));
                dto.setRecommendedMachineType(buildReadableTierDescription(rawRecommendedTier, 0.0));
            }
        }
    }

    /**
     * Calculate historical 30-day cost *for a specific instance* (in INR)
     * from BigQuery billing export. Returns -1.0 on error.
     */
    private double calculateCloudSqlHistoricalCost(String gcpProjectId, String instanceName) {
        try {
            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty()) {
                log.warn("BigQuery client not available for project {}", gcpProjectId);
                return -1.0; // Indicate error
            }

            BigQuery bigquery = bqOpt.get();
            Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);

            if (tableNameOpt.isEmpty()) {
                log.warn("Billing table not found for project {}. Cannot calculate historical cost.", gcpProjectId);
                return -1.0; // Indicate error
            }

            LocalDate today = LocalDate.now();
            LocalDate startDate = today.minusDays(30);

            // Use the label key for Cloud SQL instance name
            String sqlInstanceLabelKey = "cloudsql.googleapis.com/instance_name";

            String query = String.format(
                    "SELECT SUM(cost) as total_cost, currency " + // Select currency as well
                            "FROM `%s` " +
                            "WHERE DATE(_PARTITIONTIME) >= '%s' " + // Use _PARTITIONTIME if table is partitioned
                            // "WHERE DATE(usage_start_time) >= '%s' " + // Use usage_start_time if not
                            // partitioned
                            "  AND DATE(_PARTITIONTIME) < '%s' " +
                            // " AND DATE(usage_start_time) <= '%s' " +
                            "  AND service.description = 'Cloud SQL' " +
                            "  AND project.id = '%s' " +
                            // Query 'labels' array (UNNEST)
                            "  AND EXISTS (SELECT 1 FROM UNNEST(labels) AS l WHERE l.key = '%s' AND l.value = '%s') " +
                            "  AND cost > 0 " +
                            "GROUP BY currency", // Group by currency
                    tableNameOpt.get(),
                    startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE), // Partition date is exclusive upper
                                                                                // bound
                    // today.format(DateTimeFormatter.ISO_LOCAL_DATE), // Use for usage_start_time
                    gcpProjectId,
                    sqlInstanceLabelKey,
                    instanceName);

            log.info("📊 Querying 30-day cost for Cloud SQL instance: {}", instanceName);
            log.debug("BigQuery SQL: {}", query);

            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());

            double totalCostInr = 0.0;
            boolean dataFound = false;

            if (results.getTotalRows() == 0) {
                log.warn("⚠️ No 30-day Cloud SQL cost data found via BigQuery for instance: {} in project {}",
                        instanceName, gcpProjectId);
                return 0.0; // No data is not an error, just zero cost found
            }

            for (FieldValueList row : results.iterateAll()) {
                dataFound = true;
                FieldValue costField = row.get("total_cost");
                FieldValue currencyField = row.get("currency");

                if (!costField.isNull() && !currencyField.isNull()) {
                    double costValue = costField.getDoubleValue();
                    String currency = currencyField.getStringValue();

                    if ("USD".equalsIgnoreCase(currency)) {
                        totalCostInr += costValue * USD_TO_INR_RATE;
                        log.info("  Cost contribution (USD converted to INR): ${} -> ₹{}",
                                String.format("%.2f", costValue), String.format("%.2f", costValue * USD_TO_INR_RATE));
                    } else if ("INR".equalsIgnoreCase(currency)) {
                        totalCostInr += costValue;
                        log.info("  Cost contribution (INR): ₹{}", String.format("%.2f", costValue));
                    } else {
                        log.warn(
                                "  Unsupported currency '{}' found in billing data for instance {}. Cost: {}. Treating as USD for conversion.",
                                currency, instanceName, costValue);
                        totalCostInr += costValue * USD_TO_INR_RATE; // Fallback: Assume USD if unknown
                    }
                }
            }

            if (dataFound) {
                log.info("✅ Total Cloud SQL 30-day cost for '{}': ₹{}",
                        instanceName, String.format("%.2f", totalCostInr));
                return totalCostInr;
            } else {
                // Should be caught by getTotalRows == 0, but as safety
                log.warn("⚠️ No cost rows processed for instance '{}', returning 0.0 cost.", instanceName);
                return 0.0;
            }

        } catch (BigQueryException bqEx) {
            log.error("❌ BigQuery Error querying Cloud SQL cost for '{}' in project {}: {} - {}",
                    instanceName, gcpProjectId, bqEx.getCode(), bqEx.getMessage());
            if (bqEx.getError() != null) {
                log.error("   Reason: {}, Location: {}", bqEx.getError().getReason(), bqEx.getError().getLocation());
            }
            return -1.0; // Indicate error
        } catch (Exception e) {
            log.error("❌ Unexpected Error querying Cloud SQL cost for '{}' in project {}: {}", instanceName,
                    gcpProjectId, e.getMessage(), e);
            return -1.0; // Indicate error
        }
    }

    /** Helper to find the BigQuery billing export table name */
    private Optional<String> getBillingTableName(BigQuery bigquery, String gcpProjectId) {
        // Basic Caching could be added here
        try {
            // Prefer searching in the specific project first
            for (Dataset dataset : bigquery.listDatasets(gcpProjectId).iterateAll()) {
                for (Table table : bigquery.listTables(dataset.getDatasetId()).iterateAll()) {
                    // Match standard v1 export pattern
                    if (table.getTableId().getTable().startsWith("gcp_billing_export_v1_")) {
                        String fullName = String.format("%s.%s.%s",
                                table.getTableId().getProject(),
                                table.getTableId().getDataset(),
                                table.getTableId().getTable());
                        log.info("Found billing table in project {}: {}", gcpProjectId, fullName);
                        return Optional.of(fullName);
                    }
                }
            }

            // Fallback: If not found, log warning. A more robust solution might check a
            // central billing project ID if configured.
            log.warn(
                    "Could not find billing table matching 'gcp_billing_export_v1_...' directly in project {}. Ensure billing export is set up correctly.",
                    gcpProjectId);

        } catch (BigQueryException bqEx) {
            log.error("BigQuery API Error finding billing table in project {}: {} - {}", gcpProjectId, bqEx.getCode(),
                    bqEx.getMessage());
            if (bqEx.getError() != null) {
                log.error("   Reason: {}, Location: {}", bqEx.getError().getReason(), bqEx.getError().getLocation());
            }
        } catch (Exception e) {
            log.error("Failed to find billing table in project {}: {}", gcpProjectId, e.getMessage());
        }
        return Optional.empty(); // Return empty if not found or error occurred
    }

    // ── Tier-1: Compute — same client jar, new detectors ──────────────────────

    // Managed instance groups with targetSize == 0 — paying for the group config, no VMs.
    private CompletableFuture<List<GcpWasteItem>> findEmptyInstanceGroups(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<InstanceGroupManagersClient> clientOpt =
                    gcpClientProvider.getInstanceGroupManagersClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (InstanceGroupManagersClient client = clientOpt.get()) {
                List<GcpWasteItem> items = new ArrayList<>();
                for (var entry : client.aggregatedList(gcpProjectId).iterateAll()) {
                    for (var igm : entry.getValue().getInstanceGroupManagersList()) {
                        if (igm.getTargetSize() == 0) {
                            String zoneUrl = igm.getZone();
                            String zone = zoneUrl.contains("/") ? zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1) : zoneUrl;
                            items.add(new GcpWasteItem(igm.getName(), igm.getName(),
                                    "Empty Instance Group", zone, 0.0,
                                    "Managed instance group has 0 target instances"));
                        }
                    }
                }
                return items;
            } catch (Exception e) {
                log.warn("[GcpWaste] findEmptyInstanceGroups failed for {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    // SSL certificates not attached to any forwarding rule — paying for cert storage.
    private CompletableFuture<List<GcpWasteItem>> findUnusedSslCertificates(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<SslCertificatesClient> certOpt = gcpClientProvider.getSslCertificatesClient(gcpProjectId);
            Optional<TargetHttpsProxiesClient> proxyOpt = gcpClientProvider.getTargetHttpsProxiesClient(gcpProjectId);
            if (certOpt.isEmpty()) return List.of();
            try (SslCertificatesClient certClient = certOpt.get()) {
                Set<String> usedCertSelfLinks = new HashSet<>();
                if (proxyOpt.isPresent()) {
                    try (TargetHttpsProxiesClient proxyClient = proxyOpt.get()) {
                        StreamSupport.stream(proxyClient.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                                .flatMap(e -> e.getValue().getTargetHttpsProxiesList().stream())
                                .forEach(proxy -> usedCertSelfLinks.addAll(proxy.getSslCertificatesList()));
                    } catch (Exception e) {
                        log.warn("[GcpWaste] Could not list TargetHttpsProxies: {}", e.getMessage());
                    }
                }
                List<GcpWasteItem> items = new ArrayList<>();
                StreamSupport.stream(certClient.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(e -> e.getValue().getSslCertificatesList().stream())
                        .filter(cert -> !usedCertSelfLinks.contains(cert.getSelfLink()))
                        .forEach(cert -> items.add(new GcpWasteItem(
                                cert.getName(), cert.getName(),
                                "Unused SSL Certificate", "global", 0.0,
                                "Certificate is not attached to any HTTPS proxy")));
                return items;
            } catch (Exception e) {
                log.warn("[GcpWaste] findUnusedSslCertificates failed for {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    // VPN tunnels not in ESTABLISHED state — still billed at ~$36/mo per tunnel.
    private CompletableFuture<List<GcpWasteItem>> findDownVpnTunnels(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<VpnTunnelsClient> clientOpt = gcpClientProvider.getVpnTunnelsClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (VpnTunnelsClient client = clientOpt.get()) {
                List<GcpWasteItem> items = new ArrayList<>();
                StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(e -> e.getValue().getVpnTunnelsList().stream())
                        .filter(t -> !"ESTABLISHED".equalsIgnoreCase(t.getStatus()))
                        .forEach(t -> {
                            String regionUrl = t.getRegion();
                            String region = regionUrl.contains("/") ? regionUrl.substring(regionUrl.lastIndexOf('/') + 1) : regionUrl;
                            items.add(new GcpWasteItem(t.getName(), t.getName(),
                                    "Down VPN Tunnel", region, 36.0,
                                    "VPN tunnel status is " + t.getStatus() + " (not ESTABLISHED)"));
                        });
                return items;
            } catch (Exception e) {
                log.warn("[GcpWaste] findDownVpnTunnels failed for {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    // Target pools with no instances — load balancer config with nothing to route to.
    private CompletableFuture<List<GcpWasteItem>> findEmptyTargetPools(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<TargetPoolsClient> clientOpt = gcpClientProvider.getTargetPoolsClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (TargetPoolsClient client = clientOpt.get()) {
                List<GcpWasteItem> items = new ArrayList<>();
                StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(e -> e.getValue().getTargetPoolsList().stream())
                        .filter(tp -> tp.getInstancesList().isEmpty())
                        .forEach(tp -> {
                            String regionUrl = tp.getRegion();
                            String region = regionUrl.contains("/") ? regionUrl.substring(regionUrl.lastIndexOf('/') + 1) : regionUrl;
                            items.add(new GcpWasteItem(tp.getName(), tp.getName(),
                                    "Empty Target Pool", region, 0.0,
                                    "Target pool has no backend instances"));
                        });
                return items;
            } catch (Exception e) {
                log.warn("[GcpWaste] findEmptyTargetPools failed for {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    // ── Tier-2: Slow group ────────────────────────────────────────────────────

    // Cloud Functions with 0 invocations in 30 days — deployed and forgotten.
    private CompletableFuture<List<GcpWasteItem>> findFunctionsWithNoInvocations(
            String gcpProjectId, List<String> locations) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.cloud.functions.v2.FunctionServiceClient> clientOpt =
                    gcpClientProvider.getFunctionServiceClient(gcpProjectId);
            Optional<MetricServiceClient> metricsOpt =
                    gcpClientProvider.getMetricServiceClient(gcpProjectId);
            if (clientOpt.isEmpty() || metricsOpt.isEmpty()) return List.of();

            List<GcpWasteItem> items = new ArrayList<>();
            try (com.google.cloud.functions.v2.FunctionServiceClient fnClient = clientOpt.get();
                 MetricServiceClient metricsClient = metricsOpt.get()) {

                com.google.protobuf.Timestamp end = com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond()).build();
                com.google.protobuf.Timestamp start = com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(Instant.now().minus(30, ChronoUnit.DAYS).getEpochSecond()).build();

                for (String location : locations) {
                    String parent = "projects/" + gcpProjectId + "/locations/" + location;
                    try {
                        for (var fn : fnClient.listFunctions(parent).iterateAll()) {
                            String fnName = fn.getName().substring(fn.getName().lastIndexOf('/') + 1);
                            String filter = "metric.type=\"cloudfunctions.googleapis.com/function/call_count\" " +
                                    "AND resource.labels.function_name=\"" + fnName + "\"";
                            var req = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                                    .setName("projects/" + gcpProjectId)
                                    .setFilter(filter)
                                    .setInterval(com.google.monitoring.v3.TimeInterval.newBuilder()
                                            .setStartTime(start).setEndTime(end).build())
                                    .setView(com.google.monitoring.v3.ListTimeSeriesRequest.TimeSeriesView.HEADERS)
                                    .build();
                            boolean hasData = metricsClient.listTimeSeries(req).iterateAll().iterator().hasNext();
                            if (!hasData) {
                                items.add(new GcpWasteItem(fn.getName(), fnName,
                                        "Cloud Function (0 invocations)", location, 0.0,
                                        "No invocations in 30 days"));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[GcpWaste] Functions scan failed in {}/{}: {}", gcpProjectId, location, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findFunctionsWithNoInvocations failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }

    // GKE clusters with no non-system node pools running — cluster cost without workloads.
    private CompletableFuture<List<GcpWasteItem>> findIdleGkeClusters(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<ClusterManagerClient> clientOpt = gcpClientProvider.getClusterManagerClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (ClusterManagerClient client = clientOpt.get()) {
                List<GcpWasteItem> items = new ArrayList<>();
                String parent = "projects/" + gcpProjectId + "/locations/-";
                for (var cluster : client.listClusters(parent).getClustersList()) {
                    int totalNodes = cluster.getNodePoolsList().stream()
                            .mapToInt(np -> np.getInitialNodeCount())
                            .sum();
                    if (totalNodes == 0) {
                        items.add(new GcpWasteItem(cluster.getName(), cluster.getName(),
                                "Idle GKE Cluster", cluster.getLocation(), 0.0,
                                "Cluster has 0 nodes across all node pools"));
                    }
                }
                return items;
            } catch (Exception e) {
                log.warn("[GcpWaste] findIdleGkeClusters failed for {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    // BigQuery datasets with no tables — paying for reservation with no data.
    private CompletableFuture<List<GcpWasteItem>> findBigQueryDatasetsNotQueried(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<BigQuery> clientOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            BigQuery bq = clientOpt.get();
            List<GcpWasteItem> items = new ArrayList<>();
            try {
                for (var dataset : bq.listDatasets(gcpProjectId).iterateAll()) {
                    String datasetId = dataset.getDatasetId().getDataset();
                    try {
                        boolean hasTables = bq.listTables(dataset.getDatasetId()).iterateAll().iterator().hasNext();
                        if (!hasTables) {
                            items.add(new GcpWasteItem(datasetId, datasetId,
                                    "Empty BigQuery Dataset", "global", 0.0,
                                    "Dataset has no tables"));
                        }
                    } catch (Exception e) {
                        log.warn("[GcpWaste] Could not list tables in dataset {}: {}", datasetId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findBigQueryDatasetsNotQueried failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }

    // GCS buckets with no lifecycle rules — data accumulating in STANDARD storage silently.
    private CompletableFuture<List<GcpWasteItem>> findStorageBucketsWithNoReads(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.cloud.storage.Storage> clientOpt = gcpClientProvider.getStorageClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            com.google.cloud.storage.Storage storage = clientOpt.get();
            List<GcpWasteItem> items = new ArrayList<>();
            try {
                for (var bucket : storage.list().iterateAll()) {
                    try {
                        var fullBucket = storage.get(bucket.getName(),
                                com.google.cloud.storage.Storage.BucketGetOption.fields(
                                        com.google.cloud.storage.Storage.BucketField.LIFECYCLE));
                        if (fullBucket != null
                                && (fullBucket.getLifecycleRules() == null || fullBucket.getLifecycleRules().isEmpty())) {
                            items.add(new GcpWasteItem(bucket.getName(), bucket.getName(),
                                    "GCS Bucket (No Lifecycle Policy)", "global", 0.0,
                                    "Bucket has no lifecycle rules — data may accumulate in STANDARD storage indefinitely"));
                        }
                    } catch (Exception e) {
                        log.warn("[GcpWaste] Could not inspect lifecycle for bucket {}: {}", bucket.getName(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findStorageBucketsWithNoReads failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }

    // Artifact Registry repos not updated in 60 days — stale container image storage.
    private CompletableFuture<List<GcpWasteItem>> findStaleArtifactRepos(
            String gcpProjectId, List<String> locations) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.devtools.artifactregistry.v1.ArtifactRegistryClient> clientOpt =
                    gcpClientProvider.getArtifactRegistryClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            List<GcpWasteItem> items = new ArrayList<>();
            Instant cutoff = Instant.now().minus(60, ChronoUnit.DAYS);
            try (com.google.devtools.artifactregistry.v1.ArtifactRegistryClient client = clientOpt.get()) {
                for (String location : locations) {
                    String parent = "projects/" + gcpProjectId + "/locations/" + location;
                    try {
                        for (var repo : client.listRepositories(parent).iterateAll()) {
                            var updateTime = repo.getUpdateTime();
                            Instant updated = Instant.ofEpochSecond(updateTime.getSeconds(), updateTime.getNanos());
                            if (updated.isBefore(cutoff)) {
                                String repoName = repo.getName().substring(repo.getName().lastIndexOf('/') + 1);
                                items.add(new GcpWasteItem(repo.getName(), repoName,
                                        "Stale Artifact Registry Repo", location, 0.0,
                                        "Repository not updated in 60+ days"));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[GcpWaste] Artifact Registry scan failed in {}/{}: {}", gcpProjectId, location, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findStaleArtifactRepos failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }

    // Cloud Scheduler jobs that consistently fail — firing but never succeeding.
    private CompletableFuture<List<GcpWasteItem>> findAlwaysFailingSchedulerJobs(
            String gcpProjectId, List<String> locations) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.cloud.scheduler.v1.CloudSchedulerClient> clientOpt =
                    gcpClientProvider.getSchedulerClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            List<GcpWasteItem> items = new ArrayList<>();
            try (com.google.cloud.scheduler.v1.CloudSchedulerClient client = clientOpt.get()) {
                for (String location : locations) {
                    String parent = "projects/" + gcpProjectId + "/locations/" + location;
                    try {
                        for (var job : client.listJobs(parent).iterateAll()) {
                            var lastAttempt = job.getLastAttemptTime();
                            // Job has been attempted but last status is not OK (code 0 = OK)
                            if (lastAttempt.getSeconds() > 0 && job.getStatus().getCode() != 0) {
                                String jobName = job.getName().substring(job.getName().lastIndexOf('/') + 1);
                                items.add(new GcpWasteItem(job.getName(), jobName,
                                        "Failing Scheduler Job", location, 0.0,
                                        "Job last attempt failed: " + job.getStatus().getMessage()));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[GcpWaste] Scheduler scan failed in {}/{}: {}", gcpProjectId, location, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findAlwaysFailingSchedulerJobs failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }

}