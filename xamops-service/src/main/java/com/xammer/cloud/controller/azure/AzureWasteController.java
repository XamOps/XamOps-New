package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureWasteItem;
import com.xammer.cloud.service.azure.AzureWasteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * REST controller for Azure Waste Management detection.
 *
 * GET /api/xamops/azure/waste?accountId={subscriptionId}&forceRefresh=false
 */
@RestController
@RequestMapping("/api/xamops/azure")
public class AzureWasteController {

    private static final Logger log = LoggerFactory.getLogger(AzureWasteController.class);

    private final AzureWasteService azureWasteService;

    public AzureWasteController(AzureWasteService azureWasteService) {
        this.azureWasteService = azureWasteService;
    }

    @GetMapping("/waste")
    public ResponseEntity<List<AzureWasteItem>> getWasteItems(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            log.info("Azure Waste request - Subscription: {} (forceRefresh={})", accountId, forceRefresh);
            List<AzureWasteItem> items = azureWasteService.getWasteItems(accountId, forceRefresh);
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            log.error("Failed to fetch Azure waste items for {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }
}
