package com.xammer.cloud.service.autospotting.gcp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The template registry (design-gcp.md §9.1): the only basis for deleting a GCP instance template
 * (FR-GCP-07). A template that is not a row here is never deleted, whatever its name or label says. The row
 * is written {@code CREATING} before the insert call, so a crash between call and result still leaves a
 * record; the insert is {@code ON CONFLICT DO NOTHING} against {@code ux_sgt_live_name} (DR-28), so a
 * re-issued step finds the row instead of adding a second one.
 */
@Component
public class GcpTemplateRegistry {
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    /** One registry row. */
    public record Row(UUID id, UUID groupId, int generation, String role, String name, String scope, String selfLink,
                      String gcpId, String sourceSelfLink, String bodySha256, UUID createdByOp, String status,
                      Instant deleteAfter, String insertOperation) {
        /** The region of a regional template, or null for a global one. */
        public String regionOrNull() {
            return scope.startsWith("regions/") ? scope.substring("regions/".length()) : null;
        }
    }

    private final JdbcTemplate jdbc;

    public GcpTemplateRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS = "id, group_id, generation, role, name, scope, self_link, gcp_id, source_self_link,"
            + " body_sha256, created_by_op, status, delete_after, insert_operation";

    private static Row map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Timestamp after = rs.getTimestamp(13);
        return new Row((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                (UUID) rs.getObject(11), rs.getString(12), after == null ? null : after.toInstant(), rs.getString(14));
    }

    /** Write the CREATING row before the cloud insert. Idempotent: an existing live row for the name is returned. */
    public Row registerCreating(UUID groupId, int generation, String role, String name, String scope, String selfLink,
                                String sourceSelfLink, String sourceGcpId, String bodySha256, UUID opId, Instant now) {
        jdbc.update("INSERT INTO spot_gcp_template (id, group_id, generation, role, name, scope, self_link,"
                        + " source_self_link, source_gcp_id, body_sha256, created_by_op, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATING', ?, ?)"
                        + " ON CONFLICT (scope, name) WHERE status IN ('CREATING','IN_USE','RETIRED','DELETING') DO NOTHING",
                UUID.randomUUID(), groupId, generation, role, name, scope, selfLink, sourceSelfLink, sourceGcpId,
                bodySha256, opId, Timestamp.from(now), Timestamp.from(now));
        return findLive(scope, name).orElseThrow(() -> new IllegalStateException("registry row missing after insert"));
    }

    /** Record the insert Operation's self-link (set once the insert call returned). */
    public void setInsertOperation(UUID id, String operationSelfLink, Instant now) {
        jdbc.update("UPDATE spot_gcp_template SET insert_operation = ?, updated_at = ? WHERE id = ?",
                operationSelfLink, Timestamp.from(now), id);
    }

    public Optional<Row> findLive(String scope, String name) {
        return jdbc.query("SELECT " + COLS + " FROM spot_gcp_template WHERE scope = ? AND name = ?"
                + " AND status IN ('CREATING','IN_USE','RETIRED','DELETING')", GcpTemplateRegistry::map, scope, name)
                .stream().findFirst();
    }

    public Optional<Row> findByOp(UUID opId) {
        return jdbc.query("SELECT " + COLS + " FROM spot_gcp_template WHERE created_by_op = ? ORDER BY created_at LIMIT 1",
                GcpTemplateRegistry::map, opId).stream().findFirst();
    }

    /** Every self-link XamOps ever registered for the group, whatever its status: the "XamOps-owned" set of a revert. */
    public List<String> ownedLinks(UUID groupId) {
        return jdbc.queryForList("SELECT self_link FROM spot_gcp_template WHERE group_id = ?", String.class, groupId);
    }
    /** The group's newest SPOT template that still exists (IN_USE or RETIRED): what a FALLBACK_EXIT returns to. */
    public Optional<Row> latestSpot(UUID groupId) {
        return jdbc.query("SELECT " + COLS + " FROM spot_gcp_template WHERE group_id = ? AND role = 'SPOT'"
                + " AND status IN ('IN_USE','RETIRED') ORDER BY generation DESC LIMIT 1", GcpTemplateRegistry::map, groupId)
                .stream().findFirst();
    }
    /** Highest generation recorded for the group (0 when none). */
    public int maxGeneration(UUID groupId) {
        Integer g = jdbc.queryForObject("SELECT COALESCE(MAX(generation), 0) FROM spot_gcp_template WHERE group_id = ?",
                Integer.class, groupId);
        return g == null ? 0 : g;
    }

    /** The template read back from the cloud: its numeric id is filled and the row goes IN_USE. */
    public void markInUse(UUID id, String gcpId, Instant now) {
        jdbc.update("UPDATE spot_gcp_template SET gcp_id = COALESCE(gcp_id, ?), status = 'IN_USE', updated_at = ?"
                + " WHERE id = ? AND status IN ('CREATING','IN_USE')", gcpId, Timestamp.from(now), id);
    }

    /**
     * After a rollout has completed: every {@code IN_USE} row of the group whose template the MIG no longer
     * references becomes {@code RETIRED} with {@code delete_after = now + retention}. Nothing is deleted here.
     * Rows still referenced stay IN_USE; a RETIRED row the MIG references again returns to IN_USE.
     */
    public int retireUnreferenced(UUID groupId, Collection<String> liveTemplateLinks, Duration retention, Instant now) {
        List<Row> rows = jdbc.query("SELECT " + COLS + " FROM spot_gcp_template WHERE group_id = ?"
                + " AND status IN ('IN_USE','RETIRED')", GcpTemplateRegistry::map, groupId);
        int changed = 0;
        for (Row r : rows) {
            boolean referenced = liveTemplateLinks.stream().anyMatch(l -> sameTemplate(l, r.selfLink()));
            if (!referenced && "IN_USE".equals(r.status())) {
                changed += jdbc.update("UPDATE spot_gcp_template SET status = 'RETIRED', retired_at = ?, delete_after = ?,"
                                + " updated_at = ? WHERE id = ? AND status = 'IN_USE'", Timestamp.from(now),
                        Timestamp.from(now.plus(retention)), Timestamp.from(now), r.id());
            } else if (referenced && "RETIRED".equals(r.status())) {
                changed += jdbc.update("UPDATE spot_gcp_template SET status = 'IN_USE', retired_at = NULL,"
                        + " delete_after = NULL, updated_at = ? WHERE id = ? AND status = 'RETIRED'",
                        Timestamp.from(now), r.id());
            }
        }
        return changed;
    }

    /** Rows eligible by registry state for deletion: RETIRED, past the grace period, with a gcp_id, whose creating op is terminal. */
    public List<Row> deletionCandidates(Instant now) {
        return jdbc.query("SELECT t.id, t.group_id, t.generation, t.role, t.name, t.scope, t.self_link, t.gcp_id,"
                + " t.source_self_link, t.body_sha256, t.created_by_op, t.status, t.delete_after, t.insert_operation"
                + " FROM spot_gcp_template t JOIN spot_operation o ON o.id = t.created_by_op"
                + " WHERE t.status = 'RETIRED' AND t.delete_after < ? AND t.gcp_id IS NOT NULL"
                + " AND o.status IN ('SUCCEEDED','FAILED','CANCELLED') ORDER BY t.delete_after LIMIT 100",
                GcpTemplateRegistry::map, Timestamp.from(now));
    }

    /**
     * Rows still {@code CREATING} whose creating operation is terminal and untouched for {@code quiet}: a template an
     * operation that was preempted or failed may have inserted. Resolved by the GC job (read the template back, then
     * retire it), never deleted from here.
     */
    public List<Row> orphanCandidates(Instant now, Duration quiet) {
        return jdbc.query("SELECT t.id, t.group_id, t.generation, t.role, t.name, t.scope, t.self_link, t.gcp_id,"
                + " t.source_self_link, t.body_sha256, t.created_by_op, t.status, t.delete_after, t.insert_operation"
                + " FROM spot_gcp_template t JOIN spot_operation o ON o.id = t.created_by_op"
                + " WHERE t.status = 'CREATING' AND o.status IN ('SUCCEEDED','FAILED','CANCELLED') AND t.updated_at < ?"
                + " ORDER BY t.created_at LIMIT 100", GcpTemplateRegistry::map, Timestamp.from(now.minus(quiet)));
    }
    /** An orphan that exists in the cloud and is ours: record its id and retire it with a grace period. */
    public void retireOrphan(UUID id, String gcpId, Duration retention, Instant now) {
        jdbc.update("UPDATE spot_gcp_template SET gcp_id = COALESCE(gcp_id, ?), status = 'RETIRED', retired_at = ?,"
                + " delete_after = ?, updated_at = ? WHERE id = ? AND status = 'CREATING'", gcpId, Timestamp.from(now),
                Timestamp.from(now.plus(retention)), Timestamp.from(now), id);
    }
    /** RETIRED to DELETING, guarded: false when another instance already took the row. */
    public boolean markDeleting(UUID id, Instant now) {
        return jdbc.update("UPDATE spot_gcp_template SET status = 'DELETING', updated_at = ? WHERE id = ?"
                + " AND status = 'RETIRED'", Timestamp.from(now), id) == 1;
    }

    public void markDeleted(UUID id, Instant now) {
        jdbc.update("UPDATE spot_gcp_template SET status = 'DELETED', updated_at = ? WHERE id = ? AND status = 'DELETING'",
                Timestamp.from(now), id);
    }

    /** The template is still used by a MIG (or the delete could not complete): back to RETIRED with a new grace period. */
    public void backToRetired(UUID id, Duration retention, Instant now) {
        jdbc.update("UPDATE spot_gcp_template SET status = 'RETIRED', delete_after = ?, updated_at = ? WHERE id = ?"
                + " AND status = 'DELETING'", Timestamp.from(now.plus(retention)), Timestamp.from(now), id);
    }

    public void markGone(UUID id, Instant now) {
        jdbc.update("UPDATE spot_gcp_template SET status = 'GONE', updated_at = ? WHERE id = ? AND status <> 'DELETED'",
                Timestamp.from(now), id);
    }

    /** True when {@code link} and {@code selfLink} name the same template (host and API version are ignored). */
    public static boolean sameTemplate(String link, String selfLink) {
        return tail(link) != null && tail(link).equals(tail(selfLink));
    }

    private static String tail(String link) {
        if (link == null) {
            return null;
        }
        int i = link.indexOf("/projects/");
        return i < 0 ? link : link.substring(i);
    }
}
