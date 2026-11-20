package com.xammer.cloud.service.autospotting.gcp.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpFingerprint;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Step 0 (design-gcp.md §2.6): nothing may change until the MIG still looks like the plan. Read-only. In order:
 * the MIG exists; no rollout is in flight on it (a foreign one is {@code Failed(CONFLICT)}, which the runner
 * turns into WAITING FOREIGN_CHANGE, DR-6); the plan hash equals the plan's, else {@code PLAN_STALE}; a Full Spot
 * target carries the opt-in; and the CONFIRM-mode eligibility pass has no blocker (this keeps every blocker,
 * the GKE node pool guard included, in force at execution). Every failure is before the first mutation.
 */
public final class VerifyPreconditionsStep extends GcpStep {
    public static final String NAME = "VERIFY_PRECONDITIONS";

    public VerifyPreconditionsStep(GcpStepServices services, GcpProgram program) {
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
        if (!g.versionTargetReached() || !g.stable()) {
            return failed(ErrorKind.CONFLICT, "FOREIGN_CHANGE",
                    "A rollout is running on this instance group. XamOps will wait for it to finish.");
        }
        Optional<String> planned = services.facts().planLiveFingerprint(program.planId());
        String hash = GcpFingerprint.planHash(g);
        if (planned.isEmpty() || !planned.get().equals(hash)) {
            return failed(ErrorKind.VALIDATION, "PLAN_STALE", "The group changed after this plan was made. Review a fresh plan.");
        }
        int n = program.floorN();
        if (n == 0 && !services.facts().planConfirmAcks(program.planId()).orElse("").contains("FULL_SPOT_OPTIN")) {
            return failed(ErrorKind.VALIDATION, "FULL_SPOT_NOT_CONFIRMED",
                    "This plan runs the group on 100% Spot, which needs your explicit confirmation.");
        }
        Profile profile = n == 0 ? new Profile("full_spot", null) : new Profile("custom", "{\"floor\":" + n + "}");
        EligibilityResult eligibility = services.evaluator().evaluate(account.get(), program.groupKey(), null, profile,
                EvalMode.CONFIRM).result();
        if (!eligibility.blockers().isEmpty()) {
            EligibilityResult.Check first = eligibility.blockers().get(0);
            if (first.errorKind() != null) {
                return failed(first.errorKind(), "CHECK_FAILED", "A pre-flight check could not be completed (" + first.id() + ").");
            }
            String codes = eligibility.blockers().stream().map(EligibilityResult.Check::code).distinct()
                    .collect(Collectors.joining(","));
            ErrorKind kind = "GCP_PERMISSION".equals(first.code()) ? ErrorKind.PERMISSION : ErrorKind.VALIDATION;
            return failed(kind, first.code(), "The group is no longer eligible: " + codes + ".");
        }
        return done("{\"planHash\":\"" + hash + "\",\"blockers\":0}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // read-only: re-running is the check
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that the instance group still matches the plan, that no rollout is running, and that it is "
                + "still eligible. Nothing is changed.";
    }
}
