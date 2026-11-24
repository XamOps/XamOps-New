package com.xammer.cloud.service.autospotting.gcp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes the GCP analytics events to {@code spot_group_event} (design-gcp.md §8.3, AC-HLT-06, AC-EVT-06). Every insert
 * is {@code ON CONFLICT DO NOTHING} on {@code ux_sge_dedupe (group_id, event_type, event_key)}, so the same preemption
 * seen by two sweeps, or by two instances, is one row. Configuration changes are never rows here.
 */
@Component
public class GcpEventRecorder {
    static final String SQL = "INSERT INTO spot_group_event"
            + " (provider, group_id, event_type, event_key, instance_id, instance_type, zone, lifecycle,"
            + "  failure_class, occurred_at, source, recorded_at)"
            + " VALUES ('GCP', ?, ?, ?, ?, ?, ?, ?, NULL, ?, 'POLL', ?)"
            + " ON CONFLICT (group_id, event_type, event_key) DO NOTHING";
    public static final String PREEMPTION = "PREEMPTION";
    public static final String SUSPECTED = "SUSPECTED_PREEMPTION";

    private final JdbcTemplate jdbc;

    public GcpEventRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the row was written, false when the same fact was already recorded. */
    public boolean record(UUID groupId, String eventType, String eventKey, String instance, String instanceType,
                          String zone, Instant occurredAt, Instant now) {
        return jdbc.update(SQL, groupId, eventType, eventKey, instance, instanceType, zone, "spot",
                Timestamp.from(occurredAt), Timestamp.from(now)) == 1;
    }

    /** Recorded {@code eventType} rows of a group at or after {@code since}. */
    public int countSince(UUID groupId, String eventType, Instant since) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM spot_group_event WHERE group_id = ? AND event_type = ?"
                + " AND occurred_at >= ?", Integer.class, groupId, eventType, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    /** The zone of each recorded preemption since {@code since}, as {@code zone -> count} (explains a zonal stockout). */
    public java.util.Map<String, Integer> countByZoneSince(UUID groupId, Instant since) {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        jdbc.query("SELECT zone, count(*) FROM spot_group_event WHERE group_id = ? AND event_type = 'PREEMPTION'"
                + " AND occurred_at >= ? GROUP BY zone", rs -> {
            out.put(rs.getString(1) == null ? "unknown" : rs.getString(1), rs.getInt(2));
        }, groupId, Timestamp.from(since));
        return out;
    }
}
