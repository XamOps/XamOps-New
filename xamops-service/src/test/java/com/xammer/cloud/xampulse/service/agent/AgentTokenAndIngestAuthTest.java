package com.xammer.cloud.xampulse.service.agent;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.controller.XamPulseIngestAuthController;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTokenAndIngestAuthTest {

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private KeyPair keys;
    private KeyPair otherKeys;
    private AgentTokenService tokens;
    private Clock clock;

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    @BeforeEach
    void setUp() throws Exception {
        keys = ec();
        otherKeys = ec();
        clock = Clock.fixed(t0, ZoneOffset.UTC);
        tokens = new AgentTokenService(keys.getPrivate(), keys.getPublic(), "xamops-xampulse", "k1", clock);
    }

    @Test
    void issuesAndVerifiesAnEs256TokenCarryingInstanceAndTenant() {
        String t = tokens.issue("i-0abc", 77L);
        var c = tokens.verify(t).orElseThrow();
        assertEquals("i-0abc", c.instanceId());
        assertEquals(77L, c.tenantId());
        assertEquals(t0.plus(Duration.ofHours(24)), c.expiresAt());
        String header = new String(Base64.getUrlDecoder().decode(t.split("\\.")[0]));
        assertTrue(header.contains("ES256") && header.contains("\"kid\":\"k1\""), header);
    }

    @Test
    void expiredTokensAreRejected() {
        String t = tokens.issue("i-1", 1L);
        AgentTokenService later = new AgentTokenService(keys.getPrivate(), keys.getPublic(), "xamops-xampulse", "k1",
                Clock.fixed(t0.plus(Duration.ofHours(25)), ZoneOffset.UTC));
        assertTrue(later.verify(t).isEmpty());
        AgentTokenService almost = new AgentTokenService(keys.getPrivate(), keys.getPublic(), "xamops-xampulse", "k1",
                Clock.fixed(t0.plus(Duration.ofHours(23)), ZoneOffset.UTC));
        assertTrue(almost.verify(t).isPresent());
    }

    @Test
    void tokenSignedByAnotherKeyOrIssuerIsRejected() throws Exception {
        AgentTokenService attacker = new AgentTokenService(otherKeys.getPrivate(), otherKeys.getPublic(), "xamops-xampulse", "k1", clock);
        assertTrue(tokens.verify(attacker.issue("i-1", 1L)).isEmpty());
        AgentTokenService wrongIssuer = new AgentTokenService(keys.getPrivate(), keys.getPublic(), "someone-else", "k1", clock);
        assertTrue(tokens.verify(wrongIssuer.issue("i-1", 1L)).isEmpty());
    }

    @Test
    void algNoneAndHmacConfusionAreRejected() {
        String none = Jwts.builder().setSubject("i-1").setIssuer("xamops-xampulse").claim("tid", 1)
                .setExpiration(Date.from(t0.plus(Duration.ofHours(1)))).compact();
        assertTrue(tokens.verify(none).isEmpty());
        // HS256 signed with the public key bytes as the secret (classic algorithm confusion)
        byte[] pub = keys.getPublic().getEncoded();
        String hs = Jwts.builder().setSubject("i-1").setIssuer("xamops-xampulse").claim("tid", 1)
                .setExpiration(Date.from(t0.plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(java.util.Arrays.copyOf(pub, 64)), SignatureAlgorithm.HS256).compact();
        assertTrue(tokens.verify(hs).isEmpty());
        assertTrue(tokens.verify("not.a.jwt").isEmpty());
        assertTrue(tokens.verify(null).isEmpty());
        assertTrue(tokens.verify("a".repeat(5000)).isEmpty());
    }

    @Test
    void tamperedTenantClaimInvalidatesTheSignature() {
        String t = tokens.issue("i-1", 1L);
        String[] p = t.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(p[1]));
        String forged = payload.replace("\"tid\":1", "\"tid\":2");
        assertNotEquals(payload, forged);
        String tampered = p[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes()) + "." + p[2];
        assertTrue(tokens.verify(tampered).isEmpty());
    }

    @Test
    void withoutConfiguredKeysNothingVerifiesAndNothingIsIssued() {
        AgentTokenService none = new AgentTokenService(new XamPulseProperties());
        assertTrue(none.verify(tokens.issue("i-1", 1L)).isEmpty());
        assertThrows(IllegalStateException.class, () -> none.issue("i-1", 1L));
    }

    @Test
    void pemKeysFromConfigurationAreParsed() throws Exception {
        XamPulseProperties props = new XamPulseProperties();
        props.getJwt().setPrivateKeyPem("-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----");
        props.getJwt().setPublicKeyPem("-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        AgentTokenService fromPem = new AgentTokenService(props);
        assertTrue(fromPem.verify(fromPem.issue("i-9", 9L)).isPresent());
        props.getJwt().setPrivateKeyPem("garbage");
        assertThrows(IllegalStateException.class, () -> new AgentTokenService(props));
    }

    // ---------------- authorizer ----------------

    private XpAgent agent(String state) {
        XpAgent a = new XpAgent();
        a.setCloudAccountId(77L);
        a.setInstanceId("i-0abc");
        a.setState(state);
        return a;
    }

    @Test
    void authorizerAcceptsActiveAgentsOnlyAndCachesTheLookup() {
        XpAgentRepository agents = mock(XpAgentRepository.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(agents.findByCloudAccountIdAndInstanceId(77L, "i-0abc")).thenReturn(Optional.of(agent("ACTIVE")));
        XpAccountConfig cfg = new XpAccountConfig();
        cfg.setHotDays(21);
        when(configs.findByCloudAccountId(77L)).thenReturn(Optional.of(cfg));
        IngestAuthorizer auth = new IngestAuthorizer(tokens, agents, configs);
        String bearer = "Bearer " + tokens.issue("i-0abc", 77L);
        var d = auth.authorize(bearer);
        assertEquals(IngestAuthorizer.Status.OK, d.status());
        assertEquals(77L, d.tenantId());
        assertEquals("i-0abc", d.instanceId());
        assertEquals(21, d.retainDays());
        auth.authorize(bearer);
        verify(agents, times(1)).findByCloudAccountIdAndInstanceId(77L, "i-0abc");
    }

    @Test
    void revokedPendingAndUnknownAgentsAreDenied() {
        XpAgentRepository agents = mock(XpAgentRepository.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        IngestAuthorizer auth = new IngestAuthorizer(tokens, agents, configs);
        String bearer = "Bearer " + tokens.issue("i-0abc", 77L);
        when(agents.findByCloudAccountIdAndInstanceId(77L, "i-0abc")).thenReturn(Optional.of(agent("REVOKED")));
        assertEquals(IngestAuthorizer.Status.UNAUTHORIZED, auth.authorize(bearer).status());
        IngestAuthorizer fresh = new IngestAuthorizer(tokens, agents, configs);
        when(agents.findByCloudAccountIdAndInstanceId(77L, "i-0abc")).thenReturn(Optional.empty());
        assertEquals(IngestAuthorizer.Status.UNAUTHORIZED, fresh.authorize(bearer).status());
        assertEquals(IngestAuthorizer.Status.UNAUTHORIZED, fresh.authorize("Bearer garbage").status());
        assertEquals(IngestAuthorizer.Status.UNAUTHORIZED, fresh.authorize(null).status());
        verify(configs, never()).findByCloudAccountId(anyLong());
    }

    @Test
    void agentFromTenantAMintedTokenCannotActAsTenantB() {
        XpAgentRepository agents = mock(XpAgentRepository.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        // Only tenant 77 has this instance; a token that claims tenant 88 finds nothing.
        when(agents.findByCloudAccountIdAndInstanceId(77L, "i-0abc")).thenReturn(Optional.of(agent("ACTIVE")));
        when(agents.findByCloudAccountIdAndInstanceId(88L, "i-0abc")).thenReturn(Optional.empty());
        IngestAuthorizer auth = new IngestAuthorizer(tokens, agents, configs);
        assertEquals(IngestAuthorizer.Status.UNAUTHORIZED, auth.authorize("Bearer " + tokens.issue("i-0abc", 88L)).status());
    }

    // ---------------- controller (nginx auth_request target) ----------------

    private MockMvc mvcWithSecret(String serverSecret) {
        XpAgentRepository agents = mock(XpAgentRepository.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(agents.findByCloudAccountIdAndInstanceId(eq(77L), eq("i-0abc"))).thenReturn(Optional.of(agent("ACTIVE")));
        XamPulseProperties props = new XamPulseProperties();
        props.setInternalSecret(serverSecret);
        return MockMvcBuilders.standaloneSetup(new XamPulseIngestAuthController(props, new IngestAuthorizer(tokens, agents, configs))).build();
    }

    @Test
    void withoutOrWithTheWrongSecretTheEndpointDoesNotExist() throws Exception {
        MockMvc mvc = mvcWithSecret("s3cret-value");
        String token = "Bearer " + tokens.issue("i-0abc", 77L);
        mvc.perform(get("/internal/xampulse/auth").header("X-Xp-Token", token)).andExpect(status().isNotFound());
        mvc.perform(get("/internal/xampulse/auth").header("X-Xp-Internal", "wrong").header("X-Xp-Token", token)).andExpect(status().isNotFound());
        mvcWithSecret("").perform(get("/internal/xampulse/auth").header("X-Xp-Internal", "").header("X-Xp-Token", token)).andExpect(status().isNotFound());
        mvcWithSecret(null).perform(get("/internal/xampulse/auth").header("X-Xp-Token", token)).andExpect(status().isNotFound());
    }

    @Test
    void validSecretAndTokenReturnTheTrustedTenantHeaders() throws Exception {
        MockMvc mvc = mvcWithSecret("s3cret-value");
        mvc.perform(get("/internal/xampulse/auth").header("X-Xp-Internal", "s3cret-value")
                        .header("X-Xp-Token", "Bearer " + tokens.issue("i-0abc", 77L)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Xp-Tenant", "77"))
                .andExpect(header().string("X-Xp-Instance", "i-0abc"))
                .andExpect(header().string("X-Xp-Retain-Days", "14"));
    }

    @Test
    void invalidOrRevokedTokenIsUnauthorised() throws Exception {
        MockMvc mvc = mvcWithSecret("s3cret-value");
        mvc.perform(get("/internal/xampulse/auth").header("X-Xp-Internal", "s3cret-value").header("X-Xp-Token", "Bearer nope"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/xampulse/auth").header("X-Xp-Internal", "s3cret-value")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/xampulse/auth").header("X-Xp-Internal", "s3cret-value")
                        .header("X-Xp-Token", "Bearer " + tokens.issue("i-other", 77L)))
                .andExpect(status().isUnauthorized());
    }
    // ---------------- quota ----------------
    @Test
    void quotaIsOnlyEvaluatedForAValidActiveAgent() {
        XpAgentRepository agents = mock(XpAgentRepository.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        IngestQuotaService quota = mock(IngestQuotaService.class);
        when(quota.retryAfterIfExceeded(77L)).thenReturn(java.util.OptionalLong.of(120));
        IngestAuthorizer auth = new IngestAuthorizer(tokens, agents, configs, quota);
        // Revoked agent: 401 regardless of quota, and the quota is never consulted.
        when(agents.findByCloudAccountIdAndInstanceId(77L, "i-0abc")).thenReturn(Optional.of(agent("REVOKED")));
        assertEquals(IngestAuthorizer.Status.UNAUTHORIZED, auth.authorize("Bearer " + tokens.issue("i-0abc", 77L)).status());
        assertEquals(IngestAuthorizer.Status.UNAUTHORIZED, auth.authorize("Bearer garbage").status());
        verify(quota, never()).retryAfterIfExceeded(anyLong());
        // Active agent over quota.
        IngestAuthorizer active = new IngestAuthorizer(tokens, agents, configs, quota);
        when(agents.findByCloudAccountIdAndInstanceId(77L, "i-0abc")).thenReturn(Optional.of(agent("ACTIVE")));
        var d = active.authorize("Bearer " + tokens.issue("i-0abc", 77L));
        assertEquals(IngestAuthorizer.Status.QUOTA_EXCEEDED, d.status());
        assertEquals(120, d.retryAfterSec());
    }
}
