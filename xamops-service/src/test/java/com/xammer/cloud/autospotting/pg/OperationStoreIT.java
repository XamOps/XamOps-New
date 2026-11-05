package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.StepStatus;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OperationStore} on real PostgreSQL (plan-phase-06.md task 6.3 verify, design-core.md §4.5).
 * Runs against {@link PgHarness#emptySchema()} (Testcontainers). {@code @Tag("containers")} keeps it
 * out of the default {@code mvn test}.
 */
@Tag("containers")
class OperationStoreIT {

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

    private static OperationRecord enableOp(UUID opId, UUID groupId) {
        return OperationRecord.builder(opId, groupId, 1L, OperationKind.ENABLE)
                .priorState(GroupState.UNMANAGED)
                .compensationTarget(CompensationTargets.forKind(OperationKind.ENABLE))
                .targetConfigJson("{\"profile\":\"conservative\"}")
                .requestedBy("tester")
                .build();
    }

    // ── insertQueued writes a QUEUED op with the per-kind budget deadline ───────

    @Test
    void insertQueuedWritesAQueuedOpWithDeadlineAndCompensationTarget() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);

            UUID group = insertActiveGroup(jdbc);
            UUID opId = UUID.randomUUID();
            store.insertQueued(enableOp(opId, group));

            assertThat(jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, opId))
                    .isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT phase FROM spot_operation WHERE id = ?", String.class, opId))
                    .isEqualTo("FORWARD");
            assertThat(jdbc.queryForObject("SELECT compensation_target FROM spot_operation WHERE id = ?",
                    String.class, opId)).isEqualTo("SNAPSHOT");
            assertThat(jdbc.queryForObject("SELECT action_class FROM spot_operation WHERE id = ?",
                    String.class, opId)).isEqualTo("PROGRESS");
            assertThat(jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, opId)).isNull();
            // deadline_at ~ now + 6h.
            Timestamp deadline = jdbc.queryForObject("SELECT deadline_at FROM spot_operation WHERE id = ?",
                    Timestamp.class, opId);
            assertThat(deadline.toInstant()).isAfter(Instant.now().plusSeconds(6 * 3600 - 120));
        }
    }

    // ── Step intent is durable before the (fake) cloud call (§4.5) ──────────────

    @Test
    void stepIntentIsDurableBeforeTheCloudCall() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            LeaseService leases = ctx.getBean(LeaseService.class);

            UUID group = insertActiveGroup(jdbc);
            UUID opId = UUID.randomUUID();
            store.insertQueued(enableOp(opId, group));
            LeaseHandle handle = leases.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();

            String token = StepContext.idempotencyToken(opId, 2).toString();
            // (1) record intent — this is the durable write that precedes the cloud call.
            store.recordIntent(handle, opId, 2, "APPLY_TARGET_CONFIG", token, "{\"in\":1}", true);

            // The step row is already INTENT_RECORDED and committed, before any "cloud call".
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM spot_operation_step WHERE operation_id = ? AND seq = 2", String.class, opId))
                    .isEqualTo("INTENT_RECORDED");
            assertThat(jdbc.queryForObject(
                    "SELECT idempotency_token FROM spot_operation_step WHERE operation_id = ? AND seq = 2",
                    String.class, opId)).isEqualTo(token);
            assertThat(jdbc.queryForObject(
                    "SELECT completed_at FROM spot_operation_step WHERE operation_id = ? AND seq = 2",
                    Timestamp.class, opId)).isNull();

            // (3) complete the step after the (fake) cloud call.
            store.completeStep(handle, opId, 2, StepStatus.DONE, List.of("req-123"), "{\"out\":1}");
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM spot_operation_step WHERE operation_id = ? AND seq = 2", String.class, opId))
                    .isEqualTo("DONE");
            assertThat(jdbc.queryForObject(
                    "SELECT cloud_request_ids::text FROM spot_operation_step WHERE operation_id = ? AND seq = 2",
                    String.class, opId)).contains("req-123");
        }
    }

    // ── A step write after lease loss throws LeaseLostException ─────────────────

    @Test
    void aStepWriteAfterLeaseLossThrowsLeaseLost_andNothingCommits() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            LeaseService leases = ctx.getBean(LeaseService.class);

            UUID group = insertActiveGroup(jdbc);
            UUID opId = UUID.randomUUID();
            store.insertQueued(enableOp(opId, group));
            LeaseHandle a = leases.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();

            // Expire A, let B take the lease, then A's fenced step write must fail and roll back.
            jdbc.update("UPDATE spot_group SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                    group);
            leases.acquireGroup(group, LeaseService.Component.RECONCILER).orElseThrow();

            String token = StepContext.idempotencyToken(opId, 0).toString();
            assertThatThrownBy(() -> store.recordIntent(a, opId, 0, "VERIFY_PRECONDITIONS", token, "{}", false))
                    .isInstanceOf(LeaseLostException.class);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_operation_step WHERE operation_id = ?",
                    Long.class, opId)).isEqualTo(0L);
        }
    }

    // ── first_mutation_seq stays null when only read steps ran; set by the first mutating intent ─

    @Test
    void firstMutationSeqStaysNullForReadSteps_thenSetByTheFirstMutatingIntent() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            LeaseService leases = ctx.getBean(LeaseService.class);

            UUID group = insertActiveGroup(jdbc);
            UUID opId = UUID.randomUUID();
            store.insertQueued(enableOp(opId, group));
            LeaseHandle handle = leases.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();

            // Two read steps first: first_mutation_seq must stay null.
            store.recordIntent(handle, opId, 0, "VERIFY_PRECONDITIONS",
                    StepContext.idempotencyToken(opId, 0).toString(), "{}", false);
            store.recordIntent(handle, opId, 1, "PERSIST_SNAPSHOT",
                    StepContext.idempotencyToken(opId, 1).toString(), "{}", false);
            assertThat(jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, opId)).isNull();

            // The first mutating step records intent at seq 2: first_mutation_seq becomes 2.
            store.recordIntent(handle, opId, 2, "APPLY_TARGET_CONFIG",
                    StepContext.idempotencyToken(opId, 2).toString(), "{}", true);
            assertThat(jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, opId)).isEqualTo(2);

            // A later mutating step at seq 3 must not overwrite it.
            store.recordIntent(handle, opId, 3, "APPLY_MORE",
                    StepContext.idempotencyToken(opId, 3).toString(), "{}", true);
            assertThat(jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, opId)).isEqualTo(2);
        }
    }

    // ── input/output are redacted on write (FR-SEC-06) ──────────────────────────

    @Test
    void stepInputAndOutputAreRedactedOnWrite() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            OperationStore store = ctx.getBean(OperationStore.class);
            LeaseService leases = ctx.getBean(LeaseService.class);

            UUID group = insertActiveGroup(jdbc);
            UUID opId = UUID.randomUUID();
            store.insertQueued(enableOp(opId, group));
            LeaseHandle handle = leases.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();

            String token = StepContext.idempotencyToken(opId, 0).toString();
            store.recordIntent(handle, opId, 0, "VERIFY",
                    token, "{\"secretAccessKey\":\"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\"}", false);
            store.completeStep(handle, opId, 0, StepStatus.DONE, List.of(),
                    "{\"token\":\"ya29.super-secret-token-value\"}");

            String input = jdbc.queryForObject(
                    "SELECT input::text FROM spot_operation_step WHERE operation_id = ? AND seq = 0",
                    String.class, opId);
            String output = jdbc.queryForObject(
                    "SELECT output::text FROM spot_operation_step WHERE operation_id = ? AND seq = 0",
                    String.class, opId);
            assertThat(input).doesNotContain("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
            assertThat(output).doesNotContain("ya29.super-secret-token-value");
        }
    }
}
