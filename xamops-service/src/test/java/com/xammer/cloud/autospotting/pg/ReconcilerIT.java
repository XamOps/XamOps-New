package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.reconcile.SpotReconciler;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconciler sweep on real PostgreSQL (design-core.md §5.1, §5.2, §6.4; plan-phase-08.md task
 * 8.1 verify). Covers: a group held by a running op gets only the health pass; engaging
 * {@code ACCOUNT:{id}} pauses a stable group with {@code effective_at} = the switch's
 * {@code changed_at}; the GR-06 case (engage the switch, a DEGRADED signal, the written transition is
 * the pause, not ACTIVE → DEGRADED); release restores {@code paused_from_state}; a drift mismatch
 * parks the group PAUSED(DRIFT).
 */
@Tag("containers")
class ReconcilerIT {

    private PgHarness.Database db;
    private AnnotationConfigApplicationContext ctx;
    private JdbcTemplate jdbc;
    private SpotReconciler reconciler;
    private SpotGroupRepository groups;
    private LeaseService leaseService;
    private TestReconcileProvider provider;

    @BeforeEach
    void setUp() {
        db = PgHarness.emptySchema();
        provider = new TestReconcileProvider();
        AutoSpottingReconcileBeans.provider = provider;
        AutoSpottingReconcileBeans.discovery = null;

        ctx = new AnnotationConfigApplicationContext();
        DriverManagerDataSource ds = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        ctx.getBeanFactory().registerSingleton("dataSource", ds);
        ctx.register(AutoSpottingReconcileBeans.class);
        ctx.refresh();

        jdbc = new JdbcTemplate(ds);
        reconciler = ctx.getBean(SpotReconciler.class);
        groups = ctx.getBean(SpotGroupRepository.class);
        leaseService = ctx.getBean(LeaseService.class);
    }

    @AfterEach
    void tearDown() {
        AutoSpottingReconcileBeans.provider = null;
        if (ctx != null) {
            ctx.close();
        }
        if (db != null) {
            db.close();
        }
    }

    /** Finding #5: an APPLYING group whose only op is terminal is stuck once past the threshold, and is counted. */
    @Test
    void anApplyingGroupWithOnlyATerminalOpIsCountedAsStuckOncePastTheThreshold() {
        UUID id = insertGroup(GroupState.APPLYING, "fp-managed", ConfigMode.MANAGED, 1L, null, null);
        var store = ctx.getBean(com.xammer.cloud.service.autospotting.core.ops.OperationStore.class);
        UUID op = UUID.randomUUID();
        store.insertQueued(com.xammer.cloud.service.autospotting.core.ops.OperationRecord.builder(
                op, id, 1L, com.xammer.cloud.domain.autospotting.OperationKind.ENABLE)
                .priorState(GroupState.UNMANAGED)
                .compensationTarget(com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets.forKind(
                        com.xammer.cloud.domain.autospotting.OperationKind.ENABLE))
                .requestedBy("tester").build());
        store.finishFailed(op, "LIFECYCLE_TRANSITION_MISSED", "test");
        // Recent: inside the threshold (the §4.6 preemption gap), not stuck.
        jdbc.update("UPDATE spot_group SET state_changed_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), id);
        reconciler.sweepProvider(Provider.AWS);
        assertThat(stuckGauge()).isZero();
        // Older than the 2 h default threshold: stuck.
        jdbc.update("UPDATE spot_group SET state_changed_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(3 * 3600)), id);
        reconciler.sweepProvider(Provider.AWS);
        assertThat(stuckGauge()).isEqualTo(1.0);
    }

    private double stuckGauge() {
        return ctx.getBean(io.micrometer.core.instrument.simple.SimpleMeterRegistry.class)
                .find("autospotting.ops.stuck").tag("provider", "aws").gauge().value();
    }

    @Test
    void driftMismatch_parksGroupPausedDrift() {
        UUID id = insertActiveGroup("fp-managed", ConfigMode.MANAGED, 1L);
        provider.status.put(id, new GroupStatus(true, "fp-CHANGED", "{\"h\":true}", "{\"c\":2}", false));

        reconciler.sweepProvider(Provider.AWS);

        SpotGroup after = groups.findById(id).orElseThrow();
        assertThat(after.getState()).isEqualTo(GroupState.PAUSED);
        assertThat(after.getPauseReason()).isEqualTo(PauseReason.DRIFT);
        assertThat(after.getDriftStatus().name()).isEqualTo("MATERIAL");
    }

    @Test
    void accountSwitchEngaged_pausesStableGroup_withSwitchChangedAtEffectiveAt() {
        UUID id = insertActiveGroup("fp-managed", ConfigMode.MANAGED, 42L);
        provider.status.put(id, new GroupStatus(true, "fp-managed", "{\"h\":true}", "{\"c\":2}", false));
        Instant changedAt = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        engageSwitch("ACCOUNT:42", changedAt);

        reconciler.sweepProvider(Provider.AWS);

        SpotGroup after = groups.findById(id).orElseThrow();
        assertThat(after.getState()).isEqualTo(GroupState.PAUSED);
        assertThat(after.getPauseReason()).isEqualTo(PauseReason.ACCOUNT_SWITCH);
        assertThat(after.getPausedFromState()).isEqualTo(GroupState.ACTIVE);
        Timestamp effectiveAt = jdbc.queryForObject(
                "SELECT effective_at FROM spot_group_transition WHERE group_id = ? ORDER BY id DESC LIMIT 1",
                Timestamp.class, id);
        assertThat(effectiveAt.toInstant()).isEqualTo(changedAt);
    }

    @Test
    void gr06_switchEngagedWithDegradedSignal_writesPauseNotDegraded() {
        UUID id = insertActiveGroup("fp-managed", ConfigMode.MANAGED, 7L);
        // A DEGRADED health signal arrives this sweep...
        provider.status.put(id, new GroupStatus(true, "fp-managed", "{\"h\":false}", "{\"c\":2}", false));
        provider.actions.put(id, List.of(ReconcileAction.MARK_DEGRADED));
        // ...together with an engaged ACCOUNT switch (the GR-06 race).
        engageSwitch("ACCOUNT:7", Instant.now().minusSeconds(30));

        reconciler.sweepProvider(Provider.AWS);

        SpotGroup after = groups.findById(id).orElseThrow();
        assertThat(after.getState()).isEqualTo(GroupState.PAUSED);
        assertThat(after.getPauseReason()).isEqualTo(PauseReason.ACCOUNT_SWITCH);
        // The written transition is the pause, never ACTIVE → DEGRADED.
        List<String> toStates = jdbc.queryForList(
                "SELECT to_state FROM spot_group_transition WHERE group_id = ? ORDER BY id", String.class, id);
        assertThat(toStates).containsExactly("PAUSED");
    }

    @Test
    void release_restoresPausedFromState() {
        UUID id = insertPausedGroup(GroupState.ACTIVE, PauseReason.ACCOUNT_SWITCH, 9L);
        provider.status.put(id, new GroupStatus(true, "fp-managed", "{\"h\":true}", "{\"c\":2}", false));
        // No switch engaged → release path restores paused_from_state.

        reconciler.sweepProvider(Provider.AWS);

        SpotGroup after = groups.findById(id).orElseThrow();
        assertThat(after.getState()).isEqualTo(GroupState.ACTIVE);
    }

    @Test
    void groupHeldByRunningOp_getsOnlyTheHealthPass_noDriftTransition() {
        UUID id = insertActiveGroup("fp-managed", ConfigMode.MANAGED, 5L);
        // A drift mismatch would normally pause the group, but a held lease means health-pass only.
        provider.status.put(id, new GroupStatus(true, "fp-CHANGED", "{\"h\":true}", "{\"c\":2}", false));
        Optional<LeaseHandle> held = leaseService.acquireGroup(id, LeaseService.Component.DISPATCHER);
        assertThat(held).isPresent();
        try {
            reconciler.sweepProvider(Provider.AWS);
        } finally {
            leaseService.releaseGroup(held.get());
        }

        SpotGroup after = groups.findById(id).orElseThrow();
        // Still ACTIVE: drift is never classified while an op holds the lease (§5.1).
        assertThat(after.getState()).isEqualTo(GroupState.ACTIVE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group_transition WHERE group_id = ?",
                Integer.class, id)).isZero();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    @Test
    void aPreemptionTrackerRunsBetweenGetStatusAndReconcile_andItsStateIsPersistedWithTheRuntimeColumn() {
        UUID id = insertActiveGroup("fp-managed", ConfigMode.MANAGED, 1L);
        provider.status.put(id, new GroupStatus(true, "fp-managed", "{\"h\":true}", "{\"c\":2}", false));
        java.util.List<String> seenByReconcile = new java.util.ArrayList<>();
        provider.onReconcile = s -> seenByReconcile.add(s.healthJson());
        reconciler.setPreemptionTrackers(List.of(new com.xammer.cloud.service.autospotting.spi.PreemptionTracker() {
            @Override
            public Provider provider() {
                return Provider.AWS;
            }
            @Override
            public GroupStatus track(SpotGroup group, GroupStatus status) {
                return new GroupStatus(true, status.liveFingerprint(), "{\"h\":true,\"preemption\":{\"preemptions24h\":2}}",
                        status.capacityJson(), status.foreignActivity());
            }
        }));
        reconciler.sweepProvider(Provider.AWS);
        assertThat(seenByReconcile).hasSize(1);
        assertThat(seenByReconcile.get(0)).contains("preemptions24h");
        assertThat(jdbc.queryForObject("SELECT health_state::text FROM spot_group WHERE id = ?", String.class, id))
                .contains("preemptions24h");
        // A tracker that throws never fails the sweep: the status as read stands.
        reconciler.setPreemptionTrackers(List.of(new com.xammer.cloud.service.autospotting.spi.PreemptionTracker() {
            @Override
            public Provider provider() {
                return Provider.AWS;
            }
            @Override
            public GroupStatus track(SpotGroup group, GroupStatus status) {
                throw new IllegalStateException("boom");
            }
        }));
        reconciler.sweepProvider(Provider.AWS);
        assertThat(groups.findById(id).orElseThrow().getState()).isEqualTo(GroupState.ACTIVE);
        assertThat(seenByReconcile).hasSize(2);
        assertThat(seenByReconcile.get(1)).doesNotContain("preemptions24h");
    }
    private UUID insertActiveGroup(String desiredFingerprint, ConfigMode mode, long cloudAccountId) {
        return insertGroup(GroupState.ACTIVE, desiredFingerprint, mode, cloudAccountId, null, null);
    }

    private UUID insertPausedGroup(GroupState pausedFrom, PauseReason reason, long cloudAccountId) {
        return insertGroup(GroupState.PAUSED, "fp-managed", ConfigMode.MANAGED, cloudAccountId, reason, pausedFrom);
    }

    private UUID insertGroup(GroupState state, String desiredFingerprint, ConfigMode mode, long cloudAccountId,
                             PauseReason pauseReason, GroupState pausedFrom) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("provider", "AWS");
        row.put("cloud_account_id", cloudAccountId);
        row.put("client_id", 1L);
        row.put("group_key", "us-east-1/asg-" + id.toString().substring(0, 8));
        row.put("state", state.name());
        row.put("config_mode", mode.name());
        row.put("drift_status", "NONE");
        row.put("desired_fingerprint", desiredFingerprint);
        if (pauseReason != null) {
            row.put("pause_reason", pauseReason.name());
        }
        if (pausedFrom != null) {
            row.put("paused_from_state", pausedFrom.name());
        }
        row.put("state_changed_at", Timestamp.from(now.minusSeconds(3600)));
        row.put("last_reconciled_at", Timestamp.from(now.minusSeconds(3600)));
        row.put("created_by", "test");
        row.put("created_at", Timestamp.from(now));
        row.put("updated_at", Timestamp.from(now));
        PgHarness.insertRow(jdbc, "spot_group", row);
        return id;
    }

    private void engageSwitch(String scopeKey, Instant changedAt) {
        jdbc.update("INSERT INTO autospotting_switch (scope_key, engaged, reason, changed_by, changed_at, version)"
                        + " VALUES (?, true, ?, ?, ?, 0)",
                scopeKey, "test", "tester", Timestamp.from(changedAt));
    }

    /** A test provider whose getStatus/reconcile are scripted per group. */
    private static final class TestReconcileProvider implements SpotProvider {
        final Map<UUID, GroupStatus> status = new ConcurrentHashMap<>();
        final Map<UUID, List<ReconcileAction>> actions = new ConcurrentHashMap<>();
        java.util.function.Consumer<GroupStatus> onReconcile = s -> { };

        @Override
        public Provider provider() {
            return Provider.AWS;
        }

        @Override
        public com.xammer.cloud.service.autospotting.spi.EligibilityResult checkEligibility(
                com.xammer.cloud.service.autospotting.spi.GroupRef ref, ReadOnlyCloudView view,
                com.xammer.cloud.service.autospotting.spi.Profile profile,
                com.xammer.cloud.service.autospotting.spi.EvalMode mode) {
            return com.xammer.cloud.service.autospotting.spi.EligibilityResult.eligible(List.of(), List.of());
        }

        @Override
        public com.xammer.cloud.service.autospotting.spi.PlanBody createPlan(
                com.xammer.cloud.service.autospotting.spi.PlanRequest req, ReadOnlyCloudView view) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.xammer.cloud.service.autospotting.spi.SnapshotPayload takeSnapshot(
                com.xammer.cloud.service.autospotting.spi.GroupRef ref, ReadOnlyCloudView view) {
            return com.xammer.cloud.service.autospotting.spi.SnapshotPayload.exact(Provider.AWS, 1, "{}", "sha");
        }

        @Override
        public com.xammer.cloud.service.autospotting.spi.StepProgram executeConversion(
                com.xammer.cloud.service.autospotting.spi.OperationContext op) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.xammer.cloud.service.autospotting.spi.StepProgram restore(
                com.xammer.cloud.service.autospotting.spi.OperationContext op,
                com.xammer.cloud.service.autospotting.spi.SnapshotPayload snapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GroupStatus getStatus(SpotGroup group, ReadOnlyCloudView view) {
            return status.getOrDefault(group.getId(),
                    new GroupStatus(true, "fp-managed", "{\"h\":true}", "{\"c\":2}", false));
        }

        @Override
        public List<ReconcileAction> reconcile(SpotGroup group, GroupStatus s) {
            onReconcile.accept(s);
            return actions.getOrDefault(group.getId(), List.of());
        }
    }
}
