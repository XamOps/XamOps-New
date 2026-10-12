package com.xammer.cloud.repository;

import com.xammer.cloud.domain.type.PgTestSupport;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code V20261010_1__vertical_workload_policy.sql} applies cleanly against real PostgreSQL,
 * <strong>twice</strong>.
 *
 * <p>This test exists because <em>no</em> test in the module runs Flyway — every JPA slice test sets
 * {@code spring.flyway.enabled=false} and runs on H2 — so without it the migration would first be
 * executed against a real database in a deployed environment. It also has to be re-runnable:
 * {@code spring.jpa.hibernate.ddl-auto=update} is set in every deployed profile, so Hibernate may
 * have created the table before Flyway sees it, and prod runs with
 * {@code spring.flyway.out-of-order=true}.
 *
 * <p>Opt-in, following the module's existing PostgreSQL pattern: it <strong>skips</strong> unless
 * {@code LOBTEXT_TEST_JDBC_URL} names a <em>local</em> server, which {@link PgTestSupport} enforces
 * by failing hard on any non-localhost host. The shared remote dev database must never be written
 * to. To run it:
 *
 * <pre>
 * LOBTEXT_TEST_JDBC_URL=jdbc:postgresql://localhost:55432/postgres \
 *   mvn -o -f xamops-service/pom.xml test -Dtest=VerticalPolicyMigrationPgTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
class VerticalPolicyMigrationPgTest {

    private static final String MIGRATION = "/db/migration/V20261010_1__vertical_workload_policy.sql";

    private static String url;

    @BeforeAll
    static void applyMigrationTwice() {
        url = PgTestSupport.recreateDatabase("vertical_policy_mig");

        // The two referenced tables, reduced to what the foreign keys need. Creating them from the
        // real schema would drag in most of the application; the migration only needs client(id)
        // and kubernetes_cluster_config(id) to exist as referenceable primary keys.
        PgTestSupport.execute(url, "CREATE TABLE client (id BIGSERIAL PRIMARY KEY, name VARCHAR(255))");
        PgTestSupport.execute(url, "CREATE TABLE kubernetes_cluster_config ("
                + "id BIGSERIAL PRIMARY KEY, cluster_name VARCHAR(255) NOT NULL)");

        MigrationScript migration = MigrationScript.of(MIGRATION);
        assertThat(migration.statements()).as("the migration file must be on the test classpath").isNotEmpty();

        // Twice: the second run must be a no-op, not an error.
        migration.applyTo(url);
        migration.applyTo(url);
    }

    @Test
    @DisplayName("the table exists with its unique and lookup indexes")
    void theTableAndIndexesExist() {
        assertThat(PgTestSupport.queryString(url,
                "SELECT to_regclass('vertical_workload_policy')::text")).isEqualTo("vertical_workload_policy");

        assertThat(PgTestSupport.queryLong(url,
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'vertical_workload_policy'"
                        + " AND indexname = 'uq_vertical_workload_policy_workload'")).isEqualTo(1);

        assertThat(PgTestSupport.queryLong(url,
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'vertical_workload_policy'"
                        + " AND indexname = 'ix_vertical_workload_policy_client_cluster'")).isEqualTo(1);

        // Applying the file twice must not have produced duplicate indexes.
        assertThat(PgTestSupport.queryLong(url,
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'vertical_workload_policy'"))
                .as("one primary key plus the two declared indexes")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("every column default matches the Java constants, so a written row behaves like no row")
    void columnDefaultsMatchTheJavaConstants() {
        assertThat(defaultOf("mode")).isEqualTo("'OFF'::character varying");
        assertThat(numericDefault("headroom_pct")).isEqualTo(WorkloadPolicyDefaults.HEADROOM_PCT);
        assertThat(numericDefault("percentile")).isEqualTo(WorkloadPolicyDefaults.PERCENTILE);
        assertThat(numericDefault("window_hours")).isEqualTo(WorkloadPolicyDefaults.WINDOW_HOURS);
        assertThat(numericDefault("cpu_floor_millicores")).isEqualTo(WorkloadPolicyDefaults.CPU_FLOOR_MILLIS);
        assertThat(numericDefault("memory_floor_bytes")).isEqualTo(WorkloadPolicyDefaults.MEM_FLOOR_BYTES);
        assertThat(numericDefault("min_increase_pct"))
                .isEqualTo(WorkloadPolicyDefaults.CPU_INCREASE.minPercent());
        assertThat(numericDefault("min_decrease_pct"))
                .isEqualTo(WorkloadPolicyDefaults.CPU_DECREASE.minPercent());
        assertThat(numericDefault("min_increase_cpu_millicores"))
                .isEqualTo(WorkloadPolicyDefaults.CPU_INCREASE.minAbsolute());
        assertThat(numericDefault("min_decrease_cpu_millicores"))
                .isEqualTo(WorkloadPolicyDefaults.CPU_DECREASE.minAbsolute());
        assertThat(numericDefault("min_increase_memory_bytes"))
                .isEqualTo(WorkloadPolicyDefaults.MEM_INCREASE.minAbsolute());
        assertThat(numericDefault("min_decrease_memory_bytes"))
                .isEqualTo(WorkloadPolicyDefaults.MEM_DECREASE.minAbsolute());
        assertThat(defaultOf("apply_method")).isEqualTo("'IN_PLACE'::character varying");
        assertThat(numericDefault("cooldown_minutes")).isEqualTo(WorkloadPolicyDefaults.COOLDOWN_MINUTES);
        assertThat(doubleDefault("max_limit_increase_factor"))
                .isEqualTo(WorkloadPolicyDefaults.MAX_LIMIT_INCREASE_FACTOR);
        assertThat(defaultOf("protect_single_replica")).isEqualTo("true");
        assertThat(doubleDefault("node_capacity_fraction"))
                .isEqualTo(WorkloadPolicyDefaults.NODE_CAPACITY_FRACTION);
        assertThat(defaultOf("last_applied_at")).isNull();
    }

    @Test
    @DisplayName("the two fractional columns are the type the entity maps, so ddl-auto=update is a no-op")
    void theFractionalColumnsMatchTheMappedType() {
        // Every deployed profile runs ddl-auto=update. A NUMERIC column here would be rewritten to
        // double precision by Hibernate on the next boot, because WorkloadVerticalPolicy maps both
        // as Double -- so the migration declares what the entity means.
        // SchemaUpdateNoAlterPgTest asserts the other half: that Hibernate emits no DDL for it.
        assertThat(typeOf("max_limit_increase_factor")).isEqualTo("double precision");
        assertThat(typeOf("node_capacity_fraction")).isEqualTo("double precision");
    }

    @Test
    @DisplayName("there is deliberately no cloud_account_id column (audit B4)")
    void thereIsNoAmbiguousAccountColumn() {
        assertThat(PgTestSupport.queryLong(url,
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_name = 'vertical_workload_policy'"
                        + " AND column_name = 'cloud_account_id'"))
                .as("that column holds a DB primary key on modern rows and an AWS account number on "
                        + "legacy ones; a new table must not inherit the ambiguity")
                .isZero();
    }

    @Test
    @DisplayName("the unique key includes the namespace, so one name in two namespaces is two workloads")
    void theUniqueKeyIncludesTheNamespace() {
        PgTestSupport.execute(url, "INSERT INTO client (id, name) VALUES (1, 'acme') ON CONFLICT DO NOTHING");
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name)"
                + " VALUES (10, 'xamops-test') ON CONFLICT DO NOTHING");

        insert(1, 10, "team-a", "api");
        insert(1, 10, "team-b", "api");

        assertThat(PgTestSupport.queryLong(url,
                "SELECT count(*) FROM vertical_workload_policy WHERE workload_name = 'api'")).isEqualTo(2);

        // The same workload twice must be refused.
        boolean refused = false;
        try {
            insert(1, 10, "team-a", "api");
        } catch (RuntimeException expected) {
            refused = true;
        }
        assertThat(refused).as("one policy per workload").isTrue();
    }

    @Test
    @DisplayName("a row written with only its identity picks up every default")
    void aBareRowIsSafe() {
        PgTestSupport.execute(url, "INSERT INTO client (id, name) VALUES (2, 'bare') ON CONFLICT DO NOTHING");
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name)"
                + " VALUES (20, 'bare-cluster') ON CONFLICT DO NOTHING");
        PgTestSupport.execute(url, "INSERT INTO vertical_workload_policy"
                + " (client_id, cluster_config_id, namespace, workload_kind, workload_name)"
                + " VALUES (2, 20, 'default', 'Deployment', 'bare')");

        assertThat(PgTestSupport.queryString(url, "SELECT mode FROM vertical_workload_policy"
                + " WHERE workload_name = 'bare'"))
                .as("a new automation feature must do nothing until somebody asks for it")
                .isEqualTo("OFF");
        assertThat(PgTestSupport.queryLong(url, "SELECT cpu_floor_millicores FROM vertical_workload_policy"
                + " WHERE workload_name = 'bare'")).isEqualTo(25);
        assertThat(PgTestSupport.queryLong(url, "SELECT memory_floor_bytes FROM vertical_workload_policy"
                + " WHERE workload_name = 'bare'")).isEqualTo(262144000L);
    }

    // ---------------------------------------------------------------- helpers

    private static void insert(long clientId, long clusterConfigId, String namespace, String name) {
        PgTestSupport.execute(url, "INSERT INTO vertical_workload_policy"
                + " (client_id, cluster_config_id, namespace, workload_kind, workload_name)"
                + " VALUES (?, ?, ?, 'Deployment', ?)", clientId, clusterConfigId, namespace, name);
    }

    private static String defaultOf(String column) {
        return PgTestSupport.queryString(url,
                "SELECT column_default FROM information_schema.columns"
                        + " WHERE table_name = 'vertical_workload_policy' AND column_name = ?", column);
    }

    private static String typeOf(String column) {
        return PgTestSupport.queryString(url,
                "SELECT data_type FROM information_schema.columns"
                        + " WHERE table_name = 'vertical_workload_policy' AND column_name = ?", column);
    }

    private static long numericDefault(String column) {
        return Long.parseLong(literalDefault(column));
    }

    private static double doubleDefault(String column) {
        return Double.parseDouble(literalDefault(column));
    }

    /**
     * The default's literal, with PostgreSQL's rendering stripped: it writes a literal plus an
     * optional cast, and quotes it when the literal's type differs from the column's, so
     * {@code 25}, {@code '25'::bigint} and {@code 4.0} are all possible.
     */
    private static String literalDefault(String column) {
        String raw = defaultOf(column);
        assertThat(raw).as("column %s has no default", column).isNotNull();
        return raw.replaceAll("::.*$", "").replace("'", "").trim();
    }
}
