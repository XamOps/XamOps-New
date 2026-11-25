package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.domain.autospotting.SpotOperationStep;
import com.xammer.cloud.domain.autospotting.StepStatus;
import com.xammer.cloud.repository.autospotting.SpotOperationStepRepository;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs one step of an operation's {@link com.xammer.cloud.service.autospotting.spi.StepProgram}
 * through the write-ahead protocol of §4.5 (design-core.md §4.5, §9.1, §9.2; plan-phase-07.md task
 * 7.1). The {@link OperationRunner} calls it once per step boundary; this class owns only the
 * single-step protocol, so the runner's loop stays about control flow:
 *
 * <ol>
 *   <li><b>Intent.</b> If the step has no row yet, {@link OperationStore#recordIntent} writes it
 *       {@code INTENT_RECORDED} (fenced) before any cloud call. A resumer that finds an existing
 *       {@code INTENT_RECORDED} row does <em>not</em> re-insert; it probes.</li>
 *   <li><b>assertHeld.</b> {@link Step#execute} itself calls {@code ctx.leaseHandle().assertHeld}
 *       before a mutating cloud call (§3.4); the fake and real providers do this. The runner also
 *       asserts at the boundary (plan-phase-07.md 7.1 "calls assertHeld before each mutating step").</li>
 *   <li><b>Execute or probe.</b> A fresh step {@link Step#execute}s; a resumed {@code INTENT_RECORDED}
 *       step {@link Step#probe}s first (§4.5: "A resumer that finds INTENT_RECORDED calls probe").</li>
 *   <li><b>Complete.</b> A {@link StepOutcome.Done}/{@link StepOutcome.Failed} is written terminal;
 *       an {@link StepOutcome.InProgress} is left {@code INTENT_RECORDED} (the runner parks the op
 *       WAITING and the next poll re-enters this step to re-probe).</li>
 * </ol>
 *
 * <p>No cloud is reached here directly: the {@link Step} the provider built does the cloud work with
 * the deterministic {@code uuidv5(operation_id, seq)} token (§9.3). The {@code FakeSpotProvider}
 * makes no call at all, so the whole phase stays dark (plan-phase-07.md "No real provider is
 * registered").
 */
@Service
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final OperationStore store;
    private final SpotOperationStepRepository steps;

    public StepExecutor(OperationStore store, SpotOperationStepRepository steps) {
        this.store = store;
        this.steps = steps;
    }

    /**
     * Run the step at {@code seq} for {@code operationId} under {@code handle}. {@code resume} is true
     * when the runner is resuming a step that already has an {@code INTENT_RECORDED} row (startup
     * recovery, §4.6, or a WAITING re-poll): it probes instead of re-recording intent.
     *
     * @return the step outcome ({@link StepOutcome.Done}, {@link StepOutcome.InProgress} or
     *         {@link StepOutcome.Failed}); the runner decides the op's next move from it.
     */
    public StepOutcome run(LeaseHandle handle, UUID operationId, Step step, int seq, Duration callTimeout) {
        Optional<SpotOperationStep> existing = steps.findByOperationIdAndSeq(operationId, seq);
        StepContext ctx = new StepContext(operationId, seq, handle, null, callTimeout);

        boolean resuming = existing.isPresent() && existing.get().getStatus() == StepStatus.INTENT_RECORDED;
        if (resuming) {
            // Probe first (§4.5): did the step's side effect already happen? The probe either reports
            // Done (adopt it) or the runner re-issues execute with the same deterministic token.
            StepOutcome probed = step.probe(ctx);
            if (probed instanceof StepOutcome.Done done) {
                complete(handle, operationId, seq, done);
                return done;
            }
            // Probe inconclusive: re-issue execute with the same token (idempotent, §9.3). An earlier attempt may have
            // changed the cloud, so a rejection now does not prove "no effect" (design-core 9.4).
            StepOutcome again = executeAndComplete(handle, ctx, step, seq);
            return again instanceof StepOutcome.Failed f ? f.withoutNoEffect() : again;
        }

        if (existing.isEmpty()) {
            // (1) intent before any cloud call (fenced). mutating drives first_mutation_seq (§4.5).
            boolean mutating = isMutating(step);
            store.recordIntent(handle, operationId, seq, step.name(), ctx.idempotencyToken(),
                    step.describeIntent(ctx) == null ? null : "{\"intent\":true}", mutating);
        }
        return executeAndComplete(handle, ctx, step, seq);
    }

    private StepOutcome executeAndComplete(LeaseHandle handle, StepContext ctx, Step step, int seq) {
        StepOutcome outcome = step.execute(ctx);
        if (outcome instanceof StepOutcome.Done done) {
            complete(handle, ctx.operationId(), seq, done);
        } else if (outcome instanceof StepOutcome.Failed failed) {
            // A failed step records its terminal FAILED row with the redacted code/message (§9.4).
            store.completeStep(handle, ctx.operationId(), seq, StepStatus.FAILED,
                    java.util.List.of(), failedOutput(failed));
        }
        // InProgress: leave the row INTENT_RECORDED; the runner parks WAITING and re-polls (§4.1).
        return outcome;
    }

    private void complete(LeaseHandle handle, UUID operationId, int seq, StepOutcome.Done done) {
        store.completeStep(handle, operationId, seq, StepStatus.DONE, done.requestIds(), done.outputJson());
        if (log.isDebugEnabled()) {
            log.debug("op {} step {} DONE ({} request id(s))", operationId, seq, done.requestIds().size());
        }
    }

    /** True when the step is one that mutates the cloud; used to stamp {@code first_mutation_seq} (§4.5). */
    private static boolean isMutating(Step step) {
        return step.mutates();
    }

    private static String failedOutput(StepOutcome.Failed failed) {
        return "{\"errorKind\":\"" + failed.kind().name() + "\"}";
    }
}
