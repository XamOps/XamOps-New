package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureSecurityAssessment;
import com.xammer.cloud.dto.azure.AzureSecurityResponse;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.azure.AzureSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The {@code /security/findings} adapter the shared Security card reads.
 *
 * <p>The card buckets each item by {@code severity}, so a usable response is a flat array whose
 * items carry severity. The false-zero discipline is the other half: a permission/API failure must
 * be a 503 (card shows an error), not a 200 empty array (card shows a verified "0 findings").
 */
class AzureSecurityFindingsControllerTest {

    private static final String SUB = "sub-123";

    @Mock private AzureSecurityService azureSecurityService;
    @Mock private ProwlerService prowlerService;

    private AzureSecurityController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AzureSecurityController(azureSecurityService, prowlerService);
    }

    private AzureSecurityAssessment assessment(String id, String severity, String name) {
        return new AzureSecurityAssessment(id, name, severity, "Unhealthy",
                "/subscriptions/x/resourceGroups/rg/providers/p/r", "r", "Compute", "fix it");
    }

    @Test
    @DisplayName("OK with two unhealthy assessments → 200 array, each carrying severity")
    void okReturnsArrayWithSeverities() {
        List<AzureSecurityAssessment> assessments = new ArrayList<>(List.of(
                assessment("a1", "High", "Enable MFA"),
                assessment("a2", "Medium", "Patch VM")));
        AzureSecurityResponse resp = new AzureSecurityResponse(
                AzureSecurityResponse.Status.OK, 40, 100, new ArrayList<>(), assessments);
        when(azureSecurityService.getSecurityData(SUB, false)).thenReturn(resp);

        ResponseEntity<List<Map<String, Object>>> response = controller.getSecurityFindings(SUB, false);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).get("severity")).isEqualTo("High");
        assertThat(response.getBody().get(1).get("severity")).isEqualTo("Medium");
        assertThat(response.getBody().get(0).get("title")).isEqualTo("Enable MFA");
    }

    @Test
    @DisplayName("NO_DATA → 200 empty array (an honest 'nothing found'), not an error")
    void noDataReturnsEmptyArray() {
        AzureSecurityResponse resp = new AzureSecurityResponse(
                AzureSecurityResponse.Status.NO_DATA, 0, 100, new ArrayList<>(), new ArrayList<>());
        when(azureSecurityService.getSecurityData(SUB, false)).thenReturn(resp);

        ResponseEntity<List<Map<String, Object>>> response = controller.getSecurityFindings(SUB, false);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("PERMISSION_ERROR → 503, never a measured empty list")
    void permissionErrorIsServiceUnavailable() {
        AzureSecurityResponse resp = new AzureSecurityResponse(
                AzureSecurityResponse.Status.PERMISSION_ERROR, 0, 100, new ArrayList<>(), new ArrayList<>());
        when(azureSecurityService.getSecurityData(SUB, false)).thenReturn(resp);

        ResponseEntity<List<Map<String, Object>>> response = controller.getSecurityFindings(SUB, false);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("a thrown exception answers 503")
    void exceptionIsServiceUnavailable() {
        when(azureSecurityService.getSecurityData(anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("boom"));

        ResponseEntity<List<Map<String, Object>>> response = controller.getSecurityFindings(SUB, false);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
    }
}
