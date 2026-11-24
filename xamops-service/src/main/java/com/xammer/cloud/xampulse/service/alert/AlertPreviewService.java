package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpAlertPreview;
import com.xammer.cloud.xampulse.dto.XpAlertRule;
import com.xammer.cloud.xampulse.dto.XpCondition;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.MetricSpec;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.QueryScopeFactory;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.ScopedSql;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Alert backtest: "what would this rule have done over the last 24 hours?" The rule is replayed once a minute with
 * the live evaluator's aggregation (same rollup states, same window, same counter-rate rules) and its state machine
 * and dedupe ({@link AlertPreviewEngine}). Supported: metric thresholds (including counter rates), log counts and
 * missing heartbeats. Anomaly and forecast rules depend on baselines that are rebuilt over time and are not replayed.
 */
@Service("xpAlertPreviewService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class AlertPreviewService {

    static final Duration RANGE = Duration.ofHours(24);
    static final int ROW_LIMIT = 300_000;
    static final int MAX_SERIES = 6;

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final RuleCompiler compiler;
    private final XpAgentRepository agents;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public AlertPreviewService(QueryScopeFactory scopes, ClickHouseExecutor executor, RuleCompiler compiler, XpAgentRepository agents) {
        this.scopes = scopes;
        this.executor = executor;
        this.compiler = compiler;
        this.agents = agents;
    }

    public XpAlertPreview preview(ClientUserDetails user, XpAlertRule rule) {
        return preview(user, rule, Instant.now());
    }

    XpAlertPreview preview(ClientUserDetails user, XpAlertRule rule, Instant now) {
        compiler.validate(rule);
        XpCondition c = rule.condition();
        long lastMin = Math.floorDiv(now.getEpochSecond(), 60L) * 60L - 60L; // last complete minute
        long fromMin = lastMin - RANGE.toSeconds() + 60L;
        Long account = rule.scope().accountIds().get(0);
        QueryScope scope = scopes.build(user, List.of(account), Instant.ofEpochSecond(fromMin), now, null,
                rule.scope().instanceIds(), null, null).forTenant(account);
        long window = DurationSpec.parse(c.window(), "window").toSeconds();
        AlertStateMachine.NoDataPolicy policy = c.noData() == null ? AlertStateMachine.NoDataPolicy.KEEP : AlertStateMachine.NoDataPolicy.valueOf(c.noData());
        Duration dedupe = rule.grouping() != null && rule.grouping().dedupeWindow() != null
                ? DurationSpec.parse(rule.grouping().dedupeWindow(), "dedupeWindow") : Duration.ofMinutes(15);
        List<String> notes = new ArrayList<>();
        XpAlertPreview result;
        switch (c.type()) {
            case "METRIC_THRESHOLD", "LOG_COUNT" -> {
                if (window > 6 * 3600) {
                    throw XamPulseException.invalid("Backtest replays windows up to 6h.");
                }
                boolean logs = "LOG_COUNT".equals(c.type());
                ScopedSql sql;
                if (logs) {
                    sql = builder.backtestLogCount(scope, RuleCompiler.query(c), fromMin, lastMin, window, ROW_LIMIT);
                } else {
                    MetricSpec spec = RuleCompiler.spec(c, c.metric());
                    sql = "rate".equals(spec.agg()) ? builder.backtestCounterRate(scope, spec, fromMin, lastMin, window, ROW_LIMIT)
                            : builder.backtestMetric(scope, spec, fromMin, lastMin, window, ROW_LIMIT);
                }
                List<Map<String, Object>> rows = executor.query(sql);
                truncated(rows, notes);
                AlertPreviewEngine.Config cfg = new AlertPreviewEngine.Config(c.op(), c.value(), RuleCompiler.hold(c), policy, dedupe);
                // Log counts cover the rule's instances at every evaluation ("no matching lines" is a count of 0).
                result = AlertPreviewEngine.replay(values(rows), logs ? universe(account, rule) : List.of(), logs ? 0.0 : null,
                        fromMin, lastMin, cfg, MAX_SERIES);
            }
            case "HEARTBEAT_MISSING" -> {
                List<String> universe = universe(account, rule);
                // The evaluator looks 24 hours back for the last data; read that far before the first evaluation too.
                List<Map<String, Object>> rows = executor.query(builder.backtestPresence(scope, fromMin + 60 - RANGE.toSeconds(), lastMin + 60, ROW_LIMIT));
                truncated(rows, notes);
                AlertPreviewEngine.Config cfg = new AlertPreviewEngine.Config(">", window, RuleCompiler.hold(c), policy, dedupe);
                result = AlertPreviewEngine.replay(ages(rows, universe, fromMin, lastMin), universe, null, fromMin, lastMin, cfg, MAX_SERIES);
                notes.add("Value is the seconds since the host last sent data; it alerts above " + window + " s.");
            }
            default -> throw XamPulseException.invalid("Backtest is not available for anomaly and forecast rules: their baselines are rebuilt over time and cannot be replayed exactly.");
        }
        notes.add("Replayed once a minute with live alerting's queries, \"for\", flap guard, missing-data policy and repeat window. "
                + "Live evaluation also sees the minute in progress, so a live alert can come up to a minute sooner.");
        notes.add("Starts from OK: the rule's state before the range is not known. Silences are not replayed.");
        if (rule.routing() == null || rule.routing().channelIds() == null || rule.routing().channelIds().isEmpty()) {
            notes.add("No channel is selected, so nothing would be sent; the counts are what would be sent once one is.");
        }
        return new XpAlertPreview(result.firings(), result.evaluatedPoints(), result.wouldNotify(), result.from(), result.to(),
                result.op(), result.threshold(), result.groups(), result.resolved(), result.suppressedRepeats(), result.quietReopens(),
                result.series(), notes);
    }

    private static void truncated(List<Map<String, Object>> rows, List<String> notes) {
        if (rows.size() >= ROW_LIMIT) {
            notes.add("Too much data to replay in full: only the first instances (by id) were replayed. Narrow the rule's scope.");
        }
    }

    static Map<String, NavigableMap<Long, Double>> values(List<Map<String, Object>> rows) {
        Map<String, NavigableMap<Long, Double>> out = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Double v = Rows.dOrNull(r, "v");
            if (v != null && Double.isFinite(v)) {
                out.computeIfAbsent(Rows.s(r, "g0"), k -> new TreeMap<>()).put(Rows.l(r, "t"), v);
            }
        }
        return out;
    }

    /**
     * Seconds since each instance last sent data, at each evaluation minute, as the evaluator computes it: the latest
     * minute bucket before {@code now = m + 60} within the 24 hours before it. No data in 24 hours is "no data".
     */
    static Map<String, NavigableMap<Long, Double>> ages(List<Map<String, Object>> rows, List<String> universe, long fromMin, long lastMin) {
        Map<String, TreeMap<Long, Boolean>> seen = new HashMap<>();
        for (Map<String, Object> r : rows) {
            seen.computeIfAbsent(Rows.s(r, "g0"), k -> new TreeMap<>()).put(Rows.l(r, "t"), true);
        }
        Map<String, NavigableMap<Long, Double>> out = new HashMap<>();
        for (String i : universe) {
            TreeMap<Long, Boolean> s = seen.get(i);
            NavigableMap<Long, Double> ages = new TreeMap<>();
            for (long m = fromMin; m <= lastMin; m += 60) {
                long now = m + 60;
                Long last = s == null ? null : s.floorKey(m);
                if (last != null && last >= now - RANGE.toSeconds()) {
                    ages.put(m, (double) (now - last));
                }
            }
            out.put(i, ages);
        }
        return out;
    }

    /** Instances the rule covers, as the evaluator decides: its explicit scope, else the account's active agents. */
    private List<String> universe(Long accountId, XpAlertRule rule) {
        if (rule.scope().instanceIds() != null && !rule.scope().instanceIds().isEmpty()) {
            return rule.scope().instanceIds();
        }
        return agents.findByCloudAccountId(accountId).stream().filter(a -> "ACTIVE".equals(a.getState())).map(XpAgent::getInstanceId).toList();
    }
}
