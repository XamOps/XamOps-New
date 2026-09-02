package com.xammer.billops.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF guard for the messaging webhook URLs.
 *
 * <p>
 * The bypass cases below are the point of the test. The previous Teams check was
 * {@code startsWith("https://")}, which every one of the rejected URLs here satisfies, and
 * the Slack path had no check at all.
 */
class WebhookUrlValidatorTest {

    // ── Accepted ──────────────────────────────────────────────────────────────

    // Accept-cases assert the format/allowlist rules only, so the suite does not depend on
    // DNS resolution being available in CI.

    @Test
    void acceptsSlackIncomingWebhook() {
        assertThat(WebhookUrlValidator.isValidFormat(
                "https://hooks.slack.com/services/T000/B000/XXXXXXXX")).isTrue();
    }

    @Test
    void acceptsTeamsAndGoogleChatEndpoints() {
        assertThat(WebhookUrlValidator.isValidFormat(
                "https://contoso.webhook.office.com/webhookb2/abc/IncomingWebhook/def")).isTrue();
        assertThat(WebhookUrlValidator.isValidFormat(
                "https://chat.googleapis.com/v1/spaces/AAA/messages?key=k")).isTrue();
    }

    @Test
    void acceptsBotFrameworkServiceUrl() {
        // The Teams path reuses the webhookUrl column to hold a Bot Framework serviceUrl.
        assertThat(WebhookUrlValidator.isValidFormat("https://smba.trafficmanager.net/amer/"))
                .isTrue();
    }

    // ── Rejected ──────────────────────────────────────────────────────────────

    @Test
    void rejectsInstanceMetadata() {
        // The payload the report called out: passes a https:// prefix check, reaches IMDS.
        assertThatThrownBy(() -> WebhookUrlValidator.validateFormat(
                "https://169.254.169.254/latest/meta-data/iam/security-credentials/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLoopbackAndInternalHosts() {
        assertThat(WebhookUrlValidator.isValidFormat("https://localhost:8080/x")).isFalse();
        assertThat(WebhookUrlValidator.isValidFormat("https://127.0.0.1/x")).isFalse();
        assertThat(WebhookUrlValidator.isValidFormat("https://10.0.0.5/admin")).isFalse();
    }

    @Test
    void rejectsSuffixConfusion() {
        // The classic allowlist bypass: attacker-controlled domain with the approved host as
        // a leading label.
        assertThat(WebhookUrlValidator.isValidFormat("https://hooks.slack.com.attacker.test/x")).isFalse();
    }

    @Test
    void rejectsCredentialsInAuthority() {
        // Real host is attacker.test, not hooks.slack.com.
        assertThat(WebhookUrlValidator.isValidFormat("https://hooks.slack.com@attacker.test/x")).isFalse();
    }

    @Test
    void rejectsNonHttpsSchemes() {
        assertThat(WebhookUrlValidator.isValidFormat("http://hooks.slack.com/services/x")).isFalse();
        assertThat(WebhookUrlValidator.isValidFormat("file:///etc/passwd")).isFalse();
        assertThat(WebhookUrlValidator.isValidFormat("gopher://hooks.slack.com/x")).isFalse();
    }

    @Test
    void rejectsUnapprovedHost() {
        assertThat(WebhookUrlValidator.isValidFormat("https://example.com/webhook")).isFalse();
    }

    @Test
    void rejectsBlankAndMalformed() {
        assertThat(WebhookUrlValidator.isValidFormat(null)).isFalse();
        assertThat(WebhookUrlValidator.isValidFormat("")).isFalse();
        assertThat(WebhookUrlValidator.isValidFormat("not a url")).isFalse();
    }
}
