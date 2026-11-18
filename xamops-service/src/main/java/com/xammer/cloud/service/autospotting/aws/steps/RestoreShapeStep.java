package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsLaunchInfo;
import com.xammer.cloud.service.autospotting.aws.AwsMutatingClient;
import com.xammer.cloud.service.autospotting.aws.AwsOwnedFields;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadException;
import com.xammer.cloud.service.autospotting.aws.AwsRestoreProgram;
import com.xammer.cloud.service.autospotting.aws.AwsRestoreRequests;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.aws.AwsThreeWayDiff;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse;

import java.util.List;
import java.util.Set;

/**
 * Step 3 of the REVERT program (design-aws.md §5.3): one {@code UpdateAutoScalingGroup} with the merged owned
 * fields: a standalone {@code LaunchTemplate} (the unwrap) or the snapshot's policy, and the snapshot's
 * {@code CapacityRebalance}. The merge is the three-way check, recomputed here from the stored snapshot and the
 * live group, so it is the same on any instance. The launch template version string is never taken from the
 * snapshot, and capacity, tags, policies and hooks are never sent. Convergent: no call when the owned fields
 * already match.
 *
 * <p>V-AWS-10 (sandbox) confirms that sending {@code LaunchTemplate} removes the policy in one call. If AWS
 * rejects the unwrap, the step falls back to a single-override policy with the original type only, B=0, P=100 and
 * the snapshot's {@code CapacityRebalance}, and reports {@code EXACT_RESTORE_IMPOSSIBLE(UNWRAP_UNSUPPORTED)}.
 */
public final class RestoreShapeStep extends AwsRevertStep {
    public static final String NAME = "RESTORE_SHAPE";
    private static final Set<String> REJECTED = Set.of("ValidationError", "InvalidParameterValue",
            "InvalidParameterCombination");

    public RestoreShapeStep(AwsStepServices services, AwsProgram program, AwsRestoreProgram.Inputs inputs) {
        super(services, program, inputs);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean mutates() {
        return true;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        Live live = live();
        if (live.failure() != null) {
            return live.failure();
        }
        AwsGroupFacts facts = AwsGroupFacts.from(program.region(), live.raw());
        AwsThreeWayDiff.Target target = AwsRestoreProgram.resolve(services, live.account(), live.view(), live.raw(),
                inputs).diff().target();
        if (AwsOwnedFields.mismatches(facts, target.toNode()).isEmpty()) {
            return done("{\"alreadyApplied\":true}");
        }
        UpdateAutoScalingGroupRequest request = AwsRestoreRequests.restore(program.groupName(), target);
        List<String> forbidden = AwsRestoreRequests.forbidden(request);
        if (!forbidden.isEmpty()) {
            return failed(ErrorKind.VALIDATION, "REQUEST_NOT_OWNED_FIELDS",
                    "The update would change fields XamOps does not own: " + String.join(", ", forbidden) + ".");
        }
        ctx.leaseHandle().assertHeld(ctx.callTimeout());
        AwsMutatingClient client = services.mutating(live.account(), program.region());
        try {
            return updated(client.updateAutoScalingGroup(request), "{\"updated\":true}");
        } catch (AwsServiceException e) {
            boolean rejected = REJECTED.contains(errorCode(e));
            if (!(rejected && target.standalone() && facts.mixedInstancesPolicy())) {
                throw e;
            }
        }
        // The unwrap was rejected: the closest policy that behaves the same, never a guess (AC-REV-03).
        UpdateAutoScalingGroupRequest fallback = singleOverride(live, target);
        if (fallback == null) {
            return failed(ErrorKind.VALIDATION, "UNWRAP_UNSUPPORTED",
                    "AWS would not remove the mixed policy and the original instance type could not be found.");
        }
        ctx.leaseHandle().assertHeld(ctx.callTimeout());
        return updated(client.updateAutoScalingGroup(fallback),
                "{\"updated\":true,\"exact\":false,\"reason\":\"UNWRAP_UNSUPPORTED\"}");
    }

    private UpdateAutoScalingGroupRequest singleOverride(Live live, AwsThreeWayDiff.Target target) {
        String type;
        try {
            AwsLaunchInfo info = services.reader().launch(live.account(), live.view(), target.template());
            type = info.instanceType();
        } catch (AwsReadException e) {
            return null;
        }
        if (type == null) {
            return null;
        }
        AwsGroupFacts.LtSpec t = target.template();
        LaunchTemplateSpecification.Builder spec = LaunchTemplateSpecification.builder()
                .version(t.version() == null ? "$Default" : t.version());
        if (t.id() != null) {
            spec.launchTemplateId(t.id());
        } else {
            spec.launchTemplateName(t.name());
        }
        AwsGroupFacts f = AwsGroupFacts.from(program.region(), live.raw());
        InstancesDistribution.Builder d = InstancesDistribution.builder().onDemandBaseCapacity(0)
                .onDemandPercentageAboveBaseCapacity(100);
        if (f.onDemandAllocationStrategy() != null) {
            d.onDemandAllocationStrategy(f.onDemandAllocationStrategy());
        }
        if (f.spotAllocationStrategy() != null) {
            d.spotAllocationStrategy(f.spotAllocationStrategy());
        }
        return UpdateAutoScalingGroupRequest.builder().autoScalingGroupName(program.groupName())
                .capacityRebalance(target.capacityRebalance())
                .mixedInstancesPolicy(MixedInstancesPolicy.builder()
                        .launchTemplate(LaunchTemplate.builder().launchTemplateSpecification(spec.build())
                                .overrides(LaunchTemplateOverrides.builder().instanceType(type).build()).build())
                        .instancesDistribution(d.build()).build()).build();
    }

    private static StepOutcome updated(UpdateAutoScalingGroupResponse response, String json) {
        String requestId = response.responseMetadata() == null ? null : response.responseMetadata().requestId();
        return new StepOutcome.Done(json, requestId == null ? List.of() : List.of(requestId));
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        Live live = live();
        if (live.failure() != null) {
            return notYet();
        }
        AwsThreeWayDiff.Target target = AwsRestoreProgram.resolve(services, live.account(), live.view(), live.raw(),
                inputs).diff().target();
        if (AwsOwnedFields.mismatches(AwsGroupFacts.from(program.region(), live.raw()), target.toNode()).isEmpty()) {
            return done("{\"alreadyApplied\":true}");
        }
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Put the group's launch template, instance types and Capacity Rebalancing back to what they were "
                + "before XamOps, in one Auto Scaling update. Anything changed outside XamOps is left as it is, and "
                + "so are capacity, tags, scaling policies and hooks.";
    }
}
