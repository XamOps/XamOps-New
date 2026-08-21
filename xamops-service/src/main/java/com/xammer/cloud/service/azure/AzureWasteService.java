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
import com.xammer.cloud.service.SnapshotFreshnessService;
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
import java.util.Map;
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
    /** Redis key prefix for a subscription's waste scan. Shared so readers cannot drift. */
    public static final String CACHE_PREFIX = "azure:waste:";
    private static final String DB_CACHE_PREFIX = "AZURE_WASTE::";
    private static final int CACHE_TTL_MINUTES = 60;

    // Monthly savings are USD (AzureWasteItem.BASE_CURRENCY) and resolved from the Azure
    // Retail Prices API where a SKU is known. The constants below are only used when a
    // live lookup fails, and each is annotated with what it represents so a wrong figure
    // is traceable. Resources whose cost genuinely cannot be derived are emitted via
    // AzureWasteItem.withUnknownSavings so they are never counted as free.

    /** Standard HDD 128 GB (S10) list price, the smallest common unattached-disk case. */
    private static final double FALLBACK_UNATTACHED_DISK_USD = 5.89;
    /** Basic Standard_B1s compute rate, a deliberately conservative floor. */
    private static final double FALLBACK_VM_COMPUTE_USD = 7.59;
    /** Standard static public IPv4, billed whether associated or not since Feb 2024. */
    private static final double FALLBACK_PUBLIC_IP_USD = 3.65;
    /** Standard Load Balancer base rule charge. */
    private static final double FALLBACK_LOAD_BALANCER_USD = 18.25;
    /** Basic-tier single database (S0-equivalent) list price. */
    private static final double FALLBACK_SQL_DB_USD = 15.00;
    /** Basic B1 App Service plan list price. */
    private static final double FALLBACK_APP_SERVICE_PLAN_USD = 13.14;
    /** Cosmos DB minimum provisioned throughput, 400 RU/s. */
    private static final double FALLBACK_COSMOS_DB_USD = 23.36;

    /**
     * Grace period before an unattached disk is reported as waste. A disk detached during
     * maintenance or a migration should not be recommended for deletion immediately.
     */
    private static final int UNATTACHED_DISK_MIN_AGE_DAYS = 7;

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

    private final SnapshotFreshnessService snapshotFreshnessService;
    private final AzureRetailPricingService retailPricingService;
    private final AzureArmClient armClient;

    public AzureWasteService(AzureClientProvider clientProvider,
                             RedisCacheService redisCache,
                             CachedDataRepository cachedDataRepository,
                             SnapshotFreshnessService snapshotFreshnessService,
                             AzureRetailPricingService retailPricingService,
                             AzureArmClient armClient) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.snapshotFreshnessService = snapshotFreshnessService;
        this.retailPricingService = retailPricingService;
        this.armClient = armClient;
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
                Optional<CachedData> dbSnapshot = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(dbKey), dbKey);
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
                    if (disk.isAttachedToVirtualMachine()) {
                        continue;
                    }
                    // A disk detached seconds ago during maintenance is not waste. Require it
                    // to have been idle for a minimum period before recommending deletion.
                    if (isRecentlyDetached(disk)) {
                        log.debug("Skipping recently detached disk {} (within {} day grace period)",
                                disk.name(), UNATTACHED_DISK_MIN_AGE_DAYS);
                        continue;
                    }
                    // Disks retained by Azure Backup or Site Recovery are managed elsewhere.
                    if (hasProtectedTag(disk.tags())) {
                        log.debug("Skipping disk {} — carries a retention/protection tag", disk.name());
                        continue;
                    }
                    // A pvc- disk backs a Kubernetes PersistentVolume. It can read as unattached
                    // while its pod is rescheduled, and deleting it destroys the volume's data.
                    if (isKubernetesManaged(disk.id(), disk.name(), disk.resourceGroupName(), disk.tags())) {
                        log.debug("Skipping AKS-managed disk {} — backs a PersistentVolume", disk.name());
                        continue;
                    }

                    String region = disk.regionName() != null ? disk.regionName() : "";
                    String sku = disk.sku() != null && disk.sku().accountType() != null
                            ? disk.sku().accountType().toString() : null;
                    Optional<Double> price = sku == null ? Optional.empty()
                            : retailPricingService.getManagedDiskMonthlyPriceUsd(sku, disk.sizeInGB(), region);

                    String reason = String.format(
                            "Managed disk (%s, %d GB) has no VM attachment — incurring storage cost with no workload",
                            sku != null ? sku : "unknown SKU", disk.sizeInGB());

                    result.add(price
                            .map(p -> new AzureWasteItem(disk.id(), disk.name(), "Unattached Disk", region, reason, p))
                            .orElseGet(() -> new AzureWasteItem(disk.id(), disk.name(), "Unattached Disk",
                                    region, reason, FALLBACK_UNATTACHED_DISK_USD)));
                } catch (Exception e) {
                    log.warn("Error processing disk {}: {}", disk.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list disks for waste detection: {}", e.getMessage());
        }
    }

    /**
     * True when the disk was detached within the grace period, or when the detach time is
     * unknown and the disk was created recently.
     */
    private boolean isRecentlyDetached(Disk disk) {
        try {
            OffsetDateTime cutoff = OffsetDateTime.now().minusDays(UNATTACHED_DISK_MIN_AGE_DAYS);
            OffsetDateTime lastOwnership = disk.innerModel().lastOwnershipUpdateTime();
            if (lastOwnership != null) {
                return lastOwnership.isAfter(cutoff);
            }
            OffsetDateTime created = disk.innerModel().timeCreated();
            return created != null && created.isAfter(cutoff);
        } catch (Exception e) {
            log.debug("Could not determine detach age for disk {}: {}", disk.name(), e.getMessage());
            return false;
        }
    }

    /**
     * Identifies what a public IP is attached to, beyond the NIC and load-balancer cases
     * the SDK exposes directly.
     *
     * <p>Inspects {@code ipConfiguration.id}, which for a NAT Gateway, Application Gateway,
     * Azure Firewall, Bastion or VPN Gateway points at that resource.
     *
     * @return a human-readable resource type, or null when the IP is genuinely unattached
     */
    private String attachedResourceType(PublicIpAddress pip) {
        try {
            var inner = pip.innerModel();
            String configId = inner.ipConfiguration() != null ? inner.ipConfiguration().id() : null;
            if (configId == null || configId.isBlank()) {
                // Some resource types reference the IP without populating ipConfiguration.
                if (inner.natGateway() != null) {
                    return "NAT Gateway";
                }
                return null;
            }
            String lower = configId.toLowerCase();
            if (lower.contains("/natgateways/")) return "NAT Gateway";
            if (lower.contains("/applicationgateways/")) return "Application Gateway";
            if (lower.contains("/azurefirewalls/")) return "Azure Firewall";
            if (lower.contains("/bastionhosts/")) return "Bastion Host";
            if (lower.contains("/virtualnetworkgateways/")) return "VPN/ExpressRoute Gateway";
            if (lower.contains("/loadbalancers/")) return "Load Balancer";
            if (lower.contains("/networkinterfaces/")) return "Network Interface";
            // An ipConfiguration exists but is an unrecognised type — assume it is in use
            // rather than recommending deletion of something we do not understand.
            return "attached resource";
        } catch (Exception e) {
            log.debug("Could not determine attachment for public IP {}: {}", pip.name(), e.getMessage());
            // Fail safe: do not recommend deleting an IP we could not evaluate.
            return "unknown";
        }
    }

    /** Returns the VM size, or "unknown" when unavailable. */
    private String vmSize(VirtualMachine vm) {
        try {
            return vm.size() != null ? vm.size().toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Resolves a VM's monthly compute list price, falling back to a conservative floor when
     * the Retail Prices lookup fails.
     */
    private double resolveVmMonthlyPrice(String size, String region) {
        if (size == null || "unknown".equals(size)) {
            return FALLBACK_VM_COMPUTE_USD;
        }
        return retailPricingService.getVmMonthlyPriceUsd(size, region)
                .orElseGet(() -> {
                    log.debug("No retail price for VM size {} in {}; using conservative fallback", size, region);
                    return FALLBACK_VM_COMPUTE_USD;
                });
    }

    /** True when a SKU name denotes the free Basic tier. */
    private boolean isBasicSku(String skuName) {
        return skuName != null && skuName.toLowerCase().contains("basic");
    }

    /**
     * True when a SQL database tier is billed under the DTU model rather than vCore.
     *
     * <p>DTU databases publish {@code dtu_consumption_percent}; vCore databases publish
     * {@code cpu_percent}. Querying the wrong one returns no data.
     */
    private boolean isDtuModel(String edition) {
        if (edition == null || edition.isBlank()) {
            return false;
        }
        String lower = edition.toLowerCase();
        return lower.equals("basic") || lower.equals("standard") || lower.equals("premium");
    }

    /**
     * True when a SQL tier scales its own compute, making a low average expected rather
     * than a sign of over-provisioning.
     */
    private boolean isAutoScalingSqlTier(String edition, SqlDatabase db) {
        String lower = edition == null ? "" : edition.toLowerCase();
        if (lower.contains("hyperscale")) {
            return true;
        }
        try {
            // Serverless is expressed through the service objective name on the vCore
            // model, e.g. "GP_S_Gen5_2" (General Purpose, Serverless, Gen5, 2 vCores).
            String objective = db.currentServiceObjectiveName() == null
                    ? "" : db.currentServiceObjectiveName().toLowerCase();
            return objective.contains("serverless")
                    || objective.startsWith("gp_s_")
                    || objective.startsWith("hs_")
                    || objective.contains("_s_gen");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when a resource carries a tag indicating it is intentionally retained, e.g. by
     * Azure Backup, Site Recovery, or an explicit operator hold.
     */
    private boolean hasProtectedTag(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        return tags.keySet().stream()
                .map(String::toLowerCase)
                .anyMatch(k -> k.contains("asr") || k.contains("recovery") || k.contains("backup")
                        || k.contains("donotdelete") || k.contains("do-not-delete") || k.contains("retain"));
    }

    private void detectVirtualMachines(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (VirtualMachine vm : azure.virtualMachines().list()) {
                try {
                    PowerState powerState = vm.powerState();
                    String region = vm.regionName() != null ? vm.regionName() : "";

                    if (PowerState.STOPPED.equals(powerState)) {
                        // "Stopped" (not deallocated) still bills full compute. Price the actual
                        // SKU: a flat constant understated a Standard_E16s_v5 by roughly 50x.
                        String size = vmSize(vm);
                        double savings = resolveVmMonthlyPrice(size, region);
                        result.add(new AzureWasteItem(
                                vm.id(),
                                vm.name(),
                                "Stopped VM",
                                region,
                                String.format("VM (%s) is stopped but not deallocated, so it still incurs full "
                                        + "compute charges — deallocate to stop billing", size),
                                savings
                        ));
                    } else if (PowerState.DEALLOCATED.equals(powerState)) {
                        // Compute has already stopped. The only remaining charge is the attached
                        // storage, and realizing it requires deleting the VM and its disks — so
                        // this is reported as a finding without a compute-sized savings claim.
                        result.add(AzureWasteItem.withUnknownSavings(
                                vm.id(),
                                vm.name(),
                                "Deallocated VM",
                                region,
                                "VM is deallocated — compute is not billed, but attached disks still are. "
                                        + "Delete the VM and its disks to eliminate the remaining cost."
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
                    if (pip.hasAssignedNetworkInterface() || pip.hasAssignedLoadBalancer()) {
                        continue;
                    }
                    // A public IP can also be consumed by a NAT Gateway, Application Gateway,
                    // Azure Firewall, Bastion or VPN Gateway. None of those set
                    // hasAssignedNetworkInterface or hasAssignedLoadBalancer, so checking only
                    // those two flags labelled live infrastructure IPs as orphaned.
                    String attachedTo = attachedResourceType(pip);
                    if (attachedTo != null) {
                        log.debug("Skipping public IP {} — in use by {}", pip.name(), attachedTo);
                        continue;
                    }

                    String region = pip.regionName() != null ? pip.regionName() : "";
                    double savings = retailPricingService.getPublicIpMonthlyPriceUsd(region)
                            .orElse(FALLBACK_PUBLIC_IP_USD);
                    result.add(new AzureWasteItem(
                            pip.id(),
                            pip.name(),
                            "Orphaned Public IP",
                            region,
                            "Public IP is not attached to any resource — incurring idle IP cost",
                            savings
                    ));
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
                    // NICs carry no charge in Azure.
                    if (nic.innerModel().virtualMachine() == null) {
                        result.add(AzureWasteItem.withNoCostImpact(
                                nic.id(),
                                nic.name(),
                                "Unattached NIC",
                                nic.regionName() != null ? nic.regionName() : "",
                                "Network interface not attached to any virtual machine — resource hygiene issue"
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
                                FALLBACK_UNATTACHED_DISK_USD
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
                    // Both conditions must hold. With OR, a load balancer that had a populated
                    // backend pool but used only inbound-NAT rules (no load-balancing rules) was
                    // reported as empty.
                    boolean noBackends = lb.backends() == null || lb.backends().isEmpty();
                    boolean noRules = lb.loadBalancingRules() == null || lb.loadBalancingRules().isEmpty();
                    if (!noBackends || !noRules) {
                        continue;
                    }
                    // Basic-SKU load balancers are free, so flagging them invented cost.
                    if (isBasicSku(lb.sku() == null ? null : lb.sku().toString())) {
                        log.debug("Skipping Basic-SKU load balancer {} — no standing charge", lb.name());
                        continue;
                    }
                    if (isKubernetesManaged(lb.id(), lb.name(), lb.resourceGroupName(), lb.tags())) {
                        log.debug("Skipping AKS-managed load balancer {} — recreated by the cluster",
                                lb.name());
                        continue;
                    }
                    result.add(new AzureWasteItem(
                            lb.id(),
                            lb.name(),
                            "Empty Load Balancer",
                            lb.regionName() != null ? lb.regionName() : "",
                            "Load balancer has no backend pool members and no load-balancing rules "
                                    + "— idle resource incurring cost",
                            FALLBACK_LOAD_BALANCER_USD
                    ));
                } catch (Exception e) {
                    log.warn("Error processing load balancer {}: {}", lb.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list load balancers for waste detection: {}", e.getMessage());
        }
    }

    /**
     * Whether a resource is managed by AKS, and therefore must not be recommended for deletion.
     *
     * <p>AKS provisions load balancers, public IPs and disks into a node resource group and its
     * cloud-controller reconciles them continuously. Deleting one is at best futile — the
     * controller recreates it within minutes — and at worst breaks the cluster: a
     * {@code pvc-} disk backs a live PersistentVolume, and removing the cluster's load
     * balancer drops all ingress.
     *
     * <p>UAT showed three "Empty Load Balancer" findings all named {@code kubernetes} worth
     * ₹1,800/month each, and an "Unattached Disk" named {@code pvc-51120e77-…}. All four were
     * AKS-managed. This mirrors the Auto Scaling Group guard on the AWS side.
     *
     * @param resourceId    full ARM resource id, used to spot the {@code MC_} node resource group
     * @param name          resource name
     * @param resourceGroup owning resource group, may be null
     * @param tags          resource tags, may be null
     */
    private boolean isKubernetesManaged(String resourceId, String name, String resourceGroup,
                                        Map<String, String> tags) {
        // AKS names its cluster load balancers exactly "kubernetes" / "kubernetes-internal",
        // and dynamically provisioned disks "pvc-<uuid>".
        if (name != null) {
            String n = name.toLowerCase();
            if (n.equals("kubernetes") || n.equals("kubernetes-internal") || n.startsWith("pvc-")) {
                return true;
            }
        }
        // AKS places managed infrastructure in a node resource group named MC_<rg>_<cluster>_<region>.
        if (resourceGroup != null && resourceGroup.toUpperCase().startsWith("MC_")) {
            return true;
        }
        if (resourceId != null && resourceId.toUpperCase().contains("/RESOURCEGROUPS/MC_")) {
            return true;
        }
        // Kubernetes writes ownership tags on resources it provisions.
        if (tags != null) {
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                String key = tag.getKey() == null ? "" : tag.getKey().toLowerCase();
                if (key.startsWith("kubernetes.io/")
                        || key.startsWith("k8s.io/")
                        || key.equals("aks-managed-cluster-name")
                        || key.startsWith("aks-managed")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Network security groups not associated with any subnet or NIC — orphaned config. */
    private void detectUnusedNetworkSecurityGroups(AzureResourceManager azure, List<AzureWasteItem> result) {
        try {
            for (NetworkSecurityGroup nsg : azure.networkSecurityGroups().list()) {
                try {
                    boolean noSubnets = nsg.listAssociatedSubnets() == null || nsg.listAssociatedSubnets().isEmpty();
                    boolean noNics = nsg.networkInterfaceIds() == null || nsg.networkInterfaceIds().isEmpty();
                    if (noSubnets && noNics) {
                        // NSGs carry no charge; this is a blast-radius finding.
                        result.add(AzureWasteItem.withNoCostImpact(
                                nsg.id(),
                                nsg.name(),
                                "Unused NSG",
                                nsg.regionName() != null ? nsg.regionName() : "",
                                "Network security group not associated with any subnet or NIC — safe to remove"
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
                        // Availability sets are free.
                        result.add(AzureWasteItem.withNoCostImpact(
                                as.id(),
                                as.name(),
                                "Empty Availability Set",
                                as.regionName() != null ? as.regionName() : "",
                                "Availability set contains no virtual machines — orphaned configuration"
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
                        // Route tables are free.
                        result.add(AzureWasteItem.withNoCostImpact(
                                rt.id(),
                                rt.name(),
                                "Orphaned Route Table",
                                rt.regionName() != null ? rt.regionName() : "",
                                "Route table not associated with any subnet — orphaned network configuration"
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
                        String region = vm.regionName() != null ? vm.regionName() : "";
                        String size = vmSize(vm);
                        result.add(new AzureWasteItem(
                                vm.id(),
                                vm.name(),
                                "Idle Running VM",
                                region,
                                String.format("VM (%s) is running but averaged only %.1f%% CPU over the last 7 days "
                                        + "— consider downsizing or deallocating", size, avgCpu),
                                resolveVmMonthlyPrice(size, region)
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

                            // Elastic-pool members do not have their own compute allocation —
                            // the pool is the billing unit. Recommending a per-database tier
                            // change is meaningless and the "savings" would be fictional.
                            if (db.elasticPoolName() != null && !db.elasticPoolName().isBlank()) {
                                log.debug("Skipping SQL DB {}/{}: member of elastic pool {}",
                                        server.name(), db.name(), db.elasticPoolName());
                                continue;
                            }

                            String edition = db.edition() == null ? "" : db.edition().toString();

                            // Serverless and Hyperscale already autoscale compute and bill on
                            // actual usage, so low average CPU is the expected steady state
                            // rather than over-provisioning.
                            if (isAutoScalingSqlTier(edition, db)) {
                                log.debug("Skipping SQL DB {}/{}: tier {} scales compute automatically",
                                        server.name(), db.name(), edition);
                                continue;
                            }

                            // vCore-model databases report cpu_percent; DTU-model databases
                            // report dtu_consumption_percent. Querying only cpu_percent left
                            // every DTU database (Basic/Standard/Premium) permanently invisible.
                            String metricName = isDtuModel(edition) ? "dtu_consumption_percent" : "cpu_percent";
                            Double avgUtilisation = queryAverageMetric(db.id(), metricName, token);

                            // Fall back to the other metric if the first returns nothing, since
                            // edition strings do not map perfectly onto the two billing models.
                            if (avgUtilisation == null) {
                                String fallbackMetric = "cpu_percent".equals(metricName)
                                        ? "dtu_consumption_percent" : "cpu_percent";
                                avgUtilisation = queryAverageMetric(db.id(), fallbackMetric, token);
                                if (avgUtilisation != null) {
                                    metricName = fallbackMetric;
                                }
                            }

                            if (avgUtilisation != null && avgUtilisation < IDLE_SQL_CPU_THRESHOLD) {
                                result.add(new AzureWasteItem(
                                        db.id(),
                                        server.name() + "/" + db.name(),
                                        "Idle SQL Database",
                                        db.regionName() != null ? db.regionName() : "",
                                        String.format("SQL database (%s) averaged only %.1f%% %s over the last "
                                                        + "7 days — consider scaling down the service tier",
                                                edition.isBlank() ? "unknown tier" : edition,
                                                avgUtilisation, metricName),
                                        FALLBACK_SQL_DB_USD
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
    /**
     * Bearer token for ARM, cached by {@link AzureArmClient}.
     * Previously re-fetched three or more times per scan.
     */
    private String getBearerToken(String subscriptionId) {
        return armClient.getToken(subscriptionId);
    }

    /**
     * Authenticated ARM GET. Returns null when there is no data.
     *
     * <p>Delegates to {@link AzureArmClient}, which reuses one {@link java.net.http.HttpClient}
     * with timeouts and logs authorization and throttling failures at warn rather than
     * collapsing them to a silent null at debug.
     */
    private String armGet(String url, String token) {
        return armClient.getQuietly(url, token).orElse(null);
    }

    /** Lists every ARM {@code value} entry, following {@code nextLink} to the end. */
    private List<JsonNode> armList(String url, String token) {
        return armClient.list(url, token);
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
                            FALLBACK_APP_SERVICE_PLAN_USD
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
                            FALLBACK_UNATTACHED_DISK_USD
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
                            FALLBACK_COSMOS_DB_USD
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
                // An empty resource group is a free container.
                if (armList(resUrl, token).isEmpty()) {
                    result.add(AzureWasteItem.withNoCostImpact(
                            rg.path("id").asText(""),
                            rgName,
                            "Empty Resource Group",
                            rg.path("location").asText(""),
                            "Resource group contains no resources — safe to remove for hygiene"
                    ));
                }
            } catch (Exception e) {
                log.warn("Error processing resource group: {}", e.getMessage());
            }
        }
    }
}
