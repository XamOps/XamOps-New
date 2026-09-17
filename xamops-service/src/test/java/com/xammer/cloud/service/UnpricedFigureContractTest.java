package com.xammer.cloud.service;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.dto.azure.AzureFinOpsReportDto;
import com.xammer.cloud.dto.gcp.GcpBudgetDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one rule these DTOs exist to enforce: a figure that could not be measured must not be
 * indistinguishable from a measured zero.
 *
 * <p>Every case below was a real defect. A flat $50 stood in for every GCP performance finding's
 * saving; GCP budgets reported 0% utilisation whatever had been spent; Azure KPIs reported no spend
 * and flat month-over-month change whenever their cache was cold; AWS waste detectors received a
 * plausible us-east-1 constant when a Price List lookup failed. In each case the placeholder was a
 * value a real measurement could also take, so nothing downstream could filter it out.
 */
class UnpricedFigureContractTest {

    @Test
    @DisplayName("an unpriced wasted resource is excluded from savings totals")
    void unpricedWasteIsNotBillable() {
        var priced = new DashboardData.WastedResource("vol-1", "vol-1", "EBS Volume", "us-east-1",
                12.50, "Unattached");
        var unpriced = DashboardData.WastedResource.withUnknownSavings("vol-2", "vol-2", "EBS Volume",
                "us-east-1", "Unattached — price unavailable");
        var free = DashboardData.WastedResource.withNoCostImpact("cert-1", "example.com",
                "Certificate Manager", "us-east-1", "Expired");

        assertThat(priced.isBillableWaste()).isTrue();
        assertThat(unpriced.isBillableWaste()).isFalse();
        assertThat(free.isBillableWaste()).isFalse();

        // The sum the dashboard shows must count only the first.
        double total = List.of(priced, unpriced, free).stream()
                .filter(DashboardData.WastedResource::isBillableWaste)
                .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                .sum();
        assertThat(total).isEqualTo(12.50);
    }

    @Test
    @DisplayName("an unpriced waste finding is still reported, just without a number")
    void unpricedWasteIsStillSurfaced() {
        var unpriced = DashboardData.WastedResource.withUnknownSavings("vol-2", "vol-2", "EBS Volume",
                "us-east-1", "Unattached — price unavailable");

        // Excluding it from the total must not mean hiding the resource: it is still waste.
        assertThat(unpriced.getResourceId()).isEqualTo("vol-2");
        assertThat(unpriced.isSavingsUnknown()).isTrue();
        assertThat(unpriced.getMonthlySavings()).isZero();
    }

    @Test
    @DisplayName("a performance insight with an unpriced saving is flagged and zeroed")
    void unpricedInsightIsFlagged() {
        var insight = new PerformanceInsightDto("gce-1", "Underutilized", "desc",
                PerformanceInsightDto.InsightSeverity.WARNING,
                PerformanceInsightDto.InsightCategory.COST,
                "proj", 1, "Compute Engine", "1", "Downsize", "/docs", 50.0, "us-central1-a", "now");

        assertThat(insight.isSavingsUnknown()).isFalse();
        assertThat(insight.getPotentialSavings()).isEqualTo(50.0);

        insight.withUnknownSavings("no catalog rate for n2-standard-4 in us-central1");

        assertThat(insight.isSavingsUnknown()).isTrue();
        assertThat(insight.getSavingsUnavailableReason()).contains("n2-standard-4");
        // Zeroed deliberately: a stale figure behind a true flag invites a caller that forgets the
        // flag to keep reading it.
        assertThat(insight.getPotentialSavings()).isZero();
    }

    @Test
    @DisplayName("insight savings totals exclude unpriced findings")
    void insightTotalsExcludeUnpriced() {
        var priced = new PerformanceInsightDto("a", "i", "d",
                PerformanceInsightDto.InsightSeverity.WARNING,
                PerformanceInsightDto.InsightCategory.COST,
                "proj", 1, "Compute Engine", "1", "r", "/d", 31.40, "z", "now");
        var unpriced = new PerformanceInsightDto("b", "i", "d",
                PerformanceInsightDto.InsightSeverity.WARNING,
                PerformanceInsightDto.InsightCategory.COST,
                "proj", 1, "Compute Engine", "2", "r", "/d", 0.0, "z", "now")
                .withUnknownSavings("unpriced");

        double total = List.of(priced, unpriced).stream()
                .filter(i -> !i.isSavingsUnknown())
                .mapToDouble(PerformanceInsightDto::getPotentialSavings)
                .sum();

        assertThat(total).isEqualTo(31.40);
    }

    @Test
    @DisplayName("a GCP budget can express unknown spend rather than zero spend")
    void gcpBudgetSpendIsNullable() {
        var budget = new GcpBudgetDto("Monthly", 1000.0, null, null);
        budget.setSpendUnavailableReason("billing export returned no month-to-date total");

        // Null, not 0.0 — otherwise a budget already over its limit renders as untouched.
        assertThat(budget.getActualSpend()).isNull();
        assertThat(budget.getForecastedSpend()).isNull();
        assertThat(budget.getSpendUnavailableReason()).isNotBlank();

        budget.setActualSpend(742.10);
        budget.setForecastedSpend(980.0);
        budget.setSpendUnavailableReason(null);

        assertThat(budget.getActualSpend()).isEqualTo(742.10);
        assertThat(budget.getSpendUnavailableReason()).isNull();
    }

    @Test
    @DisplayName("Azure KPIs can express unknown rather than zero")
    void azureKpisAreNullable() {
        var kpis = new AzureFinOpsReportDto.Kpis();

        // A fresh KPI block knows nothing yet, and must not claim zero spend and flat costs.
        assertThat(kpis.getTotalSpend()).isNull();
        assertThat(kpis.getForecastedSpend()).isNull();
        assertThat(kpis.getMomChange()).isNull();
        assertThat(kpis.getYoyChange()).isNull();
    }

    @Test
    @DisplayName("Azure per-service month-over-month change is nullable")
    void azureBreakdownChangeIsNullable() {
        // A service with no prior closed month has no change, which is not the same as no movement.
        var noPriorMonth = new AzureFinOpsReportDto.CostBreakdown("Virtual Machines", 120.0, null);
        var doubled = new AzureFinOpsReportDto.CostBreakdown("Storage", 200.0, 100.0);

        assertThat(noPriorMonth.getChange()).isNull();
        assertThat(doubled.getChange()).isEqualTo(100.0);
    }
}
