package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.ScopedSql;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaselineJobTest {

    private final Instant now = Instant.parse("2026-02-02T12:00:00Z");

    private XamPulseProperties props(boolean enabled, List<String> metrics) {
        XamPulseProperties p = new XamPulseProperties();
        p.getBaseline().setEnabled(enabled);
        p.getBaseline().setWindowDays(14);
        p.getBaseline().setMetrics(metrics);
        return p;
    }

    private XpAccountConfig account(long id, String state) {
        XpAccountConfig c = new XpAccountConfig();
        c.setCloudAccountId(id);
        c.setState(state);
        return c;
    }

    /** Three hourly samples for two instances, all landing in the same hour-of-week slot. */
    private List<Map<String, Object>> twoInstancesSameSlot() {
        List<Map<String, Object>> rows = new ArrayList<>();
        // 2026-02-02T12:00 UTC is a Monday noon; step back one week per sample so they share hour-of-week.
        long base = now.getEpochSecond();
        for (String inst : List.of("i-1", "i-2")) {
            double[] vals = inst.equals("i-1") ? new double[] {10, 20, 30} : new double[] {4, 4, 10};
            for (int k = 0; k < 3; k++) {
                Map<String, Object> r = new HashMap<>();
                r.put("instance_id", inst);
                r.put("t", base - (long) k * 7 * 86_400L);
                r.put("v", vals[k]);
                rows.add(r);
            }
        }
        return rows;
    }

    private SchedulingEnvironmentConfig schedulingOn() {
        SchedulingEnvironmentConfig s = mock(SchedulingEnvironmentConfig.class);
        when(s.isSchedulingEnabled()).thenReturn(true);
        return s;
    }

    @Test
    void writesBaselineRowsWithExactColumnsAndBaselineMathValues() {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        when(executor.query(any(ScopedSql.class))).thenReturn(twoInstancesSameSlot());
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "CONNECTED")));

        BaselineJob job = new BaselineJob(configs, executor, writes, schedulingOn(),
                props(true, List.of("system.cpu.utilization")));
        job.runForAccount(7L, now);

        assertEquals(1, writes.calls.size());
        FakeClickHouseWrites.Call call = writes.calls.get(0);
        assertEquals("xp_baseline", call.table());
        assertEquals(List.of("tenant_id", "metric", "instance_id", "hour_of_week", "med", "mad", "updated_at"), call.columns());
        // Monday noon from its own three weeks, and noon on the other six days from the daily rhythm (the same three
        // samples, all at noon): seven rows per instance.
        assertEquals(14, call.rows().size(), "the weekly slot plus the daily fallback at noon, per instance");

        int hourOfWeek = BaselineMath.hourOfWeek(now);
        Map<String, List<Object>> byInstance = new HashMap<>();
        for (List<Object> row : call.rows()) {
            assertEquals(12, (int) row.get(3) % 24, "only noon has samples");
            if ((int) row.get(3) == hourOfWeek) {
                byInstance.put((String) row.get(2), row);
            }
        }
        // i-1: median 20, deviations {10,0,10} -> MAD 10
        List<Object> i1 = byInstance.get("i-1");
        assertEquals(7L, i1.get(0));
        assertEquals("system.cpu.utilization", i1.get(1));
        assertEquals(hourOfWeek, i1.get(3));
        assertEquals(20.0, (double) i1.get(4), 1e-9);
        assertEquals(10.0, (double) i1.get(5), 1e-9);
        // i-2: median 4, deviations {0,0,6} -> MAD 0
        List<Object> i2 = byInstance.get("i-2");
        assertEquals(4.0, (double) i2.get(4), 1e-9);
        assertEquals(0.0, (double) i2.get(5), 1e-9);
    }

    @Test
    void coldStartSlotsAreOmitted() {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        // Only two samples in a slot -> below BaselineMath.MIN_SAMPLES -> no rows.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int k = 0; k < 2; k++) {
            Map<String, Object> r = new HashMap<>();
            r.put("instance_id", "i-1");
            r.put("t", now.getEpochSecond() - (long) k * 7 * 86_400L);
            r.put("v", 5.0 + k);
            rows.add(r);
        }
        when(executor.query(any(ScopedSql.class))).thenReturn(rows);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);

        BaselineJob job = new BaselineJob(configs, executor, writes, schedulingOn(),
                props(true, List.of("system.cpu.utilization")));
        job.runForAccount(7L, now);

        assertTrue(writes.calls.isEmpty(), "a cold-start slot writes nothing");
    }

    @Test
    void oneAccountFailingDoesNotStopTheOthers() {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        when(executor.query(any(ScopedSql.class))).thenAnswer(inv -> {
            // Fail only for the first account's statement by tenant id in params.
            ScopedSql q = inv.getArgument(0);
            if (q.tenantIds().get(0) == 7L) {
                throw new RuntimeException("boom for account 7");
            }
            return twoInstancesSameSlot();
        });
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "CONNECTED"), account(9L, "DEGRADED")));

        BaselineJob job = new BaselineJob(configs, executor, writes, schedulingOn(),
                props(true, List.of("system.cpu.utilization")));
        job.runAll();

        assertEquals(1, writes.calls.size(), "account 9 still ran after account 7 failed");
    }

    @Test
    void disabledFlagWritesNothing() {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "CONNECTED")));

        BaselineJob job = new BaselineJob(configs, executor, writes, schedulingOn(),
                props(false, List.of("system.cpu.utilization")));
        job.runAll();

        assertTrue(writes.calls.isEmpty());
    }

    @Test
    void schedulingDisabledWritesNothing() {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        SchedulingEnvironmentConfig off = mock(SchedulingEnvironmentConfig.class);
        when(off.isSchedulingEnabled()).thenReturn(false);

        BaselineJob job = new BaselineJob(configs, executor, writes, off,
                props(true, List.of("system.cpu.utilization")));
        job.runAll();

        assertTrue(writes.calls.isEmpty());
    }

    @Test
    void inactiveAccountsAreSkipped() {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "PLANNED"), account(9L, "DISCONNECTED")));

        BaselineJob job = new BaselineJob(configs, executor, writes, schedulingOn(),
                props(true, List.of("system.cpu.utilization")));
        job.runAll();

        assertTrue(writes.calls.isEmpty());
    }
}
