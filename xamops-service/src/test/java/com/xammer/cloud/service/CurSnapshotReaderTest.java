package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.DetailedCostDto;
import com.xammer.cloud.repository.CachedDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the cross-module contract between BillOps and XamOps.
 *
 * BillOps writes CUR aggregates into the shared cached_data table; XamOps reads
 * them here. Nothing but matching JSON field names holds those two halves together,
 * so a rename on either side would otherwise produce silently empty FinOps data
 * rather than a compile error. The JSON literals below are exactly what BillOps'
 * CurAggregates serialises to — if this test starts failing, the two modules have
 * drifted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurSnapshotReaderTest {

    private static final String ACCOUNT = "123456789012";
    private static final YearMonth JULY = YearMonth.of(2026, 7);

    private CachedDataRepository repo;
    private CurSnapshotReader reader;

    // Field names here mirror CurAggregates.SvcRegionEntry in the BillOps module.
    private static final String SVC_REGION_JSON = """
            [
              {"service":"Amazon Elastic Compute Cloud","region":"us-east-1","cost":100.50},
              {"service":"Amazon Elastic Compute Cloud","region":"ap-south-1","cost":40.00},
              {"service":"AWS Data Transfer","region":"us-east-1","cost":46.08},
              {"service":"Tax","region":"global","cost":9.50}
            ]""";

    // Mirrors CurAggregates.SvcUsageEntry — note region is present, unlike the
    // Cost Explorer equivalent.
    private static final String SVC_USAGE_JSON = """
            [
              {"service":"Amazon Elastic Compute Cloud","region":"us-east-1","usageType":"BoxUsage:t3.micro","cost":80.50,"quantity":720.0,"unit":"Hrs"},
              {"service":"Amazon Elastic Compute Cloud","region":"ap-south-1","usageType":"BoxUsage:t3.micro","cost":40.00,"quantity":360.0,"unit":"Hrs"},
              {"service":"AWS Data Transfer","region":"us-east-1","usageType":"USE1-DataTransfer-Out-Bytes","cost":46.08,"quantity":512.0,"unit":"GB"},
              {"service":"Amazon CloudFront","region":"us-east-1","usageType":"US-DataTransfer-Out-Bytes","cost":12.34,"quantity":128.0,"unit":"GB"}
            ]""";

    // Mirrors CurAggregates.DailyEntry.
    private static final String DAILY_JSON = """
            [
              {"date":"2026-07-01","amount":60.00},
              {"date":"2026-07-02","amount":136.08}
            ]""";

    @BeforeEach
    void setUp() {
        repo = mock(CachedDataRepository.class);
        reader = new CurSnapshotReader(repo, new ObjectMapper());
        // @Value is not processed outside a Spring context. Production default is
        // false; these tests exercise the enabled path.
        ReflectionTestUtils.setField(reader, "curEnabled", true);
        when(repo.existsById(anyString())).thenReturn(false);
    }

    private void stub(String keyPrefix, String json) {
        String key = keyPrefix + ACCOUNT + "::" + JULY;
        when(repo.findById(key)).thenReturn(Optional.of(
                new CachedData(key, json, LocalDateTime.now())));
    }

    /** BillOps writes this sentinel last; its absence must mean "no CUR data". */
    private void markIngested() {
        when(repo.existsById("AWS_CUR_STATE::" + ACCOUNT + "::" + JULY)).thenReturn(true);
    }

    // ── Gating ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no CUR data reported when the completeness sentinel is absent")
    void reportsNoDataWithoutSentinel() {
        stub("AWS_CUR_SVC_REGION::", SVC_REGION_JSON);

        assertThat(reader.hasCurData(ACCOUNT, JULY)).isFalse();
        // A half-written month must not be served as authoritative.
        assertThat(reader.readBreakdownSvc(ACCOUNT, JULY)).isEmpty();
        assertThat(reader.readSvcRegion(ACCOUNT, JULY)).isEmpty();
    }

    @Test
    @DisplayName("disabled flag suppresses every read so callers fall back to Cost Explorer")
    void disabledFlagSuppressesReads() {
        ReflectionTestUtils.setField(reader, "curEnabled", false);
        markIngested();
        stub("AWS_CUR_SVC_REGION::", SVC_REGION_JSON);
        stub("AWS_CUR_TOTAL::", "196.08");

        assertThat(reader.hasCurData(ACCOUNT, JULY)).isFalse();
        assertThat(reader.readMonthlyTotal(ACCOUNT, JULY)).isEmpty();
        assertThat(reader.readBreakdownSvc(ACCOUNT, JULY)).isEmpty();
    }

    // ── Shape mapping ────────────────────────────────────────────────────────

    @Test
    void readsReconciledMonthlyTotal() {
        markIngested();
        stub("AWS_CUR_TOTAL::", "196.08");

        assertThat(reader.readMonthlyTotal(ACCOUNT, JULY)).contains(196.08);
    }

    @Test
    @DisplayName("service breakdown folds CUR's service x region rows by service")
    void mapsServiceBreakdown() {
        markIngested();
        stub("AWS_CUR_SVC_REGION::", SVC_REGION_JSON);

        List<CostDto> svc = reader.readBreakdownSvc(ACCOUNT, JULY).orElseThrow();

        // EC2 appears in two regions and must be summed into one entry.
        assertThat(svc).extracting(CostDto::getName)
                .containsExactly("Amazon Elastic Compute Cloud", "AWS Data Transfer", "Tax");
        assertThat(svc.get(0).getAmount()).isEqualTo(140.50);
        // Sorted by cost descending, so the UI needs no re-sort.
        assertThat(svc).isSortedAccordingTo((a, b) -> Double.compare(b.getAmount(), a.getAmount()));
    }

    @Test
    void mapsRegionBreakdown() {
        markIngested();
        stub("AWS_CUR_SVC_REGION::", SVC_REGION_JSON);

        List<CostDto> rgn = reader.readBreakdownRgn(ACCOUNT, JULY).orElseThrow();

        assertThat(rgn).extracting(CostDto::getName)
                .containsExactly("us-east-1", "ap-south-1", "global");
        // 100.50 EC2 + 46.08 data transfer
        assertThat(rgn.get(0).getAmount()).isEqualTo(146.58);
    }

    @Test
    void mapsServiceRegionBreakdownDirectly() {
        markIngested();
        stub("AWS_CUR_SVC_REGION::", SVC_REGION_JSON);

        List<DetailedCostDto> rows = reader.readSvcRegion(ACCOUNT, JULY).orElseThrow();

        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).getService()).isEqualTo("Amazon Elastic Compute Cloud");
        assertThat(rows.get(0).getRegion()).isEqualTo("us-east-1");
        assertThat(rows.get(0).getCost()).isEqualTo(100.50);
    }

    @Test
    @DisplayName("daily cost survives, which a MONTHLY export could not provide")
    void mapsDailyCost() {
        markIngested();
        stub("AWS_CUR_DAILY::", DAILY_JSON);

        List<CostDataSnapshotService.DailyCostEntry> daily =
                reader.readDailyCost(ACCOUNT, JULY).orElseThrow();

        assertThat(daily).hasSize(2);
        assertThat(daily.get(0).getDate()).isEqualTo("2026-07-01");
        assertThat(daily.get(0).getAmount()).isEqualTo(60.00);
    }

    @Test
    @DisplayName("usage rows collapse across regions to match the Cost Explorer shape")
    void mapsSvcUsageCollapsingRegions() {
        markIngested();
        stub("AWS_CUR_SVC_USAGE::", SVC_USAGE_JSON);

        List<CostDataSnapshotService.SvcUsageEntry> usage =
                reader.readSvcUsage(ACCOUNT, JULY).orElseThrow();

        // EC2 BoxUsage exists in two regions; the CE shape has no region, so the two
        // must merge rather than appear twice.
        assertThat(usage).filteredOn(u -> "BoxUsage:t3.micro".equals(u.getUsageType()))
                .singleElement()
                .satisfies(u -> {
                    assertThat(u.getCost()).isEqualTo(120.50);
                    assertThat(u.getQuantity()).isEqualTo(1080.0);
                    assertThat(u.getUnit()).isEqualTo("Hrs");
                });
    }

    @Test
    @DisplayName("region detail stays available for resource-level attribution")
    void exposesRegionAwareUsageRows() {
        markIngested();
        stub("AWS_CUR_SVC_USAGE::", SVC_USAGE_JSON);

        List<CurSnapshotReader.CurSvcUsage> rows =
                reader.readSvcUsageWithRegion(ACCOUNT, JULY).orElseThrow();

        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(CurSnapshotReader.CurSvcUsage::getRegion)
                .contains("us-east-1", "ap-south-1");
    }

    @Test
    @DisplayName("CloudFront usage is filtered out of the usage rows, replacing the manual CSV upload")
    void mapsCloudFrontUsage() {
        markIngested();
        stub("AWS_CUR_SVC_USAGE::", SVC_USAGE_JSON);

        List<CostDataSnapshotService.CfUsageEntry> cf =
                reader.readCfUsage(ACCOUNT, JULY).orElseThrow();

        assertThat(cf).singleElement().satisfies(e -> {
            assertThat(e.getUsageType()).isEqualTo("US-DataTransfer-Out-Bytes");
            assertThat(e.getRegion()).isEqualTo("us-east-1");
            assertThat(e.getCost()).isEqualTo(12.34);
            assertThat(e.getQuantity()).isEqualTo(128.0);
            assertThat(e.getUnit()).isEqualTo("GB");
        });
    }

    // ── Invoice-accuracy behaviour ───────────────────────────────────────────

    @Test
    @DisplayName("negative credit lines are kept so the displayed total is not inflated above the bill")
    void keepsNegativeCreditLines() {
        markIngested();
        stub("AWS_CUR_SVC_REGION::", """
                [
                  {"service":"Amazon Elastic Compute Cloud","region":"us-east-1","cost":100.00},
                  {"service":"Credit","region":"global","cost":-15.25},
                  {"service":"Nothing","region":"global","cost":0.0}
                ]""");

        List<CostDto> svc = reader.readBreakdownSvc(ACCOUNT, JULY).orElseThrow();

        assertThat(svc).extracting(CostDto::getName).containsExactly("Amazon Elastic Compute Cloud", "Credit");
        assertThat(svc).extracting(CostDto::getAmount).containsExactly(100.00, -15.25);
        // Exact zeros add nothing to a breakdown and are dropped.
        assertThat(svc).extracting(CostDto::getName).doesNotContain("Nothing");
    }

    // ── Failure handling ─────────────────────────────────────────────────────

    @Test
    @DisplayName("unreadable JSON yields empty rather than propagating, so Cost Explorer still serves the page")
    void malformedJsonFallsBackToEmpty() {
        markIngested();
        stub("AWS_CUR_SVC_REGION::", "{ not valid json");

        assertThat(reader.readBreakdownSvc(ACCOUNT, JULY)).isEmpty();
        assertThat(reader.readSvcRegion(ACCOUNT, JULY)).isEmpty();
    }

    @Test
    void emptyAggregateTreatedAsAbsent() {
        markIngested();
        stub("AWS_CUR_SVC_REGION::", "[]");

        assertThat(reader.readBreakdownSvc(ACCOUNT, JULY)).isEmpty();
    }

    @Test
    void missingKeyYieldsEmpty() {
        markIngested();
        when(repo.findById(anyString())).thenReturn(Optional.empty());

        assertThat(reader.readMonthlyTotal(ACCOUNT, JULY)).isEmpty();
        assertThat(reader.readDailyCost(ACCOUNT, JULY)).isEmpty();
        assertThat(reader.readSvcUsage(ACCOUNT, JULY)).isEmpty();
        assertThat(reader.readCfUsage(ACCOUNT, JULY)).isEmpty();
    }

    @Test
    void nullAccountOrMonthIsSafe() {
        assertThat(reader.hasCurData(null, JULY)).isFalse();
        assertThat(reader.hasCurData(ACCOUNT, null)).isFalse();
    }
}
