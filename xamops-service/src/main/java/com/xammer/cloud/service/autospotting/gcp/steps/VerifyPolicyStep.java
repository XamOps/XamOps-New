package com.xammer.cloud.service.autospotting.gcp.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Duration;
import java.util.Optional;

/**
 * Steps 2 and 3 of MIG_POLICY_UPDATE (design-gcp.md §3.5): {@code WAIT_PATCH_OPERATION} waits until the MIG shows the
 * saved policy, and {@code VERIFY_POLICY} reads it once more and requires every saved field. The PATCH being accepted
 * is not success. Read-only.
 */
public final class VerifyPolicyStep extends GcpStep {
    public static final String WAIT_NAME = "WAIT_PATCH_OPERATION";
    public static final String VERIFY_NAME = "VERIFY_POLICY";
    private final boolean verify;

    public VerifyPolicyStep(GcpStepServices services, GcpProgram program, boolean verify) {
        super(services, program);
        this.verify = verify;
    }

    @Override
    public String name() {
        return verify ? VERIFY_NAME : WAIT_NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return accountMissing();
        }
        Optional<GcpMigFacts> live = live(services.view(account));
        if (live.isEmpty()) {
            return groupMissing();
        }
        if (PatchPolicyStep.applied(live.get(), program.target().path("edit"))) {
            return done("{\"policyApplied\":true}");
        }
        return verify ? failed(ErrorKind.VALIDATION, "GCP_POLICY_NOT_APPLIED", "The instance group does not show the saved settings.")
                : new StepOutcome.InProgress(Duration.ofSeconds(5), null);
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that the instance group shows the saved rolling-update settings.";
    }
}
