package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureReservationData;
import com.xammer.cloud.service.azure.AzureReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for Azure Reservation Management.
 *
 * GET /api/xamops/azure/reservations?accountId={subscriptionId}&forceRefresh=false
 */
@RestController
@RequestMapping("/api/xamops/azure")
public class AzureReservationController {

    private static final Logger log = LoggerFactory.getLogger(AzureReservationController.class);

    private final AzureReservationService azureReservationService;

    public AzureReservationController(AzureReservationService azureReservationService) {
        this.azureReservationService = azureReservationService;
    }

    @GetMapping("/reservations")
    public ResponseEntity<AzureReservationData> getReservations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            log.info("Azure Reservations request - Subscription: {} (forceRefresh={})", accountId, forceRefresh);
            AzureReservationData data = azureReservationService.getReservationData(accountId, forceRefresh);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Failed to fetch Azure reservations for {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.ok(new AzureReservationData());
        }
    }
}
