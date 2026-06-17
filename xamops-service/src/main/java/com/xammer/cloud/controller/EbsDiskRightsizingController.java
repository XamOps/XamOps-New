package com.xammer.cloud.controller;

import com.xammer.cloud.domain.DiskPolicy;
import com.xammer.cloud.service.EbsDiskRightsizingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ebs")
@RequiredArgsConstructor
public class EbsDiskRightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(EbsDiskRightsizingController.class);
    private final EbsDiskRightsizingService ebsService;

    // ─── Volumes & Recommendations ───────────────────────────────────────────────

    @GetMapping("/volumes/{accountId}")
    public ResponseEntity<?> getVolumes(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(ebsService.getVolumes(accountId, forceRefresh));
        } catch (Exception e) {
            logger.error("Failed to list volumes for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/recommendations/{accountId}")
    public ResponseEntity<?> getRecommendations(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(ebsService.getRecommendations(accountId, forceRefresh));
        } catch (Exception e) {
            logger.error("Failed to get EBS recommendations for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
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
            String instanceId = (String) request.get("instanceId");
            String asgName    = (String) request.get("asgName");

            return ResponseEntity.ok(
                    ebsService.applyAction(accountId, volumeId, region, actionType, targetType, targetSizeGb,
                            instanceId, asgName));
        } catch (Exception e) {
            logger.error("Failed to apply EBS action for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ─── Policies ────────────────────────────────────────────────────────────────

    @GetMapping("/policies/{accountId}")
    public ResponseEntity<?> getPolicies(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(ebsService.getPolicies(accountId));
        } catch (Exception e) {
            logger.error("Failed to get policies for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/policies/{accountId}")
    public ResponseEntity<?> createPolicy(
            @PathVariable String accountId,
            @RequestBody DiskPolicy request) {
        try {
            return ResponseEntity.ok(ebsService.createPolicy(accountId, request));
        } catch (Exception e) {
            logger.error("Failed to create policy for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PutMapping("/policies/{policyId}")
    public ResponseEntity<?> updatePolicy(
            @PathVariable Long policyId,
            @RequestBody DiskPolicy request) {
        try {
            return ResponseEntity.ok(ebsService.updatePolicy(policyId, request));
        } catch (Exception e) {
            logger.error("Failed to update policy {}: {}", policyId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @DeleteMapping("/policies/{policyId}")
    public ResponseEntity<?> deletePolicy(@PathVariable Long policyId) {
        try {
            ebsService.deletePolicy(policyId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            logger.error("Failed to delete policy {}: {}", policyId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ─── Action History ───────────────────────────────────────────────────────────

    @GetMapping("/action-history/{accountId}")
    public ResponseEntity<?> getActionHistory(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(ebsService.getActionHistory(accountId));
        } catch (Exception e) {
            logger.error("Failed to get action history for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
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
            logger.error("Failed to initiate shrink for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ─── Expand (Data Volume) ─────────────────────────────────────────────────────

    @PostMapping("/expand/{accountId}")
    public ResponseEntity<?> initiateExpand(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request) {
        try {
            String volumeId   = (String) request.get("volumeId");
            String region     = (String) request.get("region");
            String instanceId = (String) request.get("instanceId");
            String device     = (String) request.get("device");
            int targetSizeGb  = ((Number) request.get("targetSizeGb")).intValue();
            String asgName    = (String) request.get("asgName");

            return ResponseEntity.ok(
                    ebsService.initiateExpand(accountId, volumeId, region, instanceId, device, targetSizeGb, asgName));
        } catch (Exception e) {
            logger.error("Failed to initiate expand for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ─── Pending Deletion Management ─────────────────────────────────────────────

    @GetMapping("/pending-deletion/{accountId}")
    public ResponseEntity<?> getPendingDeletionVolumes(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(ebsService.getPendingDeletionVolumes(accountId));
        } catch (Exception e) {
            logger.error("Failed to get pending-deletion volumes for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
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
            logger.error("Failed to delete pending volume for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
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
            logger.error("Failed to keep volume for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
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
            logger.error("Failed to get monitoring for volume {} account {}: {}", volumeId, accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
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
            logger.error("Failed to fetch disk usage via SSM for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
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
            logger.error("SSM fix failed for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
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
            logger.error("ROI calculation failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
