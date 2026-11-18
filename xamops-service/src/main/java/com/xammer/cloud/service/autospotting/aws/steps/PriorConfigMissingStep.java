package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

/**
 * The only step of a PRIOR_CONFIG compensation program that has no prior configuration to restore.
 * It fails the leg without touching AWS, which ends the op {@code FAILED} for an operator (the
 * compensation could not run), instead of guessing a configuration to apply to a customer's group.
 */
public final class PriorConfigMissingStep extends AwsStep {

    public static final String NAME = "PRIOR_CONFIG_MISSING";

    public PriorConfigMissingStep(AwsStepServices services, AwsProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        return failed(ErrorKind.VALIDATION, "PRIOR_CONFIG_MISSING",
                "The previous configuration was not saved, so it cannot be restored automatically.");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Stop: there is no saved previous configuration to restore.";
    }
}
