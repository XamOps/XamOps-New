package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpChange;
import com.xammer.cloud.xampulse.dto.XpContainerSummary;
import com.xammer.cloud.xampulse.dto.XpContainerSummary.Version;
import com.xammer.cloud.xampulse.dto.XpHostLimits.Finding;
import com.xammer.cloud.xampulse.dto.XpLogRow;
import com.xammer.cloud.xampulse.dto.XpLogSearchRequest;
import com.xammer.cloud.xampulse.dto.XpResourceFilter;
import com.xammer.cloud.xampulse.dto.XpScope;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.insight.ChangeIntelligenceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Container 360: one container, by name on one host, through every redeploy in the window (Docker gives a new id to
 * each new container, the name stays). Metrics are docker_stats' (contrib v0.164.1, names and units documented on
 * {@link ContainerQueryService}); identity comes from the labels V011 copies from the resource attributes.
 */
@Service("xpContainerDetailService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ContainerDetailService {

    /** Docker container names: a letter or digit, then letters, digits, '_', '.' or '-'. */
    public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");
    static final double THROTTLED_WARN = 0.20, THROTTLED_CRIT = 0.50, MIN_PERIODS = 100;
    static final double MEMORY_NEAR = 0.90, MEMORY_AT_LIMIT = 0.95;
    static final int RESTART_LOOP = 3;

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final LogQueryService logs;
    private final ChangeIntelligenceService changes;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public ContainerDetailService(QueryScopeFactory scopes, ClickHouseExecutor executor, LogQueryService logs, ChangeIntelligenceService changes) {
        this.scopes = scopes;
        this.executor = executor;
        this.logs = logs;
        this.changes = changes;
    }

    public XpContainerSummary summary(ClientUserDetails user, Long accountId, String instanceId, String name, Instant from, Instant to) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw XamPulseException.notFound();
        }
        scopes.build(user, List.of(accountId), from, to); // authorises the account like every other read
        QueryScope scope = new QueryScope(List.of(accountId), from, to, List.of(), List.of(instanceId), List.of(), List.of());
        List<MetricSpec.LabelFilter> byName = List.of(new MetricSpec.LabelFilter("container.name", "=", name));

        QueryScope withHistory = new QueryScope(List.of(accountId), from.minus(Duration.ofHours(24)), to, List.of(), List.of(instanceId), List.of(), List.of());
        List<Version> versions = new ArrayList<>();
        for (Map<String, Object> r : executor.query(builder.seriesSpans(withHistory, "container.cpu.utilization", byName))) {
            Map<String, String> l = ContainerQueryService.labels(Rows.s(r, "labels_key"));
            if (l.containsKey("container.id")) {
                versions.add(new Version(l.get("container.id"), l.getOrDefault("container.image.name", ""),
                        Instant.ofEpochSecond(Rows.l(r, "first_ts")), Instant.ofEpochSecond(Rows.l(r, "last_ts") + 59)));
            }
        }
        if (versions.isEmpty()) {
            throw XamPulseException.notFound();
        }
        versions.sort((a, b) -> a.firstSeen().compareTo(b.firstSeen()));
        Version current = versions.get(versions.size() - 1);

        TenantScopedQueryBuilder.MetricPlan plan = TenantScopedQueryBuilder.planMetric(scope, null);
        Map<String, List<double[]>> series = new LinkedHashMap<>();
        put(series, "cpu", series(scope, plan, new MetricSpec("container.cpu.utilization", "avg", List.of(), byName)));
        // Seconds since the container started (the UI reads the latest point), and the host's total memory as one point,
        // so the page can say "no memory limit" when Docker reports the host's memory as the limit.
        put(series, "uptime", series(scope, plan, new MetricSpec("container.uptime", "max", List.of(), byName)));
        Double hostBytes = HostMemory.totals(executor, builder, scope, Instant.now().minus(Duration.ofMinutes(15)).getEpochSecond())
                .get(accountId + "/" + instanceId);
        if (hostBytes != null) {
            put(series, "host_memory", new TreeMap<>(Map.of(to.toEpochMilli(), hostBytes)));
        }
        put(series, "memory", series(scope, plan, new MetricSpec("container.memory.usage.total", "avg", List.of(), byName)));
        put(series, "memory_limit", series(scope, plan, new MetricSpec("container.memory.usage.limit", "max", List.of(), byName)));
        put(series, "network_rx", series(scope, plan, new MetricSpec("container.network.io.usage.rx_bytes", "rate", List.of(), byName)));
        put(series, "network_tx", series(scope, plan, new MetricSpec("container.network.io.usage.tx_bytes", "rate", List.of(), byName)));
        put(series, "disk_read", series(scope, plan, new MetricSpec("container.blockio.io_service_bytes_recursive", "rate", List.of(),
                List.of(byName.get(0), new MetricSpec.LabelFilter("operation", "=~", "(?i)read")))));
        put(series, "disk_write", series(scope, plan, new MetricSpec("container.blockio.io_service_bytes_recursive", "rate", List.of(),
                List.of(byName.get(0), new MetricSpec.LabelFilter("operation", "=~", "(?i)write")))));
        TreeMap<Long, Double> periods = series(scope, plan, new MetricSpec("container.cpu.throttling_data.periods", "rate", List.of(), byName));
        TreeMap<Long, Double> throttled = series(scope, plan, new MetricSpec("container.cpu.throttling_data.throttled_periods", "rate", List.of(), byName));
        TreeMap<Long, Double> throttledPct = new TreeMap<>();
        periods.forEach((t, p) -> {
            Double th = throttled.get(t);
            if (th != null && p > 0) {
                throttledPct.put(t, Math.min(100, th / p * 100));
            }
        });
        put(series, "throttled_pct", throttledPct);

        // Findings over the whole window, from exact counts (not the plotted averages).
        Map<String, Double> periodsTotal = totals(scope, "container.cpu.throttling_data.periods", byName);
        Map<String, Double> throttledTotal = totals(scope, "container.cpu.throttling_data.throttled_periods", byName);
        List<Long> restartsAt = new ArrayList<>();
        long restartCount = 0;
        for (Map<String, Object> r : executor.query(builder.counterSteps(scope, "container.restarts", byName, Long.MAX_VALUE / 4))) {
            long inc = Math.round(Rows.d(r, "inc"));
            if (inc > 0) {
                restartCount += inc;
                restartsAt.add(Rows.l(r, "t"));
            }
        }
        TreeMap<Long, Double> mem = series.containsKey("memory") ? toMap(series.get("memory")) : new TreeMap<>();
        TreeMap<Long, Double> limit = series.containsKey("memory_limit") ? toMap(series.get("memory_limit")) : new TreeMap<>();
        boolean limitIsHost = Boolean.TRUE.equals(HostMemory.isHostLimit(limit.isEmpty() ? null : limit.lastEntry().getValue(), hostBytes));
        List<Finding> findings = findings(name, periodsTotal.getOrDefault(name, 0.0), throttledTotal.getOrDefault(name, 0.0),
                mem, limit, restartsAt, restartCount, plan.stepSec() * 1000L, limitIsHost);

        List<XpChange> containerChanges = changes.changesInScope(accountId, instanceId, name, from, to, Instant.now());

        List<String> ids = versions.stream().map(Version::containerId).distinct().toList();
        List<XpLogRow> errors = logs.search(user, new XpLogSearchRequest(
                new XpScope(List.of(accountId), null, from, to, new XpResourceFilter(List.of(instanceId), null, ids)),
                "severity:>=warn", "TS_DESC", 30, null)).rows();

        Double memLimit = limit.isEmpty() ? null : limit.lastEntry().getValue();
        return new XpContainerSummary(instanceId, name, current.containerId(), current.image(), versions.get(0).firstSeen(), current.lastSeen(),
                memLimit, restartCount, List.copyOf(versions), series, findings, containerChanges, errors);
    }

    private Map<String, Double> totals(QueryScope scope, String metric, List<MetricSpec.LabelFilter> filters) {
        Map<String, Double> out = new HashMap<>();
        for (Map<String, Object> r : executor.query(builder.counterIncrease(scope, metric, filters, "container.name"))) {
            out.put(Rows.s(r, "g0"), Rows.d(r, "inc"));
        }
        return out;
    }

    private TreeMap<Long, Double> series(QueryScope scope, TenantScopedQueryBuilder.MetricPlan plan, MetricSpec spec) {
        TreeMap<Long, Double> out = new TreeMap<>();
        for (Map<String, Object> r : executor.query(builder.metricSeries(scope, spec, plan))) {
            Double v = Rows.dOrNull(r, "v");
            if (v != null) {
                out.put(Rows.l(r, "t") * 1000L, v);
            }
        }
        return out;
    }

    private static void put(Map<String, List<double[]>> series, String key, TreeMap<Long, Double> points) {
        if (points.isEmpty()) {
            return;
        }
        List<double[]> list = new ArrayList<>(points.size());
        points.forEach((t, v) -> list.add(new double[] {t, v}));
        series.put(key, list);
    }

    private static TreeMap<Long, Double> toMap(List<double[]> pts) {
        TreeMap<Long, Double> m = new TreeMap<>();
        pts.forEach(p -> m.put((long) p[0], p[1]));
        return m;
    }

    /**
     * Pure. Throttling from exact period counts; memory against the limit from the plotted buckets; "likely OOM
     * kill" only when a restart came within two buckets of memory at 95% of the limit (Docker's stats do not say
     * why a container exited, so this is labelled likely, with the numbers).
     */
    static List<Finding> findings(String name, double periods, double throttled, TreeMap<Long, Double> mem, TreeMap<Long, Double> limit,
                                  List<Long> restartsAt, long restartCount, long stepMs) {
        return findings(name, periods, throttled, mem, limit, restartsAt, restartCount, stepMs, false);
    }

    /**
     * {@code limitIsHost}: Docker reported the host's memory as the limit, i.e. no limit is set. The memory findings
     * then speak about the host's memory, and the fix is to set a limit, not to raise one.
     */
    static List<Finding> findings(String name, double periods, double throttled, TreeMap<Long, Double> mem, TreeMap<Long, Double> limit,
                                  List<Long> restartsAt, long restartCount, long stepMs, boolean limitIsHost) {
        List<Finding> out = new ArrayList<>();
        if (periods >= MIN_PERIODS && throttled / periods >= THROTTLED_WARN) {
            double ratio = throttled / periods;
            out.add(new Finding(ratio >= THROTTLED_CRIT ? "CRITICAL" : "WARNING", "container_throttled", "CONTAINER",
                    String.format(Locale.ROOT, "CPU-throttled in %.0f%% of its scheduling periods", ratio * 100),
                    "The container hit its CPU limit; requests were delayed even though the host may have idle CPU.",
                    "Raise the container's CPU limit (--cpus / cpu quota), or reduce its CPU use.",
                    Map.of("throttled_ratio", ratio, "periods", periods, "throttled_periods", throttled)));
        }
        double peakShare = 0;
        long peakAt = 0;
        for (Map.Entry<Long, Double> e : mem.entrySet()) {
            Double l = limit.floorEntry(e.getKey()) == null ? null : limit.floorEntry(e.getKey()).getValue();
            if (l != null && l > 0 && e.getValue() / l > peakShare) {
                peakShare = e.getValue() / l;
                peakAt = e.getKey();
            }
        }
        boolean oom = false;
        for (long restartSec : restartsAt) {
            long restartMs = restartSec * 1000L;
            for (Map.Entry<Long, Double> e : mem.subMap(restartMs - 2 * stepMs, true, restartMs, true).entrySet()) {
                Map.Entry<Long, Double> l = limit.floorEntry(e.getKey());
                if (l != null && l.getValue() > 0 && e.getValue() / l.getValue() >= MEMORY_AT_LIMIT) {
                    oom = true;
                    break;
                }
            }
        }
        if (oom) {
            out.add(limitIsHost
                    ? new Finding("CRITICAL", "container_oom", "CONTAINER", "Restarted right after the host ran out of memory (likely an OOM kill)",
                            "The container has no memory limit and was using " + Math.round(peakShare * 100) + "% of the host's memory just before Docker restarted it.",
                            "Set a memory limit (--memory) so one container cannot starve the host, and find what grows between restarts.",
                            Map.of("peak_share", peakShare, "restarts", (double) restartCount))
                    : new Finding("CRITICAL", "container_oom", "CONTAINER", "Restarted right after memory reached its limit (likely an OOM kill)",
                            "Memory was at " + Math.round(peakShare * 100) + "% of the container's limit just before Docker restarted it.",
                            "Raise the memory limit or find what grows (a leak shows as memory climbing between restarts).",
                            Map.of("peak_share", peakShare, "restarts", (double) restartCount)));
        } else if (peakShare >= MEMORY_NEAR) {
            out.add(limitIsHost
                    ? new Finding("WARNING", "container_memory_near_limit", "CONTAINER",
                            String.format(Locale.ROOT, "Using %.0f%% of the host's memory, with no limit set", peakShare * 100),
                            "Without a memory limit this container can push the whole host into the OOM killer, taking other processes with it.",
                            "Set a memory limit (--memory), or check whether memory keeps growing.", Map.of("peak_share", peakShare, "peak_at_ms", (double) peakAt))
                    : new Finding("WARNING", "container_memory_near_limit", "CONTAINER",
                            String.format(Locale.ROOT, "Memory reached %.0f%% of its limit", peakShare * 100),
                            "At the limit the kernel kills the container's process (OOM).",
                            "Raise the memory limit, or check whether memory keeps growing.", Map.of("peak_share", peakShare, "peak_at_ms", (double) peakAt)));
        }
        if (restartCount >= RESTART_LOOP) {
            out.add(new Finding("WARNING", "container_restart_loop", "CONTAINER", "Restarted " + restartCount + " times in this range",
                    "A container that keeps restarting is usually crashing at start or being killed.",
                    "Check its logs just before each restart (Logs below).", Map.of("restarts", (double) restartCount)));
        }
        return out;
    }
}
