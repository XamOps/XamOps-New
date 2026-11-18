package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsRestoreProgram;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.util.Optional;

/**
 * The shared shell of a REVERT step: the revert inputs and one place that reads the live group. A revert step
 * never runs an eligibility, profile or desired-size check (DR-03); the only gates in front of it are the
 * switches the runner re-reads at every boundary.
 */
abstract class AwsRevertStep extends AwsStep {
    protected final AwsRestoreProgram.Inputs inputs;

    AwsRevertStep(AwsStepServices services, AwsProgram program, AwsRestoreProgram.Inputs inputs) {
        super(services, program);
        this.inputs = inputs;
    }

    /** The account, the read view and the live group, or the failure to return. */
    protected Live live() {
        Optional<CloudAccount> account = account();
        if (account.isEmpty()) {
            return new Live(null, null, null, failed(ErrorKind.VALIDATION, "ACCOUNT_MISSING",
                    "The connected account could not be found."));
        }
        AwsReadOnlyView view = services.view(account.get(), program.region());
        Optional<AutoScalingGroup> raw = services.statusReader().describeGroup(view, program.groupName());
        if (raw.isEmpty()) {
            return new Live(account.get(), view, null, failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND",
                    "The Auto Scaling group no longer exists."));
        }
        return new Live(account.get(), view, raw.get(), null);
    }

    /** What {@link #live()} found; {@code failure} is set when the step must stop. */
    protected record Live(CloudAccount account, AwsReadOnlyView view, AutoScalingGroup raw, StepOutcome.Failed failure) {
    }
}
