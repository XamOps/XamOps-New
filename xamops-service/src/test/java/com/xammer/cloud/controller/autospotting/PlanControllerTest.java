package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.config.SecurityConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.ImpersonationFilter;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingSwitchRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import com.xammer.cloud.security.TrialEnforcementFilter;
import com.xammer.cloud.service.auth.TrialService;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService;
import com.xammer.cloud.service.autospotting.core.plan.PlanService;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The v2 plan endpoints (plan-phase-11.md task 11.5, design-core.md §7.1, §7.4, §11, ux §9.2). The
 * controller-level rows: Idempotency-Key required (400 when missing), a valid create is 201 with the
 * DTO, a reused key with a different body is 422 {@code IDEMPOTENCY_KEY_REUSED}, confirm is 202
 * {@code {operationId}}, and every error is {@code application/problem+json} with a stable code. The
 * service layer is mocked (its logic is covered by {@code PlanServiceTest}/{@code ConfirmServiceTest}).
 */
@SpringJUnitWebConfig(PlanControllerTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.impersonation.secret-key=dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0",
        "app.remember-me.key=test-only-remember-me-key"
})
class PlanControllerTest {

    private static final long ACCOUNT_ID = 42L;
    private static final UUID PLAN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GROUP_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID OP_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, ImpersonationFilter.class,
            TrialEnforcementFilter.class, com.xammer.cloud.security.AutoSpottingAdminAccess.class,
            com.xammer.cloud.security.DemoClientMembership.class,
            IdempotencyService.class, PlanController.class, AutoSpottingProblemAdvice.class})
    static class TestConfig {
    }

    @MockitoBean
    private CloudAccountAccessService accountAccessService;
    @MockitoBean
    private PlanService planService;
    @MockitoBean
    private ConfirmService confirmService;
    @MockitoBean
    private SpotPlanRepository planRepository;
    @MockitoBean
    private AutoSpottingSwitchRepository switchRepository;
    @MockitoBean
    private AutoSpottingAllowlistEntryRepository allowlistRepository;
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
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(accountAccessService.requireAccess(any(), eq(ACCOUNT_ID))).thenReturn(account());
    }

    @AfterEach
    void clearImpersonation() {
        ImpersonationContext.clear();
    }

    private static RequestPostProcessor principal(String username, long id) {
        return user(new ClientUserDetails(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")), 1L, id, "default"));
    }

    private static CloudAccount account() {
        CloudAccount account = new CloudAccount();
        account.setId(ACCOUNT_ID);
        account.setProvider("AWS");
        return account;
    }

    private static SpotPlan plan() {
        SpotPlan p = new SpotPlan();
        p.setId(PLAN_ID);
        p.setGroupId(GROUP_ID);
        p.setCloudAccountId(ACCOUNT_ID);
        p.setKind(OperationKind.ENABLE);
        p.setStatus(SpotPlan.PlanStatus.OPEN);
        p.setFromState(GroupState.UNMANAGED);
        p.setVersion(1);
        p.setSchemaVersion(1);
        p.setLiveFingerprint("fp:1");
        p.setBody("{\"target\":{}}");
        p.setWarningIds("[]");
        p.setCreatedBy("owner");
        p.setCreatedAt(Instant.now());
        p.setExpiresAt(Instant.now().plusSeconds(600));
        return p;
    }

    private String plansUrl() {
        return "/api/autospotting/v2/accounts/" + ACCOUNT_ID + "/plans";
    }

    private String confirmUrl() {
        return plansUrl() + "/" + PLAN_ID + "/confirm";
    }

    @Test
    void createPlan_missingIdempotencyKey_is400() throws Exception {
        mvc.perform(post(plansUrl())
                        .with(principal("owner@example.com", 5L))
                        .contentType("application/json")
                        .content("{\"kind\":\"ENABLE\",\"groupRef\":\"us-east-1/asg\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void createPlan_valid_is201WithDto() throws Exception {
        when(planService.createPlan(any(), any(), any())).thenReturn(plan());

        mvc.perform(post(plansUrl())
                        .with(principal("owner@example.com", 5L))
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content("{\"kind\":\"ENABLE\",\"groupRef\":\"us-east-1/asg\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planId").value(PLAN_ID.toString()))
                .andExpect(jsonPath("$.kind").value("ENABLE"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void createPlan_reusedKeyDifferentBody_is422() throws Exception {
        when(planService.createPlan(any(), any(), any()))
                .thenThrow(AutoSpottingApiException.idempotencyKeyReused());

        mvc.perform(post(plansUrl())
                        .with(principal("owner@example.com", 5L))
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content("{\"kind\":\"ENABLE\",\"groupRef\":\"us-east-1/asg\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void createPlan_foreignAccount_is403() throws Exception {
        when(accountAccessService.requireAccess(any(), eq(ACCOUNT_ID)))
                .thenThrow(new AccessDeniedException("Account not found or access denied."));

        mvc.perform(post(plansUrl())
                        .with(principal("intruder@example.com", 9L))
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content("{\"kind\":\"ENABLE\",\"groupRef\":\"us-east-1/asg\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AS_FORBIDDEN_ROLE"));
    }

    @Test
    void createPlan_invalidKind_is400ProblemJson() throws Exception {
        mvc.perform(post(plansUrl())
                        .with(principal("owner@example.com", 5L))
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content("{\"kind\":\"NONSENSE\",\"groupRef\":\"us-east-1/asg\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("CHECK_FAILED"));
    }

    @Test
    void createPlan_unknownOptionKey_is400OptionUnknown() throws Exception {
        mvc.perform(post(plansUrl())
                        .with(principal("owner@example.com", 5L))
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content("{\"kind\":\"ENABLE\",\"groupRef\":\"us-east-1/asg\",\"options\":{\"allowSpotMagic\":true}}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("OPTION_UNKNOWN"));
    }
    @Test
    void confirm_valid_is202WithOperationId() throws Exception {
        when(confirmService.confirm(any(), any(), eq(PLAN_ID), any()))
                .thenReturn(new ConfirmService.ConfirmResult(OP_ID, "QUEUED"));

        mvc.perform(post(confirmUrl())
                        .with(principal("owner@example.com", 5L))
                        .header("Idempotency-Key", "key-2")
                        .contentType("application/json")
                        .content("{\"planVersion\":1,\"acknowledgedWarningIds\":[],\"fullSpotOptIn\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(OP_ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void confirm_missingIdempotencyKey_is400() throws Exception {
        mvc.perform(post(confirmUrl())
                        .with(principal("owner@example.com", 5L))
                        .contentType("application/json")
                        .content("{\"planVersion\":1,\"acknowledgedWarningIds\":[],\"fullSpotOptIn\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void confirm_staleFromService_is409ProblemJson() throws Exception {
        when(confirmService.confirm(any(), any(), eq(PLAN_ID), any()))
                .thenThrow(new AutoSpottingApiException(HttpStatus.CONFLICT, "PLAN_STALE",
                        "The group changed after the plan was created."));

        mvc.perform(post(confirmUrl())
                        .with(principal("owner@example.com", 5L))
                        .header("Idempotency-Key", "key-2")
                        .contentType("application/json")
                        .content("{\"planVersion\":1,\"acknowledgedWarningIds\":[],\"fullSpotOptIn\":false}"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("PLAN_STALE"));
    }
}
