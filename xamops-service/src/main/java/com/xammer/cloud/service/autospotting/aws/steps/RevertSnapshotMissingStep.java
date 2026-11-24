package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

/**
 * The whole program of a revert whose saved configuration cannot be found or read. It fails before any change
 * ({@code first_mutation_seq} stays null, the group keeps its state, DR-04) and makes no AWS call, so nothing is
 * restored from a guess.
 */
public final class RevertSnapshotMissingStep extends AwsStep {
    public static final String NAME = "SNAPSHOT_MISSING";

    public RevertSnapshotMissingStep(AwsStepServices services, AwsProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        return failed(ErrorKind.VALIDATION, "SNAPSHOT_MISSING",
                "The saved original configuration of this group could not be read. An operator must restore it.");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Stop: the saved original configuration could not be read.";
    }
}
