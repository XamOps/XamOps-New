package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpResourceDto;
import com.xammer.cloud.dto.gcp.GcpServicePaginatedResponse;
import com.xammer.cloud.service.gcp.GcpDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/xamops/gcp/cloudlist")
@Slf4j
public class GcpCloudlistController {

    private final GcpDataService gcpDataService;

    public GcpCloudlistController(GcpDataService gcpDataService) {
        this.gcpDataService = gcpDataService;
    }

    /**
     * GET /resources
     * forceRefresh=false → Redis → DB → live (200 blocking)
     * forceRefresh=true  → triggers background async scan, returns 202 immediately;
     *                      frontend subscribes to /topic/gcp-cloudlist-refresh for completion.
     */
    @GetMapping("/resources")
    public CompletableFuture<ResponseEntity<?>> getAllResources(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpCloudList] /resources START — project={} forceRefresh={}", accountId, forceRefresh);
        final long startMs = System.currentTimeMillis();

        if (forceRefresh) {
            gcpDataService.triggerGetResourcesAsync(accountId);
            Map<String, Object> accepted = new HashMap<>();
            accepted.put("message", "Refresh triggered — subscribe to /topic/gcp-cloudlist-refresh for completion");
            accepted.put("accountId", accountId);
            return CompletableFuture.completedFuture(ResponseEntity.accepted().body(accepted));
        }

        return gcpDataService.getAllResources(accountId, false)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(data -> {
                    log.info("[GcpCloudList] /resources OK — {} resources durationMs={}", data.size(), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpCloudList] /resources TIMEOUT — project={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpCloudList] /resources ERROR — project={}", accountId, ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * GET /services-paginated
     * Grouped view with pagination — checks dedicated grouped Redis/DB cache before falling back to flat scan.
     */
    @GetMapping("/services-paginated")
    public CompletableFuture<ResponseEntity<?>> getServicesGroupedPaginated(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int servicesPerPage,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpCloudList] /services-paginated START — project={} page={} forceRefresh={}", accountId, page, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return gcpDataService.getServicesGroupedPaginated(accountId, page, servicesPerPage, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(data -> {
                    log.info("[GcpCloudList] /services-paginated OK — durationMs={}", System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpCloudList] /services-paginated TIMEOUT — project={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
                    }
                    log.error("[GcpCloudList] /services-paginated ERROR — project={}", accountId, ex);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
                });
    }

    /**
     * GET /resources-grouped
     * Full non-paginated grouped list (top 50 services by resource count).
     */
    @GetMapping("/resources-grouped")
    public CompletableFuture<ResponseEntity<?>> getAllResourcesGrouped(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[GcpCloudList] /resources-grouped START — project={} forceRefresh={}", accountId, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return gcpDataService.getAllResourcesGrouped(accountId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(data -> {
                    log.info("[GcpCloudList] /resources-grouped OK — {} service groups durationMs={}", data.size(), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpCloudList] /resources-grouped TIMEOUT — project={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpCloudList] /resources-grouped ERROR — project={}", accountId, ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * GET /resource/{resourceId}
     * Single resource detail — reads from flat cache, no extra API call.
     */
    @GetMapping("/resource/{resourceId}")
    public CompletableFuture<ResponseEntity<?>> getResourceById(
            @RequestParam String accountId,
            @PathVariable String resourceId) {

        log.info("[GcpCloudList] /resource/{} START — project={}", resourceId, accountId);

        return gcpDataService.getResourceById(accountId, resourceId)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(opt -> {
                    if (opt.isPresent()) {
                        return ResponseEntity.ok(opt.get());
                    }
                    Map<String, String> notFound = new HashMap<>();
                    notFound.put("message", "Resource not found: " + resourceId);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound);
                })
                .exceptionally(ex -> {
                    log.error("[GcpCloudList] /resource/{} ERROR — project={}", resourceId, accountId, ex);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
                });
    }

    /**
     * POST /trigger-refresh
     * Triggers a background async resource scan and returns 202 immediately.
     * Frontend subscribes to /topic/gcp-cloudlist-refresh for completion notification.
     */
    @PostMapping("/trigger-refresh")
    public ResponseEntity<Map<String, Object>> triggerRefresh(@RequestParam String accountId) {
        log.info("[GcpCloudList] Manual refresh triggered — project={}", accountId);
        gcpDataService.triggerGetResourcesAsync(accountId);
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Refresh triggered");
        response.put("accountId", accountId);
        response.put("websocketTopic", "/topic/gcp-cloudlist-refresh");
        return ResponseEntity.accepted().body(response);
    }

    /**
     * GET /regions
     * Returns active GCP regions derived from current resource locations with lat/lng coordinates.
     */
    @GetMapping("/regions")
    public CompletableFuture<ResponseEntity<?>> getRegions(@RequestParam String accountId) {
        log.info("[GcpCloudList] /regions START — project={}", accountId);
        final long startMs = System.currentTimeMillis();

        return gcpDataService.getRegionStatus(accountId)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(data -> {
                    log.info("[GcpCloudList] /regions OK — {} active regions durationMs={}", data.size(), System.currentTimeMillis() - startMs);
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[GcpCloudList] /regions TIMEOUT — project={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[GcpCloudList] /regions ERROR — project={}", accountId, ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }

    /**
     * GET /health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "healthy");
        health.put("service", "GCP CloudList API");
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }
}
