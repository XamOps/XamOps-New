package com.xammer.cloud.service.security;

import com.xammer.cloud.domain.CloudSecurityFinding;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the security email's content and, more importantly, its escaping.
 *
 * <p>
 * Every interpolated value in this email originates outside XamOps: titles and descriptions come
 * from AWS event payloads, and resource ARNs contain names the customer chose. That makes this a
 * path from untrusted input to rendered HTML in a colleague's mail client. A bucket named
 * {@code <img onerror=...>} is a legal S3 name, so the escaping is a control rather than a
 * nicety — and an unescaped build still looks completely normal in every test that does not check
 * for it.
 */
class SecurityFindingEmailBuilderTest {

    private final SecurityFindingEmailBuilder builder = new SecurityFindingEmailBuilder();

    private CloudSecurityFinding finding(String severity, String title) {
        CloudSecurityFinding f = new CloudSecurityFinding();
        f.setSeverity(severity);
        f.setTitle(title);
        f.setDescription("Something happened");
        f.setSource(CloudSecurityFinding.SOURCE_GUARDDUTY);
        f.setResourceArn("arn:aws:s3:::my-bucket");
        f.setRegion("us-east-1");
        f.setRemediation("Turn it back on");
        f.setFirstSeenAt(Instant.now());
        return f;
    }

    // ── Escaping ────────────────────────────────────────────────────────────

    @Test
    void hostileResourceNamesAreEscapedNotRendered() {
        CloudSecurityFinding f = finding("CRITICAL", "<script>alert(1)</script>");
        f.setResourceArn("arn:aws:s3:::<img src=x onerror=alert(1)>");
        f.setDescription("<b>bold</b> & dangerous");
        f.setRemediation("<iframe src='evil'></iframe>");

        String html = builder.build(List.of(f), "prod", "123456789012");

        assertFalse(html.contains("<script>"), "a finding title must not inject script tags");
        assertFalse(html.contains("<img src=x"), "a resource name must not inject an img tag");
        assertFalse(html.contains("<iframe"), "remediation text must not inject an iframe");
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&amp; dangerous"));
    }

    @Test
    void hostileAccountNameIsEscaped() {
        String html = builder.build(List.of(finding("HIGH", "t")),
                "<script>x</script>", "<b>123</b>");

        assertFalse(html.contains("<script>x</script>"));
        assertFalse(html.contains("<b>123</b>"));
    }

    // ── Content ─────────────────────────────────────────────────────────────

    @Test
    void remediationIsIncludedBecauseItIsTheOnlyActionableLine() {
        String html = builder.build(List.of(finding("CRITICAL", "Bucket public")),
                "prod", "123456789012");

        assertTrue(html.contains("Turn it back on"));
        assertTrue(html.contains("What to do"));
    }

    /**
     * An unidentified resource is stated plainly. Leaving the field blank reads as "nothing
     * affected", which is the opposite of what an unattributed finding means.
     */
    @Test
    void missingResourceIsCalledOutRatherThanLeftBlank() {
        CloudSecurityFinding f = finding("CRITICAL", "Trail deleted");
        f.setResourceArn(null);

        String html = builder.build(List.of(f), "prod", "123456789012");

        assertTrue(html.contains("not identified from the event"));
    }

    @Test
    void severityAndSourceAppearOnEveryFinding() {
        String html = builder.build(List.of(finding("CRITICAL", "Bucket public")),
                "prod", "123456789012");

        assertTrue(html.contains("CRITICAL"));
        assertTrue(html.contains("GuardDuty"));
        assertTrue(html.contains("us-east-1"));
    }

    /**
     * Large batches are capped with an explicit overflow line. Past a couple of dozen rows the
     * email stops being a call to action, and some clients begin clipping the message — which
     * would hide the very link that shows the rest.
     */
    @Test
    void largeBatchesAreCappedWithAnExplicitOverflowLine() {
        List<CloudSecurityFinding> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(finding("CRITICAL", "Finding number " + i));
        }

        String html = builder.build(many, "prod", "123456789012");

        assertTrue(html.contains("Finding number 0"));
        assertFalse(html.contains("Finding number 59"), "the email must not list all 60");
        assertTrue(html.contains("more finding"), "the overflow must be stated, not silent");
    }

    @Test
    void producesWellFormedStandaloneHtml() {
        String html = builder.build(List.of(finding("HIGH", "t")), "prod", "123456789012");

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.endsWith("</html>"));
        assertTrue(html.contains("<body"));
    }

    // ── Subject ─────────────────────────────────────────────────────────────

    /** Severity and count first: that is all that survives a phone notification preview. */
    @Test
    void singleFindingSubjectNamesTheFinding() {
        String subject = builder.subjectFor(
                List.of(finding("CRITICAL", "S3 Block Public Access was removed")), "prod");

        assertEquals("[XamOps] Critical: S3 Block Public Access was removed — prod", subject);
    }

    @Test
    void batchSubjectLeadsWithCountAndWorstSeverity() {
        String subject = builder.subjectFor(
                List.of(finding("HIGH", "a"), finding("CRITICAL", "b"), finding("LOW", "c")),
                "prod");

        assertTrue(subject.startsWith("[XamOps] 3 new Critical security findings"),
                "actual: " + subject);
        assertTrue(subject.endsWith("prod"));
    }

    @Test
    void emptySeverityDoesNotBreakTheSubject() {
        CloudSecurityFinding f = finding(null, "Mystery");
        String subject = builder.subjectFor(List.of(f), "prod");

        assertTrue(subject.contains("Medium"), "an unknown severity degrades, it does not throw");
    }
}
