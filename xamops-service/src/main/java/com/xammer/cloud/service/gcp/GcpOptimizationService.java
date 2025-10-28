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

        List<DashboardData.RegionStatus> regions = gcpDataService.getRegionStatusForGcp(new ArrayList<>()).join();
        List<String> locations = regions.stream()
                .map(DashboardData.RegionStatus::getRegionId)
                .collect(Collectors.toList());

        if (locations.isEmpty()) {
            log.warn("No active regions found, using default GCP regions");
            locations = new ArrayList<>(Arrays.asList(
                    "us-central1", "us-east1", "us-west1", "us-east4",
                    "europe-west1", "europe-west2", "europe-west3",
                    "asia-southeast1", "asia-east1", "asia-northeast1"
            ));
        }

        locations.add("global");

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

        // ✅ Cloud SQL rightsizing - Query both overprovisioned and underprovisioned
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

        // ✅ Enhance Cloud SQL recommendations with historical cost
        enhanceCloudSqlRecommendationsWithHistoricalCost(gcpProjectId, results);

        log.info("✅ Total rightsizing recommendations found: {}", results.size());
        return results;
    }

    /**
     * Enhance Cloud SQL recommendations with actual instance details from SQL Admin API
     * @deprecated Replaced by enhanceCloudSqlRecommendationsWithHistoricalCost
     */
    @Deprecated
    private void enhanceCloudSqlRecommendations(String gcpProjectId, List<GcpOptimizationRecommendation> results) {
        // This method is kept for reference but logic is moved to
        // enhanceCloudSqlRecommendationsWithHistoricalCost
    }


    /**
     * Calculate Cloud SQL monthly price based on tier
     * Prices from https://cloud.google.com/sql/pricing (2025)
     *
     * ✅ IMPROVEMENT (Point 2): Added warning for hardcoded region.
     */
    private double calculateCloudSqlPrice(String tier, String region) {
        if (tier == null || "Unknown".equals(tier)) {
            return 0.0;
        }

        // ✅ IMPROVEMENT (Point 2): Warn about hardcoded pricing
        if (region != null && !region.startsWith("us-central1")) {
            log.warn("Cloud SQL pricing is hardcoded for us-central1. Price for region '{}' may be inaccurate.", region);
        }

        // Hourly prices in USD (us-central1 region)
        double hourlyPrice = 0.0;

        if ("db-f1-micro".equals(tier)) {
            hourlyPrice = 0.0105; // $0.0105/hour
        } else if ("db-g1-small".equals(tier)) {
            hourlyPrice = 0.035; // $0.035/hour
        } else if (tier.startsWith("db-custom-")) {
            // db-custom-<vCPU>-<memory_MB>
            String[] parts = tier.split("-");
            if (parts.length == 4) {
                try {
                    int vcpus = Integer.parseInt(parts[2]);
                    int memoryMb = Integer.parseInt(parts[3]);
                    double memoryGb = memoryMb / 1024.0;

                    // Pricing: $0.059/vCPU/hour + $0.01/GB/hour
                    hourlyPrice = (vcpus * 0.059) + (memoryGb * 0.01);
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse custom tier: {}", tier);
                }
            }
        } else if (tier.startsWith("db-n1-")) {
            // Standard N1 tiers
            if (tier.equals("db-n1-standard-1")) hourlyPrice = 0.0950;
            else if (tier.equals("db-n1-standard-2")) hourlyPrice = 0.1900;
            else if (tier.equals("db-n1-standard-4")) hourlyPrice = 0.3800;
            else if (tier.equals("db-n1-standard-8")) hourlyPrice = 0.7600;
            else if (tier.equals("db-n1-standard-16")) hourlyPrice = 1.5200;
            else if (tier.equals("db-n1-standard-32")) hourlyPrice = 3.0400;
            else if (tier.equals("db-n1-standard-64")) hourlyPrice = 6.0800;
        }

        // Convert to monthly price (730 hours/month average)
        double monthlyPrice = hourlyPrice * 730;

        log.debug("Calculated price for tier {}: ${}/hour = ${}/month",
                tier, String.format("%.4f", hourlyPrice), String.format("%.2f", monthlyPrice));

        return monthlyPrice;
    }

    /**
     * Build a human-readable tier description with vCPU, memory specs, and price
     */
    private String buildReadableTierDescription(String tier, double monthlyPrice) {
        if (tier == null || "Unknown".equals(tier)) {
            return "Unknown";
        }

        String specs = "";

        if (tier.startsWith("db-custom-")) {
            String[] parts = tier.split("-");
            if (parts.length == 4) {
                String vcpus = parts[2];
                double memoryMb = Double.parseDouble(parts[3]);
                double memoryGb = memoryMb / 1024.0;
                specs = String.format("%s vCPU, %.2f GB", vcpus, memoryGb);
            }
        } else if ("db-f1-micro".equals(tier)) {
            specs = "shared vCPU, 0.6 GB";
        } else if ("db-g1-small".equals(tier)) {
            specs = "shared vCPU, 1.7 GB";
        } else if (tier.startsWith("db-n1-")) {
            specs = "N1 standard"; // Specs are implied by standard tier name
        }

        return String.format("%s (%s, $%.2f/mo)", tier, specs, monthlyPrice);
    }

    /**
     * Infer recommended tier based on recommendation type
     */
    private String inferRecommendedTier(String currentTier, String recommendationType) {
        if ("PERFORMANCE_IMPROVEMENT".equals(recommendationType)) {
            // Underprovisioned - needs upgrade
            if (currentTier.equals("db-f1-micro")) {
                return "db-custom-1-3840"; // Standard upgrade path
            } else if (currentTier.equals("db-g1-small")) {
                return "db-custom-2-7680";
            } else if (currentTier.startsWith("db-custom-")) {
                String[] parts = currentTier.split("-");
                if (parts.length == 4) {
                    int currentVcpu = Integer.parseInt(parts[2]);
                    int currentMemMb = Integer.parseInt(parts[3]);
                    return String.format("db-custom-%d-%d", currentVcpu * 2, currentMemMb * 2);
                }
            }
        } else if ("COST_SAVINGS".equals(recommendationType)) {
            // Overprovisioned - can downgrade
            if (currentTier.startsWith("db-custom-")) {
                String[] parts = currentTier.split("-");
                if (parts.length == 4) {
                    int currentVcpu = Integer.parseInt(parts[2]);
                    int currentMemMb = Integer.parseInt(parts[3]);
                    int newVcpu = Math.max(1, currentVcpu / 2);
                    int newMemMb = Math.max(3840, currentMemMb / 2); // Ensure minimum memory for custom
                    return String.format("db-custom-%d-%d", newVcpu, newMemMb);
                }
            }
        }

        return "Unknown";
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

    @Cacheable(value = "gcpSavingsSummary", key = "'gcp:savings-summary:' + #gcpProjectId")
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

        return new DashboardData.SavingsSummary(wasteSavings + rightsizingSavings, suggestions);
    }

    @Cacheable(value = "gcpOptimizationSummary", key = "'gcp:optimization-summary:' + #gcpProjectId")
    public DashboardData.OptimizationSummary getOptimizationSummary(String gcpProjectId) {
        List<GcpWasteItem> waste = getWasteReport(gcpProjectId);
        List<GcpOptimizationRecommendation> rightsizing = getRightsizingRecommendations(gcpProjectId);

        double totalSavings = waste.stream().mapToDouble(GcpWasteItem::getMonthlySavings).sum()
                + rightsizing.stream()
                .filter(r -> r.getMonthlySavings() >= 0) // Only count actual savings
                .mapToDouble(GcpOptimizationRecommendation::getMonthlySavings)
                .sum();
        long criticalAlerts = rightsizing.size(); // Count all recommendations as alerts

        return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
    }

    public CompletableFuture<List<GcpOptimizationRecommendation>> getCudRecommendations(String gcpProjectId) {
        log.info("Fetching CUD recommendations for GCP project: {}", gcpProjectId);

        CloudAccount account = cloudAccountService.findByGcpProjectId(gcpProjectId);
        String billingAccountId = account.getGcpBillingAccountId();

        if (billingAccountId == null || billingAccountId.isEmpty()) {
            log.warn("No billing account ID found for project {}. Trying project-level CUD recommendations.", gcpProjectId);
            return getProjectLevelCudRecommendations(gcpProjectId);
        }

        String projectNumber = getProjectNumber(gcpProjectId);
        log.info("Project number for {}: {}", gcpProjectId, projectNumber);

        List<String> locations = Arrays.asList("global", "us-east1", "us-central1", "us-west1", "europe-west1", "asia-southeast1");

        List<CompletableFuture<List<GcpOptimizationRecommendation>>> futures = new ArrayList<>();

        for (String location : locations) {
            futures.add(getRecommendationsForRecommender(
                    billingAccountId,
                    "google.compute.commitment.UsageCommitmentRecommender",
                    location,
                    this::mapToCudRecommendationDto,
                    false)); // Billing account scope
        }

        // Add project-level recommendations as well
        futures.add(getProjectLevelCudRecommendations(gcpProjectId));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<GcpOptimizationRecommendation> allRecommendations = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .distinct() // Ensure uniqueness based on content
                            .collect(Collectors.toList());

                    if (allRecommendations.isEmpty()) {
                        log.warn("No CUD recommendations found for project {} or billing account {}.",
                                gcpProjectId, billingAccountId);
                    } else {
                        log.info("Found {} CUD recommendations for project {} / billing account {}.",
                                allRecommendations.size(), gcpProjectId, billingAccountId);
                    }

                    return allRecommendations;
                });
    }
    @Cacheable(value = "gcpCudRecommendations", key = "'gcp:cud-recommendations:' + #gcpProjectId")
    public List<GcpOptimizationRecommendation> getCudRecommendationsSync(String gcpProjectId) {
        log.info("🔍 Fetching CUD recommendations for GCP project: {}", gcpProjectId);

        List<GcpOptimizationRecommendation> recommendations = new ArrayList<>();
        Set<String> uniqueKeys = new HashSet<>(); // Track unique combinations

        try {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("RecommenderClient not available for project {}. Skipping CUD recommendations.", gcpProjectId);
                return recommendations;
            }

            RecommenderClient client = clientOpt.get();

            List<DashboardData.RegionStatus> regions = gcpDataService.getRegionStatusForGcp(new ArrayList<>()).join();
            List<String> locations = regions.stream()
                    .map(DashboardData.RegionStatus::getRegionId)
                    .collect(Collectors.toList());

            if (locations.isEmpty()) {
                log.warn("No active regions found, using default GCP regions for CUD");
                locations = new ArrayList<>(Arrays.asList(
                        "us-central1", "us-east1", "us-west1", "us-east4",
                        "europe-west1", "europe-west2", "europe-west3",
                        "asia-southeast1", "asia-east1", "asia-northeast1"
                ));
            }

            for (String location : locations) {
                try {
                    RecommenderName recommenderName = RecommenderName.of(gcpProjectId, location,
                            "google.compute.commitment.UsageCommitmentRecommender");

                    log.info("📊 Querying CUD recommender for location: {}", location);

                    RecommenderClient.ListRecommendationsPagedResponse response =
                            client.listRecommendations(recommenderName);

                    for (Recommendation rec : response.iterateAll()) {
                        log.info("Processing CUD recommendation in {}: {}", location, rec.getName());

                        GcpOptimizationRecommendation dto = mapToCudRecommendationDto(rec);

                        if (dto.getMonthlySavings() > 0) {
                            dto.setLocation(location); // Set location based on query

                            // ✅ ENHANCED DEDUPLICATION: Create unique key based on actual content
                            String uniqueKey = String.format("%s|%s|%.2f",
                                    dto.getCurrentMachineType(),  // e.g., "3-year General-purpose E2 Memory"
                                    dto.getLocation(),             // e.g., "us-east1"
                                    dto.getMonthlySavings()        // e.g., 96.09
                            );

                            if (!uniqueKeys.contains(uniqueKey)) {
                                uniqueKeys.add(uniqueKey);
                                recommendations.add(dto);

                                log.info("✅ Added CUD: {} with savings ₹{} in {}",
                                        dto.getCurrentMachineType(),
                                        String.format("%.2f", dto.getMonthlySavings()),
                                        location);
                            } else {
                                log.debug("⏭️ Skipped duplicate CUD: {} in {}",
                                        dto.getCurrentMachineType(), location);
                            }
                        }
                    }
                } catch (Exception locEx) {
                    log.warn("Failed to query CUD recommender for location {}: {}",
                            location, locEx.getMessage());
                }
            }

            log.info("✅ Found {} unique CUD recommendations", recommendations.size());

        } catch (Exception e) {
            log.error("❌ Failed to fetch CUD recommendations for project {}:", gcpProjectId, e);
        }

        return recommendations;
    }


    private CompletableFuture<List<GcpOptimizationRecommendation>> getProjectLevelCudRecommendations(String gcpProjectId) {
        List<String> locations = Arrays.asList("global", "us-east1", "us-central1", "us-west1", "europe-west1"); // Add more relevant regions if needed

        List<CompletableFuture<List<GcpOptimizationRecommendation>>> futures = locations.stream()
                .map(location -> getRecommendationsForRecommender(
                        gcpProjectId,
                        "google.compute.commitment.UsageCommitmentRecommender",
                        location,
                        this::mapToCudRecommendationDto,
                        true)) // Project scope
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(List::stream)
                        .collect(Collectors.toList()));
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
                log.warn("Recommender client not available for {}. Skipping recommendations for {}.",
                        identifier, recommenderId);
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
                    log.info("Sample recommendation - Name: {}, Description: {}, State: {}",
                            firstRec.getName(),
                            firstRec.getDescription(),
                            firstRec.getStateInfo().getState());
                }

                return recommendations.stream()
                        .map(mapper)
                        .collect(Collectors.toList());

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
                InstancesListResponse instances = clientOpt.get().instances().list(gcpProjectId).execute();

                if (instances.getItems() == null) {
                    log.info("No Cloud SQL instances found for project {}.", gcpProjectId);
                    return List.of();
                }

                for (DatabaseInstance instance : instances.getItems()) {
                    List<BackupRun> backups = clientOpt.get().backupRuns()
                            .list(gcpProjectId, instance.getName())
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
                                        log.warn("Failed to parse backup window start time: {}",
                                                backup.getWindowStartTime(), ex);
                                        return false;
                                    }
                                })
                                .map(backup -> new GcpWasteItem(
                                        backup.getId().toString(),
                                        "Old Cloud SQL Backup",
                                        instance.getRegion(),
                                        0.01 // Nominal cost, actual cost depends on size/duration
                                ))
                                .forEach(wasteItems::add);
                    }
                }
                log.info("Found {} old Cloud SQL backups for project {}.", wasteItems.size(), gcpProjectId);
                return wasteItems;
            } catch (Exception e) {
                log.error("Failed to list Cloud SQL backups for project {}:", gcpProjectId, e);
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
                        .map(disk -> new GcpWasteItem(
                                disk.getName(),
                                "Unattached Persistent Disk",
                                disk.getZone().substring(disk.getZone().lastIndexOf('/') + 1), // Extract zone name
                                calculateDiskCost(disk.getSizeGb()) // Estimate cost
                        ))
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
                        // Simple check: Look for deprecated images without families (might miss some)
                        .filter(image -> (image.getFamily() == null || image.getFamily().isEmpty())
                                && image.getDeprecated() != null && "DEPRECATED".equals(image.getDeprecated().getState()))
                        .map(image -> new GcpWasteItem(
                                image.getName(),
                                "Unused Custom Image",
                                "global",
                                0.05 * image.getDiskSizeGb() // Estimate storage cost ($0.05/GB/month)
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
            log.info("Checking for unused firewall rules for project: {}", gcpProjectId);
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
        } else if (overviewFields.containsKey("resource")) {
            resourceName = overviewFields.get("resource").getStringValue();
            resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }

        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            // Cost projection is negative for savings
            Money costMoney = rec.getPrimaryImpact().getCostProjection().getCost();
            double costImpact = costMoney.getUnits() + (costMoney.getNanos() / 1_000_000_000.0);
            if (costImpact < 0) {
                 monthlySavings = Math.abs(costImpact);
            }
        }
        
        // TODO: Apply INR conversion if needed
        // final double USD_TO_INR_RATE = 83.0;
        // monthlySavings = monthlySavings * USD_TO_INR_RATE;

        return new GcpWasteItem(resourceName, wasteType, rec.getName(), monthlySavings);
    }

    private GcpOptimizationRecommendation mapToRightsizingDto(Recommendation rec) {
        String resourceName = extractResourceName(rec);
        String currentMachineType = "N/A";
        String recommendedMachineType = "N/A";

        // Extract machine types from description
        if (rec.getDescription().contains(" to ")) {
            String[] parts = rec.getDescription().split(" to ");
            // Example: "Change machine type from n1-standard-4 to n1-standard-2"
            currentMachineType = parts[0].substring(parts[0].lastIndexOf(" ") + 1).trim();
            recommendedMachineType = parts[1].trim();
        }

        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            // Cost projection is negative for savings
            Money costMoney = rec.getPrimaryImpact().getCostProjection().getCost();
            double costImpact = costMoney.getUnits() + (costMoney.getNanos() / 1_000_000_000.0);
            
            if (costImpact < 0) {
                monthlySavings = Math.abs(costImpact); // Savings are positive
            } else {
                monthlySavings = -costImpact; // Cost increase is negative
            }
        }
        
        // TODO: Apply INR conversion if needed
        // final double USD_TO_INR_RATE = 83.0;
        // monthlySavings = monthlySavings * USD_TO_INR_RATE;


        String location = extractLocation(rec.getName());
        String recommendationType = monthlySavings >= 0 ? "COST_SAVINGS" : "PERFORMANCE_IMPROVEMENT";
        String description = rec.getDescription();

        return new GcpOptimizationRecommendation(
                resourceName,
                currentMachineType,
                recommendedMachineType,
                monthlySavings,
                "Compute Engine", // Assuming this mapper is for Compute Engine
                location,
                rec.getName(),
                recommendationType,
                description);
    }

    /**
     * ✅ IMPROVEMENT (Point 1A):
     * Refactored to correctly interpret cost projection from Recommender API.
     * Negative cost projection = savings (positive monthlySavings).
     * Positive cost projection = cost increase (negative monthlySavings).
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

        // ✅ Extract CURRENT tier from description
        String currentTier = extractCurrentTierFromDescription(description);
        log.info("Current tier: {}", currentTier);

        // Extract recommended tier
        String recommendedTier = extractRecommendedTierFromDescription(description);
        log.info("Recommended tier: {}", recommendedTier);

        // ✅ IMPROVEMENT (Point 1A): Get cost projection from Recommender API (USD)
        double monthlySavingsUsd = 0.0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            Money costMoney = rec.getPrimaryImpact().getCostProjection().getCost();
            double costImpactUsd = costMoney.getUnits() + (costMoney.getNanos() / 1_000_000_000.0);

            if (costImpactUsd < 0) {
                // Negative costImpact from API means savings
                monthlySavingsUsd = Math.abs(costImpactUsd);
                log.info("Recommender API savings (USD): ${}", String.format("%.2f", monthlySavingsUsd));
            } else if (costImpactUsd > 0 && isUnderprovisioned) {
                // Positive costImpact for underprovisioned means cost increase
                monthlySavingsUsd = -costImpactUsd; // Store as negative
                log.info("Recommender API cost increase (USD): ${}", String.format("%.2f", costImpactUsd));
            }
            // If costImpactUsd is 0, monthlySavingsUsd remains 0.0
        }

        // Apply INR conversion immediately if value exists
        final double USD_TO_INR_RATE = 83.0;
        double monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE;


        String location = extractLocation(rec.getName());

        return new GcpOptimizationRecommendation(
                resourceName,
                currentTier,
                recommendedTier,
                monthlySavingsInr,  // Store INR value directly
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
            log.warn("Description is null or empty");
            return "Unknown";
        }

        log.debug("Attempting to extract instance name from: {}", description);

        try {
            // Pattern 1: "Instance: basic-mysql has had..." (WITH COLON - MOST COMMON)
            Pattern pattern1 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*[a-z0-9])\\s+has\\s+had", Pattern.CASE_INSENSITIVE);
            Matcher matcher1 = pattern1.matcher(description);

            if (matcher1.find()) {
                String instanceName = matcher1.group(1);
                log.debug("✓ Successfully extracted instance name '{}' (pattern 1)", instanceName);
                return instanceName;
            }

            // Pattern 2: "Instance: basic-mysql may" (WITH COLON)
            Pattern pattern2 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*[a-z0-9])\\s+may", Pattern.CASE_INSENSITIVE);
            Matcher matcher2 = pattern2.matcher(description);

            if (matcher2.find()) {
                String instanceName = matcher2.group(1);
                log.debug("✓ Successfully extracted instance name '{}' (pattern 2)", instanceName);
                return instanceName;
            }

            // Pattern 3: "Instance basic-mysql has had..." (WITHOUT COLON - fallback)
            Pattern pattern3 = Pattern.compile("Instance\\s+([a-z0-9][-a-z0-9]*[a-z0-9])\\s+has\\s+had", Pattern.CASE_INSENSITIVE);
            Matcher matcher3 = pattern3.matcher(description);

            if (matcher3.find()) {
                String instanceName = matcher3.group(1);
                log.debug("✓ Successfully extracted instance name '{}' (pattern 3)", instanceName);
                return instanceName;
            }

            // Pattern 4: Just "Instance: <name>" (broadest fallback with colon)
            Pattern pattern4 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*)", Pattern.CASE_INSENSITIVE);
            Matcher matcher4 = pattern4.matcher(description);

            if (matcher4.find()) {
                String instanceName = matcher4.group(1);
                // Filter out common English words that might be caught
                if (!instanceName.matches("(?i)(has|had|may|the|and|with|this|perform|better)")) {
                    log.debug("✓ Successfully extracted instance name '{}' (pattern 4)", instanceName);
                    return instanceName;
                }
            }

        } catch (Exception e) {
            log.error("ERROR while extracting instance name: {}", e.getMessage(), e);
        }

        log.warn("✗ FAILED to extract instance name from description: {}", description);
        return "Unknown";
    }

    /**
     * Extract recommended machine type from description
     * Pattern: "1 (+0) vCPUs and 3.75 (+3.15) GB" -> db-custom-1-3840
     */
    private String extractRecommendedTierFromDescription(String description) {
        if (description == null || description.isEmpty()) {
            return "Unknown";
        }

        try {
            // Pattern: "CUSTOM machine type with the following resources: 1 (+0) vCPUs and 3.75 (+3.15) GB memory"
            // or "1 vCPUs and 3.75 GB memory"
            Pattern pattern = Pattern.compile("(\\d+)\\s*(?:\\([^)]*\\))?\\s*vCPUs?\\s+and\\s+([\\d.]+)\\s*(?:\\([^)]*\\))?\\s*GB", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(description);

            if (matcher.find()) {
                int vcpus = Integer.parseInt(matcher.group(1));
                double memoryGb = Double.parseDouble(matcher.group(2));
                int memoryMb = (int)(memoryGb * 1024);

                String tier = String.format("db-custom-%d-%d", vcpus, memoryMb);
                log.info("✓ Constructed recommended tier '{}' from description ({} vCPUs, {} GB)", tier, vcpus, memoryGb);
                return tier;
            }
        } catch (Exception e) {
            log.warn("Failed to parse machine type from description: {}", e.getMessage());
        }

        log.warn("✗ Could not extract recommended tier from description: {}", description);
        return "Unknown";
    }

    /**
     * Extract resource name from recommendation using multiple strategies
     *
     * ✅ IMPROVEMENT (Point 3): Prioritize structured fields, fall back to Regex.
     */
    private String extractResourceName(Recommendation rec) {
        var fieldsMap = rec.getContent().getOverview().getFieldsMap();

        // ✅ Method 1: Direct resourceName field (HIGHEST PRIORITY)
        if (fieldsMap.containsKey("resourceName")) {
            String resourceName = fieldsMap.get("resourceName").getStringValue();
            if (!isUuid(resourceName)) {
                log.debug("Using resourceName from fields: {}", resourceName);
                return resourceName;
            } else {
                log.debug("Skipping UUID resourceName: {}", resourceName);
            }
        }

        // ✅ Method 2: Resource field (full path)
        if (fieldsMap.containsKey("resource")) {
            String fullPath = fieldsMap.get("resource").getStringValue();
            String extracted = fullPath.substring(fullPath.lastIndexOf('/') + 1);
            if (!isUuid(extracted)) {
                log.debug("Extracted from resource path: {}", extracted);
                return extracted;
            } else {
                log.debug("Skipping UUID from resource path: {}", extracted);
            }
        }

        // ✅ Method 3: Parse instance name from description (FALLBACK, especially for Cloud SQL)
        String description = rec.getDescription();
        if (description != null && description.toLowerCase().contains("instance")) {
            String extracted = extractInstanceNameFromDescription(description);
            if (!"Unknown".equals(extracted)) {
                log.debug("Extracted resource name '{}' from description as fallback", extracted);
                return extracted;
            }
        }

        log.error("Could not extract valid resource name from recommendation: {}", rec.getName());
        return "Unknown";
    }

    /**
     * Check if a string looks like a UUID
     */
    private boolean isUuid(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        // UUID pattern: 8-4-4-4-12 hex digits
        return str.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /**
     * Extract location from recommendation name
     */
    private String extractLocation(String recommendationName) {
        // Format: projects/{project}/locations/{location}/recommenders/{recommender}/recommendations/{id}
        // or billingAccounts/{billing}/locations/{location}/recommenders/{recommender}/recommendations/{id}
        try {
            Pattern pattern = Pattern.compile("/locations/([^/]+)/");
            Matcher matcher = pattern.matcher(recommendationName);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.debug("Failed to extract location from recommendation name: {}", recommendationName, e);
        }
        return "global"; // Default if pattern fails
    }

    private GcpOptimizationRecommendation mapToCudRecommendationDto(Recommendation rec) {
        String resourceName = "Committed Use Discount";
        String description = rec.getDescription();

        // ✅ ENHANCED LOGGING: Log the full cost projection
        log.info("🔍 Full CUD recommendation details:");
        log.info("  Name: {}", rec.getName());
        log.info("  Description: {}", description);

        if (rec.getPrimaryImpact().hasCostProjection()) {
            log.info("  Cost Projection Currency: {}",
                    rec.getPrimaryImpact().getCostProjection().getCost().getCurrencyCode());
            log.info("  Cost Units: {}",
                    rec.getPrimaryImpact().getCostProjection().getCost().getUnits());
            log.info("  Cost Nanos: {}",
                    rec.getPrimaryImpact().getCostProjection().getCost().getNanos());
        }

        // Extract ALL cost-related data from the recommendation
        double monthlySavingsInr = 0.0;
        String currencyCode = "USD"; // Default
        final double USD_TO_INR_RATE = 83.0; // Define conversion rate

        if (rec.getPrimaryImpact().hasCostProjection() &&
                rec.getPrimaryImpact().getCostProjection().hasCost()) {

            Money costMoney = rec.getPrimaryImpact().getCostProjection().getCost();

            // Get currency code
            if (costMoney.getCurrencyCode() != null && !costMoney.getCurrencyCode().isEmpty()) {
                currencyCode = costMoney.getCurrencyCode();
            }

            try {
                long units = costMoney.getUnits();
                int nanos = costMoney.getNanos();

                // Cost projection is negative for savings
                double totalCostImpact = units + (nanos / 1000000000.0);
                double monthlySavingsUsd = 0.0;
                
                // Savings = negative impact
                if (totalCostImpact < 0) {
                     monthlySavingsUsd = Math.abs(totalCostImpact);
                }

                // ✅ Convert USD to INR
                if ("USD".equals(currencyCode)) {
                    monthlySavingsInr = monthlySavingsUsd * USD_TO_INR_RATE; // USD to INR conversion
                    log.info("💱 Converted CUD savings from USD to INR: ${} → ₹{}",
                            String.format("%.2f", monthlySavingsUsd),
                            String.format("%.2f", monthlySavingsInr));
                } else {
                    // If currency is already INR or something else, use the absolute value
                    monthlySavingsInr = Math.abs(totalCostImpact);
                }


                log.info("💰 Final CUD savings: ₹{} (Original Currency: {})",
                        String.format("%.2f", monthlySavingsInr), currencyCode);

            } catch (Exception e) {
                log.error("Failed to parse CUD cost: {}", e.getMessage());
            }
        }

        // Extract commitment details from description
        String commitmentType = "Unknown"; // Default
        String resourceType = "";

        if (description != null) {
             if (description.contains("3 year")) {
                commitmentType = "3-year";
            } else if (description.contains("1 year")) {
                commitmentType = "1-year";
            }

            // Extract resource type like "General-purpose E2 Memory"
            Pattern resourcePattern = Pattern.compile("for\\s+(General-purpose\\s+[A-Z0-9]+\\s+[A-Za-z]+)", Pattern.CASE_INSENSITIVE);
            Matcher resourceMatcher = resourcePattern.matcher(description);
            if (resourceMatcher.find()) {
                resourceType = resourceMatcher.group(1);
                commitmentType = commitmentType + " " + resourceType; // Combine like "3-year General-purpose E2 Memory"
            } else if (commitmentType.equals("Unknown")) {
                 // Fallback if year term wasn't found either
                 commitmentType = description; // Use the description itself if parsing fails
            }
        }

        String location = extractLocation(rec.getName()); // Use the general location extractor

        return new GcpOptimizationRecommendation(
                resourceName,
                commitmentType, // This now holds the combined term and type
                description != null ? description : "Purchase CUD", // Recommended Machine Type or Action
                monthlySavingsInr, // Use the INR savings value
                "Commitment", // Service category
                location,
                rec.getName(),
                "COST_SAVINGS",
                description
        );
    }


    /**
     * Extract commitment amount from description, e.g., "for 1 GB"
     * @deprecated This logic might be too specific and is better handled within mapToCudRecommendationDto if needed.
     */
    @Deprecated
    private String extractCommitmentAmountFromDescription(String description) {
       // ... (kept for reference, but usage removed from mapToCudRecommendationDto)
         if (description == null) return "";
        Pattern pattern = Pattern.compile("for\\s+(\\d+(?:\\.\\d+)?)\\s+([A-Z]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(description);
        if (matcher.find()) {
            return String.format("%s %s", matcher.group(1), matcher.group(2));
        }
        return "N/A";
    }
    /**
     * ✅ HELPER: Extract location from recommendation name
     * @deprecated Replaced by the general `extractLocation` method.
     */
     @Deprecated
    private String extractLocationFromRecommendationName(String recommendationName) {
        return extractLocation(recommendationName);
    }

    private double calculateDiskCost(long sizeGb) {
        // Standard PD cost: $0.10 per GB/month (us-central1, estimate)
        // TODO: Apply INR conversion if needed
        return (sizeGb * 0.10);
    }

    private String extractCurrentTierFromDescription(String description) {
        if (description == null || description.isEmpty()) {
            return "Unknown";
        }

        try {
            // Pattern: "uses the db-f1-micro machine type" or "Instance: ... uses the ..."
            Pattern pattern1 = Pattern.compile("uses the ([a-z0-9-]+) machine type", Pattern.CASE_INSENSITIVE);
            Matcher matcher1 = pattern1.matcher(description);
            if (matcher1.find()) {
                String tier = matcher1.group(1);
                log.info("✅ Extracted current tier '{}' from description", tier);
                return tier;
            }

            log.warn("⚠️ Could not extract current tier from description: {}", description);
            return "Unknown";

        } catch (Exception e) {
            log.error("❌ Error extracting current tier from description '{}': {}", description, e.getMessage(), e);
            return "Unknown";
        }
    }

    /**
     * ✅ IMPROVEMENT (Point 1B, 1C, 4):
     * Enhance with historical cost data from BigQuery (for the new `last30DayCost` field).
     * If Recommender API did not provide savings, calculate savings/cost
     * from hardcoded prices as a fallback.
     *
     * ✅ FIX 2025-10-28 (v2): Apply INR conversion (x83) to the
     * fallback savings calculation to match the dashboard currency.
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
        
        // Conversion rate (USD to INR, matches CUD logic)
        final double USD_TO_INR_RATE = 83.0;

        for (GcpOptimizationRecommendation dto : results) {
            if (!"Cloud SQL".equals(dto.getService())) continue;

            String instanceName = dto.getResourceName();
            if ("Unknown".equals(instanceName) || isUuid(instanceName)) {
                log.warn("Skipping enhancement for invalid/UUID resource name: {}", instanceName);
                continue;
            }

            try {
                log.info("🔍 Fetching details for Cloud SQL instance: {}", instanceName);
                DatabaseInstance instance = sqlAdmin.instances()
                        .get(gcpProjectId, instanceName)
                        .execute();

                String currentTier = instance.getSettings().getTier();
                String region = instance.getRegion();

                // Update current tier if it wasn't extracted successfully earlier
                if ("Unknown".equals(dto.getCurrentMachineType()) || dto.getCurrentMachineType() == null) {
                    dto.setCurrentMachineType(currentTier);
                    log.info("Updated current tier for '{}' from instance details: {}", instanceName, currentTier);
                } else if (!dto.getCurrentMachineType().equals(currentTier)) {
                     log.warn("Mismatch between extracted tier ('{}') and actual tier ('{}') for instance {}",
                              dto.getCurrentMachineType(), currentTier, instanceName);
                     // Optionally update to the actual tier: dto.setCurrentMachineType(currentTier);
                }


                // ✅ IMPROVEMENT (Point 1C): Calculate *instance-specific* 30-day cost (INR)
                double historicalCostInr = calculateCloudSqlHistoricalCost(gcpProjectId, instanceName);

                // ✅ IMPROVEMENT (Point 1C): Set historical cost to the new DTO field
                if (historicalCostInr > 0) {
                    dto.setLast30DayCost(historicalCostInr);
                    log.info("💰 Instance '{}': Historical 30-day cost = ₹{}",
                            instanceName, String.format("%.2f", historicalCostInr));
                } else {
                     log.warn("Could not retrieve historical cost for instance '{}'. last30DayCost will be null.", instanceName);
                     dto.setLast30DayCost(null); // Explicitly set to null if no cost found
                }

                // Calculate estimated monthly prices in USD (used for descriptions and fallback)
                double currentPriceUsd = calculateCloudSqlPrice(currentTier, region);
                // Ensure recommended type is not null or unknown before calculating price
                String recommendedTier = dto.getRecommendedMachineType();
                double recommendedPriceUsd = ("Unknown".equals(recommendedTier) || recommendedTier == null)
                                            ? 0.0
                                            : calculateCloudSqlPrice(recommendedTier, region);


                // ✅ IMPROVEMENT (Point 1B & 4): Fallback savings calculation (INR)
                // If Recommender API gave no savings (INR value is 0.0), calculate from prices.
                if (dto.getMonthlySavings() == 0.0 && currentPriceUsd > 0 && recommendedPriceUsd > 0) {
                    if (dto.getRecommendationType().equals("COST_SAVINGS")) {
                        double calculatedSavingsUsd = currentPriceUsd - recommendedPriceUsd;
                        // Only set if savings are positive
                        if (calculatedSavingsUsd > 0) {
                             double calculatedSavingsInr = calculatedSavingsUsd * USD_TO_INR_RATE; // Convert to INR
                             dto.setMonthlySavings(calculatedSavingsInr);
                             log.info("Populated 'monthlySavings' from price diff: ₹{} ({} -> {})",
                                    String.format("%.2f", calculatedSavingsInr), currentTier, recommendedTier);
                        } else {
                             log.warn("Calculated price difference for COST_SAVINGS recommendation is not positive (Current: ${}, Recommended: ${}). Keeping savings as 0.",
                                       String.format("%.2f", currentPriceUsd), String.format("%.2f", recommendedPriceUsd));
                        }
                    } else if (dto.getRecommendationType().equals("PERFORMANCE_IMPROVEMENT")) {
                        double calculatedCostIncreaseUsd = recommendedPriceUsd - currentPriceUsd;
                         // Only set if cost increase is positive
                         if (calculatedCostIncreaseUsd > 0) {
                            double calculatedCostIncreaseInr = calculatedCostIncreaseUsd * USD_TO_INR_RATE; // Convert to INR
                            dto.setMonthlySavings(-calculatedCostIncreaseInr); // Store as negative INR
                             log.info("Populated 'monthlySavings' (cost increase) from price diff: ₹{} ({} -> {})",
                                    String.format("%.2f", -calculatedCostIncreaseInr), currentTier, recommendedTier);
                         } else {
                              log.warn("Calculated price difference for PERFORMANCE_IMPROVEMENT recommendation is not positive (Current: ${}, Recommended: ${}). Keeping cost increase as 0.",
                                       String.format("%.2f", currentPriceUsd), String.format("%.2f", recommendedPriceUsd));
                         }
                    }
                }

                // Build readable descriptions using the calculated USD prices
                dto.setCurrentMachineType(buildReadableTierDescription(currentTier, currentPriceUsd));
                dto.setRecommendedMachineType(buildReadableTierDescription(recommendedTier, recommendedPriceUsd));

                log.info("✅ Enhanced recommendation for '{}': Savings: ₹{}/mo, Last 30d Cost: ₹{}/mo",
                        instanceName,
                        String.format("%.2f", dto.getMonthlySavings()),
                        dto.getLast30DayCost() != null ? String.format("%.2f", dto.getLast30DayCost()) : "N/A");

            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                if (e.getStatusCode() == 404) {
                    log.error("Cloud SQL instance '{}' not found in project {}. It may have been deleted.",
                            instanceName, gcpProjectId);
                } else {
                    log.error("API error enhancing Cloud SQL instance '{}' in project {}: {} - {}",
                            instanceName, gcpProjectId, e.getStatusCode(), e.getMessage());
                }
                 // Set cost to null if instance details fail
                 dto.setLast30DayCost(null);
                 dto.setCurrentMachineType(buildReadableTierDescription(dto.getCurrentMachineType(), 0.0)); // Clear price if error
                 dto.setRecommendedMachineType(buildReadableTierDescription(dto.getRecommendedMachineType(), 0.0));
            } catch (Exception e) {
                log.error("❌ Failed to enhance instance '{}' in project {}: {}", instanceName, gcpProjectId, e.getMessage(), e);
                 // Set cost to null if instance details fail
                 dto.setLast30DayCost(null);
                 dto.setCurrentMachineType(buildReadableTierDescription(dto.getCurrentMachineType(), 0.0)); // Clear price if error
                 dto.setRecommendedMachineType(buildReadableTierDescription(dto.getRecommendedMachineType(), 0.0));
            }
        }
    }


    /**
     * ✅ BUG FIX: Calculate historical 30-day cost *for a specific instance* (in INR)
     * from BigQuery billing export.
     * * ✅ FIX 2025-10-28: Changed query to use 'labels' instead of 'resource.name'.
     */
    private double calculateCloudSqlHistoricalCost(String gcpProjectId, String instanceName) {
        try {
            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty()) {
                log.warn("BigQuery client not available for project {}", gcpProjectId);
                return 0.0;
            }

            BigQuery bigquery = bqOpt.get();
            Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);

            if (tableNameOpt.isEmpty()) {
                log.warn("Billing table not found for project {}. Cannot calculate historical cost.", gcpProjectId);
                return 0.0;
            }

            LocalDate today = LocalDate.now();
            LocalDate startDate = today.minusDays(30);

            // ✅ FIX: Use the label key for Cloud SQL instance name
            String sqlInstanceLabelKey = "cloudsql.googleapis.com/instance_name";

            String query = String.format(
                    "SELECT SUM(cost) as total_cost, currency " + // Select currency as well
                            "FROM `%s` " +
                            "WHERE DATE(usage_start_time) >= '%s' " +
                            "  AND DATE(usage_start_time) <= '%s' " +
                            "  AND service.description = 'Cloud SQL' " +
                            "  AND project.id = '%s' " +
                            // ✅ FIX: Query 'labels' array (UNNEST) instead of 'resource.name'
                            "  AND EXISTS (SELECT 1 FROM UNNEST(labels) AS l WHERE l.key = '%s' AND l.value = '%s') " +
                            "  AND cost > 0 " +
                            "GROUP BY currency", // Group by currency
                    tableNameOpt.get(),
                    startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    gcpProjectId,
                    sqlInstanceLabelKey, // Pass the label key
                    instanceName       // Pass the simple instance name (e.g., "basic-mysql")
            );

            log.info("📊 Querying 30-day cost for Cloud SQL instance: {}", instanceName);
            log.debug("BigQuery SQL: {}", query);


            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());

            double totalCostInr = 0.0;
            final double USD_TO_INR_RATE = 83.0; // Keep consistent

             if (results.getTotalRows() == 0) {
                 log.warn("⚠️ No 30-day Cloud SQL cost data found for instance: {} in project {}", instanceName, gcpProjectId);
                 return 0.0;
             }

            for (FieldValueList row : results.iterateAll()) {
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
                        log.warn("  Unsupported currency '{}' found in billing data for instance {}. Cost: {}",
                                 currency, instanceName, costValue);
                        // Decide how to handle other currencies - for now, add directly assuming it might be INR or local
                        totalCostInr += costValue;
                    }
                }
            }


            if (totalCostInr > 0) {
                 log.info("✅ Total Cloud SQL 30-day cost for '{}': ₹{}",
                            instanceName, String.format("%.2f", totalCostInr));
                 return totalCostInr;
            } else {
                 // This case should ideally be covered by getTotalRows == 0, but added for safety
                  log.warn("⚠️ Calculated total 30-day Cloud SQL cost for instance '{}' is zero or negative.", instanceName);
                  return 0.0;
            }


        } catch (BigQueryException bqEx) {
             log.error("❌ BigQuery Error querying Cloud SQL cost for '{}' in project {}: {} - {}",
                      instanceName, gcpProjectId, bqEx.getCode(), bqEx.getMessage());
             if (bqEx.getError() != null) {
                  log.error("   Reason: {}, Location: {}", bqEx.getError().getReason(), bqEx.getError().getLocation());
             }
             return 0.0;
        }
        catch (Exception e) {
            log.error("❌ Unexpected Error querying Cloud SQL cost for '{}' in project {}: {}", instanceName, gcpProjectId, e.getMessage(), e);
            return 0.0;
        }
    }
    /**
     * ✅ Helper: Get billing table name
     */
    private Optional<String> getBillingTableName(BigQuery bigquery, String gcpProjectId) {
        // Simple caching could be added here if this becomes a performance bottleneck
        try {
            // Try searching in the project first
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
            
            // Fallback: If not in the project, it might be in a central billing project
            // This requires knowing the billing project ID, which isn't directly available here.
            // For now, log a warning and return empty. Consider passing billing project ID if available.
            log.warn("Could not find billing table matching 'gcp_billing_export_v1_...' in project {}", gcpProjectId);

        } catch (Exception e) {
            log.error("Failed to find billing table in project {}: {}", gcpProjectId, e.getMessage());
        }
        return Optional.empty();
    }

}