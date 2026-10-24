package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationTestWorld;
import com.xammer.cloud.service.EksAutoscalingService;
import com.xammer.cloud.service.K8sCapabilitiesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/xamops/k8s/capabilities} through MockMvc with the real service and access
 * services (design §11.4, §12.1, §17).
 */
class K8sCapabilitiesControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private K8sAutomationTestWorld world;
    private MockMvc mvc;
    private ClientUserDetails owner;

    @BeforeEach
    void setUp() {
        world = new K8sAutomationTestWorld();
        AgentDataCacheRepository cacheRepository = mock(AgentDataCacheRepository.class);
        AgentEventRepository eventRepository = mock(AgentEventRepository.class);
        when(cacheRepository.findTypeTimestampsByClusterConfigIdIn(anyCollection())).thenReturn(List.of());
        when(eventRepository.findFirstByClusterConfigIdAndAgentVersionEndingWithAndCreatedAtAfterOrderByCreatedAtDesc(
                anyLong(), anyString(), any())).thenReturn(Optional.empty());
        EksAutoscalingService autoscalingService = mock(EksAutoscalingService.class);
        when(autoscalingService.summarize(any())).thenReturn(
                new EksAutoscalingService.NodeAutomationSummary(false, null, null, "OFF"));
        K8sCapabilitiesService service = new K8sCapabilitiesService(world.accessService, cacheRepository,
                eventRepository, autoscalingService, new K8sAutomationReadinessProperties(), new MockEnvironment());
        mvc = MockMvcBuilders.standaloneSetup(new K8sCapabilitiesController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        owner = world.addUser(10L, 1L, "owner@tenant-a.example", "XAMOPS", "ROLE_XAMOPS", List.of());
        K8sAutomationTestWorld.signIn(owner);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ImpersonationContext.clear();
    }

    private MockHttpServletResponse get(String accountId) throws Exception {
        MockHttpServletRequestBuilder r = MockMvcRequestBuilders.get("/api/xamops/k8s/capabilities");
        if (accountId != null) {
            r.param("accountId", accountId);
        }
        return mvc.perform(r).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse r) throws Exception {
        return JSON.readTree(r.getContentAsString());
    }

    @Test
    void anEntitledCallerGetsTheCapabilities() throws Exception {
        MockHttpServletResponse r = get(K8sAutomationTestWorld.TENANT_A_AWS);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(b.get("readiness").size()).isEqualTo(9);
        assertThat(b.get("verticalEngine").get("enabled").asBoolean()).isFalse();
        assertThat(b.get("clusters").get(0).get("clusterName").asText()).isEqualTo("prod-eks");
    }

    @Test
    void aMissingOrInvalidAccountIdIs400InvalidRequest() throws Exception {
        for (String bad : new String[] {null, "abc", "1234567890123"}) {
            MockHttpServletResponse r = get(bad);
            assertThat(r.getStatus()).as(String.valueOf(bad)).isEqualTo(400);
            assertThat(body(r).get("code").asText()).isEqualTo("K8S_INVALID_REQUEST");
            assertThat(body(r).get("field").asText()).isEqualTo("accountId");
        }
    }

    @Test
    void notEntitledIs403() throws Exception {
        MockHttpServletResponse r = get(K8sAutomationTestWorld.TENANT_B_AWS);
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(body(r).get("code").asText()).isEqualTo("K8S_NO_ACCOUNT_ACCESS");
        assertThat(body(r).get("message").asText()).isEqualTo("You don't have access to this account.");
    }

    @Test
    void anImpersonatedSessionOnTheCallingThreadReportsTheImpersonationDetail() throws Exception {
        ImpersonationContext.setImpersonatedUserId(owner.getId());
        JsonNode b = body(get(K8sAutomationTestWorld.TENANT_A_AWS));
        assertThat(b.get("user").get("detail").asText()).isEqualTo("IMPERSONATION");
        assertThat(b.get("user").get("canChangeAutomation").asBoolean()).isFalse();
        assertThat(b.get("writeGate").get("allowed").asBoolean()).isFalse();
    }

    @Test
    void anUnexpectedFailureIsAFixed500() throws Exception {
        when(world.cloudAccountRepository.findByAwsAccountId(anyString()))
                .thenThrow(new IllegalStateException("db-host-17: too many connections"));
        MockHttpServletResponse r = get(K8sAutomationTestWorld.TENANT_A_AWS);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(body(r).get("code").asText()).isEqualTo("K8S_INTERNAL");
        assertThat(r.getContentAsString()).doesNotContain("db-host-17");
    }

    @Test
    void withoutAPrincipalIs401() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletResponse r = get(K8sAutomationTestWorld.TENANT_A_AWS);
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(body(r).get("code").asText()).isEqualTo("K8S_UNAUTHENTICATED");
    }
}
