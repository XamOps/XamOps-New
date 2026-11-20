package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.aws.steps.FallbackUpdateStep;
import com.xammer.cloud.service.autospotting.aws.steps.UpdateDistributionStep;
import com.xammer.cloud.service.autospotting.aws.steps.VerifyFallbackAppliedStep;
import com.xammer.cloud.service.autospotting.aws.steps.VerifyFallbackPreconditionsStep;
import com.xammer.cloud.service.autospotting.aws.steps.VerifyTargetStep;
import com.xammer.cloud.service.autospotting.aws.steps.WaitCapacityStep;
import com.xammer.cloud.service.autospotting.aws.steps.WaitRolloutStep;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.FallbackProperties;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The AWS fallback programs (design-aws.md §6.4, §6.5), derived from the operation's immutable inputs like the
 * conversion programs, so any instance can resume them.
 *
 * <pre>
 *  FALLBACK_ENTER   0 VERIFY_PRECONDITIONS  1 UPDATE_DISTRIBUTION (100% On-Demand above base, nothing else)
 *                   2 VERIFY_FALLBACK_APPLIED  3 WAIT_CAPACITY
 *  FALLBACK_EXIT    0 UPDATE_DISTRIBUTION (desired_config)  1 WAIT_ROLLOUT (exit stall rule)  2 VERIFY_TARGET
 *  FALLBACK_EXIT compensation (target FALLBACK)
 *                   0 UPDATE_DISTRIBUTION (100% On-Demand above base)  1 WAIT_CAPACITY
 * </pre>
 *
 * <p>Fallback is SAFETY: it runs while conversions are paused and under the account switch, under
 * {@code CONVERSIONS:aws} and {@code ACCOUNT:{id}}, and only the freeze stops it (AC-HLT-05). It is still
 * built only while {@link AwsProviderGate} is open, so with the AWS flags false nothing here reaches AWS.
 */
@Component
public class AwsFallbackPrograms {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AwsStepServices services;
    private final AwsFallbackRules rules;
    private final FallbackProperties props;

    public AwsFallbackPrograms(AwsStepServices services, AwsFallbackRules rules, FallbackProperties props) {
        this.services = services;
        this.rules = rules;
        this.props = props;
    }

    /** True for the kinds this class builds a program for. */
    public static boolean supports(OperationKind kind) {
        return kind == OperationKind.FALLBACK_ENTER || kind == OperationKind.FALLBACK_EXIT;
    }

    public StepProgram program(OperationContext op) {
        if (!supports(op.kind())) {
            throw new IllegalArgumentException("not a fallback kind: " + op.kind());
        }
        SpotGroup g = op.group();
        String[] parts = AwsStepLibrary.split(g.getGroupKey());
        boolean compensating = op.phase() == OperationPhase.COMPENSATING;
        if (op.kind() == OperationKind.FALLBACK_ENTER) {
            if (compensating) {
                throw new IllegalStateException("a FALLBACK_ENTER never compensates toward Spot");
            }
            AwsProgram p = program(op, parts, JSON.createObjectNode(), false);
            // A group that was APPLYING carries the half-applied change of the op fallback preempted.
            boolean preempting = g.getState() == GroupState.APPLYING;
            return new StepProgram(List.of(new VerifyFallbackPreconditionsStep(services, p, rules, preempting),
                    new FallbackUpdateStep(services, p), new VerifyFallbackAppliedStep(services, p),
                    new WaitCapacityStep(services, p, props)));
        }
        if (compensating) {
            if (op.compensationTarget() != CompensationTarget.FALLBACK) {
                throw new IllegalStateException("a FALLBACK_EXIT compensates to FALLBACK only");
            }
            AwsProgram p = program(op, parts, JSON.createObjectNode(), true);
            return new StepProgram(List.of(new FallbackUpdateStep(services, p),
                    new WaitCapacityStep(services, p, props)));
        }
        JsonNode config = parse(op.targetConfigJson());
        if (config == null || !config.path("instancesDistribution").isObject()) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_TARGET_MISSING",
                    "The group has no desired configuration to return to.");
        }
        AwsProgram p = program(op, parts, config, false);
        return new StepProgram(List.of(new UpdateDistributionStep(services, p), new WaitRolloutStep(services, p),
                new VerifyTargetStep(services, p)));
    }

    private static AwsProgram program(OperationContext op, String[] parts, JsonNode config, boolean compensating) {
        SpotGroup g = op.group();
        return new AwsProgram(op.operationId(), op.kind(), g.getId(), g.getCloudAccountId(), g.getGroupKey(),
                parts[0], parts[1], op.planId(), config, compensating);
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
