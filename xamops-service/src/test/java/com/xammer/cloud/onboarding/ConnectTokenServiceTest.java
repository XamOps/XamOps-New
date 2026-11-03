package com.xammer.cloud.onboarding;

import com.xammer.cloud.domain.ClusterConnectToken;
import com.xammer.cloud.repository.ClusterConnectTokenRepository;
import com.xammer.cloud.service.onboarding.ConnectTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BP-5 (design §2.1, §2.2, §4.6): token mint/hash/shape, single-use consume delegation, and the
 * revoke-earlier-then-issue behaviour.
 */
class ConnectTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void mint_hasValidShape_andHashIsStable() {
        ConnectTokenService svc = new ConnectTokenService(mock(ClusterConnectTokenRepository.class), clock);
        String raw = svc.mint();
        assertThat(raw).startsWith("xct_");
        assertThat(ConnectTokenService.hasValidShape(raw)).isTrue();
        assertThat(ConnectTokenService.hash(raw)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(ConnectTokenService.hash(raw)).isEqualTo(ConnectTokenService.hash(raw));
        assertThat(ConnectTokenService.displayPrefix(raw)).hasSize(12).startsWith("xct_");
    }

    @Test
    void hasValidShape_rejectsWrongPrefixOrLength() {
        assertThat(ConnectTokenService.hasValidShape(null)).isFalse();
        assertThat(ConnectTokenService.hasValidShape("xak_" + "A".repeat(43))).isFalse();
        assertThat(ConnectTokenService.hasValidShape("xct_short")).isFalse();
        assertThat(ConnectTokenService.hasValidShape("xct_" + "A".repeat(43))).isTrue();
    }

    @Test
    void issue_revokesLiveTokensThenPersistsNewRow() {
        ClusterConnectTokenRepository repo = mock(ClusterConnectTokenRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ConnectTokenService svc = new ConnectTokenService(repo, clock);

        ConnectTokenService.IssuedToken issued =
                svc.issue(100L, ConnectTokenService.PURPOSE_CONNECT_READ_ONLY, 42L, 600);

        verify(repo).revokeLiveTokens(eq(100L), eq(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY), eq(NOW));
        ArgumentCaptor<ClusterConnectToken> captor = ArgumentCaptor.forClass(ClusterConnectToken.class);
        verify(repo).save(captor.capture());
        ClusterConnectToken row = captor.getValue();
        assertThat(row.getExpiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(row.getTokenHash()).isEqualTo(ConnectTokenService.hash(issued.rawToken()));
        assertThat(issued.rawToken()).startsWith("xct_");
    }

    @Test
    void consume_returnsTrueOnlyWhenExactlyOneRowUpdated() {
        ClusterConnectTokenRepository repo = mock(ClusterConnectTokenRepository.class);
        ConnectTokenService svc = new ConnectTokenService(repo, clock);
        when(repo.consume(eq(1L), eq(NOW), anyString())).thenReturn(1);
        assertThat(svc.consume(1L, "1.2.3.4")).isTrue();
        when(repo.consume(eq(2L), eq(NOW), anyString())).thenReturn(0);
        assertThat(svc.consume(2L, "1.2.3.4")).isFalse();
    }
}
