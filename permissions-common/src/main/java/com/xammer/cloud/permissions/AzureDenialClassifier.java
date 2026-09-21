package com.xammer.cloud.permissions;

/**
 * Classifies Azure failures as permission-related or not.
 *
 * Azure uses HTTP status codes — 403 Forbidden for authorization failures,
 * 401 Unauthorized for authentication failures.
 *
 * Uses reflection to avoid hard dependency on Azure SDK in permissions-common.
 */
public final class AzureDenialClassifier {

    private AzureDenialClassifier() {
    }

    public static Result classify(Throwable failure) {
        String className = failure.getClass().getSimpleName();

        // Direct HttpResponseException
        if ("HttpResponseException".equals(className)) {
            Result result = extractFromHttpResponseException(failure);
            if (result != null) return result;
        }

        // ClientAuthenticationException
        if ("ClientAuthenticationException".equals(className)) {
            return new Result(PermissionGap.Kind.ROLE_UNREACHABLE, "AuthenticationFailed", failure.getMessage());
        }

        // Check nested exceptions
        Throwable cause = failure.getCause();
        int depth = 0;
        while (cause != null && depth++ < 5) {
            className = cause.getClass().getSimpleName();
            if ("HttpResponseException".equals(className)) {
                Result result = extractFromHttpResponseException(cause);
                if (result != null) return result;
            }
            if ("ClientAuthenticationException".equals(className)) {
                return new Result(PermissionGap.Kind.ROLE_UNREACHABLE, "AuthenticationFailed", cause.getMessage());
            }
            cause = cause.getCause();
        }

        // Check by message pattern.
        //
        // Narrower than it looks like it could be, on purpose. This block only runs when the
        // status code was unavailable, so it is guessing from prose — and a false positive here
        // is worse than a miss: it tells a customer their Azure role assignment is broken when
        // it is not, and sends them editing IAM for nothing.
        //
        // Specifically rejected: a bare "authentication" (matches "Multi-factor authentication
        // is required", and any message quoting a docs URL), and bare "401"/"403" (match
        // request ids, byte counts, and URLs like .../v403/...). Status codes are only trusted
        // when they appear in a status-shaped context.
        String msg = failure.getMessage();
        if (msg != null) {
            if (msg.contains("AuthorizationFailed") || msg.contains("Authorization failed")
                    || msg.contains("does not have authorization")) {
                return new Result(PermissionGap.Kind.PERMISSION, "AuthorizationFailed", msg);
            }
            // AADSTS is Azure AD's own error-code prefix, so it is unambiguous.
            if (msg.contains("Unauthorized") || msg.contains("AADSTS")
                    || msg.contains("ClientAuthenticationException")
                    || msg.contains("invalid_client")) {
                return new Result(PermissionGap.Kind.ROLE_UNREACHABLE, "Unauthorized", msg);
            }
            if (msg.contains("Forbidden") || mentionsHttpStatus(msg, 403)) {
                return new Result(PermissionGap.Kind.PERMISSION, "Forbidden", msg);
            }
            if (mentionsHttpStatus(msg, 401)) {
                return new Result(PermissionGap.Kind.ROLE_UNREACHABLE, "Unauthorized", msg);
            }
        }

        return null;
    }

    /**
     * Whether the message cites an HTTP status, rather than merely containing the digits.
     *
     * <p>Covers the framings the Azure SDK and ARM actually emit — {@code Status code 403},
     * {@code status: 403}, {@code (403)}, {@code "statusCode": 403}, {@code HTTP 403} — and
     * deliberately does not match the number on its own.
     */
    private static boolean mentionsHttpStatus(String message, int status) {
        String code = String.valueOf(status);
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("status code " + code)
                || lower.contains("status code: " + code)
                || lower.contains("status: " + code)
                || lower.contains("statuscode: " + code)
                || lower.contains("statuscode\": " + code)
                || lower.contains("statuscode=" + code)
                || lower.contains("http " + code)
                || lower.contains("(" + code + ")")
                || lower.contains(code + " forbidden")
                || lower.contains(code + " unauthorized");
    }

    private static Result extractFromHttpResponseException(Throwable hre) {
        try {
            // Get response: hre.getResponse()
            Object response = hre.getClass().getMethod("getResponse").invoke(hre);
            if (response == null) return null;

            // Get status code: response.getStatusCode()
            Object statusCode = response.getClass().getMethod("getStatusCode").invoke(response);
            if (statusCode == null) return null;

            int status = ((Number) statusCode).intValue();

            if (status == 403) {
                String msg = extractMessage(hre, response);
                return new Result(PermissionGap.Kind.PERMISSION, "AuthorizationFailed", msg);
            }
            if (status == 401) {
                String msg = extractMessage(hre, response);
                return new Result(PermissionGap.Kind.ROLE_UNREACHABLE, "Unauthorized", msg);
            }
        } catch (Exception e) {
            // Reflection failed, fall back to message inspection
        }
        return null;
    }

    private static String extractMessage(Throwable hre, Object response) {
        try {
            // Try to get body: response.getBodyAsString()
            Object body = response.getClass().getMethod("getBodyAsString").invoke(response);
            if (body != null) {
                String bodyStr = body.toString().trim();
                if (!bodyStr.isEmpty()) {
                    if (bodyStr.length() > 400) {
                        return bodyStr.substring(0, 400) + "…";
                    }
                    return bodyStr;
                }
            }
        } catch (Exception e) {
            // Fall back to exception message
        }
        return hre.getMessage();
    }

    public static final class Result {
        private final PermissionGap.Kind kind;
        private final String errorCode;
        private final String message;

        Result(PermissionGap.Kind kind, String errorCode, String message) {
            this.kind = kind;
            this.errorCode = errorCode;
            this.message = message;
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
    }
}
