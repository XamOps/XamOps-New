package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsMutatingClient;
import com.xammer.cloud.service.autospotting.aws.AwsOwnedFields;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.aws.AwsUpdateRequestBuilder;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse;

import java.util.List;
import java.util.Optional;

/**
 * Step 3 (design-aws.md §2.3, §2.4): the one {@code UpdateAutoScalingGroup} that moves the group to
 * the mixed-instances policy. The request is the full target of the owned fields, so the step is
 * convergent: it first reads the group and, if the owned fields already match, makes no call (a retry
 * after a crash, or a completed attempt whose result was lost). It never starts an instance refresh
 * (the write client has no such method), and launch-before-terminate comes from the policy plus
 * Capacity Rebalancing, so Auto Scaling launches replacements before it retires old instances.
 * Asserts the lease before the call (§3.4).
 */
public final class UpdateDistributionStep extends AwsStep {

    public static final String NAME = "UPDATE_DISTRIBUTION";

    public UpdateDistributionStep(AwsStepServices services, AwsProgram program) {
        super(services, program);
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
        if (AwsOwnedFields.mismatches(live, program.config()).isEmpty()) {
            return done("{\"alreadyApplied\":true}");
        }
        UpdateAutoScalingGroupRequest request;
        try {
            request = AwsUpdateRequestBuilder.build(raw.get(), program.config());
        } catch (IllegalStateException e) {
            return failed(ErrorKind.VALIDATION, "TARGET_INVALID", "The target configuration cannot be applied to this group.");
        }
        List<String> forbidden = AwsUpdateRequestBuilder.forbiddenFields(request);
        boolean prior = "LAUNCH_TEMPLATE".equals(program.config().path("shape").asText());
        if (!forbidden.isEmpty() && !(prior && forbidden.equals(List.of("LaunchTemplate")))) {
            return failed(ErrorKind.VALIDATION, "REQUEST_NOT_OWNED_FIELDS",
                    "The update would change fields XamOps does not own: " + String.join(", ", forbidden) + ".");
        }
        ctx.leaseHandle().assertHeld(ctx.callTimeout());
        AwsMutatingClient client = services.mutating(account, program.region());
        UpdateAutoScalingGroupResponse response = client.updateAutoScalingGroup(request);
        String requestId = response.responseMetadata() == null ? null : response.responseMetadata().requestId();
        return new StepOutcome.Done("{\"updated\":true}", requestId == null ? List.of() : List.of(requestId));
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return notYet();
        }
        AwsReadOnlyView view = services.view(account, program.region());
        Optional<AutoScalingGroup> raw = services.statusReader().describeGroup(view, program.groupName());
        if (raw.isPresent() && AwsOwnedFields.mismatches(AwsGroupFacts.from(program.region(), raw.get()),
                program.config()).isEmpty()) {
            return done("{\"alreadyApplied\":true}");
        }
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return program.compensation()
                ? "Put the group's instance mix back to its previous configuration with one Auto Scaling update."
                : "Switch the group to a mixed On-Demand and Spot policy with one Auto Scaling update. It keeps "
                + "your On-Demand base, turns on Capacity Rebalancing, and launches each replacement before "
                + "an old instance is retired. Desired, minimum and maximum size are not touched.";
    }
}
