package com.xammer.cloud.controller;

import com.xammer.cloud.dto.dbops.*;
import com.xammer.cloud.dto.dbops.AgentTimeseriesDto;
import com.xammer.cloud.service.DbOpsCostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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

    public DbOpsController(DbOpsCostService dbOpsCostService) {
        this.dbOpsCostService = dbOpsCostService;
    }

    // ── Inventory ─────────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/aws/{accountId}/inventory
     * Returns all RDS instances across regions for the account.
     * Cached 24h — API cost: 1 × describeDBInstances per region.
     */
    @GetMapping("/{accountId}/inventory")
    public CompletableFuture<ResponseEntity<List<DbInstanceDto>>> getInventory(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

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
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

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
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

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
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

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
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

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
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

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
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

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
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

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
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

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
            @PathVariable String accountId) {
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
            @PathVariable String accountId) {

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
            @PathVariable String accountId) {

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
            @RequestParam(defaultValue = "24") int hours) {

        return dbOpsCostService.getAgentTimeseries(accountId, hours)
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
            @RequestParam(defaultValue = "24") int hours) {

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
}
