package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.service.azure.AzureDashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/xamops/azure")
public class AzureDashboardController {

    private static final Logger logger = LoggerFactory.getLogger(AzureDashboardController.class);
    private final AzureDashboardService azureDashboardService;

    @Autowired
    public AzureDashboardController(AzureDashboardService azureDashboardService) {
        this.azureDashboardService = azureDashboardService;
    }

    @GetMapping("/dashboard-data")
    public ResponseEntity<AzureDashboardData> getDashboardData(@RequestParam String accountId) {
        try {
            logger.info("Fetching Azure dashboard data for account: {}", accountId);
            AzureDashboardData data = azureDashboardService.getDashboardData(accountId);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            logger.error("Error fetching Azure dashboard data for account: " + accountId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}