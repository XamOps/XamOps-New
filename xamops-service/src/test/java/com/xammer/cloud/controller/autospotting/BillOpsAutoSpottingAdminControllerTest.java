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

import com.xammer.cloud.controller.autospotting.BillOpsAutoSpottingAdminController;
import com.xammer.cloud.domain.autospotting.ledger.AutoSpottingContract;
import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerGoLive;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.ledger.contract.ContractService;
import com.xammer.cloud.service.autospotting.ledger.contract.GoLiveService;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
 * The BillOps contracts and go-live endpoints (plan-phase-15.md task 15.3, design-ledger.md §11.4): BillOps
 * admin and super admin succeed; an XamOps admin without the BillOps role, a customer, a demo account
 * and a Login-As session without super admin are denied; anonymous is 401; every non-GET refuses an
 * impersonated session, a missing CSRF header or a missing Idempotency-Key; service rules surface as the
 * named problem codes (422, 409).
 */
@SpringJUnitWebConfig(BillOpsAutoSpottingAdminControllerTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.impersonation.secret-key=dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0",
        "app.remember-me.key=test-only-remember-me-key"
})
class BillOpsAutoSpottingAdminControllerTest {

    private static final String BASE = "/api/xamops/billops-admin/autospotting";
    private static final UUID CONTRACT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String KEY = "11111111-1111-1111-1111-111111111111";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, ImpersonationFilter.class,
            TrialEnforcementFilter.class, com.xammer.cloud.security.AutoSpottingAdminAccess.class,
            com.xammer.cloud.security.DemoClientMembership.class,
            BillOpsAutoSpottingAdminController.class, AutoSpottingProblemAdvice.class})
    static class TestConfig {
    }

    @MockitoBean
    private ContractService contractService;
    @MockitoBean
    private GoLiveService goLiveService;
    @MockitoBean
    private com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand recomputeCommand;
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
        AutoSpottingIntentFilter intentFilter = new AutoSpottingIntentFilter(
                List.of("/api/xamops/billops-admin/autospotting/"), List.of("https://live.xamops.com"));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(intentFilter).build();
        when(idempotencyService.requireKey(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key == null || key.isBlank()) {
                throw new AutoSpottingApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "key");
            }
            return key;
        });
        when(contractService.list(3L)).thenReturn(List.of(contract()));
        when(contractService.create(eq(3L), any(), any(), anyString())).thenReturn(contract());
        when(contractService.end(eq(CONTRACT_ID), any(), any(), anyString())).thenReturn(contract());
        SavingsLedgerGoLive goLive = new SavingsLedgerGoLive();
        goLive.setProvider("AWS");
        goLive.setGoLiveAt(Instant.parse("2026-11-20T00:00:00Z"));
        goLive.setSetBy("5");
        goLive.setSetAt(Instant.parse("2026-11-19T00:00:00Z"));
        when(goLiveService.set(eq(Provider.AWS), any(), any(), anyString(), anyString())).thenReturn(goLive);
        when(goLiveService.list()).thenReturn(List.of(goLive));
    }

    @AfterEach
    void clear() {
        ImpersonationContext.clear();
    }

    private static AutoSpottingContract contract() {
        AutoSpottingContract c = new AutoSpottingContract();
        c.setId(CONTRACT_ID);
        c.setClientId(3L);
        c.setSharePct(new BigDecimal("12.500000"));
        c.setMonthlyCap(new BigDecimal("1000.00"));
        c.setDeclaredDiscountPct(new BigDecimal("0"));
        c.setEffectiveFrom(Instant.parse("2020-01-01T00:00:00Z"));
        c.setCreatedBy("5");
        c.setCreatedAt(Instant.parse("2019-12-01T00:00:00Z"));
        return c;
    }

    private static RequestPostProcessor as(String role) {
        return user(new ClientUserDetails("billops@example.com", null,
                List.of(new SimpleGrantedAuthority(role)), 1L, 5L, "default"));
    }

    private static final String CREATE = "{\"sharePct\":12.5,\"monthlyCap\":1000.00,\"discountDeclaration\":\"DECLARED\","
            + "\"declaredDiscountPct\":0,\"reason\":\"signed\"}";

    private MockHttpServletRequestBuilder post(String path, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType("application/json")
                .header(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE)
                .header("Idempotency-Key", KEY)
                .content(body);
    }

    // ── roles ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_BILLOPS_ADMIN", "ROLE_SUPER_ADMIN"})
    void billOpsAndSuperAdmin_readAndWrite(String role) throws Exception {
        mvc.perform(get(BASE + "/clients/3/contracts").with(as(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(CONTRACT_ID.toString()))
                .andExpect(jsonPath("$[0].sharePct").value("12.500000"))
                .andExpect(jsonPath("$[0].monthlyCap.amount").value("1000.00"))
                .andExpect(jsonPath("$[0].monthlyCap.currency").value("USD"))
                .andExpect(jsonPath("$[0].discountDeclaration").value("DECLARED"))
                .andExpect(jsonPath("$[0].declaredDiscountPct").value("0"));
        mvc.perform(post(BASE + "/clients/3/contracts", CREATE).with(as(role)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONTRACT_ID.toString()));
        mvc.perform(post(BASE + "/contracts/" + CONTRACT_ID + "/end", "{\"reason\":\"left\"}").with(as(role)))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + "/golive/aws", "{\"reason\":\"first lift\"}").with(as(role)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("AWS"));
        mvc.perform(get(BASE + "/golive").with(as(role))).andExpect(status().isOk());
    }

    @Test
    void recompute_isABillOpsAdminRead_withPlainDecimalStrings_andDeniedToEveryoneElse() throws Exception {
        UUID entry = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        when(recomputeCommand.recompute(entry)).thenReturn(new com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand.Result(
                entry.toString(), "aws-1", false, List.of(new com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand.FieldDiff(
                        "entry", "spot_cost", "4.1040000000", "4.1760000000", true))));
        for (String role : List.of("ROLE_BILLOPS_ADMIN", "ROLE_SUPER_ADMIN")) {
            mvc.perform(get(BASE + "/entries/" + entry + "/recompute").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entryId").value(entry.toString()))
                    .andExpect(jsonPath("$.calcVersion").value("aws-1"))
                    .andExpect(jsonPath("$.zeroDiff").value(false))
                    .andExpect(jsonPath("$.diffs[0].field").value("spot_cost"))
                    .andExpect(jsonPath("$.diffs[0].stored").value("4.1040000000"))
                    .andExpect(jsonPath("$.diffs[0].recomputed").value("4.1760000000"))
                    .andExpect(jsonPath("$.diffs[0].minorUnitDiffers").value(true));
        }
        for (String role : List.of("ROLE_XAMOPS_ADMIN", "ROLE_USER")) {
            mvc.perform(get(BASE + "/entries/" + entry + "/recompute").with(as(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE + "/entries/" + entry + "/recompute")).andExpect(status().isUnauthorized());
        // A recompute is a read: no POST route exists for it.
        mvc.perform(post(BASE + "/entries/" + entry + "/recompute", "{}").with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().is4xxClientError());
    }
    @Test
    void recompute_unknownEntry_is404() throws Exception {
        UUID entry = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-ffffffffffff");
        when(recomputeCommand.recompute(entry)).thenThrow(new AutoSpottingApiException(HttpStatus.NOT_FOUND, "ENTRY_NOT_FOUND", "none"));
        mvc.perform(get(BASE + "/entries/" + entry + "/recompute").with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ENTRY_NOT_FOUND"));
    }
    @Test
    void xamOpsAdminWithoutTheBillOpsRole_customer_isForbidden() throws Exception {
        for (String role : List.of("ROLE_XAMOPS_ADMIN", "ROLE_USER", "ROLE_BILLOPS_ADMIN_SUB")) {
            mvc.perform(get(BASE + "/clients/3/contracts").with(as(role))).andExpect(status().isForbidden());
            mvc.perform(post(BASE + "/clients/3/contracts", CREATE).with(as(role))).andExpect(status().isForbidden());
            mvc.perform(post(BASE + "/golive/aws", "{}").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void anonymous_isUnauthorized() throws Exception {
        mvc.perform(get(BASE + "/clients/3/contracts")).andExpect(status().isUnauthorized());
    }

    @Test
    void demoAccount_isDenied_evenWithTheBillOpsRole() throws Exception {
        com.xammer.cloud.domain.User demo = new com.xammer.cloud.domain.User();
        demo.setEmail("demo.billops@xammer.in");
        when(userRepository.findById(5L)).thenReturn(Optional.of(demo));
        mvc.perform(get(BASE + "/clients/3/contracts").with(as("ROLE_BILLOPS_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/clients/3/contracts", CREATE).with(as("ROLE_BILLOPS_ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(contractService);
    }

    @Test
    void impersonated_readsOnlyAsSuperAdmin_andNeverWrites() throws Exception {
        ImpersonationContext.setImpersonatedUserId(99L);
        mvc.perform(get(BASE + "/clients/3/contracts").with(as("ROLE_SUPER_ADMIN")).header("X-Impersonate-User", "99"))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + "/clients/3/contracts", CREATE).with(as("ROLE_SUPER_ADMIN")).header("X-Impersonate-User", "99"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AS_FORBIDDEN_ROLE"));
        mvc.perform(post(BASE + "/contracts/" + CONTRACT_ID + "/end", "{\"reason\":\"x\"}")
                        .with(as("ROLE_SUPER_ADMIN")).header("X-Impersonate-User", "99"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/golive/aws", "{\"reason\":\"x\"}").with(as("ROLE_SUPER_ADMIN")).header("X-Impersonate-User", "99"))
                .andExpect(status().isForbidden());
        // A Login-As session of a BillOps admin (no super admin) cannot even read.
        mvc.perform(get(BASE + "/clients/3/contracts").with(as("ROLE_BILLOPS_ADMIN")).header("X-Impersonate-User", "99"))
                .andExpect(status().isForbidden());
        verify(contractService, never()).create(anyLong(), any(), any(), anyString());
    }

    // ── headers ──────────────────────────────────────────────────────────────

    @Test
    void missingCsrfHeader_is403_andMissingIdempotencyKey_is400() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(BASE + "/golive/aws")
                        .contentType("application/json").header("Idempotency-Key", KEY).content("{\"reason\":\"x\"}")
                        .with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(BASE + "/golive/aws")
                        .contentType("application/json")
                        .header(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE)
                        .content("{\"reason\":\"x\"}").with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        verifyNoInteractions(goLiveService);
    }

    // ── service rules surface as named problems ──────────────────────────────

    @Test
    void validationAndConflicts_surfaceAsProblems() throws Exception {
        when(contractService.create(eq(3L), any(), any(), anyString())).thenThrow(
                new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED", "effectiveFrom cannot be in the past."));
        mvc.perform(post(BASE + "/clients/3/contracts", CREATE).with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CHECK_FAILED"));
        when(contractService.create(eq(3L), any(), any(), anyString())).thenThrow(
                new AutoSpottingApiException(HttpStatus.CONFLICT, "CONTRACT_OVERLAP", "overlap"));
        mvc.perform(post(BASE + "/clients/3/contracts", CREATE).with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTRACT_OVERLAP"));
        when(goLiveService.set(eq(Provider.AWS), any(), any(), anyString(), anyString())).thenThrow(
                new AutoSpottingApiException(HttpStatus.CONFLICT, "GOLIVE_ALREADY_SET", "set"));
        mvc.perform(post(BASE + "/golive/AWS", "{\"reason\":\"again\"}").with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GOLIVE_ALREADY_SET"));
        mvc.perform(post(BASE + "/golive/azure", "{\"reason\":\"x\"}").with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRequestIsPassedThroughUnchanged_withExactDecimals() throws Exception {
        mvc.perform(post(BASE + "/clients/3/contracts", "{\"sharePct\":12.123456,\"monthlyCap\":999999999.99,"
                        + "\"discountDeclaration\":\"NOT_DECLARED\",\"reason\":\"signed\"}")
                        .with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isCreated());
        org.mockito.ArgumentCaptor<ContractService.CreateCommand> captor =
                org.mockito.ArgumentCaptor.forClass(ContractService.CreateCommand.class);
        verify(contractService).create(eq(3L), captor.capture(), any(), anyString());
        assertThat(captor.getValue().sharePct()).isEqualTo(new BigDecimal("12.123456"));
        assertThat(captor.getValue().monthlyCap()).isEqualTo(new BigDecimal("999999999.99"));
        assertThat(captor.getValue().declaredDiscountPct()).isNull();
        assertThat(captor.getValue().discountDeclaration()).isEqualTo("NOT_DECLARED");
    }
}
