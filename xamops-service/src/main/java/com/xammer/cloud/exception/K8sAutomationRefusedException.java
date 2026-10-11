package com.xammer.cloud.exception;

import org.springframework.http.HttpStatus;

/**
 * A CloudK8s automation request XamOps refused on purpose.
 *
 * <p>
 * Carries everything the uniform refusal body needs ({@code K8sRefusalBody}): status, reason,
 * a fixed message, the offending field and, for {@link K8sAutomationReason#NO_USER_PERMISSION},
 * the detail. Controller-local {@code @ExceptionHandler}s map it, the same pattern
 * {@code AutoSpottingBlockedException} uses, so it never reaches {@code GlobalExceptionHandler}.
 *
 * <p>
 * The message is always fixed copy. Never build it from request input or another
 * exception's message.
 */
public class K8sAutomationRefusedException extends RuntimeException {

    private final HttpStatus status;
    private final K8sAutomationReason reason;
    private final String field;
    private final NoUserPermissionDetail detail;

    public K8sAutomationRefusedException(HttpStatus status, K8sAutomationReason reason, String message,
                                         String field, NoUserPermissionDetail detail) {
        super(message != null ? message : reason.defaultMessage());
        this.status = status != null ? status : reason.status();
        this.reason = reason;
        this.field = field;
        // NIT 6: detail only travels with NO_USER_PERMISSION.
        this.detail = reason == K8sAutomationReason.NO_USER_PERMISSION ? detail : null;
    }

    /** Refusal with the reason's own status and default message. */
    public static K8sAutomationRefusedException of(K8sAutomationReason reason) {
        return new K8sAutomationRefusedException(reason.status(), reason, reason.defaultMessage(), null, null);
    }

    /** Refusal with the reason's own status and the given fixed message. */
    public static K8sAutomationRefusedException of(K8sAutomationReason reason, String message) {
        return new K8sAutomationRefusedException(reason.status(), reason, message, null, null);
    }

    /** Refusal with the reason's own status, the given fixed message and the offending field. */
    public static K8sAutomationRefusedException of(K8sAutomationReason reason, String message, String field) {
        return new K8sAutomationRefusedException(reason.status(), reason, message, field, null);
    }

    /** {@code 403 K8S_NO_USER_PERMISSION} with the detail's copy. */
    public static K8sAutomationRefusedException noUserPermission(NoUserPermissionDetail detail) {
        return new K8sAutomationRefusedException(HttpStatus.FORBIDDEN, K8sAutomationReason.NO_USER_PERMISSION,
                detail.message(), null, detail);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public K8sAutomationReason getReason() {
        return reason;
    }

    public String getField() {
        return field;
    }

    public NoUserPermissionDetail getDetail() {
        return detail;
    }
}
