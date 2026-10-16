package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.sql.models.SqlDatabase;
import com.azure.resourcemanager.sql.models.SqlServer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentMetricsHistory;
import com.xammer.cloud.dto.dbops.*;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;

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

    public AzureDbOpsService(
            AzureClientProvider azureClientProvider,
            CloudAccountRepository cloudAccountRepository,
            RedisCacheService redisCache,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            DbAgentConfigRepository dbAgentConfigRepository,
            DbAgentDataCacheRepository dbAgentDataCacheRepository,
            DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository,
            AzureArmClient armClient) {
        this.azureClientProvider = azureClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.dbAgentConfigRepository = dbAgentConfigRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.dbAgentMetricsHistoryRepository = dbAgentMetricsHistoryRepository;
        this.armClient = armClient;
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
            Optional<List<DbInstanceDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                log.info("[AzureDbOps] DB cache hit — inventory for subscription {}", subscriptionId);
                redisCache.put(cacheKey, db.get(), INVENTORY_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            List<DbInstanceDto> instances = new ArrayList<>();
            try {
                AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
                String token = getBearerToken(subscriptionId);

                // 1. Azure SQL Databases (via SDK)
                collectSqlDatabases(azure, instances);

                // 2. Azure SQL Managed Instances (via REST API)
                collectSqlManagedInstances(subscriptionId, token, instances);

                // 3. PostgreSQL Flexible Servers (via REST API)
                collectPostgresFlexibleServers(subscriptionId, token, instances);

                // 4. MySQL Flexible Servers (via REST API)
                collectMysqlFlexibleServers(subscriptionId, token, instances);

                redisCache.put(cacheKey, instances, INVENTORY_TTL);
                saveToDb(cacheKey, instances);
                log.info("[AzureDbOps] Inventory fetched: {} instances for subscription {}", instances.size(), subscriptionId);
            } catch (Exception e) {
                log.error("[AzureDbOps] Inventory error for subscription {}: {}", subscriptionId, e.getMessage());
            }
            return instances;
        });
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
            Optional<Map<String, DbMetricsDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                log.info("[AzureDbOps] DB cache hit — metrics for subscription {}", subscriptionId);
                redisCache.put(cacheKey, db.get(), METRICS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return getDbInventory(subscriptionId, false).thenApply(instances -> {
            if (instances.isEmpty()) return Collections.<String, DbMetricsDto>emptyMap();
            Map<String, DbMetricsDto> metricsMap = new HashMap<>();
            try {
                String token = getBearerToken(subscriptionId);
                AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);

                // Pre-populate empty DTOs
                for (DbInstanceDto inst : instances) {
                    DbMetricsDto dto = new DbMetricsDto();
                    dto.setInstanceId(inst.getInstanceId());
                    dto.setRegion(inst.getRegion());
                    metricsMap.put(inst.getInstanceId(), dto);
                }

                // Fetch metrics per instance via Azure Monitor REST API
                for (DbInstanceDto inst : instances) {
                    try {
                        String resourceId = resolveResourceId(inst, subscriptionId, azure, token);
                        if (resourceId == null) continue;
                        fetchAzureMonitorMetrics(resourceId, token, metricsMap.get(inst.getInstanceId()), inst.getEngine());
                    } catch (Exception ex) {
                        log.debug("[AzureDbOps] Metrics fetch skipped for {}: {}", inst.getInstanceId(), ex.getMessage());
                    }
                }

                redisCache.put(cacheKey, metricsMap, METRICS_TTL);
                saveToDb(cacheKey, metricsMap);
                log.info("[AzureDbOps] Metrics fetched for {} instances, subscription {}", metricsMap.size(), subscriptionId);
            } catch (Exception e) {
                log.error("[AzureDbOps] Metrics error for subscription {}: {}", subscriptionId, e.getMessage());
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
            Optional<List<DbCostDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                log.info("[AzureDbOps] DB cache hit — costs for subscription {}", subscriptionId);
                redisCache.put(cacheKey, db.get(), COSTS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return getDbInventory(subscriptionId, false).thenApply(instances -> {
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
            Optional<List<DbSnapshotWasteDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                log.info("[AzureDbOps] DB cache hit — backups for subscription {}", subscriptionId);
                redisCache.put(cacheKey, db.get(), BACKUPS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return getDbInventory(subscriptionId, false).thenApply(instances -> {
            List<DbSnapshotWasteDto> wasteList = new ArrayList<>();
            try {
                AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
                Instant cutoff = Instant.now().minus(BACKUP_WASTE_AGE_DAYS, ChronoUnit.DAYS);

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
                        azure.sqlServers().list().forEach(server -> {
                            if (!server.name().equalsIgnoreCase(serverName)) return;
                            server.databases().list().forEach(database -> {
                                if (!database.name().equalsIgnoreCase(dbName)) return;
                                try {
                                    database.listRestorePoints().forEach(rp -> {
                                        try {
                                            Instant rpTime = rp.earliestRestoreDate() != null
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
                                                    ? "Safe to delete — restore point over 90 days old"
                                                    : "Review — restore point created " + ageDays + " days ago");
                                            wasteList.add(dto);
                                        } catch (Exception ex) {
                                            log.debug("[AzureDbOps] Restore point parse error: {}", ex.getMessage());
                                        }
                                    });
                                } catch (Exception ex) {
                                    log.debug("[AzureDbOps] Restore points list error for {}/{}: {}", serverName, dbName, ex.getMessage());
                                }
                            });
                        });
                    } catch (Exception ex) {
                        log.debug("[AzureDbOps] Backup scan error for {}: {}", inst.getInstanceId(), ex.getMessage());
                    }
                }

                wasteList.sort(Comparator.comparingDouble(DbSnapshotWasteDto::getEstimatedMonthlyCost).reversed());
                redisCache.put(cacheKey, wasteList, BACKUPS_TTL);
                saveToDb(cacheKey, wasteList);
                log.info("[AzureDbOps] Backups fetched: {} waste entries for subscription {}", wasteList.size(), subscriptionId);
            } catch (Exception e) {
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

                // TLS minimum version (stored in storageType field repurposed as tlsMinVersion for Azure)
                boolean tlsOk = inst.getStorageType() != null && inst.getStorageType().contains("TLS1_2");
                checks.add(makeCheck(inst, "Minimum TLS Version",
                        tlsOk ? "PASS" : "WARN",
                        tlsOk ? "INFO" : "MEDIUM",
                        tlsOk ? "Minimum TLS 1.2 is enforced"
                               : "Minimum TLS version may not be set to 1.2 — verify server TLS policy"));
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
        CompletableFuture<List<DbIdleInstanceDto>> idleFuture  = getIdleInstances(subscriptionId, forceRefresh);
        CompletableFuture<List<DbRightsizingDto>>  rightFuture = getRightsizingRecommendations(subscriptionId, forceRefresh);
        CompletableFuture<List<DbSnapshotWasteDto>> snapFuture = getDbSnapshots(subscriptionId, false);

        return CompletableFuture.allOf(invFuture, idleFuture, rightFuture, snapFuture).thenApply(v -> {
            List<DbInstanceDto>     inventory = invFuture.join();
            List<DbIdleInstanceDto> idle      = idleFuture.join();
            List<DbRightsizingDto>  right     = rightFuture.join();
            List<DbSnapshotWasteDto> snaps    = snapFuture.join();

            double totalCost        = inventory.stream().mapToDouble(DbInstanceDto::getEstimatedMonthlyCost).sum();
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
        DbInstanceTimeseriesDto dto = new DbInstanceTimeseriesDto();
        dto.setInstanceId(instanceId);
        try {
            String token = getBearerToken(subscriptionId);
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            String resourceId = resolveFullResourceId(subscriptionId, instanceId, azure, token);
            if (resourceId != null) {
                fetchTimeseriesFromMonitor(resourceId, token, dto, engineFromInstanceId(instanceId));
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

    private void fetchTimeseriesFromMonitor(String resourceId, String token,
                                             DbInstanceTimeseriesDto dto, String engine) {
        try {
            String metricNames = resolveMetricNames(engine);
            String url = ARM_BASE + resourceId
                    + "/providers/Microsoft.Insights/metrics"
                    + "?metricnames=" + metricNames
                    + "&timespan=PT24H"
                    + "&interval=PT1H"
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
                applyTimeseriesMetric(dto, metricName, points);
            }
        } catch (Exception e) {
            log.debug("[AzureDbOps] fetchTimeseriesFromMonitor error for {}: {}", resourceId, e.getMessage());
        }
    }

    private void applyTimeseriesMetric(DbInstanceTimeseriesDto dto, String metricName,
                                        List<DbInstanceTimeseriesDto.Point> points) {
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
                // bytes → GB
                List<DbInstanceTimeseriesDto.Point> storageGb = new ArrayList<>();
                for (DbInstanceTimeseriesDto.Point p : points) {
                    storageGb.add(new DbInstanceTimeseriesDto.Point(p.getTs(),
                            round2(p.getV() / (1024.0 * 1024.0 * 1024.0))));
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
            case "physical_data_read_percent":
                dto.setReadIops(points);
                break;
            case "log_write_percent":
                dto.setWriteIops(points);
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
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
                                    result.addAll(stats);
                                } catch (Exception ex) {
                                    log.warn("[AzureDbOps] Failed to parse agent query stats: {}", ex.getMessage());
                                }
                            });
                }
                return result;
            } catch (Exception e) {
                log.error("[AzureDbOps] Agent query stats error for {}: {}", subscriptionId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentTableSizeDto>> getAgentTableSizes(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> readAgentData(subscriptionId, "db-table-sizes",
                new TypeReference<List<AgentTableSizeDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentIndexUsageDto>> getAgentIndexUsage(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> readAgentData(subscriptionId, "db-index-usage",
                new TypeReference<List<AgentIndexUsageDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentTableActivityDto>> getAgentTableActivity(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> readAgentData(subscriptionId, "db-table-activity",
                new TypeReference<List<AgentTableActivityDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentVacuumStatDto>> getAgentVacuumStats(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> readAgentData(subscriptionId, "db-vacuum-stats",
                new TypeReference<List<AgentVacuumStatDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentUserActivityDto>> getAgentUserActivity(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> readAgentData(subscriptionId, "db-user-activity",
                new TypeReference<List<AgentUserActivityDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentTimeseriesDto>> getAgentTimeseries(String subscriptionId, int hours) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CloudAccount account = getAccountBySubscriptionId(subscriptionId);
                if (account == null) return Collections.emptyList();
                List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(account.getId());
                if (agents.isEmpty()) return Collections.emptyList();

                java.time.LocalDateTime since = java.time.LocalDateTime.now().minusHours(hours);
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

    private void collectSqlDatabases(AzureResourceManager azure, List<DbInstanceDto> instances) {
        try {
            azure.sqlServers().list().forEach(server -> {
                try {
                    server.databases().list().forEach(db -> {
                        try {
                            // Skip master/system databases
                            if ("master".equalsIgnoreCase(db.name())) return;
                            instances.add(toSqlDatabaseDto(server, db));
                        } catch (Exception ex) {
                            log.debug("[AzureDbOps] SQL DB dto error for {}/{}: {}", server.name(), db.name(), ex.getMessage());
                        }
                    });
                } catch (Exception ex) {
                    log.debug("[AzureDbOps] SQL DB list error for server {}: {}", server.name(), ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[AzureDbOps] collectSqlDatabases error: {}", e.getMessage());
        }
    }

    private DbInstanceDto toSqlDatabaseDto(SqlServer server, SqlDatabase db) {
        DbInstanceDto dto = new DbInstanceDto();
        dto.setInstanceId(server.name() + "/" + db.name());
        String skuName = db.currentServiceObjectiveName() != null ? db.currentServiceObjectiveName() : "GP_Gen5_2";
        dto.setInstanceClass(skuName);
        dto.setEngine("sqlserver");
        dto.setEngineVersion("SQL Server"); // Azure SQL Database doesn't expose a specific version string via SDK
        dto.setStatus(db.status() != null ? db.status().toString().toLowerCase() : "unknown");
        dto.setRegion(server.regionName() != null ? server.regionName() : "unknown");
        dto.setMultiAz("None");
        // TLS min version stored in storageType field for security checks
        dto.setStorageType("TLS1_2"); // Azure SQL enforces TLS 1.2 by default
        dto.setAllocatedStorage(db.maxSizeBytes() > 0 ? (int) (db.maxSizeBytes() / (1024L * 1024L * 1024L)) : 32);
        dto.setPubliclyAccessible(server.getActiveDirectoryAdministrator() != null
                || serverHasPublicAccess(server));
        dto.setStorageEncrypted(true); // Azure TDE is on by default
        int retentionDays = 7; // Azure SQL default backup retention
        dto.setBackupEnabled(retentionDays > 0);
        dto.setBackupRetentionDays(String.valueOf(retentionDays));
        dto.setEndpoint(server.fullyQualifiedDomainName() != null ? server.fullyQualifiedDomainName() : "");
        dto.setEstimatedMonthlyCost(SKU_COST_MAP.getOrDefault(skuName, 100.0));
        dto.setMaxConnections(estimateMaxConnections(skuName));
        return dto;
    }

    private boolean serverHasPublicAccess(SqlServer server) {
        try {
            // If a server has any firewall rules including AllowAllAzureIPs, treat as public
            return server.firewallRules().list().iterator().hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    private void collectSqlManagedInstances(String subscriptionId, String token, List<DbInstanceDto> instances) {
        try {
            String url = ARM_BASE + "/subscriptions/" + subscriptionId
                    + "/providers/Microsoft.Sql/managedInstances?api-version=2021-11-01-preview";
            String body = armGet(url, token);
            if (body == null) return;
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode node : root.path("value")) {
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
                    dto.setMultiAz("None");
                    dto.setStorageType("TLS1_2");
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
        } catch (Exception e) {
            log.warn("[AzureDbOps] collectSqlManagedInstances error: {}", e.getMessage());
        }
    }

    private void collectPostgresFlexibleServers(String subscriptionId, String token,
            List<DbInstanceDto> instances) {
        try {
            String url = ARM_BASE + "/subscriptions/" + subscriptionId
                    + "/providers/Microsoft.DBforPostgreSQL/flexibleServers?api-version=2023-06-01-preview";
            String responseBody = armGet(url, token);
            if (responseBody == null) return;

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode valueArr = root.path("value");
            if (!valueArr.isArray()) return;

            for (JsonNode node : valueArr) {
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
                    String minTls = props.path("network").path("publicNetworkAccess").asText("Enabled");
                    dto.setStorageType("TLS1_2"); // PostgreSQL flexible enforces TLS by default
                    int storageGb = props.path("storage").path("storageSizeGB").asInt(32);
                    dto.setAllocatedStorage(storageGb);
                    boolean publicAccess = "Enabled".equalsIgnoreCase(minTls);
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
        } catch (Exception e) {
            log.warn("[AzureDbOps] collectPostgresFlexibleServers error: {}", e.getMessage());
        }
    }

    private void collectMysqlFlexibleServers(String subscriptionId, String token,
            List<DbInstanceDto> instances) {
        try {
            String url = ARM_BASE + "/subscriptions/" + subscriptionId
                    + "/providers/Microsoft.DBforMySQL/flexibleServers?api-version=2023-06-30";
            String responseBody = armGet(url, token);
            if (responseBody == null) return;

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode valueArr = root.path("value");
            if (!valueArr.isArray()) return;

            for (JsonNode node : valueArr) {
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
                    dto.setStorageType("TLS1_2");
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
        } catch (Exception e) {
            log.warn("[AzureDbOps] collectMysqlFlexibleServers error: {}", e.getMessage());
        }
    }

    // =========================================================================
    // PRIVATE — AZURE MONITOR METRICS
    // =========================================================================

    private void fetchAzureMonitorMetrics(String resourceId, String token, DbMetricsDto dto, String engine) {
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
            if (responseBody == null) return;

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode valueArr = root.path("value");
            if (!valueArr.isArray()) return;

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
                double avgValue = count > 0 ? sum / count : 0.0;
                applyAzureMetric(dto, metricName, avgValue);
            }
        } catch (Exception e) {
            log.debug("[AzureDbOps] Azure Monitor metrics error for resource {}: {}", resourceId, e.getMessage());
        }
    }

    private String resolveMetricNames(String engine) {
        if ("postgres".equals(engine) || "mysql".equals(engine)) {
            return "cpu_percent,active_connections,storage_used,network_bytes_ingress,network_bytes_egress";
        }
        // SQL Server / MI
        return "cpu_percent,connection_successful,storage,physical_data_read_percent,log_write_percent";
    }

    private void applyAzureMetric(DbMetricsDto dto, String metricName, double value) {
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
                // storage in bytes → GB free (estimate based on allocated)
                dto.setFreeStorageSpaceGb(round2(value / (1024.0 * 1024.0 * 1024.0)));
                break;
            case "network_bytes_ingress":
                dto.setAvgNetworkReceiveMbps(round2(value / (1024.0 * 1024.0)));
                break;
            case "network_bytes_egress":
                dto.setAvgNetworkTransmitMbps(round2(value / (1024.0 * 1024.0)));
                break;
            case "physical_data_read_percent":
                dto.setAvgReadIops(round2(value));
                break;
            case "log_write_percent":
                dto.setAvgWriteIops(round2(value));
                break;
            default:
                break;
        }
    }

    /**
     * Resolve the ARM resource ID for a given DbInstanceDto.
     * Returns null if the resource ID cannot be determined.
     */
    private String resolveResourceId(DbInstanceDto inst, String subscriptionId, AzureResourceManager azure, String token) {
        String id = inst.getInstanceId();
        if (id == null) return null;

        if (id.startsWith("mi/")) {
            return findManagedInstanceResourceId(subscriptionId, token, id.substring(3));
        }

        if (id.startsWith("pg/") || id.startsWith("mysql/")) {
            return null;
        }

        // Azure SQL Database: serverName/dbName
        String[] parts = id.split("/", 2);
        if (parts.length < 2) return null;
        String serverName = parts[0];
        String dbName = parts[1];
        try {
            for (SqlServer server : azure.sqlServers().list()) {
                if (server.name().equalsIgnoreCase(serverName)) {
                    return "/subscriptions/" + subscriptionId + "/resourceGroups/"
                            + server.resourceGroupName() + "/providers/Microsoft.Sql/servers/"
                            + serverName + "/databases/" + dbName;
                }
            }
        } catch (Exception e) {
            log.debug("[AzureDbOps] Could not resolve SQL DB resource ID for {}: {}", id, e.getMessage());
        }
        return null;
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

    private <T> Optional<T> getFromDb(String cacheKey, TypeReference<T> typeRef) {
        return cachedDataRepository.findById(cacheKey).map(cached -> {
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
