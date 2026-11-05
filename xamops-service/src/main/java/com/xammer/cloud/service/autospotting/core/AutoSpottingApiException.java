package com.xammer.cloud.service.autospotting.core;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * A v2/operator API error carrying a stable {@code code} (design-core.md §11 code list), its HTTP
 * status and the {@code detail} safe text, mapped to the RFC 9457 envelope by
 * {@link com.xammer.cloud.controller.autospotting.AutoSpottingProblemAdvice}.
 *
 * <p>This is the throw type for the codes the v2 read endpoints and the operator API raise that are
 * not already modelled by a dedicated exception ({@link com.xammer.cloud.service.autospotting.AutoSpottingBlockedException}
 * for the Phase A/plan refusals, {@link com.xammer.cloud.service.autospotting.core.lease.LeaseUnavailableException}
 * for the lease store outage): {@code AS_FORBIDDEN_ROLE}, {@code SWITCH_PROPERTY_OFF},
 * {@code SWITCH_VERSION_MISMATCH}, {@code PLAN_KIND_NOT_ALLOWED}, {@code IDEMPOTENCY_KEY_REUSED},
 * {@code NOT_MANAGED}, and the account/provider-mismatch 404. {@code detail} is safe text only.
 */
public class AutoSpottingApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final String engagedSwitch;
    private final UUID operationId;

    public AutoSpottingApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, false, null, null);
    }

    public AutoSpottingApiException(HttpStatus status, String code, String detail, boolean retryable,
                                    String engagedSwitch, UUID operationId) {
        super(detail);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
        this.engagedSwitch = engagedSwitch;
        this.operationId = operationId;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getEngagedSwitch() {
        return engagedSwitch;
    }

    public UUID getOperationId() {
        return operationId;
    }

    // ── named factories for the codes this phase raises (design-core.md §11, §6.6) ──

    /** 403 {@code AS_FORBIDDEN_ROLE}: the caller's role or session may not perform this action. */
    public static AutoSpottingApiException forbiddenRole() {
        return new AutoSpottingApiException(HttpStatus.FORBIDDEN, "AS_FORBIDDEN_ROLE",
                "You are not allowed to perform this operator action.");
    }

    /** 409 {@code SWITCH_PROPERTY_OFF}: a tighten-only scope can't be released past the property. */
    public static AutoSpottingApiException switchPropertyOff() {
        return new AutoSpottingApiException(HttpStatus.CONFLICT, "SWITCH_PROPERTY_OFF",
                "This switch can only tighten the deployed property; it cannot be released beyond it.");
    }

    /** 409 {@code SWITCH_VERSION_MISMATCH}: another operator changed the switch first. */
    public static AutoSpottingApiException switchVersionMismatch() {
        return new AutoSpottingApiException(HttpStatus.CONFLICT, "SWITCH_VERSION_MISMATCH",
                "The switch changed since you read it. Reload and try again.");
    }

    /** 409 {@code PLAN_KIND_NOT_ALLOWED}: the group's state does not allow this operator action (§6.6, §7.4). */
    public static AutoSpottingApiException planKindNotAllowed(String detail) {
        return new AutoSpottingApiException(HttpStatus.CONFLICT, "PLAN_KIND_NOT_ALLOWED", detail);
    }

    /** 422 {@code IDEMPOTENCY_KEY_REUSED}: the same key was reused with a different body (§3.7). */
    public static AutoSpottingApiException idempotencyKeyReused() {
        return new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used with a different request body.");
    }
}
