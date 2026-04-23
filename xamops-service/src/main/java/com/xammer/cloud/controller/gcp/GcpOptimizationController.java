package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.service.gcp.GcpOptimizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/xamops/gcp/optimization")
@Slf4j
public class GcpOptimizationController {

    private final GcpOptimizationService gcpOptimizationService;

    @Autowired
    @Qualifier("backgroundTaskExecutor")
    private Executor backgroundTaskExecutor;

    public GcpOptimizationController(GcpOptimizationService gcpOptimizationService) {
        this.gcpOptimizationService = gcpOptimizationService;
    }

    @GetMapping("/rightsizing-recommendations")
    public CompletableFuture<ResponseEntity<List<GcpOptimizationRecommendation>>> getRightsizingRecommendations(
            @RequestParam String accountId) {
        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getRightsizingRecommendations(accountId),
                        backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<List<GcpOptimizationRecommendation>>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.ok(Collections.emptyList()));
    }

    /**
     * GET /waste-report
     * Gap 1: forceRefresh param + 55s timeout — returns empty list on timeout instead of hanging.
     */
    @GetMapping("/waste-report")
    public CompletableFuture<ResponseEntity<?>> getWasteReport(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpWaste] /waste-report START — project={} forceRefresh={}", accountId, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getWasteReport(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(items -> {
                    log.info("[GcpWaste] /waste-report OK — {} items durationMs={}",
                            items.size(), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(items);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpWaste] /waste-report TIMEOUT — project={} durationMs={}",
                                accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpWaste] /waste-report ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }
}
