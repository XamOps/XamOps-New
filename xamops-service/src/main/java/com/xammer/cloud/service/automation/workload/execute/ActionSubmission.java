package com.xammer.cloud.service.automation.workload.execute;

/**
 * The answer to {@link WorkloadActionExecutor#submit(ActionCommand)}: the command is durable, or it
 * was not accepted. <strong>Never the applied result</strong> — that arrives later, through
 * {@link WorkloadActionExecutor#poll(String)}.
 *
 * @param correlationId echoes the command's idempotency key
 * @param state         whether the command became durable
 * @param detail        human-readable reason, mandatory for {@link SubmitState#REFUSED}
 */
public record ActionSubmission(String correlationId, SubmitState state, String detail) {

    public ActionSubmission {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId is mandatory");
        }
        if (state == null) {
            throw new IllegalArgumentException("state is mandatory");
        }
        if (state == SubmitState.REFUSED && (detail == null || detail.isBlank())) {
            throw new IllegalArgumentException("a refusal must say why");
        }
    }

    public static ActionSubmission accepted(String correlationId) {
        return new ActionSubmission(correlationId, SubmitState.ACCEPTED, null);
    }

    /** The at-least-once case: this correlation id is already queued or already applied. */
    public static ActionSubmission duplicate(String correlationId) {
        return new ActionSubmission(correlationId, SubmitState.DUPLICATE, null);
    }

    public static ActionSubmission refused(String correlationId, String detail) {
        return new ActionSubmission(correlationId, SubmitState.REFUSED, detail);
    }
}
