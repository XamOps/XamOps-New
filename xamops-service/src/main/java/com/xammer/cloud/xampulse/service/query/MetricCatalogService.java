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

/**
 * Canonical metrics (always listed) plus any other metric the account has actually reported in the last 7 days.
 *
 * <p>Every canonical name is one the agent's collector really emits (hostmetrics and docker_stats, contrib v0.164.1),
 * with the receiver's unit and kind: COUNTER means a monotonic cumulative sum, so "rate" is the meaningful
 * aggregation. process.* metrics are not listed: they live only in the raw points (V011) and are shown by Top
 * processes, not the metric explorer.
 */
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
            e("system.network.packets", "{packets}", "COUNTER", "direction", "device"),
            e("container.cpu.utilization", "%", "GAUGE", "container.name"),
            e("container.memory.usage.total", "By", "GAUGE", "container.name"),
            e("container.memory.usage.limit", "By", "GAUGE", "container.name"),
            e("container.network.io.usage.rx_bytes", "By", "COUNTER", "container.name", "interface"),
            e("container.network.io.usage.tx_bytes", "By", "COUNTER", "container.name", "interface"),
            e("container.cpu.throttling_data.throttled_periods", "{periods}", "COUNTER", "container.name"),
            e("container.restarts", "{restarts}", "COUNTER", "container.name"),
            e("system.network.conntrack.count", "{entries}", "GAUGE"),
            e("system.network.conntrack.max", "{entries}", "GAUGE"),
            // xampulse_ec2 receiver (agent): rate of stall_time = share of time stalled; any allowance increase = AWS limited it.
            e("xp.pressure.stall_time", "s", "COUNTER", "resource", "level"),
            e("xp.ena.allowance_exceeded", "{packets}", "COUNTER", "allowance", "device"),
            e("xp.ena.conntrack_allowance_available", "{connections}", "GAUGE", "device"),
            e("xp.ec2.spot.interruption", "1", "GAUGE"),
            e("xp.ec2.rebalance_recommendation", "1", "GAUGE"));

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
