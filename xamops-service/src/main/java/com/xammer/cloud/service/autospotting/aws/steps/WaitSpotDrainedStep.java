package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsRestoreProgram;
import com.xammer.cloud.service.autospotting.aws.AwsRolloutObserver;
import com.xammer.cloud.service.autospotting.aws.AwsRolloutProperties;
import com.xammer.cloud.service.autospotting.aws.AwsSpotFailureMatcher;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.autoscaling.model.Activity;

import java.time.Instant;
import java.util.Optional;

/**
 * Step 2 of the REVERT program (design-aws.md §5.3, FR-REV-04): read-only, every 60 s. The progress text is
 * "Spot instances remaining: n". Done when no Spot instance is left and every desired instance is healthy and in
 * service. A capacity failure here is an On-Demand failure: it is flagged in the progress and logged, and the
 * step keeps waiting. It never terminates an instance and never ends Failed on capacity, because the group still
 * serves its traffic from the instances it has.
 */
public final class WaitSpotDrainedStep extends AwsRevertStep {
    private static final Logger log = LoggerFactory.getLogger(WaitSpotDrainedStep.class);
    public static final String NAME = "WAIT_SPOT_DRAINED";
    /** A capacity failure older than this is not flagged: it is not what the group is waiting on now. */
    private static final java.time.Duration RECENT = java.time.Duration.ofMinutes(15);

    public WaitSpotDrainedStep(AwsStepServices services, AwsProgram program, AwsRestoreProgram.Inputs inputs) {
        super(services, program, inputs);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        Live live = live();
        if (live.failure() != null) {
            return live.failure();
        }
        Optional<AwsRolloutObserver.Snapshot> snap = services.observer().observe(live.view(), program.groupName());
        if (snap.isEmpty()) {
            return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The Auto Scaling group no longer exists.");
        }
        AwsRolloutObserver.Snapshot s = snap.get();
        Instant now = services.clock().instant();
        boolean odFailure = onDemandCapacityFailure(s, now);
        try {
            services.events().record(program.groupId(), s.activities(), now);
        } catch (RuntimeException e) {
            log.warn("op {} could not record launch events: {}", program.operationId(), e.getClass().getSimpleName());
        }
        if (odFailure) {
            log.warn("op {} On-Demand capacity is short while draining Spot; waiting, nothing is terminated",
                    program.operationId());
        }
        String progress = "{\"message\":\"Spot instances remaining: " + s.spotNow() + "\",\"spotRemaining\":"
                + s.spotNow() + ",\"healthyInService\":" + s.healthyInService() + ",\"desired\":" + s.desired()
                + ",\"onDemandCapacityFailure\":" + odFailure + "}";
        if (s.spotNow() == 0 && s.healthyInService() >= s.desired()) {
            return done(progress);
        }
        return new StepOutcome.InProgress(AwsRolloutProperties.POLL, progress);
    }

    /** A failed launch that is not a Spot failure and names missing instance capacity. */
    private static boolean onDemandCapacityFailure(AwsRolloutObserver.Snapshot s, Instant now) {
        for (Activity a : s.activities()) {
            if (a.startTime() != null && a.startTime().isBefore(now.minus(RECENT))) {
                continue;
            }
            if ("Failed".equals(a.statusCodeAsString()) && a.description() != null
                    && a.description().startsWith("Launching")
                    && AwsSpotFailureMatcher.classify(a.statusMessage(), a.description())
                    == AwsSpotFailureMatcher.FailureClass.ON_DEMAND) {
                return true;
            }
        }
        return false;
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // every poll is a fresh read; execute is the probe
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Watch the group every minute until no Spot instance is left and every instance is healthy. "
                + "Nothing is terminated by hand.";
    }
}
