package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.dto.CostDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Asserts on the generated SQL rather than on results, because the things that go
 * wrong here are invisible in a result set: a missing account filter leaks another
 * tenant's costs, and a missing partition filter turns a fraction-of-a-cent query
 * into a full-table scan.
 */
@ExtendWith(MockitoExtension.class)
class CurCostDataSourceTest {

    @Mock
    private CurAthenaQueryService athena;

    private CurCostDataSource dataSource;
    private CurSource source;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        dataSource = new CurCostDataSource(athena);

        source = new CurSource();
        source.setId(1L);
        source.setAthenaDatabase("xamops_cur");
        source.setAthenaTable("xamops_cur");
        source.setCurVersion("cur");
        source.setTimeUnit("HOURLY");

        account = new CloudAccount();
        account.setAwsAccountId("123456789012");
        account.setProvider("AWS");
    }

    private String captureSql(Runnable call) {
        when(athena.query(any(), any(), anyString())).thenReturn(List.of());
        call.run();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(athena).query(any(), any(), sql.capture());
        return sql.getValue();
    }

    @Test
    @DisplayName("Every query filters on the account — this is the tenant boundary")
    void everyQueryScopesToTheAccount() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);

        assertTrue(captureSql(() -> dataSource.getCostBreakdown(source, account, "SERVICE", null, start, end))
                .contains("line_item_usage_account_id = '123456789012'"));
    }

    @Test
    @DisplayName("Every query prunes partitions — without this it is a full-table scan")
    void everyQueryPrunesPartitions() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);

        String sql = captureSql(() -> dataSource.getTotalCost(source, account, start, end));
        assertTrue(sql.contains("concat(year, lpad(month, 2, '0')) BETWEEN"),
                "expected a partition predicate, got: " + sql);
        // Widened one month either side, so usage billed to an adjacent period is kept.
        assertTrue(sql.contains("'202607' AND '202609'"), sql);
    }

    @Test
    @DisplayName("A Parquet timestamp column is compared directly, never parsed as a string")
    void parquetTimestampNotReparsed() {
        // The manifest types line_item_usage_start_date as TIMESTAMP, so the generated
        // table declares it a timestamp. Casting one to varchar in Trino yields
        // "2026-09-03 07:00:00.000" — a space where ISO-8601 wants a T — and
        // from_iso8601_timestamp rejects it with INVALID_FUNCTION_ARGUMENT. Observed
        // against the live report before this was fixed.
        // A closed month, so the end-date clamp is not in play — this test is about the
        // timestamp expression alone.
        String sql = captureSql(() -> dataSource.getTotalCost(source, account,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));

        assertTrue(!sql.contains("from_iso8601_timestamp"),
                "a Parquet timestamp must not be re-parsed: " + sql);
        assertTrue(sql.contains("line_item_usage_start_date >= TIMESTAMP '2026-01-01 00:00:00'"), sql);
        // Half-open upper bound, so the final day is included whole for an HOURLY report.
        assertTrue(sql.contains("< TIMESTAMP '2026-02-01 00:00:00'"), sql);
    }

    @Test
    @DisplayName("A CSV report parses its string timestamps, then normalises the type")
    void csvTimestampIsParsed() {
        source.setFormat("csv");
        String sql = captureSql(() -> dataSource.getTotalCost(source, account,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));

        // OpenCSVSerde returns every column as a string, and CUR writes ISO-8601 there.
        assertTrue(sql.contains("CAST(from_iso8601_timestamp(line_item_usage_start_date) AS timestamp)"), sql);
    }

    @Test
    @DisplayName("A current-month end date is honoured, not capped at today")
    void endOfMonthRangeIsNotCapped() {
        // Capping at today looks right and is wrong: RI and Savings Plan recurring fees,
        // support and tax carry usage dates spanning the whole billing period from the day
        // it opens, and they are already on the bill. Verified on a live report for 1-8
        // September — the full-period range gave $2,254, matching the bill; capping at
        // today gave $934 and dropped $1,320 of real fee rows.
        LocalDate today = LocalDate.now();
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        LocalDate endOfMonth = today.withDayOfMonth(today.lengthOfMonth());

        String sql = captureSql(() -> dataSource.getTotalCost(source, account, firstOfMonth, endOfMonth));

        assertTrue(sql.contains("< TIMESTAMP '" + endOfMonth.plusDays(1) + " 00:00:00'"),
                "the requested end date must be honoured, got: " + sql);
    }

    @Test
    @DisplayName("CUR 2.0 sources partition on billing_period instead of year/month")
    void cur2UsesBillingPeriodPartition() {
        source.setCurVersion("cur2");
        String sql = captureSql(() -> dataSource.getTotalCost(source, account,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
        assertTrue(sql.contains("billing_period BETWEEN '2026-07' AND '2026-09'"), sql);
    }

    @Test
    @DisplayName("No line-item-type filter, so the total matches Cost Explorer's default")
    void sumsAllLineItemTypesToMatchCostExplorer() {
        // CE's GetCostAndUsage with no filter includes credits, refunds and tax. Filtering
        // to 'Usage' here would put CUR permanently below CE on any account with credits.
        String sql = captureSql(() -> dataSource.getTotalCost(source, account,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
        assertTrue(sql.contains("SUM(line_item_unblended_cost)"), sql);
        assertTrue(!sql.contains("line_item_line_item_type = 'Usage'"),
                "must not restrict to Usage — that would drop credits and tax");
    }

    @Test
    @DisplayName("Credits are filtered to the Credit line item type and returned positive")
    void creditsAreFilteredAndAbsolute() {
        when(athena.query(any(), any(), anyString()))
                .thenReturn(List.of(Map.of("cost", "-250.75")));

        double credits = dataSource.getCreditsApplied(source, account,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        // Stored negative because they reduce the bill; the CE path applies abs() too.
        assertEquals(250.75, credits, 0.001);
    }

    @Test
    @DisplayName("SERVICE groups by product name, matching the label Cost Explorer returns")
    void serviceGroupsByProductName() {
        String sql = captureSql(() -> dataSource.getCostBreakdown(source, account, "SERVICE", null,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
        assertTrue(sql.contains("product_product_name"), sql);
    }

    @Test
    @DisplayName("An unknown groupBy is rejected rather than interpolated into SQL")
    void unknownGroupByRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                dataSource.getCostBreakdown(source, account, "DROP TABLE", null,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
    }

    @Test
    @DisplayName("A malformed tag key is rejected — it lands in identifier position")
    void malformedTagKeyRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                dataSource.getCostBreakdown(source, account, "TAG", "bad key'; DROP",
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
    }

    @Test
    @DisplayName("A quote in a service name is escaped, not passed through")
    void serviceFilterEscapesQuotes() {
        String sql = captureSql(() -> dataSource.getDailyTrend(source, account,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5), "O'Reilly", null));
        assertTrue(sql.contains("'O''Reilly'"), sql);
    }

    @Test
    @DisplayName("\"ALL\" means unfiltered, matching how the Cost Explorer path reads it")
    void allMeansNoFilter() {
        String sql = captureSql(() -> dataSource.getDailyTrend(source, account,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5), "ALL", "ALL"));
        assertTrue(!sql.contains("product_product_name ="), sql);
        assertTrue(!sql.contains("product_region ="), sql);
    }

    @Test
    @DisplayName("A daily series is refused on a MONTHLY report rather than faked")
    void dailyTrendRefusedOnMonthlyReport() {
        source.setTimeUnit("MONTHLY");
        assertThrows(CurAthenaQueryService.CurQueryException.class, () ->
                dataSource.getDailyTrend(source, account,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5)));
    }

    @Test
    @DisplayName("An account id that is not 12 digits is refused before any query runs")
    void refusesQueryWithoutValidAccountId() {
        account.setAwsAccountId(null);
        assertThrows(IllegalArgumentException.class, () ->
                dataSource.getTotalCost(source, account,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
    }

    @Test
    @DisplayName("Region-less charges get the same label the Cost Explorer path uses")
    void blankRegionNormalised() {
        when(athena.query(any(), any(), anyString())).thenReturn(List.of(
                Map.of("svc", "AWS Support", "rgn", "", "cost", "100.0")));

        List<CurCostDataSource.ServiceRegionCost> costs = dataSource.getCostByServiceAndRegion(
                source, account, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertEquals("NoRegion", costs.get(0).region());
    }

    @Test
    @DisplayName("An untagged group keeps the CE label instead of being dropped")
    void untaggedRowsAreLabelled() {
        when(athena.query(any(), any(), anyString()))
                .thenReturn(List.of(Map.of("grp", "", "cost", "12.5")));

        List<CostDto> costs = dataSource.getCostBreakdown(source, account, "TAG", "Environment",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertEquals("Untagged", costs.get(0).getName());
        assertEquals(12.5, costs.get(0).getAmount(), 0.001);
    }
}
