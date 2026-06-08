package com.xammer.cloud.service;

import com.xammer.cloud.dto.AlertDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AlertEmailBuilder — pure HTML generation, no external dependencies.
 *
 * Covers:
 * - Digest email (multi-alert summary)
 * - Single alert email
 * - Severity color logic
 * - Type icons and labels
 * - XSS protection (HTML escaping)
 * - Null safety
 */
class AlertEmailBuilderTest {

    private AlertEmailBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AlertEmailBuilder();
    }

    // ── Digest email ──────────────────────────────────────────────────────────

    @Test
    void digestEmail_containsXamOpsBranding() {
        String html = builder.buildAlertDigestEmail(List.of(quotaAlert("CRITICAL")), "Acme", "123456789012");
        assertThat(html).containsIgnoringCase("XamOps");
        assertThat(html).containsIgnoringCase("Alert Digest");
    }

    @Test
    void digestEmail_containsAccountNameAndId() {
        String html = builder.buildAlertDigestEmail(List.of(quotaAlert("WARNING")), "Acme Corp", "999888777666");
        assertThat(html).contains("Acme Corp");
        assertThat(html).contains("999888777666");
    }

    @Test
    void digestEmail_showsTotalCount() {
        List<AlertDto> alerts = List.of(
                quotaAlert("CRITICAL"), anomalyAlert("WARNING"), securityAlert("CRITICAL"));
        String html = builder.buildAlertDigestEmail(alerts, "Acme", "123");
        assertThat(html).contains("3"); // total
        assertThat(html).contains("2"); // critical count
        assertThat(html).contains("1"); // warning count
    }

    @Test
    void digestEmail_groupsByType() {
        AlertDto quota = quotaAlert("CRITICAL");
        AlertDto security = securityAlert("WARNING");
        String html = builder.buildAlertDigestEmail(List.of(quota, security), "Acme", "123");
        assertThat(html).containsIgnoringCase("Quota");
        assertThat(html).containsIgnoringCase("Security");
    }

    @Test
    void digestEmail_quotaAlerts_showUsageBar() {
        AlertDto alert = quotaAlertWithUsage("WARNING", 80.0, 100.0);
        String html = builder.buildAlertDigestEmail(List.of(alert), "Acme", "123");
        assertThat(html).contains("80");
        assertThat(html).contains("100");
    }

    @Test
    void digestEmail_isValidHtml() {
        String html = builder.buildAlertDigestEmail(List.of(quotaAlert("CRITICAL")), "Acme", "123");
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("</html>");
        assertThat(html).contains("<body");
        assertThat(html).contains("</body>");
    }

    // ── Single alert email ────────────────────────────────────────────────────

    @Test
    void singleAlertEmail_containsAlertNameAndService() {
        AlertDto alert = quotaAlert("CRITICAL");
        String html = builder.buildSingleAlertEmail(alert, "Acme", "123456789012");
        assertThat(html).contains("VPC Limit");
        assertThat(html).contains("VPC");
    }

    @Test
    void singleAlertEmail_containsAccountAndRegion() {
        AlertDto alert = quotaAlert("WARNING");
        String html = builder.buildSingleAlertEmail(alert, "Acme Corp", "123456789012");
        assertThat(html).contains("Acme Corp");
        assertThat(html).contains("us-east-1");
    }

    @Test
    void singleAlertEmail_containsRecommendedAction() {
        String html = builder.buildSingleAlertEmail(quotaAlert("CRITICAL"), "Acme", "123");
        assertThat(html).containsIgnoringCase("Recommended Action");
        assertThat(html).containsIgnoringCase("quota");
    }

    @Test
    void singleAlertEmail_anomalyType_hasAnomalyRecommendation() {
        AlertDto alert = anomalyAlert("WARNING");
        String html = builder.buildSingleAlertEmail(alert, "Acme", "123");
        assertThat(html).containsIgnoringCase("cost");
    }

    @Test
    void singleAlertEmail_securityType_hasSecurityRecommendation() {
        AlertDto alert = securityAlert("CRITICAL");
        String html = builder.buildSingleAlertEmail(alert, "Acme", "123");
        assertThat(html).containsIgnoringCase("security");
    }

    // ── Severity colors ───────────────────────────────────────────────────────

    @Test
    void criticalAlert_usesDangerColor() {
        AlertDto alert = quotaAlert("CRITICAL");
        String html = builder.buildSingleAlertEmail(alert, "Acme", "123");
        assertThat(html).contains("#dc2626"); // CRITICAL_COLOR
    }

    @Test
    void warningAlert_usesWarningColor() {
        AlertDto alert = quotaAlert("WARNING");
        String html = builder.buildSingleAlertEmail(alert, "Acme", "123");
        assertThat(html).contains("#d97706"); // WARNING_COLOR
    }

    @Test
    void highSeverity_treatedAsCritical() {
        AlertDto alert = alertWithStatus("QUOTA", "HIGH");
        String html = builder.buildSingleAlertEmail(alert, "Acme", "123");
        assertThat(html).contains("#dc2626");
    }

    // ── XSS protection ────────────────────────────────────────────────────────

    @Test
    void xss_accountNameEscaped() {
        String html = builder.buildAlertDigestEmail(
                List.of(quotaAlert("CRITICAL")), "<script>alert('xss')</script>", "123");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void xss_alertNameEscaped() {
        AlertDto alert = new AlertDto();
        alert.setType("QUOTA");
        alert.setName("<img src=x onerror=alert(1)>");
        alert.setService("VPC");
        alert.setRegion("us-east-1");
        alert.setStatus("WARNING");
        alert.setDescription("desc");

        String html = builder.buildAlertDigestEmail(List.of(alert), "Acme", "123");
        assertThat(html).doesNotContain("<img src=x");
        assertThat(html).contains("&lt;img");
    }

    @Test
    void xss_accountIdEscaped() {
        String html = builder.buildAlertDigestEmail(
                List.of(quotaAlert("CRITICAL")), "Acme", "<b>XSS</b>");
        assertThat(html).doesNotContain("<b>");
    }

    // ── Null safety ───────────────────────────────────────────────────────────

    @Test
    void emptyAlertList_rendersGracefully() {
        String html = builder.buildAlertDigestEmail(List.of(), "Acme", "123");
        assertThat(html).isNotBlank();
        assertThat(html).contains("Total");
    }

    @Test
    void nullAlertFields_handledGracefully() {
        AlertDto alert = new AlertDto();
        alert.setType("QUOTA");
        // name, service, region, status, description all null

        String html = builder.buildSingleAlertEmail(alert, "Acme", "123");
        assertThat(html).isNotBlank();
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    @Test
    void allEmails_containFooter() {
        String html = builder.buildAlertDigestEmail(List.of(quotaAlert("CRITICAL")), "Acme", "123");
        assertThat(html).containsIgnoringCase("XamOps");
        // Footer contains "Cloud Cost Management" as part of the tagline
        assertThat(html).containsIgnoringCase("Cloud Cost Management");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AlertDto quotaAlert(String status) {
        return alertFull("QUOTA", "VPC Limit", "VPC is near limit", "VPC", "us-east-1", status, 80, 100);
    }

    private AlertDto quotaAlertWithUsage(String status, double usage, double limit) {
        return alertFull("QUOTA", "EC2 Limit", "EC2 near limit", "EC2", "us-east-1", status, usage, limit);
    }

    private AlertDto anomalyAlert(String status) {
        return alertFull("ANOMALY", "EC2 Spike", "Unexpected EC2 cost increase", "EC2", "us-west-2", status, 0, 0);
    }

    private AlertDto securityAlert(String status) {
        return alertFull("SECURITY", "S3 Bucket Public", "S3 bucket is publicly accessible", "S3", "us-east-1", status, 0, 0);
    }

    private AlertDto alertWithStatus(String type, String status) {
        return alertFull(type, "Test Alert", "Test description", "EC2", "us-east-1", status, 0, 0);
    }

    private AlertDto alertFull(String type, String name, String desc, String service,
                                String region, String status, double usage, double limit) {
        AlertDto dto = new AlertDto();
        dto.setType(type);
        dto.setName(name);
        dto.setDescription(desc);
        dto.setService(service);
        dto.setRegion(region);
        dto.setStatus(status);
        dto.setUsage(usage);
        dto.setLimit(limit);
        return dto;
    }
}
