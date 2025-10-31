// src/main/java/com/xammer/cloud/service/gcp/GcpPerformanceInsightsService.java
package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.compute.v1.Disk;
import com.google.cloud.compute.v1.DisksClient;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.dto.gcp.GcpMetricDto;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
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
    private final ObjectMapper objectMapper;

    @Autowired
    public GcpPerformanceInsightsService(
            CloudAccountRepository cloudAccountRepository,
            GcpClientProvider gcpClientProvider,
            GcpMetricsService gcpMetricsService,
            @Lazy GcpDataService gcpDataService,
            @Lazy GcpOptimizationService gcpOptimizationService,
            RedisCacheService redisCache,
            ObjectMapper objectMapper) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.gcpClientProvider = gcpClientProvider;
        this.gcpMetricsService = gcpMetricsService;
        this.gcpDataService = gcpDataService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.redisCache = redisCache;
        this.objectMapper = objectMapper;
    }

    /**
     * Helper method to get CloudAccount from database
     * Tries GCP-specific lookup first, then falls back to provider account ID
     */
    private CloudAccount getAccount(String gcpProjectId) {
        log.info("🔍 Looking up GCP account by project ID: {}", gcpProjectId);
        
        // First try GCP-specific lookup
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByGcpProjectId(gcpProjectId);
        
        if (accountOpt.isPresent()) {
            log.info("✅ Found GCP account: {} (Project: {})", accountOpt.get().getAccountName(), gcpProjectId);
            return accountOpt.get();
        }
        
        // Fallback to provider account ID (might work if it matches)
        accountOpt = cloudAccountRepository.findByProviderAccountId(gcpProjectId);
        
        if (accountOpt.isPresent()) {
            log.info("✅ Found account via provider account ID: {}", accountOpt.get().getAccountName());
            return accountOpt.get();
        }
        
        // Not found
        log.error("❌ GCP project not found in database: {}", gcpProjectId);
        throw new RuntimeException("GCP project not found in database: " + gcpProjectId + 
                                   ". Please ensure the GCP account is connected and gcpProjectId is set correctly.");
    }

    /**
     * Get GCP performance insights for a project
     */
    public List<PerformanceInsightDto> getInsights(String gcpProjectId, String severity, boolean forceRefresh) {
        String cacheKey = "gcpPerformanceInsights-" + gcpProjectId + "-ALL";

        // Try cache first if not forcing refresh
        if (!forceRefresh) {
            Optional<List<PerformanceInsightDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                log.info("GCP Performance insights found in cache for project {}. Filtering by severity '{}'.", gcpProjectId, severity);
                return filterBySeverity(cachedData.get(), severity);
            }
        }

        log.info("Starting GCP performance insights scan for project: {}", gcpProjectId);
        
        try {
            // Validate account exists in database
            CloudAccount account = getAccount(gcpProjectId);
            log.info("✅ Validated account connection for project: {}", account.getAccountName());

            List<CompletableFuture<List<PerformanceInsightDto>>> futures = new ArrayList<>();

            // Add futures for different resource types
            futures.add(getComputeEngineInsights(account, forceRefresh));
            futures.add(getCloudSqlInsights(account, forceRefresh));
            futures.add(getPersistentDiskInsights(account, forceRefresh));

            // Wait for all futures to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Combine all insights
            List<PerformanceInsightDto> allInsights = futures.stream()
                    .flatMap(future -> future.join().stream())
                    .collect(Collectors.toList());

            // Cache the results
            redisCache.put(cacheKey, allInsights, 10); // Cache for 10 minutes
            log.info("Total GCP insights generated and cached for project {}: {}", gcpProjectId, allInsights.size());

            return filterBySeverity(allInsights, severity);

        } catch (RuntimeException e) {
            log.error("RuntimeException fetching GCP performance insights for project: {}", gcpProjectId, e);
            throw e; // Re-throw to be handled by controller
        } catch (Exception e) {
            log.error("Error fetching GCP performance insights for project: {}", gcpProjectId, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to fetch GCP insights: " + e.getMessage(), e);
        }
    }

    /**
     * Get Compute Engine insights
     */
    private CompletableFuture<List<PerformanceInsightDto>> getComputeEngineInsights(CloudAccount account, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            List<PerformanceInsightDto> insights = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Compute Engine insights for project {}", gcpProjectId);

            Optional<InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("InstancesClient not available for project {}", gcpProjectId);
                return insights;
            }

            try (InstancesClient client = clientOpt.get()) {
                StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(entry -> entry.getValue().getInstancesList().stream())
                        .filter(instance -> "RUNNING".equals(instance.getStatus()))
                        .forEach(instance -> {
                            try {
                                List<GcpMetricDto> cpuMetrics = gcpMetricsService.getCpuUtilization(gcpProjectId, String.valueOf(instance.getId()));
                                double avgCpu = cpuMetrics.stream().mapToDouble(GcpMetricDto::getValue).average().orElse(100.0);
                                String zone = instance.getZone().substring(instance.getZone().lastIndexOf('/') + 1);

                                // Flag underutilized instances
                                if (avgCpu < 10.0) {
                                    String severity = avgCpu < 5.0 ? "CRITICAL" : "WARNING";
                                    insights.add(new PerformanceInsightDto(
                                            "gce-" + instance.getId() + "-underutilized",
                                            "Compute Engine instance " + instance.getName() + " is underutilized (" + String.format("%.1f", avgCpu) + "% avg CPU).",
                                            "Low resource utilization detected. Consider downsizing or stopping this instance.",
                                            PerformanceInsightDto.InsightSeverity.valueOf(severity),
                                            PerformanceInsightDto.InsightCategory.COST,
                                            account.getGcpProjectId(),
                                            1,
                                            "Compute Engine",
                                            String.valueOf(instance.getId()),
                                            "Consider downsizing this instance to " + (instance.getMachineType().contains("e2") ? "smaller e2 type" : "smaller n1 type") + ". Check rightsizing recommendations.",
                                            "/docs/gce-rightsizing",
                                            50.0, // Placeholder savings
                                            zone,
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

    /**
     * Get Cloud SQL insights from optimization recommendations
     */
    private CompletableFuture<List<PerformanceInsightDto>> getCloudSqlInsights(CloudAccount account, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            List<PerformanceInsightDto> insights = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Cloud SQL insights for project {}", gcpProjectId);

            try {
                // Fetch recommendations
                List<?> rawRecs = gcpOptimizationService.getRightsizingRecommendations(gcpProjectId);

                // Convert map back to DTO if necessary (cache serialization issue)
                List<GcpOptimizationRecommendation> recs;
                if (!rawRecs.isEmpty() && rawRecs.get(0) instanceof Map) {
                    log.warn("Detected Map type from rightsizing recommendations cache. Attempting conversion.");
                    recs = ((List<Map<String, Object>>) rawRecs).stream()
                            .map(map -> objectMapper.convertValue(map, GcpOptimizationRecommendation.class))
                            .collect(Collectors.toList());
                } else {
                    recs = (List<GcpOptimizationRecommendation>) (Object) rawRecs;
                }

                // Filter Cloud SQL recommendations
                recs.stream()
                        .filter(rec -> rec != null && "Cloud SQL".equals(rec.getService()) && !rec.isCostIncrease())
                        .forEach(rec -> insights.add(new PerformanceInsightDto(
                                "sql-" + rec.getResourceName() + "-overprovisioned",
                                "Cloud SQL instance " + rec.getResourceName() + " may be overprovisioned.",
                                rec.getReasonSummary(),
                                PerformanceInsightDto.InsightSeverity.WARNING,
                                PerformanceInsightDto.InsightCategory.COST,
                                account.getGcpProjectId(),
                                1,
                                "Cloud SQL",
                                rec.getResourceName(),
                                "Consider rightsizing to " + rec.getRecommendedMachineType() + ". " + rec.getReasonSummary(),
                                "/docs/sql-rightsizing",
                                rec.getMonthlySavings(),
                                rec.getLocation(),
                                Instant.now().toString()
                        )));
            } catch (ClassCastException cce) {
                log.error("ClassCastException during Cloud SQL insights processing for project {}. Data structure might be unexpected: {}", gcpProjectId, cce.getMessage());
            } catch (Exception e) {
                log.error("Error processing Cloud SQL insights for project {}: {}", gcpProjectId, e.getMessage());
            }

            log.info("Found {} Cloud SQL insights based on recommendations for project {}", insights.size(), gcpProjectId);
            return insights;
        });
    }

    /**
     * Get Persistent Disk insights
     */
    private CompletableFuture<List<PerformanceInsightDto>> getPersistentDiskInsights(CloudAccount account, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            List<PerformanceInsightDto> insights = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Persistent Disk insights for project {}", gcpProjectId);

            Optional<DisksClient> clientOpt = gcpClientProvider.getDisksClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("DisksClient not available for project {}", gcpProjectId);
                return insights;
            }

            try (DisksClient client = clientOpt.get()) {
                StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(entry -> entry.getValue().getDisksList().stream())
                        .forEach(disk -> {
                            String zone = disk.getZone().substring(disk.getZone().lastIndexOf('/') + 1);
                            
                            // Check for unattached disks
                            if (disk.getUsersList().isEmpty()) {
                                insights.add(new PerformanceInsightDto(
                                        "disk-" + disk.getId() + "-unattached",
                                        "Persistent Disk " + disk.getName() + " is unattached.",
                                        "Unattached disks incur costs without being used.",
                                        PerformanceInsightDto.InsightSeverity.WARNING,
                                        PerformanceInsightDto.InsightCategory.COST,
                                        account.getGcpProjectId(),
                                        1,
                                        "Persistent Disk",
                                        String.valueOf(disk.getId()),
                                        "Consider deleting this disk if no longer needed, or attach it to an instance.",
                                        "/docs/gcp-disks",
                                        disk.getSizeGb() * 0.04, // Approx cost for pd-standard per GB/month
                                        zone,
                                        Instant.now().toString()
                                ));
                            }
                        });
            } catch (Exception e) {
                log.error("Error fetching Persistent Disk insights for project {}: {}", gcpProjectId, e.getMessage());
            }
            log.info("Found {} Persistent Disk insights for project {}", insights.size(), gcpProjectId);
            return insights;
        });
    }

    /**
     * Filter insights by severity
     */
    private List<PerformanceInsightDto> filterBySeverity(List<PerformanceInsightDto> insights, String severity) {
        if (insights == null) {
            return Collections.emptyList();
        }
        
        if (severity == null || severity.isEmpty() || "ALL".equalsIgnoreCase(severity)) {
            return insights;
        }
        
        try {
            PerformanceInsightDto.InsightSeverity severityEnum = PerformanceInsightDto.InsightSeverity.valueOf(severity.toUpperCase());
            return insights.stream()
                    .filter(insight -> insight != null && insight.getSeverity() == severityEnum)
                    .collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid severity filter '{}' provided. Returning all insights.", severity);
            return insights;
        }
    }

    /**
     * Get summary of GCP performance insights
     */
    public Map<String, Object> getInsightsSummary(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = "gcpInsightsSummary-" + gcpProjectId;
        
        // Try cache first
        if (!forceRefresh) {
            Optional<Map<String, Object>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                log.info("Returning cached GCP insights summary for project {}", gcpProjectId);
                return cachedData.get();
            }
        }

        log.info("Calculating fresh GCP insights summary for project {}", gcpProjectId);
        
        // Fetch all insights
        List<PerformanceInsightDto> allInsights = getInsights(gcpProjectId, "ALL", forceRefresh);

        // Build summary
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalInsights", allInsights.size());
        summary.put("critical", allInsights.stream()
                .filter(i -> i != null && i.getSeverity() == PerformanceInsightDto.InsightSeverity.CRITICAL)
                .count());
        summary.put("warning", allInsights.stream()
                .filter(i -> i != null && i.getSeverity() == PerformanceInsightDto.InsightSeverity.WARNING)
                .count());
        summary.put("weakWarning", allInsights.stream()
                .filter(i -> i != null && i.getSeverity() == PerformanceInsightDto.InsightSeverity.WEAK_WARNING)
                .count());
        summary.put("potentialSavings", allInsights.stream()
                .filter(i -> i != null && i.getCategory() == PerformanceInsightDto.InsightCategory.COST)
                .mapToDouble(PerformanceInsightDto::getPotentialSavings)
                .sum());
        summary.put("performanceScore", calculatePerformanceScore(allInsights));

        // Cache summary
        redisCache.put(cacheKey, summary, 10);
        log.info("Cached fresh GCP insights summary for project {}", gcpProjectId);
        
        return summary;
    }

    /**
     * Calculate performance score from insights
     */
    public int calculatePerformanceScore(List<PerformanceInsightDto> insights) {
        if (insights == null) {
            return 100;
        }

        int score = 100;
        int criticalWeight = 10;
        int warningWeight = 5;
        int weakWarningWeight = 2;

        for (PerformanceInsightDto insight : insights) {
            if (insight == null || insight.getSeverity() == null) {
                continue;
            }

            // Only count Cost and Performance insights
            if (insight.getCategory() == PerformanceInsightDto.InsightCategory.COST ||
                    insight.getCategory() == PerformanceInsightDto.InsightCategory.PERFORMANCE) {
                
                switch (insight.getSeverity()) {
                    case CRITICAL:
                        score -= criticalWeight;
                        break;
                    case WARNING:
                        score -= warningWeight;
                        break;
                    case WEAK_WARNING:
                        score -= weakWarningWeight;
                        break;
                    default:
                        break;
                }
            }
        }
        
        return Math.max(0, score);
    }

    /**
     * Placeholder for What-If scenario
     */
    public CompletableFuture<Object> getWhatIfScenario(String gcpProjectId, String resourceId, String targetInstanceType, boolean forceRefresh) {
        log.warn("GCP What-If Scenario not fully implemented.");
        return CompletableFuture.completedFuture(Map.of(
            "message", "GCP What-If Scenario not yet available.",
            "gcpProjectId", gcpProjectId,
            "resourceId", resourceId,
            "targetInstanceType", targetInstanceType
        ));
    }
}
