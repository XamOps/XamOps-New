package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.Result;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.TransitionRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GroupLifecycleService} on real PostgreSQL (plan-phase-06.md task 6.1 verify, design-core.md
 * §2.3, §2.4, DR-01). Runs against {@link PgHarness#emptySchema()} (Testcontainers), so the guarded
 * native UPDATE, the append-only transition insert, {@code trg_sgt_monotonic} and the fence are the
 * real ones. {@code @Tag("containers")} keeps it out of the default {@code mvn test}.
 */
@Tag("containers")
class GroupLifecycleIT {

    private AnnotationConfigApplicationContext contextFor(PgHarness.Database db) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        DataSource dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        ctx.getBeanFactory().registerSingleton("dataSource", dataSource);
        ctx.register(AutoSpottingCoreBeans.class);
        ctx.refresh();
        return ctx;
    }

    private static UUID insertActiveGroup(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", id);
        row.put("provider", "AWS");
        row.put("cloud_account_id", 1L);
        row.put("client_id", 1L);
        row.put("group_key", "us-east-1/asg-" + id.toString().substring(0, 8));
        row.put("state", "ACTIVE");
        row.put("config_mode", "MANAGED");
        row.put("state_changed_at", Timestamp.from(now));
        row.put("created_by", "test");
        row.put("created_at", Timestamp.from(now));
        row.put("updated_at", Timestamp.from(now));
        PgHarness.insertRow(jdbc, "spot_group", row);
        return id;
    }

    // ── A stale version write affects zero rows (§2.3) ──────────────────────────

    @Test
    void staleVersionWriteAffectsZeroRows_returnsStale_nothingChanges() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            LeaseService leases = ctx.getBean(LeaseService.class);
            GroupLifecycleService lifecycle = ctx.getBean(GroupLifecycleService.class);
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);

            UUID id = insertActiveGroup(jdbc);
            LeaseHandle handle = leases.acquireGroup(id, LeaseService.Component.DISPATCHER).orElseThrow();
            SpotGroup group = groups.findById(id).orElseThrow();

            // Bump the version out from under us (as another lifecycle writer would), so the guard misses.
            jdbc.update("UPDATE spot_group SET version = version + 1 WHERE id = ?", id);

            Result result = lifecycle.transition(handle, group,
                    TransitionRequest.of(GroupState.DEGRADED, "UNHEALTHY", Actor.system(), null, Instant.now()));

            assertThat(result).isEqualTo(Result.STALE);
            assertThat(jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, id))
                    .isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group_transition WHERE group_id = ?",
                    Long.class, id)).isEqualTo(0L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM autospotting_audit", Long.class)).isEqualTo(0L);
        }
    }

    // ── A backdated pause is clamped to the latest effective_at (DR-01) ─────────

    @Test
    void backdatedPauseIsClampedToTheLatestEffectiveAt() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            LeaseService leases = ctx.getBean(LeaseService.class);
            GroupLifecycleService lifecycle = ctx.getBean(GroupLifecycleService.class);
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);

            UUID id = insertActiveGroup(jdbc);
            LeaseHandle handle = leases.acquireGroup(id, LeaseService.Component.DISPATCHER).orElseThrow();

            // First transition at t0 (ACTIVE -> DEGRADED).
            Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            SpotGroup g1 = groups.findById(id).orElseThrow();
            assertThat(lifecycle.transition(handle, g1,
                    TransitionRequest.of(GroupState.DEGRADED, "UNHEALTHY", Actor.system(), null, t0)))
                    .isEqualTo(Result.APPLIED);

            // Second transition (DEGRADED -> PAUSED) requested backdated 10 min before t0: must clamp to t0.
            Instant backdated = t0.minus(10, ChronoUnit.MINUTES);
            SpotGroup g2 = groups.findById(id).orElseThrow();
            assertThat(lifecycle.transition(handle, g2,
                    TransitionRequest.of(GroupState.PAUSED, "FREEZE", Actor.system(), null, backdated)
                            .withPause(PauseReason.FREEZE, GroupState.DEGRADED)))
                    .isEqualTo(Result.APPLIED);

            Timestamp pausedEffective = jdbc.queryForObject(
                    "SELECT effective_at FROM spot_group_transition WHERE group_id = ? AND to_state = 'PAUSED'",
                    Timestamp.class, id);
            assertThat(pausedEffective.toInstant()).isEqualTo(t0); // clamped up, not the backdated value
        }
    }

    // ── A lifecycle write with a lost lease rolls back (AC-SPN-03) ──────────────

    @Test
    void lifecycleWriteWithALostLeaseRollsBack() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            LeaseService leases = ctx.getBean(LeaseService.class);
            GroupLifecycleService lifecycle = ctx.getBean(GroupLifecycleService.class);
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);

            UUID id = insertActiveGroup(jdbc);
            LeaseHandle a = leases.acquireGroup(id, LeaseService.Component.DISPATCHER).orElseThrow();
            SpotGroup group = groups.findById(id).orElseThrow();

            // Expire A and let B take the lease; A's fenced lifecycle write must throw and roll back.
            jdbc.update("UPDATE spot_group SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                    id);
            leases.acquireGroup(id, LeaseService.Component.RECONCILER).orElseThrow();

            assertThatThrownBy(() -> lifecycle.transition(a, group,
                    TransitionRequest.of(GroupState.DEGRADED, "UNHEALTHY", Actor.system(), null, Instant.now())))
                    .isInstanceOf(LeaseLostException.class);

            assertThat(jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, id))
                    .isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group_transition WHERE group_id = ?",
                    Long.class, id)).isEqualTo(0L);
        }
    }

    // ── Runtime columns written by another actor never fail a transition (§2.4) ─

    @Test
    void aRuntimeColumnWriteByAnotherActorDoesNotFailATransition() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            LeaseService leases = ctx.getBean(LeaseService.class);
            GroupLifecycleService lifecycle = ctx.getBean(GroupLifecycleService.class);
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);

            UUID id = insertActiveGroup(jdbc);
            LeaseHandle handle = leases.acquireGroup(id, LeaseService.Component.DISPATCHER).orElseThrow();
            SpotGroup group = groups.findById(id).orElseThrow();
            long versionAtRead = group.getVersion();

            // The reconciler writes a runtime column (never touches version), after we read the entity.
            jdbc.update("UPDATE spot_group SET last_capacity = '{\"desired\":3}'::jsonb,"
                    + " last_reconciled_at = clock_timestamp() WHERE id = ?", id);

            // The transition still succeeds: the guard is on (state, version) only, and version is unchanged.
            Result result = lifecycle.transition(handle, group,
                    TransitionRequest.of(GroupState.FALLBACK, "FALLBACK_VERIFIED", Actor.system(), null, Instant.now())
                            .withConfigMode(ConfigMode.FALLBACK));
            assertThat(result).isEqualTo(Result.APPLIED);
            assertThat(jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, id))
                    .isEqualTo("FALLBACK");
            assertThat(jdbc.queryForObject("SELECT version FROM spot_group WHERE id = ?", Long.class, id))
                    .isEqualTo(versionAtRead + 1);
            assertThat(jdbc.queryForObject("SELECT config_mode FROM spot_group WHERE id = ?", String.class, id))
                    .isEqualTo("FALLBACK");
            // The reconciler's runtime write survived.
            assertThat(jdbc.queryForObject("SELECT last_capacity->>'desired' FROM spot_group WHERE id = ?",
                    String.class, id)).isEqualTo("3");
        }
    }

    // ── The plan-driven row-lock path (PLANNED on plan creation, §2.3) ──────────

    @Test
    void planDrivenRowLockTransition_unmanagedToPlanned_withoutALease() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            GroupLifecycleService lifecycle = ctx.getBean(GroupLifecycleService.class);
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);

            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", id);
            row.put("provider", "AWS");
            row.put("cloud_account_id", 1L);
            row.put("client_id", 1L);
            row.put("group_key", "us-east-1/asg-planned");
            row.put("state", "UNMANAGED");
            row.put("config_mode", "MANAGED");
            row.put("state_changed_at", Timestamp.from(now));
            row.put("created_by", "test");
            row.put("created_at", Timestamp.from(now));
            row.put("updated_at", Timestamp.from(now));
            PgHarness.insertRow(jdbc, "spot_group", row);

            SpotGroup group = groups.findById(id).orElseThrow();
            Result result = lifecycle.transitionUnderRowLock(group,
                    TransitionRequest.of(GroupState.PLANNED, "PLAN_CREATED", Actor.user(7L, "ROLE_ADMIN"), null, now));

            assertThat(result).isEqualTo(Result.APPLIED);
            assertThat(jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, id))
                    .isEqualTo("PLANNED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group_transition WHERE group_id = ?"
                    + " AND to_state = 'PLANNED'", Long.class, id)).isEqualTo(1L);
        }
    }
}
