package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsOwnedFields;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.util.List;
import java.util.Optional;

/**
 * Step 5 (design-aws.md §2.3): read the group again and confirm the owned fields equal the target.
 * A mismatch means someone changed the group during the rollout (DR-2): {@code Failed(VALIDATION)},
 * which the runner compensates. Read-only. The marker is written only after this passes.
 */
public final class VerifyTargetStep extends AwsStep {

    public static final String NAME = "VERIFY_TARGET";

    public VerifyTargetStep(AwsStepServices services, AwsProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
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
        List<String> bad = AwsOwnedFields.mismatches(AwsGroupFacts.from(program.region(), raw.get()), program.config());
        if (!bad.isEmpty()) {
            return failed(ErrorKind.VALIDATION, "TARGET_MISMATCH",
                    "The group no longer matches the planned configuration (" + String.join(", ", bad) + ").");
        }
        return done("{\"verified\":true}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Read the group again and confirm it matches the planned configuration. Nothing is changed.";
    }
}
