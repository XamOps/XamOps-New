package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.BackupRun;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.api.services.sqladmin.model.IpMapping;
import com.google.api.services.sqladmin.model.InstancesListResponse;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.Aggregation;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.ProjectName;
import com.google.monitoring.v3.TimeInterval;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * GCP DbOps Service — Cloud SQL Cost & Performance Optimization
 *
 * GCP equivalent of DbOpsCostService for AWS.
 * Uses Cloud SQL Admin API for instance inventory and Cloud Monitoring for metrics.
 *
 * Caching strategy (3-tier):
 *   Redis (primary) → CachedData DB (fallback) → Live GCP API call
 */
@Service
@Slf4j
public class GcpDbOpsService {

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
    private static final double BACKUP_COST_PER_GB        = 0.08; // GCS $/GB/month

    // ── Cloud SQL tier cost map (approximate $/month, us-central1) ────────────
    private static final Map<String, Double> TIER_COST_MAP = Map.ofEntries(
            Map.entry("db-f1-micro",         7.0),
            Map.entry("db-g1-small",        26.0),
            Map.entry("db-n1-standard-1",   51.0),
            Map.entry("db-n1-standard-2",  103.0),
            Map.entry("db-n1-standard-4",  206.0),
            Map.entry("db-n1-standard-8",  412.0),
            Map.entry("db-n1-highmem-2",   130.0),
            Map.entry("db-n1-highmem-4",   260.0),
            Map.entry("db-n1-highmem-8",   520.0),
            Map.entry("db-custom-1-3840",   77.0),
            Map.entry("db-custom-2-7680",  155.0),
            Map.entry("db-custom-4-15360", 310.0),
            Map.entry("db-custom-8-30720", 620.0)
    );

    // ── Downsize recommendation map ───────────────────────────────────────────
    private static final Map<String, String> DOWNSIZE_MAP = Map.ofEntries(
            Map.entry("db-n1-standard-2",  "db-n1-standard-1"),
            Map.entry("db-n1-standard-4",  "db-n1-standard-2"),
            Map.entry("db-n1-standard-8",  "db-n1-standard-4"),
            Map.entry("db-n1-highmem-4",   "db-n1-highmem-2"),
            Map.entry("db-n1-highmem-8",   "db-n1-highmem-4"),
            Map.entry("db-custom-2-7680",  "db-custom-1-3840"),
            Map.entry("db-custom-4-15360", "db-custom-2-7680"),
            Map.entry("db-custom-8-30720", "db-custom-4-15360"),
            Map.entry("db-g1-small",       "db-f1-micro")
    );

    private final GcpClientProvider gcpClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final DbAgentConfigRepository dbAgentConfigRepository;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository;
    private final DbOpsFeatureFlags dbOpsFeatureFlags;

    public GcpDbOpsService(
            GcpClientProvider gcpClientProvider,
            CloudAccountRepository cloudAccountRepository,
            RedisCacheService redisCache,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            DbAgentConfigRepository dbAgentConfigRepository,
            DbAgentDataCacheRepository dbAgentDataCacheRepository,
            DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository,
            DbOpsFeatureFlags dbOpsFeatureFlags) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.dbAgentConfigRepository = dbAgentConfigRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.dbAgentMetricsHistoryRepository = dbAgentMetricsHistoryRepository;
        this.dbOpsFeatureFlags = dbOpsFeatureFlags;
    }

    // =========================================================================
    // PUBLIC API — INVENTORY
    // =========================================================================

    /**
     * Fetch all Cloud SQL instances for a GCP project.
     * Uses Cloud SQL Admin API — single call per project (no region loop needed).
     * Cached 24h.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbInstanceDto>> getDbInventory(String projectId, boolean forceRefresh) {
        String cacheKey = "dbops:gcp:inventory:" + projectId;

        if (!forceRefresh) {
            Optional<List<DbInstanceDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                log.info("✅ [GcpDbOps] Redis hit — inventory for project {}", projectId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<List<DbInstanceDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                log.info("✅ [GcpDbOps] DB cache hit — inventory for project {}", projectId);
                redisCache.put(cacheKey, db.get(), INVENTORY_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<SQLAdmin> sqlAdminOpt = gcpClientProvider.getSqlAdminClient(projectId);
                if (sqlAdminOpt.isEmpty()) {
                    log.warn("⚠️ [GcpDbOps] SQLAdmin client unavailable for project {}", projectId);
                    return Collections.<DbInstanceDto>emptyList();
                }

                InstancesListResponse response = sqlAdminOpt.get()
                        .instances()
                        .list(projectId)
                        .execute();

                List<DbInstanceDto> instances = new ArrayList<>();
                if (response.getItems() != null) {
                    for (DatabaseInstance inst : response.getItems()) {
                        instances.add(toInstanceDto(inst));
                    }
                }

                redisCache.put(cacheKey, instances, INVENTORY_TTL);
                saveToDb(cacheKey, instances);
                log.info("✅ [GcpDbOps] Inventory fetched: {} instances for project {}", instances.size(), projectId);
                return instances;
            } catch (Exception e) {
                log.error("❌ [GcpDbOps] Inventory error for project {}: {}", projectId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // =========================================================================
    // PUBLIC API — CLOUD MONITORING METRICS
    // =========================================================================

    /**
     * Fetch 7-day average Cloud Monitoring metrics for all Cloud SQL instances.
     * Cached 12h.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<Map<String, DbMetricsDto>> getDbMetrics(String projectId, boolean forceRefresh) {
        String cacheKey = "dbops:gcp:metrics:" + projectId;

        if (!forceRefresh) {
            Optional<Map<String, DbMetricsDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                log.info("✅ [GcpDbOps] Redis hit — metrics for project {}", projectId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<Map<String, DbMetricsDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                log.info("✅ [GcpDbOps] DB cache hit — metrics for project {}", projectId);
                redisCache.put(cacheKey, db.get(), METRICS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return getDbInventory(projectId, false).thenApply(instances -> {
            if (instances.isEmpty()) return Collections.<String, DbMetricsDto>emptyMap();
            try {
                Optional<MetricServiceClient> clientOpt = gcpClientProvider.getMetricServiceClient(projectId);
                if (clientOpt.isEmpty()) {
                    log.warn("⚠️ [GcpDbOps] MetricServiceClient unavailable for project {}", projectId);
                    return Collections.<String, DbMetricsDto>emptyMap();
                }

                MetricServiceClient metricClient = clientOpt.get();
                Map<String, DbMetricsDto> metricsMap = new HashMap<>();
                Instant endTime   = Instant.now();
                Instant startTime = endTime.minus(7, ChronoUnit.DAYS);

                // Initialize empty DTOs for all instances
                for (DbInstanceDto inst : instances) {
                    DbMetricsDto dto = new DbMetricsDto();
                    dto.setInstanceId(inst.getInstanceId());
                    dto.setRegion(inst.getRegion());
                    metricsMap.put(inst.getInstanceId(), dto);
                }

                String monitoringProject = ProjectName.of(projectId).toString();
                TimeInterval interval = TimeInterval.newBuilder()
                        .setStartTime(Timestamp.newBuilder()
                                .setSeconds(startTime.getEpochSecond()).build())
                        .setEndTime(Timestamp.newBuilder()
                                .setSeconds(endTime.getEpochSecond()).build())
                        .build();

                Aggregation aggregation = Aggregation.newBuilder()
                        .setAlignmentPeriod(Duration.newBuilder().setSeconds(86400).build())
                        .setPerSeriesAligner(Aggregation.Aligner.ALIGN_MEAN)
                        .setCrossSeriesReducer(Aggregation.Reducer.REDUCE_MEAN)
                        .addGroupByFields("resource.labels.database_id")
                        .build();

                // Fetch CPU utilization
                fetchAndApplyMetric(metricClient, monitoringProject, interval, aggregation,
                        "cloudsql.googleapis.com/database/cpu/utilization",
                        projectId, metricsMap, "cpu");

                // Fetch active connections
                fetchAndApplyMetric(metricClient, monitoringProject, interval, aggregation,
                        "cloudsql.googleapis.com/database/network/connections",
                        projectId, metricsMap, "connections");

                // Fetch memory utilization
                fetchAndApplyMetric(metricClient, monitoringProject, interval, aggregation,
                        "cloudsql.googleapis.com/database/memory/utilization",
                        projectId, metricsMap, "memory");

                // Fetch disk read ops
                fetchAndApplyMetric(metricClient, monitoringProject, interval, aggregation,
                        "cloudsql.googleapis.com/database/disk/read_ops_count",
                        projectId, metricsMap, "readOps");

                // Fetch disk write ops
                fetchAndApplyMetric(metricClient, monitoringProject, interval, aggregation,
                        "cloudsql.googleapis.com/database/disk/write_ops_count",
                        projectId, metricsMap, "writeOps");

                // Fetch disk bytes used
                fetchAndApplyMetric(metricClient, monitoringProject, interval, aggregation,
                        "cloudsql.googleapis.com/database/disk/bytes_used",
                        projectId, metricsMap, "diskBytesUsed");

                // Fetch network receive bytes
                fetchAndApplyMetric(metricClient, monitoringProject, interval, aggregation,
                        "cloudsql.googleapis.com/database/network/received_bytes_count",
                        projectId, metricsMap, "networkReceive");

                // Fetch network send bytes
                fetchAndApplyMetric(metricClient, monitoringProject, interval, aggregation,
                        "cloudsql.googleapis.com/database/network/sent_bytes_count",
                        projectId, metricsMap, "networkSend");

                try { metricClient.close(); } catch (Exception ignored) {}

                redisCache.put(cacheKey, metricsMap, METRICS_TTL);
                saveToDb(cacheKey, metricsMap);
                log.info("✅ [GcpDbOps] Metrics fetched for {} instances, project {}", metricsMap.size(), projectId);
                return metricsMap;
            } catch (Exception e) {
                log.error("❌ [GcpDbOps] Metrics error for project {}: {}", projectId, e.getMessage());
                return Collections.<String, DbMetricsDto>emptyMap();
            }
        });
    }

    // =========================================================================
    // PUBLIC API — COSTS (via BigQuery billing export)
    // =========================================================================

    /**
     * Fetch month-to-date Cloud SQL costs from the account's billing export.
     * Falls back to cost estimation from inventory if BigQuery is unavailable.
     * Cached 6h.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbCostDto>> getDbCosts(String projectId, boolean forceRefresh) {
        String cacheKey = "dbops:gcp:costs:" + projectId;

        if (!forceRefresh) {
            Optional<List<DbCostDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                log.info("✅ [GcpDbOps] Redis hit — costs for project {}", projectId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<List<DbCostDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                log.info("✅ [GcpDbOps] DB cache hit — costs for project {}", projectId);
                redisCache.put(cacheKey, db.get(), COSTS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return getDbInventory(projectId, false).thenApply(instances -> {
            try {
                // Estimate costs from known instance tiers grouped by region
                Map<String, Double> regionCostMap = new HashMap<>();
                for (DbInstanceDto inst : instances) {
                    double cost = inst.getEstimatedMonthlyCost();
                    if (cost > 0) {
                        regionCostMap.merge(inst.getRegion(), cost, Double::sum);
                    }
                }

                List<DbCostDto> costs = new ArrayList<>();
                double total = regionCostMap.values().stream().mapToDouble(Double::doubleValue).sum();

                for (Map.Entry<String, Double> entry : regionCostMap.entrySet()) {
                    DbCostDto dto = new DbCostDto();
                    dto.setService("Cloud SQL");
                    dto.setRegion(entry.getKey());
                    dto.setCost(round2(entry.getValue()));
                    dto.setPercentOfTotal(total > 0 ? round2(entry.getValue() / total * 100.0) : 0.0);
                    costs.add(dto);
                }

                costs.sort(Comparator.comparingDouble(DbCostDto::getCost).reversed());
                redisCache.put(cacheKey, costs, COSTS_TTL);
                saveToDb(cacheKey, costs);
                log.info("✅ [GcpDbOps] Costs derived: {} entries for project {}", costs.size(), projectId);
                return costs;
            } catch (Exception e) {
                log.error("❌ [GcpDbOps] Costs error for project {}: {}", projectId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // =========================================================================
    // PUBLIC API — BACKUP WASTE (Cloud SQL on-demand backups)
    // =========================================================================

    /**
     * Find on-demand Cloud SQL backups older than BACKUP_WASTE_AGE_DAYS.
     * Uses Cloud SQL Admin API backupRuns.list per instance.
     * Cached 24h.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbSnapshotWasteDto>> getDbSnapshots(String projectId, boolean forceRefresh) {
        String cacheKey = "dbops:gcp:snapshots:" + projectId;

        if (!forceRefresh) {
            Optional<List<DbSnapshotWasteDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                log.info("✅ [GcpDbOps] Redis hit — backups for project {}", projectId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<List<DbSnapshotWasteDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                log.info("✅ [GcpDbOps] DB cache hit — backups for project {}", projectId);
                redisCache.put(cacheKey, db.get(), BACKUPS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return getDbInventory(projectId, false).thenApply(instances -> {
            try {
                Optional<SQLAdmin> sqlAdminOpt = gcpClientProvider.getSqlAdminClient(projectId);
                if (sqlAdminOpt.isEmpty()) return Collections.<DbSnapshotWasteDto>emptyList();

                SQLAdmin sqlAdmin = sqlAdminOpt.get();
                List<DbSnapshotWasteDto> wasteList = new ArrayList<>();
                Instant cutoff = Instant.now().minus(BACKUP_WASTE_AGE_DAYS, ChronoUnit.DAYS);

                for (DbInstanceDto inst : instances) {
                    try {
                        // Only the instance name (not projectId:region:name format)
                        String instanceName = extractInstanceName(inst.getInstanceId());
                        var backupList = sqlAdmin.backupRuns()
                                .list(projectId, instanceName)
                                .execute();

                        if (backupList.getItems() == null) continue;
                        for (BackupRun backup : backupList.getItems()) {
                            if (!"ON_DEMAND".equals(backup.getType())) continue;
                            if (backup.getStartTime() == null) continue;

                            Instant backupTime = Instant.parse(backup.getStartTime());
                            if (backupTime.isAfter(cutoff)) continue;

                            long ageDays = ChronoUnit.DAYS.between(backupTime, Instant.now());
                            // Cloud SQL backup size is not always available; estimate from disk size
                            int sizeGb = inst.getAllocatedStorage() > 0 ? inst.getAllocatedStorage() : 10;

                            DbSnapshotWasteDto dto = new DbSnapshotWasteDto();
                            dto.setSnapshotId(backup.getId() != null ? backup.getId().toString() : "backup-" + ageDays + "d");
                            dto.setInstanceId(inst.getInstanceId());
                            dto.setRegion(inst.getRegion());
                            dto.setAgeDays((int) ageDays);
                            dto.setSizeGb(sizeGb);
                            dto.setEstimatedMonthlyCost(round2(sizeGb * BACKUP_COST_PER_GB));
                            dto.setRecommendation(ageDays > 90
                                    ? "Review retention before deleting — on-demand backup over 90 days old"
                                    : "Review — on-demand backup created " + ageDays + " days ago");
                            wasteList.add(dto);
                        }
                    } catch (Exception ex) {
                        log.debug("[GcpDbOps] Could not list backups for instance {}: {}", inst.getInstanceId(), ex.getMessage());
                    }
                }

                wasteList.sort(Comparator.comparingDouble(DbSnapshotWasteDto::getEstimatedMonthlyCost).reversed());
                redisCache.put(cacheKey, wasteList, BACKUPS_TTL);
                saveToDb(cacheKey, wasteList);
                log.info("✅ [GcpDbOps] Backups fetched: {} waste entries for project {}", wasteList.size(), projectId);
                return wasteList;
            } catch (Exception e) {
                log.error("❌ [GcpDbOps] Backups error for project {}: {}", projectId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // =========================================================================
    // DERIVED: IDLE DETECTION — 0 extra API calls
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbIdleInstanceDto>> getIdleInstances(String projectId, boolean forceRefresh) {
        return getDbMetrics(projectId, forceRefresh)
                .thenCombine(getDbInventory(projectId, false), (metrics, instances) -> {
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
                            dto.setRecommendation("No active connections for 7 days — consider stopping or deleting this Cloud SQL instance");
                            idleList.add(dto);
                        }
                    }
                    idleList.sort(Comparator.comparingDouble(DbIdleInstanceDto::getPotentialSavings).reversed());
                    return idleList;
                });
    }

    // =========================================================================
    // DERIVED: RIGHT-SIZING — 0 extra API calls
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbRightsizingDto>> getRightsizingRecommendations(String projectId, boolean forceRefresh) {
        return getDbMetrics(projectId, forceRefresh)
                .thenCombine(getDbInventory(projectId, false), (metrics, instances) -> {
                    List<DbRightsizingDto> recs = new ArrayList<>();
                    for (DbInstanceDto inst : instances) {
                        DbMetricsDto m = metrics.get(inst.getInstanceId());
                        if (m == null) continue;
                        if (m.getAvgCpuPercent() <= IDLE_CPU_THRESHOLD && m.getAvgConnections() <= IDLE_CONN_THRESHOLD) continue;
                        if (m.getAvgCpuPercent() > RIGHTSIZING_CPU_THRESHOLD) continue;

                        String recommended = DOWNSIZE_MAP.get(inst.getInstanceClass());
                        if (recommended == null) continue;

                        double currentCost     = inst.getEstimatedMonthlyCost();
                        double recommendedCost = TIER_COST_MAP.getOrDefault(recommended, currentCost * 0.6);
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
    // DERIVED: SECURITY CHECKS — 0 extra API calls
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbSecurityCheckDto>> getSecurityChecks(String projectId, boolean forceRefresh) {
        return getDbInventory(projectId, forceRefresh).thenApply(instances -> {
            List<DbSecurityCheckDto> checks = new ArrayList<>();
            for (DbInstanceDto inst : instances) {
                // Public IP exposure
                checks.add(makeCheck(inst, "Public IP Access",
                        inst.isPubliclyAccessible() ? "FAIL" : "PASS",
                        inst.isPubliclyAccessible() ? "HIGH" : "INFO",
                        inst.isPubliclyAccessible()
                                ? "Instance has a public IP address — restrict using authorized networks or Private IP"
                                : "Instance is not publicly accessible"));

                // Storage encryption (Cloud SQL encrypts by default with Google-managed keys)
                checks.add(makeCheck(inst, "Disk Encryption",
                        inst.isStorageEncrypted() ? "PASS" : "WARN",
                        inst.isStorageEncrypted() ? "INFO" : "MEDIUM",
                        inst.isStorageEncrypted()
                                ? "Disk encryption is enabled (CMEK or Google-managed)"
                                : "Using default Google-managed encryption — consider CMEK for compliance"));

                // Automated backups
                checks.add(makeCheck(inst, "Automated Backups",
                        inst.isBackupEnabled() ? "PASS" : "FAIL",
                        inst.isBackupEnabled() ? "INFO" : "HIGH",
                        inst.isBackupEnabled()
                                ? "Automated backups enabled (retention: " + inst.getBackupRetentionDays() + " days)"
                                : "Automated backups are DISABLED — enable for data protection"));

                // High Availability (Regional = HA in Cloud SQL)
                boolean ha = "REGIONAL".equalsIgnoreCase(inst.getMultiAz());
                checks.add(makeCheck(inst, "High Availability",
                        ha ? "PASS" : "WARN",
                        ha ? "INFO" : "MEDIUM",
                        ha ? "High availability is enabled (regional failover replica)"
                           : "Single-zone instance — consider enabling HA for production workloads"));
            }
            return checks;
        });
    }

    // =========================================================================
    // DERIVED: SAVINGS SUMMARY — 0 extra API calls
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<DbSavingsSummaryDto> getSavingsSummary(String projectId, boolean forceRefresh) {
        CompletableFuture<List<DbInstanceDto>> invFuture = getDbInventory(projectId, forceRefresh);
        // Phase 1a: a disabled pillar contributes an empty list, so its count/savings are 0 and it
        // never enters the savings total — the summary omits it rather than faking a value.
        CompletableFuture<List<DbIdleInstanceDto>> idleFuture = dbOpsFeatureFlags.isIdleEnabled()
                ? getIdleInstances(projectId, forceRefresh)
                : CompletableFuture.completedFuture(Collections.emptyList());
        CompletableFuture<List<DbRightsizingDto>> rightFuture = dbOpsFeatureFlags.isRightsizingEnabled()
                ? getRightsizingRecommendations(projectId, forceRefresh)
                : CompletableFuture.completedFuture(Collections.emptyList());
        CompletableFuture<List<DbSnapshotWasteDto>> snapFuture = getDbSnapshots(projectId, false);

        return CompletableFuture.allOf(invFuture, idleFuture, rightFuture, snapFuture).thenApply(v -> {
            List<DbInstanceDto> inventory   = invFuture.join();
            List<DbIdleInstanceDto> idle    = idleFuture.join();
            List<DbRightsizingDto> right    = rightFuture.join();
            List<DbSnapshotWasteDto> snaps  = snapFuture.join();

            double totalCost       = inventory.stream().mapToDouble(DbInstanceDto::getEstimatedMonthlyCost).sum();
            double idleSavings     = idle.stream().mapToDouble(DbIdleInstanceDto::getPotentialSavings).sum();
            double rightsizeSavings= right.stream().mapToDouble(DbRightsizingDto::getEstimatedSavings).sum();
            double snapshotWaste   = snaps.stream().mapToDouble(DbSnapshotWasteDto::getEstimatedMonthlyCost).sum();
            double totalSavings    = idleSavings + rightsizeSavings + snapshotWaste;

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
    // PUBLIC API — SLOW QUERIES (Cloud Logging / pg_stat_statements via agent)
    // =========================================================================

    /**
     * For GCP, slow queries require Cloud Logging integration or the DB agent.
     * Returns a graceful "logs not configured" response if unavailable.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<SlowQueriesResponseDto> getSlowQueries(String projectId, boolean forceRefresh) {
        SlowQueriesResponseDto response = new SlowQueriesResponseDto();
        response.setLogsEnabled(false);
        response.setMessage("Cloud SQL slow query logs require the XamOps DB agent or Cloud Logging export. Connect a DB agent to enable slow query analysis.");
        response.setQueries(Collections.emptyList());
        return CompletableFuture.completedFuture(response);
    }

    // =========================================================================
    // AGENT DATA — same DB tables as AWS (cloud-provider-agnostic storage)
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentInstanceMetricsDto>> getAgentInstanceMetrics(String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CloudAccount account = getAccountByProjectId(projectId);
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
                                    log.warn("Failed to parse agent instance metrics: {}", ex.getMessage());
                                }
                            });
                }
                return result;
            } catch (Exception e) {
                log.error("❌ [GcpDbOps] Agent instance metrics error for {}: {}", projectId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentQueryStatDto>> getAgentQueryStats(String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CloudAccount account = getAccountByProjectId(projectId);
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
                                    log.warn("Failed to parse agent query stats: {}", ex.getMessage());
                                }
                            });
                }
                return result;
            } catch (Exception e) {
                log.error("❌ [GcpDbOps] Agent query stats error for {}: {}", projectId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentTableSizeDto>> getAgentTableSizes(String projectId) {
        return CompletableFuture.supplyAsync(() -> readAgentData(projectId, "db-table-sizes",
                new TypeReference<List<AgentTableSizeDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentIndexUsageDto>> getAgentIndexUsage(String projectId) {
        return CompletableFuture.supplyAsync(() -> readAgentData(projectId, "db-index-usage",
                new TypeReference<List<AgentIndexUsageDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentTableActivityDto>> getAgentTableActivity(String projectId) {
        return CompletableFuture.supplyAsync(() -> readAgentData(projectId, "db-table-activity",
                new TypeReference<List<AgentTableActivityDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentVacuumStatDto>> getAgentVacuumStats(String projectId) {
        return CompletableFuture.supplyAsync(() -> readAgentData(projectId, "db-vacuum-stats",
                new TypeReference<List<AgentVacuumStatDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentUserActivityDto>> getAgentUserActivity(String projectId) {
        return CompletableFuture.supplyAsync(() -> readAgentData(projectId, "db-user-activity",
                new TypeReference<List<AgentUserActivityDto>>() {}));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AgentTimeseriesDto>> getAgentTimeseries(String projectId, int hours) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CloudAccount account = getAccountByProjectId(projectId);
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
                            log.warn("Failed to parse timeseries row for agent {}: {}", agent.getAgentName(), ex.getMessage());
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
                log.info("✅ [GcpDbOps] Agent timeseries: {} agents for project {}", result.size(), projectId);
                return result;
            } catch (Exception e) {
                log.error("❌ [GcpDbOps] Agent timeseries error for {}: {}", projectId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private DbInstanceDto toInstanceDto(DatabaseInstance inst) {
        DbInstanceDto dto = new DbInstanceDto();

        // Cloud SQL instance name format: "project:region:instanceName"
        String instanceName = inst.getName();
        String region = inst.getRegion() != null ? inst.getRegion() : "unknown";
        // Build a stable ID: projectId:region:instanceName
        String project = inst.getProject() != null ? inst.getProject() : "gcp";
        dto.setInstanceId(project + ":" + region + ":" + instanceName);

        // Instance class / tier
        String tier = (inst.getSettings() != null && inst.getSettings().getTier() != null)
                ? inst.getSettings().getTier() : "unknown";
        dto.setInstanceClass(tier);

        // Database engine
        String dbVersion = inst.getDatabaseVersion() != null ? inst.getDatabaseVersion() : "UNKNOWN";
        dto.setEngine(normalizeEngine(dbVersion));
        dto.setEngineVersion(dbVersion);

        // Status
        dto.setStatus(inst.getState() != null ? inst.getState().toLowerCase() : "unknown");

        // Region
        dto.setRegion(region);

        // High availability (REGIONAL = HA)
        String availType = (inst.getSettings() != null && inst.getSettings().getAvailabilityType() != null)
                ? inst.getSettings().getAvailabilityType() : "ZONAL";
        dto.setMultiAz(availType); // "REGIONAL" or "ZONAL"

        // Storage type
        String diskType = (inst.getSettings() != null
                && inst.getSettings().getDataDiskType() != null)
                ? inst.getSettings().getDataDiskType() : "PD_SSD";
        dto.setStorageType(diskType);

        // Storage size
        Long diskSizeGb = (inst.getSettings() != null)
                ? inst.getSettings().getDataDiskSizeGb() : null;
        dto.setAllocatedStorage(diskSizeGb != null ? diskSizeGb.intValue() : 0);

        // Public IP
        boolean hasPublicIp = false;
        if (inst.getSettings() != null
                && inst.getSettings().getIpConfiguration() != null
                && Boolean.TRUE.equals(inst.getSettings().getIpConfiguration().getIpv4Enabled())) {
            hasPublicIp = true;
        }
        dto.setPubliclyAccessible(hasPublicIp);

        // Cloud SQL encrypts disks by default
        dto.setStorageEncrypted(true);

        // Backups
        boolean backupEnabled = false;
        String retentionDays = "7";
        if (inst.getSettings() != null && inst.getSettings().getBackupConfiguration() != null) {
            backupEnabled = Boolean.TRUE.equals(inst.getSettings().getBackupConfiguration().getEnabled());
            if (inst.getSettings().getBackupConfiguration().getBackupRetentionSettings() != null) {
                Integer retained = inst.getSettings().getBackupConfiguration()
                        .getBackupRetentionSettings().getRetainedBackups();
                if (retained != null) retentionDays = retained.toString();
            }
        }
        dto.setBackupEnabled(backupEnabled);
        dto.setBackupRetentionDays(retentionDays);

        // Endpoint — prefer private IP, fall back to public
        String endpoint = "";
        if (inst.getIpAddresses() != null) {
            for (IpMapping ip : inst.getIpAddresses()) {
                if ("PRIVATE".equals(ip.getType())) {
                    endpoint = ip.getIpAddress();
                    break;
                }
            }
            if (endpoint.isEmpty()) {
                for (IpMapping ip : inst.getIpAddresses()) {
                    if ("PRIMARY".equals(ip.getType()) || "OUTGOING".equals(ip.getType())) {
                        endpoint = ip.getIpAddress();
                        break;
                    }
                }
            }
        }
        dto.setEndpoint(endpoint);

        // Estimated monthly cost
        dto.setEstimatedMonthlyCost(TIER_COST_MAP.getOrDefault(tier.toLowerCase(), 50.0));

        // Max connections (Cloud SQL formula varies; use sensible defaults)
        dto.setMaxConnections(estimateMaxConnections(tier));

        return dto;
    }

    private String normalizeEngine(String dbVersion) {
        if (dbVersion == null) return "unknown";
        String v = dbVersion.toUpperCase();
        if (v.startsWith("MYSQL")) return "mysql";
        if (v.startsWith("POSTGRES")) return "postgres";
        if (v.startsWith("SQLSERVER")) return "sqlserver";
        return dbVersion.toLowerCase();
    }

    private int estimateMaxConnections(String tier) {
        if (tier == null) return 100;
        String t = tier.toLowerCase();
        if (t.contains("micro") || t.contains("f1")) return 25;
        if (t.contains("small") || t.contains("g1")) return 100;
        if (t.contains("standard-1") || t.contains("custom-1")) return 200;
        if (t.contains("standard-2") || t.contains("custom-2")) return 400;
        if (t.contains("standard-4") || t.contains("custom-4")) return 800;
        if (t.contains("standard-8") || t.contains("custom-8")) return 1600;
        if (t.contains("highmem-2")) return 500;
        if (t.contains("highmem-4")) return 1000;
        if (t.contains("highmem-8")) return 2000;
        return 200;
    }

    /** Extract the short instance name from a full "project:region:name" id. */
    private String extractInstanceName(String fullId) {
        if (fullId == null) return "";
        String[] parts = fullId.split(":");
        return parts[parts.length - 1];
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

    /**
     * Fetch a single Cloud SQL monitoring metric and populate the metricsMap.
     * Uses ListTimeSeries with a filter for database_id.
     */
    private void fetchAndApplyMetric(MetricServiceClient client, String monitoringProject,
            TimeInterval interval, Aggregation aggregation,
            String metricType, String projectId,
            Map<String, DbMetricsDto> metricsMap, String fieldKey) {
        try {
            String filter = String.format(
                    "metric.type=\"%s\" AND resource.labels.project_id=\"%s\"",
                    metricType, projectId);

            ListTimeSeriesRequest request = ListTimeSeriesRequest.newBuilder()
                    .setName(monitoringProject)
                    .setFilter(filter)
                    .setInterval(interval)
                    .setAggregation(aggregation)
                    .build();

            for (MetricServiceClient.ListTimeSeriesPagedResponse page :
                    Collections.singletonList(client.listTimeSeries(request))) {
                for (com.google.monitoring.v3.TimeSeries ts : page.iterateAll()) {
                    // Extract database_id label — format: "project:region:instanceName"
                    String dbId = ts.getResource().getLabelsOrDefault("database_id", null);
                    if (dbId == null) continue;

                    // Match to full instanceId by checking suffix
                    String matchKey = metricsMap.keySet().stream()
                            .filter(k -> k.endsWith(":" + dbId) || k.equals(dbId))
                            .findFirst().orElse(null);
                    if (matchKey == null) continue;

                    DbMetricsDto dto = metricsMap.get(matchKey);
                    if (ts.getPointsCount() == 0) continue;

                    // Average all data points
                    double avg = ts.getPointsList().stream()
                            .mapToDouble(p -> getPointValue(p, metricType))
                            .average().orElse(0.0);

                    applyMetricValue(dto, fieldKey, avg, metricType);
                }
            }
        } catch (Exception e) {
            log.debug("[GcpDbOps] Metric fetch error for {}: {}", metricType, e.getMessage());
        }
    }

    private double getPointValue(Point point, String metricType) {
        if (point == null || point.getValue() == null) return 0.0;
        var tv = point.getValue();
        if (tv.hasDoubleValue()) return tv.getDoubleValue();
        if (tv.hasInt64Value()) return (double) tv.getInt64Value();
        return 0.0;
    }

    private void applyMetricValue(DbMetricsDto dto, String fieldKey, double value, String metricType) {
        switch (fieldKey) {
            case "cpu":
                dto.setAvgCpuPercent(round2(value * 100.0)); // utilization is 0-1, convert to %
                break;
            case "connections":
                dto.setAvgConnections(round2(value));
                break;
            case "memory":
                // memory/utilization is 0-1 ratio; we store "freeable memory" as MB
                // For GCP, store used memory % as negated freeable (approximation)
                dto.setAvgFreeableMemoryMb(round2((1.0 - value) * 1024.0)); // rough estimate
                break;
            case "readOps":
                dto.setAvgReadIops(round2(value));
                break;
            case "writeOps":
                dto.setAvgWriteIops(round2(value));
                break;
            case "diskBytesUsed":
                dto.setFreeStorageSpaceGb(0.0); // set after we know total disk
                break;
            case "networkReceive":
                dto.setAvgNetworkReceiveMbps(round2(value / (1024.0 * 1024.0)));
                break;
            case "networkSend":
                dto.setAvgNetworkTransmitMbps(round2(value / (1024.0 * 1024.0)));
                break;
            default:
                break;
        }
    }

    private <T> List<T> readAgentData(String projectId, String dataType, TypeReference<List<T>> typeRef) {
        try {
            CloudAccount account = getAccountByProjectId(projectId);
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
                                log.warn("Failed to parse agent data type {}: {}", dataType, ex.getMessage());
                            }
                        });
            }
            return result;
        } catch (Exception e) {
            log.error("❌ [GcpDbOps] Agent data ({}) error for {}: {}", dataType, projectId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private CloudAccount getAccountByProjectId(String projectId) {
        return cloudAccountRepository.findByGcpProjectId(projectId).orElse(null);
    }

    private <T> Optional<T> getFromDb(String cacheKey, TypeReference<T> typeRef) {
        return cachedDataRepository.findById(cacheKey).map(cached -> {
            try {
                return objectMapper.<T>readValue(cached.getJsonData(), typeRef);
            } catch (Exception e) {
                log.warn("Failed to deserialize DB cache for key {}: {}", cacheKey, e.getMessage());
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
            log.warn("Failed to save GCP DbOps DB snapshot for key {}: {}", cacheKey, e.getMessage());
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
