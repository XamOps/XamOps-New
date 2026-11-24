package com.xammer.cloud.service.autospotting.ledger.observe;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads and writes the two working tables {@code ledger-observe} owns (design-ledger.md §2.1):
 * {@code spot_usage_interval} and {@code spot_group_capacity_sample}. They hold observed facts, not money;
 * the writer copies what it uses into each entry's immutable {@code inputs}. Plain SQL, so an interval is
 * upserted by its natural key {@code (provider, instance_id, start_at)} and a sample by its primary key.
 */
@Repository
public class ObservationStore {

    /** A measured AWS group the observer reads. {@code snapshotPayload} may be null (no snapshot yet). */
    public record ObservedGroup(UUID id, long cloudAccountId, String groupKey, String state, String snapshotPayload) {
    }

    /** The newest stored capacity sample of a group. */
    public record Sample(BigDecimal desired, BigDecimal odFloor, BigDecimal originalSpot) {
    }

    private final JdbcTemplate jdbc;

    public ObservationStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** AWS groups in a measured state (design-ledger.md §6.4 and plan 17.2). */
    public List<ObservedGroup> measuredGroups(List<String> states) {
        return measuredGroups("AWS", states);
    }

    /** Groups of {@code provider} ({@code AWS} or {@code GCP}) in a measured state. */
    public List<ObservedGroup> measuredGroups(String provider, List<String> states) {
        String in = states.stream().map(s -> "?").collect(Collectors.joining(","));
        return jdbc.query("SELECT g.id, g.cloud_account_id, g.group_key, g.state, CAST(s.payload AS text)"
                        + " FROM spot_group g LEFT JOIN spot_group_snapshot s ON s.id = g.snapshot_id"
                        + " WHERE g.provider = '" + ("GCP".equals(provider) ? "GCP" : "AWS")
                        + "' AND g.state IN (" + in + ") ORDER BY g.cloud_account_id, g.group_key",
                (rs, i) -> new ObservedGroup(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)),
                states.toArray());
    }

    public List<IntervalTracker.Open> openIntervals(UUID groupId) {
        return jdbc.query("SELECT id, instance_id, start_at, last_seen_at, first_absent_at FROM spot_usage_interval"
                        + " WHERE group_id = ? AND closed = false ORDER BY start_at, id",
                (rs, i) -> new IntervalTracker.Open(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5))),
                groupId);
    }

    /** Every instance id the group has an interval for in the last {@code days} days, open or closed. */
    public Set<String> knownInstanceIds(UUID groupId, int days) {
        return Set.copyOf(jdbc.queryForList("SELECT DISTINCT instance_id FROM spot_usage_interval WHERE group_id = ?"
                + " AND (closed = false OR coalesce(end_at, first_absent_at, start_at) >= now() - (? * interval '1 day'))",
                String.class, groupId, days));
    }

    public Optional<Sample> latestSample(UUID groupId) {
        List<Sample> rows = jdbc.query("SELECT desired_units, od_floor_units, original_spot_units"
                        + " FROM spot_group_capacity_sample WHERE group_id = ? ORDER BY observed_at DESC LIMIT 1",
                (rs, i) -> new Sample(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), groupId);
        return rows.stream().findFirst();
    }

    /**
     * Applies one group's interval writes and, when the capacity changed, one sample, in one transaction.
     * Never writes money. Returns the number of intervals opened.
     */
    @Transactional
    public int apply(UUID groupId, long cloudAccountId, Instant now, List<IntervalTracker.Action> actions,
                     Sample newSample) {
        return apply("AWS", groupId, cloudAccountId, now, actions, newSample);
    }

    /** As {@link #apply(UUID, long, Instant, List, Sample)} for the intervals of {@code provider}. */
    @Transactional
    public int apply(String provider, UUID groupId, long cloudAccountId, Instant now,
                     List<IntervalTracker.Action> actions, Sample newSample) {
        int opened = 0;
        for (IntervalTracker.Action a : actions) {
            if (a instanceof IntervalTracker.OpenNew o) {
                opened += insert(provider, groupId, cloudAccountId, now, o);
            } else if (a instanceof IntervalTracker.Seen s) {
                jdbc.update("UPDATE spot_usage_interval SET last_seen_at = ?, first_absent_at = NULL, updated_at = ?"
                        + " WHERE id = ? AND closed = false", ts(s.at()), ts(now), s.id());
            } else if (a instanceof IntervalTracker.Absent s) {
                jdbc.update("UPDATE spot_usage_interval SET first_absent_at = ?, updated_at = ?"
                        + " WHERE id = ? AND closed = false AND first_absent_at IS NULL",
                        ts(s.firstAbsentAt()), ts(now), s.id());
            } else if (a instanceof IntervalTracker.Close c) {
                jdbc.update("UPDATE spot_usage_interval SET end_at = ?, end_source = ?, end_cause = ?,"
                                + " end_cause_source = ?, provider_interrupted = ?, closed = true, updated_at = ?"
                                + " WHERE id = ? AND closed = false",
                        c.endAt() == null ? null : ts(c.endAt()), c.endSource(), c.cause().cause(), c.cause().source(),
                        EndCauseClassifier.PROVIDER_INTERRUPTED.equals(c.cause().cause()), ts(now), c.id());
            }
        }
        if (newSample != null) {
            jdbc.update("INSERT INTO spot_group_capacity_sample (group_id, observed_at, desired_units, od_floor_units,"
                            + " original_spot_units, source) VALUES (?, ?, ?, ?, ?, 'SWEEP') ON CONFLICT DO NOTHING",
                    groupId, ts(now), newSample.desired(), newSample.odFloor(), newSample.originalSpot());
        }
        return opened;
    }

    private int insert(String provider, UUID groupId, long cloudAccountId, Instant now, IntervalTracker.OpenNew o) {
        IntervalTracker.Present p = o.instance();
        IntervalTracker.Ending end = o.closedBy();
        return jdbc.update("INSERT INTO spot_usage_interval (id, provider, cloud_account_id, group_id, instance_id,"
                        + " instance_name, instance_type, zone, platform, purchase_option, capacity_weight, start_at, start_source,"
                        + " last_seen_at, first_absent_at, end_at, end_source, end_cause, end_cause_source,"
                        + " provider_interrupted, closed, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (provider, instance_id, start_at) DO NOTHING",
                o.id(), provider, cloudAccountId, groupId, p.instanceId(), "GCP".equals(provider) ? p.instanceId() : null,
                p.instanceType(), p.zone(), p.platform(),
                p.purchaseOption(), p.capacityWeight(), ts(o.startAt()), o.startSource(),
                ts(end == null ? now : end.endAt()),
                end == null ? null : ts(end.endAt()), end == null ? null : "ASG_ACTIVITY",
                end == null ? null : end.cause().cause(), end == null ? null : end.cause().source(),
                end != null && EndCauseClassifier.PROVIDER_INTERRUPTED.equals(end.cause().cause()), end != null,
                ts(now), ts(now));
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
