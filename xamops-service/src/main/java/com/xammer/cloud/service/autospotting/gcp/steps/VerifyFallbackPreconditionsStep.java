package com.xammer.cloud.service.autospotting.gcp.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.Optional;

/**
 * Step 0 of FALLBACK_ENTER (design-gcp.md §7.3): fallback is SAFETY, so only what it needs to act: the MIG exists
 * and the original (standard) template can be named. It never waits for a foreign rollout, never re-runs the
 * conversion eligibility and has no plan hash (a system op has no plan). Read-only.
 */
public final class VerifyFallbackPreconditionsStep extends GcpStep {
    public static final String NAME = "VERIFY_PRECONDITIONS";

    public VerifyFallbackPreconditionsStep(GcpStepServices services, GcpProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        Optional<CloudAccount> account = account();
        if (account.isEmpty()) {
            return accountMissing();
        }
        Optional<GcpMigFacts> live = live(services.view(account.get()));
        if (live.isEmpty()) {
            return groupMissing();
        }
        if (originalTemplate(live.get()) == null) {
            return failed(ErrorKind.VALIDATION, "ORIGINAL_TEMPLATE_MISSING", "The original template could not be determined.");
        }
        return done("{\"originalTemplate\":true}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that the instance group exists and that its original template is known. Nothing is changed.";
    }
}
