package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsMaintenancePolicy;
import com.xammer.cloud.service.autospotting.aws.AwsMutatingClient;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsRestoreRequests;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.FallbackSteps;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse;

import java.util.List;
import java.util.Optional;

/**
 * The one mutation of FALLBACK_ENTER (design-aws.md §6.4 step 2): {@code UpdateAutoScalingGroup} with
 * {@code OnDemandPercentageAboveBaseCapacity=100} and nothing else. The On-Demand base, the overrides, the
 * strategies and {@code CapacityRebalance} are not in the request, so they stay as they are (AD-04). Auto
 * Scaling then replaces Spot with On-Demand launch-first, so the group never drops below desired. Convergent:
 * when the share is already 100 no call is made. Also the first step of a FALLBACK_EXIT's compensation, which
 * puts the group back on On-Demand. Asserts the lease before the call.
 */
public final class FallbackUpdateStep extends AwsStep {
    public FallbackUpdateStep(AwsStepServices services, AwsProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return FallbackSteps.UPDATE_DISTRIBUTION;
    }

    @Override
    public boolean mutates() {
        return true;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return failed(ErrorKind.VALIDATION, "ACCOUNT_MISSING", "The connected account could not be found.");
        }
        AwsReadOnlyView view = services.view(account, program.region());
        Optional<AutoScalingGroup> raw = services.statusReader().describeGroup(view, program.groupName());
        if (raw.isEmpty()) {
            return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The Auto Scaling group no longer exists.");
        }
        AwsGroupFacts live = AwsGroupFacts.from(program.region(), raw.get());
        if (!live.mixedInstancesPolicy()) {
            return failed(ErrorKind.VALIDATION, "NO_FALLBACK_PATH", "The group has no instance mix to change.");
        }
        if (applied(live, program.config())) {
            return done("{\"alreadyApplied\":true}");
        }
        UpdateAutoScalingGroupRequest request = AwsRestoreRequests.drain(raw.get(), program.config());
        List<String> forbidden = AwsRestoreRequests.forbidden(request);
        if (!forbidden.isEmpty()) {
            return failed(ErrorKind.VALIDATION, "REQUEST_NOT_OWNED_FIELDS",
                    "The update would change fields XamOps does not own: " + String.join(", ", forbidden) + ".");
        }
        ctx.leaseHandle().assertHeld(ctx.callTimeout());
        AwsMutatingClient client = services.mutating(account, program.region());
        UpdateAutoScalingGroupResponse response = client.updateAutoScalingGroup(request);
        String requestId = response.responseMetadata() == null ? null : response.responseMetadata().requestId();
        return new StepOutcome.Done("{\"updated\":true}", requestId == null ? List.of() : List.of(requestId));
    }

    static boolean applied(AwsGroupFacts live) {
        return live.onDemandPercentageAboveBase() != null && live.onDemandPercentageAboveBase() == 100;
    }

    /** Applied when the share is 100 and, if XamOps owns the maintenance policy, the policy is in force too (AD-08). */
    static boolean applied(AwsGroupFacts live, com.fasterxml.jackson.databind.JsonNode config) {
        if (!applied(live)) {
            return false;
        }
        if (!AwsMaintenancePolicy.hasValue(config)) {
            return true;
        }
        com.fasterxml.jackson.databind.JsonNode p = config.path(AwsMaintenancePolicy.KEY);
        return java.util.Objects.equals(live.minHealthyPercentage(), p.get("min").asInt())
                && java.util.Objects.equals(live.maxHealthyPercentage(), p.get("max").asInt());
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return notYet();
        }
        AwsReadOnlyView view = services.view(account, program.region());
        Optional<AutoScalingGroup> raw = services.statusReader().describeGroup(view, program.groupName());
        if (raw.isPresent() && applied(AwsGroupFacts.from(program.region(), raw.get()), program.config())) {
            return done("{\"alreadyApplied\":true}");
        }
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Raise the On-Demand share to 100 percent and keep your On-Demand base. Auto Scaling launches each "
                + "On-Demand replacement before it retires a Spot instance. Capacity, launch template, overrides "
                + "and Capacity Rebalancing are not touched.";
    }
}
