package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.service.gcp.GcpOptimizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/rightsizing")
@Slf4j
public class GcpRightsizingController {

    private final GcpOptimizationService gcpOptimizationService;

    public GcpRightsizingController(GcpOptimizationService gcpOptimizationService) {
        this.gcpOptimizationService = gcpOptimizationService;
    }

    /**
     * Get rightsizing recommendations for a GCP project
     * IMPORTANT: Returns synchronously to avoid CompletableFuture casting issues
     */
    @GetMapping("/recommendations")
    public ResponseEntity<List<GcpOptimizationRecommendation>> getRightsizingRecommendations(
            @RequestParam String accountId) {

        log.info("Fetching rightsizing recommendations for account: {}", accountId);

        try {
            // Call the service method synchronously (it handles async internally)
            List<GcpOptimizationRecommendation> recommendations =
                    gcpOptimizationService.getRightsizingRecommendations(accountId);

            log.info("Successfully fetched {} recommendations for account: {}",
                    recommendations.size(), accountId);

            return ResponseEntity.ok(recommendations);

        } catch (IllegalArgumentException e) {
            log.error("Invalid account ID provided: {}", accountId, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.emptyList());

        } catch (SecurityException e) {
            log.error("Permission denied for account: {}", accountId, e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Collections.emptyList());

        } catch (Exception e) {
            log.error("Failed to fetch rightsizing recommendations for account: {}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    /**
     * Get summary of rightsizing recommendations
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getRightsizingSummary(
            @RequestParam String accountId) {

        log.info("Fetching rightsizing summary for account: {}", accountId);

        try {
            List<GcpOptimizationRecommendation> recommendations =
                    gcpOptimizationService.getRightsizingRecommendations(accountId);

            // Calculate summary statistics
            long totalRecommendations = recommendations.size();
            long costSavingsCount = recommendations.stream()
                    .filter(r -> r.getMonthlySavings() >= 0)
                    .count();
            long performanceImprovementsCount = recommendations.stream()
                    .filter(r -> r.getMonthlySavings() < 0)
                    .count();

            double totalSavings = recommendations.stream()
                    .filter(r -> r.getMonthlySavings() >= 0)
                    .mapToDouble(GcpOptimizationRecommendation::getMonthlySavings)
                    .sum();

            double totalCostIncrease = Math.abs(recommendations.stream()
                    .filter(r -> r.getMonthlySavings() < 0)
                    .mapToDouble(GcpOptimizationRecommendation::getMonthlySavings)
                    .sum());

            Map<String, Object> summary = new HashMap<>();
            summary.put("totalRecommendations", totalRecommendations);
            summary.put("costSavingsCount", costSavingsCount);
            summary.put("performanceImprovementsCount", performanceImprovementsCount);
            summary.put("totalMonthlySavings", totalSavings);
            summary.put("totalMonthlyCostIncrease", totalCostIncrease);
            summary.put("netSavings", totalSavings - totalCostIncrease);

            // Breakdown by service
            Map<String, Long> byService = recommendations.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            GcpOptimizationRecommendation::getService,
                            java.util.stream.Collectors.counting()));
            summary.put("byService", byService);

            log.info("Successfully generated summary for account: {}", accountId);
            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            log.error("Failed to generate summary for account: {}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyMap());
        }
    }

    /**
     * Get filtered recommendations by service type
     */
    @GetMapping("/recommendations/by-service")
    public ResponseEntity<List<GcpOptimizationRecommendation>> getRecommendationsByService(
            @RequestParam String accountId,
            @RequestParam String service) {

        log.info("Fetching {} recommendations for account: {}", service, accountId);

        try {
            List<GcpOptimizationRecommendation> recommendations =
                    gcpOptimizationService.getRightsizingRecommendations(accountId);

            List<GcpOptimizationRecommendation> filtered = recommendations.stream()
                    .filter(r -> r.getService().equalsIgnoreCase(service))
                    .collect(java.util.stream.Collectors.toList());

            log.info("Found {} {} recommendations for account: {}",
                    filtered.size(), service, accountId);

            return ResponseEntity.ok(filtered);

        } catch (Exception e) {
            log.error("Failed to fetch {} recommendations for account: {}", service, accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    /**
     * Debug endpoint to inspect recommendation details
     */
    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> debugRecommendations(
            @RequestParam String accountId) {

        log.debug("Debug request for account: {}", accountId);

        Map<String, Object> debug = new HashMap<>();

        try {
            List<GcpOptimizationRecommendation> recs =
                    gcpOptimizationService.getRightsizingRecommendations(accountId);

            debug.put("totalCount", recs.size());
            debug.put("costIncreases", recs.stream().filter(GcpOptimizationRecommendation::isCostIncrease).count());
            debug.put("costSavings", recs.stream().filter(r -> !r.isCostIncrease()).count());

            // Sample recommendations
            debug.put("samples", recs.stream()
                    .limit(3)
                    .collect(java.util.stream.Collectors.toList()));

            // Service breakdown
            Map<String, Long> serviceBreakdown = recs.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            GcpOptimizationRecommendation::getService,
                            java.util.stream.Collectors.counting()));
            debug.put("serviceBreakdown", serviceBreakdown);

            // Location breakdown
            Map<String, Long> locationBreakdown = recs.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            GcpOptimizationRecommendation::getLocation,
                            java.util.stream.Collectors.counting()));
            debug.put("locationBreakdown", locationBreakdown);

            return ResponseEntity.ok(debug);

        } catch (Exception e) {
            debug.put("error", e.getMessage());
            debug.put("errorType", e.getClass().getSimpleName());
            log.error("Debug endpoint error for account: {}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(debug);
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "GCP Rightsizing");
        health.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.ok(health);
    }
}
