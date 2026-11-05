package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.config.SecurityConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.ImpersonationFilter;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.OperationStatus;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingSwitchRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import com.xammer.cloud.security.TrialEnforcementFilter;
import com.xammer.cloud.service.auth.TrialService;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/autospotting/v2/accounts/{accountId}/operations/{operationId}} (plan-phase-09.md
 * task 9.3, design-ux-ops.md §9.2, AC-SPN-07). The ownership rows: the owner reads 200; a user of
 * another client is 403 (never 404 leaking existence, never 200); anonymous is 401; an op that does
 * not belong to the resolved account is 404. Runs through the real {@link SecurityConfig} chain with
 * {@link CloudAccountAccessService} mocked to the Phase A rules.
 */
@SpringJUnitWebConfig(OperationControllerTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.impersonation.secret-key=dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0",
        "app.remember-me.key=test-only-remember-me-key"
})
class OperationControllerTest {

    private static final long ACCOUNT_ID = 42L;
    private static final UUID OP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GROUP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, ImpersonationFilter.class,
            TrialEnforcementFilter.class, com.xammer.cloud.security.AutoSpottingAdminAccess.class,
            com.xammer.cloud.security.DemoClientMembership.class,
            AutoSpottingSwitchProperties.class, AutoSpottingFeatureFlags.class, SwitchService.class,
            AutoSpottingV2Controller.class, AutoSpottingProblemAdvice.class})
    static class TestConfig {
    }

    @MockitoBean
    private CloudAccountAccessService accountAccessService;
    @MockitoBean
    private SpotOperationRepository operationRepository;
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
        when(switchRepository.findEngagedScopeKeys()).thenReturn(List.of());
        when(allowlistRepository.findAllowedCloudAccountIds()).thenReturn(List.of());
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
        return account;
    }

    private static SpotOperation op() {
        SpotOperation op = new SpotOperation();
        op.setId(OP_ID);
        op.setGroupId(GROUP_ID);
        op.setCloudAccountId(ACCOUNT_ID);
        op.setKind(OperationKind.ENABLE);
        op.setStatus(OperationStatus.RUNNING);
        op.setPhase(OperationPhase.FORWARD);
        op.setStepSeq(2);
        op.setDryRun(false);
        op.setDeadlineAt(Instant.now().plusSeconds(3600));
        return op;
    }

    private String url() {
        return "/api/autospotting/v2/accounts/" + ACCOUNT_ID + "/operations/" + OP_ID;
    }

    @Test
    void owner_readsOperation() throws Exception {
        when(accountAccessService.requireAccess(any(), eq(ACCOUNT_ID))).thenReturn(account());
        when(operationRepository.findById(OP_ID)).thenReturn(Optional.of(op()));

        mvc.perform(get(url()).with(principal("owner@example.com", 5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(OP_ID.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.step").value(2));
    }

    @Test
    void otherClientUser_isForbidden_notFoundNorOk() throws Exception {
        when(accountAccessService.requireAccess(any(), eq(ACCOUNT_ID)))
                .thenThrow(new AccessDeniedException("Account not found or access denied."));

        mvc.perform(get(url()).with(principal("intruder@example.com", 9L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AS_FORBIDDEN_ROLE"));
    }

    @Test
    void anonymous_isUnauthorized() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
    }

    @Test
    void operationOfAnotherAccount_is404() throws Exception {
        when(accountAccessService.requireAccess(any(), eq(ACCOUNT_ID))).thenReturn(account());
        SpotOperation foreign = op();
        foreign.setCloudAccountId(999L);
        when(operationRepository.findById(OP_ID)).thenReturn(Optional.of(foreign));

        mvc.perform(get(url()).with(principal("owner@example.com", 5L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void impersonatedOwner_readsOperation_200() throws Exception {
        // An impersonated (Login-As) session reading its own account's op is allowed (read-only).
        ImpersonationContext.setImpersonatedUserId(5L);
        when(accountAccessService.requireAccess(any(), eq(ACCOUNT_ID))).thenReturn(account());
        when(operationRepository.findById(OP_ID)).thenReturn(Optional.of(op()));

        mvc.perform(get(url())
                        .with(principal("impersonated@example.com", 5L))
                        .header("X-Impersonate-User", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(OP_ID.toString()));
    }
}
