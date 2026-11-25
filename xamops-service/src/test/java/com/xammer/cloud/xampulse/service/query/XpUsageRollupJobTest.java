package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpUsageDaily;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpUsageDailyRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XpUsageRollupJobTest {

    private final Instant now = Instant.parse("2026-02-02T12:00:00Z");

    private XamPulseProperties props(boolean enabled) {
        XamPulseProperties p = new XamPulseProperties();
        p.getUsage().setEnabled(enabled);
        p.getUsage().setLookbackDays(3);
        return p;
    }

    private XpAccountConfig account(long id, String state) {
        XpAccountConfig c = new XpAccountConfig();
        c.setCloudAccountId(id);
        c.setState(state);
        return c;
    }

    private SchedulingEnvironmentConfig schedulingOn() {
        SchedulingEnvironmentConfig s = mock(SchedulingEnvironmentConfig.class);
        when(s.isSchedulingEnabled()).thenReturn(true);
        return s;
    }

    private Map<String, Object> row(String day, String source, long bytes, long rows) {
        Map<String, Object> r = new HashMap<>();
        r.put("d", day);
        r.put("s", source);
        r.put("b", bytes);
        r.put("r", rows);
        return r;
    }

    /** Two days x two sources for a single tenant. */
    private List<Map<String, Object>> twoDaysTwoSources() {
        return List.of(
                row("2026-02-01", "app", 100L, 10L),
                row("2026-02-01", "nginx", 200L, 20L),
                row("2026-02-02", "app", 300L, 30L),
                row("2026-02-02", "nginx", 400L, 40L));
    }

    /**
     * A Mockito usage repository backed by an in-memory map keyed on the unique (cloud_account_id, day, signal,
     * source) so save() enforces upsert semantics (update-in-place, never a duplicate).
     */
    private static Map<String, XpUsageDaily> stubbedUpsert(XpUsageDailyRepository repo) {
        Map<String, XpUsageDaily> store = new HashMap<>();
        when(repo.findByCloudAccountIdAndDayAndSignalAndSource(any(), any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(key(inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3)))));
        when(repo.save(any(XpUsageDaily.class))).thenAnswer(inv -> {
            XpUsageDaily u = inv.getArgument(0);
            store.put(key(u.getCloudAccountId(), u.getDay(), u.getSignal(), u.getSource()), u);
            return u;
        });
        return store;
    }

    private static String key(Object id, Object day, Object signal, Object source) {
        return id + "|" + day + "|" + signal + "|" + source;
    }

    @Test
    void upsertsOneLogsRowPerDayAndSourceWithCorrectBytesAndRows() {
        XpUsageDailyRepository repo = mock(XpUsageDailyRepository.class);
        Map<String, XpUsageDaily> store = stubbedUpsert(repo);
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        when(executor.query(any(ScopedSql.class))).thenReturn(twoDaysTwoSources());
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "CONNECTED")));

        XpUsageRollupJob job = new XpUsageRollupJob(configs, executor, repo, schedulingOn(), props(true));
        job.runAll();

        assertEquals(4, store.size(), "one row per (day, source)");
        for (XpUsageDaily u : store.values()) {
            assertEquals(7L, u.getCloudAccountId());
            assertEquals("LOGS", u.getSignal());
            // bytes_stored is the documented rollup byte-sum approximation == bytes_in.
            assertEquals(u.getBytesIn(), u.getBytesStored());
        }
        XpUsageDaily appDay2 = store.get(key(7L, LocalDate.parse("2026-02-02"), "LOGS", "app"));
        assertNotNull(appDay2);
        assertEquals(300L, appDay2.getBytesIn());
        assertEquals(300L, appDay2.getBytesStored());
        assertEquals(30L, appDay2.getRowCount());
        XpUsageDaily nginxDay1 = store.get(key(7L, LocalDate.parse("2026-02-01"), "LOGS", "nginx"));
        assertNotNull(nginxDay1);
        assertEquals(200L, nginxDay1.getBytesIn());
        assertEquals(20L, nginxDay1.getRowCount());
    }

    @Test
    void secondRunUpdatesInPlaceWithoutDuplicating() {
        XpUsageDailyRepository repo = mock(XpUsageDailyRepository.class);
        Map<String, XpUsageDaily> store = stubbedUpsert(repo);
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "CONNECTED")));

        // First run: baseline numbers.
        when(executor.query(any(ScopedSql.class))).thenReturn(List.of(row("2026-02-02", "app", 100L, 10L)));
        XpUsageRollupJob job = new XpUsageRollupJob(configs, executor, repo, schedulingOn(), props(true));
        job.runAll();
        assertEquals(1, store.size());

        // Second run for the same (day, source): a late-arriving correction.
        when(executor.query(any(ScopedSql.class))).thenReturn(List.of(row("2026-02-02", "app", 999L, 99L)));
        job.runAll();

        assertEquals(1, store.size(), "no duplicate key: still a single row");
        XpUsageDaily u = store.get(key(7L, LocalDate.parse("2026-02-02"), "LOGS", "app"));
        assertEquals(999L, u.getBytesIn());
        assertEquals(999L, u.getBytesStored());
        assertEquals(99L, u.getRowCount());
    }

    @Test
    void disabledFlagWritesNothing() {
        XpUsageDailyRepository repo = mock(XpUsageDailyRepository.class);
        Map<String, XpUsageDaily> store = stubbedUpsert(repo);
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "CONNECTED")));

        XpUsageRollupJob job = new XpUsageRollupJob(configs, executor, repo, schedulingOn(), props(false));
        job.runAll();

        assertTrue(store.isEmpty());
    }

    @Test
    void schedulingDisabledWritesNothing() {
        XpUsageDailyRepository repo = mock(XpUsageDailyRepository.class);
        Map<String, XpUsageDaily> store = stubbedUpsert(repo);
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        SchedulingEnvironmentConfig off = mock(SchedulingEnvironmentConfig.class);
        when(off.isSchedulingEnabled()).thenReturn(false);

        XpUsageRollupJob job = new XpUsageRollupJob(configs, executor, repo, off, props(true));
        job.runAll();

        assertTrue(store.isEmpty());
    }

    @Test
    void inactiveAccountsAreSkipped() {
        XpUsageDailyRepository repo = mock(XpUsageDailyRepository.class);
        Map<String, XpUsageDaily> store = stubbedUpsert(repo);
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "PLANNED"), account(9L, "DISCONNECTED")));

        XpUsageRollupJob job = new XpUsageRollupJob(configs, executor, repo, schedulingOn(), props(true));
        job.runAll();

        assertTrue(store.isEmpty());
    }

    @Test
    void oneAccountFailingDoesNotStopTheOthers() {
        XpUsageDailyRepository repo = mock(XpUsageDailyRepository.class);
        Map<String, XpUsageDaily> store = stubbedUpsert(repo);
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        when(executor.query(any(ScopedSql.class))).thenAnswer(inv -> {
            ScopedSql q = inv.getArgument(0);
            if (q.tenantIds().get(0) == 7L) {
                throw new RuntimeException("boom for account 7");
            }
            return twoDaysTwoSources();
        });
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "CONNECTED"), account(9L, "DEGRADED")));

        XpUsageRollupJob job = new XpUsageRollupJob(configs, executor, repo, schedulingOn(), props(true));
        job.runAll();

        assertEquals(4, store.size(), "account 9 still ran after account 7 failed");
        assertTrue(store.keySet().stream().allMatch(k -> k.startsWith("9|")), "only the surviving tenant's rows were written");
    }
}
