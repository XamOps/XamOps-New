// src/main/java/com/xammer/cloud/controller/gcp/GcpPerformanceInsightsController.java
package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.service.gcp.GcpPerformanceInsightsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/performance-insights") // Changed path
@Slf4j
public class GcpPerformanceInsightsController {

    private final GcpPerformanceInsightsService gcpPerformanceInsightsService;

    public GcpPerformanceInsightsController(GcpPerformanceInsightsService gcpPerformanceInsightsService) {
        this.gcpPerformanceInsightsService = gcpPerformanceInsightsService;
    }

    @GetMapping("/insights")
    public ResponseEntity<List<PerformanceInsightDto>> getInsights(
            @RequestParam String accountId, // accountId is gcpProjectId here
            @RequestParam(required = false, defaultValue = "ALL") String severity,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            List<PerformanceInsightDto> insights = gcpPerformanceInsightsService.getInsights(accountId, severity, forceRefresh);
            return ResponseEntity.ok(insights);
        } catch (Exception e) {
            log.error("Error fetching GCP performance insights for project {}", accountId, e);
            return ResponseEntity.status(500).body(Collections.emptyList());
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getInsightsSummary(
            @RequestParam String accountId, // accountId is gcpProjectId here
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            Map<String, Object> summary = gcpPerformanceInsightsService.getInsightsSummary(accountId, forceRefresh);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Error fetching GCP performance insights summary for project {}", accountId, e);
            return ResponseEntity.status(500).body(Collections.emptyMap());
        }
    }

    // Placeholder - WhatIf needs specific GCP implementation
    @GetMapping("/what-if")
    public CompletableFuture<ResponseEntity<Object>> getWhatIfScenario(
            @RequestParam String accountId, // accountId is gcpProjectId here
            @RequestParam String resourceId,
            @RequestParam String targetInstanceType,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return gcpPerformanceInsightsService.getWhatIfScenario(accountId, resourceId, targetInstanceType, forceRefresh)
                .thenApply(result -> ResponseEntity.ok(result))
                .exceptionally(ex -> {
                    log.error("Error generating GCP what-if scenario for resource {}", resourceId, ex);
                    return ResponseEntity.status(500).body(Map.of("error", "Failed to generate scenario", "message", ex.getMessage()));
                });
    }

    // Add archive/export endpoints if needed, similar to AWS controller
    @PostMapping("/{insightId}/archive")
    public ResponseEntity<Void> archiveInsight(@PathVariable String insightId) {
        // Implement archive logic if needed (e.g., store IDs in Redis or DB)
        log.warn("Archive insight endpoint called for GCP, but not implemented yet: {}", insightId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bulk-archive")
    public ResponseEntity<Void> bulkArchiveInsights(@RequestBody List<String> insightIds) {
        log.warn("Bulk archive endpoint called for GCP, but not implemented yet: {} insights", insightIds.size());
        return ResponseEntity.ok().build();
    }

    // Export requires defining GCP insight fields and using a similar Excel export logic
//     @GetMapping("/export")
//     public void exportInsights(...) {}
}