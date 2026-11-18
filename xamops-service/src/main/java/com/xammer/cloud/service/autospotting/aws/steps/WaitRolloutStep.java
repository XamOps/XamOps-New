package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsRolloutEvaluator;
import com.xammer.cloud.service.autospotting.aws.AwsRolloutObserver;
import com.xammer.cloud.service.autospotting.aws.AwsRolloutProperties;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * Step 4 (design-aws.md §2.5): the rollout wait. Read-only; every 60 s it counts Spot instances,
 * healthy in-service instances (target groups and classic load balancers included) and the Spot
 * failures in the activity feed, and hands them to {@link AwsRolloutEvaluator}. Done only when the
 * Spot count is within tolerance of the profile, never zero Spot when the profile expects some, and
 * every desired instance is healthy. A shortfall or stall ends the step in a Failed outcome that
 * the runner turns into COMPENSATE; the compensation leg uses the lenient rule and never fails into
 * another compensation. The poll state rides in the operation's progress JSON. Launch activities from
 * the same read feed the analytics events (write-time dedupe).
 */
public final class WaitRolloutStep extends AwsStep {

    private static final Logger log = LoggerFactory.getLogger(WaitRolloutStep.class);
    public static final String NAME = "WAIT_ROLLOUT";

    public WaitRolloutStep(AwsStepServices services, AwsProgram program) {
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
            return failed(ErrorKind.VALIDATION, "ACCOUNT_MISSING", "The connected account could not be found.");
        }
        AwsReadOnlyView view = services.view(account, program.region());
        Optional<AwsRolloutObserver.Snapshot> snap = services.observer().observe(view, program.groupName());
        if (snap.isEmpty()) {
            return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The Auto Scaling group no longer exists.");
        }
        AwsRolloutObserver.Snapshot s = snap.get();
        Instant now = services.clock().instant();
        recordEvents(s, now);

        AwsRolloutEvaluator.Observation o = new AwsRolloutEvaluator.Observation(now, s.desired(), s.spotNow(),
                s.healthyInService(), s.lastSpotFailureAt());
        AwsRolloutEvaluator.State prior = AwsRolloutEvaluator.State.fromJson(previousProgress());
        int target = AwsRolloutEvaluator.spotTarget(s.desired(), program.base(), program.percentAboveBase());

        AwsRolloutEvaluator.Verdict verdict = program.compensation()
                ? services.rollout().evaluateCompensation(o, prior)
                : services.rollout().evaluate(o, target, prior, AwsRolloutEvaluator.Mode.PROGRESS);
        return switch (verdict) {
            case AwsRolloutEvaluator.Done d -> done(AwsRolloutEvaluator.progressJson(o, target, d.state(), "Done"));
            case AwsRolloutEvaluator.Waiting w -> new StepOutcome.InProgress(AwsRolloutProperties.POLL,
                    AwsRolloutEvaluator.progressJson(o, target, w.state(), w.message()));
            case AwsRolloutEvaluator.Failed f -> new StepOutcome.Failed(f.kind(), f.code(), messageFor(f.code()));
        };
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // every poll is a fresh read; execute is the probe
    }

    private String previousProgress() {
        return services.facts().operationProgress(program.operationId()).orElse(null);
    }

    private void recordEvents(AwsRolloutObserver.Snapshot s, Instant now) {
        try {
            services.events().record(program.groupId(), s.activities(), now);
        } catch (RuntimeException e) {
            // Analytics must never fail a rollout; the next poll records the same activities (deduped).
            log.warn("op {} could not record launch events: {}", program.operationId(), e.getClass().getSimpleName());
        }
    }

    private static String messageFor(String code) {
        return switch (code) {
            case AwsRolloutEvaluator.SPOT_SHORTFALL ->
                    "Spot capacity was not available while the group needed more instances. Restoring the previous configuration.";
            case AwsRolloutEvaluator.SPOT_STALL ->
                    "Spot capacity did not become available in time. Restoring the previous configuration.";
            default -> "New instances did not become healthy. Restoring the previous configuration.";
        };
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Watch the rollout every minute. It is done when Spot instances match the profile and every "
                + "desired instance is healthy. If Spot capacity is not available or instances stay unhealthy, "
                + "the previous configuration is restored.";
    }
}
