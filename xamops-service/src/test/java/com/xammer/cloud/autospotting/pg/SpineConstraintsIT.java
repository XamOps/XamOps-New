package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The spine constraints the design relies on, on real PostgreSQL (plan-phase-03.md task 3.3;
 * design-core.md §2.4, §4.5, §6.1, §8, DR-01, AC-SPN-14, AC-LED-02, AC-ROL-04). Each case runs
 * against {@link PgHarness#emptySchema()}, which applies only the AutoSpotting migrations and so
 * needs no {@code cloud_account} rows (the spine has no FK to it, PO-21).
 */
@Tag("containers")
class SpineConstraintsIT {

    private static final String P0001_APPEND = "append-only";
    private static final String P0001_MONOTONIC = "not monotonic";

    // ── ux_spot_group_identity: one live row per (provider, account, key) ─────

    @Test
    void secondLiveRowForOneIdentityFails_andGoneFrees() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            insertGroup(jdbc, UUID.randomUUID(), "AWS", 1L, "us-east-1/asg-a", null, "ACTIVE");

            assertThatThrownBy(() ->
                    insertGroup(jdbc, UUID.randomUUID(), "AWS", 1L, "us-east-1/asg-a", null, "PLANNED"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ux_spot_group_identity");

            // A GONE row keeps the key but doesn't count as live, so a fresh row inserts.
            UUID gone = UUID.randomUUID();
            insertGroup(jdbc, gone, "AWS", 1L, "us-east-1/asg-b", null, "ACTIVE");
            jdbc.update("UPDATE spot_group SET state = 'GONE' WHERE id = ?", gone);
            insertGroup(jdbc, UUID.randomUUID(), "AWS", 1L, "us-east-1/asg-b", null, "ACTIVE");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM spot_group WHERE group_key = 'us-east-1/asg-b'", Long.class)).isEqualTo(2L);
        }
    }

    // ── ux_spot_group_stable: one manager per cloud resource (AC-SPN-14) ──────

    @Test
    void twoAccountsOneStableIdInManagedStatesFail_andNonManagedStatesAllowIt() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            String stableId = "1234567890123456789"; // one MIG numeric id
            insertGroup(jdbc, UUID.randomUUID(), "GCP", 10L, "proj/us/mig-x", stableId, "ACTIVE");

            // A second CloudAccount id, one managed state, the same stable id: rejected.
            assertThatThrownBy(() ->
                    insertGroup(jdbc, UUID.randomUUID(), "GCP", 11L, "proj/us/mig-x", stableId, "PLANNED"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ux_spot_group_stable");

            // GONE, UNMANAGED and REVERTED are outside the index, so a second row in any of them inserts.
            for (String nonManaged : new String[] {"GONE", "UNMANAGED", "REVERTED"}) {
                UUID id = UUID.randomUUID();
                insertGroup(jdbc, id, "GCP", 12L, "proj/us/mig-x-" + nonManaged, stableId, nonManaged);
                assertThat(jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, id))
                        .isEqualTo(nonManaged);
            }
        }
    }

    // ── ux_spot_op_running: one running op per group; dry-run excluded ────────

    @Test
    void secondRunningOpPerGroupFails_butDryRunPasses() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            UUID group = UUID.randomUUID();
            insertGroup(jdbc, group, "AWS", 1L, "us-east-1/asg-op", null, "APPLYING");
            insertOperation(jdbc, UUID.randomUUID(), group, "RUNNING", false);

            assertThatThrownBy(() -> insertOperation(jdbc, UUID.randomUUID(), group, "RUNNING", false))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ux_spot_op_running");

            // A dry-run op is excluded by the partial index, so it coexists.
            insertOperation(jdbc, UUID.randomUUID(), group, "RUNNING", true);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_operation WHERE group_id = ?",
                    Long.class, group)).isEqualTo(2L);
        }
    }

    // ── append-only triggers (P0001) ─────────────────────────────────────────

    @Test
    void updateOrDeleteOnAuditSnapshotAndTransitionRaiseP0001() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            UUID group = UUID.randomUUID();
            insertGroup(jdbc, group, "AWS", 1L, "us-east-1/asg-ap", null, "ACTIVE");

            jdbc.update("INSERT INTO autospotting_audit (event_type, actor_type, action, outcome) "
                    + "VALUES ('STATE_TRANSITION', 'SYSTEM', 'noop', 'OK')");
            assertAppendOnly(() -> jdbc.update("UPDATE autospotting_audit SET outcome = 'FAILED'"));
            assertAppendOnly(() -> jdbc.update("DELETE FROM autospotting_audit"));

            UUID snap = UUID.randomUUID();
            jdbc.update("INSERT INTO spot_group_snapshot (id, group_id, provider, schema_version, exact, "
                    + "payload, payload_sha256, captured_at) VALUES (?, ?, 'AWS', 1, true, '{}'::jsonb, 'x', now())",
                    snap, group);
            assertAppendOnly(() -> jdbc.update("UPDATE spot_group_snapshot SET exact = false WHERE id = ?", snap));
            assertAppendOnly(() -> jdbc.update("DELETE FROM spot_group_snapshot WHERE id = ?", snap));

            insertTransition(jdbc, group, "ACTIVE", "DEGRADED", Instant.parse("2026-10-20T10:00:00Z"));
            assertAppendOnly(() -> jdbc.update("UPDATE spot_group_transition SET to_state = 'ACTIVE'"));
            assertAppendOnly(() -> jdbc.update("DELETE FROM spot_group_transition"));
        }
    }

    // ── trg_sgt_monotonic (DR-01, AC-LED-02) ─────────────────────────────────

    @Test
    void transitionWithEarlierEffectiveAtRaisesP0001() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            UUID group = UUID.randomUUID();
            insertGroup(jdbc, group, "AWS", 1L, "us-east-1/asg-mono", null, "ACTIVE");

            insertTransition(jdbc, group, null, "PLANNED", Instant.parse("2026-10-20T10:00:00Z"));
            assertThatThrownBy(() ->
                    insertTransition(jdbc, group, "PLANNED", "APPLYING", Instant.parse("2026-10-20T09:59:59Z")))
                    .isInstanceOf(UncategorizedSQLException.class)
                    .hasMessageContaining(P0001_MONOTONIC);
        }
    }

    @Test
    void fiveTransitionsInOrderSucceed_outOfOrderFailAtFirstRegression() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            UUID inOrder = UUID.randomUUID();
            insertGroup(jdbc, inOrder, "AWS", 1L, "us-east-1/asg-ok", null, "ACTIVE");
            Instant base = Instant.parse("2026-10-20T10:00:00Z");
            String[] states = {"UNMANAGED", "PLANNED", "APPLYING", "ACTIVE", "DEGRADED"};
            String prev = null;
            for (int i = 0; i < states.length; i++) {
                insertTransition(jdbc, inOrder, prev, states[i], base.plusSeconds(i * 60L));
                prev = states[i];
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group_transition WHERE group_id = ?",
                    Long.class, inOrder)).isEqualTo(5L);

            // The same five, inserted out of effective_at order, fail at the first regression (NO-08).
            UUID outOfOrder = UUID.randomUUID();
            insertGroup(jdbc, outOfOrder, "AWS", 1L, "us-east-1/asg-bad", null, "ACTIVE");
            insertTransition(jdbc, outOfOrder, null, "UNMANAGED", base.plusSeconds(240));
            assertThatThrownBy(() ->
                    insertTransition(jdbc, outOfOrder, "UNMANAGED", "PLANNED", base.plusSeconds(60)))
                    .isInstanceOf(UncategorizedSQLException.class)
                    .hasMessageContaining(P0001_MONOTONIC);
        }
    }

    // ── ck_asal_basis (design-core.md §6.1) ──────────────────────────────────

    @Test
    void allowlistBasisCheckRejectsContractWithoutIdAndNoFeeWithId() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();

            // CONTRACT needs a contract_id.
            assertThatThrownBy(() -> insertAllowlist(jdbc, 1L, "CONTRACT", null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_asal_basis");
            // NO_FEE must not carry one.
            assertThatThrownBy(() -> insertAllowlist(jdbc, 2L, "NO_FEE", UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_asal_basis");

            // The valid forms insert.
            insertAllowlist(jdbc, 3L, "CONTRACT", UUID.randomUUID());
            insertAllowlist(jdbc, 4L, "NO_FEE", null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM autospotting_allowlist", Long.class)).isEqualTo(2L);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void assertAppendOnly(Runnable mutation) {
        assertThatThrownBy(mutation::run)
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining(P0001_APPEND);
    }

    private static void insertGroup(JdbcTemplate jdbc, UUID id, String provider, long accountId,
                                    String groupKey, String stableId, String state) {
        jdbc.update("INSERT INTO spot_group (id, provider, cloud_account_id, client_id, group_key, "
                        + "provider_stable_id, state, created_by, created_at, updated_at, state_changed_at) "
                        + "VALUES (?, ?, ?, 1, ?, ?, ?, 'test', now(), now(), now())",
                id, provider, accountId, groupKey, stableId, state);
    }

    private static void insertOperation(JdbcTemplate jdbc, UUID id, UUID group, String status, boolean dryRun) {
        jdbc.update("INSERT INTO spot_operation (id, group_id, cloud_account_id, kind, action_class, status, "
                        + "prior_state, compensation_target, requested_by, dry_run, deadline_at, created_at, updated_at) "
                        + "VALUES (?, ?, 1, 'ENABLE', 'PROGRESS', ?, 'UNMANAGED', 'SNAPSHOT', 'test', ?, now(), now(), now())",
                id, group, status, dryRun);
    }

    private static void insertTransition(JdbcTemplate jdbc, UUID group, String from, String to, Instant effectiveAt) {
        jdbc.update("INSERT INTO spot_group_transition (group_id, from_state, to_state, reason_code, actor_type, "
                        + "occurred_at, effective_at) VALUES (?, ?, ?, 'TEST', 'SYSTEM', ?, ?)",
                group, from, to, Timestamp.from(effectiveAt), Timestamp.from(effectiveAt));
    }

    private static void insertAllowlist(JdbcTemplate jdbc, long accountId, String basis, UUID contractId) {
        jdbc.update("INSERT INTO autospotting_allowlist (cloud_account_id, provider, billing_basis, contract_id, "
                        + "reason, added_by, added_at) VALUES (?, 'GCP', ?, ?, 'test', 'test', now())",
                accountId, basis, contractId);
    }
}
