package com.xammer.cloud.xampulse.service.agent;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.domain.XpAgentCredential;
import com.xammer.cloud.xampulse.dto.agent.EnrollRequest;
import com.xammer.cloud.xampulse.dto.agent.EnrollResponse;
import com.xammer.cloud.xampulse.dto.agent.HeartbeatRequest;
import com.xammer.cloud.xampulse.dto.agent.HeartbeatResponse;
import com.xammer.cloud.xampulse.dto.agent.StsRequest;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.repository.XpAgentCredentialRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.repository.XpLogSourceRepository;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.setup.InstanceCandidateService;
import com.xammer.cloud.xampulse.service.stream.TicketStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Nonce use, scope decisions, token issue, refresh rotation with reuse detection, heartbeat commands, with in-memory
 * repositories so the state really moves between calls.
 */
class AgentEnrollmentFlowTest {

    private static final String AWS = "123456789012";
    private static final String INST = "i-0123456789abcdef0";
    private static final String REGION = "ap-south-1";

    private final Map<String, String> store = new HashMap<>();
    private final TicketStore tickets = new TicketStore() {
        public void put(String k, String v, Duration ttl) { store.put(k, v); }
        public Optional<String> getAndDelete(String k) { return Optional.ofNullable(store.remove(k)); }
        public Optional<String> get(String k) { return Optional.ofNullable(store.get(k)); }
        public boolean putIfAbsent(String k, String v, Duration ttl) { return store.putIfAbsent(k, v) == null; }
    };
    private final EnrollmentNonceService nonces = new EnrollmentNonceService(tickets);
    private final StsIdentityVerifier verifier = mock(StsIdentityVerifier.class);
    private final CloudAccountRepository accounts = mock(CloudAccountRepository.class);
    private final XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
    private final XpAccountRegionRepository regions = mock(XpAccountRegionRepository.class);
    private final XpAgentRepository agents = mock(XpAgentRepository.class);
    private final XpAgentCredentialRepository credentials = mock(XpAgentCredentialRepository.class);
    private final XpLogSourceRepository sources = mock(XpLogSourceRepository.class);
    private final InstanceCandidateService candidates = mock(InstanceCandidateService.class);
    private final List<XpAgent> agentRows = new ArrayList<>();
    private final List<XpAgentCredential> credRows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T10:00:00Z"));
    private AgentTokenService tokens;
    private AgentEnrollmentService enrollment;
    private AgentSessionService sessions;
    private XpAccountRegion region;

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        KeyPair kp = g.generateKeyPair();
        tokens = new AgentTokenService(kp.getPrivate(), kp.getPublic(), "xamops-xampulse", "k1", clock);

        CloudAccount a = new CloudAccount();
        a.setId(7L);
        a.setAwsAccountId(AWS);
        when(accounts.findByAwsAccountId(AWS)).thenReturn(List.of(a));
        XpAccountConfig cfg = new XpAccountConfig();
        cfg.setCloudAccountId(7L);
        cfg.setState("DEPLOYING");
        when(configs.findByCloudAccountId(7L)).thenReturn(Optional.of(cfg));
        region = new XpAccountRegion();
        region.setCloudAccountId(7L);
        region.setRegion(REGION);
        region.setTargetMode("INSTANCES");
        region.setTargetInstanceIdsJson("[\"" + INST + "\"]");
        when(regions.findByCloudAccountIdAndRegion(7L, REGION)).thenReturn(Optional.of(region));
        when(sources.findByCloudAccountId(anyLong())).thenReturn(List.of());

        when(agents.findByCloudAccountId(anyLong())).thenAnswer(i -> agentRows);
        when(agents.findByCloudAccountIdAndInstanceId(anyLong(), anyString())).thenAnswer(i ->
                agentRows.stream().filter(x -> x.getCloudAccountId().equals(i.getArgument(0)) && x.getInstanceId().equals(i.getArgument(1))).findFirst());
        when(agents.findById(anyLong())).thenAnswer(i -> agentRows.stream().filter(x -> x.getId().equals(i.getArgument(0))).findFirst());
        when(agents.save(any())).thenAnswer(i -> {
            XpAgent x = i.getArgument(0);
            if (x.getId() == null) {
                x.setId(ids.incrementAndGet());
                agentRows.add(x);
            }
            return x;
        });
        when(credentials.findByAgentId(anyLong())).thenAnswer(i -> credRows.stream().filter(c -> c.getAgentId().equals(i.getArgument(0))).toList());
        when(credentials.findByRefreshHash(anyString())).thenAnswer(i -> credRows.stream().filter(c -> c.getRefreshHash().equals(i.getArgument(0))).findFirst());
        when(credentials.save(any())).thenAnswer(i -> {
            XpAgentCredential c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId(ids.incrementAndGet());
                credRows.add(c);
            }
            return c;
        });
        XamPulseProperties props = new XamPulseProperties();
        props.getGateway().setPublicUrl("https://live.example.com");
        AgentConfigService configService = new AgentConfigService(sources, props);
        enrollment = new AgentEnrollmentService(nonces, verifier, accounts, configs, regions, agents, credentials, tokens, configService,
                candidates, mock(AuditService.class), clock);
        sessions = new AgentSessionService(agents, credentials, tokens, configService, props, clock);
        when(verifier.verify(any())).thenReturn(new StsIdentityVerifier.Identity(AWS, "arn", INST));
    }

    private EnrollRequest request(String nonce) {
        return new EnrollRequest(nonce, REGION, new StsRequest("https://sts.ap-south-1.amazonaws.com/", "POST", Map.of(), "b"),
                "1.0.0", "linux", "amd64", "host-1");
    }

    private EnrollResponse enrolled() {
        var r = enrollment.enroll(request(nonces.issue(REGION)), "10.0.0.1");
        assertInstanceOf(AgentEnrollmentService.Success.class, r);
        return ((AgentEnrollmentService.Success) r).body();
    }

    // ------------------------------------------------------------------ nonce

    @Test
    void aNonceWorksOnceAndOnlyForItsRegion() {
        String n = nonces.issue(REGION);
        assertTrue(n.matches("^[0-9a-f]{32}$"), "128 random bits");
        assertFalse(nonces.consume(n, "us-east-1"), "wrong region");
        assertFalse(nonces.consume(n, REGION), "a failed attempt still consumed it");
        String m = nonces.issue(REGION);
        assertTrue(nonces.consume(m, REGION));
        assertFalse(nonces.consume(m, REGION), "replay must fail");
        assertFalse(nonces.consume("zz", REGION));
        assertFalse(nonces.consume(null, REGION));
        assertNotEquals(nonces.issue(REGION), nonces.issue(REGION));
    }

    // ------------------------------------------------------------------ enrolment

    @Test
    void successPersistsAHashedRefreshTokenAndIssuesAUsableAccessToken() {
        EnrollResponse res = enrolled();
        assertEquals(1, credRows.size());
        assertNotEquals(res.refreshToken(), credRows.get(0).getRefreshHash());
        assertEquals(AgentEnrollmentService.sha256Hex(res.refreshToken()), credRows.get(0).getRefreshHash());
        assertEquals(64, credRows.get(0).getRefreshHash().length());
        assertTrue(credRows.get(0).getExpiresAt().isAfter(clock.instant().plus(Duration.ofDays(29))));
        var claims = tokens.verify(res.accessToken()).orElseThrow();
        assertEquals(INST, claims.instanceId());
        assertEquals(7L, claims.tenantId());
        XpAgent a = agentRows.get(0);
        assertEquals("ACTIVE", a.getState());
        assertEquals(REGION, a.getRegion());
        assertEquals("1.0.0", a.getAgentVersion());
        assertEquals("https://live.example.com/xampulse-ingest", res.config().gatewayUrl());
        assertEquals("T1", res.config().level());
    }

    @Test
    void anInstanceThatIsNotSelectedGetsNothingElseThan403WithNoDetail() {
        region.setTargetInstanceIdsJson("[\"i-0fffffffffffffff0\"]");
        assertInstanceOf(AgentEnrollmentService.Rejected.class, enrollment.enroll(request(nonces.issue(REGION)), "10.0.0.1"));
        assertTrue(agentRows.isEmpty() && credRows.isEmpty());
    }

    @Test
    void anUnknownReplayedOrWrongRegionNonceIsRejectedBeforeAnythingIsVerified() {
        assertInstanceOf(AgentEnrollmentService.Rejected.class, enrollment.enroll(request("00000000000000000000000000000000"), "ip"));
        String n = nonces.issue(REGION);
        enrollment.enroll(request(n), "ip");
        assertInstanceOf(AgentEnrollmentService.Rejected.class, enrollment.enroll(request(n), "ip"));
        verify(verifier, times(1)).verify(any());
    }

    @Test
    void aFailedIdentityProofIsRejected() {
        when(verifier.verify(any())).thenThrow(new StsIdentityVerifier.Rejected("bad"));
        assertInstanceOf(AgentEnrollmentService.Rejected.class, enrollment.enroll(request(nonces.issue(REGION)), "ip"));
        assertTrue(agentRows.isEmpty());
    }

    @Test
    void anAccountThatIsNotSetUpOrIsDisconnectedCannotEnrol() {
        for (String state : new String[] {"DISCONNECTED", "NOT_SET_UP"}) {
            configs.findByCloudAccountId(7L).get().setState(state);
            assertInstanceOf(AgentEnrollmentService.Rejected.class, enrollment.enroll(request(nonces.issue(REGION)), "ip"), state);
        }
        when(configs.findByCloudAccountId(7L)).thenReturn(Optional.empty());
        assertInstanceOf(AgentEnrollmentService.Rejected.class, enrollment.enroll(request(nonces.issue(REGION)), "ip"));
    }

    @Test
    void anUnknownAwsAccountIsRejected() {
        when(verifier.verify(any())).thenReturn(new StsIdentityVerifier.Identity("999999999999", "arn", INST));
        when(accounts.findByAwsAccountId("999999999999")).thenReturn(List.of());
        assertInstanceOf(AgentEnrollmentService.Rejected.class, enrollment.enroll(request(nonces.issue(REGION)), "ip"));
    }

    @Test
    void tagModeReturnsPendingUntilInventoryHasSeenTheTag() {
        region.setTargetMode("TAG");
        region.setObserveTagKey("xamops:observe");
        region.setObserveTagValue("true");
        when(candidates.inventoryLookup(7L, INST)).thenReturn(Optional.empty());
        var r = enrollment.enroll(request(nonces.issue(REGION)), "ip");
        var pending = assertInstanceOf(AgentEnrollmentService.Pending.class, r);
        assertEquals("PENDING_TAG_SYNC", pending.body().reason());
        assertEquals(60, pending.body().retryAfterSec());
        assertTrue(agentRows.isEmpty());

        var seen = new InstanceCandidateService.Seen(INST, "web", REGION, "running", Map.of("xamops:observe", "true"), null);
        when(candidates.inventoryLookup(7L, INST)).thenReturn(Optional.of(new InstanceCandidateService.Lookup(seen, true)));
        assertInstanceOf(AgentEnrollmentService.Success.class, enrollment.enroll(request(nonces.issue(REGION)), "ip"));
    }

    @Test
    void pausedAndRevokedAgentsCannotEnrolAgain() {
        enrolled();
        for (String state : new String[] {"PAUSED", "REVOKED"}) {
            agentRows.get(0).setState(state);
            assertInstanceOf(AgentEnrollmentService.Rejected.class, enrollment.enroll(request(nonces.issue(REGION)), "ip"), state);
        }
        agentRows.get(0).setState("PENDING"); // an admin resumed it
        assertInstanceOf(AgentEnrollmentService.Success.class, enrollment.enroll(request(nonces.issue(REGION)), "ip"));
    }

    @Test
    void reEnrolmentRevokesEveryEarlierCredential() {
        EnrollResponse first = enrolled();
        enrolled();
        assertEquals(2, credRows.size());
        assertNotNull(credRows.get(0).getRevokedAt());
        assertNull(credRows.get(1).getRevokedAt());
        assertEquals(1, agentRows.size());
        assertEquals(AgentSessionService.RefreshStatus.DENIED, sessions.refresh(first.refreshToken()).status());
    }

    @Test
    void enrolmentOfOneInstanceIsRateLimited() {
        int ok = 0;
        boolean limited = false;
        for (int i = 0; i < 10; i++) {
            var r = enrollment.enroll(request(nonces.issue(REGION)), "ip");
            if (r instanceof AgentEnrollmentService.TooMany) {
                limited = true;
            } else if (r instanceof AgentEnrollmentService.Success) {
                ok++;
            }
        }
        assertTrue(limited);
        assertEquals(AgentEnrollmentService.MAX_ENROLLS_PER_INSTANCE_PER_MINUTE, ok);
    }

    // ------------------------------------------------------------------ refresh rotation

    @Test
    void refreshRotatesTheTokenAndKeepsTheFamily() {
        EnrollResponse e = enrolled();
        clock.now = clock.now.plus(Duration.ofHours(23));
        var r = sessions.refresh(e.refreshToken());
        assertEquals(AgentSessionService.RefreshStatus.OK, r.status());
        assertNotEquals(e.refreshToken(), r.pair().refreshToken());
        assertTrue(tokens.verify(r.pair().accessToken()).isPresent());
        assertEquals(2, credRows.size());
        assertNotNull(credRows.get(0).getRevokedAt(), "the used token is spent");
        assertEquals(credRows.get(0).getFamilyId(), credRows.get(1).getFamilyId());
        assertEquals(AgentSessionService.RefreshStatus.OK, sessions.refresh(r.pair().refreshToken()).status());
    }

    @Test
    void reusingASpentRefreshTokenRevokesTheWholeFamily() {
        EnrollResponse e = enrolled();
        var rotated = sessions.refresh(e.refreshToken());
        assertEquals(AgentSessionService.RefreshStatus.OK, rotated.status());
        // The old token comes back: it was copied. Everything in the family dies, including the new token.
        assertEquals(AgentSessionService.RefreshStatus.DENIED, sessions.refresh(e.refreshToken()).status());
        assertTrue(credRows.stream().allMatch(c -> c.getRevokedAt() != null));
        assertEquals(AgentSessionService.RefreshStatus.DENIED, sessions.refresh(rotated.pair().refreshToken()).status());
    }

    @Test
    void refreshRefusesExpiredUnknownMalformedAndInactiveAgents() {
        EnrollResponse e = enrolled();
        assertEquals(AgentSessionService.RefreshStatus.DENIED, sessions.refresh("not-a-token-but-long-enough-123456").status());
        assertEquals(AgentSessionService.RefreshStatus.DENIED, sessions.refresh(null).status());
        assertEquals(AgentSessionService.RefreshStatus.DENIED, sessions.refresh("short").status());
        agentRows.get(0).setState("PAUSED");
        assertEquals(AgentSessionService.RefreshStatus.DENIED, sessions.refresh(e.refreshToken()).status());
        agentRows.get(0).setState("ACTIVE");
        clock.now = clock.now.plus(Duration.ofDays(31));
        assertEquals(AgentSessionService.RefreshStatus.DENIED, sessions.refresh(e.refreshToken()).status());
    }

    // ------------------------------------------------------------------ heartbeat

    private HeartbeatRequest hb(String version) {
        return new HeartbeatRequest(version, "0.164.0", 60L, new HeartbeatRequest.Queue(0L, 0L), List.of(), List.of());
    }

    @Test
    void heartbeatRecordsLastSeenVersionsAndAnswersNone() {
        EnrollResponse e = enrolled();
        clock.now = clock.now.plusSeconds(30);
        XpAgent agent = sessions.authenticate(e.accessToken()).orElseThrow();
        HeartbeatResponse r = sessions.heartbeat(agent, hb("1.0.1"));
        assertEquals("NONE", r.command());
        assertEquals(clock.now, agent.getLastSeenAt());
        assertEquals("1.0.1", agent.getAgentVersion());
        assertEquals("0.164.0", agent.getCollectorVersion());
    }

    @Test
    void revokedAndPausedAgentsAreToldToUninstall() {
        EnrollResponse e = enrolled();
        XpAgent agent = sessions.authenticate(e.accessToken()).orElseThrow();
        for (String state : new String[] {"REVOKED", "PAUSED"}) {
            agent.setState(state);
            assertEquals("UNINSTALL", sessions.heartbeat(agent, hb("1.0.0")).command(), state);
        }
        assertNull(sessions.config(agent), "a revoked agent may not read configuration");
    }

    @Test
    void reloadConfigIsDeliveredOnceAndUninstallStaysUntilTheAgentIsGone() {
        EnrollResponse e = enrolled();
        XpAgent agent = sessions.authenticate(e.accessToken()).orElseThrow();
        agent.setPendingCommand("RELOAD_CONFIG");
        agent.setConfigVersion(5);
        HeartbeatResponse first = sessions.heartbeat(agent, hb("1.0.0"));
        assertEquals("RELOAD_CONFIG", first.command());
        assertEquals(5, first.configVersion());
        assertEquals("NONE", sessions.heartbeat(agent, hb("1.0.0")).command());
        agent.setPendingCommand("UNINSTALL");
        assertEquals("UNINSTALL", sessions.heartbeat(agent, hb("1.0.0")).command());
        assertEquals("UNINSTALL", sessions.heartbeat(agent, hb("1.0.0")).command());
    }

    @Test
    void upgradeIsOfferedOnlyWhenALatestVersionIsConfiguredAndDiffers() {
        EnrollResponse e = enrolled();
        XpAgent agent = sessions.authenticate(e.accessToken()).orElseThrow();
        assertEquals("NONE", sessions.heartbeat(agent, hb("1.0.0")).command());
        XamPulseProperties props = new XamPulseProperties();
        props.getSetup().setLatestAgentVersion("1.1.0");
        AgentSessionService withLatest = new AgentSessionService(agents, credentials, tokens, new AgentConfigService(sources, props), props, clock);
        // No pinned release key: upgrades are manual, so the server never offers one.
        assertEquals("NONE", withLatest.heartbeat(agent, hb("1.0.0")).command());
        props.getSetup().setReleasePublicKeyPem("-----BEGIN PUBLIC KEY-----\ntest-only\n-----END PUBLIC KEY-----");
        HeartbeatResponse r = withLatest.heartbeat(agent, hb("1.0.0"));
        assertEquals("UPGRADE", r.command());
        assertEquals("1.1.0", r.upgradeTo());
        assertEquals("NONE", withLatest.heartbeat(agent, hb("1.1.0")).command());
    }

    @Test
    void accessTokensThatAreExpiredForeignOrForUnknownAgentsDoNotAuthenticate() {
        EnrollResponse e = enrolled();
        assertTrue(sessions.authenticate(e.accessToken()).isPresent());
        assertTrue(sessions.authenticate(null).isEmpty());
        assertTrue(sessions.authenticate("garbage").isEmpty());
        clock.now = clock.now.plus(Duration.ofHours(25));
        assertTrue(sessions.authenticate(e.accessToken()).isEmpty());
        clock.now = clock.now.minus(Duration.ofHours(25));
        agentRows.clear();
        assertTrue(sessions.authenticate(e.accessToken()).isEmpty());
    }
}
