package com.xammer.cloud.controller;

import com.xammer.cloud.config.SecurityConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import com.xammer.cloud.security.TrialEnforcementFilter;
import com.xammer.cloud.service.auth.TrialService;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationController;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationReadService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An unauthenticated request to the engine's paths gets <strong>401</strong>, and never reaches the
 * controller.
 *
 * <p>This runs the <em>real</em> {@link SecurityConfig} filter chains through MockMvc — the same
 * pattern {@code ImpersonationJwtAsyncDispatchTest} uses — with only their collaborators mocked.
 * {@code VerticalAutomationSecurityCoverageTest} asserts the configuration's <em>shape</em> (no
 * {@code permitAll} rule covers these paths, the chain still ends in
 * {@code anyRequest().authenticated()}); this asserts the resulting <em>behaviour</em>, which is what
 * the plan's item-9 verification actually asked for. Both are kept: the shape test fails on a bad
 * rule being added, this one fails if the chain stops applying.
 *
 * <p>Not {@code @SpringBootTest}/{@code @WebMvcTest}: those pull in every {@code @Component} filter
 * and the database behind them. <strong>{@code SecurityConfig} is imported, never edited.</strong>
 */
@SpringJUnitWebConfig(VerticalAutomationUnauthenticatedTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.impersonation.secret-key=" + VerticalAutomationUnauthenticatedTest.SECRET,
        "app.remember-me.key=test-only-remember-me-key"
})
class VerticalAutomationUnauthenticatedTest {

    /** Base64 of a 48-byte throwaway string; HS256 needs 32+. Same fixture style as the sibling test. */
    static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";

    private static final String BASE = "/api/eks/automation/vertical";
    private static final String QUERY = "?accountId=102227938259&region=ap-south-1";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, TrialEnforcementFilter.class,
            VerticalAutomationController.class})
    static class TestConfig {
    }

    @MockitoBean
    private CloudAccountAccessService accessService;
    @MockitoBean
    private KubernetesClusterConfigRepository clusterConfigRepository;
    @MockitoBean
    private VerticalAutomationReadService readService;

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
    @DisplayName("the read endpoint returns 401, not a redirect to /login and not data")
    void theReadEndpointIsAuthenticated() throws Exception {
        mvc.perform(get(BASE + "/xamops-test-cluster" + QUERY))
                .andExpect(status().isUnauthorized());

        // A 401 from the entry point rather than from the controller: nothing downstream ran, so
        // no tenancy check was relied on to refuse this.
        verifyNoInteractions(accessService, clusterConfigRepository, readService);
    }

    @Test
    @DisplayName("every contract-only path is authenticated too, so none can ship public by accident")
    void theContractOnlyPathsAreAuthenticatedBeforeTheyExist() throws Exception {
        // These four have no handler yet (404 once authenticated). The assertion is that the chain
        // refuses them first: a path that is 401 while unmapped cannot become public by someone
        // adding a @PostMapping later.
        mvc.perform(get(BASE + "/xamops-test-cluster/actions"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(BASE + "/xamops-test-cluster/workloads/team-a/Deployment/api/policy")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/xamops-test-cluster/workloads/team-a/Deployment/api/apply")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a junk bearer token is 401 as well, not an anonymous read")
    void aJunkTokenIsRefused() throws Exception {
        // With an Authorization header the request takes SecurityConfig's @Order(1) STATELESS
        // chain, which is a different code path to the session chain above.
        mvc.perform(get(BASE + "/xamops-test-cluster" + QUERY)
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accessService, clusterConfigRepository, readService);
    }
}
