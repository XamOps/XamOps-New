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
     * GET /api/xamops/gcp/costs/breakdown-by-tag
     * Cost grouped by the values of one label key.
     *
     * <p>The frontend has called this since the Tag Key option was added to the groupBy dropdown
     * and it has never existed, so selecting Tag returned a 404 and the chart rendered empty with
     * nothing to say why. The service method was already here; only the route was missing.
     *
     * <p>Returns the same bare {@code GcpCostDto} array as {@code /breakdown}, because the caller
     * feeds both into the same chart. The FinOps {@code /cost-by-label} endpoint answers a similar
     * question in a different shape ({@code labelValue}/{@code cost}) and is not interchangeable.
     */
    @GetMapping("/breakdown-by-tag")
    public CompletableFuture<ResponseEntity<?>> getCostBreakdownByTag(
            @RequestParam String accountId,
            @RequestParam String tagKey,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpCost] breakdown-by-tag START — account={} tagKey={} forceRefresh={}",
                accountId, tagKey, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return gcpCostService.getCostByTag(accountId, tagKey, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(data -> {
                    log.info("[GcpCost] breakdown-by-tag OK — account={} tagKey={} durationMs={}",
                            accountId, tagKey, System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpCost] breakdown-by-tag TIMEOUT — account={} durationMs={}",
                                accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Collections.emptyList());
                    }
                    log.error("[GcpCost] breakdown-by-tag ERROR — account={}", accountId, ex);
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
     * GET /api/xamops/gcp/costs/currency
     * The currency this project's billing account is denominated in.
     *
     * <p>{@link GcpCostService#getProjectCurrencyCode} has been able to answer this for a long time —
     * it reads {@code MAX(currency)} off the billing export and caches the result for a week — and
     * nothing exposed it over HTTP. The consequence reached the screen: every GCP cost component
     * hardcodes a rupee symbol and the cost page hardcodes {@code currency="INR"} into three shared
     * components, with a code comment admitting the real value existed and was unreachable. A GCP
     * billing account is set to whatever currency the customer signed up in, so that is wrong for
     * every customer who did not sign up in rupees — and a dollar figure shown with a rupee sign
     * reads as roughly ninety times smaller than it is.
     *
     * <p>Always answers, defaulting to USD, because a page that cannot read a currency should still
     * render its numbers. Wrapped in an object rather than returned as a bare string so a later
     * addition — the symbol, the billing account id — does not change the response type.
     */
    @GetMapping("/currency")
    public CompletableFuture<ResponseEntity<Map<String, String>>> getCurrency(
            @RequestParam String accountId) {

        return CompletableFuture
                .supplyAsync(() -> gcpCostService.getProjectCurrencyCode(accountId))
                // The first call for a project runs a BigQuery metadata read plus a one-row query;
                // every call for the next week is a Redis hit. Short timeout because the KPI cards
                // are waiting on this to choose a symbol and USD is a usable answer.
                .orTimeout(15, TimeUnit.SECONDS)
                .thenApply(code -> ResponseEntity.ok(Map.of(
                        "currency", code == null || code.isBlank() ? "USD" : code)))
                .exceptionally(ex -> {
                    log.warn("[GcpCost] currency lookup failed — account={}: {}", accountId,
                            ex.getMessage());
                    return ResponseEntity.ok(Map.of("currency", "USD"));
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
