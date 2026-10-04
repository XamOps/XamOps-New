package com.xammer.cloud.config.multitenancy;

import com.xammer.cloud.config.SecurityConfig;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import com.xammer.cloud.security.TrialEnforcementFilter;
import com.xammer.cloud.service.auth.TrialService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.security.Principal;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A BillOps "Login As" request must stay authenticated on the async dispatch of an async endpoint.
 *
 * <p>Regression guard for "Login as" -> Account Dashboard -> login page. Spring Security 7
 * re-authorizes ASYNC dispatches, and {@link ImpersonationJwtFilter} does not run on them. Until the
 * filter saved its context where the STATELESS chain reads it back, the async dispatch of
 * {@code GET /api/xamops/finops/budgets} (a CompletableFuture endpoint the AWS dashboard calls) ran
 * as anonymous and got a 401. The frontend treats a 401 as an expired session, clears the tab's
 * impersonation token and sends it to /login.
 *
 * <p>Runs the real {@link SecurityConfig} chains through MockMvc with only their collaborators
 * mocked. Not {@code @SpringBootTest}/{@code @WebMvcTest}: those would pull in every
 * {@code @Component} filter and the database behind it.
 */
@SpringJUnitWebConfig(ImpersonationJwtAsyncDispatchTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.impersonation.secret-key=" + ImpersonationJwtAsyncDispatchTest.SECRET,
        "app.remember-me.key=test-only-remember-me-key"
})
class ImpersonationJwtAsyncDispatchTest {

    /** Base64 of "test-only-impersonation-signing-key-not-a-secret" (48 bytes; HS256 needs 32+). */
    static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";

    private static final long TARGET_USER_ID = 42L;
    private static final String TARGET_USERNAME = "target-user";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, TrialEnforcementFilter.class,
            ProbeController.class})
    static class TestConfig {
    }

    /** One endpoint of each kind the dashboard calls. */
    @RestController
    static class ProbeController {

        /** Same shape as FinOpsController.getBudgets: the result completes on another thread. */
        @GetMapping("/api/test-probe/async")
        public CompletableFuture<String> async(Principal principal) {
            String name = principal.getName();
            return CompletableFuture.supplyAsync(() -> name);
        }

        @GetMapping("/api/test-probe/sync")
        public String sync(Principal principal) {
            return principal.getName();
        }
    }

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

        User target = new User();
        target.setId(TARGET_USER_ID);
        target.setUsername(TARGET_USERNAME);
        when(userRepository.findById(TARGET_USER_ID)).thenReturn(Optional.of(target));
    }

    /** Minted the way billops' ImpersonationTokenController.buildToken does it. */
    private static String impersonationToken() {
        return Jwts.builder()
                .setSubject(String.valueOf(TARGET_USER_ID))
                .claim("role", "ROLE_XAMOPS")
                .setIssuer("billops")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60L * 60 * 1000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)), SignatureAlgorithm.HS256)
                .compact();
    }

    private MvcResult startAsyncRequest() throws Exception {
        return mvc.perform(get("/api/test-probe/async")
                        .header("Authorization", "Bearer " + impersonationToken()))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    void asyncEndpointStaysAuthenticatedOnTheAsyncDispatch() throws Exception {
        MvcResult started = startAsyncRequest();

        // Before the fix this dispatch ran as anonymous and returned 401.
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(TARGET_USERNAME));
    }

    @Test
    void asyncRequestNeverCreatesAnHttpSession() throws Exception {
        MvcResult started = startAsyncRequest();
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());

        // The impersonated tab must never be given (or overwrite) a JSESSIONID.
        assertThat(started.getRequest().getSession(false)).isNull();
    }

    @Test
    void syncEndpointIsAuthenticated() throws Exception {
        mvc.perform(get("/api/test-probe/sync")
                        .header("Authorization", "Bearer " + impersonationToken()))
                .andExpect(status().isOk())
                .andExpect(content().string(TARGET_USERNAME));
    }

    @Test
    void invalidTokenIsStillRejected() throws Exception {
        mvc.perform(get("/api/test-probe/async")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"message\":\"Invalid or expired impersonation token\"}"));
    }
}
