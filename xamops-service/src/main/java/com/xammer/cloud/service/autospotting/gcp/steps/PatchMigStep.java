package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpMigClient;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpRolloutPolicy;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.gcp.GcpTargets;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.Optional;

/**
 * Step 4 (design-gcp.md §2.6, §3.1, §5.2): the one PATCH that points the MIG at the target versions with the
 * surge-first policy in the same request. Option A: {@code xas-standard} (the original template,
 * {@code targetSize.fixed = N}) plus {@code xas-spot}; Full Spot is the single Spot version. The raw MIG
 * fingerprint from a fresh read is the precondition; a 409 or 412 is CONFLICT ({@code GCP_MIG_UPDATE_CONFLICT}),
 * never success. The step is convergent: when the live MIG already shows the target, it makes no call. The PATCH
 * being accepted is only intent: success is declared by the rollout wait, never here. Asserts the lease first.
 */
public final class PatchMigStep extends GcpStep {
    public static final String NAME = "PATCH_MIG";
    private static final ObjectMapper JSON = new ObjectMapper();

    public PatchMigStep(GcpStepServices services, GcpProgram program) {
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
        ObjectNode policy = GcpRolloutPolicy.xamopsPolicy(g);
        JsonNode flex = revertFlexibility(g);
        boolean flexDone = flex == null || flex.equals(g.instanceFlexibilityPolicy());
        if (GcpTargets.matches(g, versions) && GcpTargets.policyHas(g.updatePolicy(), policy) && flexDone) {
            return done("{\"alreadyApplied\":true}");
        }
        ObjectNode body = JSON.createObjectNode();
        body.set("versions", versions);
        body.set("updatePolicy", policy);
        if (flex != null) {
            body.set("instanceFlexibilityPolicy", flex); // a revert restores the snapshot's GCP Spot field too
        }
        GcpMigClient.Mutating client = services.mutating(account);
        JsonNode op = patch(ctx, view, client, g, body);
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
        if (live.isPresent()) {
            ArrayNode versions = targetVersions(live.get());
            if (versions != null && GcpTargets.matches(live.get(), versions)) {
                return done("{\"alreadyApplied\":true}");
            }
        }
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return program.compensation()
                ? "Point the instance group back at its previous template, replacing instances one batch at a time, "
                + "new ones first, so capacity never drops."
                : "Point the instance group at the Spot template (keeping " + program.floorN() + " On-Demand instance(s) "
                + "on your original template). New instances are created first and old ones removed after, so capacity never drops.";
    }
}
