package com.xammer.cloud.controller;

import com.xammer.cloud.config.SecurityConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import com.xammer.cloud.security.TrialEnforcementFilter;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.auth.TrialService;
import com.xammer.cloud.service.matters.DatastoreDataFlowService;
import com.xammer.cloud.service.matters.DatastoreDrawerService;
import com.xammer.cloud.service.matters.DatastoreListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Without a session the Datastores endpoints answer 401 from the real {@link SecurityConfig} chain, and
 * the controller is never reached (design 5.10 step 1). Pattern of
 * {@code VerticalAutomationUnauthenticatedTest}: only the collaborators are mocked, and
 * {@code SecurityConfig} is imported, never edited.
 */
@SpringJUnitWebConfig(DspmDatastoreUnauthenticatedTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.impersonation.secret-key=" + DspmDatastoreUnauthenticatedTest.SECRET,
        "app.remember-me.key=test-only-remember-me-key"
})
class DspmDatastoreUnauthenticatedTest {

    /** Base64 of a 48-byte throwaway string; HS256 needs 32+. Same fixture style as the sibling tests. */
    static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, TrialEnforcementFilter.class,
            DspmDatastoreController.class})
    static class TestConfig {
    }

    @MockitoBean
    private XamLensTabGuard guard;
    @MockitoBean
    private DatastoreListService listService;
    @MockitoBean
    private DatastoreDrawerService drawerService;
    @MockitoBean
    private DatastoreDataFlowService dataFlowService;

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
    }

    @Test
    @DisplayName("the list returns 401, not a redirect and not data, and the controller is never reached")
    void listIsAuthenticated() throws Exception {
        mvc.perform(get("/api/dspm/datastores").param("tab", "cloud"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(guard, listService, drawerService, dataFlowService);
    }

    @Test
    @DisplayName("a drawer endpoint returns 401 and the controller is never reached")
    void drawerIsAuthenticated() throws Exception {
        mvc.perform(get("/api/dspm/datastores/0123456789abcdef01234567/overview"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(guard, listService, drawerService, dataFlowService);
    }
}
