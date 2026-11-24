package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.gcp.GcpTargets;
import com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Step 7 (design-gcp.md §2.6): after the rollout wait, one more full read of the success test: every managed
 * instance on its version's template, nothing outside the target set, the floor held. It repeats the §4.4 test at the
 * current instant (the stable pair was already proven by step 6) and never reports success on weaker evidence.
 * Read-only.
 */
public final class VerifyInstancesStep extends GcpStep {
    public static final String NAME = "VERIFY_INSTANCES";

    public VerifyInstancesStep(GcpStepServices services, GcpProgram program) {
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
        GcpReadOnlyView view = services.view(account);
        Optional<GcpMigFacts> live = live(view);
        if (live.isEmpty()) {
            return groupMissing();
        }
        GcpMigFacts g = live.get();
        ArrayNode versions = targetVersions(g);
        if (versions == null) {
            return failed(ErrorKind.VALIDATION, "TEMPLATE_NOT_REGISTERED", "The Spot template was not registered.");
        }
        List<JsonNode> managed = view.reads().listManagedInstances(g.regional(), g.location(), g.name());
        Instant now = services.clock().instant();
        GcpVersionTargetEvaluator.Observation o = GcpVersionTargetEvaluator.Observation.of(now, g, managed, List.of());
        GcpVersionTargetEvaluator.Target target = new GcpVersionTargetEvaluator.Target(GcpTargets.expected(versions),
                GcpTargets.floorOf(versions));
        GcpVersionTargetEvaluator.State asStable = new GcpVersionTargetEvaluator.State(now, now, -1,
                now.minus(GcpVersionTargetEvaluator.STABLE_GAP));
        GcpVersionTargetEvaluator.Verdict v = services.rollout().evaluate(o, target, asStable,
                GcpVersionTargetEvaluator.Mode.LENIENT);
        return v instanceof GcpVersionTargetEvaluator.Done ? done("{\"verified\":true,\"instances\":" + managed.size() + "}")
                : new StepOutcome.InProgress(java.time.Duration.ofSeconds(15), null);
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that every instance runs the template it should. Nothing is changed.";
    }
}
