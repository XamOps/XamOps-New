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
                mutations.execute("ALTER TABLE xp_events DELETE WHERE tenant_id IN (?, ?)", List.of(T1, T2));
            } finally {
                connections.close();
            }
        }
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
        List<ScopedSql> shapes = List.of(
                builder.search(scope, none, true, 10, null),
                builder.histogram(scope, none, 60),
                builder.facet(scope, none, LogField.SOURCE, null, 5),
                builder.patterns(scope, none, 10),
                builder.metricSeries(scope, avg, TenantScopedQueryBuilder.planMetric(scope, null)),
                builder.latestMetrics(scope, to.getEpochSecond() - 3600, List.of("system.cpu.utilization")),
                builder.metricNames(scope),
                builder.baselineSamples(scope, "system.cpu.utilization", 7),
                builder.processLatest(scope, "i-it", to.getEpochSecond()),
                builder.events(scope, List.of("ALERT"), 10),
                builder.usageByDay(scope),
                builder.usageBySource(scope));
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
