package com.xammer.cloud.service.automation.workload.execute;

import com.xammer.cloud.service.automation.workload.ApplyMethod;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;

import java.time.Instant;
import java.util.List;

/**
 * One durable instruction to change one workload's resources.
 *
 * <p>Every field exists because of a measured failure or a verified platform constraint:
 *
 * <dl>
 *   <dt>{@code correlationId}</dt>
 *   <dd>Delivery through the agent queue is <strong>at-least-once</strong>: the agent polls, applies,
 *       and may die before reporting, so the next poll re-delivers. This is the
 *       <strong>idempotency key</strong>. The Go rebalancer was measured non-idempotent — it
 *       re-drained the same two nodes every 60 s with no completion state.</dd>
 *
 *   <dt>{@code expectedObservedRevision}</dt>
 *   <dd>Optimistic precondition. A replay against an unchanged revision is a no-op instead of a
 *       second patch.</dd>
 *
 *   <dt>{@code dryRun}</dt>
 *   <dd>Mandatory, and echoed in {@link ActionOutcome}. The Go rebalancer emitted
 *       {@code severity: "success"} / "Rebalance completed: 2 nodes drained" under
 *       {@code DRY_RUN=true} with no marker anywhere — an audit trail of events that never
 *       happened.</dd>
 *
 *   <dt>{@code notValidAfter}</dt>
 *   <dd>Terminal timeout. A command that sat in the queue past its window <strong>expires</strong>
 *       rather than being applied late against stale measurements. This is the queue-side half of
 *       the anti-thrash design; the engine-side half is hysteresis plus cooldown.</dd>
 * </dl>
 *
 * <p>Construction validates the invariants a queue entry must never violate, so an unsatisfiable
 * command cannot become durable.
 *
 * @param correlationId            caller-generated, stable, non-blank; the idempotency key
 * @param target                   which tenant's cluster
 * @param ref                      which workload
 * @param containers               per-container targets; at least one, no duplicate container names
 * @param method                   in-place, or in-place plus template persistence
 * @param dryRun                   whether to evaluate without writing
 * @param expectedObservedRevision the revision this command was computed against; may be null when
 *                                 the source did not expose one
 * @param notValidAfter            the instant after which the command must expire unapplied
 */
public record ActionCommand(String correlationId,
                            ClusterTarget target,
                            WorkloadRef ref,
                            List<ContainerApplyTarget> containers,
                            ApplyMethod method,
                            boolean dryRun,
                            String expectedObservedRevision,
                            Instant notValidAfter) {

    public ActionCommand {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException(
                    "correlationId is mandatory: it is the idempotency key for at-least-once delivery");
        }
        if (target == null) {
            throw new IllegalArgumentException("target is mandatory");
        }
        if (ref == null) {
            throw new IllegalArgumentException("ref is mandatory");
        }
        if (containers == null || containers.isEmpty()) {
            throw new IllegalArgumentException("a command with no container targets would be a no-op");
        }
        if (method == null) {
            throw new IllegalArgumentException("method is mandatory");
        }
        if (notValidAfter == null) {
            throw new IllegalArgumentException(
                    "notValidAfter is mandatory: a command with no expiry can be applied late against "
                            + "stale measurements");
        }
        containers = List.copyOf(containers);
        long distinct = containers.stream().map(ContainerApplyTarget::containerName).distinct().count();
        if (distinct != containers.size()) {
            throw new IllegalArgumentException(
                    "duplicate container targets: one metric applied to several containers is exactly "
                            + "the bug this engine exists to prevent");
        }
    }

    /** True when this command has expired and must not be applied. */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(notValidAfter);
    }
}
