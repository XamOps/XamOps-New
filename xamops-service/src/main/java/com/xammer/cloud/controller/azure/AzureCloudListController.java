package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.azure.AzureCloudListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/azure/cloudlist")
public class AzureCloudListController {

    private static final Logger log = LoggerFactory.getLogger(AzureCloudListController.class);
    private final AzureCloudListService azureCloudListService;

    public AzureCloudListController(AzureCloudListService azureCloudListService) {
        this.azureCloudListService = azureCloudListService;
    }

    @GetMapping("/resources")
    public ResponseEntity<?> getResources(@RequestParam String accountIds) {
        try {
            log.info("Received cloudlist request for accountIds: {}", accountIds);
            
            // Validate accountIds parameter
            if (accountIds == null || accountIds.trim().isEmpty()) {
                log.warn("Empty or null accountIds parameter received");
                return ResponseEntity.badRequest().body("accountIds parameter is required");
            }
            
            // This handles only the first account ID for Azure's single-selection model
            String accountId = accountIds.split(",")[0].trim();
            log.info("Processing cloudlist for Azure account ID: {}", accountId);
            
            List<DashboardData.ServiceGroupDto> resources = azureCloudListService.getAzureResources(accountId);
            
            log.info("Successfully fetched {} resource groups for account {}", 
                     resources != null ? resources.size() : 0, accountId);
            
            return ResponseEntity.ok(resources);
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid account ID: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid account: " + e.getMessage());
            
        } catch (Exception e) {
            log.error("Error fetching Azure cloudlist for accountIds {}: {}", accountIds, e.getMessage(), e);
            return ResponseEntity.status(500).body("Error loading resources: " + e.getMessage());
        }
    }
}
