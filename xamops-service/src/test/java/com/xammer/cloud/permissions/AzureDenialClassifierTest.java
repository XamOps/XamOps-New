package com.xammer.cloud.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as an Azure access failure — and, more importantly, what does not.
 *
 * <p>This classifier's message fallback only runs when the HTTP status was unavailable, so it is
 * reading prose. A false positive there is worse than a miss: it tells a customer their role
 * assignment is broken when it is not, and sends them editing Azure IAM for nothing. The
 * original version matched a bare {@code "403"} and a bare {@code "authentication"} anywhere in
 * the message, both of which fire on innocent text. The rejection tests below are the point of
 * this file.
 */
class AzureDenialClassifierTest {

    // ── What must NOT be treated as a denial ──────────────────────────────────

    @Test
    @DisplayName("A URL containing 403 is not a 403")
    void digitsInAUrlAreNotAStatusCode() {
        Throwable failure = new RuntimeException(
                "Request failed for https://management.azure.com/subscriptions/abc/v403/resources");

        assertThat(AzureDenialClassifier.classify(failure)).isNull();
    }

    @Test
    @DisplayName("A request id that happens to contain 401 is not a 401")
    void digitsInARequestIdAreNotAStatusCode() {
        Throwable failure = new RuntimeException(
                "Operation failed. x-ms-request-id: 9f401e2c-77ab-4011-bd02-000000000000");

        assertThat(AzureDenialClassifier.classify(failure)).isNull();
    }

    @Test
    @DisplayName("Prose mentioning authentication is not an authentication failure")
    void theWordAuthenticationAloneIsNotEnough() {
        Throwable failure = new RuntimeException(
                "Multi-factor authentication is recommended for this tenant. See the docs for details.");

        assertThat(AzureDenialClassifier.classify(failure)).isNull();
    }

    @Test
    @DisplayName("A throttle is not a permissions problem")
    void throttlingIsIgnored() {
        assertThat(AzureDenialClassifier.classify(
                new RuntimeException("Too many requests. Status code 429, please retry."))).isNull();
    }

    // ── What must still be caught ─────────────────────────────────────────────

    @Test
    void statusShapedForbiddenIsAPermissionGap() {
        assertThat(AzureDenialClassifier.classify(new RuntimeException("Status code 403, \"AuthorizationFailed\"")))
                .extracting(AzureDenialClassifier.Result::getKind)
                .isEqualTo(PermissionGap.Kind.PERMISSION);
    }

    @Test
    void parenthesisedStatusIsRecognised() {
        assertThat(AzureDenialClassifier.classify(new RuntimeException("Azure denied access (403)")))
                .extracting(AzureDenialClassifier.Result::getKind)
                .isEqualTo(PermissionGap.Kind.PERMISSION);
    }

    @Test
    void statusShapedUnauthorizedIsUnreachable() {
        assertThat(AzureDenialClassifier.classify(new RuntimeException("HTTP 401 returned by ARM")))
                .extracting(AzureDenialClassifier.Result::getKind)
                .isEqualTo(PermissionGap.Kind.ROLE_UNREACHABLE);
    }

    @Test
    @DisplayName("AADSTS codes are unambiguous and map to unreachable")
    void aadstsIsUnreachable() {
        Throwable failure = new RuntimeException(
                "AADSTS7000222: The provided client secret keys for app ... are expired.");

        AzureDenialClassifier.Result result = AzureDenialClassifier.classify(failure);

        assertThat(result).isNotNull();
        assertThat(result.getKind()).isEqualTo(PermissionGap.Kind.ROLE_UNREACHABLE);
    }

    @Test
    void authorizationFailedIsAPermissionGap() {
        AzureDenialClassifier.Result result = AzureDenialClassifier.classify(new RuntimeException(
                "AuthorizationFailed: The client does not have authorization to perform action "
                        + "'Microsoft.Compute/virtualMachines/read'"));

        assertThat(result).isNotNull();
        assertThat(result.getKind()).isEqualTo(PermissionGap.Kind.PERMISSION);
        assertThat(result.getErrorCode()).isEqualTo("AuthorizationFailed");
    }

    @Test
    void expiredClientSecretIsUnreachable() {
        assertThat(AzureDenialClassifier.classify(new RuntimeException("invalid_client: secret has expired")))
                .extracting(AzureDenialClassifier.Result::getKind)
                .isEqualTo(PermissionGap.Kind.ROLE_UNREACHABLE);
    }

    @Test
    void nullMessageDoesNotThrow() {
        assertThat(AzureDenialClassifier.classify(new RuntimeException())).isNull();
    }
}
