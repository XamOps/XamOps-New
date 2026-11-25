package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpProcessRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top processes for one host (Resource 360). Process metrics ({@code process.cpu.utilization},
 * {@code process.memory.utilization}) exist only at collection level T2, so when a host runs below T2 this returns
 * an empty list rather than zeros. The two metrics are joined per pid, ordered by CPU descending and capped so the
 * drawer never renders an unbounded table.
 */
@Service("xpProcessQueryService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ProcessQueryService {

    /** Hard cap on rows returned; the drawer shows the top 10 of these. */
    static final int MAX_ROWS = 50;

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public ProcessQueryService(QueryScopeFactory scopes, ClickHouseExecutor executor) {
        this.scopes = scopes;
        this.executor = executor;
    }

    /**
     * @param at the moment to look at; {@code null} means "now" (latest sample).
     */
    public List<XpProcessRow> topProcesses(ClientUserDetails user, Long accountId, String instanceId, Instant at) {
        Instant moment = at == null ? Instant.now() : at;
        // Authorise the account through the same gate as every other read. A one-minute window keeps the scope valid.
        scopes.build(user, List.of(accountId), moment.minusSeconds(60), moment.plusSeconds(60));
        QueryScope scope = new QueryScope(List.of(accountId), moment.minusSeconds(60), moment.plusSeconds(60),
                List.of(), List.of(instanceId), List.of(), List.of());
        long atEpochSec = moment.getEpochSecond();

        Map<String, Agg> byPid = new LinkedHashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.processLatest(s, instanceId, atEpochSec))) {
            for (Map<String, Object> r : tr.rows()) {
                String pid = Rows.s(r, "pid");
                if (pid.isEmpty()) {
                    continue;
                }
                Agg a = byPid.computeIfAbsent(pid, k -> new Agg());
                a.accept(Rows.s(r, "metric"), r);
            }
        }

        List<XpProcessRow> rows = new ArrayList<>();
        for (Map.Entry<String, Agg> e : byPid.entrySet()) {
            Long pid = parseLong(e.getKey());
            if (pid == null) {
                continue;
            }
            Agg a = e.getValue();
            rows.add(new XpProcessRow(pid, a.name == null || a.name.isEmpty() ? e.getKey() : a.name,
                    a.user == null || a.user.isEmpty() ? null : a.user,
                    a.cpuPct == null ? 0.0 : a.cpuPct, a.memPct == null ? 0.0 : a.memPct,
                    null, a.threads));
        }
        rows.sort(Comparator.comparingDouble(XpProcessRow::cpuPct).reversed());
        return rows.size() > MAX_ROWS ? new ArrayList<>(rows.subList(0, MAX_ROWS)) : rows;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accumulates the two metric rows that share a pid into a single process row. */
    private static final class Agg {
        String name;
        String user;
        Integer threads;
        Double cpuPct;
        Double memPct;

        void accept(String metric, Map<String, Object> r) {
            if (name == null) {
                String n = Rows.s(r, "name");
                name = n.isEmpty() ? null : n;
            }
            if (user == null) {
                String u = Rows.s(r, "user");
                user = u.isEmpty() ? null : u;
            }
            if (threads == null) {
                String t = Rows.s(r, "threads");
                if (!t.isEmpty()) {
                    try {
                        threads = (int) Double.parseDouble(t);
                    } catch (NumberFormatException ignored) {
                        // leave null
                    }
                }
            }
            Double v = Rows.dOrNull(r, "v");
            if (v == null) {
                return;
            }
            // process.cpu.utilization is a fraction in [0,1] of the host's CPU (user + system + wait, summed per process
            // in the query); process.memory.utilization is already a percentage (gopsutil MemoryPercent, 0..100), so it
            // must not be multiplied again (it was: 3% showed as 300%).
            if ("process.cpu.utilization".equals(metric)) {
                cpuPct = v * 100.0;
            } else if ("process.memory.utilization".equals(metric)) {
                memPct = v;
            }
        }
    }
}
