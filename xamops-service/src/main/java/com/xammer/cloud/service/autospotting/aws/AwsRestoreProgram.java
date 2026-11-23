package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.aws.steps.DrainSpotStep;
import com.xammer.cloud.service.autospotting.aws.steps.RemoveMarkerStep;
import com.xammer.cloud.service.autospotting.aws.steps.RestoreShapeStep;
import com.xammer.cloud.service.autospotting.aws.steps.RevertSnapshotMissingStep;
import com.xammer.cloud.service.autospotting.aws.steps.VerifyRevertPreconditionsStep;
import com.xammer.cloud.service.autospotting.aws.steps.VerifyRevertedStep;
import com.xammer.cloud.service.autospotting.aws.steps.WaitSpotDrainedStep;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult.Check;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The AWS REVERT program and its plan (design-aws.md §5.3, §5.4, plan-phase-19.md). Replaces the Phase 18 stub:
 * a REVERT op and the SNAPSHOT compensation leg of an ENABLE both restore the stored snapshot through it.
 *
 * <pre>
 *  RESTORE        0 VERIFY_PRECONDITIONS  1 DRAIN_SPOT  2 WAIT_SPOT_DRAINED  3 RESTORE_SHAPE  4 VERIFY_REVERTED
 *                 5 REMOVE_MARKER  (6 transition: the runner)
 *                 steps 1 and 2 are left out when the snapshot itself had Spot
 *  DRAIN_ONLY     0, 1, 2, 4, 5: the closest safe target when an exact restore is impossible (no PassRole)
 *  RESTORE_ONLY   0, 3, 4, 5: GR-02, an exact revert after a drain-only revert (P is already 100)
 * </pre>
 *
 * The program is derived from immutable inputs (the op, the stored snapshot, the group row), so any instance
 * re-derives the same steps to resume. The group enters REVERTING only with the first mutating step (the runner),
 * so a revert that fails in step 0 leaves the group in its state (DR-04). Nothing here runs an eligibility,
 * profile or desired-size check (DR-03).
 */
@Component
public class AwsRestoreProgram implements AwsRevertProgramFactory {
    /** The persisted-seq base of a compensation leg (core {@code OperationRunner.COMPENSATION_SEQ_BASE}). */
    static final int COMPENSATION_SEQ_BASE = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** Which revert the plan chose. {@code RESTORE} drains first; {@code RESTORE_ONLY} is GR-02; {@code DRAIN_ONLY} needs no PassRole. */
    public enum Mode { RESTORE, DRAIN_ONLY, RESTORE_ONLY }

    /**
     * The immutable inputs of the steps: the mode, whether this is a compensation leg, the stored snapshot
     * payload, what XamOps set ({@code desired}, the enable target for a compensation), {@code accepted_fields},
     * and the reasons the plan gave for a drain-only target.
     */
    public record Inputs(Mode mode, boolean compensation, String snapshotPayload, JsonNode desired, JsonNode accepted,
                         List<String> planReasons) {
        public Inputs {
            planReasons = planReasons == null ? List.of() : List.copyOf(planReasons);
        }
    }

    /** The three-way result for the live group now. */
    public record Resolved(AwsThreeWayDiff.Result diff) {
    }

    private final AwsStepServices services;
    private final GroupLookup groups;

    public AwsRestoreProgram(AwsStepServices services, GroupLookup groups) {
        this.services = services;
        this.groups = groups;
    }

    // ---- program ----

    @Override
    public StepProgram program(OperationContext op, SnapshotPayload liveSnapshot) {
        SpotGroup g = op.group();
        boolean compensation = op.phase() == OperationPhase.COMPENSATING;
        JsonNode target = parse(op.targetConfigJson());
        Mode mode = compensation ? Mode.RESTORE : modeOf(target);
        String[] parts = AwsStepLibrary.split(g.getGroupKey());
        AwsProgram p = new AwsProgram(op.operationId(), op.kind(), g.getId(), g.getCloudAccountId(), g.getGroupKey(),
                parts[0], parts[1], op.planId(), compensation || target == null ? JSON.createObjectNode() : target,
                compensation);
        String payload = payloadOf(g, liveSnapshot);
        Optional<AwsSnapshotCodec.Owned> owned = AwsSnapshotCodec.decodeOwned(payload);
        if (owned.isEmpty()) {
            return new StepProgram(List.of(new RevertSnapshotMissingStep(services, p)));
        }
        JsonNode desired = compensation ? target
                : mergeDesired(parse(g.getDesiredConfig()), parse(services.facts().latestConversionTarget(g.getId())
                        .orElse(null)));
        Inputs in = new Inputs(mode, compensation, payload, desired, parse(g.getAcceptedFields()), reasonsOf(target));
        boolean legacy = AwsSnapshotCodec.CAPTURED_RECONSTRUCTED.equals(owned.get().capturedFrom());
        boolean drain = mode != Mode.RESTORE_ONLY
                && (mode == Mode.DRAIN_ONLY || legacy || !AwsThreeWayDiff.snapshotHadSpot(owned.get()));
        List<Step> steps = new ArrayList<>();
        steps.add(new VerifyRevertPreconditionsStep(services, p, in));
        if (drain) {
            steps.add(new DrainSpotStep(services, p, in));
            steps.add(new WaitSpotDrainedStep(services, p, in));
        }
        if (mode != Mode.DRAIN_ONLY) {
            steps.add(new RestoreShapeStep(services, p, in));
        }
        steps.add(new VerifyRevertedStep(services, p, in));
        steps.add(new RemoveMarkerStep(services, p, in, (compensation ? COMPENSATION_SEQ_BASE : 0) + steps.size()));
        return new StepProgram(steps);
    }

    /** The stored snapshot linked to the group; a legacy group with none uses the reconstruction just made. */
    private String payloadOf(SpotGroup g, SnapshotPayload liveSnapshot) {
        Optional<AwsSnapshotStore.Stored> stored = services.snapshots().findLinked(g.getId());
        if (stored.isPresent()) {
            return stored.get().payload();
        }
        if (liveSnapshot != null && liveSnapshot.payloadJson() != null
                && liveSnapshot.payloadJson().contains("\"capturedFrom\":\"" + AwsSnapshotCodec.CAPTURED_RECONSTRUCTED + "\"")) {
            return liveSnapshot.payloadJson();
        }
        return null;
    }

    /**
     * What XamOps set: {@code desired_config}, plus the instance types of the latest conversion target. The
     * second matters when a revert preempts a conversion that has not finished: the cloud already holds its
     * types (and, when it sent it, the maintenance policy XamOps owns), but {@code desired_config} is written only on
     * success.
     */
    static JsonNode mergeDesired(JsonNode desired, JsonNode latestTarget) {
        if (latestTarget == null || !latestTarget.isObject()) {
            return desired;
        }
        if (desired == null || !desired.isObject()) {
            return latestTarget;
        }
        ObjectNode merged = desired.deepCopy();
        if (AwsMaintenancePolicy.owns(latestTarget) && !AwsMaintenancePolicy.owns(desired)) {
            merged.set(AwsMaintenancePolicy.KEY, latestTarget.get(AwsMaintenancePolicy.KEY).deepCopy());
        }
        ArrayNode types = merged.putArray("overrides");
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode source : List.of(desired, latestTarget)) {
            source.path("overrides").forEach(n -> {
                if (seen.add(n.asText())) {
                    types.add(n.asText());
                }
            });
        }
        return merged;
    }

    static Mode modeOf(JsonNode target) {
        String m = target == null ? null : target.path("mode").asText(null);
        if (m != null) {
            try {
                return Mode.valueOf(m);
            } catch (IllegalArgumentException ignored) {
                // an unknown mode is the full restore, the default
            }
        }
        return Mode.RESTORE;
    }

    private static List<String> reasonsOf(JsonNode target) {
        List<String> out = new ArrayList<>();
        if (target != null && target.path("reasons").isArray()) {
            target.get("reasons").forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    /** The three-way check for the live group, with the snapshot template's existence read from AWS. */
    public static Resolved resolve(AwsStepServices services, CloudAccount account, AwsReadOnlyView view,
                                   AutoScalingGroup raw, Inputs in) {
        AwsSnapshotCodec.Owned owned = AwsSnapshotCodec.decodeOwned(in.snapshotPayload())
                .orElseThrow(() -> new IllegalStateException("snapshot payload is not readable"));
        boolean gone = false;
        if (!AwsSnapshotCodec.CAPTURED_RECONSTRUCTED.equals(owned.capturedFrom())) {
            gone = Boolean.FALSE.equals(services.templateVersionExists(view,
                    AwsThreeWayDiff.snapshotTemplate(owned)));
        }
        return new Resolved(AwsThreeWayDiff.compute(owned, in.desired(), raw, in.accepted(), gone));
    }

    // ---- plan ----

    @Override
    public PlanBody plan(PlanRequest req, CloudAccount account) {
        String[] parts = AwsEligibilityEvaluator.split(req.ref().groupKey());
        AwsReadOnlyView view = services.view(account, parts[0]);
        Optional<AutoScalingGroup> found = services.statusReader().describeGroup(view, parts[1]);
        if (found.isEmpty()) {
            return blocked(null, check("GROUP_NOT_FOUND", "The Auto Scaling group no longer exists."));
        }
        AutoScalingGroup raw = found.get();
        AwsGroupFacts live = AwsGroupFacts.from(parts[0], raw);
        SpotGroup row = groups.live(Provider.AWS, account.getId(), req.ref().groupKey()).orElse(null);
        Mode mode = row != null && row.getState() == GroupState.REVERTED ? Mode.RESTORE_ONLY : Mode.RESTORE;
        Optional<AwsSnapshotStore.Stored> stored = row == null ? Optional.empty()
                : services.snapshots().findLinked(row.getId());
        String payload = stored.map(AwsSnapshotStore.Stored::payload).orElse(null);
        boolean storedExact = stored.map(AwsSnapshotStore.Stored::exact).orElse(false);
        List<String> payloadReasons = new ArrayList<>();
        if (payload == null && live.hasLegacyTag() && !live.hasMarker()) {
            SnapshotPayload rebuilt = AwsSnapshotCodec.encode(parts[0], raw, AwsSnapshotCodec.Context.none(), true);
            payload = rebuilt.payloadJson();
            storedExact = false;
            payloadReasons.addAll(rebuilt.inexactReasons());
        }
        Optional<AwsSnapshotCodec.Owned> owned = AwsSnapshotCodec.decodeOwned(payload);
        if (owned.isEmpty()) {
            return blocked(raw, check("SNAPSHOT_MISSING", "The saved original configuration of this group could not be read."));
        }
        JsonNode desired = row == null ? null : mergeDesired(parse(row.getDesiredConfig()),
                parse(services.facts().latestConversionTarget(row.getId()).orElse(null)));
        Inputs in = new Inputs(mode, false, payload, desired, parse(row == null ? null : row.getAcceptedFields()), List.of());
        AwsThreeWayDiff.Result diff = resolve(services, account, view, raw, in).diff();
        AwsGroupFacts.LtSpec sent = diff.target().template();
        AwsLaunchInfo launch = null;
        if (sent != null) {
            try {
                launch = services.reader().launch(account, view, sent);
            } catch (AwsReadException e) {
                launch = null; // unknown template details: no PassRole target to check
            }
        }
        AwsPlanReader.RevertPermissions perm = account.getRoleArn() == null
                ? new AwsPlanReader.RevertPermissions(List.of("autoscaling:UpdateAutoScalingGroup"), false, false)
                : services.reader().revertPermissions(view, account.getRoleArn(), live.arn(), launch);

        List<Check> blockers = new ArrayList<>();
        List<Check> warnings = new ArrayList<>();
        Set<String> reasons = new LinkedHashSet<>();
        boolean drainOnly = false;
        if (!perm.missing().isEmpty()) {
            blockers.add(check("PERMISSION_MISSING", "The connection role is missing: "
                    + String.join(", ", perm.missing()) + ". Update the stack to allow it."));
        } else if (perm.passRoleDenied() || perm.checkFailed()) {
            String id = perm.passRoleDenied() ? "REVERT_NEEDS_PASSROLE" : "REVERT_CHECK_FAILED";
            reasons.add(perm.passRoleDenied() ? "PASSROLE_DENIED" : "CHECK_FAILED");
            if (mode == Mode.RESTORE_ONLY) {
                blockers.add(Check.of(id, id, "The exact revert still needs iam:PassRole. Update the stack, then plan again."));
            } else {
                drainOnly = true;
                mode = Mode.DRAIN_ONLY;
                warnings.add(Check.of(id, perm.passRoleDenied() ? id : "CHECK_FAILED", perm.passRoleDenied()
                        ? "The connection role cannot pass the instance-profile role to EC2, so the exact original "
                        + "cannot be restored. Update the stack to allow it. Until then XamOps can only drain Spot: "
                        + "the group keeps its mixed policy at 100% On-Demand, and you can revert again afterwards "
                        + "for the exact original."
                        : "XamOps could not check the permissions for an exact revert. It can drain Spot only: the "
                        + "group keeps its mixed policy at 100% On-Demand, and you can revert again later for the "
                        + "exact original."));
            }
        }
        if (!drainOnly) {
            reasons.addAll(diff.inexactReasons());
            for (String reason : diff.inexactReasons()) {
                warnings.add(Check.of(reason, reason, reasonText(reason, diff)));
            }
            for (String ack : diff.acks()) {
                if (warnings.stream().noneMatch(w -> w.id().equals(ack))) {
                    warnings.add(Check.of(ack, ack, reasonText(ack, diff)));
                }
            }
            if (!storedExact && !reasons.contains(AwsThreeWayDiff.LEGACY_RECONSTRUCTED)) {
                reasons.add("SNAPSHOT_NOT_EXACT");
                warnings.add(Check.of("SNAPSHOT_NOT_EXACT", "SNAPSHOT_NOT_EXACT",
                        "The saved original configuration is not complete, so the restore is the closest match."));
            }
        }
        // Legacy ids carry their own text; a reason with an ack of the same id is listed once.
        List<Check> unique = new ArrayList<>();
        for (Check w : warnings) {
            if (unique.stream().noneMatch(u -> u.id().equals(w.id()))) {
                unique.add(w);
            }
        }
        boolean exact = !drainOnly && reasons.isEmpty();
        ObjectNode current = currentNode(live);
        ObjectNode target = drainOnly ? drained(current) : diff.target().toNode();
        target.put("mode", mode.name());
        target.put("exact", exact);
        ArrayNode reasonNodes = target.putArray("reasons");
        reasons.forEach(reasonNodes::add);
        ArrayNode kept = target.putArray("kept");
        if (!drainOnly) {
            for (AwsThreeWayDiff.Kept k : diff.kept()) {
                kept.addObject().put("field", k.field()).put("why", k.why()).put("text", keptText(k));
            }
        }
        ArrayNode dropped = target.putArray("customerOverrides");
        if (!drainOnly) {
            diff.customerOverrides().forEach(dropped::add);
        }
        ArrayNode acks = target.putArray("acks");
        unique.forEach(w -> acks.add(w.id()));
        if (!drainOnly && diff.versionDrift()) {
            target.put("contextDrift", "LAUNCH_TEMPLATE_VERSION");
        }
        String method = drainOnly ? "DRAIN_ONLY" : mode == Mode.RESTORE_ONLY ? "RESTORE_ONLY" : "RESTORE_SNAPSHOT";
        ObjectNode revert = JSON.createObjectNode().put("program", "REVERT").put("method", method).put("exact", exact);
        ArrayNode rv = revert.putArray("reasons");
        reasons.forEach(rv::add);
        if (drainOnly) {
            revert.put("remediation", "Update stack");
            revert.put("laterExactRevert", true);
        }
        SnapshotPayload snapshot = new SnapshotPayload(Provider.AWS, AwsSnapshotCodec.SCHEMA_VERSION, storedExact,
                payloadReasons, payload, AwsFingerprint.sha256(payload));
        int spotNow = 0;
        try {
            spotNow = services.observer().observe(view, parts[1]).map(AwsRolloutObserver.Snapshot::spotNow).orElse(0);
        } catch (AwsReadException e) {
            spotNow = 0; // the count is a display figure; the revert reads it again at run time
        }
        PlanBody.Rollout rollout = new PlanBody.Rollout(
                mode == Mode.RESTORE_ONLY ? "RESTORE_ONLY" : "DRAIN_LAUNCH_FIRST",
                mode == Mode.RESTORE_ONLY ? 0 : spotNow, 1, "5 min to 1 h",
                Math.max(0, live.max() - live.desired()) + " instance(s) above desired (max " + live.max() + ")", null);
        return new PlanBody(write(current), write(target), write(diffNode(current, target)), snapshot,
                new EligibilityResult(blockers, unique, List.of()), rollout, List.of(), null, null, write(revert));
    }

    private PlanBody blocked(AutoScalingGroup raw, Check blocker) {
        return new PlanBody(null, null, null, null, new EligibilityResult(List.of(blocker), List.of(), List.of()),
                null, List.of(), null, null, null);
    }

    private static Check check(String code, String text) {
        return Check.of(code, code, text);
    }

    private static String reasonText(String id, AwsThreeWayDiff.Result diff) {
        return switch (id) {
            case AwsThreeWayDiff.TEMPLATE_REPLACED ->
                    "The launch template was changed outside XamOps. It is kept as it is, and the Spot settings are restored.";
            case AwsThreeWayDiff.TEMPLATE_VERSION_DELETED ->
                    "The original launch template version no longer exists. The group will use the $Default version "
                            + "of its template.";
            case AwsThreeWayDiff.ACK_CUSTOMER_OVERRIDES ->
                    "These instance types were added outside XamOps and are dropped when the group returns to one "
                            + "launch template: " + String.join(", ", diff.customerOverrides()) + ".";
            case AwsThreeWayDiff.ACK_LEGACY_TARGET, AwsThreeWayDiff.LEGACY_RECONSTRUCTED ->
                    "This group was converted before XamOps tracked it, so its original settings are reconstructed. "
                            + "XamOps uses the closest safe target.";
            default -> "The original configuration cannot be restored exactly.";
        };
    }

    private static String keptText(AwsThreeWayDiff.Kept k) {
        return AwsThreeWayDiff.ACCEPTED_BASELINE.equals(k.why())
                ? k.field() + " changed outside XamOps and was accepted as the baseline, left as is"
                : k.field() + " changed outside XamOps, left as is";
    }

    private static ObjectNode currentNode(AwsGroupFacts g) {
        ObjectNode n = JSON.createObjectNode();
        n.put("shape", g.mixedInstancesPolicy() ? "MIXED_INSTANCES_POLICY" : "LAUNCH_TEMPLATE");
        ObjectNode lt = n.putObject("launchTemplate");
        if (g.launchTemplate() != null) {
            lt.put("id", g.launchTemplate().id()).put("name", g.launchTemplate().name())
                    .put("version", g.launchTemplate().version());
        }
        ArrayNode types = n.putArray("overrides");
        g.overrideTypes().forEach(types::add);
        if (g.mixedInstancesPolicy()) {
            ObjectNode d = n.putObject("instancesDistribution");
            d.put("onDemandAllocationStrategy", g.onDemandAllocationStrategy());
            d.put("onDemandBaseCapacity", g.onDemandBase());
            d.put("onDemandPercentageAboveBaseCapacity", g.onDemandPercentageAboveBase());
            d.put("spotAllocationStrategy", g.spotAllocationStrategy());
            if (g.spotInstancePools() != null && "lowest-price".equals(g.spotAllocationStrategy())) {
                d.put("spotInstancePools", g.spotInstancePools());
            }
        } else {
            n.putNull("instancesDistribution");
        }
        n.put("capacityRebalance", g.capacityRebalance());
        n.put("min", g.min()).put("max", g.max()).put("desired", g.desired());
        n.putObject(AwsMaintenancePolicy.KEY).put("min", g.minHealthyPercentage()).put("max", g.maxHealthyPercentage());
        return n;
    }

    /** The drain-only target: the live configuration with the On-Demand share at 100 and everything else as is. */
    private static ObjectNode drained(ObjectNode current) {
        ObjectNode t = current.deepCopy();
        t.remove(List.of("min", "max", "desired"));
        JsonNode d = t.path("instancesDistribution");
        if (d instanceof ObjectNode dn) {
            dn.put("onDemandPercentageAboveBaseCapacity", 100);
        }
        return t;
    }

    private static ArrayNode diffNode(ObjectNode current, ObjectNode target) {
        ArrayNode diff = JSON.createArrayNode();
        add(diff, "shape", current.path("shape").asText(null), target.path("shape").asText(null));
        add(diff, "launchTemplate", templateOf(current), templateOf(target));
        add(diff, "overrides", join(current.path("overrides")), join(target.path("overrides")));
        for (String f : List.of("onDemandAllocationStrategy", "onDemandBaseCapacity",
                "onDemandPercentageAboveBaseCapacity", "spotAllocationStrategy")) {
            add(diff, f, text(current.path("instancesDistribution").path(f)), text(target.path("instancesDistribution").path(f)));
        }
        add(diff, "capacityRebalance", text(current.path("capacityRebalance")), text(target.path("capacityRebalance")));
        // AD-08: shown only when the revert writes it, so the customer sees the restore value ("none" = removed).
        if (target.path(AwsMaintenancePolicy.KEY).isObject()) {
            add(diff, AwsMaintenancePolicy.KEY, policyText(current.path(AwsMaintenancePolicy.KEY)),
                    policyText(target.path(AwsMaintenancePolicy.KEY)));
        }
        return diff;
    }

    /** {@code none} for an absent policy (also -1/-1), else {@code min 100 / max 200}. */
    public static String policyText(JsonNode p) {
        Integer min = p.hasNonNull("min") ? AwsMaintenancePolicy.normalize(p.get("min").asInt()) : null;
        Integer max = p.hasNonNull("max") ? AwsMaintenancePolicy.normalize(p.get("max").asInt()) : null;
        if (min == null && max == null) {
            return "none";
        }
        return "min " + (min == null ? "unset" : min) + " / max " + (max == null ? "unset" : max);
    }

    private static String templateOf(JsonNode n) {
        JsonNode lt = n.path("launchTemplate");
        return lt.hasNonNull("id") ? lt.get("id").asText() : text(lt.path("name"));
    }

    private static void add(ArrayNode diff, String field, String from, String to) {
        if (!java.util.Objects.equals(from, to)) {
            diff.addObject().put("field", field).put("from", from).put("to", to);
        }
    }

    private static String join(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return String.join(",", out);
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String write(JsonNode n) {
        try {
            return JSON.writeValueAsString(JSON.treeToValue(n, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("plan serialization failed", e);
        }
    }
}
