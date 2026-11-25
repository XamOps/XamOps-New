package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.xampulse.dto.XpAlertRule;
import com.xammer.cloud.xampulse.dto.XpCondition;
import com.xammer.cloud.xampulse.dto.XpMetricFilter;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.query.FilterAst;
import com.xammer.cloud.xampulse.service.query.FilterDslParser;
import com.xammer.cloud.xampulse.service.query.MetricSpec;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.ScopedSql;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates an alert rule and compiles its condition to a tenant-scoped statement via the query builder. */
@Service("xpRuleCompiler")
public class RuleCompiler {

    static final Set<String> TYPES = Set.of("METRIC_THRESHOLD", "LOG_COUNT", "HEARTBEAT_MISSING", "METRIC_ANOMALY", "FORECAST");
    static final Set<String> SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    static final Set<String> OPS = Set.of(">", ">=", "<", "<=");
    static final Set<String> THRESHOLD_AGGS = Set.of("avg", "min", "max", "sum", "p95");
    private static final Pattern INSTANCE = Pattern.compile("^[A-Za-z0-9._\\-]{1,64}$");
    public static final String DEFAULT_FORECAST_METRIC = "system.filesystem.utilization";

    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public record Compiled(Long accountId, Duration window, Duration hold, QueryScope scope, ScopedSql sql, String type) {}

    /** @throws XamPulseException XP_INVALID_REQUEST / XP_BAD_FILTER describing the first problem found */
    public void validate(XpAlertRule r) {
        if (r.name() == null || r.name().isBlank() || r.name().length() > 200) {
            throw XamPulseException.invalid("Give the rule a name (up to 200 characters).");
        }
        if (r.severity() == null || !SEVERITIES.contains(r.severity())) {
            throw XamPulseException.invalid("Unknown severity.");
        }
        if (r.scope() == null || r.scope().accountIds() == null || r.scope().accountIds().size() != 1) {
            throw XamPulseException.invalid("A rule applies to exactly one account.");
        }
        if (r.scope().tag() != null && !r.scope().tag().isBlank()) {
            throw XamPulseException.invalid("Tag-based scope is not available yet. Select instances instead.");
        }
        if (r.scope().instanceIds() != null) {
            if (r.scope().instanceIds().size() > 500) {
                throw XamPulseException.invalid("Too many instances in scope.");
            }
            for (String i : r.scope().instanceIds()) {
                if (i == null || !INSTANCE.matcher(i).matches()) {
                    throw XamPulseException.invalid("An instance id is not valid.");
                }
            }
        }
        XpCondition c = r.condition();
        if (c == null || c.type() == null || !TYPES.contains(c.type())) {
            throw XamPulseException.invalid("Unknown condition type.");
        }
        Duration window = DurationSpec.parse(c.window(), "window");
        if (window.compareTo(Duration.ofMinutes(1)) < 0 || window.compareTo(Duration.ofHours(24)) > 0) {
            throw XamPulseException.invalid("The window must be between 1m and 24h.");
        }
        Duration hold = hold(c);
        if (hold.compareTo(Duration.ofMinutes(60)) > 0) {
            throw XamPulseException.invalid("'for' can be at most 60m.");
        }
        if (c.noData() != null && !Set.of("ALERT", "OK", "KEEP").contains(c.noData())) {
            throw XamPulseException.invalid("Unknown no-data setting.");
        }
        switch (c.type()) {
            case "METRIC_THRESHOLD" -> {
                requireOp(c);
                requireValue(c);
                if (c.agg() == null || !THRESHOLD_AGGS.contains(c.agg())) {
                    throw XamPulseException.invalid("Choose avg, min, max, sum or p95.");
                }
                spec(c, c.metric());
            }
            case "LOG_COUNT" -> {
                requireOp(c);
                requireValue(c);
                query(c);
            }
            case "HEARTBEAT_MISSING" -> {
                if (window.compareTo(Duration.ofMinutes(2)) < 0) {
                    throw XamPulseException.invalid("The heartbeat window must be at least 2m.");
                }
            }
            case "METRIC_ANOMALY" -> spec(c, c.metric());
            case "FORECAST" -> {
                if (c.forecastDays() == null || c.forecastDays() < 1 || c.forecastDays() > 90) {
                    throw XamPulseException.invalid("Choose a forecast horizon between 1 and 90 days.");
                }
            }
            default -> throw XamPulseException.invalid("Unknown condition type.");
        }
        if (r.routing() != null) {
            if (r.routing().channelIds() != null && r.routing().channelIds().size() > 10) {
                throw XamPulseException.invalid("At most 10 channels per rule.");
            }
            if (r.routing().createTicket()) {
                throw XamPulseException.invalid("Ticket creation is not available yet.");
            }
            if (r.routing().runbookUrl() != null && !r.routing().runbookUrl().isBlank()) {
                try {
                    URI u = URI.create(r.routing().runbookUrl().trim());
                    if (!("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())) || u.getHost() == null
                            || r.routing().runbookUrl().length() > 2048) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException e) {
                    throw XamPulseException.invalid("The runbook link is not a valid web address.");
                }
            }
        }
        if (r.grouping() != null && r.grouping().dedupeWindow() != null) {
            DurationSpec.parse(r.grouping().dedupeWindow(), "dedupeWindow");
        }
    }

    public static Duration hold(XpCondition c) {
        return c.forDuration() == null ? Duration.ZERO : DurationSpec.parse(c.forDuration(), "for");
    }

    /** Statement for one evaluation at {@code now}. FORECAST uses 14 daily points. */
    public Compiled compile(Long accountId, XpAlertRule r, Instant now) {
        XpCondition c = r.condition();
        Duration window = DurationSpec.parse(c.window(), "window");
        List<String> instances = r.scope().instanceIds() == null ? List.of() : r.scope().instanceIds();
        Instant from = now.minus(window);
        QueryScope scope = new QueryScope(List.of(accountId), from, now, List.of(), instances, List.of(), List.of());
        ScopedSql sql;
        switch (c.type()) {
            case "METRIC_THRESHOLD", "METRIC_ANOMALY" -> {
                MetricSpec spec = spec(c, c.metric());
                sql = builder.alertMetric(scope, "METRIC_ANOMALY".equals(c.type())
                        ? new MetricSpec(spec.metric(), "avg", spec.groupBy(), spec.filters()) : spec);
            }
            case "LOG_COUNT" -> sql = builder.alertLogCount(scope, query(c));
            case "HEARTBEAT_MISSING" -> {
                scope = new QueryScope(List.of(accountId), now.minus(Duration.ofHours(24)), now, List.of(), instances, List.of(), List.of());
                sql = builder.lastHeartbeat(scope);
            }
            case "FORECAST" -> {
                scope = new QueryScope(List.of(accountId), now.minus(Duration.ofDays(14)), now, List.of(), instances, List.of(), List.of());
                String metric = c.metric() == null ? DEFAULT_FORECAST_METRIC : c.metric();
                MetricSpec spec = new MetricSpec(metric, "avg", List.of("instance_id", "mountpoint"), List.of());
                sql = builder.metricSeries(scope, spec, TenantScopedQueryBuilder.planMetric(scope, 86_400));
            }
            default -> throw XamPulseException.invalid("Unknown condition type.");
        }
        return new Compiled(accountId, window, hold(c), scope, sql, c.type());
    }

    static MetricSpec spec(XpCondition c, String metric) {
        try {
            List<MetricSpec.LabelFilter> filters = new ArrayList<>();
            if (c.filters() != null) {
                for (XpMetricFilter f : c.filters()) {
                    filters.add(new MetricSpec.LabelFilter(f.k(), f.op(), f.v()));
                }
            }
            return new MetricSpec(metric, c.agg() == null ? "avg" : c.agg(), List.of("instance_id"), filters);
        } catch (IllegalArgumentException e) {
            throw XamPulseException.invalid(e.getMessage());
        }
    }

    static FilterAst query(XpCondition c) {
        if (c.query() == null || c.query().isBlank()) {
            throw XamPulseException.invalid("A log query is required.");
        }
        return FilterDslParser.parse(c.query());
    }

    private static void requireOp(XpCondition c) {
        if (c.op() == null || !OPS.contains(c.op())) {
            throw XamPulseException.invalid("Choose a comparison (>, >=, <, <=).");
        }
    }

    private static void requireValue(XpCondition c) {
        if (c.value() == null || c.value().isNaN() || c.value().isInfinite()) {
            throw XamPulseException.invalid("A threshold value is required.");
        }
    }
}
