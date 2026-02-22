package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureSecurityResponse;
import com.xammer.cloud.service.azure.AzureSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

/**
 * REST controller for Azure Security data (Defender for Cloud).
 *
 * GET /api/xamops/azure/security?accountId={subscriptionId}&forceRefresh=false
 */
@RestController
@RequestMapping("/api/xamops/azure")
public class AzureSecurityController {

    private static final Logger log = LoggerFactory.getLogger(AzureSecurityController.class);

    private final AzureSecurityService azureSecurityService;

    public AzureSecurityController(AzureSecurityService azureSecurityService) {
        this.azureSecurityService = azureSecurityService;
    }

    @GetMapping("/security")
    public ResponseEntity<AzureSecurityResponse> getSecurityData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            log.info("Azure Security request - Subscription: {} (forceRefresh={})", accountId, forceRefresh);
            AzureSecurityResponse response = azureSecurityService.getSecurityData(accountId, forceRefresh);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to fetch Azure security data for {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.ok(new AzureSecurityResponse(
                    AzureSecurityResponse.Status.API_ERROR, 0, 100, new ArrayList<>(), new ArrayList<>()));
        }
    }
}
