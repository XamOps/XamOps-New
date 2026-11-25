package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsFingerprint;
import com.xammer.cloud.service.autospotting.aws.AwsMaintenancePolicy;
import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.FallbackSteps;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.time.Duration;
import java.util.Optional;

/**
 * Step 2 of FALLBACK_ENTER (design-aws.md §6.4 step 3): the describe shows
 * {@code OnDemandPercentageAboveBaseCapacity=100}. The output carries the live managed-field fingerprint; the
 * runner stores it as {@code fallback_fingerprint} and moves the group to FALLBACK (or keeps it PAUSED with
 * {@code paused_from_state=FALLBACK}), sets {@code config_mode=FALLBACK} and writes the FALLBACK_ENTERED audit
 * row, all in one fenced transaction with {@code effective_at} equal to the mutation step's {@code intent_at}.
 * The group is in FALLBACK whether or not On-Demand capacity is there yet. Read-only.
 */
public final class VerifyFallbackAppliedStep extends AwsStep {
    public VerifyFallbackAppliedStep(AwsStepServices services, AwsProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return FallbackSteps.VERIFY_FALLBACK_APPLIED;
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
        if (!FallbackUpdateStep.applied(live, program.config())) {
            // The write may not be visible yet; poll again. The op deadline bounds this.
            return new StepOutcome.InProgress(Duration.ofSeconds(15), "{\"waiting\":\"FALLBACK_NOT_VISIBLE\"}");
        }
        return done("{\"" + FallbackSteps.FINGERPRINT + "\":\"" + AwsFingerprint.of(live, AwsMaintenancePolicy.owns(program.config())) + "\"}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Read the group again and confirm the On-Demand share is 100 percent. Nothing is changed.";
    }
}
