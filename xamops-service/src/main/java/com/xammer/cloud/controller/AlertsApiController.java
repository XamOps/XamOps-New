// package com.xammer.cloud.controller;

// import com.xammer.cloud.dto.AlertDto;
// import com.xammer.cloud.service.CloudGuardService;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.http.ResponseEntity;
// import org.springframework.web.bind.annotation.GetMapping;
// import org.springframework.web.bind.annotation.RequestMapping;
// import org.springframework.web.bind.annotation.RequestParam;
// import org.springframework.web.bind.annotation.RestController;

// import java.util.Collections;
// import java.util.List;
// import java.util.concurrent.CompletableFuture;

// @RestController
// @RequestMapping("/api/xamops/cloudguard")
// public class AlertsApiController {

//     private static final Logger logger = LoggerFactory.getLogger(AlertsApiController.class);

//     private final CloudGuardService cloudGuardService;

//     public AlertsApiController(CloudGuardService cloudGuardService) {
//         this.cloudGuardService = cloudGuardService;
//     }

//     @GetMapping("/alerts")
//     public CompletableFuture<ResponseEntity<List<AlertDto>>> getAlerts(
//             @RequestParam String accountId,
//             @RequestParam(defaultValue = "false") boolean forceRefresh) {
//         return cloudGuardService.getAlerts(accountId, forceRefresh)
//                 .thenApply(ResponseEntity::ok)
//                 .exceptionally(ex -> {
//                     logger.error("Error fetching alerts for account {}", accountId, ex);
//                     return ResponseEntity.status(500).body(Collections.emptyList());
//                 });
//     }
// }






// UPDATED ALERTSAPICONTROLLER FILE WITH GRAFANA ALERTS CHANGES 

package com.xammer.cloud.controller;

import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.AlertsResponse;
import com.xammer.cloud.dto.GrafanaWebhookDto; // <-- IMPORT NEW DTO
import com.xammer.cloud.service.CloudGuardService;
import com.xammer.cloud.service.GrafanaAlertCache; // <-- IMPORT NEW SERVICE
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*; // <-- UPDATED IMPORTS

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/cloudguard")
public class AlertsApiController {

    private static final Logger logger = LoggerFactory.getLogger(AlertsApiController.class);

    private final CloudGuardService cloudGuardService;
    private final GrafanaAlertCache grafanaAlertCache; // <-- ADD THIS

    // MODIFY THE CONSTRUCTOR
    public AlertsApiController(CloudGuardService cloudGuardService, GrafanaAlertCache grafanaAlertCache) {
        this.cloudGuardService = cloudGuardService;
        this.grafanaAlertCache = grafanaAlertCache; // <-- ADD THIS
    }

    /**
     * Enhanced CloudGuard alerts endpoint with error details and partial success support.
     * Returns AlertsResponse with alerts, errors, and metadata.
     */
    @GetMapping("/alerts")
    public CompletableFuture<ResponseEntity<AlertsResponse>> getAlerts(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        return cloudGuardService.getAlertsWithDetails(accountId, forceRefresh)
            .thenApply(response -> {
                // Return 200 for success or partial success (some alerts + some errors)
                if (!response.getAlerts().isEmpty() || response.getErrors().isEmpty()) {
                    return ResponseEntity.ok(response);
                }

                // Return 503 if all services failed (no alerts, only errors)
                if (response.getAlerts().isEmpty() && !response.getErrors().isEmpty()) {
                    logger.error("All alert services failed for account {}", accountId);
                    return ResponseEntity.status(503).body(response);
                }

                return ResponseEntity.ok(response);
            })
            .exceptionally(ex -> {
                logger.error("Unexpected error in alerts controller for account {}", accountId, ex);

                // Return error response with system error
                AlertsResponse errorResponse = new AlertsResponse();
                errorResponse.setErrors(List.of(new AlertsResponse.AlertError(
                    "SYSTEM",
                    "UNKNOWN",
                    "Unexpected system error: " + ex.getMessage(),
                    false,
                    LocalDateTime.now()
                )));
                errorResponse.setMetadata(new AlertsResponse.AlertsMetadata(0, 0, false));

                return ResponseEntity.status(500).body(errorResponse);
            });
    }

    /**
     * Legacy endpoint for backward compatibility.
     * Returns simple list of alerts without error details.
     */
    @GetMapping("/alerts/legacy")
    public CompletableFuture<ResponseEntity<List<AlertDto>>> getAlertsLegacy(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return cloudGuardService.getAlerts(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching alerts (legacy) for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    // --- ADD THE TWO NEW ENDPOINTS BELOW ---

    /**
     * NEW INGESTION ENDPOINT
     * This receives the webhook from Grafana at:
     * /api/xamops/cloudguard/grafana-ingest
     */
    @PostMapping("/grafana-ingest")
    public ResponseEntity<Void> receiveGrafanaWebhook(@RequestBody GrafanaWebhookDto payload) {
        logger.info("Received Grafana webhook!");
        if (payload.getAlerts() != null) {
             logger.info("Processing {} alerts.", payload.getAlerts().size());
        }
        grafanaAlertCache.processIncomingAlerts(payload);
        return ResponseEntity.ok().build();
    }

    /**
     * NEW DISPLAY ENDPOINT
     * This gives the alerts to your frontend.
     * Full URL: /api/xamops/cloudguard/grafana-alerts
     */
    @GetMapping("/grafana-alerts")
    public ResponseEntity<List<AlertDto>> getGrafanaAlerts() {
        return ResponseEntity.ok(grafanaAlertCache.getCachedAlerts());
    }
}