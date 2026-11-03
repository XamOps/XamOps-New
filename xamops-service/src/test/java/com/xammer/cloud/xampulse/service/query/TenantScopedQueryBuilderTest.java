package com.xammer.cloud.xampulse.service.query;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantScopedQueryBuilderTest {

    private static final String SENTINEL = "SNTL_7f3a91";
    private static final List<Long> TENANTS = List.of(11L, 22L);
    private final TenantScopedQueryBuilder b = new TenantScopedQueryBuilder();
    private final Instant from = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-01-01T06:00:00Z");

    private QueryScope scope(List<Long> tenants) {
        return new QueryScope(tenants, from, to, List.of("us-east-1" + SENTINEL), List.of("i-" + SENTINEL),
                List.of("svc" + SENTINEL), List.of("c" + SENTINEL));
    }

    private List<ScopedSql> everyStatement(List<Long> tenants) {
        QueryScope s = scope(tenants);
        FilterAst f = FilterDslParser.parse("service:a" + SENTINEL + " severity:>=warn attr.k:v" + SENTINEL + " text" + SENTINEL);
        MetricSpec m = new MetricSpec("system.cpu.utilization", "avg", List.of("instance_id", "state"),
                List.of(new MetricSpec.LabelFilter("state", "=", "idle" + SENTINEL), new MetricSpec.LabelFilter("mountpoint", "=~", "/v.*" + SENTINEL)));
        TenantScopedQueryBuilder.MetricPlan plan = TenantScopedQueryBuilder.planMetric(s, null);
        List<ScopedSql> out = new ArrayList<>();
        out.add(b.search(s, f, true, 50, new TenantScopedQueryBuilder.LogCursor(1000, "12345")));
        out.add(b.search(s, f, false, 50, null));
        out.add(b.histogram(s, f, 60));
        out.add(b.facet(s, f, LogField.SERVICE, null, 10));
        out.add(b.facet(s, f, LogField.SEVERITY, null, 10));
        out.add(b.facet(s, f, LogField.ATTR, "http.status", 10));
        out.add(b.patterns(s, f, 20));
        out.add(b.patternCounts(s, f, List.of("123", "456")));
        out.add(b.patternTrend(s, f, List.of("123"), 300));
        out.add(b.tailPoll(s, f, 5000, 100));
        out.add(b.metricSeries(s, m, plan));
        out.add(b.latestMetrics(s, 1000, List.of("system.cpu.utilization", "x" + SENTINEL)));
        out.add(b.baseline(s, "system.cpu.utilization", List.of("i-" + SENTINEL)));
        out.add(b.baselineSamples(s, "system.cpu.utilization", 14));
        out.add(b.metricNames(s));
        out.add(b.events(s, List.of("DEPLOY"), 100));
        out.add(b.usageByDay(s));
        out.add(b.usageBySource(s));
        out.add(b.errorVolume(s));
        out.add(b.lastDataAt(s));
        out.add(b.alertMetric(s, m));
        out.add(b.alertLogCount(s, f));
        out.add(b.lastHeartbeat(s));
        return out;
    }

    @Test
    void everyMultiTenantStatementStartsItsWhereWithTheTenantPredicate() {
        for (ScopedSql q : everyStatement(TENANTS)) {
            String sql = q.sql();
            int where = sql.indexOf("WHERE ");
            assertTrue(where > 0, sql);
            assertTrue(sql.startsWith("tenant_id IN (?, ?)", where + "WHERE ".length()), "tenant predicate must come first: " + sql);
            assertEquals(TENANTS, q.params().subList(0, 2), sql);
            assertEquals(TENANTS, q.tenantIds());
        }
    }

    @Test
    void placeholderCountMatchesParameterCountAndNoUserTextIsInTheSql() {
        for (ScopedSql q : everyStatement(TENANTS)) {
            long placeholders = q.sql().chars().filter(c -> c == '?').count();
            assertEquals(placeholders, q.params().size(), q.sql());
            assertFalse(q.sql().contains(SENTINEL), "user text leaked into SQL: " + q.sql());
        }
    }

    @Test
    void sentinelValuesTravelOnlyAsParameters() {
        ScopedSql search = b.search(scope(List.of(7L)), FilterDslParser.parse("service:a" + SENTINEL), true, 10, null);
        assertTrue(search.params().stream().anyMatch(p -> String.valueOf(p).contains(SENTINEL)));
        assertFalse(search.sql().contains(SENTINEL));
    }

    @Test
    void singleTenantStatementsPassTheExecutorValidation() {
        for (ScopedSql q : everyStatement(List.of(11L))) {
            ClickHouseExecutor.validate(q);
        }
    }

    @Test
    void generatedDslInputsNeverProduceUnsafeSqlText() {
        String[] frags = {"error", "service:api", "-service:db", "(", ")", " OR ", " AND ", "NOT ", "severity:>=warn", "attr.k:v*", "\"quoted text\"",
                "'; DROP TABLE x;--", "tenant_id:1", "%_\\", "*", "service:(a OR b)", "container:c1", "trace_id:abc", "host:i-1", "\"", "'", ";", "--", "/*"};
        Random rnd = new Random(20261030L);
        QueryScope s = QueryScope.of(List.of(5L), from, to);
        int parsed = 0;
        for (int i = 0; i < 500; i++) {
            StringBuilder q = new StringBuilder();
            int n = 1 + rnd.nextInt(7);
            for (int k = 0; k < n; k++) {
                q.append(frags[rnd.nextInt(frags.length)]).append(rnd.nextBoolean() ? " " : "");
            }
            FilterAst ast;
            try {
                ast = FilterDslParser.parse(q.toString());
            } catch (com.xammer.cloud.xampulse.exception.XamPulseException e) {
                continue;
            }
            parsed++;
            ScopedSql sql = b.search(s, ast, true, 10, null);
            assertFalse(sql.sql().contains("'"), sql.sql());
            assertFalse(sql.sql().contains(";"), sql.sql());
            assertFalse(sql.sql().contains("--"), sql.sql());
            assertFalse(sql.sql().contains("/*"), sql.sql());
            assertTrue(sql.sql().contains("tenant_id IN (?)"));
            ClickHouseExecutor.validate(sql);
        }
        assertTrue(parsed > 50, "generator should produce a healthy share of valid inputs, got " + parsed);
    }

    @Test
    void likePatternsAreEscaped() {
        assertEquals("a\\%b\\_c\\\\d%", TenantScopedQueryBuilder.likePattern("a%b_c\\d*"));
    }

    @Test
    void attributeFacetKeyIsWhitelisted() {
        QueryScope s = QueryScope.of(List.of(1L), from, to);
        assertThrows(RuntimeException.class, () -> b.facet(s, null, LogField.ATTR, "k'] OR 1=1 --", 5));
        assertThrows(RuntimeException.class, () -> b.facet(s, null, LogField.ATTR, null, 5));
    }

    @Test
    void metricSpecRejectsUnsafeIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new MetricSpec("a'b", "avg", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MetricSpec("a.b", "avg); DROP", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MetricSpec("a.b", "avg", List.of("x'"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MetricSpec("a.b", "avg", List.of("region"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MetricSpec.LabelFilter("k k", "=", "v"));
        assertThrows(IllegalArgumentException.class, () -> new MetricSpec.LabelFilter("k", "LIKE", "v"));
    }

    @Test
    void rollupChoiceFollowsTheRangeAndKeepsPointsUnder4000() {
        assertEquals("xp_metrics_1m", TenantScopedQueryBuilder.planMetric(QueryScope.of(List.of(1L), to.minusSeconds(3600), to), null).table());
        assertEquals("xp_metrics_5m", TenantScopedQueryBuilder.planMetric(QueryScope.of(List.of(1L), to.minusSeconds(86_400), to), null).table());
        var longPlan = TenantScopedQueryBuilder.planMetric(QueryScope.of(List.of(1L), to.minusSeconds(30L * 86_400), to), null);
        assertEquals("xp_metrics_1h", longPlan.table());
        assertTrue(30L * 86_400 / longPlan.stepSec() <= 4000);
        assertEquals(0, longPlan.stepSec() % longPlan.tableStepSec());
        assertEquals(120, TenantScopedQueryBuilder.planMetric(QueryScope.of(List.of(1L), to.minusSeconds(3600), to), 100).stepSec());
    }

    @Test
    void invalidCursorIsRejected() {
        assertThrows(RuntimeException.class, () -> new TenantScopedQueryBuilder.LogCursor(1, "1; DROP"));
        assertThrows(RuntimeException.class, () -> b.anchor(1, 1, "i-1", "abc"));
    }

    @Test
    void baselineSamplesIsTenantScopedBindsMetricAndWindowAndRejectsBadWindows() {
        QueryScope s = QueryScope.of(List.of(5L), from, to);
        ScopedSql q = b.baselineSamples(s, "system.cpu.utilization", 14);
        assertTrue(q.sql().contains("FROM xp_metrics_1h"), q.sql());
        assertTrue(q.sql().contains("avgMerge(avg_state)"), q.sql());
        assertTrue(q.sql().startsWith("SELECT instance_id, toUnixTimestamp(ts) AS t"), q.sql());
        assertTrue(q.sql().contains("GROUP BY instance_id, ts ORDER BY instance_id, t"), q.sql());
        // tenant first, then the bound metric, then the window start epoch.
        assertEquals(5L, q.params().get(0));
        assertEquals("system.cpu.utilization", q.params().get(1));
        assertEquals(to.getEpochSecond() - 14L * 86_400L, q.params().get(2));
        assertFalse(q.sql().contains("system.cpu.utilization"), "metric travels only as a parameter");
        ClickHouseExecutor.validate(q);
        assertThrows(IllegalArgumentException.class, () -> b.baselineSamples(s, "m", 0));
        assertThrows(IllegalArgumentException.class, () -> b.baselineSamples(s, "m", 401));
    }
}
