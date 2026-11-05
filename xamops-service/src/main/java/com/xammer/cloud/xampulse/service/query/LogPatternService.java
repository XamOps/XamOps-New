package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups log lines by message template, compares with the previous period and flags new patterns. */
@Service("xpLogPatternService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class LogPatternService {

    private static final int TREND_POINTS = 12;

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public LogPatternService(QueryScopeFactory scopes, ClickHouseExecutor executor) {
        this.scopes = scopes;
        this.executor = executor;
    }

    public XpLogPatternsResponse patterns(ClientUserDetails user, XpLogPatternsRequest req) {
        QueryScope scope = scopes.build(user, req.scope());
        FilterAst filter = FilterDslParser.parse(req.query());
        int limit = req.limit() == null ? 30 : req.limit();
        boolean compare = req.compareToPrevious() == null || req.compareToPrevious();

        record Acc(String template, long count, long firstMs, String sampleId, String severity) {}
        Map<String, Acc> merged = new LinkedHashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.patterns(s, filter, limit))) {
            for (Map<String, Object> r : tr.rows()) {
                String tpl = Rows.s(r, "tpl");
                String sample = LogRowIdCodec.encode(tr.tenantId(), Rows.l(r, "s_ts"), Rows.s(r, "s_instance"), Rows.s(r, "s_hash"));
                Acc prev = merged.get(tpl);
                long count = Rows.l(r, "c") + (prev == null ? 0 : prev.count());
                long first = prev == null ? Rows.l(r, "first_ms") : Math.min(prev.firstMs(), Rows.l(r, "first_ms"));
                merged.put(tpl, new Acc(Rows.s(r, "template"), count, first, prev == null ? sample : prev.sampleId(),
                        severityName((int) Rows.l(r, "sev"))));
            }
        }
        List<String> top = merged.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().count(), a.getValue().count()))
                .limit(limit).map(Map.Entry::getKey).toList();
        if (top.isEmpty()) {
            return new XpLogPatternsResponse(List.of());
        }

        Map<String, Long> previous = new HashMap<>();
        if (compare) {
            Duration len = Duration.between(scope.from(), scope.to());
            QueryScope prevScope = scope.withRange(scope.from().minus(len), scope.from());
            for (ClickHouseExecutor.TenantRows tr : executor.queryEach(prevScope, s -> builder.patternCounts(s, filter, top))) {
                for (Map<String, Object> r : tr.rows()) {
                    previous.merge(Rows.s(r, "tpl"), Rows.l(r, "c"), Long::sum);
                }
            }
        }
        long bucketSec = Math.max(1, scope.rangeSeconds() / TREND_POINTS);
        long fromSec = scope.from().getEpochSecond();
        Map<String, long[]> trends = new HashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.patternTrend(s, filter, top, bucketSec))) {
            for (Map<String, Object> r : tr.rows()) {
                long[] arr = trends.computeIfAbsent(Rows.s(r, "tpl"), k -> new long[TREND_POINTS]);
                int idx = (int) Math.min(TREND_POINTS - 1, Math.max(0, (Rows.l(r, "t") - fromSec) / bucketSec));
                arr[idx] += Rows.l(r, "c");
            }
        }

        List<XpPattern> out = new ArrayList<>();
        for (String tpl : top) {
            Acc a = merged.get(tpl);
            Long prev = compare ? previous.getOrDefault(tpl, 0L) : null;
            Double delta = (prev == null || prev == 0) ? null : (a.count() - prev) * 100.0 / prev;
            boolean isNew = compare && prev != null && prev == 0 && a.firstMs() >= scope.from().toEpochMilli();
            List<Long> trend = new ArrayList<>();
            long[] arr = trends.getOrDefault(tpl, new long[TREND_POINTS]);
            for (long v : arr) {
                trend.add(v);
            }
            out.add(new XpPattern(tpl, a.template(), a.count(), delta, isNew, trend, Instant.ofEpochMilli(a.firstMs()),
                    a.sampleId(), XpSeverity.valueOf(a.severity())));
        }
        return new XpLogPatternsResponse(out);
    }

    private static String severityName(int level) {
        XpSeverity[] all = XpSeverity.values();
        return all[Math.max(1, Math.min(level, all.length)) - 1].name();
    }
}
