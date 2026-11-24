package com.xammer.cloud.service.autospotting.gcp.steps;

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
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.Optional;

/**
 * Step 8 (design-gcp.md §3.4, §5.5 PO-GCP-A): after the success test passed, PATCH {@code updatePolicy} back to
 * the customer's own. Changing the policy alone starts no rollout. For N = 0 the whole customer policy returns; for
 * N &gt; 0 every customer field returns except {@code type} (kept PROACTIVE) and {@code maxUnavailable} (kept 0),
 * and the overridden fields are reported in the step output (the plan showed them as an acknowledged warning). A
 * 409 or 412 is CONFLICT and is retried by the runner; the group goes ACTIVE only after the restore succeeded.
 */
public final class RestoreUpdatePolicyStep extends GcpStep {
    public static final String NAME = "RESTORE_UPDATE_POLICY";
    private static final ObjectMapper JSON = new ObjectMapper();

    public RestoreUpdatePolicyStep(GcpStepServices services, GcpProgram program) {
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
        if (program.kind() == com.xammer.cloud.domain.autospotting.OperationKind.FALLBACK_ENTER && !rolloutComplete(view, g)) {
            // The tail wait ended at its bound (DR-24): restoring a customer policy mid-rollout could delete VMs before
            // their replacements exist, so the XamOps surge-first policy stays until a later exit or revert.
            return finish(view, "{\"restored\":false,\"outcome\":\"CAPACITY_PENDING\"}");
        }
        if (program.customerPolicy() == null || !program.customerPolicy().isObject() || program.customerPolicy().isEmpty()) {
            return finish(view, "{\"restored\":false,\"reason\":\"NO_CUSTOMER_POLICY\"}");
        }
        GcpRolloutPolicy.Restored restored = GcpRolloutPolicy.restore(program.customerPolicy(), floor(g), g);
        if (GcpTargets.policyHas(g.updatePolicy(), restored.policy())) {
            return finish(view, "{\"restored\":true,\"alreadyApplied\":true}");
        }
        ObjectNode body = JSON.createObjectNode();
        body.set("updatePolicy", restored.policy());
        GcpMigClient.Mutating client = services.mutating(account);
        patch(ctx, view, client, g, body);
        return finish(view, "{\"restored\":true,\"overridden\":" + JSON.valueToTree(restored.overridden()) + "}");
    }

    /**
     * A FALLBACK_ENTER reports the live hash after this step as {@code fallbackFingerprint}: the policy is the last
     * thing the fallback changes, and the drift check compares the hash {@code getStatus} reports, which includes it.
     * The runner stores it, so two sweeps after the op show no drift whether the customer's policy was restored or the
     * wait ended CAPACITY_PENDING with XamOps's policy still on the MIG.
     */
    private StepOutcome finish(GcpReadOnlyView view, String json) {
        if (program.kind() != com.xammer.cloud.domain.autospotting.OperationKind.FALLBACK_ENTER) {
            return done(json);
        }
        Optional<GcpMigFacts> fresh = live(view);
        if (fresh.isEmpty()) {
            return done(json);
        }
        try {
            ObjectNode out = (ObjectNode) JSON.readTree(json);
            out.put(com.xammer.cloud.service.autospotting.spi.FallbackSteps.FINGERPRINT,
                    com.xammer.cloud.service.autospotting.gcp.GcpFingerprint.planHash(fresh.get()));
            return done(out.toString());
        } catch (java.io.IOException e) {
            return done(json);
        }
    }
    private int floor(GcpMigFacts g) {
        ArrayNode v = targetVersions(g);
        return v == null ? 0 : GcpTargets.floorOf(v);
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null || program.customerPolicy() == null) {
            return notYet();
        }
        Optional<GcpMigFacts> live = live(services.view(account));
        return live.isPresent() && GcpTargets.policyHas(live.get().updatePolicy(),
                GcpRolloutPolicy.restore(program.customerPolicy(), floor(live.get()), live.get()).policy())
                ? finish(services.view(account), "{\"restored\":true,\"alreadyApplied\":true}") : notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Put your own rolling-update settings back on the instance group.";
    }
}
