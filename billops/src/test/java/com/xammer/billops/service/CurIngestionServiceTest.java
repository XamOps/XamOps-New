package com.xammer.billops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.dto.cur.CurAggregates;
import com.xammer.billops.repository.BillOpsCachedDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the CUR parsing and aggregation rules.
 *
 * These assertions are about billing correctness rather than plumbing: the totals
 * produced here are what BillOps puts on a customer invoice, so the cases below are
 * the ones where getting it wrong produces a number that silently disagrees with the
 * real AWS bill.
 */
class CurIngestionServiceTest {

    @Mock
    private AwsClientProvider awsClientProvider;
    @Mock
    private BillOpsCachedDataRepository cachedDataRepository;

    private CurIngestionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CurIngestionService(awsClientProvider, cachedDataRepository, new ObjectMapper());
    }

    private CurAggregates fold(String csv) throws Exception {
        CurIngestionService.Accumulator acc = new CurIngestionService.Accumulator();
        service.foldFile(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), acc, "test.csv");
        return acc.toAggregates();
    }

    // ── CUR 2.0 (snake_case) schema ──────────────────────────────────────────

    @Test
    void parsesCur2SchemaAndSumsTotal() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost,pricing_unit",
                "2026-07-01T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,0.2496,Hrs",
                "2026-07-02T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,0.2496,Hrs");

        CurAggregates agg = fold(csv);

        assertThat(agg.getRowCount()).isEqualTo(2);
        assertThat(agg.getTotal()).isEqualTo(0.4992);
        assertThat(agg.getDaily()).hasSize(2);
        assertThat(agg.getSvcRegion()).hasSize(1);
        assertThat(agg.getSvcRegion().get(0).getService()).isEqualTo("Amazon Elastic Compute Cloud");
        assertThat(agg.getSvcRegion().get(0).getRegion()).isEqualTo("us-east-1");
    }

    /**
     * A customer who already had a legacy CUR configured before this shipped must be
     * readable without re-onboarding, so both column dialects have to resolve.
     */
    @Test
    void parsesLegacyCurSlashSchema() throws Exception {
        String csv = String.join("\n",
                "lineItem/UsageStartDate,lineItem/ProductCode,product/ProductName,product/region,lineItem/UsageType,lineItem/UsageAmount,lineItem/UnblendedCost",
                "2026-07-01T00:00:00Z,AmazonS3,Amazon Simple Storage Service,eu-west-1,TimedStorage-ByteHrs,100,1.50");

        CurAggregates agg = fold(csv);

        assertThat(agg.getTotal()).isEqualTo(1.50);
        assertThat(agg.getSvcRegion().get(0).getService()).isEqualTo("Amazon Simple Storage Service");
        assertThat(agg.getSvcRegion().get(0).getRegion()).isEqualTo("eu-west-1");
    }

    // ── Invoice-accuracy rules ───────────────────────────────────────────────

    /**
     * The whole reason for using CUR over Cost Explorer. Tax, fees, credits and
     * refunds are all real invoice lines; excluding any of them is what makes a
     * total stop matching the bill. Credits are negative and must reduce the total.
     */
    @Test
    void totalIncludesTaxFeesAndNegativeCredits() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_line_item_type,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost",
                "2026-07-01T00:00:00Z,Usage,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,100.00",
                "2026-07-01T00:00:00Z,Tax,,Tax,,,0,9.50",
                "2026-07-01T00:00:00Z,Fee,AWSSupportBusiness,AWS Support (Business),,Dollar,1,29.00",
                "2026-07-01T00:00:00Z,Credit,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,0,-15.25");

        CurAggregates agg = fold(csv);

        // 100.00 + 9.50 + 29.00 - 15.25
        assertThat(agg.getTotal()).isEqualTo(123.25);
        assertThat(agg.getRowCount()).isEqualTo(4);
    }

    /**
     * Data transfer out is the charge that prompted this work: it must survive
     * parsing as its own usage type rather than being folded away.
     */
    @Test
    void retainsDataTransferOutAsItsOwnUsageType() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost,pricing_unit",
                "2026-07-01T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,0.25,Hrs",
                "2026-07-01T00:00:00Z,AWSDataTransfer,AWS Data Transfer,us-east-1,USE1-DataTransfer-Out-Bytes,512,46.08,GB");

        CurAggregates agg = fold(csv);

        assertThat(agg.getSvcUsage())
                .anySatisfy(u -> {
                    assertThat(u.getUsageType()).isEqualTo("USE1-DataTransfer-Out-Bytes");
                    assertThat(u.getCost()).isEqualTo(46.08);
                    assertThat(u.getQuantity()).isEqualTo(512.0);
                    assertThat(u.getUnit()).isEqualTo("GB");
                });
        // Highest-cost entry first, so the breakdown needs no client-side sort.
        assertThat(agg.getSvcUsage().get(0).getCost()).isEqualTo(46.08);
    }

    /** Rows are folded across days and usage types, not just passed through. */
    @Test
    void aggregatesAcrossDaysAndUsageTypes() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost,pricing_unit",
                "2026-07-01T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,1.00,Hrs",
                "2026-07-01T06:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,2.00,Hrs",
                "2026-07-02T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,4.00,Hrs");

        CurAggregates agg = fold(csv);

        assertThat(agg.getDaily()).hasSize(2);
        assertThat(agg.getDaily().get(0).getDate()).isEqualTo("2026-07-01");
        assertThat(agg.getDaily().get(0).getAmount()).isEqualTo(3.00);
        assertThat(agg.getDaily().get(1).getAmount()).isEqualTo(4.00);
        // One service+region+usageType key, so one usage row carrying the full cost.
        assertThat(agg.getSvcUsage()).hasSize(1);
        assertThat(agg.getSvcUsage().get(0).getCost()).isEqualTo(7.00);
        assertThat(agg.getSvcUsage().get(0).getQuantity()).isEqualTo(72.0);
    }

    /**
     * Region is kept per usage row, which is what lets resources nest under their
     * real region — the gap that forces the Cost Explorer snapshot path to ignore
     * its region argument.
     */
    @Test
    void keepsRegionOnEachUsageRow() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost",
                "2026-07-01T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,1.00",
                "2026-07-01T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,ap-south-1,BoxUsage:t3.micro,24,2.00");

        CurAggregates agg = fold(csv);

        assertThat(agg.getSvcRegion()).hasSize(2);
        assertThat(agg.getSvcUsage()).hasSize(2);
        assertThat(agg.getSvcUsage()).extracting(CurAggregates.SvcUsageEntry::getRegion)
                .containsExactlyInAnyOrder("us-east-1", "ap-south-1");
    }

    // ── Edge cases seen in real exports ──────────────────────────────────────

    /** Global charges (tax, support) carry no region code. */
    @Test
    void labelsMissingRegionAsGlobal() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost",
                "2026-07-01T00:00:00Z,AWSSupportBusiness,AWS Support (Business),,Dollar,1,29.00");

        CurAggregates agg = fold(csv);

        assertThat(agg.getSvcRegion().get(0).getRegion()).isEqualTo("global");
    }

    /**
     * Tax and Savings Plan rows often leave the product name blank, so the product
     * code is the fallback — normalised to the same display name the populated rows
     * use, so both fold into one service rather than appearing twice.
     */
    @Test
    void fallsBackToProductCodeWhenNameIsBlank() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost",
                "2026-07-01T00:00:00Z,AmazonEC2,,us-east-1,BoxUsage:t3.micro,24,1.00");

        CurAggregates agg = fold(csv);

        assertThat(agg.getSvcRegion().get(0).getService()).isEqualTo("Amazon Elastic Compute Cloud");
    }

    /** Quoted commas inside a field must not shift column positions. */
    @Test
    void handlesQuotedCommasInFields() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost",
                "2026-07-01T00:00:00Z,AmazonRDS,\"Amazon RDS, PostgreSQL\",us-east-1,InstanceUsage:db.t3.micro,24,5.00");

        CurAggregates agg = fold(csv);

        assertThat(agg.getTotal()).isEqualTo(5.00);
        assertThat(agg.getSvcRegion().get(0).getService()).isEqualTo("Amazon RDS, PostgreSQL");
    }

    /**
     * Rows with neither meaningful cost nor any quantity are dropped from the usage
     * breakdown to keep the stored aggregate bounded, but the monthly total is summed
     * before that filter — so the reconciled headline figure still includes them.
     */
    @Test
    void noiseFloorTrimsValuelessUsageRowsButNotTheTotal() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost",
                "2026-07-01T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,10.00",
                // No cost and no quantity — nothing to bill or display.
                "2026-07-01T00:00:00Z,AWSKMS,AWS Key Management Service,us-east-1,KMS-Requests,0,0.00001");

        CurAggregates agg = fold(csv);

        assertThat(agg.getTotal()).isEqualTo(10.00001);
        assertThat(agg.getSvcUsage()).hasSize(1);
        assertThat(agg.getSvcUsage().get(0).getUsageType()).isEqualTo("BoxUsage:t3.micro");
    }

    /**
     * BillOps prices CloudFront from private per-GB rates, so the GB figure is what
     * generates the invoice line — not AWS's cost. CloudFront also has a 1 TB/month
     * free data-transfer tier, whose rows arrive with real quantity and zero cost.
     * Filtering on cost alone would discard up to a terabyte of billable transfer a
     * month.
     */
    @Test
    void keepsZeroCostRowsThatStillCarryBillableQuantity() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost,pricing_unit",
                // Free-tier CloudFront transfer: 500 GB delivered, nothing charged by AWS.
                "2026-07-01T00:00:00Z,AmazonCloudFront,Amazon CloudFront,,US-DataTransfer-Out-Bytes,500,0.0,GB");

        CurAggregates agg = fold(csv);

        assertThat(agg.getSvcUsage()).singleElement().satisfies(u -> {
            assertThat(u.getService()).isEqualTo("Amazon CloudFront");
            assertThat(u.getUsageType()).isEqualTo("US-DataTransfer-Out-Bytes");
            assertThat(u.getQuantity()).isEqualTo(500.0);
            assertThat(u.getCost()).isZero();
            assertThat(u.getUnit()).isEqualTo("GB");
        });
    }

    /**
     * Geography drives the private per-GB rate, and for CloudFront it lives in the
     * usage-type prefix rather than the region column — CloudFront is a global
     * service, so product_region_code is typically blank.
     */
    @Test
    void preservesCloudFrontGeographyPrefixPerUsageType() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost,pricing_unit",
                "2026-07-01T00:00:00Z,AmazonCloudFront,Amazon CloudFront,,US-DataTransfer-Out-Bytes,100,8.50,GB",
                "2026-07-01T00:00:00Z,AmazonCloudFront,Amazon CloudFront,,IN-DataTransfer-Out-Bytes,100,17.00,GB",
                "2026-07-01T00:00:00Z,AmazonCloudFront,Amazon CloudFront,,US-Requests-Tier2-HTTPS,20000,0.24,Requests");

        CurAggregates agg = fold(csv);

        // Each geography must stay separate — India and US bill at different rates.
        assertThat(agg.getSvcUsage()).extracting(CurAggregates.SvcUsageEntry::getUsageType)
                .containsExactlyInAnyOrder(
                        "US-DataTransfer-Out-Bytes",
                        "IN-DataTransfer-Out-Bytes",
                        "US-Requests-Tier2-HTTPS");
        // Requests are not GB; the unit must survive so a per-GB rate is never applied to them.
        assertThat(agg.getSvcUsage())
                .filteredOn(u -> u.getUsageType().contains("Requests"))
                .singleElement()
                .satisfies(u -> assertThat(u.getUnit()).isEqualTo("Requests"));
    }

    /**
     * A file with no recognisable cost column is skipped rather than counted as a
     * zero-cost month, which would present an empty invoice as reconciled.
     */
    @Test
    void skipsFileWithNoRecognisableCostColumn() throws Exception {
        String csv = String.join("\n",
                "some_other_column,and_another",
                "value1,value2");

        CurAggregates agg = fold(csv);

        assertThat(agg.getRowCount()).isZero();
        assertThat(agg.getTotal()).isZero();
        assertThat(agg.getSvcRegion()).isEmpty();
    }

    @Test
    void handlesEmptyFile() throws Exception {
        CurAggregates agg = fold("");

        assertThat(agg.getRowCount()).isZero();
        assertThat(agg.getTotal()).isZero();
    }

    /** A malformed numeric must not abort the whole file. */
    @Test
    void treatsUnparseableCostAsZeroWithoutFailing() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost",
                "2026-07-01T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,NOT_A_NUMBER",
                "2026-07-01T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,3.00");

        CurAggregates agg = fold(csv);

        assertThat(agg.getRowCount()).isEqualTo(2);
        assertThat(agg.getTotal()).isEqualTo(3.00);
    }

    /** Multiple files in one period accumulate rather than overwrite. */
    @Test
    void accumulatesAcrossMultipleFiles() throws Exception {
        String header = "line_item_usage_start_date,line_item_product_code,product_product_name,"
                + "product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost";
        CurIngestionService.Accumulator acc = new CurIngestionService.Accumulator();

        service.foldFile(new ByteArrayInputStream((header
                + "\n2026-07-01T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,4.00")
                .getBytes(StandardCharsets.UTF_8)), acc, "part-1.csv");
        service.foldFile(new ByteArrayInputStream((header
                + "\n2026-07-02T00:00:00Z,AmazonEC2,Amazon Elastic Compute Cloud,us-east-1,BoxUsage:t3.micro,24,6.00")
                .getBytes(StandardCharsets.UTF_8)), acc, "part-2.csv");

        CurAggregates agg = acc.toAggregates();

        assertThat(agg.getRowCount()).isEqualTo(2);
        assertThat(agg.getTotal()).isEqualTo(10.00);
        assertThat(agg.getDaily()).hasSize(2);
    }

    // ── Service-name normalisation (invoice routing depends on this) ──────────

    /**
     * InvoiceManagementService splits out a separate CloudFront invoice row and
     * applies per-service discounts by exact-matching "Amazon CloudFront". CUR leaves
     * product_product_name empty on some line item types, where the fallback product
     * code is "AmazonCloudFront" with no space — which would land those costs in the
     * standard consumption group at the wrong discount rate.
     */
    @Test
    void normalisesCloudFrontProductCodeToTheInvoiceServiceName() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost",
                // product_product_name deliberately blank, forcing the code fallback
                "2026-07-01T00:00:00Z,AmazonCloudFront,,,US-DataTransfer-Out-Bytes,128,12.34");

        CurAggregates agg = fold(csv);

        assertThat(agg.getSvcRegion().get(0).getService()).isEqualTo("Amazon CloudFront");
        assertThat(agg.getSvcUsage().get(0).getService()).isEqualTo("Amazon CloudFront");
    }

    @Test
    void cloudFrontRowsMergeRegardlessOfWhichColumnNamedThem() throws Exception {
        String csv = String.join("\n",
                "line_item_usage_start_date,line_item_product_code,product_product_name,product_region_code,line_item_usage_type,line_item_usage_amount,line_item_unblended_cost",
                "2026-07-01T00:00:00Z,AmazonCloudFront,Amazon CloudFront,,US-DataTransfer-Out-Bytes,100,10.00",
                "2026-07-02T00:00:00Z,AmazonCloudFront,,,US-DataTransfer-Out-Bytes,28,2.34");

        CurAggregates agg = fold(csv);

        // Both rows must fold into one service, or the invoice would show CloudFront twice
        // under two different names and discount only one of them.
        assertThat(agg.getSvcRegion()).hasSize(1);
        assertThat(agg.getSvcRegion().get(0).getService()).isEqualTo("Amazon CloudFront");
        assertThat(agg.getSvcRegion().get(0).getCost()).isEqualTo(12.34);
    }

    @Test
    void normalisesOtherKnownProductCodes() {
        assertThat(CurIngestionService.normaliseServiceName("AmazonEC2"))
                .isEqualTo("Amazon Elastic Compute Cloud");
        assertThat(CurIngestionService.normaliseServiceName("AWSDataTransfer"))
                .isEqualTo("AWS Data Transfer");
        // Already-friendly names pass through untouched.
        assertThat(CurIngestionService.normaliseServiceName("Amazon CloudFront"))
                .isEqualTo("Amazon CloudFront");
        // Unknown codes are preserved rather than guessed at.
        assertThat(CurIngestionService.normaliseServiceName("AWSSomeNewService"))
                .isEqualTo("AWSSomeNewService");
        assertThat(CurIngestionService.normaliseServiceName("  ")).isEqualTo("Other");
    }

    // ── Configuration ────────────────────────────────────────────────────────

    /** Must stay in step with the nested CUR CloudFormation template. */
    @Test
    void conventionalBucketMatchesTemplateNaming() {
        assertThat(CurIngestionService.conventionalBucket("123456789012"))
                .isEqualTo("xamops-cur-123456789012");
        assertThat(CurIngestionService.CONVENTIONAL_PREFIX).isEqualTo("cur2");
    }

    @Test
    void keySchemeDoesNotCollideWithCostExplorerSnapshotKeys() {
        java.time.YearMonth ym = java.time.YearMonth.of(2026, 7);

        assertThat(CurIngestionService.keyTotal("123", ym)).isEqualTo("AWS_CUR_TOTAL::123::2026-07");
        assertThat(CurIngestionService.keyState("123", ym)).isEqualTo("AWS_CUR_STATE::123::2026-07");

        // xamops-service owns AWS_MONTHLY_* / AWS_DAILY_COST / AWS_CF_USAGE in the same
        // shared table. Overlapping a prefix would have BillOps overwrite the data the
        // XamOps cost pages read.
        assertThat(CurIngestionService.keyTotal("123", ym)).doesNotStartWith("AWS_MONTHLY_");
        assertThat(CurIngestionService.keyDaily("123", ym)).doesNotStartWith("AWS_DAILY_COST");
        assertThat(CurIngestionService.keySvcUsage("123", ym)).doesNotStartWith("AWS_MONTHLY_");
        assertThat(CurIngestionService.keySvcRegion("123", ym)).doesNotStartWith("AWS_MONTHLY_");
    }
}
