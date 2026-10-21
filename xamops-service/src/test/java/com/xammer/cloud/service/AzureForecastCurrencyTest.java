package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Azure forecast/currency contract for the unified dashboard.
 *
 * <p>{@code mapAzureDataToDashboardData} already set MTD from Azure's own USD figure
 * ({@code monthToDateSpendUsd}) while leaving forecast — and the unified cost history — in the
 * subscription's native currency, so one card showed USD and the next showed native. This proves
 * the forecast and history now share MTD's currency: a non-USD subscription has them scaled by
 * the same billed rate (USD MTD / native MTD), and a USD subscription is left untouched.
 *
 * <p>{@link DashboardDataService} is allocated without its saturated constructor (Objenesis) and
 * the private {@code mapAzureDataToDashboardData} is invoked by reflection. No collaborators are
 * needed: the savings/waste calls inside the mapper are wrapped in try/catch and degrade to zero
 * when their (uninjected) services are absent, which is exactly the no-op this test wants.
 */
class AzureForecastCurrencyTest {

    private DashboardData map(AzureDashboardData azureData, CloudAccount account) throws Exception {
        DashboardDataService service = new ObjenesisStd().newInstance(DashboardDataService.class);
        Method m = DashboardDataService.class.getDeclaredMethod("mapAzureDataToDashboardData",
                AzureDashboardData.class, CloudAccount.class);
        m.setAccessible(true);
        return (DashboardData) m.invoke(service, azureData, account);
    }

    private CloudAccount azureAccount() {
        CloudAccount acc = new CloudAccount();
        acc.setId(1L);
        acc.setProvider("Azure");
        acc.setAzureSubscriptionId("sub-123");
        acc.setAccountName("Azure Sub");
        return acc;
    }

    @Test
    @DisplayName("non-USD forecast is scaled to USD by the same billed rate as MTD")
    void nonUsdForecastIsUsdConsistent() throws Exception {
        AzureDashboardData az = new AzureDashboardData();
        az.setCurrency("INR");
        az.setMonthToDateSpend(1000.0);   // native
        az.setMonthToDateSpendUsd(10.0);  // Azure's own USD => effective rate 0.01
        az.setForecastedSpend(1200.0);    // native

        AzureDashboardData.CostHistory hist = new AzureDashboardData.CostHistory();
        hist.setLabels(List.of("Jan", "Feb"));
        hist.setCosts(List.of(500.0, 700.0));
        hist.setForecast(List.of(false, true));
        az.setCostHistory(hist);

        DashboardData.Account acc = map(az, azureAccount()).getSelectedAccount();

        double rate = 10.0 / 1000.0; // 0.01
        assertThat(acc.getMonthToDateSpend()).isEqualTo(10.0);
        // Forecast is no longer the native 1200.0 — it tracks the USD MTD rate.
        assertThat(acc.getForecastedSpend()).isCloseTo(1200.0 * rate, org.assertj.core.data.Offset.offset(1e-9));
        // Cost history values are USD-scaled; the per-point forecast flags pass through unchanged.
        assertThat(acc.getCostHistory().getCosts()).containsExactly(500.0 * rate, 700.0 * rate);
        assertThat(acc.getCostHistory().getAnomalies()).containsExactly(false, true);
    }

    @Test
    @DisplayName("USD-billed subscription keeps native forecast and history unchanged")
    void usdSubscriptionIsUnchanged() throws Exception {
        AzureDashboardData az = new AzureDashboardData();
        az.setCurrency("USD");
        az.setMonthToDateSpend(800.0);
        az.setMonthToDateSpendUsd(null); // USD-billed: no separate USD figure
        az.setForecastedSpend(950.0);

        AzureDashboardData.CostHistory hist = new AzureDashboardData.CostHistory();
        hist.setLabels(List.of("Jan"));
        hist.setCosts(List.of(400.0));
        hist.setForecast(List.of(false));
        az.setCostHistory(hist);

        DashboardData.Account acc = map(az, azureAccount()).getSelectedAccount();

        assertThat(acc.getMonthToDateSpend()).isEqualTo(800.0);
        assertThat(acc.getForecastedSpend()).isEqualTo(950.0);
        assertThat(acc.getCostHistory().getCosts()).containsExactly(400.0);
    }
}
