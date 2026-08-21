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
import com.xammer.cloud.service.CurrencyConversionService;
import com.xammer.cloud.service.SnapshotFreshnessService;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
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

    /**
     * Bounded pool for detector fan-out.
     *
     * <p>This was {@code Executors.newCachedThreadPool()} — unbounded and never shut down.
     * A single waste report fans out roughly {@code 4 x locations + 27} tasks, so a handful
     * of concurrent requests could create hundreds of threads with no shared ceiling. A
     * fixed pool with a bounded queue and caller-runs rejection applies backpressure
     * instead, and the pool is shut down with the bean.
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
            GCP_DETECTOR_POOL_CORE_SIZE,
            GCP_DETECTOR_POOL_MAX_SIZE,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(GCP_DETECTOR_POOL_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("gcp-optimization-" + thread.getId());
                thread.setDaemon(true);
                return thread;
            },
            // Run on the calling thread rather than dropping work when saturated.
            new ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * Memoized Cloud SQL catalog SKUs, keyed by {@code resourceType|region}.
     * An empty Optional caches a known miss so a failing lookup is not retried per call.
     */
    private final Map<String, Optional<Sku>> cloudSqlSkuCache = new ConcurrentHashMap<>();

    /**
     * Memoized BigQuery billing-export table names, keyed by project.
     * Resolving one previously enumerated every dataset and table in the project on every
     * call, once per Cloud SQL instance.
     */
    private final Map<String, Optional<String>> billingTableNameCache = new ConcurrentHashMap<>();
    private final GcpDataService gcpDataService;
    private final CloudAccountService cloudAccountService;
    private final GcpMetricsService gcpMetricsService;
    private final RedisCacheService redisCacheService;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final CurrencyConversionService currencyConversionService;
    private final SnapshotFreshnessService snapshotFreshnessService;

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
    /**
     * Estimated monthly cost of an idle load-balancer forwarding rule, in USD.
     * GCP charges roughly $0.025/hour for the first five forwarding rules.
     */
    private static final double EST_FORWARDING_RULE_MONTHLY_USD = 18.0;

    /** Cloud DNS public managed zone: $0.20/zone/month for the first 25 zones. */
    private static final double EST_DNS_ZONE_MONTHLY_USD = 0.20;

    /** Static external IP reserved but not in use: $0.010/hour. */
    private static final double EST_UNUSED_STATIC_IP_MONTHLY_USD = 0.010 * HOURS_PER_MONTH;

    /** Compute Engine snapshot storage, multi-regional: $0.026/GB/month. */
    private static final double EST_SNAPSHOT_COST_USD_PER_GB = 0.026;

    /**
     * Returned by the Cloud SQL price helpers when a rate could not be determined.
     * Callers must treat this as "unknown", not as "free".
     */
    private static final double PRICE_UNKNOWN = 0.0;

    /** TTL for the derived savings/optimization summaries, in minutes. */
    private static final int SUMMARY_CACHE_TTL_MINUTES = 30;

    /**
     * Sustained use discount applied to a Compute Engine instance running a full month.
     *
     * <p>GCE applies SUD automatically, reaching roughly 20-30% at full-month usage
     * depending on machine family. 20% is used as the conservative end so savings are not
     * understated for partially-used instances.
     */
    private static final double SUSTAINED_USE_DISCOUNT_RATE = 0.20;

    // Bounds for the detector fan-out pool. Sized for I/O-bound work: detectors spend
    // nearly all their time waiting on GCP APIs rather than on CPU.
    private static final int GCP_DETECTOR_POOL_CORE_SIZE = 16;
    private static final int GCP_DETECTOR_POOL_MAX_SIZE = 64;
    private static final int GCP_DETECTOR_POOL_QUEUE_CAPACITY = 512;

    /**
     * Minimum number of usable Cloud SQL backups always retained, regardless of age.
     * Prevents recommending deletion of an instance's only restore point.
     */
    private static final int MIN_RETAINED_SQL_BACKUPS = 2;

    /**
     * Minimum number of snapshots retained per source disk, regardless of age, so the last
     * remaining snapshot of a disk is never proposed for deletion.
     */
    private static final int MIN_RETAINED_DISK_SNAPSHOTS = 1;

    /**
     * Grace period before an unattached persistent disk or a reserved-but-unused IP is
     * reported as waste. A disk detached during maintenance, or an address reserved for a
     * pending deployment, should not be flagged immediately.
     */
    private static final int UNUSED_RESOURCE_MIN_AGE_DAYS = 7;

    /**
     * GKE cluster management fee: $0.10/hour per cluster beyond the free tier.
     * An idle cluster with zero nodes still incurs this, so reporting it as free
     * understated one of the more common sources of GCP waste.
     */
    private static final double EST_GKE_CLUSTER_MONTHLY_USD = 0.10 * HOURS_PER_MONTH;

    /** Cloud NAT gateway uptime: ~$0.044/hour per gateway, excluding data processing. */
    private static final double EST_CLOUD_NAT_MONTHLY_USD = 0.044 * HOURS_PER_MONTH;

    /** Cloud VPN tunnel: ~$0.05/hour per tunnel. */
    private static final double EST_VPN_TUNNEL_MONTHLY_USD = 0.05 * HOURS_PER_MONTH;

    /**
     * VPN tunnel states that represent a durable failure rather than a transient one.
     *
     * <p>Deliberately an allowlist. Matching "anything but ESTABLISHED" caught tunnels that were
     * still negotiating — {@code PROVISIONING}, {@code WAITING_FOR_FULL_CONFIG},
     * {@code FIRST_HANDSHAKE}, {@code ALLOCATING_RESOURCES} — and healthy HA-VPN standby
     * tunnels, which sit outside ESTABLISHED by design.
     */
    private static final Set<String> FAILED_VPN_TUNNEL_STATES = Set.of(
            "NO_INCOMING_PACKETS",
            "AUTHORIZATION_ERROR",
            "NEGOTIATION_FAILURE",
            "DEPROVISIONING",
            "FAILED",
            "REJECTED",
            "STOPPED",
            "PEER_IDENTITY_MISMATCH",
            "TS_NARROWING_NOT_ALLOWED");

    /**
     * Monthly USD savings at or above which a recommendation is surfaced as a critical
     * alert. Keeps the alert count meaningful instead of equal to the recommendation count.
     */
    private static final double CRITICAL_ALERT_SAVINGS_THRESHOLD_USD = 100.0;

    // Regex for custom tier: db-custom-<vCPU>-<RAM_MB>
    private static final Pattern CUSTOM_TIER_PATTERN = Pattern.compile("db-custom-(\\d+)-(\\d+)");

    public GcpOptimizationService(
            GcpClientProvider gcpClientProvider,
            @Lazy GcpDataService gcpDataService,
            CloudAccountService cloudAccountService,
            GcpMetricsService gcpMetricsService,
            RedisCacheService redisCacheService,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            CurrencyConversionService currencyConversionService,
            SnapshotFreshnessService snapshotFreshnessService) {
        this.gcpClientProvider = gcpClientProvider;
        this.gcpDataService = gcpDataService;
        this.cloudAccountService = cloudAccountService;
        this.gcpMetricsService = gcpMetricsService;
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.currencyConversionService = currencyConversionService;
        this.snapshotFreshnessService = snapshotFreshnessService;
    }

    /**
     * Shuts the detector pool down with the bean so it does not outlive the context.
     * The previous cached pool was never shut down.
     */
    @javax.annotation.PreDestroy
    void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
                Optional<CachedData> dbSnap = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(dbKey), dbKey);
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
            // Do not persist the result of a failed fetch. An empty commitment inventory reads
            // as "this project has no CUDs", and getCudUtilizationSummary derives and caches
            // totalCuds: 0 from it — so one transient error asserted a confident wrong answer
            // for 60 minutes in Redis and indefinitely in the DB snapshot.
            log.error("Failed to fetch CUD inventory for project {} — not caching the partial result:",
                    gcpProjectId, e);
            return discounts;
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

                        // Utilization and realized savings both require per-hour commitment
                        // consumption, which GCP only exposes through the BigQuery billing
                        // export — not through the Compute commitments API used here.
                        //
                        // This previously reported 100% for any ACTIVE commitment (so a 90%
                        // underutilized commitment looked perfect), and derived "realized
                        // savings" by multiplying resources(0).getAmount() — a vCPU count or
                        // memory quantity, not money — by 0.30/12. Both figures are left
                        // unset rather than fabricated; the DTO exposes the reason so the UI
                        // can show "requires billing export" instead of a wrong number.
                        dto.setUtilizationPercentage(null);
                        dto.setRealizedSavings(null);
                        dto.setDataUnavailableReason(
                                "Commitment utilization requires the BigQuery billing export, which is not configured.");

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
                Optional<CachedData> dbSnap = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(dbKey), dbKey);
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
                Optional<CachedData> dbSnapshot = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(dbKey), dbKey);
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

        // Adjust for discounts the customer already receives. The Recommender API quotes
        // savings against on-demand list price, so without this the figures are an
        // unqualified overstatement for any long-running or committed resource.
        applyDiscountAdjustments(gcpProjectId, validatedRecommendations);

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
        // Memoized: the underlying call enumerates the entire Cloud SQL SKU catalog and
        // filters client-side. calculateCloudSqlPrice invokes it twice (CPU + RAM), and the
        // historical-cost enhancement calls calculateCloudSqlPrice twice per recommendation,
        // giving four full catalog enumerations per recommendation with no reuse. On an
        // account with a handful of Cloud SQL instances that alone exhausted the
        // controller's 50-second budget, which surfaced to users as an empty result.
        //
        // Catalog prices change infrequently, so a process-lifetime cache keyed by
        // resource type and region is safe and collapses N enumerations into one.
        String cacheKey = resourceType + "|" + region;
        Optional<Sku> memoized = cloudSqlSkuCache.get(cacheKey);
        if (memoized != null) {
            log.debug("Cloud SQL SKU cache hit for {} in {}", resourceType, region);
            return memoized;
        }
        Optional<Sku> resolved = findCloudSqlSkuUncached(gcpProjectId, resourceType, region);
        cloudSqlSkuCache.put(cacheKey, resolved);
        return resolved;
    }

    /** Performs the actual catalog lookup. See {@link #findCloudSqlSku} for caching. */
    private Optional<Sku> findCloudSqlSkuUncached(String gcpProjectId, String resourceType, String region) {
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

        log.info("Parsed tier '{}': {} vCPUs, {} GB RAM in region '{}'",
                tier, vcpus, String.format("%.2f", ramGb), region);

        // Find SKUs using Billing Catalog API
        Optional<Sku> cpuSku = findCloudSqlSku(gcpProjectId, "CPU", region);
        Optional<Sku> ramSku = findCloudSqlSku(gcpProjectId, "RAM", region);

        if (cpuSku.isEmpty() || ramSku.isEmpty()) {
            log.warn(
                    "Could not find CPU or RAM SKUs via Billing Catalog for region: {}. Falling back to hardcoded estimate.",
                    region);
            // No catalog price available. Returning a hardcoded 2019-era us-central1 estimate
            // here silently applied those rates to every region whenever SKU description
            // matching drifted, which it does easily: the match is built from an English
            // description and a static region→city map. Returning 0.0 lets the caller treat
            // the price as unknown instead of presenting a stale figure as authoritative.
            log.warn("Cloud SQL price unavailable for tier {} in region {} — treating as unknown "
                    + "rather than substituting a stale hardcoded rate.", tier, region);
            return PRICE_UNKNOWN;
        }

        // Get prices (hourly for CPU, monthly for RAM) from SKUs
        double cpuPricePerHour = getPricePerUnit(cpuSku.orElse(null), "h");
        double ramPricePerGiBMonth = getPricePerUnit(ramSku.orElse(null), "GiBy.mo");

        // Calculate monthly USD
        double cpuMonthly = cpuPricePerHour * vcpus * HOURS_PER_MONTH;
        double ramMonthly = ramPricePerGiBMonth * ramGb;

        double totalMonthlyUsd = cpuMonthly + ramMonthly;
        log.info("Cloud SQL monthly estimate from catalog ({}): CPU ${} + RAM ${} = ${}",
                tier, String.format("%.2f", cpuMonthly), String.format("%.2f", ramMonthly),
                String.format("%.2f", totalMonthlyUsd));

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
                Optional<CachedData> dbSnapshot = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(dbKey), dbKey);
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
        fastFutures.add(findUnusedCustomImages(gcpProjectId, locations));
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
        // Tier-2 Easy: DNS, Disks, NAT, PubSub
        fastFutures.add(findEmptyDnsZones(gcpProjectId));
        // Tier-2 Medium: Autoscalers, Logging
        fastFutures.add(findIneffectiveAutoscalers(gcpProjectId, locations));
        fastFutures.add(findExcessiveLogRetention(gcpProjectId));

        List<CompletableFuture<List<GcpWasteItem>>> safeFast = fastFutures.stream()
                .map(f -> f
                        .completeOnTimeout(Collections.<GcpWasteItem>emptyList(), 45, java.util.concurrent.TimeUnit.SECONDS)
                        .exceptionally(e -> {
                            log.warn("[GcpWaste] Fast detector failed: {}", e.getMessage());
                            return Collections.<GcpWasteItem>emptyList();
                        }))
                .collect(Collectors.toList());

        List<GcpWasteItem> fastResults;
        try {
            // Group await is now just a safety net — each detector already self-limits to 45s.
            CompletableFuture.allOf(safeFast.toArray(new CompletableFuture[0]))
                    .get(50, java.util.concurrent.TimeUnit.SECONDS);
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
                findAlwaysFailingSchedulerJobs(gcpProjectId, locations),
                // Tier-2 Easy: metric-based
                findIdleAttachedDisks(gcpProjectId),
                findIdleCloudNatGateways(gcpProjectId),
                findStalePubSubSubscriptions(gcpProjectId),
                // Tier-2 Medium: metric-based
                findIdleHttpLoadBalancers(gcpProjectId)
        );

        List<CompletableFuture<List<GcpWasteItem>>> safeSlow = slowFutures.stream()
                .map(f -> f
                        .completeOnTimeout(Collections.<GcpWasteItem>emptyList(), 90, java.util.concurrent.TimeUnit.SECONDS)
                        .exceptionally(e -> {
                            log.warn("[GcpWaste] Slow detector failed: {}", e.getMessage());
                            return Collections.<GcpWasteItem>emptyList();
                        }))
                .collect(Collectors.toList());

        boolean partial = false;
        List<GcpWasteItem> slowResults;
        try {
            // Each slow detector self-limits to 90s; group await is a safety net slightly above that.
            CompletableFuture.allOf(safeSlow.toArray(new CompletableFuture[0]))
                    .get(95, java.util.concurrent.TimeUnit.SECONDS);
            slowResults = safeSlow.stream().map(CompletableFuture::join)
                    .flatMap(List::stream).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[GcpWaste] Slow group exceeded safety net — returning per-detector results: {}", e.getMessage());
            partial = true;
            slowResults = safeSlow.stream()
                    .filter(f -> f.isDone() && !f.isCompletedExceptionally())
                    .map(CompletableFuture::join)
                    .flatMap(List::stream).collect(Collectors.toList());
        }

        List<GcpWasteItem> combined = new ArrayList<>(fastResults);
        combined.addAll(slowResults);

        // Deduplicate by resourceId — the same resource can be flagged by both a
        // Recommender-based detector and a direct-SDK detector. Keep the entry with
        // the higher monthly savings so we don't double-count or show duplicates.
        java.util.Map<String, GcpWasteItem> deduped = new java.util.LinkedHashMap<>();
        for (GcpWasteItem item : combined) {
            String key = item.getResourceId() != null && !item.getResourceId().isBlank()
                    ? item.getResourceId()
                    : item.getResourceName() + "|" + item.getType();
            GcpWasteItem existing = deduped.get(key);
            if (existing == null || item.getMonthlySavings() > existing.getMonthlySavings()) {
                deduped.put(key, item);
            }
        }
        List<GcpWasteItem> allWaste = new ArrayList<>(deduped.values());
        log.info("[GcpWaste] Total: {} items ({} before dedupe, partial={}) for project {}",
                allWaste.size(), combined.size(), partial, gcpProjectId);

        saveToDb("WASTE::" + gcpProjectId + "::" + java.time.LocalDate.now(), allWaste);

        com.xammer.cloud.dto.gcp.GcpWasteReport report =
                new com.xammer.cloud.dto.gcp.GcpWasteReport(allWaste, partial);
        redisCacheService.put(cacheKey, report, 60);
        saveToDb(dbKey, report);
        return report;
    }

    /**
     * Potential savings summary for a project.
     *
     * <p>Cached in Redis rather than with Spring's {@code @Cacheable}, matching the pattern
     * used elsewhere in this class. The annotation was previously removed outright, leaving
     * the reconciliation below to run on every dashboard load.
     */
    public DashboardData.SavingsSummary getSavingsSummary(String gcpProjectId) {
        String summaryCacheKey = "gcp:savings-summary:" + gcpProjectId;
        Optional<DashboardData.SavingsSummary> cachedSummary =
                redisCacheService.get(summaryCacheKey, DashboardData.SavingsSummary.class);
        if (cachedSummary.isPresent()) {
            return cachedSummary.get();
        }

        List<GcpWasteItem> waste = getWasteReport(gcpProjectId);
        List<GcpOptimizationRecommendation> rightsizing = getRightsizingRecommendations(gcpProjectId);

        double wasteSavings = sumWasteSavings(waste);
        double rightsizingSavings = sumRightsizingSavings(rightsizing, waste);

        List<DashboardData.SavingsSuggestion> suggestions = new ArrayList<>();
        if (rightsizingSavings > 0)
            suggestions.add(new DashboardData.SavingsSuggestion("Rightsizing", rightsizingSavings));
        if (wasteSavings > 0)
            suggestions.add(new DashboardData.SavingsSuggestion("Waste Elimination", wasteSavings));

        // Amounts are USD (GcpWasteItem.BASE_CURRENCY). realized = 0 because these are
        // potential savings from recommendations that have not been applied.
        DashboardData.SavingsSummary summary =
                new DashboardData.SavingsSummary(0, wasteSavings + rightsizingSavings, suggestions);
        redisCacheService.put(summaryCacheKey, summary, SUMMARY_CACHE_TTL_MINUTES);
        return summary;
    }

    /**
     * Sums waste savings, skipping items whose cost could not be determined so an
     * unpriced finding is never counted as zero-cost.
     */
    private double sumWasteSavings(List<GcpWasteItem> waste) {
        // Excludes both unpriced items and genuinely-free ones. Free resources carry a real
        // zero, so summing them is harmless, but filtering here keeps this total consistent
        // with the billable count the UI shows beside it.
        return waste.stream()
                .filter(GcpWasteItem::isBillableWaste)
                .mapToDouble(GcpWasteItem::getMonthlySavings)
                .sum();
    }

    /**
     * Sums rightsizing savings while excluding resources already counted in the waste
     * list.
     *
     * <p>Without this, a single under-used VM contributes twice: the idle-resource
     * recommender books its full cost as waste, and the machine-type recommender books a
     * downsize delta for the same VM. Deleting a resource and resizing it are mutually
     * exclusive, so the waste figure (the larger, full-cost one) wins and the rightsizing
     * delta is dropped.
     */
    private double sumRightsizingSavings(List<GcpOptimizationRecommendation> rightsizing,
                                          List<GcpWasteItem> waste) {
        Set<String> wasteResourceKeys = new HashSet<>();
        for (GcpWasteItem item : waste) {
            for (String candidate : new String[] { item.getResourceId(), item.getResourceName() }) {
                if (candidate != null && !candidate.isBlank()) {
                    wasteResourceKeys.add(shortResourceKey(candidate));
                }
            }
        }

        double total = 0.0;
        for (GcpOptimizationRecommendation rec : rightsizing) {
            if (rec.getMonthlySavings() < 0) {
                continue; // performance recommendations increase cost
            }
            String key = rec.getResourceName() == null ? null : shortResourceKey(rec.getResourceName());
            if (key != null && wasteResourceKeys.contains(key)) {
                log.debug("Excluding rightsizing savings for {} — already counted as waste", rec.getResourceName());
                continue;
            }
            total += rec.getMonthlySavings();
        }
        return total;
    }

    /** Normalises a resource identifier to its trailing segment for overlap comparison. */
    private String shortResourceKey(String resource) {
        String trimmed = resource.trim();
        int slash = trimmed.lastIndexOf('/');
        return (slash >= 0 && slash < trimmed.length() - 1 ? trimmed.substring(slash + 1) : trimmed)
                .toLowerCase();
    }

    /**
     * Optimization summary for a project. Cached like {@link #getSavingsSummary}.
     */
    public DashboardData.OptimizationSummary getOptimizationSummary(String gcpProjectId) {
        String summaryCacheKey = "gcp:optimization-summary:" + gcpProjectId;
        Optional<DashboardData.OptimizationSummary> cachedSummary =
                redisCacheService.get(summaryCacheKey, DashboardData.OptimizationSummary.class);
        if (cachedSummary.isPresent()) {
            return cachedSummary.get();
        }

        List<GcpWasteItem> waste = getWasteReport(gcpProjectId);
        List<GcpOptimizationRecommendation> rightsizing = getRightsizingRecommendations(gcpProjectId);

        // Same overlap reconciliation as getSavingsSummary, so the two tiles agree.
        double totalSavings = sumWasteSavings(waste) + sumRightsizingSavings(rightsizing, waste);

        // Only high-impact findings count as alerts. Previously every recommendation was
        // counted, so a project with 40 routine downsize suggestions reported 40 critical
        // alerts.
        long criticalAlerts = rightsizing.stream()
                .filter(r -> r.getMonthlySavings() >= CRITICAL_ALERT_SAVINGS_THRESHOLD_USD)
                .count();

        DashboardData.OptimizationSummary summary =
                new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
        redisCacheService.put(summaryCacheKey, summary, SUMMARY_CACHE_TTL_MINUTES);
        return summary;
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
                Optional<CachedData> dbSnap = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(dbKey), dbKey);
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
     *
     * <p>Closed months are immutable and read straight from their per-month snapshot. The
     * rollup for the <em>current</em> month is freshness-gated, because that month is still
     * accumulating: it was previously frozen at whatever value it held the first time the
     * endpoint was called.
     */
    public List<Map<String, Object>> getHistoricalCudData(String gcpProjectId, boolean forceRefresh) {
        String thisMonthKey = "GCP_CUD_HISTORICAL::" + gcpProjectId + "::" + YearMonth.now();

        if (!forceRefresh) {
            try {
                Optional<CachedData> existing = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(thisMonthKey), thisMonthKey);
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
                // No freshness gate here: these are per-calendar-month snapshots of closed
                // months, which genuinely do not change once written.
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
                Optional<CachedData> dbSnap = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(dbKey), dbKey);
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
                // Paginated: nextPageToken was previously ignored, so projects with more
                // Cloud SQL instances than one page silently lost the remainder — which
                // surfaced as fewer waste items rather than as an error.
                List<DatabaseInstance> allInstances = listAllCloudSqlInstances(sqlAdmin, gcpProjectId);

                if (allInstances.isEmpty()) {
                    log.info("No Cloud SQL instances found for project {}.", gcpProjectId);
                    return List.of();
                }

                for (DatabaseInstance instance : allInstances) {
                    String instanceName = instance.getName();
                    try {
                        List<BackupRun> backups = listAllBackupRuns(sqlAdmin, gcpProjectId, instanceName);
                        Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);

                        if (backups != null && !backups.isEmpty()) {
                            List<BackupRun> agedBackups = backups.stream()
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
                                    // Never propose deleting a failed/pending run as if it were a
                                    // usable restore point.
                                    .filter(backup -> backup.getStatus() == null
                                            || "SUCCESSFUL".equalsIgnoreCase(backup.getStatus()))
                                    // Newest first, so the retention guard below keeps the most
                                    // recent restore points.
                                    .sorted(Comparator.comparing(
                                            (BackupRun b) -> safeParseInstant(b.getWindowStartTime()),
                                            Comparator.nullsLast(Comparator.reverseOrder())))
                                    .collect(Collectors.toList());

                            long usableBackups = backups.stream()
                                    .filter(b -> b.getStatus() == null || "SUCCESSFUL".equalsIgnoreCase(b.getStatus()))
                                    .count();

                            // Always retain a minimum number of restore points. Without this the
                            // detector flagged every backup older than 90 days, including the only
                            // backup an instance had — recommending deletion of the sole recovery
                            // point for a database.
                            long deletable = Math.max(0, usableBackups - MIN_RETAINED_SQL_BACKUPS);
                            if (deletable == 0) {
                                log.debug("Instance {} has only {} usable backup(s); retaining all "
                                        + "(minimum {} kept)", instanceName, usableBackups, MIN_RETAINED_SQL_BACKUPS);
                            } else {
                                // Skip the newest MIN_RETAINED_SQL_BACKUPS entries.
                                agedBackups.stream()
                                        .skip(Math.min(MIN_RETAINED_SQL_BACKUPS, agedBackups.size()))
                                        .limit(deletable)
                                        .map(backup -> new GcpWasteItem(
                                                backup.getId().toString(),
                                                instanceName + "/backup-" + backup.getId(),
                                                "Old Cloud SQL Backup (>90 days)",
                                                instance.getRegion(),
                                                EST_BACKUP_COST_USD,
                                                String.format("Backup is older than 90 days. %d newer restore "
                                                        + "point(s) are retained.", MIN_RETAINED_SQL_BACKUPS)))
                                        .forEach(wasteItems::add);
                            }
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
                        // Kubernetes PersistentVolume disks read as unattached while their pod is
                        // rescheduled, and deleting one destroys the volume's data. GKE names them
                        // pvc-<uuid> and labels them goog-gke-volume. Azure guards this; GCP did
                        // not.
                        .filter(disk -> !isGkeVolume(disk.getName(), disk.getLabelsMap()))
                        // A disk detached moments ago during maintenance or a migration is not
                        // waste. Note this measures creation time, which is the only timestamp
                        // the Disks API exposes — GCP does not report a detach time — so a
                        // long-lived disk detached a minute ago is still reported. The GKE and
                        // label guards above carry most of the protection.
                        .filter(disk -> !isWithinGracePeriod(disk.getCreationTimestamp()))
                        .map(disk -> {
                            String zoneUrl = disk.getZone();
                            String zone = zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1);
                            return new GcpWasteItem(
                                    disk.getName(),
                                    disk.getName(),
                                    "Unattached Persistent Disk",
                                    zone,
                                    calculateDiskCostUsd(disk.getSizeGb()),
                                    String.format("Disk has no users attached and has existed for more than %d days",
                                            UNUSED_RESOURCE_MIN_AGE_DAYS)
                            );
                        })
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to list unattached disks for project {}:", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }

    /**
     * Image names referenced as a boot source by any instance template in the project.
     *
     * <p>Deprecating an image does not stop a template from naming it, and Compute Engine will
     * happily delete an image a template still points at. Nothing fails at delete time — the
     * template breaks later, when a managed instance group next tries to create an instance from
     * it. That surfaces as autoscaling and auto-healing silently ceasing to work, which is a far
     * worse outcome than an error, so these images are excluded.
     *
     * @return the referenced image names, or empty when the templates could not be enumerated
     */
    private Optional<java.util.Set<String>> imageNamesUsedByInstanceTemplates(String gcpProjectId,
            List<String> locations) {

        java.util.Set<String> referenced = new java.util.HashSet<>();
        boolean anyEnumerated = false;

        Optional<com.google.cloud.compute.v1.InstanceTemplatesClient> globalOpt =
                gcpClientProvider.getInstanceTemplatesClient(gcpProjectId);
        if (globalOpt.isPresent()) {
            try (var client = globalOpt.get()) {
                for (var template : client.list(gcpProjectId).iterateAll()) {
                    collectTemplateSourceImages(template, referenced);
                }
                anyEnumerated = true;
            } catch (Exception e) {
                log.warn("[GcpWaste] Could not list global instance templates for {}: {}",
                        gcpProjectId, e.getMessage());
            }
        }

        Optional<com.google.cloud.compute.v1.RegionInstanceTemplatesClient> regionalOpt =
                gcpClientProvider.getRegionInstanceTemplatesClient(gcpProjectId);
        if (regionalOpt.isPresent()) {
            try (var client = regionalOpt.get()) {
                for (String region : locations) {
                    try {
                        for (var template : client.list(gcpProjectId, region).iterateAll()) {
                            collectTemplateSourceImages(template, referenced);
                        }
                        anyEnumerated = true;
                    } catch (Exception e) {
                        log.debug("[GcpWaste] Regional instance templates unavailable in {}: {}",
                                region, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] Could not list regional instance templates for {}: {}",
                        gcpProjectId, e.getMessage());
            }
        }

        // A partial view is not usable here: an image missing from an incomplete set looks unused.
        return anyEnumerated ? Optional.of(referenced) : Optional.empty();
    }

    private void collectTemplateSourceImages(com.google.cloud.compute.v1.InstanceTemplate template,
            java.util.Set<String> into) {
        for (var disk : template.getProperties().getDisksList()) {
            if (!disk.hasInitializeParams()) {
                continue;
            }
            String sourceImage = disk.getInitializeParams().getSourceImage();
            if (sourceImage != null && !sourceImage.isBlank()) {
                // The value is a full or partial URL; the image name is its last segment.
                into.add(sourceImage.substring(sourceImage.lastIndexOf('/') + 1));
            }
        }
    }

    private CompletableFuture<List<GcpWasteItem>> findUnusedCustomImages(String gcpProjectId,
            List<String> locations) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Checking for unused custom images for project: {}", gcpProjectId);
            Optional<ImagesClient> clientOpt = gcpClientProvider.getImagesClient(gcpProjectId);
            if (clientOpt.isEmpty())
                return List.of();

            Optional<java.util.Set<String>> templateImages =
                    imageNamesUsedByInstanceTemplates(gcpProjectId, locations);
            if (templateImages.isEmpty()) {
                log.warn("[GcpWaste] Instance templates could not be enumerated for {} — skipping the "
                        + "deprecated image check rather than recommending deletion of an image a "
                        + "template may still boot from", gcpProjectId);
                return List.of();
            }
            java.util.Set<String> inUse = templateImages.get();

            try (ImagesClient client = clientOpt.get()) {
                return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                        .filter(image -> image.hasDeprecated() && "DEPRECATED".equals(image.getDeprecated().getState()))
                        .filter(image -> {
                            if (inUse.contains(image.getName())) {
                                log.debug("[GcpWaste] Image {} is deprecated but still referenced by an "
                                        + "instance template", image.getName());
                                return false;
                            }
                            return true;
                        })
                        .map(image -> new GcpWasteItem(
                                image.getName(),
                                image.getName(),
                                "Deprecated Custom Image",
                                "global",
                                EST_IMAGE_STORAGE_COST_USD_PER_GB * image.getDiskSizeGb(),
                                "Image is in DEPRECATED state and no instance template boots from it"))
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
                        // Firewall rules are free; a hygiene finding, not a saving.
                        .map(firewall -> GcpWasteItem.withNoCostImpact(
                                firewall.getName(),
                                firewall.getName(),
                                "Disabled Firewall Rule",
                                "global",
                                "Firewall rule is explicitly disabled"))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to list firewall rules for project {}:", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }

    // Gap 3: Old Compute Engine snapshots (>90 days)
    /**
     * Gigabytes a snapshot actually stores.
     *
     * <p>Compute Engine snapshots are incremental: {@code storageBytes} is what is billed, while
     * {@code diskSizeGb} is the provisioned size of the source disk. Falls back to the disk size
     * only when {@code storageBytes} is unavailable, since overstating is safer than reporting
     * zero cost for a snapshot that does occupy space.
     */
    private double snapshotStoredGb(com.google.cloud.compute.v1.Snapshot snap) {
        if (snap.hasStorageBytes() && snap.getStorageBytes() > 0) {
            return snap.getStorageBytes() / (1024.0 * 1024 * 1024);
        }
        return snap.getDiskSizeGb();
    }

    private CompletableFuture<List<GcpWasteItem>> findOldComputeSnapshots(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<SnapshotsClient> clientOpt = gcpClientProvider.getSnapshotsClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (SnapshotsClient client = clientOpt.get()) {
                Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);

                // Group by source disk so a retention guard can be applied per disk. Without
                // it the detector flagged every snapshot older than 90 days, including the last
                // remaining snapshot of a disk — recommending deletion of the only restore
                // point that disk had.
                Map<String, List<com.google.cloud.compute.v1.Snapshot>> bySourceDisk = new HashMap<>();
                for (var snap : client.list(gcpProjectId).iterateAll()) {
                    // A blank sourceDisk means the source disk has been deleted, so this snapshot
                    // may be the only remaining copy of that data. Keying them all under one
                    // "(unknown)" bucket defeated the retention guard: MIN_RETAINED_DISK_SNAPSHOTS
                    // kept ONE snapshot across every unrelated deleted disk in the project and
                    // offered the rest for deletion. Key by the snapshot's own name so each is
                    // treated as its own sole restore point and retained.
                    String sourceDisk = snap.getSourceDisk() == null || snap.getSourceDisk().isBlank()
                            ? "(orphaned)" + snap.getName()
                            : snap.getSourceDisk();
                    bySourceDisk.computeIfAbsent(sourceDisk, k -> new ArrayList<>()).add(snap);
                }

                List<GcpWasteItem> items = new ArrayList<>();
                for (Map.Entry<String, List<com.google.cloud.compute.v1.Snapshot>> entry : bySourceDisk.entrySet()) {
                    List<com.google.cloud.compute.v1.Snapshot> snapshots = entry.getValue();

                    List<com.google.cloud.compute.v1.Snapshot> aged = snapshots.stream()
                            .filter(snap -> {
                                Instant created = safeParseInstant(snap.getCreationTimestamp());
                                return created != null && created.isBefore(cutoff);
                            })
                            // Newest first, so the guard below keeps the most recent snapshots.
                            .sorted(Comparator.comparing(
                                    (com.google.cloud.compute.v1.Snapshot sn) ->
                                            safeParseInstant(sn.getCreationTimestamp()),
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                            .collect(Collectors.toList());

                    long deletable = Math.max(0, snapshots.size() - MIN_RETAINED_DISK_SNAPSHOTS);
                    if (deletable == 0) {
                        log.debug("[GcpWaste] Retaining all {} snapshot(s) of {} (minimum {} kept)",
                                snapshots.size(), entry.getKey(), MIN_RETAINED_DISK_SNAPSHOTS);
                        continue;
                    }

                    aged.stream()
                            .skip(Math.min(MIN_RETAINED_DISK_SNAPSHOTS, aged.size()))
                            .limit(deletable)
                            .map(snap -> new GcpWasteItem(
                                    snap.getName(),
                                    snap.getName(),
                                    "Old Compute Snapshot",
                                    "global",
                                    // GCP snapshots are incremental and billed on the bytes they
                                    // actually store, not on the source disk's provisioned size.
                                    // Using diskSizeGb overstated a 500 GB disk's incremental
                                    // snapshot — which may hold only a few GB of deltas — by two
                                    // orders of magnitude.
                                    snapshotStoredGb(snap) * EST_SNAPSHOT_COST_USD_PER_GB,
                                    String.format("Snapshot is older than 90 days. %d newer snapshot(s) of the "
                                            + "same disk are retained.", MIN_RETAINED_DISK_SNAPSHOTS)))
                            .forEach(items::add);
                }
                return items;
            } catch (Exception e) {
                log.warn("[GcpWaste] findOldComputeSnapshots failed for {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    // Gap 3: Reserved (unassigned) external IP addresses
    /**
     * Whether a persistent disk was provisioned by GKE for a PersistentVolume.
     *
     * <p>GKE names dynamically provisioned disks {@code pvc-<uuid>} and labels them
     * {@code goog-gke-volume}. Such a disk shows no users while its pod is being rescheduled,
     * so an unattached check alone will offer a live volume for deletion.
     */
    private boolean isGkeVolume(String diskName, Map<String, String> labels) {
        if (diskName != null && diskName.toLowerCase().startsWith("pvc-")) {
            return true;
        }
        if (labels != null) {
            for (String key : labels.keySet()) {
                if (key == null) continue;
                String k = key.toLowerCase();
                if (k.startsWith("goog-gke-") || k.startsWith("kubernetes.io/")
                        || k.startsWith("k8s-")) {
                    return true;
                }
            }
        }
        return false;
    }

    private CompletableFuture<List<GcpWasteItem>> findReservedUnusedIps(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<AddressesClient> clientOpt = gcpClientProvider.getAddressesClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (AddressesClient client = clientOpt.get()) {
                return StreamSupport.stream(
                        client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(entry -> entry.getValue().getAddressesList().stream())
                        .filter(addr -> "RESERVED".equals(addr.getStatus()))
                        // Only external addresses carry the unused-IP charge. Internal ranges
                        // reserved for Private Services Access (purpose VPC_PEERING) sit in
                        // RESERVED permanently by design and carry load — they back Cloud SQL and
                        // Memorystore private connectivity — so flagging them proposed breaking
                        // private access for a saving that does not exist.
                        .filter(addr -> !"INTERNAL".equalsIgnoreCase(addr.getAddressType()))
                        .filter(addr -> {
                            String purpose = addr.getPurpose() == null ? "" : addr.getPurpose();
                            return !purpose.contains("VPC_PEERING")
                                    && !purpose.contains("PRIVATE_SERVICE_CONNECT")
                                    && !purpose.contains("SHARED_LOADBALANCER_VIP");
                        })
                        // An address reserved seconds ago for a pending deployment is not waste.
                        .filter(addr -> !isWithinGracePeriod(addr.getCreationTimestamp()))
                        .map(addr -> new GcpWasteItem(
                                addr.getName(),
                                addr.getAddress(),
                                "Reserved Unused IP",
                                addr.getRegion().contains("/") ? addr.getRegion().substring(addr.getRegion().lastIndexOf('/') + 1) : addr.getRegion(),
                                EST_UNUSED_STATIC_IP_MONTHLY_USD,
                                String.format("Static IP is RESERVED but not assigned to any resource, and has been "
                                        + "reserved for more than %d days", UNUSED_RESOURCE_MIN_AGE_DAYS)))
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
                        // Pub/Sub bills throughput and retained message storage, not the
                        // existence of a topic. A topic with no subscriptions retains nothing,
                        // so there is no standing charge to reclaim.
                        items.add(GcpWasteItem.withNoCostImpact(
                                topicName,
                                shortName,
                                "Idle Pub/Sub Topic",
                                "global",
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

        // Convert USD → INR so waste items are consistent with direct-SDK detectors
        // (which already use INR constants) and the rest of the GCP cost UI.
        // Waste items are denominated in USD (GcpWasteItem.BASE_CURRENCY); conversion to a
        // display currency happens once at the presentation boundary.

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

        // Recommendation amounts stay in USD internally (GcpOptimizationRecommendation
        // documents monthlySavings as USD). The Recommender API normally reports USD; if a
        // project is billed in another currency we convert back to USD so that every
        // recommendation in the list shares one unit and can legitimately be summed.
        double monthlySavingsBase = toUsd(monthlySavingsUsd, currencyCode, rec.getName());

        String location = extractLocation(rec.getName());
        String recommendationType = resolveRecommendationType(rec.getName(), monthlySavingsBase);
        String description = rec.getDescription();

        return new GcpOptimizationRecommendation(
                resourceName,
                currentMachineType,
                recommendedMachineType,
                monthlySavingsBase,
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

        // Recommendation amounts stay in USD internally (GcpOptimizationRecommendation
        // documents monthlySavings as USD). The Recommender API normally reports USD; if a
        // project is billed in another currency we convert back to USD so that every
        // recommendation in the list shares one unit and can legitimately be summed.
        double monthlySavingsBase = toUsd(monthlySavingsUsd, currencyCode, rec.getName());

        String location = extractLocation(rec.getName());

        return new GcpOptimizationRecommendation(
                resourceName,
                currentTier, // Use tier name extracted from description initially
                recommendedTier,
                monthlySavingsBase,
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

        // Recommendation amounts stay in USD internally (GcpOptimizationRecommendation
        // documents monthlySavings as USD). The Recommender API normally reports USD; if a
        // project is billed in another currency we convert back to USD so that every
        // recommendation in the list shares one unit and can legitimately be summed.
        double monthlySavingsBase = toUsd(monthlySavingsUsd, currencyCode, rec.getName());

        log.info("Final CUD savings: ${} (original currency: {})",
                String.format("%.2f", monthlySavingsBase), currencyCode);

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
                monthlySavingsBase,
                "Commitment", // Service category
                location,
                rec.getName(),
                "COST_SAVINGS",
                description // Full original description as reason
        );
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
     * Validates that a rightsizing apply is permitted, without performing it.
     *
     * <p>Callers that dispatch {@link #applyRightsizing} asynchronously must invoke this on
     * the request thread first, so an authorization failure becomes a {@code 403} instead of
     * a {@code 202} followed by a silently swallowed exception on an executor thread.
     *
     * @throws SecurityException        when the account lacks read-write access
     * @throws IllegalArgumentException when the account or target machine type is invalid
     */
    public void verifyRightsizingAllowed(String gcpProjectId, String zone, String instanceName,
                                          String targetMachineType) {
        if (zone == null || zone.isBlank()) {
            throw new IllegalArgumentException("zone is required");
        }
        if (instanceName == null || instanceName.isBlank()) {
            throw new IllegalArgumentException("instanceName is required");
        }
        // GCE machine types are lowercase alphanumerics with dashes, e.g. e2-standard-4, or a
        // custom form like e2-custom-4-8192. Validate before it reaches a URL path.
        if (targetMachineType == null || !targetMachineType.matches("^[a-z0-9]+(-[a-z0-9]+)+$")) {
            throw new IllegalArgumentException("Invalid target machine type: " + targetMachineType);
        }

        CloudAccount account = cloudAccountService.findByGcpProjectId(gcpProjectId);
        if (account == null) {
            throw new IllegalArgumentException("Account not found for GCP project: " + gcpProjectId);
        }
        if (!"read-write".equals(account.getAccessType())) {
            throw new SecurityException("Account " + gcpProjectId
                    + " does not have read-write access. Rightsizing is not permitted.");
        }
    }

    /**
     * Applies GCE rightsizing: stop → modify machine type → start.
     * Validates read-write access before proceeding.
     * Runs synchronously in the calling thread — caller should wrap in @Async.
     */
    public void applyRightsizing(String gcpProjectId, String zone, String instanceName, String targetMachineType) {
        // Re-checked here as well as in verifyRightsizingAllowed, so this stays safe when
        // invoked directly rather than through the controller.
        verifyRightsizingAllowed(gcpProjectId, zone, instanceName, targetMachineType);

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
            // Fail closed. This previously allowed every downsize through when monitoring was
            // unavailable — precisely the case where an unjustified downsize causes an
            // outage. A recommendation with no evidence behind it is not surfaced as
            // actionable.
            log.info("Filtering out {} recommendation for {}: no utilization metrics available to justify it",
                    recommendationType, recommendation.getResourceName());
            return false;
        }

        if ("COST_SAVINGS".equals(recommendationType)) {
            // Peak guard. maxCpu/maxMemory were previously collected and then ignored, so the
            // gate ran on averages alone: a resource averaging 10% but peaking at 95% passed
            // as "underutilized" and the downsize would clip its peak.
            if (maxCpu != null && maxCpu > HIGH_CPU_THRESHOLD) {
                log.info("Filtering out downgrade recommendation for {}: peak CPU ({}%) exceeds "
                                + "the safe ceiling ({}%) despite a low average",
                        recommendation.getResourceName(), String.format("%.1f", maxCpu),
                        String.format("%.1f", HIGH_CPU_THRESHOLD));
                return false;
            }
            if (maxMemory != null && maxMemory > HIGH_MEMORY_THRESHOLD) {
                log.info("Filtering out downgrade recommendation for {}: peak memory ({}%) exceeds "
                                + "the safe ceiling ({}%) despite a low average",
                        recommendation.getResourceName(), String.format("%.1f", maxMemory),
                        String.format("%.1f", HIGH_MEMORY_THRESHOLD));
                return false;
            }

            // Downgrade recommendation - validate that resource is actually underutilized
            if (avgCpu != null && avgCpu > LOW_CPU_THRESHOLD) {
                log.info(
                        "Filtering out downgrade recommendation for {}: CPU utilization ({}%) is above threshold ({}%)",
                        recommendation.getResourceName(), String.format("%.1f", avgCpu),
                        String.format("%.1f", LOW_CPU_THRESHOLD));
                return false;
            }
            if (avgMemory != null && avgMemory > LOW_MEMORY_THRESHOLD) {
                log.info(
                        "Filtering out downgrade recommendation for {}: memory utilization ({}%) is above threshold ({}%)",
                        recommendation.getResourceName(), String.format("%.1f", avgMemory),
                        String.format("%.1f", LOW_MEMORY_THRESHOLD));
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
                        "Filtering out upgrade recommendation for {}: CPU ({}%) and memory ({}%) are below thresholds",
                        recommendation.getResourceName(),
                        String.format("%.1f", avgCpu != null ? avgCpu : 0.0),
                        String.format("%.1f", avgMemory != null ? avgMemory : 0.0));
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
    /**
     * Normalises a Recommender API cost impact to USD, the base currency for everything
     * produced by this service.
     *
     * @param amount       amount as reported by the API
     * @param currencyCode currency the API reported, may be null
     * @param context      recommendation name, for logging an unsupported currency
     * @return the amount expressed in USD
     */
    private double toUsd(double amount, String currencyCode, String context) {
        if (currencyCode == null || currencyCode.isBlank() || "USD".equalsIgnoreCase(currencyCode)) {
            return amount;
        }
        if ("INR".equalsIgnoreCase(currencyCode)) {
            return currencyConversionService.inrToUsd(amount);
        }
        log.warn("Unsupported currency {} in recommendation {}; treating the amount as USD unconverted.",
                currencyCode, context);
        return amount;
    }

    /**
     * Determines recommendation intent from the recommender that produced it.
     *
     * <p>This used to be inferred from the sign of the savings figure, which
     * misclassified any recommendation lacking a cost projection (savings 0.0) as
     * {@code COST_SAVINGS} — and those then passed the downsize validation gate without
     * any cost justification.
     *
     * @param recommendationName full recommender resource name
     * @param monthlySavingsUsd  normalised savings, used only as a last resort
     */
    private String resolveRecommendationType(String recommendationName, double monthlySavingsUsd) {
        String name = recommendationName == null ? "" : recommendationName.toLowerCase();
        if (name.contains("performance")) {
            return "PERFORMANCE_IMPROVEMENT";
        }
        if (name.contains("machinetyperecommender")
                || name.contains("idleresourcerecommender")
                || name.contains("overprovisioned")
                || name.contains("costrecommender")) {
            return "COST_SAVINGS";
        }
        if (monthlySavingsUsd > 0) {
            return "COST_SAVINGS";
        }
        if (monthlySavingsUsd < 0) {
            return "PERFORMANCE_IMPROVEMENT";
        }
        // No cost projection and no recognisable recommender: classify as neutral so it is
        // not silently treated as a justified downsize.
        return "OPTIMIZATION";
    }

    /**
     * Parses an RFC-3339 timestamp, returning null rather than throwing.
     */
    private Instant safeParseInstant(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(timestamp).toInstant();
            } catch (Exception ignored) {
                log.debug("Could not parse timestamp '{}'", timestamp);
                return null;
            }
        }
    }

    /**
     * True when a resource was created within the unused-resource grace period.
     *
     * <p>Used to avoid flagging a disk detached seconds ago during maintenance, or an IP
     * reserved moments ago for a pending deployment.
     *
     * @param creationTimestamp RFC-3339 creation timestamp from the Compute API
     */
    private boolean isWithinGracePeriod(String creationTimestamp) {
        Instant created = safeParseInstant(creationTimestamp);
        if (created == null) {
            // Unknown age: do not suppress, but the caller's own checks still apply.
            return false;
        }
        return created.isAfter(Instant.now().minus(UNUSED_RESOURCE_MIN_AGE_DAYS, ChronoUnit.DAYS));
    }

    /** Estimated monthly persistent-disk cost in USD. */
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
                            dto.setMonthlySavings(calculatedSavingsUsd);
                            log.info("Populated 'monthlySavings' from price diff: ${} ({} -> {})",
                                    String.format("%.2f", calculatedSavingsUsd), actualCurrentTier, rawRecommendedTier);
                        } else {
                            log.warn(
                                    "Fallback price difference for COST_SAVINGS is not positive (Current: ${}, Rec: ${}). Keeping savings as 0.",
                                    String.format("%.2f", currentPriceUsd), String.format("%.2f", recommendedPriceUsd));
                        }
                    } else if ("PERFORMANCE_IMPROVEMENT".equals(dto.getRecommendationType())) {
                        double calculatedCostIncreaseUsd = recommendedPriceUsd - currentPriceUsd;
                        if (calculatedCostIncreaseUsd > 0) {
                            dto.setMonthlySavings(-calculatedCostIncreaseUsd); // Negative: this raises cost
                            log.info("Populated 'monthlySavings' (cost increase) from price diff: ${} ({} -> {})",
                                    String.format("%.2f", -calculatedCostIncreaseUsd), actualCurrentTier,
                                    rawRecommendedTier);
                        } else {
                            log.warn(
                                    "Fallback price difference for PERFORMANCE_IMPROVEMENT is not positive (Current: ${}, Rec: ${}). Keeping cost increase as 0.",
                                    String.format("%.2f", currentPriceUsd), String.format("%.2f", recommendedPriceUsd));
                        }
                    }
                } else if (dto.getMonthlySavings() != 0.0) {
                    log.info("Using monthly savings/cost value derived from Recommender API for '{}': ${}",
                            instanceName, String.format("%.2f", dto.getMonthlySavings()));
                }

                // Descriptions carry USD prices, matching the DTO's documented unit.
                dto.setCurrentMachineType(buildReadableTierDescription(actualCurrentTier, currentPriceUsd));
                dto.setRecommendedMachineType(buildReadableTierDescription(rawRecommendedTier, recommendedPriceUsd));

                log.info("Enhanced recommendation for '{}': savings/cost ${}/mo, last 30d cost ${}/mo",
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

            log.info("Querying 30-day cost for Cloud SQL instance: {}", instanceName);

            // Try the partitioned form first, then fall back to usage_start_time.
            //
            // Only partitioned billing-export tables expose _PARTITIONTIME. On a
            // non-partitioned table (or a newer detailed export) that query throws, which
            // previously propagated as -1.0 and rendered as a blank actual-cost column with
            // no indication that billing export was misconfigured. Both forms are valid
            // against their respective table layouts, so try each before giving up.
            TableResult results = null;
            BigQueryException lastError = null;
            for (String dateColumn : List.of("_PARTITIONTIME", "usage_start_time")) {
                String query = buildCloudSqlCostQuery(tableNameOpt.get(), dateColumn, startDate, today,
                        gcpProjectId, sqlInstanceLabelKey, instanceName);
                log.debug("BigQuery SQL ({}): {}", dateColumn, query);
                try {
                    results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
                    break;
                } catch (BigQueryException e) {
                    lastError = e;
                    log.debug("Cloud SQL cost query using {} failed for {}: {}",
                            dateColumn, instanceName, e.getMessage());
                }
            }
            if (results == null) {
                throw lastError != null ? lastError
                        : new IllegalStateException("Cloud SQL cost query produced no result");
            }

            double totalCostUsd = 0.0;
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

                    // Billing export rows are normalised to USD, the base unit for this service.
                    totalCostUsd += toUsd(costValue, currency, instanceName);
                }
            }

            if (dataFound) {
                log.info("Total Cloud SQL 30-day cost for '{}': ${}",
                        instanceName, String.format("%.2f", totalCostUsd));
                return totalCostUsd;
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

    /**
     * Adjusts recommendation savings for discounts already applied to the project.
     *
     * <p>The Recommender API quotes savings against on-demand list price. Two GCP discount
     * mechanisms make that an overstatement:
     *
     * <ul>
     *   <li><b>Sustained use discounts</b> apply automatically to Compute Engine and reach
     *       roughly 20-30% for an instance that runs a full month. A downsize therefore
     *       saves proportionally less than list price implies.</li>
     *   <li><b>Committed use discounts</b> are prepaid. Downsizing a resource covered by a
     *       resource-based commitment yields no billing change at all until the commitment
     *       expires — the committed spend is owed regardless.</li>
     * </ul>
     *
     * <p>{@code getCommittedUseDiscountsSync} already inventories commitments but its output
     * was never consulted by the recommender, so commitment-covered resources were quoted
     * full savings. Adjusted amounts carry an explanation so the figure is auditable.
     */
    private void applyDiscountAdjustments(String gcpProjectId,
                                           List<GcpOptimizationRecommendation> recommendations) {
        if (recommendations.isEmpty()) {
            return;
        }

        // Regions with an active resource-based commitment. A downsize there is not
        // immediately realizable.
        Set<String> committedRegions = new HashSet<>();
        try {
            for (GcpCommittedUseDiscountDto cud : getCommittedUseDiscountsSync(gcpProjectId)) {
                if (cud.getRegion() != null && !cud.getRegion().isBlank()
                        && "ACTIVE".equalsIgnoreCase(cud.getStatus())) {
                    committedRegions.add(cud.getRegion().toLowerCase());
                }
            }
        } catch (Exception e) {
            log.warn("[GcpRightsizing] Could not load commitments for {}; savings will not be "
                    + "commitment-adjusted: {}", gcpProjectId, e.getMessage());
        }

        for (GcpOptimizationRecommendation rec : recommendations) {
            if (rec.getMonthlySavings() <= 0) {
                continue;
            }

            String location = rec.getLocation() == null ? "" : rec.getLocation().toLowerCase();
            String region = location.matches("^[a-z]+-[a-z]+\\d-[a-z]$")
                    ? location.substring(0, location.lastIndexOf('-'))
                    : location;

            if (committedRegions.contains(region)) {
                // Committed spend is owed whether or not the resource is downsized.
                rec.setListPriceMonthlySavings(rec.getMonthlySavings());
                rec.setMonthlySavings(0.0);
                rec.setSavingsQualifier(String.format(
                        "Region %s has an active committed use discount. Downsizing will not reduce the bill "
                                + "until the commitment expires, because committed spend is prepaid.", region));
                continue;
            }

            if ("Compute Engine".equals(rec.getService())) {
                double listPriceSavings = rec.getMonthlySavings();
                double adjusted = listPriceSavings * (1.0 - SUSTAINED_USE_DISCOUNT_RATE);
                rec.setListPriceMonthlySavings(listPriceSavings);
                rec.setMonthlySavings(Math.round(adjusted * 100.0) / 100.0);
                rec.setSavingsQualifier(String.format(
                        "Adjusted down by %.0f%% for the sustained use discount that already applies to a "
                                + "full-month Compute Engine instance. List-price saving would be $%.2f.",
                        SUSTAINED_USE_DISCOUNT_RATE * 100, listPriceSavings));
            }
        }
    }

    /**
     * Maximum pages followed by the Google-API-client pagination helpers below, as a guard
     * against an unbounded loop if a token were ever echoed back unchanged.
     */
    private static final int MAX_REST_PAGES = 50;

    /** Lists every Cloud SQL instance in a project, following {@code nextPageToken}. */
    private List<DatabaseInstance> listAllCloudSqlInstances(SQLAdmin sqlAdmin, String gcpProjectId)
            throws java.io.IOException {
        List<DatabaseInstance> all = new ArrayList<>();
        String pageToken = null;
        int pages = 0;
        do {
            InstancesListResponse response = sqlAdmin.instances().list(gcpProjectId)
                    .setPageToken(pageToken)
                    .execute();
            if (response.getItems() != null) {
                all.addAll(response.getItems());
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank() && ++pages < MAX_REST_PAGES);
        return all;
    }

    /** Lists every backup run for an instance, following {@code nextPageToken}. */
    private List<BackupRun> listAllBackupRuns(SQLAdmin sqlAdmin, String gcpProjectId, String instanceName)
            throws java.io.IOException {
        List<BackupRun> all = new ArrayList<>();
        String pageToken = null;
        int pages = 0;
        do {
            var response = sqlAdmin.backupRuns().list(gcpProjectId, instanceName)
                    .setPageToken(pageToken)
                    .execute();
            if (response.getItems() != null) {
                all.addAll(response.getItems());
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank() && ++pages < MAX_REST_PAGES);
        return all;
    }

    /** Lists every Cloud DNS managed zone, following {@code nextPageToken}. */
    private List<com.google.api.services.dns.model.ManagedZone> listAllDnsZones(
            com.google.api.services.dns.Dns dns, String gcpProjectId) throws java.io.IOException {
        List<com.google.api.services.dns.model.ManagedZone> all = new ArrayList<>();
        String pageToken = null;
        int pages = 0;
        do {
            var response = dns.managedZones().list(gcpProjectId).setPageToken(pageToken).execute();
            if (response.getManagedZones() != null) {
                all.addAll(response.getManagedZones());
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank() && ++pages < MAX_REST_PAGES);
        return all;
    }

    /** Lists every record set in a zone, following {@code nextPageToken}. */
    private List<com.google.api.services.dns.model.ResourceRecordSet> listAllDnsRecords(
            com.google.api.services.dns.Dns dns, String gcpProjectId, String zoneName)
            throws java.io.IOException {
        List<com.google.api.services.dns.model.ResourceRecordSet> all = new ArrayList<>();
        String pageToken = null;
        int pages = 0;
        do {
            var response = dns.resourceRecordSets().list(gcpProjectId, zoneName)
                    .setPageToken(pageToken)
                    .execute();
            if (response.getRrsets() != null) {
                all.addAll(response.getRrsets());
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank() && ++pages < MAX_REST_PAGES);
        return all;
    }

    /**
     * Builds the 30-day Cloud SQL cost query against a billing-export table.
     *
     * @param dateColumn either {@code _PARTITIONTIME} (partitioned tables) or
     *                   {@code usage_start_time} (non-partitioned / detailed exports)
     */
    private String buildCloudSqlCostQuery(String tableName, String dateColumn, LocalDate startDate,
                                           LocalDate today, String gcpProjectId,
                                           String sqlInstanceLabelKey, String instanceName) {
        return String.format(
                "SELECT SUM(cost) as total_cost, currency "
                        + "FROM `%s` "
                        + "WHERE DATE(%s) >= '%s' "
                        + "  AND DATE(%s) < '%s' "
                        + "  AND service.description = 'Cloud SQL' "
                        + "  AND project.id = '%s' "
                        + "  AND EXISTS (SELECT 1 FROM UNNEST(labels) AS l WHERE l.key = '%s' AND l.value = '%s') "
                        + "  AND cost > 0 "
                        + "GROUP BY currency",
                tableName,
                dateColumn, startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                // Upper bound is exclusive, so add a day to include today.
                dateColumn, today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE),
                gcpProjectId,
                sqlInstanceLabelKey,
                instanceName);
    }

    /**
     * Finds the BigQuery billing-export table name for a project.
     *
     * <p>Memoized per project: the discovery below enumerates every dataset and every table
     * in the project, and it was previously invoked once per Cloud SQL instance on every
     * request. An empty Optional is cached too, so a project without billing export does
     * not re-scan its whole BigQuery inventory on each call.
     */
    private Optional<String> getBillingTableName(BigQuery bigquery, String gcpProjectId) {
        Optional<String> memoized = billingTableNameCache.get(gcpProjectId);
        if (memoized != null) {
            return memoized;
        }
        Optional<String> resolved = discoverBillingTableName(bigquery, gcpProjectId);
        billingTableNameCache.put(gcpProjectId, resolved);
        return resolved;
    }

    /** Performs the dataset/table scan. See {@link #getBillingTableName} for caching. */
    private Optional<String> discoverBillingTableName(BigQuery bigquery, String gcpProjectId) {
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
                            // A MIG resource itself is free.
                            items.add(GcpWasteItem.withNoCostImpact(igm.getName(), igm.getName(),
                                    "Empty Instance Group", zone,
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
                boolean proxyScanComplete = true;

                if (proxyOpt.isPresent()) {
                    try (TargetHttpsProxiesClient proxyClient = proxyOpt.get()) {
                        StreamSupport.stream(proxyClient.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                                .flatMap(e -> e.getValue().getTargetHttpsProxiesList().stream())
                                .forEach(proxy -> usedCertSelfLinks.addAll(proxy.getSslCertificatesList()));
                    } catch (Exception e) {
                        log.warn("[GcpWaste] Could not list TargetHttpsProxies: {}", e.getMessage());
                        proxyScanComplete = false;
                    }
                } else {
                    proxyScanComplete = false;
                }

                // A certificate can also be attached to a TargetSslProxy, used by TCP/SSL
                // proxy load balancers. Checking only HTTPS proxies reported those certificates
                // as unused while they were terminating live traffic.
                Optional<com.google.cloud.compute.v1.TargetSslProxiesClient> sslProxyOpt =
                        gcpClientProvider.getTargetSslProxiesClient(gcpProjectId);
                if (sslProxyOpt.isPresent()) {
                    try (var sslProxyClient = sslProxyOpt.get()) {
                        for (var proxy : sslProxyClient.list(gcpProjectId).iterateAll()) {
                            usedCertSelfLinks.addAll(proxy.getSslCertificatesList());
                        }
                    } catch (Exception e) {
                        log.warn("[GcpWaste] Could not list TargetSslProxies: {}", e.getMessage());
                        proxyScanComplete = false;
                    }
                } else {
                    proxyScanComplete = false;
                }

                // Without a complete picture of what references each certificate, "unused" cannot
                // be established. Reporting anyway would flag live certificates.
                if (!proxyScanComplete) {
                    log.info("[GcpWaste] Skipping unused-SSL-certificate detection for {} — the set of "
                            + "certificate consumers could not be enumerated in full", gcpProjectId);
                    return List.of();
                }
                List<GcpWasteItem> items = new ArrayList<>();
                StreamSupport.stream(certClient.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                        .flatMap(e -> e.getValue().getSslCertificatesList().stream())
                        .filter(cert -> !usedCertSelfLinks.contains(cert.getSelfLink()))
                        // Classic Compute SSL certificates carry no charge.
                        .forEach(cert -> items.add(GcpWasteItem.withNoCostImpact(
                                cert.getName(), cert.getName(),
                                "Unused SSL Certificate", "global",
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
                        // Only durably-failed tunnels. "Not ESTABLISHED" also matched tunnels that
                        // are mid-negotiation (PROVISIONING, WAITING_FOR_FULL_CONFIG,
                        // FIRST_HANDSHAKE, ALLOCATING_RESOURCES) and healthy HA-VPN standby
                        // tunnels, so a tunnel being brought up was reported as waste and
                        // deleting it would have destroyed a config negotiated with an on-prem
                        // peer.
                        .filter(t -> FAILED_VPN_TUNNEL_STATES.contains(
                                t.getStatus() == null ? "" : t.getStatus().toUpperCase()))
                        .forEach(t -> {
                            String regionUrl = t.getRegion();
                            String region = regionUrl.contains("/") ? regionUrl.substring(regionUrl.lastIndexOf('/') + 1) : regionUrl;
                            items.add(new GcpWasteItem(t.getName(), t.getName(),
                                    "Down VPN Tunnel", region, EST_VPN_TUNNEL_MONTHLY_USD,
                                    "VPN tunnel status is " + t.getStatus()
                                            + " — a durable failure, not a transient handshake state"));
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
                            // Target pools are free.
                            items.add(GcpWasteItem.withNoCostImpact(tp.getName(), tp.getName(),
                                    "Empty Target Pool", region,
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
                                // There is no idle compute charge, but a deployed function
                                // still stores its source archive and container image, so the
                                // cost is real rather than zero. Reported as unpriced because
                                // the artifact size is not available here.
                                items.add(GcpWasteItem.withUnknownSavings(fn.getName(), fnName,
                                        "Cloud Function (0 invocations)", location,
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
                    // getInitialNodeCount() is the value the pool was CREATED with, not its
                    // current size. Reading it meant a live, autoscaled production cluster whose
                    // pools were created with 0 initial nodes was reported as an idle cluster —
                    // and a cluster scaled to zero overnight was missed. instanceGroupUrls
                    // reflects the pool's actual managed instance groups.
                    int totalNodes = 0;
                    boolean sizeKnown = false;
                    for (var pool : cluster.getNodePoolsList()) {
                        if (pool.getInstanceGroupUrlsCount() > 0) {
                            sizeKnown = true;
                        }
                        totalNodes += Math.max(pool.getInitialNodeCount(),
                                pool.getInstanceGroupUrlsCount() > 0 ? 1 : 0);
                    }

                    // Autoscaling pools legitimately sit at zero; only report a cluster with no
                    // node pools at all, which cannot be running anything.
                    boolean hasAutoscalingPool = cluster.getNodePoolsList().stream()
                            .anyMatch(p -> p.hasAutoscaling() && p.getAutoscaling().getEnabled());

                    if (cluster.getNodePoolsCount() == 0) {
                        items.add(new GcpWasteItem(cluster.getName(), cluster.getName(),
                                "Idle GKE Cluster", cluster.getLocation(), EST_GKE_CLUSTER_MONTHLY_USD,
                                "Cluster has no node pools — it can run no workloads while still "
                                        + "incurring the cluster management fee"));
                    } else if (totalNodes == 0 && !sizeKnown && !hasAutoscalingPool) {
                        items.add(new GcpWasteItem(cluster.getName(), cluster.getName(),
                                "Idle GKE Cluster", cluster.getLocation(), EST_GKE_CLUSTER_MONTHLY_USD,
                                "Cluster has 0 nodes across all node pools and no autoscaler that "
                                        + "could add them"));
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
                            // A dataset with no tables stores nothing, so BigQuery bills
                            // nothing for it — free, rather than unpriced.
                            items.add(GcpWasteItem.withNoCostImpact(datasetId, datasetId,
                                    "Empty BigQuery Dataset", "global",
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
                            items.add(GcpWasteItem.withUnknownSavings(bucket.getName(), bucket.getName(),
                                    "GCS Bucket (No Lifecycle Policy)", "global",
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

    /**
     * The most recent package push in a repository, or empty when it cannot be determined.
     *
     * <p>A {@code Repository}'s own {@code updateTime} moves when its labels, cleanup policies or
     * encryption settings change — not when an image is pushed. So a repository holding a
     * two-year-old image looked fresh if someone edited a label yesterday, and a repository being
     * pushed to daily looked stale because nobody had touched its configuration. Package
     * {@code updateTime} is the field that actually tracks pushes.
     */
    private Optional<Instant> newestArtifactPush(
            com.google.devtools.artifactregistry.v1.ArtifactRegistryClient client, String repositoryName) {
        try {
            Instant newest = null;
            for (var pkg : client.listPackages(repositoryName).iterateAll()) {
                var updated = pkg.getUpdateTime();
                if (updated.getSeconds() <= 0) {
                    continue;
                }
                Instant at = Instant.ofEpochSecond(updated.getSeconds(), updated.getNanos());
                if (newest == null || at.isAfter(newest)) {
                    newest = at;
                }
            }
            return Optional.ofNullable(newest);
        } catch (Exception e) {
            log.debug("[GcpWaste] Could not list packages in {}: {}", repositoryName, e.getMessage());
            return Optional.empty();
        }
    }

    // Artifact Registry repos holding images nobody has pushed in 60 days.
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
                            String repoName = repo.getName().substring(repo.getName().lastIndexOf('/') + 1);

                            // Artifact Registry charges for stored bytes. An empty repository
                            // costs nothing, so deleting it saves nothing — it was previously
                            // reported alongside repositories holding real data.
                            if (repo.getSizeBytes() <= 0) {
                                log.debug("[GcpWaste] Artifact repo {} stores no bytes — no cost to reclaim",
                                        repo.getName());
                                continue;
                            }

                            Optional<Instant> lastPush = newestArtifactPush(client, repo.getName());
                            if (lastPush.isEmpty()) {
                                // Either the repository has no packages despite reporting a size,
                                // or the list call failed. Neither is evidence of staleness, and
                                // deleting a repository destroys every image in it.
                                log.debug("[GcpWaste] Could not establish last push for {} — not reported",
                                        repo.getName());
                                continue;
                            }

                            if (lastPush.get().isBefore(cutoff)) {
                                items.add(GcpWasteItem.withUnknownSavings(repo.getName(), repoName,
                                        "Stale Artifact Registry Repo", location,
                                        String.format("No image pushed since %s. Holds %.2f GB. Pull "
                                                + "activity is not visible on this API, so confirm nothing "
                                                + "deploys from it before deleting.",
                                                lastPush.get().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                                                repo.getSizeBytes() / 1_073_741_824.0)));
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

    /**
     * gRPC status codes that mean the job's target is misconfigured rather than briefly
     * unavailable, so the next attempt will fail for the same reason.
     *
     * <p>Cloud Scheduler exposes only the outcome of the most recent attempt — there is no
     * attempt history on the API — so a permanent error class is the only evidence available
     * that a failure will repeat. Without this distinction one unlucky {@code UNAVAILABLE} on a
     * healthy job was enough to recommend deleting it.
     */
    private static final java.util.Set<Integer> PERMANENT_SCHEDULER_FAILURE_CODES = java.util.Set.of(
            3,  // INVALID_ARGUMENT     — the request the job sends is malformed
            5,  // NOT_FOUND            — the target URL or Pub/Sub topic is gone
            7,  // PERMISSION_DENIED    — the job's service account lost access
            9,  // FAILED_PRECONDITION  — the target rejects the request outright
            12, // UNIMPLEMENTED        — the endpoint does not accept this method
            16  // UNAUTHENTICATED      — the job's credentials are invalid
    );

    /**
     * How stale a last attempt may be before it is no longer evidence about the job's current
     * state. A job whose schedule has fired many times since would have a newer attempt time,
     * so an old one means the job is not really running on its schedule.
     */
    private static final int SCHEDULER_ATTEMPT_FRESHNESS_DAYS = 30;

    // Cloud Scheduler jobs whose target is broken — firing on schedule but unable to succeed.
    private CompletableFuture<List<GcpWasteItem>> findAlwaysFailingSchedulerJobs(
            String gcpProjectId, List<String> locations) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.cloud.scheduler.v1.CloudSchedulerClient> clientOpt =
                    gcpClientProvider.getSchedulerClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            List<GcpWasteItem> items = new ArrayList<>();
            Instant attemptCutoff = Instant.now().minus(SCHEDULER_ATTEMPT_FRESHNESS_DAYS, ChronoUnit.DAYS);
            try (com.google.cloud.scheduler.v1.CloudSchedulerClient client = clientOpt.get()) {
                for (String location : locations) {
                    String parent = "projects/" + gcpProjectId + "/locations/" + location;
                    try {
                        for (var job : client.listJobs(parent).iterateAll()) {
                            var lastAttempt = job.getLastAttemptTime();
                            if (lastAttempt.getSeconds() <= 0 || job.getStatus().getCode() == 0) {
                                // Never attempted, or the last attempt succeeded.
                                continue;
                            }

                            // A paused or disabled job is not attempting anything, so its stored
                            // status is left over from before it was paused. Reporting it as
                            // "failing" described a job that is not running at all.
                            if (job.getState() != com.google.cloud.scheduler.v1.Job.State.ENABLED) {
                                log.debug("[GcpWaste] Scheduler job {} is {} — its last status is "
                                        + "historical, not current", job.getName(), job.getState());
                                continue;
                            }

                            Instant attemptedAt = Instant.ofEpochSecond(
                                    lastAttempt.getSeconds(), lastAttempt.getNanos());
                            if (attemptedAt.isBefore(attemptCutoff)) {
                                log.debug("[GcpWaste] Scheduler job {} last attempted {} — too stale "
                                        + "to describe its current state", job.getName(), attemptedAt);
                                continue;
                            }

                            int code = job.getStatus().getCode();
                            if (!PERMANENT_SCHEDULER_FAILURE_CODES.contains(code)) {
                                // DEADLINE_EXCEEDED, UNAVAILABLE, RESOURCE_EXHAUSTED and the like
                                // are transient. The API gives no attempt history, so one of these
                                // is not evidence the job always fails.
                                log.debug("[GcpWaste] Scheduler job {} last failed with transient code {} "
                                        + "— not reported", job.getName(), code);
                                continue;
                            }

                            String jobName = job.getName().substring(job.getName().lastIndexOf('/') + 1);
                            // Cloud Scheduler bills per job per month beyond a small free
                            // allowance, so a permanently failing job is a real recurring
                            // charge — not free, as the previous 0.0 implied. The exact
                            // rate depends on how many jobs the project already has
                            // against the free tier, so it is reported as unpriced.
                            items.add(GcpWasteItem.withUnknownSavings(job.getName(), jobName,
                                    "Failing Scheduler Job", location,
                                    String.format("Last attempt failed with a permanent error (code %d): %s. "
                                            + "The target is misconfigured, so retries cannot succeed.",
                                            code, job.getStatus().getMessage())));
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

    // ── Tier-2 Easy ──────────────────────────────────────────────────────────

    // Cloud DNS managed zones containing only default NS/SOA records — billed but unconfigured.
    private CompletableFuture<List<GcpWasteItem>> findEmptyDnsZones(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.api.services.dns.Dns> clientOpt = gcpClientProvider.getDnsZonesClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            List<GcpWasteItem> items = new ArrayList<>();
            try {
                com.google.api.services.dns.Dns dns = clientOpt.get();
                List<com.google.api.services.dns.model.ManagedZone> zones = listAllDnsZones(dns, gcpProjectId);
                if (zones.isEmpty()) return List.of();
                for (com.google.api.services.dns.model.ManagedZone zone : zones) {
                    // A peering or forwarding zone resolves names by delegating elsewhere, so it
                    // holds no records of its own by design. Reporting one as empty proposed
                    // deleting the mechanism that makes hybrid or cross-VPC resolution work.
                    if (zone.getPeeringConfig() != null) {
                        log.debug("[GcpWaste] Skipping DNS peering zone {} — record-less by design",
                                zone.getName());
                        continue;
                    }
                    if (zone.getForwardingConfig() != null) {
                        log.debug("[GcpWaste] Skipping DNS forwarding zone {} — record-less by design",
                                zone.getName());
                        continue;
                    }
                    // Reverse-lookup and service-directory zones are likewise populated elsewhere.
                    if (zone.getServiceDirectoryConfig() != null) {
                        log.debug("[GcpWaste] Skipping Service Directory DNS zone {}", zone.getName());
                        continue;
                    }

                    List<com.google.api.services.dns.model.ResourceRecordSet> records =
                            listAllDnsRecords(dns, gcpProjectId, zone.getName());
                    // A null record list means the lookup failed, which is not the same as the
                    // zone being empty.
                    if (records == null) {
                        log.debug("[GcpWaste] Could not list records for DNS zone {} — not reporting it",
                                zone.getName());
                        continue;
                    }
                    boolean onlyDefaults = records.stream()
                            .allMatch(r -> "NS".equals(r.getType()) || "SOA".equals(r.getType()));
                    if (onlyDefaults) {
                        items.add(new GcpWasteItem(
                                zone.getName(), zone.getDnsName(),
                                "Empty Cloud DNS Zone", "global",
                                EST_DNS_ZONE_MONTHLY_USD,
                                "Zone contains only default NS/SOA records"));
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findEmptyDnsZones failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }

    // Persistent Disks attached to an instance but with zero read/write I/O over the lookback window.
    private CompletableFuture<List<GcpWasteItem>> findIdleAttachedDisks(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<DisksClient> clientOpt = gcpClientProvider.getDisksClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            List<GcpWasteItem> items = new ArrayList<>();
            try (DisksClient client = clientOpt.get()) {
                for (var entry : client.aggregatedList(gcpProjectId).iterateAll()) {
                    for (var disk : entry.getValue().getDisksList()) {
                        if (disk.getUsersList().isEmpty()) continue;
                        String zoneUrl = disk.getZone();
                        if (zoneUrl == null || zoneUrl.isEmpty()) continue;
                        String zone = zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1);
                        try {
                            List<com.xammer.cloud.dto.gcp.GcpMetricDto> readSeries = gcpMetricsService
                                    .getDiskReadIops(gcpProjectId, disk.getName(), zone, LOOKBACK_DAYS * 24, 3600);
                            List<com.xammer.cloud.dto.gcp.GcpMetricDto> writeSeries = gcpMetricsService
                                    .getDiskWriteIops(gcpProjectId, disk.getName(), zone, LOOKBACK_DAYS * 24, 3600);

                            // Summing an empty series yields exactly 0.0, so a Cloud Monitoring
                            // failure was indistinguishable from a genuinely idle disk — and the
                            // disk was then reported as deletable. Require actual datapoints.
                            if (readSeries.isEmpty() && writeSeries.isEmpty()) {
                                log.debug("[GcpWaste] No I/O datapoints for disk {}; cannot conclude it is idle",
                                        disk.getName());
                                continue;
                            }

                            double readOps = readSeries.stream()
                                    .mapToDouble(com.xammer.cloud.dto.gcp.GcpMetricDto::getValue).sum();
                            double writeOps = writeSeries.stream()
                                    .mapToDouble(com.xammer.cloud.dto.gcp.GcpMetricDto::getValue).sum();
                            if (readOps == 0.0 && writeOps == 0.0) {
                                items.add(new GcpWasteItem(
                                        disk.getName(), disk.getName(),
                                        "Idle Attached Disk", zone,
                                        calculateDiskCostUsd(disk.getSizeGb()),
                                        "Disk has had zero read/write I/O over the last " + LOOKBACK_DAYS + " days"));
                            }
                        } catch (Exception e) {
                            log.debug("[GcpWaste] Could not fetch I/O metrics for disk {}: {}", disk.getName(), e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findIdleAttachedDisks failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }

    // Cloud NAT gateways with zero sent/received bytes — likely unused egress paths.
    private CompletableFuture<List<GcpWasteItem>> findIdleCloudNatGateways(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<RoutersClient> routersOpt = gcpClientProvider.getRoutersClient(gcpProjectId);
            Optional<MetricServiceClient> metricsOpt = gcpClientProvider.getMetricServiceClient(gcpProjectId);
            if (routersOpt.isEmpty() || metricsOpt.isEmpty()) return List.of();

            List<GcpWasteItem> items = new ArrayList<>();
            try (RoutersClient routersClient = routersOpt.get();
                 MetricServiceClient metricsClient = metricsOpt.get()) {

                com.google.protobuf.Timestamp end = com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond()).build();
                com.google.protobuf.Timestamp start = com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS).getEpochSecond()).build();

                for (var entry : routersClient.aggregatedList(gcpProjectId).iterateAll()) {
                    for (var router : entry.getValue().getRoutersList()) {
                        String regionUrl = router.getRegion();
                        String region = regionUrl.contains("/") ? regionUrl.substring(regionUrl.lastIndexOf('/') + 1) : regionUrl;
                        for (var nat : router.getNatsList()) {
                            boolean hasTraffic = false;
                            for (String metricType : List.of(
                                    "router.googleapis.com/nat/sent_bytes_count",
                                    "router.googleapis.com/nat/received_bytes_count")) {
                                String filter = String.format(
                                        "metric.type=\"%s\" AND resource.labels.router_id=\"%s\" AND resource.labels.gateway_name=\"%s\"",
                                        metricType, router.getName(), nat.getName());
                                var req = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                                        .setName("projects/" + gcpProjectId)
                                        .setFilter(filter)
                                        .setInterval(com.google.monitoring.v3.TimeInterval.newBuilder()
                                                .setStartTime(start).setEndTime(end).build())
                                        .setView(com.google.monitoring.v3.ListTimeSeriesRequest.TimeSeriesView.HEADERS)
                                        .build();
                                if (metricsClient.listTimeSeries(req).iterateAll().iterator().hasNext()) {
                                    hasTraffic = true;
                                    break;
                                }
                            }
                            if (!hasTraffic) {
                                items.add(new GcpWasteItem(
                                        router.getName() + "/" + nat.getName(), nat.getName(),
                                        "Idle Cloud NAT Gateway", region, EST_CLOUD_NAT_MONTHLY_USD,
                                        "No NAT traffic (sent/received bytes) in the last " + LOOKBACK_DAYS + " days"));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findIdleCloudNatGateways failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }

    // Pub/Sub subscriptions with no pull/push activity in 30 days — backlog may be accruing storage cost.
    private CompletableFuture<List<GcpWasteItem>> findStalePubSubSubscriptions(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.cloud.pubsub.v1.SubscriptionAdminClient> subOpt =
                    gcpClientProvider.getPubSubSubscriptionClient(gcpProjectId);
            Optional<MetricServiceClient> metricsOpt = gcpClientProvider.getMetricServiceClient(gcpProjectId);
            if (subOpt.isEmpty() || metricsOpt.isEmpty()) return List.of();

            List<GcpWasteItem> items = new ArrayList<>();
            try (com.google.cloud.pubsub.v1.SubscriptionAdminClient subClient = subOpt.get();
                 MetricServiceClient metricsClient = metricsOpt.get()) {

                com.google.protobuf.Timestamp end = com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond()).build();
                com.google.protobuf.Timestamp start = com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(Instant.now().minus(30, ChronoUnit.DAYS).getEpochSecond()).build();

                String projectPath = com.google.pubsub.v1.ProjectName.of(gcpProjectId).toString();
                for (com.google.pubsub.v1.Subscription sub : subClient.listSubscriptions(projectPath).iterateAll()) {
                    String subName = sub.getName();
                    String shortName = subName.substring(subName.lastIndexOf('/') + 1);
                    boolean isPush = sub.hasPushConfig() && !sub.getPushConfig().getPushEndpoint().isEmpty();
                    String metricType = isPush
                            ? "pubsub.googleapis.com/subscription/push_request_count"
                            : "pubsub.googleapis.com/subscription/pull_request_count";
                    String filter = String.format(
                            "metric.type=\"%s\" AND resource.labels.subscription_id=\"%s\"",
                            metricType, shortName);
                    var req = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                            .setName("projects/" + gcpProjectId)
                            .setFilter(filter)
                            .setInterval(com.google.monitoring.v3.TimeInterval.newBuilder()
                                    .setStartTime(start).setEndTime(end).build())
                            .setView(com.google.monitoring.v3.ListTimeSeriesRequest.TimeSeriesView.HEADERS)
                            .build();
                    boolean hasActivity = metricsClient.listTimeSeries(req).iterateAll().iterator().hasNext();
                    if (!hasActivity) {
                        items.add(GcpWasteItem.withUnknownSavings(
                                subName, shortName,
                                "Idle Pub/Sub Subscription", "global",
                                "No pull/push activity in the last 30 days"));
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findStalePubSubSubscriptions failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }

    // ── Tier-2 Medium ─────────────────────────────────────────────────────────

    // Autoscalers where minNumReplicas == maxNumReplicas — autoscaling never fires.
    private CompletableFuture<List<GcpWasteItem>> findIneffectiveAutoscalers(String gcpProjectId, List<String> locations) {
        return CompletableFuture.supplyAsync(() -> {
            List<GcpWasteItem> items = new ArrayList<>();

            Optional<AutoscalersClient> zonalOpt = gcpClientProvider.getAutoscalersClient(gcpProjectId);
            if (zonalOpt.isPresent()) {
                try (AutoscalersClient client = zonalOpt.get()) {
                    for (var entry : client.aggregatedList(gcpProjectId).iterateAll()) {
                        String zoneUrl = entry.getKey();
                        String zone = zoneUrl.contains("/") ? zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1) : zoneUrl;
                        for (Autoscaler as : entry.getValue().getAutoscalersList()) {
                            collectIneffectiveAutoscaler(items, as, zone);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[GcpWaste] findIneffectiveAutoscalers (zonal) failed for {}: {}", gcpProjectId, e.getMessage());
                }
            }

            Optional<RegionAutoscalersClient> regionalOpt = gcpClientProvider.getRegionAutoscalersClient(gcpProjectId);
            if (regionalOpt.isPresent()) {
                try (RegionAutoscalersClient client = regionalOpt.get()) {
                    for (String region : locations) {
                        try {
                            for (Autoscaler as : client.list(gcpProjectId, region).iterateAll()) {
                                collectIneffectiveAutoscaler(items, as, region);
                            }
                        } catch (Exception e) {
                            log.debug("[GcpWaste] Regional autoscalers unavailable for region {}: {}", region, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("[GcpWaste] findIneffectiveAutoscalers (regional) failed for {}: {}", gcpProjectId, e.getMessage());
                }
            }
            return items;
        }, executor);
    }

    private void collectIneffectiveAutoscaler(List<GcpWasteItem> items, Autoscaler as, String location) {
        if (!as.hasAutoscalingPolicy()) return;
        var policy = as.getAutoscalingPolicy();
        if (policy.getMinNumReplicas() > 0 && policy.getMinNumReplicas() == policy.getMaxNumReplicas()) {
            // An autoscaler resource is free. The waste is the over-provisioned instances it
            // pins, which the rightsizing detectors price separately.
            items.add(GcpWasteItem.withNoCostImpact(
                    as.getName(), as.getName(),
                    "Ineffective Autoscaler (Min=Max)", location,
                    "Min and max replicas are both " + policy.getMinNumReplicas() + " — autoscaling has no effect"));
        }
    }

    // Cloud Logging buckets with retention beyond 30 days — each extra day incurs storage cost.
    private CompletableFuture<List<GcpWasteItem>> findExcessiveLogRetention(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.cloud.logging.v2.ConfigClient> clientOpt = gcpClientProvider.getConfigClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            List<GcpWasteItem> items = new ArrayList<>();
            try (com.google.cloud.logging.v2.ConfigClient client = clientOpt.get()) {
                String parent = String.format("projects/%s/locations/-", gcpProjectId);
                for (com.google.logging.v2.LogBucket bucket : client.listBuckets(parent).iterateAll()) {
                    if (bucket.getRetentionDays() <= 30) continue;
                    String name = bucket.getName();
                    if (name.endsWith("/_Required")) continue;

                    // A locked bucket's retention cannot be shortened by any API call — the only
                    // way out is deleting the bucket, and buckets are locked precisely to stop
                    // that. Recommending a reduction here produced advice that cannot be carried
                    // out, usually against a bucket someone locked for a compliance requirement.
                    if (bucket.getLocked()) {
                        log.debug("[GcpWaste] Log bucket {} is locked — retention cannot be reduced", name);
                        continue;
                    }

                    String[] parts = name.split("/");
                    String bucketId = parts[parts.length - 1];
                    String location = parts.length > 3 ? parts[3] : "global";
                    int extraDays = bucket.getRetentionDays() - 30;

                    // Cloud Logging charges $0.01 per GiB per month for bytes held past the first
                    // 30 days, so the cost is a function of the volume retained — not of the
                    // retention setting on its own. The previous figure multiplied the extra days
                    // by a flat per-day constant, which meant a bucket holding a few megabytes was
                    // quoted the same saving as one holding terabytes, and neither figure bore any
                    // relation to the invoice. The Logging API publishes no per-bucket byte count
                    // and the billing metric is project-wide, so the volume cannot be attributed
                    // to a bucket here. Reported unpriced rather than priced wrongly.
                    items.add(GcpWasteItem.withUnknownSavings(
                            name, bucketId,
                            "Cloud Logging Excessive Retention", location,
                            String.format("Retention is %d days against a 30-day default, so %d extra "
                                    + "days are billable. Cloud Logging charges $0.01 per GiB per month "
                                    + "for bytes held beyond 30 days; the saving depends on how much "
                                    + "this bucket stores, which the Logging API does not report per "
                                    + "bucket.", bucket.getRetentionDays(), extraDays)));
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findExcessiveLogRetention failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }

    // HTTP(S) / proxy-based LB forwarding rules with zero requests — billed per-rule even when idle.
    private CompletableFuture<List<GcpWasteItem>> findIdleHttpLoadBalancers(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<ForwardingRulesClient> frOpt = gcpClientProvider.getForwardingRulesClient(gcpProjectId);
            Optional<MetricServiceClient> metricsOpt = gcpClientProvider.getMetricServiceClient(gcpProjectId);
            if (frOpt.isEmpty() || metricsOpt.isEmpty()) return List.of();

            List<GcpWasteItem> items = new ArrayList<>();
            try (ForwardingRulesClient frClient = frOpt.get();
                 MetricServiceClient metricsClient = metricsOpt.get()) {

                com.google.protobuf.Timestamp end = com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond()).build();
                com.google.protobuf.Timestamp start = com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS).getEpochSecond()).build();

                for (var entry : frClient.aggregatedList(gcpProjectId).iterateAll()) {
                    for (ForwardingRule rule : entry.getValue().getForwardingRulesList()) {
                        String scheme = rule.getLoadBalancingScheme();
                        if (!scheme.endsWith("MANAGED")) continue;
                        String filter = String.format(
                                "metric.type=\"loadbalancing.googleapis.com/https/request_count\" AND resource.labels.forwarding_rule_name=\"%s\"",
                                rule.getName());
                        var req = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                                .setName("projects/" + gcpProjectId)
                                .setFilter(filter)
                                .setInterval(com.google.monitoring.v3.TimeInterval.newBuilder()
                                        .setStartTime(start).setEndTime(end).build())
                                .setView(com.google.monitoring.v3.ListTimeSeriesRequest.TimeSeriesView.HEADERS)
                                .build();
                        boolean hasTraffic = metricsClient.listTimeSeries(req).iterateAll().iterator().hasNext();
                        if (!hasTraffic) {
                            String regionUrl = rule.getRegion();
                            String location = (regionUrl == null || regionUrl.isEmpty())
                                    ? "global"
                                    : regionUrl.substring(regionUrl.lastIndexOf('/') + 1);
                            items.add(new GcpWasteItem(
                                    rule.getName(), rule.getName(),
                                    "Idle Load Balancer", location, EST_FORWARDING_RULE_MONTHLY_USD,
                                    "No requests to this " + scheme + " load balancer in the last " + LOOKBACK_DAYS + " days"));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[GcpWaste] findIdleHttpLoadBalancers failed for {}: {}", gcpProjectId, e.getMessage());
            }
            return items;
        }, executor);
    }
}