// src/main/java/com/xammer/cloud/service/gcp/GcpPerformanceInsightsService.java
package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.compute.v1.Disk;
import com.google.cloud.compute.v1.DisksClient;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.dto.gcp.GcpMetricDto;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final GcpPricingService gcpPricingService;
    private final RedisCacheService redisCache;
    private final ObjectMapper objectMapper;
    private final CachedDataRepository cachedDataRepository;

    private static final String DB_CACHE_PREFIX = "GCP_PERFORMANCE::";

    /** Compute Engine bills a 730-hour month (365 * 24 / 12). */
    private static final double HOURS_PER_MONTH = 730.0;

    /**
     * Average CPU below which an instance is treated as idle rather than merely oversized.
     *
     * <p>The distinction decides what saving can honestly be claimed: an idle instance can be
     * stopped, so the whole compute rate is recoverable, whereas an oversized one only yields the
     * difference to a smaller machine type — a figure that needs a named target to be real.
     */
    private static final double IDLE_CPU_THRESHOLD = 5.0;

    /** Average CPU below which an instance is reported as underutilized at all. */
    private static final double UNDERUTILIZED_CPU_THRESHOLD = 10.0;

    @Autowired
    public GcpPerformanceInsightsService(
            CloudAccountRepository cloudAccountRepository,
            GcpClientProvider gcpClientProvider,
            GcpMetricsService gcpMetricsService,
            @Lazy GcpDataService gcpDataService,
            @Lazy GcpOptimizationService gcpOptimizationService,
            GcpPricingService gcpPricingService,
            RedisCacheService redisCache,
            ObjectMapper objectMapper,
            CachedDataRepository cachedDataRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.gcpClientProvider = gcpClientProvider;
        this.gcpMetricsService = gcpMetricsService;
        this.gcpDataService = gcpDataService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpPricingService = gcpPricingService;
        this.redisCache = redisCache;
        this.objectMapper = objectMapper;
        this.cachedDataRepository = cachedDataRepository;
    }

    /**
     * Last path segment of a Compute Engine resource URL.
     *
     * <p>The API returns fully-qualified selfLinks ({@code .../machineTypes/e2-medium}) wherever a
     * bare name is wanted.
     */
    private static String lastSegment(String resourceUrl) {
        if (resourceUrl == null || resourceUrl.isBlank()) {
            return "";
        }
        int slash = resourceUrl.lastIndexOf('/');
        return slash < 0 ? resourceUrl : resourceUrl.substring(slash + 1);
    }

    /**
     * The region containing a zone, e.g. {@code asia-south1-a} -> {@code asia-south1}.
     *
     * <p>Pricing is published per region, while instances and zonal disks report a zone.
     */
    private static String regionOfZone(String zone) {
        if (zone == null || zone.isBlank()) {
            return "";
        }
        int lastDash = zone.lastIndexOf('-');
        return lastDash <= 0 ? zone : zone.substring(0, lastDash);
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
            log.warn("Failed to save GCP performance DB snapshot for key {}: {}", dbKey, e.getMessage());
        }
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
        String dbKey = DB_CACHE_PREFIX + gcpProjectId;

        // Try cache first if not forcing refresh
        if (!forceRefresh) {
            // 1. Redis
            Optional<List<PerformanceInsightDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                log.info("GCP Performance insights found in Redis for project {}. Filtering by severity '{}'.", gcpProjectId, severity);
                return filterBySeverity(cachedData.get(), severity);
            }

            // 2. DB snapshot
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    List<PerformanceInsightDto> dbResult = objectMapper.readValue(
                            dbSnapshot.get().getJsonData(), new TypeReference<>() {});
                    log.info("GCP Performance insights found in DB for project {} — re-warming Redis.", gcpProjectId);
                    redisCache.put(cacheKey, dbResult, 60);
                    return filterBySeverity(dbResult, severity);
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for GCP performance {}: {}", gcpProjectId, e.getMessage());
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
            redisCache.put(cacheKey, allInsights, 60);
            saveToDb(dbKey, allInsights);
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
                                if (avgCpu < UNDERUTILIZED_CPU_THRESHOLD) {
                                    boolean idle = avgCpu < IDLE_CPU_THRESHOLD;
                                    String severity = idle ? "CRITICAL" : "WARNING";
                                    String machineType = lastSegment(instance.getMachineType());
                                    String region = regionOfZone(zone);

                                    GcpPricingService.PricingInfo price = gcpPricingService
                                            .getMachineTypePricing(machineType, region, gcpProjectId);
                                    double monthlyCost = price.isPriced()
                                            ? price.getOnDemandPrice() * HOURS_PER_MONTH
                                            : 0.0;

                                    PerformanceInsightDto dto = new PerformanceInsightDto(
                                            "gce-" + instance.getId() + "-underutilized",
                                            "Compute Engine instance " + instance.getName() + " is underutilized ("
                                                    + String.format("%.1f", avgCpu) + "% avg CPU).",
                                            "Low resource utilization detected. Consider downsizing or stopping this instance.",
                                            PerformanceInsightDto.InsightSeverity.valueOf(severity),
                                            PerformanceInsightDto.InsightCategory.COST,
                                            account.getGcpProjectId(),
                                            1,
                                            "Compute Engine",
                                            String.valueOf(instance.getId()),
                                            idle
                                                    ? "Stop or delete this " + machineType + " instance. It has averaged "
                                                            + String.format("%.1f", avgCpu)
                                                            + "% CPU, so the whole compute charge is recoverable."
                                                    : "Downsize this " + machineType
                                                            + " instance. See Rightsizing for the machine type GCP's "
                                                            + "Recommender suggests and the saving that follows from it.",
                                            "/docs/gce-rightsizing",
                                            // An idle instance can be stopped, so the full compute rate is the
                                            // saving. An oversized one yields only the delta to a smaller machine
                                            // type, which needs a named target — Rightsizing owns that, and
                                            // inventing a figure here is what previously put a flat $50 against
                                            // every instance regardless of its size or region.
                                            idle ? monthlyCost : 0.0,
                                            zone,
                                            Instant.now().toString());

                                    if (!idle) {
                                        dto.withUnknownSavings("Downsizing saves the difference to a smaller "
                                                + "machine type, which depends on the target size. See Rightsizing "
                                                + "for the priced recommendation.");
                                    } else if (!price.isPriced()) {
                                        dto.withUnknownSavings("Compute Engine list price unavailable for "
                                                + machineType + " in " + region + ": " + price.getNote());
                                    }

                                    insights.add(dto);
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
                        .forEach(rec -> {
                            PerformanceInsightDto dto = new PerformanceInsightDto(
                                    "sql-" + rec.getResourceName() + "-overprovisioned",
                                    "Cloud SQL instance " + rec.getResourceName() + " may be overprovisioned.",
                                    rec.getReasonSummary(),
                                    PerformanceInsightDto.InsightSeverity.WARNING,
                                    PerformanceInsightDto.InsightCategory.COST,
                                    account.getGcpProjectId(),
                                    1,
                                    "Cloud SQL",
                                    rec.getResourceName(),
                                    "Consider rightsizing to " + rec.getRecommendedMachineType() + ". "
                                            + rec.getReasonSummary(),
                                    "/docs/sql-rightsizing",
                                    rec.getMonthlySavings(),
                                    rec.getLocation(),
                                    Instant.now().toString());

                            // GcpOptimizationService returns 0.0 as its PRICE_UNKNOWN sentinel. A
                            // downsize recommendation that saves exactly nothing is not a
                            // recommendation, so a zero here means the tier could not be priced —
                            // report that rather than showing the user "$0".
                            if (rec.getMonthlySavings() <= 0.0) {
                                dto.withUnknownSavings("The Cloud Billing Catalog did not return a rate "
                                        + "for this Cloud SQL tier, so the saving could not be priced.");
                            }

                            insights.add(dto);
                        });
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
                            // A regional disk reports no zone but does report a region, and is a
                            // separate, more expensive SKU. Reading only the zone priced those at the
                            // zonal rate.
                            boolean regional = disk.getZone() == null || disk.getZone().isBlank();
                            String zone = regional ? "" : lastSegment(disk.getZone());
                            String region = regional ? lastSegment(disk.getRegion()) : regionOfZone(zone);
                            String location = regional ? region : zone;

                            // Check for unattached disks
                            if (disk.getUsersList().isEmpty()) {
                                String diskType = lastSegment(disk.getType());
                                GcpPricingService.DiskPriceInfo price = gcpPricingService
                                        .getPersistentDiskPricing(diskType, region, regional, gcpProjectId);

                                PerformanceInsightDto dto = new PerformanceInsightDto(
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
                                        // Real per-region, per-type capacity rate from the Cloud Billing
                                        // Catalog. This was size * 0.04 — a single pd-standard rate applied
                                        // to every disk type in every region, so an SSD in a costly region
                                        // was understated several-fold.
                                        price.isPriced() ? price.getUsdPerGibMonth() * disk.getSizeGb() : 0.0,
                                        location,
                                        Instant.now().toString());

                                if (!price.isPriced()) {
                                    dto.withUnknownSavings("Persistent Disk list price unavailable for "
                                            + diskType + " in " + region + ": " + price.getNote());
                                }

                                insights.add(dto);
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
        // Only priced findings contribute. This total used to be a count of findings multiplied by
        // a flat $50 placeholder plus a guessed disk rate; it is now the sum of real Cloud Billing
        // Catalog rates, and findings that could not be priced are reported separately rather than
        // folded in as zero — so the amount and the count describe the same set.
        List<PerformanceInsightDto> costInsights = allInsights.stream()
                .filter(i -> i != null && i.getCategory() == PerformanceInsightDto.InsightCategory.COST)
                .collect(Collectors.toList());

        long unpricedCount = costInsights.stream().filter(PerformanceInsightDto::isSavingsUnknown).count();

        summary.put("potentialSavings", costInsights.stream()
                .filter(i -> !i.isSavingsUnknown())
                .mapToDouble(PerformanceInsightDto::getPotentialSavings)
                .sum());
        summary.put("pricedFindings", costInsights.size() - unpricedCount);
        summary.put("unpricedFindings", unpricedCount);
        summary.put("savingsCurrency", "USD");
        if (unpricedCount > 0) {
            summary.put("savingsIncompleteReason", unpricedCount + " of " + costInsights.size()
                    + " cost findings could not be priced, so the total is a lower bound.");
        }
        summary.put("performanceScore", calculatePerformanceScore(allInsights));

        // Cache summary
        redisCache.put(cacheKey, summary, 60);
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
