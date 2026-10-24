package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.EvictorPolicyDto;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanResponse;
import com.xammer.cloud.dto.k8s.K8sRefusalBody;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationRequestContext;
import com.xammer.cloud.service.ConsolidationPlanService;
import com.xammer.cloud.service.EksAutoscalingService;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import com.xammer.cloud.service.spot.SpotInterruptionReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The controller after the guard has run: binding errors, readiness refusals (with the service
 * never called), and no exception text in any body (design §6.6, §12.1, §17).
 */
class EksAutoscalingControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/eks/autoscaling/prod-eks";

    private EksAutoscalingService service;
    private ConsolidationPlanService consolidationPlanService;
    private SpotInterruptionReadService spotReadService;
    private EksAutoscalingController controller;
    private MockMvc mvc;
    private K8sAutomationRequestContext ctx;
    private KubernetesClusterConfig config;

    @BeforeEach
    void setUp() {
        service = mock(EksAutoscalingService.class);
        consolidationPlanService = mock(ConsolidationPlanService.class);
        spotReadService = mock(SpotInterruptionReadService.class);
        controller = new EksAutoscalingController(service,
                new K8sAutomationPolicyNormalizer(new K8sAutomationReadinessProperties()),
                consolidationPlanService, spotReadService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        CloudAccount account = new CloudAccount();
        account.setId(7L);
        account.setAwsAccountId("111111111111");
        account.setAccessType("read-write");
        account.setStatus("CONNECTED");
        config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setCloudAccountId(7L);
        config.setClusterName("prod-eks");
        ClientUserDetails user = new ClientUserDetails("jane.doe", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 1L, 10L, "default");
        ctx = new K8sAutomationRequestContext(user, List.of(account), config);
    }

    private MockHttpServletResponse call(HttpMethod method, String suffix, String body) throws Exception {
        MockHttpServletRequestBuilder r = MockMvcRequestBuilders.request(method, BASE + suffix)
                .param("accountId", "111111111111")
                .requestAttr(K8sAutomationRequestContext.ATTRIBUTE, ctx);
        if (body != null) {
            r.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(r).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }

    private static void assertRefusal(MockHttpServletResponse r, int status, String code, String field) throws Exception {
        assertThat(r.getStatus()).isEqualTo(status);
        JsonNode body = body(r);
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("code").asText()).isEqualTo(code);
        if (field == null) {
            assertThat(body.get("field").isNull()).isTrue();
        } else {
            assertThat(body.get("field").asText()).isEqualTo(field);
        }
    }

    // ── Binding errors ────────────────────────────────────────────────────

    @Test
    void malformedJsonIsAFixed400() throws Exception {
        MockHttpServletResponse r = call(HttpMethod.PUT, "/evictor-policy", "{\"analyseOnly\": tru");
        assertThat(r.getStatus()).isEqualTo(400);
        JsonNode b = body(r);
        assertThat(b.get("code").asText()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(b.get("message").asText()).isEqualTo("The request body isn't valid for this setting.");
        assertThat(r.getContentAsString()).doesNotContain("Unexpected").doesNotContain("tru\"").doesNotContain("JSON parse");
        verifyNoInteractions(service);
    }

    @Test
    void aStringWhereANumberIsExpectedNamesTheField() throws Exception {
        assertRefusal(call(HttpMethod.PUT, "/evictor-policy", "{\"targetUtilizationCpu\":\"high\"}"),
                400, "K8S_INVALID_POLICY", "targetUtilizationCpu");
        assertRefusal(call(HttpMethod.PUT, "/safety-guards", "{\"criticalNamespaces\":{\"a\":1}}"),
                400, "K8S_INVALID_POLICY", "criticalNamespaces");
        verifyNoInteractions(service);
    }

    @Test
    void enabledAndModeBodiesAreValidated() throws Exception {
        assertRefusal(call(HttpMethod.PUT, "/enabled", "{}"), 400, "K8S_INVALID_POLICY", "enabled");
        assertRefusal(call(HttpMethod.PUT, "/enabled", "{\"enabled\":\"yes\"}"), 400, "K8S_INVALID_POLICY", "enabled");
        assertRefusal(call(HttpMethod.PUT, "/mode", "{\"mode\":\"turbo\"}"), 400, "K8S_INVALID_POLICY", "mode");
        assertRefusal(call(HttpMethod.PUT, "/mode", "{}"), 400, "K8S_INVALID_POLICY", "mode");
        verifyNoInteractions(service);
    }

    @Test
    void typeMismatchAndMissingParameterHandlersUseFixedMessages() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BASE);
        MethodParameter parameter = new MethodParameter(
                EksAutoscalingController.class.getMethod("getDashboard", K8sAutomationRequestContext.class), 0);
        ResponseEntity<K8sRefusalBody> mismatch = controller.handleTypeMismatch(new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "limit", parameter, new NumberFormatException("For input string: \"abc\"")), request);
        assertThat(mismatch.getStatusCode().value()).isEqualTo(400);
        assertThat(mismatch.getBody().code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(mismatch.getBody().field()).isEqualTo("limit");
        assertThat(mismatch.getBody().message()).doesNotContain("abc");

        ResponseEntity<K8sRefusalBody> missing = controller.handleMissingParameter(
                new MissingServletRequestParameterException("accountId", "String"), request);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody().code()).isEqualTo("K8S_INVALID_REQUEST");
        assertThat(missing.getBody().field()).isEqualTo("accountId");
    }

    // ── Readiness refusals, before the service ────────────────────────────

    @Test
    void notReadyControlsAreRefusedAndTheServiceIsNeverCalled() throws Exception {
        assertRefusal(call(HttpMethod.POST, "/rebalance", null), 403, "K8S_CONTROLLER_NOT_READY", null);
        assertRefusal(call(HttpMethod.PUT, "/scale-out-policy", "{}"), 403, "K8S_CONTROLLER_NOT_READY", null);
        assertRefusal(call(HttpMethod.PUT, "/node-templates/tpl-1", "{\"name\":\"pool\"}"), 403, "K8S_CONTROLLER_NOT_READY", null);
        assertRefusal(call(HttpMethod.DELETE, "/node-templates/tpl-1", null), 403, "K8S_CONTROLLER_NOT_READY", null);
        assertRefusal(call(HttpMethod.PUT, "/evictor-policy", "{\"analyseOnly\":false}"), 403, "K8S_ANALYSE_ONLY", "analyseOnly");
        assertRefusal(call(HttpMethod.PUT, "/node-optimizer", "{\"autoDrainIdleNodes\":true}"), 403,
                "K8S_CONTROLLER_NOT_READY", "autoDrainIdleNodes");
        assertRefusal(call(HttpMethod.PUT, "/rebalancer", "{\"scheduleEnabled\":true}"), 403,
                "K8S_CONTROLLER_NOT_READY", "scheduleEnabled");
        assertRefusal(call(HttpMethod.PUT, "/safety-guards", "{\"respectPodDisruptionBudgets\":false}"), 403,
                "K8S_GUARDRAIL_LOCKED", "respectPodDisruptionBudgets");
        assertRefusal(call(HttpMethod.PUT, "/node-optimizer", "{\"maxParallelDrains\":3}"), 403,
                "K8S_GUARDRAIL_LOCKED", "maxParallelDrains");

        MockHttpServletResponse retired = call(HttpMethod.PUT, "/workload-rightsizer", "{}");
        assertRefusal(retired, 403, "K8S_CONTROLLER_NOT_READY", null);
        assertThat(body(retired).get("message").asText()).isEqualTo("The automation agent's rightsizer is retired. "
                + "Workload rightsizing is configured in Workload autoscaling.");
        assertThat(body(call(HttpMethod.POST, "/rebalance", null)).get("message").asText())
                .isEqualTo("Rebalancing stays gated pending the benefit guard and a soak window; "
                        + "the ledger already prevents repeated drains of the same nodes.");
        verifyNoInteractions(service);
    }

    @Test
    void allowedSavesReachTheServiceWithTheResolvedConfigAndActor() throws Exception {
        MockHttpServletResponse r = call(HttpMethod.PUT, "/evictor-policy", "{\"analyseOnly\":true,\"targetUtilizationCpu\":70}");
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(body(r).get("success").asBoolean()).isTrue();
        verify(service).saveEvictorPolicy(eq(config), any(EvictorPolicyDto.class), eq("jane.doe"));

        assertThat(call(HttpMethod.PUT, "/enabled", "{\"enabled\":true}").getStatus()).isEqualTo(200);
        verify(service).setAutoscalingEnabled(config, true, "jane.doe");
        assertThat(call(HttpMethod.PUT, "/mode", "{\"mode\":\"scoped\"}").getStatus()).isEqualTo(200);
        verify(service).setMode(config, "scoped", "jane.doe");
    }

    // ── Consolidation plan (look-only) ────────────────────────────────────

    @Test
    void theConsolidationPlanReadReturnsTheServiceBody() throws Exception {
        when(consolidationPlanService.getPlan(config, ctx.primaryAccount()))
                .thenReturn(new ConsolidationPlanResponse(ConsolidationPlanResponse.STALE,
                        "2026-10-24T00:00:00Z", 660L, null, null));
        MockHttpServletResponse r = call(HttpMethod.GET, "/consolidation-plan", null);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("freshness").asText()).isEqualTo("STALE");
        assertThat(b.get("ageSeconds").asLong()).isEqualTo(660L);
        assertThat(b.get("savings").isNull()).isTrue();
        verify(consolidationPlanService).getPlan(config, ctx.primaryAccount());
        verifyNoInteractions(service);
    }

    @Test
    void theConsolidationPlanCannotBeWritten() throws Exception {
        assertThat(call(HttpMethod.PUT, "/consolidation-plan", "{}").getStatus()).isEqualTo(405);
        assertThat(call(HttpMethod.POST, "/consolidation-plan", "{}").getStatus()).isEqualTo(405);
        assertThat(call(HttpMethod.DELETE, "/consolidation-plan", null).getStatus()).isEqualTo(405);
        verifyNoInteractions(consolidationPlanService);
        verifyNoInteractions(service);
    }

    // ── Spot interruptions (read-only) ────────────────────────────────────

    @Test
    void spotInterruptionsDefaultTo30DaysAnd200Rows() throws Exception {
        when(spotReadService.read(config, 30, 200))
                .thenReturn(new SpotInterruptionsDto(30, false, 365, true, null, null, List.of()));
        MockHttpServletResponse r = call(HttpMethod.GET, "/spot-interruptions", null);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("windowDays").asInt()).isEqualTo(30);
        assertThat(b.get("fleet").isNull()).isTrue();
        assertThat(b.get("events").isArray()).isTrue();
        verify(spotReadService).read(config, 30, 200);
        verifyNoInteractions(service);
    }

    @Test
    void spotInterruptionsPassValidBounds() throws Exception {
        assertThat(call(HttpMethod.GET, "/spot-interruptions?days=1&limit=500", null).getStatus()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/spot-interruptions?days=365&limit=1", null).getStatus()).isEqualTo(200);
        verify(spotReadService).read(config, 1, 500);
        verify(spotReadService).read(config, 365, 1);
    }

    @Test
    void invalidSpotInterruptionParamsAre400InvalidRequestWithTheField_AC9() throws Exception {
        for (String days : List.of("0", "366", "abc", "-1", "1.5", "")) {
            MockHttpServletResponse r = call(HttpMethod.GET, "/spot-interruptions?days=" + days, null);
            assertRefusal(r, 400, "K8S_INVALID_REQUEST", "days");
            assertThat(body(r).get("message").asText()).isEqualTo("days must be a whole number from 1 to 365.");
        }
        for (String limit : List.of("0", "501", "abc")) {
            MockHttpServletResponse r = call(HttpMethod.GET, "/spot-interruptions?limit=" + limit, null);
            assertRefusal(r, 400, "K8S_INVALID_REQUEST", "limit");
            assertThat(body(r).get("message").asText()).isEqualTo("limit must be a whole number from 1 to 500.");
        }
        verifyNoInteractions(spotReadService, service);
    }

    @Test
    void spotInterruptionsCannotBeWritten() throws Exception {
        assertThat(call(HttpMethod.PUT, "/spot-interruptions", "{}").getStatus()).isEqualTo(405);
        assertThat(call(HttpMethod.POST, "/spot-interruptions", "{}").getStatus()).isEqualTo(405);
        assertThat(call(HttpMethod.DELETE, "/spot-interruptions", null).getStatus()).isEqualTo(405);
        verifyNoInteractions(spotReadService);
    }

    // ── Unexpected failures ───────────────────────────────────────────────

    @Test
    void unexpectedFailuresAreFixed500sWithoutExceptionText() throws Exception {
        when(service.getDashboard(any())).thenThrow(new IllegalStateException("db-host-17 refused: password=hunter2"));
        MockHttpServletResponse read = call(HttpMethod.GET, "", null);
        assertRefusal(read, 500, "K8S_INTERNAL", null);
        assertThat(body(read).get("message").asText()).isEqualTo("Couldn't load automation data.");
        assertThat(read.getContentAsString()).doesNotContain("hunter2").doesNotContain("db-host-17");

        org.mockito.Mockito.doThrow(new RuntimeException("constraint uk_secret violated"))
                .when(service).saveSafetyGuards(any(), any(), any());
        MockHttpServletResponse write = call(HttpMethod.PUT, "/safety-guards", "{}");
        assertRefusal(write, 500, "K8S_INTERNAL", null);
        assertThat(body(write).get("message").asText()).isEqualTo("Couldn't save the change. Try again.");
        assertThat(write.getContentAsString()).doesNotContain("uk_secret");
    }

    @Test
    void aRefusalFromTheServiceKeepsItsCode() throws Exception {
        org.mockito.Mockito.doThrow(K8sAutomationRefusedException.of(K8sAutomationReason.INVALID_POLICY,
                        "Drain timeout must be between 5 and 60.", "drainTimeoutMinutes"))
                .when(service).saveRebalancerConfig(any(), any(), any());
        assertRefusal(call(HttpMethod.PUT, "/rebalancer", "{}"), 400, "K8S_INVALID_POLICY", "drainTimeoutMinutes");
    }

    @Test
    void withoutTheGuardsContextNothingRuns() throws Exception {
        MockHttpServletResponse r = mvc.perform(MockMvcRequestBuilders.put(BASE + "/enabled")
                .param("accountId", "111111111111")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(500);
        verifyNoInteractions(service);
    }
}
