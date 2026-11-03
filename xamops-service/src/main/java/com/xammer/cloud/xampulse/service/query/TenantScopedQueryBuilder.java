package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.dto.XpSeverity;
import com.xammer.cloud.xampulse.exception.XamPulseException;

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
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,20}$");

    static final String LOG_COLUMNS = "tenant_id, toUnixTimestamp64Milli(ts) AS ts_ms, region, instance_id, service, source, "
            + "toString(severity) AS severity, body, attrs, trace_id, container_id, "
            + "toString(cityHash64(body)) AS body_hash, toUnixTimestamp64Milli(ingest_ts) AS ingest_ms";

    /** Pattern template used for display. '#' (not '?') so the text never contains a placeholder character. */
    private static final String TEMPLATE_EXPR = "substring(replaceRegexpAll(body, '[0-9a-fA-F-]{8,}|[0-9]+', '#'), 1, 300)";

    /** Rollup table choice for a metric time range. */
    public record MetricPlan(String table, int tableStepSec, int stepSec) {}

    public record LogCursor(long tsMs, String bodyHash) {
        public LogCursor {
            if (bodyHash == null || !DIGITS.matcher(bodyHash).matches()) {
                throw XamPulseException.invalid("Invalid cursor.");
            }
        }
    }

    // ---------------------------------------------------------------- logs

    public ScopedSql search(QueryScope scope, FilterAst filter, boolean desc, int limit, LogCursor cursor) {
        Q q = start("SELECT " + LOG_COLUMNS + " FROM xp_logs", scope);
        logWhere(q, scope, filter);
        if (cursor != null) {
            if (desc) {
                q.add(" AND (ts < fromUnixTimestamp64Milli(?) OR (ts = fromUnixTimestamp64Milli(?) AND cityHash64(body) < toUInt64(?)))",
                        cursor.tsMs(), cursor.tsMs(), cursor.bodyHash());
            } else {
                q.add(" AND (ts > fromUnixTimestamp64Milli(?) OR (ts = fromUnixTimestamp64Milli(?) AND cityHash64(body) > toUInt64(?)))",
                        cursor.tsMs(), cursor.tsMs(), cursor.bodyHash());
            }
        }
        q.add(desc ? " ORDER BY ts DESC, cityHash64(body) DESC" : " ORDER BY ts ASC, cityHash64(body) ASC");
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
        StringBuilder sel = new StringBuilder("SELECT toUnixTimestamp(toStartOfInterval(ts, INTERVAL " + plan.stepSec()
                + " SECOND)) AS t");
        StringBuilder grp = new StringBuilder("t");
        int i = 0;
        for (String g : spec.groupBy()) {
            sel.append(", ").append(groupExpr(g)).append(" AS g").append(i);
            grp.append(", g").append(i);
            i++;
        }
        sel.append(", ").append(aggExpr(spec.agg(), plan.stepSec())).append(" AS v FROM ").append(plan.table());
        Q q = start(sel.toString(), scope);
        q.add(" AND metric = ?", spec.metric());
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", scope.from().getEpochSecond(), scope.to().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        for (MetricSpec.LabelFilter f : spec.filters()) {
            switch (f.op()) {
                case "=" -> q.add(" AND has(splitByChar(',', labels_key), ?)", f.key() + "=" + f.value());
                case "!=" -> q.add(" AND NOT has(splitByChar(',', labels_key), ?)", f.key() + "=" + f.value());
                default -> q.add(" AND arrayExists(x -> match(x, ?), splitByChar(',', labels_key))",
                        "^" + escapeRegex(f.key()) + "=(?:" + f.value() + ")$");
            }
        }
        q.add(" GROUP BY " + grp + " ORDER BY t ASC");
        return q.build(scope);
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
     * recompute median/MAD per hour-of-week. Tenant predicate first; {@code metric} and the window start are bound.
     */
    public ScopedSql baselineSamples(QueryScope scope, String metric, int windowDays) {
        if (windowDays < 1 || windowDays > 400) {
            throw new IllegalArgumentException("windowDays out of range");
        }
        long startEpoch = scope.to().getEpochSecond() - (long) windowDays * 86_400L;
        Q q = start("SELECT instance_id, toUnixTimestamp(ts) AS t, avgMerge(avg_state) AS v FROM xp_metrics_1h", scope);
        q.add(" AND metric = ?", metric);
        q.add(" AND ts >= toDateTime(?, 'UTC')", startEpoch);
        q.add(" GROUP BY instance_id, ts ORDER BY instance_id, t");
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

    public ScopedSql usageBySource(QueryScope scope) {
        Q q = start("SELECT service AS s, sum(bytes) AS b FROM xp_log_volume_1m", scope);
        q.add(" AND minute >= toDateTime(?, 'UTC') AND minute < toDateTime(?, 'UTC')",
                scope.from().getEpochSecond(), scope.to().getEpochSecond());
        q.add(" GROUP BY s ORDER BY b DESC LIMIT 20");
        return q.build(scope);
    }

    /** Error rows and total bytes per instance in the window, from the minute rollup. */
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
        MetricSpec grouped = new MetricSpec(spec.metric(), spec.agg(), List.of("instance_id"), spec.filters());
        Q q = start("SELECT instance_id AS g0, " + aggExpr(grouped.agg(), (int) Math.max(1, scope.rangeSeconds()))
                + " AS v FROM xp_metrics_1m", scope);
        q.add(" AND metric = ?", grouped.metric());
        q.add(" AND ts >= toDateTime(?, 'UTC') AND ts < toDateTime(?, 'UTC')", scope.from().getEpochSecond(), scope.to().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        for (MetricSpec.LabelFilter f : grouped.filters()) {
            switch (f.op()) {
                case "=" -> q.add(" AND has(splitByChar(',', labels_key), ?)", f.key() + "=" + f.value());
                case "!=" -> q.add(" AND NOT has(splitByChar(',', labels_key), ?)", f.key() + "=" + f.value());
                default -> q.add(" AND arrayExists(x -> match(x, ?), splitByChar(',', labels_key))",
                        "^" + escapeRegex(f.key()) + "=(?:" + f.value() + ")$");
            }
        }
        q.add(" GROUP BY g0");
        return q.build(scope);
    }

    /** Matching log rows per instance; used by LOG_COUNT rules. */
    public ScopedSql alertLogCount(QueryScope scope, FilterAst filter) {
        Q q = start("SELECT instance_id AS g0, count() AS v FROM xp_logs", scope);
        logWhere(q, scope, filter);
        q.add(" GROUP BY g0");
        return q.build(scope);
    }

    /** Last time each instance reported a heartbeat metric; used by HEARTBEAT_MISSING rules. */
    public ScopedSql lastHeartbeat(QueryScope scope) {
        Q q = start("SELECT instance_id AS g0, toUnixTimestamp(max(ts)) AS v FROM xp_metrics_1m", scope);
        q.add(" AND metric = 'xp.agent.heartbeat' AND ts >= toDateTime(?, 'UTC')", scope.from().getEpochSecond());
        inList(q, "instance_id", scope.instanceIds());
        q.add(" GROUP BY g0");
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

    private static String aggExpr(String agg, int stepSec) {
        return switch (agg.toLowerCase(Locale.ROOT)) {
            case "avg" -> "avgMerge(avg_state)";
            case "min" -> "minMerge(min_state)";
            case "max" -> "maxMerge(max_state)";
            case "sum" -> "sumMerge(sum_state)";
            case "p50" -> "quantilesTDigestMerge(0.5, 0.95, 0.99)(q_state)[1]";
            case "p95" -> "quantilesTDigestMerge(0.5, 0.95, 0.99)(q_state)[2]";
            case "p99" -> "quantilesTDigestMerge(0.5, 0.95, 0.99)(q_state)[3]";
            case "rate" -> "(maxMerge(max_state) - minMerge(min_state)) / " + Math.max(1, stepSec);
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
