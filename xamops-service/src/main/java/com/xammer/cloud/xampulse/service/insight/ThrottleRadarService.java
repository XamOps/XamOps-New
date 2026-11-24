package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpEc2Facts;
import com.xammer.cloud.xampulse.dto.XpHostLimits;
import com.xammer.cloud.xampulse.dto.XpHostLimits.Finding;
import com.xammer.cloud.xampulse.dto.XpHostLimits.Signal;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.MetricSpec;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.QueryScopeFactory;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Throttle Radar: "is something capping this host, and who?" Every finding comes from a measured counter or gauge in
 * the window, carries the numbers it was based on, and names the fix. A signal the host cannot provide is reported as
 * NOT_MEASURED with the reason, never as healthy.
 *
 * <p>Sources: the agent's xampulse_ec2 receiver (ENA allowance counters, PSI, IMDS Spot notices), hostmetrics (CPU
 * steal, conntrack table), docker_stats (CPU throttling against a container's limit) and CloudWatch data the user
 * fetched with Refresh (EBS exceeded checks; nothing is fetched here, so this costs nothing).
 *
 * <p>The thresholds are judgement, documented next to each rule. PSI "some" is the share of time at least one task
 * waited for the resource; "full" is the share of time every non-idle task waited (kernel PSI documentation).
 */
@Service("xpThrottleRadarService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ThrottleRadarService {

    static final double PSI_CPU_SOME_WARN = 0.25, PSI_CPU_SOME_CRIT = 0.50;
    static final double PSI_MEM_SOME_WARN = 0.10, PSI_MEM_FULL_WARN = 0.02, PSI_MEM_FULL_CRIT = 0.10;
    static final double PSI_IO_FULL_WARN = 0.10, PSI_IO_FULL_CRIT = 0.30;
    static final double STEAL_WARN = 0.10;
    static final double THROTTLED_WARN = 0.20, THROTTLED_CRIT = 0.50;
    /** Fewer CPU periods than this (about 10 s of a busy container at the default 100 ms period) is too little to judge. */
    static final double MIN_PERIODS = 100;
    static final double CONNTRACK_WARN = 0.80, CONNTRACK_CRIT = 0.95;
    static final double INODES_WARN = 0.90, INODES_CRIT = 0.95;
    static final double FILES_WARN = 0.80, FILES_CRIT = 0.95;
    static final double PORTS_WARN = 0.70, PORTS_CRIT = 0.90;
    static final double RETRANS_WARN = 0.02, RETRANS_CRIT = 0.05;
    /** Fewer segments sent than this in the range is too little traffic to judge a retransmission rate. */
    static final double MIN_SEGMENTS = 10_000;
    static final double LISTEN_OVERFLOW_CRIT_PER_SEC = 1.0;

    /** Metrics the agent's xampulse_ec2 receiver reads from /proc (agent 1.3.0 and later); inodes come from hostmetrics. */
    static final String M_INODES = "system.filesystem.inodes.usage", M_FILE_HANDLES = "xp.fs.file_handles",
            M_PROCESS_FILES = "xp.process.open_files", M_LOCAL_PORTS = "xp.net.local_ports",
            M_TCP_SEGMENTS = "xp.tcp.segments", M_LISTEN_QUEUE = "xp.tcp.listen_queue";
    static final String NEEDS_AGENT_13 = "The agent predates this check (agent 1.3.0 and later read it from /proc).";

    /** A counter's measured increase and the seconds it covers. */
    record Inc(double inc, double secs) {
        double rate() { return secs <= 0 ? 0 : inc / secs; }
    }

    /** The latest point of a gauge, with its labels (empty when the series has none). */
    record Latest(double value, Map<String, String> labels) {}

    /**
     * Operating-system limits that make a host slow or fail while CPU looks fine. Empty / null = not reported.
     *
     * @param inodes        per mount point {used, free}
     * @param processFiles  processes the agent reports near their open-file limit (labels pid, name, limit)
     * @param tcpSegments   counter increase by type: out, retrans
     * @param listenQueue   counter increase by event: overflow, drop
     */
    record Kernel(Map<String, double[]> inodes, Double fileHandles, Double fileHandlesMax, List<Latest> processFiles,
                  Double localPortsUsed, Double localPortsRange, Map<String, Inc> tcpSegments, Map<String, Inc> listenQueue) {
        static final Kernel NONE = new Kernel(Map.of(), null, null, List.of(), null, null, Map.of(), Map.of());
    }

    /** Everything the rules look at, loaded once. Empty maps and nulls mean "not reported in this window". */
    record Inputs(Map<String, Inc> enaByAllowance, Map<String, Inc> psiSome, Map<String, Inc> psiFull, Double stealAvg,
                  Map<String, Inc> containerPeriods, Map<String, Inc> containerThrottled, Double conntrackCount,
                  Double conntrackMax, Latest spot, Latest rebalance, Map<String, Double> ebsInstance,
                  Map<String, Map<String, Double>> ebsVolumes, Instant cloudWatchFetchedAt, Kernel kernel) {

        Inputs(Map<String, Inc> enaByAllowance, Map<String, Inc> psiSome, Map<String, Inc> psiFull, Double stealAvg,
               Map<String, Inc> containerPeriods, Map<String, Inc> containerThrottled, Double conntrackCount,
               Double conntrackMax, Latest spot, Latest rebalance, Map<String, Double> ebsInstance,
               Map<String, Map<String, Double>> ebsVolumes, Instant cloudWatchFetchedAt) {
            this(enaByAllowance, psiSome, psiFull, stealAvg, containerPeriods, containerThrottled, conntrackCount, conntrackMax,
                    spot, rebalance, ebsInstance, ebsVolumes, cloudWatchFetchedAt, Kernel.NONE);
        }
    }

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public ThrottleRadarService(QueryScopeFactory scopes, ClickHouseExecutor executor) {
        this.scopes = scopes;
        this.executor = executor;
    }

    public XpHostLimits limits(ClientUserDetails user, Long accountId, String instanceId, Instant from, Instant to,
                               Optional<XpEc2Facts> facts) {
        scopes.build(user, List.of(accountId), from, to); // authorises the account like every other read
        QueryScope host = new QueryScope(List.of(accountId), from, to, List.of(), List.of(instanceId), List.of(), List.of());
        return limitsInScope(host, instanceId, facts.orElse(null));
    }

    /** The radar for a scope whose account the caller has already authorised (one tenant, one instance). */
    public XpHostLimits limitsInScope(QueryScope host, String instanceId, XpEc2Facts facts) {
        return evaluate(instanceId, host.from(), host.to(), load(host, instanceId), facts);
    }

    // ------------------------------------------------------------------ loading

    Inputs load(QueryScope host, String instanceId) {
        Map<String, Inc> ena = increase(host, "xp.ena.allowance_exceeded", List.of(), "allowance");
        Map<String, Inc> some = increase(host, "xp.pressure.stall_time", List.of(eq("level", "some")), "resource");
        Map<String, Inc> full = increase(host, "xp.pressure.stall_time", List.of(eq("level", "full")), "resource");
        Map<String, Inc> periods = increase(host, "container.cpu.throttling_data.periods", List.of(), "container.name");
        Map<String, Inc> throttled = increase(host, "container.cpu.throttling_data.throttled_periods", List.of(), "container.name");

        Double steal = null;
        for (Map<String, Object> r : executor.query(builder.alertMetric(host,
                new MetricSpec("system.cpu.utilization", "avg", List.of(), List.of(eq("state", "steal")))))) {
            steal = Rows.dOrNull(r, "v");
        }

        Map<String, Latest> latest = latest(host, List.of("system.network.conntrack.count", "system.network.conntrack.max",
                "xp.ec2.spot.interruption", "xp.ec2.rebalance_recommendation"));
        Latest ctCount = latest.get("system.network.conntrack.count");
        Latest ctMax = latest.get("system.network.conntrack.max");

        Map<String, Double> ebsInstance = new HashMap<>();
        Map<String, Map<String, Double>> ebsVolumes = new HashMap<>();
        Map<String, double[]> sums = new HashMap<>(); // key -> {sum, n}
        long fetched = 0;
        for (Map<String, Object> r : executor.query(builder.cloudWatchPoints(host, instanceId))) {
            String metric = Rows.s(r, "metric");
            if (!metric.contains("exceeded_pct")) {
                continue;
            }
            String dim = r.get("dimension") == null ? "" : String.valueOf(r.get("dimension"));
            double[] acc = sums.computeIfAbsent(dim + "|" + metric, k -> new double[2]);
            acc[0] += Rows.d(r, "value");
            acc[1] += 1;
            fetched = Math.max(fetched, Rows.l(r, "fetched_ms"));
        }
        sums.forEach((k, acc) -> {
            String dim = k.substring(0, k.indexOf('|'));
            String metric = k.substring(k.indexOf('|') + 1);
            double avg = acc[1] == 0 ? 0 : acc[0] / acc[1];
            if (dim.isEmpty()) {
                ebsInstance.put(metric, avg);
            } else {
                ebsVolumes.computeIfAbsent(dim, x -> new HashMap<>()).put(metric, avg);
            }
        });
        return new Inputs(ena, some, full, steal, periods, throttled,
                ctCount == null ? null : ctCount.value(), ctMax == null ? null : ctMax.value(),
                latest.get("xp.ec2.spot.interruption"), latest.get("xp.ec2.rebalance_recommendation"),
                ebsInstance, ebsVolumes, fetched == 0 ? null : Instant.ofEpochMilli(fetched), loadKernel(host));
    }

    /** The OS-limit inputs: latest gauges at the end of the range, counter increases over it. */
    Kernel loadKernel(QueryScope host) {
        Map<String, double[]> inodes = new LinkedHashMap<>();
        Double handles = null;
        Double handlesMax = null;
        Double portsUsed = null;
        Double portsRange = null;
        List<LatestAt> processes = new ArrayList<>();
        for (LatestAt l : latestAll(host, List.of(M_INODES, M_FILE_HANDLES, M_PROCESS_FILES, M_LOCAL_PORTS))) {
            String state = l.labels().getOrDefault("state", "");
            switch (l.metric()) {
                case M_INODES -> {
                    String mount = l.labels().get("mountpoint");
                    if (mount != null && ("used".equals(state) || "free".equals(state))) {
                        inodes.computeIfAbsent(mount, k -> new double[2])["used".equals(state) ? 0 : 1] = l.value();
                    }
                }
                case M_FILE_HANDLES -> {
                    if ("allocated".equals(state)) handles = l.value();
                    if ("max".equals(state)) handlesMax = l.value();
                }
                case M_LOCAL_PORTS -> {
                    if ("used".equals(state)) portsUsed = l.value();
                    if ("range".equals(state)) portsRange = l.value();
                }
                case M_PROCESS_FILES -> processes.add(l);
                default -> { }
            }
        }
        // A process the agent stopped reporting fell back under half its limit: keep only the latest scrape's processes.
        long lastScrape = processes.stream().mapToLong(LatestAt::ts).max().orElse(0);
        List<Latest> nearLimit = processes.stream().filter(p -> p.ts() >= lastScrape - 120)
                .map(p -> new Latest(p.value(), p.labels())).toList();
        return new Kernel(inodes, handles, handlesMax, nearLimit, portsUsed, portsRange,
                increase(host, M_TCP_SEGMENTS, List.of(), "type"), increase(host, M_LISTEN_QUEUE, List.of(), "event"));
    }

    record LatestAt(String metric, double value, Map<String, String> labels, long ts) {}

    /** The latest point of every label set of the metrics (one row per series, not one per metric). */
    private List<LatestAt> latestAll(QueryScope scope, List<String> metrics) {
        List<LatestAt> out = new ArrayList<>();
        for (Map<String, Object> r : executor.query(builder.latestMetrics(scope, scope.from().getEpochSecond(), metrics))) {
            Double v = Rows.dOrNull(r, "v");
            if (v != null) {
                out.add(new LatestAt(Rows.s(r, "metric"), v, labels(Rows.s(r, "labels_key")), Rows.l(r, "last_ts")));
            }
        }
        return out;
    }

    private static MetricSpec.LabelFilter eq(String k, String v) {
        return new MetricSpec.LabelFilter(k, "=", v);
    }

    private Map<String, Inc> increase(QueryScope scope, String metric, List<MetricSpec.LabelFilter> filters, String group) {
        Map<String, Inc> out = new LinkedHashMap<>();
        for (Map<String, Object> r : executor.query(builder.counterIncrease(scope, metric, filters, group))) {
            String g = Rows.s(r, "g0");
            Double inc = Rows.dOrNull(r, "inc");
            Double secs = Rows.dOrNull(r, "secs");
            if (!g.isEmpty() && inc != null && secs != null && secs > 0) {
                out.put(g, new Inc(inc, secs));
            }
        }
        return out;
    }

    /** Latest value per metric: of a metric's label sets, the one reported most recently. */
    private Map<String, Latest> latest(QueryScope scope, List<String> metrics) {
        Map<String, Latest> out = new HashMap<>();
        Map<String, Long> at = new HashMap<>();
        for (Map<String, Object> r : executor.query(builder.latestMetrics(scope, scope.from().getEpochSecond(), metrics))) {
            String m = Rows.s(r, "metric");
            long ts = Rows.l(r, "last_ts");
            Double v = Rows.dOrNull(r, "v");
            if (v != null && ts >= at.getOrDefault(m, Long.MIN_VALUE)) {
                at.put(m, ts);
                out.put(m, new Latest(v, labels(Rows.s(r, "labels_key"))));
            }
        }
        return out;
    }

    static Map<String, String> labels(String key) {
        Map<String, String> m = new HashMap<>();
        if (key == null || key.isEmpty()) {
            return m;
        }
        for (String kv : key.split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                m.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ rules (pure)

    static XpHostLimits evaluate(String instanceId, Instant from, Instant to, Inputs in, XpEc2Facts facts) {
        List<Finding> findings = new ArrayList<>();
        List<Signal> signals = new ArrayList<>();

        // 1. ENA allowances: any packet over an allowance was queued or dropped by AWS, not by the OS.
        if (in.enaByAllowance().isEmpty()) {
            signals.add(new Signal("ena", "AWS network allowances", "NOT_MEASURED",
                    "No ENA allowance counters: the host is not on an ENA (Nitro) interface, the ENA driver is too old, or the agent predates this check."));
        } else {
            boolean hit = false;
            for (Map.Entry<String, Inc> e : in.enaByAllowance().entrySet()) {
                if (e.getValue().inc() <= 0) {
                    continue;
                }
                hit = true;
                findings.add(enaFinding(e.getKey(), e.getValue(), facts));
            }
            signals.add(new Signal("ena", "AWS network allowances", hit ? "LIMITED" : "OK", null));
        }

        // 2. Pressure (PSI).
        if (in.psiSome().isEmpty() && in.psiFull().isEmpty()) {
            signals.add(new Signal("psi", "CPU, memory and I/O pressure", "NOT_MEASURED",
                    "No pressure data: the kernel has PSI disabled (some distributions need the psi=1 boot option) or the agent predates this check."));
        } else {
            boolean cpu = psi(findings, "cpu", in.psiSome().get("cpu"), PSI_CPU_SOME_WARN, PSI_CPU_SOME_CRIT, "some",
                    "Runnable tasks waited for a CPU", "The instance needs more vCPUs, or a process is using more CPU than expected (see Top processes).");
            boolean memFull = psi(findings, "memory", in.psiFull().get("memory"), PSI_MEM_FULL_WARN, PSI_MEM_FULL_CRIT, "full",
                    "Every task was stalled on memory (reclaim or swap)", "The host is short of memory: reduce what runs on it or move to a size with more memory.");
            // "some" memory pressure is the earlier, milder sign; it is only reported when "full" did not already fire.
            boolean memSome = !memFull && psi(findings, "memory", in.psiSome().get("memory"), PSI_MEM_SOME_WARN, Double.MAX_VALUE, "some",
                    "Tasks waited for memory (page reclaim)", "Memory is getting tight; watch available memory and the largest processes.");
            boolean io = psi(findings, "io", in.psiFull().get("io"), PSI_IO_FULL_WARN, PSI_IO_FULL_CRIT, "full",
                    "Every task was stalled on disk I/O", "Storage is the bottleneck: check the EBS volume and instance limits below (Refresh CloudWatch for the exceeded checks).");
            signals.add(new Signal("psi", "CPU, memory and I/O pressure", cpu || memFull || memSome || io ? "LIMITED" : "OK", null));
        }

        // 3. CPU steal: the hypervisor gave the vCPU to someone else (a burstable instance out of credits, or contention).
        if (in.stealAvg() == null) {
            signals.add(new Signal("steal", "CPU steal", "NOT_MEASURED", "No CPU steal data in this range."));
        } else if (in.stealAvg() >= STEAL_WARN) {
            boolean burstable = facts != null && facts.burstable();
            boolean standard = burstable && !"unlimited".equals(facts.cpuCredits());
            findings.add(new Finding("WARNING", "cpu_steal", burstable ? "AWS" : "KERNEL",
                    String.format(Locale.ROOT, "CPU steal averaged %.0f%%", in.stealAvg() * 100),
                    standard ? "This is a burstable instance in standard mode: steal this high usually means its CPU credits ran out and AWS is holding it at its baseline."
                            : burstable ? "This is a burstable instance in unlimited mode, which is not throttled for credits; high steal points to contention on the host."
                            : "The hypervisor ran other work on this vCPU's physical core for that share of the time.",
                    standard ? "Refresh CloudWatch to confirm the CPU credit balance; switch to unlimited mode or a non-burstable type for sustained load."
                            : "Stop and start the instance to move it to another host if this persists, or use a larger or non-burstable type.",
                    Map.of("steal_avg", in.stealAvg())));
            signals.add(new Signal("steal", "CPU steal", "LIMITED", null));
        } else {
            signals.add(new Signal("steal", "CPU steal", "OK", null));
        }

        // 4. Container CPU limits.
        if (in.containerPeriods().isEmpty()) {
            signals.add(new Signal("container_cpu", "Container CPU limits", "NOT_MEASURED",
                    "No container throttling data: no containers with a CPU limit, Docker not enabled in Setup, or the agent predates this check."));
        } else {
            boolean hit = false;
            for (Map.Entry<String, Inc> e : in.containerPeriods().entrySet()) {
                Inc throttled = in.containerThrottled().get(e.getKey());
                double periods = e.getValue().inc();
                if (throttled == null || periods < MIN_PERIODS) {
                    continue;
                }
                double ratio = throttled.inc() / periods;
                if (ratio >= THROTTLED_WARN) {
                    hit = true;
                    findings.add(new Finding(ratio >= THROTTLED_CRIT ? "CRITICAL" : "WARNING", "container_throttled", "CONTAINER",
                            String.format(Locale.ROOT, "Container %s was CPU-throttled in %.0f%% of its scheduling periods", e.getKey(), ratio * 100),
                            "The container hit its CPU limit (--cpus / cpu quota); requests were delayed even though the host may have idle CPU.",
                            "Raise the container's CPU limit, or reduce its CPU use; the host's own CPU chart will not show this.",
                            Map.of("throttled_ratio", ratio, "periods", periods, "throttled_periods", throttled.inc())));
                }
            }
            signals.add(new Signal("container_cpu", "Container CPU limits", hit ? "LIMITED" : "OK", null));
        }

        // 5. Kernel connection-tracking table.
        if (in.conntrackCount() == null || in.conntrackMax() == null || in.conntrackMax() <= 0) {
            signals.add(new Signal("conntrack_table", "Connection-tracking table", "NOT_MEASURED",
                    "The kernel's conntrack table is not in use (nf_conntrack not loaded) or the agent predates this check."));
        } else {
            double fill = in.conntrackCount() / in.conntrackMax();
            if (fill >= CONNTRACK_WARN) {
                findings.add(new Finding(fill >= CONNTRACK_CRIT ? "CRITICAL" : "WARNING", "conntrack_table", "KERNEL",
                        String.format(Locale.ROOT, "Connection-tracking table %.0f%% full", fill * 100),
                        "When it is full the kernel drops new connections (\"nf_conntrack: table full\" in the kernel log).",
                        "Raise net.netfilter.nf_conntrack_max, shorten timeouts for idle connections, or reuse connections.",
                        Map.of("count", in.conntrackCount(), "max", in.conntrackMax(), "fill", fill)));
            }
            signals.add(new Signal("conntrack_table", "Connection-tracking table", fill >= CONNTRACK_WARN ? "LIMITED" : "OK", null));
        }

        // 6. Spot notices (IMDS, live).
        if (in.spot() == null && in.rebalance() == null) {
            signals.add(new Signal("spot", "Spot interruption notices", "NOT_MEASURED",
                    "No instance metadata data: the agent predates this check or IMDS is not reachable from the instance."));
        } else {
            boolean hit = false;
            if (in.spot() != null && in.spot().value() >= 1) {
                hit = true;
                String action = in.spot().labels().getOrDefault("action", "interrupt");
                String when = in.spot().labels().getOrDefault("time", "within two minutes");
                findings.add(new Finding("CRITICAL", "spot_interruption", "AWS",
                        "AWS will " + action + " this Spot instance at " + when,
                        "EC2 issued the two-minute Spot interruption notice.",
                        "Drain the instance now: stop taking new work and move state off it.", Map.of("notice", 1.0)));
            }
            if (in.rebalance() != null && in.rebalance().value() >= 1) {
                hit = true;
                findings.add(new Finding("WARNING", "spot_rebalance", "AWS", "EC2 recommends rebalancing this Spot instance",
                        "The instance is at elevated risk of interruption" + Optional.ofNullable(in.rebalance().labels().get("notice_time"))
                                .map(t -> " (signal at " + t + ")").orElse("") + ".",
                        "Launch a replacement now (Capacity Rebalancing does this for Auto Scaling groups).", Map.of("notice", 1.0)));
            }
            signals.add(new Signal("spot", "Spot interruption notices", hit ? "LIMITED" : "OK", null));
        }

        // 7. EBS: instance-side vs volume-side caps, from CloudWatch data the user fetched.
        if (in.ebsInstance().isEmpty() && in.ebsVolumes().isEmpty()) {
            signals.add(new Signal("ebs", "EBS volume and instance limits", "NOT_MEASURED",
                    "Not fetched for this range: press Refresh in the AWS section to read the EBS exceeded checks (Nitro instances)."));
        } else {
            boolean hit = false;
            double instIops = in.ebsInstance().getOrDefault("ebs.instance_iops_exceeded_pct", 0.0);
            double instTp = in.ebsInstance().getOrDefault("ebs.instance_throughput_exceeded_pct", 0.0);
            if (instIops > 0 || instTp > 0) {
                hit = true;
                findings.add(new Finding("WARNING", "ebs_instance_limit", "AWS",
                        String.format(Locale.ROOT, "The instance's EBS limit was hit in %.0f%% of minutes", Math.max(instIops, instTp)),
                        "All attached volumes together asked for more " + (instIops >= instTp ? "IOPS" : "throughput")
                                + " than this instance type allows. Raising a volume's provisioned performance will not help.",
                        "Move to an instance size or type with a higher EBS baseline.",
                        Map.of("instance_iops_exceeded_pct", instIops, "instance_throughput_exceeded_pct", instTp)));
            }
            for (Map.Entry<String, Map<String, Double>> v : in.ebsVolumes().entrySet()) {
                double iops = v.getValue().getOrDefault("iops_exceeded_pct", 0.0);
                double tp = v.getValue().getOrDefault("throughput_exceeded_pct", 0.0);
                if (iops > 0 || tp > 0) {
                    hit = true;
                    findings.add(new Finding("WARNING", "ebs_volume_limit", "AWS",
                            String.format(Locale.ROOT, "Volume %s hit its provisioned %s in %.0f%% of minutes", v.getKey(),
                                    iops >= tp ? "IOPS" : "throughput", Math.max(iops, tp)),
                            "The application asked this volume for more than it is provisioned for.",
                            "Raise the volume's provisioned " + (iops >= tp ? "IOPS" : "throughput") + " (gp3 and io2 can be changed online).",
                            Map.of("iops_exceeded_pct", iops, "throughput_exceeded_pct", tp)));
                }
            }
            signals.add(new Signal("ebs", "EBS volume and instance limits", hit ? "LIMITED" : "OK",
                    in.cloudWatchFetchedAt() == null ? null : "From CloudWatch data fetched " + in.cloudWatchFetchedAt() + "."));
        }

        kernelLimits(in.kernel() == null ? Kernel.NONE : in.kernel(), findings, signals);

        findings.sort(Comparator.comparingInt(f -> switch (f.severity()) { case "CRITICAL" -> 0; case "WARNING" -> 1; default -> 2; }));
        return new XpHostLimits(instanceId, from, to, List.copyOf(findings), List.copyOf(signals));
    }

    /** 8 to 12. Operating-system limits: the answer to "why is it slow, or failing, when CPU is 20%?". */
    static void kernelLimits(Kernel k, List<Finding> findings, List<Signal> signals) {
        // 8. Inodes per filesystem (hostmetrics, every agent).
        List<Map.Entry<String, double[]>> mounts = k.inodes().entrySet().stream().filter(e -> e.getValue()[0] + e.getValue()[1] > 0).toList();
        if (mounts.isEmpty()) {
            signals.add(new Signal("inodes", "Filesystem inodes", "NOT_MEASURED", "No inode counts reported for this host's filesystems."));
        } else {
            boolean hit = false;
            for (Map.Entry<String, double[]> m : mounts) {
                double used = m.getValue()[0];
                double total = used + m.getValue()[1];
                double fill = used / total;
                if (fill >= INODES_WARN) {
                    hit = true;
                    findings.add(new Finding(fill >= INODES_CRIT ? "CRITICAL" : "WARNING", "inodes", "KERNEL",
                            String.format(Locale.ROOT, "Inodes on %s are %.0f%% used", m.getKey(), fill * 100),
                            "Every file uses one inode. When they run out, writes fail with \"No space left on device\" even though df shows free space.",
                            "Remove or archive the many small files (caches, sessions, mail queues, old container layers); `find " + m.getKey()
                                    + " -xdev -type f | cut -d/ -f2-3 | sort | uniq -c | sort -n | tail` shows where they are.",
                            Map.of("inodes_used", used, "inodes_total", total, "fill", fill)));
                }
            }
            signals.add(new Signal("inodes", "Filesystem inodes", hit ? "LIMITED" : "OK", null));
        }

        // 9. Open files: the whole system (fs.file-max) and each process against its own limit (ulimit -n).
        if (k.fileHandles() == null && k.processFiles().isEmpty()) {
            signals.add(new Signal("file_handles", "Open-file limits", "NOT_MEASURED", NEEDS_AGENT_13));
        } else {
            boolean hit = false;
            if (k.fileHandles() != null && k.fileHandlesMax() != null && k.fileHandlesMax() > 0) {
                double fill = k.fileHandles() / k.fileHandlesMax();
                if (fill >= FILES_WARN) {
                    hit = true;
                    findings.add(new Finding(fill >= FILES_CRIT ? "CRITICAL" : "WARNING", "file_handles_system", "KERNEL",
                            String.format(Locale.ROOT, "The system has %.0f%% of its open-file limit in use", fill * 100),
                            "At the limit every program gets \"Too many open files in system\" (ENFILE) when it opens a file or socket.",
                            "Find what holds the files (Processes), or raise fs.file-max.",
                            Map.of("open", k.fileHandles(), "max", k.fileHandlesMax(), "fill", fill)));
                }
            }
            for (Latest p : k.processFiles()) {
                double limit = parse(p.labels().get("limit"));
                if (limit <= 0) {
                    continue;
                }
                double fill = p.value() / limit;
                if (fill >= FILES_WARN) {
                    hit = true;
                    String name = p.labels().getOrDefault("name", "a process");
                    findings.add(new Finding(fill >= FILES_CRIT ? "CRITICAL" : "WARNING", "file_handles_process", "KERNEL",
                            String.format(Locale.ROOT, "%s has %.0f of its %.0f open files in use", name, p.value(), limit),
                            "At its limit it gets \"Too many open files\" (EMFILE): new connections and file opens fail, often as timeouts.",
                            "Look for a leak (the count keeps climbing), or raise its limit (LimitNOFILE in its systemd unit, or ulimit -n).",
                            Map.of("open", p.value(), "limit", limit, "fill", fill, "pid", parse(p.labels().get("pid")))));
                }
            }
            signals.add(new Signal("file_handles", "Open-file limits", hit ? "LIMITED" : "OK", null));
        }

        // 10. Local (ephemeral) ports for outgoing connections.
        if (k.localPortsUsed() == null || k.localPortsRange() == null || k.localPortsRange() <= 0) {
            signals.add(new Signal("local_ports", "Local ports for outgoing connections", "NOT_MEASURED", NEEDS_AGENT_13));
        } else {
            double fill = k.localPortsUsed() / k.localPortsRange();
            if (fill >= PORTS_WARN) {
                findings.add(new Finding(fill >= PORTS_CRIT ? "CRITICAL" : "WARNING", "local_ports", "KERNEL",
                        String.format(Locale.ROOT, "%.0f%% of the local port range is in use", fill * 100),
                        "Outgoing connections fail with \"Cannot assign requested address\" when it runs out; usually many short connections waiting in TIME_WAIT.",
                        "Reuse connections (keep-alive, connection pools), or widen net.ipv4.ip_local_port_range.",
                        Map.of("ports_used", k.localPortsUsed(), "ports_in_range", k.localPortsRange(), "fill", fill)));
            }
            signals.add(new Signal("local_ports", "Local ports for outgoing connections", fill >= PORTS_WARN ? "LIMITED" : "OK", null));
        }

        // 11. TCP retransmissions: packets lost or delayed between this host and its peers.
        Inc out = k.tcpSegments().get("out");
        Inc retrans = k.tcpSegments().get("retrans");
        if (out == null || retrans == null) {
            signals.add(new Signal("tcp_retransmits", "TCP retransmissions", "NOT_MEASURED", NEEDS_AGENT_13));
        } else if (out.inc() < MIN_SEGMENTS) {
            signals.add(new Signal("tcp_retransmits", "TCP retransmissions", "NOT_MEASURED",
                    "Too little TCP traffic in this range to judge a retransmission rate."));
        } else {
            double share = retrans.inc() / out.inc();
            if (share >= RETRANS_WARN) {
                findings.add(new Finding(share >= RETRANS_CRIT ? "CRITICAL" : "WARNING", "tcp_retransmits", "KERNEL",
                        String.format(Locale.ROOT, "%.1f%% of TCP segments were sent again", share * 100),
                        "Packets are being lost or delayed between this host and its peers: each retransmission adds at least 200 ms to that request.",
                        "Check the AWS network allowances above and the peers' health; if one destination dominates, look there.",
                        Map.of("segments_out", out.inc(), "retransmitted", retrans.inc(), "share", share)));
            }
            signals.add(new Signal("tcp_retransmits", "TCP retransmissions", share >= RETRANS_WARN ? "LIMITED" : "OK", null));
        }

        // 12. Listen-queue overflows: a server not accepting connections fast enough.
        Inc overflow = k.listenQueue().get("overflow");
        if (overflow == null) {
            signals.add(new Signal("listen_queue", "Listen-queue overflows", "NOT_MEASURED", NEEDS_AGENT_13));
        } else {
            if (overflow.inc() > 0) {
                findings.add(new Finding(overflow.rate() >= LISTEN_OVERFLOW_CRIT_PER_SEC ? "CRITICAL" : "WARNING", "listen_queue", "KERNEL",
                        String.format(Locale.ROOT, "%.0f incoming connections were dropped: a listen queue was full", overflow.inc()),
                        "A server on this host is not accepting connections as fast as they arrive, or its backlog is too small; clients see timeouts and retries.",
                        "Find the busy service (Processes), add workers, or raise its listen backlog and net.core.somaxconn.",
                        Map.of("overflows", overflow.inc(), "per_second", overflow.rate())));
            }
            signals.add(new Signal("listen_queue", "Listen-queue overflows", overflow.inc() > 0 ? "LIMITED" : "OK", null));
        }
    }

    private static double parse(String v) {
        try {
            return v == null ? 0 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Adds a pressure finding when the stalled share reaches {@code warn}; returns whether it did. */
    private static boolean psi(List<Finding> out, String resource, Inc inc, double warn, double crit, String level, String what, String fix) {
        if (inc == null) {
            return false;
        }
        double share = inc.rate();
        if (share < warn) {
            return false;
        }
        out.add(new Finding(share >= crit ? "CRITICAL" : "WARNING", "psi_" + resource + "_" + level, "KERNEL",
                String.format(Locale.ROOT, "%s %.0f%% of the time", what, share * 100),
                "Linux pressure stall information (" + resource + ", " + level + "): measured, not estimated from utilisation.",
                fix, Map.of("stalled_share", share, "stalled_seconds", inc.inc(), "window_seconds", inc.secs())));
        return true;
    }

    private static Finding enaFinding(String allowance, Inc inc, XpEc2Facts facts) {
        String type = facts == null || facts.instanceType() == null ? "this instance type" : facts.instanceType();
        long n = Math.round(inc.inc());
        String what = switch (allowance) {
            case "bw_in" -> "inbound bandwidth allowance";
            case "bw_out" -> "outbound bandwidth allowance";
            case "pps" -> "packets-per-second allowance";
            case "conntrack" -> "connection-tracking allowance";
            case "linklocal" -> "link-local traffic allowance (DNS, IMDS, NTP)";
            default -> allowance + " allowance";
        };
        String fix = switch (allowance) {
            case "bw_in", "bw_out" -> "Use a larger size or a network-optimised type; \"up to\" bandwidth is a burst, the sustained baseline is lower.";
            case "pps" -> "Use a larger size or a network-optimised type, or send fewer, larger packets.";
            case "conntrack" -> "Use a larger size, or avoid tracking where possible (AWS documents security group rules that leave flows untracked).";
            case "linklocal" -> "Cache DNS on the instance and avoid polling instance metadata in a tight loop.";
            default -> "Check the AWS documentation for this ENA allowance.";
        };
        return new Finding("WARNING", "ena_" + allowance, "AWS",
                String.format(Locale.ROOT, "AWS is capping the network: %s exceeded %,d times", what, n),
                "The ENA driver counted " + String.format(Locale.ROOT, "%,d", n) + " packets queued or dropped because " + type
                        + " went over its " + what + ". The OS and its CPU look fine; the limit is the instance's.",
                fix, Map.of("packets", inc.inc(), "per_second", inc.rate()));
    }
}
