package com.xammer.cloud.controller;

import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.config.SecurityConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ExplainRequestRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.security.TrialEnforcementFilter;
import com.xammer.cloud.service.AgentApiKeyService;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.DbAgentService;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import com.xammer.cloud.service.OpenObserveService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.auth.TrialService;
import com.xammer.cloud.service.sre.SreInvestigationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-4/5 behavioural spec (committed re-creation of the recovered AgentEndpointAuthHarnessTest).
 *
 * <p>
 * Runs the real {@link SecurityConfig} chains, the real {@link CloudAccountAccessService}, the
 * real {@link AgentController} and the new {@link DbAgentSetupController} through MockMvc with only
 * their collaborators mocked. Proves: the wizard endpoints require sign-in + entitlement; the
 * config response carries {@code hasPassword} and never the password; Login-As reaches them; a
 * bare-PK identifier is refused; the old {@code /api/v1/agent/db/*} paths are gone; and the
 * agent-key endpoints are unchanged. (The dashboard agent-read endpoints GET /status/{id},
 * /status?… and /events/{id} are covered by AgentReadEndpointLockdownTest under origin's
 * FR-10.2/F-25 agent-key model.)
 *
 * <p>
 * Adapted to SEC-5: {@link DbAgentService#saveConnectionConfig} has no password parameter, so the
 * stub/verify use the 8-arg signature and the password-persistence test asserts no password is
 * ever passed (replacing the recovered "blank keeps the stored one" behaviour).
 */
@SpringJUnitWebConfig(DbAgentEndpointAuthTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.impersonation.secret-key=" + DbAgentEndpointAuthTest.SECRET,
        "app.remember-me.key=test-only-remember-me-key"
})
class DbAgentEndpointAuthTest {

    static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";
    static final String ACCOUNT = "111111111111";
    static final String AGENT = "prod-db";
    static final String STORED_PASSWORD = "Stored-Example-Pass-1";
    static final String AGENT_KEY = "xak_valid_test_key";
    static final long IMPERSONATED_USER_ID = 42L;

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, TrialEnforcementFilter.class,
            GlobalExceptionHandler.class, CloudAccountAccessService.class, DbOpsFeatureFlags.class,
            AgentController.class, DbAgentSetupController.class})
    static class TestConfig {
    }

    // SecurityConfig / filter collaborators
    @MockitoBean UserRepository userRepository;
    @MockitoBean CustomAuthenticationSuccessHandler authenticationSuccessHandler;
    @MockitoBean CustomAuthenticationFailureHandler authenticationFailureHandler;
    @MockitoBean CustomLogoutSuccessHandler logoutSuccessHandler;
    @MockitoBean UserDetailsService userDetailsService;
    @MockitoBean TrialService trialService;
    // CloudAccountAccessService collaborators
    @MockitoBean UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    @MockitoBean CloudAccountRepository cloudAccountRepository;
    // AgentController / DbAgentSetupController collaborators
    @MockitoBean AgentIngestService agentIngestService;
    @MockitoBean DbAgentService dbAgentService;
    @MockitoBean KubernetesClusterConfigRepository clusterConfigRepository;
    @MockitoBean AgentDataCacheRepository agentDataCacheRepository;
    @MockitoBean AgentEventRepository agentEventRepository;
    @MockitoBean RedisCacheService redisCacheService;
    @MockitoBean SreInvestigationService sreInvestigationService;
    @MockitoBean OpenObserveService openObserveService;
    @MockitoBean ExplainRequestRepository explainRequestRepository;
    @MockitoBean AgentApiKeyService agentApiKeyService;
    @MockitoBean K8sAutomationPolicyNormalizer policyNormalizer;
    @MockitoBean K8sAutomationAccessService automationAccessService;

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    /** Root user of client 10, which owns ACCOUNT. */
    final ClientUserDetails owner = new ClientUserDetails("owner@example.com", "pw",
            List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 10L, 500L, "tenant-a");
    /** Root user of client 99, which does not. */
    final ClientUserDetails outsider = new ClientUserDetails("outsider@example.com", "pw",
            List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 99L, 600L, "tenant-b");

    DbAgentConfig cfg;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Client client = new Client();
        client.setId(10L);
        CloudAccount account = new CloudAccount();
        account.setId(1L);
        account.setClient(client);
        when(cloudAccountRepository.findByProviderAccountId(ACCOUNT)).thenReturn(Optional.of(account));
        when(cloudAccountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(dbAgentService.resolveAccount(ACCOUNT)).thenReturn(account);

        // A cluster owned by a DIFFERENT account (client 99), to prove the dashboard guard reads
        // config.getCloudAccountId() rather than the path id.
        CloudAccount otherAccount = new CloudAccount();
        otherAccount.setId(2L);
        Client otherClient = new Client();
        otherClient.setId(99L);
        otherAccount.setClient(otherClient);
        when(cloudAccountRepository.findById(2L)).thenReturn(Optional.of(otherAccount));

        // Root users (no createdBy) of their own client; no explicit grants.
        when(userRepository.findById(500L)).thenReturn(Optional.of(new User()));
        when(userRepository.findById(600L)).thenReturn(Optional.of(new User()));
        User impersonated = new User();
        impersonated.setId(IMPERSONATED_USER_ID);
        impersonated.setUsername("impersonated-user");
        impersonated.setClient(client);
        when(userRepository.findById(IMPERSONATED_USER_ID)).thenReturn(Optional.of(impersonated));

        cfg = new DbAgentConfig();
        cfg.setId(5L);
        cfg.setCloudAccountId(1L);
        cfg.setAgentName(AGENT);
        cfg.setStatus("active");
        cfg.setAgentIp("10.0.0.5");
        cfg.setLastCheckin(LocalDateTime.now());
        cfg.setDbEngine("postgres");
        cfg.setDbHost("db.internal");
        cfg.setDbPort("5432");
        cfg.setDbName("app");
        cfg.setDbUser("xamops_reader");
        cfg.setDbPassword(STORED_PASSWORD);
        when(dbAgentService.loadConnectionConfig(ACCOUNT, AGENT)).thenReturn(cfg);
        when(dbAgentService.findDbAgent(ACCOUNT, AGENT)).thenReturn(Optional.of(cfg));
        when(dbAgentService.registerDbAgent(ACCOUNT, AGENT)).thenReturn(cfg);
        when(dbAgentService.deleteDbAgent(ACCOUNT, AGENT)).thenReturn(true);
        when(dbAgentService.saveConnectionConfig(eq(ACCOUNT), eq(AGENT), any(), any(), any(), any(), any(), any()))
                .thenReturn(cfg);
        when(dbAgentService.getCachedData(5L)).thenReturn(List.of());
        when(dbAgentService.processIngest(any(), any(), any())).thenReturn(AgentIngestResponse.success());

        KubernetesClusterConfig cluster = new KubernetesClusterConfig();
        cluster.setId(7L);
        cluster.setCloudAccountId(1L);
        cluster.setClusterName("prod-eks");
        when(clusterConfigRepository.findById(7L)).thenReturn(Optional.of(cluster));
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(1L, "prod-eks")).thenReturn(Optional.of(cluster));

        // Cluster 8 belongs to otherAccount (client 99): owner is not entitled to it.
        KubernetesClusterConfig otherCluster = new KubernetesClusterConfig();
        otherCluster.setId(8L);
        otherCluster.setCloudAccountId(2L);
        otherCluster.setClusterName("other-eks");
        when(clusterConfigRepository.findById(8L)).thenReturn(Optional.of(otherCluster));

        AgentEvent event = new AgentEvent(7L, "checkin");
        event.setAgentIp("10.0.0.9");
        when(agentEventRepository.findByClusterConfigIdOrderByCreatedAtDesc(eq(7L), any()))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(agentEventRepository.findRecentEvents(eq(7L), any())).thenReturn(List.of());
        when(agentEventRepository.findLastSuccessfulPush(eq(7L), any())).thenReturn(List.of());

        when(agentIngestService.authenticate(eq(AGENT_KEY), any())).thenReturn(AgentPrincipal.account(ACCOUNT));
        when(agentIngestService.checkOwnership(any(), anyString(), any())).thenReturn(null);
        when(agentIngestService.validateApiKey(AGENT_KEY)).thenReturn(true);
        when(explainRequestRepository.findFirstByAgentNameAndStatusOrderByCreatedAtAsc(AGENT, "PENDING"))
                .thenReturn(Optional.empty());
    }

    private static String loginAsToken() {
        return Jwts.builder()
                .setSubject(String.valueOf(IMPERSONATED_USER_ID))
                .claim("role", "ROLE_XAMOPS")
                .setIssuer("billops")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60L * 60 * 1000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)), SignatureAlgorithm.HS256)
                .compact();
    }

    private MvcResult run(String label, RequestBuilder request) throws Exception {
        MvcResult r = mvc.perform(request).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).as("no response may carry the stored password: " + label).doesNotContain(STORED_PASSWORD);
        return r;
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static final String Q = "?agentName=" + AGENT + "&cloudAccountId=" + ACCOUNT;
    private static final String CONFIG_BODY = "{\"agentName\":\"" + AGENT + "\",\"cloudAccountId\":\"" + ACCOUNT
            + "\",\"dbEngine\":\"postgres\",\"dbHost\":\"db.internal\",\"dbPort\":\"5432\",\"dbName\":\"app\","
            + "\"dbUser\":\"xamops_reader\",\"serverUrl\":\"\"";
    private static final String REGISTER_BODY = "{\"agentName\":\"" + AGENT + "\",\"cloudAccountId\":\"" + ACCOUNT + "\"}";

    @Test
    void wizardEndpointsRequireSignInAndEntitlement() throws Exception {
        // Unauthenticated: 401 from the security chain, before any handler runs.
        assertThat(status(run("anon GET /config", get("/api/xamops/db-agent/config" + Q)))).isEqualTo(401);
        assertThat(status(run("anon POST /config", post("/api/xamops/db-agent/config")
                .contentType("application/json").content(CONFIG_BODY + ",\"dbPassword\":\"x\"}")))).isEqualTo(401);
        assertThat(status(run("anon GET /status", get("/api/xamops/db-agent/status" + Q)))).isEqualTo(401);
        assertThat(status(run("anon POST /register", post("/api/xamops/db-agent/register")
                .contentType("application/json").content(REGISTER_BODY)))).isEqualTo(401);
        assertThat(status(run("anon DELETE /register", delete("/api/xamops/db-agent/register" + Q)))).isEqualTo(401);

        // Signed in, but not entitled to the account: 403, and nothing is read or written.
        assertThat(status(run("outsider GET /config", get("/api/xamops/db-agent/config" + Q).with(user(outsider))))).isEqualTo(403);
        assertThat(status(run("outsider POST /config", post("/api/xamops/db-agent/config").with(user(outsider))
                .contentType("application/json").content(CONFIG_BODY + ",\"dbPassword\":\"x\"}")))).isEqualTo(403);
        assertThat(status(run("outsider GET /status", get("/api/xamops/db-agent/status" + Q).with(user(outsider))))).isEqualTo(403);
        assertThat(status(run("outsider POST /register", post("/api/xamops/db-agent/register").with(user(outsider))
                .contentType("application/json").content(REGISTER_BODY)))).isEqualTo(403);
        assertThat(status(run("outsider DELETE /register", delete("/api/xamops/db-agent/register" + Q).with(user(outsider))))).isEqualTo(403);
        verify(dbAgentService, never()).loadConnectionConfig(anyString(), anyString());
        verify(dbAgentService, never()).saveConnectionConfig(any(), any(), any(), any(), any(), any(), any(), any());
        verify(dbAgentService, never()).findDbAgent(anyString(), anyString());
        verify(dbAgentService, never()).registerDbAgent(anyString(), anyString());
        verify(dbAgentService, never()).deleteDbAgent(anyString(), anyString());

        // Entitled: 200, and the config reports hasPassword instead of the password.
        MvcResult cfgRes = run("owner GET /config", get("/api/xamops/db-agent/config" + Q).with(user(owner)));
        assertThat(status(cfgRes)).isEqualTo(200);
        assertThat(body(cfgRes)).contains("\"hasPassword\":true").doesNotContain("dbPassword");
        assertThat(status(run("owner GET /status", get("/api/xamops/db-agent/status" + Q).with(user(owner))))).isEqualTo(200);
        assertThat(status(run("owner POST /register", post("/api/xamops/db-agent/register").with(user(owner))
                .contentType("application/json").content(REGISTER_BODY)))).isEqualTo(200);
        assertThat(status(run("owner DELETE /register", delete("/api/xamops/db-agent/register" + Q).with(user(owner))))).isEqualTo(200);
    }

    @Test
    void postConfigNeverPersistsAPassword() throws Exception {
        // SEC-5: saveConnectionConfig has no password parameter. A POST with or without a
        // dbPassword in the JSON returns 200 and the password is never passed to the service.
        MvcResult withPw = run("owner POST config (dbPassword present)",
                post("/api/xamops/db-agent/config").with(user(owner))
                        .contentType("application/json").content(CONFIG_BODY + ",\"dbPassword\":\"New-Pass-2\"}"));
        assertThat(status(withPw)).isEqualTo(200);
        assertThat(body(withPw)).contains("\"hasPassword\":true");

        MvcResult withoutPw = run("owner POST config (no dbPassword)",
                post("/api/xamops/db-agent/config").with(user(owner))
                        .contentType("application/json").content(CONFIG_BODY + "}"));
        assertThat(status(withoutPw)).isEqualTo(200);

        // Both saves go through the 8-arg signature that structurally cannot carry a password.
        verify(dbAgentService, times(2))
                .saveConnectionConfig(eq(ACCOUNT), eq(AGENT), any(), any(), any(), any(), any(), any());
    }

    @Test
    void loginAsTabStillReachesTheWizard() throws Exception {
        // A BillOps "Login As" tab sends only the impersonation JWT, no cookie.
        MvcResult r = run("login-as GET /config", get("/api/xamops/db-agent/config" + Q)
                .header("Authorization", "Bearer " + loginAsToken()));
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"hasPassword\":true").doesNotContain("dbPassword");
    }

    @Test
    void numericIdThatResolvesToADifferentRowIsRefused() throws Exception {
        // "1" is account 1's primary key: requireAccessByIdentifier accepts it, but DbAgentService
        // resolves provider ids only and finds nothing, so the two must not be mixed.
        assertThat(status(run("owner GET config with PK identifier", get("/api/xamops/db-agent/config?agentName=" + AGENT
                + "&cloudAccountId=1").with(user(owner))))).isEqualTo(403);
    }

    @Test
    void oldWizardPathsAreGone() throws Exception {
        assertThat(status(run("anon GET /api/v1/agent/db/config (old)", get("/api/v1/agent/db/config" + Q)))).isNotEqualTo(200);
        assertThat(status(run("anon GET /api/v1/agent/db/status (old)", get("/api/v1/agent/db/status" + Q)))).isNotEqualTo(200);
        assertThat(status(run("anon DELETE /api/v1/agent/db/register (old)", delete("/api/v1/agent/db/register" + Q)))).isNotEqualTo(200);
        verify(dbAgentService, never()).loadConnectionConfig(anyString(), anyString());
        verify(dbAgentService, never()).deleteDbAgent(anyString(), anyString());
    }

    @Test
    void agentKeyEndpointsAreUnchanged() throws Exception {
        String ingestBody = "{\"clusterName\":\"" + AGENT + "\",\"cloudAccountId\":\"" + ACCOUNT + "\",\"batches\":[]}";
        assertThat(status(run("agent-key POST /ingest", post("/api/v1/agent/ingest")
                .header("Authorization", "Bearer " + AGENT_KEY)
                .contentType("application/json").content(ingestBody)))).isEqualTo(200);
        MvcResult noKey = run("no-key POST /ingest", post("/api/v1/agent/ingest")
                .contentType("application/json").content(ingestBody));
        assertThat(status(noKey)).isEqualTo(401);
        assertThat(body(noKey)).contains("Missing authorization header");
        assertThat(status(run("agent-key POST /heartbeat", post("/api/v1/agent/heartbeat")
                .header("Authorization", "Bearer " + AGENT_KEY)
                .contentType("application/json")
                .content("{\"clusterName\":\"" + AGENT + "\",\"cloudAccountId\":\"" + ACCOUNT + "\"}")))).isEqualTo(200);
        assertThat(status(run("agent-key GET /commands", get("/api/v1/agent/commands")
                .header("Authorization", "Bearer " + AGENT_KEY).header("X-Agent-Name", AGENT)))).isEqualTo(204);
        assertThat(status(run("agent-key GET /config", get("/api/v1/agent/config?accountId=" + ACCOUNT)
                .header("Authorization", "Bearer " + AGENT_KEY)))).isEqualTo(200);
        assertThat(status(run("anon GET /install-info", get("/api/v1/agent/install-info?accountId=" + ACCOUNT)))).isEqualTo(401);
    }
}
