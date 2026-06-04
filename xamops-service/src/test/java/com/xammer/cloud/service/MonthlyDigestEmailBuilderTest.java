package com.xammer.cloud.service;

import com.xammer.cloud.dto.AwsSecurityFinding;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonthlyDigestEmailBuilderTest {

    private MonthlyDigestEmailBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new MonthlyDigestEmailBuilder();
    }

    private FinOpsReportDto buildReport(double mtdSpend, double lastMonthSpend, double savings) {
        FinOpsReportDto report = mock(FinOpsReportDto.class);
        FinOpsReportDto.Kpis kpis = mock(FinOpsReportDto.Kpis.class);
        FinOpsReportDto.CostBreakdown breakdown = mock(FinOpsReportDto.CostBreakdown.class);

        when(report.getKpis()).thenReturn(kpis);
        when(kpis.getMonthToDateSpend()).thenReturn(mtdSpend);
        when(kpis.getLastMonthSpend()).thenReturn(lastMonthSpend);
        when(kpis.getPotentialSavings()).thenReturn(savings);

        Map<String, Object> svc = new HashMap<>();
        svc.put("service", "EC2");
        svc.put("cost", 6240.0);
        when(report.getCostBreakdown()).thenReturn(breakdown);
        when(breakdown.getByService()).thenReturn(List.of(svc));
        when(report.getWastedResources()).thenReturn(List.of());
        return report;
    }

    @Test
    void heroContainsAccountNameAndDateRange() {
        FinOpsReportDto report = buildReport(14820.0, 13685.0, 2380.0);

        String html = builder.build(report, List.of(), List.of(), List.of(),
                "prod-main", "June 1 – June 30, 2026");

        assertThat(html).contains("prod-main");
        assertThat(html).contains("June 1 – June 30, 2026");
        assertThat(html).contains("XAMOPS");
        assertThat(html).contains("Monthly Cloud Digest");
    }

    @Test
    void statsStripShowsSpendAndSavings() {
        FinOpsReportDto report = buildReport(14820.0, 13685.0, 2380.0);

        String html = builder.build(report, List.of(), List.of(), List.of(),
                "prod-main", "June 1 – June 30, 2026");

        assertThat(html).contains("$14,820");
        assertThat(html).contains("$2,380");
    }

    @Test
    void securityRowShowsCorrectSeverityCounts() {
        AwsSecurityFinding critical = mock(AwsSecurityFinding.class);
        when(critical.getSeverity()).thenReturn("CRITICAL");
        when(critical.getTitle()).thenReturn("S3 bucket public");
        when(critical.getResourceId()).thenReturn("my-bucket");
        when(critical.getRegion()).thenReturn("us-east-1");

        AwsSecurityFinding high = mock(AwsSecurityFinding.class);
        when(high.getSeverity()).thenReturn("HIGH");
        when(high.getTitle()).thenReturn("MFA not enabled");
        when(high.getResourceId()).thenReturn("iam-user");
        when(high.getRegion()).thenReturn("global");

        FinOpsReportDto report = buildReport(0, 0, 0);
        String html = builder.build(report, List.of(critical, high), List.of(), List.of(),
                "prod-main", "June 2026");

        assertThat(html).contains("1 Critical");
        assertThat(html).contains("1 High");
        assertThat(html).contains("0 Medium");
        assertThat(html).contains("0 Low");
        assertThat(html).contains("S3 bucket public");
    }

    @Test
    void wasteRowShowsResourcesWithSavings() {
        DashboardData.WastedResource waste = mock(DashboardData.WastedResource.class);
        when(waste.getResourceId()).thenReturn("i-0a1b2c3d");
        when(waste.getResourceType()).thenReturn("EC2");
        when(waste.getReason()).thenReturn("Idle CPU");
        when(waste.getMonthlySavings()).thenReturn(340.0);

        FinOpsReportDto report = mock(FinOpsReportDto.class);
        FinOpsReportDto.Kpis kpis = mock(FinOpsReportDto.Kpis.class);
        FinOpsReportDto.CostBreakdown breakdown = mock(FinOpsReportDto.CostBreakdown.class);
        when(report.getKpis()).thenReturn(kpis);
        when(kpis.getMonthToDateSpend()).thenReturn(0.0);
        when(kpis.getLastMonthSpend()).thenReturn(0.0);
        when(kpis.getPotentialSavings()).thenReturn(0.0);
        when(report.getCostBreakdown()).thenReturn(breakdown);
        when(breakdown.getByService()).thenReturn(List.of());
        when(report.getWastedResources()).thenReturn(List.of(waste));

        String html = builder.build(report, List.of(), List.of(), List.of(),
                "prod-main", "June 2026");

        assertThat(html).contains("i-0a1b2c3d");
        assertThat(html).contains("Idle CPU");
        assertThat(html).contains("$340");
    }

    @Test
    void resizeRowShowsInstanceAndRecommendation() {
        XamOpsRightsizingRecommendation rec = mock(XamOpsRightsizingRecommendation.class);
        when(rec.getInstanceId()).thenReturn("i-0abc1234");
        when(rec.getCurrentInstance()).thenReturn("m5.2xlarge");
        when(rec.getIntelRecommendation()).thenReturn("m5.large");
        when(rec.getApproxCostSavings()).thenReturn("$420");
        when(rec.getRegion()).thenReturn("us-east-1");

        FinOpsReportDto report = buildReport(0, 0, 0);
        String html = builder.build(report, List.of(), List.of(rec), List.of(),
                "prod-main", "June 2026");

        assertThat(html).contains("i-0abc1234");
        assertThat(html).contains("m5.2xlarge");
        assertThat(html).contains("m5.large");
        assertThat(html).contains("$420");
    }

    @Test
    void diskRowAbsentWhenNoDiskRecommendations() {
        FinOpsReportDto report = buildReport(0, 0, 0);

        String html = builder.build(report, List.of(), List.of(), List.of(),
                "prod-main", "June 2026");

        assertThat(html).doesNotContain("Disk");
    }

    @Test
    void diskRowPresentWhenFlaggedVolumesExist() {
        Map<String, Object> vol = new HashMap<>();
        vol.put("volumeId", "vol-0abc123");
        vol.put("region", "us-east-1");
        vol.put("sizeGb", 30);
        vol.put("usedPercent", 95.0);
        vol.put("recommendation", "EXPAND");

        FinOpsReportDto report = buildReport(0, 0, 0);
        String html = builder.build(report, List.of(), List.of(), List.of(vol),
                "prod-main", "June 2026");

        assertThat(html).contains("vol-0abc123");
        assertThat(html).contains("95");
        assertThat(html).contains("Expand");
    }
}
