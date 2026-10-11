package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.controller.LoginOtpController;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The five rules that decide whether a user may change automation.
 *
 * <p>This predicate is shared with the CloudK8s UI revamp branch's {@code K8sAutomationAccessService}
 * (design decision D2). The point of testing it this precisely is that the two implementations must
 * agree: a user allowed to change automation on one screen and refused on the other is a bug in
 * whichever one differs.
 */
class AutomationWritePermissionTest {

    private CloudAccountAccessService accessService;
    private UserRepository userRepository;
    private UserTabAccessRepository tabAccessRepository;
    private Long impersonatedUserId;

    @BeforeEach
    void setUp() {
        accessService = mock(CloudAccountAccessService.class);
        userRepository = mock(UserRepository.class);
        tabAccessRepository = mock(UserTabAccessRepository.class);
        impersonatedUserId = null;
        when(accessService.canAccess(any(), any())).thenReturn(true);
        when(tabAccessRepository.findTabNamesByUserId(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("an ordinary XamOps user with no tab restrictions may change automation")
    void anUnrestrictedXamopsUserIsPermitted() {
        givenUser("real.user@example.com", "ROLE_XAMOPS");

        assertThat(permission().decide(principal(), account()).permitted()).isTrue();
    }

    @Test
    @DisplayName("a bare 'USER' role in the database is normalised, not refused")
    void theRoleIsNormalisedTheWayImpersonationFilterDoes() {
        // User's default is the bare "USER" and CustomUserDetailsService grants the raw DB value, so
        // without the same ROLE_ normalisation ImpersonationFilter performs, an ordinary user would
        // be refused.
        givenUser("real.user@example.com", "USER");

        assertThat(permission().decide(principal(), account()).permitted()).isTrue();
    }

    @Test
    @DisplayName("every permitted role is permitted, and only those five")
    void onlyXamopsProductRolesMayWrite() {
        assertThat(AutomationWritePermission.WRITE_ROLES).containsExactlyInAnyOrder(
                "ROLE_XAMOPS", "ROLE_XAMOPS_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_USER");

        for (String role : AutomationWritePermission.WRITE_ROLES) {
            givenUser("real.user@example.com", role);
            assertThat(permission().decide(principal(), account()).permitted())
                    .as("%s may change automation", role)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a BillOps-only principal is refused: changing a cluster is not a billing task")
    void billOpsOnlyPrincipalsAreRefused() {
        for (String role : List.of("ROLE_BILLOPS", "ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB",
                "ROLE_SOMETHING_NEW", "")) {
            givenUser("real.user@example.com", role);

            AutomationWritePermission.Decision decision = permission().decide(principal(), account());

            assertThat(decision.permitted()).as("%s may not change automation", role).isFalse();
            assertThat(decision.refusalCode()).isEqualTo("K8S_NO_USER_PERMISSION");
            assertThat(decision.detail()).isEqualTo(AutomationWritePermission.Detail.ROLE);
            assertThat(decision.message()).isEqualTo("Your role can view automation but not change it.");
        }
    }

    @Test
    @DisplayName("a shared demo identity is refused as DEMO, decided by email and never by username")
    void theDemoIdentityIsRefusedByEmail() {
        // The demo principals' usernames are "Xammer Admin" and "Xammer BillOps", so a username
        // comparison would never match and the OTP-free shared login would pass every gate as an
        // ordinary user of the first client.
        for (String email : AutomationWritePermission.DEMO_EMAILS) {
            givenUser(email.toUpperCase(java.util.Locale.ROOT), "ROLE_XAMOPS");

            AutomationWritePermission.Decision decision = permission().decide(principal(), account());

            assertThat(decision.permitted()).isFalse();
            assertThat(decision.reason()).isEqualTo(ReasonCode.DEMO);
            assertThat(decision.refusalCode()).isEqualTo("K8S_DEMO");
            assertThat(decision.message()).contains("shared demo identity");
        }
    }

    @Test
    @DisplayName("the demo email set matches the one the login flow owns, so the two cannot drift")
    @SuppressWarnings("unchecked")
    void theDemoEmailSetMirrorsTheLoginFlow() throws Exception {
        // LoginOtpController owns the login flow and is out of scope here, so its set is read rather
        // than copied by hand. If it ever moves, this fails with a pointer, by design.
        Field field = LoginOtpController.class.getDeclaredField("DEMO_EMAILS");
        field.setAccessible(true);

        Set<String> login = (Set<String>) field.get(null);

        assertThat(AutomationWritePermission.DEMO_EMAILS).isEqualTo(login);
    }

    @Test
    @DisplayName("an impersonated session may not change automation, whoever the admin is")
    void anImpersonatedSessionIsRefused() {
        // Both impersonation paths replace the principal with the target user, so without this rule
        // a BillOps admin could change a customer's automation by signing in as that customer.
        givenUser("real.user@example.com", "ROLE_SUPER_ADMIN");
        impersonatedUserId = 99L;

        AutomationWritePermission.Decision decision = permission().decide(principal(), account());

        assertThat(decision.permitted()).isFalse();
        assertThat(decision.refusalCode()).isEqualTo("K8S_NO_USER_PERMISSION");
        assertThat(decision.detail()).isEqualTo(AutomationWritePermission.Detail.IMPERSONATION);
        assertThat(decision.message())
                .as("an owner demoing through Login As is refused here, so a demo needs a direct login")
                .contains("signed in as another user");
    }

    @Test
    @DisplayName("a restricted user needs both the CloudK8s tab and the automation grant")
    void aRestrictedUserNeedsBothGrants() {
        givenUser("real.user@example.com", "ROLE_XAMOPS");

        when(tabAccessRepository.findTabNamesByUserId(any()))
                .thenReturn(List.of(AutomationWritePermission.TAB_CLOUDK8S));
        AutomationWritePermission.Decision onlyTheTab = permission().decide(principal(), account());
        assertThat(onlyTheTab.permitted()).isFalse();
        assertThat(onlyTheTab.detail()).isEqualTo(AutomationWritePermission.Detail.TAB_GRANT);
        assertThat(onlyTheTab.message()).contains("Ask your XamOps administrator");

        when(tabAccessRepository.findTabNamesByUserId(any()))
                .thenReturn(List.of(AutomationWritePermission.TAB_CLOUDK8S_AUTOMATION));
        assertThat(permission().decide(principal(), account()).permitted())
                .as("the automation grant alone does not imply access to the section it sits in")
                .isFalse();

        when(tabAccessRepository.findTabNamesByUserId(any())).thenReturn(List.of(
                AutomationWritePermission.TAB_CLOUDK8S,
                AutomationWritePermission.TAB_CLOUDK8S_AUTOMATION));
        assertThat(permission().decide(principal(), account()).permitted()).isTrue();
    }

    @Test
    @DisplayName("no rows at all means unrestricted, which is the existing tab-access convention")
    void noTabRowsMeansUnrestricted() {
        givenUser("real.user@example.com", "ROLE_XAMOPS");
        when(tabAccessRepository.findTabNamesByUserId(any())).thenReturn(List.of());

        assertThat(permission().decide(principal(), account()).permitted()).isTrue();
    }

    @Test
    @DisplayName("an unentitled account is K8S_NO_ACCOUNT_ACCESS, and the user row is never read")
    void anUnentitledAccountIsRefusedFirst() {
        when(accessService.canAccess(any(), any())).thenReturn(false);

        AutomationWritePermission.Decision decision = permission().decide(principal(), account());

        assertThat(decision.permitted()).isFalse();
        assertThat(decision.refusalCode()).isEqualTo("K8S_NO_ACCOUNT_ACCESS");
        assertThat(decision.message())
                .as("'unknown' and 'not yours' share a message, or the endpoint becomes an oracle")
                .isEqualTo("Account not found or access denied.");
        verifyNoInteractions(userRepository, tabAccessRepository);
    }

    @Test
    @DisplayName("a principal with no id, or no row, is refused rather than given the benefit of doubt")
    void anUnresolvableUserIsRefused() {
        AutomationWritePermission.Decision noId = permission().decide(
                new ClientUserDetails("user@example.com", "pw",
                        List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 7L, null, "tenant-a"),
                account());
        assertThat(noId.permitted()).isFalse();
        assertThat(noId.detail()).isEqualTo(AutomationWritePermission.Detail.NO_PRINCIPAL);

        when(userRepository.findById(10L)).thenReturn(Optional.empty());
        AutomationWritePermission.Decision noRow = permission().decide(principal(), account());
        assertThat(noRow.permitted()).isFalse();
        assertThat(noRow.refusalCode()).isEqualTo("K8S_NO_USER_PERMISSION");
    }

    @Test
    @DisplayName("the role, email and grants come from the database, not from the session")
    void everythingIsReadFromTheRow() {
        // The principal here claims a permitted authority and a real-looking name; the row says
        // BillOps. The row wins -- a session value is not evidence of a permission.
        givenUser("real.user@example.com", "ROLE_BILLOPS_ADMIN");

        assertThat(permission().decide(principal("ROLE_SUPER_ADMIN"), account()).permitted()).isFalse();
    }

    // ---------------------------------------------------------------- fixtures

    private AutomationWritePermission permission() {
        Supplier<Long> impersonation = () -> impersonatedUserId;
        return new AutomationWritePermission(accessService, userRepository, tabAccessRepository,
                impersonation);
    }

    private void givenUser(String email, String role) {
        User user = new User();
        user.setId(10L);
        user.setUsername("Xammer Admin");
        user.setEmail(email);
        user.setRole(role);
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
    }

    private static ClientUserDetails principal(String... authorities) {
        List<SimpleGrantedAuthority> granted = new java.util.ArrayList<>();
        for (String authority : authorities.length == 0 ? new String[]{"ROLE_XAMOPS"} : authorities) {
            granted.add(new SimpleGrantedAuthority(authority));
        }
        return new ClientUserDetails("Xammer Admin", "pw", granted, 7L, 10L, "tenant-a");
    }

    private static CloudAccount account() {
        Client client = new Client();
        client.setId(7L);
        CloudAccount account = new CloudAccount();
        account.setId(3L);
        account.setProvider("AWS");
        account.setAwsAccountId("102227938259");
        account.setAccessType("read-write");
        account.setClient(client);
        return account;
    }
}
