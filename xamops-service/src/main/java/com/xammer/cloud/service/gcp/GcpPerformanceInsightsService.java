// src/main/java/com/xammer/cloud/service/gcp/GcpPerformanceInsightsService.java
package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper; // Import ObjectMapper
import com.google.cloud.compute.v1.Disk;
import com.google.cloud.compute.v1.DisksClient;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;
import com.google.container.v1.Cluster;
//import com.google.container.v1.ClusterManagerClient;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.dto.gcp.GcpMetricDto;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.dto.gcp.GcpResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpPerformanceInsightsService {

    private final CloudAccountRepository cloudAccountRepository;
    private final GcpClientProvider gcpClientProvider;
    private final GcpMetricsService gcpMetricsService;
    private final GcpDataService gcpDataService;
    private final GcpOptimizationService gcpOptimizationService;
    private final RedisCacheService redisCache;
    private final ObjectMapper objectMapper; // Inject ObjectMapper

    @Autowired
    public GcpPerformanceInsightsService(
            CloudAccountRepository cloudAccountRepository,
            GcpClientProvider gcpClientProvider,
            GcpMetricsService gcpMetricsService,
            @Lazy GcpDataService gcpDataService,
            @Lazy GcpOptimizationService gcpOptimizationService,
            RedisCacheService redisCache,
            ObjectMapper objectMapper) { // Add ObjectMapper to constructor
        this.cloudAccountRepository = cloudAccountRepository;
        this.gcpClientProvider = gcpClientProvider;
        this.gcpMetricsService = gcpMetricsService;
        this.gcpDataService = gcpDataService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.redisCache = redisCache;
        this.objectMapper = objectMapper; // Initialize ObjectMapper
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    public List<PerformanceInsightDto> getInsights(String gcpProjectId, String severity, boolean forceRefresh) {
        String cacheKey = "gcpPerformanceInsights-" + gcpProjectId + "-ALL";

        if (!forceRefresh) {
            Optional<List<PerformanceInsightDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                log.info("GCP Performance insights found in cache for project {}. Filtering by severity '{}'.", gcpProjectId, severity);
                return filterBySeverity(cachedData.get(), severity);
            }
        }

        log.info("Starting GCP performance insights scan for project: {}", gcpProjectId);
        CloudAccount account = getAccount(gcpProjectId); // accountId is gcpProjectId here

        try {
            // Use GcpDataService to get active regions indirectly or use a default list
            List<String> activeRegions = gcpDataService.getAllKnownRegions(); // Use known regions as a starting point

            List<CompletableFuture<List<PerformanceInsightDto>>> futures = new ArrayList<>();

            // Add futures for regional checks (simplified example)
            futures.add(getComputeEngineInsights(account, forceRefresh));
            futures.add(getCloudSqlInsights(account, forceRefresh)); // This now handles the type conversion
            futures.add(getPersistentDiskInsights(account, forceRefresh));
            //futures.add(getGkeInsights(account, forceRefresh)); // Re-enabled GKE insights

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<PerformanceInsightDto> allInsights = futures.stream()
                    .flatMap(future -> future.join().stream())
                    .collect(Collectors.toList());

            // Add insights derived from Optimization Service recommendations (handled in getCloudSqlInsights)

            redisCache.put(cacheKey, allInsights, 10); // Cache for 10 minutes
            log.info("Total GCP insights generated and cached for project {}: {}", gcpProjectId, allInsights.size());

            return filterBySeverity(allInsights, severity);

        } catch (Exception e) {
            log.error("Error fetching GCP performance insights for project: {}", gcpProjectId, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Check for the specific ClassCastException in the cause
            Throwable cause = e.getCause();
            if (cause instanceof ClassCastException && cause.getMessage().contains("LinkedHashMap cannot be cast")) {
                log.error("Detected ClassCastException related to caching. Clearing relevant cache and retrying might help.");
                // Optionally clear cache here if needed: redisCache.evict("gcpRightsizingRecommendations-" + gcpProjectId);
            }
            return new ArrayList<>();
        }
    }

    // --- Specific Insight Check Implementations ---

    private CompletableFuture<List<PerformanceInsightDto>> getComputeEngineInsights(CloudAccount account, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            List<PerformanceInsightDto> insights = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Compute Engine insights for project {}", gcpProjectId);

            Optional<InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
            if (clientOpt.isEmpty()) return insights;

            try (InstancesClient client = clientOpt.get()) {
                StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(entry -> entry.getValue().getInstancesList().stream())
                        .filter(instance -> "RUNNING".equals(instance.getStatus()))
                        .forEach(instance -> {
                            try {
                                List<GcpMetricDto> cpuMetrics = gcpMetricsService.getCpuUtilization(gcpProjectId, String.valueOf(instance.getId()));
                                double avgCpu = cpuMetrics.stream().mapToDouble(GcpMetricDto::getValue).average().orElse(100.0);
                                String zone = instance.getZone().substring(instance.getZone().lastIndexOf('/') + 1);

                                if (avgCpu < 10.0) {
                                    insights.add(new PerformanceInsightDto(
                                            "gce-" + instance.getId() + "-underutilized",
                                            "Compute Engine instance " + instance.getName() + " is underutilized (" + String.format("%.1f", avgCpu) + "% avg CPU).",
                                            "Low resource utilization.",
                                            avgCpu < 5.0 ? PerformanceInsightDto.InsightSeverity.CRITICAL : PerformanceInsightDto.InsightSeverity.WARNING,
                                            PerformanceInsightDto.InsightCategory.COST, account.getGcpProjectId(), 1, "Compute Engine",
                                            String.valueOf(instance.getId()), "Consider downsizing this instance. Check rightsizing recommendations.", "/docs/gce-rightsizing",
                                            50.0, // Placeholder savings
                                            zone, // Use zone as region for instance
                                            Instant.now().toString()
                                    ));
                                }
                            } catch (IOException e) {
                                log.warn("Could not fetch metrics for GCE instance {}: {}", instance.getName(), e.getMessage());
                            }
                        });
            } catch (Exception e) {
                log.error("Error fetching GCE insights for project {}: {}", gcpProjectId, e.getMessage());
            }
            log.info("Found {} GCE insights for project {}", insights.size(), gcpProjectId);
            return insights;
        });
    }

    private CompletableFuture<List<PerformanceInsightDto>> getCloudSqlInsights(CloudAccount account, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            List<PerformanceInsightDto> insights = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Cloud SQL insights for project {}", gcpProjectId);

            try {
                // Fetch recommendations - this might return List<Object> if cache deserializes incorrectly
                List<?> rawRecs = gcpOptimizationService.getRightsizingRecommendations(gcpProjectId);

                // --- FIX: Manually convert map back to DTO if necessary ---
                List<GcpOptimizationRecommendation> recs;
                if (!rawRecs.isEmpty() && rawRecs.get(0) instanceof Map) {
                    log.warn("Detected Map type from rightsizing recommendations cache for Cloud SQL. Attempting conversion.");
                    recs = ((List<Map<String, Object>>) rawRecs).stream()
                            .map(map -> objectMapper.convertValue(map, GcpOptimizationRecommendation.class))
                            .collect(Collectors.toList());
                } else {
                    // Assume it's already the correct type or empty
                    recs = (List<GcpOptimizationRecommendation>) rawRecs;
                }
                // --- END FIX ---


                recs.stream()
                        .filter(rec -> rec != null && "Cloud SQL".equals(rec.getService()) && !rec.isCostIncrease()) // Focus on cost saving (overprovisioned)
                        .forEach(rec -> insights.add(new PerformanceInsightDto(
                                "sql-" + rec.getResourceName() + "-overprovisioned",
                                "Cloud SQL instance " + rec.getResourceName() + " may be overprovisioned.",
                                rec.getReasonSummary(),
                                PerformanceInsightDto.InsightSeverity.WARNING,
                                PerformanceInsightDto.InsightCategory.COST,
                                account.getGcpProjectId(), 1, "Cloud SQL",
                                rec.getResourceName(),
                                "Consider rightsizing to " + rec.getRecommendedMachineType() + ". " + rec.getReasonSummary(),
                                "/docs/sql-rightsizing",
                                rec.getMonthlySavings(),
                                rec.getLocation(),
                                Instant.now().toString()
                        )));
            } catch (ClassCastException cce){
                log.error("ClassCastException during Cloud SQL insights processing for project {}. Data structure might be unexpected: {}", gcpProjectId, cce.getMessage());
                // Optionally clear the cache if this error persists
                // redisCache.evict("gcpRightsizingRecommendations-" + gcpProjectId);
            } catch (Exception e) {
                log.error("Error processing Cloud SQL insights for project {}: {}", gcpProjectId, e.getMessage());
            }

            log.info("Found {} Cloud SQL insights based on recommendations for project {}", insights.size(), gcpProjectId);
            return insights;
        });
    }


    private CompletableFuture<List<PerformanceInsightDto>> getPersistentDiskInsights(CloudAccount account, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            List<PerformanceInsightDto> insights = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Persistent Disk insights for project {}", gcpProjectId);

            Optional<DisksClient> clientOpt = gcpClientProvider.getDisksClient(gcpProjectId);
            if (clientOpt.isEmpty()) return insights;

            try (DisksClient client = clientOpt.get()) {
                StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(entry -> entry.getValue().getDisksList().stream())
                        .forEach(disk -> {
                            String zone = disk.getZone().substring(disk.getZone().lastIndexOf('/') + 1);
                            // Check for unattached disks (already covered by waste report, but can add here too)
                            if (disk.getUsersList().isEmpty()) {
                                insights.add(new PerformanceInsightDto(
                                        "disk-" + disk.getId() + "-unattached",
                                        "Persistent Disk " + disk.getName() + " is unattached.",
                                        "Unattached disks incur costs without being used.",
                                        PerformanceInsightDto.InsightSeverity.WARNING,
                                        PerformanceInsightDto.InsightCategory.COST, account.getGcpProjectId(), 1, "Persistent Disk",
                                        String.valueOf(disk.getId()), "Consider deleting this disk if no longer needed, or attach it to an instance.", "/docs/gcp-disks",
                                        disk.getSizeGb() * 0.04, // Approx cost for pd-standard
                                        zone,
                                        Instant.now().toString()
                                ));
                            }
                            // Add checks for low IOPS on SSDs if needed (requires metrics)
                        });
            } catch (Exception e) {
                log.error("Error fetching Persistent Disk insights for project {}: {}", gcpProjectId, e.getMessage());
            }
            log.info("Found {} Persistent Disk insights for project {}", insights.size(), gcpProjectId);
            return insights;
        });
    }

//    private CompletableFuture<List<PerformanceInsightDto>> getGkeInsights(CloudAccount account, boolean forceRefresh) {
//        return CompletableFuture.supplyAsync(() -> {
//            List<PerformanceInsightDto> insights = new ArrayList<>();
//            String gcpProjectId = account.getGcpProjectId();
//            log.info("Checking GKE insights for project {}", gcpProjectId);
//
//            Optional<ClusterManagerClient> clientOpt = gcpClientProvider.getClusterManagerClient(gcpProjectId);
//            if(clientOpt.isEmpty()) {
//                log.warn("ClusterManagerClient not available for project {}. Skipping GKE insights.", gcpProjectId);
//                return insights;
//            }
//
//            try (ClusterManagerClient client = clientOpt.get()) {
//                String parent = "projects/" + gcpProjectId + "/locations/-";
//                client.listClusters(parent).getClustersList().forEach(cluster -> {
//                    if (!"RUNNING".equals(cluster.getStatus().toString())) {
//                        insights.add(new PerformanceInsightDto(
//                                "gke-" + cluster.getName() + "-inactive",
//                                "GKE Cluster " + cluster.getName() + " is not running.",
//                                "Inactive clusters may still incur costs (control plane). Status: " + cluster.getStatus(),
//                                PerformanceInsightDto.InsightSeverity.WARNING,
//                                PerformanceInsightDto.InsightCategory.FAULT_TOLERANCE,
//                                account.getGcpProjectId(), 1, "GKE", cluster.getName(),
//                                "Review cluster status, repair or delete if not needed.", "/docs/gke-troubleshooting",
//                                73.0, // Approx monthly cost of control plane
//                                cluster.getLocation(),
//                                Instant.now().toString()
//                        ));
//                    }
//                    // Add checks for node pool utilization if needed (requires monitoring data)
//                });
//            } catch (Exception e) {
//                log.error("Error fetching GKE insights for project {}: {}", gcpProjectId, e.getMessage());
//            }
//            log.info("Found {} GKE insights for project {}", insights.size(), gcpProjectId);
//            return insights;
//        });
//    }


    // --- Helper Methods ---

    // Removed mapOptimizationRecsToInsights as it's now integrated into getCloudSqlInsights


    private List<PerformanceInsightDto> filterBySeverity(List<PerformanceInsightDto> insights, String severity) {
        if (insights == null) return Collections.emptyList(); // Add null check
        if (severity == null || severity.isEmpty() || "ALL".equalsIgnoreCase(severity)) {
            return insights;
        }
        try {
            PerformanceInsightDto.InsightSeverity severityEnum = PerformanceInsightDto.InsightSeverity.valueOf(severity.toUpperCase());
            return insights.stream()
                    .filter(insight -> insight != null && insight.getSeverity() == severityEnum) // Added null check for insight
                    .collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid severity filter '{}' provided. Returning all insights.", severity);
            return insights;
        }
    }


    // --- FIX: getInsightsSummary now depends on getInsights ---
    public Map<String, Object> getInsightsSummary(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = "gcpInsightsSummary-" + gcpProjectId;
        if (!forceRefresh) {
            Optional<Map<String, Object>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                log.info("Returning cached GCP insights summary for project {}", gcpProjectId);
                return cachedData.get();
            }
        }

        log.info("Calculating fresh GCP insights summary for project {}", gcpProjectId);
        // *** Fetch the full list first ***
        // Use severity "ALL" and the same forceRefresh flag to ensure consistency
        List<PerformanceInsightDto> allInsights = getInsights(gcpProjectId, "ALL", forceRefresh);
        // *** ^^^ This ensures we use the same data source ^^^ ***


        Map<String, Object> summary = new HashMap<>();
        summary.put("totalInsights", allInsights.size());
        summary.put("critical", allInsights.stream().filter(i -> i != null && i.getSeverity() == PerformanceInsightDto.InsightSeverity.CRITICAL).count()); // Added null check
        summary.put("warning", allInsights.stream().filter(i -> i != null && i.getSeverity() == PerformanceInsightDto.InsightSeverity.WARNING).count()); // Added null check
        summary.put("weakWarning", allInsights.stream().filter(i -> i != null && i.getSeverity() == PerformanceInsightDto.InsightSeverity.WEAK_WARNING).count()); // Added null check
        summary.put("potentialSavings", allInsights.stream()
                .filter(i -> i != null && i.getCategory() == PerformanceInsightDto.InsightCategory.COST) // Added null check
                .mapToDouble(PerformanceInsightDto::getPotentialSavings).sum());
        summary.put("performanceScore", calculatePerformanceScore(allInsights));

        redisCache.put(cacheKey, summary, 10); // Cache the calculated summary
        log.info("Cached fresh GCP insights summary for project {}", gcpProjectId);
        return summary;
    }
    // --- END FIX ---


    public int calculatePerformanceScore(List<PerformanceInsightDto> insights) {
        int score = 100;
        int criticalWeight = 10;
        int warningWeight = 5;
        int weakWarningWeight = 2;
        if (insights == null) return 100; // Handle null case

        for (PerformanceInsightDto insight : insights) {
            if (insight == null) continue; // Skip null insights

            // Only count Cost and Performance insights against the score for now
            if (insight.getCategory() == PerformanceInsightDto.InsightCategory.COST ||
                    insight.getCategory() == PerformanceInsightDto.InsightCategory.PERFORMANCE) {
                // Check severity is not null before switching
                if (insight.getSeverity() != null) {
                    switch (insight.getSeverity()) {
                        case CRITICAL: score -= criticalWeight; break;
                        case WARNING: score -= warningWeight; break;
                        case WEAK_WARNING: score -= weakWarningWeight; break;
                        default: break; // Handle case where severity might be null or unexpected
                    }
                }
            }
        }
        return Math.max(0, score);
    }



    // Placeholder for What-If scenario - requires more complex logic and pricing data
    public CompletableFuture<Object> getWhatIfScenario(String gcpProjectId, String resourceId, String targetInstanceType, boolean forceRefresh) {
        log.warn("GCP What-If Scenario not fully implemented.");
        return CompletableFuture.completedFuture(Map.of("message", "GCP What-If Scenario not yet available."));
    }
}