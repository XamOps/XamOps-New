package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.service.alert.IncidentService;
import com.xammer.cloud.xampulse.service.query.UsageMeteringService;
import com.xammer.cloud.xampulse.service.query.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Insights tab: narrative, disk-full forecasts, new log patterns, a log-cost view and "what changed" candidates
 * for open incidents. Every item carries evidence and a confidence; nothing is generated without data behind it.
 */
@Service("xpInsightFeedService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class InsightFeedService {

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final OverviewService overview;
    private final LogPatternService patterns;
    private final UsageMeteringService usage;
    private final IncidentService incidents;
    private final EventQueryService events;
    private final ForecastService forecast;
    private final WhatChangedService whatChanged;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public InsightFeedService(QueryScopeFactory scopes, ClickHouseExecutor executor, OverviewService overview,
                              LogPatternService patterns, UsageMeteringService usage, IncidentService incidents,
                              EventQueryService events, ForecastService forecast, WhatChangedService whatChanged) {
        this.scopes = scopes;
        this.executor = executor;
        this.overview = overview;
        this.patterns = patterns;
        this.usage = usage;
        this.incidents = incidents;
        this.events = events;
        this.forecast = forecast;
        this.whatChanged = whatChanged;
    }

    public List<XpInsight> feed(ClientUserDetails user, List<Long> accountIds, Instant from, Instant to) {
        QueryScope scope = scopes.build(user, accountIds, from, to);
        Instant now = Instant.now();
        List<XpInsight> out = new ArrayList<>();

        XpOverview ov = overview.overview(user, scope.accountIds(), from, to);
        if (!ov.narrative().isEmpty()) {
            out.add(new XpInsight("narrative", "NARRATIVE", "INFO", "Fleet summary", String.join(" ", ov.narrative()),
                    scope.accountIds().get(0), null, List.of(), "HIGH", now));
        }
        for (Long account : scope.accountIds()) {
            for (XpForecast f : forecasts(user, account)) {
                if (f.daysToFull() != null && f.daysToFull() <= 30) {
                    String sev = f.daysToFull() <= 7 ? "HIGH" : "MEDIUM";
                    out.add(new XpInsight("forecast:" + account + ":" + f.instanceId() + ":" + f.resource(), "ANOMALY", sev,
                            "Disk filling up on " + f.instanceId(), f.message(), account, f.instanceId(),
                            List.of(new XpEvidence("METRIC", f.resource() + " utilisation trend (R2 " + String.format(Locale.ROOT, "%.2f", f.r2()) + ")",
                                    "/xampulse/hosts/" + f.instanceId() + "?accountId=" + account)),
                            f.r2() >= 0.85 ? "HIGH" : "MEDIUM", now));
                }
            }
            XpLogPatternsResponse p = patterns.patterns(user, new XpLogPatternsRequest(
                    new XpScope(List.of(account), null, from, to, null), "severity:>=warn", true, 20));
            p.patterns().stream().filter(XpPattern::isNew).limit(3).forEach(x ->
                    out.add(new XpInsight("pattern:" + account + ":" + x.patternId(), "NEW_PATTERN", "LOW",
                            "New log pattern (" + x.count() + " lines)", x.template(), account, null,
                            List.of(new XpEvidence("LOG", "First seen " + x.firstSeen(), "/xampulse/logs?pattern=" + x.patternId())),
                            "MEDIUM", now)));
            XpUsage u = usage.usage(user, account, "7d");
            if (!u.bySource().isEmpty() && u.bySource().get(0).pct() >= 40) {
                XpUsageSource top = u.bySource().get(0);
                boolean unnamed = UsageMeteringService.UNNAMED_SOURCE.equals(top.source());
                out.add(new XpInsight("cost:" + account, "COST", "INFO",
                        unnamed ? "Most of your logs have no service name" : "One service dominates your log volume",
                        unnamed ? String.format(Locale.ROOT, "%.0f%% of the logs ingested in the last 7 days carry no service name, so they "
                                + "cannot be filtered or costed by service. Agents from 1.1.0 name system logs after the program that wrote them.", top.pct())
                                : String.format(Locale.ROOT, "%s produced %.0f%% of the logs ingested in the last 7 days.", top.source(), top.pct()),
                        account, null, List.of(new XpEvidence("LOG", "Volume by service", "/xampulse/setup?view=usage&accounts=" + account)), "HIGH", now));
            }
            for (XpIncident inc : incidents.activeFor(account)) {
                if (!List.of("CRITICAL", "HIGH").contains(inc.severity())) {
                    continue;
                }
                QueryScope evScope = QueryScope.of(List.of(account), inc.openedAt().minus(Duration.ofMinutes(60)), inc.openedAt().plusSeconds(1));
                String instance = inc.instanceIds().isEmpty() ? null : inc.instanceIds().get(0);
                var ranked = whatChanged.rank(inc.openedAt(), instance, events.events(evScope, List.of()), 60, 0);
                if (!ranked.isEmpty()) {
                    XpEvent e = ranked.get(0).event();
                    out.add(new XpInsight("changed:" + inc.id(), "WHAT_CHANGED", "MEDIUM", "Possible cause for \"" + inc.title() + "\"",
                            e.title() + " at " + e.ts() + ", shortly before the incident. This is a candidate, not a confirmed cause.",
                            account, instance, List.of(new XpEvidence("EVENT", e.title(), "/xampulse/alerts?incident=" + inc.id())),
                            "LOW", now));
                }
            }
        }
        out.sort(Comparator.comparingInt((XpInsight i) -> sevRank(i.severity())).reversed());
        return out;
    }

    /** Disk forecasts per instance and mount point from 14 days of daily averages. */
    public List<XpForecast> forecasts(ClientUserDetails user, Long accountId) {
        Instant now = Instant.now();
        QueryScope scope = scopes.build(user, List.of(accountId), now.minus(Duration.ofDays(14)), now);
        MetricSpec spec = new MetricSpec("system.filesystem.utilization", "avg", List.of("instance_id", "mountpoint"), List.of());
        var plan = TenantScopedQueryBuilder.planMetric(scope, 86_400);
        Map<String, TreeMap<Long, Double>> groups = new java.util.HashMap<>();
        for (Map<String, Object> r : executor.query(builder.metricSeries(scope.forTenant(accountId), spec, plan))) {
            Double v = Rows.dOrNull(r, "v");
            if (v != null) {
                groups.computeIfAbsent(Rows.s(r, "g0") + "\u0000" + Rows.s(r, "g1"), k -> new TreeMap<>()).put(Rows.l(r, "t"), v * 100.0);
            }
        }
        List<XpForecast> out = new ArrayList<>();
        groups.forEach((k, pts) -> {
            long t0 = pts.firstKey();
            List<ForecastService.Point> points = pts.entrySet().stream()
                    .map(e -> new ForecastService.Point((e.getKey() - t0) / 86_400.0, e.getValue())).toList();
            String[] parts = k.split("\u0000", 2);
            out.add(forecast.forecast(accountId, parts[0], parts.length > 1 ? parts[1] : "", points));
        });
        out.sort(Comparator.comparing(XpForecast::daysToFull, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private static int sevRank(String s) {
        return switch (s) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }
}
