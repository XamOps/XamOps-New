package com.xammer.cloud.service.azure;

import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.dto.azure.AzureRightsizingRecommendation;
import com.xammer.cloud.dto.azure.AzureRightsizingResponse;
import com.xammer.cloud.dto.azure.AzureWasteItem;
import com.xammer.cloud.service.CurrencyConversionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Savings are priced in USD and shown beside INR spend, so they must be converted with Azure's own
 * rate for the subscription before display.
 */
class AzureBillingCurrencyServiceTest {

    private static final String SUB = "sub-1";

    private AzureBillingDataIngestionService ingestion;
    private AzureBillingCurrencyService service;

    @BeforeEach
    void setUp() {
        ingestion = mock(AzureBillingDataIngestionService.class);
        when(ingestion.readCurrency(anyString())).thenReturn(Optional.empty());
        when(ingestion.readCostHistory(anyString())).thenReturn(Optional.empty());
        when(ingestion.readCostUsdByMonth(anyString())).thenReturn(Optional.empty());
        service = new AzureBillingCurrencyService(ingestion, new CurrencyConversionService(83.0, "2026-01-01"));
    }

    private void inrSubscriptionWithHistory() {
        AzureDashboardData.CostHistory history = new AzureDashboardData.CostHistory();
        history.setLabels(List.of("Sep 2026", "Oct 2026", "Nov 2026", "Nov 2026 (Forecast)"));
        history.setCosts(List.of(48_741.13, 49_232.04, 36_655.14, 47_811.05));
        history.setForecast(List.of(false, false, false, true));
        when(ingestion.readCurrency(SUB)).thenReturn(Optional.of("INR"));
        when(ingestion.readCostHistory(SUB)).thenReturn(Optional.of(history));
        when(ingestion.readCostUsdByMonth(SUB)).thenReturn(Optional.of(
                Map.of("Sep 2026", 510.13, "Oct 2026", 512.89, "Nov 2026", 384.92)));
    }

    @Test
    @DisplayName("INR subscription: Azure's own rate from the latest actual month, forecast ignored")
    void azureRateFromLatestActualMonth() {
        inrSubscriptionWithHistory();

        AzureBillingCurrencyService.BillingCurrency billing = service.forSubscription(SUB);

        assertThat(billing.code()).isEqualTo("INR");
        assertThat(billing.unitsPerUsd()).isCloseTo(36_655.14 / 384.92, within(1e-9));
    }

    @Test
    @DisplayName("INR subscription without a USD series falls back to the configured rate")
    void configuredRateWhenNoUsdSeries() {
        when(ingestion.readCurrency(SUB)).thenReturn(Optional.of("inr"));

        AzureBillingCurrencyService.BillingCurrency billing = service.forSubscription(SUB);

        assertThat(billing.code()).isEqualTo("INR");
        assertThat(billing.unitsPerUsd()).isEqualTo(83.0);
    }

    @Test
    @DisplayName("Unknown or unconvertible currency keeps figures in USD and says so")
    void unknownCurrencyStaysUsd() {
        assertThat(service.forSubscription(SUB)).isEqualTo(AzureBillingCurrencyService.BillingCurrency.USD);

        when(ingestion.readCurrency(SUB)).thenReturn(Optional.of("EUR"));
        assertThat(service.forSubscription(SUB).code()).isEqualTo("USD");
    }

    @Test
    @DisplayName("A month under $1 is not trusted as a rate")
    void tinyMonthIgnored() {
        AzureDashboardData.CostHistory history = new AzureDashboardData.CostHistory();
        history.setLabels(List.of("Aug 2026", "Nov 2026"));
        history.setCosts(List.of(38_500.0, 0.26));
        history.setForecast(List.of(false, false));
        when(ingestion.readCurrency(SUB)).thenReturn(Optional.of("INR"));
        when(ingestion.readCostHistory(SUB)).thenReturn(Optional.of(history));
        when(ingestion.readCostUsdByMonth(SUB)).thenReturn(Optional.of(Map.of("Aug 2026", 403.90, "Nov 2026", 0.0027)));

        assertThat(service.forSubscription(SUB).unitsPerUsd()).isCloseTo(38_500.0 / 403.90, within(1e-9));
    }

    @Test
    @DisplayName("Waste copies are converted; the originals (the cache) stay in USD")
    void wasteItemsConvertedAsCopies() {
        inrSubscriptionWithHistory();
        AzureBillingCurrencyService.BillingCurrency billing = service.forSubscription(SUB);
        AzureWasteItem priced = new AzureWasteItem("id1", "vps", "Empty App Service Plan", "jioindiawest",
                "hosting no apps", 13.14);
        AzureWasteItem unpriced = AzureWasteItem.withUnknownSavings("id2", "vm", "Idle Running VM", "jioindiawest",
                "Full compute cost is about $16.35/month");

        List<AzureWasteItem> out = service.toBillingCurrency(List.of(priced, unpriced), billing);

        assertThat(out.get(0).getCurrency()).isEqualTo("INR");
        assertThat(out.get(0).getMonthlySavings()).isEqualTo(Math.round(13.14 * billing.unitsPerUsd() * 100.0) / 100.0);
        assertThat(out.get(1).isSavingsUnknown()).isTrue();
        assertThat(out.get(1).getReason()).contains("₹" + String.format(Locale.ROOT, "%,.0f", 16.35 * billing.unitsPerUsd()));
        assertThat(priced.getCurrency()).isEqualTo("USD");
        assertThat(priced.getMonthlySavings()).isEqualTo(13.14);
    }

    @Test
    @DisplayName("Rightsizing copies are converted and keep their status")
    void rightsizingConverted() {
        inrSubscriptionWithHistory();
        AzureBillingCurrencyService.BillingCurrency billing = service.forSubscription(SUB);
        AzureRightsizingRecommendation rec = new AzureRightsizingRecommendation();
        rec.setResourceName("vm1");
        rec.setMonthlySavings(10.0);
        rec.setAnnualSavings(120.0);
        rec.setCurrency("USD");

        AzureRightsizingResponse out = service.toBillingCurrency(
                new AzureRightsizingResponse(AzureRightsizingResponse.Status.OK, List.of(rec)), billing);

        assertThat(out.getStatus()).isEqualTo(AzureRightsizingResponse.Status.OK);
        assertThat(out.getRecommendations().get(0).getCurrency()).isEqualTo("INR");
        assertThat(out.getRecommendations().get(0).getMonthlySavings())
                .isEqualTo(Math.round(10.0 * billing.unitsPerUsd() * 100.0) / 100.0);
        assertThat(rec.getMonthlySavings()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("An Advisor INR amount round-trips exactly through USD at the same rate")
    void advisorRoundTrip() {
        inrSubscriptionWithHistory();
        AzureBillingCurrencyService.BillingCurrency billing = service.forSubscription(SUB);

        double usd = billing.toUsd(1_000.0);

        assertThat(billing.fromUsd(usd)).isCloseTo(1_000.0, within(1e-9));
    }
}
