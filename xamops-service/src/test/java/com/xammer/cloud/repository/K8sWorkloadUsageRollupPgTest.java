package com.xammer.cloud.repository;

import com.xammer.cloud.domain.type.PgTestSupport;
import com.xammer.cloud.service.automation.workload.usage.store.UsagePartitions.Table;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupJob;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupMath;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupProperties;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.DailyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.HourlyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.IdentityKey;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.SampleRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.UsageRow;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End to end on real PostgreSQL with the real migration: staged samples &rarr;
 * {@link UsageRollupJob#runHourly()} &rarr; hourly rows, rerun idempotence, then
 * {@link UsageRollupJob#runMaintenance()} 125 days later &rarr; daily rows equal to
 * {@link UsageRollupMath#rollupDay} of the hourly rows, the aged hourly and sample partitions gone, the
 * current month's partitions present, and the tenant read API still isolated.
 *
 * <p>The job runs with a real {@link TransactionTemplate} on a {@link DataSourceTransactionManager}, so
 * the advisory lock really is held inside a transaction around each DDL statement. The tests share one database and run in
 * order: each step builds on the previous one, like the jobs do in production.
 */
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class K8sWorkloadUsageRollupPgTest {

    private static final long MI = 1024L * 1024L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);
    private static final Instant HOUR_10 = Instant.parse("2026-10-24T10:00:00Z");
    private static final Instant HOUR_11 = Instant.parse("2026-10-24T11:00:00Z");
    /** 10:55Z .. 11:05Z, one snapshot per minute: 5 in hour 10, 6 in hour 11. */
    private static final Instant FIRST_SNAPSHOT = Instant.parse("2026-10-24T10:55:00Z");
    private static final int SNAPSHOTS = 11;
    private static final Instant HOURLY_RUN = Instant.parse("2026-10-24T12:07:00Z");
    private static final Instant MAINTENANCE_RUN = HOURLY_RUN.plus(Duration.ofDays(125)); // 2027-02-26T12:07Z

    private static final long CLIENT_A = 801;
    private static final long CLUSTER_A = 8011;
    private static final long CLIENT_B = 802;
    private static final long CLUSTER_B = 8021;

    private static String url;
    private static DriverManagerDataSource dataSource;
    private static UsageRollupStore store;
    private static Map<String, Long> idsA;
    private static Map<String, Long> idsB;
    private static List<HourlyRow> hourlyAfterFirstRun;

    @BeforeAll
    static void seed() {
        url = PgTestSupport.recreateDatabase("k8s_usage_rollup");
        K8sWorkloadUsageMigrationPgTest.createReferencedTables(url);
        MigrationScript.of(K8sWorkloadUsageMigrationPgTest.MIGRATION).applyTo(url);
        dataSource = new DriverManagerDataSource(url, PgTestSupport.user(), PgTestSupport.password());
        store = new UsageRollupStore(dataSource);

        idsA = tenant(CLIENT_A, CLUSTER_A, 0);
        idsB = tenant(CLIENT_B, CLUSTER_B, 1000);
    }

    // ---------------------------------------------------------------- hourly

    @Test
    @Order(1)
    @DisplayName("runHourly at 12:07Z: two hourly rows per workload with the expected p50/p95/max and counts")
    void hourlyRollup() {
        job(HOURLY_RUN).runHourly();

        assertThat(count("k8s_workload_usage_hourly")).as("2 tenants x 2 containers x 2 hours").isEqualTo(8);
        List<UsageRow> rows = store.findHourly(CLIENT_A, CLUSTER_A, HOUR_10, HOUR_11.plus(Duration.ofHours(1)));
        assertThat(rows).hasSize(4);

        UsageRow app10 = row(rows, "app", HOUR_10);
        // hour 10: snapshots k = 0..4, pods cpu 10(k+1) and 10(k+1)+5 -> [10, 15, .., 55], n = 10.
        // p50 rank 4.5 -> 30 + 0.5 * 5 = 32.5; p95 rank 8.55 -> 50 + 0.55 * 5 = 52.75; max 55.
        assertThat(app10.sampleCount()).isEqualTo(10);
        assertThat(app10.snapshotCount()).isEqualTo(5);
        assertThat(app10.cpuP50M()).isEqualTo(32.5);
        assertThat(app10.cpuP95M()).isEqualTo(52.75);
        assertThat(app10.cpuMaxM()).isEqualTo(55.0);
        // memory = cpu / 10 MiB: p50 3.25 MiB, p95 5.275 MiB = 5531238.4 -> 5531238, max 5.5 MiB.
        assertThat(app10.memP50B()).isEqualTo(3_407_872L);
        assertThat(app10.memP95B()).isEqualTo(5_531_238L);
        assertThat(app10.memMaxB()).isEqualTo(5_767_168L);
        assertThat(app10.replicasMin()).isEqualTo(2);
        assertThat(app10.replicasMax()).isEqualTo(2);
        assertThat(app10.replicasAvg()).isEqualTo(2.0);
        assertThat(app10.cpuReqM()).isEqualTo(250.0);
        assertThat(app10.memReqB()).as("unset request stays null").isNull();
        assertThat(app10.memLimB()).isEqualTo(512 * MI);

        UsageRow app11 = row(rows, "app", HOUR_11);
        // hour 11: k = 5..10 -> [60, 65, .., 115], n = 12. p50 rank 5.5 -> 87.5; p95 rank 10.45 -> 112.25.
        assertThat(app11.sampleCount()).isEqualTo(12);
        assertThat(app11.snapshotCount()).isEqualTo(6);
        assertThat(app11.cpuP50M()).isEqualTo(87.5);
        assertThat(app11.cpuP95M()).isEqualTo(112.25);
        assertThat(app11.cpuMaxM()).isEqualTo(115.0);

        assertThat(row(rows, "sidecar", HOUR_10).sampleCount()).isEqualTo(10);
        assertThat(row(rows, "sidecar", HOUR_11).sampleCount()).isEqualTo(12);

        assertThat(store.findHourly(CLIENT_B, CLUSTER_B, HOUR_10, HOUR_11.plus(Duration.ofHours(1))))
                .hasSize(4).allSatisfy(r -> assertThat(r.cpuMaxM()).as("B's own values").isGreaterThan(1000));
        assertThat(PgTestSupport.queryString(url, "SELECT (max(last_seen_at) = ?::timestamptz)::text"
                        + " FROM k8s_workload_identity WHERE cluster_config_id = ?",
                FIRST_SNAPSHOT.plus(Duration.ofMinutes(SNAPSHOTS - 1)).toString(), CLUSTER_A))
                .as("touchLastSeen raised last_seen_at to the latest sample").isEqualTo("true");

        hourlyAfterFirstRun = allHourly();
    }

    @Test
    @Order(2)
    @DisplayName("running the hourly job again leaves identical rows (count and values)")
    void hourlyRerunIsIdempotent() {
        job(HOURLY_RUN).runHourly();
        job(HOURLY_RUN.plus(Duration.ofMinutes(30))).runHourly();
        assertThat(count("k8s_workload_usage_hourly")).isEqualTo(8);
        assertThat(allHourly()).isEqualTo(hourlyAfterFirstRun);
    }

    // ---------------------------------------------------------------- maintenance

    @Test
    @Order(3)
    @DisplayName("runMaintenance 125 days later: daily = rollupDay(hourly), aged hourly + sample partitions dropped")
    void maintenance() {
        assertThat(partitions(Table.HOURLY)).contains("k8s_workload_usage_hourly_y2026m10");
        assertThat(partitions(Table.SAMPLE)).contains("k8s_workload_usage_sample_d20261024");

        job(MAINTENANCE_RUN).runMaintenance();

        // Daily rows exist and equal UsageRollupMath.rollupDay over the hourly rows they came from.
        assertThat(count("k8s_workload_usage_daily")).isEqualTo(4);
        for (long cluster : List.of(CLUSTER_A, CLUSTER_B)) {
            long client = cluster == CLUSTER_A ? CLIENT_A : CLIENT_B;
            List<UsageRow> daily = store.findDaily(client, cluster, DAY, DAY.plusDays(1));
            assertThat(daily).hasSize(2);
            for (UsageRow actual : daily) {
                List<HourlyRow> hours = hourlyAfterFirstRun.stream()
                        .filter(h -> h.clusterConfigId() == cluster && h.workloadId() == actual.workloadId())
                        .toList();
                assertThat(hours).hasSize(2);
                DailyRow expected = UsageRollupMath.rollupDay(client, cluster, actual.workloadId(), DAY, hours);
                assertThat(actual.start()).isEqualTo(DAY.atStartOfDay(ZoneOffset.UTC).toInstant());
                assertThat(actual.memP50B()).isEqualTo(expected.memP50B());
                assertThat(actual.memP95B()).isEqualTo(expected.memP95B());
                assertThat(actual.memMaxB()).isEqualTo(expected.memMaxB());
                assertThat(actual.memReqB()).isEqualTo(expected.memReqB());
                assertThat(actual.memLimB()).isEqualTo(expected.memLimB());
                assertThat(actual.cpuP50M()).isEqualTo(real(expected.cpuP50M()));
                assertThat(actual.cpuP95M()).isEqualTo(real(expected.cpuP95M()));
                assertThat(actual.cpuMaxM()).isEqualTo(real(expected.cpuMaxM()));
                assertThat(actual.cpuReqM()).isEqualTo(expected.cpuReqM());
                assertThat(actual.cpuLimM()).isEqualTo(expected.cpuLimM());
                assertThat(actual.replicasAvg()).isCloseTo(expected.replicasAvg(), within(1e-6));
                assertThat(actual.replicasMin()).isEqualTo(expected.replicasMin());
                assertThat(actual.replicasMax()).isEqualTo(expected.replicasMax());
                assertThat(actual.sampleCount()).isEqualTo(expected.sampleCount()).isEqualTo(22);
                assertThat(actual.snapshotCount()).isEqualTo(expected.snapshotCount()).isEqualTo(11);
                assertThat(actual.hoursCovered()).isEqualTo(expected.hoursCovered()).isEqualTo(2);
            }
        }

        // Aged partitions gone: hourly October (upper bound 2026-11-01 <= now - 90 d) only after
        // verification, sample Oct 24 (<= now - 48 h). November hourly is within 90 days: kept.
        assertThat(partitions(Table.HOURLY)).doesNotContain("k8s_workload_usage_hourly_y2026m10")
                .contains("k8s_workload_usage_hourly_y2026m11", "k8s_workload_usage_hourly_y2026m12");
        assertThat(partitions(Table.SAMPLE)).doesNotContain("k8s_workload_usage_sample_d20261024");
        assertThat(count("k8s_workload_usage_hourly")).isZero();
        assertThat(count("k8s_workload_usage_sample")).isZero();
        assertThat(partitions(Table.DAILY)).as("daily rows are kept 730 days").contains("k8s_workload_usage_daily_y2026m10");

        // Current-month partitions (+ premake 2) and the next samples exist.
        assertThat(partitions(Table.HOURLY)).contains("k8s_workload_usage_hourly_y2027m02",
                "k8s_workload_usage_hourly_y2027m03", "k8s_workload_usage_hourly_y2027m04");
        assertThat(partitions(Table.DAILY)).contains("k8s_workload_usage_daily_y2027m02",
                "k8s_workload_usage_daily_y2027m03", "k8s_workload_usage_daily_y2027m04");
        assertThat(partitions(Table.SAMPLE)).contains("k8s_workload_usage_sample_d20270226",
                "k8s_workload_usage_sample_d20270227", "k8s_workload_usage_sample_d20270228");

        // Tenant isolation of the result.
        List<UsageRow> dailyA = store.findDaily(CLIENT_A, CLUSTER_A, DAY, DAY.plusDays(1));
        assertThat(dailyA).extracting(UsageRow::workloadId).containsExactlyInAnyOrderElementsOf(idsA.values())
                .doesNotContainAnyElementsOf(idsB.values());
        assertThat(dailyA).allSatisfy(r -> assertThat(r.cpuMaxM()).isLessThan(1000));
        assertThat(store.findDaily(CLIENT_A, CLUSTER_B, DAY, DAY.plusDays(1))).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("a second maintenance run is a no-op: same daily rows, nothing more dropped, no failure")
    void maintenanceRerun() {
        List<UsageRow> before = store.findDaily(CLIENT_A, CLUSTER_A, DAY, DAY.plusDays(1));
        job(MAINTENANCE_RUN.plus(Duration.ofHours(1))).runMaintenance();
        assertThat(store.findDaily(CLIENT_A, CLUSTER_A, DAY, DAY.plusDays(1))).isEqualTo(before);
        assertThat(count("k8s_workload_usage_daily")).isEqualTo(4);
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * One client + cluster with a 2-pod Deployment {@code shop/api} of two containers, sampled once a
     * minute from 10:55Z to 11:05Z. {@code offset} is added to every cpu value so a leaked row of the
     * other tenant is recognisable.
     */
    private static Map<String, Long> tenant(long clientId, long clusterId, int offset) {
        PgTestSupport.execute(url, "INSERT INTO client (id, name) VALUES (?, ?)", clientId, "client-" + clientId);
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name) VALUES (?, ?)",
                clusterId, "cluster-" + clusterId);
        Map<String, IdentityKey> keys = new LinkedHashMap<>();
        for (String container : List.of("app", "sidecar")) {
            IdentityKey key = identity("shop", "Deployment", "api", container);
            keys.put(container, key);
        }
        Map<String, Long> byKey = store.upsertIdentities(clientId, clusterId, keys.values(), FIRST_SNAPSHOT);
        Map<String, Long> ids = new LinkedHashMap<>();
        keys.forEach((container, key) -> ids.put(container, byKey.get(key.workloadKey())));
        assertThat(ids.values()).doesNotContainNull();

        store.ensurePartition(Table.SAMPLE, DAY);
        List<SampleRow> samples = new ArrayList<>();
        for (int k = 0; k < SNAPSHOTS; k++) {
            Instant at = FIRST_SNAPSHOT.plus(Duration.ofMinutes(k));
            for (long workloadId : ids.values()) {
                for (int pod = 0; pod < 2; pod++) {
                    double cpu = 10.0 * (k + 1) + 5 * pod;
                    long mem = Math.round(cpu / 10 * MI);
                    samples.add(new SampleRow(clusterId, clientId, workloadId, at, mem, null, 512 * MI,
                            cpu + offset, 250.0, null, 1000 + pod, 2));
                }
            }
        }
        assertThat(store.insertSamples(samples)).isEqualTo(SNAPSHOTS * 2 * 2);
        return ids;
    }

    private static UsageRollupJob job(Instant now) {
        try {
            // The Clock seam is package-private (like ClusterCostSnapshotService); this test lives in
            // the repository package with the other PG tests, so it reaches it reflectively.
            Constructor<UsageRollupJob> constructor = UsageRollupJob.class.getDeclaredConstructor(
                    UsageRollupProperties.class, UsageRollupStore.class, MeterRegistry.class,
                    TransactionOperations.class, Clock.class);
            constructor.setAccessible(true);
            return constructor.newInstance(properties(), store, new SimpleMeterRegistry(),
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                    Clock.fixed(now, ZoneOffset.UTC));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UsageRollupProperties properties() {
        return new UsageRollupProperties(true, 90, 730, 48, 2, 3, 3, 50,
                UsageRollupProperties.DEFAULT_HOURLY_CRON, UsageRollupProperties.DEFAULT_MAINTENANCE_CRON);
    }

    private static List<HourlyRow> allHourly() {
        List<HourlyRow> rows = new ArrayList<>();
        for (long cluster : List.of(CLUSTER_A, CLUSTER_B)) {
            rows.addAll(store.readHourly(cluster, HOUR_10, HOUR_11.plus(Duration.ofHours(1))));
        }
        return rows;
    }

    private static UsageRow row(List<UsageRow> rows, String container, Instant hour) {
        return rows.stream().filter(r -> r.container().equals(container) && r.start().equals(hour))
                .findFirst().orElseThrow();
    }

    private static List<String> partitions(Table table) {
        return store.listPartitions(table.parent());
    }

    private static long count(String table) {
        return PgTestSupport.queryLong(url, "SELECT count(*) FROM " + table);
    }

    /** The double a REAL column reads back as (see UsageRollupStore). */
    private static double real(double value) {
        return Double.parseDouble(Float.toString((float) value));
    }

    private static IdentityKey identity(String namespace, String kind, String name, String container) {
        return new IdentityKey(namespace, kind, name, container, workloadKey(namespace, kind, name, container));
    }

    private static String workloadKey(String namespace, String kind, String name, String container) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((kind + "/" + namespace + "/" + name + "/" + container).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
