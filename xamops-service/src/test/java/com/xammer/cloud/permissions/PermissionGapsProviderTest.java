package com.xammer.cloud.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider-specific gap builders.
 *
 * <p>The trap these guard against: {@code PermissionGap.provider} is field-initialised to
 * {@code "AWS"}, so a builder that forgets to overwrite it produces a GCP or Azure gap labelled
 * AWS — and because gaps round-trip through Redis as JSON, the wrong label persists and the
 * banner tells the customer to go fix the wrong cloud.
 */
class PermissionGapsProviderTest {

    // ── Provider labelling ────────────────────────────────────────────────────

    @Test
    @DisplayName("A GCP gap is labelled GCP, not the AWS default")
    void gcpGapCarriesGcpProvider() {
        PermissionGap gap = PermissionGaps.gcpDenied("my-project", "compute.instances.list",
                "PERMISSION_DENIED", "denied", PermissionGap.Kind.PERMISSION);

        assertThat(gap.getProvider()).isEqualTo("GCP");
        assertThat(gap.getAccountId()).isEqualTo("my-project");
    }

    @Test
    @DisplayName("An Azure gap is labelled Azure, not the AWS default")
    void azureGapCarriesAzureProvider() {
        PermissionGap gap = PermissionGaps.azureDenied("sub-123",
                "Microsoft.Compute/virtualMachines/read", "AuthorizationFailed", "denied",
                PermissionGap.Kind.PERMISSION);

        assertThat(gap.getProvider()).isEqualTo("Azure");
        assertThat(gap.getAccountId()).isEqualTo("sub-123");
    }

    @Test
    void awsGapIsUnchanged() {
        PermissionGap gap = PermissionGaps.denied("123456789012", "ec2", "DescribeInstances",
                "us-east-1", "AccessDenied", "denied", PermissionGap.Kind.PERMISSION);

        assertThat(gap.getProvider()).isEqualTo("AWS");
        assertThat(gap.getAction()).isEqualTo("ec2:DescribeInstances");
    }

    // ── Action shape differs per provider ─────────────────────────────────────

    @Test
    @DisplayName("GCP actions are already fully qualified, so nothing is joined onto them")
    void gcpActionIsThePermissionVerbatim() {
        PermissionGap gap = PermissionGaps.gcpDenied("my-project", "compute.instances.list",
                "PERMISSION_DENIED", "denied", PermissionGap.Kind.PERMISSION);

        assertThat(gap.getAction()).isEqualTo("compute.instances.list");
        // Not "compute:instances.list" — the AWS prefix:Operation format does not apply.
        assertThat(gap.getAction()).doesNotContain(":");
    }

    @Test
    void azureActionIsTheArmAction() {
        PermissionGap gap = PermissionGaps.azureDenied("sub-123",
                "Microsoft.Storage/storageAccounts/read", "AuthorizationFailed", "denied",
                PermissionGap.Kind.PERMISSION);

        assertThat(gap.getAction()).isEqualTo("Microsoft.Storage/storageAccounts/read");
    }

    // ── Unreachable variants ──────────────────────────────────────────────────

    @Test
    void gcpUnreachableIsRoleUnreachableKind() {
        PermissionGap gap = PermissionGaps.gcpUnreachable("my-project", "INVALID_GRANT", "key revoked");

        assertThat(gap.getKind()).isEqualTo(PermissionGap.Kind.ROLE_UNREACHABLE);
        assertThat(gap.getProvider()).isEqualTo("GCP");
    }

    @Test
    void azureUnreachableIsRoleUnreachableKind() {
        PermissionGap gap = PermissionGaps.azureUnreachable("sub-123", "Unauthorized", "secret expired");

        assertThat(gap.getKind()).isEqualTo(PermissionGap.Kind.ROLE_UNREACHABLE);
        assertThat(gap.getProvider()).isEqualTo("Azure");
    }

    // ── Catalog wiring ────────────────────────────────────────────────────────

    @Test
    @DisplayName("A recognised GCP service resolves features, so feature-scoped pages can filter")
    void gcpGapResolvesFeatures() {
        PermissionGap gap = PermissionGaps.gcpDenied("my-project", "compute.instances.list",
                "PERMISSION_DENIED", "denied", PermissionGap.Kind.PERMISSION);

        assertThat(gap.getService()).isEqualTo("compute");
        assertThat(gap.getServiceLabel()).isNotBlank();
        assertThat(gap.getFeatures()).isNotEmpty();
    }

    @Test
    void azureGapResolvesFeatures() {
        PermissionGap gap = PermissionGaps.azureDenied("sub-123",
                "Microsoft.Compute/virtualMachines/read", "AuthorizationFailed", "denied",
                PermissionGap.Kind.PERMISSION);

        assertThat(gap.getServiceLabel()).isNotBlank();
        assertThat(gap.getFeatures()).isNotEmpty();
    }

    // ── Shared bookkeeping ────────────────────────────────────────────────────

    @Test
    void everyBuilderStampsTimestampsAndAnOccurrence() {
        for (PermissionGap gap : new PermissionGap[] {
                PermissionGaps.gcpUnreachable("p", "INVALID_GRANT", "m"),
                PermissionGaps.azureUnreachable("s", "Unauthorized", "m") }) {
            assertThat(gap.getFirstSeen()).isNotNull();
            assertThat(gap.getLastSeen()).isNotNull();
            assertThat(gap.getOccurrences()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("A null kind defaults to PERMISSION rather than producing a kindless gap")
    void nullKindDefaults() {
        assertThat(PermissionGaps.gcpDenied("p", "compute.instances.list", "c", "m", null).getKind())
                .isEqualTo(PermissionGap.Kind.PERMISSION);
    }
}
