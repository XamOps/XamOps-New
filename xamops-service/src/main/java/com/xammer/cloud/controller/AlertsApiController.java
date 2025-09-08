package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.CloudGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/cloudguard")
public class AlertsApiController {

    private static final Logger logger = LoggerFactory.getLogger(AlertsApiController.class);

    private final CloudGuardService cloudGuardService;

    @Autowired
    public AlertsApiController(CloudGuardService cloudGuardService) {
        this.cloudGuardService = cloudGuardService;
    }

    @GetMapping("/alerts")
    public ResponseEntity<List<DashboardData.ServiceQuotaInfo>> getAlerts(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("Received request for alerts for account ID: {}", accountId);

        try {
            // Corrected method call to use the new method in CloudGuardService
            List<DashboardData.ServiceQuotaInfo> alerts = cloudGuardService.getVpcQuotaAlerts(accountId, forceRefresh).get();
            return ResponseEntity.ok(alerts);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Failed to fetch alerts for account ID: {}. Error: {}", accountId, e.getMessage());
            return ResponseEntity.status(500).body(Collections.emptyList());
        }
    }
}