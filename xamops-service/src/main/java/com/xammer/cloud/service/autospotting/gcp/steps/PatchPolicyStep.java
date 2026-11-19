package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.gcp.GcpTargets;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.Optional;

/**
 * Step 1 of MIG_POLICY_UPDATE (design-gcp.md §3.5): PATCH the policy fields the plan showed, and nothing else:
 * {@code updatePolicy} (only the edited sub-fields, so {@code type} is sent only if it was edited) and
 * {@code targetSize} only if edited and never on an autoscaled MIG. It never sends versions, a template, a type
 * the customer did not edit, or {@code minReadySeconds}. The raw fingerprint is the precondition; 409 and 412 are
 * CONFLICT. Convergent, and the same step restores the prior policy in the compensation leg.
 */
public final class PatchPolicyStep extends GcpStep {
    public static final String NAME = "PATCH_POLICY";
    private static final ObjectMapper JSON = new ObjectMapper();

    public PatchPolicyStep(GcpStepServices services, GcpProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean mutates() {
        return true;
    }

    /** The PATCH body for an edit: policy fields only. */
    public static ObjectNode bodyOf(JsonNode edit) {
        ObjectNode body = JSON.createObjectNode();
        if (edit.path("updatePolicy").isObject()) {
            ObjectNode policy = edit.path("updatePolicy").deepCopy();
            policy.remove("minReadySeconds");
            for (String f : new String[] {"maxSurge", "maxUnavailable"}) {
                if (policy.path(f).isObject()) {
                    ((ObjectNode) policy.get(f)).remove("calculated");
                }
            }
            body.set("updatePolicy", policy);
        }
        if (edit.has("targetSize")) {
            body.put("targetSize", edit.path("targetSize").asInt());
        }
        return body;
    }

    static boolean applied(GcpMigFacts g, JsonNode edit) {
        return GcpTargets.policyHas(g.updatePolicy(), bodyOf(edit).path("updatePolicy"))
                && (!edit.has("targetSize") || g.targetSize() == edit.path("targetSize").asInt());
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
        JsonNode edit = program.target().path("edit");
        if (edit.has("targetSize") && g.autoscalerLink() != null) {
            return failed(ErrorKind.VALIDATION, "GCP_TARGET_SIZE_AUTOSCALED", "The size of an autoscaled group is set by its autoscaler.");
        }
        if (applied(g, edit)) {
            return done("{\"alreadyApplied\":true}");
        }
        JsonNode op = patch(ctx, view, services.mutatingPolicyOnly(account), g, bodyOf(edit));
        return new StepOutcome.Done("{\"patched\":true}", op.hasNonNull("id") ? java.util.List.of(op.path("id").asText())
                : java.util.List.of());
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return notYet();
        }
        Optional<GcpMigFacts> live = live(services.view(account));
        return live.isPresent() && applied(live.get(), program.target().path("edit"))
                ? done("{\"alreadyApplied\":true}") : notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return program.compensation()
                ? "Put the instance group's rolling-update settings back to what they were."
                : "Save your rolling-update settings on the instance group. Its template, size and instances are not changed.";
    }
}
