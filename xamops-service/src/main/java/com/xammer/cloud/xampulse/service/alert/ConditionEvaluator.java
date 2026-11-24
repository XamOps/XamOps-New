package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpAlertRule;
import com.xammer.cloud.xampulse.dto.XpCondition;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.insight.BaselineMath;
import com.xammer.cloud.xampulse.service.insight.ForecastService;
import com.xammer.cloud.xampulse.service.insight.XamPulseAnomalyDetector;
import com.xammer.cloud.xampulse.service.query.BaselineSignal;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Turns a compiled rule into per-group observations (breaching or not). One group per instance. */
@Service("xpConditionEvaluator")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ConditionEvaluator {

    public record Observation(String group, Double value, boolean breaching, String detail) {}

    private final RuleCompiler compiler;
    private final ClickHouseExecutor executor;
    private final XpAgentRepository agents;
    private final ForecastService forecast;
    private final XamPulseAnomalyDetector anomaly;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public ConditionEvaluator(RuleCompiler compiler, ClickHouseExecutor executor, XpAgentRepository agents,
                              ForecastService forecast, XamPulseAnomalyDetector anomaly) {
        this.compiler = compiler;
        this.executor = executor;
        this.agents = agents;
        this.forecast = forecast;
        this.anomaly = anomaly;
    }

    public List<Observation> observe(Long accountId, XpAlertRule rule, Instant now) {
        RuleCompiler.Compiled c = compiler.compile(accountId, rule, now);
        XpCondition cond = rule.condition();
        List<Map<String, Object>> rows = executor.query(c.sql());
        return switch (c.type()) {
            case "METRIC_THRESHOLD" -> threshold(rows, cond, universe(accountId, rule), false);
            case "LOG_COUNT" -> threshold(rows, cond, universe(accountId, rule), true);
            case "HEARTBEAT_MISSING" -> heartbeat(rows, universe(accountId, rule), c, now);
            case "METRIC_ANOMALY" -> anomalies(accountId, rule, rows, now);
            case "FORECAST" -> forecasts(accountId, rows, cond.forecastDays());
            default -> List.of();
        };
    }

    /** Instances the rule covers: the explicit scope, else the account's active agents. */
    List<String> universe(Long accountId, XpAlertRule rule) {
        if (rule.scope().instanceIds() != null && !rule.scope().instanceIds().isEmpty()) {
            return rule.scope().instanceIds();
        }
        return agents.findByCloudAccountId(accountId).stream()
                .filter(a -> "ACTIVE".equals(a.getState())).map(XpAgent::getInstanceId).toList();
    }

    static List<Observation> threshold(List<Map<String, Object>> rows, XpCondition cond, List<String> universe, boolean fillZero) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            values.put(Rows.s(r, "g0"), Rows.dOrNull(r, "v"));
        }
        List<Observation> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : values.entrySet()) {
            out.add(obs(e.getKey(), e.getValue(), cond));
        }
        if (fillZero) {
            // A count of zero produces no row; "fewer than N errors" must still be evaluable.
            for (String i : universe) {
                if (!values.containsKey(i)) {
                    out.add(obs(i, 0.0, cond));
                }
            }
        }
        return out;
    }

    private static Observation obs(String group, Double value, XpCondition cond) {
        if (value == null) {
            return new Observation(group, null, false, "no data");
        }
        boolean breach = AlertPreviewEngine.compare(value, cond.op(), cond.value());
        return new Observation(group, value, breach, null);
    }

    static List<Observation> heartbeat(List<Map<String, Object>> rows, List<String> universe, RuleCompiler.Compiled c, Instant now) {
        Map<String, Long> last = new HashMap<>();
        rows.forEach(r -> last.put(Rows.s(r, "g0"), Rows.l(r, "v")));
        List<Observation> out = new ArrayList<>();
        for (String i : universe) {
            Long ts = last.get(i);
            if (ts == null) {
                out.add(new Observation(i, null, true, "no heartbeat in the last 24h"));
            } else {
                long age = Math.max(0, now.getEpochSecond() - ts);
                out.add(new Observation(i, (double) age, age > c.window().toSeconds(), null));
            }
        }
        return out;
    }

    private List<Observation> anomalies(Long accountId, XpAlertRule rule, List<Map<String, Object>> rows, Instant now) {
        List<String> instances = rows.stream().map(r -> Rows.s(r, "g0")).toList();
        if (instances.isEmpty()) {
            return List.of();
        }
        QueryScope scope = new QueryScope(List.of(accountId), now.minusSeconds(60), now, List.of(), List.of(), List.of(), List.of());
        Map<String, Map<Integer, BaselineMath.Baseline>> base = new HashMap<>();
        for (Map<String, Object> b : executor.query(builder.baseline(scope, rule.condition().metric(), instances))) {
            base.computeIfAbsent(Rows.s(b, "instance_id"), k -> new HashMap<>())
                    .put((int) Rows.l(b, "hour_of_week"), new BaselineMath.Baseline(Rows.d(b, "med"), Rows.d(b, "mad")));
        }
        int how = BaselineMath.hourOfWeek(now);
        BaselineSignal signal = BaselineSignal.of(rule.condition().metric()).orElse(null);
        List<Observation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String inst = Rows.s(r, "g0");
            Double raw = Rows.dOrNull(r, "v");
            // Same reading as the baseline: CPU busy is 1 - the idle series the rule query selected.
            Double v = raw == null || signal == null ? raw : signal.value(raw);
            BaselineMath.Baseline bl = base.getOrDefault(inst, Map.of()).get(how);
            if (v == null || bl == null) {
                // Cold start: no baseline yet means no anomaly claim, and the observation is "no data".
                out.add(new Observation(inst, v, false, "no baseline yet"));
            } else {
                out.add(new Observation(inst, v, anomaly.isAnomalous(v, bl), null));
            }
        }
        return out;
    }

    private List<Observation> forecasts(Long accountId, List<Map<String, Object>> rows, Integer horizonDays) {
        Map<String, TreeMap<Long, Double>> byGroup = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Double v = Rows.dOrNull(r, "v");
            if (v != null) {
                byGroup.computeIfAbsent(Rows.s(r, "g0") + "|" + Rows.s(r, "g1"), k -> new TreeMap<>()).put(Rows.l(r, "t"), v * 100.0);
            }
        }
        List<Observation> out = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Long, Double>> e : byGroup.entrySet()) {
            long t0 = e.getValue().firstKey();
            List<ForecastService.Point> pts = e.getValue().entrySet().stream()
                    .map(p -> new ForecastService.Point((p.getKey() - t0) / 86_400.0, p.getValue())).toList();
            String[] parts = e.getKey().split("\\|", 2);
            var f = forecast.forecast(accountId, parts[0], parts.length > 1 ? parts[1] : "", pts);
            boolean breach = f.daysToFull() != null && f.daysToFull() <= horizonDays;
            out.add(new Observation(e.getKey(), f.daysToFull(), breach, f.message()));
        }
        return out;
    }
}
