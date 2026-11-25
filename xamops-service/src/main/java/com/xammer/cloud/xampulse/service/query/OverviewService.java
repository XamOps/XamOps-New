package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.alert.IncidentService;
import com.xammer.cloud.xampulse.service.insight.AttentionRanker;
import com.xammer.cloud.xampulse.service.insight.HealthScoreService;
import com.xammer.cloud.xampulse.service.insight.NarrativeBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The Overview tab and fleet health score. Every number is computed from ClickHouse/Postgres; null means "no data". */
@Service("xpOverviewService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class OverviewService {

    private static final Duration LIVE = Duration.ofMinutes(15);
    private static final Duration REPORTING = Duration.ofMinutes(10);

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final HostQueryService hosts;
    private final IncidentService incidents;
    private final XpAccountConfigRepository configs;
    private final NarrativeBuilder narrative;
    private final AttentionRanker ranker;
    private final HealthScoreService health;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public OverviewService(QueryScopeFactory scopes, ClickHouseExecutor executor, HostQueryService hosts, IncidentService incidents,
                           XpAccountConfigRepository configs, NarrativeBuilder narrative, AttentionRanker ranker, HealthScoreService health) {
        this.scopes = scopes;
        this.executor = executor;
        this.hosts = hosts;
        this.incidents = incidents;
        this.configs = configs;
        this.narrative = narrative;
        this.ranker = ranker;
        this.health = health;
    }

    public XpOverview overview(ClientUserDetails user, List<Long> accountIds, Instant from, Instant to) {
        QueryScope range = scopes.build(user, accountIds, from, to);
        return overview(range);
    }

    /** For callers that already hold an authorised scope. */
    public XpOverview overview(QueryScope range) {
        Instant now = Instant.now();
        QueryScope live = new QueryScope(range.accountIds(), now.minus(LIVE), now, List.of(), List.of(), List.of(), List.of());
        List<XpHostRow> rows = hosts.hosts(live, now);

        int total = rows.size();
        int reporting = (int) rows.stream().filter(h -> h.score() != null && h.lastSeenAt() != null
                && Duration.between(h.lastSeenAt(), now).compareTo(REPORTING) <= 0).count();
        int crit = (int) rows.stream().filter(h -> h.health() == XpHealth.CRIT).count();
        int warn = (int) rows.stream().filter(h -> h.health() == XpHealth.WARN).count();

        double minutes = Math.max(1, Duration.between(range.from(), range.to()).toMinutes());
        long errors = 0;
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(range, builder::errorVolume)) {
            errors += tr.rows().stream().mapToLong(r -> Rows.l(r, "errors")).sum();
        }
        QueryScope day = range.withRange(now.minus(Duration.ofHours(24)), now);
        long bytes24h = 0;
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(day, builder::usageByDay)) {
            bytes24h += tr.rows().stream().mapToLong(r -> Rows.l(r, "b")).sum();
        }
        Instant lastData = null;
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(range.withRange(now.minus(Duration.ofDays(7)), now), builder::lastDataAt)) {
            for (Map<String, Object> r : tr.rows()) {
                long ts = Rows.l(r, "last_ts");
                if (ts > 0 && (lastData == null || ts > lastData.getEpochSecond())) {
                    lastData = Instant.ofEpochSecond(ts);
                }
            }
        }
        int openIncidents = 0;
        List<XpIncident> active = new ArrayList<>();
        for (Long a : range.accountIds()) {
            openIncidents += (int) incidents.openCount(a);
            active.addAll(incidents.activeFor(a));
        }

        XpHostRow worst = rows.stream().filter(h -> h.health() == XpHealth.CRIT || h.health() == XpHealth.WARN)
                .min(Comparator.comparing(XpHostRow::score, Comparator.nullsLast(Comparator.naturalOrder()))).orElse(null);
        List<String> story = narrative.build(new NarrativeBuilder.Facts(total, reporting, crit, warn, errors / minutes,
                openIncidents, worst == null ? null : worst.instanceId(), worst == null ? null : worst.reason()));

        List<Integer> scores = rows.stream().map(XpHostRow::score).filter(s -> s != null).toList();
        Integer healthScore = scores.isEmpty() ? null : (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));

        List<XpHeatTile> tiles = rows.stream().map(h -> new XpHeatTile(h.accountId(), h.instanceId(), h.name(), h.health(),
                h.score(), h.cpuPct(), h.memPct())).toList();

        return new XpOverview(rows.stream().anyMatch(h -> h.instanceId().startsWith("i-demo")), accountState(range.accountIds()), lastData,
                healthScore, new XpKpis(reporting, total, errors / minutes, bytes24h, openIncidents,
                total == 0 ? 0 : reporting * 100.0 / total),
                story, attention(rows, active, now), tiles);
    }

    private List<XpAttentionItem> attention(List<XpHostRow> rows, List<XpIncident> active, Instant now) {
        List<AttentionRanker.Candidate> candidates = new ArrayList<>();
        Map<String, XpAttentionItem> items = new java.util.HashMap<>();
        for (XpIncident i : active) {
            String id = "incident:" + i.id();
            candidates.add(new AttentionRanker.Candidate(id, i.severity(), "HIGH", Math.max(1, i.instanceIds().size()), i.openedAt(), false, false));
            items.put(id, new XpAttentionItem(id, i.severity(), i.title(), i.summary() == null ? "" : i.summary(), i.accountId(),
                    i.instanceIds().isEmpty() ? null : i.instanceIds().get(0), i.openedAt(), "HIGH",
                    List.of(new XpEvidence("ALERT", "Open incident", "/xampulse/alerts?incident=" + i.id()))));
        }
        List<String> covered = active.stream().flatMap(i -> i.instanceIds().stream()).toList();
        for (XpHostRow h : rows) {
            if ((h.health() != XpHealth.CRIT && h.health() != XpHealth.WARN) || covered.contains(h.instanceId())) {
                continue;
            }
            String id = "host:" + h.accountId() + ":" + h.instanceId();
            String sev = h.health() == XpHealth.CRIT ? "HIGH" : "MEDIUM";
            // We do not track when a host became unhealthy, so the start is "now" (ranked as a one-minute-old item).
            candidates.add(new AttentionRanker.Candidate(id, sev, "HIGH", 1, now, false, false));
            items.put(id, new XpAttentionItem(id, sev, h.instanceId() + " needs attention", h.reason() == null ? "" : h.reason(),
                    h.accountId(), h.instanceId(), now, "HIGH",
                    List.of(new XpEvidence("METRIC", "Host health", "/xampulse/hosts/" + h.instanceId() + "?accountId=" + h.accountId()))));
        }
        return ranker.rank(candidates, now, 20).stream().map(r -> items.get(r.candidate().id())).toList();
    }

    private XpAccountState accountState(List<Long> accountIds) {
        XpAccountState result = XpAccountState.CONNECTED;
        boolean any = false;
        for (XpAccountConfig c : configs.findByCloudAccountIdIn(accountIds)) {
            any = true;
            XpAccountState s = parse(c.getState());
            if (s != XpAccountState.CONNECTED) {
                result = s;
            }
        }
        return any ? result : XpAccountState.NOT_SET_UP;
    }

    static XpAccountState parse(String s) {
        try {
            return XpAccountState.valueOf(s);
        } catch (RuntimeException e) {
            return XpAccountState.NOT_SET_UP;
        }
    }

    public XpHealthScore healthScore(ClientUserDetails user, Long accountId) {
        Instant now = Instant.now();
        QueryScope live = scopes.build(user, List.of(accountId), now.minus(LIVE), now);
        List<XpHostRow> rows = hosts.hosts(live, now);
        List<Integer> scores = rows.stream().map(XpHostRow::score).filter(s -> s != null).toList();
        Integer score = scores.isEmpty() ? null : (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));
        List<String> why = new ArrayList<>();
        if (scores.isEmpty()) {
            why.add("No server has reported data yet, so there is no score.");
        } else {
            why.add(scores.size() + " of " + rows.size() + " servers contribute to this score.");
            rows.stream().filter(h -> h.reason() != null).limit(3).forEach(h -> why.add(h.instanceId() + ": " + h.reason()));
        }
        // Lanes: worst state seen for each lane across the fleet.
        List<XpLane> lanes = new ArrayList<>();
        for (String lane : List.of("CPU", "MEMORY", "DISK", "ERRORS", "FRESHNESS")) {
            XpHealth worst = XpHealth.NO_DATA;
            String reason = "No data";
            for (XpHostRow h : rows) {
                var res = health.evaluate(new HealthScoreService.Signals(h.cpuPct(), h.memPct(), h.diskPct(),
                        h.lastSeenAt() == null ? null : Math.max(0, now.getEpochSecond() - h.lastSeenAt().getEpochSecond()), null));
                for (XpLane l : res.lanes()) {
                    if (l.lane().equals(lane) && rank(l.health()) > rank(worst)) {
                        worst = l.health();
                        reason = h.instanceId() + ": " + l.reason();
                    }
                }
            }
            lanes.add(new XpLane(lane, worst, reason));
        }
        return new XpHealthScore(score, lanes, now, why);
    }

    private static int rank(XpHealth h) {
        return switch (h) {
            case CRIT -> 3;
            case WARN -> 2;
            case OK -> 1;
            case NO_DATA -> 0;
        };
    }
}
