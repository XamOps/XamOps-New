package com.xammer.cloud.service;

import com.xammer.cloud.dto.DetailedCostDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for FinOpsReportEmailBuilder — pure HTML generation, no external deps.
 */
class FinOpsReportEmailBuilderTest {

    private FinOpsReportEmailBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new FinOpsReportEmailBuilder();
    }

    // ── buildProfessionalReportEmail ──────────────────────────────────────────

    @Test
    void reportEmail_isValidHtml() {
        String html = builder.buildProfessionalReportEmail(
                List.of(cost("EC2", "us-east-1", 500.0)),
                "Acme Corp", "Daily", "2026-06-08", "AWS");
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("</html>");
    }

    @Test
    void reportEmail_containsAccountNameAndFrequency() {
        String html = builder.buildProfessionalReportEmail(
                List.of(cost("EC2", "us-east-1", 500.0)),
                "Acme Corp", "Weekly", "2026-06-01", "AWS");
        assertThat(html).contains("Acme Corp");
        assertThat(html).containsIgnoringCase("Weekly");
    }

    @Test
    void reportEmail_containsServiceAndCostData() {
        String html = builder.buildProfessionalReportEmail(
                List.of(cost("RDS", "ap-south-1", 250.0)),
                "Acme", "Daily", "2026-06-08", "AWS");
        assertThat(html).contains("RDS");
        assertThat(html).contains("ap-south-1");
        assertThat(html).contains("250");
    }

    @Test
    void reportEmail_showsTotalCost() {
        List<DetailedCostDto> data = List.of(
                cost("EC2", "us-east-1", 500.0),
                cost("RDS", "us-east-1", 250.0));
        String html = builder.buildProfessionalReportEmail(data, "Acme", "Monthly", "June 2026", "AWS");
        assertThat(html).contains("750");  // total
    }

    @Test
    void reportEmail_showsServiceCount() {
        List<DetailedCostDto> data = List.of(
                cost("EC2", "us-east-1", 100),
                cost("EC2", "us-west-2", 100),  // same service, different region
                cost("RDS", "us-east-1", 100));
        String html = builder.buildProfessionalReportEmail(data, "Acme", "Daily", "2026-06-08", "AWS");
        // 2 distinct services
        assertThat(html).contains("2");
    }

    @Test
    void reportEmail_emptyData_showsNoDataMessage() {
        String html = builder.buildProfessionalReportEmail(
                List.of(), "Acme", "Daily", "2026-06-08", "AWS");
        assertThat(html).containsIgnoringCase("No cost data");
    }

    @Test
    void reportEmail_nullData_showsNoDataMessage() {
        String html = builder.buildProfessionalReportEmail(
                null, "Acme", "Daily", "2026-06-08", "AWS");
        assertThat(html).containsIgnoringCase("No cost data");
    }

    // ── Currency symbol based on provider ────────────────────────────────────

    @Test
    void awsReport_usesDollarSign() {
        String html = builder.buildProfessionalReportEmail(
                List.of(cost("EC2", "us-east-1", 100)), "Acme", "Daily", "2026-06-08", "AWS");
        assertThat(html).contains("$");
        assertThat(html).doesNotContain("₹");
    }

    @Test
    void gcpReport_usesRupeeSymbol() {
        String html = builder.buildProfessionalReportEmail(
                List.of(cost("Compute", "asia-south1", 5000)), "Acme", "Daily", "2026-06-08", "GCP");
        assertThat(html).contains("₹");
    }

    @Test
    void nullProvider_usesDollarSign() {
        String html = builder.buildProfessionalReportEmail(
                List.of(cost("EC2", "us-east-1", 100)), "Acme", "Daily", "2026-06-08", null);
        assertThat(html).contains("$");
    }

    // ── Provider badge ────────────────────────────────────────────────────────

    @Test
    void awsProvider_showsAwsBadge() {
        String html = builder.buildProfessionalReportEmail(
                List.of(cost("EC2", "us-east-1", 100)), "Acme", "Daily", "2026-06-08", "AWS");
        assertThat(html).contains("AWS");
    }

    @Test
    void gcpProvider_showsGcpBadge() {
        String html = builder.buildProfessionalReportEmail(
                List.of(cost("Compute", "us-central1", 100)), "Acme", "Daily", "2026-06-08", "GCP");
        assertThat(html).contains("GCP");
    }

    @Test
    void azureProvider_showsAzureBadge() {
        String html = builder.buildProfessionalReportEmail(
                List.of(cost("VMs", "eastus", 100)), "Acme", "Daily", "2026-06-08", "AZURE");
        assertThat(html).containsIgnoringCase("Azure");
    }

    // ── Top services summary (shown when >3 services) ────────────────────────

    @Test
    void topServiceSummary_shownWhenMoreThan3Services() {
        List<DetailedCostDto> data = List.of(
                cost("EC2", "us-east-1", 500),
                cost("RDS", "us-east-1", 300),
                cost("S3", "us-east-1", 100),
                cost("Lambda", "us-east-1", 50));  // 4 distinct services
        String html = builder.buildProfessionalReportEmail(data, "Acme", "Daily", "2026-06-08", "AWS");
        assertThat(html).containsIgnoringCase("Top Spending");
    }

    @Test
    void topServiceSummary_notShownWhenOnly3Services() {
        List<DetailedCostDto> data = List.of(
                cost("EC2", "us-east-1", 500),
                cost("RDS", "us-east-1", 300),
                cost("S3", "us-east-1", 100));
        String html = builder.buildProfessionalReportEmail(data, "Acme", "Daily", "2026-06-08", "AWS");
        assertThat(html).doesNotContain("Top Spending");
    }

    // ── buildSimpleReportEmail delegates to buildProfessionalReportEmail ──────

    @Test
    void simpleEmail_containsSameContent() {
        List<DetailedCostDto> data = List.of(cost("EC2", "us-east-1", 100));
        String simple = builder.buildSimpleReportEmail(data, "Acme", "Daily", "2026-06-08");
        String pro    = builder.buildProfessionalReportEmail(data, "Acme", "Daily", "2026-06-08", null);
        assertThat(simple).isEqualTo(pro);
    }

    // ── Schedule confirmation email ───────────────────────────────────────────

    @Test
    void scheduleConfirmation_containsDetails() {
        String html = builder.buildScheduleConfirmationEmail(
                "Acme Corp", "123456789012", "admin@acme.com", "WEEKLY", "AWS");
        assertThat(html).contains("Acme Corp");
        assertThat(html).contains("123456789012");
        assertThat(html).contains("admin@acme.com");
        assertThat(html).containsIgnoringCase("Weekly");
    }

    @Test
    void scheduleConfirmation_dailyDescription() {
        String html = builder.buildScheduleConfirmationEmail(
                "Acme", "123", "a@b.com", "DAILY", "AWS");
        assertThat(html).containsIgnoringCase("every day");
    }

    @Test
    void scheduleConfirmation_weeklyDescription() {
        String html = builder.buildScheduleConfirmationEmail(
                "Acme", "123", "a@b.com", "WEEKLY", "AWS");
        assertThat(html).containsIgnoringCase("Friday");
    }

    @Test
    void scheduleConfirmation_monthlyDescription() {
        String html = builder.buildScheduleConfirmationEmail(
                "Acme", "123", "a@b.com", "MONTHLY", "AWS");
        assertThat(html).containsIgnoringCase("30th");
    }

    @Test
    void scheduleConfirmation_isValidHtml() {
        String html = builder.buildScheduleConfirmationEmail(
                "Acme", "123", "a@b.com", "DAILY", "AWS");
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("</html>");
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    @Test
    void reportEmail_containsXamOpsFooter() {
        String html = builder.buildProfessionalReportEmail(
                List.of(cost("EC2", "us-east-1", 100)), "Acme", "Daily", "2026-06-08", "AWS");
        assertThat(html).containsIgnoringCase("XamOps");
        assertThat(html).containsIgnoringCase("All rights reserved");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private DetailedCostDto cost(String service, String region, double amount) {
        DetailedCostDto dto = new DetailedCostDto();
        dto.setService(service);
        dto.setRegion(region);
        dto.setCost(amount);
        return dto;
    }
}
