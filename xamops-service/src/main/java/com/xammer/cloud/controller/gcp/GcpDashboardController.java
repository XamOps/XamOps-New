package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpDashboardData;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
// This should be the full, explicit path
@RequestMapping("/api/xamops/gcp/dashboard")
public class GcpDashboardController {

    private static final Logger logger = LoggerFactory.getLogger(GcpDashboardController.class);
    private final GcpDataService gcpDataService;

    public GcpDashboardController(GcpDataService gcpDataService) {
        this.gcpDataService = gcpDataService;
    }

    /**
     * Fetches dashboard data for a given GCP account ID.
     * This endpoint now returns a ResponseEntity to ensure JSON is always returned,
     * even in case of an error.
     *
     * @param accountId The GCP account ID.
     * @param forceRefresh A boolean to indicate whether to bypass the cache.
     * @return A CompletableFuture containing a ResponseEntity with the dashboard data or an error message.
     */
    @GetMapping
    public CompletableFuture<ResponseEntity<?>> getDashboardData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        // Asynchronously fetch the data from the service
        return gcpDataService.getDashboardData(accountId, forceRefresh) // <-- PARAMETER PASSED
                // If the future completes successfully, wrap the data in a 200 OK response.
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                // If any exception occurs during the future's execution...
                .exceptionally(ex -> {
                    // Log the error for debugging purposes.
                    logger.error("Failed to fetch GCP dashboard data for accountId: {}", accountId, ex);
                    // ...return a 500 Internal Server Error with a JSON body.
                    return ResponseEntity
                            .status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("error", "Failed to retrieve GCP dashboard data.", "message", ex.getMessage()));
                });
    }
}