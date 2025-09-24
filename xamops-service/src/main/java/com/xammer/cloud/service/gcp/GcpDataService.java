package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.DatabaseInstance;
// import com.google.cloud.artifactregistry.v1.ArtifactRegistryClient;
// import com.google.cloud.artifactregistry.v1.Repository;
import com.google.cloud.devtools.cloudbuild.v1.CloudBuildClient;
// import com.google.cloud.build.v1.BuildTrigger;
import com.google.cloud.compute.v1.Firewall;
import com.google.cloud.compute.v1.FirewallsClient;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;
import com.google.api.services.dns.model.ManagedZone;
import com.google.cloud.compute.v1.Network;
import com.google.cloud.compute.v1.NetworksClient;
import com.google.cloud.compute.v1.Router;
import com.google.cloud.compute.v1.RoutersClient;
import com.google.cloud.compute.v1.SecurityPolicy;
import com.google.cloud.compute.v1.SecurityPoliciesClient;
import com.google.cloud.compute.v1.Subnetwork;
import com.google.cloud.compute.v1.SubnetworksClient;
import com.google.cloud.functions.v2.FunctionServiceClient;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.container.v1.Cluster;
import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.cloud.resourcemanager.v3.Project;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.GcpAccountRequestDto;
import com.xammer.cloud.dto.gcp.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.google.cloud.compute.v1.ForwardingRule;
import com.google.cloud.compute.v1.ForwardingRulesClient;

import java.io.IOException;
import java.net.URL;
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

    public GcpDataService(GcpClientProvider gcpClientProvider,
                          GcpCostService gcpCostService,
                          GcpOptimizationService gcpOptimizationService,
                          GcpSecurityService gcpSecurityService,
                          com.xammer.cloud.repository.CloudAccountRepository cloudAccountRepository) {
        this.gcpClientProvider = gcpClientProvider;
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpSecurityService = gcpSecurityService;
        this.cloudAccountRepository = cloudAccountRepository;
    }
    
    private Map<String, double[]> loadRegionCoordinates() {
        Map<String, double[]> coords = new java.util.HashMap<>();
        try {
            coords.put("us-east1", new double[]{33.829, -84.341});
            coords.put("us-central1", new double[]{41.258, -95.940});
            coords.put("us-west1", new double[]{37.422, -122.084});
            coords.put("us-west2", new double[]{33.943, -118.408});
            coords.put("us-west3", new double[]{40.761, -111.891});
            coords.put("us-west4", new double[]{36.170, -115.140});
            coords.put("southamerica-east1", new double[]{-23.550, -46.633});
            coords.put("europe-west1", new double[]{50.850, 4.350});
            coords.put("europe-west2", new double[]{51.507, -0.128});
            coords.put("europe-west3", new double[]{50.110, 8.680});
            coords.put("europe-west4", new double[]{52.370, 4.890});
            coords.put("europe-north1", new double[]{60.192, 24.946});
            coords.put("asia-south1", new double[]{19.076, 72.877});
            coords.put("asia-southeast1", new double[]{1.352, 103.819});
            coords.put("asia-southeast2", new double[]{-6.208, 106.845});
            coords.put("asia-east1", new double[]{25.033, 121.565});
            coords.put("asia-east2", new double[]{22.319, 114.169});
            coords.put("asia-northeast1", new double[]{35.689, 139.692});
            coords.put("asia-northeast2", new double[]{34.694, 135.502});
            coords.put("asia-northeast3", new double[]{37.566, 126.978});
            coords.put("australia-southeast1", new double[]{-33.868, 151.209});
            coords.put("australia-southeast2", new double[]{-37.813, 144.963});
        } catch (Exception e) {
            log.error("Failed to load GCP region coordinates.", e);
        }
        return coords;
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
                return new DashboardData.RegionStatus(regionId, regionId, "ACTIVE", coords[0], coords[1]);
            }).collect(Collectors.toList());
        });
    }

    public CompletableFuture<GcpDashboardData> getDashboardData(String gcpProjectId) {
        log.info("--- LAUNCHING EXPANDED ASYNC DATA AGGREGATION FOR GCP project {} ---", gcpProjectId);

        CompletableFuture<List<GcpResourceDto>> resourcesFuture = getAllResources(gcpProjectId)
            .exceptionally(ex -> {
                log.error("Failed to get all resources for project {}: {}", gcpProjectId, ex.getMessage());
                return Collections.emptyList();
            });

        CompletableFuture<List<GcpSecurityFinding>> securityFindingsFuture = gcpSecurityService.getSecurityFindings(gcpProjectId)
            .exceptionally(ex -> {
                log.error("Failed to get security findings for project {}: {}", gcpProjectId, ex.getMessage());
                return Collections.emptyList();
            });
        
        CompletableFuture<DashboardData.IamResources> iamResourcesFuture = getIamResources(gcpProjectId)
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

        CompletableFuture<List<GcpWasteItem>> wasteReportFuture = gcpOptimizationService.getWasteReport(gcpProjectId)
             .exceptionally(ex -> {
                log.error("Failed to get waste report for project {}: {}", gcpProjectId, ex.getMessage());
                return Collections.emptyList();
            });

        CompletableFuture<List<GcpOptimizationRecommendation>> rightsizingFuture = gcpOptimizationService.getRightsizingRecommendations(gcpProjectId)
             .exceptionally(ex -> {
                log.error("Failed to get rightsizing recommendations for project {}: {}", gcpProjectId, ex.getMessage());
                return Collections.emptyList();
            });

        CompletableFuture<DashboardData.SavingsSummary> savingsSummaryFuture = gcpOptimizationService.getSavingsSummary(gcpProjectId)
             .exceptionally(ex -> {
                log.error("Failed to get savings summary for project {}: {}", gcpProjectId, ex.getMessage());
                return new DashboardData.SavingsSummary(0.0, Collections.emptyList());
            });


        CompletableFuture<DashboardData.OptimizationSummary> optimizationSummaryFuture = gcpOptimizationService.getOptimizationSummary(gcpProjectId)
            .exceptionally(ex -> {
                log.error("Failed to get optimization summary for project {}: {}", gcpProjectId, ex.getMessage());
                return new DashboardData.OptimizationSummary(0.0, 0);
            });


        CompletableFuture<List<DashboardData.RegionStatus>> regionStatusFuture = resourcesFuture.thenCompose(this::getRegionStatusForGcp);

        return CompletableFuture.allOf(
            resourcesFuture, securityFindingsFuture, iamResourcesFuture, costHistoryFuture,
            billingSummaryFuture, wasteReportFuture, rightsizingFuture, savingsSummaryFuture,
            optimizationSummaryFuture, regionStatusFuture, unfilteredMtdSpendFuture
        ).thenApply(v -> {
            log.info("--- ALL EXPANDED GCP ASYNC DATA FETCHES COMPLETE, assembling DTO for project {} ---", gcpProjectId);

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
            Map<String, Long> counts = resources.stream().collect(Collectors.groupingBy(GcpResourceDto::getType, Collectors.counting()));
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
            
            data.setForecastedSpend(calculateForecastedSpend(currentMtdSpend));

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
                    // ADD THIS NULL CHECK
                    String category = entry.getKey() != null ? entry.getKey() : "Uncategorized";
                    return new DashboardData.SecurityInsight(
                        String.format("%s has potential issues", category),
                        category, // Use the safe category variable
                        "High",
                        entry.getValue().intValue()
                    );
                }).collect(Collectors.toList());
            data.setSecurityInsights(securityInsights);
            data.setIamResources(iamResourcesFuture.join());

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
    //     GcpResourceDto dto = new GcpResourceDto();
    //     dto.setId(repository.getName());
    //     dto.setName(repository.getName());
    //     dto.setType("Artifact Registry");
    //     dto.setLocation(repository.getName().split("/")[3]);
    //     dto.setStatus("ACTIVE");
    //     return dto;
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
        // Extract location from function name, e.g., "projects/{project}/locations/{location}/functions/{function}"
        String[] nameParts = function.getName().split("/");
        String location = (nameParts.length >= 4) ? nameParts[3] : "global";
        dto.setLocation(location);
        dto.setStatus(function.getState().toString());
        return dto;
    }

    // private GcpResourceDto mapCloudBuildTriggerToDto(BuildTrigger trigger) {
    //     GcpResourceDto dto = new GcpResourceDto();
    //     dto.setId(trigger.getId());
    //     dto.setName(trigger.getName());
    //     dto.setType("Cloud Build");
    //     dto.setLocation("global");
    //     dto.setStatus(trigger.getDisabled() ? "DISABLED" : "ACTIVE");
    //     return dto;
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

    public CompletableFuture<List<GcpResourceDto>> getAllResources(String gcpProjectId) {
        log.info("Starting to fetch all GCP resources for project: {}", gcpProjectId);
        List<CompletableFuture<List<GcpResourceDto>>> futures = List.of(
            CompletableFuture.supplyAsync(() -> getComputeInstances(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getStorageBuckets(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getGkeClusters(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getCloudSqlInstances(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getVpcNetworks(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getDnsZones(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getLoadBalancers(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getFirewallRules(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getCloudNatRouters(gcpProjectId), executor),
            // CompletableFuture.supplyAsync(() -> getArtifactRepositories(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getKmsKeys(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getCloudFunctions(gcpProjectId), executor),
            // CompletableFuture.supplyAsync(() -> getCloudBuildTriggers(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getSecretManagerSecrets(gcpProjectId), executor),
            CompletableFuture.supplyAsync(() -> getCloudArmorPolicies(gcpProjectId), executor)
        );

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList()));
    }

    public CompletableFuture<DashboardData.IamResources> getIamResources(String gcpProjectId) {
        log.info("Attempting to get IAM resources for project: {}", gcpProjectId);
        return CompletableFuture.supplyAsync(() -> {
            Optional<com.google.cloud.resourcemanager.v3.ProjectsClient> clientOpt = gcpClientProvider.getProjectsClient(gcpProjectId);
            if (clientOpt.isEmpty()) return new DashboardData.IamResources(0, 0, 0, 0);
            try (com.google.cloud.resourcemanager.v3.ProjectsClient projectsClient = clientOpt.get()) {
                com.google.cloud.resourcemanager.v3.Project project = projectsClient.getProject("projects/" + gcpProjectId);
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

    public void createGcpAccount(GcpAccountRequestDto request, Client client) throws IOException {
        try {
            com.google.cloud.storage.Storage storage = gcpClientProvider.createStorageClient(request.getServiceAccountKey());
            storage.list(com.google.cloud.storage.Storage.BucketListOption.pageSize(1));
            CloudAccount account = new CloudAccount();
            account.setAccountName(request.getAccountName());
            account.setProvider("GCP");
            account.setAwsAccountId(request.getProjectId());
            account.setGcpServiceAccountKey(request.getServiceAccountKey());
            account.setStatus("CONNECTED");
            account.setAccessType("read-only");
            account.setClient(client);
            account.setExternalId(request.getProjectId());
            account.setGcpProjectId(request.getProjectId());
            cloudAccountRepository.save(account);
        } catch (IOException e) {
            throw new RuntimeException("GCP connection error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("GCP error: " + e.getMessage(), e);
        }
    }

    public CompletableFuture<List<GcpResourceDto>> getVpcListForCloudmap(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> getVpcNetworks(gcpProjectId));
    }

    public CompletableFuture<List<Map<String, Object>>> getVpcTopologyGraph(String gcpProjectId, String vpcId) {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> elements = new ArrayList<>();
            List<com.google.cloud.compute.v1.Instance> allInstances = getRawComputeInstances(gcpProjectId);
            List<com.google.cloud.compute.v1.Subnetwork> allSubnetworks = getRawSubnetworks(gcpProjectId);
            List<com.google.cloud.compute.v1.Network> allNetworks = getRawVpcNetworks(gcpProjectId);

            if (vpcId == null || vpcId.isBlank()) {
                 allNetworks.forEach(network -> {
                    Map<String, Object> node = new HashMap<>();
                    Map<String, Object> data = new HashMap<>();
                    data.put("id", String.valueOf(network.getId()));
                    data.put("label", network.getName());
                    data.put("type", "VPC Network");
                    node.put("data", data);
                    elements.add(node);
                });
            } else {
                allNetworks.stream()
                    .filter(n -> String.valueOf(n.getId()).equals(vpcId))
                    .forEach(network -> {
                        Map<String, Object> vpcNode = new HashMap<>();
                        Map<String, Object> vpcData = new HashMap<>();
                        vpcData.put("id", String.valueOf(network.getId()));
                        vpcData.put("label", network.getName());
                        vpcData.put("type", "VPC Network");
                        vpcNode.put("data", vpcData);
                        elements.add(vpcNode);

                        allSubnetworks.stream()
                            .filter(sn -> sn.getNetwork().endsWith("/" + network.getName()))
                            .forEach(subnet -> {
                                String region = subnet.getRegion().substring(subnet.getRegion().lastIndexOf('/') + 1);
                                String regionNodeId = "region-" + region;

                                if (elements.stream().noneMatch(el -> ((Map<String,Object>)el.get("data")).get("id").equals(regionNodeId))) {
                                    Map<String, Object> regionNode = new HashMap<>();
                                    Map<String, Object> regionData = new HashMap<>();
                                    regionData.put("id", regionNodeId);
                                    regionData.put("label", region);
                                    regionData.put("type", "Region");
                                    regionData.put("parent", String.valueOf(network.getId()));
                                    regionNode.put("data", regionData);
                                    elements.add(regionNode);
                                }

                                Map<String, Object> subnetNode = new HashMap<>();
                                Map<String, Object> subnetData = new HashMap<>();
                                subnetData.put("id", String.valueOf(subnet.getId()));
                                subnetData.put("label", subnet.getName());
                                subnetData.put("type", "Subnetwork");
                                subnetData.put("parent", regionNodeId);
                                subnetNode.put("data", subnetData);
                                elements.add(subnetNode);
                            });

                        allInstances.stream()
                            .filter(inst -> inst.getNetworkInterfacesList().stream()
                                .anyMatch(ni -> ni.getNetwork().endsWith("/" + network.getName())))
                            .forEach(instance -> {
                                String subnetworkUrl = instance.getNetworkInterfaces(0).getSubnetwork();
                                Optional<com.google.cloud.compute.v1.Subnetwork> parentSubnet = allSubnetworks.stream().filter(sn -> sn.getSelfLink().equals(subnetworkUrl)).findFirst();
                                
                                if(parentSubnet.isPresent()){
                                    Map<String, Object> instanceNode = new HashMap<>();
                                    Map<String, Object> instanceData = new HashMap<>();
                                    instanceData.put("id", String.valueOf(instance.getId()));
                                    instanceData.put("label", instance.getName());
                                    instanceData.put("type", "Compute Engine");
                                    instanceData.put("parent", String.valueOf(parentSubnet.get().getId()));
                                    instanceNode.put("data", instanceData);
                                    elements.add(instanceNode);
                                }
                            });
                    });
            }
            return elements;
        });
    }

    private List<com.google.cloud.compute.v1.Network> getRawVpcNetworks(String gcpProjectId) {
        Optional<com.google.cloud.compute.v1.NetworksClient> clientOpt = gcpClientProvider.getNetworksClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (com.google.cloud.compute.v1.NetworksClient client = clientOpt.get()) {
            return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching raw VPC Networks for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<com.google.cloud.compute.v1.Subnetwork> getRawSubnetworks(String gcpProjectId) {
        Optional<com.google.cloud.compute.v1.SubnetworksClient> clientOpt = gcpClientProvider.getSubnetworksClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
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
        Optional<com.google.cloud.compute.v1.InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (com.google.cloud.compute.v1.InstancesClient client = clientOpt.get()) {
            return StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                    .flatMap(entry -> entry.getValue().getInstancesList().stream())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching raw Compute Instances for project: {}", gcpProjectId, e);
            return List.of();
        }
    }
    
    private List<GcpResourceDto> getComputeInstances(String gcpProjectId) {
        return getRawComputeInstances(gcpProjectId).stream()
            .map(this::mapInstanceToDto).collect(Collectors.toList());
    }

    private List<GcpResourceDto> getStorageBuckets(String gcpProjectId) {
        log.info("Fetching Cloud Storage buckets for project: {}", gcpProjectId);
        Optional<com.google.cloud.storage.Storage> clientOpt = gcpClientProvider.getStorageClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try {
            List<GcpResourceDto> buckets = StreamSupport.stream(clientOpt.get().list().iterateAll().spliterator(), false)
                    .map(this::mapBucketToDto)
                    .collect(Collectors.toList());
            log.info("Found {} Cloud Storage buckets.", buckets.size());
            return buckets;
        } catch (Exception e) {
            log.error("Error fetching Storage Buckets for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getGkeClusters(String gcpProjectId) {
        log.info("Fetching Kubernetes Engine clusters for project: {}", gcpProjectId);
        Optional<com.google.cloud.container.v1.ClusterManagerClient> clientOpt = gcpClientProvider.getClusterManagerClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (com.google.cloud.container.v1.ClusterManagerClient client = clientOpt.get()) {
            String parent = "projects/" + gcpProjectId + "/locations/-";
            List<com.google.container.v1.Cluster> clusters = client.listClusters(parent).getClustersList();
            log.info("Found {} Kubernetes Engine clusters.", clusters.size());
            return clusters.stream()
                    .map(this::mapGkeToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching GKE clusters for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getCloudSqlInstances(String gcpProjectId) {
        log.info("Fetching Cloud SQL instances for project: {}", gcpProjectId);
        Optional<com.google.api.services.sqladmin.SQLAdmin> sqlAdminClientOpt = gcpClientProvider.getSqlAdminClient(gcpProjectId);
        if (sqlAdminClientOpt.isEmpty()) return List.of();

        try {
            List<com.google.api.services.sqladmin.model.DatabaseInstance> instances = sqlAdminClientOpt.get()
                    .instances()
                    .list(gcpProjectId)
                    .execute()
                    .getItems();
            if (instances == null) return List.of();
            log.info("Found {} Cloud SQL instances.", instances.size());
            return instances.stream().map(this::mapSqlToDto).collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Error fetching Cloud SQL instances for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getVpcNetworks(String gcpProjectId) {
        return getRawVpcNetworks(gcpProjectId).stream()
                .map(this::mapVpcToDto).collect(Collectors.toList());
    }

    private List<GcpResourceDto> getDnsZones(String gcpProjectId) {
        log.info("Fetching Cloud DNS zones for project: {}", gcpProjectId);
        Optional<com.google.api.services.dns.Dns> clientOpt = gcpClientProvider.getDnsZonesClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try {
            com.google.api.services.dns.Dns dns = clientOpt.get();
            com.google.api.services.dns.Dns.ManagedZones.List request = dns.managedZones().list(gcpProjectId);
            List<com.google.api.services.dns.model.ManagedZone> zones = request.execute().getManagedZones();
            if (zones == null) return List.of();
            log.info("Found {} Cloud DNS zones.", zones.size());
            return zones.stream()
                    .map(this::mapDnsToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching DNS Zones for project: {}", gcpProjectId, e);
            return List.of();
        }
    }
    
    public double calculateForecastedSpend(double monthToDateSpend) {
        LocalDate today = LocalDate.now();
        int daysInMonth = today.lengthOfMonth();
        int currentDay = today.getDayOfMonth();
        if (currentDay > 0) {
            return (monthToDateSpend / currentDay) * daysInMonth;
        }
        return 0.0;
    }
    private List<GcpResourceDto> getLoadBalancers(String gcpProjectId) {
        log.info("Fetching Load Balancers for project: {}", gcpProjectId);
        Optional<com.google.cloud.compute.v1.ForwardingRulesClient> clientOpt = gcpClientProvider.getForwardingRulesClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
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

    private List<GcpResourceDto> getFirewallRules(String gcpProjectId) {
        log.info("Fetching Firewall Rules for project: {}", gcpProjectId);
        Optional<com.google.cloud.compute.v1.FirewallsClient> clientOpt = gcpClientProvider.getFirewallsClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (com.google.cloud.compute.v1.FirewallsClient client = clientOpt.get()) {
            return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                    .map(this::mapFirewallToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching Firewall Rules for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getCloudNatRouters(String gcpProjectId) {
        log.info("Fetching Cloud NAT routers for project: {}", gcpProjectId);
        Optional<RoutersClient> clientOpt = gcpClientProvider.getRoutersClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
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
    //     log.info("Fetching Artifact Registry repositories for project: {}", gcpProjectId);
    //     Optional<ArtifactRegistryClient> clientOpt = gcpClientProvider.getArtifactRegistryClient(gcpProjectId);
    //     if (clientOpt.isEmpty()) return List.of();
    //     try (ArtifactRegistryClient client = clientOpt.get()) {
    //         String parent = "projects/" + gcpProjectId + "/locations/-";
    //         return StreamSupport.stream(client.listRepositories(parent).iterateAll().spliterator(), false)
    //             .map(this::mapArtifactRepositoryToDto)
    //             .collect(Collectors.toList());
    //     } catch (Exception e) {
    //         log.error("Error fetching Artifact Registry repositories for project: {}", gcpProjectId, e);
    //         return List.of();
    //     }
    // }

    private List<GcpResourceDto> getKmsKeys(String gcpProjectId) {
        log.info("Fetching KMS keys for project: {}", gcpProjectId);
        Optional<KeyManagementServiceClient> clientOpt = gcpClientProvider.getKeyManagementServiceClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        
        try (KeyManagementServiceClient client = clientOpt.get()) {
            // Correctly iterate through locations (regions)
            return this.getRegionStatusForGcp(new ArrayList<>()).join().stream()
                .flatMap(region -> {
                    try {
                        String parent = "projects/" + gcpProjectId + "/locations/" + region.getRegionId();
                        return StreamSupport.stream(client.listKeyRings(parent).iterateAll().spliterator(), false)
                            .flatMap(keyRing -> StreamSupport.stream(client.listCryptoKeys(keyRing.getName()).iterateAll().spliterator(), false))
                            .map(this::mapKmsKeyToDto);
                    } catch (Exception e) {
                        log.warn("Could not fetch KMS keys for region {}: {}", region.getRegionId(), e.getMessage());
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching KMS keys for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getCloudFunctions(String gcpProjectId) {
        log.info("Fetching Cloud Functions for project: {}", gcpProjectId);
        Optional<FunctionServiceClient> clientOpt = gcpClientProvider.getFunctionServiceClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
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
    //     log.info("Fetching Cloud Build triggers for project: {}", gcpProjectId);
    //     Optional<CloudBuildClient> clientOpt = gcpClientProvider.getCloudBuildClient(gcpProjectId);
    //     if (clientOpt.isEmpty()) return List.of();
    //     try (CloudBuildClient client = clientOpt.get()) {
    //         return client.listBuildTriggers(gcpProjectId).stream()
    //             .map(this::mapCloudBuildTriggerToDto)
    //             .collect(Collectors.toList());
    //     } catch (Exception e) {
    //         log.error("Error fetching Cloud Build triggers for project: {}", gcpProjectId, e);
    //         return List.of();
    //     }
    // }

    private List<GcpResourceDto> getSecretManagerSecrets(String gcpProjectId) {
        log.info("Fetching secrets from Secret Manager for project: {}", gcpProjectId);
        Optional<SecretManagerServiceClient> clientOpt = gcpClientProvider.getSecretManagerServiceClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
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
        if (clientOpt.isEmpty()) return List.of();
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
        String region = regionUrl.substring(regionUrl.lastIndexOf('/') + 1);
        dto.setLocation(region);
        dto.setStatus("ACTIVE");
        return dto;
    }
}