package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpContainerRow;
import com.xammer.cloud.xampulse.dto.XpPage;
import com.xammer.cloud.xampulse.exception.XamPulseException;
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

/**
 * Docker containers, built from the latest container.* metrics (docker_stats receiver). Docker only in 1.0.
 *
 * <p>Metric names and units are the receiver's (contrib v0.164.1): {@code container.cpu.utilization} is already a
 * percent of one CPU ({@code cpuDelta / systemDelta * onlineCPUs * 100}) and can exceed 100 on multi-core hosts;
 * memory is {@code container.memory.usage.total} and {@code .limit} in bytes; {@code container.restarts} is the
 * restart count. Container identity comes from resource attributes, which V011 copies into the labels.
 */
@Service("xpContainerQueryService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ContainerQueryService {

    static final String CPU = "container.cpu.utilization";
    static final String MEM = "container.memory.usage.total";
    static final String MEM_LIMIT = "container.memory.usage.limit";
    static final String RESTARTS = "container.restarts";
    private static final List<String> METRICS = List.of(CPU, MEM, MEM_LIMIT, RESTARTS);
    private static final List<String> SORTS = List.of("name", "cpuPct", "memBytes", "restarts", "lastSeenAt");

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public ContainerQueryService(QueryScopeFactory scopes, ClickHouseExecutor executor) {
        this.scopes = scopes;
        this.executor = executor;
    }

    public XpPage<XpContainerRow> list(ClientUserDetails user, List<Long> accountIds, String q, int page, int size, String sort) {
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
                switch (Rows.s(r, "metric")) {
                    case CPU -> a.cpu = v == null ? null : Math.max(0, v);
                    case MEM -> a.mem = v;
                    case MEM_LIMIT -> a.memLimit = v == null || v <= 0 ? null : v;
                    case RESTARTS -> a.restarts = v == null ? null : Math.round(v);
                    default -> { }
                }
            }
        }
        Map<String, Double> hostMem = HostMemory.totals(executor, builder, scope, since);
        Map<String, Long> errors = new HashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, builder::containerErrors)) {
            for (Map<String, Object> r : tr.rows()) {
                errors.put(tr.tenantId() + "/" + Rows.s(r, "instance_id") + "/" + Rows.s(r, "container_id"), Rows.l(r, "errors"));
            }
        }
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<XpContainerRow> rows = new ArrayList<>();
        for (Acc a : byKey.values()) {
            if (needle.isEmpty() || (a.name + " " + a.image + " " + a.id).toLowerCase(Locale.ROOT).contains(needle)) {
                rows.add(new XpContainerRow(a.tenant, a.instance, a.id, a.name, a.image, a.cpu, a.mem, a.memLimit, a.restarts,
                        Instant.ofEpochSecond(a.lastTs), HostMemory.isHostLimit(a.memLimit, hostMem.get(a.tenant + "/" + a.instance)),
                        errors.getOrDefault(a.tenant + "/" + a.instance + "/" + a.id, 0L)));
            }
        }
        rows.sort(comparator(sort));
        int p = Math.max(0, page);
        int sz = Math.max(1, Math.min(size <= 0 ? 50 : size, 200));
        int from = Math.min(rows.size(), p * sz);
        return new XpPage<>(new ArrayList<>(rows.subList(from, Math.min(rows.size(), from + sz))), p, sz, rows.size());
    }

    private static Comparator<XpContainerRow> comparator(String sort) {
        String value = sort == null || sort.isBlank() ? "-restarts" : sort;
        boolean desc = value.startsWith("-");
        String key = desc ? value.substring(1) : value;
        if (!SORTS.contains(key)) {
            throw XamPulseException.invalid("Unknown sort field.");
        }
        Comparator<XpContainerRow> comparator = switch (key) {
            case "name" -> desc
                    ? Comparator.comparing(XpContainerRow::name, String.CASE_INSENSITIVE_ORDER.reversed())
                    : Comparator.comparing(XpContainerRow::name, String.CASE_INSENSITIVE_ORDER);
            case "cpuPct" -> Comparator.comparing(XpContainerRow::cpuPct, valueOrder(desc));
            case "memBytes" -> Comparator.comparing(XpContainerRow::memBytes, valueOrder(desc));
            case "restarts" -> Comparator.comparing(XpContainerRow::restarts, valueOrder(desc));
            default -> Comparator.comparing(XpContainerRow::lastSeenAt, valueOrder(desc));
        };
        return comparator.thenComparing(XpContainerRow::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(XpContainerRow::containerId);
    }

    private static <T extends Comparable<? super T>> Comparator<T> valueOrder(boolean desc) {
        Comparator<T> values = Comparator.naturalOrder();
        return Comparator.nullsLast(desc ? values.reversed() : values);
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
        Double memLimit;
        Long restarts;
        long lastTs;

        Acc(long tenant, String instance, String id) {
            this.tenant = tenant;
            this.instance = instance;
            this.id = id;
        }
    }
}
