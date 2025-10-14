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
     */
    private void enhanceCloudSqlRecommendations(String gcpProjectId, List<GcpOptimizationRecommendation> results) {
        Optional<SQLAdmin> clientOpt = gcpClientProvider.getSqlAdminClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.warn("SQLAdmin client not available for project {}. Skipping instance details enhancement.", gcpProjectId);
            return;
        }

        for (GcpOptimizationRecommendation dto : results) {
            if ("Cloud SQL".equals(dto.getService())) {
                // Skip if resource name is Unknown or looks like a UUID
                if ("Unknown".equals(dto.getResourceName()) || isUuid(dto.getResourceName())) {
                    log.warn("Skipping enhancement for invalid/UUID resource name: {}", dto.getResourceName());
                    continue;
                }

                try {
                    log.info("Fetching Cloud SQL instance details for: {}", dto.getResourceName());
                    DatabaseInstance instance = clientOpt.get().instances()
                            .get(gcpProjectId, dto.getResourceName())
                            .execute();

                    String currentTier = instance.getSettings().getTier();
                    String region = instance.getRegion();

                    // Calculate current price
                    double currentPrice = calculateCloudSqlPrice(currentTier, region);

                    dto.setCurrentMachineType(buildReadableTierDescription(currentTier, currentPrice));
                    log.info("Enhanced recommendation for {} - current tier: {} (${}/mo)",
                            dto.getResourceName(), currentTier, String.format("%.2f", currentPrice));

                    // Format recommended tier if already extracted
                    if (dto.getRecommendedMachineType() != null &&
                            !"Unknown".equals(dto.getRecommendedMachineType()) &&
                            !dto.getRecommendedMachineType().contains("$")) {

                        // Calculate recommended price
                        double recommendedPrice = calculateCloudSqlPrice(dto.getRecommendedMachineType(), region);
                        dto.setRecommendedMachineType(buildReadableTierDescription(
                                dto.getRecommendedMachineType(), recommendedPrice));

                        // Recalculate actual savings based on pricing
                        double actualSavings = currentPrice - recommendedPrice;
                        dto.setMonthlySavings(actualSavings);
                        log.info("Recalculated savings: current ${}/mo -> recommended ${}/mo = ${}/mo savings",
                                String.format("%.2f", currentPrice),
                                String.format("%.2f", recommendedPrice),
                                String.format("%.2f", actualSavings));
                    } else if ("Unknown".equals(dto.getRecommendedMachineType())) {
                        // Infer recommended tier if not extracted
                        String recommendedTier = inferRecommendedTier(currentTier, dto.getRecommendationType());
                        double recommendedPrice = calculateCloudSqlPrice(recommendedTier, region);
                        dto.setRecommendedMachineType(buildReadableTierDescription(recommendedTier, recommendedPrice));
                    }

                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                    if (e.getStatusCode() == 404) {
                        log.error("Cloud SQL instance '{}' not found in project {}. It may have been deleted or renamed.",
                                dto.getResourceName(), gcpProjectId);
                    } else {
                        log.error("API error fetching Cloud SQL instance details for {} in project {}: {} - {}",
                                dto.getResourceName(), gcpProjectId, e.getStatusCode(), e.getMessage());
                    }
                } catch (Exception e) {
                    log.error("Failed to fetch Cloud SQL instance details for {} in project {}: {}",
                            dto.getResourceName(), gcpProjectId, e.getMessage());
                }
            }
        }
    }

    /**
     * Calculate Cloud SQL monthly price based on tier
     * Prices from https://cloud.google.com/sql/pricing (2025)
     */
    private double calculateCloudSqlPrice(String tier, String region) {
        if (tier == null || "Unknown".equals(tier)) {
            return 0.0;
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
            specs = "N1 standard";
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
                    int newMemMb = Math.max(3840, currentMemMb / 2);
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
                .filter(r -> r.getMonthlySavings() >= 0)
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
                .filter(r -> r.getMonthlySavings() >= 0)
                .mapToDouble(GcpOptimizationRecommendation::getMonthlySavings)
                .sum();
        long criticalAlerts = rightsizing.size();

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
                    false));
        }

        futures.add(getProjectLevelCudRecommendations(gcpProjectId));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<GcpOptimizationRecommendation> allRecommendations = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .distinct()
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
                            dto.setLocation(location);

                            // ✅ ENHANCED DEDUPLICATION: Create unique key based on actual content
                            String uniqueKey = String.format("%s|%s|%.2f",
                                    dto.getCurrentMachineType(),  // e.g., "3-year General-purpose E2 Memory"
                                    dto.getLocation(),             // e.g., "us-east1"
                                    dto.getMonthlySavings()        // e.g., 1.16
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
        List<String> locations = Arrays.asList("global", "us-east1", "us-central1", "us-west1", "europe-west1");

        List<CompletableFuture<List<GcpOptimizationRecommendation>>> futures = locations.stream()
                .map(location -> getRecommendationsForRecommender(
                        gcpProjectId,
                        "google.compute.commitment.UsageCommitmentRecommender",
                        location,
                        this::mapToCudRecommendationDto,
                        true))
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
                    String projectName = project.getName();
                    if (projectName.contains("/")) {
                        return projectName.split("/")[1];
                    }
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
                                        0.01
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
                                disk.getZone().substring(disk.getZone().lastIndexOf('/') + 1),
                                calculateDiskCost(disk.getSizeGb())
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
                        .filter(image -> image.getFamily() == null || image.getFamily().isEmpty()
                                && image.getDeprecated().getState().equals("DEPRECATED"))
                        .map(image -> new GcpWasteItem(
                                image.getName(),
                                "Unused Custom Image",
                                "global",
                                0.05 * image.getDiskSizeGb()
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
                        .filter(Firewall::getDisabled)
                        .map(firewall -> new GcpWasteItem(
                                firewall.getName(),
                                "Unused Firewall Rule",
                                "global",
                                0.0
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
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue();
            resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }

        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }

        return new GcpWasteItem(resourceName, wasteType, rec.getName(), monthlySavings);
    }

    private GcpOptimizationRecommendation mapToRightsizingDto(Recommendation rec) {
        String resourceName = extractResourceName(rec);
        String currentMachineType = "N/A";
        String recommendedMachineType = "N/A";

        if (rec.getDescription().contains(" to ")) {
            String[] parts = rec.getDescription().split(" to ");
            currentMachineType = parts[0].replace("Change machine type from ", "");
            recommendedMachineType = parts[1];
        }

        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }

        String location = extractLocation(rec.getName());
        String recommendationType = monthlySavings >= 0 ? "COST_SAVINGS" : "PERFORMANCE_IMPROVEMENT";
        String description = rec.getDescription();

        return new GcpOptimizationRecommendation(
                resourceName,
                currentMachineType,
                recommendedMachineType,
                monthlySavings,
                "Compute Engine",
                location,
                rec.getName(),
                recommendationType,
                description);
    }

    private GcpOptimizationRecommendation mapToSqlRightsizingDto(Recommendation rec) {
        String description = rec.getDescription();
        log.info("📊 Processing Cloud SQL Recommendation: {}", rec.getName());
        log.info("Description: {}", description);

        // Extract instance name
        String resourceName = extractInstanceNameFromDescription(description);
        if ("Unknown".equals(resourceName)) {
            resourceName = extractResourceName(rec);
        }
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

        // Get cost projection (will be replaced with historical cost)
        double costImpact = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            double costNanos = rec.getPrimaryImpact().getCostProjection().getCost().getNanos();
            double costUnits = rec.getPrimaryImpact().getCostProjection().getCost().getUnits();
            costImpact = Math.abs(costUnits + (costNanos / 1_000_000_000.0));
        }

        String location = extractLocation(rec.getName());

        return new GcpOptimizationRecommendation(
                resourceName,
                currentTier,
                recommendedTier,
                costImpact,  // Will be replaced with historical 30-day cost
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

        log.info("Attempting to extract instance name from: {}", description);

        try {
            // Pattern 1: "Instance: basic-mysql has had..." (WITH COLON - MOST COMMON)
            Pattern pattern1 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*[a-z0-9])\\s+has\\s+had", Pattern.CASE_INSENSITIVE);
            Matcher matcher1 = pattern1.matcher(description);

            if (matcher1.find()) {
                String instanceName = matcher1.group(1);
                log.info("✓ Successfully extracted instance name '{}' from description (pattern 1 - with colon)", instanceName);
                return instanceName;
            }

            // Pattern 2: "Instance: basic-mysql may" (WITH COLON)
            Pattern pattern2 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*[a-z0-9])\\s+may", Pattern.CASE_INSENSITIVE);
            Matcher matcher2 = pattern2.matcher(description);

            if (matcher2.find()) {
                String instanceName = matcher2.group(1);
                log.info("✓ Successfully extracted instance name '{}' from description (pattern 2 - with colon)", instanceName);
                return instanceName;
            }

            // Pattern 3: "Instance basic-mysql has had..." (WITHOUT COLON - fallback)
            Pattern pattern3 = Pattern.compile("Instance\\s+([a-z0-9][-a-z0-9]*[a-z0-9])\\s+has\\s+had", Pattern.CASE_INSENSITIVE);
            Matcher matcher3 = pattern3.matcher(description);

            if (matcher3.find()) {
                String instanceName = matcher3.group(1);
                log.info("✓ Successfully extracted instance name '{}' from description (pattern 3 - without colon)", instanceName);
                return instanceName;
            }

            // Pattern 4: Just "Instance: <name>" (broadest fallback with colon)
            Pattern pattern4 = Pattern.compile("Instance:\\s*([a-z0-9][-a-z0-9]*)", Pattern.CASE_INSENSITIVE);
            Matcher matcher4 = pattern4.matcher(description);

            if (matcher4.find()) {
                String instanceName = matcher4.group(1);
                // Filter out common English words
                if (!instanceName.matches("(?i)(has|had|may|the|and|with|this)")) {
                    log.info("✓ Successfully extracted instance name '{}' from description (pattern 4 - broadest)", instanceName);
                    return instanceName;
                }
            }

        } catch (Exception e) {
            log.error("ERROR while extracting instance name: {}", e.getMessage(), e);
        }

        log.error("✗ FAILED to extract instance name from description: {}", description);
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

        log.warn("✗ Could not extract recommended tier from description");
        return "Unknown";
    }

    /**
     * Extract resource name from recommendation using multiple strategies
     */
    private String extractResourceName(Recommendation rec) {
        // Method 1: Parse instance name from description (HIGHEST PRIORITY for Cloud SQL)
        String description = rec.getDescription();
        if (description != null && description.toLowerCase().contains("instance")) {
            String extracted = extractInstanceNameFromDescription(description);
            if (!"Unknown".equals(extracted)) {
                log.debug("Extracted resource name '{}' from description", extracted);
                return extracted;
            }
        }

        // Method 2: Direct resourceName field (but skip if it's a UUID)
        var fieldsMap = rec.getContent().getOverview().getFieldsMap();
        if (fieldsMap.containsKey("resourceName")) {
            String resourceName = fieldsMap.get("resourceName").getStringValue();
            if (!isUuid(resourceName)) {
                log.debug("Using resourceName from fields: {}", resourceName);
                return resourceName;
            } else {
                log.debug("Skipping UUID resourceName: {}", resourceName);
            }
        }

        // Method 3: Resource field (full path) - but skip if it's a UUID
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
        try {
            String[] parts = recommendationName.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("locations".equals(parts[i])) {
                    return parts[i + 1];
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract location from recommendation name", e);
        }
        return "global";
    }

    private GcpOptimizationRecommendation mapToCudRecommendationDto(Recommendation rec) {
        String resourceName = "Committed Use Discount";
        String description = rec.getDescription();

        // ✅ ENHANCED LOGGING: Log the full cost projection
        log.info("🔍 Full recommendation details:");
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
        double monthlySavings = 0.0;
        String currencyCode = "USD"; // Default

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

                // Calculate total cost
                double totalCost = units + (nanos / 1000000000.0);
                monthlySavings = Math.abs(totalCost);

                // ✅ Convert USD to INR if needed (1 USD ≈ 83 INR as of 2025)
                if ("USD".equals(currencyCode)) {
                    monthlySavings = monthlySavings * 83.0; // USD to INR conversion
                    log.info("💱 Converted from USD to INR: ${} → ₹{}",
                            String.format("%.2f", Math.abs(totalCost)),
                            String.format("%.2f", monthlySavings));
                }

                log.info("💰 Final CUD savings: ₹{} (Currency: {})",
                        String.format("%.2f", monthlySavings), currencyCode);

            } catch (Exception e) {
                log.error("Failed to parse cost: {}", e.getMessage());
            }
        }

        // Rest of your code...
        String commitmentType = "3-year";
        String resourceType = "";

        if (description != null) {
            if (description.contains("3-year") || description.contains("3 year")) {
                commitmentType = "3-year";
            } else if (description.contains("1-year") || description.contains("1 year")) {
                commitmentType = "1-year";
            }

            Pattern resourcePattern = Pattern.compile("(General-purpose [A-Z0-9]+ [A-Za-z]+)");
            Matcher resourceMatcher = resourcePattern.matcher(description);
            if (resourceMatcher.find()) {
                resourceType = resourceMatcher.group(1);
            }

            if (!resourceType.isEmpty()) {
                commitmentType = commitmentType + " " + resourceType;
            }
        }

        String location = extractLocationFromRecommendationName(rec.getName());

        return new GcpOptimizationRecommendation(
                resourceName,
                commitmentType,
                description != null ? description : "Purchase CUD",
                monthlySavings,
                "Commitment",
                location,
                rec.getName(),
                "COST_SAVINGS",
                description
        );
    }


    /**
     * Extract commitment amount from description, e.g., "for 1 GB"
     */
    private String extractCommitmentAmountFromDescription(String description) {
        if (description == null) return "";

        // Pattern: "for 1 GB" or "for X units"
        Pattern pattern = Pattern.compile("for\\s+(\\d+(?:\\.\\d+)?)\\s+([A-Z]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(description);
        if (matcher.find()) {
            return String.format("%s %s", matcher.group(1), matcher.group(2));
        }

        return "1 GB"; // Default for E2 Memory
    }
    /**
     * ✅ HELPER: Extract location from recommendation name
     */
    private String extractLocationFromRecommendationName(String recommendationName) {
        // Format: projects/{project}/locations/{location}/recommenders/{recommender}/recommendations/{id}
        try {
            Pattern pattern = Pattern.compile("/locations/([^/]+)/");
            Matcher matcher = pattern.matcher(recommendationName);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.debug("Could not extract location from: {}", recommendationName);
        }
        return "global";
    }

    private double calculateDiskCost(long sizeGb) {
        return (sizeGb * 0.10);
    }

    private String extractCurrentTierFromDescription(String description) {
        if (description == null || description.isEmpty()) {
            return "Unknown";
        }

        try {
            // Pattern: "uses the db-f1-micro machine type"
            Pattern pattern1 = Pattern.compile("uses the ([a-z0-9-]+) machine type", Pattern.CASE_INSENSITIVE);
            Matcher matcher1 = pattern1.matcher(description);
            if (matcher1.find()) {
                String tier = matcher1.group(1);
                log.info("✅ Extracted current tier: {}", tier);
                return tier;
            }

            log.warn("⚠️ Could not extract current tier from description");
            return "Unknown";

        } catch (Exception e) {
            log.error("❌ Error extracting current tier: {}", e.getMessage(), e);
            return "Unknown";
        }
    }

    /**
     * ✅ FIXED: Enhance with historical cost data from Cloud SQL API + BigQuery
     */
    private void enhanceCloudSqlRecommendationsWithHistoricalCost(
            String gcpProjectId,
            List<GcpOptimizationRecommendation> results) {

        Optional<SQLAdmin> clientOpt = gcpClientProvider.getSqlAdminClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.warn("SQLAdmin client not available");
            return;
        }

        SQLAdmin sqlAdmin = clientOpt.get();

        for (GcpOptimizationRecommendation dto : results) {
            if (!"Cloud SQL".equals(dto.getService())) continue;

            String instanceName = dto.getResourceName();

            try {
                log.info("🔍 Fetching details for Cloud SQL instance: {}", instanceName);
                DatabaseInstance instance = sqlAdmin.instances()
                        .get(gcpProjectId, instanceName)
                        .execute();

                String currentTier = instance.getSettings().getTier();
                String region = instance.getRegion();

                // Update current tier if not extracted
                if ("Unknown".equals(dto.getCurrentMachineType())) {
                    dto.setCurrentMachineType(currentTier);
                }

                // ✅ Calculate HISTORICAL 30-day cost from BigQuery billing data
                double historicalCost = calculateCloudSqlHistoricalCost(gcpProjectId, instanceName);

                // ✅ Set historical cost as the "monthly savings" field
                dto.setMonthlySavings(historicalCost);

                log.info("💰 Instance '{}': Historical 30-day cost = ${}",
                        instanceName, String.format("%.2f", historicalCost));

                // Build readable descriptions
                double currentPrice = calculateCloudSqlPrice(currentTier, region);
                double recommendedPrice = calculateCloudSqlPrice(dto.getRecommendedMachineType(), region);

                dto.setCurrentMachineType(buildReadableTierDescription(currentTier, currentPrice));
                dto.setRecommendedMachineType(
                        buildReadableTierDescription(dto.getRecommendedMachineType(), recommendedPrice));

                log.info("✅ Enhanced recommendation for '{}': ${}/mo",
                        instanceName, String.format("%.2f", historicalCost));

            } catch (Exception e) {
                log.error("❌ Failed to enhance instance '{}': {}", instanceName, e.getMessage(), e);
            }
        }
    }

    /**
     * ✅ NEW: Calculate historical 30-day cost from BigQuery billing export
     */
    private double calculateCloudSqlHistoricalCost(String gcpProjectId, String instanceName) {
        try {
            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty()) {
                log.warn("BigQuery client not available");
                return 0.0;
            }

            BigQuery bigquery = bqOpt.get();
            Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);

            if (tableNameOpt.isEmpty()) {
                log.warn("Billing table not found");
                return 0.0;
            }

            LocalDate today = LocalDate.now();
            LocalDate startDate = today.minusDays(30);

            String query = String.format(
                    "SELECT SUM(cost) as total_cost " +
                            "FROM `%s` " +
                            "WHERE DATE(usage_start_time) >= '%s' " +
                            "  AND DATE(usage_start_time) <= '%s' " +
                            "  AND service.description = 'Cloud SQL' " +
                            "  AND project.id = '%s' " +
                            "  AND cost > 0",
                    tableNameOpt.get(),
                    startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    gcpProjectId
            );

            log.info("📊 Querying Cloud SQL 30-day cost for project: {}", gcpProjectId);

            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());

            if (results.getTotalRows() > 0) {
                FieldValue costField = results.getValues().iterator().next().get("total_cost");

                if (!costField.isNull()) {
                    double totalCost = costField.getDoubleValue();
                    log.info("✅ Cloud SQL 30-day cost: ₹{} (project: {}, includes instance: {})",
                            String.format("%.2f", totalCost), gcpProjectId, instanceName);
                    return totalCost;
                }
            }

            log.warn("⚠️ No Cloud SQL cost data found for project: {}", gcpProjectId);
            return 0.0;

        } catch (Exception e) {
            log.error("❌ Error querying Cloud SQL cost for '{}': {}", instanceName, e.getMessage());
            return 0.0;
        }
    }
    /**
     * ✅ Helper: Get billing table name
     */
    private Optional<String> getBillingTableName(BigQuery bigquery, String gcpProjectId) {
        try {
            for (Dataset dataset : bigquery.listDatasets(gcpProjectId).iterateAll()) {
                for (Table table : bigquery.listTables(dataset.getDatasetId()).iterateAll()) {
                    if (table.getTableId().getTable().startsWith("gcp_billing_export_v1")) {
                        String fullName = String.format("%s.%s.%s",
                                table.getTableId().getProject(),
                                table.getTableId().getDataset(),
                                table.getTableId().getTable());
                        log.info("Found billing table: {}", fullName);
                        return Optional.of(fullName);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to find billing table: {}", e.getMessage());
        }
        return Optional.empty();
    }

}
