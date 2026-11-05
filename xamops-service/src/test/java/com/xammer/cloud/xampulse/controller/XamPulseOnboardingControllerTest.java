package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.controller.agent.XamPulseAgentController;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.dto.agent.EnrollPending;
import com.xammer.cloud.xampulse.dto.agent.EnrollResponse;
import com.xammer.cloud.xampulse.dto.agent.HeartbeatResponse;
import com.xammer.cloud.xampulse.dto.agent.TokenPair;
import com.xammer.cloud.xampulse.exception.XamPulseExceptionHandler;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.agent.AgentEnrollmentService;
import com.xammer.cloud.xampulse.service.agent.AgentSessionService;
import com.xammer.cloud.xampulse.service.agent.EnrollmentNonceService;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import com.xammer.cloud.xampulse.service.setup.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Onboarding, trust, install script and agent endpoints through MockMvc: tenancy, shapes, status codes, no secrets. */
class XamPulseOnboardingControllerTest {

    private static final long MINE = 100L;
    private static final long FOREIGN = 200L;
    private static final long NOT_AWS = 300L;

    private MockMvc mvc;
    private final XamPulseSetupService setup = mock(XamPulseSetupService.class);
    private final InstanceCandidateService instances = mock(InstanceCandidateService.class);
    private final BucketHealthService buckets = mock(BucketHealthService.class);
    private final XamPulseStackWatcher watcher = mock(XamPulseStackWatcher.class);
    private final SetupChecklistService checklist = mock(SetupChecklistService.class);
    private final DisconnectService disconnect = mock(DisconnectService.class);
    private final LogSourceService sources = mock(LogSourceService.class);
    private final XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
    private final EnrollmentNonceService nonces = mock(EnrollmentNonceService.class);
    private final AgentEnrollmentService enrollment = mock(AgentEnrollmentService.class);
    private final AgentSessionService sessions = mock(AgentSessionService.class);
    private final DesiredStateService desired = mock(DesiredStateService.class);
    private XamPulseProperties props;
    private XamPulseSetupController setupController;

    @BeforeEach
    void setUp() {
        CloudAccountAccessService access = mock(CloudAccountAccessService.class);
        CloudAccount mine = new CloudAccount();
        mine.setId(MINE);
        mine.setAwsAccountId("123456789012");
        mine.setProvider("AWS");
        CloudAccount gcp = new CloudAccount();
        gcp.setId(NOT_AWS);
        gcp.setProvider("GCP");
        when(access.requireAccess(any(), eq(MINE))).thenReturn(mine);
        when(access.requireAccess(any(), eq(NOT_AWS))).thenReturn(gcp);
        when(access.requireAccess(any(), eq(FOREIGN))).thenThrow(new AccessDeniedException("Account not found or access denied."));
        XamPulseAccessGuard guard = new XamPulseAccessGuard(access, mock(CloudAccountRepository.class));

        props = new XamPulseProperties();
        props.getSetup().setReleaseBaseUrl("https://releases.example.com/xampulse");
        props.getSetup().setApiBaseUrl("https://live.example.com");
        props.getSetup().setTemplateBaseUrl("https://templates.example.com/x");
        XamPulseTemplateService templates = new XamPulseTemplateService(props);
        setupController = new XamPulseSetupController(guard, setup, instances, buckets, watcher, checklist, disconnect, sources);
        Object[] controllers = {setupController,
                new XamPulseTrustController(guard, configs, new TrustPanelService(props), templates),
                new XamPulseInstallController(templates),
                new XamPulseAgentController(nonces, enrollment, sessions, desired)};
        mvc = MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new XamPulseExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        ClientUserDetails user = new ClientUserDetails("u", "x", List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 1L, 10L, "t");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ------------------------------------------------------------------ tenancy

    private List<MockHttpServletRequestBuilder> requestsFor(long id) {
        String base = "/api/xamops/xampulse/accounts/" + id;
        List<MockHttpServletRequestBuilder> r = new ArrayList<>();
        r.add(get(base + "/setup"));
        r.add(get(base + "/instances"));
        r.add(get(base + "/buckets"));
        r.add(json(post(base + "/setup-plan"), "{}"));
        r.add(get(base + "/stack-status"));
        r.add(post(base + "/verify"));
        r.add(get(base + "/checklist/stream"));
        r.add(json(put(base + "/instances/i-0123456789abcdef0/collection"), "{\"enabled\":false,\"level\":\"T1\"}"));
        r.add(json(post(base + "/regions/us-west-2/plan"), "{\"targetMode\":\"INSTANCES\",\"instanceIds\":[\"i-0123456789abcdef0\"]}"));
        r.add(json(post(base + "/disconnect"), "{\"purgeHotData\":false,\"confirm\":\"DISCONNECT\"}"));
        r.add(get(base + "/sources"));
        r.add(json(put(base + "/sources"), "[]"));
        r.add(get("/api/xamops/xampulse/trust").param("accountId", String.valueOf(id)));
        return r;
    }

    @Test
    void everyOnboardingEndpointAnswersAForeignAccountWithTheUniformNotFoundAndTouchesNothing() throws Exception {
        for (MockHttpServletRequestBuilder req : requestsFor(FOREIGN)) {
            mvc.perform(req).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("XP_NOT_FOUND"))
                    .andExpect(jsonPath("$.error.message").value("Not found."));
        }
        verifyNoInteractions(setup, instances, buckets, watcher, checklist, disconnect, sources, configs);
    }

    @Test
    void aNonAwsAccountIsAlsoJustNotFound() throws Exception {
        for (MockHttpServletRequestBuilder req : requestsFor(NOT_AWS).subList(0, 12)) {
            mvc.perform(req).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("XP_NOT_FOUND"));
        }
        verifyNoInteractions(setup, instances, buckets, watcher, checklist, disconnect, sources);
    }

    // ------------------------------------------------------------------ setup

    @Test
    void overviewIsServedForAnOwnedAccount() throws Exception {
        when(setup.overview(any())).thenReturn(new XpSetupOverview(XpAccountState.PLANNED, false, "ap-south-1", null, List.of(),
                new XpSetupInstances(0, 0, 0, 0, 0, 0), 0, null));
        mvc.perform(get("/api/xamops/xampulse/accounts/" + MINE + "/setup")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PLANNED")).andExpect(jsonPath("$.homeRegion").value("ap-south-1"))
                .andExpect(jsonPath("$.demo").value(false)).andExpect(jsonPath("$.instances.total").value(0));
    }

    @Test
    void instanceListFiltersAndPages() throws Exception {
        List<XpInstanceCandidate> all = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            all.add(new XpInstanceCandidate(String.format("i-%017x", i), i % 2 == 0 ? "web-" + i : "db-" + i, i < 60 ? "ap-south-1" : "eu-west-1",
                    null, i % 3 == 0 ? "stopped" : "running", Map.of(), true, false, "T1", new XpAgentInfo(null, null, "NONE")));
        }
        when(instances.candidates(any())).thenReturn(all);
        String base = "/api/xamops/xampulse/accounts/" + MINE + "/instances";
        mvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(120)).andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.items.length()").value(50));
        mvc.perform(get(base).param("page", "2")).andExpect(jsonPath("$.items.length()").value(20));
        mvc.perform(get(base).param("q", "WEB-1")).andExpect(jsonPath("$.items[0].name").value("web-10"));
        mvc.perform(get(base).param("state", "stopped")).andExpect(jsonPath("$.total").value(40));
        mvc.perform(get(base).param("q", "eu-west").param("size", "1000")).andExpect(jsonPath("$.total").value(60)).andExpect(jsonPath("$.size").value(200));
        mvc.perform(get(base).param("page", "99")).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void planDisconnectAndCollectionAreForwardedWithTheCallerAndAddress() throws Exception {
        when(setup.plan(any(), any(), any(), any())).thenReturn(new XpSetupPlanResponse("p", null, List.of(), List.of("w")));
        mvc.perform(json(post("/api/xamops/xampulse/accounts/" + MINE + "/setup-plan").header("X-Forwarded-For", "9.9.9.9, 10.0.0.1"),
                        "{\"bucket\":{\"mode\":\"CREATE\",\"region\":\"ap-south-1\"},\"kms\":{\"mode\":\"CREATE\"},\"homeRegion\":\"ap-south-1\","
                                + "\"regions\":[],\"retention\":{\"hotDays\":14,\"iaAfterDays\":30,\"glacierIrAfterDays\":90,\"expireAfterDays\":365}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planId").value("p")).andExpect(jsonPath("$.warnings[0]").value("w"));
        verify(setup).plan(any(), any(), any(ClientUserDetails.class), eq("9.9.9.9"));

        when(disconnect.disconnect(any(), any(), any(), any())).thenReturn(new XpDisconnectResponse("job", List.of(new XpStackDeletionLink("ap-south-1", "https://x"))));
        mvc.perform(json(post("/api/xamops/xampulse/accounts/" + MINE + "/disconnect"), "{\"purgeHotData\":false,\"confirm\":\"DISCONNECT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stackDeletionLinks[0].region").value("ap-south-1"));
    }

    @Test
    void malformedBodiesAreInvalidRequestNotServerErrors() throws Exception {
        mvc.perform(json(post("/api/xamops/xampulse/accounts/" + MINE + "/setup-plan"), "not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("XP_INVALID_REQUEST"));
    }

    // ------------------------------------------------------------------ trust

    @Test
    void trustPanelCarriesTheRequiredSentenceAndTheTemplateHash() throws Exception {
        when(configs.findByCloudAccountId(MINE)).thenReturn(Optional.empty());
        mvc.perform(get("/api/xamops/xampulse/trust").param("accountId", String.valueOf(MINE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionsDiff.connectionRoleNote").value(org.hamcrest.Matchers.containsString("account-wide S3 read through AWS ReadOnlyAccess")))
                .andExpect(jsonPath("$.templateSha256").value(org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.permissionsDiff.connectionRoleGrants").isNotEmpty())
                .andExpect(jsonPath("$.neverCollected").isNotEmpty());
    }

    // ------------------------------------------------------------------ install script

    private static final Pattern SECRET = Pattern.compile("AKIA[A-Z0-9]{16}|(?i)bearer\\s+[a-z0-9._-]{8,}|xak_|eyJ[A-Za-z0-9_-]{10,}|-----BEGIN");

    @Test
    void installScriptIsServedAsAShellScriptWithNoSecretAndNoCaching() throws Exception {
        String body = mvc.perform(get("/agentinstall/xampulse.sh")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/x-shellscript"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.startsWith("#!/bin/bash"));
        assertTrue(body.contains("RELEASE_BASE_URL=\"https://releases.example.com/xampulse\""));
        assertTrue(body.contains("DESIRED_STATE_URL=\"https://live.example.com/api/v1/agent/xampulse/desired-state\""));
        assertFalse(body.contains("{{"));
        assertFalse(SECRET.matcher(body).find());
    }

    @Test
    void installScriptIsUnavailableUntilTheUrlsAreConfigured() throws Exception {
        props.getSetup().setReleaseBaseUrl("");
        mvc.perform(get("/agentinstall/xampulse.sh")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void windowsScriptIsTheDocumentedPlaceholder() throws Exception {
        mvc.perform(get("/agentinstall/xampulse.ps1")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Windows support is planned")));
    }

    // ------------------------------------------------------------------ agent API

    @Test
    void challengeValidatesTheRegionAndReturnsAShortLivedNonce() throws Exception {
        when(nonces.issue("ap-south-1")).thenReturn("0123456789abcdef0123456789abcdef");
        mvc.perform(json(post("/api/v1/agent/xampulse/enroll/challenge"), "{\"region\":\"ap-south-1\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nonce").value("0123456789abcdef0123456789abcdef")).andExpect(jsonPath("$.expiresInSec").value(60));
        mvc.perform(json(post("/api/v1/agent/xampulse/enroll/challenge"), "{\"region\":\"x; drop\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/agent/xampulse/enroll/challenge"), "{}")).andExpect(status().isBadRequest());
    }

    private static final String ENROLL = "{\"nonce\":\"n\",\"region\":\"ap-south-1\",\"stsRequest\":{\"url\":\"https://sts.ap-south-1.amazonaws.com/\",\"method\":\"POST\","
            + "\"headers\":{},\"body\":\"b\"},\"agentVersion\":\"1\",\"os\":\"linux\",\"arch\":\"amd64\",\"hostname\":\"h\"}";

    @Test
    void enrolmentAnswersMapToHttpStatusesAndFailuresCarryNoBody() throws Exception {
        when(enrollment.enroll(any(), any())).thenReturn(new AgentEnrollmentService.Success(new EnrollResponse("acc", "ref", "2030-01-01T00:00:00Z", null)));
        mvc.perform(json(post("/api/v1/agent/xampulse/enroll"), ENROLL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("acc")).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        when(enrollment.enroll(any(), any())).thenReturn(new AgentEnrollmentService.Pending(new EnrollPending(60, "PENDING_TAG_SYNC")));
        mvc.perform(json(post("/api/v1/agent/xampulse/enroll"), ENROLL)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.retryAfterSec").value(60)).andExpect(jsonPath("$.reason").value("PENDING_TAG_SYNC"));

        when(enrollment.enroll(any(), any())).thenReturn(new AgentEnrollmentService.Rejected());
        mvc.perform(json(post("/api/v1/agent/xampulse/enroll"), ENROLL)).andExpect(status().isForbidden()).andExpect(content().string(""));

        when(enrollment.enroll(any(), any())).thenReturn(new AgentEnrollmentService.TooMany());
        mvc.perform(json(post("/api/v1/agent/xampulse/enroll"), ENROLL)).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void refreshReturnsAPairOrAnEmpty401() throws Exception {
        when(sessions.refresh("good")).thenReturn(new AgentSessionService.Refreshed(AgentSessionService.RefreshStatus.OK, new TokenPair("a", "r", "2030-01-01T00:00:00Z")));
        when(sessions.refresh("bad")).thenReturn(new AgentSessionService.Refreshed(AgentSessionService.RefreshStatus.DENIED, null));
        mvc.perform(json(post("/api/v1/agent/xampulse/refresh"), "{\"refreshToken\":\"good\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("r"));
        mvc.perform(json(post("/api/v1/agent/xampulse/refresh"), "{\"refreshToken\":\"bad\"}")).andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void heartbeatAndConfigNeedAnAgentTokenInTheXpTokenHeader() throws Exception {
        when(sessions.authenticate(null)).thenReturn(Optional.empty());
        when(sessions.authenticate("tok")).thenReturn(Optional.empty());
        mvc.perform(json(post("/api/v1/agent/xampulse/heartbeat"), "{}")).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/agent/xampulse/heartbeat"), "{}").header("X-Xp-Token", "tok")).andExpect(status().isUnauthorized());
        // The Authorization header is not an agent credential.
        mvc.perform(json(post("/api/v1/agent/xampulse/heartbeat"), "{}").header("Authorization", "Bearer tok")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/agent/xampulse/config")).andExpect(status().isUnauthorized());

        XpAgent agent = new XpAgent();
        agent.setState("ACTIVE");
        when(sessions.authenticate("good")).thenReturn(Optional.of(agent));
        when(sessions.heartbeat(eq(agent), any())).thenReturn(new HeartbeatResponse("RELOAD_CONFIG", 3, null));
        mvc.perform(json(post("/api/v1/agent/xampulse/heartbeat"), "{\"agentVersion\":\"1\"}").header("X-Xp-Token", "good")).andExpect(status().isOk())
                .andExpect(jsonPath("$.command").value("RELOAD_CONFIG")).andExpect(jsonPath("$.configVersion").value(3));
    }

    @Test
    void configIsCachedByVersionAndRefusedForInactiveAgents() throws Exception {
        XpAgent agent = new XpAgent();
        agent.setState("ACTIVE");
        when(sessions.authenticate("good")).thenReturn(Optional.of(agent));
        var cfg = new com.xammer.cloud.xampulse.dto.agent.AgentConfig(3, "T1", List.of(), List.of(), null, "https://g/xampulse-ingest");
        when(sessions.config(agent)).thenReturn(cfg);
        mvc.perform(get("/api/v1/agent/xampulse/config").header("X-Xp-Token", "good").param("version", "0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(3)).andExpect(header().exists("ETag"));
        mvc.perform(get("/api/v1/agent/xampulse/config").header("X-Xp-Token", "good").param("version", "3")).andExpect(status().isNotModified());
        when(sessions.config(agent)).thenReturn(null);
        mvc.perform(get("/api/v1/agent/xampulse/config").header("X-Xp-Token", "good")).andExpect(status().isForbidden());
    }

    @Test
    void desiredStateIsPlainTextAndNeverCached() throws Exception {
        when(desired.answer("123456789012", "i-0123456789abcdef0")).thenReturn("INSTALL");
        when(desired.answer(null, null)).thenReturn("SKIP");
        mvc.perform(get("/api/v1/agent/xampulse/desired-state").param("accountId", "123456789012").param("instanceId", "i-0123456789abcdef0"))
                .andExpect(status().isOk()).andExpect(content().string("INSTALL")).andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/v1/agent/xampulse/desired-state")).andExpect(status().isOk()).andExpect(content().string("SKIP"));
    }
}
