package com.xammer.cloud.xampulse.service.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.xampulse.dto.XpCloudWatchView;
import com.xammer.cloud.xampulse.dto.XpEc2Facts;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.platform.ClickHouseWrites;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import com.xammer.cloud.xampulse.service.stream.TicketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CloudWatch data for the EC2 instance page, fetched only when a user presses Refresh (decision 2026-11-10: the
 * customer pays $0.01 per 1,000 metrics requested, so nothing polls in the background). Each fetch is converted to
 * operator units and stored in {@code xp_cloudwatch_points}; opening the page later reads the store for free.
 */
@Service("xpCloudWatchViewService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class CloudWatchViewService {

    private static final Logger log = LoggerFactory.getLogger(CloudWatchViewService.class);
    static final Duration MIN_REFRESH_GAP = Duration.ofSeconds(60);
    static final Duration MAX_RANGE = Duration.ofDays(15);
    static final int TARGET_POINTS = 360;
    static final double USD_PER_METRIC = 0.01 / 1000.0;
    static final List<String> COLUMNS = List.of("tenant_id", "instance_id", "metric", "dimension", "ts", "value", "fetched_at");

    /** One CloudWatch metric to request; {@code volumeId} is null for instance-level metrics. */
    record Spec(String namespace, String name, String stat, String volumeId) {}

    private final AwsClientProvider aws;
    private final Ec2FactsService facts;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();
    private final ClickHouseWrites writes;
    private final TicketStore gate;
    private final Clock clock;
    /** Used only when the shared gate cannot be reached, so a Redis outage never turns into unlimited billed fetches. */
    private final Map<String, Instant> lastRefresh = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CloudWatchViewService(AwsClientProvider aws, Ec2FactsService facts, ClickHouseExecutor executor,
                                 ClickHouseWrites writes, TicketStore gate) {
        this(aws, facts, executor, writes, gate, Clock.systemUTC());
    }

    CloudWatchViewService(AwsClientProvider aws, Ec2FactsService facts, ClickHouseExecutor executor,
                          ClickHouseWrites writes, TicketStore gate, Clock clock) {
        this.aws = aws;
        this.facts = facts;
        this.executor = executor;
        this.writes = writes;
        this.gate = gate;
        this.clock = clock;
    }

    /**
     * True when this caller may fetch now. The gate is shared through Redis (SET NX with a {@link #MIN_REFRESH_GAP}
     * expiry), so with several backend replicas one instance is still fetched, and billed, at most once a minute.
     */
    boolean acquireRefresh(String key, Instant now) {
        try {
            return gate.putIfAbsent(GATE_PREFIX + key, now.toString(), MIN_REFRESH_GAP);
        } catch (RuntimeException e) {
            log.warn("XamPulse CloudWatch refresh gate unavailable, using the local one: {}", e.getClass().getSimpleName());
            Instant prev = lastRefresh.get(key);
            if (prev != null && prev.plus(MIN_REFRESH_GAP).isAfter(now)) {
                return false;
            }
            lastRefresh.put(key, now);
            return true;
        }
    }

    /** Lets the user retry at once when the fetch itself failed (nothing was billed for data we kept). */
    void releaseRefresh(String key) {
        lastRefresh.remove(key);
        try {
            gate.getAndDelete(GATE_PREFIX + key);
        } catch (RuntimeException e) {
            log.debug("XamPulse CloudWatch refresh gate release skipped: {}", e.getClass().getSimpleName());
        }
    }

    static final String GATE_PREFIX = "xp:cw-refresh:";

    // ------------------------------------------------------------------ stored (free)

    public XpCloudWatchView stored(QueryScope scope, String instanceId, String region, String note) {
        Map<String, List<double[]>> inst = new TreeMap<>();
        Map<String, Map<String, List<double[]>>> vols = new TreeMap<>();
        long lastFetched = 0;
        for (Map<String, Object> row : executor.query(builder.cloudWatchPoints(scope, instanceId))) {
            String metric = String.valueOf(row.get("metric"));
            String dim = row.get("dimension") == null ? "" : String.valueOf(row.get("dimension"));
            double[] p = {((Number) row.get("t_ms")).doubleValue(), ((Number) row.get("value")).doubleValue()};
            lastFetched = Math.max(lastFetched, ((Number) row.get("fetched_ms")).longValue());
            (dim.isEmpty() ? inst : vols.computeIfAbsent(dim, k -> new TreeMap<>())).computeIfAbsent(metric, k -> new ArrayList<>()).add(p);
        }
        return new XpCloudWatchView(instanceId, region, scope.from(), scope.to(), 0, inst, vols, "STORED",
                lastFetched == 0 ? null : Instant.ofEpochMilli(lastFetched), null, null, note);
    }

    // ------------------------------------------------------------------ refresh (billed to the customer)

    public XpCloudWatchView refresh(CloudAccount account, QueryScope scope, String instanceId, String regionHint) {
        Instant now = clock.instant();
        String key = account.getId() + "|" + instanceId;
        XpEc2Facts f = facts.facts(account, instanceId, regionHint);
        if (!acquireRefresh(key, now)) {
            return stored(scope, instanceId, f.region(), "Refreshed less than a minute ago; showing the data fetched then.");
        }

        Instant to = scope.to().isAfter(now) ? now : scope.to();
        Instant from = scope.from().isBefore(to.minus(MAX_RANGE)) ? to.minus(MAX_RANGE) : scope.from();
        int period = period(Duration.between(from, to), f.detailedMonitoring(), Duration.between(from, now));
        List<Spec> specs = specs(f);

        Map<Spec, TreeMap<Long, Double>> raw;
        try {
            raw = fetch(aws.getCloudWatchClient(account, f.region()), instanceId, specs, from, to, period);
        } catch (SdkException e) {
            releaseRefresh(key);
            log.warn("XamPulse CloudWatch refresh failed for account {}: {}", account.getId(), e.getClass().getSimpleName());
            throw new XamPulseException("XP_AWS_UNAVAILABLE", 502, "CloudWatch did not answer. Check the account connection and try again.");
        }
        Derived d = derive(raw, period);

        List<List<Object>> rows = new ArrayList<>();
        Timestamp fetched = Timestamp.from(now);
        d.instance().forEach((m, pts) -> pts.forEach(p -> rows.add(row(account.getId(), instanceId, m, "", p, fetched))));
        d.volumes().forEach((v, series) -> series.forEach((m, pts) -> pts.forEach(p -> rows.add(row(account.getId(), instanceId, m, v, p, fetched)))));
        try {
            if (!rows.isEmpty()) {
                writes.insert("xp_cloudwatch_points", COLUMNS, rows);
            }
        } catch (Exception e) {
            // The user still sees the fetched data; it just will not be kept for later.
            log.warn("XamPulse could not store CloudWatch points: {}", e.getClass().getSimpleName());
        }
        return new XpCloudWatchView(instanceId, f.region(), from, to, period, d.instance(), d.volumes(), "REFRESH", now,
                specs.size(), Math.round(specs.size() * USD_PER_METRIC * 1_000_000d) / 1_000_000d, null);
    }

    private static List<Object> row(long tenant, String instanceId, String metric, String dim, double[] p, Timestamp fetched) {
        return List.of(tenant, instanceId, metric, dim, new Timestamp((long) p[0]), p[1], fetched);
    }

    /** Coarse enough for about {@link #TARGET_POINTS} points, never finer than what the instance publishes. */
    static int period(Duration range, boolean detailed) {
        return period(range, detailed, Duration.ZERO);
    }

    /**
     * As above, and never finer than CloudWatch still keeps for data that old: when the start time is 15 to 63 days
     * ago the period must be a multiple of 300 s, beyond 63 days a multiple of 3600 s. A finer period returns no
     * datapoints at all, yet every metric requested is still billed.
     *
     * @param age how long ago the requested window starts
     */
    static int period(Duration range, boolean detailed, Duration age) {
        int base = age.compareTo(Duration.ofDays(63)) > 0 ? 3600
                : age.compareTo(Duration.ofDays(15)) > 0 ? 300
                : detailed ? 60 : 300;
        long wanted = Math.max(base, range.getSeconds() / TARGET_POINTS);
        return (int) (((wanted + base - 1) / base) * base);
    }

    static List<Spec> specs(XpEc2Facts f) {
        List<Spec> s = new ArrayList<>();
        for (String[] m : new String[][] {
                {"CPUUtilization", "Average"}, {"NetworkIn", "Sum"}, {"NetworkOut", "Sum"}, {"NetworkPacketsIn", "Sum"},
                {"NetworkPacketsOut", "Sum"}, {"StatusCheckFailed_System", "Maximum"}, {"StatusCheckFailed_Instance", "Maximum"},
                {"EBSIOBalance%", "Average"}, {"EBSByteBalance%", "Average"},
                // 1 for each minute the attached volumes together asked for more than the INSTANCE's EBS limit (Nitro,
                // not bare metal); the average over a period is the share of its minutes at the instance limit.
                {"InstanceEBSIOPSExceededCheck", "Average"}, {"InstanceEBSThroughputExceededCheck", "Average"}}) {
            s.add(new Spec("AWS/EC2", m[0], m[1], null));
        }
        if (f.burstable()) {
            for (String[] m : new String[][] {{"CPUCreditBalance", "Average"}, {"CPUCreditUsage", "Sum"},
                    {"CPUSurplusCreditBalance", "Average"}, {"CPUSurplusCreditsCharged", "Sum"}}) {
                s.add(new Spec("AWS/EC2", m[0], m[1], null));
            }
        }
        for (XpEc2Facts.Volume v : f.volumes()) {
            for (String[] m : new String[][] {{"VolumeReadOps", "Sum"}, {"VolumeWriteOps", "Sum"}, {"VolumeReadBytes", "Sum"},
                    {"VolumeWriteBytes", "Sum"}, {"VolumeTotalReadTime", "Sum"}, {"VolumeTotalWriteTime", "Sum"},
                    {"VolumeQueueLength", "Average"}, {"VolumeIdleTime", "Sum"},
                    // 1 for each minute the application asked this VOLUME for more than it is provisioned for.
                    {"VolumeIOPSExceededCheck", "Average"}, {"VolumeThroughputExceededCheck", "Average"}}) {
                s.add(new Spec("AWS/EBS", m[0], m[1], v.volumeId()));
            }
            if ("gp2".equals(v.type()) || "st1".equals(v.type()) || "sc1".equals(v.type())) {
                s.add(new Spec("AWS/EBS", "BurstBalance", "Average", v.volumeId()));
            }
        }
        return s;
    }

    private static Map<Spec, TreeMap<Long, Double>> fetch(CloudWatchClient cw, String instanceId, List<Spec> specs, Instant from, Instant to, int period) {
        Map<String, Spec> byId = new HashMap<>();
        List<MetricDataQuery> queries = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            Spec sp = specs.get(i);
            String id = "m" + i;
            byId.put(id, sp);
            Dimension dim = sp.volumeId() == null
                    ? Dimension.builder().name("InstanceId").value(instanceId).build()
                    : Dimension.builder().name("VolumeId").value(sp.volumeId()).build();
            queries.add(MetricDataQuery.builder().id(id).returnData(true).metricStat(MetricStat.builder()
                    .metric(Metric.builder().namespace(sp.namespace()).metricName(sp.name()).dimensions(dim).build())
                    .period(period).stat(sp.stat()).build()).build());
        }
        Map<Spec, TreeMap<Long, Double>> out = new LinkedHashMap<>();
        for (int start = 0; start < queries.size(); start += 500) {
            List<MetricDataQuery> batch = queries.subList(start, Math.min(queries.size(), start + 500));
            String token = null;
            do {
                final String t = token;
                GetMetricDataResponse r = cw.getMetricData(b -> b.metricDataQueries(batch).startTime(from).endTime(to)
                        .scanBy(ScanBy.TIMESTAMP_ASCENDING).nextToken(t));
                for (MetricDataResult res : r.metricDataResults()) {
                    TreeMap<Long, Double> pts = out.computeIfAbsent(byId.get(res.id()), k -> new TreeMap<>());
                    for (int i = 0; i < res.timestamps().size() && i < res.values().size(); i++) {
                        pts.put(res.timestamps().get(i).toEpochMilli(), res.values().get(i));
                    }
                }
                token = r.nextToken();
            } while (token != null);
        }
        return out;
    }

    record Derived(Map<String, List<double[]>> instance, Map<String, Map<String, List<double[]>>> volumes) {}

    /** Raw CloudWatch statistics to operator units. Pure, unit-tested. */
    static Derived derive(Map<Spec, TreeMap<Long, Double>> raw, int period) {
        Map<String, TreeMap<Long, Double>> inst = new HashMap<>();
        Map<String, Map<String, TreeMap<Long, Double>>> vol = new HashMap<>();
        raw.forEach((sp, pts) -> {
            if (sp.volumeId() == null) {
                inst.put(sp.name(), pts);
            } else {
                vol.computeIfAbsent(sp.volumeId(), k -> new HashMap<>()).put(sp.name(), pts);
            }
        });
        Map<String, List<double[]>> i = new TreeMap<>();
        put(i, "cpu.utilization", inst.get("CPUUtilization"), 1);
        put(i, "cpu.credit_balance", inst.get("CPUCreditBalance"), 1);
        put(i, "cpu.credit_usage", inst.get("CPUCreditUsage"), 1);
        put(i, "cpu.surplus_balance", inst.get("CPUSurplusCreditBalance"), 1);
        put(i, "cpu.surplus_charged", inst.get("CPUSurplusCreditsCharged"), 1);
        put(i, "net.in_bps", inst.get("NetworkIn"), 1.0 / period);
        put(i, "net.out_bps", inst.get("NetworkOut"), 1.0 / period);
        put(i, "net.in_pps", inst.get("NetworkPacketsIn"), 1.0 / period);
        put(i, "net.out_pps", inst.get("NetworkPacketsOut"), 1.0 / period);
        put(i, "status.system_failed", inst.get("StatusCheckFailed_System"), 1);
        put(i, "status.instance_failed", inst.get("StatusCheckFailed_Instance"), 1);
        put(i, "ebs.io_balance_pct", inst.get("EBSIOBalance%"), 1);
        put(i, "ebs.byte_balance_pct", inst.get("EBSByteBalance%"), 1);
        put(i, "ebs.instance_iops_exceeded_pct", inst.get("InstanceEBSIOPSExceededCheck"), 100);
        put(i, "ebs.instance_throughput_exceeded_pct", inst.get("InstanceEBSThroughputExceededCheck"), 100);

        Map<String, Map<String, List<double[]>>> v = new TreeMap<>();
        vol.forEach((id, m) -> {
            Map<String, List<double[]>> s = new TreeMap<>();
            put(s, "iops.read", m.get("VolumeReadOps"), 1.0 / period);
            put(s, "iops.write", m.get("VolumeWriteOps"), 1.0 / period);
            put(s, "mibps.read", m.get("VolumeReadBytes"), 1.0 / period / 1048576.0);
            put(s, "mibps.write", m.get("VolumeWriteBytes"), 1.0 / period / 1048576.0);
            ratio(s, "latency_ms.read", m.get("VolumeTotalReadTime"), m.get("VolumeReadOps"), 1000);
            ratio(s, "latency_ms.write", m.get("VolumeTotalWriteTime"), m.get("VolumeWriteOps"), 1000);
            put(s, "queue_length", m.get("VolumeQueueLength"), 1);
            put(s, "burst_balance", m.get("BurstBalance"), 1);
            put(s, "idle_pct", m.get("VolumeIdleTime"), 100.0 / period);
            put(s, "iops_exceeded_pct", m.get("VolumeIOPSExceededCheck"), 100);
            put(s, "throughput_exceeded_pct", m.get("VolumeThroughputExceededCheck"), 100);
            if (!s.isEmpty()) {
                v.put(id, s);
            }
        });
        return new Derived(i, v);
    }

    private static void put(Map<String, List<double[]>> into, String key, TreeMap<Long, Double> pts, double factor) {
        if (pts == null || pts.isEmpty()) {
            return;
        }
        List<double[]> out = new ArrayList<>(pts.size());
        pts.forEach((t, val) -> out.add(new double[] {t, val * factor}));
        into.put(key, out);
    }

    /** {@code num / den * factor} at timestamps where both exist and den > 0 (e.g. seconds per op -> ms). */
    private static void ratio(Map<String, List<double[]>> into, String key, TreeMap<Long, Double> num, TreeMap<Long, Double> den, double factor) {
        if (num == null || den == null) {
            return;
        }
        List<double[]> out = new ArrayList<>();
        num.forEach((t, n) -> {
            Double d = den.get(t);
            if (d != null && d > 0) {
                out.add(new double[] {t, n / d * factor});
            }
        });
        if (!out.isEmpty()) {
            into.put(key, out);
        }
    }
}
