package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzurePerformanceResponse;
import com.xammer.cloud.service.azure.AzurePerformanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

/**
 * REST controller for Azure Advisor Performance insights.
 *
 * GET /api/xamops/azure/performance?accountId={subscriptionId}&forceRefresh=false
 */
@RestController
@RequestMapping("/api/xamops/azure")
public class AzurePerformanceController {

    private static final Logger log = LoggerFactory.getLogger(AzurePerformanceController.class);

    private final AzurePerformanceService azurePerformanceService;

    public AzurePerformanceController(AzurePerformanceService azurePerformanceService) {
        this.azurePerformanceService = azurePerformanceService;
    }

    @GetMapping("/performance")
    public ResponseEntity<AzurePerformanceResponse> getPerformanceInsights(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            log.info("Azure Performance request - Subscription: {} (forceRefresh={})", accountId, forceRefresh);
            AzurePerformanceResponse response = azurePerformanceService.getPerformanceInsights(accountId, forceRefresh);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to fetch Azure performance data for {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.ok(new AzurePerformanceResponse(
                    AzurePerformanceResponse.Status.API_ERROR, 100, new ArrayList<>()));
        }
    }
}
