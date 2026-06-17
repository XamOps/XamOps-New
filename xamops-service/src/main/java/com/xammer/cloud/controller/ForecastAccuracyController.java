package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ForecastAccuracyMetrics;
import com.xammer.cloud.service.ForecastAccuracyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

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

    private final ForecastAccuracyService accuracyService;

    public ForecastAccuracyController(ForecastAccuracyService accuracyService) {
        this.accuracyService = accuracyService;
    }

    /**
     * Accuracy metrics for an account. If serviceName is omitted, aggregates across
     * all services. provider defaults to AWS.
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
        return ResponseEntity.ok(metrics);
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
}
