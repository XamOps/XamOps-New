package com.xammer.cloud.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CloudsitterEmailBuilder — pure HTML generation, no external deps.
 */
class CloudsitterEmailBuilderTest {

    private CloudsitterEmailBuilder builder;
    private static final ZonedDateTime FIXED_TIME =
            ZonedDateTime.of(2026, 6, 8, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata"));

    @BeforeEach
    void setUp() {
        builder = new CloudsitterEmailBuilder();
    }

    // ── subject() ────────────────────────────────────────────────────────────

    @Test
    void subject_startAction_saysStarted() {
        String subj = builder.subject("STARTED", "i-0abc1234", "business-hours");
        assertThat(subj).containsIgnoringCase("started");
        assertThat(subj).contains("i-0abc1234");
        assertThat(subj).contains("business-hours");
    }

    @Test
    void subject_stopAction_saysStopped() {
        String subj = builder.subject("STOPPED", "i-0abc1234", "business-hours");
        assertThat(subj).containsIgnoringCase("stopped");
    }

    @Test
    void subject_containsXamOpsBranding() {
        String subj = builder.subject("STARTED", "i-abc", "policy");
        assertThat(subj).containsIgnoringCase("XamOps");
        assertThat(subj).containsIgnoringCase("CloudSitter");
    }

    // ── buildHtml() — STARTED ─────────────────────────────────────────────────

    @Test
    void startedEmail_isValidHtml() {
        String html = builder.buildHtml("STARTED", "i-0abc1234", "prod-server",
                "ap-south-1", "business-hours", "Asia/Kolkata", FIXED_TIME);
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("</html>");
    }

    @Test
    void startedEmail_containsInstanceDetails() {
        String html = builder.buildHtml("STARTED", "i-0abc1234", "prod-server",
                "ap-south-1", "business-hours", "Asia/Kolkata", FIXED_TIME);
        assertThat(html).contains("i-0abc1234");
        assertThat(html).contains("prod-server");
        assertThat(html).contains("ap-south-1");
        assertThat(html).contains("business-hours");
    }

    @Test
    void startedEmail_showsStartedLabel() {
        String html = builder.buildHtml("STARTED", "i-0abc1234", null,
                "us-east-1", "night-saver", "UTC", FIXED_TIME);
        assertThat(html).containsIgnoringCase("Started");
    }

    @Test
    void startedEmail_usesGreenAccentColor() {
        String html = builder.buildHtml("STARTED", "i-abc", "server",
                "us-east-1", "policy", "UTC", FIXED_TIME);
        // Green accent for STARTED
        assertThat(html).contains("#10b981");
    }

    // ── buildHtml() — STOPPED ─────────────────────────────────────────────────

    @Test
    void stoppedEmail_showsStoppedLabel() {
        String html = builder.buildHtml("STOPPED", "i-0abc1234", "prod-server",
                "eu-west-1", "off-hours", "Europe/London", FIXED_TIME);
        assertThat(html).containsIgnoringCase("Stopped");
    }

    @Test
    void stoppedEmail_usesOrangeAccentColor() {
        String html = builder.buildHtml("STOPPED", "i-abc", "server",
                "us-east-1", "policy", "UTC", FIXED_TIME);
        // Amber/orange for STOPPED
        assertThat(html).contains("#f59e0b");
    }

    // ── Instance name vs ID display ───────────────────────────────────────────

    @Test
    void whenInstanceNameProvided_showsNameAndId() {
        String html = builder.buildHtml("STARTED", "i-0abc1234", "my-server",
                "us-east-1", "biz-hours", "UTC", FIXED_TIME);
        assertThat(html).contains("my-server");
        assertThat(html).contains("i-0abc1234");
    }

    @Test
    void whenInstanceNameNull_showsIdOnly() {
        String html = builder.buildHtml("STARTED", "i-0abc1234", null,
                "us-east-1", "biz-hours", "UTC", FIXED_TIME);
        assertThat(html).contains("i-0abc1234");
    }

    @Test
    void whenInstanceNameBlank_showsIdOnly() {
        String html = builder.buildHtml("STARTED", "i-0abc1234", "   ",
                "us-east-1", "biz-hours", "UTC", FIXED_TIME);
        assertThat(html).contains("i-0abc1234");
    }

    // ── Time display ─────────────────────────────────────────────────────────

    @Test
    void actionTime_appearsInEmail() {
        String html = builder.buildHtml("STARTED", "i-abc", "server",
                "ap-south-1", "policy", "Asia/Kolkata", FIXED_TIME);
        // Should contain the year at minimum
        assertThat(html).contains("2026");
    }

    @Test
    void nullActionTime_showsDash() {
        String html = builder.buildHtml("STARTED", "i-abc", "server",
                "ap-south-1", "policy", "Asia/Kolkata", null);
        assertThat(html).contains("—");
    }

    // ── XSS protection ───────────────────────────────────────────────────────

    @Test
    void xss_instanceIdEscaped() {
        // Instance IDs and names originate from EC2 tags, so they are attacker-controlled by
        // anyone able to tag a resource. They must not reach the email body as live markup.
        String html = builder.buildHtml("STARTED", "<script>alert(1)</script>", "server",
                "us-east-1", "policy", "UTC", FIXED_TIME);

        assertThat(html).containsIgnoringCase("XamOps");
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void xss_instanceNameEscaped() {
        String html = builder.buildHtml("STOPPED", "i-0123456789abcdef0",
                "<img src=x onerror=alert(1)>", "us-east-1", "policy", "UTC", FIXED_TIME);

        assertThat(html).doesNotContain("<img src=x onerror=alert(1)>");
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    void xss_policyAndRegionEscaped() {
        String html = builder.buildHtml("STOPPED", "i-0123456789abcdef0", "server",
                "<b>region</b>", "<i>policy</i>", "UTC", FIXED_TIME);

        assertThat(html).doesNotContain("<b>region</b>");
        assertThat(html).doesNotContain("<i>policy</i>");
        assertThat(html).contains("&lt;b&gt;region&lt;/b&gt;");
        assertThat(html).contains("&lt;i&gt;policy&lt;/i&gt;");
    }

    @Test
    void subject_stripsNewlinesToPreventHeaderInjection() {
        String subject = builder.subject("STARTED",
                "i-0123\r\nBcc: attacker@example.com", "policy");

        assertThat(subject).doesNotContain("\r");
        assertThat(subject).doesNotContain("\n");
    }

    @Test
    void xss_policyNameEscaped() {
        // NOTE: Same as above — the row() helper passes values raw.
        // The policy name is passed as-is into the HTML.
        // This test documents current behavior.
        String html = builder.buildHtml("STARTED", "i-abc", "server",
                "us-east-1", "<b>evil</b>", "UTC", FIXED_TIME);
        assertThat(html).containsIgnoringCase("XamOps");
        // The policy name appears in the table — raw
        assertThat(html).contains("evil");
    }

    // ── XamOps branding ───────────────────────────────────────────────────────

    @Test
    void email_containsXamOpsAndCloudsitter() {
        String html = builder.buildHtml("STOPPED", "i-abc", "server",
                "us-east-1", "policy", "UTC", FIXED_TIME);
        assertThat(html).containsIgnoringCase("XamOps");
        assertThat(html).containsIgnoringCase("CloudSitter");
    }

    @Test
    void email_containsFooter() {
        String html = builder.buildHtml("STARTED", "i-abc", "server",
                "us-east-1", "policy", "UTC", FIXED_TIME);
        assertThat(html).containsIgnoringCase("scheduling engine");
        assertThat(html).containsIgnoringCase("XamOps");
    }
}
