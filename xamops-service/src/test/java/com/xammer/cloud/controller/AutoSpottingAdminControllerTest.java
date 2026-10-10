package com.xammer.cloud.controller;

import com.xammer.cloud.config.SecurityConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import com.xammer.cloud.security.TrialEnforcementFilter;
import com.xammer.cloud.service.AutoSpottingService;
import com.xammer.cloud.service.auth.TrialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin dashboard reads at their routable path (NO-02, core §6.6), through the real
 * {@link SecurityConfig} chains and method security, with only their collaborators mocked.
 *
 * <p>Super admins, XamOps admins and BillOps admins read every endpoint; any other role gets 403
 * and an anonymous caller 401. Nothing answers on the old {@code /api/admin/autospotting} path,
 * which the gateway sends to billops.
 */
@SpringJUnitWebConfig(AutoSpottingAdminControllerTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.impersonation.secret-key=" + AutoSpottingAdminControllerTest.SECRET,
        "app.remember-me.key=test-only-remember-me-key"
})
class AutoSpottingAdminControllerTest {

    /** Base64 of "test-only-impersonation-signing-key-not-a-secret" (48 bytes; HS256 needs 32+). */
    static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";

    private static final String BASE = "/api/xamops/autospotting-admin";
    private static final List<String> READS = List.of(
            "/total-savings", "/recent-activity?limit=50", "/historical-savings?days=7",
            "/account-breakdown", "/customer-savings");

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, TrialEnforcementFilter.class,
            AutoSpottingAdminController.class})
    static class TestConfig {
    }

    @MockitoBean
    private AutoSpottingService autoSpottingService;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private CustomAuthenticationSuccessHandler authenticationSuccessHandler;
    @MockitoBean
    private CustomAuthenticationFailureHandler authenticationFailureHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler logoutSuccessHandler;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private TrialService trialService;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(autoSpottingService.getAdminDashboardMetrics()).thenReturn(Map.of(
                "totalSavings", 0.0, "legacyUnverifiedTotal", 12.5, "activeSpotInstances", 3));
        when(autoSpottingService.getAdminRecentActivity(anyInt())).thenReturn(List.of());
        when(autoSpottingService.getAdminHistoricalSavings(anyInt())).thenReturn(List.of());
        when(autoSpottingService.getAdminAccountBreakdown()).thenReturn(List.of());
        when(autoSpottingService.getCustomerSpotSavings()).thenReturn(List.of());
    }

    private static RequestPostProcessor as(String role) {
        return user("someone@example.com").authorities(new SimpleGrantedAuthority(role));
    }

    @ParameterizedTest
    @ValueSource(strings = { "ROLE_SUPER_ADMIN", "ROLE_XAMOPS_ADMIN", "ROLE_BILLOPS_ADMIN" })
    void adminRoles_readEveryEndpoint(String role) throws Exception {
        for (String read : READS) {
            mvc.perform(get(BASE + read).with(as(role))).andExpect(status().isOk());
        }
    }

    @Test
    void totalSavings_returnsTheLegacyFigureApart_andNoDummyKeys() throws Exception {
        mvc.perform(get(BASE + "/total-savings").with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSavings").value(0.0))
                .andExpect(jsonPath("$.legacyUnverifiedTotal").value(12.5))
                .andExpect(jsonPath("$.conversionRate").doesNotExist())
                .andExpect(jsonPath("$.monthlySavings").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = { "ROLE_XAMOPS", "ROLE_BILLOPS", "ROLE_BILLOPS_ADMIN_SUB" })
    void otherRoles_includingCustomers_areForbidden(String role) throws Exception {
        clearInvocations(autoSpottingService);
        for (String read : READS) {
            mvc.perform(get(BASE + read).with(as(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(autoSpottingService);
    }

    @Test
    void anonymous_isUnauthorized() throws Exception {
        clearInvocations(autoSpottingService);
        for (String read : READS) {
            mvc.perform(get(BASE + read)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(autoSpottingService);
    }

    @Test
    void oldAdminPath_answersNothing() throws Exception {
        mvc.perform(get("/api/admin/autospotting/total-savings").with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/autospotting/customer-savings").with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isNotFound());
    }
}
