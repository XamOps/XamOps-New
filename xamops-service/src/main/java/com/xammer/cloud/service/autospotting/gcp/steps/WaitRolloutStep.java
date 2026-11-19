package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadException;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.gcp.GcpTargets;
import com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Step 6 (design-gcp.md §4): the rollout wait, and the only place success is decided. Read-only. Each poll reads the
 * MIG, {@code listManagedInstances} and {@code listErrors} and hands them to {@link GcpVersionTargetEvaluator}:
 * Done only when {@code versionTarget.isReached} AND the MIG is stable on two polls 30 s apart, every instance runs
 * its version's template, the floor holds and every desired instance is healthy. An accepted PATCH or a DONE
 * Operation is never success. A capacity or health stall fails a forward (PROGRESS) rollout so the runner
 * compensates; the compensation leg is lenient and keeps waiting. The poll state rides in the op's progress JSON.
 * Polling: 15 s for 5 min, 30 s to 30 min, then 60 s. Time alone never fails the step (the core's deadline is STUCK).
 */
public final class WaitRolloutStep extends GcpStep {
    public static final String NAME = "WAIT_ROLLOUT";

    public WaitRolloutStep(GcpStepServices services, GcpProgram program) {
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
        List<JsonNode> errors = List.of();
        try {
            errors = view.reads().listErrors(g.regional(), g.location(), g.name());
        } catch (GcpReadException e) {
            // listErrors only explains a stall; the rollout test stands without it.
        }
        Instant now = services.clock().instant();
        GcpVersionTargetEvaluator.Observation o = GcpVersionTargetEvaluator.Observation.of(now, g, managed, errors);
        GcpVersionTargetEvaluator.Target target = new GcpVersionTargetEvaluator.Target(GcpTargets.expected(versions),
                GcpTargets.floorOf(versions));
        GcpVersionTargetEvaluator.State prior = GcpVersionTargetEvaluator.State.fromJson(
                services.facts().operationProgress(program.operationId()).orElse(null), now);
        GcpVersionTargetEvaluator.Verdict v = services.rollout().evaluate(o, target, prior,
                program.compensation() ? GcpVersionTargetEvaluator.Mode.LENIENT : GcpVersionTargetEvaluator.Mode.PROGRESS);
        return switch (v) {
            case GcpVersionTargetEvaluator.Done d -> done(GcpVersionTargetEvaluator.progressJson(o, target, d.state(), "Done"));
            case GcpVersionTargetEvaluator.Waiting w -> new StepOutcome.InProgress(pollFor(w.state(), now),
                    GcpVersionTargetEvaluator.progressJson(o, target, w.state(), w.message()));
            case GcpVersionTargetEvaluator.Failed f -> new StepOutcome.Failed(f.kind(), f.code(), messageFor(f.code()));
        };
    }

    static Duration pollFor(GcpVersionTargetEvaluator.State s, Instant now) {
        Duration age = Duration.between(s.startedAt(), now);
        return age.compareTo(Duration.ofMinutes(5)) < 0 ? Duration.ofSeconds(15)
                : age.compareTo(Duration.ofMinutes(30)) < 0 ? Duration.ofSeconds(30) : Duration.ofSeconds(60);
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // every poll is a fresh read; execute is the probe
    }

    private static String messageFor(String code) {
        return GcpVersionTargetEvaluator.CAPACITY_STALL.equals(code)
                ? "Spot capacity was not available in time. Restoring the previous configuration."
                : "New instances did not become healthy. Restoring the previous configuration.";
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Watch the rollout. It is done only when every instance runs its target template, the group is stable "
                + "and every desired instance is healthy. If Spot capacity is not available or new instances stay "
                + "unhealthy, the previous configuration is restored.";
    }
}
