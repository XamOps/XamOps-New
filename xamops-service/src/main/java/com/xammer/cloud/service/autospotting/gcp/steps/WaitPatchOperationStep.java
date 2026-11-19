package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpRolloutPolicy;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.gcp.GcpTargets;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Duration;
import java.util.Optional;

/**
 * Step 5 (design-gcp.md §2.6): the PATCH is only accepted intent, so this step waits until the MIG itself shows the
 * target versions and the XamOps rollout policy. Reading the MIG, not the Operation, keeps the step stateless and
 * crash-safe. It is NOT the success test: the rollout wait (step 6) is. Read-only.
 */
public final class WaitPatchOperationStep extends GcpStep {
    public static final String NAME = "WAIT_PATCH_OPERATION";
    static final Duration POLL = Duration.ofSeconds(5);

    public WaitPatchOperationStep(GcpStepServices services, GcpProgram program) {
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
            return accountMissing();
        }
        Optional<GcpMigFacts> live = live(services.view(account));
        if (live.isEmpty()) {
            return groupMissing();
        }
        ArrayNode versions = targetVersions(live.get());
        if (versions == null) {
            return failed(ErrorKind.VALIDATION, "TEMPLATE_NOT_REGISTERED", "The Spot template was not registered.");
        }
        boolean shown = GcpTargets.matches(live.get(), versions)
                && GcpTargets.policyHas(live.get().updatePolicy(), GcpRolloutPolicy.xamopsPolicy(live.get()));
        return shown ? done("{\"accepted\":true}") : new StepOutcome.InProgress(POLL, null);
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Wait until the instance group shows the new template and rollout settings.";
    }
}
