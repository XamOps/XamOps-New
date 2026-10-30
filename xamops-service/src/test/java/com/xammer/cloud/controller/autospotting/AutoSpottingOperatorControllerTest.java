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
