package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsOwnedFields;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsRestoreProgram;
import com.xammer.cloud.service.autospotting.aws.AwsRolloutObserver;
import com.xammer.cloud.service.autospotting.aws.AwsRolloutProperties;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.aws.AwsThreeWayDiff;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.Optional;

/**
 * Step 4 of the REVERT program (design-aws.md §5.3, AC-REV-05): read-only. The owned fields equal the merged
 * target and healthy in-service instances are at least the desired count. A drain-only revert is verified
 * against its own target (On-Demand share 100, no Spot) and ends REVERTED with DR-3 context drift recorded, because
 * the group keeps the mixed policy XamOps set. Healthy but not yet enough: wait and look again.
 */
public final class VerifyRevertedStep extends AwsRevertStep {
    public static final String NAME = "VERIFY_REVERTED";

    public VerifyRevertedStep(AwsStepServices services, AwsProgram program, AwsRestoreProgram.Inputs inputs) {
        super(services, program, inputs);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        Live live = live();
        if (live.failure() != null) {
            return live.failure();
        }
        AwsGroupFacts facts = AwsGroupFacts.from(program.region(), live.raw());
        String note = "";
        if (inputs.mode() == AwsRestoreProgram.Mode.DRAIN_ONLY) {
            boolean drained = facts.mixedInstancesPolicy() && facts.onDemandPercentageAboveBase() != null
                    && facts.onDemandPercentageAboveBase() == 100;
            if (!drained) {
                return mismatch();
            }
            note = ",\"exact\":false,\"drift\":\"DR-3\",\"severity\":\"MINOR\",\"reasons\":" + reasonsJson();
        } else {
            AwsThreeWayDiff.Target target = AwsRestoreProgram.resolve(services, live.account(), live.view(),
                    live.raw(), inputs).diff().target();
            java.util.List<String> bad = AwsOwnedFields.mismatches(facts, target.toNode());
            if (!bad.isEmpty()) {
                // The fallback is accepted only when the policy is restored too (an absent one cleared, AD-08).
                if (bad.contains("instanceMaintenancePolicy") || !unwrapFallback(facts, target)) {
                    return mismatch();
                }
                note = ",\"exact\":false,\"reason\":\"UNWRAP_UNSUPPORTED\"";
            }
        }
        Optional<AwsRolloutObserver.Snapshot> snap = services.observer().observe(live.view(), program.groupName());
        if (snap.isEmpty()) {
            return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The Auto Scaling group no longer exists.");
        }
        AwsRolloutObserver.Snapshot s = snap.get();
        if (s.healthyInService() < s.desired()) {
            return new StepOutcome.InProgress(AwsRolloutProperties.POLL, "{\"message\":\"Healthy instances: "
                    + s.healthyInService() + " of " + s.desired() + "\",\"healthyInService\":" + s.healthyInService()
                    + ",\"desired\":" + s.desired() + "}");
        }
        return done("{\"verified\":true,\"healthyInService\":" + s.healthyInService() + note + "}");
    }

    private static StepOutcome mismatch() {
        return failed(ErrorKind.VALIDATION, "REVERT_MISMATCH",
                "The group does not match the restored configuration after the update.");
    }

    /** The single-override policy of the unwrap fallback: base 0, share 100, the snapshot's Capacity Rebalancing. */
    private static boolean unwrapFallback(AwsGroupFacts f, AwsThreeWayDiff.Target target) {
        return target.standalone() && f.mixedInstancesPolicy() && f.overrideTypes().size() == 1
                && f.onDemandBase() != null && f.onDemandBase() == 0 && f.onDemandPercentageAboveBase() != null
                && f.onDemandPercentageAboveBase() == 100 && f.capacityRebalance() == target.capacityRebalance();
    }

    private String reasonsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < inputs.planReasons().size(); i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(inputs.planReasons().get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that the group has the restored configuration and that every desired instance is healthy "
                + "and in service. Nothing is changed.";
    }
}
