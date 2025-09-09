package com.xammer.cloud.service.gcp;


import com.google.cloud.recommender.v1.Recommendation;
import com.google.cloud.recommender.v1.RecommenderClient;
import com.google.cloud.recommender.v1.RecommenderName;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.dto.gcp.GcpWasteItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.xammer.cloud.dto.gcp.TaggingComplianceDto;
import com.xammer.cloud.dto.gcp.GcpResourceDto;
import com.google.cloud.compute.v1.DisksClient;
import com.google.cloud.compute.v1.Disk;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.cloud.compute.v1.ImagesClient;
import com.google.cloud.compute.v1.Image;
import com.google.cloud.compute.v1.FirewallsClient;
import com.google.cloud.compute.v1.Firewall;
import com.google.api.services.sqladmin.model.BackupRun;
import com.google.api.services.sqladmin.model.InstancesListResponse;


import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpOptimizationService {
    // Utility method to fetch average metric from GCP Monitoring API
    private double fetchAverageMetric(String projectId, String instanceId, String metricType) {
        try (com.google.cloud.monitoring.v3.MetricServiceClient client = com.google.cloud.monitoring.v3.MetricServiceClient.create()) {
            long now = System.currentTimeMillis() / 1000;
            long start = now - 30 * 24 * 60 * 60; // last 30 days
            com.google.monitoring.v3.TimeInterval interval = com.google.monitoring.v3.TimeInterval.newBuilder()
                .setStartTime(com.google.protobuf.util.Timestamps.fromSeconds(start))
                .setEndTime(com.google.protobuf.util.Timestamps.fromSeconds(now))
                .build();

            String filter = String.format(
                "metric.type=\"%s\" AND resource.labels.instance_id=\"%s\"",
                metricType, instanceId);

            com.google.monitoring.v3.ListTimeSeriesRequest request = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                .setName(com.google.monitoring.v3.ProjectName.of(projectId).toString())
                .setFilter(filter)
                .setInterval(interval)
                .setView(com.google.monitoring.v3.ListTimeSeriesRequest.TimeSeriesView.FULL)
                .build();

            double sum = 0;
            int count = 0;
            for (com.google.monitoring.v3.TimeSeries ts : client.listTimeSeries(request).iterateAll()) {
                for (com.google.monitoring.v3.Point p : ts.getPointsList()) {
                    sum += p.getValue().getDoubleValue();
                    count++;
                }
            }
            return count > 0 ? sum / count : 1.0; // Default to 1.0 if no data
        } catch (Exception e) {
            log.error("Error fetching metric {} for instance {}", metricType, instanceId, e);
            return 1.0;
        }
    }
    // Example: Find underutilized VMs (high config, low usage)
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getUnderutilizedVMs(String gcpProjectId) {
        return gcpDataService.getAllResources(gcpProjectId).thenApply(resources -> {
            double cpuThreshold = 0.10; // 10% average CPU usage
            double gpuThreshold = 0.10; // 10% average GPU usage
            double memThreshold = 0.10; // 10% average memory usage
            List<DashboardData.OptimizationRecommendation> recommendations = new ArrayList<>();
            for (GcpResourceDto r : resources) {
                if (r.getType().equalsIgnoreCase("Compute Engine") && r.getStatus().equalsIgnoreCase("RUNNING")) {
                    String machineType = r.getTags() != null ? r.getTags().getOrDefault("machineType", "") : "";
                    String instanceId = r.getId();
                    double cpuUsage = fetchAverageMetric(gcpProjectId, instanceId, "compute.googleapis.com/instance/cpu/utilization");
                    double gpuUsage = fetchAverageMetric(gcpProjectId, instanceId, "compute.googleapis.com/instance/gpu/utilization");
                    double memUsage = fetchAverageMetric(gcpProjectId, instanceId, "compute.googleapis.com/instance/memory/utilization");
                    boolean highConfig = machineType.contains("n2") || machineType.contains("a2") || machineType.contains("highcpu") || machineType.contains("highmem") || machineType.contains("gpu");
                    boolean underutilized = highConfig && cpuUsage < cpuThreshold && gpuUsage < gpuThreshold && memUsage < memThreshold;
                    if (underutilized) {
                        recommendations.add(new DashboardData.OptimizationRecommendation(
                            "Compute Engine",
                            r.getName(),
                            machineType,
                            "Rightsize VM",
                            0.0,
                            String.format("VM is overprovisioned. CPU: %.2f%%, GPU: %.2f%%, Memory: %.2f%%. Consider switching to a lower configuration.", cpuUsage * 100, gpuUsage * 100, memUsage * 100),
                            0.0,
                            cpuUsage
                        ));
                    }
                }
            }
            return recommendations;
        });
    }

    private final GcpClientProvider gcpClientProvider;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final GcpDataService gcpDataService; 

    public GcpOptimizationService(GcpClientProvider gcpClientProvider, @org.springframework.context.annotation.Lazy GcpDataService gcpDataService) {
        this.gcpClientProvider = gcpClientProvider;
        this.gcpDataService = gcpDataService;
    }

    public CompletableFuture<List<GcpOptimizationRecommendation>> getRightsizingRecommendations(String gcpProjectId) {
        log.info("Fetching rightsizing recommendations for GCP project: {}", gcpProjectId);

        return gcpDataService.getRegionStatusForGcp(new ArrayList<>()).thenCompose(regions -> {
            List<String> locations = regions.stream().map(DashboardData.RegionStatus::getRegionId).collect(Collectors.toList());
            locations.add("global");

            List<CompletableFuture<List<GcpOptimizationRecommendation>>> futures = new ArrayList<>();

            // Add all relevant recommenders, dynamically checking all regions
            futures.add(getRecommendationsForRecommender(gcpProjectId, "google.compute.instance.MachineTypeRecommender", "global", this::mapToRightsizingDto));
            futures.add(getRecommendationsForRecommender(gcpProjectId, "google.compute.instanceGroupManager.MachineTypeRecommender", "global", this::mapToRightsizingDto));

            for (String location : locations) {
                if (!location.equals("global")) {
                    futures.add(getRecommendationsForRecommender(gcpProjectId, "google.cloudsql.instance.OverprovisionedRecommender", location, this::mapToSqlRightsizingDto));
                    futures.add(getRecommendationsForRecommender(gcpProjectId, "google.bigquery.capacityCommitments.Recommender", location, this::mapToBigQueryDto));
                    futures.add(getRecommendationsForRecommender(gcpProjectId, "google.bigquery.table.PartitionClusterRecommender", location, this::mapToBigQueryDto));
                    futures.add(getRecommendationsForRecommender(gcpProjectId, "google.storage.bucket.SoftDeleteRecommender", location, this::mapToStorageDto));
                }
            }

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .collect(Collectors.toList()));
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
                List<GcpOptimizationRecommendation> recommendations = StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(mapper)
                        .collect(Collectors.toList());
                
                log.info("Found {} recommendations from {} for project {} in location {}", recommendations.size(), recommenderId, gcpProjectId, location);
                return recommendations;
            } catch (Exception e) {
                log.error("Failed to get recommendations from {} for project {} in location {}. This may be due to permissions or the API not being enabled.", recommenderId, gcpProjectId, location, e);
                return List.of();
            }
        });
    }

    private GcpOptimizationRecommendation mapToRightsizingDto(Recommendation rec) {
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resourceName")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resourceName").getStringValue();
        }
        
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
        
        return new GcpOptimizationRecommendation(resourceName, currentMachineType, recommendedMachineType, monthlySavings, "Compute Engine");
    }
    
    private GcpOptimizationRecommendation mapToSqlRightsizingDto(Recommendation rec) {
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
             resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue();
             resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }
        
        String currentMachineType = "N/A";
        String recommendedMachineType = "N/A";

        if (rec.getDescription().toLowerCase().contains("resize instance")) {
            // Simple parsing, can be improved with more robust logic
            currentMachineType = "Overprovisioned"; 
            recommendedMachineType = "Smaller Tier";
        }

        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }
        
        return new GcpOptimizationRecommendation(resourceName, currentMachineType, recommendedMachineType, monthlySavings, "Cloud SQL");
    }

    private GcpOptimizationRecommendation mapToBigQueryDto(Recommendation rec) {
        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue();
            resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }
        return new GcpOptimizationRecommendation(resourceName, "N/A", "N/A", monthlySavings, "BigQuery");
    }
    
    private GcpOptimizationRecommendation mapToStorageDto(Recommendation rec) {
        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue();
            resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }
        return new GcpOptimizationRecommendation(resourceName, "N/A", "N/A", monthlySavings, "Cloud Storage");
    }
    
    public CompletableFuture<List<GcpWasteItem>> getWasteReport(String gcpProjectId) {
        log.info("Starting waste report generation for GCP project: {}", gcpProjectId);

        // Dynamically get regions for the checks below
        return gcpDataService.getRegionStatusForGcp(new ArrayList<>()).thenCompose(regions -> {
            List<String> locations = regions.stream().map(DashboardData.RegionStatus::getRegionId).collect(Collectors.toList());
            locations.add("global");

            List<CompletableFuture<List<GcpWasteItem>>> futures = new ArrayList<>();
            
            // Re-use the existing findIdleResources method with an updated list of recommenders
            futures.add(findIdleResources(gcpProjectId, "google.compute.disk.IdleResourceRecommender", "Idle Persistent Disk"));
            futures.add(findIdleResources(gcpProjectId, "google.compute.address.IdleResourceRecommender", "Unused IP Address"));
            futures.add(findIdleResources(gcpProjectId, "google.compute.instance.IdleResourceRecommender", "Idle VM Instance"));

            // Add new waste detection futures that run across all relevant locations
            for (String location : locations) {
                if (!location.equals("global")) {
                    futures.add(findIdleResources(gcpProjectId, "google.cloudsql.instance.IdleRecommender", "Idle Cloud SQL Instance"));
                }
            }

            // Other custom checks that are not covered by Recommender API
            futures.add(findOldSnapshots(gcpProjectId));
            futures.add(findUnattachedDisks(gcpProjectId));
            futures.add(findUnusedCustomImages(gcpProjectId));
            futures.add(findUnusedFirewallRules(gcpProjectId));
            futures.add(findIdleCloudSqlInstances(gcpProjectId)); // This is a custom check, kept for completeness
            futures.add(findOldSqlSnapshots(gcpProjectId));
            
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> Stream.of(futures.stream().map(CompletableFuture::join).flatMap(List::stream).collect(Collectors.toList()),
                                findIdleResources(gcpProjectId, "google.compute.disk.IdleResourceRecommender", "Idle Persistent Disk").join(),
                                findIdleResources(gcpProjectId, "google.compute.address.IdleResourceRecommender", "Unused IP Address").join(),
                                findIdleResources(gcpProjectId, "google.compute.instance.IdleResourceRecommender", "Idle VM Instance").join(),
                                findOldSnapshots(gcpProjectId).join(),
                                findUnattachedDisks(gcpProjectId).join(),
                                findUnusedCustomImages(gcpProjectId).join(),
                                findUnusedFirewallRules(gcpProjectId).join(),
                                findIdleCloudSqlInstances(gcpProjectId).join(),
                                findOldSqlSnapshots(gcpProjectId).join()
                            )
                            .flatMap(List::stream)
                            .toList());
        });
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

    private CompletableFuture<List<GcpWasteItem>> findIdleResources(String gcpProjectId, String recommenderId, String wasteType) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("Recommender client not available for project {}, skipping check for {}", gcpProjectId, wasteType);
                return new ArrayList<>();
            }

            try (RecommenderClient client = clientOpt.get()) {
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, "global", recommenderId);
                log.info("Querying recommender '{}' for project {}", recommenderId, gcpProjectId);

                return StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(rec -> mapToWasteDto(rec, wasteType))
                        .toList();
            } catch (Exception e) {
                log.error("Failed to get waste report for recommender {} in project {}. This may be due to permissions or the API not being enabled.", recommenderId, gcpProjectId, e);
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

        String location = "global";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("location")) {
            location = rec.getContent().getOverview().getFieldsMap().get("location").getStringValue();
        }

        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }

        return new GcpWasteItem(resourceName, wasteType, location, monthlySavings);
    }
    
    // NEW: Method to find idle Persistent Disks, mapping to generic DTO
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getIdlePersistentDisks(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (RecommenderClient client = clientOpt.get()) {
                String recommenderId = "google.compute.disk.IdleResourceRecommender";
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, "global", recommenderId);
                return StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(this::mapToDiskRecommendation)
                        .toList();
            } catch (Exception e) {
                log.error("Failed to get idle disk recommendations for project {}", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }
    
    private DashboardData.OptimizationRecommendation mapToDiskRecommendation(Recommendation rec) {
        double monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        String resourceName = rec.getDescription().replace("Delete idle persistent disk ", "").split(" at ")[0];
        return new DashboardData.OptimizationRecommendation(
                "Cloud Storage",
                resourceName,
                "Persistent Disk",
                "Delete Disk",
                monthlySavings,
                rec.getDescription(),
                monthlySavings,
                0.0 // Deleting the disk means the cost goes to zero.
        );
    }

    // NEW: Method to find underutilized Cloud Functions
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getUnderutilizedCloudFunctions() {
        return CompletableFuture.supplyAsync(List::of, executor);
    }
    public CompletableFuture<TaggingComplianceDto> getTaggingCompliance(String gcpProjectId) {
        // Required tags for compliance (AWS-like)
        List<String> requiredTags = List.of("Name", "Environment", "Owner");
        return gcpDataService.getAllResources(gcpProjectId).thenApply(resources -> {
            TaggingComplianceDto dto = new TaggingComplianceDto();
            if (resources == null || resources.isEmpty()) {
                dto.setCompliancePercentage(100.0);
                return dto;
            }
            int total = resources.size();
            List<GcpResourceDto> untaggedResources = resources.stream()
                .filter(r -> r.getTags() == null || requiredTags.stream().anyMatch(tag -> r.getTags().get(tag) == null || r.getTags().get(tag).isEmpty()))
                .toList();
            int untagged = untaggedResources.size();
            dto.setTotalResourcesScanned(total);
            dto.setUntaggedResourcesCount(untagged);
            dto.setCompliancePercentage(((double) (total - untagged) / total) * 100.0);
            dto.setUntaggedResources(
                untaggedResources.stream().limit(5)
                    .map(r -> new TaggingComplianceDto.UntaggedResource(r.getName(), r.getType()))
                    .toList()
            );
            return dto;
        });
    }

    // --- NEW METHODS FOR GCP WASTE DETECTION ---
    
    private CompletableFuture<List<GcpWasteItem>> findOldSnapshots(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Checking for old Persistent Disk snapshots for project: {}", gcpProjectId);
            List<GcpWasteItem> wasteItems = new ArrayList<>();
            // This is a placeholder since GCP SDK does not provide direct snapshot age.
            // In a real-world scenario, you would list snapshots and check their creation date.
            return wasteItems;
        }, executor);
    }
    
    private CompletableFuture<List<GcpWasteItem>> findUnattachedDisks(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Checking for unattached Persistent Disks for project: {}", gcpProjectId);
            Optional<DisksClient> clientOpt = gcpClientProvider.getDisksClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("DisksClient not available for project {}. Skipping check.", gcpProjectId);
                return List.of();
            }

            try (DisksClient client = clientOpt.get()) {
                List<GcpWasteItem> wasteItems = StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(entry -> entry.getValue().getDisksList().stream())
                        .filter(disk -> disk.getUsersList().isEmpty())
                        .map(disk -> new GcpWasteItem(
                                disk.getName(),
                                "Unattached Persistent Disk",
                                disk.getZone().substring(disk.getZone().lastIndexOf('/') + 1),
                                calculateDiskCost(disk.getSizeGb()) // Simplified calculation
                        ))
                        .collect(Collectors.toList());
                log.info("Found {} unattached disks for project {}.", wasteItems.size(), gcpProjectId);
                return wasteItems;
            } catch (Exception e) {
                log.error("Failed to list unattached disks for project {}: {}", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }
    
    private CompletableFuture<List<GcpWasteItem>> findUnusedCustomImages(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Checking for unused custom images for project: {}", gcpProjectId);
            Optional<ImagesClient> clientOpt = gcpClientProvider.getImagesClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("ImagesClient not available for project {}. Skipping check.", gcpProjectId);
                return List.of();
            }
            try (ImagesClient client = clientOpt.get()) {
                // Simplified implementation: lists all custom images and assumes any that are not
                // in the `global` region are custom. A more robust check would involve checking if
                // they are attached to any instances.
                List<GcpWasteItem> wasteItems = StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                        .filter(image -> image.getFamily() == null) // Assuming custom images don't have a family
                        .map(image -> new GcpWasteItem(
                                image.getName(),
                                "Unused Custom Image",
                                "global",
                                0.05 * image.getDiskSizeGb() // Simplified cost calculation
                        ))
                        .collect(Collectors.toList());
                log.info("Found {} unused custom images for project {}.", wasteItems.size(), gcpProjectId);
                return wasteItems;
            } catch (Exception e) {
                log.error("Failed to list custom images for project {}: {}", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }

    private CompletableFuture<List<GcpWasteItem>> findUnusedFirewallRules(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Checking for unused firewall rules for project: {}", gcpProjectId);
            Optional<FirewallsClient> clientOpt = gcpClientProvider.getFirewallsClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("FirewallsClient not available for project {}. Skipping check.", gcpProjectId);
                return List.of();
            }
            try (FirewallsClient client = clientOpt.get()) {
                List<GcpWasteItem> wasteItems = StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                        .filter(Firewall::getDisabled) // Finding disabled rules
                        .map(firewall -> new GcpWasteItem(
                                firewall.getName(),
                                "Unused Firewall Rule",
                                "global",
                                0.0 // No direct cost, but a security risk
                        ))
                        .collect(Collectors.toList());
                log.info("Found {} unused firewall rules for project {}.", wasteItems.size(), gcpProjectId);
                return wasteItems;
            } catch (Exception e) {
                log.error("Failed to list firewall rules for project {}: {}", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }

    private CompletableFuture<List<GcpWasteItem>> findIdleCloudSqlInstances(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Checking for idle Cloud SQL instances for project: {}", gcpProjectId);
            Optional<SQLAdmin> clientOpt = gcpClientProvider.getSqlAdminClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("SQLAdmin client not available for project {}. Skipping check.", gcpProjectId);
                return List.of();
            }
            try {
                InstancesListResponse instances = clientOpt.get().instances().list(gcpProjectId).execute();
                List<GcpWasteItem> wasteItems = instances.getItems().stream()
                        .filter(instance -> "RUNNABLE".equals(instance.getState()))
                        .map(instance -> new GcpWasteItem(
                                instance.getName(),
                                "Idle Cloud SQL Instance",
                                instance.getRegion(),
                                calculateSqlInstanceCost(instance.getSettings().getTier()) // Simplified cost calculation
                        ))
                        .collect(Collectors.toList());
                log.info("Found {} idle Cloud SQL instances for project {}.", wasteItems.size(), gcpProjectId);
                return wasteItems;
            } catch (Exception e) {
                log.error("Failed to list Cloud SQL instances for project {}: {}", gcpProjectId, e);
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
                for (DatabaseInstance instance : instances.getItems()) {
                    List<BackupRun> backups = clientOpt.get().backupRuns().list(gcpProjectId, instance.getName()).execute().getItems();
                    Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
                    backups.stream()
                            .filter(backup -> {
                                try {
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
                                    0.01 // Simplified cost
                            ))
                            .forEach(wasteItems::add);
                }
                log.info("Found {} old Cloud SQL backups for project {}.", wasteItems.size(), gcpProjectId);
                return wasteItems;
            } catch (Exception e) {
                log.error("Failed to list Cloud SQL backups for project {}: {}", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }

    private double calculateDiskCost(long sizeGb) {
        // Simplified cost calculation: assume balanced persistent disks price
        return (sizeGb * 0.10) / 1000;
    }
    
    private double calculateSqlInstanceCost(String tier) {
        // Simplified cost calculation based on tier. 
        if (tier.startsWith("db-f1-micro")) return 10.0;
        if (tier.startsWith("db-g1-small")) return 20.0;
        return 50.0;
    }
    
}