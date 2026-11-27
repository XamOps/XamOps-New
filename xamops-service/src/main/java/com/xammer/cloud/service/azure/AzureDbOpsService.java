package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.sql.models.RestorePointType;
import com.azure.resourcemanager.sql.models.ServerNetworkAccessFlag;
import com.azure.resourcemanager.sql.models.SqlDatabase;
import com.azure.resourcemanager.sql.models.SqlServer;
import com.azure.resourcemanager.sql.models.TransparentDataEncryptionState;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentMetricsHistory;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.dto.dbops.*;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Azure DbOps Service — Azure SQL, Managed Instance, PostgreSQL Flexible, MySQL Flexible
 *
 * Azure equivalent of GcpDbOpsService.
 * Uses Azure SDK for SQL DB/MI and Azure Management REST API for PostgreSQL/MySQL Flexible Servers.
 * Uses Azure Monitor REST API for metrics.
 *
 * Caching strategy (3-tier):
 *   Redis (primary) → CachedData DB (fallback) → Live Azure API call
 */
@Service
@Slf4j
public class AzureDbOpsService {

    // ── Cache TTLs (minutes) ─────────────────────────────────────────────────
    private static final int INVENTORY_TTL = 24 * 60;
    private static final int METRICS_TTL   = 12 * 60;
    private static final int COSTS_TTL     =  6 * 60;
    private static final int BACKUPS_TTL   = 24 * 60;
    /** A scan where some sources failed is kept only briefly, and never in the DB fallback. */
    private static final int PARTIAL_TTL   = 15;

    // ── Detection thresholds ─────────────────────────────────────────────────
    private static final double IDLE_CPU_THRESHOLD        = 5.0;
    private static final double IDLE_CONN_THRESHOLD       = 1.0;
    private static final double RIGHTSIZING_CPU_THRESHOLD = 10.0;
    private static final int    BACKUP_WASTE_AGE_DAYS     = 30;
    private static final double BACKUP_COST_PER_GB        = 0.115; // Azure backup storage $/GB/month

    // ── Azure SQL SKU cost map (approximate $/month) ──────────────────────────
    private static final Map<String, Double> SKU_COST_MAP = Map.ofEntries(
            Map.entry("GP_Gen5_2",   93.0),
            Map.entry("GP_Gen5_4",  186.0),
            Map.entry("GP_Gen5_8",  372.0),
            Map.entry("GP_Gen5_16", 744.0),
            Map.entry("BC_Gen5_2",  311.0),
            Map.entry("BC_Gen5_4",  622.0),
            Map.entry("BC_Gen5_8", 1244.0),
            Map.entry("HS_Gen5_2",  155.0),
            Map.entry("HS_Gen5_4",  310.0)
    );

    // ── Downsize recommendation map ───────────────────────────────────────────
    private static final Map<String, String> DOWNSIZE_MAP = Map.ofEntries(
            Map.entry("GP_Gen5_4",  "GP_Gen5_2"),
            Map.entry("GP_Gen5_8",  "GP_Gen5_4"),
            Map.entry("GP_Gen5_16", "GP_Gen5_8"),
            Map.entry("BC_Gen5_4",  "BC_Gen5_2"),
            Map.entry("BC_Gen5_8",  "BC_Gen5_4"),
            Map.entry("HS_Gen5_4",  "HS_Gen5_2")
    );

    private static final String ARM_SCOPE = "https://management.azure.com/.default";
    private static final String ARM_BASE  = "https://management.azure.com";

    private final AzureClientProvider azureClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final DbAgentConfigRepository dbAgentConfigRepository;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository;
    private final AzureArmClient armClient;
    private final DbOpsFeatureFlags dbOpsFeatureFlags;

    /** PERF-1: dedicated bounded pool for DbOps cloud I/O (off {@code ForkJoinPool.commonPool()}). */
    private final Executor dbOpsTaskExecutor;

    /** PERF-2: per-cache-key in-flight scan registry; concurrent cold-cache callers share one scan. */
    private final ConcurrentHashMap<String, CompletableFuture<?>> inFlightScans = new ConcurrentHashMap<>();

    /** Optional, so tests that build the service without billing data still work (estimates only). */
    @Autowired(required = false)
    private AzureBillingDataIngestionService billingIngestionService;

    @Autowired(required = false)
    private AzureBillingCurrencyService billingCurrencyService;

    public AzureDbOpsService(
            AzureClientProvider azureClientProvider,
            CloudAccountRepository cloudAccountRepository,
            RedisCacheService redisCache,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            DbAgentConfigRepository dbAgentConfigRepository,
            DbAgentDataCacheRepository dbAgentDataCacheRepository,
            DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository,
            AzureArmClient armClient,
            DbOpsFeatureFlags dbOpsFeatureFlags,
            @Qualifier("dbOpsTaskExecutor") Executor dbOpsTaskExecutor) {
        this.azureClientProvider = azureClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.dbAgentConfigRepository = dbAgentConfigRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.dbAgentMetricsHistoryRepository = dbAgentMetricsHistoryRepository;
        this.armClient = armClient;
        this.dbOpsFeatureFlags = dbOpsFeatureFlags;
        this.dbOpsTaskExecutor = dbOpsTaskExecutor;
    }

    // ── PERF-1/PERF-2 helpers (mirror DbOpsCostService) ───────────────────────

    /** PERF-1: run a DbOps I/O task on the dedicated bounded pool, not the common ForkJoinPool. */
    private <T> CompletableFuture<T> dbOpsAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, dbOpsTaskExecutor);
    }

    /**
     * PERF-2: per-key single-flight. Concurrent cold-cache callers for the same key share one
     * in-flight scan; the slot clears when the result settles (success or failure). Cache-HIT fast
     * paths never enter here.
     */
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> singleFlight(String cacheKey, Supplier<CompletableFuture<T>> loader) {
        CompletableFuture<T> created = new CompletableFuture<>();
        CompletableFuture<?> existing = inFlightScans.putIfAbsent(cacheKey, created);
        if (existing != null) {
            return (CompletableFuture<T>) existing;
        }
        created.whenComplete((r, e) -> inFlightScans.remove(cacheKey, created));
        try {
            loader.get().whenComplete((r, e) -> {
                if (e != null) created.completeExceptionally(e);
                else created.complete(r);
            });
        } catch (RuntimeException ex) {
            created.completeExceptionally(ex);
        }
        return created;
    }

    // =========================================================================
    // PUBLIC API — INVENTORY
    // =========================================================================

    /**
     * Fetch all Azure DB instances (SQL Database, SQL Managed Instance,
     * PostgreSQL Flexible, MySQL Flexible) for the given subscription.
     * Cached 24h.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbInstanceDto>> getDbInventory(String subscriptionId, boolean forceRefresh) {
        String cacheKey = "dbops:azure:inventory:" + subscriptionId;

        if (!forceRefresh) {
            Optional<List<DbInstanceDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                log.info("[AzureDbOps] Redis hit — inventory for subscription {}", subscriptionId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<List<DbInstanceDto>> db = getFromDb(cacheKey, new TypeReference<>() {}, INVENTORY_TTL);
            if (db.isPresent()) {
                log.info("[AzureDbOps] DB cache hit — inventory for subscription {}", subscriptionId);
                redisCache.put(cacheKey, db.get(), INVENTORY_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        // PERF-2: concurrent cold-cache callers share one inventory scan (the sources fan out 6x).
        return singleFlight(cacheKey, () -> dbOpsAsync(() -> {
            // COR-13 failure-vs-empty contract (mirrors AWS DbOpsCostService#getDbInventory):
            // a scan that could not run at all must error so the caller does not cache a
            // misleading "no databases", while a scan that ran and found nothing is cached.
            List<DbInstanceDto> instances = new ArrayList<>();

            final AzureResourceManager azure;
            final String token;
            try {
                azure = azureClientProvider.getAzureClient(subscriptionId);
                token = getBearerToken(subscriptionId);
            } catch (Exception e) {
                // Credential/client setup failed — nothing about this subscription loaded.
                log.error("[AzureDbOps] Inventory setup failed for subscription {}: {}", subscriptionId, e.getMessage());
                throw new IllegalStateException(
                        "Azure DB inventory setup failed for subscription " + subscriptionId, e);
            }

            int sourceSuccesses = 0;
            int sourceFailures = 0;
            // 1. Azure SQL Databases (via SDK)
            if (collectSqlDatabases(azure, token, instances)) sourceSuccesses++; else sourceFailures++;
            // 2. Azure SQL Managed Instances (via REST API)
            if (collectSqlManagedInstances(subscriptionId, token, instances)) sourceSuccesses++; else sourceFailures++;
            // 3. PostgreSQL Flexible Servers (via REST API)
            if (collectPostgresFlexibleServers(subscriptionId, token, instances)) sourceSuccesses++; else sourceFailures++;
            // 4. MySQL Flexible Servers (via REST API)
            if (collectMysqlFlexibleServers(subscriptionId, token, instances)) sourceSuccesses++; else sourceFailures++;

            if (sourceSuccesses == 0) {
                // Every source errored — treat as a failed scan, not an empty one; do NOT cache.
                log.error("[AzureDbOps] Inventory scan failed for subscription {} — every source errored; not caching", subscriptionId);
                throw new IllegalStateException(
                        "Azure DB inventory scan failed for subscription " + subscriptionId + " — every source errored");
            }

            if (sourceFailures > 0) {
                // Some sources errored (a throttle, a missing permission). Caching this for a day hid
                // those databases for a day; keep it briefly so the next load retries.
                log.warn("[AzureDbOps] Inventory for subscription {} is partial ({} of 4 sources failed); caching for {} min only",
                        subscriptionId, sourceFailures, PARTIAL_TTL);
                redisCache.put(cacheKey, instances, PARTIAL_TTL);
            } else {
                redisCache.put(cacheKey, instances, INVENTORY_TTL);
                saveToDb(cacheKey, instances);
            }
            log.info("[AzureDbOps] Inventory fetched: {} instances for subscription {}", instances.size(), subscriptionId);
            return instances;
        }));
    }

    // =========================================================================
    // PUBLIC API — METRICS (Azure Monitor REST API)
    // =========================================================================

    /**
     * Fetch 7-day average Azure Monitor metrics for all DB instances.
     * Cached 12h.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<Map<String, DbMetricsDto>> getDbMetrics(String subscriptionId, boolean forceRefresh) {
        String cacheKey = "dbops:azure:metrics:" + subscriptionId;

        if (!forceRefresh) {
            Optional<Map<String, DbMetricsDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                log.info("[AzureDbOps] Redis hit — metrics for subscription {}", subscriptionId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<Map<String, DbMetricsDto>> db = getFromDb(cacheKey, new TypeReference<>() {}, METRICS_TTL);
            if (db.isPresent()) {
                log.info("[AzureDbOps] DB cache hit — metrics for subscription {}", subscriptionId);
                redisCache.put(cacheKey, db.get(), METRICS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return getDbInventory(subscriptionId, false).thenApply(instances -> {
            if (instances.isEmpty()) return Collections.<String, DbMetricsDto>emptyMap();
            Map<String, DbMetricsDto> metricsMap = new HashMap<>();
            int failures = 0;
            try {
                String token = getBearerToken(subscriptionId);
                AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
                List<SqlServer> sqlServers = listSqlServers(azure);

                // An instance gets an entry only when Azure Monitor returned data for it. Every
                // instance used to get a zero-filled entry up front, so one whose metrics failed
                // read as 0% CPU and 0 connections, and idle detection flagged it.
                for (DbInstanceDto inst : instances) {
                    try {
                        String resourceId = resolveResourceIdWithServers(inst, subscriptionId, sqlServers, token);
                        if (resourceId == null) {
                            failures++;
                            continue;
                        }
                        DbMetricsDto dto = new DbMetricsDto();
                        dto.setInstanceId(inst.getInstanceId());
                        dto.setRegion(inst.getRegion());
                        if (fetchAzureMonitorMetrics(resourceId, token, dto, inst.getEngine(), inst.getAllocatedStorage())) {
                            metricsMap.put(inst.getInstanceId(), dto);
                        } else {
                            failures++;
                        }
                    } catch (Exception ex) {
                        failures++;
                        log.debug("[AzureDbOps] Metrics fetch skipped for {}: {}", inst.getInstanceId(), ex.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("[AzureDbOps] Metrics error for subscription {}: {}", subscriptionId, e.getMessage());
                return metricsMap;
            }

            if (metricsMap.isEmpty()) {
                // Nothing came back: a failure, not a result. Caching it hid metrics for 12 hours.
                log.warn("[AzureDbOps] No metrics returned for any of {} instances, subscription {}; not caching",
                        instances.size(), subscriptionId);
            } else if (failures > 0) {
                redisCache.put(cacheKey, metricsMap, PARTIAL_TTL);
                log.info("[AzureDbOps] Metrics fetched for {} of {} instances, subscription {}; partial, cached {} min",
                        metricsMap.size(), instances.size(), subscriptionId, PARTIAL_TTL);
            } else {
                redisCache.put(cacheKey, metricsMap, METRICS_TTL);
                saveToDb(cacheKey, metricsMap);
                log.info("[AzureDbOps] Metrics fetched for {} instances, subscription {}", metricsMap.size(), subscriptionId);
            }
            return metricsMap;
        });
    }

    // =========================================================================
    // PUBLIC API — COSTS
    // =========================================================================

    /**
     * Estimate Azure DB costs from inventory SKU pricing, grouped by region and service.
     * Cached 6h.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbCostDto>> getDbCosts(String subscriptionId, boolean forceRefresh) {
        String cacheKey = "dbops:azure:costs:" + subscriptionId;

        if (!forceRefresh) {
            Optional<List<DbCostDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                log.info("[AzureDbOps] Redis hit — costs for subscription {}", subscriptionId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<List<DbCostDto>> db = getFromDb(cacheKey, new TypeReference<>() {}, COSTS_TTL);
            if (db.isPresent()) {
                log.info("[AzureDbOps] DB cache hit — costs for subscription {}", subscriptionId);
                redisCache.put(cacheKey, db.get(), COSTS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return getDbInventory(subscriptionId, false).thenApply(instances -> {
            // Actual month-to-date spend from Cost Management first, as AWS uses Cost Explorer; the
            // SKU estimates below only when no database service appears in the billing data.
            List<DbCostDto> billed = billedDbCosts(subscriptionId);
            if (!billed.isEmpty()) {
                redisCache.put(cacheKey, billed, COSTS_TTL);
                saveToDb(cacheKey, billed);
                log.info("[AzureDbOps] Costs from Cost Management: {} services for subscription {}", billed.size(), subscriptionId);
                return billed;
            }
            try {
                // Group by region + service, summing estimated monthly costs
                Map<String, Double> regionServiceCostMap = new LinkedHashMap<>();
                double total = 0.0;
                for (DbInstanceDto inst : instances) {
                    if (inst.getEstimatedMonthlyCost() <= 0) continue;
                    String key = inst.getRegion() + "|" + engineToServiceName(inst.getEngine());
                    regionServiceCostMap.merge(key, inst.getEstimatedMonthlyCost(), Double::sum);
                    total += inst.getEstimatedMonthlyCost();
                }

                List<DbCostDto> costs = new ArrayList<>();
                final double finalTotal = total;
                for (Map.Entry<String, Double> entry : regionServiceCostMap.entrySet()) {
                    String[] parts = entry.getKey().split("\\|", 2);
                    DbCostDto dto = new DbCostDto();
                    dto.setService(parts.length > 1 ? parts[1] : "Azure SQL");
                    dto.setRegion(parts[0]);
                    dto.setCost(round2(entry.getValue()));
                    dto.setPercentOfTotal(finalTotal > 0 ? round2(entry.getValue() / finalTotal * 100.0) : 0.0);
                    costs.add(dto);
                }

                costs.sort(Comparator.comparingDouble(DbCostDto::getCost).reversed());
                redisCache.put(cacheKey, costs, COSTS_TTL);
                saveToDb(cacheKey, costs);
                log.info("[AzureDbOps] Costs derived: {} entries for subscription {}", costs.size(), subscriptionId);
                return costs;
            } catch (Exception e) {
                log.error("[AzureDbOps] Costs error for subscription {}: {}", subscriptionId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // =========================================================================
    // PUBLIC API — BACKUP WASTE
    // =========================================================================

    /**
     * Find Azure SQL restore points older than BACKUP_WASTE_AGE_DAYS.
     * PostgreSQL/MySQL Flexible — Azure manages backups; returns empty list.
     * Cached 24h.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbSnapshotWasteDto>> getDbSnapshots(String subscriptionId, boolean forceRefresh) {
        String cacheKey = "dbops:azure:snapshots:" + subscriptionId;

        if (!forceRefresh) {
            Optional<List<DbSnapshotWasteDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                log.info("[AzureDbOps] Redis hit — backups for subscription {}", subscriptionId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<List<DbSnapshotWasteDto>> db = getFromDb(cacheKey, new TypeReference<>() {}, BACKUPS_TTL);
            if (db.isPresent()) {
                log.info("[AzureDbOps] DB cache hit — backups for subscription {}", subscriptionId);
                redisCache.put(cacheKey, db.get(), BACKUPS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return getDbInventory(subscriptionId, false).thenApply(instances -> {
            List<DbSnapshotWasteDto> wasteList = new ArrayList<>();
            int[] scanErrors = {0};
            try {
                AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
                Instant cutoff = Instant.now().minus(BACKUP_WASTE_AGE_DAYS, ChronoUnit.DAYS);
                // Listed once for the whole scan (was re-listed for every database).
                List<SqlServer> sqlServers = listSqlServers(azure);

                for (DbInstanceDto inst : instances) {
                    // Only Azure SQL Database has SDK-accessible restore points
                    if (!"sqlserver".equals(inst.getEngine())) continue;
                    // instanceId format: "serverName/dbName" (non-MI) or "mi/instanceName"
                    if (inst.getInstanceId().startsWith("mi/")) continue;

                    try {
                        String[] idParts = inst.getInstanceId().split("/", 2);
                        if (idParts.length < 2) continue;
                        String serverName = idParts[0];
                        String dbName = idParts[1];

                        // List restore points via Azure SDK
                        sqlServers.forEach(server -> {
                            if (!server.name().equalsIgnoreCase(serverName)) return;
                            server.databases().list().forEach(database -> {
                                if (!database.name().equalsIgnoreCase(dbName)) return;
                                try {
                                    database.listRestorePoints().forEach(rp -> {
                                        try {
                                            // A CONTINUOUS point is the start of the point-in-time
                                            // restore window: automated backups Azure keeps and
                                            // bills as part of the database. Nothing to delete,
                                            // so it is not backup waste. Only DISCRETE points are.
                                            if (RestorePointType.CONTINUOUS.equals(rp.restorePointType())) return;
                                            Instant rpTime = rp.restorePointCreationDate() != null
                                                    ? rp.restorePointCreationDate().toInstant()
                                                    : rp.earliestRestoreDate() != null
                                                        ? rp.earliestRestoreDate().toInstant() : null;
                                            if (rpTime == null || rpTime.isAfter(cutoff)) return;
                                            long ageDays = ChronoUnit.DAYS.between(rpTime, Instant.now());
                                            int sizeGb = inst.getAllocatedStorage() > 0 ? inst.getAllocatedStorage() : 32;

                                            DbSnapshotWasteDto dto = new DbSnapshotWasteDto();
                                            dto.setSnapshotId("rp-" + serverName + "-" + dbName + "-" + ageDays + "d");
                                            dto.setInstanceId(inst.getInstanceId());
                                            dto.setRegion(inst.getRegion());
                                            dto.setAgeDays((int) ageDays);
                                            dto.setSizeGb(sizeGb);
                                            dto.setEstimatedMonthlyCost(round2(sizeGb * BACKUP_COST_PER_GB));
                                            dto.setRecommendation(ageDays > 90
                                                    ? "Review retention before deleting — restore point over 90 days old"
                                                    : "Review — restore point created " + ageDays + " days ago");
                                            wasteList.add(dto);
                                        } catch (Exception ex) {
                                            log.debug("[AzureDbOps] Restore point parse error: {}", ex.getMessage());
                                        }
                                    });
                                } catch (Exception ex) {
                                    scanErrors[0]++;
                                    log.debug("[AzureDbOps] Restore points list error for {}/{}: {}", serverName, dbName, ex.getMessage());
                                }
                            });
                        });
                    } catch (Exception ex) {
                        scanErrors[0]++;
                        log.debug("[AzureDbOps] Backup scan error for {}: {}", inst.getInstanceId(), ex.getMessage());
                    }
                }

                wasteList.sort(Comparator.comparingDouble(DbSnapshotWasteDto::getEstimatedMonthlyCost).reversed());
                if (scanErrors[0] > 0) {
                    // Part of the scan failed; a day-long cache would hide that. Retry on the next load.
                    redisCache.put(cacheKey, wasteList, PARTIAL_TTL);
                    log.warn("[AzureDbOps] Backup scan for subscription {} had {} errors; cached {} min only",
                            subscriptionId, scanErrors[0], PARTIAL_TTL);
                } else {
                    redisCache.put(cacheKey, wasteList, BACKUPS_TTL);
                    saveToDb(cacheKey, wasteList);
                }
                log.info("[AzureDbOps] Backups fetched: {} waste entries for subscription {}", wasteList.size(), subscriptionId);
            } catch (Exception e) {
                // Not cached: a failed scan is not "no backup waste".
                log.error("[AzureDbOps] Backups error for subscription {}: {}", subscriptionId, e.getMessage());
            }
            return wasteList;
        });
    }

    // =========================================================================
    // DERIVED: IDLE DETECTION
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbIdleInstanceDto>> getIdleInstances(String subscriptionId, boolean forceRefresh) {
        return getDbMetrics(subscriptionId, forceRefresh)
                .thenCombine(getDbInventory(subscriptionId, false), (metrics, instances) -> {
                    List<DbIdleInstanceDto> idleList = new ArrayList<>();
                    for (DbInstanceDto inst : instances) {
                        DbMetricsDto m = metrics.get(inst.getInstanceId());
                        if (m == null) continue;
                        if (m.getAvgCpuPercent() <= IDLE_CPU_THRESHOLD
                                && m.getAvgConnections() <= IDLE_CONN_THRESHOLD) {
                            DbIdleInstanceDto dto = new DbIdleInstanceDto();
                            dto.setInstanceId(inst.getInstanceId());
                            dto.setInstanceClass(inst.getInstanceClass());
                            dto.setEngine(inst.getEngine());
                            dto.setRegion(inst.getRegion());
                            dto.setAvgCpuPercent(m.getAvgCpuPercent());
                            dto.setAvgConnections(m.getAvgConnections());
                            dto.setEstimatedMonthlyCost(inst.getEstimatedMonthlyCost());
                            dto.setPotentialSavings(inst.getEstimatedMonthlyCost());
                            dto.setRecommendation("No active connections for 7 days — consider stopping or deleting this Azure DB instance");
                            idleList.add(dto);
                        }
                    }
                    idleList.sort(Comparator.comparingDouble(DbIdleInstanceDto::getPotentialSavings).reversed());
                    return idleList;
                });
    }

    // =========================================================================
    // DERIVED: RIGHT-SIZING
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbRightsizingDto>> getRightsizingRecommendations(String subscriptionId, boolean forceRefresh) {
        return getDbMetrics(subscriptionId, forceRefresh)
                .thenCombine(getDbInventory(subscriptionId, false), (metrics, instances) -> {
                    List<DbRightsizingDto> recs = new ArrayList<>();
                    for (DbInstanceDto inst : instances) {
                        DbMetricsDto m = metrics.get(inst.getInstanceId());
                        if (m == null) continue;
                        if (m.getAvgCpuPercent() <= IDLE_CPU_THRESHOLD && m.getAvgConnections() <= IDLE_CONN_THRESHOLD) continue;
                        if (m.getAvgCpuPercent() > RIGHTSIZING_CPU_THRESHOLD) continue;

                        String recommended = DOWNSIZE_MAP.get(inst.getInstanceClass());
                        if (recommended == null) continue;

                        double currentCost     = inst.getEstimatedMonthlyCost();
                        double recommendedCost = SKU_COST_MAP.getOrDefault(recommended, currentCost * 0.6);
                        double savings         = currentCost - recommendedCost;
                        if (savings <= 0) continue;

                        DbRightsizingDto dto = new DbRightsizingDto();
                        dto.setInstanceId(inst.getInstanceId());
                        dto.setCurrentInstanceClass(inst.getInstanceClass());
                        dto.setRecommendedInstanceClass(recommended);
                        dto.setEngine(inst.getEngine());
                        dto.setRegion(inst.getRegion());
                        dto.setAvgCpuPercent(m.getAvgCpuPercent());
                        dto.setAvgFreeableMemoryMb(m.getAvgFreeableMemoryMb());
                        dto.setCurrentMonthlyCost(currentCost);
                        dto.setRecommendedMonthlyCost(recommendedCost);
                        dto.setEstimatedSavings(round2(savings));
                        dto.setReason(String.format("Avg CPU %.1f%% over 7 days — downsize from %s to %s",
                                m.getAvgCpuPercent(), inst.getInstanceClass(), recommended));
                        recs.add(dto);
                    }
                    recs.sort(Comparator.comparingDouble(DbRightsizingDto::getEstimatedSavings).reversed());
                    return recs;
                });
    }

    // =========================================================================
    // DERIVED: SECURITY CHECKS
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbSecurityCheckDto>> getSecurityChecks(String subscriptionId, boolean forceRefresh) {
        return getDbInventory(subscriptionId, forceRefresh).thenApply(instances -> {
            List<DbSecurityCheckDto> checks = new ArrayList<>();
            for (DbInstanceDto inst : instances) {
                // Public network access
                checks.add(makeCheck(inst, "Public Network Access",
                        inst.isPubliclyAccessible() ? "FAIL" : "PASS",
                        inst.isPubliclyAccessible() ? "HIGH" : "INFO",
                        inst.isPubliclyAccessible()
                                ? "Instance has public network access enabled — restrict via private endpoints or firewall rules"
                                : "Instance is not publicly accessible"));

                // Storage encryption (Azure encrypts by default)
                checks.add(makeCheck(inst, "Data Encryption at Rest",
                        inst.isStorageEncrypted() ? "PASS" : "WARN",
                        inst.isStorageEncrypted() ? "INFO" : "MEDIUM",
                        inst.isStorageEncrypted()
                                ? "Transparent Data Encryption (TDE) is enabled"
                                : "Encryption at rest may not be configured — verify TDE settings"));

                // Automated backups
                checks.add(makeCheck(inst, "Automated Backups",
                        inst.isBackupEnabled() ? "PASS" : "FAIL",
                        inst.isBackupEnabled() ? "INFO" : "HIGH",
                        inst.isBackupEnabled()
                                ? "Automated backups enabled (retention: " + inst.getBackupRetentionDays() + " days)"
                                : "Automated backups are DISABLED — enable for data protection"));

                // Zone redundancy / HA
                boolean ha = "ZoneRedundant".equalsIgnoreCase(inst.getMultiAz())
                        || "true".equalsIgnoreCase(inst.getMultiAz());
                checks.add(makeCheck(inst, "Zone Redundancy (HA)",
                        ha ? "PASS" : "WARN",
                        ha ? "INFO" : "MEDIUM",
                        ha ? "Zone redundancy is enabled (high availability)"
                           : "Not zone-redundant — consider enabling for production workloads"));

                // TLS floor as the server reports it. This used to read a hardcoded "TLS1_2", so the
                // check passed for every server whatever its policy.
                String minTls = inst.getMinTlsVersion();
                boolean tlsOk = tlsAtLeast12(minTls);
                checks.add(makeCheck(inst, "Minimum TLS Version",
                        tlsOk ? "PASS" : "WARN",
                        tlsOk ? "INFO" : "MEDIUM",
                        tlsOk ? "Minimum TLS 1.2 is enforced (" + minTls + ")"
                               : minTls == null
                                   ? "Minimum TLS version could not be read — verify the server's TLS policy"
                                   : "Minimum TLS version is " + minTls + " — set it to 1.2 or higher"));
            }
            return checks;
        });
    }

    // =========================================================================
    // DERIVED: SAVINGS SUMMARY
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<DbSavingsSummaryDto> getSavingsSummary(String subscriptionId, boolean forceRefresh) {
        CompletableFuture<List<DbInstanceDto>>     invFuture   = getDbInventory(subscriptionId, forceRefresh);
        // Phase 1a: a disabled pillar contributes an empty list, so its count/savings are 0 and it
        // never enters the savings total — the summary omits it rather than faking a value.
        CompletableFuture<List<DbIdleInstanceDto>> idleFuture  = dbOpsFeatureFlags.isIdleEnabled()
                ? getIdleInstances(subscriptionId, forceRefresh)
                : CompletableFuture.completedFuture(Collections.emptyList());
        CompletableFuture<List<DbRightsizingDto>>  rightFuture = dbOpsFeatureFlags.isRightsizingEnabled()
                ? getRightsizingRecommendations(subscriptionId, forceRefresh)
                : CompletableFuture.completedFuture(Collections.emptyList());
        // Refresh has to reach the backup scan too; it was always read from its 24-hour cache.
        CompletableFuture<List<DbSnapshotWasteDto>> snapFuture = getDbSnapshots(subscriptionId, forceRefresh);
        CompletableFuture<List<DbCostDto>>         costFuture  = getDbCosts(subscriptionId, forceRefresh);

        return CompletableFuture.allOf(invFuture, idleFuture, rightFuture, snapFuture, costFuture).thenApply(v -> {
            List<DbInstanceDto>     inventory = invFuture.join();
            List<DbIdleInstanceDto> idle      = idleFuture.join();
            List<DbRightsizingDto>  right     = rightFuture.join();
            List<DbSnapshotWasteDto> snaps    = snapFuture.join();
            List<DbCostDto>         costs     = costFuture.join();

            // "Monthly DB Spend" is the billed month-to-date spend, as on AWS; the SKU estimates
            // only when there is no billing data for the database services.
            double totalCost        = costs.stream().mapToDouble(DbCostDto::getCost).sum();
            if (totalCost == 0.0 && !inventory.isEmpty()) {
                totalCost = inventory.stream().mapToDouble(DbInstanceDto::getEstimatedMonthlyCost).sum();
            }
            double idleSavings      = idle.stream().mapToDouble(DbIdleInstanceDto::getPotentialSavings).sum();
            double rightsizeSavings = right.stream().mapToDouble(DbRightsizingDto::getEstimatedSavings).sum();
            double snapshotWaste    = snaps.stream().mapToDouble(DbSnapshotWasteDto::getEstimatedMonthlyCost).sum();
            double totalSavings     = idleSavings + rightsizeSavings + snapshotWaste;

            DbSavingsSummaryDto dto = new DbSavingsSummaryDto();
            dto.setTotalDbMonthlyCost(round2(totalCost));
            dto.setTotalPotentialSavings(round2(totalSavings));
            dto.setTotalInstances(inventory.size());
            dto.setIdleInstances(idle.size());
            dto.setIdleSavingsPotential(round2(idleSavings));
            dto.setRightsizingCandidates(right.size());
            dto.setRightsizingSavingsPotential(round2(rightsizeSavings));
            dto.setSnapshotWasteCost(round2(snapshotWaste));
            dto.setSavingsPercentage(totalCost > 0 ? round2(totalSavings / totalCost * 100.0) : 0.0);
            return dto;
        });
    }

    // =========================================================================
    // PUBLIC API — SLOW QUERIES
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<SlowQueriesResponseDto> getSlowQueries(String subscriptionId, boolean forceRefresh) {
        SlowQueriesResponseDto response = new SlowQueriesResponseDto();
        response.setLogsEnabled(false);
        response.setMessage("Azure DB slow query analysis requires the XamOps DB agent or Azure Monitor diagnostic settings.");
        response.setQueries(Collections.emptyList());
        return CompletableFuture.completedFuture(response);
    }

    // =========================================================================
    // PUBLIC API — INSTANCE TIMESERIES
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<DbInstanceTimeseriesDto> getInstanceTimeseries(
            String subscriptionId, String instanceId) {
        return getInstanceTimeseries(subscriptionId, instanceId, 24);
    }

    /** Per-instance Azure Monitor series over the last {@code hours} (1–720; it was fixed at 24). */
    @Async("awsTaskExecutor")
    public CompletableFuture<DbInstanceTimeseriesDto> getInstanceTimeseries(
            String subscriptionId, String instanceId, int hours) {
        DbInstanceTimeseriesDto dto = new DbInstanceTimeseriesDto();
        dto.setInstanceId(instanceId);
        int window = Math.max(1, Math.min(hours, 720));
        try {
            String token = getBearerToken(subscriptionId);
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            String resourceId = resolveFullResourceId(subscriptionId, instanceId, azure, token);
            if (resourceId != null) {
                int allocatedGb = resolveAllocatedStorageGb(subscriptionId, instanceId);
                fetchTimeseriesFromMonitor(resourceId, token, dto, engineFromInstanceId(instanceId), allocatedGb, window);
            }
        } catch (Exception e) {
            log.warn("[AzureDbOps] Timeseries failed for {}: {}", instanceId, e.getMessage());
        }
        if (dto.getCpu() == null) dto.setCpu(Collections.emptyList());
        if (dto.getConnections() == null) dto.setConnections(Collections.emptyList());
        if (dto.getReadIops() == null) dto.setReadIops(Collections.emptyList());
        if (dto.getWriteIops() == null) dto.setWriteIops(Collections.emptyList());
        if (dto.getReadLatencyMs() == null) dto.setReadLatencyMs(Collections.emptyList());
        if (dto.getWriteLatencyMs() == null) dto.setWriteLatencyMs(Collections.emptyList());
        if (dto.getFreeStorageGb() == null) dto.setFreeStorageGb(Collections.emptyList());
        if (dto.getFreeableMemoryMb() == null) dto.setFreeableMemoryMb(Collections.emptyList());
        if (dto.getNetworkReceiveMbps() == null) dto.setNetworkReceiveMbps(Collections.emptyList());
        if (dto.getNetworkTransmitMbps() == null) dto.setNetworkTransmitMbps(Collections.emptyList());
        if (dto.getReplicaLagSeconds() == null) dto.setReplicaLagSeconds(Collections.emptyList());
        return CompletableFuture.completedFuture(dto);
    }

    private String engineFromInstanceId(String instanceId) {
        if (instanceId == null) return "sqldb";
        if (instanceId.startsWith("pg/")) return "postgres";
        if (instanceId.startsWith("mysql/")) return "mysql";
        if (instanceId.startsWith("mi/")) return "sqlmi";
        return "sqldb";
    }

    /**
     * Resolves the full ARM resource ID for any of the 4 supported Azure DB types.
     * For SQL DB and SQL MI, delegates to resolveResourceId.
     * For PostgreSQL/MySQL Flexible Servers, calls the list REST API to find the RG.
     */
    private String resolveFullResourceId(String subscriptionId, String instanceId,
                                          AzureResourceManager azure, String token) {
        if (instanceId == null) return null;

        if (instanceId.startsWith("pg/")) {
            String serverName = instanceId.substring(3);
            return findFlexibleServerResourceId(subscriptionId, token, serverName,
                    "Microsoft.DBforPostgreSQL", "flexibleServers", "2023-06-01-preview");
        }
        if (instanceId.startsWith("mysql/")) {
            String serverName = instanceId.substring(6);
            return findFlexibleServerResourceId(subscriptionId, token, serverName,
                    "Microsoft.DBforMySQL", "flexibleServers", "2023-06-30");
        }
        if (instanceId.startsWith("mi/")) {
            return findManagedInstanceResourceId(subscriptionId, token, instanceId.substring(3));
        }
        // SQL DB only
        return resolveResourceId(buildMinimalDto(instanceId), subscriptionId, azure, token);
    }

    private String findFlexibleServerResourceId(String subscriptionId, String token,
                                                  String serverName, String provider,
                                                  String resourceType, String apiVersion) {
        try {
            String url = ARM_BASE + "/subscriptions/" + subscriptionId
                    + "/providers/" + provider + "/" + resourceType
                    + "?api-version=" + apiVersion;
            String body = armGet(url, token);
            if (body == null) return null;
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode node : root.path("value")) {
                if (serverName.equalsIgnoreCase(node.path("name").asText(""))) {
                    String id = node.path("id").asText(null);
                    return id;
                }
            }
        } catch (Exception e) {
            log.debug("[AzureDbOps] Could not find resource ID for {}/{}: {}", provider, serverName, e.getMessage());
        }
        return null;
    }

    private String findManagedInstanceResourceId(String subscriptionId, String token, String miName) {
        try {
            String url = ARM_BASE + "/subscriptions/" + subscriptionId
                    + "/providers/Microsoft.Sql/managedInstances?api-version=2021-11-01-preview";
            String body = armGet(url, token);
            if (body == null) return null;
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode node : root.path("value")) {
                if (miName.equalsIgnoreCase(node.path("name").asText(""))) {
                    return node.path("id").asText(null);
                }
            }
        } catch (Exception e) {
            log.debug("[AzureDbOps] Could not find MI resource ID for {}: {}", miName, e.getMessage());
        }
        return null;
    }

    private DbInstanceDto buildMinimalDto(String instanceId) {
        DbInstanceDto d = new DbInstanceDto();
        d.setInstanceId(instanceId);
        return d;
    }

    /**
     * Looks up the instance's provisioned storage (GB) from inventory so a used-storage metric
     * can be turned into free = allocated − used. Returns 0 (unknown) if it cannot be resolved.
     */
    private int resolveAllocatedStorageGb(String subscriptionId, String instanceId) {
        try {
            for (DbInstanceDto inst : getDbInventory(subscriptionId, false).join()) {
                if (instanceId != null && instanceId.equals(inst.getInstanceId())) {
                    return inst.getAllocatedStorage();
                }
            }
        } catch (Exception e) {
            log.debug("[AzureDbOps] Could not resolve allocated storage for {}: {}", instanceId, e.getMessage());
        }
        return 0;
    }

    private void fetchTimeseriesFromMonitor(String resourceId, String token,
                                             DbInstanceTimeseriesDto dto, String engine, int allocatedGb, int hours) {
        try {
            String metricNames = resolveMetricNames(engine);
            // Grain sized to the window so a chart has tens of points, not 1 or 700.
            String interval = hours <= 6 ? "PT5M" : hours <= 24 ? "PT15M" : hours <= 72 ? "PT1H" : "PT6H";
            String url = ARM_BASE + resourceId
                    + "/providers/Microsoft.Insights/metrics"
                    + "?metricnames=" + metricNames
                    + "&timespan=PT" + hours + "H"
                    + "&interval=" + interval
                    + "&aggregation=Average"
                    + "&api-version=2018-01-01";

            String body = armGet(url, token);
            if (body == null) return;

            JsonNode root = objectMapper.readTree(body);
            for (JsonNode metricNode : root.path("value")) {
                String metricName = metricNode.path("name").path("value").asText("");
                JsonNode timeseries = metricNode.path("timeseries");
                if (!timeseries.isArray() || timeseries.isEmpty()) continue;
                JsonNode dataArr = timeseries.get(0).path("data");
                if (!dataArr.isArray()) continue;

                List<DbInstanceTimeseriesDto.Point> points = new ArrayList<>();
                for (JsonNode point : dataArr) {
                    String tsStr = point.path("timeStamp").asText(null);
                    JsonNode avg = point.path("average");
                    if (tsStr == null || avg.isMissingNode() || avg.isNull()) continue;
                    long epochMs = java.time.Instant.parse(tsStr).toEpochMilli();
                    points.add(new DbInstanceTimeseriesDto.Point(epochMs, round2(avg.asDouble())));
                }
                applyTimeseriesMetric(dto, metricName, points, allocatedGb);
            }
        } catch (Exception e) {
            log.debug("[AzureDbOps] fetchTimeseriesFromMonitor error for {}: {}", resourceId, e.getMessage());
        }
    }

    private void applyTimeseriesMetric(DbInstanceTimeseriesDto dto, String metricName,
                                        List<DbInstanceTimeseriesDto.Point> points, int allocatedGb) {
        switch (metricName) {
            case "cpu_percent":
                dto.setCpu(points);
                break;
            case "active_connections":
            case "connection_successful":
                dto.setConnections(points);
                break;
            case "storage_used":
            case "storage":
                // COR-9: bytes USED → free GB = allocated − used (was storing used-as-free).
                List<DbInstanceTimeseriesDto.Point> storageGb = new ArrayList<>();
                for (DbInstanceTimeseriesDto.Point p : points) {
                    double usedGb = p.getV() / (1024.0 * 1024.0 * 1024.0);
                    double freeGb = allocatedGb > 0 ? Math.max(0.0, allocatedGb - usedGb) : 0.0;
                    storageGb.add(new DbInstanceTimeseriesDto.Point(p.getTs(), round2(freeGb)));
                }
                dto.setFreeStorageGb(storageGb);
                break;
            case "network_bytes_ingress":
                List<DbInstanceTimeseriesDto.Point> rx = new ArrayList<>();
                for (DbInstanceTimeseriesDto.Point p : points) {
                    rx.add(new DbInstanceTimeseriesDto.Point(p.getTs(), round2(p.getV() / (1024.0 * 1024.0))));
                }
                dto.setNetworkReceiveMbps(rx);
                break;
            case "network_bytes_egress":
                List<DbInstanceTimeseriesDto.Point> tx = new ArrayList<>();
                for (DbInstanceTimeseriesDto.Point p : points) {
                    tx.add(new DbInstanceTimeseriesDto.Point(p.getTs(), round2(p.getV() / (1024.0 * 1024.0))));
                }
                dto.setNetworkTransmitMbps(tx);
                break;
            case "iops":
                // COR-9: real Flexible-server IOPS series. SQL DB/MI request no IOPS metric, so
                // their read/write IOPS stay empty rather than holding percentages.
                dto.setReadIops(points);
                break;
            default:
                break;
        }
    }

    // =========================================================================
    // AGENT DATA — same DB tables as GCP (cloud-provider-agnostic storage)
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentInstanceMetricsDto>> getAgentInstanceMetrics(String subscriptionId) {
        return dbOpsAsync(() -> {
            try {
                CloudAccount account = getAccountBySubscriptionId(subscriptionId);
                if (account == null) return Collections.emptyList();
                List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(account.getId());
                List<AgentInstanceMetricsDto> result = new ArrayList<>();
                for (DbAgentConfig agent : agents) {
                    dbAgentDataCacheRepository.findByDbAgentIdAndDataType(agent.getId(), "db-instance-metrics")
                            .ifPresent(cache -> {
                                try {
                                    List<AgentInstanceMetricsDto> items = objectMapper.readValue(
                                            cache.getDataJson(), new TypeReference<List<AgentInstanceMetricsDto>>() {});
                                    items.forEach(dto -> {
                                        dto.setAgentName(agent.getAgentName());
                                        result.add(dto);
                                    });
                                } catch (Exception ex) {
                                    log.warn("[AzureDbOps] Failed to parse agent instance metrics: {}", ex.getMessage());
                                }
                            });
                }
                return result;
            } catch (Exception e) {
                log.error("[AzureDbOps] Agent instance metrics error for {}: {}", subscriptionId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentQueryStatDto>> getAgentQueryStats(String subscriptionId) {
        return dbOpsAsync(() -> {
            try {
                CloudAccount account = getAccountBySubscriptionId(subscriptionId);
                if (account == null) return Collections.emptyList();
                List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(account.getId());
                List<AgentQueryStatDto> result = new ArrayList<>();
                for (DbAgentConfig agent : agents) {
                    dbAgentDataCacheRepository.findByDbAgentIdAndDataType(agent.getId(), "db-query-stats")
                            .ifPresent(cache -> {
                                try {
                                    List<AgentQueryStatDto> stats = objectMapper.readValue(
                                            cache.getDataJson(), new TypeReference<>() {});
                                    // When the batch was collected, as AWS stamps it; the tab shows it.
                                    String ts = cache.getCollectedAt() != null
                                            ? cache.getCollectedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                                            : null;
                                    stats.forEach(s -> s.setCollectedAt(ts));
                                    result.addAll(stats);
                                } catch (Exception ex) {
                                    log.warn("[AzureDbOps] Failed to parse agent query stats: {}", ex.getMessage());
                                }
                            });
                }
                // Slowest first, the order the AWS endpoint returns.
                result.sort(Comparator.comparingDouble(AgentQueryStatDto::getTotalTimeSeconds).reversed());
                return result;
            } catch (Exception e) {
                log.error("[AzureDbOps] Agent query stats error for {}: {}", subscriptionId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentTableSizeDto>> getAgentTableSizes(String subscriptionId) {
        return dbOpsAsync(() -> readAgentData(subscriptionId, "db-table-sizes",
                new TypeReference<List<AgentTableSizeDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentIndexUsageDto>> getAgentIndexUsage(String subscriptionId) {
        return dbOpsAsync(() -> readAgentData(subscriptionId, "db-index-usage",
                new TypeReference<List<AgentIndexUsageDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentTableActivityDto>> getAgentTableActivity(String subscriptionId) {
        return dbOpsAsync(() -> readAgentData(subscriptionId, "db-table-activity",
                new TypeReference<List<AgentTableActivityDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentVacuumStatDto>> getAgentVacuumStats(String subscriptionId) {
        return dbOpsAsync(() -> readAgentData(subscriptionId, "db-vacuum-stats",
                new TypeReference<List<AgentVacuumStatDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentUserActivityDto>> getAgentUserActivity(String subscriptionId) {
        return dbOpsAsync(() -> readAgentData(subscriptionId, "db-user-activity",
                new TypeReference<List<AgentUserActivityDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentTimeseriesDto>> getAgentTimeseries(String subscriptionId, int hours) {
        return dbOpsAsync(() -> {
            try {
                CloudAccount account = getAccountBySubscriptionId(subscriptionId);
                if (account == null) return Collections.emptyList();
                List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(account.getId());
                if (agents.isEmpty()) return Collections.emptyList();

                java.time.LocalDateTime since = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(hours);
                List<AgentTimeseriesDto> result = new ArrayList<>();

                for (DbAgentConfig agent : agents) {
                    List<DbAgentMetricsHistory> historyRows =
                            dbAgentMetricsHistoryRepository
                                    .findByDbAgentIdAndDataTypeAndCollectedAtAfterOrderByCollectedAtAsc(
                                            agent.getId(), "db-instance-metrics", since);
                    if (historyRows.isEmpty()) continue;

                    List<AgentTimeseriesDto.SnapshotPoint> snapshots = new ArrayList<>();
                    String actualDbHost = agent.getDbHost();

                    for (DbAgentMetricsHistory row : historyRows) {
                        try {
                            List<AgentInstanceMetricsDto> items = objectMapper.readValue(
                                    row.getDataJson(), new TypeReference<List<AgentInstanceMetricsDto>>() {});
                            if (items == null || items.isEmpty()) continue;
                            AgentInstanceMetricsDto m = items.get(0);
                            if (actualDbHost == null && m.getDbHost() != null) {
                                actualDbHost = m.getDbHost();
                            }
                            AgentTimeseriesDto.SnapshotPoint point = new AgentTimeseriesDto.SnapshotPoint();
                            point.setTs(row.getCollectedAt().toString());
                            point.setConnections(m.getCurrentConnections());
                            point.setMaxConnections(m.getMaxConnections());
                            point.setBufferPoolHitPct(m.getInnodbBufferPoolHitPct());
                            point.setTotalQueries(m.getTotalQueries());
                            point.setTotalSlowQueries(m.getTotalSlowQueries());
                            point.setComSelect(m.getComSelect());
                            point.setComInsert(m.getComInsert());
                            point.setComUpdate(m.getComUpdate());
                            point.setComDelete(m.getComDelete());
                            point.setInnodbRowLockWaits(m.getInnodbRowLockWaits());
                            point.setInnodbDeadlocks(m.getInnodbDeadlocks());
                            point.setThreadsRunning(m.getThreadsRunning());
                            snapshots.add(point);
                        } catch (Exception ex) {
                            log.warn("[AzureDbOps] Failed to parse timeseries row for agent {}: {}", agent.getAgentName(), ex.getMessage());
                        }
                    }
                    if (!snapshots.isEmpty()) {
                        AgentTimeseriesDto dto = new AgentTimeseriesDto();
                        dto.setAgentName(agent.getAgentName());
                        dto.setDbHost(actualDbHost);
                        dto.setSnapshots(snapshots);
                        result.add(dto);
                    }
                }
                log.info("[AzureDbOps] Agent timeseries: {} agents for subscription {}", result.size(), subscriptionId);
                return result;
            } catch (Exception e) {
                log.error("[AzureDbOps] Agent timeseries error for {}: {}", subscriptionId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // =========================================================================
    // PRIVATE — AZURE SDK COLLECTION HELPERS
    // =========================================================================

    private boolean collectSqlDatabases(AzureResourceManager azure, String token, List<DbInstanceDto> instances) {
        try {
            azure.sqlServers().list().forEach(server -> {
                try {
                    // Server-level settings, read once per server rather than once per database.
                    boolean serverPublic = serverHasPublicAccess(server);
                    String minTls = null;
                    try {
                        minTls = server.innerModel().minimalTlsVersion();
                    } catch (Exception ignored) {}
                    final String serverTls = minTls;
                    server.databases().list().forEach(db -> {
                        try {
                            // Skip master/system databases
                            if ("master".equalsIgnoreCase(db.name())) return;
                            instances.add(toSqlDatabaseDto(server, db, serverPublic, serverTls, token));
                        } catch (Exception ex) {
                            log.debug("[AzureDbOps] SQL DB dto error for {}/{}: {}", server.name(), db.name(), ex.getMessage());
                        }
                    });
                } catch (Exception ex) {
                    log.debug("[AzureDbOps] SQL DB list error for server {}: {}", server.name(), ex.getMessage());
                }
            });
            return true;
        } catch (Exception e) {
            log.warn("[AzureDbOps] collectSqlDatabases error: {}", e.getMessage());
            return false;
        }
    }

    private DbInstanceDto toSqlDatabaseDto(SqlServer server, SqlDatabase db, boolean serverPublic,
                                           String serverTls, String token) {
        DbInstanceDto dto = new DbInstanceDto();
        dto.setInstanceId(server.name() + "/" + db.name());
        String skuName = db.currentServiceObjectiveName() != null ? db.currentServiceObjectiveName() : "GP_Gen5_2";
        dto.setInstanceClass(skuName);
        dto.setEngine("sqlserver");
        dto.setEngineVersion("SQL Server"); // Azure SQL Database doesn't expose a specific version string via SDK
        dto.setStatus(db.status() != null ? db.status().toString().toLowerCase() : "unknown");
        dto.setRegion(server.regionName() != null ? server.regionName() : "unknown");
        // Read from the database instead of the hardcoded "None" (a zone-redundant DB showed as zonal).
        boolean zoneRedundant = false;
        try {
            zoneRedundant = Boolean.TRUE.equals(db.innerModel().zoneRedundant());
        } catch (Exception ignored) {}
        dto.setMultiAz(zoneRedundant ? "ZoneRedundant" : "None");
        // storageType held a hardcoded "TLS1_2", which the TLS check then always passed and the
        // drawer showed as the storage type. TLS now has its own field, read from the server.
        dto.setMinTlsVersion(serverTls);
        dto.setAllocatedStorage(db.maxSizeBytes() > 0 ? (int) (db.maxSizeBytes() / (1024L * 1024L * 1024L)) : 32);
        // An Entra (AD) admin is an identity setting, not network exposure; it used to count as public.
        dto.setPubliclyAccessible(serverPublic);
        dto.setStorageEncrypted(tdeEnabled(db));
        int retentionDays = sqlBackupRetentionDays(db, token);
        dto.setBackupEnabled(retentionDays > 0);
        dto.setBackupRetentionDays(String.valueOf(retentionDays));
        dto.setEndpoint(server.fullyQualifiedDomainName() != null ? server.fullyQualifiedDomainName() : "");
        dto.setEstimatedMonthlyCost(SKU_COST_MAP.getOrDefault(skuName, 100.0));
        dto.setMaxConnections(estimateMaxConnections(skuName));
        return dto;
    }

    /**
     * Reachable from the internet: public network access is not Disabled and at least one firewall
     * rule admits traffic. With no rules the public endpoint exists but refuses every connection.
     */
    private boolean serverHasPublicAccess(SqlServer server) {
        try {
            ServerNetworkAccessFlag access = server.innerModel().publicNetworkAccess();
            if (ServerNetworkAccessFlag.DISABLED.equals(access)) {
                return false;
            }
            return server.firewallRules().list().iterator().hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    /** Transparent Data Encryption state of the database; Azure's default (on) if it cannot be read. */
    private boolean tdeEnabled(SqlDatabase db) {
        try {
            return !TransparentDataEncryptionState.DISABLED.equals(db.getTransparentDataEncryption().status());
        } catch (Exception e) {
            log.debug("[AzureDbOps] TDE state unreadable for {}: {}", db.name(), e.getMessage());
            return true;
        }
    }

    /** Point-in-time restore retention from the short-term retention policy; Azure's default (7) if unreadable. */
    private int sqlBackupRetentionDays(SqlDatabase db, String token) {
        try {
            String body = armGet(ARM_BASE + db.id()
                    + "/backupShortTermRetentionPolicies/default?api-version=2021-11-01", token);
            if (body != null) {
                int days = objectMapper.readTree(body).path("properties").path("retentionDays").asInt(0);
                if (days > 0) {
                    return days;
                }
            }
        } catch (Exception e) {
            log.debug("[AzureDbOps] Backup retention unreadable for {}: {}", db.name(), e.getMessage());
        }
        return 7;
    }

    /**
     * Reads one server parameter of a Flexible server (e.g. the TLS floor). Null when unreadable,
     * which the security check reports as unverified rather than as a pass.
     */
    private String flexibleServerParameter(String serverId, String parameter, String apiVersion, String token) {
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        try {
            String body = armGet(ARM_BASE + serverId + "/configurations/" + parameter + "?api-version=" + apiVersion, token);
            if (body == null) {
                return null;
            }
            String value = objectMapper.readTree(body).path("properties").path("value").asText(null);
            return value == null || value.isBlank() ? null : value;
        } catch (Exception e) {
            log.debug("[AzureDbOps] Parameter {} unreadable for {}: {}", parameter, serverId, e.getMessage());
            return null;
        }
    }

    /**
     * True when the TLS floor is 1.2 or higher. Accepts Azure's spellings: "1.2" (SQL), "TLSv1.2"
     * (PostgreSQL) and "TLSv1.2,TLSv1.3" (MySQL lists every allowed version).
     */
    static boolean tlsAtLeast12(String minTls) {
        if (minTls == null || minTls.isBlank()) {
            return false;
        }
        boolean modern = false;
        for (String token : minTls.split(",")) {
            String v = token.trim().toUpperCase(Locale.ROOT).replace("TLSV", "").replace("TLS", "").replace("_", ".");
            if (v.equals("1") || v.equals("1.0") || v.equals("1.1") || v.equals("NONE")) {
                return false;
            }
            if (v.equals("1.2") || v.equals("1.3")) {
                modern = true;
            }
        }
        return modern;
    }

    private boolean collectSqlManagedInstances(String subscriptionId, String token, List<DbInstanceDto> instances) {
        try {
            String url = ARM_BASE + "/subscriptions/" + subscriptionId
                    + "/providers/Microsoft.Sql/managedInstances?api-version=2021-11-01-preview";
            // list() follows nextLink (only the first page was read before) and throws when the
            // first page fails, which the catch below reports as a source failure.
            for (JsonNode node : armClient.list(url, token)) {
                try {
                    JsonNode props = node.path("properties");
                    String name = node.path("name").asText("unknown");
                    JsonNode skuNode = node.path("sku");
                    String skuName = skuNode.path("name").asText("GP_Gen5_8");
                    if (skuNode.has("tier") && skuNode.has("capacity")) {
                        skuName = skuNode.path("name").asText("GP_Gen5_8")
                                + "_" + skuNode.path("capacity").asText("8");
                    }
                    DbInstanceDto dto = new DbInstanceDto();
                    dto.setInstanceId("mi/" + name);
                    dto.setInstanceClass(skuName);
                    dto.setEngine("sqlserver");
                    dto.setEngineVersion("SQL Managed Instance");
                    dto.setStatus(props.path("state").asText("unknown").toLowerCase());
                    dto.setRegion(node.path("location").asText("unknown"));
                    dto.setMultiAz(props.path("zoneRedundant").asBoolean(false) ? "ZoneRedundant" : "None");
                    dto.setMinTlsVersion(props.path("minimalTlsVersion").asText(null));
                    dto.setAllocatedStorage(props.path("storageSizeInGB").asInt(0));
                    dto.setPubliclyAccessible(props.path("publicDataEndpointEnabled").asBoolean(false));
                    dto.setStorageEncrypted(true);
                    dto.setBackupEnabled(true);
                    dto.setBackupRetentionDays("7");
                    dto.setEndpoint(props.path("fullyQualifiedDomainName").asText(""));
                    dto.setEstimatedMonthlyCost(SKU_COST_MAP.getOrDefault(skuName, 500.0));
                    dto.setMaxConnections(estimateMaxConnections(skuName));
                    instances.add(dto);
                } catch (Exception ex) {
                    log.debug("[AzureDbOps] SQL MI dto error: {}", ex.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("[AzureDbOps] collectSqlManagedInstances error: {}", e.getMessage());
            return false;
        }
    }

    private boolean collectPostgresFlexibleServers(String subscriptionId, String token,
            List<DbInstanceDto> instances) {
        try {
            String url = ARM_BASE + "/subscriptions/" + subscriptionId
                    + "/providers/Microsoft.DBforPostgreSQL/flexibleServers?api-version=2023-06-01-preview";
            // list() follows nextLink and throws on a failed first page (a source failure below).
            for (JsonNode node : armClient.list(url, token)) {
                try {
                    DbInstanceDto dto = new DbInstanceDto();
                    String name = node.path("name").asText("unknown");
                    dto.setInstanceId("pg/" + name);
                    JsonNode props = node.path("properties");
                    JsonNode sku = node.path("sku");
                    String skuName = sku.path("name").asText("Standard_D2s_v3");
                    dto.setInstanceClass(skuName);
                    dto.setEngine("postgres");
                    dto.setEngineVersion(props.path("version").asText("14"));
                    dto.setStatus(props.path("state").asText("unknown").toLowerCase());
                    dto.setRegion(node.path("location").asText("unknown"));
                    String haMode = props.path("highAvailability").path("mode").asText("Disabled");
                    dto.setMultiAz("ZoneRedundant".equalsIgnoreCase(haMode) ? "ZoneRedundant" : "None");
                    String publicNetworkAccess = props.path("network").path("publicNetworkAccess").asText("Enabled");
                    dto.setStorageType(props.path("storage").path("type").asText(null));
                    // The TLS floor is a server parameter, not a property; it was hardcoded as passing.
                    dto.setMinTlsVersion(flexibleServerParameter(node.path("id").asText(null),
                            "ssl_min_protocol_version", "2023-06-01-preview", token));
                    int storageGb = props.path("storage").path("storageSizeGB").asInt(32);
                    dto.setAllocatedStorage(storageGb);
                    boolean publicAccess = "Enabled".equalsIgnoreCase(publicNetworkAccess);
                    dto.setPubliclyAccessible(publicAccess);
                    dto.setStorageEncrypted(true);
                    int backupDays = props.path("backup").path("backupRetentionDays").asInt(7);
                    dto.setBackupEnabled(backupDays > 0);
                    dto.setBackupRetentionDays(String.valueOf(backupDays));
                    dto.setEndpoint(props.path("fullyQualifiedDomainName").asText(""));
                    dto.setEstimatedMonthlyCost(estimateFlexibleCost(skuName));
                    dto.setMaxConnections(estimateMaxConnections(skuName));
                    instances.add(dto);
                } catch (Exception ex) {
                    log.debug("[AzureDbOps] Postgres flexible server dto error: {}", ex.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("[AzureDbOps] collectPostgresFlexibleServers error: {}", e.getMessage());
            return false;
        }
    }

    private boolean collectMysqlFlexibleServers(String subscriptionId, String token,
            List<DbInstanceDto> instances) {
        try {
            String url = ARM_BASE + "/subscriptions/" + subscriptionId
                    + "/providers/Microsoft.DBforMySQL/flexibleServers?api-version=2023-06-30";
            // list() follows nextLink and throws on a failed first page (a source failure below).
            for (JsonNode node : armClient.list(url, token)) {
                try {
                    DbInstanceDto dto = new DbInstanceDto();
                    String name = node.path("name").asText("unknown");
                    dto.setInstanceId("mysql/" + name);
                    JsonNode props = node.path("properties");
                    JsonNode sku = node.path("sku");
                    String skuName = sku.path("name").asText("Standard_D2ds_v4");
                    dto.setInstanceClass(skuName);
                    dto.setEngine("mysql");
                    dto.setEngineVersion(props.path("version").asText("8.0.21"));
                    dto.setStatus(props.path("state").asText("unknown").toLowerCase());
                    dto.setRegion(node.path("location").asText("unknown"));
                    String haMode = props.path("highAvailability").path("mode").asText("Disabled");
                    dto.setMultiAz("ZoneRedundant".equalsIgnoreCase(haMode) ? "ZoneRedundant" : "None");
                    dto.setStorageType(props.path("storage").path("storageSku").asText(null));
                    // tls_version lists every allowed version, e.g. "TLSv1.2,TLSv1.3".
                    dto.setMinTlsVersion(flexibleServerParameter(node.path("id").asText(null),
                            "tls_version", "2023-06-30", token));
                    int storageGb = props.path("storage").path("storageSizeGB").asInt(20);
                    dto.setAllocatedStorage(storageGb);
                    String publicAccess = props.path("network").path("publicNetworkAccess").asText("Enabled");
                    dto.setPubliclyAccessible("Enabled".equalsIgnoreCase(publicAccess));
                    dto.setStorageEncrypted(true);
                    int backupDays = props.path("backup").path("backupRetentionDays").asInt(7);
                    dto.setBackupEnabled(backupDays > 0);
                    dto.setBackupRetentionDays(String.valueOf(backupDays));
                    dto.setEndpoint(props.path("fullyQualifiedDomainName").asText(""));
                    dto.setEstimatedMonthlyCost(estimateFlexibleCost(skuName));
                    dto.setMaxConnections(estimateMaxConnections(skuName));
                    instances.add(dto);
                } catch (Exception ex) {
                    log.debug("[AzureDbOps] MySQL flexible server dto error: {}", ex.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("[AzureDbOps] collectMysqlFlexibleServers error: {}", e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // PRIVATE — AZURE MONITOR METRICS
    // =========================================================================

    /** @return true when Azure Monitor returned a CPU series, the metric every check depends on */
    private boolean fetchAzureMonitorMetrics(String resourceId, String token, DbMetricsDto dto,
                                             String engine, int allocatedGb) {
        boolean gotCpu = false;
        try {
            String metricNames = resolveMetricNames(engine);
            String timespan = "P7D";
            String interval = "PT1H";
            String url = ARM_BASE + resourceId
                    + "/providers/Microsoft.Insights/metrics"
                    + "?metricnames=" + metricNames
                    + "&timespan=" + timespan
                    + "&interval=" + interval
                    + "&aggregation=Average"
                    + "&api-version=2018-01-01";

            String responseBody = armGet(url, token);
            if (responseBody == null) return false;

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode valueArr = root.path("value");
            if (!valueArr.isArray()) return false;

            for (JsonNode metricNode : valueArr) {
                String metricName = metricNode.path("name").path("value").asText("");
                JsonNode timeSeries = metricNode.path("timeseries");
                if (!timeSeries.isArray() || timeSeries.isEmpty()) continue;
                JsonNode dataArr = timeSeries.get(0).path("data");
                if (!dataArr.isArray() || dataArr.isEmpty()) continue;

                // Calculate average of all data points
                double sum = 0.0;
                int count = 0;
                for (JsonNode point : dataArr) {
                    JsonNode avg = point.path("average");
                    if (!avg.isMissingNode() && !avg.isNull()) {
                        sum += avg.asDouble();
                        count++;
                    }
                }
                // A series with no data points is "no data", not an average of zero.
                if (count == 0) continue;
                applyAzureMetric(dto, metricName, sum / count, allocatedGb);
                if ("cpu_percent".equals(metricName)) {
                    gotCpu = true;
                }
            }
        } catch (Exception e) {
            log.debug("[AzureDbOps] Azure Monitor metrics error for resource {}: {}", resourceId, e.getMessage());
        }
        return gotCpu;
    }

    private String resolveMetricNames(String engine) {
        if ("postgres".equals(engine) || "mysql".equals(engine)) {
            // Flexible servers expose a real aggregate IOPS counter.
            return "cpu_percent,active_connections,storage_used,network_bytes_ingress,network_bytes_egress,iops";
        }
        // SQL Server / MI — Azure exposes read/write as PERCENTAGES, not IOPS, so no honest IOPS
        // metric exists here; request none rather than mislabel a percentage as IOPS.
        return "cpu_percent,connection_successful,storage";
    }

    void applyAzureMetric(DbMetricsDto dto, String metricName, double value, int allocatedGb) {
        switch (metricName) {
            case "cpu_percent":
                dto.setAvgCpuPercent(round2(value));
                break;
            case "active_connections":
            case "connection_successful":
                dto.setAvgConnections(round2(value));
                break;
            case "storage_used":
            case "storage":
                // COR-9: Azure reports bytes USED. Free space = allocated − used; the previous
                // code stored used-as-free. When allocated is unknown (0) leave free at 0 rather
                // than reporting the used figure as free.
                double usedGb = value / (1024.0 * 1024.0 * 1024.0);
                double freeGb = allocatedGb > 0 ? Math.max(0.0, allocatedGb - usedGb) : 0.0;
                dto.setFreeStorageSpaceGb(round2(freeGb));
                break;
            case "network_bytes_ingress":
                dto.setAvgNetworkReceiveMbps(round2(value / (1024.0 * 1024.0)));
                break;
            case "network_bytes_egress":
                dto.setAvgNetworkTransmitMbps(round2(value / (1024.0 * 1024.0)));
                break;
            case "iops":
                // COR-9: real Flexible-server IOPS. SQL DB/MI never reach here (not requested),
                // so their IOPS stays unset instead of holding a percentage.
                dto.setAvgReadIops(round2(value));
                break;
            default:
                break;
        }
    }

    /**
     * Resolve the ARM resource ID for a given DbInstanceDto.
     * Returns null if the resource ID cannot be determined.
     */
    String resolveResourceId(DbInstanceDto inst, String subscriptionId, AzureResourceManager azure, String token) {
        String id = inst.getInstanceId();
        if (id == null) return null;

        if (id.startsWith("mi/")) {
            return findManagedInstanceResourceId(subscriptionId, token, id.substring(3));
        }

        // COR-4: PostgreSQL / MySQL Flexible servers — resolve the ARM resource ID via the list
        // API (mirroring resolveFullResourceId) instead of returning null, which previously left
        // every Flexible server without metrics.
        if (id.startsWith("pg/")) {
            return findFlexibleServerResourceId(subscriptionId, token, id.substring(3),
                    "Microsoft.DBforPostgreSQL", "flexibleServers", "2023-06-01-preview");
        }
        if (id.startsWith("mysql/")) {
            return findFlexibleServerResourceId(subscriptionId, token, id.substring(6),
                    "Microsoft.DBforMySQL", "flexibleServers", "2023-06-30");
        }

        // Azure SQL Database: serverName/dbName
        try {
            return resolveResourceIdWithServers(inst, subscriptionId, listSqlServers(azure), token);
        } catch (Exception e) {
            log.debug("[AzureDbOps] Could not resolve SQL DB resource ID for {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Same as above with the subscription's SQL servers already listed, so a scan over many
     * databases lists the servers once instead of once per database.
     */
    String resolveResourceIdWithServers(DbInstanceDto inst, String subscriptionId, List<SqlServer> sqlServers,
                                        String token) {
        String id = inst.getInstanceId();
        if (id == null) return null;
        if (id.startsWith("mi/") || id.startsWith("pg/") || id.startsWith("mysql/")) {
            return resolveResourceId(inst, subscriptionId, (AzureResourceManager) null, token);
        }
        String[] parts = id.split("/", 2);
        if (parts.length < 2) return null;
        String serverName = parts[0];
        String dbName = parts[1];
        for (SqlServer server : sqlServers) {
            if (server.name().equalsIgnoreCase(serverName)) {
                return "/subscriptions/" + subscriptionId + "/resourceGroups/"
                        + server.resourceGroupName() + "/providers/Microsoft.Sql/servers/"
                        + serverName + "/databases/" + dbName;
            }
        }
        return null;
    }

    /** The subscription's SQL servers; empty when they cannot be listed (SQL DBs then get no metrics). */
    private List<SqlServer> listSqlServers(AzureResourceManager azure) {
        List<SqlServer> servers = new ArrayList<>();
        if (azure == null) {
            return servers;
        }
        try {
            azure.sqlServers().list().forEach(servers::add);
        } catch (Exception e) {
            log.debug("[AzureDbOps] Could not list SQL servers: {}", e.getMessage());
        }
        return servers;
    }

    // =========================================================================
    // PRIVATE — HTTP HELPERS FOR ARM REST API
    // =========================================================================

    private String getBearerToken(String subscriptionId) {
        TokenCredential credential = azureClientProvider.getCredential(subscriptionId);
        return credential.getToken(new TokenRequestContext().addScopes(ARM_SCOPE))
                .block()
                .getToken();
    }

    /**
     * Authenticated ARM GET, delegating to the shared {@link AzureArmClient}.
     *
     * <p>Previously created a fresh untimed {@link java.net.http.HttpClient} per call and
     * collapsed every non-2xx (and every exception) to {@code null}. {@code getQuietly}
     * preserves that "2xx body else null" contract — it returns the body on success and an
     * empty Optional on 404, on an authorization/throttle/server failure, or on a transport
     * error — while reusing the pooled, timeout-configured client.
     */
    private String armGet(String url, String token) {
        return armClient.getQuietly(url, token).orElse(null);
    }

    // =========================================================================
    // PRIVATE — HELPERS
    // =========================================================================

    /**
     * Month-to-date spend on the database services, from the ingested Cost Management summary.
     * The summary is in the billing currency; it is stored here in USD like every other DbOps
     * figure, and the controller converts the response back. Empty when there is no billing data.
     */
    List<DbCostDto> billedDbCosts(String subscriptionId) {
        if (billingIngestionService == null) {
            return Collections.emptyList();
        }
        try {
            List<AzureDashboardData.BillingSummary> lines =
                    billingIngestionService.readBillingSummary(subscriptionId).orElse(Collections.emptyList());
            AzureBillingCurrencyService.BillingCurrency billing = billingCurrencyService != null
                    ? billingCurrencyService.forSubscription(subscriptionId)
                    : AzureBillingCurrencyService.BillingCurrency.USD;
            Map<String, Double> byService = new LinkedHashMap<>();
            for (AzureDashboardData.BillingSummary line : lines) {
                if (line == null || !isDatabaseService(line.getService()) || line.getCost() <= 0) continue;
                byService.merge(line.getService(), billing.toUsd(line.getCost()), Double::sum);
            }
            double total = byService.values().stream().mapToDouble(Double::doubleValue).sum();
            List<DbCostDto> costs = new ArrayList<>();
            for (Map.Entry<String, Double> e : byService.entrySet()) {
                DbCostDto dto = new DbCostDto();
                dto.setService(e.getKey());
                dto.setRegion("");   // the summary is per service, not per region
                dto.setCost(round2(e.getValue()));
                dto.setPercentOfTotal(total > 0 ? round2(e.getValue() / total * 100.0) : 0.0);
                costs.add(dto);
            }
            costs.sort(Comparator.comparingDouble(DbCostDto::getCost).reversed());
            return costs;
        } catch (Exception e) {
            log.warn("[AzureDbOps] Billing summary unreadable for subscription {}: {}", subscriptionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Cost Management service names of the relational database services this page covers. */
    static boolean isDatabaseService(String service) {
        if (service == null) return false;
        return service.equalsIgnoreCase("SQL Database")
                || service.equalsIgnoreCase("SQL Managed Instance")
                || service.toLowerCase(Locale.ROOT).startsWith("azure database for");
    }

    private String engineToServiceName(String engine) {
        if (engine == null) return "Azure SQL";
        switch (engine) {
            case "sqlserver": return "Azure SQL";
            case "postgres":  return "Azure PostgreSQL";
            case "mysql":     return "Azure MySQL";
            default:          return "Azure SQL";
        }
    }

    private double estimateFlexibleCost(String skuName) {
        if (skuName == null) return 100.0;
        // Approximate monthly cost for common flexible server SKUs
        if (skuName.contains("D2"))  return 73.0;
        if (skuName.contains("D4"))  return 146.0;
        if (skuName.contains("D8"))  return 292.0;
        if (skuName.contains("D16")) return 584.0;
        if (skuName.contains("E2"))  return 91.0;
        if (skuName.contains("E4"))  return 182.0;
        if (skuName.contains("E8"))  return 364.0;
        return 100.0;
    }

    private int estimateMaxConnections(String skuName) {
        if (skuName == null) return 100;
        if (skuName.contains("2"))  return 200;
        if (skuName.contains("4"))  return 400;
        if (skuName.contains("8"))  return 800;
        if (skuName.contains("16")) return 1600;
        return 100;
    }

    private DbSecurityCheckDto makeCheck(DbInstanceDto inst, String checkName,
            String status, String severity, String details) {
        DbSecurityCheckDto dto = new DbSecurityCheckDto();
        dto.setInstanceId(inst.getInstanceId());
        dto.setRegion(inst.getRegion());
        dto.setCheckName(checkName);
        dto.setStatus(status);
        dto.setSeverity(severity);
        dto.setDetails(details);
        return dto;
    }

    private <T> List<T> readAgentData(String subscriptionId, String dataType, TypeReference<List<T>> typeRef) {
        try {
            CloudAccount account = getAccountBySubscriptionId(subscriptionId);
            if (account == null) return Collections.emptyList();
            List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(account.getId());
            List<T> result = new ArrayList<>();
            for (DbAgentConfig agent : agents) {
                dbAgentDataCacheRepository.findByDbAgentIdAndDataType(agent.getId(), dataType)
                        .ifPresent(cache -> {
                            try {
                                List<T> items = objectMapper.readValue(cache.getDataJson(), typeRef);
                                result.addAll(items);
                            } catch (Exception ex) {
                                log.warn("[AzureDbOps] Failed to parse agent data type {}: {}", dataType, ex.getMessage());
                            }
                        });
            }
            return result;
        } catch (Exception e) {
            log.error("[AzureDbOps] Agent data ({}) error for {}: {}", dataType, subscriptionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private CloudAccount getAccountBySubscriptionId(String subscriptionId) {
        return cloudAccountRepository.findByAzureSubscriptionId(subscriptionId).orElse(null);
    }

    /**
     * The DB snapshot, if it is younger than the cache's TTL. The fallback used to return a copy of
     * any age, so after a Redis flush a weeks-old inventory or metric set came back as current.
     */
    private <T> Optional<T> getFromDb(String cacheKey, TypeReference<T> typeRef, int ttlMinutes) {
        // saveToDb stamps lastUpdated in IST, so the cutoff is computed in IST too.
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusMinutes(ttlMinutes);
        return cachedDataRepository.findById(cacheKey)
                .filter(cached -> cached.getLastUpdated() != null && cached.getLastUpdated().isAfter(cutoff))
                .map(cached -> {
            try {
                return objectMapper.<T>readValue(cached.getJsonData(), typeRef);
            } catch (Exception e) {
                log.warn("[AzureDbOps] Failed to deserialize DB cache for key {}: {}", cacheKey, e.getMessage());
                return null;
            }
        });
    }

    private void saveToDb(String cacheKey, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            CachedData entity = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
            entity.setCacheKey(cacheKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
        } catch (Exception e) {
            log.warn("[AzureDbOps] Failed to save Azure DbOps DB snapshot for key {}: {}", cacheKey, e.getMessage());
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
