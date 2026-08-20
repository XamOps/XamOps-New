package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.service.CacheService;
import com.xammer.cloud.service.gcp.GcpOptimizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/xamops/gcp/rightsizing")
@Slf4j
public class GcpRightsizingController {

    private final GcpOptimizationService gcpOptimizationService;
    private final CacheService cacheService;

    @Autowired
    @Qualifier("backgroundTaskExecutor")
    private Executor backgroundTaskExecutor;

    public GcpRightsizingController(GcpOptimizationService gcpOptimizationService, CacheService cacheService) {
        this.gcpOptimizationService = gcpOptimizationService;
        this.cacheService = cacheService;
    }

    /**
     * GET /recommendations
     * Gap 1: forceRefresh param added.
     * Gap 6: 50s timeout — returns empty list on timeout instead of hanging.
     */
    @GetMapping("/recommendations")
    public CompletableFuture<ResponseEntity<?>> getRightsizingRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpRightsizing] /recommendations START — project={} forceRefresh={}", accountId, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getRightsizingRecommendations(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(50, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(recs -> {
                    log.info("[GcpRightsizing] /recommendations OK — {} recs durationMs={}",
                            recs.size(), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(recs);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpRightsizing] /recommendations TIMEOUT — project={} durationMs={}",
                                accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpRightsizing] /recommendations ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * GET /summary
     * Aggregated stats computed from recommendations (no extra API call).
     */
    @GetMapping("/summary")
    public CompletableFuture<ResponseEntity<?>> getRightsizingSummary(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpRightsizing] /summary START — project={}", accountId);
        final long startMs = System.currentTimeMillis();

        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getRightsizingRecommendations(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(50, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(recommendations -> {
                    double totalSavings = recommendations.stream()
                            .filter(r -> !r.isCostIncrease())
                            .mapToDouble(GcpOptimizationRecommendation::getMonthlySavings).sum();
                    double totalCostIncrease = recommendations.stream()
                            .filter(GcpOptimizationRecommendation::isCostIncrease)
                            .mapToDouble(GcpOptimizationRecommendation::getAbsoluteMonthlyCost).sum();
                    Map<String, Long> byService = recommendations.stream()
                            .collect(Collectors.groupingBy(GcpOptimizationRecommendation::getService, Collectors.counting()));

                    Map<String, Object> summary = new HashMap<>();
                    summary.put("totalRecommendations", recommendations.size());
                    summary.put("costSavingsCount", recommendations.stream().filter(r -> !r.isCostIncrease()).count());
                    summary.put("performanceImprovementsCount", recommendations.stream().filter(GcpOptimizationRecommendation::isCostIncrease).count());
                    summary.put("totalMonthlySavings", totalSavings);
                    summary.put("totalMonthlyCostIncrease", totalCostIncrease);
                    summary.put("netSavings", totalSavings - totalCostIncrease);
                    summary.put("totalPerformanceGapCost", totalCostIncrease);
                    summary.put("byService", byService);

                    log.info("[GcpRightsizing] /summary OK — durationMs={}", System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(summary);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpRightsizing] /summary TIMEOUT — project={}", accountId);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyMap());
                    }
                    log.error("[GcpRightsizing] /summary ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyMap());
                });
    }

    /**
     * GET /recommendations/by-service
     * Filtered view — reuses cached recommendations, no extra API call.
     */
    @GetMapping("/recommendations/by-service")
    public CompletableFuture<ResponseEntity<?>> getRecommendationsByService(
            @RequestParam String accountId,
            @RequestParam String service,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpRightsizing] /by-service START — project={} service={}", accountId, service);

        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getRightsizingRecommendations(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(50, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(recs -> {
                    List<GcpOptimizationRecommendation> filtered = recs.stream()
                            .filter(r -> r.getService().equalsIgnoreCase(service))
                            .collect(Collectors.toList());
                    log.info("[GcpRightsizing] /by-service OK — {} {} recommendations for project={}",
                            filtered.size(), service, accountId);
                    return ResponseEntity.ok(filtered);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpRightsizing] /by-service ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * POST /apply
     * Gap 5: Apply rightsizing — stop → modify machine type → start GCE instance.
     * Validates read-write access. Returns 202 immediately; async execution.
     * Only operates on accounts with accessType = "read-write".
     */
    @PostMapping("/apply")
    public ResponseEntity<?> applyRightsizing(
            @RequestParam String accountId,
            @RequestParam String zone,
            @RequestParam String instanceName,
            @RequestParam String targetMachineType) {

        log.info("[GcpRightsizing] /apply request — project={} zone={} instance={} target={}",
                accountId, zone, instanceName, targetMachineType);

        try {
            // Run the read-write access guard on THIS thread. It previously lived inside the
            // runAsync body, so the SecurityException it throws surfaced on an executor
            // thread where the catch blocks below could never see it: a read-only account
            // received 202 Accepted with a message saying the instance would be restarted,
            // and the rejection was dropped with no completion handler.
            gcpOptimizationService.verifyRightsizingAllowed(accountId, zone, instanceName, targetMachineType);

            CompletableFuture
                    .runAsync(() -> gcpOptimizationService.applyRightsizing(
                            accountId, zone, instanceName, targetMachineType), backgroundTaskExecutor)
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            log.error("[GcpRightsizing] async apply FAILED — project={} instance={}: {}",
                                    accountId, instanceName, throwable.getMessage(), throwable);
                        }
                    });

            return ResponseEntity.accepted().body(Map.of(
                    "message", "Rightsizing is being applied. Instance " + instanceName +
                               " will be stopped, resized to " + targetMachineType + ", and restarted.",
                    "instanceName", instanceName,
                    "targetMachineType", targetMachineType,
                    "zone", zone,
                    "accountId", accountId
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[GcpRightsizing] /apply ERROR — project={} instance={}: {}", accountId, instanceName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to apply rightsizing", "message", e.getMessage()));
        }
    }

    /**
     * GET /debug
     */
    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> debugRecommendations(@RequestParam String accountId) {
        Map<String, Object> debug = new HashMap<>();
        try {
            List<GcpOptimizationRecommendation> recs = gcpOptimizationService.getRightsizingRecommendations(accountId);
            debug.put("totalCount", recs.size());
            debug.put("costIncreases", recs.stream().filter(GcpOptimizationRecommendation::isCostIncrease).count());
            debug.put("costSavings", recs.stream().filter(r -> !r.isCostIncrease()).count());
            debug.put("samples", recs.stream().limit(3).collect(Collectors.toList()));
            debug.put("serviceBreakdown", recs.stream().collect(
                    Collectors.groupingBy(GcpOptimizationRecommendation::getService, Collectors.counting())));
            debug.put("locationBreakdown", recs.stream().collect(
                    Collectors.groupingBy(GcpOptimizationRecommendation::getLocation, Collectors.counting())));
            return ResponseEntity.ok(debug);
        } catch (Exception e) {
            debug.put("error", e.getMessage());
            log.error("[GcpRightsizing] /debug ERROR — project={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(debug);
        }
    }

    /**
     * POST /cache/clear
     * Evicts Redis cache — next request re-fetches from DB or live API.
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, String>> clearCache(@RequestParam String accountId) {
        log.info("[GcpRightsizing] Cache clear requested for project={}", accountId);
        try {
            cacheService.evictGcpRightsizingCache(accountId);
            cacheService.evictGcpWasteCache(accountId);
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Cache cleared for account: " + accountId);
            response.put("note", "Next request will serve from DB snapshot, then live API if DB is cold.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[GcpRightsizing] Cache clear ERROR — project={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * GET /health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "GCP Rightsizing");
        health.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.ok(health);
    }
}
