package com.xammer.cloud.service.autospotting.gcp.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.gcp.GcpMigStatusReader;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpSnapshotCodec;
import com.xammer.cloud.service.autospotting.gcp.GcpSnapshotStore;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.List;
import java.util.Optional;

/**
 * Step 1 (design-gcp.md §2.6, AC-GCP-01): the ORIGINAL template, versions and update policy are persisted before
 * the first change. The row is immutable and keyed by the operation id, so a re-run after a crash finds it.
 * ENABLE captures the live MIG; ADOPT uses the reconstructed snapshot discovery wrote. A snapshot that cannot
 * be read exactly (a referenced template unreadable) fails the op here, before anything changed. No cloud mutation.
 */
public final class PersistSnapshotStep extends GcpStep {
    public static final String NAME = "PERSIST_SNAPSHOT";

    public PersistSnapshotStep(GcpStepServices services, GcpProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        Optional<GcpSnapshotStore.Stored> existing = services.snapshots().findByOp(program.operationId());
        if (existing.isPresent()) {
            return output(existing.get());
        }
        if (program.kind() == OperationKind.ADOPT) {
            Optional<GcpSnapshotStore.Stored> linked = services.snapshots().findLinked(program.groupId());
            return linked.isPresent() ? output(linked.get())
                    : failed(ErrorKind.VALIDATION, "SNAPSHOT_MISSING",
                    "No saved original configuration exists for this group. Plan again.");
        }
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return accountMissing();
        }
        GcpReadOnlyView view = services.view(account);
        GcpMigStatusReader.Read read = services.statusReader().readForSnapshot(view, program.groupKey());
        if (read.group() == null) {
            return groupMissing();
        }
        SnapshotPayload payload = GcpSnapshotCodec.encode(read.group(), read.autoscaler(), read.templates(), false, List.of());
        if (!payload.exact()) {
            return failed(ErrorKind.VALIDATION, "SNAPSHOT_INEXACT",
                    "The original configuration could not be read completely, so nothing was changed.");
        }
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
                        ? services.snapshots().findLinked(program.groupId()).map(this::output) : Optional.empty())
                .orElseGet(GcpStep::notYet);
    }

    private StepOutcome output(GcpSnapshotStore.Stored s) {
        return done("{\"snapshotId\":\"" + s.id() + "\",\"exact\":" + s.exact() + ",\"sha256\":\"" + s.payloadSha256() + "\"}");
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Save the instance group's original template and update policy, so it can be restored exactly.";
    }
}
