package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsMutatingClient;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsRestoreProgram;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.services.autoscaling.model.DeleteTagsRequest;
import software.amazon.awssdk.services.autoscaling.model.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 5 of the REVERT program (design-aws.md §5.3): {@code DeleteTags} for {@code xamops:autospotting:group-id}
 * and the legacy {@code spot-enabled}, last, so a half-reverted group still shows as managed. Idempotent: no call
 * when neither tag is there. A failure is reported TRANSIENT and retried; on the fifth attempt the step finishes
 * Done with DR-3 (marker still present, MINOR) so the group ends REVERTED rather than stuck on a tag.
 */
public final class RemoveMarkerStep extends AwsRevertStep {
    public static final String NAME = "REMOVE_MARKER";
    /** Failed attempts after which the op finishes REVERTED with DR-3. */
    public static final int MAX_ATTEMPTS = 5;
    private final int seq;

    /** @param seq the step's persisted {@code seq}, so the attempt count can be read from its row */
    public RemoveMarkerStep(AwsStepServices services, AwsProgram program, AwsRestoreProgram.Inputs inputs, int seq) {
        super(services, program, inputs);
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
            return remove(ctx);
        } catch (LeaseLostException e) {
            throw e;
        } catch (RuntimeException e) {
            if (services.facts().stepAttempts(program.operationId(), seq) >= MAX_ATTEMPTS) {
                return done("{\"markerRemoved\":false,\"drift\":\"DR-3\",\"severity\":\"MINOR\"}");
            }
            return failed(ErrorKind.TRANSIENT, "MARKER_REMOVE_FAILED", "The marker tag could not be removed yet.");
        }
    }

    private StepOutcome remove(StepContext ctx) {
        Live live = live();
        if (live.failure() != null) {
            return live.failure();
        }
        AwsGroupFacts facts = AwsGroupFacts.from(program.region(), live.raw());
        List<String> present = new ArrayList<>();
        for (String key : List.of(AwsGroupFacts.MARKER_TAG, AwsGroupFacts.LEGACY_TAG)) {
            if (facts.tags().containsKey(key)) {
                present.add(key);
            }
        }
        if (present.isEmpty()) {
            return done("{\"markerRemoved\":true,\"alreadyAbsent\":true}");
        }
        ctx.leaseHandle().assertHeld(ctx.callTimeout());
        AwsMutatingClient client = services.mutating(live.account(), program.region());
        DeleteTagsRequest.Builder b = DeleteTagsRequest.builder();
        for (String key : present) {
            b.tags(Tag.builder().resourceId(program.groupName()).resourceType("auto-scaling-group").key(key).build());
        }
        var response = client.deleteTags(b.build());
        String requestId = response.responseMetadata() == null ? null : response.responseMetadata().requestId();
        return new StepOutcome.Done("{\"markerRemoved\":true}", requestId == null ? List.of() : List.of(requestId));
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        Live live = live();
        if (live.failure() == null) {
            AwsGroupFacts facts = AwsGroupFacts.from(program.region(), live.raw());
            if (!facts.tags().containsKey(AwsGroupFacts.MARKER_TAG) && !facts.tags().containsKey(AwsGroupFacts.LEGACY_TAG)) {
                return done("{\"markerRemoved\":true,\"alreadyAbsent\":true}");
            }
        }
        return notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Remove the XamOps tag from the group (and the old spot-enabled tag). This is the last change.";
    }
}
