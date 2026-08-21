package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostByTagDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ReservationDto;
import com.xammer.cloud.dto.ReservationModificationRequestDto;
import com.xammer.cloud.dto.SavingsPlanRecommendationDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/reservations")
public class ReservationController {

    private static final Logger logger = LoggerFactory.getLogger(ReservationController.class);

    private final ReservationService reservationService;
    private final CloudAccountRepository cloudAccountRepository;
    private final DashboardDataService dashboardDataService;

    public ReservationController(ReservationService reservationService,
            CloudAccountRepository cloudAccountRepository,
            DashboardDataService dashboardDataService) {
        this.reservationService = reservationService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.dashboardDataService = dashboardDataService;
    }

    @GetMapping
    public CompletableFuture<ResponseEntity<ReservationDto>> getReservationData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        String cacheKey = "RESERVATION::" + accountId;

        if (!forceRefresh) {
            // Check DB snapshot first (survives container restarts)
            ReservationDto dbCached = dashboardDataService.getReservationSnapshot(cacheKey);
            if (dbCached != null) {
                logger.info("[Reservation] ✅ Serving from DB snapshot for account {}", accountId);
                return CompletableFuture.completedFuture(ResponseEntity.ok(dbCached));
            }
        }

        logger.info("[Reservation] Fetching live data for account {}", accountId);

        return reservationService.getReservationPageData(accountId, forceRefresh)
                .thenApply(data -> {
                    if (data != null) {
                        dashboardDataService.saveReservationSnapshot(cacheKey, data);
                        logger.info("[Reservation] ✅ Saved to DB snapshot for account {}", accountId);
                    }
                    return ResponseEntity.ok(data);
                })
                .exceptionally(ex -> {
                    logger.error("[Reservation] Error fetching data for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(null);
                });
    }

    @GetMapping("/recommendations")
    public CompletableFuture<ResponseEntity<List<DashboardData.ReservationPurchaseRecommendation>>> getPurchaseRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "ONE_YEAR") String term,
            @RequestParam(defaultValue = "NO_UPFRONT") String paymentOption,
            @RequestParam(defaultValue = "THIRTY_DAYS") String lookback,
            @RequestParam(defaultValue = "STANDARD") String offeringClass,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found: " + accountId);
        }
        CloudAccount account = accounts.get(0);

        return reservationService.getPurchaseRecommendationSet(
                    account, term, paymentOption, lookback, offeringClass, forceRefresh
                )
                // An empty list means either "nothing worth reserving" or "we could not find
                // out", and the UI renders both as "Fully Optimized". These headers let it tell
                // the difference without changing the response body's shape.
                .thenApply(set -> ResponseEntity.ok()
                        .header("X-Recommendations-Complete", String.valueOf(set.complete()))
                        .header("X-Recommendations-Failed-Services", String.join(",", set.failedServices()))
                        .body(set.recommendations()))
                .exceptionally(ex -> {
                    logger.error("Error fetching purchase recommendations for account {}", accountId, ex);
                    return ResponseEntity.status(500)
                            .header("X-Recommendations-Complete", "false")
                            .body(Collections.emptyList());
                });
    }

    @GetMapping("/sp-recommendations")
    public CompletableFuture<ResponseEntity<List<SavingsPlanRecommendationDto>>> getSpRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "COMPUTE_SP") String spType,
            @RequestParam(defaultValue = "ONE_YEAR") String term,
            @RequestParam(defaultValue = "NO_UPFRONT") String paymentOption,
            @RequestParam(defaultValue = "THIRTY_DAYS") String lookback,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found: " + accountId);
        }
        CloudAccount account = accounts.get(0);

        return reservationService.getSavingsPlanRecommendations(account, spType, term, paymentOption, lookback, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching SP recommendations for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/cost-by-tag")
    public CompletableFuture<ResponseEntity<List<CostByTagDto>>> getReservationCostByTag(
            @RequestParam String accountId,
            @RequestParam String tagKey,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return reservationService.getReservationCostByTag(accountId, tagKey, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching reservation cost by tag for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @PostMapping("/modify")
    public ResponseEntity<Map<String, String>> modifyReservation(
            @RequestParam String accountId,
            @RequestBody ReservationModificationRequestDto modificationRequest) {
        try {
            String transactionId = reservationService.applyReservationModification(accountId, modificationRequest);
            return ResponseEntity.ok(Map.of("status", "success", "transactionId", transactionId));
        } catch (Exception e) {
            logger.error("Error modifying reservation for account {}", accountId, e);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
