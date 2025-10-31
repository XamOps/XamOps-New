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
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpCommittedUseDiscountDto;
import com.xammer.cloud.dto.gcp.GcpCudUtilizationDto;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.dto.gcp.GcpWasteItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    // Full GCP region-to-location mapping (as of Oct 2025; source: cloud.google.com/compute/docs/regions-zones)
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
        Map.entry("us-west4", "Las Vegas, Nevada, North America")
    );

    private static final String CLOUD_SQL_SERVICE = "services/9662-B51E-5089";
    private static final int HOURS_PER_MONTH = 730;  // Standard estimate; configurable if needed
    private static final double EST_IMAGE_STORAGE_COST_USD_PER_GB = 0.05;  // Configurable
    private static final double EST_BACKUP_COST_USD = 0.01;  // Nominal; configurable
    private static final double EST_PD_COST_USD_PER_GB = 0.10;  // Will query catalog below
    private static final double USD_TO_INR_RATE = 83.0; // Define conversion rate

    // Regex for custom tier: db-custom-<vCPU>-<RAM_MB>
    private static final Pattern CUSTOM_TIER_PATTERN = Pattern.compile("db-custom-(\\d+)-(\\d+)");

    public GcpOptimizationService(
            GcpClientProvider gcpClientProvider,
            @Lazy GcpDataService gcpDataService,
            CloudAccountService cloudAccountService) {
        this.gcpClientProvider = gcpClientProvider;
        this.gcpDataService = gcpDataService;
        this.cloudAccountService = cloudAccountService;
    }

    @Cacheable(value = "gcpCommittedUseDiscounts", key = "'gcp:committed-use-discounts:' + #gcpProjectId")
    public List<GcpCommittedUseDiscountDto> getCommittedUseDiscountsSync(String gcpProjectId) {
        log.info("Fetching committed use discounts for GCP project: {}", gcpProjectId);
        List<GcpCommittedUseDiscountDto> discounts = new ArrayList<>();
        Optional<RegionCommitmentsClient> clientOpt = gcpClientProvider.getRegionCommitmentsClient(gcpProjectId);

        if (clientOpt.isEmpty()) {
            log.warn("RegionCommitmentsClient not available for project {}. Skipping.", gcpProjectId);
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
            log.error("Failed to fetch committed use discounts for project {}:", gcpProjectId, e);
        }
        return discounts;
    }

    public CompletableFuture<List<GcpCommittedUseDiscountDto>> getCommittedUseDiscounts(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> getCommittedUseDiscountsSync(gcpProjectId), executor);
    }

    @Cacheable(value = "gcpCudUtilization", key = "'gcp:cud-utilization:' + #gcpProjectId + ':' + #cudId")
    public GcpCudUtilizationDto getCudUtilizationSync(String gcpProjectId, String cudId) {
        // Placeholder implementation
        return new GcpCudUtilizationDto();
    }

    public CompletableFuture<GcpCudUtilizationDto> getCudUtilization(String gcpProjectId, String cudId) {
        return CompletableFuture.supplyAsync(() -> getCudUtilizationSync(gcpProjectId, cudId), executor);
    }

    @Cacheable(value = "gcpRightsizingRecommendations", key = "'gcp:rightsizing-recommendations:' + #gcpProjectId")
    public List<GcpOptimizationRecommendation> getRightsizingRecommendations(String gcpProjectId) {
        log.info("🔍 Fetching rightsizing recommendations for GCP project: {}", gcpProjectId);

        // Dynamic regions via API (fallback to static if fails)
        List<String> locations = getDynamicRegions(gcpProjectId);
        locations.add("global");  // Always include global

        List<CompletableFuture<List<GcpOptimizationRecommendation>>> futures = new ArrayList<>();

        // Compute Engine rightsizing
        futures.add(getRecommendationsForRecommender(
                gcpProjectId,
                "google.compute.instance.MachineTypeRecommender",
                "global",
                this::mapToRightsizingDto,
                true));
        futures.add(getRecommendationsForRecommender(
                gcpProjectId,
                "google.compute.instanceGroupManager.MachineTypeRecommender",
                "global",
                this::mapToRightsizingDto,
                true));

        // Cloud SQL rightsizing
        for (String location : locations) {
            if (!"global".equals(location)) {
                futures.add(getRecommendationsForRecommender(
                        gcpProjectId,
                        "google.cloudsql.instance.OverprovisionedRecommender",
                        location,
                        this::mapToSqlRightsizingDto,
                        true));
                futures.add(getRecommendationsForRecommender(
                        gcpProjectId,
                        "google.cloudsql.instance.UnderprovisionedRecommender",
                        location,
                        this::mapToSqlRightsizingDto,
                        true));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<GcpOptimizationRecommendation> results = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        enhanceCloudSqlRecommendationsWithHistoricalCost(gcpProjectId, results);

        log.info("✅ Total rightsizing recommendations found: {}", results.size());
        return results;
    }

    // Dynamic region fetcher
    private List<String> getDynamicRegions(String gcpProjectId) {
        Optional<RegionsClient> clientOpt = gcpClientProvider.getRegionsClient(gcpProjectId);
        if (clientOpt.isPresent()) {
            try (RegionsClient client = clientOpt.get()) {
                return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                        .map(region -> region.getName().substring(region.getName().lastIndexOf('/') + 1))  // Extract region ID
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("Failed to fetch dynamic regions for {}: {}. Using static fallback.", gcpProjectId, e.getMessage());
            }
        }
        // Fallback to all known regions
        return new ArrayList<>(REGION_TO_LOCATION.keySet());
    }

    /**
     * Finds the base custom SKU for vCPU or RAM in the given region.
     * @param resourceType "CPU" or "RAM"
     * @param region Region code (e.g., "us-central1")
     * @return Optional<Sku> matching SKU
     */
   /**
     * Finds the base custom SKU for vCPU or RAM in the given region by filtering after listing.
     * @param resourceType "CPU" or "RAM"
     * @param region Region code (e.g., "us-central1")
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

            log.info("Querying Billing Catalog for ALL Cloud SQL SKUs and filtering for: Type={}, Region={}, DescPrefix='{}'",
                     resourceType, region, descriptionPrefix);

            ListSkusRequest request = ListSkusRequest.newBuilder()
                    .setParent(CLOUD_SQL_SERVICE)
                    // No setFilter here
                    .build();

            // Iterate ALL SKUs for the service and filter manually
            for (Sku sku : client.listSkus(request).iterateAll()) {
                boolean regionMatch = sku.getServiceRegionsList().contains(region);
                boolean descriptionMatch = sku.getDescription().contains(descriptionPrefix);
                // Resource Group might not always be present or exactly "CPU"/"RAM", adjust if needed
                boolean resourceGroupMatch = expectedResourceGroup.equalsIgnoreCase(sku.getCategory().getResourceGroup());

                if (regionMatch && descriptionMatch && resourceGroupMatch) {
                    log.debug("Found matching SKU: {} - Description: {}", sku.getSkuId(), sku.getDescription());
                    // Additional check: Ensure it's the base custom SKU (doesn't specify vCPU/RAM amount)
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
            log.error("Failed to query/filter Billing Catalog for Cloud SQL {} SKU (Region: {}): {}", resourceType, region, e.getMessage(), e);
        }

        log.warn("Could not find matching SKU for Cloud SQL {} in Region: {} after filtering.", resourceType, region);
        return Optional.empty();
    }

    /**
     * Extracts price per unit from SKU (assumes USD, first tier, On-Demand).
     * @param sku The matching SKU
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

        PricingExpression.TierRate tieredRate = pricingExpression.getTieredRates(0);  // Base tier
        Money unitPrice = tieredRate.getUnitPrice();
        if (!"USD".equals(unitPrice.getCurrencyCode())) {
            log.warn("Non-USD currency for SKU {}: {}", sku.getSkuId(), unitPrice.getCurrencyCode());
            // Consider adding conversion logic if needed, otherwise return 0
            return 0.0;
        }

        String actualUsageUnit = pricingExpression.getUsageUnit();
        if (!expectedUsageUnit.equalsIgnoreCase(actualUsageUnit)) {
            log.warn("Unexpected usage unit for SKU {}: {} (expected: {})", sku.getSkuId(), actualUsageUnit, expectedUsageUnit);
            return 0.0;
        }

        // Parse price: units.nanos (e.g., 0.040400000 for $0.0404)
        BigDecimal price = new BigDecimal(unitPrice.getUnits())
                .add(new BigDecimal(unitPrice.getNanos()).movePointLeft(9));
        double pricePerUnit = price.doubleValue();

        log.debug("SKU {} Price: ${} per {} (Currency: {})", sku.getSkuId(), pricePerUnit, actualUsageUnit, unitPrice.getCurrencyCode());

        return pricePerUnit;
    }

    /**
     * Calculate Cloud SQL monthly price based on tier using Billing Catalog API (for dedicated/custom).
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
            ramGb = Double.parseDouble(matcher.group(2)) / 1024.0;  // MB to GB
        } else if (tier.startsWith("db-n1-standard-")) {
            // Parse standard: db-n1-standard-<vCPU>
            Pattern standardPattern = Pattern.compile("db-n1-standard-(\\d+)");
            Matcher standardMatcher = standardPattern.matcher(tier);
            if (!standardMatcher.matches()) {
                log.warn("Could not parse standard tier: {}", tier);
                return 0.0;
            }
            vcpus = Integer.parseInt(standardMatcher.group(1));
            // Standard ratio: 3.75 GB per vCPU (this might vary slightly, Catalog is better)
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
            log.warn("Could not find CPU or RAM SKUs via Billing Catalog for region: {}. Falling back to hardcoded estimate.", region);
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
        log.info("Cloud SQL Monthly Estimate from Catalog ({}): CPU ${:.2f} + RAM ${:.2f} = ${:.2f}", tier, cpuMonthly, ramMonthly, totalMonthlyUsd);

        return totalMonthlyUsd; // Return USD
    }

   /**
     * Build a human-readable tier description with vCPU, memory specs, and price IN RUPEES.
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
        // Add more else if blocks here if other tier patterns need specific spec parsing


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
                         return String.format("db-custom-%d-%d", nextVcpu, (int)(nextVcpu * 3.75 * 1024));
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
                     if (prevVcpu == 1 && currentVcpu > 1) return "db-n1-standard-1";
                     if (currentVcpu == 1) return "db-g1-small"; // Example downgrade path
                     // Add more logic for other standard tiers
                      return String.format("db-n1-standard-%d", prevVcpu); // If prev standard exists
                 }
            }
        }

        log.warn("Could not infer recommended tier for current tier '{}' and type '{}'", currentTier, recommendationType);
        return "Unknown"; // Fallback
    }


    @Cacheable(value = "gcpWasteReport", key = "'gcp:waste-report:' + #gcpProjectId")
    public List<GcpWasteItem> getWasteReport(String gcpProjectId) {
        log.info("Starting waste report generation for GCP project: {}", gcpProjectId);
        List<CompletableFuture<List<GcpWasteItem>>> futures = new ArrayList<>();

        futures.add(findIdleResources(gcpProjectId, "google.compute.disk.IdleResourceRecommender", "Idle Persistent Disk"));
        futures.add(findIdleResources(gcpProjectId, "google.compute.address.IdleResourceRecommender", "Unused IP Address"));
        futures.add(findIdleResources(gcpProjectId, "google.compute.instance.IdleResourceRecommender", "Idle VM Instance"));
        futures.add(findIdleResources(gcpProjectId, "google.cloudsql.instance.IdleRecommender", "Idle Cloud SQL Instance"));
        futures.add(findUnattachedDisks(gcpProjectId));
        futures.add(findUnusedCustomImages(gcpProjectId));
        futures.add(findUnusedFirewallRules(gcpProjectId));
        futures.add(findOldSqlSnapshots(gcpProjectId));

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    //
    // -------------------- ⬇️ FIX 1: CACHE ANNOTATION REMOVED ⬇️ --------------------
    //
    // @Cacheable(value = "gcpSavingsSummary", key = "'gcp:savings-summary:' + #gcpProjectId")
    public DashboardData.SavingsSummary getSavingsSummary(String gcpProjectId) {
        List<GcpWasteItem> waste = getWasteReport(gcpProjectId);
        List<GcpOptimizationRecommendation> rightsizing = getRightsizingRecommendations(gcpProjectId);

        double wasteSavings = waste.stream().mapToDouble(GcpWasteItem::getMonthlySavings).sum();
        double rightsizingSavings = rightsizing.stream()
                .filter(r -> r.getMonthlySavings() >= 0) // Only count actual savings
                .mapToDouble(GcpOptimizationRecommendation::getMonthlySavings)
                .sum();

        List<DashboardData.SavingsSuggestion> suggestions = new ArrayList<>();
        if (rightsizingSavings > 0) suggestions.add(new DashboardData.SavingsSuggestion("Rightsizing", rightsizingSavings));
        if (wasteSavings > 0) suggestions.add(new DashboardData.SavingsSuggestion("Waste Elimination", wasteSavings));

        // Return values in INR
        return new DashboardData.SavingsSummary(wasteSavings + rightsizingSavings, suggestions);
    }

    //
    // -------------------- ⬇️ FIX 2: CACHE ANNOTATION REMOVED ⬇️ --------------------
    //
    // @Cacheable(value = "gcpOptimizationSummary", key = "'gcp:optimization-summary:' + #gcpProjectId")
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
        log.info("Fetching CUD recommendations for GCP project: {}", gcpProjectId);

        CloudAccount account = cloudAccountService.findByGcpProjectId(gcpProjectId);
        if (account == null) {
            log.error("Could not find CloudAccount for GCP project ID: {}", gcpProjectId);
            return CompletableFuture.completedFuture(List.of());
        }
        String billingAccountId = account.getGcpBillingAccountId();

        List<CompletableFuture<List<GcpOptimizationRecommendation>>> futures = new ArrayList<>();

        // Dynamic locations
        List<String> locations = getDynamicRegions(gcpProjectId);

        if (billingAccountId == null || billingAccountId.isEmpty()) {
            log.warn("No billing account ID found for project {}. Fetching only project-level CUD recommendations.", gcpProjectId);
        } else {
             log.info("Fetching billing-account level CUD recommendations for {}", billingAccountId);
             for (String location : locations) {
                futures.add(getRecommendationsForRecommender(
                        billingAccountId,
                        "google.compute.commitment.UsageCommitmentRecommender",
                        location,
                        this::mapToCudRecommendationDto,
                        false)); // Billing account scope
            }
        }

        // Always add project-level recommendations as well
        log.info("Fetching project-level CUD recommendations for {}", gcpProjectId);
        futures.addAll(getProjectLevelCudRecommendationsFutures(gcpProjectId));


        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, GcpOptimizationRecommendation> uniqueRecommendations = new HashMap<>();
                    futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .forEach(rec -> {
                                // Use recommendationId for basic deduplication, enhance if needed
                                uniqueRecommendations.putIfAbsent(rec.getRecommendationId(), rec);
                            });

                     List<GcpOptimizationRecommendation> finalRecommendations = new ArrayList<>(uniqueRecommendations.values());

                    if (finalRecommendations.isEmpty()) {
                        log.warn("No unique CUD recommendations found for project {} or billing account {}.",
                                gcpProjectId, billingAccountId != null ? billingAccountId : "N/A");
                    } else {
                        log.info("Found {} unique CUD recommendations for project {} / billing account {}.",
                                finalRecommendations.size(), gcpProjectId, billingAccountId != null ? billingAccountId : "N/A");
                    }

                    return finalRecommendations;
                });
    }

    @Cacheable(value = "gcpCudRecommendations", key = "'gcp:cud-recommendations:' + #gcpProjectId")
    public List<GcpOptimizationRecommendation> getCudRecommendationsSync(String gcpProjectId) {
         return getCudRecommendations(gcpProjectId).join();
    }


    private List<CompletableFuture<List<GcpOptimizationRecommendation>>> getProjectLevelCudRecommendationsFutures(String gcpProjectId) {
        // Dynamic locations
        List<String> locations = getDynamicRegions(gcpProjectId);

        return locations.stream()
                .map(location -> getRecommendationsForRecommender(
                        gcpProjectId,
                        "google.compute.commitment.UsageCommitmentRecommender",
                        location,
                        this::mapToCudRecommendationDto,
                        true)) // Project scope
                .collect(Collectors.toList());
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

                if (!recommendations.isEmpty()) {
                    Recommendation firstRec = recommendations.get(0);
                    log.debug("Sample recommendation - Name: {}, Description: {}, State: {}", // Changed to debug
                            firstRec.getName(),
                            firstRec.getDescription(),
                            firstRec.getStateInfo().getState());
                } else {
                    log.info("No recommendations found for parent: {}", parent);
                }


                return recommendations.stream()
                        .map(mapper)
                        .filter(Objects::nonNull) // Ensure mapper doesn't return null
                        .collect(Collectors.toList());

            } catch (com.google.api.gax.rpc.PermissionDeniedException e) {
                 log.error("Permission denied querying recommender {} for {} {} in location {}. Ensure Recommender API is enabled and permissions are correct.",
                        recommenderId, isProjectScope ? "project" : "billing account", identifier, location, e);
                 return List.of();
            }
            catch (Exception e) {
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
                                            if (backup.getWindowStartTime() == null) return false;
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
                                            "Old Cloud SQL Backup (>90 days)",
                                            instance.getRegion(),
                                             // Convert nominal USD cost to INR
                                            EST_BACKUP_COST_USD * USD_TO_INR_RATE
                                    ))
                                    .forEach(wasteItems::add);
                        }
                    } catch (Exception backupEx) {
                         log.error("Failed to list backups for Cloud SQL instance {}: {}", instanceName, backupEx.getMessage());
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
            if (clientOpt.isEmpty()) return List.of();

            try (DisksClient client = clientOpt.get()) {
                return StreamSupport.stream(
                                client.aggregatedList(gcpProjectId).iterateAll().spliterator(),
                                false)
                        .flatMap(entry -> entry.getValue().getDisksList().stream())
                        .filter(disk -> disk.getUsersList().isEmpty())
                        .map(disk -> {
                             // Extract zone name reliably
                             String zoneUrl = disk.getZone();
                             String zone = zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1);
                             return new GcpWasteItem(
                                disk.getName(),
                                "Unattached Persistent Disk",
                                zone,
                                calculateDiskCost(disk.getSizeGb()) // Returns INR
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
            if (clientOpt.isEmpty()) return List.of();

            try (ImagesClient client = clientOpt.get()) {
                return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                        // Refined check: Deprecated images
                        .filter(image -> image.hasDeprecated() && "DEPRECATED".equals(image.getDeprecated().getState()))
                        .map(image -> new GcpWasteItem(
                                image.getName(),
                                "Deprecated Custom Image",
                                "global",
                                // Estimate storage cost ($0.05/GB/month) and convert to INR
                                (EST_IMAGE_STORAGE_COST_USD_PER_GB * image.getDiskSizeGb()) * USD_TO_INR_RATE
                        ))
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
            if (clientOpt.isEmpty()) return List.of();

            try (FirewallsClient client = clientOpt.get()) {
                return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                        .filter(Firewall::getDisabled) // Only finds explicitly disabled rules
                        .map(firewall -> new GcpWasteItem(
                                firewall.getName(),
                                "Disabled Firewall Rule", // More specific type
                                "global",
                                0.0 // No direct cost, but potential security risk or clutter
                        ))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to list firewall rules for project {}:", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }

    private CompletableFuture<List<GcpWasteItem>> findIdleResources(
            String gcpProjectId,
            String recommenderId,
            String wasteType) {

        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) return new ArrayList<>();

            try (RecommenderClient client = clientOpt.get()) {
                // Most idle recommenders are global, but check specific recommender docs if needed
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, "global", recommenderId);
                log.info("Querying recommender '{}' for project {}", recommenderId, gcpProjectId);

                return StreamSupport.stream(
                                client.listRecommendations(recommenderName).iterateAll().spliterator(),
                                false)
                        .map(rec -> mapToWasteDto(rec, wasteType))
                        .filter(Objects::nonNull) // Filter out nulls if mapping fails
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to get waste report for recommender {} in project {}",
                        recommenderId, gcpProjectId, e);
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
        String currencyCode = "USD"; // Assume USD unless specified
        if (rec.getPrimaryImpact().hasCostProjection()) {
            // Cost projection is negative for savings
            Money costMoney = rec.getPrimaryImpact().getCostProjection().getCost();
             if (costMoney.getCurrencyCode() != null && !costMoney.getCurrencyCode().isEmpty()) {
                currencyCode = costMoney.getCurrencyCode();
            }
            double costImpact = costMoney.getUnits() + (costMoney.getNanos() / 1_000_000_000.0);
            if (costImpact < 0) {
                 monthlySavingsUsd = Math.abs(costImpact);
            }
        }

        // Convert to INR
        double monthlySavingsInr = monthlySavingsUsd;
         if ("USD".equalsIgnoreCase(currencyCode)) {
             monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE;
         } else if (!"INR".equalsIgnoreCase(currencyCode)) {
             log.warn("Unsupported currency {} in waste recommendation {}, assuming USD for conversion.", currencyCode, rec.getName());
             monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE; // Fallback to USD conversion
         }
         // If currencyCode was INR, monthlySavingsInr remains as is.


        return new GcpWasteItem(resourceName, wasteType, extractLocation(rec.getName()), monthlySavingsInr);
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
             log.warn("Unsupported currency {} in rightsizing recommendation {}, assuming USD for conversion.", currencyCode, rec.getName());
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
             log.warn("Unsupported currency {} in SQL rightsizing recommendation {}, assuming USD for conversion.", currencyCode, rec.getName());
             monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE; // Fallback to USD conversion
         }

        String location = extractLocation(rec.getName());

        return new GcpOptimizationRecommendation(
                resourceName,
                currentTier, // Use tier name extracted from description initially
                recommendedTier,
                monthlySavingsInr,  // Store INR value
                "Cloud SQL",
                location,
                rec.getName(),
                recommendationType,
                description
        );
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
            Pattern pattern1 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*[a-z0-9])\\s+has\\s+had", Pattern.CASE_INSENSITIVE);
            Matcher matcher1 = pattern1.matcher(description);
            if (matcher1.find()) {
                String instanceName = matcher1.group(1);
                log.debug("✓ Extracted instance name '{}' (pattern 1)", instanceName);
                return instanceName;
            }

            // Pattern 2: "Instance: [name] may..."
            Pattern pattern2 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*[a-z0-9])\\s+may", Pattern.CASE_INSENSITIVE);
            Matcher matcher2 = pattern2.matcher(description);
            if (matcher2.find()) {
                String instanceName = matcher2.group(1);
                log.debug("✓ Extracted instance name '{}' (pattern 2)", instanceName);
                return instanceName;
            }

            // Pattern 3: "Instance [name] has had..." (without colon)
             Pattern pattern3 = Pattern.compile("Instance\\s+([a-z0-9][-a-z0-9]*[a-z0-9])\\s+has\\s+had", Pattern.CASE_INSENSITIVE);
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
     * Pattern: "...resources: 1 (+0) vCPUs and 3.75 (+3.15) GB memory" -> db-custom-1-3840
     */
    private String extractRecommendedTierFromDescription(String description) {
        if (description == null || description.isEmpty()) {
            return "Unknown";
        }

        try {
            // Updated pattern to be more flexible with surrounding text
            Pattern pattern = Pattern.compile("(\\d+)(?:\\s*\\([^)]*\\))?\\s*vCPUs?\\s+and\\s+([\\d.]+)(?:\\s*\\([^)]*\\))?\\s*GB(?:\\s+memory)?", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(description);

            if (matcher.find()) {
                int vcpus = Integer.parseInt(matcher.group(1));
                double memoryGb = Double.parseDouble(matcher.group(2));
                int memoryMb = (int)Math.round(memoryGb * 1024); // Round to nearest MB

                // Check for standard tiers first based on vCPU (common ones)
                if (vcpus == 1 && Math.abs(memoryGb - 3.75) < 0.1) return "db-n1-standard-1";
                if (vcpus == 2 && Math.abs(memoryGb - 7.5) < 0.1) return "db-n1-standard-2";
                if (vcpus == 4 && Math.abs(memoryGb - 15) < 0.1) return "db-n1-standard-4";
                 // Add more standard checks if needed

                // If not standard, construct custom tier name
                String tier = String.format("db-custom-%d-%d", vcpus, memoryMb);
                log.info("✓ Constructed recommended tier '{}' from description ({} vCPUs, {} GB)", tier, vcpus, memoryGb);
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

        // Method 3: Parse instance name from description (FALLBACK, especially for Cloud SQL)
        String description = rec.getDescription();
        if (description != null && !description.isEmpty() && description.toLowerCase().contains("instance:")) {
            String extracted = extractInstanceNameFromDescription(description);
            if (!"Unknown".equals(extracted)) {
                log.debug("Extracted resource name '{}' from description as fallback", extracted);
                return extracted;
            }
        }

        log.warn("Could not extract valid resource name from recommendation: {}. Overview Fields: {}", rec.getName(), fieldsMap.keySet());
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
        if (recommendationName == null || recommendationName.isEmpty()) return "global";
        try {
            Pattern pattern = Pattern.compile("/locations/([^/]+)/");
            Matcher matcher = pattern.matcher(recommendationName);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.warn("Failed to extract location from recommendation name '{}': {}", recommendationName, e.getMessage());
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
            currencyCode = costMoney.getCurrencyCode() != null && !costMoney.getCurrencyCode().isEmpty() ? costMoney.getCurrencyCode() : "USD";

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
             log.info("💱 Converted CUD savings from USD to INR: ${} → ₹{}", String.format("%.2f", monthlySavingsUsd), String.format("%.2f", monthlySavingsInr));
         } else if (!"INR".equalsIgnoreCase(currencyCode)) {
             log.warn("Unsupported currency {} in CUD recommendation {}, assuming USD for conversion.", currencyCode, rec.getName());
             monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE; // Fallback to USD conversion
         }

        log.info("💰 Final CUD savings: ₹{} (Original Currency: {})", String.format("%.2f", monthlySavingsInr), currencyCode);


        // Extract commitment details from description
        String commitmentType = "Unknown Commitment"; // Default
        if (description != null) {
            // Try to extract term and resource type
            String term = "Unknown";
            if (description.contains("3 year")) term = "3-year";
            else if (description.contains("1 year")) term = "1-year";

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
     * @deprecated More robust parsing integrated into mapToCudRecommendationDto.
     */
    @Deprecated
    private String extractCommitmentAmountFromDescription(String description) {
       // Kept for reference
       return "N/A";
    }

    /**
     * HELPER: Extract location from recommendation name.
     * @deprecated Replaced by the general `extractLocation` method.
     */
     @Deprecated
    private String extractLocationFromRecommendationName(String recommendationName) {
        return extractLocation(recommendationName);
    }

    /** Calculates estimated monthly disk cost in INR */
    private double calculateDiskCost(long sizeGb) {
        // Standard PD cost: $0.10 per GB/month (us-central1, estimate)
        double costUsd = sizeGb * EST_PD_COST_USD_PER_GB;
        return costUsd * USD_TO_INR_RATE; // Convert to INR
    }

    /** Attempts to extract the current tier name from the recommendation description */
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
     * Enhances Cloud SQL recommendations with historical cost (INR) and formatted descriptions.
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
            if (!"Cloud SQL".equals(dto.getService()) || dto.getResourceName() == null) continue;

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
                     log.warn("Mismatch between extracted tier ('{}') and actual tier ('{}') for instance {}. Using actual tier.",
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
                     log.warn("Could not retrieve valid historical cost for instance '{}'. last30DayCost set to null.", instanceName);
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
                    log.info("Recommender API provided 0 savings for '{}', calculating fallback based on price estimates.", instanceName);
                    if ("COST_SAVINGS".equals(dto.getRecommendationType())) {
                        double calculatedSavingsUsd = currentPriceUsd - recommendedPriceUsd;
                        if (calculatedSavingsUsd > 0) {
                             double calculatedSavingsInr = calculatedSavingsUsd * USD_TO_INR_RATE;
                             dto.setMonthlySavings(calculatedSavingsInr);
                             log.info("Populated 'monthlySavings' from price diff: ₹{} ({} -> {})",
                                    String.format("%.2f", calculatedSavingsInr), actualCurrentTier, rawRecommendedTier);
                        } else {
                             log.warn("Fallback price difference for COST_SAVINGS is not positive (Current: ${}, Rec: ${}). Keeping savings as 0.",
                                       String.format("%.2f", currentPriceUsd), String.format("%.2f", recommendedPriceUsd));
                        }
                    } else if ("PERFORMANCE_IMPROVEMENT".equals(dto.getRecommendationType())) {
                        double calculatedCostIncreaseUsd = recommendedPriceUsd - currentPriceUsd;
                         if (calculatedCostIncreaseUsd > 0) {
                            double calculatedCostIncreaseInr = calculatedCostIncreaseUsd * USD_TO_INR_RATE;
                            dto.setMonthlySavings(-calculatedCostIncreaseInr); // Store as negative INR
                             log.info("Populated 'monthlySavings' (cost increase) from price diff: ₹{} ({} -> {})",
                                    String.format("%.2f", -calculatedCostIncreaseInr), actualCurrentTier, rawRecommendedTier);
                         } else {
                              log.warn("Fallback price difference for PERFORMANCE_IMPROVEMENT is not positive (Current: ${}, Rec: ${}). Keeping cost increase as 0.",
                                       String.format("%.2f", currentPriceUsd), String.format("%.2f", recommendedPriceUsd));
                         }
                    }
                } else if (dto.getMonthlySavings() != 0.0) {
                     log.info("Using monthly savings/cost value derived from Recommender API for '{}': ₹{}", instanceName, String.format("%.2f", dto.getMonthlySavings()));
                }


                // Build readable descriptions using calculated INR prices
                double currentPriceInr = currentPriceUsd * USD_TO_INR_RATE;
                double recommendedPriceInr = recommendedPriceUsd * USD_TO_INR_RATE;
                dto.setCurrentMachineType(buildReadableTierDescription(actualCurrentTier, currentPriceInr)); // Pass INR
                dto.setRecommendedMachineType(buildReadableTierDescription(rawRecommendedTier, recommendedPriceInr)); // Pass INR

                log.info("✅ Enhanced recommendation for '{}': Savings/Cost: ₹{}/mo, Last 30d Cost: ₹{}/mo",
                        instanceName,
                        String.format("%.2f", dto.getMonthlySavings()),
                        dto.getLast30DayCost() != null ? String.format("%.2f", dto.getLast30DayCost()) : "N/A");

            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                if (e.getStatusCode() == 404) {
                    log.error("Cloud SQL instance '{}' not found in project {}. It may have been deleted.", instanceName, gcpProjectId);
                } else if (e.getStatusCode() == 403) {
                     log.error("Permission denied enhancing Cloud SQL instance '{}' in project {}. Check SQL Admin API permissions.", instanceName, gcpProjectId);
                }
                else {
                    log.error("API error enhancing Cloud SQL instance '{}' in project {}: {} - {}",
                            instanceName, gcpProjectId, e.getStatusCode(), e.getMessage());
                }
                 // Clear potentially misleading data on error
                 dto.setLast30DayCost(null);
                 dto.setCurrentMachineType(buildReadableTierDescription(dto.getCurrentMachineType(), 0.0)); // Use raw tier, clear price
                 dto.setRecommendedMachineType(buildReadableTierDescription(rawRecommendedTier, 0.0)); // Use raw tier, clear price
            } catch (Exception e) {
                log.error("❌ Unexpected error enhancing instance '{}' in project {}: {}", instanceName, gcpProjectId, e.getMessage(), e);
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
                         // "WHERE DATE(usage_start_time) >= '%s' " + // Use usage_start_time if not partitioned
                            "  AND DATE(_PARTITIONTIME) < '%s' " +
                         // "  AND DATE(usage_start_time) <= '%s' " +
                            "  AND service.description = 'Cloud SQL' " +
                            "  AND project.id = '%s' " +
                            // Query 'labels' array (UNNEST)
                            "  AND EXISTS (SELECT 1 FROM UNNEST(labels) AS l WHERE l.key = '%s' AND l.value = '%s') " +
                            "  AND cost > 0 " +
                            "GROUP BY currency", // Group by currency
                    tableNameOpt.get(),
                    startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE), // Partition date is exclusive upper bound
                 // today.format(DateTimeFormatter.ISO_LOCAL_DATE), // Use for usage_start_time
                    gcpProjectId,
                    sqlInstanceLabelKey,
                    instanceName
            );

            log.info("📊 Querying 30-day cost for Cloud SQL instance: {}", instanceName);
            log.debug("BigQuery SQL: {}", query);

            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());

            double totalCostInr = 0.0;
            boolean dataFound = false;

             if (results.getTotalRows() == 0) {
                 log.warn("⚠️ No 30-day Cloud SQL cost data found via BigQuery for instance: {} in project {}", instanceName, gcpProjectId);
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
                        log.warn("  Unsupported currency '{}' found in billing data for instance {}. Cost: {}. Treating as USD for conversion.",
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
        }
        catch (Exception e) {
            log.error("❌ Unexpected Error querying Cloud SQL cost for '{}' in project {}: {}", instanceName, gcpProjectId, e.getMessage(), e);
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

            // Fallback: If not found, log warning. A more robust solution might check a central billing project ID if configured.
            log.warn("Could not find billing table matching 'gcp_billing_export_v1_...' directly in project {}. Ensure billing export is set up correctly.", gcpProjectId);

        } catch (BigQueryException bqEx) {
             log.error("BigQuery API Error finding billing table in project {}: {} - {}", gcpProjectId, bqEx.getCode(), bqEx.getMessage());
             if (bqEx.getError() != null) {
                  log.error("   Reason: {}, Location: {}", bqEx.getError().getReason(), bqEx.getError().getLocation());
             }
        }
        catch (Exception e) {
            log.error("Failed to find billing table in project {}: {}", gcpProjectId, e.getMessage());
        }
        return Optional.empty(); // Return empty if not found or error occurred
    }

}