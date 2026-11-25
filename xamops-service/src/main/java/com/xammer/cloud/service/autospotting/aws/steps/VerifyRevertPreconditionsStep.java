package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.service.autospotting.aws.AwsFingerprint;
import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsMaintenancePolicy;
import com.xammer.cloud.service.autospotting.aws.AwsLaunchInfo;
import com.xammer.cloud.service.autospotting.aws.AwsPlanReader;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadException;
import com.xammer.cloud.service.autospotting.aws.AwsRestoreProgram;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.List;
import java.util.Optional;

/**
 * Step 0 of the REVERT program (design-aws.md §5.3). Read-only, and the revert precondition set of core §7.4
 * only: the group exists, no foreign instance refresh is running ({@code Failed(CONFLICT, FOREIGN_CHANGE)}, which
 * the runner turns into {@code WAITING(FOREIGN_CHANGE)}), the group still looks like the plan, the three-way check
 * resolves, and the permissions the later steps need are allowed. There is no eligibility, profile or
 * desired-size check, so a revert at desired 1, or of a group that gained a new blocker, is never refused here (DR-03).
 *
 * <p>Every failure here is before the first mutation, so the group stays in its state and the op ends FAILED with
 * {@code first_mutation_seq} null (DR-04). A missing {@code iam:PassRole} fails here, never after Spot was drained:
 * the plan offers the drain-only revert instead.
 */
public final class VerifyRevertPreconditionsStep extends AwsRevertStep {
    public static final String NAME = "VERIFY_PRECONDITIONS";

    public VerifyRevertPreconditionsStep(AwsStepServices services, AwsProgram program, AwsRestoreProgram.Inputs inputs) {
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
        AwsGroupFacts facts = AwsGroupFacts.from(program.region(), live.raw());
        if (services.reader().refreshInProgress(live.view(), program.groupName())) {
            return failed(ErrorKind.CONFLICT, "FOREIGN_CHANGE",
                    "An instance refresh is running on this group. XamOps will wait for it to finish.");
        }
        // The one form the revert plan was hashed with: PlanService and ConfirmService take ownership from the
        // plan's target (liveFingerprintFor), which is this program's config.
        String fingerprint = AwsFingerprint.of(facts, AwsMaintenancePolicy.owns(program.config()));
        if (!inputs.compensation()) {
            Optional<String> planned = services.facts().planLiveFingerprint(program.planId());
            if (planned.isEmpty() || !planned.get().equals(fingerprint)) {
                return failed(ErrorKind.VALIDATION, "PLAN_STALE",
                        "The group changed after this plan was made. Review a fresh revert plan.");
            }
        }
        AwsRestoreProgram.Resolved resolved = AwsRestoreProgram.resolve(services, live.account(), live.view(),
                live.raw(), inputs);
        if (!inputs.compensation()) {
            String acks = services.facts().planConfirmAcks(program.planId()).orElse("");
            for (String ack : resolved.diff().acks()) {
                if (!acks.contains(ack)) {
                    return failed(ErrorKind.VALIDATION, "ACK_REQUIRED",
                            "This revert needs an acknowledgement (" + ack + "). Review a fresh revert plan.");
                }
            }
        }
        if (live.account().getRoleArn() == null) {
            return failed(ErrorKind.PERMISSION, "PERMISSION_MISSING", "No connection role is stored for this account.");
        }
        boolean sendsTemplate = inputs.mode() != AwsRestoreProgram.Mode.DRAIN_ONLY;
        AwsLaunchInfo launch = null;
        if (sendsTemplate && resolved.diff().target().template() != null) {
            try {
                launch = services.reader().launch(live.account(), live.view(), resolved.diff().target().template());
            } catch (AwsReadException e) {
                if (e.kind() != ErrorKind.NOT_FOUND) {
                    throw e;
                }
            }
        }
        AwsPlanReader.RevertPermissions perm = services.reader().revertPermissions(live.view(),
                live.account().getRoleArn(), facts.arn(), launch);
        if (!perm.missing().isEmpty()) {
            return failed(ErrorKind.PERMISSION, "PERMISSION_MISSING",
                    "The connection role is missing: " + String.join(", ", perm.missing()) + ".");
        }
        if (sendsTemplate && perm.passRoleDenied()) {
            return failed(ErrorKind.PERMISSION, "REVERT_NEEDS_PASSROLE",
                    "The connection role may not pass the instance-profile role to EC2. Update the stack, or plan "
                            + "the revert again to drain Spot only. Nothing was changed.");
        }
        if (sendsTemplate && perm.checkFailed() && !inputs.compensation()) {
            return failed(ErrorKind.PERMISSION, "CHECK_FAILED",
                    "The permission check could not be completed. Nothing was changed.");
        }
        List<String> reasons = resolved.diff().inexactReasons();
        return done("{\"fingerprint\":\"" + fingerprint + "\",\"mode\":\"" + inputs.mode() + "\",\"exact\":"
                + (reasons.isEmpty() && inputs.mode() != AwsRestoreProgram.Mode.DRAIN_ONLY)
                + ",\"keptLive\":" + resolved.diff().kept().size() + "}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // read-only: re-running is the check
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that the group still matches the revert plan, that no instance refresh is running and that "
                + "the connection role may make every change the revert needs. Nothing is changed.";
    }
}
