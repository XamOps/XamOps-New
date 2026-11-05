package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpContainerRow;
import com.xammer.cloud.xampulse.dto.XpPage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Docker containers, built from the latest container.* metrics. Docker only in 1.0. */
@Service("xpContainerQueryService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ContainerQueryService {

    private static final List<String> METRICS = List.of("container.cpu.utilization", "container.memory.usage");

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public ContainerQueryService(QueryScopeFactory scopes, ClickHouseExecutor executor) {
        this.scopes = scopes;
        this.executor = executor;
    }

    public XpPage<XpContainerRow> list(ClientUserDetails user, List<Long> accountIds, String q, int page, int size) {
        Instant now = Instant.now();
        QueryScope scope = scopes.build(user, accountIds, now.minus(Duration.ofMinutes(15)), now);
        long since = scope.from().getEpochSecond();
        Map<String, Acc> byKey = new HashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.latestMetrics(s, since, METRICS))) {
            for (Map<String, Object> r : tr.rows()) {
                Map<String, String> labels = labels(Rows.s(r, "labels_key"));
                String id = labels.getOrDefault("container.id", labels.getOrDefault("container_id", ""));
                if (id.isEmpty()) {
                    continue;
                }
                Acc a = byKey.computeIfAbsent(tr.tenantId() + "/" + Rows.s(r, "instance_id") + "/" + id,
                        k -> new Acc(tr.tenantId(), Rows.s(r, "instance_id"), id));
                a.name = labels.getOrDefault("container.name", a.name);
                a.image = labels.getOrDefault("container.image.name", a.image);
                a.lastTs = Math.max(a.lastTs, Rows.l(r, "last_ts"));
                Double v = Rows.dOrNull(r, "v");
                if ("container.cpu.utilization".equals(Rows.s(r, "metric"))) {
                    a.cpu = v == null ? null : HostQueryService.clampPct(v * 100);
                } else {
                    a.mem = v;
                }
            }
        }
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<XpContainerRow> rows = new ArrayList<>();
        for (Acc a : byKey.values()) {
            if (needle.isEmpty() || (a.name + " " + a.image + " " + a.id).toLowerCase(Locale.ROOT).contains(needle)) {
                rows.add(new XpContainerRow(a.tenant, a.instance, a.id, a.name, a.image, a.cpu, a.mem, Instant.ofEpochSecond(a.lastTs)));
            }
        }
        rows.sort(Comparator.comparing(XpContainerRow::name, String.CASE_INSENSITIVE_ORDER));
        int p = Math.max(0, page);
        int sz = Math.max(1, Math.min(size <= 0 ? 50 : size, 200));
        int from = Math.min(rows.size(), p * sz);
        return new XpPage<>(new ArrayList<>(rows.subList(from, Math.min(rows.size(), from + sz))), p, sz, rows.size());
    }

    static Map<String, String> labels(String labelsKey) {
        Map<String, String> m = new HashMap<>();
        for (String kv : labelsKey.split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                m.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return m;
    }

    private static final class Acc {
        final long tenant;
        final String instance;
        final String id;
        String name = "";
        String image = "";
        Double cpu;
        Double mem;
        long lastTs;

        Acc(long tenant, String instance, String id) {
            this.tenant = tenant;
            this.instance = instance;
            this.id = id;
        }
    }
}
