package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.Disk;
import com.azure.resourcemanager.compute.models.PowerState;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.compute.models.Snapshot;
import com.azure.resourcemanager.compute.models.AvailabilitySet;
import com.azure.resourcemanager.network.models.NetworkInterface;
import com.azure.resourcemanager.network.models.PublicIpAddress;
import com.azure.resourcemanager.network.models.LoadBalancer;
import com.azure.resourcemanager.network.models.NetworkSecurityGroup;
import com.azure.resourcemanager.network.models.RouteTable;
import com.azure.resourcemanager.sql.models.SqlServer;
import com.azure.resourcemanager.sql.models.SqlDatabase;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.azure.AzureWasteItem;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Detects idle and wasteful Azure resources using Azure Resource Manager SDK.
 *
 * Detected categories:
 *  - Unattached Managed Disks (!disk.isAttachedToVirtualMachine())
 *  - Stopped VMs           (PowerState.STOPPED — still incurring compute charges)
 *  - Deallocated VMs       (PowerState.DEALLOCATED — compute stopped, storage still billed)
 *  - Orphaned Public IPs   (!pip.hasAssignedNetworkInterface() && !pip.hasAssignedLoadBalancer())
 *  - Unattached NICs       (nic.innerModel().virtualMachine() == null — NIC exists but no VM attached)
 *  - Old Snapshots         (snapshot older than 90 days — stale backup storage)
 *  - Empty Load Balancers  (no backend pool members — idle LB charge)
 *  - Unused NSGs           (network security group not associated with any subnet or NIC)
 *  - Empty Availability Sets (availability set with no VMs)
 *  - Orphaned Route Tables (route table not associated with any subnet)
 *  - Idle Running VMs      (running VM with avg CPU below 5% over 7 days — Azure Monitor metric)
 *  - Idle SQL Databases    (SQL DB with avg CPU below 5% over 7 days — Azure Monitor metric)
 *  - Empty App Service Plans (paid-tier plan hosting zero apps — ARM REST)
 *  - Idle Storage Accounts (zero transactions over 7 days — Azure Monitor metric)
 *  - Idle Cosmos DB        (zero requests over 7 days — Azure Monitor metric)
 *  - Empty Resource Groups (resource group with no resources — ARM REST)
 *
 * Results are cached in Redis (60-min TTL) + persisted to DB for cross-restart resilience.
 */
@Service
public class AzureWasteService {

    private static final Logger log = LoggerFactory.getLogger(AzureWasteService.class);
    private static final String CACHE_PREFIX = "azure:waste:";
    private static final String DB_CACHE_PREFIX = "AZURE_WASTE::";
    private static final int CACHE_TTL_MINUTES = 60;

    // Flat monthly savings estimates in INR
    private static final double SAVINGS_UNATTACHED_DISK = 620.0;
    private static final double SAVINGS_STOPPED_VM = 1500.0;
    private static final double SAVINGS_DEALLOCATED_VM = 300.0;
    private static final double SAVINGS_ORPHANED_IP = 280.0;
    private static final double SAVINGS_UNATTACHED_NIC = 0.0;
    private static final double SAVINGS_OLD_SNAPSHOT = 200.0;
    private static final double SAVINGS_EMPTY_LOAD_BALANCER = 1800.0;
    private static final double SAVINGS_UNUSED_NSG = 0.0;
    private static final double SAVINGS_EMPTY_AVAILABILITY_SET = 0.0;
    private static final double SAVINGS_ORPHANED_ROUTE_TABLE = 0.0;
    private static final double SAVINGS_IDLE_RUNNING_VM = 2200.0;
    private static final double SAVINGS_IDLE_SQL_DB = 1900.0;
    private static final double SAVINGS_EMPTY_APP_SERVICE_PLAN = 2500.0;
    private static final double SAVINGS_IDLE_STORAGE_ACCOUNT = 150.0;
    private static final double SAVINGS_IDLE_COSMOS_DB = 3500.0;
    private static final double SAVINGS_EMPTY_RESOURCE_GROUP = 0.0;

    // Snapshot age threshold (days) before it's considered waste
    private static final int SNAPSHOT_AGE_DAYS = 90;

    // Idle resource detection thresholds (Azure Monitor — free API)
    private static final double IDLE_VM_CPU_THRESHOLD = 5.0;   // avg CPU % over window
    private static final double IDLE_SQL_CPU_THRESHOLD = 5.0;  // avg DTU/CPU % over window
    private static final String ARM_SCOPE = "https://management.azure.com/.default";
    private static final String ARM_BASE = "https://management.azure.com";

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureWasteService(AzureClientProvider clientProvider,
                             RedisCacheService redisCache,
                             CachedDataRepository cachedDataRepository) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
    }

    /**
     * Returns a list of waste items for the given subscription.
     * Cache strategy: Redis (60-min TTL) → DB snapshot → Azure API.
     * Pass forceRefresh=true to bypass all caches.
     */
    public List<AzureWasteItem> getWasteItems(String subscriptionId, boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + subscriptionId;
        String dbKey = DB_CACHE_PREFIX + subscriptionId;

        if (!forceRefresh) {
            // 1. Try Redis first
            try {
                Optional<Object> cached = redisCache.get(cacheKey, Object.class);
                if (cached.isPresent()) {
                    List<AzureWasteItem> items = objectMapper.convertValue(
                            cached.get(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, AzureWasteItem.class));
                    log.info("Returning Redis-cached waste items for {} ({} items)", subscriptionId, items.size());
                    return items;
                }
            } catch (Exception e) {
                log.warn("Redis deserialization failed for waste items for {}: {}", subscriptionId, e.getMessage());
            }

            // 2. Try DB snapshot
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    List<AzureWasteItem> items = objectMapper.readValue(
                            dbSnapshot.get().getJsonData(),
                            new TypeReference<List<AzureWasteItem>>() {});
                    log.info("Returning DB-cached waste items for {} ({} items) — re-warming Redis", subscriptionId, items.size());
                    redisCache.put(cacheKey, items, CACHE_TTL_MINUTES);
                    return items;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for waste items for {}: {}", subscriptionId, e.getMessage());
            }
        }

        // 3. Fetch from Azure API
        List<AzureWasteItem> items = detectWaste(subscriptionId);
        redisCache.put(cacheKey, items, CACHE_TTL_MINUTES);
        saveToDb(dbKey, items);
        return items;
    }

    private void saveToDb(String dbKey, List<AzureWasteItem> items) {
        try {
            String json = objectMapper.writeValueAsString(items);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
            log.info("✅ DB snapshot saved for Azure waste: {}", dbKey);
        } catch (Exception e) {
            log.warn("Failed to save DB snapshot for Azure waste {}: {}", dbKey, e.getMessage());
        }
    }

    private List<AzureWasteItem> detectWaste(String subscriptionId) {
        List<AzureWasteItem> result = new ArrayList<>();
        try {
            AzureResourceManager azure = clientProvider.getAzureClient(subscriptionId);

            detectUnattachedDisks(azure, result);
            detectVirtualMachines(azure, result);
            detectOrphanedPublicIps(azure, result);
            detectUnattachedNics(azure, result);
            detectOldSnapshots(azure, result);
            detectEmptyLoadBalancers(azure, result);
            detectUnusedNetworkSecurityGroups(azure, result);
            detectEmptyAvailabilitySets(azure, result);
            detectOrphanedRouteTables(azure, result);
            detectIdleRunningVms(azure, subscriptionId, result);
            detectIdleSqlDatabases(azure, subscriptionId, result);

            // REST-based detectors over free ARM APIs (no extra SDK deps)
            try {
                String token = getBearerToken(subscriptionId);
                detectEmptyAppServicePlans(subscriptionId, token, result);
                detectIdleStorageAccounts(subscriptionId, token, result);
                detectIdleCosmosDb(subscriptionId, token, result);
                detectEmptyResourceGroups(subscriptionId, token, result);
            } catch (Exception e) {
                log.warn("Could not run REST-based waste detectors for {}: {}", subscriptionId, e.getMessage());
            }

        } catch (Exception e) {
            log.error("Failed to detect waste for subscription {}: {}", subscriptionId, e.getMessage(), e);
        }

        // Sort by monthly savings descending (highest potential savings first)
        result.sort(Comparator.comparingDouble(AzureWasteItem::getMonthlySavings).reversed());
        log.info("Waste detection complete for {}: {} items found", subscriptionId, result.size());
        return result;
    }

    private void detectUnattachedDisks(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (Disk disk : azure.disks().list()) {
                try {
                    if (!disk.isAttachedToVirtualMachine()) {
                        result.add(new AzureWasteItem(
                                disk.id(),
                                disk.name(),
                                "Unattached Disk",
                                disk.regionName() != null ? disk.regionName() : "",
                                "Managed disk has no VM attachment — incurring storage cost with no workload",
                                SAVINGS_UNATTACHED_DISK
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing disk {}: {}", disk.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list disks for waste detection: {}", e.getMessage());
        }
    }

    private void detectVirtualMachines(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (VirtualMachine vm : azure.virtualMachines().list()) {
                try {
                    PowerState powerState = vm.powerState();
                    if (PowerState.STOPPED.equals(powerState)) {
                        result.add(new AzureWasteItem(
                                vm.id(),
                                vm.name(),
                                "Stopped VM",
                                vm.regionName() != null ? vm.regionName() : "",
                                "VM is stopped but still incurring compute charges — deallocate to stop billing",
                                SAVINGS_STOPPED_VM
                        ));
                    } else if (PowerState.DEALLOCATED.equals(powerState)) {
                        result.add(new AzureWasteItem(
                                vm.id(),
                                vm.name(),
                                "Deallocated VM",
                                vm.regionName() != null ? vm.regionName() : "",
                                "VM is deallocated — compute stopped but storage (OS disk) is still billed",
                                SAVINGS_DEALLOCATED_VM
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing VM {}: {}", vm.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list VMs for waste detection: {}", e.getMessage());
        }
    }

    private void detectOrphanedPublicIps(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (PublicIpAddress pip : azure.publicIpAddresses().list()) {
                try {
                    if (!pip.hasAssignedNetworkInterface() && !pip.hasAssignedLoadBalancer()) {
                        result.add(new AzureWasteItem(
                                pip.id(),
                                pip.name(),
                                "Orphaned Public IP",
                                pip.regionName() != null ? pip.regionName() : "",
                                "Static public IP reserved but not attached to any resource — incurring idle IP cost",
                                SAVINGS_ORPHANED_IP
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing public IP {}: {}", pip.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list public IPs for waste detection: {}", e.getMessage());
        }
    }

    private void detectUnattachedNics(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (NetworkInterface nic : azure.networkInterfaces().list()) {
                try {
                    if (nic.innerModel().virtualMachine() == null) {
                        result.add(new AzureWasteItem(
                                nic.id(),
                                nic.name(),
                                "Unattached NIC",
                                nic.regionName() != null ? nic.regionName() : "",
                                "Network interface not attached to any virtual machine — resource hygiene issue",
                                SAVINGS_UNATTACHED_NIC
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing NIC {}: {}", nic.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list NICs for waste detection: {}", e.getMessage());
        }
    }

    /** Snapshots older than {@link #SNAPSHOT_AGE_DAYS} days — stale backup storage still billed. */
    private void detectOldSnapshots(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            OffsetDateTime cutoff = OffsetDateTime.now().minus(SNAPSHOT_AGE_DAYS, ChronoUnit.DAYS);
            for (Snapshot snapshot : azure.snapshots().list()) {
                try {
                    OffsetDateTime created = snapshot.innerModel() != null ? snapshot.innerModel().timeCreated() : null;
                    if (created != null && created.isBefore(cutoff)) {
                        long ageDays = ChronoUnit.DAYS.between(created, OffsetDateTime.now());
                        result.add(new AzureWasteItem(
                                snapshot.id(),
                                snapshot.name(),
                                "Old Snapshot",
                                snapshot.regionName() != null ? snapshot.regionName() : "",
                                "Snapshot is " + ageDays + " days old — consider deleting stale backups to save storage cost",
                                SAVINGS_OLD_SNAPSHOT
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing snapshot {}: {}", snapshot.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list snapshots for waste detection: {}", e.getMessage());
        }
    }

    /** Load balancers with no backend pool members — paying for an idle LB. */
    private void detectEmptyLoadBalancers(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (LoadBalancer lb : azure.loadBalancers().list()) {
                try {
                    boolean noBackends = lb.backends() == null || lb.backends().isEmpty();
                    boolean noRules = lb.loadBalancingRules() == null || lb.loadBalancingRules().isEmpty();
                    if (noBackends || noRules) {
                        result.add(new AzureWasteItem(
                                lb.id(),
                                lb.name(),
                                "Empty Load Balancer",
                                lb.regionName() != null ? lb.regionName() : "",
                                "Load balancer has no backend pool members or rules — idle resource incurring cost",
                                SAVINGS_EMPTY_LOAD_BALANCER
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing load balancer {}: {}", lb.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list load balancers for waste detection: {}", e.getMessage());
        }
    }

    /** Network security groups not associated with any subnet or NIC — orphaned config. */
    private void detectUnusedNetworkSecurityGroups(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (NetworkSecurityGroup nsg : azure.networkSecurityGroups().list()) {
                try {
                    boolean noSubnets = nsg.listAssociatedSubnets() == null || nsg.listAssociatedSubnets().isEmpty();
                    boolean noNics = nsg.networkInterfaceIds() == null || nsg.networkInterfaceIds().isEmpty();
                    if (noSubnets && noNics) {
                        result.add(new AzureWasteItem(
                                nsg.id(),
                                nsg.name(),
                                "Unused NSG",
                                nsg.regionName() != null ? nsg.regionName() : "",
                                "Network security group not associated with any subnet or NIC — safe to remove",
                                SAVINGS_UNUSED_NSG
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing NSG {}: {}", nsg.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list NSGs for waste detection: {}", e.getMessage());
        }
    }

    /** Availability sets with no VMs — orphaned grouping config. */
    private void detectEmptyAvailabilitySets(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (AvailabilitySet as : azure.availabilitySets().list()) {
                try {
                    if (as.virtualMachineIds() == null || as.virtualMachineIds().isEmpty()) {
                        result.add(new AzureWasteItem(
                                as.id(),
                                as.name(),
                                "Empty Availability Set",
                                as.regionName() != null ? as.regionName() : "",
                                "Availability set contains no virtual machines — orphaned configuration",
                                SAVINGS_EMPTY_AVAILABILITY_SET
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing availability set {}: {}", as.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list availability sets for waste detection: {}", e.getMessage());
        }
    }

    /** Route tables not associated with any subnet — orphaned network config. */
    private void detectOrphanedRouteTables(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (RouteTable rt : azure.routeTables().list()) {
                try {
                    if (rt.listAssociatedSubnets() == null || rt.listAssociatedSubnets().isEmpty()) {
                        result.add(new AzureWasteItem(
                                rt.id(),
                                rt.name(),
                                "Orphaned Route Table",
                                rt.regionName() != null ? rt.regionName() : "",
                                "Route table not associated with any subnet — orphaned network configuration",
                                SAVINGS_ORPHANED_ROUTE_TABLE
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error processing route table {}: {}", rt.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list route tables for waste detection: {}", e.getMessage());
        }
    }

    /**
     * Detects RUNNING VMs that are effectively idle (avg CPU below threshold over 7 days)
     * using the free Azure Monitor metrics API. These are the costliest kind of waste —
     * a VM that's powered on and billing full compute but doing almost nothing.
     */
    private void detectIdleRunningVms(AzureResourceManager azure, String subscriptionId, List<AzureWasteItem> result) {
        String token;
        try {
            token = getBearerToken(subscriptionId);
        } catch (Exception e) {
            log.warn("Could not obtain ARM token for idle VM detection: {}", e.getMessage());
            return;
        }

        try {
            for (VirtualMachine vm : azure.virtualMachines().list()) {
                try {
                    // Only consider running VMs — stopped/deallocated are caught by detectVirtualMachines
                    if (!PowerState.RUNNING.equals(vm.powerState())) continue;

                    Double avgCpu = queryAverageMetric(vm.id(), "Percentage%20CPU", token);
                    if (avgCpu != null && avgCpu < IDLE_VM_CPU_THRESHOLD) {
                        result.add(new AzureWasteItem(
                                vm.id(),
                                vm.name(),
                                "Idle Running VM",
                                vm.regionName() != null ? vm.regionName() : "",
                                String.format("VM is running but averaged only %.1f%% CPU over the last 7 days — consider downsizing or deallocating", avgCpu),
                                SAVINGS_IDLE_RUNNING_VM
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Error checking idle CPU for VM {}: {}", vm.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list VMs for idle detection: {}", e.getMessage());
        }
    }

    /**
     * Queries Azure Monitor for the average value of a metric over the last 7 days.
     * Returns null if no data is available. Uses the free Monitor metrics REST API.
     * @param metricName URL-encoded metric name (e.g. "Percentage%20CPU" for VMs, "cpu_percent" for SQL DBs)
     */
    private Double queryAverageMetric(String resourceId, String metricName, String token) {
        try {
            String url = ARM_BASE + resourceId
                    + "/providers/Microsoft.Insights/metrics"
                    + "?metricnames=" + metricName
                    + "&timespan=P7D"
                    + "&interval=PT1H"
                    + "&aggregation=Average"
                    + "&api-version=2018-01-01";

            String body = armGet(url, token);
            if (body == null) return null;

            JsonNode root = objectMapper.readTree(body);
            JsonNode valueArr = root.path("value");
            if (!valueArr.isArray() || valueArr.isEmpty()) return null;

            JsonNode timeSeries = valueArr.get(0).path("timeseries");
            if (!timeSeries.isArray() || timeSeries.isEmpty()) return null;
            JsonNode dataArr = timeSeries.get(0).path("data");
            if (!dataArr.isArray() || dataArr.isEmpty()) return null;

            double sum = 0.0;
            int count = 0;
            for (JsonNode point : dataArr) {
                JsonNode avg = point.path("average");
                if (!avg.isMissingNode() && !avg.isNull()) {
                    sum += avg.asDouble();
                    count++;
                }
            }
            return count > 0 ? sum / count : null;
        } catch (Exception e) {
            log.debug("Azure Monitor metric query failed for {} ({}): {}", resourceId, metricName, e.getMessage());
            return null;
        }
    }

    /**
     * Detects Azure SQL databases that are effectively idle (avg CPU below threshold over 7 days)
     * using the free Azure Monitor metrics API. Skips system databases.
     */
    private void detectIdleSqlDatabases(AzureResourceManager azure, String subscriptionId, List<AzureWasteItem> result) {
        String token;
        try {
            token = getBearerToken(subscriptionId);
        } catch (Exception e) {
            log.warn("Could not obtain ARM token for idle SQL detection: {}", e.getMessage());
            return;
        }

        try {
            for (SqlServer server : azure.sqlServers().list()) {
                try {
                    for (SqlDatabase db : server.databases().list()) {
                        try {
                            if ("master".equalsIgnoreCase(db.name())) continue;

                            Double avgCpu = queryAverageMetric(db.id(), "cpu_percent", token);
                            if (avgCpu != null && avgCpu < IDLE_SQL_CPU_THRESHOLD) {
                                result.add(new AzureWasteItem(
                                        db.id(),
                                        server.name() + "/" + db.name(),
                                        "Idle SQL Database",
                                        db.regionName() != null ? db.regionName() : "",
                                        String.format("SQL database averaged only %.1f%% CPU over the last 7 days — consider scaling down the service tier", avgCpu),
                                        SAVINGS_IDLE_SQL_DB
                                ));
                            }
                        } catch (Exception e) {
                            log.warn("Error checking idle CPU for SQL DB {}/{}: {}", server.name(), db.name(), e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Error listing databases for SQL server {}: {}", server.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list SQL servers for idle detection: {}", e.getMessage());
        }
    }

    /** Acquires an ARM bearer token for the subscription's service principal. */
    private String getBearerToken(String subscriptionId) {
        TokenCredential credential = clientProvider.getCredential(subscriptionId);
        return credential.getToken(new TokenRequestContext().addScopes(ARM_SCOPE))
                .block()
                .getToken();
    }

    /** Simple authenticated GET against the Azure Resource Manager REST API. */
    private String armGet(String url, String token) {
        try {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            log.debug("ARM GET {} returned HTTP {}", url, response.statusCode());
            return null;
        } catch (Exception e) {
            log.debug("ARM GET error for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** Lists all entries in the ARM "value" array, following nextLink pagination. */
    private List<JsonNode> armList(String url, String token) {
        List<JsonNode> out = new ArrayList<>();
        String next = url;
        int pages = 0;
        while (next != null && !next.isBlank() && pages < 20) {
            String body = armGet(next, token);
            if (body == null) break;
            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode value = root.path("value");
                if (value.isArray()) {
                    for (JsonNode n : value) out.add(n);
                }
                next = root.path("nextLink").asText(null);
            } catch (Exception e) {
                log.debug("armList parse error for {}: {}", url, e.getMessage());
                break;
            }
            pages++;
        }
        return out;
    }

    /**
     * Queries Azure Monitor for the SUM of a metric over the last 7 days (free API).
     * Used for "is this resource being used at all" checks (Transactions, requests, etc.).
     * Returns null if no data is available.
     */
    private Double queryTotalMetric(String resourceId, String metricName, String token) {
        try {
            String url = ARM_BASE + resourceId
                    + "/providers/Microsoft.Insights/metrics"
                    + "?metricnames=" + metricName
                    + "&timespan=P7D"
                    + "&interval=P1D"
                    + "&aggregation=Total"
                    + "&api-version=2018-01-01";

            String body = armGet(url, token);
            if (body == null) return null;

            JsonNode root = objectMapper.readTree(body);
            JsonNode valueArr = root.path("value");
            if (!valueArr.isArray() || valueArr.isEmpty()) return null;

            JsonNode timeSeries = valueArr.get(0).path("timeseries");
            if (!timeSeries.isArray() || timeSeries.isEmpty()) return null;
            JsonNode dataArr = timeSeries.get(0).path("data");
            if (!dataArr.isArray() || dataArr.isEmpty()) return null;

            double sum = 0.0;
            boolean any = false;
            for (JsonNode point : dataArr) {
                JsonNode total = point.path("total");
                if (!total.isMissingNode() && !total.isNull()) {
                    sum += total.asDouble();
                    any = true;
                }
            }
            return any ? sum : null;
        } catch (Exception e) {
            log.debug("Azure Monitor total-metric query failed for {} ({}): {}", resourceId, metricName, e.getMessage());
            return null;
        }
    }

    /** App Service Plans on a paid tier hosting zero apps — billing with no workload. */
    private void detectEmptyAppServicePlans(String subscriptionId, String token, List<AzureWasteItem> result) {
        String url = ARM_BASE + "/subscriptions/" + subscriptionId
                + "/providers/Microsoft.Web/serverfarms?api-version=2023-12-01";
        for (JsonNode plan : armList(url, token)) {
            try {
                int sites = plan.path("properties").path("numberOfSites").asInt(-1);
                String tier = plan.path("sku").path("tier").asText("");
                boolean paidTier = !tier.isBlank()
                        && !"Free".equalsIgnoreCase(tier) && !"Shared".equalsIgnoreCase(tier)
                        && !"Dynamic".equalsIgnoreCase(tier);
                if (sites == 0 && paidTier) {
                    result.add(new AzureWasteItem(
                            plan.path("id").asText(""),
                            plan.path("name").asText(""),
                            "Empty App Service Plan",
                            plan.path("location").asText(""),
                            "App Service Plan (" + tier + " tier) is hosting no apps — billing for unused compute",
                            SAVINGS_EMPTY_APP_SERVICE_PLAN
                    ));
                }
            } catch (Exception e) {
                log.warn("Error processing App Service Plan: {}", e.getMessage());
            }
        }
    }

    /** Storage accounts with effectively zero transactions over 7 days — likely abandoned. */
    private void detectIdleStorageAccounts(String subscriptionId, String token, List<AzureWasteItem> result) {
        String url = ARM_BASE + "/subscriptions/" + subscriptionId
                + "/providers/Microsoft.Storage/storageAccounts?api-version=2023-01-01";
        for (JsonNode sa : armList(url, token)) {
            try {
                String id = sa.path("id").asText("");
                if (id.isEmpty()) continue;
                Double txns = queryTotalMetric(id, "Transactions", token);
                if (txns != null && txns < 1.0) {
                    result.add(new AzureWasteItem(
                            id,
                            sa.path("name").asText(""),
                            "Idle Storage Account",
                            sa.path("location").asText(""),
                            "Storage account had no transactions over the last 7 days — review for deletion or archival",
                            SAVINGS_IDLE_STORAGE_ACCOUNT
                    ));
                }
            } catch (Exception e) {
                log.warn("Error processing storage account: {}", e.getMessage());
            }
        }
    }

    /** Cosmos DB accounts with zero requests over 7 days — provisioned throughput billed with no usage. */
    private void detectIdleCosmosDb(String subscriptionId, String token, List<AzureWasteItem> result) {
        String url = ARM_BASE + "/subscriptions/" + subscriptionId
                + "/providers/Microsoft.DocumentDB/databaseAccounts?api-version=2023-11-15";
        for (JsonNode acc : armList(url, token)) {
            try {
                String id = acc.path("id").asText("");
                if (id.isEmpty()) continue;
                Double reqs = queryTotalMetric(id, "TotalRequests", token);
                if (reqs != null && reqs < 1.0) {
                    result.add(new AzureWasteItem(
                            id,
                            acc.path("name").asText(""),
                            "Idle Cosmos DB",
                            acc.path("location").asText(""),
                            "Cosmos DB account served no requests over the last 7 days — consider scaling down RU/s or deleting",
                            SAVINGS_IDLE_COSMOS_DB
                    ));
                }
            } catch (Exception e) {
                log.warn("Error processing Cosmos DB account: {}", e.getMessage());
            }
        }
    }

    /** Resource groups containing no resources — clutter / orphaned config. */
    private void detectEmptyResourceGroups(String subscriptionId, String token, List<AzureWasteItem> result) {
        String url = ARM_BASE + "/subscriptions/" + subscriptionId
                + "/resourcegroups?api-version=2021-04-01";
        for (JsonNode rg : armList(url, token)) {
            try {
                String rgName = rg.path("name").asText("");
                if (rgName.isEmpty()) continue;
                String resUrl = ARM_BASE + "/subscriptions/" + subscriptionId
                        + "/resourceGroups/" + rgName + "/resources?api-version=2021-04-01";
                if (armList(resUrl, token).isEmpty()) {
                    result.add(new AzureWasteItem(
                            rg.path("id").asText(""),
                            rgName,
                            "Empty Resource Group",
                            rg.path("location").asText(""),
                            "Resource group contains no resources — safe to remove for hygiene",
                            SAVINGS_EMPTY_RESOURCE_GROUP
                    ));
                }
            } catch (Exception e) {
                log.warn("Error processing resource group: {}", e.getMessage());
            }
        }
    }
}
