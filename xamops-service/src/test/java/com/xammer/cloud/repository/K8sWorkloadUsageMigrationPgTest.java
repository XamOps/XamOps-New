package com.xammer.cloud.repository;

import com.xammer.cloud.domain.type.PgTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code V20261024_37__k8s_workload_usage_rollups.sql} applies cleanly against real
 * PostgreSQL, <strong>twice</strong>, and produces partitioned parents (H2 cannot run this DDL, and
 * no other test runs Flyway).
 *
 * <p>Opt-in like {@link VerticalPolicyMigrationPgTest}: skipped unless {@code LOBTEXT_TEST_JDBC_URL}
 * names a <em>local</em> server.
 *
 * <pre>
 * LOBTEXT_TEST_JDBC_URL=jdbc:postgresql://localhost:55432/postgres \
 *   mvn -o -q -pl xamops-service -am test -Dtest=K8sWorkloadUsageMigrationPgTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
class K8sWorkloadUsageMigrationPgTest {

    static final String MIGRATION = "/db/migration/V20261024_37__k8s_workload_usage_rollups.sql";

    private static final List<String> TABLES = List.of(
            "k8s_workload_identity", "k8s_workload_usage_sample", "k8s_workload_usage_hourly",
            "k8s_workload_usage_daily");

    private static final List<String> STATIC_PARTITIONS = List.of(
            "k8s_workload_usage_hourly_y2026m10", "k8s_workload_usage_hourly_y2026m11",
            "k8s_workload_usage_hourly_y2026m12", "k8s_workload_usage_hourly_y2027m01",
            "k8s_workload_usage_daily_y2026m10", "k8s_workload_usage_daily_y2026m11",
            "k8s_workload_usage_daily_y2026m12", "k8s_workload_usage_daily_y2027m01");

    private static String url;
    private static Map<String, Long> indexesAfterFirstRun;
    private static Map<String, Long> constraintsAfterFirstRun;

    @BeforeAll
    static void applyMigrationTwice() {
        url = PgTestSupport.recreateDatabase("k8s_usage_mig");
        createReferencedTables(url);

        MigrationScript migration = MigrationScript.of(MIGRATION);
        assertThat(migration.statements()).as("the migration file must be on the test classpath").isNotEmpty();

        migration.applyTo(url);
        indexesAfterFirstRun = counts("SELECT count(*) FROM pg_indexes WHERE tablename = ?");
        constraintsAfterFirstRun = counts("SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass(?)");

        // The second run must be a no-op, not an error.
        migration.applyTo(url);
    }

    /** client(id) and kubernetes_cluster_config(id), reduced to what the foreign keys need. */
    static void createReferencedTables(String url) {
        PgTestSupport.execute(url, "CREATE TABLE client (id BIGSERIAL PRIMARY KEY, name VARCHAR(255))");
        PgTestSupport.execute(url, "CREATE TABLE kubernetes_cluster_config ("
                + "id BIGSERIAL PRIMARY KEY, cluster_name VARCHAR(255) NOT NULL)");
    }

    @Test
    @DisplayName("sample, hourly and daily are partitioned parents; identity is a plain table")
    void theFactTablesArePartitioned() {
        assertThat(relkind("k8s_workload_usage_sample")).isEqualTo("p");
        assertThat(relkind("k8s_workload_usage_hourly")).isEqualTo("p");
        assertThat(relkind("k8s_workload_usage_daily")).isEqualTo("p");
        assertThat(relkind("k8s_workload_identity")).isEqualTo("r");
    }

    @Test
    @DisplayName("the 8 static monthly partitions exist and are attached to their parents")
    void theStaticPartitionsExist() {
        for (String partition : STATIC_PARTITIONS) {
            String parent = partition.substring(0, partition.lastIndexOf('_'));
            assertThat(PgTestSupport.queryString(url,
                    "SELECT p.relname FROM pg_inherits inh JOIN pg_class p ON p.oid = inh.inhparent"
                            + " WHERE inh.inhrelid = to_regclass(?)", partition))
                    .as(partition).isEqualTo(parent);
        }
        assertThat(PgTestSupport.queryLong(url,
                "SELECT count(*) FROM pg_inherits WHERE inhparent = to_regclass('k8s_workload_usage_sample')"))
                .as("sample partitions are created on demand only")
                .isZero();
    }

    @Test
    @DisplayName("the hourly partitions have explicit UTC bounds")
    void theHourlyBoundsAreUtc() throws SQLException {
        // pg_get_expr renders the bound in the session time zone (pgjdbc sends the JVM's), so read it
        // in a UTC session: midnight UTC must be what was stored, whatever zone ran the migration.
        try (Connection c = PgTestSupport.connect(url); Statement s = c.createStatement()) {
            s.execute("SET TIME ZONE 'UTC'");
            try (ResultSet rs = s.executeQuery("SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_class c"
                    + " WHERE c.oid = to_regclass('k8s_workload_usage_hourly_y2026m10')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1))
                        .contains("2026-10-01 00:00:00+00").contains("2026-11-01 00:00:00+00");
            }
        }
    }

    @Test
    @DisplayName("the second run created no extra index or constraint on any table")
    void theSecondRunIsANoOp() {
        assertThat(counts("SELECT count(*) FROM pg_indexes WHERE tablename = ?")).isEqualTo(indexesAfterFirstRun);
        assertThat(counts("SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass(?)"))
                .isEqualTo(constraintsAfterFirstRun);
        assertThat(indexesAfterFirstRun.get("k8s_workload_identity"))
                .as("primary key, unique workload key, client/cluster index")
                .isEqualTo(3);
        assertThat(indexesAfterFirstRun.get("k8s_workload_usage_hourly")).as("primary key only").isEqualTo(1);
    }

    @Test
    @DisplayName("every foreign key cascades on delete and points at client or kubernetes_cluster_config")
    void everyForeignKeyCascades() {
        for (String table : TABLES) {
            assertThat(PgTestSupport.queryLong(url,
                    "SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND conrelid = to_regclass(?)", table))
                    .as("%s has one FK to client and one to kubernetes_cluster_config", table)
                    .isEqualTo(2);
            assertThat(PgTestSupport.queryLong(url,
                    "SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND conrelid = to_regclass(?)"
                            + " AND (confdeltype <> 'c'"
                            + " OR confrelid::regclass::text NOT IN ('client', 'kubernetes_cluster_config'))",
                    table))
                    .as("%s: every FK is ON DELETE CASCADE to client or kubernetes_cluster_config", table)
                    .isZero();
        }
    }

    @Test
    @DisplayName("there is deliberately no cloud_account_id column on any table (audit B4)")
    void thereIsNoAmbiguousAccountColumn() {
        assertThat(PgTestSupport.queryLong(url,
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_name LIKE 'k8s_workload_%' AND column_name = 'cloud_account_id'"))
                .isZero();
    }

    @Test
    @DisplayName("client_id and cluster_config_id are NOT NULL on every table")
    void everyRowIsTenantScoped() {
        for (String table : TABLES) {
            assertThat(PgTestSupport.queryLong(url,
                    "SELECT count(*) FROM information_schema.columns WHERE table_name = ?"
                            + " AND column_name IN ('client_id', 'cluster_config_id') AND is_nullable = 'NO'",
                    table))
                    .as(table).isEqualTo(2);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String relkind(String table) {
        return PgTestSupport.queryString(url, "SELECT relkind::text FROM pg_class WHERE oid = to_regclass(?)", table);
    }

    private static Map<String, Long> counts(String sql) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            counts.put(table, PgTestSupport.queryLong(url, sql, table));
        }
        for (String partition : STATIC_PARTITIONS) {
            counts.put(partition, PgTestSupport.queryLong(url, sql, partition));
        }
        return counts;
    }
}
