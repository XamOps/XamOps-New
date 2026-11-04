package com.xammer.cloud.service.automation.proposal;

import org.springframework.http.HttpStatus;

/**
 * A proposal request XamOps refused on purpose. Carries the HTTP status, a stable machine {@code code}
 * (the UI and the agent switch on it) and a fixed, user-safe message. Never built from request input.
 *
 * <p>Code conventions (design 3.8): the three codes that share a name with a
 * {@code K8sAutomationReason} carry its {@code K8S_} wire prefix ({@code K8S_CONTROLLER_NOT_READY},
 * {@code K8S_MODE_DISABLED}, {@code K8S_AGENT_TOO_OLD}); every other code is bare
 * ({@code VERSION_CONFLICT}, {@code NOT_FOUND}, ...).
 */
public class ProposalException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String field;

    public ProposalException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ProposalException(HttpStatus status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getField() {
        return field;
    }

    public static ProposalException notFound() {
        return new ProposalException(HttpStatus.NOT_FOUND, "NOT_FOUND", "That proposal doesn't exist.");
    }

    public static ProposalException conflict(String code, String message) {
        return new ProposalException(HttpStatus.CONFLICT, code, message);
    }

    public static ProposalException badRequest(String code, String message) {
        return new ProposalException(HttpStatus.BAD_REQUEST, code, message);
    }
}
