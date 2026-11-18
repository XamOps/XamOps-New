package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.domain.autospotting.SpotOperationStep;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationStepRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only facts about a plan, an operation and its steps that a provider's step program needs
 * (plan-phase-18.md): the plan's live fingerprint for {@code VERIFY_PRECONDITIONS}, the operation's
 * last parked progress for {@code WAIT_ROLLOUT}'s poll state, and a step's attempt count for the
 * marker's five-attempt rule. It lives in the core because the spine repositories are driven only by the
 * core services (design-core.md §11); a provider reads these facts through it and writes nothing.
 */
@Component
public class OperationFacts {

    private final SpotPlanRepository plans;
    private final SpotOperationRepository operations;
    private final SpotOperationStepRepository steps;

    public OperationFacts(SpotPlanRepository plans, SpotOperationRepository operations,
                          SpotOperationStepRepository steps) {
        this.plans = plans;
        this.operations = operations;
        this.steps = steps;
    }

    /** The live fingerprint the plan was made against, or empty when the plan is missing. */
    public Optional<String> planLiveFingerprint(UUID planId) {
        return planId == null ? Optional.empty()
                : plans.findById(planId).map(SpotPlan::getLiveFingerprint);
    }

    /** The acknowledgements the customer gave at confirm ({@code confirm_acks} JSON), or empty. */
    public Optional<String> planConfirmAcks(UUID planId) {
        return planId == null ? Optional.empty()
                : plans.findById(planId).map(SpotPlan::getConfirmAcks);
    }

    /** The progress JSON the operation last parked with ({@code InProgress}), or empty. */
    public Optional<String> operationProgress(UUID operationId) {
        return operationId == null ? Optional.empty()
                : operations.findById(operationId).map(SpotOperation::getProgress);
    }

    /** The recorded attempts of the step at {@code (operationId, seq)}; 1 when no row exists yet. */
    public int stepAttempts(UUID operationId, int seq) {
        return steps.findByOperationIdAndSeq(operationId, seq).map(SpotOperationStep::getAttempts).orElse(1);
    }
}
