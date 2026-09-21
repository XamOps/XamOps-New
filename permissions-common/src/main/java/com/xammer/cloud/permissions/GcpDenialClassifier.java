package com.xammer.cloud.permissions;

/**
 * Classifies GCP failures as permission-related or not.
 *
 * GCP uses gRPC Status codes — PERMISSION_DENIED (code 7) for IAM denials,
 * UNAUTHENTICATED (code 16) for auth failures. Both mean the caller lacks
 * permission; we distinguish them for the summary message.
 *
 * <p>Three shapes are checked, in order of reliability. The gRPC status code is
 * authoritative and comes first. The message patterns after it exist because two
 * important failures never produce a gRPC status at all: a revoked service-account key
 * fails at the OAuth token endpoint with {@code invalid_grant}, and a project with the
 * API switched off answers {@code SERVICE_DISABLED}. Both would otherwise be classified
 * as "not a permissions problem" and stay invisible.
 *
 * <p>Uses reflection to avoid a hard dependency on the GCP SDK in permissions-common.
 */
public final class GcpDenialClassifier {

    private GcpDenialClassifier() {
    }

    public static Result classify(Throwable failure) {
        // Check exception type name to avoid hard dependency
        String className = failure.getClass().getSimpleName();

        // Direct PermissionDeniedException from Google API client library
        if ("PermissionDeniedException".equals(className)) {
            return new Result(PermissionGap.Kind.PERMISSION, "PERMISSION_DENIED", failure.getMessage());
        }

        // gRPC Status error wrapping
        if ("StatusRuntimeException".equals(className)) {
            Result result = extractFromStatusRuntimeException(failure);
            if (result != null) return result;
        }

        // Check for nested status in the cause chain
        Throwable cause = failure.getCause();
        int depth = 0;
        while (cause != null && depth++ < 5) {
            className = cause.getClass().getSimpleName();
            if ("StatusRuntimeException".equals(className)) {
                Result result = extractFromStatusRuntimeException(cause);
                if (result != null) return result;
            }
            cause = cause.getCause();
        }

        // Check by message pattern
        String msg = failure.getMessage();
        if (msg != null) {
            if (msg.contains("PERMISSION_DENIED") || msg.contains("Permission denied") || msg.contains("does not have permission")) {
                return new Result(PermissionGap.Kind.PERMISSION, "PERMISSION_DENIED", msg);
            }
            if (msg.contains("UNAUTHENTICATED") || msg.contains("Unauthenticated")) {
                return new Result(PermissionGap.Kind.ROLE_UNREACHABLE, "UNAUTHENTICATED", msg);
            }
            // A service-account key that has been deleted, disabled, or had its project
            // removed. This is the most common way GCP access dies in practice, and none of
            // the patterns above match it: the OAuth token endpoint answers 400 with
            // invalid_grant long before any gRPC status exists, so there is no
            // PERMISSION_DENIED or UNAUTHENTICATED anywhere in the exception. Without this
            // branch the failure is invisible to the tracker and surfaces only as a raw
            // error string on the page.
            if (msg.contains("invalid_grant")
                    || msg.contains("Invalid JWT")
                    || msg.contains("Invalid grant")
                    || msg.contains("invalid_rapt")) {
                return new Result(PermissionGap.Kind.ROLE_UNREACHABLE, "INVALID_GRANT", msg);
            }
            // The key itself is fine but the project has the API switched off. Not a policy
            // problem, and telling the customer to edit IAM roles would send them to the
            // wrong screen — this needs "enable the API", which is why it maps to
            // SERVICE_NOT_ENABLED rather than PERMISSION.
            if (msg.contains("SERVICE_DISABLED")
                    || msg.contains("has not been used in project")
                    || msg.contains("is disabled. Enable it by visiting")
                    || msg.contains("accessNotConfigured")) {
                return new Result(PermissionGap.Kind.SERVICE_NOT_ENABLED, "SERVICE_DISABLED", msg);
            }
        }

        return null;
    }

    private static Result extractFromStatusRuntimeException(Throwable sre) {
        try {
            // Get the status via reflection: sre.getStatus()
            Object status = sre.getClass().getMethod("getStatus").invoke(sre);
            if (status == null) return null;

            // Get the code: status.getCode()
            Object code = status.getClass().getMethod("getCode").invoke(status);
            if (code == null) return null;

            // Get the description: status.getDescription()
            Object description = status.getClass().getMethod("getDescription").invoke(status);
            String desc = description != null ? description.toString() : "";

            // Check against code enum names
            String codeName = code.toString();
            if ("PERMISSION_DENIED".equals(codeName)) {
                return new Result(PermissionGap.Kind.PERMISSION, "PERMISSION_DENIED", desc);
            }
            if ("UNAUTHENTICATED".equals(codeName)) {
                return new Result(PermissionGap.Kind.ROLE_UNREACHABLE, "UNAUTHENTICATED", desc);
            }
        } catch (Exception e) {
            // Reflection failed, fall back to message inspection
        }
        return null;
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
