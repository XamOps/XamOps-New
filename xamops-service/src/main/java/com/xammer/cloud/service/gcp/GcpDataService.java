package com.xammer.cloud.service.gcp;

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
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.GcpAccountRequestDto;
import com.xammer.cloud.dto.gcp.*;
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

import javax.transaction.Transactional;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
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
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, double[]> regionCoordinates = loadRegionCoordinates();
    private final ObjectMapper objectMapper;
    private final RedisCacheService redisCache;

    private static final String DASHBOARD_CACHE_PREFIX = "gcp:dashboard:";
    private static final String ALL_RESOURCES_CACHE_PREFIX = "gcp:all-resources:";
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
            com.xammer.cloud.repository.CloudAccountRepository cloudAccountRepository, RedisCacheService redisCache,
            ObjectMapper objectMapper) {
        this.gcpClientProvider = gcpClientProvider;
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpSecurityService = gcpSecurityService;
        this.cloudAccountRepository = cloudAccountRepository;
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
        String cacheKey = DASHBOARD_CACHE_PREFIX + gcpProjectId;

        // Check cache first (unless force refresh)
        if (!forceRefresh) {
            Optional<GcpDashboardData> cached = redisCache.get(cacheKey, GcpDashboardData.class);
            if (cached.isPresent()) {
                log.info("✅ Returning cached GCP dashboard data for project: {}", gcpProjectId);
                return CompletableFuture.completedFuture(cached.get());
            }
        }

        log.info("🔍 Fetching GCP dashboard data for project {}", gcpProjectId);
        log.info("--- LAUNCHING EXPANDED ASYNC DATA AGGREGATION FOR GCP project {} ---", gcpProjectId);

        CompletableFuture<List<GcpResourceDto>> resourcesFuture = getAllResources(gcpProjectId, forceRefresh)
                .exceptionally(ex -> {
                    log.error("Failed to get all resources for project {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<List<GcpSecurityFinding>> securityFindingsFuture = gcpSecurityService
                .getSecurityFindings(gcpProjectId)
                .exceptionally(ex -> {
                    log.error("Failed to get security findings for project {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<DashboardData.IamResources> iamResourcesFuture = getIamResources(gcpProjectId, forceRefresh)
                .exceptionally(ex -> {
                    log.error("Failed to get IAM resources for project {}: {}", gcpProjectId, ex.getMessage());
                    return new DashboardData.IamResources(0, 0, 0, 0);
                });

        CompletableFuture<Double> unfilteredMtdSpendFuture = gcpCostService.getUnfilteredMonthToDateSpend(gcpProjectId)
                .exceptionally(ex -> {
                    log.error("Failed to get unfiltered MTD spend for project {}: {}", gcpProjectId, ex.getMessage());
                    return 0.0;
                });

        CompletableFuture<List<GcpCostDto>> costHistoryFuture = gcpCostService.getHistoricalCosts(gcpProjectId)
                .exceptionally(ex -> {
                    log.error("Failed to get cost history for project {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<List<GcpCostDto>> billingSummaryFuture = gcpCostService.getBillingSummary(gcpProjectId)
                .exceptionally(ex -> {
                    log.error("Failed to get billing summary for project {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<List<GcpWasteItem>> wasteReportFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getWasteReport(gcpProjectId), executor)
                .exceptionally(ex -> {
                    log.error("Failed to get waste report for project {}: {}", gcpProjectId, ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<List<GcpOptimizationRecommendation>> rightsizingFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getRightsizingRecommendations(gcpProjectId), executor)
                .exceptionally(ex -> {
                    log.error("Failed to get rightsizing recommendations for project {}: {}", gcpProjectId,
                            ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<DashboardData.SavingsSummary> savingsSummaryFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getSavingsSummary(gcpProjectId), executor)
                .exceptionally(ex -> {
                    log.error("Failed to get savings summary for project {}: {}", gcpProjectId, ex.getMessage());
                    return new DashboardData.SavingsSummary(0.0, 0.0);
                });

        CompletableFuture<DashboardData.OptimizationSummary> optimizationSummaryFuture = CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getOptimizationSummary(gcpProjectId), executor)
                .exceptionally(ex -> {
                    log.error("Failed to get optimization summary for project {}: {}", gcpProjectId, ex.getMessage());
                    return new DashboardData.OptimizationSummary(0.0, 0);
                });

        CompletableFuture<List<DashboardData.RegionStatus>> regionStatusFuture = resourcesFuture
                .thenCompose(this::getRegionStatusForGcp);

        return CompletableFuture.allOf(
                resourcesFuture, securityFindingsFuture, iamResourcesFuture, costHistoryFuture,
                billingSummaryFuture, wasteReportFuture, rightsizingFuture, savingsSummaryFuture,
                optimizationSummaryFuture, regionStatusFuture, unfilteredMtdSpendFuture).thenApply(v -> {
                    log.info("--- ALL EXPANDED GCP ASYNC DATA FETCHES COMPLETE, assembling DTO for project {} ---",
                            gcpProjectId);

                    GcpDashboardData data = new GcpDashboardData();

                    data.setRegionStatus(regionStatusFuture.join());
                    data.setCostHistory(costHistoryFuture.join());
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
                    double lastMonthSpend = data.getCostHistory().stream()
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
                                        category,
                                        "High",
                                        entry.getValue().intValue());
                            }).collect(Collectors.toList());

                    data.setSecurityInsights(securityInsights);
                    data.setIamResources(iamResourcesFuture.join());

                    // ✅ Cache the result for 10 minutes
                    redisCache.put(cacheKey, data, 10);

                    return data;
                });
    }

    private double calculateForecastedSpend(double currentMtdSpend) {
        LocalDate now = LocalDate.now();
        int currentDay = now.getDayOfMonth();
        int totalDaysInMonth = now.lengthOfMonth();

        // If we're at the end of the month, return current spend
        if (currentDay >= totalDaysInMonth) {
            log.info("✅ End of month reached - Forecast = MTD: ${}", currentMtdSpend);
            return currentMtdSpend;
        }

        // Calculate daily average and project to end of month
        double dailyAverage = currentMtdSpend / currentDay;
        double forecastedSpend = dailyAverage * totalDaysInMonth;

        log.info("✅ Forecast calculation - Day {}/{}: MTD=${}, Daily Avg=${}, Forecast=${}",
                currentDay, totalDaysInMonth, currentMtdSpend, dailyAverage, forecastedSpend);

        return forecastedSpend;
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
        String cacheKey = ALL_RESOURCES_CACHE_PREFIX + gcpProjectId;

        // Check cache first
        if (!forceRefresh) {
            Optional<List<GcpResourceDto>> cached = redisCache.get(
                    cacheKey,
                    new TypeReference<List<GcpResourceDto>>() {
                    });
            if (cached.isPresent()) {
                log.info("✅ Returning cached GCP resources for project: {}", gcpProjectId);
                return CompletableFuture.completedFuture(cached.get());
            }
        }

        log.info("🔍 Fetching all GCP resources for project: {}", gcpProjectId);

        List<CompletableFuture<List<GcpResourceDto>>> futures = List.of(
                CompletableFuture.supplyAsync(() -> getComputeInstances(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getStorageBuckets(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getGkeClusters(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getCloudSqlInstances(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getVpcNetworks(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getDnsZones(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getLoadBalancers(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getFirewallRules(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getCloudNatRouters(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getKmsKeys(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getCloudFunctions(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getSecretManagerSecrets(gcpProjectId, forceRefresh), executor),
                CompletableFuture.supplyAsync(() -> getCloudArmorPolicies(gcpProjectId), executor),
                CompletableFuture.supplyAsync(() -> getApiGateways(gcpProjectId), executor),
                // CompletableFuture.supplyAsync(() -> getAppEngineApplications(gcpProjectId),
                // executor),
                CompletableFuture.supplyAsync(() -> getBigQueryDatasets(gcpProjectId), executor),
                CompletableFuture.supplyAsync(() -> getLoggingBuckets(gcpProjectId), executor),
                // CompletableFuture.supplyAsync(() -> getArtifactRepositories(gcpProjectId),
                // executor),
                // CompletableFuture.supplyAsync(() -> getCloudBuildTriggers(gcpProjectId),
                // executor),

                // ✅ NEW - Vertex AI
                getVertexAIModels(gcpProjectId),
                getVertexAIEndpoints(gcpProjectId),

                // ✅ NEW - PubSub
                getPubSubTopics(gcpProjectId),
                getPubSubSubscriptions(gcpProjectId),

                // ✅ NEW - Monitoring
                getMonitoringAlertPolicies(gcpProjectId),
                getMonitoringUptimeChecks(gcpProjectId),

                // ✅ NEW - Scheduler & Dataplex
                getSchedulerJobs(gcpProjectId),
                getDataplexLakes(gcpProjectId),

                // ✅ NEW - BigQuery & VM Manager
                getBigQueryReservations(gcpProjectId),
                getVMManagerPatchDeployments(gcpProjectId));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<GcpResourceDto> allResources = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());

                    // ✅ Cache for 15 minutes
                    redisCache.put(cacheKey, allResources, 15);

                    return allResources;
                });
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

            // ✅ NEW: Set billing export table
            account.setBillingExportTable(request.getBillingExportTable());

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
        }, executor);
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
        }, executor);
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
        }, executor);
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
        }, executor);
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
        }, executor);
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
        }, executor);
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
        }, executor);
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
        }, executor);
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
        }, executor);
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
        }, executor);
    }

    /**
     * Clear all caches for a specific GCP project
     */
    public void clearProjectCache(String gcpProjectId) {
        log.info("🗑️ Clearing all cache for GCP project: {}", gcpProjectId);

        // Dashboard and main resources
        redisCache.evict(DASHBOARD_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(ALL_RESOURCES_CACHE_PREFIX + gcpProjectId);
        redisCache.evict(IAM_RESOURCES_CACHE_PREFIX + gcpProjectId);

        // Individual resource caches
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

        // Also clear cost-related caches
        gcpCostService.clearCostCacheForProject(gcpProjectId);

        log.info("✅ Cleared all cache for GCP project: {}", gcpProjectId);
    }

    /**
     * Clear specific cache types
     */
    public void clearDashboardCache(String gcpProjectId) {
        redisCache.evict(DASHBOARD_CACHE_PREFIX + gcpProjectId);
        log.info("🗑️ Cleared dashboard cache for project {}", gcpProjectId);
    }

    public void clearResourcesCache(String gcpProjectId) {
        redisCache.evict(ALL_RESOURCES_CACHE_PREFIX + gcpProjectId);
        log.info("🗑️ Cleared resources cache for project {}", gcpProjectId);
    }

}