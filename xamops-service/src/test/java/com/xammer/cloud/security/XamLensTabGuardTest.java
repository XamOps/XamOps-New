package com.xammer.cloud.security;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.ImpersonationFilter;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserTabAccessRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Design 5.10 step 2 with BUILD-BRIEF 13 resolution 1: the real guard over mocked rule sources. The guard
 * is an allow-list: the tab rules apply to {@code ROLE_XAMOPS}, {@code ROLE_SUPER_ADMIN} passes, and every
 * other role is refused. Under impersonation the real filters hand the guard the target user.
 */
class XamLensTabGuardTest {

    private static final long USER_ID = 500L;
    private static final long ADMIN_ID = 1L;
    private static final String TAB = "secops-xamlens";
    private static final String MODULE = "secops";

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private UserRepository userRepository;
    private XamLensTabGuard guard;

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        userRepository = mock(UserRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());
        guard = new XamLensTabGuard(rules, tabs);
    }

    @AfterEach
    void clearContexts() {
        ImpersonationContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static ClientUserDetails user(Long id, String... roles) {
        return new ClientUserDetails("user@example.com", "pw",
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList(), 10L, id, "tenant-a");
    }

    private void globalRules(List<String> hidden, List<String> granted) {
        when(rules.rulesFor(USER_ID)).thenReturn(new GlobalTabRuleReader.UserRules(hidden, granted));
    }

    private void tabList(String... keys) {
        when(tabs.findTabNamesByUserId(USER_ID)).thenReturn(List.of(keys));
    }

    private void assertAllowed(ClientUserDetails principal) {
        assertThatCode(() -> guard.require(principal)).doesNotThrowAnyException();
    }

    private void assertDenied(ClientUserDetails principal) {
        assertThatThrownBy(() -> guard.require(principal)).isInstanceOf(XamLensAccessDeniedException.class)
                .hasMessage("You do not have access to XamLens.");
    }

    @Test
    @DisplayName("a missing principal is denied")
    void nullPrincipal() {
        assertDenied(null);
    }

    @Test
    @DisplayName("a principal without an id is denied, whatever its role")
    void nullId() {
        assertDenied(user(null, "ROLE_XAMOPS"));
        assertDenied(user(null, "ROLE_SUPER_ADMIN"));
    }

    @Test
    @DisplayName("ROLE_XAMOPS with an empty tab list (unrestricted) is allowed")
    void xamopsUnrestricted() {
        assertAllowed(user(USER_ID, "ROLE_XAMOPS"));
    }

    @Test
    @DisplayName("ROLE_XAMOPS whose list holds secops-xamlens is allowed")
    void xamopsWithTheTab() {
        tabList("cloudops-cloudk8s", TAB);
        assertAllowed(user(USER_ID, "ROLE_XAMOPS"));
    }

    @Test
    @DisplayName("ROLE_XAMOPS whose list leaves secops-xamlens out is denied")
    void xamopsWithoutTheTab() {
        tabList("cloudops-cloudk8s", "secops-xamsecure");
        assertDenied(user(USER_ID, "ROLE_XAMOPS"));
    }

    @Test
    @DisplayName("ROLE_XAMOPS under a platform-wide HIDE on secops-xamlens is denied, even with the tab listed")
    void xamopsUnderTabHide() {
        globalRules(List.of(TAB), List.of());
        tabList(TAB);
        assertDenied(user(USER_ID, "ROLE_XAMOPS"));
    }

    @Test
    @DisplayName("ROLE_XAMOPS under a platform-wide HIDE on the secops module is denied")
    void xamopsUnderModuleHide() {
        globalRules(List.of(MODULE), List.of());
        assertDenied(user(USER_ID, "ROLE_XAMOPS"));
    }

    @Test
    @DisplayName("a GRANT on the secops module allows a list that lacks the tab")
    void moduleGrant() {
        globalRules(List.of(), List.of(MODULE));
        tabList("cloudops-cloudk8s");
        assertAllowed(user(USER_ID, "ROLE_XAMOPS"));
    }

    @Test
    @DisplayName("a GRANT on the tab beats a HIDE on its module")
    void tabGrantBeatsModuleHide() {
        globalRules(List.of(MODULE), List.of(TAB));
        tabList("cloudops-cloudk8s");
        assertAllowed(user(USER_ID, "ROLE_XAMOPS"));
    }

    @Test
    @DisplayName("HIDE beats GRANT on the same key")
    void hideBeatsGrant() {
        globalRules(List.of(TAB), List.of(TAB));
        assertDenied(user(USER_ID, "ROLE_XAMOPS"));
    }

    @Test
    @DisplayName("an authority stored as XAMOPS is gated like ROLE_XAMOPS")
    void unprefixedAuthorityIsGated() {
        globalRules(List.of(TAB), List.of());
        assertDenied(user(USER_ID, "XAMOPS"));
    }

    @Test
    @DisplayName("ROLE_SUPER_ADMIN under a HIDE on secops-xamlens is allowed with no repository read")
    void superAdminIsNotGated() {
        globalRules(List.of(TAB), List.of());
        tabList("cloudops-cloudk8s");
        assertAllowed(user(USER_ID, "ROLE_SUPER_ADMIN"));
        verifyNoInteractions(rules, tabs);
    }

    @Test
    @DisplayName("a ROLE_SUPER_ADMIN team member whose list leaves XamLens out is allowed")
    void superAdminTeamMember() {
        tabList("cloudops-cloudk8s", "secops-xamsecure");
        assertAllowed(user(USER_ID, "ROLE_SUPER_ADMIN"));
        verifyNoInteractions(rules, tabs);
    }

    @Test
    @DisplayName("ROLE_BILLOPS and ROLE_BILLOPS_ADMIN, which never see XamLens, are refused with no repository read")
    void billopsRolesAreRefused() {
        assertDenied(user(USER_ID, "ROLE_BILLOPS"));
        assertDenied(user(USER_ID, "ROLE_BILLOPS_ADMIN"));
        verifyNoInteractions(rules, tabs);
    }

    @Test
    @DisplayName("an unknown role, or no role at all, is refused with no repository read")
    void unknownRoleIsRefused() {
        assertDenied(user(USER_ID, "ROLE_AUDITOR"));
        assertDenied(user(USER_ID));
        verifyNoInteractions(rules, tabs);
    }

    @Test
    @DisplayName("a failure reading the platform-wide rules propagates: the guard fails closed")
    void ruleReadFailurePropagates() {
        when(rules.rulesFor(USER_ID)).thenThrow(new DataAccessResourceFailureException("rules unavailable"));
        assertThatThrownBy(() -> guard.require(user(USER_ID, "ROLE_XAMOPS")))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("a failure reading the user's tab list propagates: the guard fails closed")
    void tabListReadFailurePropagates() {
        when(tabs.findTabNamesByUserId(USER_ID)).thenThrow(new DataAccessResourceFailureException("tabs unavailable"));
        assertThatThrownBy(() -> guard.require(user(USER_ID, "ROLE_XAMOPS")))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    // ── Impersonation: the principal is the target, so the target's role and tab rules apply ──────

    @Test
    @DisplayName("super-admin header impersonation of a restricted ROLE_XAMOPS user is refused on the target's tab list")
    void headerImpersonationOfRestrictedXamopsUser() throws Exception {
        targetRow("ROLE_XAMOPS");
        tabList("cloudops-cloudk8s");
        assertThat(requireUnderHeaderImpersonation()).isInstanceOf(XamLensAccessDeniedException.class);
        verify(tabs).findTabNamesByUserId(USER_ID);
    }

    @Test
    @DisplayName("super-admin header impersonation of an entitled ROLE_XAMOPS user is allowed")
    void headerImpersonationOfEntitledXamopsUser() throws Exception {
        targetRow("ROLE_XAMOPS");
        tabList("cloudops-cloudk8s", TAB);
        assertThat(requireUnderHeaderImpersonation()).as("allowed").isNull();
    }

    @Test
    @DisplayName("super-admin header impersonation of a ROLE_BILLOPS user is refused on the target's role")
    void headerImpersonationOfBillopsUser() throws Exception {
        targetRow("ROLE_BILLOPS");
        assertThat(requireUnderHeaderImpersonation()).isInstanceOf(XamLensAccessDeniedException.class);
        verifyNoInteractions(rules, tabs);
    }

    @Test
    @DisplayName("a BillOps Login-As JWT for a restricted ROLE_XAMOPS user is refused on the target's tab list")
    void loginAsOfRestrictedXamopsUser() throws Exception {
        targetRow("ROLE_XAMOPS");
        tabList("cloudops-cloudk8s");
        assertThat(requireUnderLoginAs("XAMOPS")).isInstanceOf(XamLensAccessDeniedException.class);
        verify(tabs).findTabNamesByUserId(USER_ID);
    }

    /** The impersonation target's users row, read by both filters. */
    private void targetRow(String role) {
        User target = new User();
        target.setId(USER_ID);
        target.setUsername("target-" + USER_ID);
        target.setRole(role);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(target));
    }

    /**
     * A super admin's request after a "view as user" switch: the session holds the admin's context, which
     * Spring Security has already loaded, and the request carries {@code X-Impersonate-User}.
     */
    private Throwable requireUnderHeaderImpersonation() throws Exception {
        ImpersonationFilter filter = new ImpersonationFilter();
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        ClientUserDetails admin = user(ADMIN_ID, "ROLE_SUPER_ADMIN");
        SecurityContext adminContext = SecurityContextHolder.createEmptyContext();
        adminContext.setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", adminContext);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dspm/status");
        request.setSession(session);
        request.addHeader(ImpersonationFilter.IMPERSONATION_HEADER, String.valueOf(USER_ID));
        SecurityContextHolder.setContext(adminContext);
        return requireInChain(filter, request);
    }

    /** A BillOps Login-As request: a bearer JWT whose subject is the target, signed with a per-test key. */
    private Throwable requireUnderLoginAs(String role) throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ImpersonationJwtFilter filter = new ImpersonationJwtFilter();
        ReflectionTestUtils.setField(filter, "secretKey", Base64.getEncoder().encodeToString(key));
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        ReflectionTestUtils.invokeMethod(filter, "initSigningKey");
        String token = Jwts.builder()
                .setSubject(String.valueOf(USER_ID))
                .setIssuer("billops")
                .claim("role", role)
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(key), SignatureAlgorithm.HS256)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dspm/status");
        request.addHeader("Authorization", "Bearer " + token);
        return requireInChain(filter, request);
    }

    /** Runs the filter and calls the guard with the principal the chain sees; null means allowed. */
    private Throwable requireInChain(Filter filter, MockHttpServletRequest request) throws Exception {
        AtomicReference<ClientUserDetails> seen = new AtomicReference<>();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            ClientUserDetails principal =
                    (ClientUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            seen.set(principal);
            outcome.set(catchThrowable(() -> guard.require(principal)));
        });
        assertThat(seen.get()).as("the filter reached the chain").isNotNull();
        assertThat(seen.get().getId()).as("the guard saw the target, not the admin").isEqualTo(USER_ID);
        return outcome.get();
    }
}
