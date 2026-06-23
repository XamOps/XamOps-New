package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.ProwlerFinding;
import com.xammer.cloud.dto.gcp.GcpContainerVulnerabilityDto;
import com.xammer.cloud.dto.gcp.GcpIamPolicyDriftDto;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.gcp.GcpSecurityService;
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
@RequestMapping("/api/xamops/gcp/security")
@Slf4j
public class GcpSecurityController {

    private final GcpSecurityService gcpSecurityService;
    private final ProwlerService prowlerService;

    @Autowired
    @Qualifier("backgroundTaskExecutor")
    private Executor backgroundTaskExecutor;

    public GcpSecurityController(GcpSecurityService gcpSecurityService, ProwlerService prowlerService) {
        this.gcpSecurityService = gcpSecurityService;
        this.prowlerService = prowlerService;
    }

    /**
     * Gap 5+6: forceRefresh param + 55s timeout.
     * Gap E: Prowler findings are now merged inside GcpSecurityService.
     */
    @GetMapping("/findings")
    public CompletableFuture<ResponseEntity<?>> getSecurityFindings(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpSecurity] /findings START — project={} forceRefresh={}", accountId, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return CompletableFuture
                .supplyAsync(() -> gcpSecurityService.getSecurityFindings(accountId, forceRefresh).join(),
                        backgroundTaskExecutor)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(findings -> {
                    log.info("[GcpSecurity] /findings OK — {} findings durationMs={}",
                            findings.size(), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(findings);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpSecurity] /findings TIMEOUT — project={} durationMs={}",
                                accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpSecurity] /findings ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    @GetMapping("/iam-drift")
    public CompletableFuture<ResponseEntity<?>> getIamPolicyDrift(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpSecurity] /iam-drift START — project={} forceRefresh={}", accountId, forceRefresh);

        return CompletableFuture
                .supplyAsync(() -> gcpSecurityService.getIamPolicyDrift(accountId, forceRefresh).join(),
                        backgroundTaskExecutor)
                .orTimeout(30, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException)
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    log.error("[GcpSecurity] /iam-drift ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    @GetMapping("/container-vulnerabilities")
    public CompletableFuture<ResponseEntity<?>> getContainerVulnerabilities(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpSecurity] /container-vulnerabilities START — project={} forceRefresh={}", accountId, forceRefresh);

        return CompletableFuture
                .supplyAsync(() -> gcpSecurityService.getContainerScanningResults(accountId, forceRefresh).join(),
                        backgroundTaskExecutor)
                .orTimeout(30, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException)
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    log.error("[GcpSecurity] /container-vulnerabilities ERROR — project={}", accountId, cause);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /** Exposes the calculated security score. */
    @GetMapping("/score")
    public CompletableFuture<ResponseEntity<?>> getSecurityScore(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        return CompletableFuture
                .supplyAsync(() -> {
                    List<GcpSecurityFinding> findings =
                            gcpSecurityService.getSecurityFindings(accountId, forceRefresh).join();
                    int score = gcpSecurityService.calculateSecurityScore(findings);
                    Map<String, Object> result = new HashMap<>();
                    result.put("score", score);
                    result.put("totalFindings", findings.size());
                    result.put("critical", findings.stream().filter(f -> "CRITICAL".equalsIgnoreCase(f.getSeverity())).count());
                    result.put("high",     findings.stream().filter(f -> "HIGH".equalsIgnoreCase(f.getSeverity())).count());
                    result.put("medium",   findings.stream().filter(f -> "MEDIUM".equalsIgnoreCase(f.getSeverity())).count());
                    return result;
                }, backgroundTaskExecutor)
                .orTimeout(30, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.ok(Map.of("score", 0)));
    }

    // --- Prowler endpoints ---

    @PostMapping("/prowler/scan")
    public ResponseEntity<Map<String, Object>> triggerComplianceScan(@RequestParam String accountId) {
        log.info("[GcpSecurity] Prowler scan triggered for project={}", accountId);

        Map<String, Object> current = prowlerService.getGcpScanStatus(accountId);
        if ("RUNNING".equals(current.get("status"))) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_RUNNING",
                    "message", "A scan is already in progress for this GCP project."));
        }

        gcpSecurityService.triggerProwlerScan(accountId);

        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "message", "GCP compliance scan triggered. Poll /prowler/status for progress.",
                "frameworks", GcpSecurityService.class.getDeclaredFields().length // just surfacing it exists
        ));
    }

    @GetMapping("/prowler/status")
    public ResponseEntity<Map<String, Object>> getScanStatus(@RequestParam String accountId) {
        return ResponseEntity.ok(prowlerService.getGcpScanStatus(accountId));
    }

    @GetMapping("/prowler/findings")
    public ResponseEntity<List<ProwlerFinding>> getProwlerFindings(@RequestParam String accountId) {
        return ResponseEntity.ok(prowlerService.getCachedGcpFindings(accountId));
    }

    /** Cache clear — evicts Redis for all security keys for this project. */
    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, String>> clearCache(@RequestParam String accountId) {
        log.info("[GcpSecurity] Cache clear requested for project={}", accountId);
        try {
            gcpSecurityService.clearCacheForProject(accountId);
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Security caches cleared for project: " + accountId);
            response.put("note", "Next /findings request will re-fetch from SCC and merge Prowler findings.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[GcpSecurity] Cache clear ERROR — project={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "GCP Security");
        health.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.ok(health);
    }
}
