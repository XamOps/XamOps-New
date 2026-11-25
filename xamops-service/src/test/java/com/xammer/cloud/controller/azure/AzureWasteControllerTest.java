package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.dto.azure.AzureWasteItem;
import com.xammer.cloud.service.CurrencyConversionService;
import com.xammer.cloud.service.azure.AzureBillingCurrencyService;
import com.xammer.cloud.service.azure.AzureBillingDataIngestionService;
import com.xammer.cloud.service.azure.AzureWasteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    @Mock private AzureBillingDataIngestionService ingestionService;

    private AzureWasteController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(ingestionService.readCurrency(anyString())).thenReturn(Optional.empty());
        AzureBillingCurrencyService billingCurrency = new AzureBillingCurrencyService(
                ingestionService, new CurrencyConversionService(83.0, "2026-01-01"));
        controller = new AzureWasteController(azureWasteService, billingCurrency);
    }

    @Test
    @DisplayName("accountIds (the shared card's param) binds and returns the service's items")
    void accountIdsBinds() {
        List<AzureWasteItem> items = List.of(
                new AzureWasteItem("id", "disk", "Unattached Disk", "eastus", "idle", 12.5));
        when(azureWasteService.getWasteItems(SUB, false)).thenReturn(items);

        ResponseEntity<List<AzureWasteItem>> response = controller.getWasteItems(null, SUB, false);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).usingRecursiveFieldByFieldElementComparator().isEqualTo(items);
        verify(azureWasteService).getWasteItems(SUB, false);
    }

    @Test
    @DisplayName("an INR subscription gets its USD savings in rupees, at Azure's own rate")
    void savingsConvertedToBillingCurrency() {
        AzureDashboardData.CostHistory history = new AzureDashboardData.CostHistory();
        history.setLabels(List.of("Oct 2026", "Nov 2026", "Nov 2026 (Forecast)"));
        history.setCosts(List.of(49_232.04, 36_655.14, 47_811.05));
        history.setForecast(List.of(false, false, true));
        when(ingestionService.readCurrency(SUB)).thenReturn(Optional.of("INR"));
        when(ingestionService.readCostHistory(SUB)).thenReturn(Optional.of(history));
        when(ingestionService.readCostUsdByMonth(SUB))
                .thenReturn(Optional.of(Map.of("Oct 2026", 512.89, "Nov 2026", 384.92)));
        AzureWasteItem item = new AzureWasteItem("id", "vm", "Idle Running VM", "eastus",
                "about $16.35/month", 10.0);
        when(azureWasteService.getWasteItems(SUB, false)).thenReturn(List.of(item));

        List<AzureWasteItem> body = controller.getWasteItems(SUB, null, false).getBody();

        double rate = 36_655.14 / 384.92;
        assertThat(body).hasSize(1);
        assertThat(body.get(0).getCurrency()).isEqualTo("INR");
        assertThat(body.get(0).getMonthlySavings()).isEqualTo(Math.round(10.0 * rate * 100.0) / 100.0);
        assertThat(body.get(0).getReason())
                .isEqualTo("about ₹" + String.format(java.util.Locale.ROOT, "%,.0f", 16.35 * rate) + "/month");
        // The cached item the service returned is left in USD.
        assertThat(item.getCurrency()).isEqualTo("USD");
        assertThat(item.getMonthlySavings()).isEqualTo(10.0);
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
