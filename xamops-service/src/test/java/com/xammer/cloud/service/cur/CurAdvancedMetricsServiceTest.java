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
import java.util.Optional;

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
