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
import com.xammer.cloud.service.matters.DashboardService;
import com.xammer.cloud.service.matters.DatastoreDataFlowService;
import com.xammer.cloud.service.matters.DatastoreDrawerService;
import com.xammer.cloud.service.matters.DatastoreListService;
import com.xammer.cloud.service.matters.DspmPagesService;
import com.xammer.cloud.service.matters.EndpointDrawerService;
import com.xammer.cloud.service.matters.MisconfigurationService;
import com.xammer.cloud.service.matters.EndpointFileService;
import com.xammer.cloud.service.matters.FilesService;
import com.xammer.cloud.service.matters.SaasDrawerService;
import com.xammer.cloud.service.matters.SaasFileService;
import com.xammer.cloud.service.matters.ScanConfigurationService;
import com.xammer.cloud.service.matters.ScanManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
            DspmDatastoreController.class, DspmSaasController.class, DspmEndpointController.class,
            DspmMisconfigurationController.class,
            DspmFilesController.class, DspmPagesController.class, DspmScanController.class})
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
    private SaasDrawerService saasDrawerService;
    @MockitoBean
    private SaasFileService saasFileService;
    @MockitoBean
    private EndpointDrawerService endpointDrawerService;
    @MockitoBean
    private MisconfigurationService misconfigurationService;
    @MockitoBean
    private FilesService filesService;
    @MockitoBean
    private EndpointFileService endpointFileService;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private DspmPagesService pagesService;

    @MockitoBean
    private ScanManagementService scanManagementService;

    @MockitoBean
    private ScanConfigurationService scanConfigurationService;

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
        mvc.perform(get("/api/dspm/data-flow/tables/0000000a-0000-4000-8000-00000000000b/relations"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(guard, listService, drawerService, dataFlowService);
    }

    @Test
    @DisplayName("a SaaS drawer and a SaaS file endpoint return 401 and the controller is never reached")
    void saasIsAuthenticated() throws Exception {
        mvc.perform(get("/api/dspm/datastores/0123456789abcdef01234567/saas/detail"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dspm/saas-files/abcdef0123456789abcdef01/lineage"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(guard, saasDrawerService, saasFileService);
    }

    @Test
    @DisplayName("an Endpoint drawer endpoint returns 401 and the controller is never reached")
    void endpointIsAuthenticated() throws Exception {
        mvc.perform(get("/api/dspm/datastores/0123456789abcdef01234567/endpoint/occurrences")
                        .param("entityName", "EMAIL ADDRESS"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(guard, endpointDrawerService);
    }

    @Test
    @DisplayName("the Misconfigurations list returns 401 and the controller is never reached")
    void misconfigurationListIsAuthenticated() throws Exception {
        mvc.perform(get("/api/dspm/misconfigurations"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(guard, misconfigurationService);
    }

    @Test
    @DisplayName("a Misconfigurations policy returns 401 and the controller is never reached")
    void misconfigurationDetailIsAuthenticated() throws Exception {
        mvc.perform(get("/api/dspm/misconfigurations/policies/p-0123456789abcdef"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(guard, misconfigurationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/dspm/files/saas",
            "/api/dspm/files/saas/filter-options",
            "/api/dspm/files/endpoint",
            "/api/dspm/files/endpoint/filter-options",
            "/api/dspm/endpoint-files/0000aaaa-0000-4000-8000-00000000000c/overview",
            "/api/dspm/endpoint-files/0000aaaa-0000-4000-8000-00000000000c/activity",
            "/api/dspm/endpoint-files/0000aaaa-0000-4000-8000-00000000000c/lineage",
            "/api/dspm/endpoint-files/0000aaaa-0000-4000-8000-00000000000c/copies"})
    @DisplayName("each Files page endpoint (F1 to F8) returns 401 and the controller is never reached")
    void filesAreAuthenticated(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());

        verifyNoInteractions(guard, filesService, endpointFileService);
    }

    @Test
    @DisplayName("build 6: the Dashboard and a Scan Management endpoint return 401 and the controller is never reached")
    void build6PagesAreAuthenticated() throws Exception {
        mvc.perform(get("/api/dspm/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dspm/scan-management/schedules")).andExpect(status().isUnauthorized());
        verifyNoInteractions(guard, dashboardService, pagesService, scanManagementService, scanConfigurationService);
    }
}
