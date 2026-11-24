package com.xammer.cloud.service.autospotting.gcp.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Duration;
import java.util.Optional;

/**
 * Step 9 (design-gcp.md §2.6, §9.3): database only. Runs after the rollout has completed and the policy is
 * restored: every registry row the MIG no longer references becomes RETIRED with a grace period, and a row the
 * MIG references is IN_USE. Nothing is deleted here, and the original customer template is never in the registry.
 * It refuses to retire anything while a rollout is still in flight. The group's own transition and
 * {@code desired_config} write are the runner's.
 */
public final class FinalizeStep extends GcpStep {
    public static final String NAME = "FINALIZE";

    public FinalizeStep(GcpStepServices services, GcpProgram program) {
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
        GcpMigFacts g = live.get();
        if (!g.versionTargetReached() || !g.stable()) {
            return new StepOutcome.InProgress(Duration.ofSeconds(15), null);
        }
        int changed = services.registry().retireUnreferenced(program.groupId(), g.templateLinks(), services.retention(),
                services.clock().instant());
        return done("{\"registryRowsChanged\":" + changed + "}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // idempotent: re-running is the probe
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Record which templates XamOps created are no longer used. Nothing is deleted.";
    }
}
