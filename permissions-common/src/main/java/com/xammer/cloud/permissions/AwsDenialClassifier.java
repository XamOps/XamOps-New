package com.xammer.cloud.permissions;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.sts.model.StsException;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a failed AWS call failed because the customer's IAM role is
 * missing something — as opposed to a throttle, a timeout, or a bad request.
 *
 * Only authorisation-shaped failures should ever reach the customer as a
 * "missing permission" banner. A network blip that got reported as a revoked
 * policy would send them editing IAM for no reason, so the checks here are
 * deliberately narrow and error-code driven rather than message matching.
 */
public final class AwsDenialClassifier {

    private AwsDenialClassifier() {
    }

    /** Error codes AWS uses when the caller is not authorised. */
    private static final Set<String> DENIAL_CODES = Set.of(
            "accessdenied",
            "accessdeniedexception",
            "unauthorizedoperation",
            "unauthorizedexception",
            "authorizationerror",
            "authfailure",
            "notauthorized",
            "notauthorizedexception",
            "forbidden",
            "missingauthenticationtoken",
            "invalidclienttokenid",
            "accessdeniedfault",
            "clientunauthorizedexception",
            "unrecognizedclientexception");

    /** Codes meaning "the service is not turned on", which is not a policy problem. */
    private static final Set<String> NOT_ENABLED_CODES = Set.of(
            "optinrequired",
            "subscriptionrequiredexception",
            "subscriptionrequired",
            "datanotavailableexception",
            "invalidsubscription",
            "organizationnotinuseexception",
            "awsorganizationsnotinuseexception");

    /**
     * Classifies a failure. Returns {@code null} when the failure is not an
     * authorisation problem and should be ignored by the permission tracker.
     */
    public static Result classify(Throwable failure) {
        AwsServiceException serviceException = findServiceException(failure);
        if (serviceException == null) {
            return null;
        }

        String errorCode = errorCodeOf(serviceException);
        String normalized = errorCode == null ? "" : errorCode.toLowerCase(Locale.ROOT);
        int status = serviceException.statusCode();

        PermissionGap.Kind kind;
        if (NOT_ENABLED_CODES.contains(normalized)) {
            kind = PermissionGap.Kind.SERVICE_NOT_ENABLED;
        } else if (DENIAL_CODES.contains(normalized) || status == 403) {
            // A denial raised by STS means credential resolution itself failed:
            // the cross-account role is gone, renamed, or its trust policy no
            // longer trusts XamOps. Nothing at all works for this account.
            kind = (serviceException instanceof StsException)
                    ? PermissionGap.Kind.ROLE_UNREACHABLE
                    : PermissionGap.Kind.PERMISSION;
        } else {
            return null;
        }

        return new Result(kind, errorCode, serviceException.getMessage(), serviceException instanceof StsException);
    }

    /** Walks the cause chain — the SDK wraps service exceptions in client exceptions and completion stages. */
    private static AwsServiceException findServiceException(Throwable failure) {
        Throwable current = failure;
        int guard = 0;
        while (current != null && guard++ < 12) {
            if (current instanceof AwsServiceException) {
                return (AwsServiceException) current;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String errorCodeOf(AwsServiceException exception) {
        if (exception.awsErrorDetails() != null && exception.awsErrorDetails().errorCode() != null) {
            return exception.awsErrorDetails().errorCode();
        }
        return null;
    }

    /** Outcome of classifying a failure as an authorisation problem. */
    public static final class Result {
        private final PermissionGap.Kind kind;
        private final String errorCode;
        private final String message;
        private final boolean fromSts;

        Result(PermissionGap.Kind kind, String errorCode, String message, boolean fromSts) {
            this.kind = kind;
            this.errorCode = errorCode;
            this.message = message;
            this.fromSts = fromSts;
        }

        public PermissionGap.Kind getKind() {
            return kind;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getMessage() {
            return message;
        }

        /** True when the denial came from assuming the role, not from the target service. */
        public boolean isFromSts() {
            return fromSts;
        }
    }
}
