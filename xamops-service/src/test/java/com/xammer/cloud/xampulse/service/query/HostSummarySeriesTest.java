package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpHostSummary;
import com.xammer.cloud.xampulse.dto.XpLogSearchResponse;
import com.xammer.cloud.xampulse.dto.XpScanned;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.alert.IncidentService;
import com.xammer.cloud.xampulse.service.insight.HealthScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The depth series produced by {@link HostQueryService#summary} must carry exactly the keys that
 * {@code components/XamPulse/hosts/Resource360Drawer.tsx} parses, in the precise string form, and the four original
 * keys must be unchanged. The ClickHouse executor is faked: it applies the builder function and returns rows carrying
 * the state/cpu/mountpoint/direction labels the query selects.
 */
class HostSummarySeriesTest {

    private static final long ACCOUNT = 100L;
    private static final String INSTANCE = "i-0abc";
    private static final Instant FROM = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-30T06:00:00Z");

    private HostQueryService hosts;
    private ClientUserDetails user;
    private ClickHouseExecutor executor;

    @BeforeEach
    void setUp() {
        XamPulseProperties props = new XamPulseProperties();
        CloudAccountAccessService access = mock(CloudAccountAccessService.class);
        CloudAccount mine = new CloudAccount();
        mine.setId(ACCOUNT);
        when(access.requireAccess(any(), any())).thenReturn(mine);
        XamPulseAccessGuard guard = new XamPulseAccessGuard(access, mock(CloudAccountRepository.class));
        QueryScopeFactory scopes = new QueryScopeFactory(guard, props);

        executor = mock(ClickHouseExecutor.class);
        when(executor.queryEach(any(), any())).thenAnswer(inv -> {
            QueryScope scope = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            Function<QueryScope, ScopedSql> fn = (Function<QueryScope, ScopedSql>) inv.getArgument(1);
            List<ClickHouseExecutor.TenantRows> out = new ArrayList<>();
            for (Long id : scope.accountIds()) {
                ScopedSql sql = fn.apply(scope.forTenant(id));
                out.add(new ClickHouseExecutor.TenantRows(id, rowsFor(sql)));
            }
            return out;
        });

        XpAgentRepository agents = mock(XpAgentRepository.class);
        XpAgent agent = new XpAgent();
        agent.setCloudAccountId(ACCOUNT);
        agent.setInstanceId(INSTANCE);
        agent.setRegion("us-east-1");
        agent.setOs("linux");
        agent.setState("ACTIVE");
        agent.setCollectionLevel("T1");
        agent.setLastSeenAt(TO);
        when(agents.findByCloudAccountId(ACCOUNT)).thenReturn(List.of(agent));

        IncidentService incidents = mock(IncidentService.class);
        when(incidents.openForInstance(any(), any())).thenReturn(List.of());
        LogQueryService logs = mock(LogQueryService.class);
        when(logs.search(any(), any())).thenReturn(new XpLogSearchResponse(List.of(), null, new XpScanned(0, 0), 1, false, null));
        EventQueryService events = mock(EventQueryService.class);
        when(events.events(any(QueryScope.class), anyList())).thenReturn(List.of());

        hosts = new HostQueryService(scopes, executor, agents, new HealthScoreService(), incidents, logs, events);
        user = new ClientUserDetails("u", "x", List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 1L, 10L, "t");
    }

    /** Synthesises rows for a series query based on the metric, group-by expression and bound label filter. */
    private static List<Map<String, Object>> rowsFor(ScopedSql sql) {
        String s = sql.sql();
        List<Object> params = sql.params();
        // Per-core, per-mount and per-direction queries group by a label: value lands in g0.
        if (s.contains("'cpu='")) {
            return List.of(groupedRow("cpu0", 0.9), groupedRow("cpu1", 0.8)); // the agent labels CPUs cpu0, cpu1; idle -> 10% and 20%
        }
        if (s.contains("'mountpoint='")) {
            return List.of(groupedRow("/", 0.42), groupedRow("/var", 0.55));
        }
        if (s.contains("'direction='")) {
            // disk.io read/write and network.io receive/transmit each filter to one direction value.
            String dir = directionFilter(params);
            return dir == null ? List.of() : List.of(groupedRow(dir, 1_048_576.0));
        }
        // Ungrouped percent series: discriminate by the bound metric and state filter.
        String metric = metricParam(params);
        boolean steal = params.contains("state=steal");
        boolean iowait = params.contains("state=wait"); // the agent's real label for I/O wait
        boolean pagingUsed = "system.paging.utilization".equals(metric) && params.contains("state=used");
        boolean cpuIdle = "system.cpu.utilization".equals(metric) && params.contains("state=idle");
        boolean memUsed = "system.memory.utilization".equals(metric) && params.contains("state=used");
        boolean fsMax = "system.filesystem.utilization".equals(metric);
        boolean load = "system.cpu.load_average.1m".equals(metric);
        if (steal) {
            return List.of(point(0.03));
        }
        if (iowait) {
            return List.of(point(0.07));
        }
        if (pagingUsed) {
            return List.of(point(0.25));
        }
        if (cpuIdle) {
            return List.of(point(0.85));
        }
        if (memUsed) {
            return List.of(point(0.6));
        }
        if (fsMax) {
            return List.of(point(0.5));
        }
        if (load) {
            return List.of(point(1.5));
        }
        // CPU tab: user and system time, vCPU count, 5m load, run queue and process creation are reported;
        // softirq, interrupt, nice and the 15m load are not, so their keys must be absent.
        if ("system.cpu.utilization".equals(metric) && params.contains("state=user")) {
            return List.of(point(0.6));
        }
        if ("system.cpu.utilization".equals(metric) && params.contains("state=system")) {
            return List.of(point(0.12));
        }
        if ("system.cpu.logical.count".equals(metric)) {
            return List.of(point(2));
        }
        if ("system.cpu.load_average.5m".equals(metric)) {
            return List.of(point(1.2));
        }
        if ("system.processes.count".equals(metric) && params.contains("status=running")) {
            return List.of(point(3));
        }
        if ("system.processes.count".equals(metric) && params.contains("status=blocked")) {
            return List.of(point(1));
        }
        if ("system.processes.created".equals(metric)) {
            return List.of(point(4.0));
        }
        // latestMetrics / errorVolume and other scaffolding queries return nothing.
        return List.of();
    }

    private static String metricParam(List<Object> params) {
        for (Object p : params) {
            if (p instanceof String str && str.startsWith("system.")) {
                return str;
            }
        }
        return "";
    }

    private static String directionFilter(List<Object> params) {
        for (String d : List.of("read", "write", "receive", "transmit")) {
            if (params.contains("direction=" + d)) {
                return d;
            }
        }
        return null;
    }

    private static Map<String, Object> point(double v) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("t", 1_000_000L);
        r.put("v", v);
        return r;
    }

    private static Map<String, Object> groupedRow(String g0, double v) {
        Map<String, Object> r = point(v);
        r.put("g0", g0);
        return r;
    }

    @Test
    void summaryEmitsExactlyTheResource360SeriesKeysInTheRightForm() {
        XpHostSummary summary = hosts.summary(user, ACCOUNT, INSTANCE, FROM, TO);
        Map<String, List<double[]>> series = summary.series();

        Set<String> expected = Set.of(
                "system.cpu.utilization",
                "system.memory.utilization",
                "system.filesystem.utilization",
                "system.cpu.load_average.1m",
                "system.cpu.utilization{state=steal}",
                "system.cpu.utilization{state=iowait}",
                "system.paging.utilization",
                "system.cpu.utilization{cpu=0}",
                "system.cpu.utilization{cpu=1}",
                "system.filesystem.utilization{mountpoint=/}",
                "system.filesystem.utilization{mountpoint=/var}",
                "system.disk.io{direction=read}",
                "system.disk.io{direction=write}",
                "system.network.io{direction=receive}",
                "system.network.io{direction=transmit}",
                // CPU tab, only the reported ones (no softirq / interrupt / nice / 15m load keys).
                "system.cpu.utilization{state=user}",
                "system.cpu.utilization{state=system}",
                "system.cpu.load_average.5m",
                "system.cpu.logical.count",
                "system.processes.count{status=running}",
                "system.processes.count{status=blocked}",
                "system.processes.created");
        assertThat(series.keySet()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(series.get("system.cpu.utilization{state=user}").get(0)[1]).isCloseTo(60.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(series.get("system.cpu.logical.count").get(0)[1]).isEqualTo(2.0);
        assertThat(series.get("system.processes.count{status=blocked}").get(0)[1]).isEqualTo(1.0);
        assertThat(series.get("system.processes.created").get(0)[1]).isEqualTo(4.0);

        // Original keys keep their meaning: cpu total = (1 - idle) * 100, mem = used * 100, fs = max * 100, load raw.
        org.assertj.core.data.Offset<Double> eps = org.assertj.core.data.Offset.offset(1e-6);
        assertThat(series.get("system.cpu.utilization").get(0)[1]).isCloseTo(15.0, eps);
        assertThat(series.get("system.memory.utilization").get(0)[1]).isCloseTo(60.0, eps);
        assertThat(series.get("system.filesystem.utilization").get(0)[1]).isCloseTo(50.0, eps);
        assertThat(series.get("system.cpu.load_average.1m").get(0)[1]).isCloseTo(1.5, eps);

        // Depth keys carry the scaled/raw values.
        assertThat(series.get("system.cpu.utilization{state=steal}").get(0)[1]).isCloseTo(3.0, eps);
        assertThat(series.get("system.cpu.utilization{state=iowait}").get(0)[1]).isCloseTo(7.0, eps);
        assertThat(series.get("system.paging.utilization").get(0)[1]).isCloseTo(25.0, eps);
        assertThat(series.get("system.cpu.utilization{cpu=0}").get(0)[1]).isCloseTo(10.0, eps);
        assertThat(series.get("system.cpu.utilization{cpu=1}").get(0)[1]).isCloseTo(20.0, eps);
        assertThat(series.get("system.filesystem.utilization{mountpoint=/}").get(0)[1]).isCloseTo(42.0, eps);
        assertThat(series.get("system.filesystem.utilization{mountpoint=/var}").get(0)[1]).isCloseTo(55.0, eps);
        assertThat(series.get("system.disk.io{direction=read}").get(0)[1]).isEqualTo(1_048_576.0);
        assertThat(series.get("system.disk.io{direction=write}").get(0)[1]).isEqualTo(1_048_576.0);
        assertThat(series.get("system.network.io{direction=receive}").get(0)[1]).isEqualTo(1_048_576.0);
        assertThat(series.get("system.network.io{direction=transmit}").get(0)[1]).isEqualTo(1_048_576.0);
    }

    /**
     * Host 360 draws the normal range behind CPU, memory, disk and load, in each chart's unit, only where a baseline was
     * learned; and "compare with last week" returns the same window a week earlier moved onto today's time axis.
     */
    @Test
    void bandsAreInTheChartsUnitOnlyWhenLearnedAndCompareShiftsLastWeekOntoToday() {
        // Cold start: no learned baseline, no band, and no comparison unless asked for.
        XpHostSummary cold = hosts.summary(user, ACCOUNT, INSTANCE, FROM, TO);
        assertThat(cold.bands()).isEmpty();
        assertThat(cold.compare()).isNull();
        assertThat(cold.compareOffsetSec()).isNull();

        // Learned: CPU busy 15% +/- a little, memory 60%, the fullest disk 50%, load 1.5, for every hour of the week.
        Map<String, Double> medians = Map.of("system.cpu.utilization", 0.15, "system.memory.utilization", 0.60,
                "system.filesystem.utilization", 0.50, "system.cpu.load_average.1m", 1.5);
        when(executor.query(any(ScopedSql.class))).thenAnswer(inv -> {
            ScopedSql q = inv.getArgument(0);
            if (!q.sql().contains("FROM xp_baseline")) {
                return List.of();
            }
            Double med = medians.get(metricParam(q.params()));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int h = 0; h < 168 && med != null; h++) {
                rows.add(Map.of("instance_id", INSTANCE, "hour_of_week", (long) h, "med", med, "mad", 0.01));
            }
            return rows;
        });
        long week = 7L * 86_400L;
        XpHostSummary s = hosts.summary(user, ACCOUNT, INSTANCE, FROM, TO, week);
        assertThat(s.bands().keySet()).containsExactlyInAnyOrder("system.cpu.utilization", "system.memory.utilization",
                "system.filesystem.utilization", "system.cpu.load_average.1m");
        double[] cpu = s.bands().get("system.cpu.utilization").get(0);
        assertThat(cpu[0]).isEqualTo(1_000_000_000.0);
        assertThat(cpu[1]).isLessThan(15.0).isGreaterThan(5.0);   // percent, around the 15% median
        assertThat(cpu[2]).isGreaterThan(15.0).isLessThan(25.0);
        double[] load = s.bands().get("system.cpu.load_average.1m").get(0);
        assertThat(load[1]).isLessThan(1.5).isGreaterThan(1.0);   // load stays raw, not a percent
        assertThat(load[2]).isGreaterThan(1.5).isLessThan(2.0);

        assertThat(s.compareOffsetSec()).isEqualTo(week);
        assertThat(s.compare().keySet()).containsExactlyInAnyOrder("system.cpu.utilization", "system.memory.utilization",
                "system.cpu.load_average.1m", "system.network.io{direction=receive}", "system.network.io{direction=transmit}");
        double[] lastWeekCpu = s.compare().get("system.cpu.utilization").get(0);
        assertThat(lastWeekCpu[0]).isEqualTo(1_000_000_000.0 + week * 1000.0);   // moved onto today's axis
        assertThat(lastWeekCpu[1]).isCloseTo(15.0, org.assertj.core.data.Offset.offset(1e-6));
    }

}
