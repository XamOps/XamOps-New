package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.domain.DiskPolicy;
import com.xammer.cloud.service.gcp.GcpDiskRightsizingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/gcp/disks")
@RequiredArgsConstructor
public class GcpDiskRightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(GcpDiskRightsizingController.class);
    private final GcpDiskRightsizingService gcpDiskService;

    @GetMapping("/{accountId}")
    public ResponseEntity<?> getDisks(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(gcpDiskService.getDisks(accountId, forceRefresh));
        } catch (Exception e) {
            logger.error("Failed to list GCP disks for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/recommendations/{accountId}")
    public ResponseEntity<?> getRecommendations(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(gcpDiskService.getRecommendations(accountId, forceRefresh));
        } catch (Exception e) {
            logger.error("Failed to get GCP disk recommendations for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/action/{accountId}")
    public ResponseEntity<?> applyAction(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request) {
        try {
            String diskName    = (String) request.get("diskName");
            String zone        = (String) request.get("zone");
            String actionType  = (String) request.get("actionType");
            Integer targetSize = request.get("targetSizeGb") != null
                    ? ((Number) request.get("targetSizeGb")).intValue() : null;
            return ResponseEntity.ok(
                    gcpDiskService.applyAction(accountId, diskName, zone, actionType, targetSize));
        } catch (Exception e) {
            logger.error("Failed to apply GCP disk action for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/policies/{accountId}")
    public ResponseEntity<?> getPolicies(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(gcpDiskService.getPolicies(accountId));
        } catch (Exception e) {
            logger.error("Failed to get GCP disk policies for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/policies/{accountId}")
    public ResponseEntity<?> savePolicy(
            @PathVariable String accountId,
            @RequestBody DiskPolicy policy) {
        try {
            return ResponseEntity.ok(gcpDiskService.savePolicy(accountId, policy));
        } catch (Exception e) {
            logger.error("Failed to save GCP disk policy for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/history/{accountId}")
    public ResponseEntity<?> getActionHistory(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(gcpDiskService.getActionHistory(accountId));
        } catch (Exception e) {
            logger.error("Failed to get GCP disk action history for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
