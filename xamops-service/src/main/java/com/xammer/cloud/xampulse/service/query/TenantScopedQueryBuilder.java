package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.dto.XpSeverity;
import com.xammer.cloud.xampulse.exception.XamPulseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single place that writes ClickHouse SQL for XamPulse reads.
 *
 * <p>Security rules, all enforced here and covered by {@code TenantScopedQueryBuilderTest}:
 * <ul>
 *   <li>every statement has {@code WHERE tenant_id IN (?, ...)} first, and the tenant ids are the first parameters;</li>
 *   <li>user-supplied values (filter values, instance ids, regions, label values, cursors) are always bound
 *       parameters, never concatenated;</li>
 *   <li>identifiers come from closed enums, constants, or (for attribute/label keys used as a column expression)
 *       a strict {@code [A-Za-z0-9_.-]{1,64}} whitelist re-checked here;</li>
 *   <li>LIKE patterns are escaped.</li>
 * </ul>
 */
public final class TenantScopedQueryBuilder {

    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9_.\\-]{1,64}$");
    private static final Pattern INSTANCE = Pattern.compile("^[A-Za-z0-9._:\\-]{0,64}$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,20}$");

    /**
     * Never alias an expression with a column's own name: ClickHouse resolves a name in WHERE to a SELECT alias first,
     * so "toString(severity) AS severity" turned the severity filter into toUInt8('INFO') and every filtered log query
     * failed (seen from ClickHouse 25.8 on).
     */
    static final String LOG_COLUMNS = "tenant_id, toUnixTimestamp64Milli(ts) AS ts_ms, region, instance_id, service, source, "
            + "toString(severity) AS severity_name, body, attrs, trace_id, container_id, "
            + "toString(cityHash64(body)) AS body_hash, toUnixTimestamp64Milli(ingest_ts) AS ingest_ms";

    /** Pattern template used for display. '#' (not '?') so the text never contains a placeholder character. */
    private static final String TEMPLATE_EXPR = "substring(replaceRegexpAll(body, '[0-9a-fA-F-]{8,}|[0-9]+', '#'), 1, 300)";

    /** Rollup table choice for a metric time range. */
    public record MetricPlan(String table, int tableStepSec, int stepSec) {}

    public record LogCursor(long tsMs, long tenantId, String instanceId, String bodyHash) {
        public LogCursor {
            if (tenantId <= 0 || instanceId == null || !INSTANCE.matcher(instanceId).matches()
                    || bodyHash == null || !DIGITS.matcher(bodyHash).matches()) {
                throw XamPulseException.invalid("Invalid cursor.");
            }
        }
    }

    // ---------------------------------------------------------------- logs

    public ScopedSql search(QueryScope scope, FilterAst filter, boolean desc, int limit, LogCursor cursor) {
        Q q = start("SELECT " + LOG_COLUMNS + " FROM xp_logs", scope);
        logWhere(q, scope, filter);
        if (cursor != null) {
            String op = desc ? "<" : ">";
            q.add(" AND (ts, tenant_id, instance_id, cityHash64(body)) " + op
                            + " (fromUnixTimestamp64Milli(?), toUInt64(?), ?, toUInt64(?))",
                    cursor.tsMs(), cursor.tenantId(), cursor.instanceId(), cursor.bodyHash());
        }
        q.add(desc
                ? " ORDER BY ts DESC, tenant_id DESC, instance_id DESC, cityHash64(body) DESC"
                : " ORDER BY ts ASC, tenant_id ASC, instance_id ASC, cityHash64(body) ASC");
        q.add(" LIMIT ?", clampLimit(limit));
        return q.build(scope);
    }

    public ScopedSql histogram(QueryScope scope, FilterAst filter, long bucketSec) {
        if (bucketSec < 1 || bucketSec > 86_400L * 7) {
            throw new IllegalArgumentException("bucketSec out of range");
        }
        Q q = start("SELECT toUnixTimestamp(toStartOfInterval(ts, INTERVAL " + bucketSec
                + " SECOND)) AS t, toString(severity) AS sev, count() AS c FROM xp_logs", scope);
        logWhere(q, scope, filter);
        q.add(" GROUP BY t, sev ORDER BY t ASC");
        return q.build(scope);
    }

    /** Top-N values of one field. {@code attrKey} is only used when {@code field == ATTR}. */
    public ScopedSql facet(QueryScope scope, FilterAst filter, LogField field, String attrKey, int topN) {
        String col = switch (field) {
            case SEVERITY -> "toString(severity)";
            case ATTR -> {
                if (attrKey == null || !SAFE_KEY.matcher(attrKey).matches()) {
                    throw XamPulseException.badFilter("Invalid attribute name.");
                }
                yield "attrs['" + attrKey + "']";
            }
            default -> field.column();
        };
        Q q = start("SELECT " + col + " AS v, count() AS c FROM xp_logs", scope);
        logWhere(q, scope, filter);
        q.add(" AND " + col + " != ''");
        q.add(" GROUP BY v ORDER BY c DESC LIMIT ?", Math.max(1, Math.min(topN, 50)));
        return q.build(scope);
    }

    public ScopedSql patterns(QueryScope scope, FilterAst filter, int limit) {
        Q q = start("SELECT toString(tpl_hash) AS tpl, count() AS c, min(toUnixTimestamp64Milli(ts)) AS first_ms, any("
                + TEMPLATE_EXPR + ") AS template, argMin(instance_id, ts) AS s_instance, "
                + "argMin(toString(cityHash64(body)), ts) AS s_hash, argMin(toUnixTimestamp64Milli(ts), ts) AS s_ts, "
                + "max(toUInt8(severity)) AS sev FROM xp_logs", scope);
        logWhere(q, scope, filter);
        q.add(" GROUP BY tpl ORDER BY c DESC LIMIT ?", Math.max(1, Math.min(limit, 100)));
        return q.build(scope);
    }

    /** Per-pattern counts in a window (used for the previous-period comparison). */
    public ScopedSql patternCounts(QueryScope scope, FilterAst filter, Collection<String> tplHashes) {
        Q q = start("SELECT toString(tpl_hash) AS tpl, count() AS c FROM xp_logs", scope);
        logWhere(q, scope, filter);
        hashIn(q, tplHashes);
        q.add(" GROUP BY tpl");
        return q.build(scope);
    }

    public ScopedSql patternTrend(QueryScope scope, FilterAst filter, Collection<String> tplHashes, long bucketSec) {
        if (bucketSec < 1) {
            throw new IllegalArgumentException("bucketSec out of range");
        }
        Q q = start("SELECT toString(tpl_hash) AS tpl, toUnixTimestamp(toStartOfInterval(ts, INTERVAL " + bucketSec
                + " SECOND)) AS t, count() AS c FROM xp_logs", scope);
        logWhere(q, scope, filter);
        hashIn(q, tplHashes);
        q.add(" GROUP BY tpl, t ORDER BY t ASC");
        return q.build(scope);
    }

    /** Fetches exactly one row identified by a decoded row id. */
    public ScopedSql anchor(long tenantId, long tsMs, String instanceId, String bodyHash) {
        requireHash(bodyHash);
        Q q = new Q("SELECT " + LOG_COLUMNS + " FROM xp_logs WHERE " + ScopedSql.TENANT_MARKER + "?)", List.of(tenantId), tenantId);
        q.add(" AND ts = fromUnixTimestamp64Milli(?) AND instance_id = ? AND cityHash64(body) = toUInt64(?) LIMIT 1",
                tsMs, instanceId, bodyHash);
        return q.buildFor(List.of(tenantId));
    }

    /** Neighbours of an anchor row: {@code before == true} gives older rows (nearest first). */
    public ScopedSql context(long tenantId, long tsMs, String bodyHash, String instanceId, String source,
                             boolean before, boolean sameSource, int limit) {
        requireHash(bodyHash);
        Q q = new Q("SELECT " + LOG_COLUMNS + " FROM xp_logs WHERE " + ScopedSql.TENANT_MARKER + "?)", List.of(tenantId), tenantId);
        long window = 3_600_000L;
        q.add(" AND ts >= fromUnixTimestamp64Milli(?) AND ts <= fromUnixTimestamp64Milli(?)", tsMs - window, tsMs + window);
        if (before) {
            q.add(" AND (ts < fromUnixTimestamp64Milli(?) OR (ts = fromUnixTimestamp64Milli(?) AND cityHash64(body) < toUInt64(?)))",
                    tsMs, tsMs, bodyHash);
        } else {
            q.add(" AND (ts > fromUnixTimestamp64Milli(?) OR (ts = fromUnixTimestamp64Milli(?) AND cityHash64(body) > toUInt64(?)))",
                    tsMs, tsMs, bodyHash);
        }
        if (sameSource) {
            q.add(" AND instance_id = ? AND source = ?", instanceId, source);
        }
        q.add(before ? " ORDER BY ts DESC, cityHash64(body) DESC" : " ORDER BY ts ASC, cityHash64(body) ASC");
        q.add(" LIMIT ?", Math.max(1, Math.min(limit, 100)));
        return q.buildFor(List.of(tenantId));
    }

    /** Live-tail poll: rows ingested after the cursor, oldest first. */
    public ScopedSql tailPoll(QueryScope scope, FilterAst filter, long sinceIngestMs, int limit) {
        Q q = start("SELECT " + LOG_COLUMNS + " FROM xp_logs", scope);
        logWhere(q, scope, filter);
        q.add(" AND ingest_ts > fromUnixTimestamp64Milli(?)", sinceIngestMs);
        q.add(" ORDER BY ingest_ts ASC, cityHash64(body) ASC LIMIT ?", Math.max(1, Math.min(limit, 5000)));
        return q.build(scope);
    }

    // ------------------------------------------------------------- metrics

    public static MetricPlan planMetric(QueryScope scope, Integer requestedStepSec) {
        long range = scope.rangeSeconds();
        String table;
        int base;
        if (range <= 6 * 3600L) {
            table = "xp_metrics_1m";
            base = 60;
        } else if (range <= 3 * 86_400L) {
            table = "xp_metrics_5m";
            base = 300;
        } else {
            table = "xp_metrics_1h";
            base = 3600;
        }
        long wanted = Math.max(requestedStepSec == null ? 0 : requestedStepSec, (range + 3999) / 4000);
        long step = Math.max(base, ((wanted + base - 1) / base) * base);
        return new MetricPlan(table, base, (int) step);
    }

    public ScopedSql metricSeries(QueryScope scope, MetricSpec spec, MetricPlan plan) {
        if ("rate".equals(spec.agg())) {
            return counterRateSeries(scope, spec, plan);
        }
        StringBuilder sel = new StringBuilder("SELECT toUnixTimestamp(toStartOfInterval(ts, INTERVAL " + plan.stepSec()
                + " SECOND)) AS t");
        StringBuilder grp = new StringBuilder("t");
        int i = 0;
        for (String g : spec.groupBy()) {
            sel.append(", ").append(groupExpr(g)).append(" AS g").append(i);
            grp.append(", g").append(i);
            i++;
        }
        sel.append(", ").append(aggExpr(spec.agg())).append(" AS v FROM ").append(plan.table());
        Q q = start(sel.toString(), scope);
        q.add(" AND metric = ?", spec.metric());
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", scope.from().getEpochSecond(), scope.to().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        labelFilters(q, spec.filters());
        q.add(" GROUP BY " + grp + " ORDER BY t ASC");
        return q.build(scope);
    }

    /**
     * Per-second rate of a cumulative counter (disk bytes, network bytes, packets, CPU time).
     *
     * <p>A counter only means something per series, so the rate is worked out for each (instance, label set) before
     * anything is added up: the last value in each step bucket minus the last value of that series' previous bucket,
     * divided by the time between the two. The per-series rates are then summed per group, which gives the total
     * across the selected devices. One bucket before the range is read so the first bucket has a predecessor; a
     * series' first bucket has none and is left out rather than guessed.
     *
     * <p>A smaller value than before means the counter restarted (host reboot, container or process restart). How
     * much was counted before the restart, and when it happened, is unknown: after a reboot with a gap, "new value /
     * elapsed time" read 14 KB/s for a host doing 150 KB/s. So such a bucket is left out, and so is the group's
     * bucket, because a total without one of its members is too low. Every value returned is an exactly measured
     * increase; a restart shows as one missing point, never as a spike, a dip or a negative rate.
     *
     * <p>The previous formula, {@code (max - min) / step} over the whole group, mixed different devices' lifetime
     * counters (a bridge's total minus an idle interface's zero) and missed the increase between buckets.
     */
    private ScopedSql counterRateSeries(QueryScope scope, MetricSpec spec, MetricPlan plan) {
        int step = plan.stepSec();
        long firstBucket = Math.floorDiv(scope.from().getEpochSecond(), step) * step;
        StringBuilder sel = new StringBuilder("SELECT t");
        StringBuilder grp = new StringBuilder("t");
        int i = 0;
        for (String g : spec.groupBy()) {
            sel.append(", ").append(groupExpr(g)).append(" AS g").append(i);
            grp.append(", g").append(i);
            i++;
        }
        sel.append(", sum(r) AS v FROM (SELECT t, instance_id, labels_key, lv < prev_lv AS restarted, ")
                .append("if(restarted, 0, (lv - prev_lv) / (toInt64(t) - toInt64(prev_t))) AS r FROM (")
                .append(PREVIOUS_BUCKET)
                .append("SELECT toUnixTimestamp(toStartOfInterval(ts, INTERVAL ").append(step)
                .append(" SECOND)) AS t, instance_id, labels_key, argMaxMerge(last_state) AS lv FROM ").append(plan.table());
        Q q = start(sel.toString(), scope);
        q.add(" AND metric = ?", spec.metric());
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", firstBucket - step, scope.to().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        labelFilters(q, spec.filters());
        q.add(" GROUP BY t, instance_id, labels_key)" + SERIES_WINDOW + ") WHERE prev_t > 0 AND t >= ?)", firstBucket);
        q.add(" GROUP BY " + grp + " HAVING max(restarted) = 0 ORDER BY t ASC");
        return q.build(scope);
    }

    /** Adds the previous bucket's value and time to each row of one series (first row: 0, filtered out by callers). */
    private static final String PREVIOUS_BUCKET = "SELECT t, instance_id, labels_key, lv, "
            + "lagInFrame(lv, 1, toFloat64(0)) OVER w AS prev_lv, lagInFrame(t, 1, toUInt32(0)) OVER w AS prev_t FROM (";

    private static final String SERIES_WINDOW =
            " WINDOW w AS (PARTITION BY instance_id, labels_key ORDER BY t ASC ROWS BETWEEN 1 PRECEDING AND CURRENT ROW)";

    private static void labelFilters(Q q, List<MetricSpec.LabelFilter> filters) {
        for (MetricSpec.LabelFilter f : filters) {
            switch (f.op()) {
                case "=" -> q.add(" AND has(splitByChar(',', labels_key), ?)", f.key() + "=" + f.value());
                case "!=" -> q.add(" AND NOT has(splitByChar(',', labels_key), ?)", f.key() + "=" + f.value());
                default -> q.add(" AND arrayExists(x -> match(x, ?), splitByChar(',', labels_key))",
                        "^" + escapeRegex(f.key()) + "=(?:" + f.value() + ")$");
            }
        }
    }

    /** Latest value of each (instance, metric, label set) since {@code sinceEpochSec}; used by host and container views. */
    public ScopedSql latestMetrics(QueryScope scope, long sinceEpochSec, Collection<String> metrics) {
        Q q = start("SELECT instance_id, metric, labels_key, argMaxMerge(last_state) AS v, toUnixTimestamp(max(ts)) AS last_ts "
                + "FROM xp_metrics_1m", scope);
        q.add(" AND ts >= toDateTime(?, 'UTC')", sinceEpochSec);
        inList(q, "metric", new ArrayList<>(metrics));
        inList(q, "instance_id", scope.instanceIds());
        q.add(" GROUP BY instance_id, metric, labels_key");
        return q.build(scope);
    }

    /** Metric names seen for the tenant in the window (custom and canonical), for the catalog. */
    public ScopedSql metricNames(QueryScope scope) {
        Q q = start("SELECT metric, any(labels_key) AS sample_labels FROM xp_metrics_1h", scope);
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", scope.from().getEpochSecond(), scope.to().getEpochSecond());
        q.add(" GROUP BY metric ORDER BY metric ASC LIMIT 1000");
        return q.build(scope);
    }

    public ScopedSql baseline(QueryScope scope, String metric, Collection<String> instanceIds) {
        Q q = start("SELECT instance_id, hour_of_week, med, mad FROM xp_baseline FINAL", scope);
        q.add(" AND metric = ?", metric);
        inList(q, "instance_id", new ArrayList<>(instanceIds));
        return q.build(scope);
    }

    /**
     * Hourly samples of one metric per instance over the last {@code windowDays}, used by the baseline writer to
     * recompute median/MAD per hour-of-week. Each hour is the host-level value of {@link BaselineSignal} (CPU busy =
     * 1 - idle, memory used, the fullest filesystem, load), never the average of the metric's label series. A metric
     * without a definition is averaged across its series, which is only meaningful for single-series metrics.
     * Tenant predicate first; {@code metric}, the label filter and the window start are bound.
     */
    public ScopedSql baselineSamples(QueryScope scope, String metric, int windowDays) {
        if (windowDays < 1 || windowDays > 400) {
            throw new IllegalArgumentException("windowDays out of range");
        }
        long startEpoch = scope.to().getEpochSecond() - (long) windowDays * 86_400L;
        BaselineSignal signal = BaselineSignal.of(metric).orElse(null);
        String combine = signal != null && signal.maxOfSeries() ? "max(v)" : "avg(v)";
        String value = signal != null && signal.invert() ? "1 - " + combine : combine;
        Q q = start("SELECT instance_id, t, " + value + " AS v FROM ("
                + "SELECT instance_id, toUnixTimestamp(ts) AS t, labels_key, avgMerge(avg_state) AS v FROM xp_metrics_1h", scope);
        q.add(" AND metric = ?", metric);
        q.add(" AND ts >= toDateTime(?, 'UTC')", startEpoch);
        labelFilters(q, signal == null ? List.of() : signal.filters());
        q.add(" GROUP BY instance_id, ts, labels_key) GROUP BY instance_id, t ORDER BY instance_id, t");
        return q.build(scope);
    }

    /**
     * Latest value of the two process metrics for one instance at (or nearest before) {@code atEpochSec}, keyed by
     * the process identity labels. Reads raw {@code xp_metric_points} (process metrics live only at collection
     * level T2) and keeps, per {@code (metric, pid)}, the most recent point that is not after the timestamp.
     *
     * <p>hostmetrics reports {@code process.cpu.utilization} as one series per CPU state (user, system, wait), all
     * stamped with the scrape's time, and each a fraction of the whole host. The states of one scrape are added up
     * first, then the latest scrape wins; picking one series' latest value would show a single state (often "wait",
     * near zero) for a process that is busy.
     *
     * <p>Tenant predicate first; {@code instanceId} and the timestamp are bound parameters. The process identity
     * labels are read out of the {@code labels} map by constant key names, never from user text. Returns one row
     * per {@code (metric, pid)} with columns {@code metric, pid, name, user, threads, v}.
     */
    public ScopedSql processLatest(QueryScope scope, String instanceId, long atEpochSec) {
        Q q = start("SELECT metric, pid, argMax(name, t) AS name, argMax(user, t) AS user, '' AS threads, argMax(v, t) AS v FROM ("
                + "SELECT metric, labels['process.pid'] AS pid, ts AS t, any(labels['process.executable.name']) AS name, "
                + "any(labels['process.owner']) AS user, sum(value) AS v FROM xp_metric_points", scope);
        q.add(" AND instance_id = ?", instanceId);
        q.add(" AND metric IN ('process.cpu.utilization', 'process.memory.utilization')");
        q.add(" AND ts <= fromUnixTimestamp64Milli(?)", atEpochSec * 1000L);
        q.add(" AND ts >= fromUnixTimestamp64Milli(?)", (atEpochSec - PROCESS_LOOKBACK_SEC) * 1000L);
        q.add(" AND labels['process.pid'] != ''");
        q.add(" GROUP BY metric, pid, t) GROUP BY metric, pid ORDER BY v DESC");
        return q.build(scope);
    }

    /** How far back {@link #processLatest} looks for the nearest point before the timestamp. */
    static final long PROCESS_LOOKBACK_SEC = 3600L;

    /**
     * Stored CloudWatch datapoints for one instance (fetched earlier by a Refresh). FINAL collapses overlapping
     * refreshes of the same window to one point per (metric, dimension, ts).
     */
    public ScopedSql cloudWatchPoints(QueryScope scope, String instanceId) {
        Q q = start("SELECT metric, dimension, toUnixTimestamp64Milli(ts) AS t_ms, value, "
                + "toUnixTimestamp64Milli(fetched_at) AS fetched_ms FROM xp_cloudwatch_points FINAL", scope);
        q.add(" AND instance_id = ?", instanceId);
        q.add(" AND ts >= fromUnixTimestamp64Milli(?) AND ts < fromUnixTimestamp64Milli(?)",
                scope.from().toEpochMilli(), scope.to().toEpochMilli());
        q.add(" ORDER BY metric, dimension, ts LIMIT 200000");
        return q.build(scope);
    }

    // ------------------------------------------------- events, usage, health

    public ScopedSql events(QueryScope scope, Collection<String> kinds, int limit) {
        Q q = start("SELECT toUnixTimestamp64Milli(ts) AS ts_ms, kind, resource, title, detail FROM xp_events", scope);
        q.add(" AND ts >= fromUnixTimestamp64Milli(?) AND ts < fromUnixTimestamp64Milli(?)",
                scope.from().toEpochMilli(), scope.to().toEpochMilli());
        inList(q, "kind", new ArrayList<>(kinds));
        q.add(" ORDER BY ts DESC LIMIT ?", Math.max(1, Math.min(limit, 500)));
        return q.build(scope);
    }

    public ScopedSql usageByDay(QueryScope scope) {
        Q q = start("SELECT toString(toDate(minute)) AS d, sum(bytes) AS b, sum(rows) AS r FROM xp_log_volume_1m", scope);
        q.add(" AND minute >= toDateTime(?, 'UTC') AND minute < toDateTime(?, 'UTC')",
                scope.from().getEpochSecond(), scope.to().getEpochSecond());
        q.add(" GROUP BY d ORDER BY d ASC");
        return q.build(scope);
    }

    /**
     * Per-day, per-source ingested bytes and row counts from the minute rollup. Backs the usage metering writer
     * ({@code XpUsageRollupJob}), which upserts one {@code xp_usage_daily} row per {@code (day, source)}. Tenant
     * predicate first; the window bounds are bound parameters.
     */
    public ScopedSql usageDailyBySource(QueryScope scope) {
        Q q = start("SELECT toString(toDate(minute)) AS d, service AS s, sum(bytes) AS b, sum(rows) AS r "
                + "FROM xp_log_volume_1m", scope);
        q.add(" AND minute >= toDateTime(?, 'UTC') AND minute < toDateTime(?, 'UTC')",
                scope.from().getEpochSecond(), scope.to().getEpochSecond());
        q.add(" GROUP BY d, s");
        return q.build(scope);
    }

    public ScopedSql usageBySource(QueryScope scope) {
        Q q = start("SELECT service AS s, sum(bytes) AS b FROM xp_log_volume_1m", scope);
        q.add(" AND minute >= toDateTime(?, 'UTC') AND minute < toDateTime(?, 'UTC')",
                scope.from().getEpochSecond(), scope.to().getEpochSecond());
        q.add(" GROUP BY s ORDER BY b DESC LIMIT 20");
        return q.build(scope);
    }

    /** Error rows and total bytes per instance in the window, from the minute rollup. */
    /** ERROR and FATAL lines per container in the window (container logs carry the full 64-character container id). */
    public ScopedSql containerErrors(QueryScope scope) {
        Q q = start("SELECT instance_id, container_id, count() AS errors FROM xp_logs", scope);
        q.add(" AND ts >= fromUnixTimestamp64Milli(?) AND ts < fromUnixTimestamp64Milli(?)",
                scope.from().toEpochMilli(), scope.to().toEpochMilli());
        q.add(" AND container_id != '' AND severity IN ('ERROR', 'FATAL')");
        inList(q, "instance_id", scope.instanceIds());
        q.add(" GROUP BY instance_id, container_id");
        return q.build(scope);
    }

    public ScopedSql errorVolume(QueryScope scope) {
        Q q = start("SELECT instance_id, sumIf(rows, severity IN ('ERROR', 'FATAL')) AS errors, sum(rows) AS total, "
                + "sum(bytes) AS b FROM xp_log_volume_1m", scope);
        q.add(" AND minute >= toDateTime(?, 'UTC') AND minute < toDateTime(?, 'UTC')",
                scope.from().getEpochSecond(), scope.to().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        q.add(" GROUP BY instance_id");
        return q.build(scope);
    }

    public ScopedSql lastDataAt(QueryScope scope) {
        Q q = start("SELECT toUnixTimestamp(max(ts)) AS last_ts FROM xp_metrics_1m", scope);
        q.add(" AND ts >= toDateTime(?, 'UTC')", scope.from().getEpochSecond());
        return q.build(scope);
    }

    // ------------------------------------------------------------ alerting

    /** Aggregate of one metric over the scope window, grouped by instance; used by METRIC_THRESHOLD rules. */
    public ScopedSql alertMetric(QueryScope scope, MetricSpec spec) {
        if ("rate".equals(spec.agg())) {
            return alertCounterRate(scope, spec);
        }
        Q q = start("SELECT instance_id AS g0, " + aggExpr(spec.agg()) + " AS v FROM xp_metrics_1m", scope);
        q.add(" AND metric = ?", spec.metric());
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", scope.from().getEpochSecond(), scope.to().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        labelFilters(q, spec.filters());
        q.add(" GROUP BY g0");
        return q.build(scope);
    }

    /**
     * Average per-second rate of a counter over the rule window, per instance: for each series the summed increase
     * divided by the summed time it covers, then added up across the instance's series. An interval in which the
     * counter restarted is left out of both sums (its increase is unknown, see {@link #counterRateSeries}). Dividing
     * by the covered time, not the nominal window, keeps a window that only partly has data from reading low.
     */
    private ScopedSql alertCounterRate(QueryScope scope, MetricSpec spec) {
        long firstBucket = Math.floorDiv(scope.from().getEpochSecond(), 60L) * 60L;
        Q q = start("SELECT instance_id AS g0, sum(series_rate) AS v FROM ("
                + "SELECT instance_id, labels_key, sum(lv - prev_lv) / sum(toInt64(t) - toInt64(prev_t)) AS series_rate FROM ("
                + PREVIOUS_BUCKET
                + "SELECT toUnixTimestamp(toStartOfMinute(ts)) AS t, instance_id, labels_key, argMaxMerge(last_state) AS lv "
                + "FROM xp_metrics_1m", scope);
        q.add(" AND metric = ?", spec.metric());
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", firstBucket - 60L, scope.to().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        labelFilters(q, spec.filters());
        q.add(" GROUP BY t, instance_id, labels_key)" + SERIES_WINDOW + ") WHERE prev_t > 0 AND lv >= prev_lv AND t >= ?"
                + " GROUP BY instance_id, labels_key) GROUP BY g0", firstBucket);
        return q.build(scope);
    }

    // ------------------------------------------------------------ alert backtest
    //
    // Replays the live evaluator once a minute over a past range. The evaluator at `now` reads the minute buckets in
    // [now - W, now); replayed at each complete minute m (now = m + 60) that is the buckets m - W + 60 .. m. So each
    // bucket b is fanned out to the evaluation minutes b .. b + W - 60 (ARRAY JOIN range(b, b + W, 60)) and merged
    // there with the same aggregate states the evaluator merges: the value at each minute is the one the rule would
    // have compared with its threshold. Output: g0 (instance), t (evaluation minute m), v.

    private static void backtestArgs(long fromMin, long lastMin, long windowSec) {
        if (windowSec < 60 || windowSec % 60 != 0 || windowSec > 6 * 3600 || fromMin % 60 != 0 || lastMin % 60 != 0 || lastMin < fromMin) {
            throw new IllegalArgumentException("Bad backtest range");
        }
    }

    private static final String FANOUT = " ARRAY JOIN range(toUInt64(b), toUInt64(b) + toUInt64(?), toUInt64(60)) AS m"
            + " WHERE m >= toUInt64(?) AND m <= toUInt64(?)";

    /** Window aggregate (avg, min, max, sum, p95) of a gauge per instance at every evaluation minute. */
    public ScopedSql backtestMetric(QueryScope scope, MetricSpec spec, long fromMin, long lastMin, long windowSec, int limit) {
        backtestArgs(fromMin, lastMin, windowSec);
        String agg = spec.agg().toLowerCase(Locale.ROOT);
        String[] fn = switch (agg) {
            case "avg" -> new String[] {"avgMergeState(avg_state)", "avgMerge(st)"};
            case "min" -> new String[] {"minMergeState(min_state)", "minMerge(st)"};
            case "max" -> new String[] {"maxMergeState(max_state)", "maxMerge(st)"};
            case "sum" -> new String[] {"sumMergeState(sum_state)", "sumMerge(st)"};
            case "p95" -> new String[] {"quantilesTDigestMergeState(0.5, 0.95, 0.99)(q_state)", "quantilesTDigestMerge(0.5, 0.95, 0.99)(st)[2]"};
            default -> throw new IllegalArgumentException("Unsupported aggregation");
        };
        Q q = start("SELECT g0, toUInt64(m) AS t, " + fn[1] + " AS v FROM (SELECT instance_id AS g0, toUnixTimestamp(ts) AS b, "
                + fn[0] + " AS st FROM xp_metrics_1m", scope);
        q.add(" AND metric = ?", spec.metric());
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", fromMin - windowSec + 60, lastMin + 60);
        inList(q, "instance_id", scope.instanceIds());
        labelFilters(q, spec.filters());
        q.add(" GROUP BY g0, b)" + FANOUT, windowSec, fromMin, lastMin);
        q.add(" GROUP BY g0, t ORDER BY g0, t LIMIT ?", clampBacktest(limit));
        return q.build(scope);
    }

    /**
     * Counter rate per instance at every evaluation minute, exactly as {@link #alertCounterRate}: per series the summed
     * increase over the summed seconds of the steps inside the window, a step counted only when its predecessor is
     * inside what the evaluator reads (from one bucket before the window) and the counter did not go down.
     */
    public ScopedSql backtestCounterRate(QueryScope scope, MetricSpec spec, long fromMin, long lastMin, long windowSec, int limit) {
        backtestArgs(fromMin, lastMin, windowSec);
        Q q = start("SELECT g0, t, sum(series_rate) AS v FROM ("
                + "SELECT instance_id AS g0, labels_key, toUInt64(m) AS t, sum(inc) / sum(secs) AS series_rate FROM ("
                + "SELECT instance_id, labels_key, t AS b, lv - prev_lv AS inc, toInt64(t) - toInt64(prev_t) AS secs, prev_t FROM ("
                + PREVIOUS_BUCKET
                + "SELECT toUnixTimestamp(toStartOfMinute(ts)) AS t, instance_id, labels_key, argMaxMerge(last_state) AS lv "
                + "FROM xp_metrics_1m", scope);
        q.add(" AND metric = ?", spec.metric());
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", fromMin - windowSec, lastMin + 60);
        inList(q, "instance_id", scope.instanceIds());
        labelFilters(q, spec.filters());
        q.add(" GROUP BY t, instance_id, labels_key)" + SERIES_WINDOW + ") WHERE prev_t > 0 AND lv >= prev_lv)" + FANOUT,
                windowSec, fromMin, lastMin);
        q.add(" AND toInt64(prev_t) >= toInt64(m) - ?", windowSec);
        q.add(" GROUP BY g0, labels_key, t) GROUP BY g0, t ORDER BY g0, t LIMIT ?", clampBacktest(limit));
        return q.build(scope);
    }

    /** Matching log lines per instance in the window at every evaluation minute (minutes with none are absent). */
    public ScopedSql backtestLogCount(QueryScope scope, FilterAst filter, long fromMin, long lastMin, long windowSec, int limit) {
        backtestArgs(fromMin, lastMin, windowSec);
        QueryScope read = new QueryScope(scope.accountIds(), Instant.ofEpochSecond(fromMin - windowSec + 60), Instant.ofEpochSecond(lastMin + 60),
                scope.regions(), scope.instanceIds(), scope.services(), scope.containers());
        Q q = start("SELECT g0, toUInt64(m) AS t, sum(c) AS v FROM (SELECT instance_id AS g0, "
                + "toUnixTimestamp(toStartOfMinute(ts)) AS b, count() AS c FROM xp_logs", read);
        logWhere(q, read, filter);
        q.add(" GROUP BY g0, b)" + FANOUT, windowSec, fromMin, lastMin);
        q.add(" GROUP BY g0, t ORDER BY g0, t LIMIT ?", clampBacktest(limit));
        return q.build(read);
    }

    /** Minutes in which each instance delivered any metric, for replaying HEARTBEAT_MISSING: {@code g0, t}. */
    public ScopedSql backtestPresence(QueryScope scope, long fromSec, long toSec, int limit) {
        Q q = start("SELECT instance_id AS g0, toUnixTimestamp(ts) AS t FROM xp_metrics_1m", scope);
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", fromSec, toSec);
        inList(q, "instance_id", scope.instanceIds());
        q.add(" GROUP BY g0, t ORDER BY g0, t LIMIT ?", clampBacktest(limit));
        return q.build(scope);
    }

    private static int clampBacktest(int limit) {
        return Math.max(1, Math.min(limit, 300_000));
    }

    /** Matching log rows per instance; used by LOG_COUNT rules. */
    public ScopedSql alertLogCount(QueryScope scope, FilterAst filter) {
        Q q = start("SELECT instance_id AS g0, count() AS v FROM xp_logs", scope);
        logWhere(q, scope, filter);
        q.add(" GROUP BY g0");
        return q.build(scope);
    }

    /**
     * Last minute in which each instance delivered any metric; used by HEARTBEAT_MISSING rules. The agent's own
     * heartbeat goes to the control plane over HTTP and is never a metric (no {@code xp.agent.heartbeat} series
     * exists), so "data stopped arriving" is the signal: it also catches a collector that died while the agent
     * process kept heartbeating.
     */
    public ScopedSql lastHeartbeat(QueryScope scope) {
        Q q = start("SELECT instance_id AS g0, toUnixTimestamp(max(ts)) AS v FROM xp_metrics_1m", scope);
        q.add(" AND ts >= toDateTime(?, 'UTC')", scope.from().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        q.add(" GROUP BY g0");
        return q.build(scope);
    }

    /**
     * Measured increase of a counter over the scope window, per value of one label (or per instance), with the
     * seconds it covers: {@code g0, inc, secs}. Built like {@link #alertCounterRate}: per series, minute by minute,
     * restart intervals left out of both columns, so {@code inc / secs} is an exact average rate and {@code inc} an
     * exact count (packets dropped by an ENA allowance, seconds stalled under pressure, throttled CPU periods).
     */
    public ScopedSql counterIncrease(QueryScope scope, String metric, List<MetricSpec.LabelFilter> filters, String groupKey) {
        if (!MetricSpec.METRIC_NAME.matcher(metric).matches()) {
            throw new IllegalArgumentException("Invalid metric name");
        }
        long firstBucket = Math.floorDiv(scope.from().getEpochSecond(), 60L) * 60L;
        Q q = start("SELECT g0, sum(lv - prev_lv) AS inc, sum(toInt64(t) - toInt64(prev_t)) AS secs FROM ("
                + "SELECT " + groupExpr(groupKey) + " AS g0, t, lv, prev_lv, prev_t FROM ("
                + PREVIOUS_BUCKET
                + "SELECT toUnixTimestamp(toStartOfMinute(ts)) AS t, instance_id, labels_key, argMaxMerge(last_state) AS lv "
                + "FROM xp_metrics_1m", scope);
        q.add(" AND metric = ?", metric);
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", firstBucket - 60L, scope.to().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        labelFilters(q, filters);
        q.add(" GROUP BY t, instance_id, labels_key)" + SERIES_WINDOW + ") WHERE prev_t > 0 AND lv >= prev_lv AND t >= ?)"
                + " GROUP BY g0", firstBucket);
        return q.build(scope);
    }

    /**
     * First and last minute each (instance, label set) of a metric was seen in the scope window:
     * {@code instance_id, labels_key, first_ts, last_ts}. Used to see a container's id or image change (a deploy).
     */
    public ScopedSql seriesSpans(QueryScope scope, String metric, List<MetricSpec.LabelFilter> filters) {
        if (!MetricSpec.METRIC_NAME.matcher(metric).matches()) {
            throw new IllegalArgumentException("Invalid metric name");
        }
        Q q = start("SELECT instance_id, labels_key, toUnixTimestamp(min(ts)) AS first_ts, toUnixTimestamp(max(ts)) AS last_ts "
                + "FROM xp_metrics_1m", scope);
        q.add(" AND metric = ?", metric);
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", scope.from().getEpochSecond(), scope.to().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        labelFilters(q, filters);
        q.add(" GROUP BY instance_id, labels_key ORDER BY first_ts ASC LIMIT 5000");
        return q.build(scope);
    }

    /**
     * The minutes in which a counter series did something: rose ({@code inc > 0}), dropped (a restart of whatever
     * counts it: a reboot for kernel counters, a new container for Docker's), or resumed after more than
     * {@code gapSec} without data: {@code instance_id, labels_key, t, inc, restarted, gap_secs}. Quiet minutes are
     * not returned.
     */
    public ScopedSql counterSteps(QueryScope scope, String metric, List<MetricSpec.LabelFilter> filters, long gapSec) {
        if (!MetricSpec.METRIC_NAME.matcher(metric).matches()) {
            throw new IllegalArgumentException("Invalid metric name");
        }
        long firstBucket = Math.floorDiv(scope.from().getEpochSecond(), 60L) * 60L;
        Q q = start("SELECT instance_id, labels_key, t, if(lv >= prev_lv, lv - prev_lv, 0) AS inc, lv < prev_lv AS restarted, "
                + "toInt64(t) - toInt64(prev_t) AS gap_secs FROM ("
                + PREVIOUS_BUCKET
                + "SELECT toUnixTimestamp(toStartOfMinute(ts)) AS t, instance_id, labels_key, argMaxMerge(last_state) AS lv "
                + "FROM xp_metrics_1m", scope);
        q.add(" AND metric = ?", metric);
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", firstBucket - 60L, scope.to().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        labelFilters(q, filters);
        q.add(" GROUP BY t, instance_id, labels_key)" + SERIES_WINDOW + ") WHERE prev_t > 0 AND t >= ?"
                + " AND (lv != prev_lv OR toInt64(t) - toInt64(prev_t) > ?) ORDER BY t ASC LIMIT 5000", firstBucket, gapSec);
        return q.build(scope);
    }

    /** Error and fatal log lines per minute for one instance: {@code t, v}. Minutes without log rows are absent. */
    public ScopedSql errorsPerMinute(QueryScope scope, String instanceId) {
        Q q = start("SELECT toUnixTimestamp(minute) AS t, sumIf(rows, severity IN ('ERROR', 'FATAL')) AS v FROM xp_log_volume_1m", scope);
        q.add(" AND instance_id = ?", instanceId);
        q.add(" AND minute >= toDateTime(?, 'UTC') AND minute < toDateTime(?, 'UTC')", scope.from().getEpochSecond(), scope.to().getEpochSecond());
        q.add(" GROUP BY t ORDER BY t ASC");
        return q.build(scope);
    }

    /** Log rows landed per region in the scope's time range; used by the setup checklist ("first logs"). */
    public ScopedSql landedLogRegions(QueryScope scope) {
        Q q = start("SELECT region AS g0, count() AS v FROM xp_logs", scope);
        q.add(" AND ts >= fromUnixTimestamp64Milli(?) AND ts < fromUnixTimestamp64Milli(?)",
                scope.from().toEpochMilli(), scope.to().toEpochMilli());
        q.add(" GROUP BY g0");
        return q.build(scope);
    }

    /** Metric points landed per instance in the scope's time range ("first metrics"; points carry no region). */
    public ScopedSql landedMetricInstances(QueryScope scope) {
        Q q = start("SELECT instance_id AS g0, count() AS v FROM xp_metric_points", scope);
        q.add(" AND ts >= fromUnixTimestamp64Milli(?) AND ts < fromUnixTimestamp64Milli(?)",
                scope.from().toEpochMilli(), scope.to().toEpochMilli());
        q.add(" AND metric != 'xp.agent.heartbeat' GROUP BY g0");
        return q.build(scope);
    }

    // ------------------------------------------------------------ internals

    private static Q start(String selectFrom, QueryScope scope) {
        List<Long> ids = scope.accountIds();
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            ph.append(i == 0 ? "?" : ", ?");
        }
        return new Q(selectFrom + " WHERE " + ScopedSql.TENANT_MARKER + ph + ")", new ArrayList<>(ids), ids);
    }

    private static void logWhere(Q q, QueryScope scope, FilterAst filter) {
        q.add(" AND ts >= fromUnixTimestamp64Milli(?) AND ts < fromUnixTimestamp64Milli(?)",
                scope.from().toEpochMilli(), scope.to().toEpochMilli());
        inList(q, "region", scope.regions());
        inList(q, "instance_id", scope.instanceIds());
        inList(q, "service", scope.services());
        inList(q, "container_id", scope.containers());
        if (filter != null && !(filter instanceof FilterAst.MatchAll)) {
            q.add(" AND ");
            compile(q, filter);
        }
    }

    private static void inList(Q q, String column, Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (values.size() > 500) {
            throw XamPulseException.invalid("Too many values for " + column + ".");
        }
        StringBuilder ph = new StringBuilder();
        List<Object> ps = new ArrayList<>();
        for (String v : values) {
            ph.append(ps.isEmpty() ? "?" : ", ?");
            ps.add(v);
        }
        q.add(" AND " + column + " IN (" + ph + ")", ps.toArray());
    }

    private static void hashIn(Q q, Collection<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            throw new IllegalArgumentException("hashes required");
        }
        StringBuilder ph = new StringBuilder();
        List<Object> ps = new ArrayList<>();
        for (String h : hashes) {
            requireHash(h);
            ph.append(ps.isEmpty() ? "toUInt64(?)" : ", toUInt64(?)");
            ps.add(h);
        }
        q.add(" AND tpl_hash IN (" + ph + ")", ps.toArray());
    }

    private static void requireHash(String h) {
        if (h == null || !DIGITS.matcher(h).matches()) {
            throw XamPulseException.invalid("Invalid log reference.");
        }
    }

    private static void compile(Q q, FilterAst node) {
        switch (node) {
            case FilterAst.MatchAll m -> q.add("1");
            case FilterAst.Text t -> q.add("positionCaseInsensitive(body, ?) > 0", t.value());
            case FilterAst.Not n -> {
                q.add("NOT (");
                compile(q, n.child());
                q.add(")");
            }
            case FilterAst.And a -> join(q, a.children(), " AND ");
            case FilterAst.Or o -> join(q, o.children(), " OR ");
            case FilterAst.Field f -> compileField(q, f);
        }
    }

    private static void join(Q q, List<FilterAst> children, String op) {
        q.add("(");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                q.add(op);
            }
            compile(q, children.get(i));
        }
        q.add(")");
    }

    private static void compileField(Q q, FilterAst.Field f) {
        if (f.field() == LogField.SEVERITY) {
            if (!"=".equals(f.cmp())) {
                int level = XpSeverity.valueOf(f.values().get(0)).ordinal() + 1;
                q.add("toUInt8(severity) " + f.cmp() + " ?", level);
                return;
            }
            q.add("(");
            for (int i = 0; i < f.values().size(); i++) {
                q.add((i > 0 ? " OR " : "") + "severity = ?", f.values().get(i));
            }
            q.add(")");
            return;
        }
        q.add("(");
        for (int i = 0; i < f.values().size(); i++) {
            String v = f.values().get(i);
            String sep = i > 0 ? " OR " : "";
            if (f.field() == LogField.ATTR) {
                if (v.contains("*")) {
                    q.add(sep + "attrs[?] LIKE ?", f.attrKey(), likePattern(v));
                } else {
                    q.add(sep + "attrs[?] = ?", f.attrKey(), v);
                }
            } else if (v.contains("*")) {
                q.add(sep + f.field().column() + " LIKE ?", likePattern(v));
            } else {
                q.add(sep + f.field().column() + " = ?", v);
            }
        }
        q.add(")");
    }

    /** Escapes LIKE metacharacters, then turns the DSL wildcard {@code *} into {@code %}. */
    static String likePattern(String v) {
        return v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("*", "%");
    }

    private static String escapeRegex(String s) {
        return s.replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
    }

    private static String groupExpr(String g) {
        if ("instance_id".equals(g)) {
            return "instance_id";
        }
        if (!SAFE_KEY.matcher(g).matches()) {
            throw XamPulseException.invalid("Invalid group-by key.");
        }
        return "substring(arrayFirst(x -> startsWith(x, '" + g + "='), splitByChar(',', labels_key)), "
                + (g.length() + 2) + ")";
    }

    /** Aggregations that merge rollup states directly. "rate" is not one: it needs per-series ordering (see above). */
    private static String aggExpr(String agg) {
        return switch (agg.toLowerCase(Locale.ROOT)) {
            case "avg" -> "avgMerge(avg_state)";
            case "min" -> "minMerge(min_state)";
            case "max" -> "maxMerge(max_state)";
            case "sum" -> "sumMerge(sum_state)";
            case "p50" -> "quantilesTDigestMerge(0.5, 0.95, 0.99)(q_state)[1]";
            case "p95" -> "quantilesTDigestMerge(0.5, 0.95, 0.99)(q_state)[2]";
            case "p99" -> "quantilesTDigestMerge(0.5, 0.95, 0.99)(q_state)[3]";
            case "last" -> "argMaxMerge(last_state)";
            default -> throw new IllegalArgumentException("Unsupported aggregation");
        };
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 1000));
    }

    /** Statement accumulator. Every placeholder is added together with its value, in order. */
    private static final class Q {
        private final StringBuilder sql;
        private final List<Object> params;
        private final List<Long> tenants;

        Q(String head, List<Object> initialParams, long tenant) {
            this(head, initialParams, List.of(tenant));
        }

        Q(String head, List<?> initialParams, List<Long> tenants) {
            this.sql = new StringBuilder(head);
            this.params = new ArrayList<>(initialParams);
            this.tenants = tenants;
        }

        void add(String text, Object... ps) {
            sql.append(text);
            for (Object p : ps) {
                params.add(p);
            }
        }

        ScopedSql build(QueryScope scope) {
            return new ScopedSql(sql.toString(), params, scope.accountIds());
        }

        ScopedSql buildFor(List<Long> ids) {
            return new ScopedSql(sql.toString(), params, ids);
        }
    }
}
