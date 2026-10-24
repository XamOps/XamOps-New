package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.config.K8sAutomationWebConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanResponse;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationGuardInterceptor;
import com.xammer.cloud.security.K8sAutomationTestWorld;
import com.xammer.cloud.service.ConsolidationPlanService;
import com.xammer.cloud.service.EksAutoscalingService;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.xammer.cloud.security.K8sAutomationTestWorld.CLUSTER;
import static com.xammer.cloud.security.K8sAutomationTestWorld.TENANT_A_AWS;
import static com.xammer.cloud.security.K8sAutomationTestWorld.TENANT_B_AWS;
import static com.xammer.cloud.security.K8sAutomationTestWorld.TENANT_B_DB_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The owner-required persona matrix across all 17 {@code /api/eks/autoscaling/**} endpoints
 * (design §6.6 endpoint matrix, plan item 6 and the verification contract).
 *
 * <p>
 * MockMvc runs the real controller behind the real {@link K8sAutomationGuardInterceptor},
 * registered on {@link K8sAutomationWebConfig#GUARDED_PATHS} exactly as production registers it,
 * over the real {@code K8sAutomationAccessService} and {@code CloudAccountAccessService} with
 * in-memory repositories. Only {@link EksAutoscalingService} and {@link ConsolidationPlanService} are
 * mocked, so every denial also proves neither service was reached.
 */
class K8sAutomationEndpointMatrixTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A tenant-B-only cluster, to show a cluster name alone never crosses tenants. */
    private static final String TENANT_B_ONLY_CLUSTER = "tenant-b-eks";

    record Endpoint(String method, String suffix, String body) {
        boolean mutation() {
            return !method.equals("GET");
        }

        @Override
        public String toString() {
            return method + " /{c}" + suffix;
        }
    }

    /** The 17 endpoints of EksAutoscalingController, with complete, allowed bodies. */
    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("GET", "", null),
            new Endpoint("GET", "/rightsizing", null),
            new Endpoint("GET", "/rebalance-status", null),
            new Endpoint("GET", "/node-fleet", null),
            new Endpoint("GET", "/evictor-candidates", null),
            new Endpoint("GET", "/consolidation-plan", null),
            new Endpoint("PUT", "/enabled", "{\"enabled\":true}"),
            new Endpoint("PUT", "/mode", "{\"mode\":\"scoped\"}"),
            new Endpoint("PUT", "/scale-out-policy", "{\"enabled\":true}"),
            new Endpoint("PUT", "/evictor-policy", "{\"enabled\":true,\"analyseOnly\":true}"),
            new Endpoint("PUT", "/workload-rightsizer", "{\"enabled\":false}"),
            new Endpoint("PUT", "/safety-guards", "{\"respectPodDisruptionBudgets\":true,\"protectCriticalNamespaces\":true}"),
            new Endpoint("PUT", "/node-optimizer", "{\"idleCpuThresholdPct\":15,\"handleSpotInterruptions\":false}"),
            new Endpoint("PUT", "/rebalancer", "{\"scheduleEnabled\":false,\"gracefulDraining\":true}"),
            new Endpoint("POST", "/rebalance", null),
            new Endpoint("PUT", "/node-templates/tpl-1", "{\"name\":\"pool\"}"),
            new Endpoint("DELETE", "/node-templates/tpl-1", null));

    /** Mutations readiness allows today; the rest must be refused with K8S_CONTROLLER_NOT_READY. */
    static final Set<String> READINESS_ALLOWED = Set.of(
            "/enabled", "/mode", "/evictor-policy", "/safety-guards", "/node-optimizer", "/rebalancer");

    static Stream<Endpoint> all() {
        return ENDPOINTS.stream();
    }

    static Stream<Endpoint> mutations() {
        return ENDPOINTS.stream().filter(Endpoint::mutation);
    }

    static Stream<Endpoint> reads() {
        return ENDPOINTS.stream().filter(e -> !e.mutation());
    }

    private K8sAutomationTestWorld world;
    private EksAutoscalingService service;
    private ConsolidationPlanService consolidationPlanService;
    private MockMvc mvc;
    private ClientUserDetails tenantAUser;

    @BeforeEach
    void setUp() {
        world = new K8sAutomationTestWorld();
        world.addConfig(201L, TENANT_B_DB_ID, TENANT_B_ONLY_CLUSTER);
        service = mock(EksAutoscalingService.class);
        when(service.getDashboard(any())).thenReturn(new AutoscalerDashboardDto());
        consolidationPlanService = mock(ConsolidationPlanService.class);
        when(consolidationPlanService.getPlan(any(), any())).thenReturn(ConsolidationPlanResponse.noPlan());
        EksAutoscalingController controller = new EksAutoscalingController(service,
                new K8sAutomationPolicyNormalizer(new K8sAutomationReadinessProperties()),
                consolidationPlanService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addMappedInterceptors(K8sAutomationWebConfig.GUARDED_PATHS.toArray(String[]::new),
                        new K8sAutomationGuardInterceptor(world.accessService))
                .build();
        tenantAUser = world.addUser(10L, 1L, "owner@tenant-a.example", "XAMOPS", "ROLE_XAMOPS", List.of());
        K8sAutomationTestWorld.signIn(tenantAUser);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ImpersonationContext.clear();
    }

    private MockHttpServletResponse call(Endpoint e, String cluster, String accountId) throws Exception {
        MockHttpServletRequestBuilder r = MockMvcRequestBuilders
                .request(HttpMethod.valueOf(e.method()), "/api/eks/autoscaling/" + cluster + e.suffix())
                .param("accountId", accountId);
        if (e.body() != null) {
            r.contentType(MediaType.APPLICATION_JSON).content(e.body());
        }
        return mvc.perform(r).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse r) throws Exception {
        return JSON.readTree(r.getContentAsString());
    }

    private void assertRefusedBeforeTheService(MockHttpServletResponse r, int status, String code, String detail)
            throws Exception {
        assertThat(r.getStatus()).isEqualTo(status);
        JsonNode b = body(r);
        assertThat(b.get("success").asBoolean()).isFalse();
        assertThat(b.get("code").asText()).isEqualTo(code);
        if (detail == null) {
            assertThat(b.get("detail").isNull()).isTrue();
        } else {
            assertThat(b.get("detail").asText()).isEqualTo(detail);
        }
        verifyNoInteractions(service, consolidationPlanService);
    }

    private int serviceCalls() {
        return Mockito.mockingDetails(service).getInvocations().size()
                + Mockito.mockingDetails(consolidationPlanService).getInvocations().size();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    void coversEveryEndpoint(Endpoint e) {
        assertThat(ENDPOINTS).hasSize(17);
        assertThat(ENDPOINTS.stream().filter(Endpoint::mutation)).hasSize(11);
    }

    // ── Read-only connection ──────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void aReadOnlyConnectionGets403OnEveryMutation(Endpoint e) throws Exception {
        world.tenantA.setAccessType("read-only");
        assertRefusedBeforeTheService(call(e, CLUSTER, TENANT_A_AWS), 403, "K8S_READ_ONLY_CONNECTION", null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reads")
    void aReadOnlyConnectionStillReads(Endpoint e) throws Exception {
        world.tenantA.setAccessType("read-only");
        assertThat(call(e, CLUSTER, TENANT_A_AWS).getStatus()).isEqualTo(200);
        assertThat(serviceCalls()).isPositive();
    }

    // ── User permission ───────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void aRestrictedUserWithoutTheKeyGets403TabGrantOnEveryMutation(Endpoint e) throws Exception {
        K8sAutomationTestWorld.signIn(world.addUser(30L, 1L, "member@tenant-a.example", "XAMOPS", "ROLE_XAMOPS",
                List.of("cloudops-cloudk8s", "finops-dashboard")));
        assertRefusedBeforeTheService(call(e, CLUSTER, TENANT_A_AWS), 403, "K8S_NO_USER_PERMISSION", "TAB_GRANT");
    }

    // ── Cross-tenant ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    void anotherTenantsClusterIsRefusedOnAll17(Endpoint e) throws Exception {
        assertRefusedBeforeTheService(call(e, TENANT_B_ONLY_CLUSTER, TENANT_B_AWS), 403, "K8S_NO_ACCOUNT_ACCESS", null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    void anotherTenantsCloudAccountIdAsAccountIdIsRefusedOnAll17(Endpoint e) throws Exception {
        // Before the guard, this number went straight into findByCloudAccountIdAndClusterName and
        // reached tenant B's modern config (cloud_account_id = 42) for the same cluster name.
        assertRefusedBeforeTheService(call(e, CLUSTER, String.valueOf(TENANT_B_DB_ID)), 403, "K8S_NO_ACCOUNT_ACCESS", null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    void anotherTenantsClusterNameUnderYourOwnAccountIsNotRegistered(Endpoint e) throws Exception {
        assertRefusedBeforeTheService(call(e, TENANT_B_ONLY_CLUSTER, TENANT_A_AWS), 404, "K8S_CLUSTER_NOT_REGISTERED", null);
    }

    // ── Demo and impersonation ────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void aDemoIdentityGets403OnEveryMutation(Endpoint e) throws Exception {
        // The demo principal's username is "Xammer Admin"; the user row's email decides.
        K8sAutomationTestWorld.signIn(world.addUser(40L, 1L, "demo.xamops@xammer.in", "XAMOPS", "ROLE_XAMOPS", List.of()));
        assertRefusedBeforeTheService(call(e, CLUSTER, TENANT_A_AWS), 403, "K8S_DEMO", null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void anImpersonatedSessionGets403OnEveryMutation(Endpoint e) throws Exception {
        ImpersonationContext.setImpersonatedUserId(tenantAUser.getId());
        assertRefusedBeforeTheService(call(e, CLUSTER, TENANT_A_AWS), 403, "K8S_NO_USER_PERMISSION", "IMPERSONATION");
    }

    // ── Read-write unrestricted admin ─────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    void aReadWriteAdminReachesTheServiceWhereReadinessAllows(Endpoint e) throws Exception {
        K8sAutomationTestWorld.signIn(world.addUser(50L, 1L, "admin@xamops.example", "XAMOPS_ADMIN",
                "ROLE_XAMOPS_ADMIN", List.of()));
        MockHttpServletResponse r = call(e, CLUSTER, TENANT_A_AWS);

        if (!e.mutation() || READINESS_ALLOWED.contains(e.suffix())) {
            assertThat(r.getStatus()).as(r.getContentAsString()).isBetween(200, 299);
            assertThat(serviceCalls()).isPositive();
        } else {
            assertRefusedBeforeTheService(r, 403, "K8S_CONTROLLER_NOT_READY", null);
        }
    }
}
