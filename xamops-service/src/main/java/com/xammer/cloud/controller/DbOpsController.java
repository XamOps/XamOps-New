package com.xammer.cloud.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.domain.ExplainRequest;
import com.xammer.cloud.dto.dbops.*;
import com.xammer.cloud.dto.dbops.AgentTimeseriesDto;
import com.xammer.cloud.dto.dbops.MongoInstanceMetricsDto;
import com.xammer.cloud.dto.dbops.MongoSlowOpDto;
import com.xammer.cloud.dto.dbops.MongoIndexStatDto;
import com.xammer.cloud.dto.dbops.RedisInstanceMetricsDto;
import com.xammer.cloud.dto.dbops.RedisSlowLogDto;
import com.xammer.cloud.dto.dbops.RedisKeyspaceDto;
import com.xammer.cloud.domain.DbHealthAlert;
import com.xammer.cloud.domain.DbHealthSnapshot;
import com.xammer.cloud.dto.dbops.DbHealthAlertDto;
import com.xammer.cloud.dto.dbops.DbHealthSnapshotDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbHealthAlertRepository;
import com.xammer.cloud.repository.DbHealthSnapshotRepository;
import com.xammer.cloud.repository.ExplainRequestRepository;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.DbOpsCostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * DbOps REST Controller — AWS Database Cost Optimization
 *
 * Base path: /api/dbops/aws/{accountId}
 *
 * All endpoints accept ?forceRefresh=true to bypass cache and hit AWS APIs directly.
 * Default behaviour always serves from Redis or DB cache when available.
 */
@RestController
@RequestMapping("/api/dbops/aws")
public class DbOpsController {

    private static final Logger logger = LoggerFactory.getLogger(DbOpsController.class);

    private final DbOpsCostService dbOpsCostService;
    private final CloudAccountRepository cloudAccountRepository;
    private final DbAgentConfigRepository dbAgentConfigRepository;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final ExplainRequestRepository explainRequestRepository;
    private final DbHealthSnapshotRepository dbHealthSnapshotRepository;
    private final DbHealthAlertRepository dbHealthAlertRepository;
    private final CloudAccountAccessService cloudAccountAccessService;
    private final DbOpsFeatureFlags dbOpsFeatureFlags;
    private final ObjectMapper objectMapper;

    public DbOpsController(
            DbOpsCostService dbOpsCostService,
            CloudAccountRepository cloudAccountRepository,
            DbAgentConfigRepository dbAgentConfigRepository,
            DbAgentDataCacheRepository dbAgentDataCacheRepository,
            ExplainRequestRepository explainRequestRepository,
            DbHealthSnapshotRepository dbHealthSnapshotRepository,
            DbHealthAlertRepository dbHealthAlertRepository,
            CloudAccountAccessService cloudAccountAccessService,
            DbOpsFeatureFlags dbOpsFeatureFlags) {
        this.dbOpsCostService = dbOpsCostService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.dbAgentConfigRepository = dbAgentConfigRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.explainRequestRepository = explainRequestRepository;
        this.dbHealthSnapshotRepository = dbHealthSnapshotRepository;
        this.dbHealthAlertRepository = dbHealthAlertRepository;
        this.cloudAccountAccessService = cloudAccountAccessService;
        this.dbOpsFeatureFlags = dbOpsFeatureFlags;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ── Authorization (SEC-1) ───────────────────────────────────────────────
    //
    // Every handler asserts entitlement as its FIRST synchronous statement — before any
    // CompletableFuture is built and before any try/catch — so a denied request is a
    // deterministic 403 (via GlobalExceptionHandler) and is never swallowed into an empty 200
    // by an async lambda or a catch block. Do NOT move these calls into a .thenApply(...).
    //
    // The access method is split (design M-1): the 19 AWS-only endpoints use requireAwsAccess
    // (provider-pinned, no PK fallback — the sibling AWS-controller idiom); the 12 deliberately
    // provider-shared endpoints (wait-events, diagnostics, mongo/redis, health-history,
    // instance timeseries, explain-*) use requireAccessByIdentifier, because the GCP/Azure
    // DataOps drawer reaches those AWS endpoints cross-cloud and an AWS provider pin would 403
    // those agent panels. Both still close the cross-tenant IDOR; only the provider pin differs.

    // ── Inventory ─────────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/inventory
     * Returns all RDS instances across regions for the account.
     * Cached 24h — API cost: 1 × describeDBInstances per region.
     */
    @GetMapping("/{accountId}/inventory")
    public CompletableFuture<ResponseEntity<List<DbInstanceDto>>> getInventory(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        logger.info("📦 [DbOps] Inventory request — account: {}", accountId);
        return dbOpsCostService.getDbInventory(accountId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Inventory returned {} instances", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Inventory error for {}: {}", accountId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Idle Detection ────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/idle
     * Returns DB instances with avg CPU ≤ 5% and avg connections ≤ 1 over 7 days.
     * 0 extra API calls — derived from cached inventory + CloudWatch metrics.
     */
    @GetMapping("/{accountId}/idle")
    public CompletableFuture<ResponseEntity<List<DbIdleInstanceDto>>> getIdleInstances(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        // Phase 1a: fail closed when idle detection is disabled — no data leaves the server.
        if (!dbOpsFeatureFlags.isIdleEnabled()) {
            return CompletableFuture.completedFuture(ResponseEntity.ok(Collections.emptyList()));
        }
        logger.info("💤 [DbOps] Idle instances request — account: {}", accountId);
        return dbOpsCostService.getIdleInstances(accountId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Idle returned {} candidates", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Idle error for {}: {}", accountId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Right-Sizing ──────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/rightsizing
     * Returns instances that can be downsized (CPU ≤ 10%, active connections present).
     * 0 extra API calls — derived from cached inventory + CloudWatch metrics.
     */
    @GetMapping("/{accountId}/rightsizing")
    public CompletableFuture<ResponseEntity<List<DbRightsizingDto>>> getRightsizing(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        // Phase 1a: fail closed when right-sizing is disabled — no data leaves the server.
        if (!dbOpsFeatureFlags.isRightsizingEnabled()) {
            return CompletableFuture.completedFuture(ResponseEntity.ok(Collections.emptyList()));
        }
        logger.info("📐 [DbOps] Right-sizing request — account: {}", accountId);
        return dbOpsCostService.getRightsizingRecommendations(accountId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Right-sizing returned {} recommendations", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Right-sizing error for {}: {}", accountId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── DB Costs ──────────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/costs
     * Returns month-to-date costs broken down by DB service and region.
     * Cached 6h — API cost: 1 × Cost Explorer call (filtered to DB services only).
     */
    @GetMapping("/{accountId}/costs")
    public CompletableFuture<ResponseEntity<List<DbCostDto>>> getCosts(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        logger.info("💰 [DbOps] Costs request — account: {}", accountId);
        return dbOpsCostService.getDbCosts(accountId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Costs returned {} line items", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Costs error for {}: {}", accountId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Snapshot Waste ────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/snapshots
     * Returns manual RDS snapshots older than 30 days with estimated monthly storage cost.
     * Cached 24h — API cost: 1 × describeDBSnapshots per region.
     */
    @GetMapping("/{accountId}/snapshots")
    public CompletableFuture<ResponseEntity<List<DbSnapshotWasteDto>>> getSnapshots(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        logger.info("📸 [DbOps] Snapshots request — account: {}", accountId);
        return dbOpsCostService.getDbSnapshots(accountId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Snapshots returned {} waste entries", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Snapshots error for {}: {}", accountId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Security Posture ──────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/security
     * Returns security check results: public access, encryption, backups, multi-AZ.
     * 0 extra API calls — derived from cached inventory only.
     */
    @GetMapping("/{accountId}/security")
    public CompletableFuture<ResponseEntity<List<DbSecurityCheckDto>>> getSecurityChecks(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        logger.info("🔒 [DbOps] Security checks request — account: {}", accountId);
        return dbOpsCostService.getSecurityChecks(accountId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Security returned {} findings", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Security error for {}: {}", accountId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Savings Summary ───────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/summary
     * Returns aggregate savings potential across idle, right-sizing, and snapshot waste.
     * 0 extra API calls — aggregates all cached derived data.
     */
    @GetMapping("/{accountId}/summary")
    public CompletableFuture<ResponseEntity<DbSavingsSummaryDto>> getSavingsSummary(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        logger.info("📊 [DbOps] Summary request — account: {}", accountId);
        return dbOpsCostService.getSavingsSummary(accountId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Summary: total cost ${}, savings potential ${}",
                            data.getTotalDbMonthlyCost(), data.getTotalPotentialSavings());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Summary error for {}: {}", accountId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Slow Query Analysis ───────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/slow-queries
     * Returns top 20 slow queries from CloudWatch Logs (last 7 days).
     * Returns logsEnabled=false gracefully if log export is not configured.
     * Cached 30 min.
     */
    @GetMapping("/{accountId}/slow-queries")
    public CompletableFuture<ResponseEntity<SlowQueriesResponseDto>> getSlowQueries(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        logger.info("🐢 [DbOps] Slow queries request — account: {}", accountId);
        return dbOpsCostService.getSlowQueries(accountId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Slow queries returned {} queries, logsEnabled={}",
                            data.getQueries().size(), data.isLogsEnabled());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Slow queries error for {}: {}", accountId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Metrics (raw — for debugging / advanced use) ──────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/metrics
     * Returns raw 7-day CloudWatch average metrics per instance.
     * Cached 12h — API cost: 1 × GetMetricData batch per region.
     */
    @GetMapping("/{accountId}/metrics")
    public CompletableFuture<ResponseEntity<Map<String, DbMetricsDto>>> getMetrics(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        logger.info("📈 [DbOps] Metrics request — account: {}", accountId);
        return dbOpsCostService.getDbMetrics(accountId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Metrics returned for {} instances", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Metrics error for {}: {}", accountId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Agent Query Stats ─────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/agent-instance-metrics
     * Returns server-level status metrics pushed by the DB agent (connections, QPS, buffer pool, etc.)
     */
    @GetMapping("/{accountId}/agent-instance-metrics")
    public CompletableFuture<ResponseEntity<List<AgentInstanceMetricsDto>>> getAgentInstanceMetrics(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        return dbOpsCostService.getAgentInstanceMetrics(accountId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    /**
     * GET /api/dbops/aws/{accountId}/agent-query-stats
     *
     * Returns aggregated per-query stats pushed by the XamOps DB agent.
     * Data comes from performance_schema (MySQL) or pg_stat_statements (Postgres).
     * Returns [] if no agent has pushed data yet — no error.
     */
    @GetMapping("/{accountId}/agent-query-stats")
    public CompletableFuture<ResponseEntity<List<AgentQueryStatDto>>> getAgentQueryStats(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        logger.info("🔍 [DbOps] Agent query stats request — account: {}", accountId);
        return dbOpsCostService.getAgentQueryStats(accountId)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Agent query stats: {} queries for account {}", data.size(), accountId);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Agent query stats error for {}: {}", accountId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    /**
     * GET /api/dbops/aws/{accountId}/agent-table-sizes
     *
     * Returns latest table storage breakdown pushed by the XamOps DB agent.
     * Data comes from information_schema.TABLES (MySQL) or pg_stat_user_tables (Postgres).
     * Returns [] if no agent has pushed data yet.
     */
    @GetMapping("/{accountId}/agent-table-sizes")
    public CompletableFuture<ResponseEntity<List<AgentTableSizeDto>>> getAgentTableSizes(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        return dbOpsCostService.getAgentTableSizes(accountId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    /**
     * GET /api/dbops/aws/{accountId}/agent-timeseries?hours=24
     *
     * Returns time-series snapshots from db_agent_metrics_history for all DB agents in the account.
     * Each push is an append-only row — enables Datadog-style connection, QPS, buffer pool charts.
     */
    @GetMapping("/{accountId}/agent-timeseries")
    public CompletableFuture<ResponseEntity<List<AgentTimeseriesDto>>> getAgentTimeseries(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "24") int hours,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        return dbOpsCostService.getAgentTimeseries(accountId, hours)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    /**
     * GET /api/dbops/aws/{accountId}/agent-index-usage
     */
    @GetMapping("/{accountId}/agent-index-usage")
    public CompletableFuture<ResponseEntity<List<AgentIndexUsageDto>>> getAgentIndexUsage(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        return dbOpsCostService.getAgentIndexUsage(accountId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    /**
     * GET /api/dbops/aws/{accountId}/agent-table-activity
     */
    @GetMapping("/{accountId}/agent-table-activity")
    public CompletableFuture<ResponseEntity<List<AgentTableActivityDto>>> getAgentTableActivity(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        return dbOpsCostService.getAgentTableActivity(accountId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    /**
     * GET /api/dbops/aws/{accountId}/agent-vacuum-stats
     *
     * Returns latest vacuum/analyze history and dead tuple ratios from pg_stat_user_tables.
     * PostgreSQL only — returns [] for MySQL-only accounts.
     */
    @GetMapping("/{accountId}/agent-vacuum-stats")
    public CompletableFuture<ResponseEntity<List<AgentVacuumStatDto>>> getAgentVacuumStats(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        return dbOpsCostService.getAgentVacuumStats(accountId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    /**
     * GET /api/dbops/aws/{accountId}/agent-user-activity
     *
     * Returns per-user connection counts and cumulative query stats.
     * Sourced from pg_stat_activity + pg_stat_statements (Postgres) or PROCESSLIST (MySQL).
     */
    @GetMapping("/{accountId}/agent-user-activity")
    public CompletableFuture<ResponseEntity<List<AgentUserActivityDto>>> getAgentUserActivity(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAwsAccess(userDetails, accountId); // SEC-1 (synchronous-first)
        return dbOpsCostService.getAgentUserActivity(accountId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    /**
     * GET /api/dbops/aws/{accountId}/instance/{instanceId}/timeseries?hours=24
     *
     * Returns hourly CloudWatch data points for a single RDS instance.
     * Used by the DbOps detail drawer to render time-series charts.
     * Not cached — called on demand when user opens the detail drawer.
     */
    @GetMapping("/{accountId}/instance/{instanceId}/timeseries")
    public CompletableFuture<ResponseEntity<DbInstanceTimeseriesDto>> getInstanceTimeseries(
            @PathVariable String accountId,
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "24") int hours,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId); // SEC-1 (provider-shared)
        logger.info("🔍 [DbOps] Timeseries request — account: {}, instance: {}, hours: {}", accountId, instanceId, hours);
        return dbOpsCostService.getInstanceTimeseries(accountId, instanceId, hours)
                .thenApply(data -> {
                    logger.info("✅ [DbOps] Timeseries returned for {}", instanceId);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [DbOps] Timeseries error for {}: {}", instanceId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Agent Wait Events ─────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/agent-wait-events
     *
     * Returns latest db-wait-events batches pushed by the DB agent, grouped by DSN alias.
     * Returns [] if no agent has pushed data yet.
     */
    @GetMapping("/{accountId}/agent-wait-events")
    public ResponseEntity<List<AgentWaitEventDto>> getAgentWaitEvents(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        // SEC-1 (provider-shared): entitlement only, no AWS pin — the GCP/Azure drawer reaches
        // this AWS endpoint cross-cloud, so pinning the provider would 403 those agent panels.
        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId);
        return cloudAccountRepository
                .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId)
                .map(account -> {
                    List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(account.getId());
                    List<AgentWaitEventDto> result = new ArrayList<>();
                    for (DbAgentConfig agent : agents) {
                        // wait-events are stored with per-DSN cache keys: "db-wait-events:{accountId}:{dsnAlias}"
                        // fetch all cache rows for this agent whose dataType starts with the prefix
                        List<DbAgentDataCache> rows = dbAgentDataCacheRepository.findByDbAgentId(agent.getId());
                        for (DbAgentDataCache row : rows) {
                            if (!row.getDataType().startsWith("db-wait-events:")) continue;
                            try {
                                List<AgentWaitEventDto> events = objectMapper.readValue(
                                        row.getDataJson(), new TypeReference<List<AgentWaitEventDto>>() {});
                                if (events != null) result.addAll(events);
                            } catch (Exception e) {
                                logger.warn("[DbOps] agent-wait-events parse failed for agent {}: {}",
                                        agent.getAgentName(), e.getMessage());
                            }
                        }
                    }
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.ok(List.of()));
    }

    // ── Agent Diagnostics ─────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/agent-diagnostics
     *
     * Returns latest db-diagnostics batch pushed by the DB agent (connectivity checks per DSN).
     * Returns [] if no agent has pushed data yet.
     */
    @GetMapping("/{accountId}/agent-diagnostics")
    public ResponseEntity<List<AgentDiagnosticDto>> getAgentDiagnostics(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId); // SEC-1 (provider-shared)
        return cloudAccountRepository
                .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId)
                .map(account -> {
                    List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(account.getId());
                    List<AgentDiagnosticDto> result = new ArrayList<>();
                    for (DbAgentConfig agent : agents) {
                        String cacheKey = "db-diagnostics:" + account.getId();
                        Optional<DbAgentDataCache> cacheOpt =
                                dbAgentDataCacheRepository.findByDbAgentIdAndDataType(agent.getId(), cacheKey);
                        if (cacheOpt.isEmpty()) continue;
                        try {
                            List<AgentDiagnosticDto> diags = objectMapper.readValue(
                                    cacheOpt.get().getDataJson(), new TypeReference<List<AgentDiagnosticDto>>() {});
                            if (diags != null) result.addAll(diags);
                        } catch (Exception e) {
                            logger.warn("[DbOps] agent-diagnostics parse failed for agent {}: {}",
                                    agent.getAgentName(), e.getMessage());
                        }
                    }
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.ok(List.of()));
    }

    // ── Explain Query ─────────────────────────────────────────────────────────

    /**
     * POST /api/dbops/aws/{accountId}/explain-query
     *
     * Enqueues an EXPLAIN request for the DB agent to execute.
     * Body: { agentName, dsnAlias, queryText, dbEngine }
     * Returns the new requestId so the UI can poll for the result.
     */
    @PostMapping("/{accountId}/explain-query")
    public ResponseEntity<Map<String, Object>> enqueueExplain(
            @PathVariable String accountId,
            @RequestBody ExplainQueryRequest body,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId); // SEC-1 (provider-shared)

        // SEC-2/3: Explain is disabled server-side (xamops.dbops.explain.enabled, default false).
        // Fail closed at enqueue — persist nothing, return 409 so the UI can tell "feature off"
        // (409) apart from "not entitled" (the 403 the guard above returns on this same handler).
        if (!dbOpsFeatureFlags.isExplainEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "explain_disabled",
                    "message", "Explain is temporarily unavailable."));
        }

        return cloudAccountRepository
                .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId)
                .map(account -> {
                    if (body.getAgentName() == null || body.getQueryText() == null
                            || body.getDsnAlias() == null || body.getDbEngine() == null) {
                        return ResponseEntity.badRequest()
                                .<Map<String, Object>>body(Map.of("error",
                                        "agentName, dsnAlias, queryText and dbEngine are required"));
                    }
                    ExplainRequest req = new ExplainRequest();
                    req.setId(UUID.randomUUID().toString());
                    req.setCloudAccountId(account.getId());
                    req.setAgentName(body.getAgentName());
                    req.setDsnAlias(body.getDsnAlias());
                    req.setQueryText(body.getQueryText());
                    req.setDbEngine(body.getDbEngine());
                    req.setStatus("PENDING");
                    req.setCreatedAt(LocalDateTime.now());
                    explainRequestRepository.save(req);
                    logger.info("[DbOps] explain-query enqueued: id={} agent={}", req.getId(), req.getAgentName());
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "requestId", req.getId(),
                            "status", req.getStatus()
                    ));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Cloud account not found")));
    }

    // ── Explain Result ────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/explain-result/{requestId}
     *
     * Polls for the result of a previously enqueued EXPLAIN request.
     * Returns status PENDING / IN_FLIGHT / COMPLETED / FAILED / EXPIRED.
     */
    @GetMapping("/{accountId}/explain-result/{requestId}")
    public ResponseEntity<?> getExplainResult(
            @PathVariable String accountId,
            @PathVariable String requestId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        // SEC-1 (provider-shared): entitlement first. Phase-1 residual (documented, not fixed
        // here): the row is still fetched by requestId ignoring the account, so this proves the
        // caller owns the path account but not the result row. Moot in Phase 0 — Explain is
        // disabled so no result rows are produced; scoping explain_request by account is Phase 1.
        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId);

        // SEC-2/3: same 409 as enqueue when disabled.
        if (!dbOpsFeatureFlags.isExplainEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "explain_disabled",
                    "message", "Explain is temporarily unavailable."));
        }

        Optional<ExplainRequest> reqOpt = explainRequestRepository.findById(requestId);
        if (reqOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ExplainRequest req = reqOpt.get();
        return ResponseEntity.ok(new ExplainResultDto(
                req.getId(),
                req.getStatus(),
                req.getResultJson(),
                req.getErrorMessage()
        ));
    }

    // ── DB Health Alerting ────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/health-history
     * Returns health snapshots for one instance over the last N hours (default 24).
     */
    @GetMapping("/{accountId}/health-history")
    public ResponseEntity<List<DbHealthSnapshotDto>> getHealthHistory(
            @PathVariable String accountId,
            @RequestParam String instanceId,
            @RequestParam(defaultValue = "24") int hours,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        // SEC-1 (provider-shared): guard BEFORE the try so a denied request is a 403, not
        // swallowed into an empty 200 by the catch below.
        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId);
        // Phase 1a: fail closed when health is disabled.
        if (!dbOpsFeatureFlags.isHealthEnabled()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            com.xammer.cloud.domain.CloudAccount account = getAccount(accountId);
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusHours(hours);
            List<DbHealthSnapshot> snaps = dbHealthSnapshotRepository
                .findByAccountIdAndInstanceIdAndComputedAtAfterOrderByComputedAtAsc(
                    account.getId(), instanceId, since);
            List<DbHealthSnapshotDto> result = snaps.stream().map(s -> {
                List<String> issues = java.util.List.of();
                try { issues = objectMapper.readValue(
                    s.getIssuesJson() != null ? s.getIssuesJson() : "[]",
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}); }
                catch (Exception ignored) {}
                return DbHealthSnapshotDto.from(s, issues);
            }).collect(java.util.stream.Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.warn("health-history error for {}: {}", accountId, e.getMessage());
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * GET /api/dbops/aws/{accountId}/health-snapshots
     * Returns the latest health snapshot for every DB instance under this account.
     */
    @GetMapping("/{accountId}/health-snapshots")
    public ResponseEntity<List<DbHealthSnapshotDto>> getHealthSnapshots(@PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        // SEC-1: guard BEFORE the try so a denied request is a 403, not swallowed into 200.
        cloudAccountAccessService.requireAwsAccess(userDetails, accountId);
        // Phase 1a: fail closed when health is disabled.
        if (!dbOpsFeatureFlags.isHealthEnabled()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            com.xammer.cloud.domain.CloudAccount account = getAccount(accountId);
            List<DbHealthSnapshot> snaps = dbHealthSnapshotRepository.findLatestPerInstance(account.getId());
            List<DbHealthSnapshotDto> result = snaps.stream().map(s -> {
                List<String> issues = java.util.List.of();
                try { issues = objectMapper.readValue(
                    s.getIssuesJson() != null ? s.getIssuesJson() : "[]",
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}); }
                catch (Exception ignored) {}
                return DbHealthSnapshotDto.from(s, issues);
            }).collect(java.util.stream.Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.warn("health-snapshots error for {}: {}", accountId, e.getMessage());
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * GET /api/dbops/aws/{accountId}/db-alerts
     * Returns active alerts + alerts resolved in the last 24h for the in-app feed.
     */
    @GetMapping("/{accountId}/db-alerts")
    public ResponseEntity<List<DbHealthAlertDto>> getDbAlerts(@PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        // SEC-1: guard BEFORE the try so a denied request is a 403, not swallowed into 200.
        cloudAccountAccessService.requireAwsAccess(userDetails, accountId);
        // Phase 1a: fail closed when health is disabled.
        if (!dbOpsFeatureFlags.isHealthEnabled()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            com.xammer.cloud.domain.CloudAccount account = getAccount(accountId);
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusHours(24);
            List<DbHealthAlert> alerts = dbHealthAlertRepository.findForFeed(account.getId(), since);
            return ResponseEntity.ok(alerts.stream()
                .map(DbHealthAlertDto::from)
                .collect(java.util.stream.Collectors.toList()));
        } catch (Exception e) {
            logger.warn("db-alerts error for {}: {}", accountId, e.getMessage());
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    // ── MongoDB agent endpoints ───────────────────────────────────────────────

    @GetMapping("/{accountId}/agent-mongo-metrics")
    public ResponseEntity<List<MongoInstanceMetricsDto>> getMongoMetrics(@PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId); // SEC-1 (provider-shared)
        if (!dbOpsFeatureFlags.isMongoEnabled()) { // Phase 1a: fail closed when Mongo is disabled.
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(readAgentCacheByPrefix(accountId, "mongo-instance-metrics:",
                new TypeReference<List<MongoInstanceMetricsDto>>() {}));
    }

    @GetMapping("/{accountId}/agent-mongo-slow-ops")
    public ResponseEntity<List<MongoSlowOpDto>> getMongoSlowOps(@PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId); // SEC-1 (provider-shared)
        if (!dbOpsFeatureFlags.isMongoEnabled()) { // Phase 1a: fail closed when Mongo is disabled.
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(readAgentCacheByPrefix(accountId, "mongo-slow-ops:",
                new TypeReference<List<MongoSlowOpDto>>() {}));
    }

    @GetMapping("/{accountId}/agent-mongo-index-stats")
    public ResponseEntity<List<MongoIndexStatDto>> getMongoIndexStats(@PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId); // SEC-1 (provider-shared)
        if (!dbOpsFeatureFlags.isMongoEnabled()) { // Phase 1a: fail closed when Mongo is disabled.
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(readAgentCacheByPrefix(accountId, "mongo-index-stats:",
                new TypeReference<List<MongoIndexStatDto>>() {}));
    }

    // ── Redis agent endpoints ─────────────────────────────────────────────────

    @GetMapping("/{accountId}/agent-redis-metrics")
    public ResponseEntity<List<RedisInstanceMetricsDto>> getRedisMetrics(@PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId); // SEC-1 (provider-shared)
        if (!dbOpsFeatureFlags.isRedisEnabled()) { // Phase 1a: fail closed when Redis is disabled.
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(readAgentCacheByPrefix(accountId, "redis-instance-metrics:",
                new TypeReference<List<RedisInstanceMetricsDto>>() {}));
    }

    @GetMapping("/{accountId}/agent-redis-slow-log")
    public ResponseEntity<List<RedisSlowLogDto>> getRedisSlowLog(@PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId); // SEC-1 (provider-shared)
        if (!dbOpsFeatureFlags.isRedisEnabled()) { // Phase 1a: fail closed when Redis is disabled.
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(readAgentCacheByPrefix(accountId, "redis-slow-log:",
                new TypeReference<List<RedisSlowLogDto>>() {}));
    }

    @GetMapping("/{accountId}/agent-redis-keyspace")
    public ResponseEntity<List<RedisKeyspaceDto>> getRedisKeyspace(@PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        cloudAccountAccessService.requireAccessByIdentifier(userDetails, accountId); // SEC-1 (provider-shared)
        if (!dbOpsFeatureFlags.isRedisEnabled()) { // Phase 1a: fail closed when Redis is disabled.
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(readAgentCacheByPrefix(accountId, "redis-keyspace:",
                new TypeReference<List<RedisKeyspaceDto>>() {}));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Generic helper: read all agent cache rows for an account whose dataType starts with prefix,
     * parse each as List<T>, and flatten into a single result list.
     */
    private <T> List<T> readAgentCacheByPrefix(String accountId, String prefix,
            TypeReference<List<T>> typeRef) {
        try {
            com.xammer.cloud.domain.CloudAccount account = getAccount(accountId);
            List<T> result = new ArrayList<>();
            for (DbAgentConfig agent : dbAgentConfigRepository.findByCloudAccountId(account.getId())) {
                for (DbAgentDataCache cache : dbAgentDataCacheRepository.findByDbAgentId(agent.getId())) {
                    if (cache.getDataType() == null || !cache.getDataType().startsWith(prefix)) continue;
                    if (cache.getDataJson() == null) continue;
                    try {
                        List<T> items = objectMapper.readValue(cache.getDataJson(), typeRef);
                        if (items != null) result.addAll(items);
                    } catch (Exception e) {
                        logger.warn("readAgentCacheByPrefix [{}] parse: {}", prefix, e.getMessage());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            logger.warn("readAgentCacheByPrefix [{}]: {}", prefix, e.getMessage());
            return Collections.emptyList();
        }
    }

    private com.xammer.cloud.domain.CloudAccount getAccount(String accountId) {
        return cloudAccountRepository
                .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Cloud account not found: " + accountId));
    }
}
