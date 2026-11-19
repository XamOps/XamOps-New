package com.xammer.cloud.service.autospotting.gcp.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpFingerprint;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpRestoreProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpRevertTarget;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Step 0 of a REVERT (design-gcp.md §2.6 "restore(REVERT)", design-core.md §7.4, DR-03, GR-03). Read-only, and the
 * revert precondition set only: the MIG exists; no FOREIGN rollout is running ({@code Failed(CONFLICT,
 * FOREIGN_CHANGE)}, the DR-6 wait; a rollout XamOps itself started carries its {@code xas-*} version names and is
 * not foreign); the group still looks like the plan; the target resolves; and {@code testIamPermissions} grants what
 * the later steps call. The surge quota is a warning in the output. {@code GCP_SIZE_TOO_SMALL},
 * {@code GCP_NO_READINESS_GATE}, {@code GCP_FLOOR_SHAPE_UNVERIFIED}, {@code GCP_STANDBY_POOL} and IaC markers are
 * never evaluated here, so a revert is never blocked by a conversion-only check.
 *
 * <p>A missing permission ends the op {@code Failed(PERMISSION, REVERT_NEEDS_PERMISSION)} before any mutation
 * ({@code first_mutation_seq} stays null), so the group keeps its prior state and fallback and billing resume (GR-03).
 */
public final class VerifyRevertPreconditionsStep extends GcpStep {
    public static final String NAME = "VERIFY_PRECONDITIONS";
    public static final String NEEDS_PERMISSION = "REVERT_NEEDS_PERMISSION";

    public VerifyRevertPreconditionsStep(GcpStepServices services, GcpProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        Optional<CloudAccount> account = account();
        if (account.isEmpty()) {
            return accountMissing();
        }
        GcpReadOnlyView view = services.view(account.get());
        Optional<GcpMigFacts> live = live(view);
        if (live.isEmpty()) {
            return groupMissing();
        }
        GcpMigFacts g = live.get();
        boolean ours = g.versions().stream().anyMatch(v -> GcpMigFacts.STANDARD_VERSION.equals(v.name())
                || GcpMigFacts.SPOT_VERSION.equals(v.name()));
        if ((!g.versionTargetReached() || !g.stable()) && !ours) {
            return failed(ErrorKind.CONFLICT, "FOREIGN_CHANGE",
                    "A rollout is running on this instance group. XamOps will wait for it to finish.");
        }
        Optional<String> planned = services.facts().planLiveFingerprint(program.planId());
        String hash = GcpFingerprint.planHash(g);
        if (planned.isEmpty() || !planned.get().equals(hash)) {
            return failed(ErrorKind.VALIDATION, "PLAN_STALE",
                    "The group changed after this plan was made. Review a fresh revert plan.");
        }
        GcpRevertTarget.Resolved target = GcpRevertTarget.resolve(services, program, view, g);
        if (target.failure() != null) {
            return failed(ErrorKind.VALIDATION, target.failure(),
                    "The original configuration cannot be restored from what was saved. Nothing was changed.");
        }
        String acks = services.facts().planConfirmAcks(program.planId()).orElse("");
        for (String note : target.notes()) {
            if (!acks.contains(note)) {
                return failed(ErrorKind.VALIDATION, "ACK_REQUIRED", "This revert needs an acknowledgement (" + note
                        + "). Review a fresh revert plan.");
            }
        }
        List<String> required = new ArrayList<>(GcpRestoreProgram.REVERT_PERMISSIONS);
        if (target.rebuild()) {
            required.add(GcpRestoreProgram.CREATE_PERMISSION);
        }
        Set<String> granted = view.reads().testIamPermissions(required);
        List<String> missing = required.stream().filter(p -> !granted.contains(p)).toList();
        if (!missing.isEmpty()) {
            return failed(ErrorKind.PERMISSION, NEEDS_PERMISSION, "The connection is missing " + String.join(", ", missing)
                    + ". Grant them, then plan the revert again. Nothing was changed.");
        }
        return done("{\"planHash\":\"" + hash + "\",\"exact\":" + target.exact() + ",\"rebuild\":" + target.rebuild()
                + ",\"surgeQuota\":\"WARNING_ONLY\"}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // read-only: re-running is the check
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that the instance group still matches the revert plan, that no other rollout is running and that "
                + "the connection may make every change the revert needs. Nothing is changed.";
    }
}
