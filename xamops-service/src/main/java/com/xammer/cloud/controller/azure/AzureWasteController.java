package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureWasteItem;
import com.xammer.cloud.service.azure.AzureWasteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for Azure Waste Management detection.
 *
 * GET /api/xamops/azure/waste?accountId={subscriptionId}&forceRefresh=false
 * GET /api/xamops/azure/waste?accountIds={subscriptionId}&forceRefresh=false
 */
@RestController
@RequestMapping("/api/xamops/azure")
public class AzureWasteController {

    private static final Logger log = LoggerFactory.getLogger(AzureWasteController.class);

    private final AzureWasteService azureWasteService;

    public AzureWasteController(AzureWasteService azureWasteService) {
        this.azureWasteService = azureWasteService;
    }

    /**
     * Accepts the subscription id under both {@code accountId} and {@code accountIds}.
     *
     * <p>The shared dashboard summary card (used for AWS, GCP and Azure) sends {@code accountIds}
     * to match the AWS {@code DashboardController} /waste route, while the standalone Azure Waste
     * page sends {@code accountId}. Binding only {@code accountId} (required) made Spring answer
     * the card's request with a 400, which the card rendered as "No waste detected" — a false
     * all-clear. Supporting both names keeps every caller working without changing the service.
     */
    @GetMapping("/waste")
    public ResponseEntity<List<AzureWasteItem>> getWasteItems(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        String subscriptionId = firstNonBlank(accountId, accountIds);
        if (subscriptionId == null) {
            log.warn("Azure Waste request rejected: neither accountId nor accountIds was provided");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        try {
            log.info("Azure Waste request - Subscription: {} (forceRefresh={})", subscriptionId, forceRefresh);
            List<AzureWasteItem> items = azureWasteService.getWasteItems(subscriptionId, forceRefresh);
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            // Returning 200 with an empty list made an expired service-principal secret or a
            // missing role look identical to a subscription with nothing to optimize.
            log.error("Failed to fetch Azure waste items for {}: {}", subscriptionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
