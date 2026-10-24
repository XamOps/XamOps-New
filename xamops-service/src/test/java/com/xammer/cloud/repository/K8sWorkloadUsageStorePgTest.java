package com.xammer.cloud.repository;

import com.xammer.cloud.domain.type.PgTestSupport;
import com.xammer.cloud.service.automation.workload.usage.store.UsagePartitions;
import com.xammer.cloud.service.automation.workload.usage.store.UsagePartitions.Table;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.DailyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.HourlyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.IdentityKey;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.SampleRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.UsageRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link UsageRollupStore} against real PostgreSQL with the real migration: tenant isolation of the
 * read API, the tenant resolver SQL, cascade on cluster and client delete across partitions,
 * idempotent partition creation, replayed-sample dedupe, and the measured storage cost of an hourly
 * and a sample row.
 *
 * <p>Opt-in like {@link VerticalPolicyMigrationPgTest}: skipped unless {@code LOBTEXT_TEST_JDBC_URL}
 * names a <em>local</em> server. Each test seeds its own client and cluster ids, so the tests do not
 * depend on each other's order.
 */
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
class K8sWorkloadUsageStorePgTest {

    private static final long MI = 1024L * 1024L;
    private static final Instant SEEN = Instant.parse("2026-10-31T23:05:00Z");
    private static final Instant OCT_HOUR = Instant.parse("2026-10-31T23:00:00Z");
    private static final Instant NOV_HOUR = Instant.parse("2026-11-01T00:00:00Z");
    private static final LocalDate OCT_DAY = LocalDate.of(2026, 10, 31);
    private static final LocalDate NOV_DAY = LocalDate.of(2026, 11, 1);
    private static final List<String> FACT_TABLES = List.of(
            "k8s_workload_identity", "k8s_workload_usage_sample", "k8s_workload_usage_hourly",
            "k8s_workload_usage_daily");

    private static String url;
    private static DriverManagerDataSource dataSource;
    private static UsageRollupStore store;

    @BeforeAll
    static void createSchema() {
        url = PgTestSupport.recreateDatabase("k8s_usage_store");
        K8sWorkloadUsageMigrationPgTest.createReferencedTables(url);
        MigrationScript.of(K8sWorkloadUsageMigrationPgTest.MIGRATION).applyTo(url);
        // What resolveClientId reads, reduced to its columns. Unlike the entity (aws_account_id unique,
        // client_id not null) neither is constrained here, so the ambiguous and NULL guards are tested.
        PgTestSupport.execute(url, "ALTER TABLE kubernetes_cluster_config ADD COLUMN cloud_account_id BIGINT");
        PgTestSupport.execute(url, "CREATE TABLE cloud_account (id BIGINT PRIMARY KEY,"
                + " aws_account_id VARCHAR(255), client_id BIGINT)");

        dataSource = new DriverManagerDataSource(url, PgTestSupport.user(), PgTestSupport.password());
        store = new UsageRollupStore(dataSource);
    }

    // ---------------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("same names in two tenants: each tenant reads only its own rows, never the other's cluster")
    void theReadApiIsTenantIsolated() {
        Tenant a = seed(101, 1011);
        Tenant b = seed(102, 1021);
        Instant from = Instant.parse("2026-10-31T00:00:00Z");
        Instant to = Instant.parse("2026-11-02T00:00:00Z");

        List<UsageRow> hourly = store.findHourly(a.clientId(), a.clusterId(), from, to);
        assertThat(hourly).hasSize(4)
                .allSatisfy(row -> assertThat(row.memP50B()).as("only A's marker").isEqualTo(marker(a)));
        assertThat(hourly).extracting(UsageRow::container, UsageRow::start).containsExactly(
                tuple("app", OCT_HOUR),
                tuple("app", NOV_HOUR),
                tuple("sidecar", OCT_HOUR),
                tuple("sidecar", NOV_HOUR));
        UsageRow first = hourly.get(0);
        assertThat(first.namespace()).isEqualTo("shop");
        assertThat(first.workloadKind()).isEqualTo("Deployment");
        assertThat(first.workloadName()).isEqualTo("api");
        assertThat(first.workloadId()).isEqualTo(a.ids().get(workloadKey("shop", "Deployment", "api", "app")));
        assertThat(first.memReqB()).as("an unset request reads back as null, never 0").isNull();
        assertThat(first.memLimB()).isEqualTo(512 * MI);
        assertThat(first.cpuP50M()).isEqualTo(1.234567);
        assertThat(first.cpuLimM()).isNull();
        assertThat(first.sampleCount()).isEqualTo(24);
        assertThat(first.snapshotCount()).isEqualTo(12);
        assertThat(first.hoursCovered()).isNull();

        assertThat(store.findHourly(a.clientId(), b.clusterId(), from, to))
                .as("tenant A asking for tenant B's cluster gets nothing").isEmpty();
        assertThat(store.findHourly(b.clientId(), a.clusterId(), from, to)).isEmpty();
        assertThat(store.findHourly(b.clientId(), b.clusterId(), from, to)).hasSize(4)
                .allSatisfy(row -> assertThat(row.memP50B()).isEqualTo(marker(b)));

        List<UsageRow> daily = store.findDaily(a.clientId(), a.clusterId(), OCT_DAY, NOV_DAY.plusDays(1));
        assertThat(daily).hasSize(4)
                .allSatisfy(row -> {
                    assertThat(row.memP50B()).isEqualTo(marker(a));
                    assertThat(row.hoursCovered()).isEqualTo(24);
                });
        assertThat(daily.get(0).start()).isEqualTo(Instant.parse("2026-10-31T00:00:00Z"));
        assertThat(store.findDaily(a.clientId(), a.clusterId(), OCT_DAY, NOV_DAY)).as("to is exclusive").hasSize(2);
        assertThat(store.findDaily(a.clientId(), b.clusterId(), OCT_DAY, NOV_DAY.plusDays(1))).isEmpty();
        assertThat(store.findDaily(b.clientId(), a.clusterId(), OCT_DAY, NOV_DAY.plusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("an identity row of another client is never returned as this tenant's workload id")
    void identitiesAreTenantScoped() {
        Tenant a = seed(111, 1111);
        PgTestSupport.execute(url, "INSERT INTO client (id, name) VALUES (112, 'intruder')");
        IdentityKey key = identity("shop", "Deployment", "api", "app");

        assertThat(store.upsertIdentities(112, a.clusterId(), List.of(key), SEEN))
                .as("client 112 cannot adopt client 111's identity in cluster 1111").isEmpty();
        assertThat(store.upsertIdentities(a.clientId(), a.clusterId(), List.of(key), SEEN))
                .containsEntry(key.workloadKey(), a.ids().get(key.workloadKey()));
    }

    // ---------------------------------------------------------------- cascades

    @Test
    @DisplayName("deleting a cluster removes its identity, sample, hourly (2 partitions) and daily rows only")
    void clusterDeleteCascades() {
        Tenant a = seed(201, 2011);
        Tenant b = seed(202, 2021);
        assertThat(rows("k8s_workload_usage_hourly_y2026m10", "cluster_config_id", a.clusterId())).isEqualTo(2);
        assertThat(rows("k8s_workload_usage_hourly_y2026m11", "cluster_config_id", a.clusterId())).isEqualTo(2);
        Map<String, Long> bBefore = rowsPerTable("cluster_config_id", b.clusterId());

        PgTestSupport.execute(url, "DELETE FROM kubernetes_cluster_config WHERE id = ?", a.clusterId());

        assertThat(rowsPerTable("cluster_config_id", a.clusterId())).allSatisfy((table, count) ->
                assertThat(count).as(table).isZero());
        assertThat(rowsPerTable("cluster_config_id", b.clusterId())).isEqualTo(bBefore);
        assertThat(bBefore).containsEntry("k8s_workload_usage_hourly", 4L);
    }

    @Test
    @DisplayName("deleting a client removes all its identity, sample, hourly and daily rows")
    void clientDeleteCascades() {
        Tenant a = seed(301, 3011);
        Tenant b = seed(302, 3021);
        Map<String, Long> aBefore = rowsPerTable("client_id", a.clientId());

        PgTestSupport.execute(url, "DELETE FROM client WHERE id = ?", b.clientId());

        assertThat(rowsPerTable("client_id", b.clientId())).allSatisfy((table, count) ->
                assertThat(count).as(table).isZero());
        assertThat(PgTestSupport.queryLong(url, "SELECT count(*) FROM kubernetes_cluster_config WHERE id = ?",
                b.clusterId()))
                .as("the minimal test cluster table has no client FK: only the client_id cascade ran")
                .isEqualTo(1);
        assertThat(rowsPerTable("client_id", a.clientId())).isEqualTo(aBefore);
    }

    // ---------------------------------------------------------------- partitions and writes

    @Test
    @DisplayName("ensurePartition is idempotent, cached or not, and listPartitions sees it once")
    void ensurePartitionIsIdempotent() {
        LocalDate day = LocalDate.of(2026, 11, 15);
        store.ensurePartition(Table.SAMPLE, day);
        store.ensurePartition(Table.SAMPLE, day);
        UsageRollupStore cold = new UsageRollupStore(dataSource);
        cold.ensurePartition(Table.SAMPLE, day);
        cold.ensurePartition(Table.HOURLY, LocalDate.of(2026, 10, 1));

        assertThat(store.listPartitions(Table.SAMPLE.parent()))
                .filteredOn("k8s_workload_usage_sample_d20261115"::equals).hasSize(1);
        assertThat(store.listPartitions(Table.HOURLY.parent()))
                .contains("k8s_workload_usage_hourly_y2026m10", "k8s_workload_usage_hourly_y2027m01");
    }

    @Test
    @DisplayName("dropPartition drops one of ours, and ensurePartition can re-create it afterwards")
    void dropPartition() {
        LocalDate day = LocalDate.of(2026, 12, 2);
        store.ensurePartition(Table.SAMPLE, day);
        assertThat(store.listPartitions(Table.SAMPLE.parent())).contains("k8s_workload_usage_sample_d20261202");

        store.dropPartition("k8s_workload_usage_sample_d20261202");
        assertThat(store.listPartitions(Table.SAMPLE.parent())).doesNotContain("k8s_workload_usage_sample_d20261202");

        store.ensurePartition(Table.SAMPLE, day);
        assertThat(store.listPartitions(Table.SAMPLE.parent())).contains("k8s_workload_usage_sample_d20261202");
    }

    @Test
    @DisplayName("a replayed sample batch keeps one copy of each point")
    void replayedSamplesDedupe() {
        Tenant t = seed(401, 4011);
        long wid = t.ids().get(workloadKey("shop", "Deployment", "api", "app"));
        Instant at = Instant.parse("2026-10-31T22:10:00Z");
        List<SampleRow> batch = List.of(
                new SampleRow(t.clusterId(), t.clientId(), wid, at, 34 * MI, null, 64 * MI, 1.234567, 100.0, null, 11, 2),
                new SampleRow(t.clusterId(), t.clientId(), wid, at, 36 * MI, null, 64 * MI, 2.5, 100.0, null, 22, 2),
                new SampleRow(t.clusterId(), t.clientId(), wid, at.plusSeconds(300), 35 * MI, null, 64 * MI, 3.0,
                        100.0, null, 11, 2));

        assertThat(store.insertSamples(batch)).isEqualTo(3);
        assertThat(store.insertSamples(batch)).as("the replay inserts nothing").isZero();

        List<SampleRow> read = store.readSamples(t.clusterId(), Instant.parse("2026-10-31T22:00:00Z"),
                Instant.parse("2026-10-31T23:00:00Z"));
        assertThat(read).containsExactlyInAnyOrderElementsOf(batch);
        assertThat(store.clustersWithUsage()).contains(t.clusterId());
    }

    @Test
    @DisplayName("upsertHourly/upsertDaily recompute: a rerun replaces the row, never adds to it")
    void upsertsRecompute() {
        Tenant t = seed(501, 5011);
        long wid = t.ids().get(workloadKey("shop", "Deployment", "api", "app"));
        HourlyRow replacement = new HourlyRow(t.clusterId(), t.clientId(), wid, OCT_HOUR,
                7, 8, 9, 100 * MI, null, 0.5, 0.75, 1.0, null, 250.0, 1.0, 3, 3, 1, 1);
        store.upsertHourly(List.of(replacement));
        store.upsertHourly(List.of(replacement));

        List<HourlyRow> hourly = store.readHourly(t.clusterId(), OCT_HOUR, OCT_HOUR.plus(Duration.ofHours(1)));
        assertThat(hourly).filteredOn(row -> row.workloadId() == wid).containsExactly(replacement);

        DailyRow day = new DailyRow(t.clusterId(), t.clientId(), wid, OCT_DAY,
                7, 8, 9, null, null, 0.5, 0.75, 1.0, null, null, 1.0, 3, 3, 1, 1, 1);
        store.upsertDaily(List.of(day));
        store.upsertDaily(List.of(day));
        assertThat(PgTestSupport.queryLong(url, "SELECT sample_count FROM k8s_workload_usage_daily"
                + " WHERE cluster_config_id = ? AND workload_id = ? AND day = ?", t.clusterId(), wid, OCT_DAY))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("touchLastSeen raises last_seen_at and never lowers it")
    void touchLastSeen() {
        Tenant t = seed(601, 6011);
        long wid = t.ids().get(workloadKey("shop", "Deployment", "api", "app"));
        Instant later = SEEN.plus(Duration.ofHours(2));

        store.touchLastSeen(t.clusterId(), List.of(wid), later);
        store.touchLastSeen(t.clusterId(), List.of(wid), SEEN);

        assertThat(PgTestSupport.queryString(url, "SELECT (last_seen_at = ?::timestamptz)::text"
                + " FROM k8s_workload_identity WHERE id = ?", later.toString(), wid)).isEqualTo("true");
    }

    @Test
    @DisplayName("countUnrolledDays counts hourly (cluster, workload, day) groups without a daily row")
    void countUnrolledDays() {
        Tenant t = seed(701, 7011);
        long wid = t.ids().get(workloadKey("shop", "Deployment", "api", "app"));
        LocalDate march = LocalDate.of(2027, 3, 1);
        store.ensurePartition(Table.HOURLY, march);
        store.ensurePartition(Table.DAILY, march);
        String partition = UsagePartitions.partitionName(Table.HOURLY, march);
        store.upsertHourly(List.of(
                hourly(t, wid, Instant.parse("2027-03-01T00:00:00Z")),
                hourly(t, wid, Instant.parse("2027-03-01T23:00:00Z")),
                hourly(t, wid, Instant.parse("2027-03-02T05:00:00Z"))));

        assertThat(store.countUnrolledDays(partition)).isEqualTo(2);
        store.upsertDaily(List.of(daily(t, wid, LocalDate.of(2027, 3, 1))));
        assertThat(store.countUnrolledDays(partition)).isEqualTo(1);
        store.upsertDaily(List.of(daily(t, wid, LocalDate.of(2027, 3, 2))));
        assertThat(store.countUnrolledDays(partition)).isZero();
    }

    @Test
    @DisplayName("clustersWithUsage lists every cluster with an identity, once, in id order")
    void clustersWithUsage() {
        Tenant a = seed(1201, 12011);
        Tenant b = seed(1202, 12012);
        PgTestSupport.execute(url, "INSERT INTO client (id, name) VALUES (1203, 'idle')");
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name) VALUES (12013, 'idle')");

        List<Long> clusters = store.clustersWithUsage();
        assertThat(clusters).contains(a.clusterId(), b.clusterId()).doesNotContain(12013L)
                .doesNotHaveDuplicates().isSorted();
    }

    // ---------------------------------------------------------------- tenant resolution

    @Test
    @DisplayName("resolveClientId: cloud_account.id match first, legacy aws_account_id match second")
    void resolveClientIdMatches() {
        account(91001, "111111111111", 1001L);
        cluster(91011, 91001L);
        assertThat(store.resolveClientId(91011)).as("primary: kcc.cloud_account_id = cloud_account.id").contains(1001L);

        account(91002, "222222222222", 1002L);
        cluster(91012, 222222222222L);
        assertThat(store.resolveClientId(91012)).as("legacy: the AWS account number in cloud_account_id (B4)")
                .contains(1002L);

        // 91003 is both an account id (client 1003) and another account's aws_account_id (client 1004).
        account(91003, "999999999991", 1003L);
        account(91004, "91003", 1004L);
        cluster(91013, 91003L);
        assertThat(store.resolveClientId(91013)).as("the primary match wins, the legacy one is not consulted")
                .contains(1003L);

        account(91005, "555555555555", 1005L);
        account(91006, "555555555555", 1005L);
        cluster(91015, 555555555555L);
        assertThat(store.resolveClientId(91015)).as("two accounts of the same client are one client").contains(1005L);
    }

    @Test
    @DisplayName("resolveClientId: ambiguous, NULL-client, unmatched and unknown clusters resolve to empty")
    void resolveClientIdNeverGuesses() {
        account(92001, "333333333333", 2001L);
        account(92002, "333333333333", 2002L);
        cluster(92011, 333333333333L);
        assertThat(store.resolveClientId(92011)).as("two clients share the account number: ambiguous").isEmpty();

        account(92003, "444444444444", null);
        cluster(92013, 92003L);
        assertThat(store.resolveClientId(92013)).as("the account has no client").isEmpty();
        cluster(92014, 444444444444L);
        assertThat(store.resolveClientId(92014)).as("the legacy match has no client either").isEmpty();

        cluster(92015, 777777777777L);
        assertThat(store.resolveClientId(92015)).as("no account matches").isEmpty();
        cluster(92016, null);
        assertThat(store.resolveClientId(92016)).as("no cloud account at all").isEmpty();
        assertThat(store.resolveClientId(92999)).as("no such cluster").isEmpty();
    }

    // ---------------------------------------------------------------- storage

    @Test
    @DisplayName("an hourly row costs under 220 heap bytes (measured over 10000 rows)")
    void hourlyBytesPerRow() {
        PgTestSupport.execute(url, "INSERT INTO client (id, name) VALUES (901, 'storage')");
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name) VALUES (9011, 'storage')");
        LocalDate june = LocalDate.of(2027, 6, 1);
        store.ensurePartition(Table.HOURLY, june);
        String partition = UsagePartitions.partitionName(Table.HOURLY, june);

        Instant start = UsagePartitions.lowerBound(Table.HOURLY, june);
        List<HourlyRow> rows = new ArrayList<>();
        for (long workload = 1; workload <= 20; workload++) {
            for (int hour = 0; hour < 500; hour++) {
                rows.add(new HourlyRow(9011, 901, workload, start.plus(Duration.ofHours(hour)),
                        180 * MI + hour, 210 * MI + hour, 240 * MI + hour, 256 * MI, 512 * MI,
                        12.345f + hour, 45.678f, 99.5f, 100.0, 500.0, 2.5, 24, 12, 2, 3));
            }
        }
        store.upsertHourly(rows);
        PgTestSupport.execute(url, "VACUUM ANALYZE " + partition);

        long count = PgTestSupport.queryLong(url, "SELECT count(*) FROM " + partition);
        long heap = PgTestSupport.queryLong(url, "SELECT pg_relation_size(to_regclass(?))", partition);
        long index = PgTestSupport.queryLong(url, "SELECT pg_indexes_size(to_regclass(?))", partition);
        double heapPerRow = (double) heap / count;
        double indexPerRow = (double) index / count;
        System.out.printf("[storage] hourly heap B/row=%.1f index B/row=%.1f (rows=%d heap=%d index=%d)%n",
                heapPerRow, indexPerRow, count, heap, index);

        assertThat(count).isEqualTo(10_000);
        assertThat(heapPerRow).isLessThan(220);
    }

    @Test
    @DisplayName("a sample row costs under 120 heap bytes with the 64-bit pod_hash (measured over 10000 rows)")
    void sampleBytesPerRow() {
        PgTestSupport.execute(url, "INSERT INTO client (id, name) VALUES (902, 'sample-storage')");
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name) VALUES (9021, 'sample-storage')");
        LocalDate day = LocalDate.of(2027, 6, 2);
        store.ensurePartition(Table.SAMPLE, day);
        String partition = UsagePartitions.partitionName(Table.SAMPLE, day);

        Instant start = UsagePartitions.lowerBound(Table.SAMPLE, day);
        List<SampleRow> rows = new ArrayList<>();
        for (long workload = 1; workload <= 20; workload++) {
            for (int snapshot = 0; snapshot < 250; snapshot++) {
                for (int pod = 0; pod < 2; pod++) {
                    rows.add(new SampleRow(9021, 902, workload, start.plus(Duration.ofMinutes(5L * snapshot)),
                            180 * MI + snapshot, 256 * MI, 512 * MI, 12.345 + snapshot, 100.0, 500.0,
                            Long.MAX_VALUE - pod, 2));
                }
            }
        }
        assertThat(store.insertSamples(rows)).isEqualTo(10_000);
        PgTestSupport.execute(url, "VACUUM ANALYZE " + partition);

        long count = PgTestSupport.queryLong(url, "SELECT count(*) FROM " + partition);
        long heap = PgTestSupport.queryLong(url, "SELECT pg_relation_size(to_regclass(?))", partition);
        long index = PgTestSupport.queryLong(url, "SELECT pg_indexes_size(to_regclass(?))", partition);
        double heapPerRow = (double) heap / count;
        double indexPerRow = (double) index / count;
        System.out.printf("[storage] sample heap B/row=%.1f index B/row=%.1f (rows=%d heap=%d index=%d)%n",
                heapPerRow, indexPerRow, count, heap, index);

        assertThat(count).isEqualTo(10_000);
        assertThat(PgTestSupport.queryString(url, "SELECT data_type FROM information_schema.columns"
                + " WHERE table_name = 'k8s_workload_usage_sample' AND column_name = 'pod_hash'")).isEqualTo("bigint");
        assertThat(store.readSamples(9021, start, start.plus(Duration.ofMinutes(1))))
                .filteredOn(row -> row.workloadId() == 1)
                .extracting(SampleRow::podHash).containsExactlyInAnyOrder(Long.MAX_VALUE, Long.MAX_VALUE - 1);
        assertThat(heapPerRow).isLessThan(120);
    }

    // ---------------------------------------------------------------- fixtures

    private static void account(long id, String awsAccountId, Long clientId) {
        PgTestSupport.execute(url, "INSERT INTO cloud_account (id, aws_account_id, client_id) VALUES (?, ?, ?)",
                id, awsAccountId, clientId);
    }

    private static void cluster(long id, Long cloudAccountId) {
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name, cloud_account_id)"
                + " VALUES (?, ?, ?)", id, "cluster-" + id, cloudAccountId);
    }

    private record Tenant(long clientId, long clusterId, Map<String, Long> ids) {
    }

    /**
     * A client and its cluster with two containers of one Deployment (the same names in every
     * tenant), one sample each, hourly rows in two monthly partitions and daily rows for two days.
     */
    private static Tenant seed(long clientId, long clusterId) {
        PgTestSupport.execute(url, "INSERT INTO client (id, name) VALUES (?, ?)", clientId, "client-" + clientId);
        PgTestSupport.execute(url, "INSERT INTO kubernetes_cluster_config (id, cluster_name) VALUES (?, ?)",
                clusterId, "cluster-" + clusterId);

        List<IdentityKey> keys = List.of(identity("shop", "Deployment", "api", "app"),
                identity("shop", "Deployment", "api", "sidecar"));
        Map<String, Long> ids = store.upsertIdentities(clientId, clusterId, keys, SEEN);
        assertThat(ids).hasSize(2);
        Tenant tenant = new Tenant(clientId, clusterId, ids);

        store.ensurePartition(Table.SAMPLE, OCT_DAY);
        List<SampleRow> samples = new ArrayList<>();
        List<HourlyRow> hourly = new ArrayList<>();
        List<DailyRow> daily = new ArrayList<>();
        for (long wid : ids.values()) {
            samples.add(new SampleRow(clusterId, clientId, wid, SEEN, 34 * MI, null, 512 * MI,
                    1.234567, 250.0, null, 42, 2));
            hourly.add(hourly(tenant, wid, OCT_HOUR));
            hourly.add(hourly(tenant, wid, NOV_HOUR));
            daily.add(daily(tenant, wid, OCT_DAY));
            daily.add(daily(tenant, wid, NOV_DAY));
        }
        assertThat(store.insertSamples(samples)).isEqualTo(2);
        store.upsertHourly(hourly);
        store.upsertDaily(daily);
        return tenant;
    }

    /** A per-tenant value in mem_p50_b, so a leaked row is recognisable. */
    private static long marker(Tenant tenant) {
        return tenant.clientId() * 1000;
    }

    private static HourlyRow hourly(Tenant t, long wid, Instant hour) {
        return new HourlyRow(t.clusterId(), t.clientId(), wid, hour, marker(t), marker(t) + 1, marker(t) + 2,
                null, 512 * MI, 1.234567, 40.25, 80.0, 250.0, null, 2.5, 24, 12, 2, 3);
    }

    private static DailyRow daily(Tenant t, long wid, LocalDate day) {
        return new DailyRow(t.clusterId(), t.clientId(), wid, day, marker(t), marker(t) + 1, marker(t) + 2,
                null, 512 * MI, 1.234567, 40.25, 80.0, 250.0, null, 2.5, 576, 288, 24, 2, 3);
    }

    private static Map<String, Long> rowsPerTable(String column, long id) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : FACT_TABLES) {
            counts.put(table, rows(table, column, id));
        }
        return counts;
    }

    private static long rows(String table, String column, long id) {
        return PgTestSupport.queryLong(url, "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", id);
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
