package com.xammer.cloud.service.gcp;

import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.BackupRun;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.api.services.sqladmin.model.InstancesListResponse;
import com.google.cloud.compute.v1.*;
import com.google.cloud.recommender.v1.Recommendation;
import com.google.cloud.recommender.v1.RecommenderClient;
import com.google.cloud.recommender.v1.RecommenderName;
import com.google.cloud.resourcemanager.v3.Project;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
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
        log.info("Fetching rightsizing recommendations for GCP project: {}", gcpProjectId);

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

        // Cloud SQL rightsizing - Query both overprovisioned and underprovisioned recommenders
        for (String location : locations) {
            if (!location.equals("global")) {
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

        // Enhance Cloud SQL recommendations with instance details
        Optional<SQLAdmin> clientOpt = gcpClientProvider.getSqlAdminClient(gcpProjectId);
        if (clientOpt.isPresent()) {
            for (GcpOptimizationRecommendation dto : results) {
                if ("Cloud SQL".equals(dto.getService())) {
                    try {
                        DatabaseInstance instance = clientOpt.get().instances().get(gcpProjectId, dto.getResourceName()).execute();
                        String currentTier = instance.getSettings().getTier();
                        String currentDetails = currentTier;
                        if (currentTier.startsWith("db-custom-")) {
                            String[] parts = currentTier.split("-");
                            if (parts.length == 4) {
                                String vcpus = parts[2];
                                double memoryGb = Integer.parseInt(parts[3]) / 1024.0;
                                currentDetails += " (" + vcpus + " vCPU, " + memoryGb + " GB)";
                            }
                        } else {
                            // For shared instances like db-f1-micro, add approximate specs
                            if ("db-f1-micro".equals(currentTier)) {
                                currentDetails += " (shared vCPU, 0.6 GB)";
                            } // Add more if needed
                        }
                        dto.setCurrentMachineType(currentDetails);
                    } catch (Exception e) {
                        log.error("Failed to fetch Cloud SQL instance details for {} in project {}: {}", dto.getResourceName(), gcpProjectId, e.getMessage());
                    }
                }
            }
        } else {
            log.warn("SQLAdmin client not available for project {}. Skipping instance details enhancement.", gcpProjectId);
        }

        log.info("Total rightsizing recommendations found: {}", results.size());
        return results;
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
        double rightsizingSavings = rightsizing.stream().mapToDouble(GcpOptimizationRecommendation::getMonthlySavings).sum();

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
                + rightsizing.stream().mapToDouble(GcpOptimizationRecommendation::getMonthlySavings).sum();
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

    public List<GcpOptimizationRecommendation> getCudRecommendationsSync(String gcpProjectId) {
        log.info("Fetching CUD recommendations synchronously for GCP project: {}", gcpProjectId);
        return getCudRecommendations(gcpProjectId).join();
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
        String resourceName = "N/A", currentMachineType = "N/A", recommendedMachineType = "N/A";

        if (rec.getDescription().contains(" to ")) {
            String[] parts = rec.getDescription().split(" to ");
            currentMachineType = parts[0].replace("Change machine type from ", "");
            recommendedMachineType = parts[1];
        }

        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }

        return new GcpOptimizationRecommendation(
                resourceName,
                currentMachineType,
                recommendedMachineType,
                monthlySavings,
                "Compute Engine");
    }

    private GcpOptimizationRecommendation mapToSqlRightsizingDto(Recommendation rec) {
        String description = rec.getDescription().toLowerCase();

        // Parse resource name from description (e.g., "Instance: basic-mysql ...")
        String resourceName = "N/A";
        if (description.contains("instance: ")) {
            int start = description.indexOf("instance: ") + "instance: ".length();
            int end = description.indexOf(" ", start);
            if (end == -1) end = description.length();
            resourceName = description.substring(start, end).replaceAll("[^a-zA-Z0-9-]", "");
        } else if (rec.getContent().getOverview().getFieldsMap().containsKey("resourceName")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resourceName").getStringValue();
        } else if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue()
                    .substring(rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue().lastIndexOf('/') + 1);
        }

        // Parse current/recommended from description (e.g., "1 (+0) vCPUs and 3.75 (+3.15) GB memory")
        String currentState = "Underprovisioned";
        String recommendation = "Increase Resources";
        if (description.contains("overprovisioned") || description.contains("reduce") || description.contains("decrease")) {
            currentState = "Overprovisioned";
            recommendation = "Reduce Resources";
        } else if (!description.contains("high") && !description.contains("underprovisioned")) {
            currentState = "Needs Adjustment";
            recommendation = "Resize Instance";
        }

        // Extract detailed vCPU/memory changes if present
        String vcpuMatch = "N/A";
        String memoryMatch = "N/A";
        int vcpuStart = description.indexOf("vCPUs and");
        if (vcpuStart != -1) {
            vcpuMatch = description.substring(description.indexOf(": ") + 2, vcpuStart).trim() + " vCPUs";
            memoryMatch = description.substring(vcpuStart + "vCPUs and ".length(), description.indexOf("memory.")).trim() + " GB memory";
        }
        if (!vcpuMatch.equals("N/A")) {
            recommendation += " to " + vcpuMatch + ", " + memoryMatch;
        }

        // Cost: For underprovisioned, if positive (cost increase), set savings to 0; else abs for savings
        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            double costNanos = rec.getPrimaryImpact().getCostProjection().getCost().getNanos();
            double costUnits = rec.getPrimaryImpact().getCostProjection().getCost().getUnits();
            double totalCost = costUnits + (costNanos / 1_000_000_000.0);
            if (totalCost < 0) {
                monthlySavings = Math.abs(totalCost);  // Savings from overprovisioned
            }  // Else 0 for underprovisioned (cost increase)
        }

        return new GcpOptimizationRecommendation(
                resourceName,
                currentState,
                recommendation,
                monthlySavings,
                "Cloud SQL");
    }

    private GcpOptimizationRecommendation mapToCudRecommendationDto(Recommendation rec) {
        String resourceName = "Committed Use Discount";
        String description = rec.getDescription();

        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = Math.abs(rec.getPrimaryImpact()
                    .getCostProjection()
                    .getCost()
                    .getNanos() / -1_000_000_000.0);
        }

        String commitmentType = "N/A";
        String recommendedAction = description != null ? description : "Purchase CUD";

        if (description != null) {
            if (description.contains("3-year")) {
                commitmentType = "3-year General-purpose E2";
            } else if (description.contains("1-year")) {
                commitmentType = "1-year General-purpose E2";
            }

            if (description.contains("Memory for")) {
                int memStart = description.indexOf("Memory for");
                if (memStart != -1) {
                    String memPart = description.substring(memStart + "Memory for ".length());
                    if (!memPart.isEmpty()) {
                        commitmentType += " " + memPart.split(" ")[0];
                    }
                }
            }
        }

        return new GcpOptimizationRecommendation(
                resourceName,
                commitmentType,
                recommendedAction,
                monthlySavings,
                "Commitment");
    }

    private double calculateDiskCost(long sizeGb) {
        return (sizeGb * 0.10);
    }

    private double calculateSqlInstanceCost(String tier) {
        if (tier.startsWith("db-f1-micro")) return 10.0;
        if (tier.startsWith("db-g1-small")) return 20.0;
        return 50.0;
    }
}