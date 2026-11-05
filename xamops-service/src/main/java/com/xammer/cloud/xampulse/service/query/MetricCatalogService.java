package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpMetricCatalogEntry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Canonical metrics (always listed) plus any other metric the account has actually reported in the last 7 days. */
@Service("xpMetricCatalogService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class MetricCatalogService {

    static final List<XpMetricCatalogEntry> CANONICAL = List.of(
            e("system.cpu.utilization", "1", "GAUGE", "state"),
            e("system.cpu.load_average.1m", "1", "GAUGE"),
            e("system.cpu.load_average.5m", "1", "GAUGE"),
            e("system.cpu.load_average.15m", "1", "GAUGE"),
            e("system.memory.utilization", "1", "GAUGE", "state"),
            e("system.paging.utilization", "1", "GAUGE"),
            e("system.filesystem.utilization", "1", "GAUGE", "mountpoint", "device"),
            e("system.filesystem.inodes.usage", "{inodes}", "GAUGE", "mountpoint"),
            e("system.disk.io", "By", "COUNTER", "direction", "device"),
            e("system.disk.operations", "{operations}", "COUNTER", "direction", "device"),
            e("system.network.io", "By", "COUNTER", "direction", "device"),
            e("system.network.errors", "{errors}", "COUNTER", "direction", "device"),
            e("system.network.dropped", "{packets}", "COUNTER", "direction", "device"),
            e("process.cpu.utilization", "1", "GAUGE"),
            e("process.memory.utilization", "1", "GAUGE"),
            e("container.cpu.utilization", "1", "GAUGE"),
            e("container.memory.usage", "By", "GAUGE"),
            e("container.network.io", "By", "COUNTER", "direction"),
            e("xp.agent.heartbeat", "1", "GAUGE"));

    private static XpMetricCatalogEntry e(String name, String unit, String kind, String... labelKeys) {
        return new XpMetricCatalogEntry(name, unit, kind, List.of(labelKeys));
    }

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public MetricCatalogService(QueryScopeFactory scopes, ClickHouseExecutor executor) {
        this.scopes = scopes;
        this.executor = executor;
    }

    public List<XpMetricCatalogEntry> catalog(ClientUserDetails user, Long accountId, String q) {
        Instant now = Instant.now();
        QueryScope scope = scopes.build(user, List.of(accountId), now.minus(Duration.ofDays(7)), now);
        Map<String, XpMetricCatalogEntry> merged = new LinkedHashMap<>();
        CANONICAL.forEach(c -> merged.put(c.name(), c));
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, builder::metricNames)) {
            for (Map<String, Object> r : tr.rows()) {
                String name = Rows.s(r, "metric");
                if (!merged.containsKey(name)) {
                    List<String> keys = new ArrayList<>();
                    for (String kv : Rows.s(r, "sample_labels").split(",")) {
                        int eq = kv.indexOf('=');
                        if (eq > 0) {
                            keys.add(kv.substring(0, eq));
                        }
                    }
                    merged.put(name, new XpMetricCatalogEntry(name, "", "GAUGE", keys));
                }
            }
        }
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return merged.values().stream().filter(m -> needle.isEmpty() || m.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }
}
