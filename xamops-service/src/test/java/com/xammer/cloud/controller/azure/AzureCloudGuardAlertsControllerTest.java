package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.azure.AzureSecurityAlert;
import com.xammer.cloud.dto.azure.AzureSecurityResponse;
import com.xammer.cloud.service.azure.AzureCostAnomalyService;
import com.xammer.cloud.service.azure.AzureSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The {@code /cloudguard/alerts} adapter the shared Alerts &amp; Anomalies card reads.
 *
 * <p>It merges two honest Azure sources — cost anomalies and Defender alerts — into the
 * {@code {type, service, usage, severity}} shape the card's {@code normaliseAlerts} /
 * {@code computeAlertSummary} consume. A failure in one source must not drop the other.
 */
class AzureCloudGuardAlertsControllerTest {

    private static final String SUB = "sub-123";

    @Mock private AzureCostAnomalyService azureCostAnomalyService;
    @Mock private AzureSecurityService azureSecurityService;

    private AzureCloudGuardAlertsController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AzureCloudGuardAlertsController(azureCostAnomalyService, azureSecurityService);
    }

    @Test
    @DisplayName("one anomaly + one Defender alert → one ANOMALY item and one SECURITY item")
    void mergesAnomalyAndSecurityItems() {
        DashboardData.CostAnomaly anomaly = new DashboardData.CostAnomaly(
                "anom-1", "Virtual Machines", 42.0, LocalDate.now().minusDays(1), LocalDate.now());
        when(azureCostAnomalyService.detect(SUB, false)).thenReturn(
                new AzureCostAnomalyService.AnomalyOutcome(List.of(anomaly), null));

        List<AzureSecurityAlert> alerts = new ArrayList<>(List.of(new AzureSecurityAlert(
                "al-1", "Suspicious sign-in", "High", "Active", "vm-1", "desc", "2024-01-01T00:00:00Z")));
        AzureSecurityResponse security = new AzureSecurityResponse(
                AzureSecurityResponse.Status.OK, 50, 100, alerts, new ArrayList<>());
        when(azureSecurityService.getSecurityData(SUB, false)).thenReturn(security);

        ResponseEntity<List<Map<String, Object>>> response = controller.getAlerts(SUB, false);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> body = response.getBody();
        assertThat(body).hasSize(2);

        Map<String, Object> anomalyItem = body.stream()
                .filter(m -> "ANOMALY".equals(m.get("type"))).findFirst().orElseThrow();
        assertThat(anomalyItem.get("service")).isEqualTo("Virtual Machines");
        assertThat(anomalyItem.get("usage")).isEqualTo(42.0);

        Map<String, Object> securityItem = body.stream()
                .filter(m -> "SECURITY".equals(m.get("type"))).findFirst().orElseThrow();
        // normalized to the single severity vocabulary
        assertThat(securityItem.get("severity")).isEqualTo("HIGH");
        assertThat(securityItem.get("service")).isEqualTo("vm-1");
    }

    @Test
    @DisplayName("a failed anomaly read still returns the Defender alerts (partial, honest result)")
    void anomalyFailureDoesNotDropSecurity() {
        when(azureCostAnomalyService.detect(anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("boom"));

        List<AzureSecurityAlert> alerts = new ArrayList<>(List.of(new AzureSecurityAlert(
                "al-1", "Alert", "Medium", "Active", "vm-9", "desc", "t")));
        AzureSecurityResponse security = new AzureSecurityResponse(
                AzureSecurityResponse.Status.OK, 50, 100, alerts, new ArrayList<>());
        when(azureSecurityService.getSecurityData(SUB, false)).thenReturn(security);

        ResponseEntity<List<Map<String, Object>>> response = controller.getAlerts(SUB, false);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).get("type")).isEqualTo("SECURITY");
    }
}
