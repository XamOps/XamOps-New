package com.xammer.cloud.repository;

import com.xammer.cloud.domain.type.PgTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC1: {@code V20261024_20__k8s_spot_interruption_event.sql} applies cleanly against real
 * PostgreSQL, <strong>twice</strong>, and the second run changes nothing.
 *
 * <p>{@link VerticalPolicyMigrationPgTest} pattern: no test in the module runs Flyway, and every
 * deployed profile sets {@code ddl-auto=update} (prod also {@code out-of-order=true}), so the file
 * must be re-runnable. {@link SchemaUpdateNoAlterPgTest} covers the Hibernate side of the same file.
 *
 * <p>Opt-in: it <strong>skips</strong> unless {@code LOBTEXT_TEST_JDBC_URL} names a <em>local</em>
 * server ({@link PgTestSupport} fails hard on any other host):
 *
 * <pre>
 * LOBTEXT_TEST_JDBC_URL=jdbc:postgresql://localhost:55433/postgres \
 *   mvn -f xamops-service/pom.xml test -Dtest=SpotInterruptionMigrationPgTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
class SpotInterruptionMigrationPgTest {

    private static final String MIGRATION = "/db/migration/V20261024_20__k8s_spot_interruption_event.sql";
    private static final String TABLE = "k8s_spot_interruption_event";

    /** Columns, types, nullability, indexes and constraints: what a second run must leave as it is. */
    private static final String SCHEMA_FINGERPRINT = "SELECT"
            + " (SELECT string_agg(column_name || ':' || data_type || ':' || is_nullable || ':'"
            + "   || coalesce(character_maximum_length::text, '-'), ',' ORDER BY ordinal_position)"
            + "   FROM information_schema.columns WHERE table_name = '" + TABLE + "')"
            + " || '|' || (SELECT string_agg(indexname || ':' || indexdef, ',' ORDER BY indexname)"
            + "   FROM pg_indexes WHERE tablename = '" + TABLE + "')"
            + " || '|' || (SELECT string_agg(conname || ':' || contype::text, ',' ORDER BY conname)"
            + "   FROM pg_constraint WHERE conrelid = '" + TABLE + "'::regclass)";

    private static String url;
    private static String afterFirstRun;
    private static String afterSecondRun;

    @BeforeAll
    static void applyMigrationTwice() {
        url = PgTestSupport.recreateDatabase("spot_interruption_mig");
        // The referenced table, reduced to what the foreign key needs.
        PgTestSupport.execute(url, "CREATE TABLE kubernetes_cluster_config ("
                + "id BIGSERIAL PRIMARY KEY, cluster_name VARCHAR(255) NOT NULL)");
        MigrationScript migration = MigrationScript.of(MIGRATION);
        assertThat(migration.statements()).as("the migration file must be on the test classpath").isNotEmpty();
        migration.applyTo(url);
        afterFirstRun = PgTestSupport.queryString(url, SCHEMA_FINGERPRINT);
        // Twice: the second run must be a no-op, not an error.
        migration.applyTo(url);
        afterSecondRun = PgTestSupport.queryString(url, SCHEMA_FINGERPRINT);
    }

    @Test
    @DisplayName("the second run changes nothing")
    void theSecondRunIsANoOp() {
        assertThat(afterFirstRun).isNotBlank();
        assertThat(afterSecondRun).isEqualTo(afterFirstRun);
    }

    @Test
    @DisplayName("the table exists with its unique key and both indexes, once each")
    void theTableAndIndexesExist() {
        assertThat(PgTestSupport.queryString(url, "SELECT to_regclass('" + TABLE + "')::text")).isEqualTo(TABLE);
        for (String index : new String[]{"uq_k8s_spot_interruption_event_node",
                "ix_k8s_spot_interruption_event_cluster_noticed", "ix_k8s_spot_interruption_event_noticed"}) {
            assertThat(PgTestSupport.queryLong(url,
                    "SELECT count(*) FROM pg_indexes WHERE tablename = ? AND indexname = ?", TABLE, index))
                    .as(index).isEqualTo(1);
        }
        assertThat(PgTestSupport.queryLong(url, "SELECT count(*) FROM pg_indexes WHERE tablename = ?", TABLE))
                .as("primary key, unique key and the two declared indexes")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("every Instant column is timestamptz, the type Hibernate maps it to")
    void timestampsAreTimestamptz() {
        assertThat(PgTestSupport.queryLong(url, "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = ? AND column_name LIKE '%\\_at' AND data_type <> 'timestamp with time zone'",
                TABLE)).isZero();
        assertThat(PgTestSupport.queryLong(url, "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = ? AND data_type = 'timestamp with time zone'", TABLE)).isEqualTo(14);
    }

    @Test
    @DisplayName("there is deliberately no cloud_account_id column (audit B4)")
    void thereIsNoAmbiguousAccountColumn() {
        assertThat(PgTestSupport.queryLong(url, "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = ? AND column_name = 'cloud_account_id'", TABLE)).isZero();
    }

    @Test
    @DisplayName("one row per node lifecycle; a reused name with another creation time is another row")
    void theUniqueKeyIsTheNodeLifecycle() {
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name)"
                + " VALUES (10, 'xamops-test-1') ON CONFLICT DO NOTHING");
        insert("ip-10-0-1-5", "2026-10-24T06:00:00Z");
        insert("ip-10-0-1-5", "2026-10-24T07:00:00Z");
        assertThat(PgTestSupport.queryLong(url,
                "SELECT count(*) FROM " + TABLE + " WHERE node_name = 'ip-10-0-1-5'")).isEqualTo(2);
        boolean refused = false;
        try {
            insert("ip-10-0-1-5", "2026-10-24T06:00:00Z");
        } catch (RuntimeException expected) {
            refused = true;
        }
        assertThat(refused).as("the same node lifecycle twice").isTrue();
    }

    @Test
    @DisplayName("rows go with their cluster")
    void deletingTheClusterDeletesItsHistory() {
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name)"
                + " VALUES (20, 'gone') ON CONFLICT DO NOTHING");
        PgTestSupport.execute(url, "INSERT INTO " + TABLE + " (cluster_config_id, node_name, node_created_at,"
                + " notice_kind, sources, noticed_at, recovery_status, created_at, updated_at)"
                + " VALUES (20, 'n', now(), 'unconfirmed', 'node-removed', now(), 'no-snapshot', now(), now())");
        PgTestSupport.execute(url, "DELETE FROM kubernetes_cluster_config WHERE id = 20");
        assertThat(PgTestSupport.queryLong(url,
                "SELECT count(*) FROM " + TABLE + " WHERE cluster_config_id = 20")).isZero();
    }

    private static void insert(String node, String createdAt) {
        PgTestSupport.execute(url, "INSERT INTO " + TABLE + " (cluster_config_id, node_name, node_created_at,"
                + " notice_kind, sources, noticed_at, recovery_status, created_at, updated_at)"
                + " VALUES (10, ?, ?::timestamptz, 'interruption', 'nth-taint', now(), 'no-snapshot', now(), now())",
                node, createdAt);
    }
}
