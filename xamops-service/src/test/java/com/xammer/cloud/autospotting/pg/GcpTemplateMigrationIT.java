package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.service.autospotting.gcp.GcpTemplateRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V20261110_2__autospotting_gcp_templates.sql} and {@link GcpTemplateRegistry} on real PostgreSQL, both
 * harness modes (plan-phase-27.md 27.1, design-gcp.md §9.1, DR-28): the table and its three indexes exist, a second
 * run changes nothing, there is no foreign key, a re-issued insert finds the one live row, and only a RETIRED row
 * past its grace period, with a terminal creating operation, is ever offered for deletion.
 */
@Tag("containers")
class GcpTemplateMigrationIT {
    static final String VERSION = "20261110.2";
    private static final UUID GROUP = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String LINK = "https://www.googleapis.com/compute/v1/projects/xamops-test/global/instanceTemplates/";

    @Test
    void emptySchema_createsTheTableAndIndexesAndIsIdempotent() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            assertThat(db.appliedVersions()).contains(VERSION);
            assertShape(db.jdbc());
            db.jdbc().execute(PgHarness.migration(VERSION).sql());
            assertShape(db.jdbc());
        }
    }

    @Test
    void hibernateBase_appliesTheSameWay() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            assertThat(db.appliedVersions()).contains(VERSION);
            assertShape(db.jdbc());
            db.jdbc().execute(PgHarness.migration(VERSION).sql());
            assertShape(db.jdbc());
        }
    }

    @Test
    void aReIssuedInsertFindsTheOneLiveRow_writeAheadIdempotency() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            GcpTemplateRegistry registry = new GcpTemplateRegistry(db.jdbc());
            UUID op = UUID.randomUUID();
            Instant now = Instant.parse("2026-11-20T10:00:00Z");
            GcpTemplateRegistry.Row a = registry.registerCreating(GROUP, 1, "SPOT", "web-xas-aaaaaaaa", "global",
                    LINK + "web-xas-aaaaaaaa", LINK + "web-tpl-1", "7700", "sha", op, now);
            GcpTemplateRegistry.Row b = registry.registerCreating(GROUP, 1, "SPOT", "web-xas-aaaaaaaa", "global",
                    LINK + "web-xas-aaaaaaaa", LINK + "web-tpl-1", "7700", "sha", op, now.plusSeconds(5));
            assertThat(b.id()).isEqualTo(a.id());
            assertThat(count(db.jdbc(), "")).isEqualTo(1);
            assertThat(a.status()).isEqualTo("CREATING");
            assertThat(registry.findByOp(op)).isPresent();
            // The same name can be registered again once the first row is DELETED or GONE, never while it is live.
            db.jdbc().update("UPDATE spot_gcp_template SET status = 'DELETED' WHERE id = ?", a.id());
            GcpTemplateRegistry.Row c = registry.registerCreating(GROUP, 2, "SPOT", "web-xas-aaaaaaaa", "global",
                    LINK + "web-xas-aaaaaaaa", LINK + "web-tpl-1", "7700", "sha", UUID.randomUUID(), now);
            assertThat(c.id()).isNotEqualTo(a.id());
            assertThat(count(db.jdbc(), "")).isEqualTo(2);
        }
    }

    @Test
    void theUniqueIndexRejectsASecondLiveRowOfTheSameNameInTheSameScope() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            GcpTemplateRegistry registry = new GcpTemplateRegistry(db.jdbc());
            Instant now = Instant.parse("2026-11-20T10:00:00Z");
            registry.registerCreating(GROUP, 1, "SPOT", "dup", "global", LINK + "dup", LINK + "s", null, "sha", UUID.randomUUID(), now);
            assertThatThrownBy(() -> db.jdbc().update("INSERT INTO spot_gcp_template (id, group_id, generation, role, name, scope,"
                    + " self_link, source_self_link, body_sha256, created_by_op, status, created_at, updated_at)"
                    + " VALUES (?, ?, 2, 'SPOT', 'dup', 'global', 'x', 's', 'sha', ?, 'IN_USE', now(), now())",
                    UUID.randomUUID(), GROUP, UUID.randomUUID())).hasMessageContaining("ux_sgt_live_name");
            // A regional template of the same name is a different scope.
            GcpTemplateRegistry.Row regional = registry.registerCreating(GROUP, 1, "SPOT", "dup", "regions/us-central1",
                    LINK + "dup", LINK + "s", null, "sha", UUID.randomUUID(), now);
            assertThat(regional.regionOrNull()).isEqualTo("us-central1");
        }
    }

    @Test
    void retiredAfterTheRolloutAndOnlyThenAndOnlyPastGraceWithATerminalOperationIsOfferedForDeletion() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            GcpTemplateRegistry registry = new GcpTemplateRegistry(jdbc);
            Instant t0 = Instant.parse("2026-11-20T10:00:00Z");
            UUID op = UUID.randomUUID();
            insertOperation(jdbc, op, "RUNNING", t0);
            GcpTemplateRegistry.Row row = registry.registerCreating(GROUP, 1, "SPOT", "web-xas-aaaaaaaa", "global",
                    LINK + "web-xas-aaaaaaaa", LINK + "web-tpl-1", "7700", "sha", op, t0);
            registry.markInUse(row.id(), "9001", t0);
            // The MIG still references it: nothing is retired.
            assertThat(registry.retireUnreferenced(GROUP, List.of(LINK + "web-xas-aaaaaaaa"), Duration.ofDays(7), t0)).isZero();
            // The MIG moved back to the original: the XamOps template is retired, never deleted yet.
            assertThat(registry.retireUnreferenced(GROUP, List.of(LINK + "web-tpl-1"), Duration.ofDays(7), t0)).isEqualTo(1);
            assertThat(status(jdbc, row.id())).isEqualTo("RETIRED");
            // Past the grace period but the creating op is not terminal: not offered.
            assertThat(registry.deletionCandidates(t0.plus(Duration.ofDays(8)))).isEmpty();
            jdbc.update("UPDATE spot_operation SET status = 'SUCCEEDED' WHERE id = ?", op);
            // Terminal but inside the grace period: not offered.
            assertThat(registry.deletionCandidates(t0.plus(Duration.ofDays(6)))).isEmpty();
            // Terminal and past grace: offered, with its gcp_id.
            List<GcpTemplateRegistry.Row> due = registry.deletionCandidates(t0.plus(Duration.ofDays(8)));
            assertThat(due).extracting(GcpTemplateRegistry.Row::name).containsExactly("web-xas-aaaaaaaa");
            assertThat(due.get(0).gcpId()).isEqualTo("9001");
            // Claiming is guarded: a second claimer loses.
            assertThat(registry.markDeleting(row.id(), t0)).isTrue();
            assertThat(registry.markDeleting(row.id(), t0)).isFalse();
            registry.backToRetired(row.id(), Duration.ofDays(7), t0.plus(Duration.ofDays(8)));
            assertThat(registry.deletionCandidates(t0.plus(Duration.ofDays(9)))).isEmpty();
            // A row without a gcp_id (never read back) is never offered.
            GcpTemplateRegistry.Row unread = registry.registerCreating(GROUP, 2, "SPOT", "unread", "global", LINK + "unread",
                    LINK + "s", null, "sha", op, t0);
            jdbc.update("UPDATE spot_gcp_template SET status = 'RETIRED', delete_after = ? WHERE id = ?",
                    Timestamp.from(t0), unread.id());
            assertThat(registry.deletionCandidates(t0.plus(Duration.ofDays(30)))).extracting(GcpTemplateRegistry.Row::name)
                    .containsExactly("web-xas-aaaaaaaa");
        }
    }

    @Test
    void aRetiredTemplateTheMigReferencesAgainReturnsToInUse() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            GcpTemplateRegistry registry = new GcpTemplateRegistry(db.jdbc());
            Instant t0 = Instant.parse("2026-11-20T10:00:00Z");
            GcpTemplateRegistry.Row row = registry.registerCreating(GROUP, 1, "SPOT", "back", "global", LINK + "back", LINK + "s",
                    null, "sha", UUID.randomUUID(), t0);
            registry.markInUse(row.id(), "9002", t0);
            registry.retireUnreferenced(GROUP, List.of(), Duration.ofDays(7), t0);
            assertThat(status(db.jdbc(), row.id())).isEqualTo("RETIRED");
            registry.retireUnreferenced(GROUP, List.of(LINK + "back"), Duration.ofDays(7), t0);
            assertThat(status(db.jdbc(), row.id())).isEqualTo("IN_USE");
        }
    }

    private static String status(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT status FROM spot_gcp_template WHERE id = ?", String.class, id);
    }

    private static int count(JdbcTemplate jdbc, String where) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM spot_gcp_template" + where, Integer.class);
        return n == null ? 0 : n;
    }

    private static void insertOperation(JdbcTemplate jdbc, UUID id, String status, Instant now) {
        jdbc.update("INSERT INTO spot_operation (id, group_id, cloud_account_id, kind, action_class, status, prior_state,"
                        + " compensation_target, requested_by, deadline_at, created_at, updated_at)"
                        + " VALUES (?, ?, 3, 'ENABLE', 'PROGRESS', ?, 'UNMANAGED', 'SNAPSHOT', 'test', ?, ?, ?)",
                id, GROUP, status, Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private static void assertShape(JdbcTemplate jdbc) {
        assertThat(PgHarness.tableExists(jdbc, "spot_gcp_template")).isTrue();
        for (String index : List.of("ux_sgt_name", "ux_sgt_live_name", "ix_sgt_gc")) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND indexname = ?", Integer.class, index);
            assertThat(n).as(index).isEqualTo(1);
        }
        Integer fk = jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f'"
                + " AND (conrelid = to_regclass('spot_gcp_template') OR confrelid = to_regclass('spot_gcp_template'))", Integer.class);
        assertThat(fk).as("no foreign key (PO-21 retention)").isZero();
    }
}
