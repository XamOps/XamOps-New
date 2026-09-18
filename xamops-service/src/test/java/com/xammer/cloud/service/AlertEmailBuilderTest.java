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

    // ── New-alerts digest (bounded) ────────────────────────────────────────────
    //
    // The old digest itemised the entire active set with no cap. A GCP customer with 2000
    // alerts received a mail listing all 2000 every time anything changed — past the size at
    // which Gmail clips a message. These tests pin the cap and the total-count reporting.

    private static final int CAP = 20;

    @Test
    void newAlertsDigest_leadsWithTheNewCount() {
        String html = builder.buildNewAlertsDigestEmail(
                List.of(quotaAlert("CRITICAL"), securityAlert("WARNING")), 57, false, "Acme", "123");

        assertThat(html).contains("2 New Alerts");
    }

    @Test
    void newAlertsDigest_singleNewAlertUsesSingularWording() {
        String html = builder.buildNewAlertsDigestEmail(
                List.of(quotaAlert("CRITICAL")), 12, false, "Acme", "123");

        assertThat(html).contains("1 New Alert");
        assertThat(html).doesNotContain("1 New Alerts");
    }

    @Test
    void newAlertsDigest_reportsTotalOpenAsAFigure() {
        String html = builder.buildNewAlertsDigestEmail(
                List.of(quotaAlert("CRITICAL")), 57, false, "Acme", "123");

        assertThat(html).contains("Total Open");
        assertThat(html).contains(">57<");
    }

    @Test
    void newAlertsDigest_coldStartIsWordedAsABaselineNotAsNewFindings() {
        // After a Redis flush every alert looks new. Saying "2000 New Alerts" would be false.
        String html = builder.buildNewAlertsDigestEmail(
                List.of(quotaAlert("CRITICAL"), securityAlert("WARNING")), 2, true, "Acme", "123");

        assertThat(html).contains("Alert Digest");
        // No "N New Alerts" heading, and the lead stat is labelled Total rather than New.
        assertThat(html).doesNotContain("New Alert");
        assertThat(html).contains("Total");
    }

    @Test
    void newAlertsDigest_itemisesAtMostTwentyAlerts() {
        List<AlertDto> many = manyAlerts(2000);

        String html = builder.buildNewAlertsDigestEmail(many, 2000, false, "Big Corp", "999");

        // The first CAP alerts are listed; nothing beyond that is.
        assertThat(html).contains("alert-name-0");
        assertThat(html).contains("alert-name-" + (CAP - 1));
        assertThat(html).doesNotContain("alert-name-" + CAP);
        assertThat(html).doesNotContain("alert-name-1999");
    }

    @Test
    void newAlertsDigest_tellsYouHowManyItLeftOut() {
        String html = builder.buildNewAlertsDigestEmail(manyAlerts(2000), 2000, false, "Big Corp", "999");

        assertThat(html).contains("and " + (2000 - CAP) + " more new alerts");
        assertThat(html).contains("2000 alerts open in total");
    }

    @Test
    void newAlertsDigest_twoThousandAlertsStaysUnderGmailClipThreshold() {
        // Gmail clips around 102KB. The uncapped builder blew past that.
        String html = builder.buildNewAlertsDigestEmail(manyAlerts(2000), 2000, false, "Big Corp", "999");

        assertThat(html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThan(102_000);
    }

    @Test
    void newAlertsDigest_exactlyAtCap_showsNoMoreNotice() {
        String html = builder.buildNewAlertsDigestEmail(manyAlerts(CAP), CAP, false, "Acme", "123");

        assertThat(html).contains("alert-name-" + (CAP - 1));
        assertThat(html).doesNotContain("more new alert");
    }

    @Test
    void newAlertsDigest_oneOverCap_saysAndOneMoreSingular() {
        String html = builder.buildNewAlertsDigestEmail(manyAlerts(CAP + 1), CAP + 1, false, "Acme", "123");

        assertThat(html).contains("and 1 more new alert");
        assertThat(html).doesNotContain("and 1 more new alerts");
    }

    @Test
    void newAlertsDigest_smallListIsFullyItemised() {
        String html = builder.buildNewAlertsDigestEmail(manyAlerts(3), 3, false, "Acme", "123");

        assertThat(html).contains("alert-name-0").contains("alert-name-1").contains("alert-name-2");
        assertThat(html).doesNotContain("more new alert");
    }

    @Test
    void newAlertsDigest_containsAccountNameAndId() {
        String html = builder.buildNewAlertsDigestEmail(
                List.of(quotaAlert("CRITICAL")), 1, false, "Acme Corp", "999888777666");

        assertThat(html).contains("Acme Corp").contains("999888777666");
    }

    @Test
    void newAlertsDigest_capKeepsTheCriticalAndDropsTheInformational() {
        // The cap has to be applied to a ranked list. Twenty OK rows arriving ahead of one
        // critical would otherwise push the only thing worth reading into "and N more".
        List<AlertDto> unsorted = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            unsorted.add(alertFull("QUOTA", "healthy-" + i, "d", "EC2", "us-east-1", "OK", 10, 100));
        }
        unsorted.add(alertFull("SECURITY", "the-critical-one", "d", "S3", "us-east-1", "CRITICAL", 0, 0));

        String html = builder.buildNewAlertsDigestEmail(unsorted, 41, false, "Acme", "123");

        assertThat(html).contains("the-critical-one");
        assertThat(html).contains("and 21 more new alerts");
    }

    @Test
    void newAlertsDigest_itemisesInSeverityOrder() {
        List<AlertDto> unsorted = List.of(
                alertFull("QUOTA", "informational-row", "d", "EC2", "us-east-1", "OK", 10, 100),
                alertFull("SECURITY", "critical-row", "d", "S3", "us-east-1", "CRITICAL", 0, 0),
                alertFull("QUOTA", "warning-row", "d", "VPC", "us-east-1", "WARNING", 80, 100));

        String html = builder.buildNewAlertsDigestEmail(unsorted, 3, false, "Acme", "123");

        assertThat(html.indexOf("critical-row")).isLessThan(html.indexOf("warning-row"));
        assertThat(html.indexOf("warning-row")).isLessThan(html.indexOf("informational-row"));
    }

    @Test
    void newAlertsDigest_criticalCountUsesSeverityNotStatusText() {
        // Azure's Sev1 arrives with status CRITICAL but a true severity of HIGH. Counting the
        // status string called it critical; counting severity does not.
        AlertDto azureSev1 = alertFull("ANOMALY", "Sev1 alert", "d", "VM", "eastus", "CRITICAL", 0, 0);
        azureSev1.setSeverity("Error");
        AlertDto realCritical = alertFull("SECURITY", "Real critical", "d", "S3", "us-east-1", "CRITICAL", 0, 0);

        String html = builder.buildNewAlertsDigestEmail(
                List.of(azureSev1, realCritical), 2, false, "Acme", "123");

        // One critical, and the Sev1 counted among the warnings band instead.
        assertThat(html).contains(">1</div><div style='color:#dc2626;font-size:11px");
    }

    @Test
    void newAlertsDigest_escapesAccountNameToPreventInjection() {
        String html = builder.buildNewAlertsDigestEmail(
                List.of(quotaAlert("CRITICAL")), 1, false, "<script>alert(1)</script>", "123");

        assertThat(html).doesNotContain("<script>alert(1)</script>");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Distinctly-named alerts so a test can tell which ones made it into the HTML. */
    private List<AlertDto> manyAlerts(int count) {
        List<AlertDto> alerts = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            alerts.add(alertFull("QUOTA", "alert-name-" + i, "description " + i,
                    "EC2", "us-east-1", "WARNING", 80, 100));
        }
        return alerts;
    }

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
