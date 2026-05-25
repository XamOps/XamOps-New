package com.xammer.cloud.controller;

import com.xammer.cloud.domain.DiskPolicy;
import com.xammer.cloud.service.EbsDiskRightsizingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ebs")
@RequiredArgsConstructor
public class EbsDiskRightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(EbsDiskRightsizingController.class);
    private static final String GENERIC_ERROR_MESSAGE = "An unexpected error occurred while processing the EBS request.";
    private final EbsDiskRightsizingService ebsService;

    // ─── Volumes & Recommendations ───────────────────────────────────────────────

    @GetMapping("/volumes/{accountId}")
    public ResponseEntity<?> getVolumes(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(ebsService.getVolumes(accountId, forceRefresh));
        } catch (Exception e) {
            return handleError("Failed to list volumes for account", accountId, e);
        }
    }

    @GetMapping("/recommendations/{accountId}")
    public ResponseEntity<?> getRecommendations(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(ebsService.getRecommendations(accountId, forceRefresh));
        } catch (Exception e) {
            return handleError("Failed to get EBS recommendations for account", accountId, e);
        }
    }

    // ─── Apply Actions ───────────────────────────────────────────────────────────

    @PostMapping("/action/{accountId}")
    public ResponseEntity<?> applyAction(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request) {
        try {
            String volumeId   = (String) request.get("volumeId");
            String region     = (String) request.get("region");
            String actionType = (String) request.get("actionType");
            String targetType = (String) request.get("targetType");
            Integer targetSizeGb = request.get("targetSizeGb") != null
                    ? ((Number) request.get("targetSizeGb")).intValue() : null;

            return ResponseEntity.ok(
                    ebsService.applyAction(accountId, volumeId, region, actionType, targetType, targetSizeGb));
        } catch (Exception e) {
            return handleError("Failed to apply EBS action for account", accountId, e);
        }
    }

    // ─── Policies ────────────────────────────────────────────────────────────────

    @GetMapping("/policies/{accountId}")
    public ResponseEntity<?> getPolicies(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(ebsService.getPolicies(accountId));
        } catch (Exception e) {
            return handleError("Failed to get policies for account", accountId, e);
        }
    }

    @PostMapping("/policies/{accountId}")
    public ResponseEntity<?> createPolicy(
            @PathVariable String accountId,
            @RequestBody DiskPolicy request) {
        try {
            return ResponseEntity.ok(ebsService.createPolicy(accountId, request));
        } catch (Exception e) {
            return handleError("Failed to create policy for account", accountId, e);
        }
    }

    @PutMapping("/policies/{policyId}")
    public ResponseEntity<?> updatePolicy(
            @PathVariable Long policyId,
            @RequestBody DiskPolicy request) {
        try {
            return ResponseEntity.ok(ebsService.updatePolicy(policyId, request));
        } catch (Exception e) {
            return handleError("Failed to update policy", policyId, e);
        }
    }

    @DeleteMapping("/policies/{policyId}")
    public ResponseEntity<?> deletePolicy(@PathVariable Long policyId) {
        try {
            ebsService.deletePolicy(policyId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return handleError("Failed to delete policy", policyId, e);
        }
    }

    // ─── Action History ───────────────────────────────────────────────────────────

    @GetMapping("/action-history/{accountId}")
    public ResponseEntity<?> getActionHistory(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(ebsService.getActionHistory(accountId));
        } catch (Exception e) {
            return handleError("Failed to get action history for account", accountId, e);
        }
    }

    // ─── Shrink (Data Volume) ─────────────────────────────────────────────────────

    @PostMapping("/shrink/{accountId}")
    public ResponseEntity<?> initiateDownsize(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request) {
        try {
            String volumeId   = (String) request.get("volumeId");
            String region     = (String) request.get("region");
            String instanceId = (String) request.get("instanceId");
            String mountPoint = (String) request.get("mountPoint");
            int targetSizeGb  = ((Number) request.get("targetSizeGb")).intValue();

            return ResponseEntity.ok(
                    ebsService.initiateDownsize(accountId, volumeId, region, instanceId, mountPoint, targetSizeGb));
        } catch (Exception e) {
            return handleError("Failed to initiate shrink for account", accountId, e);
        }
    }

    // ─── Pending Deletion Management ─────────────────────────────────────────────

    @GetMapping("/pending-deletion/{accountId}")
    public ResponseEntity<?> getPendingDeletionVolumes(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(ebsService.getPendingDeletionVolumes(accountId));
        } catch (Exception e) {
            return handleError("Failed to get pending-deletion volumes for account", accountId, e);
        }
    }

    @DeleteMapping("/pending-deletion/{accountId}")
    public ResponseEntity<?> deletePendingVolume(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request) {
        try {
            String volumeId = (String) request.get("volumeId");
            String region   = (String) request.get("region");
            return ResponseEntity.ok(ebsService.deletePendingVolume(accountId, volumeId, region));
        } catch (Exception e) {
            return handleError("Failed to delete pending volume for account", accountId, e);
        }
    }

    @PostMapping("/pending-deletion/{accountId}/keep")
    public ResponseEntity<?> keepVolume(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request) {
        try {
            String volumeId = (String) request.get("volumeId");
            String region   = (String) request.get("region");
            return ResponseEntity.ok(ebsService.keepVolume(accountId, volumeId, region));
        } catch (Exception e) {
            return handleError("Failed to keep volume for account", accountId, e);
        }
    }

    // ─── Volume Monitoring ────────────────────────────────────────────────────────

    @GetMapping("/monitoring/{accountId}/{volumeId}")
    public ResponseEntity<?> getVolumeMonitoring(
            @PathVariable String accountId,
            @PathVariable String volumeId,
            @RequestParam String region,
            @RequestParam(defaultValue = "1") int hours) {
        try {
            return ResponseEntity.ok(ebsService.getVolumeMonitoring(accountId, volumeId, region, hours));
        } catch (Exception e) {
            return handleError("Failed to get monitoring for volume " + volumeId + " account", accountId, e);
        }
    }

    // ─── SSM Disk Usage ───────────────────────────────────────────────────────────

    @PostMapping("/disk-usage/{accountId}")
    public ResponseEntity<?> getDiskUsage(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request) {
        try {
            String region = (String) request.get("region");
            @SuppressWarnings("unchecked")
            java.util.List<String> instanceIds = (java.util.List<String>) request.get("instanceIds");
            return ResponseEntity.ok(ebsService.getDiskUsageViaSSM(accountId, region, instanceIds));
        } catch (Exception e) {
            return handleError("Failed to fetch disk usage via SSM for account", accountId, e);
        }
    }

    // ─── SSM Auto-Fix ────────────────────────────────────────────────────────────

    @PostMapping("/ssm/fix/{accountId}")
    public ResponseEntity<?> fixSsmPermissions(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request) {
        try {
            String region = (String) request.get("region");
            @SuppressWarnings("unchecked")
            java.util.List<String> instanceIds = (java.util.List<String>) request.get("instanceIds");
            return ResponseEntity.ok(ebsService.attachSsmPolicyToInstances(accountId, region, instanceIds));
        } catch (Exception e) {
            return handleError("SSM fix failed for account", accountId, e);
        }
    }

    // ─── ROI Calculator ───────────────────────────────────────────────────────────

    @PostMapping("/roi/calculate")
    public ResponseEntity<?> calculateRoi(@RequestBody Map<String, Object> request) {
        try {
            int provisionedGb      = ((Number) request.get("provisionedGb")).intValue();
            double utilizationPct  = ((Number) request.get("utilizationPct")).doubleValue();
            double growthRatePct   = ((Number) request.getOrDefault("growthRatePct", 25)).doubleValue();
            String diskType        = (String) request.getOrDefault("diskType", "gp2");

            return ResponseEntity.ok(ebsService.calculateRoi(provisionedGb, utilizationPct, growthRatePct, diskType));
        } catch (Exception e) {
            return handleError("ROI calculation failed", null, e);
        }
    }

    private ResponseEntity<?> handleError(String logMessage, Object contextId, Exception e) {
        logger.error("{}{}: {}", logMessage, contextId != null ? " " + contextId : "", e.getMessage(), e);
        if (e.getMessage() != null && (e.getMessage().contains("Access denied") || e.getMessage().contains("not found"))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "error", "Resource not found or access denied"));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "error", GENERIC_ERROR_MESSAGE));
    }
}
