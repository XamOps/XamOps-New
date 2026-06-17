package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.domain.ForecastAccuracyRecord;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.service.ForecastAccuracyService;
import com.xammer.cloud.service.ForecastingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/forecast")
public class GcpForecastingController {

    private final ForecastingService forecastingService;
    private final ForecastAccuracyService forecastAccuracyService;

    public GcpForecastingController(ForecastingService forecastingService,
                                    ForecastAccuracyService forecastAccuracyService) {
        this.forecastingService = forecastingService;
        this.forecastAccuracyService = forecastAccuracyService;
    }

    @GetMapping("/cost")
    public CompletableFuture<ResponseEntity<List<ForecastDto>>> getCostForecast(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "30") int periods,
            @RequestParam(required = false, defaultValue = "ALL") String serviceName) {

        return forecastingService.getGcpCostForecast(accountId, serviceName, periods)
                .thenApply(forecast -> {
                    // Record predictions for accuracy tracking (fire-and-forget).
                    if (forecast != null && !forecast.isEmpty()) {
                        try {
                            forecastAccuracyService.recordPredictions(
                                    ForecastAccuracyRecord.PROVIDER_GCP, accountId, serviceName, forecast);
                        } catch (Exception ignored) {
                            // never break the forecast response on tracking failure
                        }
                    }
                    return ResponseEntity.ok(forecast);
                });
    }
}