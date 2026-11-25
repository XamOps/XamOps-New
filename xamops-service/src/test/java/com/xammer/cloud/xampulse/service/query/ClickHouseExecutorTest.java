package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseExecutorTest {

    private final TenantScopedQueryBuilder b = new TenantScopedQueryBuilder();
    private final QueryScope scope = QueryScope.of(List.of(42L), Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"));

    static final class FakeGateway implements ClickHouseGateway {
        final List<String> sqls = new ArrayList<>();
        final List<List<Object>> params = new ArrayList<>();
        SQLException toThrow;

        @Override
        public List<Map<String, Object>> query(String sql, List<Object> p, int timeoutSec) throws SQLException {
            sqls.add(sql);
            params.add(p);
            if (toThrow != null) {
                throw toThrow;
            }
            return List.of(Map.of("n", 1));
        }
    }

    private ClickHouseExecutor executor(FakeGateway g) {
        return new ClickHouseExecutor(g, new XamPulseProperties());
    }

    @Test
    void appendsTheTenantSettingAndBoundsToTheStatement() {
        FakeGateway g = new FakeGateway();
        executor(g).query(b.usageByDay(scope));
        String sql = g.sqls.get(0);
        assertTrue(sql.contains("SETTINGS SQL_xp_tenant = '42'"), sql);
        assertTrue(sql.contains("max_execution_time = 15"));
        assertEquals(42L, g.params.get(0).get(0));
    }

    @Test
    void refusesStatementsWithoutTheTenantPredicate() {
        ClickHouseExecutor ex = executor(new FakeGateway());
        assertThrows(IllegalArgumentException.class, () -> ex.query(new ScopedSql("SELECT 1", List.of(), List.of(1L))));
        assertThrows(IllegalArgumentException.class, () -> ex.query(new ScopedSql("SELECT 1 FROM xp_logs WHERE ts > now()", List.of(1L), List.of(1L))));
    }

    @Test
    void refusesEmptyOrMultipleTenants() {
        ClickHouseExecutor ex = executor(new FakeGateway());
        assertThrows(IllegalArgumentException.class, () -> ex.query(new ScopedSql("SELECT 1 FROM t WHERE tenant_id IN (?)", List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> ex.query(new ScopedSql("SELECT 1 FROM t WHERE tenant_id IN (?, ?)", List.of(1L, 2L), List.of(1L, 2L))));
    }

    @Test
    void refusesSetSettingsAndMultiStatementText() {
        ClickHouseExecutor ex = executor(new FakeGateway());
        String head = "SELECT 1 FROM t WHERE tenant_id IN (?)";
        assertThrows(IllegalArgumentException.class, () -> ex.query(new ScopedSql("SET SQL_xp_tenant = 9; " + head, List.of(1L), List.of(1L))));
        assertThrows(IllegalArgumentException.class, () -> ex.query(new ScopedSql(head + " SETTINGS SQL_xp_tenant = '9'", List.of(1L), List.of(1L))));
        assertThrows(IllegalArgumentException.class, () -> ex.query(new ScopedSql(head + "; DROP TABLE t", List.of(1L), List.of(1L))));
        assertThrows(IllegalArgumentException.class, () -> ex.query(new ScopedSql("INSERT INTO t SELECT 1 FROM t WHERE tenant_id IN (?)", List.of(1L), List.of(1L))));
    }

    @Test
    void refusesWhenTenantIsNotTheFirstParameter() {
        ClickHouseExecutor ex = executor(new FakeGateway());
        assertThrows(IllegalArgumentException.class,
                () -> ex.query(new ScopedSql("SELECT 1 FROM t WHERE tenant_id IN (?) AND x = ?", List.of(99L, 1L), List.of(1L))));
        assertThrows(IllegalArgumentException.class,
                () -> ex.query(new ScopedSql("SELECT 1 FROM t WHERE tenant_id IN (?) AND x = ?", List.of(1L), List.of(1L))));
    }

    @Test
    void mapsTimeoutAndFailureToContractErrors() {
        FakeGateway g = new FakeGateway();
        g.toThrow = new SQLException("Read timed out");
        XamPulseException t = assertThrows(XamPulseException.class, () -> executor(g).query(b.errorVolume(scope)));
        assertEquals("XP_QUERY_TIMEOUT", t.getCode());
        g.toThrow = new SQLException("connection refused");
        XamPulseException u = assertThrows(XamPulseException.class, () -> executor(g).query(b.errorVolume(scope)));
        assertEquals("XP_STORE_UNAVAILABLE", u.getCode());
        assertEquals(503, u.getHttpStatus());
    }

    @Test
    void queryEachRunsOneSingleTenantStatementPerAccount() {
        FakeGateway g = new FakeGateway();
        QueryScope two = QueryScope.of(List.of(1L, 2L), scope.from(), scope.to());
        var res = executor(g).queryEach(two, b::errorVolume);
        assertEquals(2, res.size());
        assertEquals(List.of(1L, 2L), res.stream().map(ClickHouseExecutor.TenantRows::tenantId).toList());
        assertTrue(g.sqls.get(0).contains("SQL_xp_tenant = '1'"));
        assertTrue(g.sqls.get(1).contains("SQL_xp_tenant = '2'"));
    }

    @Test
    void perTenantConcurrencyLimitReturnsRateLimited() throws Exception {
        XamPulseProperties props = new XamPulseProperties();
        props.getLimits().setConcurrentQueriesPerTenant(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ClickHouseGateway blocking = (sql, p, t) -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        };
        ClickHouseExecutor ex = new ClickHouseExecutor(blocking, props);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> ex.query(b.errorVolume(scope)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<XamPulseException> err = new AtomicReference<>();
            try {
                ex.query(b.errorVolume(scope));
            } catch (XamPulseException e) {
                err.set(e);
            }
            assertEquals("XP_RATE_LIMITED", err.get().getCode());
            // another tenant is unaffected
            ex.query(b.errorVolume(QueryScope.of(List.of(7L), scope.from(), scope.to()))).size();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
