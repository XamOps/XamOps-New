package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.dto.dbops.*;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.domain.DbAgentMetricsHistory;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.DimensionValues;
import software.amazon.awssdk.services.costexplorer.model.Expression;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageResponse;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.Group;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinition;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinitionType;
import software.amazon.awssdk.services.costexplorer.model.ResultByTime;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DBSnapshot;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbSnapshotsRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbSnapshotsResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * DbOps Cost Service — AWS Database Cost Optimization
 *
 * Caching strategy (3-tier):
 *   Redis (primary, fast) → CachedData DB (warm fallback) → Live AWS API call
 *
 * API hit minimization:
 *   - Inventory:  1 × describeDBInstances per region per account  (cached 24h)
 *   - Metrics:    1 × CloudWatch GetMetricData batch per region    (cached 12h)
 *   - Costs:      1 × Cost Explorer call filtered to DB services   (cached 6h)
 *   - Snapshots:  1 × describeDBSnapshots per region per account   (cached 24h)
 *   - Idle/Rightsizing/Security/Summary: fully derived — 0 extra API calls
 */
@Service
public class DbOpsCostService {

    private static final Logger logger = LoggerFactory.getLogger(DbOpsCostService.class);

    // ── Cache TTLs (minutes) ─────────────────────────────────────────────────
    private static final int INVENTORY_TTL  = 24 * 60;
    private static final int METRICS_TTL    = 12 * 60;
    private static final int COSTS_TTL      =  6 * 60;
    private static final int SNAPSHOTS_TTL  = 24 * 60;

    // ── Detection thresholds ─────────────────────────────────────────────────
    private static final double IDLE_CPU_THRESHOLD         = 5.0;   // %
    private static final double IDLE_CONN_THRESHOLD        = 1.0;   // connections
    private static final double RIGHTSIZING_CPU_THRESHOLD  = 10.0;  // %
    private static final int    SNAPSHOT_WASTE_AGE_DAYS    = 30;    // days
    private static final double SNAPSHOT_COST_PER_GB       = 0.095; // $/GB/month

    // ── Regions to scan ──────────────────────────────────────────────────────
    private static final List<String> SCAN_REGIONS = List.of(
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "eu-west-1", "eu-central-1",
            "ap-south-1", "ap-southeast-1", "ap-northeast-1"
    );

    // ── DB services for Cost Explorer filter ─────────────────────────────────
    private static final List<String> DB_SERVICES = List.of(
            "Amazon Relational Database Service",
            "Amazon ElastiCache",
            "Amazon Redshift",
            "Amazon DynamoDB"
    );

    // ── Approximate on-demand monthly costs (USD, single-AZ) ─────────────────
    private static final Map<String, Double> INSTANCE_COST_MAP = Map.ofEntries(
            Map.entry("db.t3.micro",    15.0),
            Map.entry("db.t3.small",    30.0),
            Map.entry("db.t3.medium",   60.0),
            Map.entry("db.t3.large",   120.0),
            Map.entry("db.m5.large",   140.0),
            Map.entry("db.m5.xlarge",  280.0),
            Map.entry("db.m5.2xlarge", 560.0),
            Map.entry("db.m5.4xlarge",1120.0),
            Map.entry("db.r5.large",   180.0),
            Map.entry("db.r5.xlarge",  360.0),
            Map.entry("db.r5.2xlarge", 720.0),
            Map.entry("db.r5.4xlarge",1440.0),
            Map.entry("db.r6g.large",  170.0),
            Map.entry("db.r6g.xlarge", 340.0),
            Map.entry("db.r6g.2xlarge",680.0)
    );

    // ── Max connections by instance class (MySQL formula: RAM / 12582880) ────
    private static final Map<String, Integer> MAX_CONNECTIONS_MAP = Map.ofEntries(
            Map.entry("db.t3.micro",      87),
            Map.entry("db.t3.small",     170),
            Map.entry("db.t3.medium",    340),
            Map.entry("db.t3.large",     682),
            Map.entry("db.m5.large",     682),
            Map.entry("db.m5.xlarge",   1365),
            Map.entry("db.m5.2xlarge",  2730),
            Map.entry("db.m5.4xlarge",  5461),
            Map.entry("db.r5.large",    1365),
            Map.entry("db.r5.xlarge",   2730),
            Map.entry("db.r5.2xlarge",  5461),
            Map.entry("db.r5.4xlarge", 10922),
            Map.entry("db.r6g.large",   1365),
            Map.entry("db.r6g.xlarge",  2730),
            Map.entry("db.r6g.2xlarge", 5461)
    );

    // ── Downgrade recommendation map ─────────────────────────────────────────
    private static final Map<String, String> DOWNSIZE_MAP = Map.ofEntries(
            Map.entry("db.t3.medium",   "db.t3.small"),
            Map.entry("db.t3.large",    "db.t3.medium"),
            Map.entry("db.m5.large",    "db.t3.large"),
            Map.entry("db.m5.xlarge",   "db.m5.large"),
            Map.entry("db.m5.2xlarge",  "db.m5.xlarge"),
            Map.entry("db.m5.4xlarge",  "db.m5.2xlarge"),
            Map.entry("db.r5.large",    "db.m5.large"),
            Map.entry("db.r5.xlarge",   "db.r5.large"),
            Map.entry("db.r5.2xlarge",  "db.r5.xlarge"),
            Map.entry("db.r5.4xlarge",  "db.r5.2xlarge"),
            Map.entry("db.r6g.xlarge",  "db.r6g.large"),
            Map.entry("db.r6g.2xlarge", "db.r6g.xlarge")
    );

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final AgentDataCacheRepository agentDataCacheRepository;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final DbAgentConfigRepository dbAgentConfigRepository;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository;
    private final SpotPricingService spotPricingService;
    private final DbOpsFeatureFlags dbOpsFeatureFlags;

    @Autowired
    public DbOpsCostService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            RedisCacheService redisCache,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            AgentDataCacheRepository agentDataCacheRepository,
            KubernetesClusterConfigRepository clusterConfigRepository,
            DbAgentConfigRepository dbAgentConfigRepository,
            DbAgentDataCacheRepository dbAgentDataCacheRepository,
            DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository,
            @Lazy SpotPricingService spotPricingService,
            DbOpsFeatureFlags dbOpsFeatureFlags) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.clusterConfigRepository = clusterConfigRepository;
        this.dbAgentConfigRepository = dbAgentConfigRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.dbAgentMetricsHistoryRepository = dbAgentMetricsHistoryRepository;
        this.spotPricingService = spotPricingService;
        this.dbOpsFeatureFlags = dbOpsFeatureFlags;
        logger.info("DbOpsCostService initialized");
    }

    // =========================================================================
    // PUBLIC API — INVENTORY
    // =========================================================================

    /**
     * Fetch all RDS instances across all scan regions for an account.
     * API cost: 1 × describeDBInstances per region (cached 24h).
     * Security checks, right-sizing, and idle detection all derive from this result.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbInstanceDto>> getDbInventory(String accountId, boolean forceRefresh) {
        String cacheKey = "dbops:aws:inventory:" + accountId;

        if (!forceRefresh) {
            Optional<List<DbInstanceDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                logger.info("✅ [DbOps] Redis hit — inventory for account {}", accountId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<List<DbInstanceDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                logger.info("✅ [DbOps] DB cache hit — inventory for account {}", accountId);
                redisCache.put(cacheKey, db.get(), INVENTORY_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                CloudAccount account = getAccount(accountId);
                List<DbInstanceDto> instances = new ArrayList<>();

                for (String region : SCAN_REGIONS) {
                    try {
                        RdsClient rds = awsClientProvider.getRdsClient(account, region);
                        // Paginator — single logical call per region, handles multiple pages
                        for (DescribeDbInstancesResponse page :
                                rds.describeDBInstancesPaginator(DescribeDbInstancesRequest.builder().build())) {
                            for (DBInstance db : page.dbInstances()) {
                                instances.add(toInstanceDto(db, region));
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("[DbOps] No RDS access in region {} (account {}): {}", region, accountId, e.getMessage());
                    }
                }

                redisCache.put(cacheKey, instances, INVENTORY_TTL);
                saveToDb(cacheKey, instances);
                logger.info("✅ [DbOps] Inventory fetched: {} instances for account {}", instances.size(), accountId);
                return instances;
            } catch (Exception e) {
                logger.error("❌ [DbOps] Inventory error for account {}: {}", accountId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // =========================================================================
    // PUBLIC API — CLOUDWATCH METRICS (batch — 1 call per region)
    // =========================================================================

    /**
     * Fetch 7-day average CloudWatch metrics for all RDS instances.
     * API cost: 1 × GetMetricData per region (batches ALL instances in one call, cached 12h).
     * Idle detection and right-sizing both consume this result — no duplicate calls.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<Map<String, DbMetricsDto>> getDbMetrics(String accountId, boolean forceRefresh) {
        String cacheKey = "dbops:aws:metrics:" + accountId;

        if (!forceRefresh) {
            Optional<Map<String, DbMetricsDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                logger.info("✅ [DbOps] Redis hit — metrics for account {}", accountId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<Map<String, DbMetricsDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                logger.info("✅ [DbOps] DB cache hit — metrics for account {}", accountId);
                redisCache.put(cacheKey, db.get(), METRICS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        // Need inventory first; cache hit expected after getDbInventory runs
        return getDbInventory(accountId, false).thenApply(instances -> {
            if (instances.isEmpty()) return Collections.<String, DbMetricsDto>emptyMap();
            try {
                CloudAccount account = getAccount(accountId);
                Map<String, DbMetricsDto> metricsMap = new HashMap<>();

                Instant endTime   = Instant.now();
                Instant startTime = endTime.minus(7, ChronoUnit.DAYS);

                // Group by region so one CloudWatch call covers all instances in that region
                Map<String, List<DbInstanceDto>> byRegion = instances.stream()
                        .collect(Collectors.groupingBy(DbInstanceDto::getRegion));

                for (Map.Entry<String, List<DbInstanceDto>> entry : byRegion.entrySet()) {
                    String region = entry.getKey();
                    List<DbInstanceDto> regionInstances = entry.getValue();

                    // Build one batch of MetricDataQuery for all instances in this region
                    // CloudWatch allows up to 500 queries per call; 5 metrics × instance << 500
                    List<MetricDataQuery> queries = new ArrayList<>();
                    for (DbInstanceDto inst : regionInstances) {
                        String sid = sanitizeId(inst.getInstanceId());
                        String dbId = inst.getInstanceId();
                        // One-day period average over 7 days → 7 data points per metric
                        queries.add(buildMetricQuery("cpu_"   + sid, "AWS/RDS", "CPUUtilization",            dbId, 86400));
                        queries.add(buildMetricQuery("conn_"  + sid, "AWS/RDS", "DatabaseConnections",        dbId, 86400));
                        queries.add(buildMetricQuery("mem_"   + sid, "AWS/RDS", "FreeableMemory",             dbId, 86400));
                        queries.add(buildMetricQuery("riops_" + sid, "AWS/RDS", "ReadIOPS",                   dbId, 86400));
                        queries.add(buildMetricQuery("wiops_" + sid, "AWS/RDS", "WriteIOPS",                  dbId, 86400));
                        // Datadog-equivalent metrics
                        queries.add(buildMetricQuery("rlat_"  + sid, "AWS/RDS", "ReadLatency",                dbId, 86400));
                        queries.add(buildMetricQuery("wlat_"  + sid, "AWS/RDS", "WriteLatency",               dbId, 86400));
                        queries.add(buildMetricQuery("dq_"    + sid, "AWS/RDS", "DiskQueueDepth",             dbId, 86400));
                        queries.add(buildMetricQuery("fs_"    + sid, "AWS/RDS", "FreeStorageSpace",           dbId, 86400));
                        queries.add(buildMetricQuery("rl_"    + sid, "AWS/RDS", "ReplicaLag",                 dbId, 86400));
                        queries.add(buildMetricQuery("nrx_"   + sid, "AWS/RDS", "NetworkReceiveThroughput",   dbId, 86400));
                        queries.add(buildMetricQuery("ntx_"   + sid, "AWS/RDS", "NetworkTransmitThroughput",  dbId, 86400));
                        queries.add(buildMetricQuery("swap_"  + sid, "AWS/RDS", "SwapUsage",                  dbId, 86400));
                        queries.add(buildMetricQuery("burst_" + sid, "AWS/RDS", "BurstBalance",               dbId, 86400));
                    }

                    // Single API call covers all instances in this region
                    CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, region);
                    Map<String, Double> valueMap = new HashMap<>();

                    GetMetricDataRequest req = GetMetricDataRequest.builder()
                            .startTime(startTime)
                            .endTime(endTime)
                            .metricDataQueries(queries)
                            .build();

                    // Handle CloudWatch pagination (rare for DB counts, but correct)
                    GetMetricDataResponse resp = cw.getMetricData(req);
                    collectMetricValues(resp, valueMap);
                    while (resp.nextToken() != null && !resp.nextToken().isEmpty()) {
                        resp = cw.getMetricData(req.toBuilder().nextToken(resp.nextToken()).build());
                        collectMetricValues(resp, valueMap);
                    }

                    for (DbInstanceDto inst : regionInstances) {
                        String sid = sanitizeId(inst.getInstanceId());
                        DbMetricsDto dto = new DbMetricsDto();
                        dto.setInstanceId(inst.getInstanceId());
                        dto.setRegion(region);
                        dto.setAvgCpuPercent(round2(valueMap.getOrDefault("cpu_"   + sid, 0.0)));
                        dto.setAvgConnections(round2(valueMap.getOrDefault("conn_"  + sid, 0.0)));
                        // FreeableMemory bytes → MB
                        dto.setAvgFreeableMemoryMb(round2(valueMap.getOrDefault("mem_"   + sid, 0.0) / (1024.0 * 1024.0)));
                        dto.setAvgReadIops(round2(valueMap.getOrDefault("riops_" + sid, 0.0)));
                        dto.setAvgWriteIops(round2(valueMap.getOrDefault("wiops_" + sid, 0.0)));
                        // ReadLatency / WriteLatency: seconds → ms
                        dto.setAvgReadLatencyMs(round2(valueMap.getOrDefault("rlat_"  + sid, 0.0) * 1000.0));
                        dto.setAvgWriteLatencyMs(round2(valueMap.getOrDefault("wlat_"  + sid, 0.0) * 1000.0));
                        dto.setAvgDiskQueueDepth(round2(valueMap.getOrDefault("dq_"    + sid, 0.0)));
                        // FreeStorageSpace bytes → GB
                        dto.setFreeStorageSpaceGb(round2(valueMap.getOrDefault("fs_"   + sid, 0.0) / (1024.0 * 1024.0 * 1024.0)));
                        dto.setReplicaLagSeconds(round2(valueMap.getOrDefault("rl_"    + sid, 0.0)));
                        // Network bytes/sec → MB/s
                        dto.setAvgNetworkReceiveMbps(round2(valueMap.getOrDefault("nrx_"  + sid, 0.0) / (1024.0 * 1024.0)));
                        dto.setAvgNetworkTransmitMbps(round2(valueMap.getOrDefault("ntx_"  + sid, 0.0) / (1024.0 * 1024.0)));
                        // SwapUsage bytes → MB
                        dto.setAvgSwapUsageMb(round2(valueMap.getOrDefault("swap_"  + sid, 0.0) / (1024.0 * 1024.0)));
                        dto.setBurstBalance(round2(valueMap.getOrDefault("burst_" + sid, 0.0)));
                        metricsMap.put(inst.getInstanceId(), dto);
                    }
                }

                redisCache.put(cacheKey, metricsMap, METRICS_TTL);
                saveToDb(cacheKey, metricsMap);
                logger.info("✅ [DbOps] Metrics fetched for {} instances, account {}", metricsMap.size(), accountId);
                return metricsMap;
            } catch (Exception e) {
                logger.error("❌ [DbOps] Metrics error for account {}: {}", accountId, e.getMessage());
                return Collections.<String, DbMetricsDto>emptyMap();
            }
        });
    }

    // =========================================================================
    // PUBLIC API — COST EXPLORER (filtered to DB services only)
    // =========================================================================

    /**
     * Fetch month-to-date DB costs from Cost Explorer.
     * API cost: 1 call (cached 6h). Filter restricts to DB services — no full account scan.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbCostDto>> getDbCosts(String accountId, boolean forceRefresh) {
        String cacheKey = "dbops:aws:costs:" + accountId;

        if (!forceRefresh) {
            Optional<List<DbCostDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                logger.info("✅ [DbOps] Redis hit — costs for account {}", accountId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<List<DbCostDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                logger.info("✅ [DbOps] DB cache hit — costs for account {}", accountId);
                redisCache.put(cacheKey, db.get(), COSTS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                CloudAccount account = getAccount(accountId);
                CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

                LocalDate start = LocalDate.now().withDayOfMonth(1);
                LocalDate end   = LocalDate.now().plusDays(1); // exclusive upper bound

                // Filter: only DB-related AWS services
                Expression filter = Expression.builder()
                        .dimensions(DimensionValues.builder()
                                .key(software.amazon.awssdk.services.costexplorer.model.Dimension.SERVICE)
                                .values(DB_SERVICES)
                                .build())
                        .build();

                GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                        .timePeriod(DateInterval.builder()
                                .start(start.toString())
                                .end(end.toString())
                                .build())
                        .granularity(Granularity.MONTHLY)
                        .filter(filter)
                        .groupBy(
                                GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("SERVICE").build(),
                                GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("REGION").build()
                        )
                        .metrics("UnblendedCost")
                        .build();

                GetCostAndUsageResponse response = ce.getCostAndUsage(req);

                List<DbCostDto> costs = new ArrayList<>();
                double grandTotal = 0.0;

                for (ResultByTime result : response.resultsByTime()) {
                    for (Group group : result.groups()) {
                        double cost = Double.parseDouble(group.metrics().get("UnblendedCost").amount());
                        if (cost < 0.01) continue; // skip negligible entries
                        grandTotal += cost;
                        DbCostDto dto = new DbCostDto();
                        dto.setService(group.keys().get(0));
                        dto.setRegion(group.keys().size() > 1 ? group.keys().get(1) : "global");
                        dto.setCost(round2(cost));
                        costs.add(dto);
                    }
                }

                final double total = grandTotal;
                costs.forEach(c -> c.setPercentOfTotal(
                        total > 0 ? round2(c.getCost() / total * 100.0) : 0.0));
                costs.sort(Comparator.comparingDouble(DbCostDto::getCost).reversed());

                redisCache.put(cacheKey, costs, COSTS_TTL);
                saveToDb(cacheKey, costs);
                logger.info("✅ [DbOps] Costs fetched: {} entries for account {}", costs.size(), accountId);
                return costs;
            } catch (Exception e) {
                logger.error("❌ [DbOps] Costs error for account {}: {}", accountId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // =========================================================================
    // PUBLIC API — SNAPSHOT WASTE
    // =========================================================================

    /**
     * Find manual snapshots older than SNAPSHOT_WASTE_AGE_DAYS.
     * API cost: 1 × describeDBSnapshots per region (cached 24h).
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbSnapshotWasteDto>> getDbSnapshots(String accountId, boolean forceRefresh) {
        String cacheKey = "dbops:aws:snapshots:" + accountId;

        if (!forceRefresh) {
            Optional<List<DbSnapshotWasteDto>> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) {
                logger.info("✅ [DbOps] Redis hit — snapshots for account {}", accountId);
                return CompletableFuture.completedFuture(redis.get());
            }
            Optional<List<DbSnapshotWasteDto>> db = getFromDb(cacheKey, new TypeReference<>() {});
            if (db.isPresent()) {
                logger.info("✅ [DbOps] DB cache hit — snapshots for account {}", accountId);
                redisCache.put(cacheKey, db.get(), SNAPSHOTS_TTL);
                return CompletableFuture.completedFuture(db.get());
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                CloudAccount account = getAccount(accountId);
                List<DbSnapshotWasteDto> wasteList = new ArrayList<>();
                Instant cutoff = Instant.now().minus(SNAPSHOT_WASTE_AGE_DAYS, ChronoUnit.DAYS);

                for (String region : SCAN_REGIONS) {
                    try {
                        RdsClient rds = awsClientProvider.getRdsClient(account, region);
                        DescribeDbSnapshotsRequest req = DescribeDbSnapshotsRequest.builder()
                                .snapshotType("manual") // only manual snapshots — automated ones are AWS-managed
                                .build();

                        for (DescribeDbSnapshotsResponse page : rds.describeDBSnapshotsPaginator(req)) {
                            for (DBSnapshot snap : page.dbSnapshots()) {
                                if (snap.snapshotCreateTime() == null) continue;
                                if (snap.snapshotCreateTime().isAfter(cutoff)) continue;

                                long ageDays = Duration.between(snap.snapshotCreateTime(), Instant.now()).toDays();
                                int sizeGb = snap.allocatedStorage() != null ? snap.allocatedStorage() : 0;

                                DbSnapshotWasteDto dto = new DbSnapshotWasteDto();
                                dto.setSnapshotId(snap.dbSnapshotIdentifier());
                                dto.setInstanceId(snap.dbInstanceIdentifier());
                                dto.setRegion(region);
                                dto.setAgeDays((int) ageDays);
                                dto.setSizeGb(sizeGb);
                                dto.setEstimatedMonthlyCost(round2(sizeGb * SNAPSHOT_COST_PER_GB));
                                dto.setRecommendation(ageDays > 90
                                        ? "Review retention before deleting — over 90 days old"
                                        : "Review — created " + ageDays + " days ago");
                                wasteList.add(dto);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("[DbOps] No snapshot access in region {} (account {}): {}", region, accountId, e.getMessage());
                    }
                }

                wasteList.sort(Comparator.comparingDouble(DbSnapshotWasteDto::getEstimatedMonthlyCost).reversed());
                redisCache.put(cacheKey, wasteList, SNAPSHOTS_TTL);
                saveToDb(cacheKey, wasteList);
                logger.info("✅ [DbOps] Snapshots fetched: {} waste entries for account {}", wasteList.size(), accountId);
                return wasteList;
            } catch (Exception e) {
                logger.error("❌ [DbOps] Snapshots error for account {}: {}", accountId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // =========================================================================
    // DERIVED: IDLE DETECTION — 0 extra API calls (uses metrics + inventory cache)
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbIdleInstanceDto>> getIdleInstances(String accountId, boolean forceRefresh) {
        return getDbMetrics(accountId, forceRefresh)
                .thenCombine(getDbInventory(accountId, false), (metrics, instances) -> {
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
                            dto.setPotentialSavings(inst.getEstimatedMonthlyCost()); // full saving if stopped/deleted
                            dto.setRecommendation("No active connections for 7 days — consider stopping or deleting");
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
    public CompletableFuture<List<DbRightsizingDto>> getRightsizingRecommendations(String accountId, boolean forceRefresh) {
        return getDbMetrics(accountId, forceRefresh)
                .thenCombine(getDbInventory(accountId, false), (metrics, instances) -> {
                    List<DbRightsizingDto> recs = new ArrayList<>();
                    for (DbInstanceDto inst : instances) {
                        DbMetricsDto m = metrics.get(inst.getInstanceId());
                        if (m == null) continue;
                        // Idle instances are already handled separately
                        if (m.getAvgCpuPercent() <= IDLE_CPU_THRESHOLD && m.getAvgConnections() <= IDLE_CONN_THRESHOLD) continue;
                        if (m.getAvgCpuPercent() > RIGHTSIZING_CPU_THRESHOLD) continue;

                        String recommended = DOWNSIZE_MAP.get(inst.getInstanceClass());
                        if (recommended == null) continue;

                        double currentCost     = inst.getEstimatedMonthlyCost();
                        double recommendedCost = estimateMonthlyCost(recommended);
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
                        dto.setReason(String.format(
                                "Avg CPU %.1f%% over 7 days — instance is over-provisioned for current workload",
                                m.getAvgCpuPercent()));
                        recs.add(dto);
                    }
                    recs.sort(Comparator.comparingDouble(DbRightsizingDto::getEstimatedSavings).reversed());
                    return recs;
                });
    }

    // =========================================================================
    // DERIVED: SECURITY CHECKS — 0 extra API calls (derived from inventory)
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DbSecurityCheckDto>> getSecurityChecks(String accountId, boolean forceRefresh) {
        return getDbInventory(accountId, forceRefresh).thenApply(instances -> {
            List<DbSecurityCheckDto> checks = new ArrayList<>();
            for (DbInstanceDto inst : instances) {
                String id = inst.getInstanceId();
                String region = inst.getRegion();

                // 1. Public accessibility
                checks.add(new DbSecurityCheckDto(id, region,
                        "Public Accessibility",
                        inst.isPubliclyAccessible() ? "HIGH" : "INFO",
                        inst.isPubliclyAccessible() ? "FAIL" : "PASS",
                        inst.isPubliclyAccessible()
                                ? "Instance is publicly accessible — reachable from the internet"
                                : "Not publicly accessible"));

                // 2. Storage encryption
                checks.add(new DbSecurityCheckDto(id, region,
                        "Storage Encryption",
                        !inst.isStorageEncrypted() ? "HIGH" : "INFO",
                        inst.isStorageEncrypted() ? "PASS" : "FAIL",
                        inst.isStorageEncrypted()
                                ? "Storage is encrypted at rest"
                                : "Storage is NOT encrypted at rest — data at risk"));

                // 3. Automated backups
                checks.add(new DbSecurityCheckDto(id, region,
                        "Automated Backups",
                        !inst.isBackupEnabled() ? "MEDIUM" : "INFO",
                        inst.isBackupEnabled() ? "PASS" : "FAIL",
                        inst.isBackupEnabled()
                                ? "Backups enabled (retention: " + inst.getBackupRetentionDays() + " days)"
                                : "Automated backups are DISABLED — no recovery point"));

                // 4. Multi-AZ
                boolean multiAz = "Yes".equals(inst.getMultiAz());
                checks.add(new DbSecurityCheckDto(id, region,
                        "Multi-AZ Deployment",
                        !multiAz ? "MEDIUM" : "INFO",
                        multiAz ? "PASS" : "WARN",
                        multiAz
                                ? "Multi-AZ enabled — automatic failover available"
                                : "Single-AZ — no automatic failover on host failure"));
            }

            // Sort: FAIL → WARN → PASS
            checks.sort(Comparator.comparingInt(c -> {
                switch (c.getStatus()) {
                    case "FAIL": return 0;
                    case "WARN": return 1;
                    default:     return 2;
                }
            }));
            return checks;
        });
    }

    // =========================================================================
    // DERIVED: SAVINGS SUMMARY — 0 extra API calls (aggregates all derived data)
    // =========================================================================

    @Async("awsTaskExecutor")
    public CompletableFuture<DbSavingsSummaryDto> getSavingsSummary(String accountId, boolean forceRefresh) {
        CompletableFuture<List<DbInstanceDto>>     inventoryFuture    = getDbInventory(accountId, false);
        // Phase 1a: a disabled pillar contributes an empty list, so its count/savings are 0 and it
        // never enters the savings total — the summary omits it rather than faking a value.
        CompletableFuture<List<DbIdleInstanceDto>> idleFuture         = dbOpsFeatureFlags.isIdleEnabled()
                ? getIdleInstances(accountId, forceRefresh)
                : CompletableFuture.completedFuture(Collections.emptyList());
        CompletableFuture<List<DbRightsizingDto>>  rightsizingFuture  = dbOpsFeatureFlags.isRightsizingEnabled()
                ? getRightsizingRecommendations(accountId, false)
                : CompletableFuture.completedFuture(Collections.emptyList());
        CompletableFuture<List<DbSnapshotWasteDto>> snapshotsFuture   = getDbSnapshots(accountId, false);
        CompletableFuture<List<DbCostDto>>         costsFuture        = getDbCosts(accountId, false);

        return CompletableFuture.allOf(inventoryFuture, idleFuture, rightsizingFuture, snapshotsFuture, costsFuture)
                .thenApply(v -> {
                    List<DbInstanceDto>     inventory   = inventoryFuture.join();
                    List<DbIdleInstanceDto> idle        = idleFuture.join();
                    List<DbRightsizingDto>  rightsizing = rightsizingFuture.join();
                    List<DbSnapshotWasteDto> snapshots  = snapshotsFuture.join();
                    List<DbCostDto>         costs       = costsFuture.join();

                    double totalCost          = costs.stream().mapToDouble(DbCostDto::getCost).sum();
                    // Fallback: if Cost Explorer returned nothing, sum estimated costs from inventory
                    if (totalCost == 0.0 && !inventory.isEmpty()) {
                        totalCost = inventory.stream().mapToDouble(DbInstanceDto::getEstimatedMonthlyCost).sum();
                    }
                    double idleSavings        = idle.stream().mapToDouble(DbIdleInstanceDto::getPotentialSavings).sum();
                    double rightsizingSavings = rightsizing.stream().mapToDouble(DbRightsizingDto::getEstimatedSavings).sum();
                    double snapshotWaste      = snapshots.stream().mapToDouble(DbSnapshotWasteDto::getEstimatedMonthlyCost).sum();
                    double totalSavings       = idleSavings + rightsizingSavings + snapshotWaste;

                    DbSavingsSummaryDto summary = new DbSavingsSummaryDto();
                    summary.setTotalDbMonthlyCost(round2(totalCost));
                    summary.setTotalInstances(inventory.size());
                    summary.setIdleInstances(idle.size());
                    summary.setIdleSavingsPotential(round2(idleSavings));
                    summary.setRightsizingCandidates(rightsizing.size());
                    summary.setRightsizingSavingsPotential(round2(rightsizingSavings));
                    summary.setSnapshotWasteCost(round2(snapshotWaste));
                    summary.setTotalPotentialSavings(round2(totalSavings));
                    summary.setSavingsPercentage(
                            totalCost > 0 ? round2(totalSavings / totalCost * 100.0) : 0.0);
                    return summary;
                });
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst()
                .orElseThrow(() -> new RuntimeException("AWS account not found: " + accountId));
    }

    private double estimateMonthlyCost(String instanceClass) {
        return INSTANCE_COST_MAP.getOrDefault(instanceClass, 50.0);
    }

    /**
     * Sanitize a DB instance ID for use as a CloudWatch MetricDataQuery ID.
     * Rule: must match [a-z][a-zA-Z0-9_]*, max 128 chars.
     */
    // =========================================================================
    // PUBLIC API — AGENT INSTANCE METRICS (server-level stats from SHOW STATUS)
    // =========================================================================

    @Async
    public CompletableFuture<List<AgentInstanceMetricsDto>> getAgentInstanceMetrics(String accountId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<CloudAccount> accountOpt = cloudAccountRepository
                        .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId);
                if (accountOpt.isEmpty()) return List.of();
                Long internalAccountId = accountOpt.get().getId();

                List<AgentInstanceMetricsDto> merged = new ArrayList<>();

                // 1. Read from db_agent_data_cache (DB-only agents registered via wizard)
                List<DbAgentConfig> dbAgents = dbAgentConfigRepository.findByCloudAccountId(internalAccountId);
                for (DbAgentConfig agent : dbAgents) {
                    Optional<DbAgentDataCache> cacheOpt = dbAgentDataCacheRepository
                            .findByDbAgentIdAndDataType(agent.getId(), "db-instance-metrics");
                    if (cacheOpt.isEmpty()) continue;
                    try {
                        List<AgentInstanceMetricsDto> batch = objectMapper.readValue(
                                cacheOpt.get().getDataJson(),
                                new TypeReference<List<AgentInstanceMetricsDto>>() {});
                        batch.forEach(dto -> {
                            dto.setAgentName(agent.getAgentName());
                            if (dto.getEc2InstanceType() != null && !dto.getEc2InstanceType().isEmpty()
                                    && dto.getRegion() != null && !dto.getRegion().isEmpty()) {
                                try {
                                    double price = spotPricingService.getHourlyOnDemandPrice(
                                            dto.getEc2InstanceType(), dto.getRegion());
                                    dto.setEc2HourlyPrice(price);
                                } catch (Exception ex) {
                                    logger.debug("[DbOps] EC2 price lookup failed for {} in {}: {}",
                                            dto.getEc2InstanceType(), dto.getRegion(), ex.getMessage());
                                }
                            }
                        });
                        merged.addAll(batch);
                    } catch (Exception e) {
                        logger.warn("[DbOps] getAgentInstanceMetrics: parse failed for db agent {}: {}",
                                agent.getAgentName(), e.getMessage());
                    }
                }

                // 2. Also read from agent_data_cache (K8s agents that push db-instance-metrics)
                List<KubernetesClusterConfig> clusters = clusterConfigRepository.findByCloudAccountId(internalAccountId);
                for (KubernetesClusterConfig cluster : clusters) {
                    Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                            .findByClusterConfigIdAndDataType(cluster.getId(), "db-instance-metrics");
                    if (cacheOpt.isEmpty()) continue;
                    try {
                        List<AgentInstanceMetricsDto> batch = objectMapper.readValue(
                                cacheOpt.get().getDataJson(),
                                new TypeReference<List<AgentInstanceMetricsDto>>() {});
                        merged.addAll(batch);
                    } catch (Exception e) {
                        logger.warn("[DbOps] getAgentInstanceMetrics: parse failed for cluster {}: {}",
                                cluster.getClusterName(), e.getMessage());
                    }
                }
                return merged;
            } catch (Exception e) {
                logger.error("[DbOps] getAgentInstanceMetrics error for {}: {}", accountId, e.getMessage());
                return List.of();
            }
        });
    }

    // =========================================================================
    // PUBLIC API — INSTANCE TIMESERIES (for detail drawer charts)
    // =========================================================================

    /**
     * Fetch hourly CloudWatch data points for a single RDS instance.
     * Used by the DbOps detail drawer to render Datadog-style time-series charts.
     * Period: 1h datapoints over the requested window (default 24h).
     * API cost: 1 CloudWatch GetMetricData call (not cached — called on demand).
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<DbInstanceTimeseriesDto> getInstanceTimeseries(
            String accountId, String instanceId, int hours) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CloudAccount account = getAccount(accountId);

                // Find the region for this instance from the inventory cache
                List<DbInstanceDto> inventory = getDbInventory(accountId, false).get();
                String region = inventory.stream()
                        .filter(i -> i.getInstanceId().equalsIgnoreCase(instanceId))
                        .map(DbInstanceDto::getRegion)
                        .findFirst()
                        .orElse("us-east-1");

                CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, region);
                Instant endTime   = Instant.now();
                Instant startTime = endTime.minusSeconds((long) hours * 3600);
                int periodSec     = 3600; // 1-hour granularity

                List<MetricDataQuery> queries = List.of(
                    buildMetricQuery("cpu",   "AWS/RDS", "CPUUtilization",           instanceId, periodSec),
                    buildMetricQuery("conn",  "AWS/RDS", "DatabaseConnections",       instanceId, periodSec),
                    buildMetricQuery("riops", "AWS/RDS", "ReadIOPS",                  instanceId, periodSec),
                    buildMetricQuery("wiops", "AWS/RDS", "WriteIOPS",                 instanceId, periodSec),
                    buildMetricQuery("rlat",  "AWS/RDS", "ReadLatency",               instanceId, periodSec),
                    buildMetricQuery("wlat",  "AWS/RDS", "WriteLatency",              instanceId, periodSec),
                    buildMetricQuery("fs",    "AWS/RDS", "FreeStorageSpace",          instanceId, periodSec),
                    buildMetricQuery("mem",   "AWS/RDS", "FreeableMemory",            instanceId, periodSec),
                    buildMetricQuery("nrx",   "AWS/RDS", "NetworkReceiveThroughput",  instanceId, periodSec),
                    buildMetricQuery("ntx",   "AWS/RDS", "NetworkTransmitThroughput", instanceId, periodSec),
                    buildMetricQuery("rl",    "AWS/RDS", "ReplicaLag",                instanceId, periodSec)
                );

                GetMetricDataRequest req = GetMetricDataRequest.builder()
                        .startTime(startTime).endTime(endTime)
                        .metricDataQueries(queries)
                        .build();

                // Collect all pages
                Map<String, List<Map.Entry<Instant, Double>>> rawMap = new HashMap<>();
                GetMetricDataResponse resp = cw.getMetricData(req);
                collectTimeseriesPoints(resp, rawMap);
                while (resp.nextToken() != null && !resp.nextToken().isEmpty()) {
                    resp = cw.getMetricData(req.toBuilder().nextToken(resp.nextToken()).build());
                    collectTimeseriesPoints(resp, rawMap);
                }

                DbInstanceTimeseriesDto dto = new DbInstanceTimeseriesDto();
                dto.setInstanceId(instanceId);
                dto.setRegion(region);
                dto.setCpu(toPoints(rawMap.get("cpu")));
                dto.setConnections(toPoints(rawMap.get("conn")));
                dto.setReadIops(toPoints(rawMap.get("riops")));
                dto.setWriteIops(toPoints(rawMap.get("wiops")));
                dto.setReadLatencyMs(toPointsScaled(rawMap.get("rlat"), 1000.0));   // s → ms
                dto.setWriteLatencyMs(toPointsScaled(rawMap.get("wlat"), 1000.0));  // s → ms
                dto.setFreeStorageGb(toPointsScaled(rawMap.get("fs"),    1.0 / (1024.0 * 1024.0 * 1024.0)));
                dto.setFreeableMemoryMb(toPointsScaled(rawMap.get("mem"), 1.0 / (1024.0 * 1024.0)));
                dto.setNetworkReceiveMbps(toPointsScaled(rawMap.get("nrx"),  1.0 / (1024.0 * 1024.0)));
                dto.setNetworkTransmitMbps(toPointsScaled(rawMap.get("ntx"), 1.0 / (1024.0 * 1024.0)));
                dto.setReplicaLagSeconds(toPoints(rawMap.get("rl")));

                logger.info("✅ [DbOps] Timeseries fetched for {} ({}h)", instanceId, hours);
                return dto;
            } catch (Exception e) {
                logger.error("❌ [DbOps] Timeseries error for {}: {}", instanceId, e.getMessage());
                DbInstanceTimeseriesDto empty = new DbInstanceTimeseriesDto();
                empty.setInstanceId(instanceId);
                return empty;
            }
        });
    }

    private void collectTimeseriesPoints(GetMetricDataResponse resp,
                                          Map<String, List<Map.Entry<Instant, Double>>> out) {
        for (MetricDataResult result : resp.metricDataResults()) {
            List<Instant> timestamps = result.timestamps();
            List<Double>  values     = result.values();
            if (timestamps == null || values == null) continue;
            List<Map.Entry<Instant, Double>> points = out.computeIfAbsent(
                    result.id(), k -> new ArrayList<>());
            for (int i = 0; i < Math.min(timestamps.size(), values.size()); i++) {
                points.add(Map.entry(timestamps.get(i), values.get(i)));
            }
        }
    }

    private List<DbInstanceTimeseriesDto.Point> toPoints(List<Map.Entry<Instant, Double>> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new DbInstanceTimeseriesDto.Point(e.getKey().toEpochMilli(), round2(e.getValue())))
                .collect(java.util.stream.Collectors.toList());
    }

    private List<DbInstanceTimeseriesDto.Point> toPointsScaled(
            List<Map.Entry<Instant, Double>> raw, double scale) {
        if (raw == null) return List.of();
        return raw.stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new DbInstanceTimeseriesDto.Point(
                        e.getKey().toEpochMilli(), round2(e.getValue() * scale)))
                .collect(java.util.stream.Collectors.toList());
    }

    private String sanitizeId(String instanceId) {
        String s = "i" + instanceId.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private MetricDataQuery buildMetricQuery(String id, String namespace, String metricName,
                                              String dbInstanceId, int periodSeconds) {
        return MetricDataQuery.builder()
                .id(id)
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder()
                                .namespace(namespace)
                                .metricName(metricName)
                                .dimensions(Dimension.builder()
                                        .name("DBInstanceIdentifier")
                                        .value(dbInstanceId)
                                        .build())
                                .build())
                        .period(periodSeconds)
                        .stat("Average")
                        .build())
                .returnData(true)
                .build();
    }

    /** Collect average of all data points for each MetricDataResult into valueMap. */
    private void collectMetricValues(GetMetricDataResponse resp, Map<String, Double> valueMap) {
        for (MetricDataResult result : resp.metricDataResults()) {
            List<Double> values = result.values();
            if (values == null || values.isEmpty()) continue;
            double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            // Accumulate across pages by averaging with existing value
            valueMap.merge(result.id(), avg, (existing, newVal) -> (existing + newVal) / 2.0);
        }
    }

    private DbInstanceDto toInstanceDto(DBInstance db, String region) {
        DbInstanceDto dto = new DbInstanceDto();
        dto.setInstanceId(db.dbInstanceIdentifier());
        dto.setInstanceClass(db.dbInstanceClass());
        dto.setEngine(db.engine());
        dto.setEngineVersion(db.engineVersion());
        dto.setStatus(db.dbInstanceStatus());
        dto.setRegion(region);
        dto.setMultiAz(Boolean.TRUE.equals(db.multiAZ()) ? "Yes" : "No");
        dto.setStorageType(db.storageType());
        dto.setAllocatedStorage(db.allocatedStorage() != null ? db.allocatedStorage() : 0);
        dto.setPubliclyAccessible(Boolean.TRUE.equals(db.publiclyAccessible()));
        dto.setStorageEncrypted(Boolean.TRUE.equals(db.storageEncrypted()));
        int retention = db.backupRetentionPeriod() != null ? db.backupRetentionPeriod() : 0;
        dto.setBackupEnabled(retention > 0);
        dto.setBackupRetentionDays(String.valueOf(retention));
        dto.setEndpoint(db.endpoint() != null ? db.endpoint().address() : "N/A");
        dto.setEstimatedMonthlyCost(estimateMonthlyCost(db.dbInstanceClass()));
        dto.setMaxConnections(MAX_CONNECTIONS_MAP.getOrDefault(db.dbInstanceClass(), 100));
        return dto;
    }

    // =========================================================================
    // PUBLIC API — SLOW QUERY ANALYSIS (CloudWatch Logs)
    // =========================================================================

    /**
     * Fetch top slow queries from CloudWatch Logs.
     * Log group: /aws/rds/instance/{instanceId}/slowquery  (MySQL)
     *            /aws/rds/instance/{instanceId}/postgresql  (PostgreSQL)
     * Returns logsEnabled=false gracefully if log export is not configured.
     * Cached 30 min — log data is near real-time but expensive to scan.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<SlowQueriesResponseDto> getSlowQueries(String accountId, boolean forceRefresh) {
        String cacheKey = "dbops:aws:slowqueries:" + accountId;

        if (!forceRefresh) {
            Optional<SlowQueriesResponseDto> redis = redisCache.get(cacheKey, new TypeReference<>() {});
            if (redis.isPresent()) return CompletableFuture.completedFuture(redis.get());
        }

        return getDbInventory(accountId, false).thenApply(instances -> {
            if (instances.isEmpty()) {
                return new SlowQueriesResponseDto(false, "No RDS instances found.", Collections.emptyList());
            }
            try {
                CloudAccount account = getAccount(accountId);
                List<SlowQueryDto> allQueries = new ArrayList<>();
                boolean anyEnabled = false;

                Instant since = Instant.now().minus(7, ChronoUnit.DAYS);

                for (DbInstanceDto inst : instances) {
                    String logGroup = inst.getEngine().startsWith("postgres")
                            ? "/aws/rds/instance/" + inst.getInstanceId() + "/postgresql"
                            : "/aws/rds/instance/" + inst.getInstanceId() + "/slowquery";

                    try {
                        CloudWatchLogsClient logs = awsClientProvider.getCloudWatchLogsClient(account, inst.getRegion());
                        FilterLogEventsResponse resp = logs.filterLogEvents(
                                FilterLogEventsRequest.builder()
                                        .logGroupName(logGroup)
                                        .startTime(since.toEpochMilli())
                                        .limit(200)
                                        .build());

                        if (!resp.events().isEmpty()) {
                            anyEnabled = true;
                            List<SlowQueryDto> parsed = inst.getEngine().startsWith("postgres")
                                    ? parsePgLogs(resp.events(), inst.getInstanceId(), inst.getRegion())
                                    : parseMysqlSlowLogs(resp.events(), inst.getInstanceId(), inst.getRegion());
                            allQueries.addAll(parsed);
                        }
                    } catch (ResourceNotFoundException e) {
                        logger.debug("[DbOps] Slow query log group {} not found for instance {}", logGroup, inst.getInstanceId());
                    } catch (Exception e) {
                        logger.debug("[DbOps] Could not fetch slow query logs for {}: {}", inst.getInstanceId(), e.getMessage());
                    }
                }

                allQueries.sort(Comparator.comparingDouble(SlowQueryDto::getQueryTimeSeconds).reversed());
                List<SlowQueryDto> top20 = allQueries.stream().limit(20).collect(Collectors.toList());

                String msg = anyEnabled ? null
                        : "Slow query logs are not exported to CloudWatch. Enable via RDS → Logs & Monitoring → Log exports → slowquery (MySQL) or log_min_duration_statement (PostgreSQL).";

                SlowQueriesResponseDto result = new SlowQueriesResponseDto(anyEnabled, msg, top20);
                redisCache.put(cacheKey, result, 30);
                return result;
            } catch (Exception e) {
                logger.error("❌ [DbOps] Slow queries error for account {}: {}", accountId, e.getMessage());
                return new SlowQueriesResponseDto(false, "Error fetching slow query data.", Collections.emptyList());
            }
        });
    }

    /** Parse MySQL slow query log entries from CloudWatch log events. */
    private List<SlowQueryDto> parseMysqlSlowLogs(List<FilteredLogEvent> events,
                                                   String instanceId, String region) {
        List<SlowQueryDto> queries = new ArrayList<>();
        // Collect all messages into one block for line-by-line parsing
        List<String> lines = new ArrayList<>();
        for (FilteredLogEvent e : events) {
            if (e.message() != null) Collections.addAll(lines, e.message().split("\n"));
        }

        double queryTime = 0, lockTime = 0;
        int rowsSent = 0, rowsExamined = 0;
        String timestamp = "";
        StringBuilder sqlBuilder = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("# Time:")) {
                // Flush previous query
                if (sqlBuilder.length() > 0 && queryTime > 0) {
                    queries.add(new SlowQueryDto(instanceId, region, round2(queryTime), round2(lockTime),
                            rowsExamined, rowsSent, truncateSql(sqlBuilder.toString()), timestamp));
                }
                timestamp = line.substring(7).trim();
                sqlBuilder = new StringBuilder();
                queryTime = lockTime = 0; rowsSent = rowsExamined = 0;
            } else if (line.startsWith("# Query_time:")) {
                queryTime  = extractDouble(line, "Query_time:");
                lockTime   = extractDouble(line, "Lock_time:");
                rowsSent   = (int) extractDouble(line, "Rows_sent:");
                rowsExamined = (int) extractDouble(line, "Rows_examined:");
            } else if (!line.startsWith("#") && !line.startsWith("SET timestamp") && !line.isEmpty()) {
                if (sqlBuilder.length() > 0) sqlBuilder.append(" ");
                sqlBuilder.append(line);
            }
        }
        // Flush last
        if (sqlBuilder.length() > 0 && queryTime > 0) {
            queries.add(new SlowQueryDto(instanceId, region, round2(queryTime), round2(lockTime),
                    rowsExamined, rowsSent, truncateSql(sqlBuilder.toString()), timestamp));
        }
        return queries;
    }

    /** Parse PostgreSQL log entries (duration: X ms  statement: SQL). */
    private List<SlowQueryDto> parsePgLogs(List<FilteredLogEvent> events,
                                            String instanceId, String region) {
        List<SlowQueryDto> queries = new ArrayList<>();
        for (FilteredLogEvent e : events) {
            String msg = e.message();
            if (msg == null || !msg.contains("duration:") || !msg.contains("statement:")) continue;
            try {
                int durIdx = msg.indexOf("duration:") + 9;
                int msIdx  = msg.indexOf(" ms", durIdx);
                double ms  = Double.parseDouble(msg.substring(durIdx, msIdx).trim());

                int stIdx  = msg.indexOf("statement:") + 10;
                String sql = msg.substring(stIdx).trim();

                String ts  = msg.length() > 23 ? msg.substring(0, 23) : "";
                queries.add(new SlowQueryDto(instanceId, region, round2(ms / 1000.0), 0, 0, 0,
                        truncateSql(sql), ts));
            } catch (Exception ignored) {}
        }
        return queries;
    }

    private double extractDouble(String line, String key) {
        try {
            int idx = line.indexOf(key);
            if (idx < 0) return 0;
            String rest = line.substring(idx + key.length()).trim();
            return Double.parseDouble(rest.split("\\s+")[0]);
        } catch (Exception e) { return 0; }
    }

    private String truncateSql(String sql) {
        sql = sql.trim();
        return sql.length() > 300 ? sql.substring(0, 297) + "..." : sql;
    }

    /** Persist data to DB cache as JSON (fallback when Redis is cold). */
    private void saveToDb(String key, Object data) {
        try {
            CachedData entry = new CachedData();
            entry.setCacheKey(key);
            entry.setJsonData(objectMapper.writeValueAsString(data));
            entry.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entry);
        } catch (Exception e) {
            logger.warn("⚠️ [DbOps] Failed to persist DB cache for key {}: {}", key, e.getMessage());
        }
    }

    /** Read from DB cache (warm fallback for cold Redis). */
    private <T> Optional<T> getFromDb(String key, TypeReference<T> typeRef) {
        return cachedDataRepository.findById(key).map(entry -> {
            try {
                return objectMapper.readValue(entry.getJsonData(), typeRef);
            } catch (Exception e) {
                logger.warn("⚠️ [DbOps] Failed to parse DB cache for key {}: {}", key, e.getMessage());
                return null;
            }
        });
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ── Agent Query Stats ─────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/agent-query-stats
     *
     * Returns aggregated query statistics pushed by the XamOps DB agent
     * (performance_schema for MySQL, pg_stat_statements for Postgres).
     * Data is sourced from agent_data_cache (dataType = "db-query-stats").
     *
     * Returns empty list if no agent has pushed data yet for this account.
     */
    @Async
    public CompletableFuture<List<AgentQueryStatDto>> getAgentQueryStats(String accountId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Resolve the CloudAccount internal ID from the accountId string
                Optional<CloudAccount> accountOpt = cloudAccountRepository
                        .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId);
                if (accountOpt.isEmpty()) {
                    logger.warn("[DbOps] getAgentQueryStats: account not found: {}", accountId);
                    return List.of();
                }
                Long internalAccountId = accountOpt.get().getId();

                List<AgentQueryStatDto> merged = new ArrayList<>();

                // 2. Read from db_agent_data_cache (DB-only agents registered via wizard)
                List<DbAgentConfig> dbAgents = dbAgentConfigRepository.findByCloudAccountId(internalAccountId);
                for (DbAgentConfig agent : dbAgents) {
                    Optional<DbAgentDataCache> cacheOpt = dbAgentDataCacheRepository
                            .findByDbAgentIdAndDataType(agent.getId(), "db-query-stats");
                    if (cacheOpt.isEmpty()) continue;
                    try {
                        List<AgentQueryStatDto> batch = objectMapper.readValue(
                                cacheOpt.get().getDataJson(),
                                new TypeReference<List<AgentQueryStatDto>>() {});
                        String ts = cacheOpt.get().getCollectedAt() != null
                                ? cacheOpt.get().getCollectedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) : null;
                        batch.forEach(s -> s.setCollectedAt(ts));
                        merged.addAll(batch);
                    } catch (Exception e) {
                        logger.warn("[DbOps] getAgentQueryStats: failed to parse cache for db agent {}: {}",
                                agent.getAgentName(), e.getMessage());
                    }
                }

                // 3. Also read from agent_data_cache (K8s agents that push db-query-stats)
                List<KubernetesClusterConfig> clusters = clusterConfigRepository.findByCloudAccountId(internalAccountId);
                for (KubernetesClusterConfig cluster : clusters) {
                    Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                            .findByClusterConfigIdAndDataType(cluster.getId(), "db-query-stats");
                    if (cacheOpt.isEmpty()) continue;
                    try {
                        List<AgentQueryStatDto> batch = objectMapper.readValue(
                                cacheOpt.get().getDataJson(),
                                new TypeReference<List<AgentQueryStatDto>>() {});
                        String ts = cacheOpt.get().getCollectedAt() != null
                                ? cacheOpt.get().getCollectedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) : null;
                        batch.forEach(s -> s.setCollectedAt(ts));
                        merged.addAll(batch);
                    } catch (Exception e) {
                        logger.warn("[DbOps] getAgentQueryStats: failed to parse cache for cluster {}: {}",
                                cluster.getClusterName(), e.getMessage());
                    }
                }

                // 4. Sort by total time descending (slowest queries first)
                merged.sort(Comparator.comparingDouble(AgentQueryStatDto::getTotalTimeSeconds).reversed());
                logger.info("[DbOps] getAgentQueryStats: returning {} queries for account {}", merged.size(), accountId);
                return merged;

            } catch (Exception e) {
                logger.error("[DbOps] getAgentQueryStats error for {}: {}", accountId, e.getMessage());
                return List.of();
            }
        });
    }

    // =========================================================================
    // PUBLIC API — AGENT TIMESERIES (Datadog-style charts from db_agent_metrics_history)
    // =========================================================================

    /**
     * Returns time-series snapshots of db-instance-metrics for all DB agents in an account.
     * Each push from the agent is stored as an append-only row; this reads the last N hours.
     * Used to render connection count, buffer pool hit %, QPS breakdown charts.
     */
    @Async
    public CompletableFuture<List<AgentTimeseriesDto>> getAgentTimeseries(String accountId, int hours) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<CloudAccount> accountOpt = cloudAccountRepository
                        .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId);
                if (accountOpt.isEmpty()) return List.of();
                Long internalAccountId = accountOpt.get().getId();

                List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(internalAccountId);
                if (agents.isEmpty()) return List.of();

                LocalDateTime since = LocalDateTime.now().minusHours(hours);
                List<AgentTimeseriesDto> result = new ArrayList<>();

                for (DbAgentConfig agent : agents) {
                    List<DbAgentMetricsHistory> historyRows = dbAgentMetricsHistoryRepository
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
                        } catch (Exception e) {
                            logger.warn("[DbOps] getAgentTimeseries: parse failed for agent {} row {}: {}",
                                    agent.getAgentName(), row.getId(), e.getMessage());
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

                logger.info("[DbOps] getAgentTimeseries: {} agents with history for account {}", result.size(), accountId);
                return result;

            } catch (Exception e) {
                logger.error("[DbOps] getAgentTimeseries error for {}: {}", accountId, e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * Returns the latest "db-table-sizes" batch for all DB agents in the account.
     * Reads from db_agent_data_cache (upserted — always the freshest push).
     */
    public CompletableFuture<List<AgentTableSizeDto>> getAgentTableSizes(String accountId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<CloudAccount> accountOpt = cloudAccountRepository
                        .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId);
                if (accountOpt.isEmpty()) return List.of();
                Long internalAccountId = accountOpt.get().getId();

                List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(internalAccountId);
                List<AgentTableSizeDto> result = new ArrayList<>();

                for (DbAgentConfig agent : agents) {
                    Optional<DbAgentDataCache> cacheOpt = dbAgentDataCacheRepository
                            .findByDbAgentIdAndDataType(agent.getId(), "db-table-sizes");
                    if (cacheOpt.isEmpty()) continue;
                    try {
                        List<AgentTableSizeDto> tables = objectMapper.readValue(
                                cacheOpt.get().getDataJson(),
                                new TypeReference<List<AgentTableSizeDto>>() {});
                        if (tables != null) result.addAll(tables);
                    } catch (Exception e) {
                        logger.warn("[DbOps] getAgentTableSizes: parse failed for agent {}: {}", agent.getAgentName(), e.getMessage());
                    }
                }

                logger.info("[DbOps] getAgentTableSizes: {} tables for account {}", result.size(), accountId);
                return result;

            } catch (Exception e) {
                logger.error("[DbOps] getAgentTableSizes error for {}: {}", accountId, e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * Returns the latest "db-index-usage" batch for all DB agents in the account.
     */
    public CompletableFuture<List<AgentIndexUsageDto>> getAgentIndexUsage(String accountId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<CloudAccount> accountOpt = cloudAccountRepository
                        .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId);
                if (accountOpt.isEmpty()) return List.of();
                Long internalAccountId = accountOpt.get().getId();
                List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(internalAccountId);
                List<AgentIndexUsageDto> result = new ArrayList<>();
                for (DbAgentConfig agent : agents) {
                    Optional<DbAgentDataCache> cacheOpt = dbAgentDataCacheRepository
                            .findByDbAgentIdAndDataType(agent.getId(), "db-index-usage");
                    if (cacheOpt.isEmpty()) continue;
                    try {
                        List<AgentIndexUsageDto> rows = objectMapper.readValue(
                                cacheOpt.get().getDataJson(), new TypeReference<List<AgentIndexUsageDto>>() {});
                        if (rows != null) result.addAll(rows);
                    } catch (Exception e) {
                        logger.warn("[DbOps] getAgentIndexUsage: parse failed for agent {}: {}", agent.getAgentName(), e.getMessage());
                    }
                }
                return result;
            } catch (Exception e) {
                logger.error("[DbOps] getAgentIndexUsage error for {}: {}", accountId, e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * Returns the latest "db-table-activity" batch for all DB agents in the account.
     */
    public CompletableFuture<List<AgentTableActivityDto>> getAgentTableActivity(String accountId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<CloudAccount> accountOpt = cloudAccountRepository
                        .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId);
                if (accountOpt.isEmpty()) return List.of();
                Long internalAccountId = accountOpt.get().getId();
                List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(internalAccountId);
                List<AgentTableActivityDto> result = new ArrayList<>();
                for (DbAgentConfig agent : agents) {
                    Optional<DbAgentDataCache> cacheOpt = dbAgentDataCacheRepository
                            .findByDbAgentIdAndDataType(agent.getId(), "db-table-activity");
                    if (cacheOpt.isEmpty()) continue;
                    try {
                        List<AgentTableActivityDto> rows = objectMapper.readValue(
                                cacheOpt.get().getDataJson(), new TypeReference<List<AgentTableActivityDto>>() {});
                        if (rows != null) result.addAll(rows);
                    } catch (Exception e) {
                        logger.warn("[DbOps] getAgentTableActivity: parse failed for agent {}: {}", agent.getAgentName(), e.getMessage());
                    }
                }
                return result;
            } catch (Exception e) {
                logger.error("[DbOps] getAgentTableActivity error for {}: {}", accountId, e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * Returns the latest "db-vacuum-stats" batch for all DB agents in the account.
     * PostgreSQL only — contains pg_stat_user_tables vacuum/analyze history and dead tuple ratios.
     */
    public CompletableFuture<List<AgentVacuumStatDto>> getAgentVacuumStats(String accountId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<CloudAccount> accountOpt = cloudAccountRepository
                        .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId);
                if (accountOpt.isEmpty()) return List.of();
                Long internalAccountId = accountOpt.get().getId();

                List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(internalAccountId);
                List<AgentVacuumStatDto> result = new ArrayList<>();

                for (DbAgentConfig agent : agents) {
                    Optional<DbAgentDataCache> cacheOpt = dbAgentDataCacheRepository
                            .findByDbAgentIdAndDataType(agent.getId(), "db-vacuum-stats");
                    if (cacheOpt.isEmpty()) continue;
                    try {
                        List<AgentVacuumStatDto> rows = objectMapper.readValue(
                                cacheOpt.get().getDataJson(),
                                new TypeReference<List<AgentVacuumStatDto>>() {});
                        if (rows != null) result.addAll(rows);
                    } catch (Exception e) {
                        logger.warn("[DbOps] getAgentVacuumStats: parse failed for agent {}: {}", agent.getAgentName(), e.getMessage());
                    }
                }

                logger.info("[DbOps] getAgentVacuumStats: {} rows for account {}", result.size(), accountId);
                return result;

            } catch (Exception e) {
                logger.error("[DbOps] getAgentVacuumStats error for {}: {}", accountId, e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * Returns the latest "db-user-activity" batch for all DB agents in the account.
     * Contains per-user connection counts and cumulative query stats.
     */
    public CompletableFuture<List<AgentUserActivityDto>> getAgentUserActivity(String accountId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<CloudAccount> accountOpt = cloudAccountRepository
                        .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId);
                if (accountOpt.isEmpty()) return List.of();
                Long internalAccountId = accountOpt.get().getId();

                List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(internalAccountId);
                List<AgentUserActivityDto> result = new ArrayList<>();

                for (DbAgentConfig agent : agents) {
                    Optional<DbAgentDataCache> cacheOpt = dbAgentDataCacheRepository
                            .findByDbAgentIdAndDataType(agent.getId(), "db-user-activity");
                    if (cacheOpt.isEmpty()) continue;
                    try {
                        List<AgentUserActivityDto> rows = objectMapper.readValue(
                                cacheOpt.get().getDataJson(),
                                new TypeReference<List<AgentUserActivityDto>>() {});
                        if (rows != null) result.addAll(rows);
                    } catch (Exception e) {
                        logger.warn("[DbOps] getAgentUserActivity: parse failed for agent {}: {}", agent.getAgentName(), e.getMessage());
                    }
                }

                logger.info("[DbOps] getAgentUserActivity: {} users for account {}", result.size(), accountId);
                return result;

            } catch (Exception e) {
                logger.error("[DbOps] getAgentUserActivity error for {}: {}", accountId, e.getMessage());
                return List.of();
            }
        });
    }
}
