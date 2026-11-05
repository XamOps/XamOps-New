package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.config.SecurityConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.ImpersonationFilter;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/autospotting/v2/config} (plan-phase-09.md task 9.3, design-ux-ops.md §2.1). Any
 * authenticated user reads it; it carries no tenant data. It reports {@code shellEnabled} (false by
 * default here), the dry-run flag and per-provider readiness with the safe defaults, and the engaged
 * switch names. Anonymous is 401. Runs through the real {@link SecurityConfig} chain, with the switch
 * and flag beans real and their stores mocked empty.
 */
@SpringJUnitWebConfig(AutoSpottingV2ConfigControllerTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.impersonation.secret-key=dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0",
        "app.remember-me.key=test-only-remember-me-key"
})
class AutoSpottingV2ConfigControllerTest {

    private static final String CONFIG = "/api/autospotting/v2/config";

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
    private AutoSpottingAllowlistEntryRepository allowlistRepository;
    @MockitoBean
    private AutoSpottingSwitchRepository switchRepository;
    @MockitoBean
    private SpotOperationRepository operationRepository;
    @MockitoBean
    private CloudAccountAccessService accountAccessService;
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

    private static RequestPostProcessor owner() {
        return user(new ClientUserDetails("owner@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")), 1L, 5L, "default"));
    }

    @Test
    void owner_readsConfig_withSafeDefaults() throws Exception {
        mvc.perform(get(CONFIG).with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shellEnabled").value(false))
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.providers.AWS").exists())
                .andExpect(jsonPath("$.providers.GCP").exists())
                // v2 mutations off by default, so each provider is still Beta and not enabled.
                .andExpect(jsonPath("$.providers.AWS.v2MutationsEnabled").value(false))
                .andExpect(jsonPath("$.providers.AWS.beta").value(true))
                .andExpect(jsonPath("$.providers.AWS.allowlistOnly").value(true))
                .andExpect(jsonPath("$.engagedSwitches").isArray());
    }

    @Test
    void anonymous_isUnauthorized() throws Exception {
        mvc.perform(get(CONFIG)).andExpect(status().isUnauthorized());
    }
}
