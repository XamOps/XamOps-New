package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurAdvancedMetricsServiceTest {

    @Mock
    private CurAthenaQueryService athena;

    @Mock
    private CurTagColumns tagColumns;

    @Mock
    private CurColumnCatalog columnCatalog;

    private CurAdvancedMetricsService service;
    private CurSource source;
    private CloudAccount account;

    private final LocalDate start = LocalDate.of(2026, 8, 1);
    private final LocalDate end = LocalDate.of(2026, 8, 31);

    @BeforeEach
    void setUp() {
        service = new CurAdvancedMetricsService(athena, tagColumns, columnCatalog);

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
        everyColumnPresent();
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
        everyColumnPresent();
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
        everyColumnPresent();
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(Map.of(
                "amortized", "50.0", "on_demand", "0", "unblended", "50.0")));

        assertEquals(0.0, service.getEffectiveSavingsRate(source, account, start, end).rate(), 0.0001);
    }

    @Test
    @DisplayName("Savings rate refuses a trimmed export rather than 502-ing on a missing column")
    void savingsRateRefusesWithoutTheCommitmentColumn() {
        // Regression: this method reached amortizedCostExpr directly, so a report without the
        // column failed in Athena's analysis phase — a 502 — while /capabilities was telling the
        // same client the amortized basis was unavailable.
        presentExcept("reservation_effective_cost");

        ReportCannotAnswerException thrown = assertThrows(ReportCannotAnswerException.class,
                () -> service.getEffectiveSavingsRate(source, account, start, end));

        assertEquals("the amortized cost basis", thrown.question());
        assertEquals(List.of("reservation_effective_cost"), thrown.missingColumns());
        verifyNoInteractions(athena);
    }

    @Test
    @DisplayName("Savings rate refuses without the list-price column rather than reporting 0%")
    void savingsRateRefusesWithoutTheOnDemandColumn() {
        // Substituting 0 would zero the denominator and the existing branch would return exactly
        // 0% — "you are saving nothing", which is a measurement this report cannot make.
        presentExcept("pricing_public_on_demand_cost");

        ReportCannotAnswerException thrown = assertThrows(ReportCannotAnswerException.class,
                () -> service.getEffectiveSavingsRate(source, account, start, end));

        assertEquals("the effective savings rate", thrown.question());
        assertEquals(List.of("pricing_public_on_demand_cost"), thrown.missingColumns());
        verifyNoInteractions(athena);
    }

    // ── Cost basis and capabilities ─────────────────────────────────────────────

    @Test
    @DisplayName("The amortized basis uses the CASE expression when both commitment columns exist")
    void amortizedBasisUsesTheCaseExpression() {
        everyColumnPresent();
        when(athena.query(any(), any(), contains("'DiscountedUsage' THEN reservation_effective_cost")))
                .thenReturn(List.of());

        // Matching the stub is the assertion: an unblended SUM would not match it.
        assertTrue(service.getChargesByService(source, account, start, end, CostBasis.AMORTIZED)
                .isEmpty());
    }

    @Test
    @DisplayName("The amortized basis refuses when reservation_effective_cost is absent")
    void amortizedBasisRefusesWithoutReservationEffectiveCost() {
        presentExcept("reservation_effective_cost");

        ReportCannotAnswerException thrown = assertThrows(ReportCannotAnswerException.class,
                () -> service.getChargesByService(source, account, start, end, CostBasis.AMORTIZED));

        // Never a silent unblended downgrade: the basis is on screen beside the figure.
        assertEquals("the amortized cost basis", thrown.question());
        assertEquals(List.of("reservation_effective_cost"), thrown.missingColumns());
        verifyNoInteractions(athena);
    }

    @Test
    @DisplayName("The amortized basis refuses when the Savings Plan column is absent")
    void amortizedBasisRefusesWithoutTheSavingsPlanColumn() {
        presentExcept("savings_plan_savings_plan_effective_cost");

        ReportCannotAnswerException thrown = assertThrows(ReportCannotAnswerException.class,
                () -> service.getChargeTypeBreakdown(source, account, start, end, CostBasis.AMORTIZED));

        assertEquals(List.of("savings_plan_savings_plan_effective_cost"), thrown.missingColumns());
        verifyNoInteractions(athena);
    }

    @Test
    @DisplayName("Capabilities name the absent columns, and say nothing about the ones that are there")
    void capabilitiesNameTheMissingColumns() {
        when(columnCatalog.readable(any(), any())).thenReturn(true);
        when(columnCatalog.has(any(), any(), anyString())).thenAnswer(invocation ->
                "pricing_unit".equals(invocation.getArgument(2, String.class)));

        CurAdvancedMetricsService.ReportCapabilities capabilities =
                service.getReportCapabilities(source, account);

        assertTrue(!capabilities.amortizedAvailable());
        assertEquals(List.of("reservation_effective_cost", "savings_plan_savings_plan_effective_cost"),
                capabilities.amortizedMissingColumns());
        assertTrue(capabilities.amortizedDetail().contains("reservation_effective_cost"), 
                capabilities.amortizedDetail());
        assertTrue(capabilities.amortizedDetail().contains("savings_plan_savings_plan_effective_cost"),
                capabilities.amortizedDetail());
        // One sentence per limitation: the unit is available, so its sentence is empty rather than
        // carrying an explanation the reader has no use for.
        assertTrue(capabilities.unitAvailable());
        assertEquals("", capabilities.unitDetail());
    }

    @Test
    @DisplayName("An unreadable catalog says so, rather than telling the reader to recreate an export")
    void capabilitiesDistinguishUnreadableFromAbsent() {
        // columns(...) caches the empty set for ten minutes on a failed probe, so without this
        // branch "the column is absent" and "we could not look" are the same answer — and their
        // remedies are "recreate the export" and "try again in ten minutes".
        when(columnCatalog.readable(any(), any())).thenReturn(false);

        CurAdvancedMetricsService.ReportCapabilities capabilities =
                service.getReportCapabilities(source, account);

        assertTrue(!capabilities.amortizedAvailable());
        assertTrue(!capabilities.unitAvailable());
        assertTrue(capabilities.amortizedDetail().contains("could not be read"),
                capabilities.amortizedDetail());
        assertTrue(capabilities.unitDetail().contains("could not be read"), capabilities.unitDetail());
        // Textually distinct from the absent-column wording, which names a column instead.
        assertTrue(!capabilities.amortizedDetail().contains("does not carry"),
                capabilities.amortizedDetail());
        // Nothing is known about either column, so both are reported unknown rather than one.
        assertEquals(2, capabilities.amortizedMissingColumns().size());
        verifyNoInteractions(athena);
    }

    @Test
    @DisplayName("Capabilities are answered from the catalog alone, scanning no data")
    void capabilitiesAskTheCatalogAndNotAthena() {
        when(columnCatalog.readable(any(), any())).thenReturn(true);
        when(columnCatalog.has(any(), any(), anyString())).thenReturn(true);

        service.getReportCapabilities(source, account);

        // The catalog is itself a mock here, so verifyNoInteractions(athena) alone would be
        // vacuous — nothing in this test could reach Athena whatever the method did. These two
        // verifications are what pin that the answer comes from the column list. The genuine
        // zero-bytes-scanned property belongs to CurColumnCatalog, which reads information_schema
        // and has its own caching.
        verify(columnCatalog).has(source, account, "reservation_effective_cost");
        verify(columnCatalog).has(source, account, "savings_plan_savings_plan_effective_cost");
        verify(columnCatalog).has(source, account, "pricing_unit");
        verifyNoInteractions(athena);
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
        everyColumnPresent();
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(Map.of(
                "unused_recurring", "120.0", "unused_upfront", "80.0")));

        CurAdvancedMetricsService.UnusedCommitment unused =
                service.getUnusedCommitment(source, account, start, end);

        assertEquals(200.0, unused.total(), 0.0001);
        assertTrue(unused.available());
    }

    @ParameterizedTest(name = "without {0}")
    @ValueSource(strings = {"reservation_unused_recurring_fee",
            "reservation_unused_amortized_upfront_fee_for_billing_period"})
    @DisplayName("Unused commitment reports it cannot say, rather than 502-ing on a trimmed export")
    void unusedCommitmentDegradesWithoutEitherColumn(String absent) {
        // Regression: both columns were named outright, so a Data Export without either 502'd the
        // endpoint and the FinOps card lost itself to a .catch(). Both are required rather than
        // either, because the card's headline is their sum and a sum missing one term understates
        // waste while looking like a measurement.
        presentExcept(absent);

        CurAdvancedMetricsService.UnusedCommitment unused =
                service.getUnusedCommitment(source, account, start, end);

        assertTrue(!unused.available());
        assertEquals(0.0, unused.total(), 0.0001);
        // No query at all: a zero here would read as "you wasted nothing".
        verifyNoInteractions(athena);
    }

    @Test
    @DisplayName("Top resources carries the Name tag when the report has it")
    void topResourcesReadsTheNameTag() {
        when(tagColumns.resourceNameExpr(any(), any()))
                .thenReturn(Optional.of("resource_tags_user_name"));
        when(athena.query(any(), any(), contains("MAX(resource_tags_user_name) AS resource_name")))
                .thenReturn(List.of(Map.of(
                        "resource_id", "i-089ccf1b5a839880a",
                        "resource_name", "prod-api-01",
                        "service", "Amazon Elastic Compute Cloud",
                        "region", "ap-south-1",
                        "instance_type", "m7i.xlarge",
                        "cost", "32.03",
                        "total_cost", "2367.27",
                        "group_count", "431")));

        CurAdvancedMetricsService.TopResources top =
                service.getTopResources(source, account, start, end);

        assertEquals("prod-api-01", top.resources().get(0).name());
        assertEquals(431L, top.groupCount());
    }

    @Test
    @DisplayName("A report without the tag activated still returns resources, just unnamed")
    void topResourcesOmitsTheNameColumnWhenAbsent() {
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        // Selecting a column the table does not have fails the whole query in Athena, so
        // the absent case must leave it out of the SQL entirely rather than select null.
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            assertTrue(!invocation.getArgument(2, String.class).contains("resource_name"),
                    "no name column should be referenced when the report has none");
            return List.of(Map.of(
                    "resource_id", "i-089ccf1b5a839880a",
                    "service", "Amazon Elastic Compute Cloud",
                    "region", "ap-south-1",
                    "cost", "32.03",
                    "total_cost", "2367.27",
                    "group_count", "431"));
        });

        CurAdvancedMetricsService.TopResources top =
                service.getTopResources(source, account, start, end);

        assertEquals("", top.resources().get(0).name());
        assertEquals("i-089ccf1b5a839880a", top.resources().get(0).resourceId());
    }

    // ── Charges by service ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Charges by service substitutes a constant when the report has no pricing_unit")
    void chargesByServiceDegradesWithoutTheUnitColumn() {
        // Regression for a latent 502: the column was selected and grouped by outright, so a Data
        // Export without it took down this entire card rather than leaving one column blank.
        presentExcept("pricing_unit");
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2, String.class);
            assertTrue(sql.contains("'' AS unit"), sql);
            assertTrue(!sql.contains("pricing_unit"),
                    "no pricing_unit reference should survive when the report has none:\n" + sql);
            return List.of(Map.of("service", "Amazon Simple Storage Service", "region", "ap-south-1",
                    "description", "storage", "usage_type", "APS3-TimedStorage-ByteHrs",
                    "quantity", "47210", "cost", "1180.25"));
        });

        List<CurAdvancedMetricsService.ServiceCharge> charges =
                service.getChargesByService(source, account, start, end, CostBasis.UNBLENDED);

        // The row reader keeps its shape either way, so the unit is simply blank.
        assertEquals("", charges.get(0).unit());
        assertEquals(1180.25, charges.get(0).cost(), 0.0001);
    }

    @Test
    @DisplayName("Charges by service filters on the absolute total, so a credit row survives")
    void chargesByServiceKeepsCredits() {
        everyColumnPresent();
        when(athena.query(any(), any(), contains("HAVING ABS(SUM("))).thenReturn(List.of(
                Map.of("service", "Refund", "region", "", "description", "Service credit",
                        "usage_type", "Credit", "unit", "", "quantity", "1", "cost", "-250.00")));

        List<CurAdvancedMetricsService.ServiceCharge> charges =
                service.getChargesByService(source, account, start, end, CostBasis.UNBLENDED);

        // HAVING SUM(cost) > 0.001 would have dropped this row and the card would no longer
        // reconcile to the invoice.
        assertEquals(-250.00, charges.get(0).cost(), 0.0001);
    }

    @Test
    @DisplayName("On the amortized basis the HAVING is built over the amortized expression too")
    void chargesByServiceFiltersOnTheSameExpressionItSelects() {
        everyColumnPresent();
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2, String.class);
            int having = sql.indexOf("HAVING ABS(SUM(");
            assertTrue(having > 0, sql);
            // Filtering unblended cost while selecting amortized would drop the wrong rows — and
            // would keep the four fee groups that must net to zero on this basis.
            assertTrue(sql.substring(having).contains("'DiscountedUsage' THEN reservation_effective_cost"),
                    "the HAVING must re-use the amortized expression:\n" + sql);
            return List.of();
        });

        service.getChargesByService(source, account, start, end, CostBasis.AMORTIZED);
    }

    @Test
    @DisplayName("A row with no service or region keeps this section's own words for them")
    void chargesByServiceLabelsBlanks() {
        everyColumnPresent();
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(
                Map.of("service", "", "region", "", "description", "", "usage_type", "Tax",
                        "unit", "", "quantity", "1", "cost", "620.00")));

        CurAdvancedMetricsService.ServiceCharge charge =
                service.getChargesByService(source, account, start, end, CostBasis.UNBLENDED).get(0);

        assertEquals("Other", charge.service());
        assertEquals("Global", charge.region());
        // Description is blank on tax rows, so the usage type is the next most specific thing.
        assertEquals("Tax", charge.description());
    }

    // ── Charge type breakdown ───────────────────────────────────────────────────

    @Test
    @DisplayName("Charge types keep negative rows and name an unlabelled type Unknown")
    void chargeTypesKeepCreditsAndLabelBlanks() {
        // No catalog stub: the unblended basis names only mandatory columns, so this path never
        // asks the catalog anything. MockitoExtension is strict, and that is the point.
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(
                Map.of("charge_type", "Usage", "cost", "15980.75"),
                Map.of("charge_type", "Credit", "cost", "-250.00"),
                Map.of("charge_type", "", "cost", "12.00")));

        List<CurAdvancedMetricsService.ChargeTypeAmount> types =
                service.getChargeTypeBreakdown(source, account, start, end, CostBasis.UNBLENDED);

        assertEquals(3, types.size());
        assertEquals(-250.00, types.get(1).cost(), 0.0001);
        assertEquals("Unknown", types.get(2).chargeType());
    }

    @Test
    @DisplayName("On the amortized basis the zeroed fee types are omitted, not returned at 0.00")
    void chargeTypesOmitTheZeroedFeesOnTheAmortizedBasis() {
        everyColumnPresent();
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(
                Map.of("charge_type", "Usage", "cost", "15980.75"),
                Map.of("charge_type", "RIFee", "cost", "0"),
                Map.of("charge_type", "SavingsPlanRecurringFee", "cost", "0"),
                Map.of("charge_type", "SavingsPlanUpfrontFee", "cost", "0"),
                Map.of("charge_type", "SavingsPlanNegation", "cost", "0"),
                Map.of("charge_type", "Tax", "cost", "620.00")));

        List<CurAdvancedMetricsService.ChargeTypeAmount> types =
                service.getChargeTypeBreakdown(source, account, start, end, CostBasis.AMORTIZED);

        // A $0.00 row is a zero whose truth is "not applicable on this basis", which is the one
        // thing the house style forbids outright.
        assertEquals(List.of("Usage", "Tax"),
                types.stream().map(CurAdvancedMetricsService.ChargeTypeAmount::chargeType).toList());
    }

    @Test
    @DisplayName("The zeroed-type constant matches the expression it is derived from")
    void amortizedZeroedTypesMatchTheExpression() {
        // The constant exists so the ledger can say which types it is not listing. It is derived
        // from amortizedCostExpr's CASE, and this is what stops the two drifting apart — the
        // expression is the thing that can change.
        String expr = CurAdvancedMetricsService.amortizedCostExpr(source);
        assertEquals(List.of("RIFee", "SavingsPlanRecurringFee", "SavingsPlanUpfrontFee",
                "SavingsPlanNegation"), CurAdvancedMetricsService.AMORTIZED_ZEROED_TYPES);
        for (String type : CurAdvancedMetricsService.AMORTIZED_ZEROED_TYPES) {
            assertTrue(expr.contains("'" + type + "'"), type + " is not zeroed by:\n" + expr);
        }
    }

    // ── One service's resources ─────────────────────────────────────────────────

    @Test
    @DisplayName("A service name containing a quote is escaped rather than closing the string")
    void serviceResourcesEscapesTheServiceName() {
        everyColumnPresent();
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        when(athena.query(any(), any(), contains("product_product_name = 'Acme''s Service'")))
                .thenReturn(List.of());

        // The service name arrives from a query parameter; one that terminated the string early
        // would be an injection into the tenant's own billing data.
        service.getServiceResources(source, account, "Acme's Service", start, end, CostBasis.UNBLENDED);
    }

    @Test
    @DisplayName("service=Other matches the blank the display word stands for, and the literal")
    void serviceResourcesInvertsTheOtherDisplayWord() {
        everyColumnPresent();
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2, String.class);
            // "Other" is this class's own word for a row the report gave no product name. Matching
            // it literally finds nothing, so every unnamed-service row would open an empty panel.
            assertTrue(sql.contains("(product_product_name IS NULL OR product_product_name = ''"
                    + " OR product_product_name = 'Other')"), sql);
            return List.of();
        });

        service.getServiceResources(source, account, "Other", start, end, CostBasis.UNBLENDED);
    }

    @Test
    @DisplayName("The drill-down inverts it the same way, because it delegates to one predicate")
    void resourceChangeInvertsTheOtherDisplayWordToo() {
        everyColumnPresent();
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            assertTrue(invocation.getArgument(2, String.class)
                    .contains("(product_product_name IS NULL OR product_product_name = ''"
                            + " OR product_product_name = 'Other')"),
                    invocation.getArgument(2, String.class));
            return List.of();
        });

        // One assertion site, both paths: scopePredicate now calls servicePredicate, so the
        // scoped resource query and the drill-down cannot disagree about what "Other" means.
        service.getResourceChange(source, account, "i-0abc", "Other", "ap-south-1",
                start, end, start.minusMonths(1), end.minusMonths(1), false);
    }

    @Test
    @DisplayName("Both window functions sit inside the subquery, with the cap outside it")
    void serviceResourcesComputesTotalsBeforeTheLimit() {
        everyColumnPresent();
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2, String.class);
            assertTrue(sql.contains(") OVER () AS total_cost"), sql);
            assertTrue(sql.contains("COUNT(*) OVER () AS group_count"), sql);
            // Unpartitioned and before the LIMIT is the only ordering that works: summing the
            // returned rows would understate the service total by the truncated tail.
            int closingParen = sql.lastIndexOf(") ORDER BY cost DESC LIMIT ");
            assertTrue(closingParen > sql.indexOf("OVER () AS total_cost"), sql);
            // Built from the constant, so changing the cap does not need this test edited.
            assertTrue(sql.endsWith(") ORDER BY cost DESC LIMIT 1000"), sql);
            return List.of();
        });

        service.getServiceResources(source, account, "AWS Lambda", start, end, CostBasis.UNBLENDED);
    }

    @Test
    @DisplayName("The service total comes from the window columns, not from summing the page")
    void serviceResourcesReadsTotalsFromTheWindows() {
        everyColumnPresent();
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(
                Map.of("resource_id", "i-0abc", "service", "AWS Lambda", "region", "ap-south-1",
                        "instance_type", "", "cost", "10.0",
                        "total_cost", "500.0", "group_count", "300"),
                Map.of("resource_id", "i-0def", "service", "AWS Lambda", "region", "ap-south-1",
                        "instance_type", "", "cost", "20.0",
                        "total_cost", "500.0", "group_count", "300")));

        CurAdvancedMetricsService.TopResources result =
                service.getServiceResources(source, account, "AWS Lambda", start, end,
                        CostBasis.UNBLENDED);

        // 500 and 300, not 30 and 2 — which is what lets the panel say what it is not showing.
        assertEquals(500.0, result.totalCost(), 0.0001);
        assertEquals(300L, result.groupCount());
        assertEquals(2, result.resources().size());
    }

    @Test
    @DisplayName("A charge belonging to no resource survives with an empty id")
    void serviceResourcesKeepsResourcelessCharges() {
        everyColumnPresent();
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(
                Map.of("resource_id", "", "service", "AWS Data Transfer", "region", "ap-south-1",
                        "instance_type", "", "cost", "450.0",
                        "total_cost", "470.0", "group_count", "2")));

        CurAdvancedMetricsService.TopResources result =
                service.getServiceResources(source, account, "AWS Data Transfer", start, end,
                        CostBasis.UNBLENDED);

        // They are the service's tax, support and subscription charges, and the panel lists them
        // in their own block precisely so its total can be reconciled against the header.
        assertEquals("", result.resources().get(0).resourceId());
        assertEquals(450.0, result.resources().get(0).cost(), 0.0001);
    }

    @Test
    @DisplayName("Without product_instance_type the column is a constant and every chip is empty")
    void serviceResourcesDegradesWithoutTheInstanceTypeColumn() {
        presentExcept("product_instance_type");
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2, String.class);
            assertTrue(sql.contains("'' AS instance_type"), sql);
            assertTrue(!sql.contains("MAX(product_instance_type)"), sql);
            return List.of(Map.of("resource_id", "i-0abc", "service", "Amazon Elastic Compute Cloud",
                    "region", "ap-south-1", "cost", "32.03",
                    "total_cost", "32.03", "group_count", "1"));
        });

        CurAdvancedMetricsService.TopResources result =
                service.getServiceResources(source, account, "Amazon Elastic Compute Cloud",
                        start, end, CostBasis.UNBLENDED);

        assertEquals("", result.resources().get(0).instanceType());
    }

    @Test
    @DisplayName("Without a Name-tag column no name column is built and rows come back unnamed")
    void serviceResourcesOmitsTheNameColumnWhenAbsent() {
        everyColumnPresent();
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            // Selecting a column the table does not have fails the whole query during analysis, so
            // the absent case must leave it out of the SQL rather than select null.
            assertTrue(!invocation.getArgument(2, String.class).contains("resource_name"),
                    "no name column should be referenced when the report has none");
            return List.of(Map.of("resource_id", "arn:aws:s3:::xammer-prod-logs",
                    "service", "Amazon Simple Storage Service", "region", "ap-south-1",
                    "instance_type", "", "cost", "540.0",
                    "total_cost", "1920.25", "group_count", "3"));
        });

        CurAdvancedMetricsService.TopResources result =
                service.getServiceResources(source, account, "Amazon Simple Storage Service",
                        start, end, CostBasis.UNBLENDED);

        assertEquals("", result.resources().get(0).name());
    }

    @Test
    @DisplayName("The amortized expression is threaded into the scoped SUM and its window")
    void serviceResourcesThreadsTheAmortizedExpression() {
        everyColumnPresent();
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        when(athena.query(any(), any(), contains(
                "SUM(SUM(CASE WHEN line_item_line_item_type = 'DiscountedUsage'")))
                .thenReturn(List.of());

        service.getServiceResources(source, account, "Amazon Relational Database Service",
                start, end, CostBasis.AMORTIZED);
    }

    // ── Data transfer ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Data transfer names both ends of the route when the report carries them")
    void dataTransferReadsTheRouteColumns() {
        when(columnCatalog.present(any(), any(), contains("from_location"),
                contains("from_region_code"))).thenReturn(List.of("product_from_location"));
        when(columnCatalog.present(any(), any(), contains("to_location"),
                contains("to_region_code"))).thenReturn(List.of("product_to_location"));
        when(columnCatalog.has(any(), any(), anyString())).thenReturn(true);
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());

        when(athena.query(any(), any(), contains("AS usage_type")))
                .thenReturn(List.of(Map.of(
                        "category", "Inter-region",
                        "service", "AWS Data Transfer",
                        "usage_type", "APS3-USE1-AWS-Out-Bytes",
                        "unit", "GB",
                        "region", "ap-south-1",
                        "from_location", "Asia Pacific (Mumbai)",
                        "to_location", "US East (N. Virginia)",
                        "quantity", "29.0",
                        "cost", "2.44")));
        when(athena.query(any(), any(), contains("rank_in_category")))
                .thenReturn(List.of(Map.ofEntries(
                        Map.entry("category", "Inter-region"),
                        Map.entry("resource_id", "arn:aws:s3:::prod-replica"),
                        Map.entry("service", "Amazon Simple Storage Service"),
                        Map.entry("region", "ap-south-1"),
                        Map.entry("availability_zone", ""),
                        Map.entry("from_location", "Asia Pacific (Mumbai)"),
                        Map.entry("to_location", "US East (N. Virginia)"),
                        Map.entry("usage_type", "APS3-USE1-AWS-Out-Bytes"),
                        Map.entry("gigabytes", "29.0"),
                        Map.entry("hours", "0.0"),
                        Map.entry("cost", "2.44"),
                        Map.entry("category_cost", "2.60"),
                        Map.entry("category_flows", "3"))));

        CurAdvancedMetricsService.DataTransferBreakdown breakdown =
                service.getDataTransferCosts(source, account, start, end);

        assertTrue(breakdown.routeDataAvailable());
        assertTrue(breakdown.resourceDataAvailable());
        assertEquals("Asia Pacific (Mumbai)", breakdown.charges().get(0).fromLocation());
        assertEquals("US East (N. Virginia)", breakdown.charges().get(0).toLocation());
        // Reported by AWS, not worked out from the usage type — the flag is what lets the UI
        // avoid marking a first-hand answer as an inference.
        assertTrue(!breakdown.charges().get(0).routeDerived());

        // One row carrying resource, route and cost together is the point of the grain.
        CurAdvancedMetricsService.TransferFlow flow = breakdown.flows().get(0);
        assertEquals("arn:aws:s3:::prod-replica", flow.resourceId());
        assertEquals("Asia Pacific (Mumbai)", flow.fromLocation());
        assertEquals("US East (N. Virginia)", flow.toLocation());
        assertEquals(2.44, flow.cost(), 0.0001);
        // The category total travels with the row, so the caller can say what the list of
        // three flows leaves out rather than summing the page and understating it.
        assertEquals(3L, flow.categoryFlows());
        assertEquals(2.60, flow.categoryCost(), 0.0001);
    }

    @Test
    @DisplayName("Without the location columns the route is recovered from the usage type")
    void dataTransferDerivesTheRouteFromTheUsageType() {
        // Athena rejects the whole query during analysis when a selected column does not
        // exist, so an absent location has to be left out of the SQL, not selected as null.
        when(columnCatalog.present(any(), any(), anyString(), anyString())).thenReturn(List.of());
        when(columnCatalog.has(any(), any(), anyString())).thenReturn(false);

        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2, String.class);
            assertTrue(!sql.contains("product_from_location") && !sql.contains("product_to_location"),
                    "no location column should be referenced when the report has none");
            return List.of(Map.of(
                    "category", "Inter-region",
                    "service", "AWS Data Transfer",
                    "usage_type", "APS3-USE1-AWS-Out-Bytes",
                    "unit", "GB",
                    "region", "ap-south-1",
                    "quantity", "29.0",
                    "cost", "2.44"));
        });

        CurAdvancedMetricsService.DataTransferBreakdown breakdown =
                service.getDataTransferCosts(source, account, start, end);

        // The column is missing, so the flag is false — but the route is not, because the
        // usage type encodes both ends and saying nothing would be a worse answer.
        assertTrue(!breakdown.routeDataAvailable());
        assertTrue(!breakdown.resourceDataAvailable());
        CurAdvancedMetricsService.TransferCharge charge = breakdown.charges().get(0);
        assertEquals("Asia Pacific (Mumbai)", charge.fromLocation());
        assertEquals("US East (N. Virginia)", charge.toLocation());
        assertTrue(charge.routeDerived(), "an inferred route must be marked as inferred");
        assertEquals(2.44, charge.cost(), 0.0001);
        assertTrue(breakdown.flows().isEmpty());
    }

    @Test
    @DisplayName("AWS's 'External' and our 'Internet' are one place, so a route is not split in two")
    void internetEndpointHasOneSpelling() {
        when(columnCatalog.present(any(), any(), contains("from_location"),
                contains("from_region_code"))).thenReturn(List.of("product_from_location"));
        when(columnCatalog.present(any(), any(), contains("to_location"),
                contains("to_region_code"))).thenReturn(List.of("product_to_location"));
        when(columnCatalog.has(any(), any(), anyString())).thenReturn(false);

        when(athena.query(any(), any(), anyString())).thenReturn(List.of(
                // What AWS writes when it fills the column in...
                Map.of("category", "Internet egress", "service", "AWS Data Transfer",
                        "usage_type", "APS3-DataTransfer-Out-Bytes", "unit", "GB",
                        "region", "ap-south-1",
                        "from_location", "Asia Pacific (Mumbai)", "to_location", "External",
                        "quantity", "40.0", "cost", "4.00"),
                // ...and a row where it did not, so the destination is inferred instead.
                Map.of("category", "Internet egress", "service", "Amazon Simple Storage Service",
                        "usage_type", "APS3-DataTransfer-Out-Bytes", "unit", "GB",
                        "region", "ap-south-1",
                        "from_location", "Asia Pacific (Mumbai)", "to_location", "",
                        "quantity", "20.0", "cost", "2.00")));

        List<CurAdvancedMetricsService.TransferCharge> charges =
                service.getDataTransferCosts(source, account, start, end).charges();

        // Both name the same destination, so the UI rolls them into one route worth $6.00
        // rather than two worth $4.00 and $2.00 — which would read as two smaller problems.
        assertEquals("Internet", charges.get(0).toLocation());
        assertEquals("Internet", charges.get(1).toLocation());
        // Provenance still differs, and still shows.
        assertTrue(!charges.get(0).routeDerived());
        assertTrue(charges.get(1).routeDerived());
    }

    @Test
    @DisplayName("Flow quantities keep gigabytes and hours apart, so NAT bytes are not added to hours")
    void dataTransferFlowQuantitiesAreSplitByUnit() {
        when(columnCatalog.present(any(), any(), anyString(), anyString())).thenReturn(List.of());
        when(columnCatalog.has(any(), any(), anyString())).thenReturn(true);
        when(tagColumns.resourceNameExpr(any(), any())).thenReturn(Optional.empty());
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2, String.class);
            if (sql.contains("rank_in_category")) {
                assertTrue(sql.contains("upper(pricing_unit) = 'GB'"),
                        "gigabytes must be restricted to GB-denominated rows");
                assertTrue(sql.contains("upper(pricing_unit) IN ('HRS', 'HOURS', 'HOUR')"),
                        "hours must be restricted to hour-denominated rows");
                assertTrue(sql.contains("line_item_usage_type AS usage_type"),
                        "usage type belongs in the flow grain — it is what the route falls back to");
            }
            return List.of();
        });

        service.getDataTransferCosts(source, account, start, end);
    }

    @Test
    @DisplayName("VPC endpoint and Transit Gateway are their own categories, not Other network")
    void networkCategoriesCoverGatewaysAndEndpoints() {
        when(columnCatalog.present(any(), any(), anyString(), anyString())).thenReturn(List.of());
        when(columnCatalog.has(any(), any(), anyString())).thenReturn(false);
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2, String.class);
            // A VPC endpoint is the fix for NAT gateway processing charges, so it has to be
            // visible as its own number rather than folded into a catch-all.
            assertTrue(sql.contains("'VPC endpoint'") && sql.contains("'Transit Gateway'"),
                    "both categories must be in the CASE");
            // Their hourly components carry no "Bytes", so the filter has to name them.
            assertTrue(sql.contains("LIKE '%VpcEndpoint%'") && sql.contains("LIKE '%TransitGateway%'"),
                    "both must be in the usage type filter");
            return List.of();
        });

        service.getDataTransferCosts(source, account, start, end);
    }

    @Test
    @DisplayName("Tag coverage measures dollars, and excludes charges that can carry no tag")
    void tagCoverageIsCostWeightedAndSkipsAccountLevelCharges() {
        when(tagColumns.exprsFor(any(), any(), any()))
                .thenReturn(Map.of("cost-center", "resource_tags_user_cost_center"));
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2, String.class);
            // Tax and support belong to no resource and can never be tagged; scoring them
            // would put an unfixable floor under the number.
            assertTrue(sql.contains("<> ''"), sql);
            return List.of(Map.ofEntries(
                    Map.entry("resource_id", "nat-0b07847ea0840d03b"),
                    Map.entry("service", "Amazon Elastic Compute Cloud"),
                    Map.entry("region", "us-east-1"),
                    Map.entry("cost", "63.60"),
                    Map.entry("allocated", "0.0"),
                    Map.entry("total_cost", "2423.49"),
                    Map.entry("total_allocated", "1972.29"),
                    Map.entry("tagged_0", "0.0"),
                    Map.entry("total_tagged_0", "1972.29"),
                    Map.entry("untagged_count", "137"),
                    Map.entry("service_untagged", "300.00"),
                    Map.entry("service_untagged_count", "42"),
                    Map.entry("global_rank", "1")));
        });

        CurAdvancedMetricsService.TagCoverage coverage =
                service.getTagCoverage(source, account, start, end, List.of("cost-center"));

        // Counted over every resource, not over the folded list — otherwise an account with
        // more offenders than the cap reports the cap as its finding.
        assertEquals(137L, coverage.untaggedCount());
        assertEquals(2423.49, coverage.totalCost(), 0.0001);
        assertEquals(1972.29, coverage.allocatedCost(), 0.0001);
        assertEquals(451.20, coverage.byTag().get(0).untaggedCost(), 0.01);

        // The service carries its own complete total, not the sum of the rows listed under
        // it — one row came back, but it stands for 42.
        CurAdvancedMetricsService.UntaggedService svc = coverage.untaggedServices().get(0);
        assertEquals("Amazon Elastic Compute Cloud", svc.service());
        assertEquals(300.00, svc.cost(), 0.0001);
        assertEquals(42L, svc.resourceCount());
        assertEquals(1, svc.resources().size());
        // The resource is listed against its unattributed spend, not its total.
        assertEquals(63.60, svc.resources().get(0).cost(), 0.0001);
        assertEquals(List.of("cost-center"), svc.resources().get(0).missingTags());
        // Ranked inside the head, so it counts toward "the top 20 are this much of it".
        assertEquals(63.60, coverage.concentrationCost(), 0.0001);
    }

    @Test
    @DisplayName("Window columns sit in the SELECT list, not after the GROUP BY")
    void tagCoverageBuildsParseableSql() {
        when(tagColumns.exprsFor(any(), any(), any()))
                .thenReturn(Map.of("cost-center", "resource_tags_user_cost_center"));
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2, String.class);
            // Athena is mocked here, so nothing else in this class would notice the window
            // columns being concatenated onto the end of the finished statement — where
            // they are a syntax error rather than a wrong answer.
            int windows = sql.indexOf("AS service_untagged");
            int groupBy = sql.indexOf("GROUP BY");
            assertTrue(windows > 0 && groupBy > 0, sql);
            assertTrue(windows < groupBy,
                    "window columns must precede GROUP BY, got:\n" + sql);
            // And the ranks they feed must be filtered in the enclosing query, not inline.
            assertTrue(sql.lastIndexOf("WHERE service_rank") > groupBy, sql);
            return List.of();
        });

        service.getTagCoverage(source, account, start, end, List.of("cost-center"));
    }

    @Test
    @DisplayName("A resource outside the concentration head does not inflate its figure")
    void tagCoverageConcentrationCountsOnlyTheHead() {
        when(tagColumns.exprsFor(any(), any(), any()))
                .thenReturn(Map.of("cost-center", "resource_tags_user_cost_center"));
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(
                row("big", "1", "100.0"),
                row("small", "97", "3.0")));

        CurAdvancedMetricsService.TagCoverage coverage =
                service.getTagCoverage(source, account, start, end, List.of("cost-center"));

        assertEquals(100.0, coverage.concentrationCost(), 0.0001);
        assertEquals(20, coverage.concentrationHead());
    }

    /**
     * A report carrying every optional column this class asks about.
     *
     * <p>Stubbed per test rather than in {@code setUp}: {@code MockitoExtension} is strict about
     * unused stubs, so a test whose path never reaches the catalog must not declare it.
     */
    private void everyColumnPresent() {
        when(columnCatalog.has(any(), any(), anyString())).thenReturn(true);
    }

    /** A trimmed Data Export: every optional column except the named one. */
    private void presentExcept(String absent) {
        when(columnCatalog.has(any(), any(), anyString())).thenAnswer(invocation ->
                !absent.equals(invocation.getArgument(2, String.class)));
    }

    /** One untagged resource row, at a given global rank. */
    private Map<String, String> row(String id, String globalRank, String cost) {
        return Map.ofEntries(
                Map.entry("resource_id", id),
                Map.entry("service", "Amazon Elastic Compute Cloud"),
                Map.entry("region", "ap-south-1"),
                Map.entry("cost", cost),
                Map.entry("allocated", "0.0"),
                Map.entry("total_cost", "2423.49"),
                Map.entry("total_allocated", "0.0"),
                Map.entry("untagged_count", "1826"),
                Map.entry("service_untagged", "2423.49"),
                Map.entry("service_untagged_count", "1826"),
                Map.entry("global_rank", globalRank),
                Map.entry("tagged_0", "0.0"),
                Map.entry("total_tagged_0", "0.0"));
    }

    @Test
    @DisplayName("A tag missing from the report is reported unavailable, not scored as 0%")
    void tagCoverageSeparatesUnavailableFromUntagged() {
        when(tagColumns.exprsFor(any(), any(), any())).thenReturn(Map.of());
        when(tagColumns.availableTagKeys(any(), any())).thenReturn(List.of("name"));

        CurAdvancedMetricsService.TagCoverage coverage =
                service.getTagCoverage(source, account, start, end, List.of("cost-center", "project"));

        // Claiming 0% compliant would be a statement about the infrastructure when the
        // truth is a setting in the billing console.
        assertEquals(List.of("cost-center", "project"), coverage.unavailableTags());
        assertTrue(coverage.checkedTags().isEmpty());
        assertTrue(coverage.untaggedServices().isEmpty());
    }

    @Test
    @DisplayName("Chargeback covers the same charges as coverage, so the two halves reconcile")
    void costByTagExcludesAccountLevelCharges() {
        when(tagColumns.exprsFor(any(), any(), any()))
                .thenReturn(Map.of("cost-center", "resource_tags_user_cost_center"));
        when(athena.query(any(), any(), anyString())).thenAnswer(invocation -> {
            // Without this the split includes tax and support while the coverage total does
            // not, and every share is computed against a smaller denominator than its own
            // numerator — which is how a bar reads 103%.
            assertTrue(invocation.getArgument(2, String.class)
                    .contains("COALESCE(NULLIF(line_item_resource_id, ''), '') <> ''"));
            return List.of(Map.of("tag_value", "platform", "cost", "1204.00", "resources", "12"));
        });

        assertEquals(1, service.getCostByTagValue(source, account, start, end, "cost-center").size());
    }

    @Test
    @DisplayName("Chargeback files spend with no tag value under (unassigned) rather than dropping it")
    void costByTagKeepsUnassignedSpend() {
        when(tagColumns.exprsFor(any(), any(), any()))
                .thenReturn(Map.of("cost-center", "resource_tags_user_cost_center"));
        when(athena.query(any(), any(), contains("(unassigned)"))).thenReturn(List.of(
                Map.of("tag_value", "platform", "cost", "1204.00", "resources", "12"),
                Map.of("tag_value", "(unassigned)", "cost", "451.20", "resources", "37")));

        List<CurAdvancedMetricsService.TagValueCost> byValue =
                service.getCostByTagValue(source, account, start, end, "cost-center");

        assertEquals(2, byValue.size());
        assertEquals("(unassigned)", byValue.get(1).value());
        assertEquals(37L, byValue.get(1).resourceCount());
    }

    // ── The batch per-resource query, behind the connectivity tab ───────────────

    @Test
    @DisplayName("Every candidate reaches one IN list, with embedded quotes doubled")
    void resourceCostsEscapesEveryCandidate() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(athena.query(any(), any(), sql.capture())).thenReturn(List.of());

        service.getResourceCosts(source, account,
                List.of("i-0abc", "arn:aws:rds:ap-south-1:123456789012:db:o'brien"),
                start, end, CostBasis.UNBLENDED);

        String emitted = sql.getValue();
        assertTrue(emitted.contains("line_item_resource_id IN ('i-0abc',"
                        + " 'arn:aws:rds:ap-south-1:123456789012:db:o''brien')"),
                emitted);
    }

    @Test
    @DisplayName("A resource whose charges and credits cancel is dropped, not reported as $0.00")
    void resourceCostsUsesAbsoluteHaving() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(athena.query(any(), any(), sql.capture())).thenReturn(List.of());

        service.getResourceCosts(source, account, List.of("i-0abc"), start, end,
                CostBasis.UNBLENDED);

        // Credits and refunds are negative, so a bare `SUM(cost) > 0.001` would keep a resource
        // whose credits exceed its charges and drop one whose charges exceed its credits.
        assertTrue(sql.getValue().contains("HAVING ABS(SUM("), sql.getValue());
        assertTrue(sql.getValue().contains(")) > 0.001"), sql.getValue());
    }

    @Test
    @DisplayName("The totals windows are inside the subquery and the LIMIT is the candidate count")
    void resourceCostsSizesTheLimitToTheCandidateList() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(athena.query(any(), any(), sql.capture())).thenReturn(List.of());

        // Longer than MAX_LOOKUP_IDS, which is reachable because each requested id contributes up
        // to two candidates. A LIMIT of 100 here would drop the cheapest groups and leave
        // pricedCount disagreeing with the total_cost window.
        List<String> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < CurAdvancedMetricsService.MAX_LOOKUP_IDS + 23; i++) {
            candidates.add("i-" + i);
        }
        service.getResourceCosts(source, account, candidates, start, end, CostBasis.UNBLENDED);

        String emitted = sql.getValue();
        int subqueryEnd = emitted.indexOf(") ORDER BY cost DESC");
        assertTrue(subqueryEnd > 0, emitted);
        assertTrue(emitted.indexOf("SUM(SUM(") < subqueryEnd, emitted);
        assertTrue(emitted.indexOf("COUNT(*) OVER ()") < subqueryEnd, emitted);
        assertTrue(emitted.endsWith(" LIMIT " + candidates.size()), emitted);
    }

    @Test
    @DisplayName("No charge-type filter and no service or region scope — a peer's whole month")
    void resourceCostsSumsEveryChargeTypeEverywhere() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(athena.query(any(), any(), sql.capture())).thenReturn(List.of());

        service.getResourceCosts(source, account, List.of("i-0abc"), start, end,
                CostBasis.UNBLENDED);

        String emitted = sql.getValue();
        // A peer is a different resource with its own services and regions, so scoping it by the
        // focus resource's service would zero almost every peer.
        assertTrue(!emitted.contains("line_item_line_item_type"), emitted);
        assertTrue(!emitted.contains("product_product_name"), emitted);
        assertTrue(!emitted.contains("product_region"), emitted);
    }

    @Test
    @DisplayName("Both unpartitioned windows describe the matched set, not the returned page")
    void resourceCostsReadsTheWindowsOffEveryRow() {
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(
                Map.of("resource_id", "i-0abc", "cost", "1621.35",
                        "total_cost", "2106.09", "group_count", "2"),
                Map.of("resource_id", "vol-0aa1", "cost", "484.74",
                        "total_cost", "2106.09", "group_count", "2")));

        CurAdvancedMetricsService.ResourceGroupCosts costs = service.getResourceCosts(
                source, account, List.of("i-0abc", "vol-0aa1"), start, end, CostBasis.UNBLENDED);

        assertEquals(2, costs.groups().size());
        assertEquals("i-0abc", costs.groups().get(0).curResourceId());
        assertEquals(1621.35, costs.groups().get(0).cost(), 0.0001);
        assertEquals(2106.09, costs.totalCost(), 0.0001);
        assertEquals(2L, costs.groupCount());
    }

    @Test
    @DisplayName("The amortized basis refuses a trimmed export rather than 502-ing in Athena")
    void resourceCostsRefusesAmortizedWithoutTheCommitmentColumn() {
        presentExcept("reservation_effective_cost");

        ReportCannotAnswerException thrown = assertThrows(ReportCannotAnswerException.class,
                () -> service.getResourceCosts(source, account, List.of("i-0abc"), start, end,
                        CostBasis.AMORTIZED));

        assertEquals("the amortized cost basis", thrown.question());
        assertEquals(List.of("reservation_effective_cost"), thrown.missingColumns());
        verifyNoInteractions(athena);
    }

    @Test
    @DisplayName("The basis threads through to the cost expression")
    void resourceCostsHonoursTheAmortizedBasis() {
        everyColumnPresent();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(athena.query(any(), any(), sql.capture())).thenReturn(List.of());

        service.getResourceCosts(source, account, List.of("i-0abc"), start, end,
                CostBasis.AMORTIZED);

        assertTrue(sql.getValue().contains("reservation_effective_cost"), sql.getValue());
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
