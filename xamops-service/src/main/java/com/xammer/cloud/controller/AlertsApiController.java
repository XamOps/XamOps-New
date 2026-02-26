package com.xammer.cloud.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.config.multitenancy.TenantContextUtil;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.AlertsResponseDto;
import com.xammer.cloud.service.CloudGuardService;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.GrafanaAlertCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final GrafanaAlertCache grafanaAlertCache;
    private final Executor awsTaskExecutor;

    public AlertsApiController(CloudGuardService cloudGuardService,
                               DashboardDataService dashboardDataService,
                               GrafanaAlertCache grafanaAlertCache,
                               @Qualifier("awsTaskExecutor") Executor awsTaskExecutor) {
        this.cloudGuardService = cloudGuardService;
        this.dashboardDataService = dashboardDataService;
        this.grafanaAlertCache = grafanaAlertCache;
        this.awsTaskExecutor = awsTaskExecutor;
    }

    @GetMapping("/grafana-alerts")
    public ResponseEntity<List<AlertDto>> getGrafanaAlerts() {
        return ResponseEntity.ok(grafanaAlertCache.getCachedAlerts());
    }

    @GetMapping("/alerts")
    public CompletableFuture<ResponseEntity<AlertsResponseDto>> getAlerts(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        String cacheKey = "alerts-" + accountId;

        if (!forceRefresh) {
            AlertsResponseDto dbCached = (AlertsResponseDto) dashboardDataService
                    .getAlertsSnapshot(cacheKey, new TypeReference<AlertsResponseDto>() {});
            if (dbCached != null) {
                logger.info("Alerts served from DB snapshot for account {}", accountId);
                return CompletableFuture.completedFuture(ResponseEntity.ok(dbCached));
            }
        }

        return cloudGuardService.getAlerts(accountId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .thenApplyAsync(alerts -> {
                    dashboardDataService.saveAlertsSnapshot(cacheKey, alerts);
                    return ResponseEntity.ok(alerts);
                }, TenantContextUtil.tenantAware(awsTaskExecutor))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        logger.warn("Alerts request timed out for account {}", accountId);
                    } else {
                        logger.error("Error fetching alerts for account {}", accountId, ex);
                    }
                    return ResponseEntity.status(503).body(AlertsResponseDto.builder()
                            .alerts(Collections.emptyList())
                            .errors(Collections.emptyList())
                            .build());
                });
    }
}