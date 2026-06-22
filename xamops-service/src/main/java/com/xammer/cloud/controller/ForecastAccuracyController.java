package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ForecastAccuracyMetrics;
import com.xammer.cloud.domain.ForecastAccuracyRecord;
import com.xammer.cloud.service.ForecastAccuracyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exposes ML forecast accuracy metrics so we can measure whether the model is
 * actually any good (and whether model changes improve real-world accuracy).
 *
 * GET  /api/xamops/forecast/accuracy           → metrics for an account/service
 * POST /api/xamops/forecast/accuracy/evaluate  → manually run evaluation (testing/admin)
 */
@RestController
@RequestMapping("/api/xamops/forecast/accuracy")
public class ForecastAccuracyController {

    private static final Logger log = LoggerFactory.getLogger(ForecastAccuracyController.class);

    /** Cooldown so a missing-data account doesn't trigger a backfill on every page load. */
    private static final Duration AUTO_BACKFILL_COOLDOWN = Duration.ofMinutes(30);
    private static final int AUTO_BACKFILL_HORIZON = 30;
    private static final int AUTO_BACKFILL_ORIGINS = 4;

    private final ForecastAccuracyService accuracyService;
    private final ConcurrentHashMap<String, Instant> lastAutoBackfill = new ConcurrentHashMap<>();

    public ForecastAccuracyController(ForecastAccuracyService accuracyService) {
        this.accuracyService = accuracyService;
    }

    /**
     * Accuracy metrics for an account. If serviceName is omitted, aggregates across
     * all services. provider defaults to AWS.
     *
     * If there's no evaluated data yet (cold start), this auto-runs a one-time backfill
     * from stored daily cost snapshots so real metrics appear immediately instead of
     * sitting on "Calibrating…". Guarded by a per-account cooldown.
     *
     * Example: /api/xamops/forecast/accuracy?accountId=123&serviceName=ALL&days=90
     */
    @GetMapping
    public ResponseEntity<ForecastAccuracyMetrics> getAccuracy(
            @RequestParam String accountId,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false, defaultValue = "AWS") String provider,
            @RequestParam(defaultValue = "90") int days) {

        log.info("📊 Forecast accuracy request - Provider: {}, Account: {}, Service: {}, Days: {}",
                provider, accountId, serviceName, days);

        ForecastAccuracyMetrics metrics = accuracyService.getMetrics(provider, accountId, serviceName, days);

        // Cold start: auto-backfill from snapshots (AWS only) once per cooldown window.
        if ("INSUFFICIENT_DATA".equals(metrics.getDataQuality())
                && ForecastAccuracyRecord.PROVIDER_AWS.equalsIgnoreCase(provider)
                && shouldAutoBackfill(accountId)) {
            try {
                log.info("🔁 Cold start — auto-backfilling accuracy from snapshots for {}", accountId);
                accuracyService.backfillFromSnapshots(accountId, AUTO_BACKFILL_HORIZON, AUTO_BACKFILL_ORIGINS);
                metrics = accuracyService.getMetrics(provider, accountId, serviceName, days);
            } catch (Exception e) {
                log.warn("⚠️ Auto-backfill failed for {}: {}", accountId, e.getMessage());
            }
        }

        return ResponseEntity.ok(metrics);
    }

    private boolean shouldAutoBackfill(String accountId) {
        Instant now = Instant.now();
        Instant last = lastAutoBackfill.get(accountId);
        if (last != null && Duration.between(last, now).compareTo(AUTO_BACKFILL_COOLDOWN) < 0) {
            return false;
        }
        lastAutoBackfill.put(accountId, now);
        return true;
    }

    /**
     * Manually trigger evaluation of pending predictions. Handy for testing/backfill
     * without waiting for the nightly job.
     */
    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> triggerEvaluation() {
        log.info("🔄 Manual forecast accuracy evaluation triggered");
        Map<String, Object> response = new HashMap<>();
        try {
            int evaluated = accuracyService.evaluatePending();
            response.put("status", "success");
            response.put("evaluated", evaluated);
            response.put("message", "Evaluated " + evaluated + " pending prediction(s)");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Manual evaluation failed", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Backfill accuracy using stored daily cost snapshots — replays past forecasts and
     * scores them against actuals we already have, so real metrics appear immediately
     * instead of waiting days. AWS + all-services.
     *
     * Example: POST /api/xamops/forecast/accuracy/backfill?accountId=123&horizonDays=30&origins=4
     */
    @PostMapping("/backfill")
    public ResponseEntity<ForecastAccuracyMetrics> backfill(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "30") int horizonDays,
            @RequestParam(defaultValue = "4") int origins) {

        log.info("🔁 Forecast accuracy backfill - Account: {}, Horizon: {}d, Origins: {}",
                accountId, horizonDays, origins);
        ForecastAccuracyMetrics metrics =
                accuracyService.backfillFromSnapshots(accountId, horizonDays, origins);
        return ResponseEntity.ok(metrics);
    }
}
