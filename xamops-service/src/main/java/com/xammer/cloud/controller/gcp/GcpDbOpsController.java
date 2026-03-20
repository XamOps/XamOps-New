package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.dbops.*;
import com.xammer.cloud.service.gcp.GcpDbOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * GCP DbOps REST Controller — Cloud SQL Cost & Performance Optimization
 *
 * Base path: /api/dbops/gcp/{projectId}
 *
 * All endpoints mirror the AWS DbOpsController but operate on Cloud SQL via
 * the GCP Cloud SQL Admin API and Cloud Monitoring.
 *
 * All endpoints accept ?forceRefresh=true to bypass cache.
 */
@RestController
@RequestMapping("/api/dbops/gcp")
public class GcpDbOpsController {

    private static final Logger logger = LoggerFactory.getLogger(GcpDbOpsController.class);

    private final GcpDbOpsService gcpDbOpsService;

    public GcpDbOpsController(GcpDbOpsService gcpDbOpsService) {
        this.gcpDbOpsService = gcpDbOpsService;
    }

    // ── Inventory ─────────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/gcp/{projectId}/inventory
     * Returns all Cloud SQL instances for the GCP project.
     * Cached 24h — single Cloud SQL Admin API call.
     */
    @GetMapping("/{projectId}/inventory")
    public CompletableFuture<ResponseEntity<List<DbInstanceDto>>> getInventory(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("📦 [GcpDbOps] Inventory request — project: {}", projectId);
        return gcpDbOpsService.getDbInventory(projectId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [GcpDbOps] Inventory returned {} instances", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [GcpDbOps] Inventory error for {}: {}", projectId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Idle Detection ────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/gcp/{projectId}/idle
     * Returns Cloud SQL instances with avg CPU ≤ 5% and connections ≤ 1 over 7 days.
     */
    @GetMapping("/{projectId}/idle")
    public CompletableFuture<ResponseEntity<List<DbIdleInstanceDto>>> getIdleInstances(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("💤 [GcpDbOps] Idle instances request — project: {}", projectId);
        return gcpDbOpsService.getIdleInstances(projectId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [GcpDbOps] Idle returned {} candidates", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [GcpDbOps] Idle error for {}: {}", projectId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Right-Sizing ──────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/gcp/{projectId}/rightsizing
     * Returns instances that can be downsized based on low CPU utilization.
     */
    @GetMapping("/{projectId}/rightsizing")
    public CompletableFuture<ResponseEntity<List<DbRightsizingDto>>> getRightsizing(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("📐 [GcpDbOps] Right-sizing request — project: {}", projectId);
        return gcpDbOpsService.getRightsizingRecommendations(projectId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [GcpDbOps] Right-sizing returned {} recommendations", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [GcpDbOps] Right-sizing error for {}: {}", projectId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── DB Costs ──────────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/gcp/{projectId}/costs
     * Returns estimated costs by region derived from instance tier pricing.
     * Cached 6h.
     */
    @GetMapping("/{projectId}/costs")
    public CompletableFuture<ResponseEntity<List<DbCostDto>>> getCosts(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("💰 [GcpDbOps] Costs request — project: {}", projectId);
        return gcpDbOpsService.getDbCosts(projectId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [GcpDbOps] Costs returned {} line items", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [GcpDbOps] Costs error for {}: {}", projectId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Backup Waste ──────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/gcp/{projectId}/snapshots
     * Returns on-demand Cloud SQL backups older than 30 days with estimated storage cost.
     * Cached 24h.
     */
    @GetMapping("/{projectId}/snapshots")
    public CompletableFuture<ResponseEntity<List<DbSnapshotWasteDto>>> getSnapshots(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("📸 [GcpDbOps] Backups request — project: {}", projectId);
        return gcpDbOpsService.getDbSnapshots(projectId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [GcpDbOps] Backups returned {} waste entries", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [GcpDbOps] Backups error for {}: {}", projectId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Security Posture ──────────────────────────────────────────────────────

    /**
     * GET /api/dbops/gcp/{projectId}/security
     * Returns security check results: public IP, encryption, backups, HA.
     */
    @GetMapping("/{projectId}/security")
    public CompletableFuture<ResponseEntity<List<DbSecurityCheckDto>>> getSecurityChecks(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("🔒 [GcpDbOps] Security checks request — project: {}", projectId);
        return gcpDbOpsService.getSecurityChecks(projectId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [GcpDbOps] Security returned {} findings", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [GcpDbOps] Security error for {}: {}", projectId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Savings Summary ───────────────────────────────────────────────────────

    /**
     * GET /api/dbops/gcp/{projectId}/summary
     * Returns aggregate savings potential across idle, right-sizing, and backup waste.
     */
    @GetMapping("/{projectId}/summary")
    public CompletableFuture<ResponseEntity<DbSavingsSummaryDto>> getSavingsSummary(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("📊 [GcpDbOps] Summary request — project: {}", projectId);
        return gcpDbOpsService.getSavingsSummary(projectId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [GcpDbOps] Summary: total cost ${}, savings potential ${}",
                            data.getTotalDbMonthlyCost(), data.getTotalPotentialSavings());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [GcpDbOps] Summary error for {}: {}", projectId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Slow Query Analysis ───────────────────────────────────────────────────

    /**
     * GET /api/dbops/gcp/{projectId}/slow-queries
     * Returns slow queries — requires DB agent or Cloud Logging export.
     */
    @GetMapping("/{projectId}/slow-queries")
    public CompletableFuture<ResponseEntity<SlowQueriesResponseDto>> getSlowQueries(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("🐢 [GcpDbOps] Slow queries request — project: {}", projectId);
        return gcpDbOpsService.getSlowQueries(projectId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [GcpDbOps] Slow queries returned {} queries, logsEnabled={}",
                            data.getQueries().size(), data.isLogsEnabled());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [GcpDbOps] Slow queries error for {}: {}", projectId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Metrics (raw) ─────────────────────────────────────────────────────────

    /**
     * GET /api/dbops/gcp/{projectId}/metrics
     * Returns raw 7-day Cloud Monitoring average metrics per instance.
     * Cached 12h.
     */
    @GetMapping("/{projectId}/metrics")
    public CompletableFuture<ResponseEntity<Map<String, DbMetricsDto>>> getMetrics(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("📈 [GcpDbOps] Metrics request — project: {}", projectId);
        return gcpDbOpsService.getDbMetrics(projectId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [GcpDbOps] Metrics returned for {} instances", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [GcpDbOps] Metrics error for {}: {}", projectId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Agent Endpoints ───────────────────────────────────────────────────────

    @GetMapping("/{projectId}/agent-instance-metrics")
    public CompletableFuture<ResponseEntity<List<AgentInstanceMetricsDto>>> getAgentInstanceMetrics(
            @PathVariable String projectId) {
        return gcpDbOpsService.getAgentInstanceMetrics(projectId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{projectId}/agent-query-stats")
    public CompletableFuture<ResponseEntity<List<AgentQueryStatDto>>> getAgentQueryStats(
            @PathVariable String projectId) {
        logger.info("🔍 [GcpDbOps] Agent query stats request — project: {}", projectId);
        return gcpDbOpsService.getAgentQueryStats(projectId)
                .thenApply(data -> {
                    logger.info("✅ [GcpDbOps] Agent query stats: {} queries for project {}", data.size(), projectId);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [GcpDbOps] Agent query stats error for {}: {}", projectId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    @GetMapping("/{projectId}/agent-table-sizes")
    public CompletableFuture<ResponseEntity<List<AgentTableSizeDto>>> getAgentTableSizes(
            @PathVariable String projectId) {
        return gcpDbOpsService.getAgentTableSizes(projectId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{projectId}/agent-timeseries")
    public CompletableFuture<ResponseEntity<List<AgentTimeseriesDto>>> getAgentTimeseries(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "24") int hours) {
        return gcpDbOpsService.getAgentTimeseries(projectId, hours)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{projectId}/agent-index-usage")
    public CompletableFuture<ResponseEntity<List<AgentIndexUsageDto>>> getAgentIndexUsage(
            @PathVariable String projectId) {
        return gcpDbOpsService.getAgentIndexUsage(projectId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{projectId}/agent-table-activity")
    public CompletableFuture<ResponseEntity<List<AgentTableActivityDto>>> getAgentTableActivity(
            @PathVariable String projectId) {
        return gcpDbOpsService.getAgentTableActivity(projectId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{projectId}/agent-vacuum-stats")
    public CompletableFuture<ResponseEntity<List<AgentVacuumStatDto>>> getAgentVacuumStats(
            @PathVariable String projectId) {
        return gcpDbOpsService.getAgentVacuumStats(projectId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{projectId}/agent-user-activity")
    public CompletableFuture<ResponseEntity<List<AgentUserActivityDto>>> getAgentUserActivity(
            @PathVariable String projectId) {
        return gcpDbOpsService.getAgentUserActivity(projectId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }
}
