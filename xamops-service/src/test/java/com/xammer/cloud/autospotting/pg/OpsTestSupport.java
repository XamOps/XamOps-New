package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Shared fixtures for the Phase 07 operation-runner ITs (group + op row inserts). */
final class OpsTestSupport {

    private OpsTestSupport() {
    }

    static AnnotationConfigApplicationContext contextFor(PgHarness.Database db) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        DataSource dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        ctx.getBeanFactory().registerSingleton("dataSource", dataSource);
        ctx.register(AutoSpottingOpsBeans.class);
        ctx.refresh();
        return ctx;
    }

    /** Insert a live group in {@code state} and return its id. */
    static UUID insertGroup(JdbcTemplate jdbc, GroupState state) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("provider", "AWS");
        row.put("cloud_account_id", 1L);
        row.put("client_id", 1L);
        row.put("group_key", "us-east-1/asg-" + id.toString().substring(0, 8));
        row.put("state", state.name());
        row.put("config_mode", "MANAGED");
        row.put("state_changed_at", Timestamp.from(now));
        row.put("created_by", "test");
        row.put("created_at", Timestamp.from(now));
        row.put("updated_at", Timestamp.from(now));
        PgHarness.insertRow(jdbc, "spot_group", row);
        return id;
    }

    /** Insert a QUEUED op of {@code kind} with {@code priorState}, returning its id. */
    static UUID insertQueuedOp(OperationStore store, UUID groupId, OperationKind kind, GroupState priorState) {
        UUID opId = UUID.randomUUID();
        store.insertQueued(OperationRecord.builder(opId, groupId, 1L, kind)
                .priorState(priorState)
                .compensationTarget(CompensationTargets.forKind(kind))
                .targetConfigJson("{\"profile\":\"conservative\"}")
                .requestedBy("tester")
                .build());
        return opId;
    }

    static String opStatus(JdbcTemplate jdbc, UUID opId) {
        return jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, opId);
    }

    static String opPhase(JdbcTemplate jdbc, UUID opId) {
        return jdbc.queryForObject("SELECT phase FROM spot_operation WHERE id = ?", String.class, opId);
    }

    static String groupState(JdbcTemplate jdbc, UUID groupId) {
        return jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, groupId);
    }

    static String opCancelReason(JdbcTemplate jdbc, UUID opId) {
        return jdbc.queryForObject("SELECT cancel_reason FROM spot_operation WHERE id = ?", String.class, opId);
    }
}
