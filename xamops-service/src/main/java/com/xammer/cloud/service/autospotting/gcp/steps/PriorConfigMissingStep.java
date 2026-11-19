package com.xammer.cloud.service.autospotting.gcp.steps;

import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

/** A compensation whose prior configuration cannot be found fails safely: it changes nothing and ends the op FAILED. */
public final class PriorConfigMissingStep extends GcpStep {
    public static final String NAME = "PRIOR_CONFIG_MISSING";

    public PriorConfigMissingStep(GcpStepServices services, GcpProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        return failed(ErrorKind.VALIDATION, "PRIOR_CONFIG_MISSING", "The previous configuration could not be found, so it was not restored.");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Nothing: the previous configuration could not be found.";
    }
}
