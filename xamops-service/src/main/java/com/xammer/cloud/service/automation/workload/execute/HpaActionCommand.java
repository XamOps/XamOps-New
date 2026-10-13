package com.xammer.cloud.service.automation.workload.execute;

import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;

import java.time.Instant;

/**
 * One durable instruction to change one HPA's bounds and/or target — the horizontal counterpart to
 * {@link ActionCommand}.
 *
 * <p>Every field exists for the same measured reason the vertical command's does:
 *
 * <dl>
 *   <dt>{@code correlationId}</dt>
 *   <dd>Delivery through the agent queue is <strong>at-least-once</strong>, so this is the
 *       <strong>idempotency key</strong>: a replay answers {@link SubmitState#DUPLICATE} and sends no
 *       second patch.</dd>
 *
 *   <dt>{@code expectedObservedRevision}</dt>
 *   <dd>Optimistic precondition — the HPA's {@code metadata.resourceVersion} the numbers were
 *       computed against. The patch carries it so a concurrent edit between read and apply is a
 *       conflict the API server rejects, not a silent overwrite. May be null when the source did not
 *       expose one.</dd>
 *
 *   <dt>{@code dryRun}</dt>
 *   <dd>Mandatory, and echoed in the response. The Go reference emitted {@code severity: "success"}
 *       under {@code DRY_RUN=true} with no marker — an audit trail of events that never happened.</dd>
 *
 *   <dt>{@code notValidAfter}</dt>
 *   <dd>Terminal timeout. A command that sat in the queue past its window <strong>expires</strong>
 *       rather than being applied late against stale measurements.</dd>
 * </dl>
 *
 * <p>Construction validates the invariants a queue entry must never violate: a non-blank
 * {@code correlationId}, {@code min <= max} when both are set, and <strong>at least one</strong> of
 * {@code minReplicas} / {@code maxReplicas} / {@code targetUtilizationPct} non-null — a command that
 * changes nothing cannot become durable.
 *
 * @param correlationId            caller-generated, stable, non-blank; the idempotency key
 * @param target                   which tenant's cluster
 * @param workload                 the workload the HPA scales (for the audit log and the lock key)
 * @param hpaNamespace             the HPA's namespace
 * @param hpaName                  the HPA's name
 * @param minReplicas              proposed {@code spec.minReplicas}, or null to leave it alone
 * @param maxReplicas              proposed {@code spec.maxReplicas}, or null to leave it alone
 * @param targetUtilizationPct     proposed CPU {@code averageUtilization}, or null to leave it alone
 * @param expectedObservedRevision the HPA {@code resourceVersion} this command was computed against
 * @param dryRun                   whether to evaluate without writing
 * @param notValidAfter            the instant after which the command must expire unapplied
 */
public record HpaActionCommand(String correlationId,
                               ClusterTarget target,
                               WorkloadRef workload,
                               String hpaNamespace,
                               String hpaName,
                               Integer minReplicas,
                               Integer maxReplicas,
                               Integer targetUtilizationPct,
                               String expectedObservedRevision,
                               boolean dryRun,
                               Instant notValidAfter) {

    public HpaActionCommand {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException(
                    "correlationId is mandatory: it is the idempotency key for at-least-once delivery");
        }
        if (target == null) {
            throw new IllegalArgumentException("target is mandatory");
        }
        if (workload == null) {
            throw new IllegalArgumentException("workload is mandatory");
        }
        if (hpaNamespace == null || hpaNamespace.isBlank()) {
            throw new IllegalArgumentException("hpaNamespace is mandatory");
        }
        if (hpaName == null || hpaName.isBlank()) {
            throw new IllegalArgumentException("hpaName is mandatory");
        }
        if (notValidAfter == null) {
            throw new IllegalArgumentException(
                    "notValidAfter is mandatory: a command with no expiry can be applied late against "
                            + "stale measurements");
        }
        if (minReplicas == null && maxReplicas == null && targetUtilizationPct == null) {
            throw new IllegalArgumentException(
                    "an HPA command that moves no bound and no target would be a no-op");
        }
        if (minReplicas != null && minReplicas <= 0) {
            throw new IllegalArgumentException("minReplicas must be positive when set, got " + minReplicas);
        }
        if (maxReplicas != null && maxReplicas <= 0) {
            throw new IllegalArgumentException("maxReplicas must be positive when set, got " + maxReplicas);
        }
        if (minReplicas != null && maxReplicas != null && minReplicas > maxReplicas) {
            throw new IllegalArgumentException(
                    "minReplicas " + minReplicas + " exceeds maxReplicas " + maxReplicas
                            + ": an HPA cannot have a floor above its ceiling");
        }
        if (targetUtilizationPct != null && (targetUtilizationPct <= 0 || targetUtilizationPct > 100)) {
            throw new IllegalArgumentException(
                    "targetUtilizationPct must be in 1..100 when set, got " + targetUtilizationPct);
        }
    }

    /** True when this command has expired and must not be applied. */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(notValidAfter);
    }
}
