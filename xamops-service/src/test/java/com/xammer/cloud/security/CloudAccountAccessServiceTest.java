package com.xammer.cloud.security;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorization rules for CloudShell and any other caller that resolves a CloudAccount
 * from a caller-supplied identifier.
 *
 * The cross-tenant cases here are the regression guard for the unauthenticated/unowned
 * CloudShell access path, where naming another customer's account ID was enough to be
 * handed an interactive shell with that customer's cloud credentials.
 */
class CloudAccountAccessServiceTest {

    private static final Long CALLER_CLIENT_ID = 10L;
    private static final Long OTHER_CLIENT_ID = 99L;
    private static final Long CALLER_USER_ID = 500L;
    private static final Long ACCOUNT_ID = 42L;

    private UserRepository userRepository;
    private UserCloudAccountAccessRepository accessRepository;
    private CloudAccountAccessService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        accessRepository = mock(UserCloudAccountAccessRepository.class);
        service = new CloudAccountAccessService(userRepository, accessRepository);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ClientUserDetails caller(String... roles) {
        return new ClientUserDetails(
                "caller@example.com",
                "pw",
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList(),
                CALLER_CLIENT_ID,
                CALLER_USER_ID,
                "tenant-a");
    }

    private CloudAccount account(Long id, Long clientId) {
        CloudAccount account = new CloudAccount();
        account.setId(id);
        if (clientId != null) {
            Client client = new Client();
            client.setId(clientId);
            account.setClient(client);
        }
        return account;
    }

    /** A primary/root user — no createdBy, so they implicitly own their client's accounts. */
    private void givenRootUser() {
        User user = new User();
        user.setCreatedBy(null);
        when(userRepository.findById(CALLER_USER_ID)).thenReturn(Optional.of(user));
    }

    /** A sub-user created by someone else — access must come from explicit grants only. */
    private void givenSubUser() {
        User user = new User();
        user.setCreatedBy(1L);
        when(userRepository.findById(CALLER_USER_ID)).thenReturn(Optional.of(user));
    }

    private void givenNoExplicitGrants() {
        when(accessRepository.findCloudAccountIdsByUserId(CALLER_USER_ID)).thenReturn(List.of());
    }

    // ── platform admins ───────────────────────────────────────────────────────

    @Test
    void superAdmin_reachesAnyAccount_withoutRepositoryLookups() {
        assertThat(service.canAccess(caller("ROLE_SUPER_ADMIN"), account(ACCOUNT_ID, OTHER_CLIENT_ID)))
                .isTrue();
        verify(accessRepository, never()).findCloudAccountIdsByUserId(anyLong());
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    void xamopsAdmin_reachesAnyAccount() {
        assertThat(service.canAccess(caller("ROLE_XAMOPS_ADMIN"), account(ACCOUNT_ID, OTHER_CLIENT_ID)))
                .isTrue();
    }

    // ── root users are scoped to their own client ──────────────────────────────

    @Test
    void rootUser_reachesOwnClientsAccount() {
        givenNoExplicitGrants();
        givenRootUser();

        assertThat(service.canAccess(caller("ROLE_USER"), account(ACCOUNT_ID, CALLER_CLIENT_ID)))
                .isTrue();
    }

    @Test
    void rootUser_cannotReachAnotherClientsAccount() {
        givenNoExplicitGrants();
        givenRootUser();

        assertThat(service.canAccess(caller("ROLE_USER"), account(ACCOUNT_ID, OTHER_CLIENT_ID)))
                .isFalse();
    }

    // ── explicit grant lists are exhaustive, not additive ─────────────────────

    @Test
    void grantedAccount_isReachable() {
        when(accessRepository.findCloudAccountIdsByUserId(CALLER_USER_ID))
                .thenReturn(List.of(ACCOUNT_ID));

        assertThat(service.canAccess(caller("ROLE_USER"), account(ACCOUNT_ID, CALLER_CLIENT_ID)))
                .isTrue();
    }

    @Test
    void ungrantedAccount_isDenied_evenWithinOwnClient() {
        when(accessRepository.findCloudAccountIdsByUserId(CALLER_USER_ID))
                .thenReturn(List.of(7L, 8L));

        assertThat(service.canAccess(caller("ROLE_USER"), account(ACCOUNT_ID, CALLER_CLIENT_ID)))
                .isFalse();
    }

    // ── sub-users without grants hold nothing ────────────────────────────────

    @Test
    void subUserWithoutGrants_isDenied() {
        givenNoExplicitGrants();
        givenSubUser();

        assertThat(service.canAccess(caller("ROLE_USER"), account(ACCOUNT_ID, CALLER_CLIENT_ID)))
                .isFalse();
    }

    // ── fail closed on missing data ──────────────────────────────────────────

    @Test
    void nullUserDetails_isDenied() {
        assertThat(service.canAccess(null, account(ACCOUNT_ID, CALLER_CLIENT_ID))).isFalse();
    }

    @Test
    void nullAccount_isDenied() {
        assertThat(service.canAccess(caller("ROLE_USER"), null)).isFalse();
    }

    @Test
    void accountWithoutId_isDenied() {
        assertThat(service.canAccess(caller("ROLE_USER"), account(null, CALLER_CLIENT_ID))).isFalse();
    }

    @Test
    void accountWithoutClient_isDenied() {
        givenNoExplicitGrants();
        givenRootUser();

        assertThat(service.canAccess(caller("ROLE_USER"), account(ACCOUNT_ID, null))).isFalse();
    }

    @Test
    void unknownUser_isDenied() {
        givenNoExplicitGrants();
        when(userRepository.findById(CALLER_USER_ID)).thenReturn(Optional.empty());

        assertThat(service.canAccess(caller("ROLE_USER"), account(ACCOUNT_ID, CALLER_CLIENT_ID)))
                .isFalse();
    }
}
