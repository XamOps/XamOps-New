package com.xammer.cloud.service.automation.workload.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xammer.cloud.service.automation.workload.api.ReasonCode;

/**
 * A refusal body: {@code { "code": "K8S_…", "message": "…", "field": "…"?, "detail": "…"? }}.
 *
 * <p>{@code field} is present only on a {@code 400}. "Unknown account", "not your account" and "no
 * AWS row" all produce the <strong>same</strong> {@code 403 K8S_NO_ACCOUNT_ACCESS} message, because
 * distinguishing them turns the endpoint into an oracle for which account ids exist — which is most
 * of the value of the original IDOR this platform already fixed elsewhere.
 *
 * <p>{@code detail} is present only when the rule that refused has a finer name than its code: a
 * caller-level refusal carries the {@code AutomationWritePermission.Detail} that decided it
 * ({@code TAB_GRANT}, {@code IMPERSONATION}, {@code ROLE}, {@code NO_PRINCIPAL}, {@code DEMO},
 * {@code ACCOUNT}). One {@code K8S_NO_USER_PERMISSION} code covers four different situations with
 * four different remedies, and the UI picks the sentence and the next step by this value (glossary
 * 7.2) instead of guessing from the message. It names the rule, never the data: an {@code ACCOUNT}
 * detail is the same for every account, so it is not an oracle either.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefusalDto(String code, String message, String field, String detail) {

    /** Codes that are errors rather than gates: they never disable a control in the UI. */
    public static final String UNAUTHENTICATED = "K8S_UNAUTHENTICATED";
    public static final String INVALID_REQUEST = "K8S_INVALID_REQUEST";
    public static final String INVALID_POLICY = "K8S_INVALID_POLICY";
    public static final String NO_ACCOUNT_ACCESS = "K8S_NO_ACCOUNT_ACCESS";
    public static final String CLUSTER_NOT_REGISTERED = "K8S_CLUSTER_NOT_REGISTERED";
    public static final String INTERNAL = "K8S_INTERNAL";
    /** {@code 409}: another real apply to the same workload is still running. */
    public static final String APPLY_IN_PROGRESS = "K8S_APPLY_IN_PROGRESS";

    /** A refusal with no finer rule name than its code. */
    public RefusalDto(String code, String message, String field) {
        this(code, message, field, null);
    }

    public static RefusalDto of(String code, String message) {
        return new RefusalDto(code, message, null);
    }

    public static RefusalDto invalidRequest(String field, String message) {
        return new RefusalDto(INVALID_REQUEST, message, field);
    }

    public static RefusalDto gate(ReasonCode reason, String message) {
        return new RefusalDto(reason.refusalCode(), message, null);
    }
}
