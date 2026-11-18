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

    /** A revert factory that records the call and builds the same failing program as the stub. */
    static final class RecordingRevert implements AwsRevertProgramFactory {
        int calls;

        @Override
        public com.xammer.cloud.service.autospotting.spi.StepProgram program(
                com.xammer.cloud.service.autospotting.spi.OperationContext op,
                com.xammer.cloud.service.autospotting.spi.SnapshotPayload snapshot) {
            calls++;
            return new StubAwsRevertProgramFactory().program(op, snapshot);
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
                new AwsSnapshotStore(jdbc, ctx.getBean(FencedTx.class)), new AwsLaunchEventRecorder(jdbc), revert);
        runner = new OperationRunner(ctx.getBean(SpotOperationRepository.class), ctx.getBean(SpotGroupRepository.class),
                store, ctx.getBean(StepExecutor.class), ctx.getBean(GroupLifecycleService.class),
                ctx.getBean(FencedTx.class), ctx.getBean(AutoSpottingAuditWriter.class),
                ctx.getBean(SwitchService.class), ctx.getBean(SpotOpsLifecycle.class),
                new ProviderRegistry(List.of(provider)),
                ctx.getBean(com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics.class));
        lease = ctx.getBean(LeaseService.class).acquireGroup(f.groupId, LeaseService.Component.DISPATCHER).orElseThrow();
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
