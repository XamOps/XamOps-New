package com.xammer.cloud.service.autospotting.gcp.steps;

import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

/** A revert with no saved original fails safely before any change: nothing is guessed. */
public final class RevertSnapshotMissingStep extends GcpStep {
    public static final String NAME = "REVERT_SNAPSHOT_MISSING";

    public RevertSnapshotMissingStep(GcpStepServices services, GcpProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        return failed(ErrorKind.VALIDATION, "REVERT_SNAPSHOT_MISSING",
                "The original configuration was not saved for this group, so it cannot be restored. Nothing was changed.");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Nothing: the original configuration could not be found.";
    }
}
