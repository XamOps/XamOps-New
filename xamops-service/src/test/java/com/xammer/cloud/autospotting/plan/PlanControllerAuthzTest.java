package com.xammer.cloud.autospotting.plan;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.UserCloudAccountAccess;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Decision;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The PO-14 authorization matrix for confirm, both modes (design-core.md §11, ux §9.2; plan-phase-11
 * task 11.4 verify, AC-SEC-03). DEFAULT is the code default (po-decisions.md PO-14); PO14_RECOMMENDED
 * is implemented and tested, UNLIFTED. Driven at the {@link AutoSpottingAuthorization} boundary the
 * controller delegates to, so it covers the rows without a servlet context.
 */
class PlanControllerAuthzTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long CLIENT_ID = 7L;

    private final UserRepository users = mock(UserRepository.class);
    private final UserCloudAccountAccessRepository access = mock(UserCloudAccountAccessRepository.class);

    @AfterEach
    void clearImpersonation() {
        ImpersonationContext.clear();
    }

    private AutoSpottingAuthorization authz(String mode) {
        return new AutoSpottingAuthorization(users, access, mode);
    }

    private CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(ACCOUNT_ID);
        Client client = new Client();
        client.setId(CLIENT_ID);
        a.setClient(client);
        return a;
    }

    private ClientUserDetails owner() {
        return new ClientUserDetails("owner", "x", List.of(new SimpleGrantedAuthority("ROLE_USER")),
                CLIENT_ID, 100L, "t");
    }

    private ClientUserDetails subUser() {
        return new ClientUserDetails("sub", "x", List.of(new SimpleGrantedAuthority("ROLE_USER")),
                CLIENT_ID, 200L, "t");
    }

    private ClientUserDetails admin() {
        return new ClientUserDetails("admin", "x", List.of(new SimpleGrantedAuthority("ROLE_XAMOPS_ADMIN")),
                99L, 300L, "t");
    }

    private void ownerRows() {
        when(access.findCloudAccountIdsByUserId(100L)).thenReturn(List.of());
        User u = new User();
        u.setId(100L);
        u.setCreatedBy(null);
        when(users.findById(100L)).thenReturn(Optional.of(u));
    }

    private void subUserRows(boolean write) {
        when(access.findCloudAccountIdsByUserId(200L)).thenReturn(List.of(ACCOUNT_ID));
        UserCloudAccountAccess row = new UserCloudAccountAccess();
        CloudAccount a = new CloudAccount();
        a.setId(ACCOUNT_ID);
        row.setCloudAccount(a);
        row.setAutospottingWrite(write);
        when(access.findByUserId(200L)).thenReturn(List.of(row));
    }

    // ── DEFAULT mode ───────────────────────────────────────────────────────────

    @Test
    void default_clientOwnerConfirmsEnableAndRevert() {
        ownerRows();
        AutoSpottingAuthorization a = authz("DEFAULT");
        assertThat(a.authorizeConfirm(owner(), account(), OperationKind.ENABLE, null).allowed()).isTrue();
        assertThat(a.authorizeConfirm(owner(), account(), OperationKind.REVERT, null).allowed()).isTrue();
    }

    @Test
    void default_subUserReadsOnly_anyConfirmForbidden() {
        subUserRows(false);
        Decision d = authz("DEFAULT").authorizeConfirm(subUser(), account(), OperationKind.ENABLE, null);
        assertThat(d.allowed()).isFalse();
        assertThat(d.denyCode()).isEqualTo("AS_FORBIDDEN_ROLE");
        assertThat(d.role()).isEqualTo(Role.SUB_USER);
    }

    @Test
    void default_adminConfirmsRevertWithReason_422WithoutReason_forbiddenOnEnable() {
        AutoSpottingAuthorization a = authz("DEFAULT");
        Decision noReason = a.authorizeConfirm(admin(), account(), OperationKind.REVERT, null);
        assertThat(noReason.allowed()).isFalse();
        assertThat(noReason.denyCode()).isEqualTo("CHECK_FAILED");

        Decision withReason = a.authorizeConfirm(admin(), account(), OperationKind.REVERT, "incident 12");
        assertThat(withReason.allowed()).isTrue();
        assertThat(withReason.role()).isEqualTo(Role.PLATFORM_ADMIN);

        Decision enable = a.authorizeConfirm(admin(), account(), OperationKind.ENABLE, "x");
        assertThat(enable.allowed()).isFalse();
        assertThat(enable.denyCode()).isEqualTo("AS_FORBIDDEN_ROLE");
    }

    @Test
    void default_impersonatedSessionReadsOnly() {
        ImpersonationContext.setImpersonatedUserId(500L);
        Decision d = authz("DEFAULT").authorizeConfirm(owner(), account(), OperationKind.REVERT, "x");
        assertThat(d.allowed()).isFalse();
        assertThat(d.role()).isEqualTo(Role.IMPERSONATED);
        assertThat(d.denyCode()).isEqualTo("AS_FORBIDDEN_ROLE");
    }

    // ── PO14_RECOMMENDED mode (implemented, UNLIFTED) ────────────────────────────

    @Test
    void recommended_subUserWithWriteConfirms_withoutWriteForbidden() {
        subUserRows(true);
        assertThat(authz("PO14_RECOMMENDED").authorizeConfirm(subUser(), account(), OperationKind.ENABLE, null)
                .allowed()).isTrue();

        subUserRows(false);
        Decision d = authz("PO14_RECOMMENDED").authorizeConfirm(subUser(), account(), OperationKind.ENABLE, null);
        assertThat(d.allowed()).isFalse();
        assertThat(d.denyCode()).isEqualTo("AS_FORBIDDEN_ROLE");
    }

    @Test
    void recommended_impersonatedMayRevert_andEnableOnlyWithReasonWritingAdminEnabled() {
        ImpersonationContext.setImpersonatedUserId(500L);
        AutoSpottingAuthorization a = authz("PO14_RECOMMENDED");

        assertThat(a.authorizeConfirm(owner(), account(), OperationKind.REVERT, null).allowed()).isTrue();

        Decision noReason = a.authorizeConfirm(owner(), account(), OperationKind.ENABLE, null);
        assertThat(noReason.allowed()).isFalse();
        assertThat(noReason.denyCode()).isEqualTo("CHECK_FAILED");

        Decision withReason = a.authorizeConfirm(owner(), account(), OperationKind.ENABLE, "on their behalf");
        assertThat(withReason.allowed()).isTrue();
        assertThat(withReason.adminEnabled()).isTrue();
        assertThat(withReason.actor().impersonatorUserId()).isEqualTo(500L);
    }

    @Test
    void unknownModeFailsSafeToDefault() {
        ownerRows();
        AutoSpottingAuthorization a = authz("NONSENSE");
        assertThat(a.mode()).isEqualTo(AutoSpottingAuthorization.Mode.DEFAULT);
    }
}
