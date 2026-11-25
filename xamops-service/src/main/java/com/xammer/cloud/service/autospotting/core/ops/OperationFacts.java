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

    /**
     * The target configuration of the group's most recent conversion operation (ENABLE, ADOPT, CONFIG_APPLY or
     * PROFILE_CHANGE), or empty. A revert that preempts a conversion still in flight needs it: the conversion
     * already wrote its target to the cloud, but {@code desired_config} is written only when it succeeds, so it is
     * the only record of what XamOps added.
     */
    public Optional<String> latestConversionTarget(UUID groupId) {
        if (groupId == null) {
            return Optional.empty();
        }
        return operations.findByGroupId(groupId).stream()
                .filter(o -> o.getTargetConfig() != null && switch (o.getKind()) {
                    case ENABLE, ADOPT, CONFIG_APPLY, PROFILE_CHANGE -> true;
                    default -> false;
                })
                .max(java.util.Comparator.comparing(
                        (SpotOperation o) -> o.getCreatedAt() == null ? java.time.Instant.EPOCH : o.getCreatedAt()))
                .map(SpotOperation::getTargetConfig);
    }
    /**
     * The target configuration of the CONFIG_APPLY / PROFILE_CHANGE that FALLBACK_ENTER {@code fallbackOpId}
     * preempted, or empty. It becomes {@code desired_config} when the fallback lands, so the fallback program
     * resolves maintenance-policy ownership from it (the group's old desired_config does not have it yet).
     */
    public Optional<String> preemptedConfigTarget(UUID fallbackOpId) {
        if (fallbackOpId == null) {
            return Optional.empty();
        }
        return operations.findConfigOpsPreemptedBy(fallbackOpId).stream()
                .map(SpotOperation::getTargetConfig)
                .filter(t -> t != null && !t.isBlank())
                .findFirst();
    }
}
