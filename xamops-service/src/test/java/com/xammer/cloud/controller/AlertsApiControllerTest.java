package com.xammer.cloud.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.dto.AlertsResponseDto;
import com.xammer.cloud.service.CloudGuardService;
import com.xammer.cloud.service.DashboardDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The AWS Alerts endpoint's two duties towards the page: never serve an old snapshot as current,
 * and never answer a failed scan with an empty list the page would render as "All Clear!".
 */
class AlertsApiControllerTest {

    private static final String ACCOUNT_ID = "123456789012";

    @Mock private CloudGuardService cloudGuardService;
    @Mock private DashboardDataService dashboardDataService;

    private AlertsApiController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AlertsApiController(cloudGuardService, dashboardDataService, Runnable::run);
    }

    @Test
    void pageLoad_readsTheSnapshotWithAnAgeLimit() {
        AlertsResponseDto snapshot = AlertsResponseDto.builder().alerts(List.of()).errors(List.of()).build();
        when(dashboardDataService.getAlertsSnapshot(eq("alerts-" + ACCOUNT_ID), any(TypeReference.class),
                eq(CloudGuardService.ALERTS_SNAPSHOT_MAX_AGE_MINUTES))).thenReturn(snapshot);

        ResponseEntity<AlertsResponseDto> response = controller.getAlerts(ACCOUNT_ID, false).join();

        assertThat(response.getBody()).isSameAs(snapshot);
        verify(cloudGuardService, never()).getAlerts(anyString(), anyBoolean());
    }

    @Test
    void staleSnapshot_rebuildsLive() {
        // Past the age limit the snapshot read returns null.
        when(dashboardDataService.getAlertsSnapshot(anyString(), any(TypeReference.class), anyInt()))
                .thenReturn(null);
        AlertsResponseDto live = AlertsResponseDto.builder().alerts(List.of()).errors(List.of()).build();
        when(cloudGuardService.getAlerts(ACCOUNT_ID, false)).thenReturn(CompletableFuture.completedFuture(live));

        ResponseEntity<AlertsResponseDto> response = controller.getAlerts(ACCOUNT_ID, false).join();

        assertThat(response.getBody()).isSameAs(live);
        verify(dashboardDataService).saveAlertsSnapshot("alerts-" + ACCOUNT_ID, live);
    }

    @Test
    void scanThatFinishesLater_isStillSaved() {
        // A scan that outlives the request's timeout used to be thrown away, so the retry the
        // timeout message suggests started the whole scan again.
        CompletableFuture<AlertsResponseDto> scan = new CompletableFuture<>();
        when(cloudGuardService.getAlerts(ACCOUNT_ID, true)).thenReturn(scan);

        controller.getAlerts(ACCOUNT_ID, true);
        verify(dashboardDataService, never()).saveAlertsSnapshot(anyString(), any());

        AlertsResponseDto result = AlertsResponseDto.builder()
                .alerts(List.of(new com.xammer.cloud.dto.AlertDto())).errors(List.of()).build();
        scan.complete(result);

        verify(dashboardDataService).saveAlertsSnapshot("alerts-" + ACCOUNT_ID, result);
    }

    @Test
    void resultWithOnlyErrors_isNotSaved() {
        // Saved, it would be served as the account's alerts for the next hour instead of retried.
        AlertsResponseDto failed = AlertsResponseDto.builder()
                .alerts(List.of())
                .errors(List.of(com.xammer.cloud.dto.AlertErrorDto.builder().service("SYSTEM").build()))
                .build();
        when(cloudGuardService.getAlerts(ACCOUNT_ID, true)).thenReturn(CompletableFuture.completedFuture(failed));

        controller.getAlerts(ACCOUNT_ID, true).join();

        verify(dashboardDataService, never()).saveAlertsSnapshot(anyString(), any());
    }

    @Test
    void failedScan_answersWithTheErrorNotAnEmptyList() {
        when(cloudGuardService.getAlerts(ACCOUNT_ID, true))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        ResponseEntity<AlertsResponseDto> response = controller.getAlerts(ACCOUNT_ID, true).join();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getAlerts()).isEmpty();
        assertThat(response.getBody().getErrors()).hasSize(1);
        assertThat(response.getBody().getErrors().get(0).getErrorType()).isEqualTo("API_ERROR");
        assertThat(response.getBody().getErrors().get(0).getMessage()).contains("boom");
    }
}
