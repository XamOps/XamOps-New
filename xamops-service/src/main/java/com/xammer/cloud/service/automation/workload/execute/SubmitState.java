package com.xammer.cloud.service.automation.workload.execute;

/** Outcome of making a command durable. */
public enum SubmitState {

    /** Queued. The applied result arrives later. */
    ACCEPTED,

    /**
     * This {@code correlationId} is already queued or already applied, so nothing new was enqueued.
     * Not an error: it is the expected answer to a retry under at-least-once delivery.
     */
    DUPLICATE,

    /** Not enqueued. {@link ActionSubmission#detail()} says why. */
    REFUSED
}
