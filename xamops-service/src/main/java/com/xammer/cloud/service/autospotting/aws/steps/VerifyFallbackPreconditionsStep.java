package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsFallbackRules;
import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsRolloutObserver;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Duration;
import java.util.Optional;

/**
 * Step 0 of FALLBACK_ENTER (design-aws.md §6.4 step 1): read the group again and abort when the condition that
 * called for the fallback has cleared, or when there is no fallback path (the group is already 100% On-Demand
 * above base). Read-only; every failure here is before the first mutation, so the group stays where it was.
 *
 * <p>A group that was APPLYING when the fallback was queued has a half-applied change from the op the fallback
 * preempted; for it the cleared check is skipped, because the fallback is also what puts the group back into a
 * defined configuration.
 */
public final class VerifyFallbackPreconditionsStep extends AwsStep {
    public static final String NAME = "VERIFY_PRECONDITIONS";
    private final AwsFallbackRules rules;
    private final boolean skipClearedCheck;

    public VerifyFallbackPreconditionsStep(AwsStepServices services, AwsProgram program, AwsFallbackRules rules,
                                           boolean skipClearedCheck) {
        super(services, program);
        this.rules = rules;
        this.skipClearedCheck = skipClearedCheck;
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
        Optional<AwsRolloutObserver.Snapshot> snap = services.observer().observe(view, program.groupName());
        if (snap.isEmpty()) {
            return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The Auto Scaling group no longer exists.");
        }
        AwsGroupFacts facts = AwsGroupFacts.from(program.region(), snap.get().group());
        if (!facts.mixedInstancesPolicy() || (facts.onDemandPercentageAboveBase() != null
                && facts.onDemandPercentageAboveBase() == 100)) {
            return failed(ErrorKind.VALIDATION, "NO_FALLBACK_PATH",
                    "The group already runs on On-Demand above its base, so there is nothing to fall back to.");
        }
        if (!skipClearedCheck && snap.get().healthyInService() >= snap.get().desired()
                && !stormNow(snap.get().spotNow())) {
            return failed(ErrorKind.VALIDATION, "FALLBACK_NOT_NEEDED",
                    "The Spot capacity problem cleared before the fallback started.");
        }
        return done("{\"healthyInService\":" + snap.get().healthyInService() + ",\"desired\":" + snap.get().desired()
                + "}");
    }

    private boolean stormNow(int spotInstances) {
        int interruptions;
        try {
            interruptions = services.events().interruptionsSince(program.groupId(),
                    services.clock().instant().minus(Duration.ofMinutes(30)));
        } catch (RuntimeException e) {
            return false;
        }
        return rules.storm(interruptions, spotInstances);
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // read-only: re-running is the check
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that Spot capacity is still short and that the group can fall back to On-Demand. "
                + "Nothing is changed.";
    }
}
