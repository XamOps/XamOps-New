package com.xammer.cloud.service.automation.workload.execute;

import java.time.Instant;
import java.util.Map;

/**
 * What actually happened to a command, reported on a separate, later request than the one that
 * submitted it.
 *
 * <p>{@code dryRun} is repeated here rather than looked up from the command on purpose: an audit
 * entry that cannot be read without a join is an audit entry somebody will render without the join.
 *
 * @param correlationId the command's idempotency key
 * @param dryRun        whether this was a rehearsal; always present, never defaulted
 * @param terminal      the final state
 * @param perContainer  per-container result, keyed by container name
 * @param reportedAt    when the executing side reported this
 * @param attempt       1-based; greater than 1 means at-least-once delivery re-delivered
 * @param detail        human-readable detail for a refusal or failure
 */
public record ActionOutcome(String correlationId,
                            boolean dryRun,
                            Terminal terminal,
                            Map<String, ContainerApplyStatus> perContainer,
                            Instant reportedAt,
                            int attempt,
                            String detail) {

    public ActionOutcome {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId is mandatory");
        }
        if (terminal == null) {
            throw new IllegalArgumentException("terminal is mandatory");
        }
        if (reportedAt == null) {
            throw new IllegalArgumentException("reportedAt is mandatory");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt is 1-based, got " + attempt);
        }
        perContainer = perContainer == null ? Map.of() : Map.copyOf(perContainer);
    }

    /** Terminal states. A command is in exactly one of them, and never leaves it. */
    public enum Terminal {
        /** Every container target was written. */
        APPLIED,
        /** Some containers were written and some were not; {@code perContainer} says which. */
        PARTIALLY_APPLIED,
        /** The cluster or a safety gate declined; nothing was written. */
        REFUSED,
        /** An error prevented the write; some containers may have been written. */
        FAILED,
        /** {@code notValidAfter} passed before the command was picked up. Nothing was written. */
        EXPIRED
    }

    /** Per-container result, so a partial apply is legible rather than a single misleading verdict. */
    public record ContainerApplyStatus(String containerName, boolean applied, String detail) {
        public ContainerApplyStatus {
            if (containerName == null || containerName.isBlank()) {
                throw new IllegalArgumentException("containerName is mandatory");
            }
        }
    }
}
