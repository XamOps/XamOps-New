package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureRightsizingResponse;
import com.xammer.cloud.service.azure.AzureBillingCurrencyService;
import com.xammer.cloud.service.azure.AzureRightsizingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

/**
 * REST controller for Azure Rightsizing recommendations.
 *
 * GET /api/xamops/azure/rightsizing/recommendations?accountId={subscriptionId}&forceRefresh=false
 */
@RestController
@RequestMapping("/api/xamops/azure")
public class AzureRightsizingController {

    private static final Logger log = LoggerFactory.getLogger(AzureRightsizingController.class);

    private final AzureRightsizingService azureRightsizingService;
    private final AzureBillingCurrencyService billingCurrencyService;

    public AzureRightsizingController(AzureRightsizingService azureRightsizingService,
                                      AzureBillingCurrencyService billingCurrencyService) {
        this.azureRightsizingService = azureRightsizingService;
        this.billingCurrencyService = billingCurrencyService;
    }

    @GetMapping("/rightsizing/recommendations")
    public ResponseEntity<AzureRightsizingResponse> getRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            log.info("Azure Rightsizing request - Subscription: {} (forceRefresh={})", accountId, forceRefresh);
            AzureRightsizingResponse response =
                    azureRightsizingService.getRecommendations(accountId, forceRefresh);
            // The service works in USD (cross-provider savings totals read its snapshot); the page
            // and the dashboard card print the subscription's billing currency, so convert here.
            return ResponseEntity.ok(billingCurrencyService.toBillingCurrency(
                    response, billingCurrencyService.forSubscription(accountId)));
        } catch (Exception e) {
            log.error("Failed to fetch Azure rightsizing recommendations for {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.ok(new AzureRightsizingResponse(
                    AzureRightsizingResponse.Status.API_ERROR, new ArrayList<>()));
        }
    }
}
