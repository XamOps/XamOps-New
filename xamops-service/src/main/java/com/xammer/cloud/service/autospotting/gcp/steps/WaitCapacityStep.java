package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.core.FallbackProperties;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadException;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.gcp.GcpTargets;
import com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.FallbackSteps;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The tail wait of FALLBACK_ENTER (design-gcp.md §7.3 step 4, DR-24): it runs until the §4.4 success test passes, so
 * the customer's update policy is restored only once the rollout has finished. Read-only. Each poll reports
 * {@code capacity=OK|SHORT} and, once the group has been short for the still-short window (20 min) since the wait
 * began, {@code degrade=true}: the runner applies FALLBACK to DEGRADED and back, so the provider never sets a state.
 * It never blocks anything that matters: a REVERT preempts the op at any boundary.
 *
 * <p>The wait is bounded by {@code autospotting.gcp.fallback.tail-wait-max} (2 h, well short of the 6 h deadline).
 * At the bound the step ends with {@code outcome=CAPACITY_PENDING}; the next step then skips the restore of the
 * customer's policy, the op ends SUCCEEDED, and the reconciler classifies drift and health again. The MIG keeps
 * creating standard VMs under the XamOps surge-first policy until a later exit or revert, both of which end with
 * RESTORE_UPDATE_POLICY.
 */
public final class WaitCapacityStep extends GcpStep {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FallbackProperties props;

    public WaitCapacityStep(GcpStepServices services, GcpProgram program, FallbackProperties props) {
        super(services, program);
        this.props = props;
    }

    @Override
    public String name() {
        return FallbackSteps.WAIT_CAPACITY;
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
            return failed(ErrorKind.VALIDATION, "ORIGINAL_TEMPLATE_MISSING", "The original template could not be determined.");
        }
        List<JsonNode> managed = view.reads().listManagedInstances(g.regional(), g.location(), g.name());
        List<JsonNode> errors = List.of();
        try {
            errors = view.reads().listErrors(g.regional(), g.location(), g.name());
        } catch (GcpReadException e) {
            // listErrors only explains a stall
        }
        Instant now = services.clock().instant();
        GcpVersionTargetEvaluator.Observation o = GcpVersionTargetEvaluator.Observation.of(now, g, managed, errors);
        GcpVersionTargetEvaluator.Target target = new GcpVersionTargetEvaluator.Target(GcpTargets.expected(versions), 0);
        JsonNode prior = prior();
        GcpVersionTargetEvaluator.State state = GcpVersionTargetEvaluator.State.fromJson(prior.toString(), now);
        GcpVersionTargetEvaluator.Verdict verdict = services.rollout().evaluate(o, target, state,
                GcpVersionTargetEvaluator.Mode.LENIENT);
        GcpVersionTargetEvaluator.State next = switch (verdict) {
            case GcpVersionTargetEvaluator.Done d -> d.state();
            case GcpVersionTargetEvaluator.Waiting w -> w.state();
            case GcpVersionTargetEvaluator.Failed f -> f.state();
        };
        Instant startedAt = instant(prior.path("tail"), "startedAt");
        if (startedAt == null) {
            startedAt = now;
        }
        int running = (int) managed.stream().filter(m -> "RUNNING".equals(m.path("instanceStatus").asText())
                && !"DELETING".equals(m.path("currentAction").asText("NONE"))
                && !"ABANDONING".equals(m.path("currentAction").asText("NONE"))).count();
        boolean ok = running >= g.targetSize();
        Instant shortSince = ok ? null : (instant(prior.path("tail"), "shortSince") == null ? now
                : instant(prior.path("tail"), "shortSince"));
        boolean degrade = !ok && !startedAt.plus(props.stillShort()).isAfter(now);
        boolean bound = !startedAt.plus(props.tailWaitMax()).isAfter(now);
        ObjectNode out;
        try {
            out = (ObjectNode) JSON.readTree(GcpVersionTargetEvaluator.progressJson(o, target, next, "tail wait"));
        } catch (java.io.IOException e) {
            out = JSON.createObjectNode();
        }
        ObjectNode tail = out.putObject("tail");
        tail.put("startedAt", startedAt.toString());
        tail.put("shortSince", shortSince == null ? null : shortSince.toString());
        out.put(FallbackSteps.CAPACITY, ok ? FallbackSteps.OK : FallbackSteps.SHORT);
        out.put(FallbackSteps.SHORT_SINCE, shortSince == null ? null : shortSince.toString());
        out.put("degrade", degrade);
        out.put("running", running);
        out.put("desired", g.targetSize());
        if (verdict instanceof GcpVersionTargetEvaluator.Done) {
            return done(out.toString());
        }
        if (bound) {
            out.put("outcome", "CAPACITY_PENDING");
            return done(out.toString());
        }
        return new StepOutcome.InProgress(WaitRolloutStep.pollFor(next, now), out.toString());
    }

    private JsonNode prior() {
        String json = services.facts().operationProgress(program.operationId()).orElse(null);
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static Instant instant(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        if (v == null || v.isBlank() || "null".equals(v)) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // every poll is a fresh read; execute is the probe
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Watch the instance group until every instance runs your original template and is healthy, for up to "
                + props.tailWaitMax().toMinutes() + " minutes. Nothing is changed.";
    }
}
