package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpAlertsResponse;
import com.xammer.cloud.service.gcp.GcpCloudGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/cloudguard") // Changed path
@Slf4j
public class GcpAlertsApiController {

    private final GcpCloudGuardService gcpCloudGuardService;

    public GcpAlertsApiController(GcpCloudGuardService gcpCloudGuardService) {
        this.gcpCloudGuardService = gcpCloudGuardService;
    }

    /**
     * Returns the alert list plus the metadata describing how current and how complete it is.
     *
     * <p>
     * A failure is answered with {@code 200} and a degraded payload rather than {@code 500} and an
     * empty body. The client needs to render "we could not check" — an empty list on an error status
     * gave it nothing to distinguish that from a clean project, and the page showed "All Clear!"
     * either way.
     */
    @GetMapping("/alerts")
    public CompletableFuture<ResponseEntity<GcpAlertsResponse>> getAlerts(
            @RequestParam String accountId, // accountId is gcpProjectId here
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return gcpCloudGuardService.getAlerts(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    log.error("Error fetching GCP alerts for project {}", accountId, ex);
                    return ResponseEntity.ok(GcpAlertsResponse.failed(
                            "The alert scan could not be completed. Please retry in a few minutes."));
                });
    }
}
