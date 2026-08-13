package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.compliance.ComplianceGovernanceService;
import com.xammer.cloud.service.compliance.ComplianceOpsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tenant isolation for ComplianceOps.
 *
 * Every endpoint on this controller is addressed by a caller-supplied accountId (or by a
 * record id that resolves to one), and the compliance tables key on that string directly.
 * Before these checks existed, any signed-in user could name another customer's account
 * and read its failing controls and per-resource evidence, or — worse, because these are
 * writes — approve, reject and revoke that customer's compliance waivers through the
 * id-addressed endpoints, which never mention an account at all.
 *
 * Each denial case asserts two things: that the call is refused, and that the underlying
 * service was never reached. A guard that throws only after the data has been fetched
 * still leaks it to anything logging or timing the call.
 *
 * The entitlement rule itself is covered by CloudAccountAccessServiceTest. These tests
 * only verify that this controller asks it, and asks it before doing any work.
 */
class ComplianceOpsControllerAuthorizationTest {

    private static final String OWN_ACCOUNT = "111111111111";
    private static final String FOREIGN_ACCOUNT = "999999999999";
    private static final String UNKNOWN_ACCOUNT = "000000000000";
    private static final Long EXCEPTION_ID = 7L;
    private static final Long REMEDIATION_ID = 8L;

    private ComplianceOpsService complianceOpsService;
    private ComplianceGovernanceService governanceService;
    private CloudAccountRepository cloudAccountRepository;
    private CloudAccountAccessService accessService;
    private ComplianceOpsController controller;

    private ClientUserDetails caller;

    /** ClientUserDetails is a UserDetails, not a Principal, so the actor arg is separate. */
    private final Principal principal = () -> "caller@example.com";

    @BeforeEach
    void setUp() {
        complianceOpsService = mock(ComplianceOpsService.class);
        governanceService = mock(ComplianceGovernanceService.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);
        accessService = mock(CloudAccountAccessService.class);
        controller = new ComplianceOpsController(
                complianceOpsService, governanceService, cloudAccountRepository, accessService);

        caller = new ClientUserDetails(
                "caller@example.com",
                "pw",
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                10L,
                500L,
                "tenant-a");

        // Both accounts exist and resolve; only entitlement differs.
        CloudAccount own = account(1L, 10L);
        CloudAccount foreign = account(2L, 99L);
        when(cloudAccountRepository.findByProviderAccountId(OWN_ACCOUNT)).thenReturn(Optional.of(own));
        when(cloudAccountRepository.findByProviderAccountId(FOREIGN_ACCOUNT)).thenReturn(Optional.of(foreign));
        when(cloudAccountRepository.findByProviderAccountId(UNKNOWN_ACCOUNT)).thenReturn(Optional.empty());
        when(cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(UNKNOWN_ACCOUNT, UNKNOWN_ACCOUNT))
                .thenReturn(List.of());

        when(accessService.canAccess(caller, own)).thenReturn(true);
        when(accessService.canAccess(caller, foreign)).thenReturn(false);
    }

    private CloudAccount account(Long id, Long clientId) {
        CloudAccount account = new CloudAccount();
        account.setId(id);
        Client client = new Client();
        client.setId(clientId);
        account.setClient(client);
        return account;
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Test
    void report_forForeignAccount_isDenied_andServiceNeverCalled() {
        assertThatThrownBy(() -> controller.getReport(FOREIGN_ACCOUNT, 90, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(complianceOpsService, never()).getReport(anyString(), anyInt());
    }

    @Test
    void report_forOwnAccount_isAllowed() {
        assertThatCode(() -> controller.getReport(OWN_ACCOUNT, 90, caller)).doesNotThrowAnyException();
        verify(complianceOpsService).getReport(OWN_ACCOUNT, 90);
    }

    @Test
    void trend_forForeignAccount_isDenied() {
        assertThatThrownBy(() -> controller.getTrend(FOREIGN_ACCOUNT, 90, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(complianceOpsService, never()).buildTrend(anyString(), anyInt());
    }

    @Test
    void scans_forForeignAccount_isDenied() {
        assertThatThrownBy(() -> controller.getScans(FOREIGN_ACCOUNT, 30, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(complianceOpsService, never()).getScanHistory(anyString(), anyInt());
    }

    @Test
    void listExceptions_forForeignAccount_isDenied() {
        assertThatThrownBy(() -> controller.listExceptions(FOREIGN_ACCOUNT, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).listExceptions(anyString());
    }

    @Test
    void listOwners_forForeignAccount_isDenied() {
        assertThatThrownBy(() -> controller.listOwners(FOREIGN_ACCOUNT, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).listOwners(anyString());
    }

    @Test
    void listRemediations_forForeignAccount_isDenied() {
        assertThatThrownBy(() -> controller.listRemediations(FOREIGN_ACCOUNT, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).listRemediations(anyString());
    }

    // ── Writes carrying an accountId in the body ───────────────────────────────

    @Test
    void requestException_forForeignAccount_isDenied() {
        ComplianceOpsController.ExceptionRequest body = new ComplianceOpsController.ExceptionRequest();
        body.accountId = FOREIGN_ACCOUNT;
        body.checkId = "check-1";

        assertThatThrownBy(() -> controller.requestException(body, principal, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).requestException(
                anyString(), anyString(), any(), any(), any(), anyString(), any());
    }

    @Test
    void setOwner_forForeignAccount_isDenied() {
        ComplianceOpsController.OwnerRequest body = new ComplianceOpsController.OwnerRequest();
        body.accountId = FOREIGN_ACCOUNT;
        body.checkId = "check-1";

        assertThatThrownBy(() -> controller.setOwner(body, principal, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).setOwner(
                anyString(), anyString(), any(), any(), any(), any(), anyString());
    }

    @Test
    void createRemediation_forForeignAccount_isDenied() {
        ComplianceOpsController.RemediationRequest body = new ComplianceOpsController.RemediationRequest();
        body.accountId = FOREIGN_ACCOUNT;
        body.checkId = "check-1";

        assertThatThrownBy(() -> controller.createRemediation(body, principal, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).createRemediation(
                anyString(), anyString(), any(), any(), any(), any(), any(), any(), anyString());
    }

    // ── Writes addressed only by record id ─────────────────────────────────────
    // These are the sharpest cases: the request never names an account, so the owning
    // account has to be looked up before the caller can be judged.

    @Test
    void approveException_onForeignRecord_isDenied() {
        when(governanceService.findExceptionAccountId(EXCEPTION_ID)).thenReturn(FOREIGN_ACCOUNT);

        assertThatThrownBy(() -> controller.approveException(EXCEPTION_ID, null, principal, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).decideException(anyLong(), anyBoolean(), anyString());
    }

    @Test
    void rejectException_onForeignRecord_isDenied() {
        when(governanceService.findExceptionAccountId(EXCEPTION_ID)).thenReturn(FOREIGN_ACCOUNT);

        assertThatThrownBy(() -> controller.rejectException(EXCEPTION_ID, null, principal, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).decideException(anyLong(), anyBoolean(), anyString());
    }

    @Test
    void revokeException_onForeignRecord_isDenied() {
        when(governanceService.findExceptionAccountId(EXCEPTION_ID)).thenReturn(FOREIGN_ACCOUNT);

        assertThatThrownBy(() -> controller.revokeException(EXCEPTION_ID, null, principal, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).revokeException(anyLong(), any(), anyString());
    }

    @Test
    void approveException_onOwnRecord_isAllowed() {
        when(governanceService.findExceptionAccountId(EXCEPTION_ID)).thenReturn(OWN_ACCOUNT);

        assertThatCode(() -> controller.approveException(EXCEPTION_ID, null, principal, caller))
                .doesNotThrowAnyException();
        verify(governanceService).decideException(EXCEPTION_ID, true, "caller@example.com");
    }

    @Test
    void updateRemediation_onForeignRecord_isDenied() {
        when(governanceService.findRemediationAccountId(REMEDIATION_ID)).thenReturn(FOREIGN_ACCOUNT);
        ComplianceOpsController.RemediationRequest body = new ComplianceOpsController.RemediationRequest();
        body.status = "RESOLVED";

        assertThatThrownBy(() -> controller.updateRemediation(REMEDIATION_ID, body, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).updateRemediation(
                anyLong(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A record id that does not exist must be refused exactly like a forbidden one.
     * Returning a different error would let a caller enumerate which waiver ids are real.
     */
    @Test
    void approveException_onUnknownRecord_isDeniedNotNotFound() {
        when(governanceService.findExceptionAccountId(EXCEPTION_ID)).thenReturn(null);

        assertThatThrownBy(() -> controller.approveException(EXCEPTION_ID, null, principal, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(governanceService, never()).decideException(anyLong(), anyBoolean(), anyString());
    }

    // ── Fail-closed edges ─────────────────────────────────────────────────────

    /** An unresolvable account is refused with the same error, so ids cannot be enumerated. */
    @Test
    void unknownAccount_isDeniedNotNotFound() {
        assertThatThrownBy(() -> controller.getReport(UNKNOWN_ACCOUNT, 90, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(complianceOpsService, never()).getReport(anyString(), anyInt());
    }

    @Test
    void blankAccountId_isDenied_withoutRepositoryLookup() {
        assertThatThrownBy(() -> controller.getReport("   ", 90, caller))
                .isInstanceOf(AccessDeniedException.class);
        verify(cloudAccountRepository, never()).findByProviderAccountId(anyString());
    }

    /** No principal resolved (misconfigured filter chain) must fail closed, not open. */
    @Test
    void missingPrincipal_isDenied() {
        assertThatThrownBy(() -> controller.getReport(OWN_ACCOUNT, 90, null))
                .isInstanceOf(AccessDeniedException.class);
        verify(complianceOpsService, never()).getReport(anyString(), anyInt());
    }
}
