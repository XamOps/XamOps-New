package com.xammer.billops.service.cur;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.CurSourceView;
import com.xammer.billops.dto.cur.CurAggregates;
import com.xammer.billops.repository.CurSourceViewRepository;
import com.xammer.billops.service.CurIngestionService;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers when the report is allowed to answer for an invoice, and what it answers.
 *
 * Every refusal here is one an admin will read on the invoices page, and every figure
 * is one that ends up on a customer's bill, so the cases are the ways each could be
 * wrong without anything visibly failing.
 */
class CurInvoiceDataServiceTest {

    private static final String ACCOUNT_ID = "605134457560";
    private static final String EC2 = "Amazon Elastic Compute Cloud";

    private CurSourceViewRepository sources;
    private CurAthenaQueryService athena;
    private CurIngestionService store;
    private CurInvoiceDataService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        sources = mock(CurSourceViewRepository.class);
        athena = mock(CurAthenaQueryService.class);
        store = mock(CurIngestionService.class);
        service = new CurInvoiceDataService(sources, athena, store);
        ReflectionTestUtils.setField(service, "curEnabled", true);
        ReflectionTestUtils.setField(service, "openMonthMaxAgeHours", 6);

        account = new CloudAccount();
        account.setAwsAccountId(ACCOUNT_ID);
        account.setProvider("AWS");
    }

    // ------------------------------------------------------------------------
    // Availability
    // ------------------------------------------------------------------------

    @Test
    void refusesWhenReadingTheReportIsSwitchedOff() {
        ReflectionTestUtils.setField(service, "curEnabled", false);
        assertThat(service.checkAvailability(account, closedMonth()).reason())
                .isEqualTo(CurUnavailable.CUR_DISABLED);
    }

    @Test
    void refusesAnAccountWithNoReportConnected() {
        when(sources.findBestForAccount(ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThat(service.checkAvailability(account, closedMonth()).reason())
                .isEqualTo(CurUnavailable.NO_CUR_SOURCE);
    }

    @Test
    void aMissingRegistryReadsAsNoReportRatherThanASqlError() {
        // An environment where xamops-service's cur_source migration has not run.
        when(sources.findBestForAccount(ACCOUNT_ID))
                .thenThrow(new RuntimeException("relation \"cur_source\" does not exist"));

        CurInvoiceDataService.Availability result = service.checkAvailability(account, closedMonth());

        assertThat(result.reason()).isEqualTo(CurUnavailable.NO_CUR_SOURCE);
        assertThat(result.message()).doesNotContain("relation");
    }

    @Test
    void refusesAFailedSourceAndSaysWhy() {
        // The status detail is the actual cause. Dropping it would leave the admin with
        // "not active" and no idea whether to fix a bucket, a role or a prefix.
        CurSourceView failed = source("FAILED", "2026-01");
        ReflectionTestUtils.setField(failed, "statusDetail", "manifest not found under cur/xamops-cur");
        when(sources.findBestForAccount(ACCOUNT_ID)).thenReturn(Optional.of(failed));

        CurInvoiceDataService.Availability result = service.checkAvailability(account, closedMonth());

        assertThat(result.reason()).isEqualTo(CurUnavailable.SOURCE_NOT_ACTIVE);
        assertThat(result.message()).contains("manifest not found under cur/xamops-cur");
    }

    @Test
    void refusesAnActiveSourceThatNeverDiscoveredItsTable() {
        CurSourceView noTable = source("ACTIVE", "2026-01");
        ReflectionTestUtils.setField(noTable, "athenaTable", null);
        when(sources.findBestForAccount(ACCOUNT_ID)).thenReturn(Optional.of(noTable));

        assertThat(service.checkAvailability(account, closedMonth()).reason())
                .isEqualTo(CurUnavailable.SOURCE_NOT_ACTIVE);
    }

    @Test
    void refusesAMonthBeforeTheReportStartedAndNamesTheStart() {
        // A CUR never backfills. This is the one refusal that waiting will not fix, so
        // the message has to say where the report actually begins.
        YearMonth start = YearMonth.now().minusMonths(1);
        when(sources.findBestForAccount(ACCOUNT_ID))
                .thenReturn(Optional.of(source("ACTIVE", start.toString())));

        CurInvoiceDataService.Availability result =
                service.checkAvailability(account, start.minusMonths(1));

        assertThat(result.reason()).isEqualTo(CurUnavailable.PERIOD_BEFORE_REPORT_START);
        assertThat(result.message()).contains(start.toString());
    }

    @Test
    void refusesAMonthThatHasNotStarted() {
        assertThat(service.checkAvailability(account, YearMonth.now().plusMonths(1)).reason())
                .isEqualTo(CurUnavailable.PERIOD_IN_FUTURE);
    }

    @Test
    void servesTheOpenMonth() {
        // Open-month drafts come from the report and are marked provisional by the
        // invoice. Refusing here would push every current-month draft to Cost Explorer.
        when(sources.findBestForAccount(ACCOUNT_ID))
                .thenReturn(Optional.of(source("ACTIVE", YearMonth.now().minusMonths(3).toString())));

        assertThat(service.checkAvailability(account, YearMonth.now()).available()).isTrue();
    }

    @Test
    void treatsAnUnknownStartAsQueryable() {
        // A source validated before earliest_billing_period was populated. An empty
        // query result will say so honestly; refusing up front would not.
        when(sources.findBestForAccount(ACCOUNT_ID)).thenReturn(Optional.of(source("ACTIVE", null)));

        assertThat(service.checkAvailability(account, closedMonth()).available()).isTrue();
    }

    // ------------------------------------------------------------------------
    // Rate lines — what the invoice lists
    // ------------------------------------------------------------------------

    @Test
    void listsEachRateLineWithItsQuantityUnitAndAmount() {
        activeSource();
        answerWith(List.of(
                row(EC2, "ap-south-1", "APS3-BoxUsage:m7i.xlarge",
                        "$0.2121 per On Demand Linux m7i.xlarge Instance Hour", "Hrs", 95.38, 449.67, 0)));

        CurAggregates result = service.ensureMonthLoaded(account, closedMonth(), false);

        assertThat(result.getCharges()).singleElement().satisfies(c -> {
            assertThat(c.getService()).isEqualTo(EC2);
            assertThat(c.getRegion()).isEqualTo("ap-south-1");
            assertThat(c.getUsageType()).isEqualTo("APS3-BoxUsage:m7i.xlarge");
            assertThat(c.getDescription()).isEqualTo("$0.2121 per On Demand Linux m7i.xlarge Instance Hour");
            assertThat(c.getUnit()).isEqualTo("Hrs");
            assertThat(c.getQuantity()).isEqualTo(449.67);
            assertThat(c.getCost()).isEqualTo(95.38);
        });
    }

    @Test
    void keepsNegativeLinesSoCoveredUsageIsNotBilledAtFullRate() {
        // The live report's m7i.xlarge line and its Savings Plan negation. Dropping the
        // negative half would bill the covered hours at the on-demand rate.
        activeSource();
        answerWith(List.of(
                row(EC2, "ap-south-1", "APS3-BoxUsage:m7i.xlarge",
                        "$0.2121 per On Demand Linux m7i.xlarge Instance Hour", "Hrs", 95.38, 449.67, 0),
                row(EC2, "ap-south-1", "APS3-BoxUsage:m7i.xlarge",
                        "SavingsPlanNegation used by AccountId : 605134457560 and UsageSku : 9F8VBK2QNNNBSF2Y",
                        "Hrs", -0.11, 0.525833, 0)));

        CurAggregates result = service.ensureMonthLoaded(account, closedMonth(), false);

        assertThat(result.getCharges()).hasSize(2);
        assertThat(result.getCharges()).extracting(CurAggregates.ChargeEntry::getCost)
                .containsExactly(95.38, -0.11);
        // The rollups the billing page reads net the two, as the bill does.
        assertThat(result.getSvcUsage()).singleElement()
                .satisfies(u -> assertThat(u.getCost()).isCloseTo(95.27, Offset.offset(1e-9)));
    }

    @Test
    void leavesOffLinesThatNetToNothingButStillTotalsThem() {
        // The cost page's HAVING drops lines at or under $0.001; the invoice must list the
        // same lines, while the month total still includes every cent.
        activeSource();
        answerWith(List.of(
                row(EC2, "ap-south-1", "APS3-BoxUsage:t3.medium", "t3.medium hours", "Hrs", 100.0, 720, 0),
                row("AWS CloudTrail", "us-east-1", "USE1-FreeEventsRecorded", "Free events", "Events", 0.0004, 0, 0)));

        CurAggregates result = service.ensureMonthLoaded(account, closedMonth(), false);

        assertThat(result.getCharges()).extracting(CurAggregates.ChargeEntry::getService).containsExactly(EC2);
        assertThat(result.getTotal()).isCloseTo(100.0004, Offset.offset(1e-9));
    }

    @Test
    void labelsBlanksExactlyAsTheCostPageDoes() {
        // Region-less charges are "Global", as the AWS invoice files them; a line with no
        // description falls back to its usage type.
        activeSource();
        answerWith(List.of(row("", "", "USE1-Tax", "", "", 3.0, 0, 0)));

        CurAggregates result = service.ensureMonthLoaded(account, closedMonth(), false);

        assertThat(result.getCharges()).singleElement().satisfies(c -> {
            assertThat(c.getService()).isEqualTo("Other");
            assertThat(c.getRegion()).isEqualTo("Global");
            assertThat(c.getDescription()).isEqualTo("USE1-Tax");
        });
    }

    @Test
    void groupsTheWayTheCostPageDoes() {
        // Same grouping as xamops-service's "Charges by service" query, so the invoice
        // and that section list the same lines.
        activeSource();
        answerWith(List.of(row(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 10.0, 1, 0)));

        service.ensureMonthLoaded(account, closedMonth(), true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(athena, atLeastOnce()).query(any(), any(), sql.capture());
        assertThat(sql.getAllValues().get(0)).contains("GROUP BY product_product_name, product_region,"
                + " line_item_line_item_description, line_item_usage_type, pricing_unit");
    }

    // ------------------------------------------------------------------------
    // Rollups and totals
    // ------------------------------------------------------------------------

    @Test
    void foldsRateLinesIntoOneRowPerServiceAndRegion() {
        activeSource();
        answerWith(List.of(
                row(EC2, "ap-south-1", "APS3-BoxUsage:t3.medium", "t3.medium", "Hrs", 100.0, 720, 0),
                row(EC2, "ap-south-1", "APS3-EBS:VolumeUsage.gp3", "gp3", "GB-Mo", 20.0, 100, 0),
                row(EC2, "us-east-1", "USE1-BoxUsage:t3.micro", "t3.micro", "Hrs", 5.0, 720, 0)));

        CurAggregates result = service.ensureMonthLoaded(account, closedMonth(), false);

        assertThat(result.getSvcRegion()).hasSize(2);
        assertThat(result.getSvcRegion()).filteredOn(r -> r.getRegion().equals("ap-south-1"))
                .singleElement()
                .satisfies(r -> assertThat(r.getCost()).isEqualTo(120.0));
    }

    @Test
    void measuresMarketplaceEvenWhenItIsZero() {
        // Zero and "never measured" are different answers: the invoice refuses the
        // second rather than billing nothing for charges that may exist.
        activeSource();
        answerWith(List.of(row(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 10.0, 1, 0)));

        assertThat(service.ensureMonthLoaded(account, closedMonth(), false).getMarketplace()).isEqualTo(0.0);
    }

    @Test
    void sumsMarketplaceChargesAcrossRows() {
        activeSource();
        answerWith(List.of(
                row("Claude Sonnet (Amazon Bedrock Edition)", "ap-south-1", "APS3-MP:Output", "tokens", "Units", 1.0, 10, 1.0),
                row("Some Marketplace AMI", "us-east-1", "USE1-MP:Hours", "hours", "Hrs", 0.1174, 3, 0.1174),
                row(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 10.0, 1, 0)));

        assertThat(service.ensureMonthLoaded(account, closedMonth(), false).getMarketplace())
                .isCloseTo(1.1174, Offset.offset(1e-9));
    }

    @Test
    void storesWhatItRead() {
        activeSource();
        answerWith(List.of(row(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 10.0, 1, 0)));
        YearMonth month = closedMonth();

        CurAggregates result = service.ensureMonthLoaded(account, month, false);

        verify(store).storeAggregates(ACCOUNT_ID, month, result);
    }

    // ------------------------------------------------------------------------
    // Failures
    // ------------------------------------------------------------------------

    @Test
    void anEmptyMonthIsNoDataRatherThanAFailure() {
        activeSource();
        answerWith(List.of());

        assertThatThrownBy(() -> service.ensureMonthLoaded(account, closedMonth(), false))
                .isInstanceOf(CurUnavailableException.class)
                .extracting(e -> ((CurUnavailableException) e).getReason())
                .isEqualTo(CurUnavailable.NO_DATA_FOR_MONTH);
        verify(store, never()).storeAggregates(anyString(), any(), any());
    }

    @Test
    void anAthenaFailureIsReportedNotDowngraded() {
        activeSource();
        when(athena.query(any(), any(), anyString()))
                .thenThrow(new CurAthenaQueryService.CurQueryException("Athena query FAILED: TABLE_NOT_FOUND"));

        assertThatThrownBy(() -> service.ensureMonthLoaded(account, closedMonth(), false))
                .isInstanceOf(CurUnavailableException.class)
                .satisfies(e -> {
                    assertThat(((CurUnavailableException) e).getReason()).isEqualTo(CurUnavailable.QUERY_FAILED);
                    assertThat(e.getMessage()).contains("TABLE_NOT_FOUND");
                });
    }

    // ------------------------------------------------------------------------
    // Reuse
    // ------------------------------------------------------------------------

    @Test
    void reusesAStoredClosedMonthWithoutQuerying() {
        // A closed month cannot move, and every query is billed to the customer.
        activeSource();
        YearMonth month = closedMonth();
        storedWithRateLines(month, LocalDateTime.now().minusDays(20));

        service.ensureMonthLoaded(account, month, false);

        verify(athena, never()).query(any(), any(), anyString());
    }

    @Test
    void rereadsAStoredMonthThatHasNoRateLines() {
        // Stored by the CSV ingestion, or by this class before it read at the rate-line
        // grain. It cannot build an invoice however recent it is.
        activeSource();
        YearMonth month = closedMonth();
        when(store.hasCurData(ACCOUNT_ID, month)).thenReturn(true);
        when(store.readCharges(ACCOUNT_ID, month)).thenReturn(Optional.empty());
        answerWith(List.of(row(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 10.0, 1, 0)));

        service.ensureMonthLoaded(account, month, false);

        verify(store).storeAggregates(eq(ACCOUNT_ID), eq(month), any());
    }

    @Test
    void reusesARecentOpenMonth() {
        activeSource();
        YearMonth month = YearMonth.now();
        storedWithRateLines(month, LocalDateTime.now().minusHours(1));

        service.ensureMonthLoaded(account, month, false);

        verify(athena, never()).query(any(), any(), anyString());
    }

    @Test
    void rereadsAStaleOpenMonth() {
        // AWS rewrites the open month several times a day; a draft built on a copy from
        // yesterday would miss a day of charges.
        activeSource();
        YearMonth month = YearMonth.now();
        storedWithRateLines(month, LocalDateTime.now().minusHours(10));
        answerWith(List.of(row(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 10.0, 1, 0)));

        service.ensureMonthLoaded(account, month, false);

        verify(store).storeAggregates(eq(ACCOUNT_ID), eq(month), any());
    }

    @Test
    void queriesOnlyThisAccountAndOnlyTheMonthsPartitions() {
        // Without the account filter a payer's report bills this customer for every
        // member account; without the partition filter the customer pays to scan years.
        activeSource();
        answerWith(List.of(row(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 10.0, 1, 0)));
        YearMonth month = YearMonth.of(2026, 9);
        ReflectionTestUtils.setField(sources.findBestForAccount(ACCOUNT_ID).get(), "earliestBillingPeriod", "2026-01");

        service.ensureMonthLoaded(account, month, true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(athena, atLeastOnce()).query(any(), any(), sql.capture());
        assertThat(sql.getAllValues()).allSatisfy(s -> {
            assertThat(s).contains("line_item_usage_account_id = '" + ACCOUNT_ID + "'");
            assertThat(s).contains("BETWEEN '202608' AND '202610'");
            assertThat(s).contains("TIMESTAMP '2026-09-01 00:00:00'");
            assertThat(s).contains("TIMESTAMP '2026-10-01 00:00:00'");
        });
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static YearMonth closedMonth() {
        return YearMonth.now().minusMonths(2);
    }

    private void activeSource() {
        when(sources.findBestForAccount(ACCOUNT_ID))
                .thenReturn(Optional.of(source("ACTIVE", YearMonth.now().minusMonths(6).toString())));
    }

    private void storedWithRateLines(YearMonth month, LocalDateTime at) {
        when(store.hasCurData(ACCOUNT_ID, month)).thenReturn(true);
        when(store.readCharges(ACCOUNT_ID, month)).thenReturn(Optional.of(List.of(
                new CurAggregates.ChargeEntry(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 1, 10.0))));
        CurIngestionService.CurIngestState state = new CurIngestionService.CurIngestState();
        state.setIngestedAt(at.toString());
        when(store.readState(ACCOUNT_ID, month)).thenReturn(Optional.of(state));
    }

    /** Answers the breakdown query with {@code rows} and the daily query with one day. */
    private void answerWith(List<Map<String, String>> rows) {
        when(athena.query(any(), any(), anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(2);
            if (sql.contains("date_format")) {
                return rows.isEmpty() ? List.of() : List.of(Map.of("day", "2026-09-01", "cost", "1.0"));
            }
            return new ArrayList<>(rows);
        });
    }

    private static Map<String, String> row(String service, String region, String usageType, String description,
                                           String unit, double cost, double quantity, double marketplace) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("service", service);
        row.put("region", region);
        row.put("description", description);
        row.put("usage_type", usageType);
        row.put("unit", unit);
        row.put("quantity", String.valueOf(quantity));
        row.put("cost", String.valueOf(cost));
        row.put("marketplace", String.valueOf(marketplace));
        return row;
    }

    private static CurSourceView source(String status, String earliest) {
        CurSourceView source = new CurSourceView();
        ReflectionTestUtils.setField(source, "id", 1L);
        ReflectionTestUtils.setField(source, "ownerAccountId", ACCOUNT_ID);
        ReflectionTestUtils.setField(source, "athenaDatabase", "xamops_cur");
        ReflectionTestUtils.setField(source, "athenaTable", "xamops_cur_" + ACCOUNT_ID);
        ReflectionTestUtils.setField(source, "athenaWorkgroup", "primary");
        ReflectionTestUtils.setField(source, "region", "us-east-1");
        ReflectionTestUtils.setField(source, "curVersion", "cur");
        ReflectionTestUtils.setField(source, "format", "Parquet");
        ReflectionTestUtils.setField(source, "status", status);
        ReflectionTestUtils.setField(source, "earliestBillingPeriod", earliest);
        return source;
    }
}
