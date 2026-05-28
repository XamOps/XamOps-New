package com.xammer.cloud.controller.azure;

import com.xammer.cloud.domain.DiskPolicy;
import com.xammer.cloud.service.azure.AzureDiskRightsizingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/azure/disks")
@RequiredArgsConstructor
public class AzureDiskRightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(AzureDiskRightsizingController.class);
    private final AzureDiskRightsizingService azureDiskService;

    @GetMapping("/volumes/{accountId}")
    public ResponseEntity<?> getDisks(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(azureDiskService.getDisks(accountId, forceRefresh));
        } catch (Exception e) {
            logger.error("Failed to list Azure disks for account {}: {}", accountId, e.getMessage(), e);
            // Sentinel security directive: Return a generic error message to prevent internal detail leakage
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "An unexpected error occurred while processing the Azure disk request."));
        }
    }

    @GetMapping("/recommendations/{accountId}")
    public ResponseEntity<?> getRecommendations(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            return ResponseEntity.ok(azureDiskService.getRecommendations(accountId, forceRefresh));
        } catch (Exception e) {
            logger.error("Failed to get Azure disk recommendations for account {}: {}", accountId, e.getMessage(), e);
            // Sentinel security directive: Return a generic error message to prevent internal detail leakage
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "An unexpected error occurred while processing the Azure disk request."));
        }
    }

    @PostMapping("/action/{accountId}")
    public ResponseEntity<?> applyAction(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request) {
        try {
            String diskName       = (String) request.get("diskName");
            String resourceGroup  = (String) request.get("resourceGroup");
            String actionType     = (String) request.get("actionType");
            Integer targetSizeGb  = request.get("targetSizeGb") != null
                    ? ((Number) request.get("targetSizeGb")).intValue() : null;
            return ResponseEntity.ok(
                    azureDiskService.applyAction(accountId, diskName, resourceGroup, actionType, targetSizeGb));
        } catch (Exception e) {
            logger.error("Failed to apply Azure disk action for account {}: {}", accountId, e.getMessage(), e);
            // Sentinel security directive: Return a generic error message to prevent internal detail leakage
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "An unexpected error occurred while processing the Azure disk request."));
        }
    }

    @GetMapping("/policies/{accountId}")
    public ResponseEntity<?> getPolicies(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(azureDiskService.getPolicies(accountId));
        } catch (Exception e) {
            logger.error("Failed to get Azure disk policies for account {}: {}", accountId, e.getMessage(), e);
            // Sentinel security directive: Return a generic error message to prevent internal detail leakage
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "An unexpected error occurred while processing the Azure disk request."));
        }
    }

    @PostMapping("/policies/{accountId}")
    public ResponseEntity<?> savePolicy(
            @PathVariable String accountId,
            @RequestBody DiskPolicy policy) {
        try {
            return ResponseEntity.ok(azureDiskService.savePolicy(accountId, policy));
        } catch (Exception e) {
            logger.error("Failed to save Azure disk policy for account {}: {}", accountId, e.getMessage(), e);
            // Sentinel security directive: Return a generic error message to prevent internal detail leakage
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "An unexpected error occurred while processing the Azure disk request."));
        }
    }

    @GetMapping("/history/{accountId}")
    public ResponseEntity<?> getActionHistory(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(azureDiskService.getActionHistory(accountId));
        } catch (Exception e) {
            logger.error("Failed to get Azure disk action history for account {}: {}", accountId, e.getMessage(), e);
            // Sentinel security directive: Return a generic error message to prevent internal detail leakage
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "An unexpected error occurred while processing the Azure disk request."));
        }
    }
}
