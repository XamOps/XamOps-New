package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.config.ClickHouseConnections;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.service.insight.EventWriter;
import com.xammer.cloud.xampulse.service.platform.JdbcClickHouseMutations;
import com.xammer.cloud.xampulse.service.platform.JdbcClickHouseWrites;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round trip against a REAL ClickHouse (deploy/xampulse/docker-compose.yml after scripts/apply-schema.sh).
 * Skips cleanly when nothing is listening or credentials are not available.
 *
 * <p>Run: {@code mvn -pl xamops-service test -Dtest=ClickHouseRoundTripIT -Dgroups=containers -Dsurefire.excludedGroups=}
 * Environment: {@code XAMPULSE_IT_CLICKHOUSE_URL} (default jdbc:clickhouse://127.0.0.1:8123/xampulse),
 * {@code XP_CH_READER_PASSWORD}, {@code XP_CH_WRITER_PASSWORD} (or deploy/xampulse/.env).
 */
@Tag("containers")
class ClickHouseRoundTripIT {

    private static final long T1 = 9_000_001L;
    private static final long T2 = 9_000_002L;
    /** Backtest data has its own tenant: its counters must not add to other tests' unfiltered reads. */
    private static final long T3 = 9_000_003L;

    private static ClickHouseConnections connections;
    private static ClickHouseExecutor executor;
    private static JdbcClickHouseMutations mutations;
    private static String url;
    private static String readerUser;
    private static String readerPassword;

    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    @BeforeAll
    static void connect() {
        url = System.getenv().getOrDefault("XAMPULSE_IT_CLICKHOUSE_URL", "jdbc:clickhouse://127.0.0.1:8123/xampulse");
        Map<String, String> env = new HashMap<>(readDotEnv());
        env.putAll(System.getenv());
        readerUser = env.getOrDefault("XP_CH_READER_USER", "xp_reader");
        readerPassword = env.get("XP_CH_READER_PASSWORD");
        String writerUser = env.getOrDefault("XP_CH_WRITER_USER", "xp_writer");
        String writerPassword = env.get("XP_CH_WRITER_PASSWORD");
        assumeTrue(readerPassword != null && writerPassword != null, "ClickHouse credentials not provided: skipping");
        assumeTrue(reachable(url), "ClickHouse not reachable at the IT url: skipping");

        XamPulseProperties props = new XamPulseProperties();
        props.getClickhouse().setUrl(url);
        props.getClickhouse().setReaderUser(readerUser);
        props.getClickhouse().setReaderPassword(readerPassword);
        props.getClickhouse().setWriterUser(writerUser);
        props.getClickhouse().setWriterPassword(writerPassword);
        connections = new ClickHouseConnections(props.getClickhouse());
        executor = new ClickHouseExecutor(new JdbcClickHouseGateway(connections), props);
        mutations = new JdbcClickHouseMutations(connections);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (connections != null) {
            try {
                mutations.execute("ALTER TABLE xp_events DELETE WHERE tenant_id IN (?, ?, ?)", List.of(T1, T2, T3));
                for (String t : List.of("xp_metric_points", "xp_metrics_1m", "xp_metrics_5m", "xp_metrics_1h")) {
                    mutations.execute("ALTER TABLE " + t + " DELETE WHERE tenant_id IN (?, ?, ?)", List.of(T1, T2, T3));
                }
                for (String t : List.of("xp_land_metrics_sum", "xp_land_metrics_gauge")) {
                    mutations.execute("ALTER TABLE " + t + " DELETE WHERE ResourceAttributes['xp.tenant_id'] IN (?, ?, ?)",
                            List.of(String.valueOf(T1), String.valueOf(T2), String.valueOf(T3)));
                }
            } finally {
                connections.close();
            }
        }
    }

    /**
     * Counters the way hostmetrics sends them: cumulative, per device, scraped every 30 s. ens5 receives exactly
     * 1,000 B/s on top of a large lifetime total and restarts from zero between samples 11 and 12 (a reboot); an idle
     * bridge holds a big constant total and lo stays at zero. The old {@code (max - min) / step} read the bridge's
     * total minus lo's zero (about 3.3 GB/s here); the rate must be exactly 1,000 B/s, the restart minute (whose
     * increase is unknown) must be absent rather than a spike, a dip or a negative value, and adding all devices must
     * still give 1,000 B/s.
     */
    @Test
    void counterRateIsWorkedOutPerSeriesAndSurvivesARestart() throws Exception {
        long base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(30 * 60).getEpochSecond();
        String insert = "INSERT INTO xp_land_metrics_sum (ResourceAttributes, MetricName, Attributes, TimeUnix, Value, "
                + "AggregationTemporality, IsMonotonic) SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-rate'), "
                + "'system.network.io', map('device', ?, 'direction', 'receive'), toDateTime64(? + number * 30, 9, 'UTC'), "
                + "%s, 2, true FROM numbers(21)";
        String tenant = String.valueOf(T1);
        mutations.execute(insert.formatted("if(number < 12, toFloat64(500000000000 + number * 30000), toFloat64(15000) + (toFloat64(number) - 12) * 30000)"),
                List.of(tenant, "ens5", base));
        mutations.execute(insert.formatted("toFloat64(200000000000)"), List.of(tenant, "br-idle", base));
        mutations.execute(insert.formatted("toFloat64(0)"), List.of(tenant, "lo", base));

        QueryScope scope = QueryScope.of(List.of(T1), Instant.ofEpochSecond(base + 60), Instant.ofEpochSecond(base + 600));
        TenantScopedQueryBuilder.MetricPlan plan = TenantScopedQueryBuilder.planMetric(scope, null);
        assertEquals(60, plan.stepSec());
        MetricSpec ens5 = new MetricSpec("system.network.io", "rate", List.of("direction"),
                List.of(new MetricSpec.LabelFilter("direction", "=", "receive"), new MetricSpec.LabelFilter("device", "=~", "ens[0-9]+")));
        Map<Long, Double> perMinute = awaitSeries(scope, ens5, plan, 8);
        assertEquals(8, perMinute.size(), "minutes 1..9 except the restart minute 6: " + perMinute);
        assertFalse(perMinute.containsKey(base + 6 * 60), "the restart minute has no measurable increase");
        perMinute.forEach((t, v) -> assertEquals(1000.0, v, 1e-6, "minute " + (t - base) / 60));

        MetricSpec allDevices = new MetricSpec("system.network.io", "rate", List.of(),
                List.of(new MetricSpec.LabelFilter("direction", "=", "receive")));
        Map<Long, Double> summed = awaitSeries(scope, allDevices, plan, 8);
        assertEquals(8, summed.size(), "the group's restart minute is left out too: " + summed);
        summed.forEach((t, v) -> assertEquals(1000.0, v, 1e-6, "idle bridge and lo add nothing, minute " + (t - base) / 60));

        QueryScope window = QueryScope.of(List.of(T1), Instant.ofEpochSecond(base + 60), Instant.ofEpochSecond(base + 300));
        List<Map<String, Object>> alert = executor.query(builder.alertMetric(window, ens5));
        assertEquals(1, alert.size(), String.valueOf(alert));
        assertEquals(1000.0, ((Number) alert.get(0).get("v")).doubleValue(), 1e-6);
    }

    /**
     * Baselines must learn what the pages show. hostmetrics sends CPU as one series per state and memory as one per
     * state, so the old "average of the metric" learned 1/8 for CPU (12.5% on a host that is 80% busy) and about 1/3
     * for memory here. CPU must be 1 - idle, memory the used state, disk the fullest mount.
     */
    @Test
    void baselinesLearnTheHostLevelValueNotTheAverageOfTheStates() throws Exception {
        long hour = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(3, ChronoUnit.HOURS).getEpochSecond();
        String tenant = String.valueOf(T1);
        String gauge = "INSERT INTO xp_land_metrics_gauge (ResourceAttributes, MetricName, Attributes, TimeUnix, Value) "
                + "SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-base'), ?, map(?, ?), toDateTime64(? + number * 600, 9, 'UTC'), toFloat64(?) "
                + "FROM numbers(6)";
        Object[][] points = {
                {"system.cpu.utilization", "state", "idle", 0.20}, {"system.cpu.utilization", "state", "user", 0.60},
                {"system.cpu.utilization", "state", "system", 0.10}, {"system.cpu.utilization", "state", "wait", 0.05},
                {"system.cpu.utilization", "state", "steal", 0.05},
                {"system.memory.utilization", "state", "used", 0.70}, {"system.memory.utilization", "state", "free", 0.20},
                {"system.memory.utilization", "state", "cached", 0.10},
                {"system.filesystem.utilization", "mountpoint", "/", 0.40}, {"system.filesystem.utilization", "mountpoint", "/var", 0.90},
        };
        for (Object[] p : points) {
            mutations.execute(gauge, List.of(tenant, p[0], p[1], p[2], hour, p[3]));
        }
        QueryScope scope = QueryScope.of(List.of(T1), Instant.ofEpochSecond(hour - 3600), Instant.now());
        Map<String, Double> expected = Map.of("system.cpu.utilization", 0.80, "system.memory.utilization", 0.70,
                "system.filesystem.utilization", 0.90);
        for (Map.Entry<String, Double> e : expected.entrySet()) {
            List<Map<String, Object>> rows = List.of();
            for (int i = 0; i < 20; i++) {
                rows = executor.query(builder.baselineSamples(scope, e.getKey(), 7)).stream()
                        .filter(r -> "i-it-base".equals(String.valueOf(r.get("instance_id")))).toList();
                if (!rows.isEmpty()) {
                    break;
                }
                Thread.sleep(300);
            }
            assertEquals(1, rows.size(), e.getKey() + ": one hourly sample " + rows);
            assertEquals(e.getValue(), ((Number) rows.get(0).get("v")).doubleValue(), 1e-9, e.getKey());
        }
    }

    /**
     * Processes and containers the way the collector sends them: identity on the RESOURCE (V011 copies it into the
     * labels), process CPU as one series per state. Top processes must add the states of the latest scrape (not show
     * one state, not an older scrape), and every container must stay its own series through the minute rollup.
     */
    @Test
    void processAndContainerIdentityArriveFromResourceAttributes() throws Exception {
        long t = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(20 * 60).getEpochSecond();
        String tenant = String.valueOf(T1);
        String gauge = "INSERT INTO xp_land_metrics_gauge (ResourceAttributes, MetricName, Attributes, TimeUnix, Value) "
                + "SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-ident'%s), ?, map(%s), toDateTime64(?, 9, 'UTC'), toFloat64(?)";
        String java = ", 'process.pid', '4121', 'process.executable.name', 'java', 'process.owner', 'app', 'process.command_line', 'java -Dpw=x'";
        // An older scrape that must not win, then the latest one split by state: 0.30 + 0.10 + 0.02 = 0.42.
        mutations.execute(gauge.formatted(java, "'state', 'user'"), List.of(tenant, "process.cpu.utilization", t - 30, 0.90));
        for (Object[] s : new Object[][] {{"user", 0.30}, {"system", 0.10}, {"wait", 0.02}}) {
            mutations.execute(gauge.formatted(java, "'state', '" + s[0] + "'"), List.of(tenant, "process.cpu.utilization", t, s[1]));
        }
        mutations.execute(gauge.formatted(java, ""), List.of(tenant, "process.memory.utilization", t, 0.25));
        for (String[] c : new String[][] {{"aaa111", "api", "35.5"}, {"bbb222", "db", "120"}}) {
            mutations.execute(gauge.formatted(", 'container.id', '" + c[0] + "', 'container.name', '" + c[1] + "', 'container.image.name', 'img:1'", ""),
                    List.of(tenant, "container.cpu.utilization", t, Double.parseDouble(c[2])));
        }

        QueryScope scope = QueryScope.of(List.of(T1), Instant.ofEpochSecond(t - 600), Instant.ofEpochSecond(t + 600));
        Map<String, Double> byMetric = new HashMap<>();
        for (int i = 0; i < 10 && byMetric.size() < 2; i++) {
            for (Map<String, Object> r : executor.query(builder.processLatest(scope, "i-it-ident", t + 1))) {
                assertEquals("4121", String.valueOf(r.get("pid")));
                assertEquals("java", String.valueOf(r.get("name")));
                assertEquals("app", String.valueOf(r.get("user")));
                byMetric.put(String.valueOf(r.get("metric")), ((Number) r.get("v")).doubleValue());
            }
            if (byMetric.size() < 2) {
                Thread.sleep(300);
            }
        }
        assertEquals(0.42, byMetric.get("process.cpu.utilization"), 1e-9, "states of the latest scrape are added up");
        assertEquals(0.25, byMetric.get("process.memory.utilization"), 1e-9);

        List<Map<String, Object>> containers = executor.query(builder.latestMetrics(
                QueryScope.of(List.of(T1), Instant.ofEpochSecond(t - 600), Instant.ofEpochSecond(t + 600)), t - 600, List.of("container.cpu.utilization")));
        assertEquals(2, containers.size(), "one series per container: " + containers);
        for (Map<String, Object> r : containers) {
            String key = String.valueOf(r.get("labels_key"));
            assertTrue(key.contains("container.id=aaa111") || key.contains("container.id=bbb222"), key);
            assertFalse(key.contains("command_line"), key);
        }
    }

    /**
     * Throttle Radar end to end: the xampulse_ec2 receiver's metrics, as it sends them, through landing, V011 and the
     * minute rollup. ens5's PPS allowance counter rises 100 per 30 s scrape (1,800 in the 9 measured minutes), CPU
     * PSI "some" rises 9 s per 30 s (30% of the time stalled), and IMDS reports a Spot terminate notice.
     */
    @Test
    void throttleRadarFindsAwsAndKernelLimitsFromReceiverData() throws Exception {
        long base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(40 * 60).getEpochSecond();
        String tenant = String.valueOf(T1);
        String sum = "INSERT INTO xp_land_metrics_sum (ResourceAttributes, MetricName, Attributes, TimeUnix, Value, "
                + "AggregationTemporality, IsMonotonic) SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-radar'), ?, map(%s), "
                + "toDateTime64(? + number * 30, 9, 'UTC'), %s, 2, true FROM numbers(21)";
        mutations.execute(sum.formatted("'device', 'ens5', 'allowance', 'pps'", "toFloat64(5000 + number * 100)"),
                List.of(tenant, "xp.ena.allowance_exceeded", base));
        mutations.execute(sum.formatted("'device', 'ens5', 'allowance', 'bw_in'", "toFloat64(7)"),
                List.of(tenant, "xp.ena.allowance_exceeded", base));
        mutations.execute(sum.formatted("'resource', 'cpu', 'level', 'some'", "toFloat64(1000 + number * 9)"),
                List.of(tenant, "xp.pressure.stall_time", base));
        mutations.execute("INSERT INTO xp_land_metrics_gauge (ResourceAttributes, MetricName, Attributes, TimeUnix, Value) "
                + "SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-radar'), 'xp.ec2.spot.interruption', "
                + "map('action', 'terminate', 'time', '2026-11-12T10:42:00Z'), toDateTime64(? + 590, 9, 'UTC'), toFloat64(1)",
                List.of(tenant, base));

        QueryScope host = new QueryScope(List.of(T1), Instant.ofEpochSecond(base + 60), Instant.ofEpochSecond(base + 600),
                List.of(), List.of("i-it-radar"), List.of(), List.of());
        com.xammer.cloud.xampulse.service.insight.ThrottleRadarService radar =
                new com.xammer.cloud.xampulse.service.insight.ThrottleRadarService(null, executor);
        com.xammer.cloud.xampulse.dto.XpHostLimits limits = null;
        for (int i = 0; i < 10 && (limits == null || limits.findings().size() < 3); i++) {
            limits = radar.limitsInScope(host, "i-it-radar", null);
            if (limits.findings().size() < 3) {
                Thread.sleep(300);
            }
        }
        Map<String, com.xammer.cloud.xampulse.dto.XpHostLimits.Finding> byCode = new HashMap<>();
        limits.findings().forEach(f -> byCode.put(f.code(), f));
        assertEquals(java.util.Set.of("spot_interruption", "ena_pps", "psi_cpu_some"), byCode.keySet(), String.valueOf(limits.findings()));
        assertEquals("spot_interruption", limits.findings().get(0).code(), "the critical Spot notice comes first");
        assertEquals(1800.0, byCode.get("ena_pps").evidence().get("packets"), 1e-9, "exact count over the 9 measured minutes");
        assertEquals(0.30, byCode.get("psi_cpu_some").evidence().get("stalled_share"), 1e-9);
        assertTrue(byCode.get("spot_interruption").title().contains("terminate"));
        Map<String, String> states = new HashMap<>();
        limits.signals().forEach(s -> states.put(s.code(), s.state()));
        assertEquals("LIMITED", states.get("ena"));
        assertEquals("NOT_MEASURED", states.get("conntrack_table"), "no conntrack data was sent");
    }

    /**
     * OS limits end to end, shaped the way hostmetrics (inodes: one series per mount and state) and the agent's
     * receiver (gauges with a state label, cumulative counters by type / event) send them: each must reach its check.
     */
    @Test
    void throttleRadarFindsOsLimitsFromHostmetricsAndReceiverData() throws Exception {
        long base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(30 * 60).getEpochSecond();
        String tenant = String.valueOf(T1);
        String gauge = "INSERT INTO xp_land_metrics_gauge (ResourceAttributes, MetricName, Attributes, TimeUnix, Value) "
                + "SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-os'), ?, map(%s), toDateTime64(? + 590, 9, 'UTC'), toFloat64(?)";
        Object[][] gauges = {
                {"system.filesystem.inodes.usage", "'device', 'nvme0n1p1', 'mountpoint', '/', 'state', 'used'", 970_000},
                {"system.filesystem.inodes.usage", "'device', 'nvme0n1p1', 'mountpoint', '/', 'state', 'free'", 30_000},
                {"xp.process.open_files", "'pid', '4121', 'name', 'java', 'limit', '1024'", 990},
                {"xp.fs.file_handles", "'state', 'allocated'", 9_000}, {"xp.fs.file_handles", "'state', 'max'", 9_000_000},
                {"xp.net.local_ports", "'state', 'used'", 25_000}, {"xp.net.local_ports", "'state', 'range'", 28_232},
        };
        for (Object[] g : gauges) {
            mutations.execute(gauge.formatted(g[1]), List.of(tenant, g[0], base, g[2]));
        }
        String sum = "INSERT INTO xp_land_metrics_sum (ResourceAttributes, MetricName, Attributes, TimeUnix, Value, "
                + "AggregationTemporality, IsMonotonic) SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-os'), ?, map(%s), "
                + "toDateTime64(? + number * 30, 9, 'UTC'), %s, 2, true FROM numbers(21)";
        mutations.execute(sum.formatted("'type', 'out'", "toFloat64(50000000 + number * 100000)"), List.of(tenant, "xp.tcp.segments", base));
        mutations.execute(sum.formatted("'type', 'retrans'", "toFloat64(900000 + number * 3500)"), List.of(tenant, "xp.tcp.segments", base));
        mutations.execute(sum.formatted("'event', 'overflow'", "toFloat64(12 + number * 45)"), List.of(tenant, "xp.tcp.listen_queue", base));

        QueryScope host = new QueryScope(List.of(T1), Instant.ofEpochSecond(base + 60), Instant.ofEpochSecond(base + 600),
                List.of(), List.of("i-it-os"), List.of(), List.of());
        com.xammer.cloud.xampulse.service.insight.ThrottleRadarService radar =
                new com.xammer.cloud.xampulse.service.insight.ThrottleRadarService(null, executor);
        java.util.Set<String> expected = java.util.Set.of("inodes", "file_handles_process", "local_ports", "tcp_retransmits", "listen_queue");
        Map<String, com.xammer.cloud.xampulse.dto.XpHostLimits.Finding> byCode = new HashMap<>();
        for (int i = 0; i < 15 && !byCode.keySet().containsAll(expected); i++) {
            byCode.clear();
            radar.limitsInScope(host, "i-it-os", null).findings().forEach(f -> byCode.put(f.code(), f));
            if (!byCode.keySet().containsAll(expected)) {
                Thread.sleep(300);
            }
        }
        assertEquals(expected, byCode.keySet(), String.valueOf(byCode.values()));
        assertTrue(byCode.get("inodes").title().contains("Inodes on / are 97% used"), byCode.get("inodes").title());
        assertEquals("java has 990 of its 1024 open files in use", byCode.get("file_handles_process").title());
        assertEquals(0.035, byCode.get("tcp_retransmits").evidence().get("share"), 1e-9, "3,500 of 100,000 segments per scrape");
    }

    /**
     * Change Intelligence end to end: container "api" runs image api:1 (CPU ~20%) and is redeployed as api:2 at
     * minute 40 (CPU ~80%); the host's kernel CPU counter starts again from zero at minute 70 (a reboot).
     */
    @Test
    void changeIntelligenceSeesADeployItsEffectAndAReboot() throws Exception {
        long base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(150 * 60).getEpochSecond();
        String tenant = String.valueOf(T1);
        String gauge = "INSERT INTO xp_land_metrics_gauge (ResourceAttributes, MetricName, Attributes, TimeUnix, Value) "
                + "SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-chg', 'container.id', ?, 'container.name', 'api', "
                + "'container.image.name', ?), 'container.cpu.utilization', map(), toDateTime64(? + number * 30, 9, 'UTC'), "
                + "toFloat64(%s + (number %% 3)) FROM numbers(%d)";
        mutations.execute(gauge.formatted("20", 80), List.of(tenant, "aaaaaaaaaaaa1111", "api:1", base));              // minutes 0..39
        mutations.execute(gauge.formatted("80", 92), List.of(tenant, "bbbbbbbbbbbb2222", "api:2", base + 40 * 60));    // minutes 40..85 (still running at the end)
        mutations.execute("INSERT INTO xp_land_metrics_sum (ResourceAttributes, MetricName, Attributes, TimeUnix, Value, AggregationTemporality, IsMonotonic) "
                + "SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-chg'), 'system.cpu.time', map('state', 'idle'), "
                + "toDateTime64(? + number * 30, 9, 'UTC'), if(number < 140, toFloat64(900000 + number * 25), (toFloat64(number) - 140) * 25 + 10), 2, true "
                + "FROM numbers(170)", List.of(tenant, base));

        com.xammer.cloud.xampulse.service.insight.ChangeIntelligenceService svc =
                new com.xammer.cloud.xampulse.service.insight.ChangeIntelligenceService(null, executor);
        Instant from = Instant.ofEpochSecond(base + 10 * 60);
        Instant to = Instant.ofEpochSecond(base + 85 * 60);
        List<com.xammer.cloud.xampulse.dto.XpChange> changes = List.of();
        for (int i = 0; i < 10 && changes.size() < 2; i++) {
            changes = svc.changesInScope(T1, "i-it-chg", null, from, to, Instant.now());
            if (changes.size() < 2) {
                Thread.sleep(300);
            }
        }
        Map<String, com.xammer.cloud.xampulse.dto.XpChange> byKind = new HashMap<>();
        changes.forEach(c -> byKind.put(c.kind(), c));
        assertEquals(java.util.Set.of("DEPLOY", "REBOOT"), byKind.keySet(), String.valueOf(changes));
        com.xammer.cloud.xampulse.dto.XpChange deploy = byKind.get("DEPLOY");
        assertEquals(Instant.ofEpochSecond(base + 40 * 60), deploy.ts());
        assertEquals("Container api deployed api:2", deploy.title());
        assertEquals("api", deploy.subject());
        assertEquals("FOLLOWED_BY_CHANGE", deploy.verdict(), String.valueOf(deploy));
        com.xammer.cloud.xampulse.dto.XpChange.Effect cpu = deploy.effects().stream()
                .filter(e -> e.signal().equals("container_cpu")).findFirst().orElseThrow();
        assertEquals("STRONG", cpu.strength());
        assertEquals(21.0, cpu.before(), 1.0);
        assertEquals(81.0, cpu.after(), 1.0);
        assertEquals(Instant.ofEpochSecond(base + 70 * 60), byKind.get("REBOOT").ts(), "the minute the counter started again");
    }

    private static long backtestLast(Instant to) {
        return to.getEpochSecond() / 60 * 60 - 60;
    }

    private static long backtestFrom(Instant to) {
        return backtestLast(to) - 1800;
    }

    /**
     * Alert backtest equals live evaluation: at every replayed minute m the backtest value must be exactly what the
     * live evaluator's own statement returns when it runs at m + 60 over the rule window (absent where it has no row).
     * Two CPU series with a four-minute gap; a network counter that restarts and has a gap.
     */
    @Test
    void backtestReplaysExactlyWhatTheLiveEvaluatorWouldHaveRead() throws Exception {
        long base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(60 * 60).getEpochSecond();
        String tenant = String.valueOf(T3);
        String gauge = "INSERT INTO xp_land_metrics_gauge (ResourceAttributes, MetricName, Attributes, TimeUnix, Value) "
                + "SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-bt'), 'system.cpu.utilization', map('cpu', ?), "
                + "toDateTime64(? + number * 30, 9, 'UTC'), %s FROM numbers(90) WHERE number < 30 OR number >= 38";
        mutations.execute(gauge.formatted("toFloat64((number * 37) % 100) / 100"), List.of(tenant, "0", base));
        mutations.execute(gauge.formatted("toFloat64((number * 53) % 90) / 100"), List.of(tenant, "1", base));
        mutations.execute("INSERT INTO xp_land_metrics_sum (ResourceAttributes, MetricName, Attributes, TimeUnix, Value, "
                + "AggregationTemporality, IsMonotonic) SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-bt'), 'system.network.io', "
                + "map('device', 'ens5', 'direction', 'receive'), toDateTime64(? + number * 30, 9, 'UTC'), "
                + "if(number < 50, toFloat64(9000000 + number * number * 700), toFloat64((number - 50) * 30000)), 2, true "
                + "FROM numbers(90) WHERE number < 60 OR number >= 66", List.of(tenant, base));

        long w = 300;
        long fromMin = base + 6 * 60;
        long lastMin = base + 44 * 60;
        QueryScope scope = new QueryScope(List.of(T3), Instant.ofEpochSecond(fromMin), Instant.ofEpochSecond(lastMin + 60),
                List.of(), List.of("i-it-bt"), List.of(), List.of());
        List<MetricSpec> specs = new java.util.ArrayList<>();
        for (String agg : List.of("avg", "min", "max", "sum", "p95")) {
            specs.add(new MetricSpec("system.cpu.utilization", agg, List.of("instance_id"), List.of()));
        }
        specs.add(new MetricSpec("system.network.io", "rate", List.of("instance_id"), List.of(new MetricSpec.LabelFilter("direction", "=", "receive"))));
        for (int i = 0; i < 20 && executor.query(builder.backtestMetric(scope, specs.get(0), fromMin, lastMin, w, 10_000)).size() < 30; i++) {
            Thread.sleep(300);
        }
        for (MetricSpec spec : specs) {
            ScopedSql bt = "rate".equals(spec.agg()) ? builder.backtestCounterRate(scope, spec, fromMin, lastMin, w, 10_000)
                    : builder.backtestMetric(scope, spec, fromMin, lastMin, w, 10_000);
            Map<Long, Double> replay = new java.util.TreeMap<>();
            for (Map<String, Object> r : executor.query(bt)) {
                assertEquals("i-it-bt", r.get("g0"));
                replay.put(((Number) r.get("t")).longValue(), ((Number) r.get("v")).doubleValue());
            }
            int compared = 0;
            for (long m = fromMin; m <= lastMin; m += 60) {
                QueryScope live = new QueryScope(List.of(T3), Instant.ofEpochSecond(m + 60 - w), Instant.ofEpochSecond(m + 60),
                        List.of(), List.of("i-it-bt"), List.of(), List.of());
                List<Map<String, Object>> rows = executor.query(builder.alertMetric(live, spec));
                Double expected = rows.isEmpty() || rows.get(0).get("v") == null ? null : ((Number) rows.get(0).get("v")).doubleValue();
                if (expected != null && Double.isNaN(expected)) {
                    expected = null;
                }
                String at = spec.agg() + " at minute " + (m - base) / 60;
                if (expected == null) {
                    assertFalse(replay.containsKey(m), at + ": live reads nothing, so the replay must not invent a value");
                } else {
                    assertTrue(replay.containsKey(m), at + ": live reads " + expected);
                    assertEquals(expected, replay.get(m), Math.abs(expected) * 1e-9 + 1e-12, at);
                    compared++;
                }
            }
            assertTrue(compared >= 25, spec.agg() + ": compared " + compared + " minutes");
        }
    }

    /**
     * Exposure check on real data: listening sockets the way the xampulse_ec2 receiver sends them (a gauge per socket
     * with protocol, port, bind and address) must come back through the rollup as labels the exposure service reads,
     * and a socket that stopped being reported (closed) must not count as listening.
     */
    @Test
    void listeningSocketsArriveAsLabelsTheExposureCheckReads() throws Exception {
        long now = Instant.now().getEpochSecond();
        String tenant = String.valueOf(T3);
        String gauge = "INSERT INTO xp_land_metrics_gauge (ResourceAttributes, MetricName, Attributes, TimeUnix, Value) "
                + "SELECT map('xp.tenant_id', ?, 'xp.instance_id', 'i-it-exp'), 'xp.net.listening', "
                + "map('protocol', ?, 'port', ?, 'bind', ?, 'address', ?), toDateTime64(?, 9, 'UTC'), toFloat64(1)";
        mutations.execute(gauge, List.of(tenant, "tcp", "22", "all", "0.0.0.0", now - 30));
        mutations.execute(gauge, List.of(tenant, "tcp", "6379", "loopback", "127.0.0.1", now - 30));
        mutations.execute(gauge, List.of(tenant, "tcp", "8080", "all", "::", now - 600)); // closed ten minutes ago
        QueryScope scope = new QueryScope(List.of(T3), Instant.ofEpochSecond(now - 900), Instant.ofEpochSecond(now + 60),
                List.of(), List.of("i-it-exp"), List.of(), List.of());
        List<Map<String, Object>> rows = List.of();
        for (int i = 0; i < 20 && rows.size() < 3; i++) {
            rows = executor.query(builder.latestMetrics(scope, now - 900, List.of("xp.net.listening")));
            if (rows.size() < 3) {
                Thread.sleep(300);
            }
        }
        assertEquals(3, rows.size(), String.valueOf(rows));
        var socks = com.xammer.cloud.xampulse.service.insight.ExposureTestAccess.latest(rows);
        assertEquals(List.of("tcp|22|all|0.0.0.0", "tcp|6379|loopback|127.0.0.1"), socks, "the closed socket is not listening any more");
    }

    private Map<Long, Double> awaitSeries(QueryScope scope, MetricSpec spec, TenantScopedQueryBuilder.MetricPlan plan, int expected)
            throws InterruptedException {
        Map<Long, Double> out = new java.util.TreeMap<>();
        for (int i = 0; i < 10 && out.size() < expected; i++) {
            out.clear();
            for (Map<String, Object> r : executor.query(builder.metricSeries(scope, spec, plan))) {
                out.put(((Number) r.get("t")).longValue(), ((Number) r.get("v")).doubleValue());
            }
            if (out.size() < expected) {
                Thread.sleep(300);
            }
        }
        return out;
    }

    @Test
    void writerRowsAreVisibleToTheReaderOnlyForTheirOwnTenant() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        EventWriter writer = new EventWriter(new JdbcClickHouseWrites(connections));
        writer.record(T1, "ALERT", "it", "round trip", "detail", now);

        QueryScope s1 = QueryScope.of(List.of(T1), now.minusSeconds(600), now.plusSeconds(600));
        QueryScope s2 = QueryScope.of(List.of(T2), now.minusSeconds(600), now.plusSeconds(600));
        // Inserts are asynchronous-free on the native format, but allow a short settle.
        List<Map<String, Object>> mine = awaitRows(s1);
        assertFalse(mine.isEmpty(), "tenant 1 should read its own event");
        assertEquals("round trip", String.valueOf(mine.get(0).get("title")));
        assertTrue(executor.query(builder.events(s2, List.of("ALERT"), 10)).isEmpty(), "tenant 2 must see nothing");
    }

    @Test
    void aReaderStatementWithoutTheTenantSettingReturnsNoRows() throws Exception {
        try (Connection c = DriverManager.getConnection(url, readerUser, readerPassword);
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT count() FROM xp_events")) {
            assertTrue(rs.next());
            assertEquals(0L, rs.getLong(1), "the row policy must hide every row when SQL_xp_tenant is the default");
        }
    }

    @Test
    void everyQueryShapeTheServicesUseExecutesAgainstTheLiveReader() {
        Instant to = Instant.now();
        QueryScope scope = QueryScope.of(List.of(T1), to.minusSeconds(3600), to);
        FilterAst none = FilterDslParser.parse("");
        MetricSpec avg = new MetricSpec("system.cpu.utilization", "avg", List.of(), List.of());
        MetricSpec rate = new MetricSpec("system.disk.io", "rate", List.of("direction"), List.of());
        // Filtered shapes too: an unfiltered search never reached the WHERE clause where a SELECT alias named like a
        // column ("toString(severity) AS severity") broke every severity filter on ClickHouse 25.8.
        FilterAst errors = FilterDslParser.parse("severity:>=error service:api \"connection reset\"");
        List<ScopedSql> shapes = List.of(
                builder.search(scope, none, true, 10, null),
                builder.search(scope, errors, true, 10, null),
                builder.search(scope, FilterDslParser.parse("severity:warn"), false, 10, null),
                builder.histogram(scope, errors, 60),
                builder.facet(scope, errors, LogField.SEVERITY, null, 5),
                builder.patterns(scope, errors, 10),
                builder.histogram(scope, none, 60),
                builder.facet(scope, none, LogField.SOURCE, null, 5),
                builder.patterns(scope, none, 10),
                builder.metricSeries(scope, avg, TenantScopedQueryBuilder.planMetric(scope, null)),
                builder.metricSeries(scope, rate, TenantScopedQueryBuilder.planMetric(scope, null)),
                builder.alertMetric(scope, rate),
                builder.lastHeartbeat(scope),
                builder.seriesSpans(scope, "container.cpu.utilization", List.of(new MetricSpec.LabelFilter("container.name", "=", "api"))),
                builder.counterSteps(scope, "system.cpu.time", List.of(new MetricSpec.LabelFilter("state", "=", "idle")), 300),
                builder.errorsPerMinute(scope, "i-it"),
                builder.counterIncrease(scope, "xp.ena.allowance_exceeded", List.of(), "allowance"),
                builder.latestMetrics(scope, to.getEpochSecond() - 3600, List.of("system.cpu.utilization")),
                builder.metricNames(scope),
                builder.baselineSamples(scope, "system.cpu.utilization", 7),
                builder.processLatest(scope, "i-it", to.getEpochSecond()),
                builder.events(scope, List.of("ALERT"), 10),
                builder.usageByDay(scope),
                builder.usageBySource(scope),
                builder.backtestLogCount(scope, FilterDslParser.parse("severity:error"), backtestFrom(to), backtestLast(to), 300, 1000),
                builder.backtestPresence(scope, to.getEpochSecond() - 7200, to.getEpochSecond(), 1000));
        for (ScopedSql q : shapes) {
            assertDoesNotThrow(() -> executor.query(q), q.sql());
        }
    }

    @Test
    void v009RejectsRaisingLimitsOrChangingReadonly() throws Exception {
        try (Connection c = DriverManager.getConnection(url, readerUser, readerPassword); Statement st = c.createStatement()) {
            // Only meaningful when V009 has been applied (apply-schema.sh); a profile without it would accept these.
            boolean raised = accepts(st, "SELECT 1 SETTINGS max_rows_to_read = 9999999999");
            boolean readonlyOff = accepts(st, "SELECT 1 SETTINGS readonly = 0");
            assertFalse(raised, "max_rows_to_read above the V009 MAX must be rejected");
            assertFalse(readonlyOff, "readonly must not be lowered by the reader");
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> awaitRows(QueryScope scope) throws InterruptedException {
        List<Map<String, Object>> rows = List.of();
        for (int i = 0; i < 10 && rows.isEmpty(); i++) {
            rows = executor.query(builder.events(scope, List.of("ALERT"), 10));
            if (rows.isEmpty()) {
                Thread.sleep(300);
            }
        }
        return rows;
    }

    private static boolean accepts(Statement st, String sql) {
        try {
            st.execute(sql);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean reachable(String jdbcUrl) {
        try {
            URI u = URI.create(jdbcUrl.substring("jdbc:".length()));
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 8123), 1500);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, String> readDotEnv() {
        Map<String, String> out = new HashMap<>();
        for (Path p : List.of(Path.of("deploy/xampulse/.env"), Path.of("../deploy/xampulse/.env"))) {
            if (Files.isRegularFile(p)) {
                try {
                    for (String line : Files.readAllLines(p)) {
                        int i = line.indexOf('=');
                        if (!line.startsWith("#") && i > 0) {
                            out.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
                        }
                    }
                } catch (Exception ignored) {
                    // fall through to the environment
                }
                break;
            }
        }
        return out;
    }
}
