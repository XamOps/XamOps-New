package com.xammer.cloud.controller;

import com.xammer.cloud.config.SecurityConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.ImpersonationFilter;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.AutoSpottingAdminAccess;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import com.xammer.cloud.security.TrialEnforcementFilter;
import com.xammer.cloud.service.AutoSpottingService;
import com.xammer.cloud.service.auth.TrialService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
 * {@link SecurityConfig} chains, both Login-As filters and method security, with only their
 * collaborators mocked.
 *
 * <p>A normal session with the super, XamOps or BillOps admin role reads every endpoint; any other
 * role gets 403 and an anonymous caller 401. A Login-As session (a billops-signed token, or the
 * X-Impersonate-User header) reads them only as a super admin, and the OTP-exempt demo accounts
 * get 403 whatever their role (P02-R3, P02-R8). Nothing answers on the old
 * {@code /api/admin/autospotting} path, which the gateway sends to billops.
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

    private static final long ADMIN_USER_ID = 11L;
    private static final long TARGET_USER_ID = 42L;
    private static final long DEMO_USER_ID = 501L;

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, ImpersonationFilter.class,
            TrialEnforcementFilter.class, AutoSpottingAdminAccess.class,
            com.xammer.cloud.security.DemoClientMembership.class, AutoSpottingAdminController.class})
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
    @Autowired
    private ImpersonationFilter impersonationFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // ImpersonationFilter is a plain servlet filter that runs after Spring Security, as in the
        // application. Without an X-Impersonate-User header it changes nothing.
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .addFilters(impersonationFilter).build();
        when(autoSpottingService.getAdminDashboardMetrics()).thenReturn(Map.of(
                "totalSavings", 0.0, "legacyUnverifiedTotal", 12.5, "activeSpotInstances", 3));
        when(autoSpottingService.getAdminRecentActivity(anyInt())).thenReturn(List.of());
        when(autoSpottingService.getAdminHistoricalSavings(anyInt())).thenReturn(List.of());
        when(autoSpottingService.getAdminAccountBreakdown()).thenReturn(List.of());
        when(autoSpottingService.getCustomerSpotSavings()).thenReturn(List.of());
    }

    @AfterEach
    void clearImpersonation() {
        ImpersonationContext.clear();
    }

    private static RequestPostProcessor as(String role) {
        return user("someone@example.com").authorities(new SimpleGrantedAuthority(role));
    }

    /** A logged-in principal as CustomUserDetailsService builds it. */
    private static ClientUserDetails sessionUser(long id, String username, String role) {
        return new ClientUserDetails(username, null, List.of(new SimpleGrantedAuthority(role)), 1L, id, "default");
    }

    private static User userRecord(long id, String username, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }

    /** Minted the way billops' ImpersonationTokenController.buildToken does it. */
    private static String loginAsToken(String role) {
        return Jwts.builder()
                .setSubject(String.valueOf(TARGET_USER_ID))
                .claim("role", role)
                .setIssuer("billops")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60L * 60 * 1000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)), SignatureAlgorithm.HS256)
                .compact();
    }

    /** What a Login-As tab sends: the token, and the X-Impersonate-User header main.tsx adds. */
    private static RequestPostProcessor loginAs(String role) {
        String token = loginAsToken(role);
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            request.addHeader("X-Impersonate-User", String.valueOf(TARGET_USER_ID));
            return request;
        };
    }

    /** A session holding {@code principal}'s login, where ImpersonationFilter looks for it. */
    private static MockHttpSession sessionOf(ClientUserDetails principal) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return session;
    }

    @ParameterizedTest
    @ValueSource(strings = { "ROLE_SUPER_ADMIN", "ROLE_XAMOPS_ADMIN", "ROLE_BILLOPS_ADMIN" })
    void adminRoles_readEveryEndpoint(String role) throws Exception {
        for (String read : READS) {
            mvc.perform(get(BASE + read).with(as(role))).andExpect(status().isOk());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "ROLE_SUPER_ADMIN", "ROLE_XAMOPS_ADMIN", "ROLE_BILLOPS_ADMIN" })
    void normalAdminSession_withItsOwnUserRecord_readsEveryEndpoint(String role) throws Exception {
        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(
                userRecord(ADMIN_USER_ID, "ops-admin", "ops-admin@example.com", role)));
        for (String read : READS) {
            mvc.perform(get(BASE + read).with(user(sessionUser(ADMIN_USER_ID, "ops-admin", role))))
                    .andExpect(status().isOk());
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

    @ParameterizedTest
    @ValueSource(strings = { "ROLE_BILLOPS_ADMIN", "ROLE_XAMOPS_ADMIN" })
    void loginAs_withABillOpsOrXamOpsAdminRole_isForbidden(String role) throws Exception {
        clearInvocations(autoSpottingService);
        for (String read : READS) {
            mvc.perform(get(BASE + read).with(loginAs(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(autoSpottingService);
    }

    @Test
    void loginAs_withSuperAdminRole_readsEveryEndpoint() throws Exception {
        // billops never mints this role (it downgrades ROLE_SUPER_ADMIN), but the rule allows it.
        for (String read : READS) {
            mvc.perform(get(BASE + read).with(loginAs("ROLE_SUPER_ADMIN"))).andExpect(status().isOk());
        }
    }

    @ParameterizedTest
    @CsvSource({ "ROLE_BILLOPS_ADMIN, 403", "ROLE_XAMOPS_ADMIN, 403", "ROLE_SUPER_ADMIN, 200" })
    void headerLoginAs_needsSuperAdminToo(String targetRole, int expectedStatus) throws Exception {
        // A BillOps admin's own session, switched to another user with X-Impersonate-User.
        when(userRepository.findById(TARGET_USER_ID)).thenReturn(Optional.of(
                userRecord(TARGET_USER_ID, "target-user", "target@example.com", targetRole)));
        MockHttpSession session = sessionOf(sessionUser(ADMIN_USER_ID, "ops-admin", "ROLE_BILLOPS_ADMIN"));
        clearInvocations(autoSpottingService);
        for (String read : READS) {
            mvc.perform(get(BASE + read).session(session).header("X-Impersonate-User", TARGET_USER_ID))
                    .andExpect(status().is(expectedStatus));
        }
        if (expectedStatus == 403) {
            verifyNoInteractions(autoSpottingService);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "demo.billops@xammer.in, Xammer BillOps, ROLE_BILLOPS_ADMIN",
            "demo.xamops@xammer.in,  Xammer Admin,   ROLE_XAMOPS_ADMIN" })
    void demoAccounts_areForbidden_whateverTheirRole(String email, String username, String role) throws Exception {
        // Usernames as DataInitializer seeds them; demo.billops is seeded as ROLE_BILLOPS_ADMIN.
        when(userRepository.findById(DEMO_USER_ID)).thenReturn(Optional.of(
                userRecord(DEMO_USER_ID, username, email, role)));
        clearInvocations(autoSpottingService);
        for (String read : READS) {
            mvc.perform(get(BASE + read).with(user(sessionUser(DEMO_USER_ID, username, role))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(autoSpottingService);
    }

    @Test
    void demoClientUser_isForbidden_evenWithANonDemoEmailAndAdminRole() throws Exception {
        // A host-provisioned user: ordinary email, admin role, but attached to the is_demo client.
        User demoClientUser = userRecord(DEMO_USER_ID, "someone@gmail.com", "someone@gmail.com", "ROLE_XAMOPS_ADMIN");
        com.xammer.cloud.domain.Client demoClient = new com.xammer.cloud.domain.Client("Demo Workspace");
        demoClient.setId(900L);
        demoClient.setDemo(true);
        demoClientUser.setClient(demoClient);
        when(userRepository.findById(DEMO_USER_ID)).thenReturn(Optional.of(demoClientUser));
        // DemoClientMembership now loads via findByIdWithClient (JOIN FETCH client) to avoid lazy
        // init in the pre-transaction guard; stub it so the is_demo client is seen.
        when(userRepository.findByIdWithClient(DEMO_USER_ID)).thenReturn(Optional.of(demoClientUser));
        clearInvocations(autoSpottingService);
        for (String read : READS) {
            mvc.perform(get(BASE + read)
                    .with(user(sessionUser(DEMO_USER_ID, "someone@gmail.com", "ROLE_XAMOPS_ADMIN"))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(autoSpottingService);
    }

    @Test
    void demoAccountList_matchesTheOtpExemptLogins() throws Exception {
        Field otpExempt = LoginOtpController.class.getDeclaredField("DEMO_EMAILS");
        otpExempt.setAccessible(true);
        // Set equality: same emails, whatever the Set implementation.
        assertThat(otpExempt.get(null)).isEqualTo(AutoSpottingAdminAccess.DEMO_EMAILS);
    }

    @Test
    void oldAdminPath_answersNothing() throws Exception {
        mvc.perform(get("/api/admin/autospotting/total-savings").with(as("ROLE_SUPER_ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/autospotting/customer-savings").with(as("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isNotFound());
    }
}
