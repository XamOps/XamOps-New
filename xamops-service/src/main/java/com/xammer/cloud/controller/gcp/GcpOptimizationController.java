package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.dto.gcp.GcpWasteReport;
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
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return CompletableFuture
                .supplyAsync(() -> gcpOptimizationService.getRightsizingRecommendations(accountId, forceRefresh),
                        backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<List<GcpOptimizationRecommendation>>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    // Returning 200 with an empty list made a permissions failure or timeout
                    // indistinguishable from a fully optimized project. Surface the failure.
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpRightsizing] timed out for project={}", accountId);
                        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
                    }
                    log.error("[GcpRightsizing] failed for project={}", accountId, cause);
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
                });
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
                .orTimeout(100, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(report -> {
                    log.info("[GcpWaste] /waste-report OK — {} items partial={} durationMs={}",
                            report.getItems().size(), report.isPartial(), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(report);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpWaste] /waste-report TIMEOUT — project={} durationMs={}",
                                accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(GcpWasteReport.timeout(
                                        "The waste scan exceeded its time budget. Retry, or use forceRefresh=false "
                                                + "to read the last completed scan."));
                    }
                    // This previously returned 200 with partial=false, which told the client the
                    // empty result was a complete, clean scan.
                    log.error("[GcpWaste] /waste-report ERROR — project={}", accountId, cause);
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(GcpWasteReport.error("The waste scan could not be completed: "
                                    + cause.getMessage()));
                });
    }
}
