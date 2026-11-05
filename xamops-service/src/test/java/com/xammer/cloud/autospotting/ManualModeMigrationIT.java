package com.xammer.cloud.autospotting;

import com.xammer.cloud.autospotting.pg.PgHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V20261104_1__manual_mode_proposals.sql} on real PostgreSQL, on the shape prod actually has:
 * Hibernate (ddl-auto) builds the base schema from the entities first, then Flyway applies the
 * migrations after the baseline (design 3.14, V29/V30). Needs Docker; run with
 * {@code -Dgroups=containers -Dsurefire.excludedGroups=} (CI job {@code xamops-containers-test}).
 *
 * <p>What H2 cannot prove: the TEXT columns are really {@code text}, the partial unique index exists
 * and rejects a second live row, and the migration is a no-op when Hibernate got there first.
 */
@Tag("containers")
class ManualModeMigrationIT {

    private static final String VERSION = "20261104.1";
    private static final List<String> TEXT_COLUMNS = List.of(
            "automation_proposal.params_json", "automation_proposal.evidence_json",
            "automation_proposal.refusal_reason", "automation_proposal.gate_json",
            "automation_proposal.outcome_json", "automation_proposal_event.detail");

    private static long newCluster(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO kubernetes_cluster_config (cloud_account_id, cluster_name, region) "
                + "VALUES (1, 'prod-eks', 'us-east-1') RETURNING id", Long.class);
    }

    private static void insertProposal(JdbcTemplate jdbc, long cluster, String id, String status, String stage) {
        jdbc.update("INSERT INTO automation_proposal (id, cluster_config_id, action_type, source, origin_controller, "
                + "target_kind, target_ref, dedupe_key, params_json, evidence_json, verdict, dry_run, plan_fingerprint, "
                + "status, refusal_stage, proposed_at, first_seen_at, last_seen_at, expires_at, version) "
                + "VALUES (?, ?, 'NODE_DRAIN', 'AGENT', 'node-optimizer', 'NODE', 'n1', 'k1', '{}', '{}', 'ALLOWED', "
                + "false, ?, ?, ?, now(), now(), now(), now() + interval '1 hour', 0)",
                id, cluster, "sha256:" + "a".repeat(64), status, stage);
    }

    @Test
    void migrationAppliesOnAHibernateBuiltSchemaAndAddsTheGuardedColumnsWithSafeDefaults() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            JdbcTemplate jdbc = db.jdbc();
            assertThat(db.appliedVersions()).contains(VERSION);
            assertThat(PgHarness.tableExists(jdbc, "automation_proposal")).isTrue();
            assertThat(PgHarness.tableExists(jdbc, "automation_proposal_event")).isTrue();

            long cluster = newCluster(jdbc);
            assertThat(jdbc.queryForObject("SELECT automation_approval_mode FROM kubernetes_cluster_config WHERE id = ?",
                    String.class, cluster)).as("an untouched cluster stays AUTONOMOUS").isEqualTo("AUTONOMOUS");
            for (String column : List.of("approval_mode_changed_at", "approval_mode_changed_by",
                    "policy_served_approval_mode", "policy_served_at", "automation_agent_features",
                    "automation_agent_features_seen_at", "automation_agent_drain_timeout_seconds")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'kubernetes_cluster_config' AND column_name = ?", Integer.class, column))
                        .as(column).isEqualTo(1);
            }
        }
    }

    @Test
    void theLargeTextColumnsAreTextNotVarcharOrOid() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            for (String qualified : TEXT_COLUMNS) {
                String[] parts = qualified.split("\\.");
                assertThat(db.jdbc().queryForObject("SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?", String.class, parts[0], parts[1]))
                        .as(qualified).isEqualTo("text");
            }
        }
    }

    @Test
    void thePartialUniqueIndexAdmitsOneLiveRowPerKeyAndLetsHistoryAccumulate() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            JdbcTemplate jdbc = db.jdbc();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_ap_live_dedupe'",
                    Integer.class)).isEqualTo(1);
            long cluster = newCluster(jdbc);

            insertProposal(jdbc, cluster, "p1", "PENDING", null);
            assertThatThrownBy(() -> insertProposal(jdbc, cluster, "p2", "APPROVED", null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertProposal(jdbc, cluster, "p3", "REFUSED", "PROPOSAL"))
                    .as("a proposal-stage refusal is live too").isInstanceOf(DataIntegrityViolationException.class);

            // history is not live: terminal rows and execution-stage refusals do not block a new row
            insertProposal(jdbc, cluster, "p4", "EXECUTED", null);
            insertProposal(jdbc, cluster, "p5", "REFUSED", "EXECUTION");
            insertProposal(jdbc, cluster, "p6", "STALE", null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM automation_proposal", Integer.class)).isEqualTo(4);
        }
    }

    @Test
    void runningTheMigrationAgainChangesNothing() throws IOException {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            JdbcTemplate jdbc = db.jdbc();
            long cluster = newCluster(jdbc);
            insertProposal(jdbc, cluster, "p1", "PENDING", null);
            String sql;
            try (InputStream in = getClass().getResourceAsStream("/db/migration/V20261104_1__manual_mode_proposals.sql")) {
                assertThat(in).isNotNull();
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            jdbc.execute(sql);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM automation_proposal", Integer.class)).isEqualTo(1);
        }
    }
}
