package com.xammer.cloud.service.automation.workload.usage.store;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All SQL against the {@code k8s_workload_*} usage-rollup tables. JdbcTemplate only: none of these
 * tables is a JPA entity (see the header of {@code V20261024_37__k8s_workload_usage_rollups.sql}).
 *
 * <h2>Tenant scoping</h2>
 *
 * Every row carries {@code client_id} and {@code cluster_config_id}. The tenant read API
 * ({@link #findHourly}, {@link #findDaily}) rejects non-positive ids and filters on both, and joins
 * the identity on both, so a row of one tenant can never be returned for another even if a
 * {@code workload_id} were wrong. The internal job reads ({@link #readSamples}, {@link #readHourly})
 * are cluster-scoped; their results are written back under the same cluster and never returned to a
 * caller. {@code UsageRollupStoreSqlGuardTest} enforces this over the SQL constants below.
 *
 * <p>All SQL is held in {@code static final String} constants so that test can read it.
 */
@Repository
public class UsageRollupStore {

    // ---------------------------------------------------------------- SQL

    static final String INSERT_IDENTITY =
            "INSERT INTO k8s_workload_identity (client_id, cluster_config_id, namespace, workload_kind,"
                    + " workload_name, container, workload_key, first_seen_at, last_seen_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (cluster_config_id, workload_key) DO NOTHING";

    static final String SELECT_IDENTITY_IDS =
            "SELECT id, workload_key FROM k8s_workload_identity"
                    + " WHERE client_id = ? AND cluster_config_id = ? AND workload_key = ANY(?)";

    static final String UPDATE_LAST_SEEN =
            "UPDATE k8s_workload_identity SET last_seen_at = GREATEST(last_seen_at, ?)"
                    + " WHERE cluster_config_id = ? AND id = ANY(?)";

    static final String INSERT_SAMPLE =
            "INSERT INTO k8s_workload_usage_sample (cluster_config_id, client_id, workload_id, sampled_at,"
                    + " mem_b, mem_req_b, mem_lim_b, cpu_m, cpu_req_m, cpu_lim_m, pod_hash, running_replicas)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT DO NOTHING";

    static final String SELECT_SAMPLES =
            "SELECT cluster_config_id, client_id, workload_id, sampled_at, mem_b, mem_req_b, mem_lim_b,"
                    + " cpu_m, cpu_req_m, cpu_lim_m, pod_hash, running_replicas"
                    + " FROM k8s_workload_usage_sample"
                    + " WHERE cluster_config_id = ? AND sampled_at >= ? AND sampled_at < ?"
                    + " ORDER BY workload_id, sampled_at, pod_hash";

    static final String SELECT_CLUSTERS_WITH_SAMPLES =
            "SELECT DISTINCT cluster_config_id FROM k8s_workload_usage_sample"
                    + " WHERE sampled_at >= ? AND sampled_at < ? ORDER BY cluster_config_id";

    private static final String HOURLY_METRIC_COLUMNS =
            "mem_p50_b, mem_p95_b, mem_max_b, mem_req_b, mem_lim_b,"
                    + " cpu_p50_m, cpu_p95_m, cpu_max_m, cpu_req_m, cpu_lim_m,"
                    + " replicas_avg, sample_count, snapshot_count, replicas_min, replicas_max";

    private static final String METRIC_UPDATE_SET =
            " mem_p50_b = EXCLUDED.mem_p50_b, mem_p95_b = EXCLUDED.mem_p95_b, mem_max_b = EXCLUDED.mem_max_b,"
                    + " mem_req_b = EXCLUDED.mem_req_b, mem_lim_b = EXCLUDED.mem_lim_b,"
                    + " cpu_p50_m = EXCLUDED.cpu_p50_m, cpu_p95_m = EXCLUDED.cpu_p95_m,"
                    + " cpu_max_m = EXCLUDED.cpu_max_m, cpu_req_m = EXCLUDED.cpu_req_m,"
                    + " cpu_lim_m = EXCLUDED.cpu_lim_m, replicas_avg = EXCLUDED.replicas_avg,"
                    + " sample_count = EXCLUDED.sample_count, snapshot_count = EXCLUDED.snapshot_count,"
                    + " replicas_min = EXCLUDED.replicas_min, replicas_max = EXCLUDED.replicas_max,"
                    + " computed_at = now()";

    /** Recompute, never accumulate: a rerun over the same samples writes the same row. */
    static final String UPSERT_HOURLY =
            "INSERT INTO k8s_workload_usage_hourly (cluster_config_id, client_id, workload_id, hour, "
                    + HOURLY_METRIC_COLUMNS + ")"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (cluster_config_id, workload_id, hour) DO UPDATE SET" + METRIC_UPDATE_SET;

    static final String UPSERT_DAILY =
            "INSERT INTO k8s_workload_usage_daily (cluster_config_id, client_id, workload_id, day, "
                    + HOURLY_METRIC_COLUMNS + ", hours_covered)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (cluster_config_id, workload_id, day) DO UPDATE SET" + METRIC_UPDATE_SET
                    + ", hours_covered = EXCLUDED.hours_covered";

    static final String SELECT_HOURLY =
            "SELECT cluster_config_id, client_id, workload_id, hour, " + HOURLY_METRIC_COLUMNS
                    + " FROM k8s_workload_usage_hourly"
                    + " WHERE cluster_config_id = ? AND hour >= ? AND hour < ?"
                    + " ORDER BY workload_id, hour";

    static final String SELECT_CLUSTERS_WITH_HOURLY =
            "SELECT DISTINCT cluster_config_id FROM k8s_workload_usage_hourly"
                    + " WHERE hour >= ? AND hour < ? ORDER BY cluster_config_id";

    private static final String USAGE_ROW_COLUMNS =
            "i.id, i.namespace, i.workload_kind, i.workload_name, i.container,"
                    + " f.mem_p50_b, f.mem_p95_b, f.mem_max_b, f.mem_req_b, f.mem_lim_b,"
                    + " f.cpu_p50_m, f.cpu_p95_m, f.cpu_max_m, f.cpu_req_m, f.cpu_lim_m,"
                    + " f.replicas_avg, f.replicas_min, f.replicas_max, f.sample_count, f.snapshot_count";

    /** Tenant read API: both ids in the WHERE and in the identity join. */
    static final String FIND_HOURLY =
            "SELECT " + USAGE_ROW_COLUMNS + ", f.hour AS period_start, NULL::smallint AS hours_covered"
                    + " FROM k8s_workload_usage_hourly f"
                    + " JOIN k8s_workload_identity i ON i.id = f.workload_id"
                    + " AND i.client_id = f.client_id AND i.cluster_config_id = f.cluster_config_id"
                    + " WHERE f.client_id = ? AND f.cluster_config_id = ? AND f.hour >= ? AND f.hour < ?"
                    + " ORDER BY i.namespace, i.workload_kind, i.workload_name, i.container, f.hour";

    static final String FIND_DAILY =
            "SELECT " + USAGE_ROW_COLUMNS + ", (f.day::timestamp AT TIME ZONE 'UTC') AS period_start,"
                    + " f.hours_covered"
                    + " FROM k8s_workload_usage_daily f"
                    + " JOIN k8s_workload_identity i ON i.id = f.workload_id"
                    + " AND i.client_id = f.client_id AND i.cluster_config_id = f.cluster_config_id"
                    + " WHERE f.client_id = ? AND f.cluster_config_id = ? AND f.day >= ? AND f.day < ?"
                    + " ORDER BY i.namespace, i.workload_kind, i.workload_name, i.container, f.day";

    /** Partitions of a parent, resolved through the search_path. */
    static final String LIST_PARTITIONS =
            "SELECT c.relname FROM pg_inherits inh JOIN pg_class c ON c.oid = inh.inhrelid"
                    + " WHERE inh.inhparent = to_regclass(?) ORDER BY c.relname";

    /** {@code %s} is a name {@link UsagePartitions#parse} accepted, never caller text. */
    static final String DROP_PARTITION_TEMPLATE = "DROP TABLE IF EXISTS %s";

    /**
     * Hourly (cluster, workload, UTC day) groups of one hourly partition with no daily row: the
     * check that must return 0 before an aged hourly partition may be dropped. {@code %s} is a name
     * {@link UsagePartitions#parse} accepted as {@link UsagePartitions.Table#HOURLY}.
     */
    static final String COUNT_UNROLLED_DAYS_TEMPLATE =
            "SELECT count(*) FROM (SELECT DISTINCT h.cluster_config_id, h.workload_id,"
                    + " (h.hour AT TIME ZONE 'UTC')::date AS day FROM %s h) g"
                    + " WHERE NOT EXISTS (SELECT 1 FROM k8s_workload_usage_daily d"
                    + " WHERE d.cluster_config_id = g.cluster_config_id AND d.workload_id = g.workload_id"
                    + " AND d.day = g.day)";

    /**
     * Transaction-scoped advisory lock: released at commit/rollback, so it only means something
     * inside a transaction. Reads no table.
     */
    static final String TRY_ADVISORY_XACT_LOCK = "SELECT pg_try_advisory_xact_lock(?)";

    /** The owning tenant of a cluster: {@code cloud_account.id = kubernetes_cluster_config.cloud_account_id}. */
    static final String RESOLVE_CLIENT_ID =
            "SELECT DISTINCT ca.client_id FROM kubernetes_cluster_config kcc"
                    + " JOIN cloud_account ca ON ca.id = kcc.cloud_account_id"
                    + " WHERE kcc.id = ? AND ca.client_id IS NOT NULL";

    /** Legacy rows that stored the provider account number in {@code cloud_account_id} (audit B4). */
    static final String RESOLVE_CLIENT_ID_BY_AWS_ACCOUNT =
            "SELECT DISTINCT ca.client_id FROM kubernetes_cluster_config kcc"
                    + " JOIN cloud_account ca ON ca.aws_account_id = CAST(kcc.cloud_account_id AS VARCHAR)"
                    + " WHERE kcc.id = ? AND ca.client_id IS NOT NULL";

    // ---------------------------------------------------------------- limits

    /** The identity cache is cleared, not evicted, past this size: simple and bounded. */
    static final int MAX_IDENTITY_CACHE_ENTRIES = 200_000;
    private static final int BATCH_SIZE = 500;
    private static final String DUPLICATE_TABLE = "42P07";
    /** Concurrent CREATE TABLE IF NOT EXISTS can also collide on the pg_type unique index. */
    private static final String UNIQUE_VIOLATION = "23505";

    // ---------------------------------------------------------------- records

    /** The natural key of a workload-container; {@code workloadKey} is its sha256 hex. */
    public record IdentityKey(String namespace, String kind, String name, String container, String workloadKey) {
        public IdentityKey {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(workloadKey, "workloadKey");
        }
    }

    /** One raw point. A {@code null} request/limit means "not set", never 0. */
    public record SampleRow(long clusterConfigId, long clientId, long workloadId, Instant sampledAt,
                            long memB, Long memReqB, Long memLimB,
                            double cpuM, Double cpuReqM, Double cpuLimM,
                            int podHash, int runningReplicas) {
    }

    public record HourlyRow(long clusterConfigId, long clientId, long workloadId, Instant hour,
                            long memP50B, long memP95B, long memMaxB, Long memReqB, Long memLimB,
                            double cpuP50M, double cpuP95M, double cpuMaxM, Double cpuReqM, Double cpuLimM,
                            double replicasAvg, int sampleCount, int snapshotCount,
                            int replicasMin, int replicasMax) {
    }

    public record DailyRow(long clusterConfigId, long clientId, long workloadId, LocalDate day,
                           long memP50B, long memP95B, long memMaxB, Long memReqB, Long memLimB,
                           double cpuP50M, double cpuP95M, double cpuMaxM, Double cpuReqM, Double cpuLimM,
                           double replicasAvg, int sampleCount, int snapshotCount, int hoursCovered,
                           int replicasMin, int replicasMax) {
    }

    /**
     * A row of the tenant read API: names plus metrics. {@code start} is the UTC start of the hour
     * ({@link #findHourly}) or of the day ({@link #findDaily}); {@code hoursCovered} is null for
     * hourly rows.
     */
    public record UsageRow(long workloadId, String namespace, String workloadKind, String workloadName,
                           String container, Instant start,
                           long memP50B, long memP95B, long memMaxB, Long memReqB, Long memLimB,
                           double cpuP50M, double cpuP95M, double cpuMaxM, Double cpuReqM, Double cpuLimM,
                           double replicasAvg, int replicasMin, int replicasMax,
                           int sampleCount, int snapshotCount, Integer hoursCovered) {
    }

    // ---------------------------------------------------------------- state

    private final JdbcTemplate jdbc;
    /**
     * {@code clusterConfigId:clientId:workloadKey -> k8s_workload_identity.id}. The client is part of
     * the key so a cached id is only ever returned to the tenant whose SELECT produced it.
     */
    private final Map<String, Long> identityCache = new ConcurrentHashMap<>();
    private final Set<String> knownPartitions = ConcurrentHashMap.newKeySet();

    /** @param dataSource the {@code @Primary} application DataSource */
    public UsageRollupStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ---------------------------------------------------------------- tenant resolution

    /**
     * The client owning a cluster config, by its cloud account id, falling back to the legacy
     * provider-account-number match. Empty when unresolved or when the match is ambiguous (more than
     * one client): a tenant is never guessed.
     */
    public Optional<Long> resolveClientId(long clusterConfigId) {
        List<Long> clients = jdbc.queryForList(RESOLVE_CLIENT_ID, Long.class, clusterConfigId);
        if (clients.isEmpty()) {
            clients = jdbc.queryForList(RESOLVE_CLIENT_ID_BY_AWS_ACCOUNT, Long.class, clusterConfigId);
        }
        return clients.size() == 1 ? Optional.of(clients.get(0)) : Optional.empty();
    }

    // ---------------------------------------------------------------- identities

    /**
     * Ensures an identity row per key and returns {@code workloadKey -> id} for this tenant's
     * cluster. A key whose row belongs to another client is absent from the result.
     */
    public Map<String, Long> upsertIdentities(long clientId, long clusterConfigId,
                                              Collection<IdentityKey> keys, Instant seenAt) {
        requireIds(clientId, clusterConfigId);
        Objects.requireNonNull(seenAt, "seenAt");
        Map<String, Long> result = new HashMap<>();
        Map<String, IdentityKey> missing = new LinkedHashMap<>();
        for (IdentityKey key : keys) {
            Long cached = identityCache.get(cacheKey(clusterConfigId, clientId, key.workloadKey()));
            if (cached != null) {
                result.put(key.workloadKey(), cached);
            } else {
                missing.putIfAbsent(key.workloadKey(), key);
            }
        }
        if (missing.isEmpty()) {
            return result;
        }

        OffsetDateTime seen = ts(seenAt);
        jdbc.batchUpdate(INSERT_IDENTITY, new ArrayList<>(missing.values()), BATCH_SIZE, (ps, key) -> {
            ps.setLong(1, clientId);
            ps.setLong(2, clusterConfigId);
            ps.setString(3, key.namespace());
            ps.setString(4, key.kind());
            ps.setString(5, key.name());
            ps.setString(6, key.container());
            ps.setString(7, key.workloadKey());
            ps.setObject(8, seen);
            ps.setObject(9, seen);
        });

        String[] wanted = missing.keySet().toArray(String[]::new);
        Map<String, Long> found = new HashMap<>();
        jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(SELECT_IDENTITY_IDS);
            ps.setLong(1, clientId);
            ps.setLong(2, clusterConfigId);
            ps.setArray(3, connection.createArrayOf("bpchar", wanted));
            return ps;
        }, rs -> {
            found.put(rs.getString("workload_key").stripTrailing(), rs.getLong("id"));
        });

        if (identityCache.size() + found.size() > MAX_IDENTITY_CACHE_ENTRIES) {
            identityCache.clear();
        }
        found.forEach((workloadKey, id) -> identityCache.put(cacheKey(clusterConfigId, clientId, workloadKey), id));
        result.putAll(found);
        return result;
    }

    /** Drops this cluster's cached identity ids (offboarding, or a cluster re-bound to a client). */
    public void evictCluster(long clusterConfigId) {
        String prefix = clusterConfigId + ":";
        identityCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Raises {@code last_seen_at} (never lowers it) for the given workloads of one cluster. */
    public void touchLastSeen(long clusterConfigId, Collection<Long> workloadIds, Instant seenAt) {
        if (workloadIds.isEmpty()) {
            return;
        }
        Long[] ids = workloadIds.toArray(Long[]::new);
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(UPDATE_LAST_SEEN);
            ps.setObject(1, ts(seenAt));
            ps.setLong(2, clusterConfigId);
            ps.setArray(3, connection.createArrayOf("int8", ids));
            return ps;
        });
    }

    // ---------------------------------------------------------------- samples

    /**
     * Inserts raw points; a point already present (a replayed push) is skipped. The target sample
     * partition must exist ({@link #ensurePartition}). Returns how many rows were new.
     */
    public int insertSamples(List<SampleRow> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        int[][] counts = jdbc.batchUpdate(INSERT_SAMPLE, samples, BATCH_SIZE, (ps, s) -> {
            ps.setLong(1, s.clusterConfigId());
            ps.setLong(2, s.clientId());
            ps.setLong(3, s.workloadId());
            ps.setObject(4, ts(s.sampledAt()));
            ps.setLong(5, s.memB());
            setLong(ps, 6, s.memReqB());
            setLong(ps, 7, s.memLimB());
            ps.setFloat(8, (float) s.cpuM());
            setReal(ps, 9, s.cpuReqM());
            setReal(ps, 10, s.cpuLimM());
            ps.setInt(11, s.podHash());
            ps.setShort(12, toShort(s.runningReplicas()));
        });
        return inserted(counts);
    }

    /** Cluster-scoped internal read for the hourly job: samples in {@code [from, to)}. */
    public List<SampleRow> readSamples(long clusterConfigId, Instant from, Instant to) {
        return jdbc.query(SELECT_SAMPLES, SAMPLE_MAPPER, clusterConfigId, ts(from), ts(to));
    }

    /** Clusters with at least one sample in {@code [from, to)}. */
    public List<Long> clustersWithSamples(Instant from, Instant to) {
        return jdbc.queryForList(SELECT_CLUSTERS_WITH_SAMPLES, Long.class, ts(from), ts(to));
    }

    // ---------------------------------------------------------------- hourly / daily

    /** Writes each row, replacing any existing row for the same key (recompute, never add). */
    public void upsertHourly(List<HourlyRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(UPSERT_HOURLY, rows, BATCH_SIZE, (ps, r) -> {
            ps.setLong(1, r.clusterConfigId());
            ps.setLong(2, r.clientId());
            ps.setLong(3, r.workloadId());
            ps.setObject(4, ts(r.hour()));
            bindMetrics(ps, 5, r.memP50B(), r.memP95B(), r.memMaxB(), r.memReqB(), r.memLimB(),
                    r.cpuP50M(), r.cpuP95M(), r.cpuMaxM(), r.cpuReqM(), r.cpuLimM(),
                    r.replicasAvg(), r.sampleCount(), r.snapshotCount(), r.replicasMin(), r.replicasMax());
        });
    }

    public void upsertDaily(List<DailyRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(UPSERT_DAILY, rows, BATCH_SIZE, (ps, r) -> {
            ps.setLong(1, r.clusterConfigId());
            ps.setLong(2, r.clientId());
            ps.setLong(3, r.workloadId());
            ps.setObject(4, r.day());
            bindMetrics(ps, 5, r.memP50B(), r.memP95B(), r.memMaxB(), r.memReqB(), r.memLimB(),
                    r.cpuP50M(), r.cpuP95M(), r.cpuMaxM(), r.cpuReqM(), r.cpuLimM(),
                    r.replicasAvg(), r.sampleCount(), r.snapshotCount(), r.replicasMin(), r.replicasMax());
            ps.setShort(20, toShort(r.hoursCovered()));
        });
    }

    /** Cluster-scoped internal read for the daily job: hourly rows in {@code [from, to)}. */
    public List<HourlyRow> readHourly(long clusterConfigId, Instant from, Instant to) {
        return jdbc.query(SELECT_HOURLY, HOURLY_MAPPER, clusterConfigId, ts(from), ts(to));
    }

    /** Clusters with at least one hourly row in {@code [from, to)}. */
    public List<Long> clustersWithHourly(Instant from, Instant to) {
        return jdbc.queryForList(SELECT_CLUSTERS_WITH_HOURLY, Long.class, ts(from), ts(to));
    }

    // ---------------------------------------------------------------- tenant read API

    /**
     * One tenant's hourly rows for one of its clusters, hours in {@code [from, to)}, ordered by
     * namespace, kind, name, container, hour.
     *
     * @throws IllegalArgumentException when either id is not positive
     */
    public List<UsageRow> findHourly(long clientId, long clusterConfigId, Instant from, Instant to) {
        requireIds(clientId, clusterConfigId);
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return jdbc.query(FIND_HOURLY, USAGE_MAPPER, clientId, clusterConfigId, ts(from), ts(to));
    }

    /**
     * One tenant's daily rows for one of its clusters, UTC days in {@code [from, to)} ({@code to}
     * exclusive, like {@link #findHourly}).
     *
     * @throws IllegalArgumentException when either id is not positive
     */
    public List<UsageRow> findDaily(long clientId, long clusterConfigId, LocalDate from, LocalDate to) {
        requireIds(clientId, clusterConfigId);
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return jdbc.query(FIND_DAILY, USAGE_MAPPER, clientId, clusterConfigId, from, to);
    }

    // ---------------------------------------------------------------- partitions

    /**
     * Creates the partition of {@code table} containing {@code period} if it does not exist. Cached
     * per JVM; a concurrent creator winning the race is success.
     */
    public void ensurePartition(UsagePartitions.Table table, LocalDate period) {
        String name = UsagePartitions.partitionName(table, period);
        if (knownPartitions.contains(name)) {
            return;
        }
        try {
            jdbc.execute(UsagePartitions.createSql(table, period));
        } catch (DataAccessException e) {
            String state = sqlState(e);
            if (!DUPLICATE_TABLE.equals(state) && !UNIQUE_VIOLATION.equals(state)) {
                throw e;
            }
        }
        knownPartitions.add(name);
    }

    /**
     * {@code pg_try_advisory_xact_lock(key)}: true when this transaction now holds the lock, false
     * when another session does. Must be called inside a transaction (the lock lives until it ends).
     */
    public boolean tryAdvisoryXactLock(long key) {
        Boolean locked = jdbc.queryForObject(TRY_ADVISORY_XACT_LOCK, Boolean.class, key);
        return Boolean.TRUE.equals(locked);
    }

    /** Names of the partitions currently attached to {@code parent} (one of the three parents). */
    public List<String> listPartitions(String parent) {
        boolean known = false;
        for (UsagePartitions.Table table : UsagePartitions.Table.values()) {
            known |= table.parent().equals(parent);
        }
        if (!known) {
            throw new IllegalArgumentException("not a usage-rollup parent table: " + parent);
        }
        return jdbc.queryForList(LIST_PARTITIONS, String.class, parent);
    }

    /**
     * Drops one partition. Only names {@link UsagePartitions#parse} accepts are allowed, so a
     * partition this code did not name is never dropped.
     *
     * @throws IllegalArgumentException for any other name
     */
    public void dropPartition(String name) {
        UsagePartitions.Partition partition = UsagePartitions.parse(name)
                .orElseThrow(() -> new IllegalArgumentException("not a usage-rollup partition: " + name));
        jdbc.execute(String.format(DROP_PARTITION_TEMPLATE, partition.name()));
        knownPartitions.remove(partition.name());
    }

    /**
     * Hourly (cluster, workload, UTC day) groups in one hourly partition that have no daily row.
     *
     * @throws IllegalArgumentException unless {@code hourlyPartitionName} is an hourly partition name
     */
    public long countUnrolledDays(String hourlyPartitionName) {
        UsagePartitions.Partition partition = UsagePartitions.parse(hourlyPartitionName)
                .filter(p -> p.table() == UsagePartitions.Table.HOURLY)
                .orElseThrow(() -> new IllegalArgumentException(
                        "not an hourly usage-rollup partition: " + hourlyPartitionName));
        Long count = jdbc.queryForObject(String.format(COUNT_UNROLLED_DAYS_TEMPLATE, partition.name()), Long.class);
        return count == null ? 0L : count;
    }

    // ---------------------------------------------------------------- mapping

    private static final RowMapper<SampleRow> SAMPLE_MAPPER = (rs, n) -> new SampleRow(
            rs.getLong("cluster_config_id"),
            rs.getLong("client_id"),
            rs.getLong("workload_id"),
            instant(rs, "sampled_at"),
            rs.getLong("mem_b"),
            nullableLong(rs, "mem_req_b"),
            nullableLong(rs, "mem_lim_b"),
            real(rs, "cpu_m"),
            nullableReal(rs, "cpu_req_m"),
            nullableReal(rs, "cpu_lim_m"),
            rs.getInt("pod_hash"),
            rs.getInt("running_replicas"));

    private static final RowMapper<HourlyRow> HOURLY_MAPPER = (rs, n) -> new HourlyRow(
            rs.getLong("cluster_config_id"),
            rs.getLong("client_id"),
            rs.getLong("workload_id"),
            instant(rs, "hour"),
            rs.getLong("mem_p50_b"),
            rs.getLong("mem_p95_b"),
            rs.getLong("mem_max_b"),
            nullableLong(rs, "mem_req_b"),
            nullableLong(rs, "mem_lim_b"),
            real(rs, "cpu_p50_m"),
            real(rs, "cpu_p95_m"),
            real(rs, "cpu_max_m"),
            nullableReal(rs, "cpu_req_m"),
            nullableReal(rs, "cpu_lim_m"),
            real(rs, "replicas_avg"),
            rs.getInt("sample_count"),
            rs.getInt("snapshot_count"),
            rs.getInt("replicas_min"),
            rs.getInt("replicas_max"));

    private static final RowMapper<UsageRow> USAGE_MAPPER = (rs, n) -> {
        int covered = rs.getInt("hours_covered");
        Integer hoursCovered = rs.wasNull() ? null : covered;
        return new UsageRow(
                rs.getLong("id"),
                rs.getString("namespace"),
                rs.getString("workload_kind"),
                rs.getString("workload_name"),
                rs.getString("container"),
                instant(rs, "period_start"),
                rs.getLong("mem_p50_b"),
                rs.getLong("mem_p95_b"),
                rs.getLong("mem_max_b"),
                nullableLong(rs, "mem_req_b"),
                nullableLong(rs, "mem_lim_b"),
                real(rs, "cpu_p50_m"),
                real(rs, "cpu_p95_m"),
                real(rs, "cpu_max_m"),
                nullableReal(rs, "cpu_req_m"),
                nullableReal(rs, "cpu_lim_m"),
                real(rs, "replicas_avg"),
                rs.getInt("replicas_min"),
                rs.getInt("replicas_max"),
                rs.getInt("sample_count"),
                rs.getInt("snapshot_count"),
                hoursCovered);
    };

    // ---------------------------------------------------------------- helpers

    private static void requireIds(long clientId, long clusterConfigId) {
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId must be positive, was " + clientId);
        }
        if (clusterConfigId <= 0) {
            throw new IllegalArgumentException("clusterConfigId must be positive, was " + clusterConfigId);
        }
    }

    private static String cacheKey(long clusterConfigId, long clientId, String workloadKey) {
        return clusterConfigId + ":" + clientId + ":" + workloadKey;
    }

    private static OffsetDateTime ts(Instant instant) {
        return Objects.requireNonNull(instant, "instant").atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * A {@code REAL} as the double its shortest decimal text denotes ({@code 1.234567}, not
     * {@code 1.2345670461654663}), identically under the text and binary protocols.
     */
    private static double real(ResultSet rs, String column) throws SQLException {
        return Double.parseDouble(Float.toString(rs.getFloat(column)));
    }

    private static Double nullableReal(ResultSet rs, String column) throws SQLException {
        float value = rs.getFloat(column);
        return rs.wasNull() ? null : Double.parseDouble(Float.toString(value));
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setReal(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setFloat(index, value.floatValue());
        }
    }

    private static short toShort(int value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    /** Binds the 15 metric columns of {@link #HOURLY_METRIC_COLUMNS} starting at {@code i}. */
    private static void bindMetrics(PreparedStatement ps, int i, long memP50B, long memP95B, long memMaxB,
                                    Long memReqB, Long memLimB, double cpuP50M, double cpuP95M,
                                    double cpuMaxM, Double cpuReqM, Double cpuLimM, double replicasAvg,
                                    int sampleCount, int snapshotCount, int replicasMin, int replicasMax)
            throws SQLException {
        ps.setLong(i, memP50B);
        ps.setLong(i + 1, memP95B);
        ps.setLong(i + 2, memMaxB);
        setLong(ps, i + 3, memReqB);
        setLong(ps, i + 4, memLimB);
        ps.setFloat(i + 5, (float) cpuP50M);
        ps.setFloat(i + 6, (float) cpuP95M);
        ps.setFloat(i + 7, (float) cpuMaxM);
        setReal(ps, i + 8, cpuReqM);
        setReal(ps, i + 9, cpuLimM);
        ps.setFloat(i + 10, (float) replicasAvg);
        ps.setInt(i + 11, sampleCount);
        ps.setInt(i + 12, snapshotCount);
        ps.setShort(i + 13, toShort(replicasMin));
        ps.setShort(i + 14, toShort(replicasMax));
    }

    private static int inserted(int[][] counts) {
        int total = 0;
        for (int[] batch : counts) {
            for (int count : batch) {
                if (count > 0) {
                    total += count;
                }
            }
        }
        return total;
    }

    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
