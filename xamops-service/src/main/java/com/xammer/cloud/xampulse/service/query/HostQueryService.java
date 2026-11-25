package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.alert.IncidentService;
import com.xammer.cloud.xampulse.service.insight.HealthScoreService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fleet and host views. The host roster comes from enrolled agents (Postgres); live numbers come from ClickHouse
 * rollups. A host with no recent data is reported with null values and NO_DATA health, never zeros.
 */
@Service("xpHostQueryService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class HostQueryService {

    static final List<String> HOST_METRICS = List.of("system.cpu.utilization", "system.memory.utilization",
            "system.cpu.load_average.1m", "system.filesystem.utilization");
    private static final Set<String> SORTS = Set.of("name", "cpu", "mem", "health", "lastSeen");
    private static final Duration LIVE_WINDOW = Duration.ofMinutes(15);

    /**
     * Whole block devices on EC2 (Nitro NVMe, Xen xvd, sd/vd). Partitions (nvme0n1p1, xvda1), loop, dm-* and md*
     * are left out because their bytes are already counted on the disk underneath.
     */
    static final String WHOLE_DISK = "nvme[0-9]+n[0-9]+|xvd[a-z]+|sd[a-z]+|vd[a-z]+";

    /**
     * Interfaces that carry the instance's own traffic in and out (ENA eth/ens/enp/eno, Xen enX). lo, docker0, br-*,
     * veth*, cni*, eni* and tunnels are left out: they are host-internal or a second view of bytes the NIC counts.
     */
    static final String PHYSICAL_NIC = "eth[0-9]+|ens[0-9]+|enp[0-9a-z]+|eno[0-9]+|enX[0-9]+";

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final XpAgentRepository agents;
    private final HealthScoreService health;
    private final IncidentService incidents;
    private final LogQueryService logs;
    private final EventQueryService events;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    /** Optional: EC2 Name tag and instance type for the host list. Without it hosts are named by instance id. */
    private com.xammer.cloud.xampulse.service.insight.TopologyService topology;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTopology(com.xammer.cloud.xampulse.service.insight.TopologyService topology) {
        this.topology = topology;
    }

    public HostQueryService(QueryScopeFactory scopes, ClickHouseExecutor executor, XpAgentRepository agents,
                            HealthScoreService health, IncidentService incidents, LogQueryService logs, EventQueryService events) {
        this.scopes = scopes;
        this.executor = executor;
        this.agents = agents;
        this.health = health;
        this.incidents = incidents;
        this.logs = logs;
        this.events = events;
    }

    public XpPage<XpHostRow> list(ClientUserDetails user, List<Long> accountIds, String q, String state, int page, int size, String sort) {
        Instant now = Instant.now();
        QueryScope scope = scopes.build(user, accountIds, now.minus(LIVE_WINDOW), now);
        List<XpHostRow> rows = new ArrayList<>(hosts(scope, now));
        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase(Locale.ROOT);
            rows.removeIf(h -> !h.instanceId().toLowerCase(Locale.ROOT).contains(needle)
                    && !h.name().toLowerCase(Locale.ROOT).contains(needle));
        }
        if (state != null && !state.isBlank()) {
            XpHealth wanted;
            try {
                wanted = XpHealth.valueOf(state.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw XamPulseException.invalid("Unknown state filter.");
            }
            rows.removeIf(h -> h.health() != wanted);
        }
        rows.sort(comparator(sort));
        int p = Math.max(0, page);
        int sz = Math.max(1, Math.min(size <= 0 ? 50 : size, 200));
        int fromIdx = Math.min(rows.size(), p * sz);
        int toIdx = Math.min(rows.size(), fromIdx + sz);
        return new XpPage<>(new ArrayList<>(rows.subList(fromIdx, toIdx)), p, sz, rows.size());
    }

    private static Comparator<XpHostRow> comparator(String sort) {
        String s = sort == null || sort.isBlank() ? "health" : sort;
        boolean desc = s.startsWith("-");
        String key = desc ? s.substring(1) : s;
        if (!SORTS.contains(key)) {
            throw XamPulseException.invalid("Unknown sort field.");
        }
        return switch (key) {
            case "name" -> desc
                    ? Comparator.comparing(XpHostRow::name, String.CASE_INSENSITIVE_ORDER.reversed())
                    : Comparator.comparing(XpHostRow::name, String.CASE_INSENSITIVE_ORDER);
            case "cpu" -> Comparator.comparing(XpHostRow::cpuPct, valueOrder(desc));
            case "mem" -> Comparator.comparing(XpHostRow::memPct, valueOrder(desc));
            case "lastSeen" -> Comparator.comparing(XpHostRow::lastSeenAt, valueOrder(desc));
            // Health order is operational severity, not the nullable score: CRIT, WARN, NO_DATA, OK.
            default -> {
                Comparator<XpHostRow> health = Comparator.comparingInt(row -> healthRank(row.health()));
                if (desc) {
                    health = health.reversed();
                }
                yield health.thenComparing(XpHostRow::score, valueOrder(desc));
            }
        };
    }

    private static <T extends Comparable<? super T>> Comparator<T> valueOrder(boolean desc) {
        Comparator<T> values = Comparator.naturalOrder();
        return Comparator.nullsLast(desc ? values.reversed() : values);
    }

    private static int healthRank(XpHealth value) {
        return switch (value) {
            case CRIT -> 0;
            case WARN -> 1;
            case NO_DATA -> 2;
            case OK -> 3;
        };
    }

    /** All hosts for already-authorised accounts. {@code scope} must be a short live window ending at {@code now}. */
    public List<XpHostRow> hosts(QueryScope scope, Instant now) {
        long since = scope.from().getEpochSecond();
        Map<Long, Map<String, Live>> live = new HashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.latestMetrics(s, since, HOST_METRICS))) {
            Map<String, Live> byInstance = live.computeIfAbsent(tr.tenantId(), k -> new HashMap<>());
            for (Map<String, Object> r : tr.rows()) {
                byInstance.computeIfAbsent(Rows.s(r, "instance_id"), k -> new Live()).accept(r);
            }
        }
        Map<Long, Map<String, Double>> errors = new HashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, builder::errorVolume)) {
            Map<String, Double> m = errors.computeIfAbsent(tr.tenantId(), k -> new HashMap<>());
            double minutes = Math.max(1, Duration.between(scope.from(), scope.to()).toMinutes());
            for (Map<String, Object> r : tr.rows()) {
                m.put(Rows.s(r, "instance_id"), Rows.l(r, "errors") / minutes);
            }
        }
        Map<Long, Map<String, List<Double>>> sparks = sparks(scope, now);
        Map<Long, Map<String, double[]>> net = networkRates(scope);

        List<XpHostRow> out = new ArrayList<>();
        for (Long tenant : scope.accountIds()) {
            List<XpAgent> tenantAgents = agents.findByCloudAccountId(tenant);
            // Name tag and instance type from the cached EC2 lookup (free, 10 minutes); the instance id when unknown.
            Map<String, com.xammer.cloud.xampulse.service.aws.Ec2FactsService.PeerKey> ec2 = topology == null ? Map.of()
                    : topology.keys(tenant, tenantAgents.stream().map(XpAgent::getInstanceId).toList());
            for (XpAgent a : tenantAgents) {
                var key = ec2.get(a.getInstanceId());
                String name = key != null && key.name() != null && !key.name().isBlank() ? key.name() : a.getInstanceId();
                String type = key == null ? null : key.instanceType();
                Live l = live.getOrDefault(tenant, Map.of()).get(a.getInstanceId());
                Long secondsSince = l == null || l.lastTs == 0 ? null : Math.max(0, now.getEpochSecond() - l.lastTs);
                Double errPerMin = errors.getOrDefault(tenant, Map.of()).get(a.getInstanceId());
                HealthScoreService.Result res = health.evaluate(new HealthScoreService.Signals(
                        l == null ? null : l.cpuPct(), l == null ? null : l.memPct(), l == null ? null : l.diskPct(),
                        secondsSince, l == null ? null : (errPerMin == null ? 0.0 : errPerMin)));
                Instant lastSeen = l != null && l.lastTs > 0 ? Instant.ofEpochSecond(l.lastTs) : a.getLastSeenAt();
                double[] rates = net.getOrDefault(tenant, Map.of()).get(a.getInstanceId());
                // State and tags come from the same cached DescribeInstances; when EC2 cannot be read they stay
                // empty (state omitted) rather than being guessed.
                out.add(new XpHostRow(tenant, a.getInstanceId(), name, a.getRegion(),
                        a.getOs() == null ? "linux" : a.getOs().toLowerCase(Locale.ROOT), type, key == null ? null : key.state(),
                        res.health(), res.score(), res.reason(),
                        l == null ? null : l.cpuPct(), l == null ? null : l.memPct(), l == null ? null : l.diskPct(),
                        rates == null || Double.isNaN(rates[0]) ? null : rates[0], rates == null || Double.isNaN(rates[1]) ? null : rates[1],
                        l == null ? null : l.load1,
                        sparks.getOrDefault(tenant, Map.of()).getOrDefault(a.getInstanceId(), List.of()),
                        lastSeen, agentStatus(a, now), a.getCollectionLevel(),
                        key == null || key.tags() == null ? Map.of() : key.tags()));
            }
        }
        return out;
    }

    static String agentStatus(XpAgent a, Instant now) {
        String st = a.getState() == null ? "NONE" : a.getState();
        if ("ACTIVE".equals(st) && (a.getLastSeenAt() == null || Duration.between(a.getLastSeenAt(), now).toMinutes() > 10)) {
            return "STALE";
        }
        return switch (st) {
            case "ACTIVE", "PENDING", "STALE", "REVOKED" -> st;
            default -> "NONE";
        };
    }

    /**
     * Latest network rate per host, bytes/s {in, out}, over the live window. system.network.io is a cumulative counter
     * per device, so this is the same counter-rate query the host page charts use (resets dropped), on the instance's
     * real NICs only (lo, docker0, veth* never left the host). NaN for a direction with no rate yet; the host is
     * absent when nothing was measured, so the list says "no data" instead of 0 B/s.
     */
    Map<Long, Map<String, double[]>> networkRates(QueryScope live) {
        MetricSpec spec = new MetricSpec("system.network.io", "rate", List.of("instance_id", "direction"),
                List.of(new MetricSpec.LabelFilter("device", "=~", PHYSICAL_NIC)));
        TenantScopedQueryBuilder.MetricPlan plan = TenantScopedQueryBuilder.planMetric(live, 60);
        Map<Long, Map<String, double[]>> out = new HashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(live, s -> builder.metricSeries(s, spec, plan))) {
            Map<String, double[]> m = out.computeIfAbsent(tr.tenantId(), k -> new HashMap<>());
            Map<String, Long> lastT = new HashMap<>();
            for (Map<String, Object> r : tr.rows()) {
                Double v = Rows.dOrNull(r, "v");
                String instance = Rows.s(r, "g0");
                String dir = Rows.s(r, "g1");
                int idx = "receive".equals(dir) ? 0 : "transmit".equals(dir) ? 1 : -1;
                long t = Rows.l(r, "t");
                if (v == null || idx < 0 || instance == null) {
                    continue;
                }
                String k = instance + "|" + idx;
                if (lastT.getOrDefault(k, Long.MIN_VALUE) < t) {
                    lastT.put(k, t);
                    m.computeIfAbsent(instance, x -> new double[] {Double.NaN, Double.NaN})[idx] = Math.max(0, v);
                }
            }
        }
        return out;
    }

    private Map<Long, Map<String, List<Double>>> sparks(QueryScope live, Instant now) {
        QueryScope hour = live.withRange(now.minus(Duration.ofHours(1)), now);
        MetricSpec idle = new MetricSpec("system.cpu.utilization", "avg", List.of("instance_id"),
                List.of(new MetricSpec.LabelFilter("state", "=", "idle")));
        TenantScopedQueryBuilder.MetricPlan plan = TenantScopedQueryBuilder.planMetric(hour, 300);
        Map<Long, Map<String, List<Double>>> out = new HashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(hour, s -> builder.metricSeries(s, idle, plan))) {
            Map<String, List<Double>> m = out.computeIfAbsent(tr.tenantId(), k -> new HashMap<>());
            for (Map<String, Object> r : tr.rows()) {
                Double v = Rows.dOrNull(r, "v");
                if (v != null) {
                    m.computeIfAbsent(Rows.s(r, "g0"), k -> new ArrayList<>()).add(clampPct((1 - v) * 100));
                }
            }
        }
        return out;
    }

    public XpHostSummary summary(ClientUserDetails user, Long accountId, String instanceId, Instant from, Instant to) {
        QueryScope range = scopes.build(user, List.of(accountId), from, to);
        Instant now = Instant.now();
        QueryScope narrowLive = new QueryScope(List.of(accountId), now.minus(LIVE_WINDOW), now, List.of(), List.of(instanceId), List.of(), List.of());
        XpHostRow host = hosts(narrowLive, now).stream().filter(h -> h.instanceId().equals(instanceId)).findFirst()
                .orElseThrow(XamPulseException::notFound);
        HealthScoreService.Result res = health.evaluate(new HealthScoreService.Signals(host.cpuPct(), host.memPct(), host.diskPct(),
                host.lastSeenAt() == null ? null : Math.max(0, now.getEpochSecond() - host.lastSeenAt().getEpochSecond()), null));
        QueryScope hostScope = new QueryScope(List.of(accountId), from, to, List.of(), List.of(instanceId), List.of(), List.of());

        Map<String, List<double[]>> series = new LinkedHashMap<>();
        TenantScopedQueryBuilder.MetricPlan plan = TenantScopedQueryBuilder.planMetric(hostScope, null);
        series.put("system.cpu.utilization", percentSeries(hostScope, plan,
                new MetricSpec("system.cpu.utilization", "avg", List.of(), List.of(new MetricSpec.LabelFilter("state", "=", "idle"))), true));
        series.put("system.memory.utilization", percentSeries(hostScope, plan,
                new MetricSpec("system.memory.utilization", "avg", List.of(), List.of(new MetricSpec.LabelFilter("state", "=", "used"))), false));
        series.put("system.filesystem.utilization", percentSeries(hostScope, plan,
                new MetricSpec("system.filesystem.utilization", "max", List.of(), List.of()), false));
        series.put("system.cpu.load_average.1m", percentSeries(hostScope, plan,
                new MetricSpec("system.cpu.load_average.1m", "avg", List.of(), List.of()), null));

        // Depth series (D7): the Resource 360 drawer parses these by exact key. CPU sub-states and per-core are
        // percentages (value * 100); disk/network I/O are byte rates grouped by direction.
        series.put("system.cpu.utilization{state=steal}", percentSeries(hostScope, plan,
                new MetricSpec("system.cpu.utilization", "avg", List.of(), List.of(new MetricSpec.LabelFilter("state", "=", "steal"))), false));
        // The OpenTelemetry hostmetrics scraper names I/O wait "wait" (states: user, system, idle, interrupt, nice,
        // softirq, steal, wait); the UI key stays {state=iowait}. Querying "iowait" matched nothing on real hosts.
        series.put("system.cpu.utilization{state=iowait}", percentSeries(hostScope, plan,
                new MetricSpec("system.cpu.utilization", "avg", List.of(), List.of(new MetricSpec.LabelFilter("state", "=", "wait"))), false));
        // CPU tab: the rest of the CPU-time breakdown (user = application code, system = kernel, softirq/interrupt =
        // packet and device work, nice = low-priority jobs), the load trend, the vCPU count that turns load into
        // "load per vCPU", the run queue (running vs blocked on I/O) and how fast processes are being created.
        // Each key is present only when the host reports it, so the UI can say "not reported" instead of drawing 0.
        for (String st : List.of("user", "system", "softirq", "interrupt", "nice")) {
            putIfAny(series, "system.cpu.utilization{state=" + st + "}", percentSeries(hostScope, plan,
                    new MetricSpec("system.cpu.utilization", "avg", List.of(), List.of(new MetricSpec.LabelFilter("state", "=", st))), false));
        }
        putIfAny(series, "system.cpu.load_average.5m", percentSeries(hostScope, plan,
                new MetricSpec("system.cpu.load_average.5m", "avg", List.of(), List.of()), null));
        putIfAny(series, "system.cpu.load_average.15m", percentSeries(hostScope, plan,
                new MetricSpec("system.cpu.load_average.15m", "avg", List.of(), List.of()), null));
        putIfAny(series, "system.cpu.logical.count", percentSeries(hostScope, plan,
                new MetricSpec("system.cpu.logical.count", "max", List.of(), List.of()), null));
        for (String status : List.of("running", "blocked")) {
            putIfAny(series, "system.processes.count{status=" + status + "}", percentSeries(hostScope, plan,
                    new MetricSpec("system.processes.count", "avg", List.of(), List.of(new MetricSpec.LabelFilter("status", "=", status))), null));
        }
        putIfAny(series, "system.processes.created", percentSeries(hostScope, plan,
                new MetricSpec("system.processes.created", "rate", List.of(), List.of()), null));
        series.put("system.paging.utilization", percentSeries(hostScope, plan,
                new MetricSpec("system.paging.utilization", "avg", List.of(), List.of(new MetricSpec.LabelFilter("state", "=", "used"))), false));
        // Per-core CPU: the agent's separate per-core stream (xp.cpu.core.utilization, idle only, one series per
        // logical CPU) so the per-state totals above stay per host. Non-idle = (1 - idle) * 100. The agent labels
        // CPUs "cpu0", "cpu1": the key uses the bare number the UI parses.
        fanInvertedByLabel(series, hostScope, plan, "cpu",
                new MetricSpec("xp.cpu.core.utilization", "avg", List.of("cpu"), List.of(new MetricSpec.LabelFilter("state", "=", "idle"))),
                v -> "system.cpu.utilization{cpu=" + v.replaceFirst("^cpu", "") + "}");
        // Per-mount filesystem usage: one key per mountpoint, used fraction * 100.
        fanScaledByLabel(series, hostScope, plan, "mountpoint",
                new MetricSpec("system.filesystem.utilization", "max", List.of("mountpoint"), List.of()),
                v -> "system.filesystem.utilization{mountpoint=" + v + "}");
        // Disk and network I/O as byte rates (bytes per second), grouped by direction. Only whole physical disks and
        // the instance's real NICs are added up: a partition repeats its disk's bytes, and lo, docker0, br-*, veth*
        // carry traffic that never left the host or is already counted on the NIC.
        for (String dir : List.of("read", "write")) {
            fanRawByLabel(series, hostScope, plan, "direction",
                    new MetricSpec("system.disk.io", "rate", List.of("direction"), List.of(
                            new MetricSpec.LabelFilter("direction", "=", dir), new MetricSpec.LabelFilter("device", "=~", WHOLE_DISK))),
                    v -> "system.disk.io{direction=" + v + "}");
        }
        for (String dir : List.of("receive", "transmit")) {
            fanRawByLabel(series, hostScope, plan, "direction",
                    new MetricSpec("system.network.io", "rate", List.of("direction"), List.of(
                            new MetricSpec.LabelFilter("direction", "=", dir), new MetricSpec.LabelFilter("device", "=~", PHYSICAL_NIC))),
                    v -> "system.network.io{direction=" + v + "}");
        }
        // Pressure (PSI, xampulse_ec2): the rate of stall seconds is the share of time stalled; shown in percent.
        // Absent (no key) when the host reports no PSI, so the UI can say "not measurable" instead of drawing zero.
        fanScaledByLabel(series, hostScope, plan, "resource",
                new MetricSpec("xp.pressure.stall_time", "rate", List.of("resource"), List.of(new MetricSpec.LabelFilter("level", "=", "some"))),
                v -> "xp.pressure{resource=" + v + ",level=some}");
        fanScaledByLabel(series, hostScope, plan, "resource",
                new MetricSpec("xp.pressure.stall_time", "rate", List.of("resource"), List.of(new MetricSpec.LabelFilter("level", "=", "full"))),
                v -> "xp.pressure{resource=" + v + ",level=full}");
        // AWS network allowances (xampulse_ec2): packets per second over each allowance, all ENA interfaces together.
        fanRawByLabel(series, hostScope, plan, "allowance",
                new MetricSpec("xp.ena.allowance_exceeded", "rate", List.of("allowance"), List.of()),
                v -> "xp.ena.allowance_exceeded{allowance=" + v + "}");

        List<XpLogRow> recentErrors = logs.search(user, new XpLogSearchRequest(
                new XpScope(List.of(accountId), null, from, to, new XpResourceFilter(List.of(instanceId), null, null)),
                "severity:>=error", "TS_DESC", 20, null)).rows();
        List<XpEvent> evs = events.events(range, List.of()).stream()
                .filter(e -> instanceId.equals(e.resource()) || e.resource() == null || e.resource().isEmpty()).toList();
        return new XpHostSummary(host, res.lanes(), series, recentErrors, evs, incidents.openForInstance(accountId, instanceId));
    }

    /** @param invertAndScale true: (1-v)*100; false: v*100; null: raw value */
    private static void putIfAny(Map<String, List<double[]>> series, String key, List<double[]> points) {
        if (!points.isEmpty()) {
            series.put(key, points);
        }
    }

    private List<double[]> percentSeries(QueryScope scope, TenantScopedQueryBuilder.MetricPlan plan, MetricSpec spec, Boolean invertAndScale) {
        List<double[]> pts = new ArrayList<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.metricSeries(s, spec, plan))) {
            for (Map<String, Object> r : tr.rows()) {
                Double v = Rows.dOrNull(r, "v");
                if (v == null) {
                    continue;
                }
                double val = invertAndScale == null ? v : (invertAndScale ? (1 - v) * 100 : v * 100);
                pts.add(new double[] {Rows.l(r, "t") * 1000.0, invertAndScale == null ? val : clampPct(val)});
            }
        }
        return pts;
    }

    /** Scaling applied to a grouped point value before it is stored. */
    private enum Scale { INVERT_PCT, SCALE_PCT, RAW }

    /** Per-core CPU: each label value becomes one series of (1 - idle) * 100, clamped to a percentage. */
    private void fanInvertedByLabel(Map<String, List<double[]>> series, QueryScope scope, TenantScopedQueryBuilder.MetricPlan plan,
                                    String labelKey, MetricSpec spec, java.util.function.Function<String, String> keyFor) {
        fanByLabel(series, scope, plan, labelKey, spec, keyFor, Scale.INVERT_PCT);
    }

    /** Per-mount filesystem: each label value becomes one series of fraction * 100, clamped to a percentage. */
    private void fanScaledByLabel(Map<String, List<double[]>> series, QueryScope scope, TenantScopedQueryBuilder.MetricPlan plan,
                                  String labelKey, MetricSpec spec, java.util.function.Function<String, String> keyFor) {
        fanByLabel(series, scope, plan, labelKey, spec, keyFor, Scale.SCALE_PCT);
    }

    /** Disk/network I/O: each label value becomes one series of the raw aggregated value (a byte rate). */
    private void fanRawByLabel(Map<String, List<double[]>> series, QueryScope scope, TenantScopedQueryBuilder.MetricPlan plan,
                               String labelKey, MetricSpec spec, java.util.function.Function<String, String> keyFor) {
        fanByLabel(series, scope, plan, labelKey, spec, keyFor, Scale.RAW);
    }

    /**
     * Runs one grouped {@code metricSeries} query (the label is the single group-by, so its value is in {@code g0})
     * and fans the rows into one keyed series map entry per distinct label value. A blank or missing label value is
     * skipped so a stray key never appears. Points inside each series stay time-ordered as ClickHouse returns them.
     */
    private void fanByLabel(Map<String, List<double[]>> series, QueryScope scope, TenantScopedQueryBuilder.MetricPlan plan,
                            String labelKey, MetricSpec spec, java.util.function.Function<String, String> keyFor, Scale scale) {
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.metricSeries(s, spec, plan))) {
            for (Map<String, Object> r : tr.rows()) {
                String value = Rows.s(r, "g0");
                if (value.isEmpty()) {
                    continue;
                }
                Double v = Rows.dOrNull(r, "v");
                if (v == null) {
                    continue;
                }
                double scaled = switch (scale) {
                    case INVERT_PCT -> clampPct((1 - v) * 100);
                    case SCALE_PCT -> clampPct(v * 100);
                    case RAW -> v;
                };
                series.computeIfAbsent(keyFor.apply(value), k -> new ArrayList<>())
                        .add(new double[] {Rows.l(r, "t") * 1000.0, scaled});
            }
        }
    }

    static double clampPct(double v) {
        return Math.max(0, Math.min(100, v));
    }

    /** Latest values per instance, accumulated from rollup rows. */
    static final class Live {
        Double idle, memUsed, load1, diskMax;
        long lastTs;

        void accept(Map<String, Object> r) {
            String metric = Rows.s(r, "metric");
            Double v = Rows.dOrNull(r, "v");
            lastTs = Math.max(lastTs, Rows.l(r, "last_ts"));
            if (v == null) {
                return;
            }
            List<String> labels = List.of(Rows.s(r, "labels_key").split(","));
            switch (metric) {
                case "system.cpu.utilization" -> {
                    if (labels.contains("state=idle")) {
                        idle = v;
                    }
                }
                case "system.memory.utilization" -> {
                    if (labels.contains("state=used")) {
                        memUsed = v;
                    }
                }
                case "system.cpu.load_average.1m" -> load1 = v;
                case "system.filesystem.utilization" -> diskMax = diskMax == null ? v : Math.max(diskMax, v);
                default -> { }
            }
        }

        Double cpuPct() { return idle == null ? null : clampPct((1 - idle) * 100); }
        Double memPct() { return memUsed == null ? null : clampPct(memUsed * 100); }
        Double diskPct() { return diskMax == null ? null : clampPct(diskMax * 100); }
    }
}
