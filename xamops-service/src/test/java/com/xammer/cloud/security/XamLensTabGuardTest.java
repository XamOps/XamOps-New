package com.xammer.cloud.security;

import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Design 5.10 step 2 with BUILD-BRIEF 13 resolution 1: the real guard over mocked rule sources. The tab
 * rules apply only to principals the frontend gates ({@code ROLE_XAMOPS}).
 */
class XamLensTabGuardTest {

    private static final long USER_ID = 500L;
    private static final String TAB = "secops-xamlens";
    private static final String MODULE = "secops";

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private XamLensTabGuard guard;

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());
        guard = new XamLensTabGuard(rules, tabs);
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
    @DisplayName("ROLE_BILLOPS_ADMIN is not gated")
    void billopsAdmin() {
        globalRules(List.of(TAB, MODULE), List.of());
        assertAllowed(user(USER_ID, "ROLE_BILLOPS_ADMIN"));
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
}
