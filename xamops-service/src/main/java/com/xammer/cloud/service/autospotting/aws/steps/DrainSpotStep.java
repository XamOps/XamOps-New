package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsMutatingClient;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsRestoreProgram;
import com.xammer.cloud.service.autospotting.aws.AwsRestoreRequests;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse;

import java.util.List;

/**
 * Step 1 of the REVERT program (design-aws.md §5.3): {@code OnDemandPercentageAboveBaseCapacity=100}, the base
 * unchanged. Raising the On-Demand share is a purchase-option change, so Auto Scaling replaces Spot with
 * On-Demand launch-first (AC-REV-06) and the group never drops below its capacity. Convergent: when the share is
 * already 100 and no Spot instance runs (a retry, or a group with no policy) no call is made. The plan refuses share
 * 100 with Spot still running (REVERT_SPOT_STUCK); if that state appears after the plan, the call is sent again. The step is left out of the program when the
 * snapshot itself had Spot. Asserts the lease before the call.
 */
public final class DrainSpotStep extends AwsRevertStep {
    public static final String NAME = "DRAIN_SPOT";

    public DrainSpotStep(AwsStepServices services, AwsProgram program, AwsRestoreProgram.Inputs inputs) {
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
        if (drained(facts) && !spotRunning(live)) {
            return done("{\"alreadyDrained\":true}");
        }
        // Share 100 with Spot still running after the plan was made (the plan blocks it, REVERT_SPOT_STUCK) re-sends the
        // drain rather than report it done; the wait step then bounds the operation.
        UpdateAutoScalingGroupRequest request = AwsRestoreRequests.drain(live.raw());
        List<String> forbidden = AwsRestoreRequests.forbidden(request);
        if (!forbidden.isEmpty()) {
            return failed(ErrorKind.VALIDATION, "REQUEST_NOT_OWNED_FIELDS",
                    "The update would change fields XamOps does not own: " + String.join(", ", forbidden) + ".");
        }
        ctx.leaseHandle().assertHeld(ctx.callTimeout());
        AwsMutatingClient client = services.mutating(live.account(), program.region());
        UpdateAutoScalingGroupResponse response = client.updateAutoScalingGroup(request);
        String requestId = response.responseMetadata() == null ? null : response.responseMetadata().requestId();
        return new StepOutcome.Done("{\"drained\":true}", requestId == null ? List.of() : List.of(requestId));
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        Live live = live();
        // The same test as doExecute: share 100 with Spot still running is not drained (review High #3).
        if (live.failure() == null && drained(AwsGroupFacts.from(program.region(), live.raw())) && !spotRunning(live)) {
            return done("{\"alreadyDrained\":true}");
        }
        return notYet();
    }

    /**
     * Whether Spot instances may still be in service. Read only when the share is already 100. A failed read, or a group
     * the observer no longer finds, counts as running: the drain stays open and is re-sent, and the step does not fail
     * on a read it only uses to skip a call (review Medium #5).
     */
    private boolean spotRunning(Live live) {
        if (!AwsGroupFacts.from(program.region(), live.raw()).mixedInstancesPolicy()) {
            return false;
        }
        try {
            return services.observer().observe(live.view(), program.groupName())
                    .map(snapshot -> snapshot.spotNow() > 0).orElse(true);
        } catch (com.xammer.cloud.service.autospotting.aws.AwsReadException e) {
            return true;
        }
    }

    /** A group with no mixed policy has no Spot share to remove. */
    private static boolean drained(AwsGroupFacts f) {
        return !f.mixedInstancesPolicy()
                || (f.onDemandPercentageAboveBase() != null && f.onDemandPercentageAboveBase() == 100);
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Raise the On-Demand share to 100 percent and keep your On-Demand base. Auto Scaling launches each "
                + "On-Demand replacement before it retires a Spot instance. Capacity, launch template and "
                + "overrides are not touched.";
    }
}
