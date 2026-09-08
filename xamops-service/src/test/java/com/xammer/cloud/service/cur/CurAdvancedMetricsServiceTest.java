package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurAdvancedMetricsServiceTest {

    @Mock
    private CurAthenaQueryService athena;

    private CurAdvancedMetricsService service;
    private CurSource source;
    private CloudAccount account;

    private final LocalDate start = LocalDate.of(2026, 8, 1);
    private final LocalDate end = LocalDate.of(2026, 8, 31);

    @BeforeEach
    void setUp() {
        service = new CurAdvancedMetricsService(athena);

        source = new CurSource();
        source.setId(1L);
        source.setAthenaDatabase("xamops_cur");
        source.setAthenaTable("xamops_cur");
        source.setCurVersion("cur");
        source.setFormat("Parquet");

        account = new CloudAccount();
        account.setAwsAccountId("123456789012");
    }

    @Test
    @DisplayName("Amortized cost zeroes the fee rows so commitments are not double counted")
    void amortizedExpressionZeroesFeeRows() {
        String expr = CurAdvancedMetricsService.amortizedCostExpr(source);

        // Covered usage is charged at the commitment's effective rate...
        assertTrue(expr.contains("'DiscountedUsage' THEN reservation_effective_cost"), expr);
        assertTrue(expr.contains("'SavingsPlanCoveredUsage' THEN savings_plan_savings_plan_effective_cost"), expr);
        // ...so the fee rows that paid for it must contribute nothing, or the same
        // commitment is counted twice.
        assertTrue(expr.contains("'RIFee','SavingsPlanRecurringFee',"
                + "'SavingsPlanUpfrontFee','SavingsPlanNegation') THEN 0"), expr);
    }

    @Test
    @DisplayName("Savings rate is amortized cost against public on-demand cost")
    void savingsRateUsesAmortizedNumerator() {
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(Map.of(
                "amortized", "700.0", "on_demand", "1000.0", "unblended", "650.0")));

        CurAdvancedMetricsService.SavingsRate rate =
                service.getEffectiveSavingsRate(source, account, start, end);

        assertEquals(0.30, rate.rate(), 0.0001);
        assertEquals(300.0, rate.savings(), 0.0001);
        assertEquals(700.0, rate.amortizedCost(), 0.0001);
    }

    @Test
    @DisplayName("Savings rate ignores tax and credits, which have no on-demand counterpart")
    void savingsRateRestrictsToUsageLines() {
        when(athena.query(any(), any(), contains("line_item_line_item_type IN ("
                + "'Usage','DiscountedUsage','SavingsPlanCoveredUsage','SavingsPlanNegation')")))
                .thenReturn(List.of());

        // Passing the matcher above is the assertion: a query without that restriction
        // would not match and the stub would return null.
        CurAdvancedMetricsService.SavingsRate rate =
                service.getEffectiveSavingsRate(source, account, start, end);
        assertEquals(0.0, rate.rate(), 0.0001);
    }

    @Test
    @DisplayName("Zero on-demand cost yields a zero rate rather than a division by zero")
    void savingsRateHandlesZeroDenominator() {
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(Map.of(
                "amortized", "50.0", "on_demand", "0", "unblended", "50.0")));

        assertEquals(0.0, service.getEffectiveSavingsRate(source, account, start, end).rate(), 0.0001);
    }

    @Test
    @DisplayName("Price and volume effects sum to the total cost change")
    void priceAndVolumeEffectsReconcile() {
        // Prior: 100 units at $1 = $100. Current: 150 units at $1.20 = $180.
        when(athena.query(any(), any(), anyString()))
                .thenReturn(List.of(Map.of("svc", "Amazon S3", "cost", "180.0", "qty", "150.0")))
                .thenReturn(List.of(Map.of("svc", "Amazon S3", "cost", "100.0", "qty", "100.0")));

        List<CurAdvancedMetricsService.PriceVolumeChange> changes = service.getPriceVsVolume(
                source, account, start, end, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        CurAdvancedMetricsService.PriceVolumeChange s3 = changes.get(0);
        assertEquals(80.0, s3.costChange(), 0.0001);
        // volume = (150-100) x 1.00 = 50; price = (1.20-1.00) x 150 = 30
        assertEquals(50.0, s3.volumeEffect(), 0.0001);
        assertEquals(30.0, s3.priceEffect(), 0.0001);
        assertEquals(s3.costChange(), s3.priceEffect() + s3.volumeEffect(), 0.0001);
    }

    @Test
    @DisplayName("A service that is new this period is pure volume, not a rate change")
    void newServiceIsAllVolume() {
        when(athena.query(any(), any(), anyString()))
                .thenReturn(List.of(Map.of("svc", "AWS Lambda", "cost", "40.0", "qty", "80.0")))
                .thenReturn(List.of());

        CurAdvancedMetricsService.PriceVolumeChange change = service.getPriceVsVolume(
                source, account, start, end,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)).get(0);

        // No prior rate exists, so attributing any of it to price would be invented —
        // the whole increase is new volume.
        assertEquals(0.0, change.priceEffect(), 0.0001);
        assertEquals(40.0, change.volumeEffect(), 0.0001);
        assertEquals(40.0, change.costChange(), 0.0001);
    }

    @Test
    @DisplayName("Container costs return empty when the report has no split cost data")
    void containerCostsSkippedWithoutSplitData() {
        source.setSplitCostDataEnabled(false);
        assertTrue(service.getContainerCosts(source, account, start, end).isEmpty());
    }

    @Test
    @DisplayName("Unused commitment sums both the recurring and amortized upfront waste")
    void unusedCommitmentSumsBothComponents() {
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(Map.of(
                "unused_recurring", "120.0", "unused_upfront", "80.0")));

        CurAdvancedMetricsService.UnusedCommitment unused =
                service.getUnusedCommitment(source, account, start, end);

        assertEquals(200.0, unused.total(), 0.0001);
    }

    @Test
    @DisplayName("A CSV report casts its numeric columns; Parquet does not need to")
    void csvSourcesCastNumerics() {
        source.setFormat("csv");
        assertTrue(CurAdvancedMetricsService.amortizedCostExpr(source)
                .contains("try_cast(reservation_effective_cost AS double)"));

        source.setFormat("Parquet");
        assertTrue(!CurAdvancedMetricsService.amortizedCostExpr(source).contains("try_cast"));
    }
}
