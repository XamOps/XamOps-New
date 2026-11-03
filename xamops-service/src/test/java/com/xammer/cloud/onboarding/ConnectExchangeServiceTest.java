package com.xammer.cloud.onboarding;

import com.xammer.cloud.domain.ClusterConnectToken;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.onboarding.ConnectExchangeRequest;
import com.xammer.cloud.dto.onboarding.ConnectExchangeResponse;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.onboarding.ClusterKeyService;
import com.xammer.cloud.service.onboarding.ConnectExchangeService;
import com.xammer.cloud.service.onboarding.ConnectTokenService;
import com.xammer.cloud.service.onboarding.OnboardingAuditService;
import com.xammer.cloud.service.onboarding.OnboardingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BP-5 (design §4.3): the exchange decision table — every refusal row with its code/status, which
 * refusals leave the token un-consumed, the TTL boundary, and the consume race (AC-4). The
 * capability health check is stubbed; the feature-catalog/iam parameters are out of scope here.
 */
class ConnectExchangeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private KubernetesClusterConfigRepository configRepo;
    private ConnectTokenService tokenService;
    private ClusterKeyService keyService;
    private ConnectExchangeService exchange;

    @BeforeEach
    void setUp() {
        configRepo = mock(KubernetesClusterConfigRepository.class);
        tokenService = mock(ConnectTokenService.class);
        keyService = mock(ClusterKeyService.class);
        OnboardingAuditService audit = mock(OnboardingAuditService.class);
        exchange = new ConnectExchangeService(configRepo, tokenService, keyService, audit, clock,
                Optional.of(config -> true));
        ReflectionTestUtils.setField(exchange, "serverUrl", "https://live.xamops.test");

        when(keyService.getOrCreateDataKey(any())).thenReturn("xamops_DATA_KEY_VALUE_1234567890");
        when(keyService.getOrCreateAutomationKey(any())).thenReturn("xamops_AUTO_KEY_VALUE_1234567890");
    }

    private static String rawTokenOfValidShape() {
        // xct_ + 43 base64url chars.
        return "xct_" + "A".repeat(43);
    }

    private ClusterConnectToken token(String purpose) {
        ClusterConnectToken t = new ClusterConnectToken();
        t.setId(1L);
        t.setTokenPrefix("xct_AAAAAAAA");
        t.setClusterConfigId(100L);
        t.setPurpose(purpose);
        t.setFeatures("COST_INSIGHTS");
        t.setIssuedAt(NOW.minus(1, ChronoUnit.MINUTES));
        t.setExpiresAt(NOW.plus(9, ChronoUnit.MINUTES));
        return t;
    }

    private KubernetesClusterConfig config() {
        KubernetesClusterConfig c = new KubernetesClusterConfig();
        c.setId(100L);
        c.setCloudAccountId(111111111111L);
        c.setClusterName("prod-eks");
        return c;
    }

    private ConnectExchangeRequest req(String phase) {
        ConnectExchangeRequest r = new ConnectExchangeRequest();
        r.setClusterName("prod-eks");
        r.setCloudAccountId("111111111111");
        r.setRegion("ap-south-1");
        r.setPhase(phase);
        return r;
    }

    private OnboardingException run(String raw, ConnectExchangeRequest r) {
        return catchThrowableOfType(() -> exchange.exchange(raw, r, "1.2.3.4"), OnboardingException.class);
    }

    @Test
    void malformedToken_is401_TOKEN_INVALID_notConsumed() {
        OnboardingException e = run("not-a-token", req("READ_ONLY"));
        assertThat(e.getCode()).isEqualTo("TOKEN_INVALID");
        assertThat(e.getStatus().value()).isEqualTo(401);
        verify(tokenService, never()).consume(anyLong(), anyString());
    }

    @Test
    void unknownHash_is401_TOKEN_INVALID() {
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.empty());
        OnboardingException e = run(rawTokenOfValidShape(), req("READ_ONLY"));
        assertThat(e.getCode()).isEqualTo("TOKEN_INVALID");
        assertThat(e.getStatus().value()).isEqualTo(401);
    }

    @Test
    void revoked_is410_notConsumed() {
        ClusterConnectToken t = token(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY);
        t.setRevokedAt(NOW.minus(1, ChronoUnit.MINUTES));
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(t));
        OnboardingException e = run(rawTokenOfValidShape(), req("READ_ONLY"));
        assertThat(e.getCode()).isEqualTo("TOKEN_REVOKED");
        assertThat(e.getStatus().value()).isEqualTo(410);
        verify(tokenService, never()).consume(anyLong(), anyString());
    }

    @Test
    void expiredAtBoundary_is410() {
        ClusterConnectToken t = token(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY);
        t.setExpiresAt(NOW); // expires_at <= now ⇒ expired
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(t));
        OnboardingException e = run(rawTokenOfValidShape(), req("READ_ONLY"));
        assertThat(e.getCode()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void justBeforeExpiry_passesTtlCheck() {
        ClusterConnectToken t = token(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY);
        t.setExpiresAt(NOW.plusSeconds(1)); // still valid
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(t));
        when(configRepo.findById(100L)).thenReturn(Optional.of(config()));
        when(tokenService.consume(eq(1L), anyString())).thenReturn(true);
        // Should succeed (not throw on TTL).
        ConnectExchangeResponse resp = exchange.exchange(rawTokenOfValidShape(), req("READ_ONLY"), "1.2.3.4");
        assertThat(resp.getKeyKind()).isEqualTo("DATA");
    }

    @Test
    void alreadyUsed_is409() {
        ClusterConnectToken t = token(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY);
        t.setUsedAt(NOW.minus(1, ChronoUnit.MINUTES));
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(t));
        OnboardingException e = run(rawTokenOfValidShape(), req("READ_ONLY"));
        assertThat(e.getCode()).isEqualTo("TOKEN_USED");
        assertThat(e.getStatus().value()).isEqualTo(409);
    }

    @Test
    void clusterMismatch_is409_notConsumed() {
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(token(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY)));
        when(configRepo.findById(100L)).thenReturn(Optional.of(config()));
        ConnectExchangeRequest r = req("READ_ONLY");
        r.setClusterName("wrong-cluster");
        OnboardingException e = run(rawTokenOfValidShape(), r);
        assertThat(e.getCode()).isEqualTo("CLUSTER_MISMATCH");
        verify(tokenService, never()).consume(anyLong(), anyString());
    }

    @Test
    void accountMismatch_is409() {
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(token(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY)));
        when(configRepo.findById(100L)).thenReturn(Optional.of(config()));
        ConnectExchangeRequest r = req("READ_ONLY");
        r.setCloudAccountId("222222222222");
        OnboardingException e = run(rawTokenOfValidShape(), r);
        assertThat(e.getCode()).isEqualTo("ACCOUNT_MISMATCH");
    }

    @Test
    void accountMismatch_toleratesLeadingZeroDifference() {
        ClusterConnectToken t = token(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY);
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(t));
        KubernetesClusterConfig c = config();
        c.setCloudAccountId(80310056620L); // stored without leading zero
        when(configRepo.findById(100L)).thenReturn(Optional.of(c));
        when(tokenService.consume(eq(1L), anyString())).thenReturn(true);
        ConnectExchangeRequest r = req("READ_ONLY");
        r.setCloudAccountId("080310056620"); // reported with leading zero
        ConnectExchangeResponse resp = exchange.exchange(rawTokenOfValidShape(), r, "1.2.3.4");
        assertThat(resp.getKeyKind()).isEqualTo("DATA");
    }

    @Test
    void phaseMismatch_is409() {
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(token(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY)));
        when(configRepo.findById(100L)).thenReturn(Optional.of(config()));
        OnboardingException e = run(rawTokenOfValidShape(), req("AUTOMATION"));
        assertThat(e.getCode()).isEqualTo("PHASE_MISMATCH");
    }

    @Test
    void consumeRace_lostRace_is409_TOKEN_USED() {
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(token(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY)));
        when(configRepo.findById(100L)).thenReturn(Optional.of(config()));
        when(tokenService.consume(eq(1L), anyString())).thenReturn(false); // another caller won
        OnboardingException e = run(rawTokenOfValidShape(), req("READ_ONLY"));
        assertThat(e.getCode()).isEqualTo("TOKEN_USED");
        assertThat(e.getStatus().value()).isEqualTo(409);
    }

    @Test
    void readOnlySuccess_consumesAndIssuesDataKey_clearsNothing() {
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(token(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY)));
        KubernetesClusterConfig c = config();
        when(configRepo.findById(100L)).thenReturn(Optional.of(c));
        when(tokenService.consume(eq(1L), anyString())).thenReturn(true);

        ConnectExchangeResponse resp = exchange.exchange(rawTokenOfValidShape(), req("READ_ONLY"), "1.2.3.4");

        assertThat(resp.getKeyKind()).isEqualTo("DATA");
        assertThat(resp.getAgentKey()).startsWith("xamops_");
        assertThat(resp.toString()).doesNotContain(resp.getAgentKey());
        verify(keyService).getOrCreateDataKey(c);
        verify(configRepo).save(c);
    }

    @Test
    void automationExchange_permissionsChanged_whenConsentMissing() {
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(token(ConnectTokenService.PURPOSE_ENABLE_AUTOMATION)));
        when(configRepo.findById(100L)).thenReturn(Optional.of(config())); // no consent / hash
        OnboardingException e = run(rawTokenOfValidShape(), req("AUTOMATION"));
        assertThat(e.getCode()).isEqualTo("PERMISSIONS_CHANGED");
    }

    @Test
    void automationExchange_success_clearsDisabledAtAndIssuesAutomationKey() {
        ClusterConnectToken t = token(ConnectTokenService.PURPOSE_ENABLE_AUTOMATION);
        t.setPermissionHash("hashvalue");
        when(tokenService.findByRaw(anyString())).thenReturn(Optional.of(t));
        KubernetesClusterConfig c = config();
        c.setAutomationConsentAt(NOW.minusSeconds(10));
        c.setAutomationPermissionHash("hashvalue");
        c.setAutomationDisabledAt(NOW.minusSeconds(100));
        when(configRepo.findById(100L)).thenReturn(Optional.of(c));
        when(tokenService.consume(eq(1L), anyString())).thenReturn(true);

        ConnectExchangeResponse resp = exchange.exchange(rawTokenOfValidShape(), req("AUTOMATION"), "1.2.3.4");

        assertThat(resp.getKeyKind()).isEqualTo("AUTOMATION");
        assertThat(c.getAutomationDisabledAt()).isNull(); // §3.1a cleared on successful enable
    }

    @Test
    void nodeScalerChanged_detection_vs_snapshot_and_config() {
        assertThat(ConnectExchangeService.nodeScalerChanged("KARPENTER", "NONE", null)).isTrue();
        assertThat(ConnectExchangeService.nodeScalerChanged("NONE", "NONE", "KARPENTER")).isTrue();
        assertThat(ConnectExchangeService.nodeScalerChanged("NONE", "NONE", "UNKNOWN")).isFalse();
        assertThat(ConnectExchangeService.nodeScalerChanged("NONE", "NONE", "NONE")).isFalse();
        assertThat(ConnectExchangeService.nodeScalerChanged(null, "NONE", "KARPENTER")).isFalse();
    }
}
