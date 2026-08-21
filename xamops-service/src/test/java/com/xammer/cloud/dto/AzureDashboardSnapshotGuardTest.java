package com.xammer.cloud.dto;

import com.xammer.cloud.dto.azure.AzureDashboardData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the completeness semantics that stop a failed refresh from erasing the Azure dashboard.
 *
 * <p>Cost figures come from a Redis cache filled by a separate billing-ingestion job. When that
 * cache was cold the dashboard builder set month-to-date and forecast spend to zero and carried
 * on, and the result was written over the database snapshot — which has no TTL. Pressing
 * Refresh therefore turned a dashboard showing ₹37,544 of spend into an all-zero one,
 * permanently.
 */
class AzureDashboardSnapshotGuardTest {

    private static AzureDashboardData populated() {
        AzureDashboardData data = new AzureDashboardData();
        data.setMonthToDateSpend(37544.31);
        data.setForecastedSpend(58193.68);
        AzureDashboardData.CostHistory history = new AzureDashboardData.CostHistory();
        history.setLabels(List.of("Jul 2026", "Aug 2026"));
        history.setCosts(List.of(1500.0, 37544.31));
        data.setCostHistory(history);
        return data;
    }

    private static AzureDashboardData zeroed() {
        AzureDashboardData data = new AzureDashboardData();
        data.setMonthToDateSpend(0.0);
        data.setForecastedSpend(0.0);
        data.setLastMonthSpend(0.0);
        AzureDashboardData.CostHistory history = new AzureDashboardData.CostHistory();
        history.setLabels(List.of());
        history.setCosts(List.of());
        data.setCostHistory(history);
        data.setBillingSummary(List.of());
        return data;
    }

    @Test
    @DisplayName("a fresh payload is complete until something reports otherwise")
    void completeByDefault() {
        assertThat(populated().isDataComplete()).isTrue();
        assertThat(populated().getDataWarnings()).isEmpty();
    }

    @Test
    @DisplayName("recording a warning marks the payload incomplete and keeps the reason")
    void warningMarksIncomplete() {
        AzureDashboardData data = zeroed();
        data.addDataWarning("Cost history unavailable: the billing ingestion cache is empty.");

        assertThat(data.isDataComplete()).isFalse();
        assertThat(data.getDataWarnings()).hasSize(1);
        assertThat(data.getDataWarnings().get(0)).contains("billing ingestion cache");
    }

    @Test
    @DisplayName("multiple warnings accumulate")
    void warningsAccumulate() {
        AzureDashboardData data = zeroed();
        data.addDataWarning("Cost history unavailable");
        data.addDataWarning("Resource inventory unavailable");

        assertThat(data.isDataComplete()).isFalse();
        assertThat(data.getDataWarnings()).hasSize(2);
    }

    @Test
    @DisplayName("a payload with no spend, no history and no billing lines is materially empty")
    void zeroedPayloadIsMateriallyEmpty() {
        assertThat(zeroed().isMateriallyEmpty()).isTrue();
    }

    @Test
    @DisplayName("a payload carrying spend is not materially empty")
    void populatedPayloadIsNotEmpty() {
        assertThat(populated().isMateriallyEmpty()).isFalse();
    }

    @Test
    @DisplayName("spend alone is enough to make a payload worth keeping")
    void spendAloneCounts() {
        AzureDashboardData data = zeroed();
        data.setMonthToDateSpend(37544.31);

        assertThat(data.isMateriallyEmpty()).isFalse();
    }

    @Test
    @DisplayName("cost history alone is enough to make a payload worth keeping")
    void historyAloneCounts() {
        AzureDashboardData data = zeroed();
        AzureDashboardData.CostHistory history = new AzureDashboardData.CostHistory();
        history.setCosts(List.of(1500.0));
        data.setCostHistory(history);

        assertThat(data.isMateriallyEmpty()).isFalse();
    }

    @Test
    @DisplayName("the overwrite guard fires only for an incomplete AND empty payload")
    void overwriteGuardCondition() {
        // This is the exact predicate AzureDashboardService.saveDashboardToDatabase applies.
        AzureDashboardData failedRefresh = zeroed();
        failedRefresh.addDataWarning("Cost history unavailable");
        assertThat(failedRefresh.isDataComplete()).isFalse();
        assertThat(failedRefresh.isMateriallyEmpty()).isTrue();

        // A genuinely empty subscription reports complete, so its zeros are a real answer and
        // must still be persisted.
        AzureDashboardData genuinelyEmpty = zeroed();
        assertThat(genuinelyEmpty.isDataComplete()).isTrue();

        // A partial failure that still carries spend is worth saving.
        AzureDashboardData partialButUseful = populated();
        partialButUseful.addDataWarning("Resource inventory unavailable");
        assertThat(partialButUseful.isDataComplete()).isFalse();
        assertThat(partialButUseful.isMateriallyEmpty()).isFalse();
    }

    @Test
    @DisplayName("the derived emptiness flag is not serialised into API payloads")
    void derivedFlagNotSerialised() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(populated());

        assertThat(json).doesNotContain("materiallyEmpty");
        assertThat(json).contains("dataComplete");
    }
}
