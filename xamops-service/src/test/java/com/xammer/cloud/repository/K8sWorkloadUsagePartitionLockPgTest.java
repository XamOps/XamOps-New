package com.xammer.cloud.repository;

import com.xammer.cloud.domain.type.PgTestSupport;
import com.xammer.cloud.service.automation.workload.usage.store.UsagePartitions;
import com.xammer.cloud.service.automation.workload.usage.store.UsagePartitions.Table;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupJob;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupProperties;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.PartitionLockTimeoutException;
import com.xammer.cloud.service.automation.workload.usage.store.UsageSampleCapture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Partition creation must never queue agent ingest. {@code CREATE TABLE ... PARTITION OF} clones the
 * cascading foreign keys and takes SHARE ROW EXCLUSIVE on {@code client} and
 * {@code kubernetes_cluster_config}; every agent push UPDATEs its {@code kubernetes_cluster_config}
 * row. Here a second session holds exactly such an open UPDATE, and the store's create, and the
 * maintenance job around it, must give up within the lock timeout without failing, then succeed once
 * the ingest transaction ends.
 *
 * <p>Opt-in like {@link VerticalPolicyMigrationPgTest}: skipped unless {@code LOBTEXT_TEST_JDBC_URL}
 * names a <em>local</em> server.
 */
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
class K8sWorkloadUsagePartitionLockPgTest {

    private static final long CLIENT = 1;
    private static final long CLUSTER = 11;
    /** Generous against the 2 s lock_timeout, far below "waits until the ingest commits". */
    private static final Duration GIVES_UP_WITHIN = Duration.ofSeconds(10);

    private static String url;
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void createSchema() {
        url = PgTestSupport.recreateDatabase("k8s_usage_lock");
        K8sWorkloadUsageMigrationPgTest.createReferencedTables(url);
        MigrationScript.of(K8sWorkloadUsageMigrationPgTest.MIGRATION).applyTo(url);
        dataSource = new DriverManagerDataSource(url, PgTestSupport.user(), PgTestSupport.password());
        PgTestSupport.execute(url, "INSERT INTO client (id, name) VALUES (?, 'tenant')", CLIENT);
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name) VALUES (?, 'c')", CLUSTER);
    }

    @Test
    @DisplayName("ensurePartition behind an open cluster-config UPDATE gives up on lock_timeout, then succeeds after it")
    void ensurePartitionGivesUpBehindAnIngestUpdate() throws SQLException {
        UsageRollupStore store = new UsageRollupStore(dataSource);
        LocalDate day = LocalDate.of(2027, 3, 10);
        String name = UsagePartitions.partitionName(Table.SAMPLE, day);

        try (Connection ingest = openClusterUpdate()) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> store.ensurePartition(Table.SAMPLE, day))
                    .isInstanceOf(PartitionLockTimeoutException.class)
                    .hasMessageContaining(name);
            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            assertThat(waited).as("waited for the lock_timeout, not for the ingest")
                    .isGreaterThanOrEqualTo(Duration.ofMillis(1500)).isLessThan(GIVES_UP_WITHIN);
            assertThat(store.listPartitions(Table.SAMPLE.parent())).doesNotContain(name);
            ingest.rollback();
        }

        store.ensurePartition(Table.SAMPLE, day);
        assertThat(store.listPartitions(Table.SAMPLE.parent())).contains(name);
    }

    @Test
    @DisplayName("inside a caller's transaction: its lock_timeout is restored, and a timed-out create leaves it usable")
    void insideATransaction() throws SQLException {
        UsageRollupStore store = new UsageRollupStore(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        LocalDate created = LocalDate.of(2027, 3, 12);
        LocalDate timedOut = LocalDate.of(2027, 3, 13);

        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '7s'");
            store.ensurePartition(Table.SAMPLE, created);
            assertThat(jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class)).isEqualTo("7s");
        });
        assertThat(store.listPartitions(Table.SAMPLE.parent()))
                .contains(UsagePartitions.partitionName(Table.SAMPLE, created));

        try (Connection ingest = openClusterUpdate()) {
            try {
                tx.executeWithoutResult(status -> {
                    assertThatThrownBy(() -> store.ensurePartition(Table.SAMPLE, timedOut))
                            .isInstanceOf(PartitionLockTimeoutException.class);
                    assertThat(jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class))
                            .as("rolled back to the savepoint: the transaction is usable, its setting intact")
                            .isEqualTo("0");
                });
            } finally {
                ingest.rollback();
            }
        }
        assertThat(store.listPartitions(Table.SAMPLE.parent()))
                .doesNotContain(UsagePartitions.partitionName(Table.SAMPLE, timedOut));
    }

    @Test
    @DisplayName("an existing partition needs no lock: ensurePartition returns at once behind the same UPDATE")
    void existingPartitionTakesNoLock() throws SQLException {
        // A static partition from the migration; daily partitions are kept 730 days, so no other test
        // of this class drops it.
        LocalDate month = LocalDate.of(2026, 11, 1);
        try (Connection ingest = openClusterUpdate()) {
            long started = System.nanoTime();
            new UsageRollupStore(dataSource).ensurePartition(Table.DAILY, month);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            ingest.rollback();
        }
    }

    @Test
    @DisplayName("dropping a partition does not wait on the cluster-config UPDATE")
    void dropDoesNotWaitOnIngest() throws SQLException {
        UsageRollupStore store = new UsageRollupStore(dataSource);
        LocalDate day = LocalDate.of(2027, 3, 20);
        store.ensurePartition(Table.SAMPLE, day);
        String name = UsagePartitions.partitionName(Table.SAMPLE, day);

        try (Connection ingest = openClusterUpdate()) {
            try {
                assertTimeoutPreemptively(GIVES_UP_WITHIN, () -> store.dropPartition(name));
            } finally {
                ingest.rollback();
            }
        }
        assertThat(store.listPartitions(Table.SAMPLE.parent())).doesNotContain(name);
    }

    @Test
    @DisplayName("maintenance behind the UPDATE skips the one missing create (not a failure) and makes it next run")
    void maintenanceSkipsAndRetries() throws SQLException {
        // 2027-04-15: hourly/daily y2027m04..m06 and samples Apr 15..22. Pre-create all but Apr 22 so
        // only one create has to wait for the lock.
        Instant now = Instant.parse("2027-04-15T01:30:00Z");
        UsageRollupStore setup = new UsageRollupStore(dataSource);
        for (int month = 4; month <= 6; month++) {
            setup.ensurePartition(Table.HOURLY, LocalDate.of(2027, month, 1));
            setup.ensurePartition(Table.DAILY, LocalDate.of(2027, month, 1));
        }
        for (int d = 15; d <= 21; d++) {
            setup.ensurePartition(Table.SAMPLE, LocalDate.of(2027, 4, d));
        }
        String missing = UsagePartitions.partitionName(Table.SAMPLE, LocalDate.of(2027, 4, 22));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageRollupStore store = new UsageRollupStore(dataSource);
        UsageRollupJob job = job(store, registry, now);
        try (Connection ingest = openClusterUpdate()) {
            try {
                assertTimeoutPreemptively(GIVES_UP_WITHIN, job::runMaintenance);
                assertTimeoutPreemptively(GIVES_UP_WITHIN, job::onApplicationReady);
            } finally {
                ingest.rollback();
            }
        }
        assertThat(store.listPartitions(Table.SAMPLE.parent())).doesNotContain(missing);
        assertThat(registry.get(UsageSampleCapture.ERRORS).tag("stage", "maintenance").counter().count())
                .as("a lock timeout is a skip, retried next run, not an error").isZero();

        job.runMaintenance();
        assertThat(store.listPartitions(Table.SAMPLE.parent())).contains(missing);
    }

    // ---------------------------------------------------------------- fixtures

    /** A second session in the middle of an ingest: it has UPDATEd the cluster's row and not committed. */
    private static Connection openClusterUpdate() throws SQLException {
        Connection ingest = PgTestSupport.connect(url);
        ingest.setAutoCommit(false);
        try (Statement statement = ingest.createStatement()) {
            statement.executeUpdate("UPDATE kubernetes_cluster_config SET cluster_name = cluster_name WHERE id = "
                    + CLUSTER);
        }
        return ingest;
    }

    private static UsageRollupJob job(UsageRollupStore store, MeterRegistry registry, Instant now) {
        try {
            // Package-private Clock seam, as in K8sWorkloadUsageRollupPgTest. A real transaction
            // manager, so the create runs under the advisory-lock transaction's savepoint.
            Constructor<UsageRollupJob> constructor = UsageRollupJob.class.getDeclaredConstructor(
                    UsageRollupProperties.class, UsageRollupStore.class, MeterRegistry.class,
                    TransactionOperations.class, Clock.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    new UsageRollupProperties(true, 90, 730, 48, 2, 3, 3, 50,
                            UsageRollupProperties.DEFAULT_HOURLY_CRON, UsageRollupProperties.DEFAULT_MAINTENANCE_CRON),
                    store, registry, new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                    Clock.fixed(now, ZoneOffset.UTC));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
