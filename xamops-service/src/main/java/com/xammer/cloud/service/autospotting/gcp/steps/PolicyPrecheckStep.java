package com.xammer.cloud.service.autospotting.gcp.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpFingerprint;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpRolloutPolicy;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Step 0 of MIG_POLICY_UPDATE (design-gcp.md §3.5): the MIG exists, it still matches the plan (plan hash, which
 * includes the update policy), the saved policy passes the validation rules, a {@code targetSize} edit is not made
 * on an autoscaled MIG, and {@code testIamPermissions} grants {@code compute.instanceGroupManagers.update}.
 * Read-only.
 */
public final class PolicyPrecheckStep extends GcpStep {
    public static final String NAME = "VERIFY_PRECONDITIONS";
    static final String UPDATE = "compute.instanceGroupManagers.update";

    public PolicyPrecheckStep(GcpStepServices services, GcpProgram program) {
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
        String hash = GcpFingerprint.planHash(g);
        Optional<String> planned = services.facts().planLiveFingerprint(program.planId());
        if (planned.isEmpty() || !planned.get().equals(hash)) {
            return failed(ErrorKind.VALIDATION, "PLAN_STALE", "The group changed after this plan was made. Review a fresh plan.");
        }
        var edit = program.target().path("edit");
        if (edit.has("targetSize") && g.autoscalerLink() != null) {
            return failed(ErrorKind.VALIDATION, "GCP_TARGET_SIZE_AUTOSCALED",
                    "The size of an autoscaled group is set by its autoscaler.");
        }
        String violation = GcpRolloutPolicy.validateEdit(edit.path("updatePolicy"), g.regional(),
                g.regional() ? g.distributionZones().size() : 1, edit.has("targetSize") ? edit.path("targetSize").asInt()
                        : g.targetSize());
        if (violation != null) {
            return failed(ErrorKind.VALIDATION, "GCP_POLICY_" + violation, "The rolling-update settings are not valid for this group.");
        }
        Set<String> granted = view.reads().testIamPermissions(List.of(UPDATE));
        if (!granted.contains(UPDATE)) {
            return failed(ErrorKind.PERMISSION, "GCP_PERMISSION", "XamOps is missing permission " + UPDATE + " on this project.");
        }
        return done("{\"planHash\":\"" + hash + "\"}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that the instance group still matches the plan and that XamOps may update it. Nothing is changed.";
    }
}
