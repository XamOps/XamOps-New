package com.xammer.cloud.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.config.multitenancy.TenantContextUtil;
import com.xammer.cloud.dto.AlertErrorDto;
import com.xammer.cloud.dto.AlertsResponseDto;
import com.xammer.cloud.service.CloudGuardService;
import com.xammer.cloud.service.DashboardDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/xamops/cloudguard")
public class AlertsApiController {

    private static final Logger logger = LoggerFactory.getLogger(AlertsApiController.class);

    private final CloudGuardService cloudGuardService;
    private final DashboardDataService dashboardDataService;
    private final Executor awsTaskExecutor;

    public AlertsApiController(CloudGuardService cloudGuardService,
                               DashboardDataService dashboardDataService,
                               @Qualifier("awsTaskExecutor") Executor awsTaskExecutor) {
        this.cloudGuardService = cloudGuardService;
        this.dashboardDataService = dashboardDataService;
        this.awsTaskExecutor = awsTaskExecutor;
    }

    /**
     * A scan that produced nothing but errors is not kept: saved, it would be served as the
     * account's alerts for the next hour instead of being retried.
     */
    private static boolean worthSaving(AlertsResponseDto result) {
        if (result == null) {
            return false;
        }
        boolean noAlerts = result.getAlerts() == null || result.getAlerts().isEmpty();
        boolean hasErrors = result.getErrors() != null && !result.getErrors().isEmpty();
        return !(noAlerts && hasErrors);
    }

    @GetMapping("/alerts")
    public CompletableFuture<ResponseEntity<AlertsResponseDto>> getAlerts(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        String cacheKey = "alerts-" + accountId;

        if (!forceRefresh) {
            // Age-limited: past the cutoff this returns null and the request rebuilds live, so a
            // page load never serves the list the last scheduled run left behind days ago.
            AlertsResponseDto dbCached = (AlertsResponseDto) dashboardDataService
                    .getAlertsSnapshot(cacheKey, new TypeReference<AlertsResponseDto>() {},
                            CloudGuardService.ALERTS_SNAPSHOT_MAX_AGE_MINUTES);
            if (dbCached != null) {
                logger.info("Alerts served from DB snapshot for account {}", accountId);
                return CompletableFuture.completedFuture(ResponseEntity.ok(dbCached));
            }
        }

        CompletableFuture<AlertsResponseDto> scan = cloudGuardService.getAlerts(accountId, forceRefresh);

        // Saved when the scan finishes, whether or not this request is still waiting. It used to be
        // saved only on the response path, so a scan that outlived the timeout was never written,
        // and "retry in a minute" started the whole scan again.
        scan.thenAcceptAsync(result -> {
            if (worthSaving(result)) {
                dashboardDataService.saveAlertsSnapshot(cacheKey, result);
            }
        }, TenantContextUtil.tenantAware(awsTaskExecutor));

        // A copy, because orTimeout completes the future it is called on. On the scan itself it
        // would fail the scan for every other request that has joined it.
        return scan.copy()
                .orTimeout(55, TimeUnit.SECONDS)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    // The body names what went wrong. It used to carry an empty error list, which
                    // the page read as "nothing failed" and rendered as "All Clear!" — a timeout
                    // presented as a clean account.
                    AlertErrorDto error;
                    if (cause instanceof TimeoutException) {
                        logger.warn("Alerts request timed out for account {}", accountId);
                        // The scan is not cancelled by the timeout; it finishes in the background
                        // and caches its result, so a retry shortly after usually succeeds.
                        error = AlertErrorDto.builder()
                                .service("Alerts scan")
                                .errorType("TIMEOUT")
                                .message("The scan is taking longer than usual. It keeps running in the "
                                        + "background — retry in a minute to see the result.")
                                .timestamp(Instant.now().toString())
                                .build();
                    } else {
                        logger.error("Error fetching alerts for account {}", accountId, ex);
                        error = AlertErrorDto.builder()
                                .service("Alerts scan")
                                .errorType("API_ERROR")
                                .message("The alerts could not be loaded: " + cause.getMessage())
                                .timestamp(Instant.now().toString())
                                .build();
                    }
                    return ResponseEntity.status(503).body(AlertsResponseDto.builder()
                            .alerts(Collections.emptyList())
                            .errors(List.of(error))
                            .build());
                });
    }
}