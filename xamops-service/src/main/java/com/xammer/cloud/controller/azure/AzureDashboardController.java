package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.service.azure.AzureDashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/xamops/azure")
public class AzureDashboardController {

    private static final Logger log = LoggerFactory.getLogger(AzureDashboardController.class);

    @Autowired
    private AzureDashboardService azureDashboardService;

    @GetMapping("/dashboard-data")
    public ResponseEntity<AzureDashboardData> getDashboardData(@RequestParam String accountId, @RequestParam(defaultValue = "false") boolean force) {
        try {
            // The accountId here is the Azure Subscription ID
            AzureDashboardData data = azureDashboardService.getDashboardData(accountId, force);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error fetching Azure dashboard data for accountId: {}", accountId, e);
            // Return a proper 500 error response
            return ResponseEntity.status(500).build();
        }
    }
}