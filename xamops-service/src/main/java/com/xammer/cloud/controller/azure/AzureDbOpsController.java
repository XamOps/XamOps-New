package com.xammer.cloud.controller.azure;

import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.dbops.*;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.azure.AzureDbOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Azure DbOps REST Controller — Azure SQL / PostgreSQL / MySQL Cost & Performance Optimization
 *
 * Base path: /api/dbops/azure/{subscriptionId}
 *
 * Mirrors GcpDbOpsController — all endpoints accept ?forceRefresh=true to bypass cache.
 */
@RestController
@RequestMapping("/api/dbops/azure")
public class AzureDbOpsController {

    private static final Logger logger = LoggerFactory.getLogger(AzureDbOpsController.class);

    private final AzureDbOpsService azureDbOpsService;
    private final CloudAccountAccessService accountAccessService;
    private final DbOpsFeatureFlags dbOpsFeatureFlags;

    public AzureDbOpsController(AzureDbOpsService azureDbOpsService,
            CloudAccountAccessService accountAccessService,
            DbOpsFeatureFlags dbOpsFeatureFlags) {
        this.azureDbOpsService = azureDbOpsService;
        this.accountAccessService = accountAccessService;
        this.dbOpsFeatureFlags = dbOpsFeatureFlags;
    }

    // ── Authorization (SEC-1) ───────────────────────────────────────────────

    /**
     * Entitlement guard, run as the first synchronous statement of every handler so a denied
     * request is a deterministic 403 (via GlobalExceptionHandler) and never a lambda-swallowed
     * empty 200. {@code requireAccessByIdentifier} closes the cross-tenant IDOR; the explicit
     * Azure provider assertion stops an identifier resolving to a non-Azure row. This controller
     * is never reached cross-cloud (the AWS controller is the shared one), so the pin is safe.
     */
    private void requireAzureAccount(ClientUserDetails userDetails, String subscriptionId) {
        CloudAccount account = accountAccessService.requireAccessByIdentifier(userDetails, subscriptionId);
        if (!"Azure".equalsIgnoreCase(account.getProvider())) {
            throw new AccessDeniedException("Account not found or access denied.");
        }
    }

    // ── Inventory ─────────────────────────────────────────────────────────────

    @GetMapping("/{subscriptionId}/inventory")
    public CompletableFuture<ResponseEntity<List<DbInstanceDto>>> getInventory(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        logger.info("📦 [AzureDbOps] Inventory request — subscription: {}", subscriptionId);
        return azureDbOpsService.getDbInventory(subscriptionId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Inventory returned {} instances", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Inventory error for {}: {}", subscriptionId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Idle Detection ────────────────────────────────────────────────────────

    @GetMapping("/{subscriptionId}/idle")
    public CompletableFuture<ResponseEntity<List<DbIdleInstanceDto>>> getIdleInstances(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        // Phase 1a: fail closed when idle detection is disabled — no data leaves the server.
        if (!dbOpsFeatureFlags.isIdleEnabled()) {
            return CompletableFuture.completedFuture(ResponseEntity.ok(Collections.emptyList()));
        }
        logger.info("💤 [AzureDbOps] Idle instances request — subscription: {}", subscriptionId);
        return azureDbOpsService.getIdleInstances(subscriptionId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Idle returned {} candidates", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Idle error for {}: {}", subscriptionId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Right-Sizing ──────────────────────────────────────────────────────────

    @GetMapping("/{subscriptionId}/rightsizing")
    public CompletableFuture<ResponseEntity<List<DbRightsizingDto>>> getRightsizing(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        // Phase 1a: fail closed when right-sizing is disabled — no data leaves the server.
        if (!dbOpsFeatureFlags.isRightsizingEnabled()) {
            return CompletableFuture.completedFuture(ResponseEntity.ok(Collections.emptyList()));
        }
        logger.info("📐 [AzureDbOps] Right-sizing request — subscription: {}", subscriptionId);
        return azureDbOpsService.getRightsizingRecommendations(subscriptionId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Right-sizing returned {} recommendations", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Right-sizing error for {}: {}", subscriptionId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── DB Costs ──────────────────────────────────────────────────────────────

    @GetMapping("/{subscriptionId}/costs")
    public CompletableFuture<ResponseEntity<List<DbCostDto>>> getCosts(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        logger.info("💰 [AzureDbOps] Costs request — subscription: {}", subscriptionId);
        return azureDbOpsService.getDbCosts(subscriptionId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Costs returned {} line items", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Costs error for {}: {}", subscriptionId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Backup Waste ──────────────────────────────────────────────────────────

    @GetMapping("/{subscriptionId}/snapshots")
    public CompletableFuture<ResponseEntity<List<DbSnapshotWasteDto>>> getSnapshots(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        logger.info("📸 [AzureDbOps] Snapshots request — subscription: {}", subscriptionId);
        return azureDbOpsService.getDbSnapshots(subscriptionId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Snapshots returned {} waste entries", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Snapshots error for {}: {}", subscriptionId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Security Posture ──────────────────────────────────────────────────────

    @GetMapping("/{subscriptionId}/security")
    public CompletableFuture<ResponseEntity<List<DbSecurityCheckDto>>> getSecurityChecks(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        logger.info("🔒 [AzureDbOps] Security checks request — subscription: {}", subscriptionId);
        return azureDbOpsService.getSecurityChecks(subscriptionId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Security returned {} findings", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Security error for {}: {}", subscriptionId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Savings Summary ───────────────────────────────────────────────────────

    @GetMapping("/{subscriptionId}/summary")
    public CompletableFuture<ResponseEntity<DbSavingsSummaryDto>> getSavingsSummary(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        logger.info("📊 [AzureDbOps] Summary request — subscription: {}", subscriptionId);
        return azureDbOpsService.getSavingsSummary(subscriptionId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Summary: total cost ${}, savings potential ${}",
                            data.getTotalDbMonthlyCost(), data.getTotalPotentialSavings());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Summary error for {}: {}", subscriptionId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Slow Query Analysis ───────────────────────────────────────────────────

    @GetMapping("/{subscriptionId}/slow-queries")
    public CompletableFuture<ResponseEntity<SlowQueriesResponseDto>> getSlowQueries(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        logger.info("🐢 [AzureDbOps] Slow queries request — subscription: {}", subscriptionId);
        return azureDbOpsService.getSlowQueries(subscriptionId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Slow queries returned {} queries, logsEnabled={}",
                            data.getQueries().size(), data.isLogsEnabled());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Slow queries error for {}: {}", subscriptionId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    @GetMapping("/{subscriptionId}/metrics")
    public CompletableFuture<ResponseEntity<Map<String, DbMetricsDto>>> getMetrics(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        logger.info("📈 [AzureDbOps] Metrics request — subscription: {}", subscriptionId);
        return azureDbOpsService.getDbMetrics(subscriptionId, forceRefresh)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Metrics returned for {} instances", data.size());
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Metrics error for {}: {}", subscriptionId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // ── Agent Endpoints ───────────────────────────────────────────────────────

    @GetMapping("/{subscriptionId}/agent-instance-metrics")
    public CompletableFuture<ResponseEntity<List<AgentInstanceMetricsDto>>> getAgentInstanceMetrics(
            @PathVariable String subscriptionId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        return azureDbOpsService.getAgentInstanceMetrics(subscriptionId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{subscriptionId}/agent-query-stats")
    public CompletableFuture<ResponseEntity<List<AgentQueryStatDto>>> getAgentQueryStats(
            @PathVariable String subscriptionId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        logger.info("🔍 [AzureDbOps] Agent query stats request — subscription: {}", subscriptionId);
        return azureDbOpsService.getAgentQueryStats(subscriptionId)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Agent query stats: {} queries for subscription {}", data.size(), subscriptionId);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Agent query stats error for {}: {}", subscriptionId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    @GetMapping("/{subscriptionId}/agent-table-sizes")
    public CompletableFuture<ResponseEntity<List<AgentTableSizeDto>>> getAgentTableSizes(
            @PathVariable String subscriptionId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        return azureDbOpsService.getAgentTableSizes(subscriptionId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{subscriptionId}/agent-timeseries")
    public CompletableFuture<ResponseEntity<List<AgentTimeseriesDto>>> getAgentTimeseries(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "24") int hours,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        return azureDbOpsService.getAgentTimeseries(subscriptionId, hours)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{subscriptionId}/agent-index-usage")
    public CompletableFuture<ResponseEntity<List<AgentIndexUsageDto>>> getAgentIndexUsage(
            @PathVariable String subscriptionId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        return azureDbOpsService.getAgentIndexUsage(subscriptionId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{subscriptionId}/agent-table-activity")
    public CompletableFuture<ResponseEntity<List<AgentTableActivityDto>>> getAgentTableActivity(
            @PathVariable String subscriptionId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        return azureDbOpsService.getAgentTableActivity(subscriptionId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{subscriptionId}/agent-vacuum-stats")
    public CompletableFuture<ResponseEntity<List<AgentVacuumStatDto>>> getAgentVacuumStats(
            @PathVariable String subscriptionId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        return azureDbOpsService.getAgentVacuumStats(subscriptionId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{subscriptionId}/agent-user-activity")
    public CompletableFuture<ResponseEntity<List<AgentUserActivityDto>>> getAgentUserActivity(
            @PathVariable String subscriptionId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        return azureDbOpsService.getAgentUserActivity(subscriptionId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/{subscriptionId}/instance/{instanceId}/timeseries")
    public CompletableFuture<ResponseEntity<DbInstanceTimeseriesDto>> getInstanceTimeseries(
            @PathVariable String subscriptionId,
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "24") int hours,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireAzureAccount(userDetails, subscriptionId); // SEC-1 (synchronous-first)
        logger.info("🔍 [AzureDbOps] Timeseries request — subscription: {}, instance: {}", subscriptionId, instanceId);
        return azureDbOpsService.getInstanceTimeseries(subscriptionId, instanceId)
                .thenApply(data -> {
                    logger.info("✅ [AzureDbOps] Timeseries returned for {}", instanceId);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("❌ [AzureDbOps] Timeseries error for {}: {}", instanceId, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }
}
