package com.xammer.cloud.controller.azure;

import com.xammer.cloud.domain.DiskPolicy;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.azure.AzureDiskRightsizingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/azure/disks")
@RequiredArgsConstructor
public class AzureDiskRightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(AzureDiskRightsizingController.class);
    private final AzureDiskRightsizingService azureDiskService;

    /**
     * Extracts the caller's client id. {@code accountId} is a path variable, so the
     * service layer needs the authenticated tenant to verify ownership before touching
     * an Azure subscription.
     */
    private Long clientIdOf(ClientUserDetails userDetails) {
        if (userDetails == null || userDetails.getClientId() == null) {
            throw new SecurityException("Unauthenticated request");
        }
        return userDetails.getClientId();
    }

    /** Maps tenant-isolation failures to 403 and everything else to 500. */
    private ResponseEntity<?> toErrorResponse(String context, String accountId, Exception e) {
        if (e instanceof SecurityException) {
            logger.warn("Denied {} for account {}: {}", context, accountId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "error", "Not authorized for this account"));
        }
        if (e instanceof IllegalArgumentException) {
            logger.warn("Bad request for {} account {}: {}", context, accountId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
        logger.error("Failed {} for account {}: {}", context, accountId, e.getMessage(), e);
        return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
    }

    @GetMapping("/volumes/{accountId}")
    public ResponseEntity<?> getDisks(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            return ResponseEntity.ok(
                    azureDiskService.getDisks(accountId, clientIdOf(userDetails), forceRefresh));
        } catch (Exception e) {
            return toErrorResponse("list Azure disks", accountId, e);
        }
    }

    @GetMapping("/recommendations/{accountId}")
    public ResponseEntity<?> getRecommendations(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            return ResponseEntity.ok(
                    azureDiskService.getRecommendations(accountId, clientIdOf(userDetails), forceRefresh));
        } catch (Exception e) {
            return toErrorResponse("get Azure disk recommendations", accountId, e);
        }
    }

    @PostMapping("/action/{accountId}")
    public ResponseEntity<?> applyAction(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            String diskName       = (String) request.get("diskName");
            String resourceGroup  = (String) request.get("resourceGroup");
            String actionType     = (String) request.get("actionType");
            Integer targetSizeGb  = request.get("targetSizeGb") != null
                    ? ((Number) request.get("targetSizeGb")).intValue() : null;
            return ResponseEntity.ok(
                    azureDiskService.applyAction(accountId, clientIdOf(userDetails), diskName, resourceGroup,
                            actionType, targetSizeGb));
        } catch (Exception e) {
            return toErrorResponse("apply Azure disk action", accountId, e);
        }
    }

    @GetMapping("/policies/{accountId}")
    public ResponseEntity<?> getPolicies(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            return ResponseEntity.ok(azureDiskService.getPolicies(accountId, clientIdOf(userDetails)));
        } catch (Exception e) {
            return toErrorResponse("get Azure disk policies", accountId, e);
        }
    }

    @PostMapping("/policies/{accountId}")
    public ResponseEntity<?> savePolicy(
            @PathVariable String accountId,
            @RequestBody DiskPolicy policy,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            return ResponseEntity.ok(azureDiskService.savePolicy(accountId, clientIdOf(userDetails), policy));
        } catch (Exception e) {
            return toErrorResponse("save Azure disk policy", accountId, e);
        }
    }

    @GetMapping("/history/{accountId}")
    public ResponseEntity<?> getActionHistory(
            @PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            return ResponseEntity.ok(azureDiskService.getActionHistory(accountId, clientIdOf(userDetails)));
        } catch (Exception e) {
            return toErrorResponse("get Azure disk action history", accountId, e);
        }
    }
}
