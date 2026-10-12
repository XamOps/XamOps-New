package com.xammer.cloud.service.automation.workload.execute;

import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;

import java.util.Optional;

/**
 * Applies a workload-autoscaling decision to a cluster.
 *
 * <p><strong>Exactly one implementation ships:</strong> {@link DirectInPlaceResizeExecutor}, the
 * direct {@link ExecutionPath#DIRECT} path. {@code ActionCommandContractTest} asserts that list
 * exactly — it was an absence assertion while none existed — so adding a second thing that can write
 * to a cluster stays a deliberate, reviewable act rather than an accident.
 *
 * <h2>Why the interface is asynchronous</h2>
 *
 * The interface is shaped around the <strong>primary</strong> execution path: a command the
 * in-cluster agent fetches, applies, and reports on <em>later</em>. The agent polls
 * {@code GET /api/v1/agent/commands} outbound-only and processes at most one command per heartbeat
 * cycle, so nothing can block on an in-cluster apply. A synchronous {@code apply()} that returned
 * the result would have to be redesigned the moment the agent path landed — and the UI built against
 * it would have to be rewritten too, which is why the published contract already specifies
 * {@code 202 + correlationId}.
 *
 * <p>Semantics an implementation must honour:
 *
 * <ul>
 *   <li><strong>At-least-once delivery.</strong> {@link ActionCommand#correlationId()} is the
 *       idempotency key; {@link ActionCommand#expectedObservedRevision()} makes a replay a no-op.</li>
 *   <li><strong>Expiry is terminal.</strong> A command past {@link ActionCommand#notValidAfter()}
 *       must end {@link ActionOutcome.Terminal#EXPIRED} and must not be applied, however idle the
 *       queue was.</li>
 *   <li><strong>{@code dryRun} is carried end to end</strong> and appears on every audit entry.</li>
 * </ul>
 *
 * <h2>Deferred implementations</h2>
 *
 * <ol>
 *   <li><strong>Agent command queue</strong> ({@link ExecutionPath#AGENT}) — a cluster-scoped
 *       command table, a new command type served through {@code /api/v1/agent/commands}, an
 *       outcome-report endpoint, and the {@code notValidAfter} expiry sweep. Note the existing
 *       {@code /commands} handler is the <em>DB agent's</em> {@code ExplainRequest} queue, addressed
 *       by {@code agentName} and authorised with the <em>global</em> API key; cluster scoping is new
 *       work and needs its own security review.</li>
 * </ol>
 *
 * <p>The direct executor is no longer deferred: see {@link DirectInPlaceResizeExecutor}.
 */
public interface WorkloadActionExecutor {

    /** Which path this executor uses. */
    ExecutionPath path();

    /** Whether this executor can act on {@code target}. Structural; performs no I/O. */
    boolean supports(ClusterTarget target);

    /**
     * Makes {@code command} durable.
     *
     * <p>Returns as soon as the command is persisted — <strong>never the applied result</strong>.
     * Re-submitting the same {@link ActionCommand#correlationId()} must answer
     * {@link SubmitState#DUPLICATE} without enqueueing a second copy.
     *
     * @throws ClusterAccessException when no execution path into the cluster exists, with the same
     *                                two typed kinds the read path uses
     */
    ActionSubmission submit(ActionCommand command) throws ClusterAccessException;

    /**
     * The outcome, once the executing side has reported it.
     *
     * @return empty while the command is still queued or in flight — which is not an error
     */
    Optional<ActionOutcome> poll(String correlationId);

    /**
     * Best-effort cancellation.
     *
     * <p>A command already fetched by an agent may still land: there is no way to recall it. A
     * caller must therefore treat cancellation as a request, and read {@link #poll(String)} for the
     * truth.
     */
    void cancel(String correlationId);
}
