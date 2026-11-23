package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsFingerprint;
import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsMaintenancePolicy;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Step 0 (design-aws.md §2.3): nothing may change until the group still looks like the plan.
 * Read-only. In order:
 * <ol>
 *   <li>the group exists and is a launch-template group (a Launch Configuration group never gets here:
 *       it is rejected at plan time, and this is the second check);</li>
 *   <li>no instance refresh is running: a foreign one is {@code Failed(CONFLICT)}, which the runner
 *       turns into {@code WAITING(FOREIGN_CHANGE)} (DR-6);</li>
 *   <li>the live fingerprint equals the plan's, else {@code PLAN_STALE};</li>
 *   <li>the CONFIRM-mode eligibility pass has no blocker, else {@code Failed(VALIDATION, <code>)}.</li>
 * </ol>
 * Every failure here is before the first mutation, so the group goes back to its prior state.
 */
public final class VerifyPreconditionsStep extends AwsStep {

    public static final String NAME = "VERIFY_PRECONDITIONS";

    public VerifyPreconditionsStep(AwsStepServices services, AwsProgram program) {
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
            return failed(ErrorKind.VALIDATION, "ACCOUNT_MISSING", "The connected account could not be found.");
        }
        AwsReadOnlyView view = services.view(account.get(), program.region());
        Optional<AutoScalingGroup> raw = services.statusReader().describeGroup(view, program.groupName());
        if (raw.isEmpty()) {
            return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The Auto Scaling group no longer exists.");
        }
        AwsGroupFacts facts = AwsGroupFacts.from(program.region(), raw.get());
        if (facts.launchConfigurationName() != null || (facts.launchTemplate() == null && !facts.mixedInstancesPolicy())) {
            return failed(ErrorKind.VALIDATION, "LAUNCH_CONFIGURATION",
                    "This group uses a launch configuration. Migrate it to a launch template first.");
        }
        if (services.reader().refreshInProgress(view, program.groupName())) {
            return failed(ErrorKind.CONFLICT, "FOREIGN_CHANGE",
                    "An instance refresh is running on this group. XamOps will wait for it to finish.");
        }
        Optional<String> planned = services.facts().planLiveFingerprint(program.planId());
        String live = AwsFingerprint.of(facts, AwsMaintenancePolicy.owns(program.config()));
        // The plan's fingerprint includes the maintenance policy exactly when the plan's target owns it (AD-08).
        if (planned.isEmpty() || !planned.get().equals(live)) {
            return failed(ErrorKind.VALIDATION, "PLAN_STALE",
                    "The group changed after this plan was made. Review a fresh plan.");
        }
        // 100% Spot only by explicit opt-in: a target with no On-Demand base and no On-Demand share needs the
        // FULL_SPOT_OPTIN acknowledgement recorded when the plan was confirmed (FR-PLN-04). Defense in depth
        // behind the confirm path's own check.
        if (program.base() == 0 && program.percentAboveBase() == 0
                && !services.facts().planConfirmAcks(program.planId()).orElse("").contains("FULL_SPOT_OPTIN")) {
            return failed(ErrorKind.VALIDATION, "FULL_SPOT_NOT_CONFIRMED",
                    "This plan runs the group on 100% Spot, which needs your explicit confirmation.");
        }
        // The plan's burstable choice (AD-09), so a plan the customer allowed is not blocked here by the default.
        boolean allowBurstable = program.config().path("options").path("allowBurstable").asBoolean(false);
        EligibilityResult eligibility = services.evaluator().evaluate(account.get(), program.groupKey(),
                profileOf(), EvalMode.CONFIRM, allowBurstable).result();
        if (!eligibility.blockers().isEmpty()) {
            EligibilityResult.Check first = eligibility.blockers().get(0);
            if (first.errorKind() != null) {
                return failed(first.errorKind(), "CHECK_FAILED",
                        "A pre-flight check could not be completed (" + first.id() + ").");
            }
            String codes = eligibility.blockers().stream().map(EligibilityResult.Check::code)
                    .distinct().collect(Collectors.joining(","));
            ErrorKind kind = "PERMISSION_MISSING".equals(first.code()) ? ErrorKind.PERMISSION : ErrorKind.VALIDATION;
            return failed(kind, first.code(), "The group is no longer eligible: " + codes + ".");
        }
        return done("{\"fingerprint\":\"" + live + "\",\"blockers\":0}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // read-only: re-running is the check
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that the group still matches the plan, that no instance refresh is running, and that it "
                + "is still eligible. Nothing is changed.";
    }

    private Profile profileOf() {
        return new Profile("custom", "{\"base\":" + program.base() + ",\"percentAboveBase\":"
                + program.percentAboveBase() + "}");
    }
}
