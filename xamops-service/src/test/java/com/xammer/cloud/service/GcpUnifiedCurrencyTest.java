package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpDashboardData;
import com.xammer.cloud.service.gcp.GcpCostService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the unified dashboard's GCP currency contract.
 *
 * <p>The aggregator used to divide every GCP figure by a hardcoded FX constant
 * ({@code currency.usd-to-inr-rate}, default 83.0), which quietly shrank a USD-billed
 * project's spend to about 1/83 of its real value and converted a non-USD project at the
 * wrong rate. The fix resolves each project's billing currency and, for a non-USD project,
 * scales by GCP's own billed rate (the export's {@code currency_conversion_rate}). These tests
 * prove a USD project passes through untouched and a non-USD project uses the billed rate — not
 * the 83.0 constant.
 *
 * <p>{@link DashboardDataService} has a saturated constructor and reaches cloud SDKs, so it is
 * allocated without a constructor (Objenesis) and only its {@code gcpCostService} collaborator
 * is injected. The private {@code aggregateGcpDashboardData} is invoked by reflection — the same
 * entry point the unified GCP snapshot uses.
 */
class GcpUnifiedCurrencyTest {

    private DashboardData aggregate(DashboardDataService service, List<GcpDashboardData> data,
            List<CloudAccount> accounts) throws Exception {
        Method m = DashboardDataService.class.getDeclaredMethod("aggregateGcpDashboardData",
                List.class, List.class, List.class, List.class);
        m.setAccessible(true);
        List<String> names = accounts.stream().map(CloudAccount::getAccountName).toList();
        return (DashboardData) m.invoke(service, data, accounts, names, Collections.emptyList());
    }

    private DashboardDataService serviceWith(GcpCostService gcpCostService) throws Exception {
        DashboardDataService service = new ObjenesisStd().newInstance(DashboardDataService.class);
        Field f = DashboardDataService.class.getDeclaredField("gcpCostService");
        f.setAccessible(true);
        f.set(service, gcpCostService);
        return service;
    }

    private CloudAccount gcpAccount(String projectId, String name) {
        CloudAccount acc = new CloudAccount();
        acc.setId(1L);
        acc.setProvider("GCP");
        acc.setGcpProjectId(projectId);
        acc.setAccountName(name);
        return acc;
    }

    private GcpDashboardData gcpData(double mtd, double forecast, double lastMonth) {
        GcpDashboardData d = new GcpDashboardData();
        d.setMonthToDateSpend(mtd);
        d.setForecastedSpend(forecast);
        d.setLastMonthSpend(lastMonth);
        return d;
    }

    @Test
    @DisplayName("a USD-billed project's MTD passes through unchanged, not divided by ~83")
    void usdProjectIsUnchanged() throws Exception {
        GcpCostService gcp = Mockito.mock(GcpCostService.class);
        Mockito.when(gcp.getProjectCurrencyCode("usd-proj")).thenReturn("USD");

        DashboardData result = aggregate(
                serviceWith(gcp),
                List.of(gcpData(1000.0, 1200.0, 900.0)),
                List.of(gcpAccount("usd-proj", "USD Project")));

        DashboardData.Account acc = result.getSelectedAccount();
        assertThat(acc.getMonthToDateSpend()).isEqualTo(1000.0);
        assertThat(acc.getForecastedSpend()).isEqualTo(1200.0);
        assertThat(acc.getLastMonthSpend()).isEqualTo(900.0);
        // A USD project must never consult the USD MTD query, and must never be scaled.
        Mockito.verify(gcp, Mockito.never()).getUnfilteredMonthToDateSpendUsd(Mockito.anyString());
        Mockito.verify(gcp, Mockito.never()).getUnfilteredMonthToDateSpendUsd(Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    @DisplayName("a non-USD project is converted at GCP's billed rate, not the 83.0 constant")
    void nonUsdProjectUsesBilledRate() throws Exception {
        GcpCostService gcp = Mockito.mock(GcpCostService.class);
        Mockito.when(gcp.getProjectCurrencyCode("eur-proj")).thenReturn("EUR");
        // Native MTD 1000 EUR; GCP's own billed USD for the same window is 1100 (a >1 rate,
        // nothing like 1/83). Effective billed rate = 1100/1000 = 1.1.
        Mockito.when(gcp.getUnfilteredMonthToDateSpendUsd("eur-proj")).thenReturn(Optional.of(1100.0));

        DashboardData result = aggregate(
                serviceWith(gcp),
                List.of(gcpData(1000.0, 1200.0, 900.0)),
                List.of(gcpAccount("eur-proj", "EUR Project")));

        DashboardData.Account acc = result.getSelectedAccount();
        double billedRate = 1100.0 / 1000.0; // 1.1
        assertThat(acc.getMonthToDateSpend()).isEqualTo(1100.0);
        assertThat(acc.getForecastedSpend()).isCloseTo(1200.0 * billedRate, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(acc.getLastMonthSpend()).isCloseTo(900.0 * billedRate, org.assertj.core.data.Offset.offset(1e-9));

        // The old hardcoded path would have divided by ~83 (forecast ~14.5). The billed rate
        // gives 1320.0 — so the conversion clearly tracks GCP's rate, not the constant.
        assertThat(acc.getForecastedSpend()).isGreaterThan(1000.0);
    }

    @Test
    @DisplayName("a non-USD project with no billed USD keeps native figures (no 83.0 division)")
    void nonUsdProjectWithoutUsdFallsBackToNative() throws Exception {
        GcpCostService gcp = Mockito.mock(GcpCostService.class);
        Mockito.when(gcp.getProjectCurrencyCode("eur-proj")).thenReturn("EUR");
        Mockito.when(gcp.getUnfilteredMonthToDateSpendUsd("eur-proj")).thenReturn(Optional.empty());

        DashboardData result = aggregate(
                serviceWith(gcp),
                List.of(gcpData(500.0, 600.0, 450.0)),
                List.of(gcpAccount("eur-proj", "EUR Project")));

        DashboardData.Account acc = result.getSelectedAccount();
        // Native, unchanged — not 500/83.
        assertThat(acc.getMonthToDateSpend()).isEqualTo(500.0);
        assertThat(acc.getForecastedSpend()).isEqualTo(600.0);
        assertThat(acc.getLastMonthSpend()).isEqualTo(450.0);
    }
}
