package com.xammer.cloud.service.gcp;

import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.BackupRun;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.api.services.sqladmin.model.InstancesListResponse;
import com.google.cloud.compute.v1.*;
import com.google.cloud.recommender.v1.Recommendation;
import com.google.cloud.recommender.v1.RecommenderClient;
import com.google.cloud.recommender.v1.RecommenderName;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpCommittedUseDiscountDto;
import com.xammer.cloud.dto.gcp.GcpCudUtilizationDto;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.dto.gcp.GcpWasteItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpOptimizationService {

    private final GcpClientProvider gcpClientProvider;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final GcpDataService gcpDataService;

    public GcpOptimizationService(GcpClientProvider gcpClientProvider, @Lazy GcpDataService gcpDataService) {
        this.gcpClientProvider = gcpClientProvider;
        this.gcpDataService = gcpDataService;
    }
    
    public CompletableFuture<List<GcpCommittedUseDiscountDto>> getCommittedUseDiscounts(String gcpProjectId) {
        // Placeholder implementation
        return CompletableFuture.completedFuture(new ArrayList<>());
    }
    
    public CompletableFuture<GcpCudUtilizationDto> getCudUtilization(String gcpProjectId, String cudId) {
        // Placeholder implementation
        return CompletableFuture.completedFuture(new GcpCudUtilizationDto());
    }

    public CompletableFuture<List<GcpOptimizationRecommendation>> getRightsizingRecommendations(String gcpProjectId) {
        log.info("Fetching rightsizing recommendations for GCP project: {}", gcpProjectId);

        return gcpDataService.getRegionStatusForGcp(new ArrayList<>()).thenCompose(regions -> {
            List<String> locations = regions.stream().map(DashboardData.RegionStatus::getRegionId).collect(Collectors.toList());
            locations.add("global");

            List<CompletableFuture<List<GcpOptimizationRecommendation>>> futures = new ArrayList<>();

            futures.add(getRecommendationsForRecommender(gcpProjectId, "google.compute.instance.MachineTypeRecommender", "global", this::mapToRightsizingDto));
            futures.add(getRecommendationsForRecommender(gcpProjectId, "google.compute.instanceGroupManager.MachineTypeRecommender", "global", this::mapToRightsizingDto));

            for (String location : locations) {
                if (!location.equals("global")) {
                    futures.add(getRecommendationsForRecommender(gcpProjectId, "google.cloudsql.instance.OverprovisionedRecommender", location, this::mapToSqlRightsizingDto));
                    // Add other location-specific recommenders here if needed
                }
            }

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .collect(Collectors.toList()));
        });
    }

    public CompletableFuture<List<GcpWasteItem>> getWasteReport(String gcpProjectId) {
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

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(List::stream)
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<DashboardData.SavingsSummary> getSavingsSummary(String gcpProjectId) {
        CompletableFuture<List<GcpWasteItem>> wasteFuture = getWasteReport(gcpProjectId);
        CompletableFuture<List<GcpOptimizationRecommendation>> rightsizingFuture = getRightsizingRecommendations(gcpProjectId);

        return CompletableFuture.allOf(wasteFuture, rightsizingFuture).thenApply(v -> {
            double wasteSavings = wasteFuture.join().stream().mapToDouble(GcpWasteItem::getMonthlySavings).sum();
            double rightsizingSavings = rightsizingFuture.join().stream().mapToDouble(GcpOptimizationRecommendation::getMonthlySavings).sum();

            List<DashboardData.SavingsSuggestion> suggestions = new ArrayList<>();
            if (rightsizingSavings > 0) suggestions.add(new DashboardData.SavingsSuggestion("Rightsizing", rightsizingSavings));
            if (wasteSavings > 0) suggestions.add(new DashboardData.SavingsSuggestion("Waste Elimination", wasteSavings));

            return new DashboardData.SavingsSummary(wasteSavings + rightsizingSavings, suggestions);
        });
    }

    public CompletableFuture<DashboardData.OptimizationSummary> getOptimizationSummary(String gcpProjectId) {
        CompletableFuture<List<GcpWasteItem>> wasteFuture = getWasteReport(gcpProjectId);
        CompletableFuture<List<GcpOptimizationRecommendation>> rightsizingFuture = getRightsizingRecommendations(gcpProjectId);

        return CompletableFuture.allOf(wasteFuture, rightsizingFuture).thenApply(v -> {
            double totalSavings = wasteFuture.join().stream().mapToDouble(GcpWasteItem::getMonthlySavings).sum()
                    + rightsizingFuture.join().stream().mapToDouble(GcpOptimizationRecommendation::getMonthlySavings).sum();
            long criticalAlerts = rightsizingFuture.join().size();
            return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
        });
    }

    private CompletableFuture<List<GcpOptimizationRecommendation>> getRecommendationsForRecommender(String gcpProjectId, String recommenderId, String location, java.util.function.Function<Recommendation, GcpOptimizationRecommendation> mapper) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("Recommender client not available for project {}. Skipping recommendations for {}.", gcpProjectId, recommenderId);
                return List.of();
            }

            try (RecommenderClient client = clientOpt.get()) {
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, location, recommenderId);
                log.info("Querying recommender '{}' for project {} in location {}", recommenderId, gcpProjectId, location);
                return StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(mapper)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to get recommendations from {} for project {} in location {}", recommenderId, gcpProjectId, location, e);
                return List.of();
            }
        });
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
                    List<BackupRun> backups = clientOpt.get().backupRuns().list(gcpProjectId, instance.getName()).execute().getItems();
                    Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);

                    if (backups != null) {
                        backups.stream()
                                .filter(backup -> {
                                    try {
                                        if (backup.getWindowStartTime() == null) return false;
                                        Instant backupTime = Instant.parse(backup.getWindowStartTime());
                                        return backupTime.isBefore(ninetyDaysAgo);
                                    } catch (Exception ex) {
                                        log.warn("Failed to parse backup window start time: {}", backup.getWindowStartTime(), ex);
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
                // Corrected logging: Pass the exception object 'e' as the last argument
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
                return StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
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
                        .filter(image -> image.getFamily() == null)
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

    private CompletableFuture<List<GcpWasteItem>> findIdleResources(String gcpProjectId, String recommenderId, String wasteType) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) return new ArrayList<>();

            try (RecommenderClient client = clientOpt.get()) {
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, "global", recommenderId);
                log.info("Querying recommender '{}' for project {}", recommenderId, gcpProjectId);

                return StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(rec -> mapToWasteDto(rec, wasteType))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to get waste report for recommender {} in project {}", recommenderId, gcpProjectId, e);
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

    // Mappers, cost calculators, and other private methods...

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
        return new GcpOptimizationRecommendation(resourceName, currentMachineType, recommendedMachineType, monthlySavings, "Compute Engine");
    }

    private GcpOptimizationRecommendation mapToSqlRightsizingDto(Recommendation rec) {
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue();
            resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }
        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }
        return new GcpOptimizationRecommendation(resourceName, "Overprovisioned", "Smaller Tier", monthlySavings, "Cloud SQL");
    }

    private GcpOptimizationRecommendation mapToGkeRightsizingDto(Recommendation rec) {
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue();
            resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }
        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }
        return new GcpOptimizationRecommendation(resourceName, "N/A", "N/A", monthlySavings, "GKE");
    }

    private GcpOptimizationRecommendation mapToFunctionRightsizingDto(Recommendation rec) {
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue();
            resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }
        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }
        return new GcpOptimizationRecommendation(resourceName, "N/A", "N/A", monthlySavings, "Cloud Function");
    }

    private GcpOptimizationRecommendation mapToBigQueryDto(Recommendation rec) {
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue();
            resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }
        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }
        return new GcpOptimizationRecommendation(resourceName, "N/A", "N/A", monthlySavings, "BigQuery");
    }

    private GcpOptimizationRecommendation mapToStorageDto(Recommendation rec) {
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue();
            resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }
        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }
        return new GcpOptimizationRecommendation(resourceName, "N/A", "N/A", monthlySavings, "Cloud Storage");
    }

    private double calculateDiskCost(long sizeGb) {
        return (sizeGb * 0.10); // Simplified cost
    }

    private double calculateSqlInstanceCost(String tier) {
        if (tier.startsWith("db-f1-micro")) return 10.0;
        if (tier.startsWith("db-g1-small")) return 20.0;
        return 50.0; // Default
    }
}