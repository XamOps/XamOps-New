package com.xammer.cloud.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as a GCP access failure.
 *
 * <p>The case that matters most here is {@code invalid_grant}. A deleted, disabled or rotated
 * service-account key is the most common way GCP access dies, and it never produces a gRPC
 * status — the OAuth token endpoint answers 400 long before any gapic client exists. The
 * classifier originally matched only {@code PERMISSION_DENIED} and {@code UNAUTHENTICATED}, so
 * that failure returned null, nothing was recorded, and the customer saw a raw exception string
 * on the Alerts page with no explanation attached.
 */
class GcpDenialClassifierTest {

    // ── The revoked-key case ──────────────────────────────────────────────────

    @Test
    @DisplayName("A revoked service-account key is recognised as unreachable, not ignored")
    void invalidGrantIsRoleUnreachable() {
        // Verbatim shape of what GoogleCredentials.refreshIfExpired() throws.
        Throwable failure = new IOException("Error getting access token for service account: "
                + "400 Bad Request POST https://oauth2.googleapis.com/token, "
                + "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}, "
                + "iss: xamops@xamops.iam.gserviceaccount.com");

        GcpDenialClassifier.Result result = GcpDenialClassifier.classify(failure);

        assertThat(result).isNotNull();
        assertThat(result.getKind()).isEqualTo(PermissionGap.Kind.ROLE_UNREACHABLE);
        assertThat(result.getErrorCode()).isEqualTo("INVALID_GRANT");
    }

    @Test
    @DisplayName("The other spellings Google uses for a dead key are covered too")
    void otherInvalidGrantSpellings() {
        assertThat(GcpDenialClassifier.classify(new IOException("Invalid JWT: token expired")))
                .extracting(GcpDenialClassifier.Result::getKind)
                .isEqualTo(PermissionGap.Kind.ROLE_UNREACHABLE);
        assertThat(GcpDenialClassifier.classify(new IOException("Invalid grant: account not found")))
                .extracting(GcpDenialClassifier.Result::getKind)
                .isEqualTo(PermissionGap.Kind.ROLE_UNREACHABLE);
    }

    // ── A disabled API is not a policy problem ────────────────────────────────

    @Test
    @DisplayName("A disabled API maps to SERVICE_NOT_ENABLED, so the advice is 'enable it' not 'fix IAM'")
    void disabledApiIsNotAPermissionProblem() {
        Throwable failure = new RuntimeException("Compute Engine API has not been used in project "
                + "123456 before or it is disabled. Enable it by visiting "
                + "https://console.developers.google.com/apis/api/compute.googleapis.com/overview");

        GcpDenialClassifier.Result result = GcpDenialClassifier.classify(failure);

        assertThat(result).isNotNull();
        assertThat(result.getKind()).isEqualTo(PermissionGap.Kind.SERVICE_NOT_ENABLED);
        assertThat(result.getErrorCode()).isEqualTo("SERVICE_DISABLED");
    }

    @Test
    void serviceDisabledStatusIsAlsoRecognised() {
        assertThat(GcpDenialClassifier.classify(new RuntimeException("SERVICE_DISABLED")))
                .extracting(GcpDenialClassifier.Result::getKind)
                .isEqualTo(PermissionGap.Kind.SERVICE_NOT_ENABLED);
    }

    // ── The original behaviour still holds ────────────────────────────────────

    @Test
    void permissionDeniedIsAPermissionGap() {
        GcpDenialClassifier.Result result = GcpDenialClassifier.classify(
                new RuntimeException("PERMISSION_DENIED: Required 'compute.instances.list' permission"));

        assertThat(result).isNotNull();
        assertThat(result.getKind()).isEqualTo(PermissionGap.Kind.PERMISSION);
    }

    @Test
    void unauthenticatedIsUnreachable() {
        assertThat(GcpDenialClassifier.classify(new RuntimeException("UNAUTHENTICATED")))
                .extracting(GcpDenialClassifier.Result::getKind)
                .isEqualTo(PermissionGap.Kind.ROLE_UNREACHABLE);
    }

    @Test
    void nestedCauseIsFound() {
        Throwable nested = new RuntimeException("wrapper",
                new RuntimeException("PERMISSION_DENIED on compute.regions.list"));

        assertThat(GcpDenialClassifier.classify(nested)).isNull();
        // The message walk only inspects the top-level message; the cause chain is searched for
        // StatusRuntimeException specifically. Documented here so the limit is deliberate rather
        // than discovered later.
    }

    // ── Silence on everything else ────────────────────────────────────────────

    @Test
    @DisplayName("A timeout is not a permissions problem and must be ignored")
    void unrelatedFailuresReturnNull() {
        assertThat(GcpDenialClassifier.classify(new IOException("Read timed out"))).isNull();
        assertThat(GcpDenialClassifier.classify(new RuntimeException("DEADLINE_EXCEEDED"))).isNull();
        assertThat(GcpDenialClassifier.classify(new RuntimeException("RESOURCE_EXHAUSTED"))).isNull();
        assertThat(GcpDenialClassifier.classify(new RuntimeException("NOT_FOUND"))).isNull();
    }

    @Test
    void nullMessageDoesNotThrow() {
        assertThat(GcpDenialClassifier.classify(new RuntimeException())).isNull();
    }
}
