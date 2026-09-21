package com.xammer.cloud.permissions;

import com.xammer.cloud.domain.CloudAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Permission status across all three providers.
 *
 * <p>Two things are under test. First, that a GCP or Azure account gets a status at all — the
 * service used to short-circuit any non-AWS account to an empty gap list, so those clouds could
 * never report a problem however much the registry knew. Second, that the remediation sentence
 * names the right thing to go and fix: a customer handed AWS assume-role instructions for a
 * broken GCP service-account key has been told nothing useful.
 */
class PermissionGapServiceProviderTest {

    @Mock private PermissionGapRegistry registry;

    private PermissionGapService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PermissionGapService(registry);
    }

    // ── The gate is gone ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A GCP account now reports its gaps instead of always looking healthy")
    void gcpAccountIsNoLongerSkipped() {
        CloudAccount account = account("GCP", "my-project", "Acme GCP");
        when(registry.findByAccount("my-project")).thenReturn(List.of(
                PermissionGaps.gcpUnreachable("my-project", "INVALID_GRANT", "key revoked")));

        AccountPermissionStatusDto status = service.statusFor(account, null);

        assertThat(status.getHealth()).isEqualTo(AccountPermissionStatusDto.Health.UNREACHABLE);
        assertThat(status.getProvider()).isEqualTo("GCP");
    }

    @Test
    void azureAccountIsNoLongerSkipped() {
        CloudAccount account = account("Azure", "sub-123", "Acme Azure");
        when(registry.findByAccount("sub-123")).thenReturn(List.of(
                PermissionGaps.azureDenied("sub-123", "Microsoft.Compute/virtualMachines/read",
                        "AuthorizationFailed", "denied", PermissionGap.Kind.PERMISSION)));

        AccountPermissionStatusDto status = service.statusFor(account, null);

        assertThat(status.getHealth()).isEqualTo(AccountPermissionStatusDto.Health.DEGRADED);
    }

    @Test
    @DisplayName("Lower-case provider strings still work — the column holds 'Azure', not 'AZURE'")
    void providerMatchingIsCaseInsensitive() {
        CloudAccount account = account("azure", "sub-123", "Acme");
        when(registry.findByAccount("sub-123")).thenReturn(List.of(
                PermissionGaps.azureUnreachable("sub-123", "Unauthorized", "secret expired")));

        assertThat(service.statusFor(account, null).getSummary())
                .contains("service principal");
    }

    @Test
    void anAccountWithNoGapsIsHealthy() {
        CloudAccount account = account("GCP", "my-project", "Acme GCP");
        when(registry.findByAccount("my-project")).thenReturn(List.of());

        AccountPermissionStatusDto status = service.statusFor(account, null);

        assertThat(status.getHealth()).isEqualTo(AccountPermissionStatusDto.Health.HEALTHY);
        assertThat(status.getSummary()).isNull();
    }

    // ── Remediation names the right thing to fix ──────────────────────────────

    @Test
    @DisplayName("A dead GCP key talks about the service account key, not an IAM role")
    void gcpUnreachableSummaryIsAboutTheKey() {
        CloudAccount account = account("GCP", "my-project", "Acme GCP");
        when(registry.findByAccount("my-project")).thenReturn(List.of(
                PermissionGaps.gcpUnreachable("my-project", "INVALID_GRANT", "key revoked")));

        String summary = service.statusFor(account, null).getSummary();

        assertThat(summary).contains("service account key");
        assertThat(summary).doesNotContain("assume");
        assertThat(summary).doesNotContain("XamOpsReadOnlyRole");
    }

    @Test
    @DisplayName("An expired Azure secret talks about the service principal")
    void azureUnreachableSummaryIsAboutTheServicePrincipal() {
        CloudAccount account = account("Azure", "sub-123", "Acme Azure");
        when(registry.findByAccount("sub-123")).thenReturn(List.of(
                PermissionGaps.azureUnreachable("sub-123", "Unauthorized", "secret expired")));

        String summary = service.statusFor(account, null).getSummary();

        assertThat(summary).contains("service principal");
        assertThat(summary).doesNotContain("assume");
    }

    @Test
    @DisplayName("AWS wording is untouched")
    void awsUnreachableSummaryStillTalksAboutAssumingTheRole() {
        CloudAccount account = account("AWS", "123456789012", "Acme AWS");
        when(registry.findByAccount("123456789012")).thenReturn(List.of(
                PermissionGaps.roleUnreachable("123456789012", "AccessDenied", "cannot assume")));

        String summary = service.statusFor(account, null).getSummary();

        assertThat(summary).contains("assume").contains("role");
    }

    @Test
    @DisplayName("A GCP permission gap points at the setup script, not at an AWS policy")
    void gcpPermissionSummaryPointsAtTheSetupScript() {
        CloudAccount account = account("GCP", "my-project", "Acme GCP");
        when(registry.findByAccount("my-project")).thenReturn(List.of(
                PermissionGaps.gcpDenied("my-project", "compute.instances.list",
                        "PERMISSION_DENIED", "denied", PermissionGap.Kind.PERMISSION)));

        String summary = service.statusFor(account, null).getSummary();

        assertThat(summary).contains("setup script");
        assertThat(summary).doesNotContain("XamOps policy");
    }

    @Test
    void azurePermissionSummaryPointsAtTheRoleAssignment() {
        CloudAccount account = account("Azure", "sub-123", "Acme Azure");
        when(registry.findByAccount("sub-123")).thenReturn(List.of(
                PermissionGaps.azureDenied("sub-123", "Microsoft.Compute/virtualMachines/read",
                        "AuthorizationFailed", "denied", PermissionGap.Kind.PERMISSION)));

        assertThat(service.statusFor(account, null).getSummary()).contains("role assignment");
    }

    @Test
    @DisplayName("A disabled GCP API sends the customer to the Google console, not the AWS one")
    void disabledServiceSummaryNamesTheRightConsole() {
        CloudAccount account = account("GCP", "my-project", "Acme GCP");
        when(registry.findByAccount("my-project")).thenReturn(List.of(
                PermissionGaps.gcpDenied("my-project", "compute.instances.list",
                        "SERVICE_DISABLED", "api off", PermissionGap.Kind.SERVICE_NOT_ENABLED)));

        String summary = service.statusFor(account, null).getSummary();

        assertThat(summary).contains("Google Cloud console");
        assertThat(summary).doesNotContain("AWS console");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private CloudAccount account(String provider, String providerAccountId, String name) {
        CloudAccount account = mock(CloudAccount.class);
        when(account.getProvider()).thenReturn(provider);
        when(account.getProviderAccountId()).thenReturn(providerAccountId);
        when(account.getAccountName()).thenReturn(name);
        return account;
    }
}
