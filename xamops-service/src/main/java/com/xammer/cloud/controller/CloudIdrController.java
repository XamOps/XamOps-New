package com.xammer.cloud.controller;

import com.xammer.cloud.dto.cloudidr.CloudIdrReserveRequest;
import com.xammer.cloud.dto.cloudidr.CloudIdrSearchRequest;
import com.xammer.cloud.service.CloudIdrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/xamops/cloudidr")
@CrossOrigin(origins = "*")
public class CloudIdrController {

    private static final Logger logger = LoggerFactory.getLogger(CloudIdrController.class);

    @Autowired
    private CloudIdrService cloudIdrService;

    /**
     * Search for available compute instances
     * POST /api/xamops/cloudidr/search
     */
    @PostMapping("/search")
    public ResponseEntity<?> searchInstances(@Valid @RequestBody CloudIdrSearchRequest request) {
        try {
            logger.info("Received CloudIDR search request for account: {}", request.getAccountId());
            Object result = cloudIdrService.searchCompute(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error in CloudIDR search endpoint", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Search failed: " + e.getMessage()));
        }
    }

    /**
     * Reserve capacity
     * POST /api/xamops/cloudidr/reserve
     */
    @PostMapping("/reserve")
    public ResponseEntity<?> reserveCapacity(@Valid @RequestBody CloudIdrReserveRequest request) {
        try {
            logger.info("Received CloudIDR reserve request: {}", request.getPolicyName());
            Object result = cloudIdrService.reserveCapacity(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error in CloudIDR reserve endpoint", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Reservation failed: " + e.getMessage()));
        }
    }

    /**
     * Purchase capacity
     * POST /api/xamops/cloudidr/purchase
     */
    @PostMapping("/purchase")
    public ResponseEntity<?> purchaseCapacity(@RequestBody Map<String, String> payload) {
        try {
            String cidrId = payload.get("cidrId");

            if (cidrId == null || cidrId.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Missing or empty cidrId"));
            }

            logger.info("Received CloudIDR purchase request for: {}", cidrId);
            Object result = cloudIdrService.purchaseCapacity(cidrId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error in CloudIDR purchase endpoint", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Purchase failed: " + e.getMessage()));
        }
    }

    /**
     * Get policy details
     * GET /api/xamops/cloudidr/policy/{cidrId}
     */
    @GetMapping("/policy/{cidrId}")
    public ResponseEntity<?> getPolicyDetails(@PathVariable String cidrId) {
        try {
            if (cidrId == null || cidrId.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Invalid cidrId"));
            }

            logger.info("Received CloudIDR policy details request for: {}", cidrId);
            Object result = cloudIdrService.getSchedulePolicy(cidrId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error in CloudIDR policy details endpoint", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch policy: " + e.getMessage()));
        }
    }

    /**
     * Cancel policy
     * POST /api/xamops/cloudidr/cancel
     */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancelPolicy(@RequestBody Map<String, String> payload) {
        try {
            String cidrId = payload.get("cidrId");

            if (cidrId == null || cidrId.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Missing or empty cidrId"));
            }

            logger.info("Received CloudIDR cancel request for: {}", cidrId);
            Object result = cloudIdrService.cancelPolicy(cidrId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error in CloudIDR cancel endpoint", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Cancellation failed: " + e.getMessage()));
        }
    }

    /**
     * Get all scheduled capacities
     * GET /api/xamops/cloudidr/scheduled-capacities
     */
    @GetMapping("/scheduled-capacities")
    public ResponseEntity<?> getAllScheduledCapacities() {
        try {
            logger.info("Received CloudIDR scheduled capacities request");
            Object result = cloudIdrService.getAllScheduledCapacities();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error in CloudIDR scheduled capacities endpoint", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch capacities: " + e.getMessage()));
        }
    }

    /**
     * Get capacity summary
     * GET /api/xamops/cloudidr/summary
     */
    @GetMapping("/summary")
    public ResponseEntity<?> getCapacitySummary() {
        try {
            logger.info("Received CloudIDR summary request");
            Map<String, Object> result = cloudIdrService.getCapacitySummary();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error in CloudIDR summary endpoint", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to calculate summary: " + e.getMessage()));
        }
    }

    /**
     * Health check endpoint
     * GET /api/xamops/cloudidr/health
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("service", "CloudIDR Integration");
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }

    /**
     * Clear token cache (admin/testing endpoint)
     * POST /api/xamops/cloudidr/admin/clear-cache
     */
    @PostMapping("/admin/clear-cache")
    public ResponseEntity<?> clearCache() {
        try {
            cloudIdrService.clearTokenCache();
            Map<String, String> response = new HashMap<>();
            response.put("message", "CloudIDR token cache cleared successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error clearing cache", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to clear cache: " + e.getMessage()));
        }
    }

    /**
     * Helper method to create error response
     */
    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("timestamp", System.currentTimeMillis());
        return error;
    }
}
