package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpCommittedUseDiscountDto;
import com.xammer.cloud.dto.gcp.GcpCudUtilizationDto;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.dto.gcp.GcpReservationPageDto;
import com.xammer.cloud.service.CacheService;
import com.xammer.cloud.service.gcp.GcpOptimizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/xamops/gcp/reservations")
@Slf4j
public class GcpReservationController {

    private final GcpOptimizationService gcpOptimizationService;
    private final CacheService cacheService;

    @Autowired
    @Qualifier("backgroundTaskExecutor")
    private Executor backgroundTaskExecutor;

    public GcpReservationController(GcpOptimizationService gcpOptimizationService, CacheService cacheService) {
        this.gcpOptimizationService = gcpOptimizationService;
        this.cacheService = cacheService;
    }

    /**
     * Gap 1: Page-bundle — all reservation domains in one call.
     * 55s timeout, forceRefresh support.
     */
    @GetMapping
    public CompletableFuture<ResponseEntity<?>> getReservationPageBundle(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpReservation] GET / START — project={} forceRefresh={}", accountId, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getReservationPageBundle(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(page -> {
                    log.info("[GcpReservation] GET / OK — durationMs={}", System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(page);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpReservation] GET / TIMEOUT — project={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new GcpReservationPageDto());
                    }
                    log.error("[GcpReservation] GET / ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(new GcpReservationPageDto());
                });
    }

    /**
     * Gap 4: CUD inventory — exposes committed use discount inventory.
     * Gap 10: forceRefresh param. Gap 11: 55s timeout.
     */
    @GetMapping("/inventory")
    public CompletableFuture<ResponseEntity<?>> getCudInventory(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpReservation] /inventory START — project={}", accountId);
        final long startMs = System.currentTimeMillis();

        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getCommittedUseDiscountsSync(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(inv -> {
                    log.info("[GcpReservation] /inventory OK — {} CUDs durationMs={}", inv.size(), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(inv);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpReservation] /inventory TIMEOUT — project={}", accountId);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpReservation] /inventory ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * Gap 2+10+11: CUD recommendations with 3-tier cache, forceRefresh, and timeout.
     */
    @GetMapping("/cuds")
    public CompletableFuture<ResponseEntity<?>> getGcpCuds(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpReservation] /cuds START — project={} forceRefresh={}", accountId, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getCudRecommendationsSync(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(recs -> {
                    log.info("[GcpReservation] /cuds OK — {} recs durationMs={}", recs.size(), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(recs);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpReservation] /cuds TIMEOUT — project={}", accountId);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpReservation] /cuds ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * Gap 3: Real CUD utilization — derived from commitment status + inventory.
     */
    @GetMapping("/utilization")
    public CompletableFuture<ResponseEntity<?>> getCudUtilization(
            @RequestParam String accountId,
            @RequestParam(required = false) String cudId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpReservation] /utilization START — project={} cudId={}", accountId, cudId);

        if (cudId != null && !cudId.isBlank()) {
            return CompletableFuture
                    .supplyAsync(() -> gcpOptimizationService.getCudUtilizationSync(accountId, cudId),
                            backgroundTaskExecutor)
                    .orTimeout(30, TimeUnit.SECONDS)
                    .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        if (cause instanceof TimeoutException)
                            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new GcpCudUtilizationDto());
                        log.error("[GcpReservation] /utilization ERROR — project={}", accountId, cause);
                        return ResponseEntity.ok(new GcpCudUtilizationDto());
                    });
        }

        // No cudId → return aggregated summary
        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getCudUtilizationSummary(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(30, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException)
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyMap());
                    log.error("[GcpReservation] /utilization summary ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyMap());
                });
    }

    /**
     * Gap 5: Historical CUD data — up to 6 monthly snapshots.
     */
    @GetMapping("/historical")
    public CompletableFuture<ResponseEntity<?>> getHistoricalCudData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpReservation] /historical START — project={}", accountId);

        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getHistoricalCudData(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(30, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException)
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    log.error("[GcpReservation] /historical ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * Gap 6: Modification recommendations — renew expired / purchase new CUDs.
     */
    @GetMapping("/modification-recommendations")
    public CompletableFuture<ResponseEntity<?>> getModificationRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpReservation] /modification-recommendations START — project={}", accountId);

        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getCudModificationRecommendations(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(30, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException)
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    log.error("[GcpReservation] /modification-recommendations ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * Gap 9: Cost-by-tag — CUD recommendation savings grouped by resource type / location tag.
     */
    @GetMapping("/cost-by-tag")
    public CompletableFuture<ResponseEntity<?>> getCostByTag(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "service") String groupBy,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpReservation] /cost-by-tag START — project={} groupBy={}", accountId, groupBy);

        return CompletableFuture
                .supplyAsync(() -> {
                    List<GcpOptimizationRecommendation> recs =
                            gcpOptimizationService.getCudRecommendationsSync(accountId, forceRefresh);
                    Map<String, Double> grouped = new java.util.LinkedHashMap<>();
                    for (GcpOptimizationRecommendation r : recs) {
                        String tag = "location".equals(groupBy) ? r.getLocation() : r.getService();
                        if (tag == null) tag = "Unknown";
                        grouped.merge(tag, r.getMonthlySavings(), Double::sum);
                    }
                    return grouped;
                }, backgroundTaskExecutor)
                .orTimeout(30, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException)
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyMap());
                    log.error("[GcpReservation] /cost-by-tag ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyMap());
                });
    }

    /**
     * Gap 7: Apply stub — logs intent, returns 202.
     * Actual GCP CUD purchase must go through Cloud Console or Billing API.
     */
    @PostMapping("/apply")
    public ResponseEntity<?> applyCud(
            @RequestParam String accountId,
            @RequestParam String region,
            @RequestParam String plan,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false, defaultValue = "0") double commitmentAmount) {

        log.info("[GcpReservation] /apply request — project={} region={} plan={}", accountId, region, plan);
        Map<String, Object> response = new HashMap<>();
        response.put("message", "CUD purchase request acknowledged. GCP requires commitments to be created via the Billing API or Cloud Console.");
        response.put("accountId", accountId);
        response.put("region", region);
        response.put("plan", plan);
        response.put("resourceType", resourceType);
        response.put("commitmentAmount", commitmentAmount);
        response.put("status", "PENDING_MANUAL_ACTION");
        return ResponseEntity.accepted().body(response);
    }

    /**
     * POST /cache/clear — evict all reservation caches for this project.
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, String>> clearCache(@RequestParam String accountId) {
        log.info("[GcpReservation] Cache clear requested for project={}", accountId);
        try {
            cacheService.evictGcpReservationCache(accountId);
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Reservation caches cleared for account: " + accountId);
            response.put("note", "Next request will serve from DB snapshot, then live API if DB is cold.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[GcpReservation] Cache clear ERROR — project={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "GCP Reservations");
        health.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.ok(health);
    }
}
