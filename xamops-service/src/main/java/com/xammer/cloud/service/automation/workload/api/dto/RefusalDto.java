package com.xammer.cloud.service.automation.workload.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xammer.cloud.service.automation.workload.api.ReasonCode;

/**
 * A refusal body: {@code { "code": "K8S_…", "message": "…", "field": "…"? }}.
 *
 * <p>{@code field} is present only on a {@code 400}. "Unknown account", "not your account" and "no
 * AWS row" all produce the <strong>same</strong> {@code 403 K8S_NO_ACCOUNT_ACCESS} message, because
 * distinguishing them turns the endpoint into an oracle for which account ids exist — which is most
 * of the value of the original IDOR this platform already fixed elsewhere.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefusalDto(String code, String message, String field) {

    /** Codes that are errors rather than gates: they never disable a control in the UI. */
    public static final String UNAUTHENTICATED = "K8S_UNAUTHENTICATED";
    public static final String INVALID_REQUEST = "K8S_INVALID_REQUEST";
    public static final String INVALID_POLICY = "K8S_INVALID_POLICY";
    public static final String NO_ACCOUNT_ACCESS = "K8S_NO_ACCOUNT_ACCESS";
    public static final String CLUSTER_NOT_REGISTERED = "K8S_CLUSTER_NOT_REGISTERED";
    public static final String INTERNAL = "K8S_INTERNAL";

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
