package com.xammer.cloud.service.autospotting.aws.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for the AWS event pipeline (design-aws.md §9.4, §9.6, §9.7): the inbox, {@code spot_group_event}
 * rows from events, group and instance lookups, the reconcile hint and the region coverage table. A
 * {@link JdbcTemplate} because none of the pipeline tables has an entity. Every write is idempotent or
 * keyed, so a redelivery or a replay repeats nothing.
 */
@Repository
public class AwsEventStore {

    /** Group states in which events are recorded: a group XamOps manages or has under legacy watch. */
    static final String MANAGED_STATES = "('GONE','UNMANAGED','REVERTED','PLANNED')";

    static final String INBOX_INSERT = "INSERT INTO autospotting_event_inbox"
            + " (provider_event_id, cloud_account_id, detail_type, received_at, outcome) VALUES (?, ?, ?, ?, ?)"
            + " ON CONFLICT (provider_event_id) DO NOTHING";

    static final String EVENT_INSERT = "INSERT INTO spot_group_event"
            + " (provider, group_id, event_type, event_key, instance_id, instance_type, zone, lifecycle,"
            + "  failure_class, occurred_at, source, recorded_at)"
            + " VALUES ('AWS', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EVENT', ?)"
            + " ON CONFLICT (group_id, event_type, event_key) DO NOTHING";

    private final JdbcTemplate jdbc;

    public AwsEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── inbox ────────────────────────────────────────────────────────────────

    public boolean inboxSeen(String providerEventId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM autospotting_event_inbox WHERE provider_event_id = ?",
                Integer.class, providerEventId);
        return n != null && n > 0;
    }

    /** Insert the event id; false when it was already there (a duplicate or a replay). */
    public boolean insertInbox(String providerEventId, Long cloudAccountId, String detailType, String outcome,
                               Instant now) {
        return jdbc.update(INBOX_INSERT, providerEventId, cloudAccountId, detailType, Timestamp.from(now),
                outcome) > 0;
    }

    public void setInboxOutcome(String providerEventId, String outcome) {
        jdbc.update("UPDATE autospotting_event_inbox SET outcome = ? WHERE provider_event_id = ?", outcome,
                providerEventId);
    }

    /** Delete inbox rows received before {@code cutoff}; the inbox is not audit (design-aws.md §9.7). */
    public int purgeInboxBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM autospotting_event_inbox WHERE received_at < ?", Timestamp.from(cutoff));
    }

    // ── forwarder role id (sender check) ─────────────────────────────────────

    public void storeForwarderRoleId(long cloudAccountId, String roleId) {
        jdbc.update("UPDATE cloud_account SET as_forwarder_role_id = ? WHERE id = ?", roleId, cloudAccountId);
    }

    // ── groups and instances ─────────────────────────────────────────────────

    /** The managed group of {@code groupKey} ({@code region/name}) on the account, if any. */
    public Optional<UUID> managedGroup(long cloudAccountId, String groupKey) {
        List<UUID> ids = jdbc.query("SELECT id FROM spot_group WHERE provider = 'AWS' AND cloud_account_id = ?"
                        + " AND group_key = ? AND state NOT IN " + MANAGED_STATES,
                (rs, i) -> rs.getObject(1, UUID.class), cloudAccountId, groupKey);
        return ids.stream().findFirst();
    }

    /** The managed group that {@code instanceId} belongs to on the account, from the reconciler's index. */
    public Optional<UUID> groupForInstance(long cloudAccountId, String instanceId) {
        List<UUID> ids = jdbc.query("SELECT g.id FROM spot_group_instance i JOIN spot_group g ON g.id = i.group_id"
                        + " WHERE i.instance_id = ? AND g.cloud_account_id = ? AND g.provider = 'AWS'"
                        + " AND g.state NOT IN " + MANAGED_STATES,
                (rs, i) -> rs.getObject(1, UUID.class), instanceId, cloudAccountId);
        return ids.stream().findFirst();
    }

    /** Remember {@code instanceId} for the group so the next event for it needs no AWS call. */
    public void rememberInstance(String instanceId, UUID groupId, String region, String lifecycle, Instant now) {
        jdbc.update("INSERT INTO spot_group_instance (instance_id, group_id, region, lifecycle, seen_at)"
                        + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (instance_id) DO UPDATE SET group_id = EXCLUDED.group_id,"
                        + " region = EXCLUDED.region, lifecycle = COALESCE(EXCLUDED.lifecycle,"
                        + " spot_group_instance.lifecycle), seen_at = EXCLUDED.seen_at",
                instanceId, groupId, region, lifecycle, Timestamp.from(now));
    }

    /** The lifecycle the index holds for {@code instanceId}, or null. */
    public String lifecycleOf(String instanceId) {
        List<String> rows = jdbc.query("SELECT lifecycle FROM spot_group_instance WHERE instance_id = ?",
                (rs, i) -> rs.getString(1), instanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── spot_group_event and the hint ────────────────────────────────────────

    /** One analytics row from an event; a row for the same fact (poll or event) is a no-op. */
    public int insertEvent(UUID groupId, String eventType, String eventKey, String instanceId, String instanceType,
                           String zone, String lifecycle, String failureClass, Instant occurredAt, Instant now) {
        return jdbc.update(EVENT_INSERT, groupId, eventType, eventKey, instanceId, instanceType, zone, lifecycle,
                failureClass, Timestamp.from(occurredAt), Timestamp.from(now));
    }

    /** Unfenced single-column write (design-core.md §2.4): never touches {@code version}. */
    public void setHint(UUID groupId, Instant at) {
        jdbc.update("UPDATE spot_group SET reconcile_hint_at = ? WHERE id = ?", Timestamp.from(at), groupId);
    }

    // ── region coverage ──────────────────────────────────────────────────────

    public record CoverageRow(String region, String status, String ruleNames, Instant checkedAt, Instant lastEventAt) {
    }

    public void upsertCoverage(long cloudAccountId, String region, String status, String ruleNamesJson,
                               Instant checkedAt) {
        jdbc.update("INSERT INTO autospotting_region_coverage (cloud_account_id, region, status, rule_names, checked_at)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?) ON CONFLICT (cloud_account_id, region) DO UPDATE SET"
                        + " status = EXCLUDED.status, rule_names = EXCLUDED.rule_names, checked_at = EXCLUDED.checked_at",
                cloudAccountId, region, status, ruleNamesJson, Timestamp.from(checkedAt));
    }

    /** Regions of the account the last run no longer lists lose their row, so a removed region reads as unknown. */
    public int deleteCoverageNotIn(long cloudAccountId, List<String> keepRegions) {
        if (keepRegions.isEmpty()) {
            return jdbc.update("DELETE FROM autospotting_region_coverage WHERE cloud_account_id = ?", cloudAccountId);
        }
        String marks = String.join(",", java.util.Collections.nCopies(keepRegions.size(), "?"));
        Object[] args = new Object[keepRegions.size() + 1];
        args[0] = cloudAccountId;
        for (int i = 0; i < keepRegions.size(); i++) {
            args[i + 1] = keepRegions.get(i);
        }
        return jdbc.update("DELETE FROM autospotting_region_coverage WHERE cloud_account_id = ? AND region NOT IN ("
                + marks + ")", args);
    }

    public List<CoverageRow> coverage(long cloudAccountId) {
        return jdbc.query("SELECT region, status, rule_names::text, checked_at, last_event_at"
                        + " FROM autospotting_region_coverage WHERE cloud_account_id = ? ORDER BY region",
                (rs, i) -> new CoverageRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()),
                cloudAccountId);
    }

    public Optional<String> coverageStatus(long cloudAccountId, String region) {
        List<String> rows = jdbc.query("SELECT status FROM autospotting_region_coverage"
                        + " WHERE cloud_account_id = ? AND region = ?", (rs, i) -> rs.getString(1), cloudAccountId, region);
        return rows.stream().findFirst();
    }

    /** Informational: the instant the pipeline last delivered an event for the region. */
    public void touchLastEvent(long cloudAccountId, String region, Instant at) {
        jdbc.update("UPDATE autospotting_region_coverage SET last_event_at = ? WHERE cloud_account_id = ?"
                + " AND region = ?", Timestamp.from(at), cloudAccountId, region);
    }
}
