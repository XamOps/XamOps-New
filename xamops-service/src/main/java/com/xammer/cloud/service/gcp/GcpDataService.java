package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.aiplatform.v1.EndpointServiceClient;
import com.google.cloud.aiplatform.v1.ModelServiceClient;
import com.google.cloud.bigquery.reservation.v1.ReservationServiceClient;
import com.google.cloud.compute.v1.Router;
import com.google.cloud.compute.v1.RoutersClient;
import com.google.cloud.compute.v1.SecurityPolicy;
import com.google.cloud.compute.v1.SecurityPoliciesClient;
import com.google.cloud.dataplex.v1.DataplexServiceClient;
import com.google.cloud.functions.v2.FunctionServiceClient;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.monitoring.v3.AlertPolicyServiceClient;
import com.google.cloud.monitoring.v3.UptimeCheckServiceClient;
import com.google.cloud.osconfig.v1.OsConfigServiceClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.scheduler.v1.CloudSchedulerClient;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.logging.v2.LogBucket;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.GcpAccountRequestDto;
import com.xammer.cloud.dto.gcp.*;
import com.xammer.cloud.domain.CspBillingConfig;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CspBillingConfigRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
// Correct imports
import com.google.cloud.logging.v2.ConfigClient;
import com.google.cloud.apigateway.v1.Api;
import com.google.cloud.apigateway.v1.ApiGatewayServiceClient;
//import com.google.cloud.appengine.v1.Application;
//import com.google.cloud.appengine.v1.ApplicationsClient;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Dataset;

import com.xammer.cloud.config.multitenancy.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpDataService {

    private final GcpClientProvider gcpClientProvider;
    private final GcpCostService gcpCostService;
    private final GcpOptimizationService gcpOptimizationService;
    private final GcpSecurityService gcpSecurityService;
    private final com.xammer.cloud.repository.CloudAccountRepository cloudAccountRepository;
    private final CachedDataRepository cachedDataRepository;
    private final TransactionTemplate transactionTemplate;
    @Autowired
    @Qualifier("backgroundTaskExecutor")
    private Executor backgroundTaskExecutor;

    @Autowired
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @Autowired
    private CspBillingConfigRepository cspBillingConfigRepository;
    private final Map<String, double[]> regionCoordinates = loadRegionCoordinates();
    private final ObjectMapper objectMapper;
    private final RedisCacheService redisCache;

    private static final String DASHBOARD_CACHE_PREFIX    = "gcp:dashboard:";
    private static final String DB_DASHBOARD_CACHE_PREFIX = "GCP_DASHBOARD::";

    // Granular per-widget cache prefixes (mirror AWS DASHBOARD_KPI / DASHBOARD_COST etc.)
    private static final String GCP_GRANULAR_KPI_REDIS  = "gcp:granular:kpi:";
    private static final String GCP_GRANULAR_INV_REDIS  = "gcp:granular:inventory:";
    private static final String GCP_GRANULAR_COST_REDIS = "gcp:granular:cost:";
    private static final String GCP_GRANULAR_OPT_REDIS  = "gcp:granular:opt:";
    private static final String GCP_GRANULAR_MAP_REDIS  = "gcp:granular:map:";

    private static final String GCP_GRANULAR_KPI_DB  = "GCP_GRANULAR_KPI::";
    private static final String GCP_GRANULAR_INV_DB  = "GCP_GRANULAR_INVENTORY::";
    private static final String GCP_GRANULAR_COST_DB = "GCP_GRANULAR_COST::";
    private static final String GCP_GRANULAR_OPT_DB  = "GCP_GRANULAR_OPT::";
    private static final String GCP_GRANULAR_MAP_DB  = "GCP_GRANULAR_MAP::";
    private static final String ALL_RESOURCES_CACHE_PREFIX     = "gcp:all-resources:";
    private static final String GCP_CLOUDLIST_GROUPED_REDIS    = "gcp:cloudlist-grouped:";
    private static final String GCP_CLOUDLIST_FLAT_DB          = "GCP_CLOUDLIST_FLAT::";
    private static final String GCP_CLOUDLIST_GROUPED_DB       = "GCP_CLOUDLIST_GROUPED::";
    private static final String IAM_RESOURCES_CACHE_PREFIX = "gcp:iam-resources:";
    private static final String COMPUTE_INSTANCES_CACHE_PREFIX = "gcp:compute-instances:";
    private static final String STORAGE_BUCKETS_CACHE_PREFIX = "gcp:storage-buckets:";
    private static final String GKE_CLUSTERS_CACHE_PREFIX = "gcp:gke-clusters:";
    private static final String CLOUD_SQL_CACHE_PREFIX = "gcp:cloud-sql:";
    private static final String VPC_NETWORKS_CACHE_PREFIX = "gcp:vpc-networks:";
    private static final String DNS_ZONES_CACHE_PREFIX = "gcp:dns-zones:";
    private static final String LOAD_BALANCERS_CACHE_PREFIX = "gcp:load-balancers:";
    private static final String FIREWALL_RULES_CACHE_PREFIX = "gcp:firewall-rules:";
    private static final String CLOUD_NAT_CACHE_PREFIX = "gcp:cloud-nat:";
    private static final String KMS_KEYS_CACHE_PREFIX = "gcp:kms-keys:";
    private static final String CLOUD_FUNCTIONS_CACHE_PREFIX = "gcp:cloud-functions:";
    private static final String SECRET_MANAGER_CACHE_PREFIX = "gcp:secret-manager:";

    @Value("${tagging.compliance.required-tags}")
    private List<String> requiredTags;

    public GcpDataService(GcpClientProvider gcpClientProvider,
            GcpCostService gcpCostService,
            GcpOptimizationService gcpOptimizationService,
            GcpSecurityService gcpSecurityService,
            com.xammer.cloud.repository.CloudAccountRepository cloudAccountRepository,
            CachedDataRepository cachedDataRepository,
            TransactionTemplate transactionTemplate,
            RedisCacheService redisCache,
            ObjectMapper objectMapper) {
        this.gcpClientProvider = gcpClientProvider;
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpSecurityService = gcpSecurityService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cachedDataRepository = cachedDataRepository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.redisCache = redisCache;
    }

    private Map<String, double[]> loadRegionCoordinates() {
        Map<String, double[]> coords = new java.util.HashMap<>();
        try {
            coords.put("us-east1", new double[] { 33.829, -84.341 });
            coords.put("us-central1", new double[] { 41.258, -95.940 });
            coords.put("us-west1", new double[] { 37.422, -122.084 });
            coords.put("us-west2", new double[] { 33.943, -118.408 });
            coords.put("us-west3", new double[] { 40.761, -111.891 });
            coords.put("us-west4", new double[] { 36.170, -115.140 });
            coords.put("southamerica-east1", new double[] { -23.550, -46.633 });
            coords.put("europe-west1", new double[] { 50.850, 4.350 });
            coords.put("europe-west2", new double[] { 51.507, -0.128 });
            coords.put("europe-west3", new double[] { 50.110, 8.680 });
            coords.put("europe-west4", new double[] { 52.370, 4.890 });
            coords.put("europe-north1", new double[] { 60.192, 24.946 });
            coords.put("asia-south1", new double[] { 19.076, 72.877 });
            coords.put("asia-southeast1", new double[] { 1.352, 103.819 });
            coords.put("asia-southeast2", new double[] { -6.208, 106.845 });
            coords.put("asia-east1", new double[] { 25.033, 121.565 });
            coords.put("asia-east2", new double[] { 22.319, 114.169 });
            coords.put("asia-northeast1", new double[] { 35.689, 139.692 });
            coords.put("asia-northeast2", new double[] { 34.694, 135.502 });
            coords.put("asia-northeast3", new double[] { 37.566, 126.978 });
            coords.put("australia-southeast1", new double[] { -33.868, 151.209 });
            coords.put("australia-southeast2", new double[] { -37.813, 144.963 });
        } catch (Exception e) {
            log.error("Failed to load GCP region coordinates.", e);
        }
        return coords;
    }

    public List<String> getAllKnownRegions() {
        return new ArrayList<>(regionCoordinates.keySet());
    }

    public CompletableFuture<List<DashboardData.RegionStatus>> getRegionStatusForGcp(List<GcpResourceDto> resources) {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> activeRegions = resources.stream()
                    .map(GcpResourceDto::getLocation)
                    .filter(Objects::nonNull)
                    .map(loc -> {
                        String[] parts = loc.split("-");
                        if (parts.length > 2) {
                            return parts[0] + "-" + parts[1];
                        }
                        return loc;
                    })
                    .filter(regionCoordinates::containsKey)
                    .collect(Collectors.toSet());

            log.info("Found {} active GCP regions with deployed resources: {}", activeRegions.size(), activeRegions);

            return activeRegions.stream().map(regionId -> {
                double[] coords = regionCoordinates.get(regionId);
                return new DashboardData.RegionStatus(regionId, "ACTIVE", "ACTIVE", coords[0], coords[1]);
            }).collect(Collectors.toList());
        });
    }

    public CompletableFuture<GcpDashboardData> getDashboardData(String gcpProjectId) {
        return getDashboardData(gcpProjectId, false);
    }

    public CompletableFuture<GcpDashboardData> getDashboardData(String gcpProjectId, boolean forceRefresh) {
        String tenantPrefix  = tenantPrefix();
        String redisCacheKey = DASHBOARD_CACHE_PREFIX + tenantPrefix + gcpProjectId;
        String dbCacheKey    = DB_DASHBOARD_CACHE_PREFIX + tenantPrefix + gcpProjectId;

        // TIER 1: Redis (fastest) — TIER 2: DB fallback (survives restarts)
        if (!forceRefresh) {
            Optional<GcpDashboardData> redisCached = redisCache.get(redisCacheKey, GcpDashboardData.class);
            if (redisCached.isPresent()) {
                log.info("✅ Redis hit for GCP dashboard: {}", gcpProjectId);
                return CompletableFuture.completedFuture(redisCached.get());
            }

            GcpDashboardData dbCached = getGcpDashboardSnapshot(dbCacheKey);
            if (dbCached != null) {
                log.info("✅ DB hit for GCP dashboard — repopulating Redis: {}", gcpProjectId);
                redisCache.put(redisCacheKey, dbCached, 60);
                return CompletableFuture.completedFuture(dbCached);
            }
        }

        // TIER 3: Live GCP API calls — 12 parallel futures, each with 55 s timeout
        log.info("🔍 Fetching live GCP dashboard data for project: {}", gcpProjectId);

        CompletableFuture<List<GcpResourceDto>> resourcesFuture = getAllResources(gcpProjectId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP resources timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<List<GcpSecurityFinding>> securityFindingsFuture = gcpSecurityService
                .getSecurityFindings(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP security findings timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<DashboardData.IamResources> iamResourcesFuture = getIamResources(gcpProjectId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP IAM resources timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return new DashboardData.IamResources(0, 0, 0, 0);
                });

        // IAM Details promoted to proper async future (was blocking .get() inside thenApply)
        CompletableFuture<DashboardData.IamDetail> iamDetailsFuture = getIamDetails(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP IAM details timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return new DashboardData.IamDetail(Collections.emptyList(), Collections.emptyList());
                });

        CompletableFuture<Double> unfilteredMtdSpendFuture = gcpCostService.getUnfilteredMonthToDateSpend(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP MTD spend timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return 0.0;
                });

        CompletableFuture<List<GcpCostDto>> costHistoryFuture = gcpCostService.getHistoricalCosts(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP cost history timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<List<GcpCostDto>> billingSummaryFuture = gcpCostService.getBillingSummary(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP billing summary timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<List<GcpWasteItem>> wasteReportFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getWasteReport(gcpProjectId), backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP waste report timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<List<GcpOptimizationRecommendation>> rightsizingFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getRightsizingRecommendations(gcpProjectId), backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP rightsizing timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<DashboardData.SavingsSummary> savingsSummaryFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getSavingsSummary(gcpProjectId), backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP savings summary timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return new DashboardData.SavingsSummary(0.0, 0.0);
                });

        CompletableFuture<DashboardData.OptimizationSummary> optimizationSummaryFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getOptimizationSummary(gcpProjectId), backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("⚠️ GCP optimization summary timed out / failed for {}: {}", gcpProjectId, ex.getMessage());
                    return new DashboardData.OptimizationSummary(0.0, 0);
                });

        CompletableFuture<List<DashboardData.RegionStatus>> regionStatusFuture = resourcesFuture
                .thenCompose(this::getRegionStatusForGcp);

        return CompletableFuture.allOf(
                resourcesFuture, securityFindingsFuture, iamResourcesFuture, iamDetailsFuture,
                costHistoryFuture, billingSummaryFuture, wasteReportFuture, rightsizingFuture,
                savingsSummaryFuture, optimizationSummaryFuture, regionStatusFuture,
                unfilteredMtdSpendFuture).thenApply(v -> {
                    log.info("✅ All GCP async fetches complete — assembling DTO for project: {}", gcpProjectId);

                    GcpDashboardData data = new GcpDashboardData();

                    data.setRegionStatus(regionStatusFuture.join());

                    List<GcpCostDto> historyList = costHistoryFuture.join();
                    DashboardData.CostHistory costHistory = new DashboardData.CostHistory();
                    costHistory.setLabels(historyList.stream().map(GcpCostDto::getName).collect(Collectors.toList()));
                    costHistory.setCosts(historyList.stream().map(GcpCostDto::getAmount).collect(Collectors.toList()));
                    costHistory.setAnomalies(historyList.stream().map(GcpCostDto::isAnomaly).collect(Collectors.toList()));
                    data.setCostHistory(costHistory);

                    data.setBillingSummary(billingSummaryFuture.join());
                    data.setWastedResources(wasteReportFuture.join());
                    data.setRightsizingRecommendations(rightsizingFuture.join());
                    data.setOptimizationSummary(optimizationSummaryFuture.join());
                    data.setSavingsSummary(savingsSummaryFuture.join());

                    List<GcpResourceDto> resources = resourcesFuture.join();
                    List<GcpSecurityFinding> securityFindings = securityFindingsFuture.join();

                    DashboardData.ResourceInventory inventory = new DashboardData.ResourceInventory();
                    Map<String, Long> counts = resources.stream()
                            .collect(Collectors.groupingBy(GcpResourceDto::getType, Collectors.counting()));

                    inventory.setEc2((int) counts.getOrDefault("Compute Engine", 0L).longValue());
                    inventory.setS3Buckets((int) counts.getOrDefault("Cloud Storage", 0L).longValue());
                    inventory.setRdsInstances((int) counts.getOrDefault("Cloud SQL", 0L).longValue());
                    inventory.setKubernetes((int) counts.getOrDefault("Kubernetes Engine", 0L).longValue());
                    inventory.setVpc((int) counts.getOrDefault("VPC Network", 0L).longValue());
                    inventory.setRoute53Zones((int) counts.getOrDefault("Cloud DNS", 0L).longValue());
                    inventory.setLoadBalancers((int) counts.getOrDefault("Load Balancer", 0L).longValue());
                    inventory.setFirewalls((int) counts.getOrDefault("Firewall Rule", 0L).longValue());
                    inventory.setCloudNatRouters((int) counts.getOrDefault("Cloud NAT", 0L).longValue());
                    inventory.setArtifactRepositories((int) counts.getOrDefault("Artifact Registry", 0L).longValue());
                    inventory.setKmsKeys((int) counts.getOrDefault("Cloud KMS", 0L).longValue());
                    inventory.setCloudFunctions((int) counts.getOrDefault("Cloud Function", 0L).longValue());
                    inventory.setCloudBuildTriggers((int) counts.getOrDefault("Cloud Build", 0L).longValue());
                    inventory.setSecretManagerSecrets((int) counts.getOrDefault("Secret Manager", 0L).longValue());
                    inventory.setCloudArmorPolicies((int) counts.getOrDefault("Cloud Armor", 0L).longValue());
                    data.setResourceInventory(inventory);

                    double currentMtdSpend = unfilteredMtdSpendFuture.join();
                    data.setMonthToDateSpend(currentMtdSpend);
                    data.setForecastedSpend(gcpCostService.calculateForecastedSpend(currentMtdSpend));

                    String lastMonthStr = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
                    double lastMonthSpend = historyList.stream()
                            .filter(c -> c.getName().equals(lastMonthStr))
                            .mapToDouble(GcpCostDto::getAmount)
                            .findFirst().orElse(0.0);
                    data.setLastMonthSpend(lastMonthSpend);

                    data.setSecurityScore(gcpSecurityService.calculateSecurityScore(securityFindings));
                    List<DashboardData.SecurityInsight> securityInsights = securityFindings.stream()
                            .collect(Collectors.groupingBy(GcpSecurityFinding::getCategory, Collectors.counting()))
                            .entrySet().stream()
                            .map(entry -> {
                                String category = entry.getKey() != null ? entry.getKey() : "Uncategorized";
                                return new DashboardData.SecurityInsight(
                                        String.format("%s has potential issues", category),
                                        category, "High", entry.getValue().intValue());
                            }).collect(Collectors.toList());
                    data.setSecurityInsights(securityInsights);

                    data.setIamResources(iamResourcesFuture.join());
                    data.setIamDetails(iamDetailsFuture.join()); // now properly async

                    data.setLastUpdated(java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));

                    // Save to Redis (60 min) AND DB (permanent) — atomic, same as AWS saveSnapshot()
                    saveGcpSnapshot(redisCacheKey, dbCacheKey, data);

                    // Pre-fill all 5 granular keys from this fetch — zero extra API calls (mirrors AWS updateGranularSnapshots)
                    updateGcpGranularSnapshots(gcpProjectId, data);

                    return data;
                });
    }

    private GcpResourceDto mapInstanceToDto(com.google.cloud.compute.v1.Instance instance) {
        String zoneUrl = instance.getZone();
        String zone = zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1);
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(String.valueOf(instance.getId()));
        dto.setName(instance.getName());
        dto.setType("Compute Engine");
        dto.setLocation(zone);
        dto.setStatus(instance.getStatus());
        return dto;
    }

    private GcpResourceDto mapBucketToDto(com.google.cloud.storage.Bucket bucket) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(bucket.getName());
        dto.setName(bucket.getName());
        dto.setType("Cloud Storage");
        dto.setLocation(bucket.getLocation());
        dto.setStatus("ACTIVE");
        return dto;
    }

    private GcpResourceDto mapGkeToDto(com.google.container.v1.Cluster cluster) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(cluster.getId());
        dto.setName(cluster.getName());
        dto.setType("Kubernetes Engine");
        dto.setLocation(cluster.getLocation());
        dto.setStatus(cluster.getStatus().toString());
        return dto;
    }

    private GcpResourceDto mapSqlToDto(com.google.api.services.sqladmin.model.DatabaseInstance instance) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(instance.getName());
        dto.setName(instance.getName());
        dto.setType("Cloud SQL");
        dto.setLocation(instance.getRegion());
        dto.setStatus(instance.getState().toString());
        return dto;
    }

    private GcpResourceDto mapVpcToDto(com.google.cloud.compute.v1.Network network) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(String.valueOf(network.getId()));
        dto.setName(network.getName());
        dto.setType("VPC Network");
        dto.setLocation("global");
        dto.setStatus("ACTIVE");
        return dto;
    }

    private GcpResourceDto mapDnsToDto(com.google.api.services.dns.model.ManagedZone zone) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(String.valueOf(zone.getId()));
        dto.setName(zone.getDnsName());
        dto.setType("Cloud DNS");
        dto.setLocation("global");
        dto.setStatus("ACTIVE");
        return dto;
    }

    private GcpResourceDto mapFirewallToDto(com.google.cloud.compute.v1.Firewall firewall) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(String.valueOf(firewall.getId()));
        dto.setName(firewall.getName());
        dto.setType("Firewall Rule");
        dto.setLocation("global");
        dto.setStatus(firewall.getDisabled() ? "DISABLED" : "ACTIVE");
        return dto;
    }

    private GcpResourceDto mapNatRouterToDto(Router router) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(String.valueOf(router.getId()));
        dto.setName(router.getName());
        dto.setType("Cloud NAT");
        dto.setLocation(router.getRegion().substring(router.getRegion().lastIndexOf('/') + 1));
        dto.setStatus("ACTIVE");
        return dto;
    }

    // private GcpResourceDto mapArtifactRepositoryToDto(Repository repository) {
    // GcpResourceDto dto = new GcpResourceDto();
    // dto.setId(repository.getName());
    // dto.setName(repository.getName());
    // dto.setType("Artifact Registry");
    // dto.setLocation(repository.getName().split("/")[3]);
    // dto.setStatus("ACTIVE");
    // return dto;
    // }

    private GcpResourceDto mapKmsKeyToDto(CryptoKey key) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(key.getName());
        dto.setName(key.getName());
        dto.setType("Cloud KMS");
        dto.setLocation(key.getName().split("/")[3]);
        dto.setStatus(key.getPrimary().getState().toString());
        return dto;
    }

    private GcpResourceDto mapCloudFunctionToDto(com.google.cloud.functions.v2.Function function) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(function.getName());
        dto.setName(function.getName());
        dto.setType("Cloud Function");
        String[] nameParts = function.getName().split("/");
        String location = (nameParts.length >= 4) ? nameParts[3] : "global";
        dto.setLocation(location);
        dto.setStatus(function.getState().toString());
        return dto;
    }

    // private GcpResourceDto mapCloudBuildTriggerToDto(BuildTrigger trigger) {
    // GcpResourceDto dto = new GcpResourceDto();
    // dto.setId(trigger.getId());
    // dto.setName(trigger.getName());
    // dto.setType("Cloud Build");
    // dto.setLocation("global");
    // dto.setStatus(trigger.getDisabled() ? "DISABLED" : "ACTIVE");
    // return dto;
    // }

    private GcpResourceDto mapSecretToDto(Secret secret) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(secret.getName());
        dto.setName(secret.getName());
        dto.setType("Secret Manager");
        dto.setLocation("global");
        dto.setStatus("ACTIVE");
        return dto;
    }

    private GcpResourceDto mapCloudArmorPolicyToDto(SecurityPolicy policy) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(String.valueOf(policy.getId()));
        dto.setName(policy.getName());
        dto.setType("Cloud Armor");
        dto.setLocation("global");
        dto.setStatus("ACTIVE");
        return dto;
    }

    // --- ADDED MISSING MAPPER METHODS ---
    private GcpResourceDto mapApiGatewayToDto(Api api) {
        GcpResourceDto dto = new GcpResourceDto();
        String[] nameParts = api.getName().split("/");
        dto.setId(nameParts[nameParts.length - 1]);
        dto.setName(api.getDisplayName() != null ? api.getDisplayName() : nameParts[nameParts.length - 1]);
        dto.setType("API Gateway");
        dto.setLocation(nameParts[3]);
        dto.setStatus(api.getState().toString());
        return dto;
    }

    // private GcpResourceDto mapAppEngineToDto(Application app) {
    // GcpResourceDto dto = new GcpResourceDto();
    // dto.setId(app.getName());
    // dto.setName(app.getName());
    // dto.setType("App Engine");
    // dto.setLocation(app.getLocationId());
    // dto.setStatus(app.getServingStatus().name());
    // return dto;
    // }

    private GcpResourceDto mapBigQueryDatasetToDto(Dataset dataset) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(dataset.getDatasetId().getDataset());
        dto.setName(
                dataset.getFriendlyName() != null ? dataset.getFriendlyName() : dataset.getDatasetId().getDataset());
        dto.setType("BigQuery Dataset");
        dto.setLocation(dataset.getLocation());
        dto.setStatus("ACTIVE");
        return dto;
    }

    private GcpResourceDto mapLogBucketToDto(LogBucket bucket) {
        GcpResourceDto dto = new GcpResourceDto();
        String[] nameParts = bucket.getName().split("/");
        dto.setId(nameParts[nameParts.length - 1]);
        dto.setName(nameParts[nameParts.length - 1]);
        dto.setType("Logging Bucket");
        dto.setLocation(nameParts[3]);
        dto.setStatus(bucket.getLifecycleState().name());
        return dto;
    }

    public CompletableFuture<List<GcpResourceDto>> getAllResources(String gcpProjectId) {
        return getAllResources(gcpProjectId, false);
    }

    public CompletableFuture<List<GcpResourceDto>> getAllResources(String gcpProjectId, boolean forceRefresh) {
        String tenantPrefix = tenantPrefix();
        String redisKey = ALL_RESOURCES_CACHE_PREFIX + gcpProjectId;
        String dbKey    = GCP_CLOUDLIST_FLAT_DB + tenantPrefix + gcpProjectId;

        // TIER 1: Redis
        if (!forceRefresh) {
            Optional<List<GcpResourceDto>> cached = redisCache.get(redisKey, new TypeReference<List<GcpResourceDto>>() {});
            if (cached.isPresent()) {
                log.info("[GcpCloudList] Redis hit — {} resources for project: {}", cached.get().size(), gcpProjectId);
                return CompletableFuture.completedFuture(cached.get());
            }
            // TIER 2: DB snapshot
            List<GcpResourceDto> dbCached = getCloudlistFlatSnapshot(dbKey);
            if (dbCached != null) {
                log.info("[GcpCloudList] DB hit — re-warming Redis ({} resources) for project: {}", dbCached.size(), gcpProjectId);
                redisCache.put(redisKey, dbCached, 240);
                return CompletableFuture.completedFuture(dbCached);
            }
        }

        log.info("[GcpCloudList] Cache miss — live GCP scan starting for project: {}", gcpProjectId);
        final long startMs = System.currentTimeMillis();

        List<CompletableFuture<List<GcpResourceDto>>> futures = List.of(
                CompletableFuture.supplyAsync(() -> getComputeInstances(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getStorageBuckets(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getGkeClusters(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getCloudSqlInstances(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getVpcNetworks(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getDnsZones(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getLoadBalancers(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getFirewallRules(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getCloudNatRouters(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getKmsKeys(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getCloudFunctions(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getSecretManagerSecrets(gcpProjectId, forceRefresh), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getCloudArmorPolicies(gcpProjectId), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getApiGateways(gcpProjectId), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getBigQueryDatasets(gcpProjectId), backgroundTaskExecutor),
                CompletableFuture.supplyAsync(() -> getLoggingBuckets(gcpProjectId), backgroundTaskExecutor),
                getVertexAIModels(gcpProjectId),
                getVertexAIEndpoints(gcpProjectId),
                getPubSubTopics(gcpProjectId),
                getPubSubSubscriptions(gcpProjectId),
                getMonitoringAlertPolicies(gcpProjectId),
                getMonitoringUptimeChecks(gcpProjectId),
                getSchedulerJobs(gcpProjectId),
                getDataplexLakes(gcpProjectId),
                getBigQueryReservations(gcpProjectId),
                getVMManagerPatchDeployments(gcpProjectId));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<GcpResourceDto> allResources = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());

                    log.info("[GcpCloudList] Live scan complete — {} resources in {}ms for project: {}",
                            allResources.size(), System.currentTimeMillis() - startMs, gcpProjectId);

                    // Save flat list: Redis (60 min) + DB (permanent)
                    redisCache.put(redisKey, allResources, 60);
                    saveCloudlistFlatSnapshot(dbKey, allResources);

                    // Build + cache grouped separately (Gap 5)
                    List<GcpServicePaginatedResponse.GcpServiceGroupDto> grouped = buildGrouped(allResources);
                    String groupedRedisKey = GCP_CLOUDLIST_GROUPED_REDIS + gcpProjectId;
                    String groupedDbKey    = GCP_CLOUDLIST_GROUPED_DB + tenantPrefix + gcpProjectId;
                    redisCache.put(groupedRedisKey, grouped, 60);
                    saveCloudlistGroupedSnapshot(groupedDbKey, grouped);

                    return allResources;
                });
    }

    /** Triggers a background async resource refresh (returns immediately, notifies via WebSocket). */
    public void triggerGetResourcesAsync(String gcpProjectId) {
        log.info("[GcpCloudList] Async refresh triggered for project: {}", gcpProjectId);
        CompletableFuture.runAsync(() -> getAllResources(gcpProjectId, true).join(), backgroundTaskExecutor)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[GcpCloudList] Async refresh FAILED for project: {}", gcpProjectId, ex);
                        messagingTemplate.convertAndSend("/topic/gcp-cloudlist-refresh",
                                Map.of("status", "refresh-failed", "accountId", gcpProjectId));
                    } else {
                        log.info("[GcpCloudList] Async refresh COMPLETE for project: {}", gcpProjectId);
                        messagingTemplate.convertAndSend("/topic/gcp-cloudlist-refresh",
                                Map.of("status", "refresh-complete", "accountId", gcpProjectId));
                    }
                });
    }

    private List<GcpServicePaginatedResponse.GcpServiceGroupDto> buildGrouped(List<GcpResourceDto> resources) {
        Map<String, List<GcpResourceDto>> byType = resources.stream()
                .filter(r -> r.getType() != null && !r.getType().isEmpty())
                .collect(Collectors.groupingBy(GcpResourceDto::getType));
        return byType.entrySet().stream()
                .map(e -> new GcpServicePaginatedResponse.GcpServiceGroupDto(e.getKey(), e.getValue().size(), e.getValue()))
                .sorted((a, b) -> Integer.compare(b.getResourceCount(), a.getResourceCount()))
                .limit(50)
                .collect(Collectors.toList());
    }

    // ── Cloudlist DB snapshot helpers ─────────────────────────────────────────

    private void saveCloudlistFlatSnapshot(String dbKey, List<GcpResourceDto> resources) {
        try {
            String json = objectMapper.writeValueAsString(resources);
            transactionTemplate.executeWithoutResult(status -> {
                com.xammer.cloud.domain.CachedData entry = cachedDataRepository.findById(dbKey).orElse(new com.xammer.cloud.domain.CachedData());
                entry.setCacheKey(dbKey);
                entry.setJsonData(json);
                entry.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(entry);
                cachedDataRepository.flush();
            });
            log.info("[GcpCloudList] Flat snapshot saved to DB: {}", dbKey);
        } catch (Exception e) {
            log.error("[GcpCloudList] Failed to save flat snapshot to DB {}: {}", dbKey, e.getMessage());
        }
    }

    private List<GcpResourceDto> getCloudlistFlatSnapshot(String dbKey) {
        try {
            Optional<com.xammer.cloud.domain.CachedData> cached = cachedDataRepository.findById(dbKey);
            if (cached.isPresent()) {
                return objectMapper.readValue(cached.get().getJsonData(), new TypeReference<List<GcpResourceDto>>() {});
            }
        } catch (Exception e) {
            log.warn("[GcpCloudList] Failed to read flat snapshot from DB {}: {}", dbKey, e.getMessage());
        }
        return null;
    }

    private void saveCloudlistGroupedSnapshot(String dbKey, List<GcpServicePaginatedResponse.GcpServiceGroupDto> grouped) {
        try {
            String json = objectMapper.writeValueAsString(grouped);
            transactionTemplate.executeWithoutResult(status -> {
                com.xammer.cloud.domain.CachedData entry = cachedDataRepository.findById(dbKey).orElse(new com.xammer.cloud.domain.CachedData());
                entry.setCacheKey(dbKey);
                entry.setJsonData(json);
                entry.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(entry);
                cachedDataRepository.flush();
            });
            log.info("[GcpCloudList] Grouped snapshot saved to DB: {}", dbKey);
        } catch (Exception e) {
            log.error("[GcpCloudList] Failed to save grouped snapshot to DB {}: {}", dbKey, e.getMessage());
        }
    }

    private List<GcpServicePaginatedResponse.GcpServiceGroupDto> getCloudlistGroupedSnapshot(String dbKey) {
        try {
            Optional<com.xammer.cloud.domain.CachedData> cached = cachedDataRepository.findById(dbKey);
            if (cached.isPresent()) {
                return objectMapper.readValue(cached.get().getJsonData(),
                        new TypeReference<List<GcpServicePaginatedResponse.GcpServiceGroupDto>>() {});
            }
        } catch (Exception e) {
            log.warn("[GcpCloudList] Failed to read grouped snapshot from DB {}: {}", dbKey, e.getMessage());
        }
        return null;
    }

    @Cacheable(value = "gcpIamResources", key = "'gcp:iam-resources:' + #gcpProjectId")
    public CompletableFuture<DashboardData.IamResources> getIamResources(String gcpProjectId, boolean forceRefresh) {
        log.info("Attempting to get IAM resources for project: {}", gcpProjectId);
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.cloud.resourcemanager.v3.ProjectsClient> clientOpt = gcpClientProvider
                    .getProjectsClient(gcpProjectId);
            if (clientOpt.isEmpty())
                return new DashboardData.IamResources(0, 0, 0, 0);
            try (com.google.cloud.resourcemanager.v3.ProjectsClient projectsClient = clientOpt.get()) {
                com.google.cloud.resourcemanager.v3.Project project = projectsClient
                        .getProject("projects/" + gcpProjectId);
                log.info("Fetched project details for: {}", project.getDisplayName());
                int userCount = 10;
                int roleCount = 20;
                return new DashboardData.IamResources(userCount, 0, 0, roleCount);
            } catch (Exception e) {
                log.error("Error fetching IAM resources for project: {}", gcpProjectId, e);
                return new DashboardData.IamResources(0, 0, 0, 0);
            }
        });
    }

    public CompletableFuture<DashboardData.IamDetail> getIamDetails(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            List<DashboardData.IamUserDetail> users = new ArrayList<>();
            List<DashboardData.IamRoleDetail> roles = new ArrayList<>();

            // 1. Fetch Service Accounts
            try (com.google.cloud.iam.admin.v1.IAMClient iamClient = gcpClientProvider.getIamClient(gcpProjectId)
                    .orElse(null)) {
                if (iamClient != null) {
                    com.google.iam.admin.v1.ListServiceAccountsRequest request = com.google.iam.admin.v1.ListServiceAccountsRequest
                            .newBuilder()
                            .setName("projects/" + gcpProjectId)
                            .build();

                    for (com.google.iam.admin.v1.ServiceAccount sa : iamClient.listServiceAccounts(request)
                            .iterateAll()) {
                        DashboardData.IamUserDetail user = new DashboardData.IamUserDetail();
                        user.setUserName(sa.getDisplayName().isEmpty() ? sa.getEmail() : sa.getDisplayName());
                        user.setUserId(sa.getUniqueId());
                        user.setArn(sa.getName()); // "projects/PID/serviceAccounts/email"
                        user.setCreateDate(null); // Not readily available in list?
                        user.setPasswordLastUsed(null);
                        users.add(user);
                    }

                    // 2. Fetch Roles (Custom roles usually, pre-defined are too many)
                    // For now, let's list custom roles or just leave roles empty/minimal to avoid
                    // massive list
                    com.google.iam.admin.v1.ListRolesRequest rolesRequest = com.google.iam.admin.v1.ListRolesRequest
                            .newBuilder()
                            .setParent("projects/" + gcpProjectId)
                            .setView(com.google.iam.admin.v1.RoleView.BASIC)
                            .build();

                    for (com.google.iam.admin.v1.Role role : iamClient.listRoles(rolesRequest).iterateAll()) {
                        DashboardData.IamRoleDetail roleDetail = new DashboardData.IamRoleDetail();
                        roleDetail.setRoleName(role.getTitle());
                        roleDetail.setRoleId(role.getName());
                        roleDetail.setArn(role.getName());
                        roleDetail.setCreateDate(null);
                        roles.add(roleDetail);
                    }
                }
            } catch (Exception e) {
                log.error("Error fetching IAM details for project {}", gcpProjectId, e);
            }

            return new DashboardData.IamDetail(users, roles);
        });
    }

    @Transactional
    public CloudAccount createGcpAccount(GcpAccountRequestDto request, Client client) {
        log.info("Creating GCP account: {} for client: {}", request.getAccountName(), client.getId());

        try {
            // Parse service account JSON to extract email
            String serviceAccountEmail = extractServiceAccountEmail(request.getServiceAccountKey());

            // Validate billing export table format if provided
            if (request.getBillingExportTable() != null && !request.getBillingExportTable().isEmpty()) {
                validateBillingTableFormat(request.getBillingExportTable());
            }

            // Create CloudAccount entity
            CloudAccount account = new CloudAccount();
            account.setAccountName(request.getAccountName());
            account.setProvider("GCP");
            account.setStatus("CONNECTED");
            account.setAccessType("read-only");
            account.setClient(client);

            // GCP specific fields
            String projectId = request.getGcpProjectId() != null ? request.getGcpProjectId() : request.getProjectId();
            account.setGcpProjectId(projectId);
            account.setGcpServiceAccountEmail(serviceAccountEmail);
            account.setGcpServiceAccountKey(request.getServiceAccountKey());
            account.setGcpWorkloadIdentityPoolId(request.getGcpWorkloadIdentityPoolId());
            account.setGcpWorkloadIdentityProviderId(request.getGcpWorkloadIdentityProviderId());

            //
            // ✨ --- FIX IMPLEMENTED --- ✨
            // Set the external_id to the projectId for GCP accounts to satisfy the not-null
            // constraint.
            //
            account.setExternalId(projectId);

            account.setBillingExportTable(request.getBillingExportTable());

            if (Boolean.TRUE.equals(request.getCspManaged())) {
                account.setCspManaged(true);
                if (request.getCspBillingConfigId() != null) {
                    cspBillingConfigRepository.findById(request.getCspBillingConfigId())
                        .ifPresent(account::setCspBillingConfig);
                }
            }

            // Save to database
            CloudAccount savedAccount = cloudAccountRepository.save(account);
            log.info("✅ GCP account created successfully with ID: {}", savedAccount.getId());

            if (savedAccount.getBillingExportTable() != null) {
                log.info("📊 Billing export table configured: {}", savedAccount.getBillingExportTable());
            } else {
                log.warn("⚠️ No billing export table configured - cost data will not be available");
            }

            return savedAccount;

        } catch (Exception e) {
            log.error("❌ Failed to create GCP account: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create GCP account: " + e.getMessage());
        }
    }

    @Transactional
    public CloudAccount updateBillingTable(Long accountId, String billingTable) {
        CloudAccount account = cloudAccountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("GCP account not found with ID: " + accountId));

        if (!"GCP".equals(account.getProvider())) {
            throw new RuntimeException("Account is not a GCP account");
        }

        if (billingTable != null && !billingTable.isEmpty()) {
            validateBillingTableFormat(billingTable);
        }

        account.setBillingExportTable(billingTable);
        CloudAccount saved = cloudAccountRepository.save(account);
        log.info("Updated billing export table for account {}: {}", accountId, billingTable);
        return saved;
    }

    private String extractServiceAccountEmail(String serviceAccountKey) {
        try {
            JsonNode jsonNode = objectMapper.readTree(serviceAccountKey);
            String email = jsonNode.get("client_email").asText();

            if (email == null || email.isEmpty()) {
                throw new RuntimeException("Service account email not found in JSON key");
            }

            log.info("✅ Extracted service account email: {}", email);
            return email;

        } catch (Exception e) {
            log.error("Failed to parse service account key", e);
            throw new RuntimeException("Invalid service account key format: " + e.getMessage());
        }
    }

    private void validateBillingTableFormat(String billingTable) {
        String[] parts = billingTable.split("\\.");

        if (parts.length != 3) {
            throw new RuntimeException(
                    "Invalid BigQuery table format. Expected format: project.dataset.table " +
                            "(e.g., my-project.billing_export.gcp_billing_export_v1_XXXXXX_XXXXXX_XXXXXX)");
        }

        // Validate each part is not empty
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].trim().isEmpty()) {
                throw new RuntimeException("Invalid BigQuery table format: Part " + (i + 1) + " is empty");
            }
        }

        log.info("✅ Billing export table validated: {}", billingTable);
    }

    public CompletableFuture<List<GcpResourceDto>> getVpcListForCloudmap(String gcpProjectId, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> getVpcNetworks(gcpProjectId, forceRefresh));
    }

    public CompletableFuture<List<Map<String, Object>>> getVpcTopologyGraph(String gcpProjectId, String vpcId) {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> elements = new ArrayList<>();
            List<com.google.cloud.compute.v1.Instance> allInstances = getRawComputeInstances(gcpProjectId);
            List<com.google.cloud.compute.v1.Subnetwork> allSubnetworks = getRawSubnetworks(gcpProjectId);
            List<com.google.cloud.compute.v1.Network> allNetworks = getRawVpcNetworks(gcpProjectId);

            if (vpcId == null || vpcId.isBlank()) {
                allNetworks.forEach(
                        network -> elements.add(createNode(String.valueOf(network.getId()), network.getName(), "VPC")));
            } else {
                allNetworks.stream()
                        .filter(n -> String.valueOf(n.getId()).equals(vpcId))
                        .forEach(network -> {
                            // Add VPC node
                            elements.add(createNode(String.valueOf(network.getId()), network.getName(), "VPC"));

                            Set<String> addedRegions = new HashSet<>();

                            allSubnetworks.stream()
                                    .filter(sn -> sn.getNetwork().endsWith("/" + network.getName()))
                                    .forEach(subnet -> {
                                        String region = subnet.getRegion()
                                                .substring(subnet.getRegion().lastIndexOf('/') + 1);
                                        String regionNodeId = "region-" + region;

                                        // Add Region node if it hasn't been added yet
                                        if (addedRegions.add(regionNodeId)) {
                                            elements.add(createNode(regionNodeId, region, "Region"));
                                            elements.add(createEdge(regionNodeId + "-" + network.getId(), regionNodeId,
                                                    String.valueOf(network.getId())));
                                        }

                                        // Add Subnet node
                                        elements.add(
                                                createNode(String.valueOf(subnet.getId()), subnet.getName(), "Subnet"));
                                        // Add edge from Subnet to Region
                                        elements.add(createEdge(subnet.getId() + "-" + regionNodeId,
                                                String.valueOf(subnet.getId()), regionNodeId));
                                    });

                            allInstances.stream()
                                    .filter(inst -> inst.getNetworkInterfacesList().stream()
                                            .anyMatch(ni -> ni.getNetwork().endsWith("/" + network.getName())))
                                    .forEach(instance -> {
                                        String subnetworkUrl = instance.getNetworkInterfaces(0).getSubnetwork();
                                        allSubnetworks.stream()
                                                .filter(sn -> sn.getSelfLink().equals(subnetworkUrl))
                                                .findFirst()
                                                .ifPresent(parentSubnet -> {
                                                    // Add Instance node
                                                    elements.add(createNode(String.valueOf(instance.getId()),
                                                            instance.getName(), "Instance"));
                                                    // Add edge from Instance to Subnet
                                                    elements.add(
                                                            createEdge(instance.getId() + "-" + parentSubnet.getId(),
                                                                    String.valueOf(instance.getId()),
                                                                    String.valueOf(parentSubnet.getId())));
                                                });
                                    });
                        });
            }
            return elements;
        });
    }

    private Map<String, Object> createNode(String id, String label, String type) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("label", label);
        data.put("type", type);

        Map<String, Object> node = new HashMap<>();
        node.put("group", "nodes");
        node.put("data", data);
        return node;
    }

    private Map<String, Object> createEdge(String id, String source, String target) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("source", source);
        data.put("target", target);

        Map<String, Object> edge = new HashMap<>();
        edge.put("group", "edges");
        edge.put("data", data);
        return edge;
    }

    private List<com.google.cloud.compute.v1.Network> getRawVpcNetworks(String gcpProjectId) {
        Optional<com.google.cloud.compute.v1.NetworksClient> clientOpt = gcpClientProvider
                .getNetworksClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (com.google.cloud.compute.v1.NetworksClient client = clientOpt.get()) {
            return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching raw VPC Networks for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<com.google.cloud.compute.v1.Subnetwork> getRawSubnetworks(String gcpProjectId) {
        Optional<com.google.cloud.compute.v1.SubnetworksClient> clientOpt = gcpClientProvider
                .getSubnetworksClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (com.google.cloud.compute.v1.SubnetworksClient client = clientOpt.get()) {
            return StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                    .flatMap(entry -> entry.getValue().getSubnetworksList().stream())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching raw Subnetworks for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<com.google.cloud.compute.v1.Instance> getRawComputeInstances(String gcpProjectId) {
        Optional<com.google.cloud.compute.v1.InstancesClient> clientOpt = gcpClientProvider
                .getInstancesClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (com.google.cloud.compute.v1.InstancesClient client = clientOpt.get()) {
            return StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                    .flatMap(entry -> entry.getValue().getInstancesList().stream())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching raw Compute Instances for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getComputeInstances(String gcpProjectId, boolean forceRefresh) {
        return getRawComputeInstances(gcpProjectId).stream()
                .map(this::mapInstanceToDto).collect(Collectors.toList());
    }

    private List<GcpResourceDto> getStorageBuckets(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching Cloud Storage buckets for project: {}", gcpProjectId);
        Optional<com.google.cloud.storage.Storage> clientOpt = gcpClientProvider.getStorageClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try {
            return StreamSupport.stream(clientOpt.get().list().iterateAll().spliterator(), false)
                    .map(this::mapBucketToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching Storage Buckets for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getGkeClusters(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching Kubernetes Engine clusters for project: {}", gcpProjectId);
        Optional<com.google.cloud.container.v1.ClusterManagerClient> clientOpt = gcpClientProvider
                .getClusterManagerClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (com.google.cloud.container.v1.ClusterManagerClient client = clientOpt.get()) {
            String parent = "projects/" + gcpProjectId + "/locations/-";
            List<com.google.container.v1.Cluster> clusters = client.listClusters(parent).getClustersList();
            return clusters.stream()
                    .map(this::mapGkeToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching GKE clusters for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getCloudSqlInstances(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching Cloud SQL instances for project: {}", gcpProjectId);
        Optional<com.google.api.services.sqladmin.SQLAdmin> sqlAdminClientOpt = gcpClientProvider
                .getSqlAdminClient(gcpProjectId);
        if (sqlAdminClientOpt.isEmpty())
            return List.of();

        try {
            List<com.google.api.services.sqladmin.model.DatabaseInstance> instances = sqlAdminClientOpt.get()
                    .instances()
                    .list(gcpProjectId)
                    .execute()
                    .getItems();
            if (instances == null)
                return List.of();
            return instances.stream().map(this::mapSqlToDto).collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Error fetching Cloud SQL instances for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getVpcNetworks(String gcpProjectId, boolean forceRefresh) {
        return getRawVpcNetworks(gcpProjectId).stream()
                .map(this::mapVpcToDto).collect(Collectors.toList());
    }

    private List<GcpResourceDto> getDnsZones(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching Cloud DNS zones for project: {}", gcpProjectId);
        Optional<com.google.api.services.dns.Dns> clientOpt = gcpClientProvider.getDnsZonesClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try {
            com.google.api.services.dns.Dns dns = clientOpt.get();
            com.google.api.services.dns.Dns.ManagedZones.List request = dns.managedZones().list(gcpProjectId);
            List<com.google.api.services.dns.model.ManagedZone> zones = request.execute().getManagedZones();
            if (zones == null)
                return List.of();
            return zones.stream()
                    .map(this::mapDnsToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching DNS Zones for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getLoadBalancers(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching Load Balancers for project: {}", gcpProjectId);
        Optional<com.google.cloud.compute.v1.ForwardingRulesClient> clientOpt = gcpClientProvider
                .getForwardingRulesClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (com.google.cloud.compute.v1.ForwardingRulesClient client = clientOpt.get()) {
            return StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                    .flatMap(entry -> entry.getValue().getForwardingRulesList().stream())
                    .map(this::mapForwardingRuleToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching Load Balancers for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getFirewallRules(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching Firewall Rules for project: {}", gcpProjectId);
        Optional<com.google.cloud.compute.v1.FirewallsClient> clientOpt = gcpClientProvider
                .getFirewallsClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (com.google.cloud.compute.v1.FirewallsClient client = clientOpt.get()) {
            return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                    .map(this::mapFirewallToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching Firewall Rules for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getCloudNatRouters(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching Cloud NAT routers for project: {}", gcpProjectId);
        Optional<RoutersClient> clientOpt = gcpClientProvider.getRoutersClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (RoutersClient client = clientOpt.get()) {
            return StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                    .flatMap(entry -> entry.getValue().getRoutersList().stream())
                    .filter(router -> !router.getNatsList().isEmpty())
                    .map(this::mapNatRouterToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching Cloud NAT routers for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    // private List<GcpResourceDto> getArtifactRepositories(String gcpProjectId) {
    // log.info("Fetching Artifact Registry repositories for project: {}",
    // gcpProjectId);
    // Optional<ArtifactRegistryClient> clientOpt =
    // gcpClientProvider.getArtifactRegistryClient(gcpProjectId);
    // if (clientOpt.isEmpty()) return List.of();
    // try (ArtifactRegistryClient client = clientOpt.get()) {
    // String parent = "projects/" + gcpProjectId + "/locations/-";
    // return
    // StreamSupport.stream(client.listRepositories(parent).iterateAll().spliterator(),
    // false)
    // .map(this::mapArtifactRepositoryToDto)
    // .collect(Collectors.toList());
    // } catch (Exception e) {
    // log.error("Error fetching Artifact Registry repositories for project: {}",
    // gcpProjectId, e);
    // return List.of();
    // }
    // }

    private List<GcpResourceDto> getKmsKeys(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching KMS keys for project: {}", gcpProjectId);
        Optional<KeyManagementServiceClient> clientOpt = gcpClientProvider.getKeyManagementServiceClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();

        try (KeyManagementServiceClient client = clientOpt.get()) {
            return getAllKnownRegions().stream()
                    .flatMap(region -> {
                        try {
                            String parent = "projects/" + gcpProjectId + "/locations/" + region;
                            return StreamSupport.stream(client.listKeyRings(parent).iterateAll().spliterator(), false)
                                    .flatMap(keyRing -> StreamSupport.stream(
                                            client.listCryptoKeys(keyRing.getName()).iterateAll().spliterator(), false))
                                    .map(this::mapKmsKeyToDto);
                        } catch (Exception e) {
                            log.warn("Could not fetch KMS keys for region {}: {}", region, e.getMessage());
                            return Stream.empty();
                        }
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching KMS keys for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getCloudFunctions(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching Cloud Functions for project: {}", gcpProjectId);
        Optional<FunctionServiceClient> clientOpt = gcpClientProvider.getFunctionServiceClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (FunctionServiceClient client = clientOpt.get()) {
            String parent = "projects/" + gcpProjectId + "/locations/-";
            return StreamSupport.stream(client.listFunctions(parent).iterateAll().spliterator(), false)
                    .map(this::mapCloudFunctionToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching Cloud Functions for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    // private List<GcpResourceDto> getCloudBuildTriggers(String gcpProjectId) {
    // log.info("Fetching Cloud Build triggers for project: {}", gcpProjectId);
    // Optional<CloudBuildClient> clientOpt =
    // gcpClientProvider.getCloudBuildClient(gcpProjectId);
    // if (clientOpt.isEmpty()) return List.of();
    // try (CloudBuildClient client = clientOpt.get()) {
    // return client.listBuildTriggers(gcpProjectId).stream()
    // .map(this::mapCloudBuildTriggerToDto)
    // .collect(Collectors.toList());
    // } catch (Exception e) {
    // log.error("Error fetching Cloud Build triggers for project: {}",
    // gcpProjectId, e);
    // return List.of();
    // }
    // }

    private List<GcpResourceDto> getSecretManagerSecrets(String gcpProjectId, boolean forceRefresh) {
        log.info("Fetching secrets from Secret Manager for project: {}", gcpProjectId);
        Optional<SecretManagerServiceClient> clientOpt = gcpClientProvider.getSecretManagerServiceClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (SecretManagerServiceClient client = clientOpt.get()) {
            String parent = "projects/" + gcpProjectId;
            return StreamSupport.stream(client.listSecrets(parent).iterateAll().spliterator(), false)
                    .map(this::mapSecretToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching secrets from Secret Manager for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getCloudArmorPolicies(String gcpProjectId) {
        log.info("Fetching Cloud Armor policies for project: {}", gcpProjectId);
        Optional<SecurityPoliciesClient> clientOpt = gcpClientProvider.getSecurityPoliciesClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (SecurityPoliciesClient client = clientOpt.get()) {
            return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                    .map(this::mapCloudArmorPolicyToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching Cloud Armor policies for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private GcpResourceDto mapForwardingRuleToDto(com.google.cloud.compute.v1.ForwardingRule forwardingRule) {
        GcpResourceDto dto = new GcpResourceDto();
        dto.setId(String.valueOf(forwardingRule.getId()));
        dto.setName(forwardingRule.getName());
        dto.setType("Load Balancer");
        String regionUrl = forwardingRule.getRegion();
        String region = regionUrl != null ? regionUrl.substring(regionUrl.lastIndexOf('/') + 1) : "global";
        dto.setLocation(region);
        dto.setStatus("ACTIVE");
        return dto;
    }

    private List<GcpResourceDto> getApiGateways(String gcpProjectId) {
        log.info("Fetching API Gateways for project: {}", gcpProjectId);
        Optional<ApiGatewayServiceClient> clientOpt = gcpClientProvider.getApiGatewayServiceClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (ApiGatewayServiceClient client = clientOpt.get()) {
            String parent = "projects/" + gcpProjectId + "/locations/-";
            return StreamSupport.stream(client.listApis(parent).iterateAll().spliterator(), false)
                    .map(this::mapApiGatewayToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching API Gateways for project {}: {}", gcpProjectId, e.getMessage(), e);
            return List.of();
        }
    }

    // private List<GcpResourceDto> getAppEngineApplications(String gcpProjectId) {
    // log.info("Fetching App Engine applications for project: {}", gcpProjectId);
    // Optional<ApplicationsClient> clientOpt =
    // gcpClientProvider.getAppsClient(gcpProjectId);
    // if (clientOpt.isEmpty()) return List.of();
    // try (ApplicationsClient client = clientOpt.get()) {
    // Application app = client.getApplication("apps/" + gcpProjectId);
    // if (app != null) {
    // return List.of(mapAppEngineToDto(app));
    // }
    // return List.of();
    // } catch (Exception e) {
    // log.error("Error fetching App Engine application for project {}: {}",
    // gcpProjectId, e.getMessage());
    // return List.of();
    // }
    // }

    private List<GcpResourceDto> getBigQueryDatasets(String gcpProjectId) {
        log.info("Fetching BigQuery datasets for project: {}", gcpProjectId);
        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty())
            return List.of();
        try {
            return StreamSupport.stream(bqOpt.get().listDatasets(gcpProjectId).iterateAll().spliterator(), false)
                    .map(this::mapBigQueryDatasetToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching BigQuery datasets for project {}: {}", gcpProjectId, e.getMessage(), e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getLoggingBuckets(String gcpProjectId) {
        log.info("Fetching Logging buckets for project: {}", gcpProjectId);
        Optional<ConfigClient> clientOpt = gcpClientProvider.getConfigClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return List.of();
        try (ConfigClient client = clientOpt.get()) {
            String parent = String.format("projects/%s/locations/-", gcpProjectId);
            return StreamSupport.stream(client.listBuckets(parent).iterateAll().spliterator(), false)
                    .map(this::mapLogBucketToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching Logging buckets for project {}: {}", gcpProjectId, e.getMessage(), e);
            return List.of();
        }
    }
    // ==================== VERTEX AI SERVICES ====================

    /**
     * Fetch Vertex AI Models
     */
    private CompletableFuture<List<GcpResourceDto>> getVertexAIModels(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching Vertex AI Models for project: {}", gcpProjectId);
            Optional<ModelServiceClient> clientOpt = gcpClientProvider.getVertexAIModelClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("ModelServiceClient not available for project {}. Skipping.", gcpProjectId);
                return List.of();
            }

            try (ModelServiceClient client = clientOpt.get()) {
                List<GcpResourceDto> resources = new ArrayList<>();
                String parent = String.format("projects/%s/locations/us-central1", gcpProjectId);

                client.listModels(parent).iterateAll().forEach(model -> {
                    GcpResourceDto dto = new GcpResourceDto();
                    dto.setId(model.getName());
                    dto.setName(model.getDisplayName());
                    dto.setType("Vertex AI Model");
                    dto.setLocation("us-central1");
                    dto.setStatus(model.getDeployedModelsCount() > 0 ? "DEPLOYED" : "AVAILABLE");
                    resources.add(dto);
                });

                log.info("Found {} Vertex AI models for project {}", resources.size(), gcpProjectId);
                return resources;
            } catch (Exception e) {
                log.error("Failed to fetch Vertex AI models for project {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, backgroundTaskExecutor);
    }

    /**
     * Fetch Vertex AI Endpoints
     */
    private CompletableFuture<List<GcpResourceDto>> getVertexAIEndpoints(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching Vertex AI Endpoints for project: {}", gcpProjectId);
            Optional<com.google.cloud.aiplatform.v1.EndpointServiceClient> clientOpt = gcpClientProvider
                    .getVertexAIEndpointClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("EndpointServiceClient not available for project {}. Skipping.", gcpProjectId);
                return List.of();
            }

            try (EndpointServiceClient client = clientOpt.get()) {
                List<GcpResourceDto> resources = new ArrayList<>();
                String parent = String.format("projects/%s/locations/us-central1", gcpProjectId);

                client.listEndpoints(parent).iterateAll().forEach(endpoint -> {
                    GcpResourceDto dto = new GcpResourceDto();
                    dto.setId(endpoint.getName());
                    dto.setName(endpoint.getDisplayName());
                    dto.setType("Vertex AI Endpoint");
                    dto.setLocation("us-central1");
                    dto.setStatus("ACTIVE");
                    resources.add(dto);
                });

                log.info("Found {} Vertex AI endpoints for project {}", resources.size(), gcpProjectId);
                return resources;
            } catch (Exception e) {
                log.error("Failed to fetch Vertex AI endpoints for project {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, backgroundTaskExecutor);
    }

    // ==================== CLOUD PUB/SUB SERVICES ====================

    /**
     * Fetch Cloud Pub/Sub Topics
     */
    private CompletableFuture<List<GcpResourceDto>> getPubSubTopics(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching Pub/Sub Topics for project: {}", gcpProjectId);
            Optional<TopicAdminClient> clientOpt = gcpClientProvider.getPubSubTopicClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("TopicAdminClient not available for project {}. Skipping.", gcpProjectId);
                return List.of();
            }

            try (TopicAdminClient client = clientOpt.get()) {
                List<GcpResourceDto> resources = new ArrayList<>();
                String project = String.format("projects/%s", gcpProjectId);

                client.listTopics(project).iterateAll().forEach(topic -> {
                    GcpResourceDto dto = new GcpResourceDto();
                    dto.setId(topic.getName());
                    dto.setName(topic.getName().substring(topic.getName().lastIndexOf('/') + 1));
                    dto.setType("Pub/Sub Topic");
                    dto.setLocation("global");
                    dto.setStatus("ACTIVE");
                    resources.add(dto);
                });

                log.info("Found {} Pub/Sub topics for project {}", resources.size(), gcpProjectId);
                return resources;
            } catch (Exception e) {
                log.error("Failed to fetch Pub/Sub topics for project {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, backgroundTaskExecutor);
    }

    /**
     * Fetch Cloud Pub/Sub Subscriptions
     */
    private CompletableFuture<List<GcpResourceDto>> getPubSubSubscriptions(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching Pub/Sub Subscriptions for project: {}", gcpProjectId);
            Optional<SubscriptionAdminClient> clientOpt = gcpClientProvider.getPubSubSubscriptionClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("SubscriptionAdminClient not available for project {}. Skipping.", gcpProjectId);
                return List.of();
            }

            try (SubscriptionAdminClient client = clientOpt.get()) {
                List<GcpResourceDto> resources = new ArrayList<>();
                String project = String.format("projects/%s", gcpProjectId);

                client.listSubscriptions(project).iterateAll().forEach(subscription -> {
                    GcpResourceDto dto = new GcpResourceDto();
                    dto.setId(subscription.getName());
                    dto.setName(subscription.getName().substring(subscription.getName().lastIndexOf('/') + 1));
                    dto.setType("Pub/Sub Subscription");
                    dto.setLocation("global");
                    dto.setStatus(subscription.getState().toString());
                    resources.add(dto);
                });

                log.info("Found {} Pub/Sub subscriptions for project {}", resources.size(), gcpProjectId);
                return resources;
            } catch (Exception e) {
                log.error("Failed to fetch Pub/Sub subscriptions for project {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, backgroundTaskExecutor);
    }

    // ==================== CLOUD MONITORING SERVICES ====================

    /**
     * Fetch Cloud Monitoring Alert Policies
     */
    private CompletableFuture<List<GcpResourceDto>> getMonitoringAlertPolicies(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching Monitoring Alert Policies for project: {}", gcpProjectId);
            Optional<AlertPolicyServiceClient> clientOpt = gcpClientProvider.getMonitoringAlertClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("AlertPolicyServiceClient not available for project {}. Skipping.", gcpProjectId);
                return List.of();
            }

            try (AlertPolicyServiceClient client = clientOpt.get()) {
                List<GcpResourceDto> resources = new ArrayList<>();
                String projectName = String.format("projects/%s", gcpProjectId);

                client.listAlertPolicies(projectName).iterateAll().forEach(policy -> {
                    GcpResourceDto dto = new GcpResourceDto();
                    dto.setId(policy.getName());
                    dto.setName(policy.getDisplayName());
                    dto.setType("Monitoring Alert Policy");
                    dto.setLocation("global");
                    dto.setStatus(policy.getEnabled().getValue() ? "ENABLED" : "DISABLED");
                    resources.add(dto);
                });

                log.info("Found {} monitoring alert policies for project {}", resources.size(), gcpProjectId);
                return resources;
            } catch (Exception e) {
                log.error("Failed to fetch monitoring alert policies for project {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, backgroundTaskExecutor);
    }

    /**
     * Fetch Cloud Monitoring Uptime Checks
     */
    private CompletableFuture<List<GcpResourceDto>> getMonitoringUptimeChecks(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching Monitoring Uptime Checks for project: {}", gcpProjectId);
            Optional<UptimeCheckServiceClient> clientOpt = gcpClientProvider.getMonitoringUptimeClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("UptimeCheckServiceClient not available for project {}. Skipping.", gcpProjectId);
                return List.of();
            }

            try (UptimeCheckServiceClient client = clientOpt.get()) {
                List<GcpResourceDto> resources = new ArrayList<>();
                String projectName = String.format("projects/%s", gcpProjectId);

                client.listUptimeCheckConfigs(projectName).iterateAll().forEach(check -> {
                    GcpResourceDto dto = new GcpResourceDto();
                    dto.setId(check.getName());
                    dto.setName(check.getDisplayName());
                    dto.setType("Monitoring Uptime Check");
                    dto.setLocation("global");
                    dto.setStatus("ACTIVE");
                    resources.add(dto);
                });

                log.info("Found {} monitoring uptime checks for project {}", resources.size(), gcpProjectId);
                return resources;
            } catch (Exception e) {
                log.error("Failed to fetch monitoring uptime checks for project {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, backgroundTaskExecutor);
    }

    // ==================== DATAPLEX SERVICE ====================

    /**
     * Fetch Dataplex Lakes
     */
    private CompletableFuture<List<GcpResourceDto>> getDataplexLakes(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching Dataplex Lakes for project: {}", gcpProjectId);
            Optional<DataplexServiceClient> clientOpt = gcpClientProvider.getDataplexClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("DataplexServiceClient not available for project {}. Skipping.", gcpProjectId);
                return List.of();
            }

            try (DataplexServiceClient client = clientOpt.get()) {
                List<GcpResourceDto> resources = new ArrayList<>();
                String parent = String.format("projects/%s/locations/us-central1", gcpProjectId);

                client.listLakes(parent).iterateAll().forEach(lake -> {
                    GcpResourceDto dto = new GcpResourceDto();
                    dto.setId(lake.getName());
                    dto.setName(lake.getDisplayName());
                    dto.setType("Dataplex Lake");
                    dto.setLocation("us-central1");
                    dto.setStatus(lake.getState().toString());
                    resources.add(dto);
                });

                log.info("Found {} Dataplex lakes for project {}", resources.size(), gcpProjectId);
                return resources;
            } catch (Exception e) {
                log.error("Failed to fetch Dataplex lakes for project {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, backgroundTaskExecutor);
    }

    // ==================== CLOUD SCHEDULER SERVICE ====================

    /**
     * Fetch Cloud Scheduler Jobs
     */
    private CompletableFuture<List<GcpResourceDto>> getSchedulerJobs(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching Cloud Scheduler Jobs for project: {}", gcpProjectId);
            Optional<CloudSchedulerClient> clientOpt = gcpClientProvider.getSchedulerClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("CloudSchedulerClient not available for project {}. Skipping.", gcpProjectId);
                return List.of();
            }

            try (CloudSchedulerClient client = clientOpt.get()) {
                List<GcpResourceDto> resources = new ArrayList<>();
                String parent = String.format("projects/%s/locations/us-central1", gcpProjectId);

                client.listJobs(parent).iterateAll().forEach(job -> {
                    GcpResourceDto dto = new GcpResourceDto();
                    dto.setId(job.getName());
                    dto.setName(job.getName().substring(job.getName().lastIndexOf('/') + 1));
                    dto.setType("Cloud Scheduler Job");
                    dto.setLocation("us-central1");
                    dto.setStatus(job.getState().toString());
                    resources.add(dto);
                });

                log.info("Found {} Cloud Scheduler jobs for project {}", resources.size(), gcpProjectId);
                return resources;
            } catch (Exception e) {
                log.error("Failed to fetch Cloud Scheduler jobs for project {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, backgroundTaskExecutor);
    }

    // ==================== VM MANAGER (OS CONFIG) SERVICE ====================

    /**
     * Fetch VM Manager Patch Deployments
     */
    private CompletableFuture<List<GcpResourceDto>> getVMManagerPatchDeployments(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching VM Manager Patch Deployments for project: {}", gcpProjectId);
            Optional<OsConfigServiceClient> clientOpt = gcpClientProvider.getOsConfigClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("OsConfigServiceClient not available for project {}. Skipping.", gcpProjectId);
                return List.of();
            }

            try (OsConfigServiceClient client = clientOpt.get()) {
                List<GcpResourceDto> resources = new ArrayList<>();
                String parent = String.format("projects/%s", gcpProjectId);

                client.listPatchDeployments(parent).iterateAll().forEach(deployment -> {
                    GcpResourceDto dto = new GcpResourceDto();
                    dto.setId(deployment.getName());
                    dto.setName(deployment.getName().substring(deployment.getName().lastIndexOf('/') + 1));
                    dto.setType("VM Manager Patch Deployment");
                    dto.setLocation("global");
                    dto.setStatus("ACTIVE");
                    resources.add(dto);
                });

                log.info("Found {} VM Manager patch deployments for project {}", resources.size(), gcpProjectId);
                return resources;
            } catch (Exception e) {
                log.error("Failed to fetch VM Manager patch deployments for project {}: {}", gcpProjectId,
                        e.getMessage());
                return List.of();
            }
        }, backgroundTaskExecutor);
    }

    // ==================== BIGQUERY RESERVATION SERVICE ====================

    /**
     * Fetch BigQuery Reservations
     */
    private CompletableFuture<List<GcpResourceDto>> getBigQueryReservations(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching BigQuery Reservations for project: {}", gcpProjectId);
            Optional<ReservationServiceClient> clientOpt = gcpClientProvider.getBigQueryReservationClient(gcpProjectId);

            if (clientOpt.isEmpty()) {
                log.warn("ReservationServiceClient not available for project {}. Skipping.", gcpProjectId);
                return List.of();
            }

            try (ReservationServiceClient client = clientOpt.get()) {
                List<GcpResourceDto> resources = new ArrayList<>();
                String parent = String.format("projects/%s/locations/us-central1", gcpProjectId);

                client.listReservations(parent).iterateAll().forEach(reservation -> {
                    GcpResourceDto dto = new GcpResourceDto();
                    dto.setId(reservation.getName());
                    dto.setName(reservation.getName().substring(reservation.getName().lastIndexOf('/') + 1));
                    dto.setType("BigQuery Reservation");
                    dto.setLocation("us-central1");
                    dto.setStatus("ACTIVE");
                    resources.add(dto);
                });

                log.info("Found {} BigQuery reservations for project {}", resources.size(), gcpProjectId);
                return resources;
            } catch (Exception e) {
                log.error("Failed to fetch BigQuery reservations for project {}: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
        }, backgroundTaskExecutor);
    }

    /**
     * Clear all caches for a specific GCP project
     */
    // ============================================================================================
    // GRANULAR PER-WIDGET ENDPOINTS — mirrors AWS /granular/{kpi,inventory,cost,opt,map}
    // Each method fetches only the relevant futures, stores in its own DB+Redis key.
    // ============================================================================================

    public GcpDashboardData getGranularKpi(String gcpProjectId, boolean forceRefresh) {
        String tenantPrefix = tenantPrefix();
        String redisKey = GCP_GRANULAR_KPI_REDIS + tenantPrefix + gcpProjectId;
        String dbKey    = GCP_GRANULAR_KPI_DB    + tenantPrefix + gcpProjectId;

        if (!forceRefresh) {
            Optional<GcpDashboardData> r = redisCache.get(redisKey, GcpDashboardData.class);
            if (r.isPresent()) { log.info("✅ [GCP-KPI] Redis hit: {}", gcpProjectId); return r.get(); }
            GcpDashboardData db = getGcpDashboardSnapshot(dbKey);
            if (db != null) { log.info("✅ [GCP-KPI] DB hit: {}", gcpProjectId); redisCache.put(redisKey, db, 60); return db; }
        }

        log.info("🔍 [GCP-KPI] Live fetch for: {}", gcpProjectId);
        CompletableFuture<Double> mtdFuture = gcpCostService.getUnfilteredMonthToDateSpend(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> 0.0);
        CompletableFuture<List<GcpCostDto>> histFuture = gcpCostService.getHistoricalCosts(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> Collections.emptyList());
        CompletableFuture<DashboardData.OptimizationSummary> optFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getOptimizationSummary(gcpProjectId), backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> new DashboardData.OptimizationSummary(0.0, 0));
        CompletableFuture<DashboardData.SavingsSummary> savFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getSavingsSummary(gcpProjectId), backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> new DashboardData.SavingsSummary(0.0, 0.0));

        CompletableFuture.allOf(mtdFuture, histFuture, optFuture, savFuture).join();

        double mtd = mtdFuture.join();
        List<GcpCostDto> hist = histFuture.join();
        String lastMonthStr = java.time.LocalDate.now().minusMonths(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));

        GcpDashboardData data = new GcpDashboardData();
        data.setMonthToDateSpend(mtd);
        data.setForecastedSpend(gcpCostService.calculateForecastedSpend(mtd));
        data.setLastMonthSpend(hist.stream().filter(c -> c.getName().equals(lastMonthStr))
                .mapToDouble(GcpCostDto::getAmount).findFirst().orElse(0.0));
        DashboardData.CostHistory ch = new DashboardData.CostHistory();
        ch.setLabels(hist.stream().map(GcpCostDto::getName).collect(Collectors.toList()));
        ch.setCosts(hist.stream().map(GcpCostDto::getAmount).collect(Collectors.toList()));
        ch.setAnomalies(hist.stream().map(GcpCostDto::isAnomaly).collect(Collectors.toList()));
        data.setCostHistory(ch);
        data.setOptimizationSummary(optFuture.join());
        data.setSavingsSummary(savFuture.join());
        data.setLastUpdated(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));

        saveGcpSnapshot(redisKey, dbKey, data);
        return data;
    }

    public GcpDashboardData getGranularInventory(String gcpProjectId, boolean forceRefresh) {
        String tenantPrefix = tenantPrefix();
        String redisKey = GCP_GRANULAR_INV_REDIS + tenantPrefix + gcpProjectId;
        String dbKey    = GCP_GRANULAR_INV_DB    + tenantPrefix + gcpProjectId;

        if (!forceRefresh) {
            Optional<GcpDashboardData> r = redisCache.get(redisKey, GcpDashboardData.class);
            if (r.isPresent()) { log.info("✅ [GCP-Inventory] Redis hit: {}", gcpProjectId); return r.get(); }
            GcpDashboardData db = getGcpDashboardSnapshot(dbKey);
            if (db != null) { log.info("✅ [GCP-Inventory] DB hit: {}", gcpProjectId); redisCache.put(redisKey, db, 60); return db; }
        }

        log.info("🔍 [GCP-Inventory] Live fetch for: {}", gcpProjectId);
        CompletableFuture<List<GcpResourceDto>> resFuture = getAllResources(gcpProjectId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> Collections.emptyList());
        CompletableFuture<DashboardData.IamResources> iamResFuture = getIamResources(gcpProjectId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> new DashboardData.IamResources(0, 0, 0, 0));
        CompletableFuture<DashboardData.IamDetail> iamDetFuture = getIamDetails(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> new DashboardData.IamDetail(Collections.emptyList(), Collections.emptyList()));

        CompletableFuture.allOf(resFuture, iamResFuture, iamDetFuture).join();

        List<GcpResourceDto> resources = resFuture.join();
        Map<String, Long> counts = resources.stream()
                .collect(Collectors.groupingBy(GcpResourceDto::getType, Collectors.counting()));

        DashboardData.ResourceInventory inventory = new DashboardData.ResourceInventory();
        inventory.setEc2((int) counts.getOrDefault("Compute Engine", 0L).longValue());
        inventory.setS3Buckets((int) counts.getOrDefault("Cloud Storage", 0L).longValue());
        inventory.setRdsInstances((int) counts.getOrDefault("Cloud SQL", 0L).longValue());
        inventory.setKubernetes((int) counts.getOrDefault("Kubernetes Engine", 0L).longValue());
        inventory.setVpc((int) counts.getOrDefault("VPC Network", 0L).longValue());
        inventory.setRoute53Zones((int) counts.getOrDefault("Cloud DNS", 0L).longValue());
        inventory.setLoadBalancers((int) counts.getOrDefault("Load Balancer", 0L).longValue());
        inventory.setFirewalls((int) counts.getOrDefault("Firewall Rule", 0L).longValue());
        inventory.setCloudNatRouters((int) counts.getOrDefault("Cloud NAT", 0L).longValue());
        inventory.setArtifactRepositories((int) counts.getOrDefault("Artifact Registry", 0L).longValue());
        inventory.setKmsKeys((int) counts.getOrDefault("Cloud KMS", 0L).longValue());
        inventory.setCloudFunctions((int) counts.getOrDefault("Cloud Function", 0L).longValue());
        inventory.setCloudBuildTriggers((int) counts.getOrDefault("Cloud Build", 0L).longValue());
        inventory.setSecretManagerSecrets((int) counts.getOrDefault("Secret Manager", 0L).longValue());
        inventory.setCloudArmorPolicies((int) counts.getOrDefault("Cloud Armor", 0L).longValue());

        GcpDashboardData data = new GcpDashboardData();
        data.setResourceInventory(inventory);
        data.setIamResources(iamResFuture.join());
        data.setIamDetails(iamDetFuture.join());
        data.setLastUpdated(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));

        saveGcpSnapshot(redisKey, dbKey, data);
        return data;
    }

    public GcpDashboardData getGranularCost(String gcpProjectId, boolean forceRefresh) {
        String tenantPrefix = tenantPrefix();
        String redisKey = GCP_GRANULAR_COST_REDIS + tenantPrefix + gcpProjectId;
        String dbKey    = GCP_GRANULAR_COST_DB    + tenantPrefix + gcpProjectId;

        if (!forceRefresh) {
            Optional<GcpDashboardData> r = redisCache.get(redisKey, GcpDashboardData.class);
            if (r.isPresent()) { log.info("✅ [GCP-Cost] Redis hit: {}", gcpProjectId); return r.get(); }
            GcpDashboardData db = getGcpDashboardSnapshot(dbKey);
            if (db != null) { log.info("✅ [GCP-Cost] DB hit: {}", gcpProjectId); redisCache.put(redisKey, db, 60); return db; }
        }

        log.info("🔍 [GCP-Cost] Live fetch for: {}", gcpProjectId);
        CompletableFuture<Double> mtdFuture = gcpCostService.getUnfilteredMonthToDateSpend(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> 0.0);
        CompletableFuture<List<GcpCostDto>> histFuture = gcpCostService.getHistoricalCosts(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> Collections.emptyList());
        CompletableFuture<List<GcpCostDto>> billFuture = gcpCostService.getBillingSummary(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> Collections.emptyList());

        CompletableFuture.allOf(mtdFuture, histFuture, billFuture).join();

        double mtd = mtdFuture.join();
        List<GcpCostDto> hist = histFuture.join();
        String lastMonthStr = java.time.LocalDate.now().minusMonths(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));

        DashboardData.CostHistory ch = new DashboardData.CostHistory();
        ch.setLabels(hist.stream().map(GcpCostDto::getName).collect(Collectors.toList()));
        ch.setCosts(hist.stream().map(GcpCostDto::getAmount).collect(Collectors.toList()));
        ch.setAnomalies(hist.stream().map(GcpCostDto::isAnomaly).collect(Collectors.toList()));

        GcpDashboardData data = new GcpDashboardData();
        data.setMonthToDateSpend(mtd);
        data.setForecastedSpend(gcpCostService.calculateForecastedSpend(mtd));
        data.setLastMonthSpend(hist.stream().filter(c -> c.getName().equals(lastMonthStr))
                .mapToDouble(GcpCostDto::getAmount).findFirst().orElse(0.0));
        data.setCostHistory(ch);
        data.setBillingSummary(billFuture.join());
        data.setLastUpdated(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));

        saveGcpSnapshot(redisKey, dbKey, data);
        return data;
    }

    public GcpDashboardData getGranularOptimization(String gcpProjectId, boolean forceRefresh) {
        String tenantPrefix = tenantPrefix();
        String redisKey = GCP_GRANULAR_OPT_REDIS + tenantPrefix + gcpProjectId;
        String dbKey    = GCP_GRANULAR_OPT_DB    + tenantPrefix + gcpProjectId;

        if (!forceRefresh) {
            Optional<GcpDashboardData> r = redisCache.get(redisKey, GcpDashboardData.class);
            if (r.isPresent()) { log.info("✅ [GCP-Opt] Redis hit: {}", gcpProjectId); return r.get(); }
            GcpDashboardData db = getGcpDashboardSnapshot(dbKey);
            if (db != null) { log.info("✅ [GCP-Opt] DB hit: {}", gcpProjectId); redisCache.put(redisKey, db, 60); return db; }
        }

        log.info("🔍 [GCP-Optimization] Live fetch for: {}", gcpProjectId);
        CompletableFuture<List<GcpWasteItem>> wasteFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getWasteReport(gcpProjectId), backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> Collections.emptyList());
        CompletableFuture<List<GcpOptimizationRecommendation>> rightsizeFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getRightsizingRecommendations(gcpProjectId), backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> Collections.emptyList());
        CompletableFuture<DashboardData.OptimizationSummary> optFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getOptimizationSummary(gcpProjectId), backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> new DashboardData.OptimizationSummary(0.0, 0));
        CompletableFuture<DashboardData.SavingsSummary> savFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getSavingsSummary(gcpProjectId), backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> new DashboardData.SavingsSummary(0.0, 0.0));

        CompletableFuture.allOf(wasteFuture, rightsizeFuture, optFuture, savFuture).join();

        GcpDashboardData data = new GcpDashboardData();
        data.setWastedResources(wasteFuture.join());
        data.setRightsizingRecommendations(rightsizeFuture.join());
        data.setOptimizationSummary(optFuture.join());
        data.setSavingsSummary(savFuture.join());
        data.setLastUpdated(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));

        saveGcpSnapshot(redisKey, dbKey, data);
        return data;
    }

    public GcpDashboardData getGranularMap(String gcpProjectId, boolean forceRefresh) {
        String tenantPrefix = tenantPrefix();
        String redisKey = GCP_GRANULAR_MAP_REDIS + tenantPrefix + gcpProjectId;
        String dbKey    = GCP_GRANULAR_MAP_DB    + tenantPrefix + gcpProjectId;

        if (!forceRefresh) {
            Optional<GcpDashboardData> r = redisCache.get(redisKey, GcpDashboardData.class);
            if (r.isPresent()) { log.info("✅ [GCP-Map] Redis hit: {}", gcpProjectId); return r.get(); }
            GcpDashboardData db = getGcpDashboardSnapshot(dbKey);
            if (db != null) { log.info("✅ [GCP-Map] DB hit: {}", gcpProjectId); redisCache.put(redisKey, db, 60); return db; }
        }

        log.info("🔍 [GCP-Map] Live fetch for: {}", gcpProjectId);
        CompletableFuture<List<GcpResourceDto>> resFuture = getAllResources(gcpProjectId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> Collections.emptyList());
        CompletableFuture<List<GcpSecurityFinding>> secFuture = gcpSecurityService.getSecurityFindings(gcpProjectId)
                .orTimeout(55, TimeUnit.SECONDS).exceptionally(ex -> Collections.emptyList());
        CompletableFuture<List<DashboardData.RegionStatus>> regionFuture = resFuture
                .thenCompose(this::getRegionStatusForGcp);

        CompletableFuture.allOf(regionFuture, secFuture).join();

        List<GcpSecurityFinding> findings = secFuture.join();
        List<DashboardData.SecurityInsight> insights = findings.stream()
                .collect(Collectors.groupingBy(GcpSecurityFinding::getCategory, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new DashboardData.SecurityInsight(
                        String.format("%s has potential issues", e.getKey() != null ? e.getKey() : "Uncategorized"),
                        e.getKey() != null ? e.getKey() : "Uncategorized", "High", e.getValue().intValue()))
                .collect(Collectors.toList());

        GcpDashboardData data = new GcpDashboardData();
        data.setRegionStatus(regionFuture.join());
        data.setSecurityScore(gcpSecurityService.calculateSecurityScore(findings));
        data.setSecurityInsights(insights);
        data.setLastUpdated(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));

        saveGcpSnapshot(redisKey, dbKey, data);
        return data;
    }

    /**
     * Pre-fills all 5 granular DB+Redis keys from a just-fetched full dashboard payload.
     * Called inside getDashboardData.thenApply() — zero extra API calls, mirrors AWS updateGranularSnapshots().
     */
    private void updateGcpGranularSnapshots(String gcpProjectId, GcpDashboardData full) {
        try {
            String tp = tenantPrefix();

            // 1. KPI — spend numbers + sparkline + optimization/savings summaries
            if (full.getCostHistory() != null) {
                GcpDashboardData kpi = new GcpDashboardData();
                kpi.setMonthToDateSpend(full.getMonthToDateSpend());
                kpi.setForecastedSpend(full.getForecastedSpend());
                kpi.setLastMonthSpend(full.getLastMonthSpend());
                kpi.setCostHistory(full.getCostHistory());
                kpi.setOptimizationSummary(full.getOptimizationSummary());
                kpi.setSavingsSummary(full.getSavingsSummary());
                kpi.setLastUpdated(full.getLastUpdated());
                saveGcpSnapshot(GCP_GRANULAR_KPI_REDIS + tp + gcpProjectId,
                                GCP_GRANULAR_KPI_DB   + tp + gcpProjectId, kpi);
            }

            // 2. Inventory — resource counts + IAM
            if (full.getResourceInventory() != null) {
                GcpDashboardData inv = new GcpDashboardData();
                inv.setResourceInventory(full.getResourceInventory());
                inv.setIamResources(full.getIamResources());
                inv.setIamDetails(full.getIamDetails());
                inv.setLastUpdated(full.getLastUpdated());
                saveGcpSnapshot(GCP_GRANULAR_INV_REDIS + tp + gcpProjectId,
                                GCP_GRANULAR_INV_DB   + tp + gcpProjectId, inv);
            }

            // 3. Cost — history + billing breakdown
            if (full.getCostHistory() != null) {
                GcpDashboardData cost = new GcpDashboardData();
                cost.setMonthToDateSpend(full.getMonthToDateSpend());
                cost.setForecastedSpend(full.getForecastedSpend());
                cost.setLastMonthSpend(full.getLastMonthSpend());
                cost.setCostHistory(full.getCostHistory());
                cost.setBillingSummary(full.getBillingSummary());
                cost.setLastUpdated(full.getLastUpdated());
                saveGcpSnapshot(GCP_GRANULAR_COST_REDIS + tp + gcpProjectId,
                                GCP_GRANULAR_COST_DB   + tp + gcpProjectId, cost);
            }

            // 4. Optimization — waste + rightsizing + summaries
            GcpDashboardData opt = new GcpDashboardData();
            opt.setWastedResources(full.getWastedResources());
            opt.setRightsizingRecommendations(full.getRightsizingRecommendations());
            opt.setOptimizationSummary(full.getOptimizationSummary());
            opt.setSavingsSummary(full.getSavingsSummary());
            opt.setLastUpdated(full.getLastUpdated());
            saveGcpSnapshot(GCP_GRANULAR_OPT_REDIS + tp + gcpProjectId,
                            GCP_GRANULAR_OPT_DB   + tp + gcpProjectId, opt);

            // 5. Map — region status + security score + insights
            if (full.getRegionStatus() != null) {
                GcpDashboardData map = new GcpDashboardData();
                map.setRegionStatus(full.getRegionStatus());
                map.setSecurityScore(full.getSecurityScore());
                map.setSecurityInsights(full.getSecurityInsights());
                map.setLastUpdated(full.getLastUpdated());
                saveGcpSnapshot(GCP_GRANULAR_MAP_REDIS + tp + gcpProjectId,
                                GCP_GRANULAR_MAP_DB   + tp + gcpProjectId, map);
            }

            log.info("✅ [GCP] Granular snapshots pre-filled for project: {}", gcpProjectId);
        } catch (Exception e) {
            log.warn("[GCP] updateGcpGranularSnapshots failed for {}: {}", gcpProjectId, e.getMessage());
        }
    }

    /** Tenant prefix helper — shared by all granular and full-dashboard methods. */
    private String tenantPrefix() {
        String t = TenantContext.getCurrentTenant();
        return (t != null) ? t + ":" : "";
    }

    public void clearProjectCache(String gcpProjectId) {
        String tenantId = TenantContext.getCurrentTenant();
        String tenantPrefix = (tenantId != null) ? tenantId + ":" : "";
        log.info("🗑️ Clearing all cache for GCP project: {} (tenant: {})", gcpProjectId, tenantId);

        redisCache.evict(DASHBOARD_CACHE_PREFIX + tenantPrefix + gcpProjectId);
        redisCache.evict(ALL_RESOURCES_CACHE_PREFIX + tenantPrefix + gcpProjectId);
        redisCache.evict(IAM_RESOURCES_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(COMPUTE_INSTANCES_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(STORAGE_BUCKETS_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(GKE_CLUSTERS_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(CLOUD_SQL_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(VPC_NETWORKS_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(DNS_ZONES_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(LOAD_BALANCERS_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(FIREWALL_RULES_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(CLOUD_NAT_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(KMS_KEYS_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(CLOUD_FUNCTIONS_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(SECRET_MANAGER_CACHE_PREFIX + gcpProjectId);
        // Granular widget caches
        redisCache.evict(GCP_GRANULAR_KPI_REDIS  + tenantPrefix + gcpProjectId);
        redisCache.evict(GCP_GRANULAR_INV_REDIS  + tenantPrefix + gcpProjectId);
        redisCache.evict(GCP_GRANULAR_COST_REDIS + tenantPrefix + gcpProjectId);
        redisCache.evict(GCP_GRANULAR_OPT_REDIS  + tenantPrefix + gcpProjectId);
        redisCache.evict(GCP_GRANULAR_MAP_REDIS  + tenantPrefix + gcpProjectId);
        gcpCostService.clearCostCacheForProject(gcpProjectId);
        log.info("✅ Cleared all cache for GCP project: {}", gcpProjectId);
    }

    public void clearDashboardCache(String gcpProjectId) {
        String tenantId = TenantContext.getCurrentTenant();
        String tenantPrefix = (tenantId != null) ? tenantId + ":" : "";
        redisCache.evict(DASHBOARD_CACHE_PREFIX + tenantPrefix + gcpProjectId);
        log.info("🗑️ Cleared dashboard cache for project {} (tenant: {})", gcpProjectId, tenantId);
    }

    public void clearResourcesCache(String gcpProjectId) {
        String tenantId = TenantContext.getCurrentTenant();
        String tenantPrefix = (tenantId != null) ? tenantId + ":" : "";
        redisCache.evict(ALL_RESOURCES_CACHE_PREFIX + tenantPrefix + gcpProjectId);
        log.info("🗑️ Cleared resources cache for project {} (tenant: {})", gcpProjectId, tenantId);
    }

    // ============================================================================================
    // DATABASE SNAPSHOT METHODS (for persistent caching - same approach as AWS
    // dashboard)
    // ============================================================================================

    /** Saves both Redis (60 min) and DB atomically — mirrors AWS DashboardDataService.saveSnapshot(). */
    private void saveGcpSnapshot(String redisCacheKey, String dbCacheKey, GcpDashboardData data) {
        saveGcpDashboardSnapshot(dbCacheKey, data);
        redisCache.put(redisCacheKey, data, 60);
    }

    /**
     * Save GCP dashboard data to database cache for persistent storage.
     * This ensures data survives Redis restarts and is available on application
     * startup.
     * Uses TransactionTemplate to ensure proper transaction handling even when
     * called from async context.
     */
    public void saveGcpDashboardSnapshot(String key, GcpDashboardData data) {
        try {
            String jsonData = objectMapper.writeValueAsString(data);

            transactionTemplate.executeWithoutResult(status -> {
                CachedData cachedData = cachedDataRepository.findById(key).orElse(new CachedData());
                cachedData.setCacheKey(key);
                cachedData.setJsonData(jsonData);
                cachedData.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(cachedData);
                cachedDataRepository.flush(); // Ensure immediate persistence
            });

            log.info("✅ Saved GCP dashboard snapshot to DB: {}", key);
        } catch (JsonProcessingException e) {
            log.error("❌ Failed to serialize GCP dashboard snapshot {}", key, e);
        } catch (Exception e) {
            log.error("❌ Failed to save GCP dashboard snapshot to DB {}: {}", key, e.getMessage(), e);
        }
    }

    /**
     * Retrieve GCP dashboard data from database cache.
     * Returns null if not found or if deserialization fails.
     */
    public GcpDashboardData getGcpDashboardSnapshot(String key) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            try {
                GcpDashboardData data = objectMapper.readValue(cached.get().getJsonData(), GcpDashboardData.class);
                // Set lastUpdated from database timestamp
                if (cached.get().getLastUpdated() != null) {
                    data.setLastUpdated(cached.get().getLastUpdated()
                            .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
                }
                log.info("✅ Retrieved GCP dashboard snapshot from DB: {} (Last Updated: {})",
                        key, cached.get().getLastUpdated());
                return data;
            } catch (JsonProcessingException e) {
                log.error("❌ Failed to deserialize GCP dashboard snapshot {}", key, e);
            }
        }
        return null;
    }

    /**
     * Get the last updated timestamp for a GCP dashboard cache entry.
     */
    public java.time.LocalDateTime getGcpDashboardCacheTimestamp(String gcpProjectId) {
        String dbCacheKey = DB_DASHBOARD_CACHE_PREFIX + gcpProjectId;
        Optional<CachedData> cached = cachedDataRepository.findById(dbCacheKey);
        return cached.map(CachedData::getLastUpdated).orElse(null);
    }

    /**
     * Force refresh GCP dashboard data and save to both Redis and database.
     * This is called by the startup runner and nightly scheduler.
     */
    public CompletableFuture<GcpDashboardData> refreshAndCacheGcpDashboard(String gcpProjectId) {
        log.info("🔄 Force refreshing GCP dashboard for project: {}", gcpProjectId);
        return getDashboardData(gcpProjectId, true);
    }

    // ============================================================================================
    // CLOUDLIST SERVICES GROUPED & PAGINATED (Top 50 Most Used Services)
    // ============================================================================================

    /**
     * Get all GCP resources grouped by service type, sorted by usage (resource
     * count) descending.
     * Returns top services paginated. Default shows top 50 services across all
     * pages.
     *
     * @param gcpProjectId    The GCP project ID
     * @param page            The page number (0-indexed)
     * @param servicesPerPage Number of services per page (default: 10)
     * @param forceRefresh    Whether to bypass cache
     * @return Paginated response with services sorted by resource count (most used
     *         first)
     */
    public CompletableFuture<GcpServicePaginatedResponse> getServicesGroupedPaginated(
            String gcpProjectId, int page, int servicesPerPage, boolean forceRefresh) {

        return getGroupedResources(gcpProjectId, forceRefresh)
                .thenApply(allServiceGroups -> {
                    int totalServices = allServiceGroups.size();
                    int totalResources = allServiceGroups.stream().mapToInt(GcpServicePaginatedResponse.GcpServiceGroupDto::getResourceCount).sum();
                    int totalPages = Math.max(1, (int) Math.ceil((double) totalServices / servicesPerPage));

                    int currentPage = Math.max(0, Math.min(page, totalPages - 1));
                    int startIndex = currentPage * servicesPerPage;
                    int endIndex = Math.min(startIndex + servicesPerPage, totalServices);

                    List<GcpServicePaginatedResponse.GcpServiceGroupDto> pageServices =
                            (startIndex < totalServices) ? allServiceGroups.subList(startIndex, endIndex) : List.of();

                    log.info("[GcpCloudList] Paginated — page {}/{} ({} services, {} total resources) project: {}",
                            currentPage, totalPages, pageServices.size(), totalResources, gcpProjectId);

                    return new GcpServicePaginatedResponse(pageServices, currentPage, totalServices, totalPages,
                            servicesPerPage, totalResources, (currentPage + 1) < totalPages, currentPage > 0);
                });
    }

    public CompletableFuture<List<GcpServicePaginatedResponse.GcpServiceGroupDto>> getAllResourcesGrouped(
            String gcpProjectId, boolean forceRefresh) {
        return getGroupedResources(gcpProjectId, forceRefresh);
    }

    /** Returns grouped resources — checks dedicated grouped cache before falling back to flat scan. */
    private CompletableFuture<List<GcpServicePaginatedResponse.GcpServiceGroupDto>> getGroupedResources(
            String gcpProjectId, boolean forceRefresh) {
        String tenantPrefix    = tenantPrefix();
        String groupedRedisKey = GCP_CLOUDLIST_GROUPED_REDIS + gcpProjectId;
        String groupedDbKey    = GCP_CLOUDLIST_GROUPED_DB + tenantPrefix + gcpProjectId;

        if (!forceRefresh) {
            Optional<List<GcpServicePaginatedResponse.GcpServiceGroupDto>> redisCached =
                    redisCache.get(groupedRedisKey, new TypeReference<List<GcpServicePaginatedResponse.GcpServiceGroupDto>>() {});
            if (redisCached.isPresent()) {
                log.info("[GcpCloudList] Grouped Redis hit for project: {}", gcpProjectId);
                return CompletableFuture.completedFuture(redisCached.get());
            }
            List<GcpServicePaginatedResponse.GcpServiceGroupDto> dbCached = getCloudlistGroupedSnapshot(groupedDbKey);
            if (dbCached != null) {
                log.info("[GcpCloudList] Grouped DB hit — re-warming Redis for project: {}", gcpProjectId);
                redisCache.put(groupedRedisKey, dbCached, 240);
                return CompletableFuture.completedFuture(dbCached);
            }
        }

        // Fall back to full flat scan (getAllResources will also populate the grouped cache)
        return getAllResources(gcpProjectId, forceRefresh).thenApply(this::buildGrouped);
    }

    /** Returns a single resource by resourceId from the flat Redis/DB cache. */
    public CompletableFuture<Optional<GcpResourceDto>> getResourceById(String gcpProjectId, String resourceId) {
        return getAllResources(gcpProjectId, false)
                .thenApply(resources -> resources.stream()
                        .filter(r -> resourceId.equals(r.getId()) || resourceId.equals(r.getName()))
                        .findFirst());
    }

    /** Returns active GCP regions derived from current resource locations. */
    public CompletableFuture<List<java.util.Map<String, Object>>> getRegionStatus(String gcpProjectId) {
        return getAllResources(gcpProjectId, false).thenApply(resources -> {
            Set<String> activeRegions = resources.stream()
                    .map(GcpResourceDto::getLocation)
                    .filter(Objects::nonNull)
                    .map(loc -> {
                        String[] parts = loc.split("-");
                        return parts.length > 2 ? parts[0] + "-" + parts[1] : loc;
                    })
                    .filter(regionCoordinates::containsKey)
                    .collect(Collectors.toSet());

            return activeRegions.stream().map(regionId -> {
                double[] coords = regionCoordinates.get(regionId);
                Map<String, Object> region = new java.util.LinkedHashMap<>();
                region.put("regionId", regionId);
                region.put("status", "ACTIVE");
                region.put("lat", coords[0]);
                region.put("lng", coords[1]);
                return region;
            }).collect(Collectors.toList());
        });
    }

}