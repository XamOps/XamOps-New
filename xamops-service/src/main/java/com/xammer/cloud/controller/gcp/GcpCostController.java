package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpBillingStatusDto;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.service.gcp.GcpBillingStatusService;
import com.xammer.cloud.service.gcp.GcpCostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/xamops/gcp/costs")
@Slf4j
public class GcpCostController {

    private final GcpCostService gcpCostService;
    private final GcpBillingStatusService gcpBillingStatusService;

    public GcpCostController(GcpCostService gcpCostService,
            GcpBillingStatusService gcpBillingStatusService) {
        this.gcpCostService = gcpCostService;
        this.gcpBillingStatusService = gcpBillingStatusService;
    }

    /**
     * GET /api/xamops/gcp/costs/billing-status
     * Why this project's cost data looks the way it does.
     *
     * <p>Separate endpoint rather than an envelope on {@code /breakdown} because that
     * endpoint returns a bare array which existing callers iterate directly — wrapping it
     * would break them. This is cheap enough to call alongside every cost page load: it
     * performs BigQuery metadata reads only, never a query.
     */
    @GetMapping("/billing-status")
    public CompletableFuture<ResponseEntity<GcpBillingStatusDto>> getBillingStatus(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        return CompletableFuture
                .supplyAsync(() -> gcpBillingStatusService.getStatus(accountId, forceRefresh))
                .orTimeout(15, TimeUnit.SECONDS)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    // A failure to explain the absence of data must not itself become a
                    // silent absence of data — that is the bug this endpoint exists to fix.
                    log.error("[GcpCost] billing-status ERROR — account={}", accountId, ex);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    return ResponseEntity.ok(GcpBillingStatusDto.unknown(
                            accountId, null, cause.getMessage()));
                });
    }

    /**
     * GET /api/xamops/gcp/costs/breakdown
     * Cost breakdown by SERVICE, REGION, or PROJECT with optional date range.
     */
    @GetMapping("/breakdown")
    public CompletableFuture<ResponseEntity<?>> getCostBreakdown(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "SERVICE") String groupBy,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpCost] breakdown START — account={} groupBy={} forceRefresh={} dates={}-{}",
                accountId, groupBy, forceRefresh, startDate, endDate);
        final long startMs = System.currentTimeMillis();

        return gcpCostService.getCostBreakdown(accountId, groupBy, startDate, endDate, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(data -> {
                    log.info("[GcpCost] breakdown OK — account={} groupBy={} durationMs={}",
                            accountId, groupBy, System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpCost] breakdown TIMEOUT — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpCost] breakdown ERROR — account={}", accountId, ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * GET /api/xamops/gcp/costs/historical
     * 6-month monthly cost history (closed months from snapshots, current month from BigQuery).
     */
    @GetMapping("/historical")
    public CompletableFuture<ResponseEntity<?>> getHistoricalCosts(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpCost] historical START — account={} forceRefresh={}", accountId, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return gcpCostService.getHistoricalCosts(accountId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(data -> {
                    log.info("[GcpCost] historical OK — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpCost] historical TIMEOUT — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpCost] historical ERROR — account={}", accountId, ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * GET /api/xamops/gcp/costs/historical-trend
     * Daily cost trend for the last N days, optionally filtered by service.
     */
    @GetMapping("/historical-trend")
    public CompletableFuture<ResponseEntity<?>> getHistoricalTrend(
            @RequestParam String accountId,
            @RequestParam(required = false) String serviceName,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpCost] historical-trend START — account={} service={} days={} forceRefresh={}",
                accountId, serviceName, days, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return gcpCostService.getDailyCostsForForecast(accountId, serviceName, days, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(data -> {
                    log.info("[GcpCost] historical-trend OK — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpCost] historical-trend TIMEOUT — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpCost] historical-trend ERROR — account={}", accountId, ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * GET /api/xamops/gcp/costs/summary
     * Parallel fetch of service breakdown + region breakdown + 6-month historical.
     */
    @GetMapping("/summary")
    public CompletableFuture<ResponseEntity<?>> getCostSummary(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpCost] summary START — account={} days={} forceRefresh={}", accountId, days, forceRefresh);
        final long startMs = System.currentTimeMillis();

        String start = startDate != null ? startDate : LocalDate.now().withDayOfMonth(1).toString();
        String end   = endDate   != null ? endDate   : LocalDate.now().toString();

        CompletableFuture<List<GcpCostDto>> svcFuture  = gcpCostService.getCostBreakdown(accountId, "SERVICE", start, end, forceRefresh);
        CompletableFuture<List<GcpCostDto>> rgnFuture  = gcpCostService.getCostBreakdown(accountId, "REGION",  start, end, forceRefresh);
        CompletableFuture<List<GcpCostDto>> histFuture = gcpCostService.getHistoricalCosts(accountId, forceRefresh);

        return CompletableFuture.allOf(svcFuture, rgnFuture, histFuture)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(v -> {
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("serviceBreakdown", svcFuture.join());
                    summary.put("regionBreakdown",  rgnFuture.join());
                    summary.put("historicalTrend",  histFuture.join());
                    summary.put("accountId", accountId);
                    summary.put("days", days);
                    log.info("[GcpCost] summary OK — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(summary);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpCost] summary TIMEOUT — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyMap());
                    }
                    log.error("[GcpCost] summary ERROR — account={}", accountId, ex);
                    return ResponseEntity.ok(Collections.emptyMap());
                });
    }

    /**
     * GET /api/xamops/gcp/costs/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "healthy");
        health.put("service", "GCP Cost API");
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }
}
