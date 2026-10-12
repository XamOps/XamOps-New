package com.xammer.cloud.service.automation.workload.api;

/**
 * One request parameter or path variable is missing or malformed, and {@link #field()} names it.
 *
 * <p>Exists because the controller used to raise {@code IllegalArgumentException(field)} and then
 * interpolate the message into user copy ("The {message} parameter is missing or malformed"). That
 * reads correctly only when the message really is a field name. Domain invariants raise
 * {@code IllegalArgumentException} too — {@code ClusterTarget}'s "clientId is mandatory: tenant
 * scoping is not optional", the {@code ContainerDecision} constructor's checks,
 * {@code ResourceEnvelope.recommendation} — and every one of those would have been rendered as a
 * 400 blaming the caller for a parameter they did not send, with a sentence spliced into the middle
 * of another sentence.
 *
 * <p>A distinct type keeps the two apart <strong>structurally</strong>: only a shape failure can
 * reach the controller's 400 handler, and a server-side invariant violation reaches
 * {@code GlobalExceptionHandler} like any other fault. Extends {@code RuntimeException} rather than
 * {@code IllegalArgumentException} for exactly that reason — subclassing it would put this back
 * inside the handler family it is trying to leave.
 */
public class ParameterShapeException extends RuntimeException {

    private final String field;

    public ParameterShapeException(String field) {
        super("parameter " + field + " is missing or malformed");
        this.field = field;
    }

    /** The parameter the caller has to fix. Safe to show: it is a field name, never a value. */
    public String field() {
        return field;
    }
}
