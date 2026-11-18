package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsMutatingClient;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.CreateOrUpdateTagsRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteTagsRequest;
import software.amazon.awssdk.services.autoscaling.model.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Step 6 (design-aws.md §2.3, FR-AWS-04, AC-AWS-04): the marker tag is written <em>last</em>, only
 * after the group is converted and verified, so a half-converted group never shows as managed.
 * {@code xamops:autospotting:group-id=<group uuid>} with {@code PropagateAtLaunch=false}; an ADOPT also
 * deletes the legacy {@code spot-enabled} tag. The tag write is idempotent, and the step skips the call
 * when the live tags already match.
 *
 * <p>A failure here leaves the group converted and verified, so it is never compensated. Every failure
 * is reported as TRANSIENT, which the runner retries; on the fifth attempt the step finishes Done with
 * a DR-3 (marker missing, MINOR) note instead, the group ends ACTIVE, and the next plan offers a re-tag.
 */
public final class WriteMarkerStep extends AwsStep {

    public static final String NAME = "WRITE_MARKER";
    /** Failed attempts after which the op finishes ACTIVE with DR-3 (design-aws.md §2.3 step 6). */
    public static final int MAX_ATTEMPTS = 5;

    private final int seq;

    /** @param seq the step's persisted {@code seq}, so the attempt count can be read from its row */
    public WriteMarkerStep(AwsStepServices services, AwsProgram program, int seq) {
        super(services, program);
        this.seq = seq;
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
        try {
            return write(ctx);
        } catch (RuntimeException e) {
            if (e instanceof com.xammer.cloud.service.autospotting.core.lease.LeaseLostException) {
                throw e;
            }
            if (attempts() >= MAX_ATTEMPTS) {
                return done("{\"markerWritten\":false,\"drift\":\"DR-3\",\"severity\":\"MINOR\"}");
            }
            return failed(ErrorKind.TRANSIENT, "MARKER_WRITE_FAILED", "The marker tag could not be written yet.");
        }
    }

    private StepOutcome write(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null) {
            throw new IllegalStateException("account missing");
        }
        AwsReadOnlyView view = services.view(account, program.region());
        Optional<AutoScalingGroup> raw = services.statusReader().describeGroup(view, program.groupName());
        if (raw.isEmpty()) {
            return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The Auto Scaling group no longer exists.");
        }
        AwsGroupFacts facts = AwsGroupFacts.from(program.region(), raw.get());
        String value = program.groupId().toString();
        boolean markerOk = value.equals(facts.tags().get(AwsGroupFacts.MARKER_TAG));
        boolean legacyGone = program.kind() != OperationKind.ADOPT || !facts.tags().containsKey(AwsGroupFacts.LEGACY_TAG);
        if (markerOk && legacyGone) {
            return done("{\"markerWritten\":true,\"alreadyPresent\":true}");
        }
        ctx.leaseHandle().assertHeld(ctx.callTimeout());
        AwsMutatingClient client = services.mutating(account, program.region());
        List<String> requestIds = new ArrayList<>();
        if (!markerOk) {
            var r = client.createOrUpdateTags(CreateOrUpdateTagsRequest.builder().tags(Tag.builder()
                    .resourceId(program.groupName()).resourceType("auto-scaling-group")
                    .key(AwsGroupFacts.MARKER_TAG).value(value).propagateAtLaunch(false).build()).build());
            addId(requestIds, r.responseMetadata());
        }
        if (!legacyGone) {
            ctx.leaseHandle().assertHeld(ctx.callTimeout());
            var r = client.deleteTags(DeleteTagsRequest.builder().tags(Tag.builder()
                    .resourceId(program.groupName()).resourceType("auto-scaling-group")
                    .key(AwsGroupFacts.LEGACY_TAG).build()).build());
            addId(requestIds, r.responseMetadata());
        }
        return new StepOutcome.Done("{\"markerWritten\":true}", requestIds);
    }

    private static void addId(List<String> ids, software.amazon.awssdk.awscore.AwsResponseMetadata meta) {
        if (meta != null && meta.requestId() != null) {
            ids.add(meta.requestId());
        }
    }

    private int attempts() {
        return services.facts().stepAttempts(program.operationId(), seq);
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return notYet();
        }
        AwsReadOnlyView view = services.view(account, program.region());
        Optional<AutoScalingGroup> raw = services.statusReader().describeGroup(view, program.groupName());
        if (raw.isPresent()) {
            AwsGroupFacts facts = AwsGroupFacts.from(program.region(), raw.get());
            boolean markerOk = program.groupId().toString().equals(facts.tags().get(AwsGroupFacts.MARKER_TAG));
            boolean legacyGone = program.kind() != OperationKind.ADOPT
                    || !facts.tags().containsKey(AwsGroupFacts.LEGACY_TAG);
            if (markerOk && legacyGone) {
                return done("{\"markerWritten\":true,\"alreadyPresent\":true}");
            }
        }
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return program.kind() == OperationKind.ADOPT
                ? "Tag the group as managed by XamOps (not copied to new instances) and remove the old spot-enabled tag."
                : "Tag the group as managed by XamOps (not copied to new instances). This is the last change.";
    }
}
