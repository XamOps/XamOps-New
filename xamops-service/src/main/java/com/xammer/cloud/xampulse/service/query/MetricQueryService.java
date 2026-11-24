package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.insight.BaselineMath;
import com.xammer.cloud.xampulse.service.insight.XamPulseAnomalyDetector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Metric time series with automatic rollup selection, optional anomaly band and period-over-period compare. */
@Service("xpMetricQueryService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class MetricQueryService {

    static final int MAX_SERIES = 200;
    private static final double BAND_K = XamPulseAnomalyDetector.DEFAULT_K;

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public MetricQueryService(QueryScopeFactory scopes, ClickHouseExecutor executor) {
        this.scopes = scopes;
        this.executor = executor;
    }

    public XpMetricQueryResponse query(ClientUserDetails user, XpMetricQueryRequest req) {
        long started = System.nanoTime();
        QueryScope scope = scopes.build(user, req.scope());
        boolean multiAccount = scope.accountIds().size() > 1;
        List<XpMetricSeries> all = new ArrayList<>();
        int stepOut = 0;
        for (XpMetricSeriesSpec specDto : req.series()) {
            MetricSpec spec = toSpec(specDto);
            TenantScopedQueryBuilder.MetricPlan plan = TenantScopedQueryBuilder.planMetric(scope, specDto.stepSec());
            stepOut = Math.max(stepOut, plan.stepSec());
            Map<String, XpMetricSeries> primary = run(scope, spec, plan, multiAccount, 0);
            Map<String, XpMetricSeries> compare = req.compareOffsetSec() != null && req.compareOffsetSec() > 0
                    ? run(scope.withRange(scope.from().minusSeconds(req.compareOffsetSec()), scope.to().minusSeconds(req.compareOffsetSec())),
                    spec, plan, multiAccount, req.compareOffsetSec())
                    : Map.of();
            Map<String, List<double[]>> bands = "ANOMALY".equalsIgnoreCase(req.band())
                    ? bands(scope, spec, primary) : Map.of();
            for (Map.Entry<String, XpMetricSeries> e : primary.entrySet()) {
                XpMetricSeries s = e.getValue();
                XpMetricSeries c = compare.get(e.getKey());
                all.add(new XpMetricSeries(s.metric(), s.labels(), s.points(), bands.get(e.getKey()), c == null ? null : c.points()));
            }
            if (all.size() > MAX_SERIES) {
                throw XamPulseException.invalid("Too many series. Add a filter or reduce group-by.");
            }
        }
        return new XpMetricQueryResponse(stepOut, all, (System.nanoTime() - started) / 1_000_000);
    }

    static MetricSpec toSpec(XpMetricSeriesSpec dto) {
        try {
            List<MetricSpec.LabelFilter> filters = new ArrayList<>();
            if (dto.filters() != null) {
                for (XpMetricFilter f : dto.filters()) {
                    filters.add(new MetricSpec.LabelFilter(f.k(), f.op(), f.v()));
                }
            }
            return new MetricSpec(dto.metric(), dto.agg(), dto.groupBy(), filters);
        } catch (IllegalArgumentException e) {
            throw XamPulseException.invalid(e.getMessage());
        }
    }

    private Map<String, XpMetricSeries> run(QueryScope scope, MetricSpec spec, TenantScopedQueryBuilder.MetricPlan plan,
                                            boolean multiAccount, long shiftSec) {
        Map<String, XpMetricSeries> out = new LinkedHashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.metricSeries(s, spec, plan))) {
            for (Map<String, Object> r : tr.rows()) {
                Map<String, String> labels = new LinkedHashMap<>();
                for (int i = 0; i < spec.groupBy().size(); i++) {
                    labels.put(spec.groupBy().get(i), Rows.s(r, "g" + i));
                }
                if (multiAccount) {
                    labels.put("accountId", String.valueOf(tr.tenantId()));
                }
                String key = labels.toString();
                XpMetricSeries series = out.computeIfAbsent(key,
                        k -> new XpMetricSeries(spec.metric(), labels, new ArrayList<>(), null, null));
                Double v = Rows.dOrNull(r, "v");
                series.points().add(new Object[] {(Rows.l(r, "t") + shiftSec) * 1000L, v});
            }
        }
        return out;
    }

    /**
     * True when a chart series is the value a baseline was learned on ({@link BaselineSignal}): one line per instance,
     * no further split, the defining label filter only, and the same way of combining series. A band on anything
     * else (CPU split by state, one mount, memory "free") would compare it with a range it was never learned on.
     */
    static boolean bandApplies(MetricSpec spec) {
        BaselineSignal signal = BaselineSignal.of(spec.metric()).orElse(null);
        if (signal == null || !List.of("instance_id").equals(spec.groupBy())) {
            return false;
        }
        String agg = signal.maxOfSeries() ? "max" : "avg";
        return agg.equals(spec.agg()) && new HashSet<>(signal.filters()).equals(new HashSet<>(spec.filters()));
    }

    /** Anomaly band per series, only for the series a baseline describes (see bandApplies) and once it exists. */
    private Map<String, List<double[]>> bands(QueryScope scope, MetricSpec spec, Map<String, XpMetricSeries> series) {
        Map<String, List<double[]>> out = new HashMap<>();
        if (!bandApplies(spec) || series.isEmpty()) {
            return out;
        }
        BaselineSignal signal = BaselineSignal.of(spec.metric()).orElseThrow();
        List<String> instances = series.values().stream().map(s -> s.labels().get("instance_id")).distinct().toList();
        Map<Long, Map<String, Map<Integer, BaselineMath.Baseline>>> byTenant = new HashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.baseline(s, spec.metric(), instances))) {
            for (Map<String, Object> r : tr.rows()) {
                byTenant.computeIfAbsent(tr.tenantId(), k -> new HashMap<>())
                        .computeIfAbsent(Rows.s(r, "instance_id"), k -> new HashMap<>())
                        .put((int) Rows.l(r, "hour_of_week"), new BaselineMath.Baseline(Rows.d(r, "med"), Rows.d(r, "mad")));
            }
        }
        boolean single = scope.accountIds().size() == 1;
        for (Map.Entry<String, XpMetricSeries> e : series.entrySet()) {
            XpMetricSeries s = e.getValue();
            long tenant = single ? scope.accountIds().get(0) : parseTenant(s.labels().get("accountId"));
            Map<Integer, BaselineMath.Baseline> base = byTenant.getOrDefault(tenant, Map.of()).get(s.labels().get("instance_id"));
            if (base == null || base.isEmpty()) {
                continue;
            }
            List<double[]> band = new ArrayList<>();
            for (Object[] p : s.points()) {
                long tsMs = (Long) p[0];
                double[] b = BaselineMath.band(base.get(BaselineMath.hourOfWeek(Instant.ofEpochMilli(tsMs))), BAND_K);
                if (b != null) {
                    double[] onSeries = signal.bandForSeries(b[0], b[1]);
                    band.add(new double[] {tsMs, onSeries[0], onSeries[1]});
                }
            }
            if (!band.isEmpty()) {
                out.put(e.getKey(), band);
            }
        }
        return out;
    }

    private static long parseTenant(String s) {
        try {
            return Long.parseLong(s);
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
