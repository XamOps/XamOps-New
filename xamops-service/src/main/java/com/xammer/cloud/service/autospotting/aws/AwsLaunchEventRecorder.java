package com.xammer.cloud.service.autospotting.aws;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.model.Activity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes the analytics events a {@code DescribeScalingActivities} page carries to
 * {@code spot_group_event} (design-core.md §8.1, plan-phase-18.md task 18.3, AC-HLT-06): launches,
 * launch failures and terminations by their Auto Scaling {@code ActivityId}, and rebalance
 * recommendations. Every insert is {@code ON CONFLICT DO NOTHING} on {@code ux_sge_dedupe}
 * {@code (group_id, event_type, event_key)}, so a sweep, a poll and the event pipeline that record the
 * same activity collapse to one row. Configuration changes are never rows here (they are audit
 * records), and a failure is classified by {@link AwsSpotFailureMatcher}.
 */
@Component
public class AwsLaunchEventRecorder {

    static final String SQL = "INSERT INTO spot_group_event"
            + " (provider, group_id, event_type, event_key, instance_id, instance_type, zone, lifecycle,"
            + "  failure_class, occurred_at, source, recorded_at)"
            + " VALUES ('AWS', ?, ?, ?, ?, NULL, ?, NULL, ?, ?, 'POLL', ?)"
            + " ON CONFLICT (group_id, event_type, event_key) DO NOTHING";

    private final JdbcTemplate jdbc;

    public AwsLaunchEventRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Record the activities; returns the number of rows written (duplicates write none). */
    public int record(UUID groupId, List<Activity> activities, Instant now) {
        int written = 0;
        for (Activity a : activities) {
            if (a.activityId() == null || a.startTime() == null) {
                continue;
            }
            String type = typeOf(a);
            if (type == null) {
                continue;
            }
            String failureClass = null;
            if ("LAUNCH_FAILED".equals(type)) {
                failureClass = AwsSpotFailureMatcher.classify(a.statusMessage(), a.description()).name();
            }
            String key = "REBALANCE_RECOMMENDATION".equals(type) ? "act:" + a.activityId() : a.activityId();
            written += jdbc.update(SQL, groupId, type, key, instanceIdOf(a), a.details() == null ? null : zoneOf(a),
                    failureClass, Timestamp.from(a.startTime()), Timestamp.from(now));
        }
        return written;
    }

    /** The event type of an activity, or null when it is not an analytics event. */
    static String typeOf(Activity a) {
        String description = a.description() == null ? "" : a.description();
        String cause = a.cause() == null ? "" : a.cause().toLowerCase(java.util.Locale.ROOT);
        String code = a.statusCodeAsString();
        if (cause.contains("rebalance recommendation")) {
            return "REBALANCE_RECOMMENDATION";
        }
        if (description.startsWith("Launching")) {
            if ("Successful".equals(code)) {
                return "LAUNCH";
            }
            if ("Failed".equals(code) || "Cancelled".equals(code)) {
                return "LAUNCH_FAILED";
            }
            return null;
        }
        if (description.startsWith("Terminating") && "Successful".equals(code)) {
            return "TERMINATE";
        }
        return null;
    }

    private static String instanceIdOf(Activity a) {
        String d = a.description() == null ? "" : a.description();
        int i = d.indexOf("i-");
        if (i < 0) {
            return null;
        }
        int end = i;
        while (end < d.length() && (Character.isLetterOrDigit(d.charAt(end)) || d.charAt(end) == '-')) {
            end++;
        }
        return d.substring(i, end);
    }

    private static String zoneOf(Activity a) {
        // Details is a JSON string such as {"Availability Zone":"ap-south-1a"}; read it without a parser.
        String details = a.details();
        int k = details.indexOf("Availability Zone\":\"");
        if (k < 0) {
            return null;
        }
        int start = k + "Availability Zone\":\"".length();
        int end = details.indexOf('"', start);
        return end < 0 ? null : details.substring(start, end);
    }
    /** Interruption warnings of a group since {@code since}, from the events the pipeline recorded (FB-4, §11.1). */
    public int interruptionsSince(UUID groupId, Instant since) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM spot_group_event WHERE group_id = ?"
                + " AND event_type = 'INTERRUPTION_WARNING' AND occurred_at >= ?", Integer.class, groupId,
                Timestamp.from(since));
        return n == null ? 0 : n;
    }
}
