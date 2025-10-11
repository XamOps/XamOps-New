package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpCudUtilizationDto;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.service.gcp.GcpOptimizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/xamops/gcp/reservations")
@Slf4j
public class GcpReservationController {

    private final GcpOptimizationService gcpOptimizationService;

    public GcpReservationController(GcpOptimizationService gcpOptimizationService) {
        this.gcpOptimizationService = gcpOptimizationService;
    }

    /**
     * Get CUD recommendations for a GCP project
     * No backend caching - frontend handles caching via localStorage
     */
    @GetMapping("/cuds")
    public ResponseEntity<List<GcpOptimizationRecommendation>> getGcpCuds(@RequestParam String accountId) {
        try {
            log.info("Fetching CUD recommendations for account: {}", accountId);

            // Use synchronous method - no caching on backend
            List<GcpOptimizationRecommendation> recommendations =
                    gcpOptimizationService.getCudRecommendationsSync(accountId);

            log.info("Returning {} CUD recommendations for account: {}",
                    recommendations.size(), accountId);
            return ResponseEntity.ok(recommendations);

        } catch (Exception ex) {
            log.error("Failed to fetch CUD recommendations for account: {}", accountId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get CUD utilization details
     */
    @GetMapping("/cud-utilization")
    public ResponseEntity<GcpCudUtilizationDto> getCudUtilization(
            @RequestParam String accountId,
            @RequestParam String cudId) {
        try {
            log.info("Fetching CUD utilization for account: {}, cudId: {}", accountId, cudId);

            GcpCudUtilizationDto utilization =
                    gcpOptimizationService.getCudUtilization(accountId, cudId).join();

            return ResponseEntity.ok(utilization);

        } catch (Exception ex) {
            log.error("Failed to fetch CUD utilization for account: {}, cudId: {}",
                    accountId, cudId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
