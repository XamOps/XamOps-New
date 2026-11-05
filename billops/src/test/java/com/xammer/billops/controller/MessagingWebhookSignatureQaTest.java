package com.xammer.billops.controller;

import com.xammer.billops.domain.MessagingIntegrationConfig;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.MessagingIntegrationConfigRepository;
import com.xammer.billops.repository.SupportMessageRepository;
import com.xammer.billops.repository.UserPlatformConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA depth-testing: inbound Slack webhook authentication (report 02-bugs-A, BUG A-01).
 *
 * <p>The signature is verified only when BOTH Slack headers are present, so a caller that simply
 * omits them skips verification even though a signing secret is configured.
 */
class MessagingWebhookSignatureQaTest {

    private static final String EVENT = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
            + "\"channel\":\"C1\",\"user\":\"U1\",\"text\":\"pay now\",\"ts\":\"1.1\"}}";

    private MessagingWebhookController controller;

    @BeforeEach
    void setUp() {
        MessagingIntegrationConfigRepository cfgRepo = mock(MessagingIntegrationConfigRepository.class);
        MessagingIntegrationConfig cfg = new MessagingIntegrationConfig();
        cfg.setSigningSecret("unit-test-signing-secret");
        when(cfgRepo.findByPlatform("SLACK")).thenReturn(Optional.of(cfg));
        controller = new MessagingWebhookController(mock(SupportMessageRepository.class),
                mock(UserPlatformConnectionRepository.class), cfgRepo, mock(AppUserRepository.class));
    }

    @Test
    @DisplayName("characterization: a wrong signature with both headers is rejected with 401")
    void wrongSignatureRejected() {
        ResponseEntity<Map<String, Object>> r = controller.handleSlackEvent(EVENT, "v0=deadbeef",
                String.valueOf(System.currentTimeMillis() / 1000));
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("characterization: url_verification challenge is echoed")
    void urlVerificationChallenge() {
        ResponseEntity<Map<String, Object>> r = controller.handleSlackEvent(
                "{\"type\":\"url_verification\",\"challenge\":\"abc\"}", null, null);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("challenge", "abc");
    }

    @Test
    @Disabled("BUG A-01: omitting X-Slack-Signature/X-Slack-Request-Timestamp bypasses verification even when a signing secret is configured")
    @DisplayName("a request with no signature headers must be rejected when a signing secret is configured")
    void missingSignatureHeadersRejected() {
        ResponseEntity<Map<String, Object>> r = controller.handleSlackEvent(EVENT, null, null);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }
}
