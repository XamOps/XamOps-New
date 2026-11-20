package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsLaunchInfo;
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
 * Step 1 (design-aws.md §2.3, AC-AWS-06): simulate the write set and {@code iam:PassRole} for the
 * template's instance-profile role with {@code iam:PassedToService=ec2.amazonaws.com}. A denied action
 * ends the op {@code FAILED(PERMISSION_MISSING)} with the action list, before any change: this step
 * is read-only, and the program stops here, so no mutating call is ever made.
 */
public final class CheckPermissionsStep extends AwsStep {

    public static final String NAME = "CHECK_PERMISSIONS";

    public CheckPermissionsStep(AwsStepServices services, AwsProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        Optional<CloudAccount> account = account();
        if (account.isEmpty() || account.get().getRoleArn() == null) {
            return failed(ErrorKind.PERMISSION, "PERMISSION_MISSING",
                    "No connection role is stored for this account.");
        }
        AwsReadOnlyView view = services.view(account.get(), program.region());
        Optional<AutoScalingGroup> raw = services.statusReader().describeGroup(view, program.groupName());
        if (raw.isEmpty()) {
            return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The Auto Scaling group no longer exists.");
        }
        AwsGroupFacts facts = AwsGroupFacts.from(program.region(), raw.get());
        AwsLaunchInfo launch = facts.launchTemplate() == null ? null
                : services.reader().launch(account.get(), view, facts.launchTemplate());
        List<String> missing = services.reader().missingPermissions(view, account.get().getRoleArn(), launch);
        if (!missing.isEmpty()) {
            return failed(ErrorKind.PERMISSION, "PERMISSION_MISSING",
                    "The connection role is missing: " + String.join(", ", missing) + ".");
        }
        return done("{\"missing\":[]}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that the connection role may update the group, write its marker tag and pass the "
                + "instance-profile role to EC2. Nothing is changed.";
    }
}
