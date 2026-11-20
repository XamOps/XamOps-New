package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.aws.steps.CheckPermissionsStep;
import com.xammer.cloud.service.autospotting.aws.steps.PersistSnapshotStep;
import com.xammer.cloud.service.autospotting.aws.steps.PriorConfigMissingStep;
import com.xammer.cloud.service.autospotting.aws.steps.UpdateDistributionStep;
import com.xammer.cloud.service.autospotting.aws.steps.VerifyPreconditionsStep;
import com.xammer.cloud.service.autospotting.aws.steps.VerifyTargetStep;
import com.xammer.cloud.service.autospotting.aws.steps.WaitRolloutStep;
import com.xammer.cloud.service.autospotting.aws.steps.WriteMarkerStep;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The AWS step library (design-aws.md §2.3): builds the deterministic {@link StepProgram} for an
 * operation from its immutable inputs, so any instance can re-derive it to resume.
 *
 * <pre>
 *  ENABLE / ADOPT        0 VERIFY_PRECONDITIONS  1 CHECK_PERMISSIONS  2 PERSIST_SNAPSHOT  3 UPDATE_DISTRIBUTION
 *                        4 WAIT_ROLLOUT  5 VERIFY_TARGET  6 WRITE_MARKER  (7 transition: the runner)
 *  CONFIG_APPLY /        0 VERIFY_PRECONDITIONS  1 CHECK_PERMISSIONS  then 3, 4, 5 (no snapshot, no marker)
 *  PROFILE_CHANGE
 *  compensation          3, 4, 5 against the prior configuration (PRIOR_CONFIG: ADOPT, CONFIG_APPLY,
 *                        PROFILE_CHANGE). A SNAPSHOT compensation (ENABLE) is the REVERT program ({@link AwsRestoreProgram}).
 * </pre>
 *
 * <p>Persisted {@code seq} equals the position in the list for the forward leg, so {@link WriteMarkerStep}
 * reads its own attempt count by position. The fallback kinds (FALLBACK_ENTER/EXIT) and
 * ACCEPT_BASELINE belong to later phases and are refused here.
 */
@Component
public class AwsStepLibrary {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AwsStepServices services;

    public AwsStepLibrary(AwsStepServices services) {
        this.services = services;
    }

    /** The program for a conversion operation (forward leg or PRIOR_CONFIG compensation leg). */
    public StepProgram program(OperationContext op) {
        AwsProgram p = programOf(op);
        if (p.compensation() && !p.config().has("shape")) {
            return noPrior(p);
        }
        if (p.compensation()) {
            return new StepProgram(List.of(new UpdateDistributionStep(services, p), new WaitRolloutStep(services, p),
                    new VerifyTargetStep(services, p)));
        }
        List<Step> steps = new ArrayList<>();
        steps.add(new VerifyPreconditionsStep(services, p));
        steps.add(new CheckPermissionsStep(services, p));
        boolean converting = op.kind() == OperationKind.ENABLE || op.kind() == OperationKind.ADOPT;
        if (converting) {
            steps.add(new PersistSnapshotStep(services, p));
        }
        steps.add(new UpdateDistributionStep(services, p));
        steps.add(new WaitRolloutStep(services, p));
        steps.add(new VerifyTargetStep(services, p));
        if (converting) {
            steps.add(new WriteMarkerStep(services, p, steps.size()));
        }
        return new StepProgram(steps);
    }

    /** True for the kinds this library builds a program for. */
    public static boolean supports(OperationKind kind) {
        return kind == OperationKind.ENABLE || kind == OperationKind.ADOPT
                || kind == OperationKind.CONFIG_APPLY || kind == OperationKind.PROFILE_CHANGE;
    }

    private AwsProgram programOf(OperationContext op) {
        if (!supports(op.kind())) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_PROVIDER_NOT_READY",
                    "This AWS operation is not available yet.");
        }
        SpotGroup g = op.group();
        String[] parts = split(g.getGroupKey());
        boolean compensating = op.phase() == OperationPhase.COMPENSATING;
        JsonNode config;
        if (compensating) {
            if (op.compensationTarget() == CompensationTarget.SNAPSHOT) {
                throw new IllegalStateException("a SNAPSHOT compensation is the REVERT program, not a conversion");
            }
            String snapshotPayload = op.kind() == OperationKind.ADOPT
                    ? services.snapshots().findLinked(g.getId()).map(AwsSnapshotStore.Stored::payload).orElse(null)
                    : null;
            Optional<JsonNode> prior = AwsPriorConfig.resolve(op.compensationConfigJson(), snapshotPayload,
                    op.kind() == OperationKind.ADOPT ? null : g.getDesiredConfig());
            if (prior.isEmpty()) {
                return new AwsProgram(op.operationId(), op.kind(), g.getId(), g.getCloudAccountId(), g.getGroupKey(),
                        parts[0], parts[1], op.planId(), JSON.createObjectNode(), true);
            }
            config = prior.get();
        } else {
            config = parse(op.targetConfigJson());
            if (config == null || !config.path("instancesDistribution").isObject()) {
                throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_TARGET_MISSING",
                        "The plan has no target configuration to apply.");
            }
        }
        return new AwsProgram(op.operationId(), op.kind(), g.getId(), g.getCloudAccountId(), g.getGroupKey(),
                parts[0], parts[1], op.planId(), config, compensating);
    }

    /** A compensation program for an op whose prior configuration cannot be found: it fails safely. */
    StepProgram noPrior(AwsProgram p) {
        return new StepProgram(List.of(new PriorConfigMissingStep(services, p)));
    }

    static String[] split(String groupKey) {
        int slash = groupKey.indexOf('/');
        if (slash <= 0 || slash == groupKey.length() - 1) {
            throw new IllegalArgumentException("groupKey must be region/name: " + groupKey);
        }
        return new String[] {groupKey.substring(0, slash), groupKey.substring(slash + 1)};
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
