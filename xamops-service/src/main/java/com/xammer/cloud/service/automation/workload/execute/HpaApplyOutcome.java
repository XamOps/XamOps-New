package com.xammer.cloud.service.automation.workload.execute;

/**
 * What happened to one {@link HpaActionCommand} — the horizontal counterpart to {@link ActionOutcome}
 * / {@link com.xammer.cloud.service.automation.workload.execute.ResizeProof}, kept deliberately small
 * because an HPA patch is a single atomic write with no per-pod rollout to prove.
 *
 * <p>{@code dryRun} and the resulting bounds are repeated here rather than looked up from the command
 * on purpose: an audit entry that cannot be read without a join is one somebody renders without the
 * join.
 *
 * @param correlationId        the command's idempotency key
 * @param submitState          whether the command became durable ({@code ACCEPTED} / {@code DUPLICATE}
 *                             / {@code REFUSED})
 * @param terminal             the final state
 * @param dryRun               whether this was a rehearsal; always present, never defaulted
 * @param minReplicas          the resulting {@code spec.minReplicas}, or null if left alone / not applied
 * @param maxReplicas          the resulting {@code spec.maxReplicas}, or null if left alone / not applied
 * @param targetUtilizationPct the resulting CPU {@code averageUtilization}, or null if left alone / not applied
 * @param detail               human-readable detail for a refusal, failure or expiry; null on success
 */
public record HpaApplyOutcome(String correlationId,
                              SubmitState submitState,
                              Terminal terminal,
                              boolean dryRun,
                              Integer minReplicas,
                              Integer maxReplicas,
                              Integer targetUtilizationPct,
                              String detail) {

    /** Terminal states. A command is in exactly one of them, and never leaves it. */
    public enum Terminal {
        /** The bounds/target were patched (or, on a dry run, would have been). */
        APPLIED,
        /** Queued for the in-cluster agent; the applied result arrives later. */
        QUEUED,
        /** A safety rule, a missing HPA or a lost optimistic race declined; nothing was written. */
        REFUSED,
        /** An error prevented the patch, or its outcome could not be confirmed. */
        FAILED,
        /** {@code notValidAfter} passed before the command was applied. Nothing was written. */
        EXPIRED
    }

    public HpaApplyOutcome {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId is mandatory");
        }
        if (submitState == null) {
            throw new IllegalArgumentException("submitState is mandatory");
        }
        if (terminal == null) {
            throw new IllegalArgumentException("terminal is mandatory");
        }
    }

    public static HpaApplyOutcome applied(HpaActionCommand command, boolean dryRun) {
        return new HpaApplyOutcome(command.correlationId(), SubmitState.ACCEPTED, Terminal.APPLIED,
                dryRun, command.minReplicas(), command.maxReplicas(), command.targetUtilizationPct(),
                null);
    }

    public static HpaApplyOutcome queued(HpaActionCommand command) {
        return new HpaApplyOutcome(command.correlationId(), SubmitState.ACCEPTED, Terminal.QUEUED,
                command.dryRun(), command.minReplicas(), command.maxReplicas(),
                command.targetUtilizationPct(), null);
    }

    public static HpaApplyOutcome refused(HpaActionCommand command, String detail) {
        return new HpaApplyOutcome(command.correlationId(), SubmitState.REFUSED, Terminal.REFUSED,
                command.dryRun(), null, null, null, detail);
    }

    public static HpaApplyOutcome failed(HpaActionCommand command, String detail) {
        return new HpaApplyOutcome(command.correlationId(), SubmitState.ACCEPTED, Terminal.FAILED,
                command.dryRun(), null, null, null, detail);
    }

    public static HpaApplyOutcome expired(HpaActionCommand command, String detail) {
        return new HpaApplyOutcome(command.correlationId(), SubmitState.REFUSED, Terminal.EXPIRED,
                command.dryRun(), null, null, null, detail);
    }

    /** A replay: the same terminal state as the first processing, flagged as a duplicate submission. */
    public static HpaApplyOutcome duplicate(String correlationId, HpaApplyOutcome original) {
        return new HpaApplyOutcome(correlationId, SubmitState.DUPLICATE, original.terminal(),
                original.dryRun(), original.minReplicas(), original.maxReplicas(),
                original.targetUtilizationPct(), original.detail());
    }

    public boolean applied() {
        return terminal == Terminal.APPLIED;
    }
}
