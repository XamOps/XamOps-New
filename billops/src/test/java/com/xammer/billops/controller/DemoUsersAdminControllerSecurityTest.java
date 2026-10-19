package com.xammer.billops.controller;

import com.xammer.billops.config.SecurityConfig;
import com.xammer.billops.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.billops.dto.DemoUserDto;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.UserActivityEventRepository;
import com.xammer.billops.security.CustomAuthenticationSuccessHandler;
import com.xammer.billops.security.CustomUserDetailsService;
import com.xammer.billops.security.TrialEnforcementFilter;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.security.ClientUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-27 guard for {@code GET /api/billops/admin/demo-users}: the endpoint requires
 * {@code ROLE_BILLOPS_ADMIN} or {@code ROLE_BILLOPS_ADMIN_SUB}; anonymous callers get 401 and
 * signed-in under-privileged callers get 403, neither reaching the controller. An admin reaches the
 * controller and gets the {@link DemoUserDto} list (AC-28 shape), including the null/0 contract for a
 * demo user with no LOGIN row.
 *
 * <p>Runs the real {@link SecurityConfig} chains through MockMvc with only collaborators mocked —
 * same approach as {@code AdminCloudFrontControllerSecurityTest}, so the {@code @PreAuthorize} guard
 * is exercised exactly as in production rather than in a hand-built slice.
 */
@SpringJUnitWebConfig(DemoUsersAdminControllerSecurityTest.TestConfig.class)
@TestPropertySource(properties = "app.impersonation.secret-key=" + DemoUsersAdminControllerSecurityTest.SECRET)
class DemoUsersAdminControllerSecurityTest {

    /** Base64 of "test-only-impersonation-signing-key-not-a-secret" (48 bytes; HS256 needs 32+). */
    static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";

    private static final String PATH = "/api/billops/admin/demo-users";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, TrialEnforcementFilter.class,
            DemoUsersAdminController.class})
    static class TestConfig {
    }

    @MockitoBean
    private CustomAuthenticationSuccessHandler authenticationSuccessHandler;
    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private StringRedisTemplate redisTemplate;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private UserActivityEventRepository eventRepository;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** A signed-in browser session with this role: no Authorization header, so the @Order(2) chain runs. */
    private static RequestPostProcessor sessionUser(String role) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        ClientUserDetails user = new ClientUserDetails("user", null, authorities, null, 7L);
        return authentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(appUserRepository, eventRepository);
    }

    @ParameterizedTest(name = "session {0} -> 403")
    @ValueSource(strings = {"ROLE_XAMOPS", "ROLE_BILLOPS", "ROLE_USER"})
    void signedInNonAdminIsForbidden(String role) throws Exception {
        mvc.perform(get(PATH).with(sessionUser(role))).andExpect(status().isForbidden());
        verifyNoInteractions(appUserRepository, eventRepository);
    }

    @ParameterizedTest(name = "session {0} reaches the controller")
    @ValueSource(strings = {"ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB"})
    void adminGetsTheDemoUserList(String role) throws Exception {
        AppUser withLogin = appUser(1L, "alice@example.com");
        AppUser noLogin = appUser(2L, "bob@example.com");
        when(appUserRepository.findByClient_Demo(true)).thenReturn(List.of(withLogin, noLogin));
        when(eventRepository.aggregateLoginsByUserIds(List.of(1L, 2L)))
                .thenReturn(List.of(new FakeAgg(1L,
                        LocalDateTime.of(2026, 1, 1, 9, 0),
                        LocalDateTime.of(2026, 1, 3, 9, 0),
                        "GOOGLE", 3L)));
        com.xammer.cloud.domain.UserActivityEvent lastLogin = new com.xammer.cloud.domain.UserActivityEvent();
        lastLogin.setIpAddress("203.0.113.9");
        when(eventRepository.findFirstByUserIdAndActionOrderByCreatedAtDesc(1L, "LOGIN"))
                .thenReturn(java.util.Optional.of(lastLogin));

        mvc.perform(get(PATH).with(sessionUser(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // user with LOGIN rows
                .andExpect(jsonPath("$[0].email").value("alice@example.com"))
                .andExpect(jsonPath("$[0].displayName").value("alice@example.com"))
                .andExpect(jsonPath("$[0].authProvider").value("GOOGLE"))
                .andExpect(jsonPath("$[0].loginCount").value(3))
                .andExpect(jsonPath("$[0].lastIp").value("203.0.113.9"))
                // user with no LOGIN rows -> null/0 contract
                .andExpect(jsonPath("$[1].email").value("bob@example.com"))
                .andExpect(jsonPath("$[1].displayName").value("bob@example.com"))
                .andExpect(jsonPath("$[1].authProvider").doesNotExist())
                .andExpect(jsonPath("$[1].firstSeen").doesNotExist())
                .andExpect(jsonPath("$[1].lastLogin").doesNotExist())
                .andExpect(jsonPath("$[1].loginCount").value(0))
                .andExpect(jsonPath("$[1].lastIp").doesNotExist());
    }

    private static AppUser appUser(Long id, String email) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setEmail(email);
        u.setUsername(email); // auto-provision sets username = email
        return u;
    }

    /** Hand-rolled projection stand-in for the mocked aggregate query. */
    private record FakeAgg(Long userId, LocalDateTime firstSeen, LocalDateTime lastLogin,
                           String authProvider, long loginCount)
            implements com.xammer.billops.dto.DemoUserLoginAgg {
        public Long getUserId() { return userId; }
        public LocalDateTime getFirstSeen() { return firstSeen; }
        public LocalDateTime getLastLogin() { return lastLogin; }
        public String getAuthProvider() { return authProvider; }
        public long getLoginCount() { return loginCount; }
    }
}
