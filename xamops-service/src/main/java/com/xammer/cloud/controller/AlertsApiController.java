package com.xammer.cloud.controller;

import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.service.CloudGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/cloudguard")
public class AlertsApiController {

    private static final Logger logger = LoggerFactory.getLogger(AlertsApiController.class);

    private final CloudGuardService cloudGuardService;

    public AlertsApiController(CloudGuardService cloudGuardService) {
        this.cloudGuardService = cloudGuardService;
    }

    @GetMapping("/alerts")
    public CompletableFuture<ResponseEntity<List<AlertDto>>> getAlerts(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return cloudGuardService.getAlerts(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching alerts for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }
}