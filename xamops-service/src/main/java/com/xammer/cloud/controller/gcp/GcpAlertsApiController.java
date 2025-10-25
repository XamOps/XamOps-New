package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.service.gcp.GcpCloudGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/cloudguard") // Changed path
@Slf4j
public class GcpAlertsApiController {

    private final GcpCloudGuardService gcpCloudGuardService;

    public GcpAlertsApiController(GcpCloudGuardService gcpCloudGuardService) {
        this.gcpCloudGuardService = gcpCloudGuardService;
    }

    @GetMapping("/alerts")
    public CompletableFuture<ResponseEntity<List<AlertDto>>> getAlerts(
            @RequestParam String accountId, // accountId is gcpProjectId here
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return gcpCloudGuardService.getAlerts(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    log.error("Error fetching GCP alerts for project {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }
}