package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureWasteItem;
import com.xammer.cloud.service.azure.AzureWasteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The waste endpoint's contract with the shared dashboard summary card.
 *
 * <p>The card sends {@code accountIds} (matching the AWS /waste route); the standalone Azure Waste
 * page sends {@code accountId}. Both must bind to the same service call, and neither-present must
 * be a 400 rather than a silent empty list that reads as "no waste".
 */
class AzureWasteControllerTest {

    private static final String SUB = "sub-123";

    @Mock private AzureWasteService azureWasteService;

    private AzureWasteController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AzureWasteController(azureWasteService);
    }

    @Test
    @DisplayName("accountIds (the shared card's param) binds and returns the service's items")
    void accountIdsBinds() {
        List<AzureWasteItem> items = List.of(
                new AzureWasteItem("id", "disk", "Unattached Disk", "eastus", "idle", 12.5));
        when(azureWasteService.getWasteItems(SUB, false)).thenReturn(items);

        ResponseEntity<List<AzureWasteItem>> response = controller.getWasteItems(null, SUB, false);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(items);
        verify(azureWasteService).getWasteItems(SUB, false);
    }

    @Test
    @DisplayName("accountId (the standalone page's param) also binds")
    void accountIdBinds() {
        when(azureWasteService.getWasteItems(SUB, true)).thenReturn(List.of());

        ResponseEntity<List<AzureWasteItem>> response = controller.getWasteItems(SUB, null, true);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(azureWasteService).getWasteItems(SUB, true);
    }

    @Test
    @DisplayName("neither param present → 400, and the service is never called")
    void bothBlankIsBadRequest() {
        ResponseEntity<List<AzureWasteItem>> response = controller.getWasteItems("  ", "", false);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(azureWasteService);
    }

    @Test
    @DisplayName("a scan failure answers 503, never a measured empty list")
    void serviceExceptionIsServiceUnavailable() {
        when(azureWasteService.getWasteItems(anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("boom"));

        ResponseEntity<List<AzureWasteItem>> response = controller.getWasteItems(SUB, null, false);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
    }
}
