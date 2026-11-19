package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.autospotting.pg.OpsContexts;
import com.xammer.cloud.autospotting.pg.PgHarness;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
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
import com.xammer.cloud.service.autospotting.core.ops.OperationFacts;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.OperationRunner;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.StepExecutor;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One real PostgreSQL, one real runner, lease, stores, lifecycle and GCP provider over a stubbed Compute, for the
 * Phase 28 ITs (revert, fallback). The group is first driven through a real ENABLE to ACTIVE, so every later
 * operation starts from the state the product would really be in. The mutation switches are on in this context only.
 * No GCP call is made.
 */
final class GcpOpsHarness implements AutoCloseable {
    final PgHarness.Database db = PgHarness.emptySchema();
    final AnnotationConfigApplicationContext ctx = OpsContexts.contextFor(db);
    final JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
    final GcpConversionFixture f = new GcpConversionFixture();
    final OperationStore store = ctx.getBean(OperationStore.class);
    final LeaseHandle lease;
    OperationRunner runner;
    UUID opId;

    GcpOpsHarness() {
        insertGroup();
        lease = ctx.getBean(LeaseService.class).acquireGroup(f.groupId, LeaseService.Component.DISPATCHER).orElseThrow();
        restart();
    }

    /** The real stores a new instance builds after a restart. */
    GcpStepServices services() {
        OperationFacts facts = new OperationFacts(ctx.getBean(SpotPlanRepository.class),
                ctx.getBean(SpotOperationRepository.class), ctx.getBean(SpotOperationStepRepository.class));
        return new GcpStepServices(f.accounts, a -> f.gcp.view(), a -> f.mutating, f.gate, f.evaluator,
                new GcpMigStatusReader(), new GcpSnapshotStore(jdbc, ctx.getBean(FencedTx.class)),
                new GcpTemplateRegistry(jdbc), new GcpVersionTargetEvaluator(), facts, f.clock, Duration.ofDays(7));
    }

    GcpMigSpotProvider provider() {
        GcpStepServices services = services();
        GcpViewSource views = a -> f.gcp.view();
        var fallback = com.xammer.cloud.service.autospotting.core.FallbackProperties.defaults();
        return new GcpMigSpotProvider(f.accounts, views, f.evaluator, new GcpMigStatusReader(),
                new GcpEstimatePriceService(views, f.shapes, java.time.Clock.systemUTC()), f.gate, false, false, f.clock,
                new GcpStepLibrary(services), new GcpRestoreProgram(services), new GcpFallbackPrograms(services, fallback),
                new GcpFallbackRules(GcpHealthProperties.defaults(), fallback));
    }

    /** A new runner over a new provider: nothing carried over in memory. */
    void restart() {
        runner = new OperationRunner(ctx.getBean(SpotOperationRepository.class), ctx.getBean(SpotGroupRepository.class),
                store, ctx.getBean(StepExecutor.class), ctx.getBean(GroupLifecycleService.class),
                ctx.getBean(FencedTx.class), ctx.getBean(AutoSpottingAuditWriter.class), ctx.getBean(SwitchService.class),
                ctx.getBean(SpotOpsLifecycle.class), new ProviderRegistry(List.of(provider())),
                ctx.getBean(com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics.class));
    }

    private void insertGroup() {
        Instant now = f.clock.instant();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", f.groupId);
        row.put("provider", "GCP");
        row.put("cloud_account_id", 3L);
        row.put("client_id", 1L);
        row.put("group_key", f.key);
        row.put("state", "UNMANAGED");
        row.put("config_mode", "MANAGED");
        row.put("state_changed_at", Timestamp.from(now));
        row.put("created_by", "test");
        row.put("created_at", Timestamp.from(now));
        row.put("updated_at", Timestamp.from(now));
        PgHarness.insertRow(jdbc, "spot_group", row);
    }

    void insertPlan(UUID planId, String kind, String fromState, String fingerprint, String body, String warningIds,
                    String acks) {
        Instant now = f.clock.instant();
        jdbc.update("INSERT INTO spot_plan (id, group_id, cloud_account_id, kind, status, from_state, version,"
                        + " schema_version, live_fingerprint, body, requires_full_spot_optin, warning_ids, confirm_acks,"
                        + " dry_run, created_by, created_at, expires_at) VALUES (?, ?, 3, ?, 'CONFIRMED', ?, 1, 1, ?,"
                        + " ?::jsonb, false, ?::jsonb, ?::jsonb, false, 'test', ?, ?)",
                planId, f.groupId, kind, fromState, fingerprint, body, warningIds, acks, Timestamp.from(now),
                Timestamp.from(now.plusSeconds(3600)));
    }

    /** ENABLE at floor {@code n} through the real runner until ACTIVE; the stub MIG ends converted. */
    void enableToActive(int n) {
        PlanBody plan = provider().createPlan(new PlanRequest(new GroupRef(Provider.GCP, 3L, f.key, null),
                OperationKind.ENABLE, new Profile("conservative", null)), f.gcp.view());
        jdbc.update("UPDATE spot_group SET state = 'APPLYING' WHERE id = ?", f.groupId);
        insertPlan(f.planId, "ENABLE", "UNMANAGED", GcpFingerprint.planHash(GcpMigFacts.from(FakeGcp.PROJECT, f.gcp.mig())),
                "{\"current\":" + plan.currentJson() + ",\"target\":" + plan.targetJson() + "}", "[]", "[]");
        String target = n == 3 ? plan.targetJson() : f.target(n).toString();
        store.insertQueued(OperationRecord.builder(f.opId, f.groupId, 3L, OperationKind.ENABLE).planId(f.planId)
                .priorState(GroupState.UNMANAGED).compensationTarget(CompensationTargets.forKind(OperationKind.ENABLE))
                .targetConfigJson(target).requestedBy("tester").build());
        opId = f.opId;
        claimAndRun();
        String spot = jdbc.queryForObject("SELECT self_link FROM spot_gcp_template WHERE created_by_op = ?", String.class, f.opId);
        f.completeRollout(n, spot);
        for (int i = 0; i < 40 && !"SUCCEEDED".equals(opStatus()); i++) {
            f.clock.advance(Duration.ofSeconds(61));
            makeDue();
            claimAndRun();
        }
        if (!"SUCCEEDED".equals(opStatus()) || !"ACTIVE".equals(groupState())) {
            throw new AssertionError("enable did not reach ACTIVE: " + opStatus() + " / " + groupState());
        }
        f.mutating.calls.clear();
        f.mutating.patches.clear();
        f.mutating.inserted.clear();
        f.lastProgress = null;
    }

    /** Queue and claim a REVERT with a CONFIRMED plan against the live MIG; returns the op id. */
    UUID queueRevert(String warningIds, String acks, String bodyJson) {
        UUID planId = UUID.randomUUID();
        UUID revertOp = UUID.randomUUID();
        insertPlan(planId, "REVERT", "ACTIVE", GcpFingerprint.planHash(GcpMigFacts.from(FakeGcp.PROJECT, f.gcp.mig())),
                bodyJson, warningIds, acks);
        store.insertQueued(OperationRecord.builder(revertOp, f.groupId, 3L, OperationKind.REVERT).planId(planId)
                .priorState(GroupState.valueOf(groupState())).compensationTarget(CompensationTarget.NONE)
                .requestedBy("tester").build());
        opId = revertOp;
        return revertOp;
    }

    /** Queue a system FALLBACK_ENTER or FALLBACK_EXIT (no plan). */
    UUID queueFallback(OperationKind kind, GroupState prior) {
        UUID id = UUID.randomUUID();
        OperationRecord.Builder b = OperationRecord.builder(id, f.groupId, 3L, kind).priorState(prior)
                .compensationTarget(kind == OperationKind.FALLBACK_EXIT ? CompensationTarget.FALLBACK : CompensationTarget.NONE)
                .requestedBy("system");
        if (kind == OperationKind.FALLBACK_EXIT) {
            b.targetConfigJson(jdbc.queryForObject("SELECT desired_config::text FROM spot_group WHERE id = ?", String.class,
                    f.groupId));
        }
        store.insertQueued(b.build());
        opId = id;
        return id;
    }

    void claimAndRun() {
        store.claimForRun(opId);
        runner.runClaimed(lease, opId);
    }

    void makeDue() {
        jdbc.update("UPDATE spot_operation SET next_attempt_at = now() - interval '1 second' WHERE id = ?", opId);
    }

    String opStatus() {
        return jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, opId);
    }

    String opPhase() {
        return jdbc.queryForObject("SELECT phase FROM spot_operation WHERE id = ?", String.class, opId);
    }

    String groupState() {
        return jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, f.groupId);
    }

    String groupColumn(String column) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM spot_group WHERE id = ?", String.class, f.groupId);
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
