package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.ImpersonationFilter;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.matters.DspmStatus;
import com.xammer.cloud.dto.matters.pages.AssistList;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DashboardService;
import com.xammer.cloud.service.matters.DspmPagesService;
import com.xammer.cloud.service.matters.MattersDspmService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who reaches XamLens over HTTP, through the real impersonation filters, {@link XamLensTabGuard} and the
 * exception handlers: a super admin's "view as user" gets 200 whatever the target's role or tab rules, and
 * reads the target's client; a BillOps admin's "view as user", BillOps Login-As (whichever actor claims its
 * token carries) and direct calls keep their gates.
 */
class DspmImpersonationAccessTest {

    private static final String STATUS = "/api/dspm/status";
    private static final String TAB = "secops-xamlens";
    private static final long ADMIN_ID = 1L;
    private static final long ADMIN_CLIENT_ID = 1L;
    private static final long TARGET_ID = 500L;
    private static final long TARGET_CLIENT_ID = 10L;
    /** The actor claims billops mints now, here for a BillOps admin. */
    private static final Map<String, Object> NAMED_ADMIN = Map.of(
            "actorUserId", ADMIN_ID, "actorUsername", "user" + ADMIN_ID + "@example.com", "actorRole", "ROLE_BILLOPS_ADMIN");

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private UserRepository userRepository;
    private MattersDspmService dspmService;
    private byte[] jwtKey;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());
        userRepository = mock(UserRepository.class);
        dspmService = mock(MattersDspmService.class);
        when(dspmService.getStatus(anyLong())).thenReturn(DspmStatus.ready("synthetic-project", null));

        ImpersonationFilter headerFilter = new ImpersonationFilter();
        ReflectionTestUtils.setField(headerFilter, "userRepository", userRepository);
        jwtKey = new byte[32];
        new SecureRandom().nextBytes(jwtKey);
        ImpersonationJwtFilter jwtFilter = new ImpersonationJwtFilter();
        ReflectionTestUtils.setField(jwtFilter, "secretKey", Base64.getEncoder().encodeToString(jwtKey));
        ReflectionTestUtils.setField(jwtFilter, "userRepository", userRepository);
        ReflectionTestUtils.invokeMethod(jwtFilter, "initSigningKey");

        mvc = MockMvcBuilders.standaloneSetup(new DspmController(dspmService, new XamLensTabGuard(rules, tabs)))
                .setControllerAdvice(new GlobalExceptionHandler(), new MattersExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                // The app's order: the JWT filter runs inside Spring Security, the header filter after it.
                .addFilters(jwtFilter, headerFilter)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ImpersonationContext.clear();
    }

    private static ClientUserDetails principal(long userId, long clientId, String role) {
        return new ClientUserDetails("user" + userId + "@example.com", "pw",
                List.of(new SimpleGrantedAuthority(role)), clientId, userId, "tenant-a");
    }

    /** The target's users row, read by both filters; its client is not the admin's. */
    private void target(String role) {
        Client client = new Client();
        client.setId(TARGET_CLIENT_ID);
        User user = new User();
        user.setId(TARGET_ID);
        user.setUsername("target-" + TARGET_ID);
        user.setRole(role);
        user.setClient(client);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(user));
    }

    /** A platform-wide HIDE on the tab, and a tab list without it. */
    private void restrictTarget() {
        when(rules.rulesFor(TARGET_ID)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));
        when(tabs.findTabNamesByUserId(TARGET_ID)).thenReturn(List.of("cloudops-cloudk8s"));
    }

    private static SecurityContext contextOf(ClientUserDetails principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return context;
    }

    private static MockHttpSession sessionOf(ClientUserDetails principal) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", contextOf(principal));
        return session;
    }

    /** A "view as user" request from {@code admin}'s session, whose context Spring Security has loaded. */
    private static MockHttpServletRequestBuilder viewAsTarget(ClientUserDetails admin) {
        MockHttpSession session = sessionOf(admin);
        SecurityContextHolder.setContext((SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT"));
        return get(STATUS).session(session).header(ImpersonationFilter.IMPERSONATION_HEADER, String.valueOf(TARGET_ID));
    }

    /** A BillOps Login-As request as the browser sends it: the bearer token and X-Impersonate-User. */
    private MockHttpServletRequestBuilder loginAsTarget(String role) {
        return loginAsTarget(role, Map.of());
    }

    /** The same, with a token that also carries {@code actorClaims}, which name the admin. */
    private MockHttpServletRequestBuilder loginAsTarget(String role, Map<String, Object> actorClaims) {
        String token = Jwts.builder()
                .setSubject(String.valueOf(TARGET_ID))
                .setIssuer("billops")
                .claim("role", role)
                .addClaims(actorClaims)
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(jwtKey), SignatureAlgorithm.HS256)
                .compact();
        return get(STATUS).header("Authorization", "Bearer " + token)
                .header(ImpersonationFilter.IMPERSONATION_HEADER, String.valueOf(TARGET_ID));
    }

    @Test
    @DisplayName("a super admin viewing as a restricted ROLE_XAMOPS user gets 200 with the target's client data")
    void superAdminViewingAsRestrictedXamopsUser() throws Exception {
        target("ROLE_XAMOPS");
        restrictTarget();
        mvc.perform(viewAsTarget(principal(ADMIN_ID, ADMIN_CLIENT_ID, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        verify(dspmService).getStatus(TARGET_CLIENT_ID);
    }

    @Test
    @DisplayName("a super admin viewing as a ROLE_BILLOPS user gets 200 with the target's client data")
    void superAdminViewingAsBillopsUser() throws Exception {
        target("ROLE_BILLOPS");
        mvc.perform(viewAsTarget(principal(ADMIN_ID, ADMIN_CLIENT_ID, "ROLE_SUPER_ADMIN"))).andExpect(status().isOk());
        verify(dspmService).getStatus(TARGET_CLIENT_ID);
    }

    @Test
    @DisplayName("a super admin viewing as an entitled ROLE_XAMOPS user gets 200 with the target's client data")
    void superAdminViewingAsEntitledXamopsUser() throws Exception {
        target("ROLE_XAMOPS");
        when(tabs.findTabNamesByUserId(TARGET_ID)).thenReturn(List.of(TAB));
        mvc.perform(viewAsTarget(principal(ADMIN_ID, ADMIN_CLIENT_ID, "ROLE_SUPER_ADMIN"))).andExpect(status().isOk());
        verify(dspmService).getStatus(TARGET_CLIENT_ID);
    }

    @Test
    @DisplayName("a BillOps admin viewing as a restricted ROLE_XAMOPS user gets 403")
    void billopsAdminViewingAsRestrictedXamopsUser() throws Exception {
        target("ROLE_XAMOPS");
        restrictTarget();
        mvc.perform(viewAsTarget(principal(ADMIN_ID, ADMIN_CLIENT_ID, "ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"));
        verifyNoInteractions(dspmService);
    }

    @Test
    @DisplayName("BillOps Login-As for a restricted ROLE_XAMOPS user gets 403, also with a super admin's session cookie")
    void loginAsOfRestrictedXamopsUser() throws Exception {
        target("ROLE_XAMOPS");
        restrictTarget();
        mvc.perform(loginAsTarget("XAMOPS"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"));
        // The Login-As chain is stateless, so the cookie's context is never loaded onto the thread.
        mvc.perform(loginAsTarget("XAMOPS").session(sessionOf(principal(ADMIN_ID, ADMIN_CLIENT_ID, "ROLE_SUPER_ADMIN"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"));
        verifyNoInteractions(dspmService);
    }

    @Test
    @DisplayName("a BillOps admin viewing as an entitled ROLE_XAMOPS user gets 200 with the target's client data")
    void billopsAdminViewingAsEntitledXamopsUser() throws Exception {
        target("ROLE_XAMOPS");
        when(tabs.findTabNamesByUserId(TARGET_ID)).thenReturn(List.of(TAB));
        mvc.perform(viewAsTarget(principal(ADMIN_ID, ADMIN_CLIENT_ID, "ROLE_BILLOPS_ADMIN"))).andExpect(status().isOk());
        verify(dspmService).getStatus(TARGET_CLIENT_ID);
    }

    @Test
    @DisplayName("BillOps Login-As with a token that names the admin for a restricted ROLE_XAMOPS user still gets 403")
    void loginAsNamingTheAdminOfRestrictedXamopsUser() throws Exception {
        target("ROLE_XAMOPS");
        restrictTarget();
        mvc.perform(loginAsTarget("XAMOPS", NAMED_ADMIN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"));
        verifyNoInteractions(dspmService);
    }

    @Test
    @DisplayName("BillOps Login-As for an entitled ROLE_XAMOPS user gets 200 with the target's client data, with old tokens and new")
    void loginAsOfEntitledXamopsUser() throws Exception {
        target("ROLE_XAMOPS");
        when(tabs.findTabNamesByUserId(TARGET_ID)).thenReturn(List.of(TAB));
        mvc.perform(loginAsTarget("XAMOPS")).andExpect(status().isOk());
        mvc.perform(loginAsTarget("XAMOPS", Map.of("actorUserId", ADMIN_ID))).andExpect(status().isOk());
        mvc.perform(loginAsTarget("XAMOPS", NAMED_ADMIN)).andExpect(status().isOk());
        verify(dspmService, times(3)).getStatus(TARGET_CLIENT_ID);
    }

    @Test
    @DisplayName("a direct ROLE_BILLOPS call gets 403")
    void directBillopsCall() throws Exception {
        SecurityContextHolder.setContext(contextOf(principal(TARGET_ID, TARGET_CLIENT_ID, "ROLE_BILLOPS")));
        mvc.perform(get(STATUS))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"));
        verifyNoInteractions(dspmService);
    }

    @Test
    @DisplayName("a direct call with an unknown role gets 403")
    void directUnknownRoleCall() throws Exception {
        SecurityContextHolder.setContext(contextOf(principal(TARGET_ID, TARGET_CLIENT_ID, "ROLE_AUDITOR")));
        mvc.perform(get(STATUS))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"));
        verifyNoInteractions(dspmService);
    }

    // ── build 6: the pages controller behind the same guard ─────────────────────────────────────

    @Test
    @DisplayName("build 6: a super admin viewing as a restricted ROLE_XAMOPS user reaches the Assists page with the target's client")
    void superAdminViewingAsRestrictedXamopsUserReachesThePagesController() throws Exception {
        target("ROLE_XAMOPS");
        restrictTarget();
        DspmPagesService pagesService = mock(DspmPagesService.class);
        when(pagesService.assists(anyLong())).thenReturn(new AssistList(List.of()));

        ImpersonationFilter headerFilter = new ImpersonationFilter();
        ReflectionTestUtils.setField(headerFilter, "userRepository", userRepository);
        ImpersonationJwtFilter jwtFilter = new ImpersonationJwtFilter();
        ReflectionTestUtils.setField(jwtFilter, "secretKey", Base64.getEncoder().encodeToString(jwtKey));
        ReflectionTestUtils.setField(jwtFilter, "userRepository", userRepository);
        ReflectionTestUtils.invokeMethod(jwtFilter, "initSigningKey");
        MockMvc pagesMvc = MockMvcBuilders.standaloneSetup(new DspmPagesController(
                        new XamLensTabGuard(rules, tabs), mock(DashboardService.class), pagesService))
                .setControllerAdvice(new GlobalExceptionHandler(), new MattersExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .addFilters(jwtFilter, headerFilter)
                .build();

        MockHttpSession session = sessionOf(principal(ADMIN_ID, ADMIN_CLIENT_ID, "ROLE_SUPER_ADMIN"));
        SecurityContextHolder.setContext((SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT"));
        pagesMvc.perform(get("/api/dspm/assists").session(session)
                        .header(ImpersonationFilter.IMPERSONATION_HEADER, String.valueOf(TARGET_ID)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assists").isArray());
        verify(pagesService).assists(TARGET_CLIENT_ID);
    }
}
