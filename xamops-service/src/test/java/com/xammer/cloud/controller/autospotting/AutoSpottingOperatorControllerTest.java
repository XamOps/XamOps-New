package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.config.SecurityConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.ImpersonationFilter;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.AutoSpottingSwitch;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.permissions.autospotting.AutoSpottingIntentFilter;
import com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingSwitchRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import com.xammer.cloud.security.TrialEnforcementFilter;
import com.xammer.cloud.service.auth.TrialService;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.switches.ActionGate;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchOperatorService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operator API (plan-phase-09.md task 9.4, design-core.md §6.6). The role gate (G1 decision D3):
 * super admin and XamOps admin succeed; BillOps admin is 403 (these are not dashboard reads); a
 * customer is 403; anonymous is 401; an impersonated session is 403 on every non-GET. A missing CSRF
 * header is 403 (the {@code permissions-common} filter, added here as it is registered in the app). A
 * 501-character reason is 422. A PUT engage is visible through {@code GET /switches} and through
 * {@link ActionGate} within 60 s (AC-SPN-05) — the propagation check at the bottom exercises the real
 * {@link SwitchOperatorService}, {@link SwitchService} and {@link ActionGate} over a mutable store.
 */
@SpringJUnitWebConfig(AutoSpottingOperatorControllerTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.impersonation.secret-key=dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0",
        "app.remember-me.key=test-only-remember-me-key"
})
class AutoSpottingOperatorControllerTest {

    private static final String SWITCHES = "/api/xamops/autospotting-admin/switches";
    private static final String GLOBAL = "/api/xamops/autospotting-admin/switches/GLOBAL";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, ImpersonationFilter.class,
            TrialEnforcementFilter.class, com.xammer.cloud.security.AutoSpottingAdminAccess.class,
            com.xammer.cloud.security.DemoClientMembership.class,
            AutoSpottingOperatorController.class, AutoSpottingProblemAdvice.class})
    static class TestConfig {
    }

    @MockitoBean
    private SwitchOperatorService switchOperatorService;
    @MockitoBean
    private com.xammer.cloud.service.autospotting.core.lifecycle.OperatorGroupService operatorGroupService;
    @MockitoBean
    private SpotOperationRepository operationRepository;
    @MockitoBean
    private com.xammer.cloud.service.autospotting.core.IdempotencyService idempotencyService;
    @MockitoBean
    private com.xammer.cloud.service.autospotting.core.allowlist.AllowlistOperatorService allowlistService;
    @MockitoBean
    private com.xammer.cloud.service.autospotting.aws.events.AwsEventDlqService dlqService;
    @MockitoBean
    private com.xammer.cloud.repository.UserRepository userRepository;
    @MockitoBean
    private CustomAuthenticationSuccessHandler authenticationSuccessHandler;
    @MockitoBean
    private CustomAuthenticationFailureHandler authenticationFailureHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler logoutSuccessHandler;
    @MockitoBean
    private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;
    @MockitoBean
    private TrialService trialService;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Add the AutoSpotting CSRF/origin filter the way the app registers it, so the missing-header
        // 403 is exercised. It runs before the security chain.
        AutoSpottingIntentFilter intentFilter = new AutoSpottingIntentFilter(
                List.of("/api/xamops/autospotting-admin/", "/api/autospotting/v2/"),
                List.of("https://live.xamops.com"));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .addFilters(intentFilter).build();
        when(switchOperatorService.list()).thenReturn(List.of(new SwitchOperatorService.SwitchView(
                "GLOBAL", false, false, true, "seed", "system", Instant.now(), 0L)));
        when(switchOperatorService.setEngaged(anyString(), anyBoolean(), anyLong(), any(), anyString()))
                .thenReturn(new SwitchOperatorService.SwitchView(
                        "GLOBAL", false, true, false, "freeze reason", "11", Instant.now(), 1L));
        when(allowlistService.options(7L)).thenReturn(
                new com.xammer.cloud.service.autospotting.core.allowlist.AllowlistOperatorService.BasisOptions(
                        7L, "AWS", "prod", 3L, true,
                        java.util.UUID.fromString("11111111-2222-3333-4444-555555555555")));
        when(allowlistService.list()).thenReturn(List.of(allowlistView("CONTRACT", true)));
        when(allowlistService.add(eq(7L), eq("CONTRACT"), anyString(), any(), anyString()))
                .thenReturn(allowlistView("CONTRACT", true));
        when(allowlistService.add(eq(7L), eq("NO_FEE"), any(), any(), anyString()))
                .thenReturn(allowlistView("NO_FEE", false));
        when(operationRepository.findOperatorOps(any(), any(), any())).thenAnswer(inv -> {
            org.springframework.data.domain.Pageable pageable = inv.getArgument(2);
            return new PageImpl<>(List.of(), pageable, 0);
        });
    }

    @AfterEach
    void clear() {
        ImpersonationContext.clear();
    }

    private static RequestPostProcessor as(String role) {
        return user(new ClientUserDetails("op@example.com", null,
                List.of(new SimpleGrantedAuthority(role)), 1L, 11L, "default"));
    }

    private static String reason(int length) {
        return "{\"engaged\":true,\"reason\":\"" + "x".repeat(length) + "\",\"expectedVersion\":0}";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putSwitch(String body) {
        return put(GLOBAL)
                .contentType("application/json")
                .header(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE)
                .header("Idempotency-Key", "11111111-1111-1111-1111-111111111111")
                .content(body);
    }

    // ── roles ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = { "ROLE_SUPER_ADMIN", "ROLE_XAMOPS_ADMIN" })
    void operatorRoles_getSwitches_andEngage(String role) throws Exception {
        mvc.perform(get(SWITCHES).with(as(role))).andExpect(status().isOk());
        mvc.perform(putSwitch(reason(10)).with(as(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("GLOBAL"))
                .andExpect(jsonPath("$.engaged").value(true));
    }

    @Test
    void billOpsAdmin_isForbidden() throws Exception {
        mvc.perform(get(SWITCHES).with(as("ROLE_BILLOPS_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(putSwitch(reason(10)).with(as("ROLE_BILLOPS_ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void customer_isForbidden() throws Exception {
        mvc.perform(get(SWITCHES).with(as("ROLE_USER"))).andExpect(status().isForbidden());
        mvc.perform(putSwitch(reason(10)).with(as("ROLE_USER"))).andExpect(status().isForbidden());
    }

    @Test
    void anonymous_isUnauthorized() throws Exception {
        mvc.perform(get(SWITCHES)).andExpect(status().isUnauthorized());
    }

    @Test
    void impersonatedSuperAdmin_readsButCannotWrite() throws Exception {
        // Read is allowed for an impersonated super admin; every non-GET is 403.
        ImpersonationContext.setImpersonatedUserId(99L);
        mvc.perform(get(SWITCHES).with(as("ROLE_SUPER_ADMIN")).header("X-Impersonate-User", "99"))
                .andExpect(status().isOk());
        mvc.perform(putSwitch(reason(10)).with(as("ROLE_SUPER_ADMIN")).header("X-Impersonate-User", "99"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AS_FORBIDDEN_ROLE"));
    }

    // ── CSRF header and validation ─────────────────────────────────────────────

    @Test
    void missingCsrfHeader_is403() throws Exception {
        mvc.perform(put(GLOBAL).contentType("application/json").content(reason(10))
                        .header("Idempotency-Key", "k").with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void reason501_is422() throws Exception {
        mvc.perform(putSwitch(reason(501)).with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHECK_FAILED"));
    }

    @Test
    void operationsList_isPaginatedOk() throws Exception {
        mvc.perform(get("/api/xamops/autospotting-admin/operations?waitReason=STUCK&size=500")
                        .with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200))
                .andExpect(jsonPath("$.operations").isArray());
    }

    // ── allowlist with billing basis (plan-phase-15.md task 15.4, design-core.md §6.1, ux §9.2) ──

    private static final String ALLOWLIST = "/api/xamops/autospotting-admin/allowlist";

    private static com.xammer.cloud.service.autospotting.core.allowlist.AllowlistOperatorService.AllowlistView
            allowlistView(String basis, boolean inForce) {
        return new com.xammer.cloud.service.autospotting.core.allowlist.AllowlistOperatorService.AllowlistView(
                7L, "AWS", "prod", 3L, basis,
                "CONTRACT".equals(basis) ? java.util.UUID.fromString("11111111-2222-3333-4444-555555555555") : null,
                inForce, "pilot", "11", Instant.parse("2026-11-20T00:00:00Z"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postAllowlist(String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ALLOWLIST)
                .contentType("application/json")
                .header(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE)
                .header("Idempotency-Key", "11111111-1111-1111-1111-111111111111")
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deleteAllowlist(String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(ALLOWLIST + "/7")
                .contentType("application/json")
                .header(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE)
                .header("Idempotency-Key", "11111111-1111-1111-1111-111111111111")
                .content(body);
    }

    @ParameterizedTest
    @ValueSource(strings = { "ROLE_SUPER_ADMIN", "ROLE_XAMOPS_ADMIN" })
    void allowlist_operatorsReadAddAndRemove(String role) throws Exception {
        mvc.perform(get(ALLOWLIST + "/options/7").with(as(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contractInForce").value(true));
        mvc.perform(get(ALLOWLIST).with(as(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].billingBasis").value("CONTRACT"))
                .andExpect(jsonPath("$[0].contractInForce").value(true))
                .andExpect(jsonPath("$[0].cloudAccountId").value(7));
        // ux §9.2: CONTRACT with a contract in force, and NO_FEE with a reason, are 2xx.
        mvc.perform(postAllowlist("{\"cloudAccountId\":7,\"billingBasis\":\"CONTRACT\",\"reason\":\"pilot\"}").with(as(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.billingBasis").value("CONTRACT"));
        mvc.perform(postAllowlist("{\"cloudAccountId\":7,\"billingBasis\":\"NO_FEE\",\"reason\":\"test project\"}").with(as(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.billingBasis").value("NO_FEE"))
                .andExpect(jsonPath("$.contractId").doesNotExist());
        mvc.perform(deleteAllowlist("{\"reason\":\"pilot over\"}").with(as(role))).andExpect(status().isNoContent());
        verify(allowlistService).remove(eq(7L), eq("pilot over"), any());
    }

    @Test
    void allowlist_withoutABillingBasis_is409() throws Exception {
        when(allowlistService.add(eq(7L), eq("CONTRACT"), anyString(), any(), anyString())).thenThrow(
                new com.xammer.cloud.service.autospotting.core.AutoSpottingApiException(
                        org.springframework.http.HttpStatus.CONFLICT, "ALLOWLIST_NEEDS_BILLING_BASIS", "no contract"));
        when(allowlistService.add(eq(7L), eq("NO_FEE"), any(), any(), anyString())).thenThrow(
                new com.xammer.cloud.service.autospotting.core.AutoSpottingApiException(
                        org.springframework.http.HttpStatus.CONFLICT, "ALLOWLIST_NEEDS_BILLING_BASIS", "no reason"));
        mvc.perform(postAllowlist("{\"cloudAccountId\":7,\"billingBasis\":\"CONTRACT\",\"reason\":\"pilot\"}").with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALLOWLIST_NEEDS_BILLING_BASIS"));
        mvc.perform(postAllowlist("{\"cloudAccountId\":7,\"billingBasis\":\"NO_FEE\"}").with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALLOWLIST_NEEDS_BILLING_BASIS"));
    }

    @Test
    void allowlist_rolesImpersonationAndHeaders() throws Exception {
        // BillOps admin, customer: 403; anonymous: 401.
        for (String role : List.of("ROLE_BILLOPS_ADMIN", "ROLE_USER")) {
            mvc.perform(get(ALLOWLIST).with(as(role))).andExpect(status().isForbidden());
            mvc.perform(postAllowlist("{\"cloudAccountId\":7,\"billingBasis\":\"NO_FEE\",\"reason\":\"r\"}").with(as(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(deleteAllowlist("{\"reason\":\"r\"}").with(as(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(ALLOWLIST)).andExpect(status().isUnauthorized());
        // Impersonated super admin reads; writes are 403 AS_FORBIDDEN_ROLE.
        ImpersonationContext.setImpersonatedUserId(99L);
        mvc.perform(get(ALLOWLIST).with(as("ROLE_SUPER_ADMIN")).header("X-Impersonate-User", "99")).andExpect(status().isOk());
        mvc.perform(postAllowlist("{\"cloudAccountId\":7,\"billingBasis\":\"NO_FEE\",\"reason\":\"r\"}")
                        .with(as("ROLE_SUPER_ADMIN")).header("X-Impersonate-User", "99"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AS_FORBIDDEN_ROLE"));
        mvc.perform(deleteAllowlist("{\"reason\":\"r\"}").with(as("ROLE_SUPER_ADMIN")).header("X-Impersonate-User", "99"))
                .andExpect(status().isForbidden());
        // An impersonated XamOps admin (no super admin) cannot read it at all.
        mvc.perform(get(ALLOWLIST).with(as("ROLE_XAMOPS_ADMIN")).header("X-Impersonate-User", "99")).andExpect(status().isForbidden());
        ImpersonationContext.clear();
        // The OTP-exempt demo principal is denied.
        com.xammer.cloud.domain.User demo = new com.xammer.cloud.domain.User();
        demo.setEmail("demo.xamops@xammer.in");
        when(userRepository.findById(11L)).thenReturn(java.util.Optional.of(demo));
        mvc.perform(get(ALLOWLIST).with(as("ROLE_SUPER_ADMIN"))).andExpect(status().isForbidden());
        when(userRepository.findById(11L)).thenReturn(java.util.Optional.empty());
        // CSRF header and Idempotency-Key.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ALLOWLIST)
                        .contentType("application/json").header("Idempotency-Key", "k")
                        .content("{\"cloudAccountId\":7,\"billingBasis\":\"NO_FEE\",\"reason\":\"r\"}").with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.doThrow(new com.xammer.cloud.service.autospotting.core.AutoSpottingApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "key"))
                .when(idempotencyService).requireKey(org.mockito.ArgumentMatchers.isNull());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ALLOWLIST)
                        .contentType("application/json")
                        .header(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE)
                        .content("{\"cloudAccountId\":7,\"billingBasis\":\"NO_FEE\",\"reason\":\"r\"}").with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        // A missing account id is a field error, not a 500.
        mvc.perform(postAllowlist("{\"billingBasis\":\"NO_FEE\",\"reason\":\"r\"}").with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── AWS event DLQ (plan-phase-22.md 22.5, design-aws.md §9.8) ──
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder redrive(String body, boolean key) {
        var b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/xamops/autospotting-admin/events/dlq/redrive")
                .contentType("application/json")
                .header(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE)
                .content(body);
        return key ? b.header("Idempotency-Key", "11111111-1111-1111-1111-111111111111") : b;
    }
    @ParameterizedTest
    @ValueSource(strings = { "ROLE_SUPER_ADMIN", "ROLE_XAMOPS_ADMIN" })
    void dlq_operatorsReadAndRedrive(String role) throws Exception {
        when(dlqService.summary()).thenReturn(new com.xammer.cloud.service.autospotting.aws.events.AwsEventDlqService.Summary(
                true, "q-dlq.fifo", 2, List.of(), false));
        when(dlqService.redrive(eq("replay"), any())).thenReturn(
                new com.xammer.cloud.service.autospotting.aws.events.AwsEventDlqService.RedriveResult("MOVE_TASK", "t1", 0));
        mvc.perform(get("/api/xamops/autospotting-admin/events/dlq?provider=aws").with(as(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.depth").value(2));
        mvc.perform(redrive("{\"reason\":\"replay\"}", true).with(as(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("MOVE_TASK"));
    }
    @Test
    void dlq_rulesAndGates() throws Exception {
        mvc.perform(get("/api/xamops/autospotting-admin/events/dlq?provider=gcp").with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/xamops/autospotting-admin/events/dlq").with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.doThrow(new com.xammer.cloud.service.autospotting.core.AutoSpottingApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "key"))
                .when(idempotencyService).requireKey(org.mockito.ArgumentMatchers.isNull());
        mvc.perform(redrive("{\"reason\":\"replay\"}", false).with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        // A missing or oversized reason is a field error (the problem advice maps bean validation to 400 CHECK_FAILED).
        mvc.perform(redrive("{\"reason\":\"" + "x".repeat(501) + "\"}", true).with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHECK_FAILED"));
        when(dlqService.redrive(anyString(), any())).thenThrow(
                new com.xammer.cloud.service.autospotting.core.AutoSpottingApiException(
                        org.springframework.http.HttpStatus.CONFLICT, "REDRIVE_IN_PROGRESS", "running"));
        mvc.perform(redrive("{\"reason\":\"again\"}", true).with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REDRIVE_IN_PROGRESS"));
    }
    // ── AC-SPN-05: a PUT engage is visible through GET /switches and ActionGate within 60 s ──

    @Test
    void engage_isVisibleThroughSwitchesAndActionGate() {
        // A mutable in-memory switch store: the write flips the GLOBAL row, and a 0 s-cache
        // SwitchService then sees it immediately — standing in for "within 60 s" (AC-SPN-05).
        AutoSpottingSwitchRepository switchRepo = mock(AutoSpottingSwitchRepository.class);
        AutoSpottingAllowlistEntryRepository allowlistRepo = mock(AutoSpottingAllowlistEntryRepository.class);
        when(allowlistRepo.findAllowedCloudAccountIds()).thenReturn(List.of());

        AutoSpottingSwitch global = new AutoSpottingSwitch();
        global.setScopeKey("GLOBAL");
        global.setEngaged(false);
        global.setReason("seed");
        global.setChangedBy("system");
        global.setChangedAt(Instant.now());
        global.setVersion(0L);

        when(switchRepo.findById("GLOBAL")).thenReturn(java.util.Optional.of(global));
        when(switchRepo.updateGuarded(eq("GLOBAL"), anyBoolean(), anyString(), anyString(), any(), eq(0L)))
                .thenAnswer(inv -> {
                    global.setEngaged(inv.getArgument(1));
                    global.setVersion(global.getVersion() + 1);
                    return 1;
                });
        when(switchRepo.findEngagedScopeKeys())
                .thenAnswer(inv -> global.isEngaged() ? List.of("GLOBAL") : List.<String>of());
        when(switchRepo.findAllByOrderByChangedAtDesc())
                .thenAnswer(inv -> List.of(global));

        AutoSpottingSwitchProperties properties = new AutoSpottingSwitchProperties(
                true, true, true, true, false, false, false, false, false, false);
        AutoSpottingFeatureFlags flags = new AutoSpottingFeatureFlags(true, true, false, false);
        var auditWriter = mock(com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.class);
        SwitchOperatorService operator = new SwitchOperatorService(switchRepo, properties, flags, auditWriter, true);
        SwitchService switchService = new SwitchService(switchRepo, allowlistRepo, 0L);
        ActionGate gate = new ActionGate(properties, flags, true);

        CloudAccount account = new CloudAccount();
        account.setId(7L);

        // Before: GLOBAL not engaged, so ENABLE is not blocked by GLOBAL.
        assertThat(switchService.current().globalEngaged()).isFalse();

        // Engage GLOBAL through the operator service.
        operator.setEngaged("GLOBAL",
                true, 0L,
                com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor.operator(11L, "ROLE_SUPER_ADMIN"),
                "freeze everything");

        // After: GET /switches shows it engaged, and ActionGate blocks ENABLE with AS_DISABLED.
        assertThat(operator.list())
                .anySatisfy(v -> {
                    assertThat(v.scopeKey()).isEqualTo("GLOBAL");
                    assertThat(v.engaged()).isTrue();
                });
        SwitchState state = switchService.current();
        assertThat(state.globalEngaged()).isTrue();
        ActionGate.Decision decision = gate.check(ActionGate.ActionKind.ENABLE, account, Provider.AWS, state);
        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.code()).isEqualTo(ActionGate.Code.AS_DISABLED);
    }
}
