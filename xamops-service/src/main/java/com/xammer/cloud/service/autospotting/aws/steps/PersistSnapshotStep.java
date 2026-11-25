package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.aws.AwsGroupFacts;
import com.xammer.cloud.service.autospotting.aws.AwsLaunchInfo;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsSnapshotCodec;
import com.xammer.cloud.service.autospotting.aws.AwsSnapshotStore;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.util.Optional;

/**
 * Step 2 (design-aws.md §2.3, AC-AWS-04): the original configuration is persisted before the first
 * change. The row is immutable and keyed by the operation id, so a re-run after a crash finds it.
 * ENABLE captures the live group; ADOPT uses the reconstructed snapshot discovery wrote (PO-12) and
 * writes nothing new. No cloud mutation, and none has happened yet; a database error is retried by the
 * runner as a transient failure.
 */
public final class PersistSnapshotStep extends AwsStep {

    public static final String NAME = "PERSIST_SNAPSHOT";

    public PersistSnapshotStep(AwsStepServices services, AwsProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        Optional<AwsSnapshotStore.Stored> existing = services.snapshots().findByOp(program.operationId());
        if (existing.isPresent()) {
            return output(existing.get());
        }
        if (program.kind() == OperationKind.ENABLE
                && program.config().path(com.xammer.cloud.service.autospotting.aws.AwsSpotProvider.KEEP_LINKED_SNAPSHOT)
                        .asBoolean(false)) {
            // D-23b-03: an enable after a drain-only revert. The drained group still carries XamOps' configuration,
            // so the linked snapshot (taken before the first enable) stays the original the next revert restores.
            Optional<AwsSnapshotStore.Stored> linked = services.snapshots().findLinked(program.groupId());
            if (linked.isPresent()) {
                return output(linked.get());
            }
        }
        if (program.kind() == OperationKind.ADOPT) {
            Optional<AwsSnapshotStore.Stored> linked = services.snapshots().findLinked(program.groupId());
            return linked.isPresent() ? output(linked.get())
                    : failed(ErrorKind.VALIDATION, "SNAPSHOT_MISSING",
                            "No saved original configuration exists for this group. Plan again.");
        }
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return failed(ErrorKind.VALIDATION, "ACCOUNT_MISSING", "The connected account could not be found.");
        }
        AwsReadOnlyView view = services.view(account, program.region());
        Optional<AutoScalingGroup> raw = services.statusReader().describeGroup(view, program.groupName());
        if (raw.isEmpty()) {
            return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The Auto Scaling group no longer exists.");
        }
        AwsGroupFacts facts = AwsGroupFacts.from(program.region(), raw.get());
        AwsLaunchInfo launch = facts.launchTemplate() == null ? null
                : services.reader().launch(account, view, facts.launchTemplate());
        AwsSnapshotCodec.Context context = new AwsSnapshotCodec.Context(
                launch == null ? null : launch.resolvedVersion(), launch == null ? null : launch.versionSha256(),
                null, java.util.List.of(), java.util.List.of(), java.util.List.of());
        SnapshotPayload payload = AwsSnapshotCodec.encode(program.region(), raw.get(), context, false);
        try {
            return output(services.snapshots().persist(ctx.leaseHandle(), program.operationId(), program.groupId(),
                    payload, services.clock().instant()));
        } catch (org.springframework.dao.DataAccessException e) {
            return failed(ErrorKind.TRANSIENT, "SNAPSHOT_WRITE_FAILED", "The original configuration could not be saved yet.");
        }
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return services.snapshots().findByOp(program.operationId()).<StepOutcome>map(this::output)
                .or(() -> program.kind() == OperationKind.ADOPT
                        ? services.snapshots().findLinked(program.groupId()).map(this::output)
                        : Optional.empty())
                .orElseGet(AwsStep::notYet);
    }

    private StepOutcome output(AwsSnapshotStore.Stored s) {
        return done("{\"snapshotId\":\"" + s.id() + "\",\"exact\":" + s.exact()
                + ",\"sha256\":\"" + s.payloadSha256() + "\"}");
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return program.kind() == OperationKind.ADOPT
                ? "Keep the original configuration saved when the group was found, so it can be restored."
                : "Save the group's original configuration, so it can be restored exactly.";
    }
}
