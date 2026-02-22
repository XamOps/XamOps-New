package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureAlertsResponse;
import com.xammer.cloud.service.azure.AzureAlertsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

/**
 * REST controller for Azure Monitor alerts.
 *
 * GET /api/xamops/azure/alerts?accountId={subscriptionId}&forceRefresh=false
 */
@RestController
@RequestMapping("/api/xamops/azure")
public class AzureAlertsController {

    private static final Logger log = LoggerFactory.getLogger(AzureAlertsController.class);

    private final AzureAlertsService azureAlertsService;

    public AzureAlertsController(AzureAlertsService azureAlertsService) {
        this.azureAlertsService = azureAlertsService;
    }

    @GetMapping("/alerts")
    public ResponseEntity<AzureAlertsResponse> getAlerts(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            log.info("Azure Alerts request - Subscription: {} (forceRefresh={})", accountId, forceRefresh);
            AzureAlertsResponse response = azureAlertsService.getAlerts(accountId, forceRefresh);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to fetch Azure alerts for {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.ok(new AzureAlertsResponse(
                    AzureAlertsResponse.Status.API_ERROR, new ArrayList<>()));
        }
    }
}
