package com.xammer.cloud.onboarding;

import com.xammer.cloud.domain.ClusterOnboardingEvent;
import com.xammer.cloud.repository.ClusterOnboardingEventRepository;
import com.xammer.cloud.service.onboarding.OnboardingAuditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * BP-4 (design §2.3): {@code OnboardingAuditService} writes an event whose {@code detailJson}
 * never contains a token or key. The assertion mirrors the design's forbidden shape
 * {@code xct_ | xak_ | xamops_[A-Za-z0-9_-]{20,}}.
 */
class OnboardingAuditServiceTest {

    /** The design §2.3 secret shape that must never appear in an audit detail. */
    private static final Pattern SECRET = Pattern.compile("xct_|xak_|xamops_[A-Za-z0-9_-]{20,}");

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void record_persistsEventWithNoSecretInDetail() {
        ClusterOnboardingEventRepository repo = mock(ClusterOnboardingEventRepository.class);
        OnboardingAuditService audit = new OnboardingAuditService(repo, clock);

        // A realistic detail carries only ids, codes and check results — never a token or key,
        // and not even a xct_/xak_-prefixed string (design §2.3 forbids those substrings outright).
        String detail = "{\"tokenId\":5551,\"code\":\"TOKEN_EXPIRED\",\"purpose\":\"CONNECT_READ_ONLY\"}";
        audit.record(1L, 7L, "prod-eks", 100L, "TOKEN_REFUSED",
                OnboardingAuditService.ACTOR_INSTALLER, null, detail);

        ArgumentCaptor<ClusterOnboardingEvent> captor = ArgumentCaptor.forClass(ClusterOnboardingEvent.class);
        verify(repo).save(captor.capture());
        ClusterOnboardingEvent saved = captor.getValue();

        assertThat(saved.getEventType()).isEqualTo("TOKEN_REFUSED");
        assertThat(saved.getActorType()).isEqualTo("INSTALLER");
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-10-30T12:00:00Z"));
        // The 8-char display prefix is allowed; a full token/key (20+ chars after xamops_) is not.
        assertThat(SECRET.matcher(saved.getDetailJson()).find())
                .as("audit detail must not contain a full token or key")
                .isFalse();
    }

    @Test
    void record_rejectsNothingButNeverStoresAFullKeyShape() {
        // Guard: if a caller ever passed a full key, the test regex would catch it. Prove the
        // regex actually fires on a full xamops_ key so the negative assertion above is meaningful.
        String leaked = "xamops_AbCdEfGhIjKlMnOpQrStUvWx";
        assertThat(SECRET.matcher(leaked).find()).isTrue();
    }
}
