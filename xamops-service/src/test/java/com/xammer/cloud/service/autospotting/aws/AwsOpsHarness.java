package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.autospotting.pg.OpsContexts;
import com.xammer.cloud.autospotting.pg.PgHarness;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationStepRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.service.autospotting.core.SpotOpsLifecycle;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.OperationRunner;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.StepExecutor;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A real PostgreSQL, the real operation runner, lease, store and lifecycle, and the real AWS provider
 * over the stubbed SDK (no AWS call, ever). One group row, one plan row and one claimed op, ready to
 * run. Shared by {@code AwsEnableEndToEndIT} and {@code AwsRolloutShortageIT}.
 */
final class AwsOpsHarness implements AutoCloseable {

    /** The real REVERT program, counting how often it was asked for a program. */
    static final class RecordingRevert implements AwsRevertProgramFactory {
        int calls;
        AwsRestoreProgram delegate;

        @Override
        public com.xammer.cloud.service.autospotting.spi.StepProgram program(
                com.xammer.cloud.service.autospotting.spi.OperationContext op,
                com.xammer.cloud.service.autospotting.spi.SnapshotPayload snapshot) {
            calls++;
            return delegate.program(op, snapshot);
        }

        @Override
        public PlanBody plan(com.xammer.cloud.service.autospotting.spi.PlanRequest request,
                             com.xammer.cloud.domain.CloudAccount account) {
            return delegate.plan(request, account);
        }
    }

    final PgHarness.Database db;
    final AnnotationConfigApplicationContext ctx;
    final JdbcTemplate jdbc;
    final AwsConversionFixture f;
    final AwsSpotProvider provider;
    final RecordingRevert revert = new RecordingRevert();
    final OperationRunner runner;
    final OperationStore store;
    final LeaseHandle lease;
    final PlanBody plan;

    AwsOpsHarness(AutoScalingGroup group, OperationKind kind, GroupState groupState, GroupState priorState,
                  String profile) {
        this(group, kind, groupState, priorState, profile, true);
    }

    AwsOpsHarness(AutoScalingGroup group, OperationKind kind, GroupState groupState, GroupState priorState,
                  String profile, boolean mutationsOpen) {
        db = PgHarness.emptySchema();
        ctx = OpsContexts.contextFor(db);
        jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
        f = new AwsConversionFixture(group, mutationsOpen);
        plan = f.plan(profile);
        store = ctx.getBean(OperationStore.class);
        insertGroup(groupState);
        insertPlan(kind);
        store.insertQueued(OperationRecord.builder(f.opId, f.groupId, 7L, kind)
                .planId(f.planId)
                .priorState(priorState)
                .compensationTarget(CompensationTargets.forKind(kind))
                .targetConfigJson(plan.targetJson())
                .compensationConfigJson(CompensationTargets.forKind(kind)
                        == com.xammer.cloud.domain.autospotting.CompensationTarget.PRIOR_CONFIG
                        ? plan.currentJson() : null)
                .requestedBy("tester").build());
        store.claimForRun(f.opId);
        provider = f.providerWith(new com.xammer.cloud.service.autospotting.core.ops.OperationFacts(
                        ctx.getBean(SpotPlanRepository.class), ctx.getBean(SpotOperationRepository.class),
                        ctx.getBean(SpotOperationStepRepository.class)),
                new AwsSnapshotStore(jdbc, ctx.getBean(FencedTx.class)), new AwsLaunchEventRecorder(jdbc), services -> {
                    revert.delegate = new AwsRestoreProgram(services, new com.xammer.cloud.service.autospotting.core
                            .GroupLookup(ctx.getBean(SpotGroupRepository.class)));
                    return revert;
                });
        runner = newRunner();
        lease = ctx.getBean(LeaseService.class).acquireGroup(f.groupId, LeaseService.Component.DISPATCHER).orElseThrow();
    }

    /**
     * A REVERT ready to run: {@code live} is the converted group, {@code original} what it was before ENABLE
     * (stored as the group's linked snapshot), the group row is in {@code groupState} with {@code desired_config}
     * set, and the plan is the provider's own REVERT plan with every warning acknowledged at confirm.
     */
    AwsOpsHarness(AutoScalingGroup live, AutoScalingGroup original, GroupState groupState, String desiredConfig) {
        this(live, original, groupState, desiredConfig, true);
    }

    /** As above; {@code claim=false} leaves the REVERT op QUEUED (an op it preempts must end first). */
    AwsOpsHarness(AutoScalingGroup live, AutoScalingGroup original, GroupState groupState, String desiredConfig,
                  boolean claim) {
        this(live, original, groupState, desiredConfig, claim, x -> { });
    }

    /** As above; {@code beforePlan} stubs the fake AWS before the plan reads it (the plan reader caches the template). */
    AwsOpsHarness(AutoScalingGroup live, AutoScalingGroup original, GroupState groupState, String desiredConfig,
                  boolean claim, java.util.function.Consumer<AwsConversionFixture> beforePlan) {
        db = PgHarness.emptySchema();
        ctx = OpsContexts.contextFor(db);
        jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
        f = new AwsConversionFixture(live, true);
        beforePlan.accept(f);
        store = ctx.getBean(OperationStore.class);
        insertGroup(groupState);
        jdbc.update("UPDATE spot_group SET desired_config = ?::jsonb WHERE id = ?", desiredConfig, f.groupId);
        UUID snapshotId = UUID.randomUUID();
        com.xammer.cloud.service.autospotting.spi.SnapshotPayload snap = AwsSnapshotCodec.encode(FakeAws.REGION, original,
                AwsSnapshotCodec.Context.none(), false);
        jdbc.update("INSERT INTO spot_group_snapshot (id, group_id, provider, schema_version, exact, payload,"
                        + " payload_sha256, captured_at, captured_by_op) VALUES (?, ?, 'AWS', ?, true, ?::jsonb, ?, ?, ?)",
                snapshotId, f.groupId, snap.schemaVersion(), snap.payloadJson(), snap.payloadSha256(),
                Timestamp.from(f.clock.instant()), UUID.randomUUID());
        jdbc.update("UPDATE spot_group SET snapshot_id = ? WHERE id = ?", snapshotId, f.groupId);
        provider = f.providerWith(new com.xammer.cloud.service.autospotting.core.ops.OperationFacts(
                        ctx.getBean(SpotPlanRepository.class), ctx.getBean(SpotOperationRepository.class),
                        ctx.getBean(SpotOperationStepRepository.class)),
                new AwsSnapshotStore(jdbc, ctx.getBean(FencedTx.class)), new AwsLaunchEventRecorder(jdbc), services -> {
                    revert.delegate = new AwsRestoreProgram(services, new com.xammer.cloud.service.autospotting.core
                            .GroupLookup(ctx.getBean(SpotGroupRepository.class)));
                    return revert;
                });
        plan = provider.createPlan(new com.xammer.cloud.service.autospotting.spi.PlanRequest(
                new com.xammer.cloud.service.autospotting.spi.GroupRef(com.xammer.cloud.domain.autospotting.Provider.AWS,
                        7L, FakeAws.REGION + "/web-asg", null), OperationKind.REVERT, null), f.aws.view());
        insertRevertPlan();
        store.insertQueued(OperationRecord.builder(f.opId, f.groupId, 7L, OperationKind.REVERT)
                .planId(f.planId).priorState(groupState)
                .compensationTarget(CompensationTargets.forKind(OperationKind.REVERT))
                .targetConfigJson(plan.targetJson()).requestedBy("tester").build());
        if (claim) {
            store.claimForRun(f.opId);
        }
        runner = newRunner();
        lease = ctx.getBean(LeaseService.class).acquireGroup(f.groupId, LeaseService.Component.DISPATCHER).orElseThrow();
    }

    final UUID enableOpId = UUID.randomUUID();

    /** An ENABLE that already changed the group and waits for its rollout (WAITING, first mutation at step 3). */
    void startEnableInFlight(String targetJson) {
        jdbc.update("UPDATE spot_group SET state = 'APPLYING' WHERE id = ?", f.groupId);
        store.insertQueued(OperationRecord.builder(enableOpId, f.groupId, 7L, OperationKind.ENABLE)
                .priorState(GroupState.UNMANAGED)
                .compensationTarget(com.xammer.cloud.domain.autospotting.CompensationTarget.SNAPSHOT)
                .targetConfigJson(targetJson).requestedBy("tester").build());
        store.claimForRun(enableOpId);
        store.park(enableOpId, com.xammer.cloud.domain.autospotting.WaitReason.CLOUD_PROGRESS,
                Instant.now().minusSeconds(1), 4, null);
        jdbc.update("UPDATE spot_operation SET first_mutation_seq = 3 WHERE id = ?", enableOpId);
    }

    private OperationRunner newRunner() {
        return new OperationRunner(ctx.getBean(SpotOperationRepository.class), ctx.getBean(SpotGroupRepository.class),
                store, ctx.getBean(StepExecutor.class), ctx.getBean(GroupLifecycleService.class),
                ctx.getBean(FencedTx.class), ctx.getBean(AutoSpottingAuditWriter.class),
                ctx.getBean(SwitchService.class), ctx.getBean(SpotOpsLifecycle.class),
                new ProviderRegistry(List.of(provider)),
                ctx.getBean(com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics.class));
    }

    /** The REVERT plan row: the live fingerprint now, the provider's body, and every warning acknowledged. */
    private void insertRevertPlan() {
        Instant now = f.clock.instant();
        String fingerprint = AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, f.group()));
        String body = "{\"current\":" + plan.currentJson() + ",\"target\":" + plan.targetJson()
                + ",\"revertPath\":" + plan.revertPathJson() + "}";
        StringBuilder acks = new StringBuilder("[");
        for (var w : plan.eligibility().warnings()) {
            acks.append(acks.length() > 1 ? "," : "").append('"').append(w.id()).append('"');
        }
        acks.append(']');
        jdbc.update("INSERT INTO spot_plan (id, group_id, cloud_account_id, kind, status, from_state, version,"
                        + " schema_version, live_fingerprint, body, requires_full_spot_optin, warning_ids, confirm_acks,"
                        + " dry_run, created_by, created_at, expires_at) VALUES (?, ?, 7, 'REVERT', 'CONFIRMED',"
                        + " 'ACTIVE', 1, 1, ?, ?::jsonb, false, '[]'::jsonb, ?::jsonb, false, 'test', ?, ?)",
                f.planId, f.groupId, fingerprint, body, acks.toString(), Timestamp.from(now),
                Timestamp.from(now.plusSeconds(3600)));
    }

    private void insertGroup(GroupState state) {
        Instant now = f.clock.instant();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", f.groupId);
        row.put("provider", "AWS");
        row.put("cloud_account_id", 7L);
        row.put("client_id", 1L);
        row.put("group_key", FakeAws.REGION + "/web-asg");
        row.put("state", state.name());
        row.put("config_mode", "MANAGED");
        row.put("state_changed_at", Timestamp.from(now));
        row.put("created_by", "test");
        row.put("created_at", Timestamp.from(now));
        row.put("updated_at", Timestamp.from(now));
        PgHarness.insertRow(jdbc, "spot_group", row);
    }

    private void insertPlan(OperationKind kind) {
        Instant now = f.clock.instant();
        String fingerprint = AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, f.group()));
        String body = "{\"current\":" + plan.currentJson() + ",\"target\":" + plan.targetJson() + "}";
        jdbc.update("INSERT INTO spot_plan (id, group_id, cloud_account_id, kind, status, from_state, version,"
                        + " schema_version, live_fingerprint, body, requires_full_spot_optin, warning_ids, dry_run,"
                        + " created_by, created_at, expires_at) VALUES (?, ?, 7, ?, 'CONFIRMED', 'UNMANAGED', 1, 1,"
                        + " ?, ?::jsonb, false, '[]'::jsonb, false, 'test', ?, ?)",
                f.planId, f.groupId, kind.name(), fingerprint, body, Timestamp.from(now),
                Timestamp.from(now.plusSeconds(3600)));
    }

    void engageSwitch(String scopeKey) {
        jdbc.update("INSERT INTO autospotting_switch (scope_key, engaged, reason, changed_by, changed_at, version)"
                + " VALUES (?, true, 'test', 'tester', ?, 0)", scopeKey, Timestamp.from(f.clock.instant()));
    }

    /** Claim the op (QUEUED or WAITING) and run it to its next boundary. */
    void claimAndRun() {
        store.claimForRun(f.opId);
        runner.runClaimed(lease, f.opId);
    }

    String opStatus() {
        return jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, f.opId);
    }

    String opPhase() {
        return jdbc.queryForObject("SELECT phase FROM spot_operation WHERE id = ?", String.class, f.opId);
    }

    String opWaitReason() {
        return jdbc.queryForObject("SELECT wait_reason FROM spot_operation WHERE id = ?", String.class, f.opId);
    }

    String opError() {
        return jdbc.queryForObject("SELECT error_code FROM spot_operation WHERE id = ?", String.class, f.opId);
    }

    String groupState() {
        return jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, f.groupId);
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Override
    public void close() {
        ctx.close();
        db.close();
    }
}
