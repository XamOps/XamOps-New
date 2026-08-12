package com.xammer.cloud.service.war;

import com.xammer.cloud.dto.war.WarFinding;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.dto.war.WarStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WarEmailBuilder}.
 *
 * <p>
 * The behaviour under test that actually matters is the assessable gate: a scan that
 * could not read the account must not produce an email containing a score. Everything
 * else here is content and escaping. Pure string generation, no Spring context.
 */
class WarEmailBuilderTest {

    private WarEmailBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new WarEmailBuilder();
        // @Value is not processed outside a context; the CTA links need a base.
        ReflectionTestUtils.setField(builder, "frontendUrl", "https://app.xamops.com");
    }

    // ── The assessable gate ─────────────────────────────────────────────────

    @Test
    void unassessableReport_neverRendersAScore() {
        WarReport report = blockedReport();

        String html = builder.build(report, "Acme Prod");

        assertThat(html).contains("Unable to assess");
        // The score is forced to 0 upstream; no score furniture may reach the reader.
        // "Verified 0" is fine and expected — a bare 0 is only a lie next to "/100".
        assertThat(html).doesNotContain("/100");
        assertThat(html).doesNotContain("Critical risk");
        assertThat(html).doesNotContain("Weighted pass rate");
    }

    @Test
    void blockedEmailIsWellFormed() {
        String html = builder.buildBlocked(blockedReport(), "Acme Prod");

        // The metadata rows once emitted one unclosed <table> each, which Outlook
        // rendered as a single collapsed cell.
        assertThat(countOccurrences(html, "<table"))
                .isEqualTo(countOccurrences(html, "</table>"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }

    @Test
    void unassessableReport_saysItIsNotAVerdictOnTheirSecurity() {
        String html = builder.build(blockedReport(), "Acme Prod");

        assertThat(html).contains("not a verdict");
    }

    @Test
    void unassessableReport_surfacesTheProviderErrorAndTheDeniedActions() {
        String html = builder.build(blockedReport(), "Acme Prod");

        assertThat(html).contains("The security token included in the request is invalid");
        assertThat(html).contains("iam:ListUsers");
        assertThat(html).contains("AWS reported");
    }

    @Test
    void unassessableReport_subjectAsksForAction() {
        String subject = builder.subject(blockedReport(), "Acme Prod");

        assertThat(subject).isEqualTo("Action needed: XamOps could not scan Acme Prod");
    }

    // ── The healthy path ────────────────────────────────────────────────────

    @Test
    void assessableReport_rendersScoreRiskAndCounts() {
        String html = builder.build(assessableReport(), "Acme Prod");

        assertThat(html).contains("42");
        assertThat(html).contains("/100");
        assertThat(html).contains("Critical risk");
        assertThat(html).contains("Exposed");
        assertThat(html).contains("Protected");
        assertThat(html).contains("Acme Prod");
    }

    @Test
    void assessableReport_rendersRankedTopRisks() {
        String html = builder.build(assessableReport(), "Acme Prod");

        assertThat(html).contains("Fix these first");
        assertThat(html).contains("Root account has no MFA");
        assertThat(html).contains("AWS-IAM-01");
        assertThat(html).contains("4 affected");
    }

    @Test
    void assessableReport_listsOnlyAttackPathsWithSomethingOpen() {
        String html = builder.build(assessableReport(), "Acme Prod");

        assertThat(html).contains("Account takeover");
        // CONTAINED path: nothing failed or warned, so it earns no line in the email.
        assertThat(html).doesNotContain("Crypto mining");
    }

    @Test
    void assessableReport_statesCoverageWhenControlsWereUnverified() {
        String html = builder.build(assessableReport(), "Acme Prod");

        assertThat(html).contains("blocked, not failed");
    }

    @Test
    void assessableReport_subjectLeadsWithCriticalCount() {
        String subject = builder.subject(assessableReport(), "Acme Prod");

        assertThat(subject).isEqualTo("Your first security review of Acme Prod — 2 critical exposures");
    }

    @Test
    void assessableReport_subjectFallsBackToScoreWithNoCriticals() {
        WarReport report = assessableReport();
        report.setSeverityCounts(Map.of("Critical", 0, "High", 1, "Medium", 0, "Low", 0));

        assertThat(builder.subject(report, "Acme Prod"))
                .isEqualTo("Your first security review of Acme Prod — Security score 42/100");
    }

    @Test
    void cleanReport_saysNoConfirmedExposuresRatherThanShowingAnEmptyList() {
        WarReport report = assessableReport();
        report.setTopRisks(List.of());

        String html = builder.build(report, "Acme Prod");

        assertThat(html).contains("No confirmed exposures");
        assertThat(html).doesNotContain("Fix these first");
    }

    // ── Provider routing and escaping ───────────────────────────────────────

    @Test
    void ctaLinksToTheProvidersOwnRoute() {
        WarReport aws = assessableReport();
        assertThat(builder.build(aws, "Acme")).contains("https://app.xamops.com/war");

        WarReport gcp = assessableReport();
        gcp.setProvider("GCP");
        assertThat(builder.build(gcp, "Acme")).contains("https://app.xamops.com/gcp-war");

        WarReport azure = assessableReport();
        azure.setProvider("Azure");
        assertThat(builder.build(azure, "Acme")).contains("https://app.xamops.com/azure-war");
    }

    @Test
    void blockedEmailNamesTheProviderInItsOwnTerms() {
        WarReport report = blockedReport();
        report.setProvider("GCP");

        assertThat(builder.buildBlocked(report, "Acme")).contains("Google Cloud reported");
    }

    @Test
    void escapesCustomerControlledText() {
        WarReport report = assessableReport();
        WarFinding finding = report.getTopRisks().get(0);
        finding.setTitle("<script>alert(1)</script>");
        finding.setSummary("bucket \"prod\" & <b>staging</b>");

        String html = builder.build(report, "<img onerror=x>");

        assertThat(html).doesNotContain("<script>");
        assertThat(html).doesNotContain("<img onerror=x>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&amp;");
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private WarReport assessableReport() {
        return WarReport.builder()
                .accountId("123456789012")
                .accountName("Acme Prod")
                .provider("AWS")
                .generatedAt("2026-08-10 12:30 IST")
                .scannedRegions(List.of("ap-south-1", "us-east-1"))
                .warScore(42)
                .riskLevel("CRITICAL")
                .totalControls(86)
                .automatedControls(64)
                .verifiedControls(60)
                .assessable(true)
                .statusCounts(Map.of(
                        "PASS", 40, "FAIL", 15, "WARN", 5, "UNKNOWN", 12, "MANUAL", 14))
                .severityCounts(Map.of("Critical", 2, "High", 6, "Medium", 8, "Low", 4))
                .attackPaths(List.of(
                        WarReport.AttackPathSummary.builder()
                                .path("ACCOUNT_TAKEOVER").label("Account takeover")
                                .failed(3).warned(1).unverified(0).total(9)
                                .state("OPEN").worstSeverity("Critical")
                                .topControlIds(List.of("AWS-IAM-01")).build(),
                        WarReport.AttackPathSummary.builder()
                                .path("CRYPTO_MINING").label("Crypto mining")
                                .failed(0).warned(0).unverified(0).total(6)
                                .state("CONTAINED").worstSeverity("Low")
                                .topControlIds(List.of()).build()))
                .categories(List.of())
                .findings(List.of())
                .topRisks(List.of(finding()))
                .deniedActions(List.of())
                .blockedActions(List.of())
                .scanWarnings(List.of())
                .build();
    }

    private WarReport blockedReport() {
        return WarReport.builder()
                .accountId("123456789012")
                .accountName("Acme Prod")
                .provider("AWS")
                .generatedAt("2026-08-10 12:30 IST")
                .scannedRegions(List.of("ap-south-1"))
                // Exactly what the assembler produces for an unreadable account.
                .warScore(0)
                .riskLevel("UNKNOWN")
                .totalControls(86)
                .automatedControls(64)
                .verifiedControls(0)
                .assessable(false)
                .blockedReason("The security token included in the request is invalid")
                .statusCounts(Map.of("UNKNOWN", 72, "MANUAL", 14))
                .severityCounts(Map.of("Critical", 0, "High", 0, "Medium", 0, "Low", 0))
                .attackPaths(List.of())
                .categories(List.of())
                .findings(List.of())
                .topRisks(List.of())
                .deniedActions(List.of("iam:ListUsers", "s3:GetBucketPolicy"))
                .blockedActions(List.of())
                .scanWarnings(List.of())
                .build();
    }

    private WarFinding finding() {
        WarFinding finding = new WarFinding();
        finding.setControlId("AWS-IAM-01");
        finding.setTitle("Root account has no MFA");
        finding.setSummary("The root user can sign in with a password alone");
        finding.setSeverity("Critical");
        finding.setStatus(WarStatus.FAIL);
        finding.setAffectedCount(4);
        finding.setRiskWeight(20);
        finding.setAttackPaths(List.of("ACCOUNT_TAKEOVER"));
        finding.setAttackPathLabels(List.of("Account takeover"));
        finding.setEvidence(List.of());
        return finding;
    }
}
