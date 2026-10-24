-- =============================================================================
-- Per-tenant hourly (and daily) Kubernetes workload usage rollups: the durable
-- usage history the rightsizing engine and the cost views can read back instead
-- of asking a live Prometheus that may not exist, may have short retention, or
-- may be unreachable.
--
-- WHY THESE TABLES EXIST
--
-- Every agent push of the k8s-resources snapshot already carries metrics-server
-- usage (CPU, working-set memory) and the requests/limits of every running
-- container, but nothing keeps it: the snapshot cache is overwritten on the next
-- push. These four tables turn those ~12 points per container per hour into a
-- compact, tenant-scoped history:
--
--   k8s_workload_identity      one row per (cluster, namespace, owning workload
--                              kind+name, container); the surrogate workload_id
--                              the fact tables carry instead of repeating names.
--   k8s_workload_usage_sample  transient raw points, one per (pod, container,
--                              snapshot). Daily partitions, created on demand and
--                              dropped after the sample retention (48 h default).
--   k8s_workload_usage_hourly  p50/p95/max per workload-container per UTC hour,
--                              with sample_count / snapshot_count so a thin hour
--                              is visible as such. Monthly partitions, 90 days.
--   k8s_workload_usage_daily   the daily downsample of the hourly rows. Monthly
--                              partitions, 730 days.
--
-- The tables are additive and inert on their own: nothing writes them unless
-- xamops.k8s.usage-rollup.enabled is true (default false; true only in UAT).
--
-- CANONICAL IDENTIFIERS -- and the column these tables deliberately do NOT have
--
-- client_id         -> client(id)                    the tenant, on EVERY row
-- cluster_config_id -> kubernetes_cluster_config(id) the cluster, on EVERY row
--
-- Every read of tenant data filters on BOTH (UsageRollupStoreSqlGuardTest), and
-- both foreign keys cascade, so deleting a cluster or a client removes its usage
-- history in every table and every partition.
--
-- There is NO cloud_account_id column, for the reason spelled out in full on
-- V20261013_1__vertical_workload_action.sql: kubernetes_cluster_config
-- .cloud_account_id holds the cloud_account primary key on modern rows and the
-- 12-digit AWS account number on legacy ones (audit finding B4), so a table
-- copying it would inherit a field whose meaning depends on the row's age.
--
-- The fact tables have no foreign key to k8s_workload_identity: a cascading FK
-- would need a workload_id index over every fact partition. Identity rows and
-- fact rows are each cascaded directly from client / kubernetes_cluster_config.
--
-- NOT JPA ENTITIES -- and why
--
-- Every deployed profile runs spring.jpa.hibernate.ddl-auto=update, and the
-- Flyway bean has no ordering against the EntityManagerFactory. A partitioned
-- table that Hibernate created first would be a plain heap table, and IF NOT
-- EXISTS below would then silently keep it unpartitioned. So no entity maps any
-- k8s_workload_* table; all access is JdbcTemplate (UsageRollupStore), and
-- Hibernate never sees them.
--
-- PARTITIONS
--
-- Static first partitions are created here (hourly and daily, 2026-10 to
-- 2027-01). Later months and every daily sample partition are created by the
-- application (UsageRollupStore.ensurePartition, CREATE TABLE IF NOT EXISTS ...
-- PARTITION OF) and aged ones are dropped by the maintenance job. Bounds are
-- always explicit UTC. Partition names are derived only from dates.
--
-- GUARDS AND RE-RUNNABILITY
--
-- spring.jpa.hibernate.ddl-auto=update is set in every deployed profile and
-- spring.flyway.enabled=false in local, and prod runs flyway.out-of-order=true.
-- Everything below is therefore IF NOT EXISTS and the whole file is safe to run
-- twice (precedent: V20261022_1__cluster_cost_snapshot.sql). No DO blocks or
-- functions: plain statements only.
--
-- REVERSAL
--
--   DROP TABLE IF EXISTS k8s_workload_usage_sample;
--   DROP TABLE IF EXISTS k8s_workload_usage_hourly;
--   DROP TABLE IF EXISTS k8s_workload_usage_daily;
--   DROP TABLE IF EXISTS k8s_workload_identity;
--
-- Dropping a partitioned parent drops its partitions. Dropping these loses only
-- the usage history. Nothing else references them.
-- =============================================================================

CREATE TABLE IF NOT EXISTS k8s_workload_identity (
    id                 BIGSERIAL    PRIMARY KEY,
    client_id          BIGINT       NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    cluster_config_id  BIGINT       NOT NULL REFERENCES kubernetes_cluster_config(id) ON DELETE CASCADE,
    namespace          VARCHAR(253) NOT NULL,
    workload_kind      VARCHAR(32)  NOT NULL,
    workload_name      VARCHAR(253) NOT NULL,
    container          VARCHAR(253) NOT NULL,
    workload_key       CHAR(64)     NOT NULL,   -- sha256 hex of kind/namespace/name/container
    first_seen_at      TIMESTAMPTZ  NOT NULL,
    last_seen_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_k8s_workload_identity_key UNIQUE (cluster_config_id, workload_key)
);

CREATE INDEX IF NOT EXISTS ix_k8s_workload_identity_client_cluster
    ON k8s_workload_identity (client_id, cluster_config_id);

-- Transient raw points, one per (pod, container, snapshot); daily partitions,
-- created on demand, dropped after the sample retention.
CREATE TABLE IF NOT EXISTS k8s_workload_usage_sample (
    cluster_config_id  BIGINT      NOT NULL REFERENCES kubernetes_cluster_config(id) ON DELETE CASCADE,
    client_id          BIGINT      NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    workload_id        BIGINT      NOT NULL,   -- k8s_workload_identity.id (no FK, see header)
    sampled_at         TIMESTAMPTZ NOT NULL,
    mem_b              BIGINT      NOT NULL,
    mem_req_b          BIGINT,
    mem_lim_b          BIGINT,
    cpu_m              REAL        NOT NULL,
    cpu_req_m          REAL,
    cpu_lim_m          REAL,
    pod_hash           INTEGER     NOT NULL,   -- String.hashCode() of the pod name: dedupes replayed pushes
    running_replicas   SMALLINT    NOT NULL,   -- Running pods of this workload in this snapshot
    PRIMARY KEY (cluster_config_id, workload_id, sampled_at, pod_hash)
) PARTITION BY RANGE (sampled_at);

-- Column order puts the 8-byte columns first to avoid alignment padding.
CREATE TABLE IF NOT EXISTS k8s_workload_usage_hourly (
    cluster_config_id  BIGINT      NOT NULL REFERENCES kubernetes_cluster_config(id) ON DELETE CASCADE,
    client_id          BIGINT      NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    workload_id        BIGINT      NOT NULL,   -- k8s_workload_identity.id (no FK, see header)
    hour               TIMESTAMPTZ NOT NULL,   -- UTC, truncated to the hour
    mem_p50_b          BIGINT      NOT NULL,
    mem_p95_b          BIGINT      NOT NULL,
    mem_max_b          BIGINT      NOT NULL,
    mem_req_b          BIGINT,                 -- NULL = not set on the container (never a fabricated 0)
    mem_lim_b          BIGINT,
    computed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    cpu_p50_m          REAL        NOT NULL,   -- millicores
    cpu_p95_m          REAL        NOT NULL,
    cpu_max_m          REAL        NOT NULL,
    cpu_req_m          REAL,
    cpu_lim_m          REAL,
    replicas_avg       REAL        NOT NULL,
    sample_count       INTEGER     NOT NULL CHECK (sample_count > 0),
    snapshot_count     SMALLINT    NOT NULL CHECK (snapshot_count > 0),
    replicas_min       SMALLINT    NOT NULL,
    replicas_max       SMALLINT    NOT NULL,
    PRIMARY KEY (cluster_config_id, workload_id, hour)
) PARTITION BY RANGE (hour);

-- The hourly columns with day (UTC DATE) instead of hour, an INTEGER
-- snapshot_count (a day can exceed SMALLINT at short agent intervals) and
-- hours_covered.
CREATE TABLE IF NOT EXISTS k8s_workload_usage_daily (
    cluster_config_id  BIGINT      NOT NULL REFERENCES kubernetes_cluster_config(id) ON DELETE CASCADE,
    client_id          BIGINT      NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    workload_id        BIGINT      NOT NULL,   -- k8s_workload_identity.id (no FK, see header)
    mem_p50_b          BIGINT      NOT NULL,
    mem_p95_b          BIGINT      NOT NULL,
    mem_max_b          BIGINT      NOT NULL,
    mem_req_b          BIGINT,
    mem_lim_b          BIGINT,
    computed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    day                DATE        NOT NULL,   -- UTC day
    cpu_p50_m          REAL        NOT NULL,
    cpu_p95_m          REAL        NOT NULL,
    cpu_max_m          REAL        NOT NULL,
    cpu_req_m          REAL,
    cpu_lim_m          REAL,
    replicas_avg       REAL        NOT NULL,
    sample_count       INTEGER     NOT NULL CHECK (sample_count > 0),
    snapshot_count     INTEGER     NOT NULL CHECK (snapshot_count > 0),
    hours_covered      SMALLINT    NOT NULL CHECK (hours_covered BETWEEN 1 AND 24),
    replicas_min       SMALLINT    NOT NULL,
    replicas_max       SMALLINT    NOT NULL,
    PRIMARY KEY (cluster_config_id, workload_id, day)
) PARTITION BY RANGE (day);

-- Static first partitions; the application creates the rest. Bounds are UTC.
CREATE TABLE IF NOT EXISTS k8s_workload_usage_hourly_y2026m10 PARTITION OF k8s_workload_usage_hourly
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS k8s_workload_usage_hourly_y2026m11 PARTITION OF k8s_workload_usage_hourly
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS k8s_workload_usage_hourly_y2026m12 PARTITION OF k8s_workload_usage_hourly
    FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS k8s_workload_usage_hourly_y2027m01 PARTITION OF k8s_workload_usage_hourly
    FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS k8s_workload_usage_daily_y2026m10 PARTITION OF k8s_workload_usage_daily
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE IF NOT EXISTS k8s_workload_usage_daily_y2026m11 PARTITION OF k8s_workload_usage_daily
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE IF NOT EXISTS k8s_workload_usage_daily_y2026m12 PARTITION OF k8s_workload_usage_daily
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS k8s_workload_usage_daily_y2027m01 PARTITION OF k8s_workload_usage_daily
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

-- Sample partitions (k8s_workload_usage_sample_dYYYYMMDD) are created on demand only.

COMMENT ON TABLE k8s_workload_identity IS
    'One row per (cluster, namespace, owning workload kind+name, container). Scoped by client_id and kubernetes_cluster_config.id, never by the ambiguous cloud_account_id. Not a JPA entity.';
COMMENT ON COLUMN k8s_workload_identity.workload_key IS
    'sha256 hex of kind/namespace/name/container: the per-cluster natural key.';
COMMENT ON COLUMN k8s_workload_identity.workload_kind IS
    'The owning workload kind (Deployment, StatefulSet, DaemonSet, CronJob, Job, Pod, ...): ReplicaSet pods are attributed to their Deployment.';

COMMENT ON TABLE k8s_workload_usage_sample IS
    'Transient raw metrics-server points, one per (pod, container, agent snapshot). Daily partitions, dropped after the sample retention. Not a JPA entity.';
COMMENT ON COLUMN k8s_workload_usage_sample.mem_req_b IS
    'Memory request in bytes; NULL = not set on the container, never a fabricated 0.';
COMMENT ON COLUMN k8s_workload_usage_sample.cpu_req_m IS
    'CPU request in millicores; NULL = not set on the container, never a fabricated 0.';
COMMENT ON COLUMN k8s_workload_usage_sample.pod_hash IS
    'String.hashCode() of the pod name; part of the key so a replayed push dedupes.';

COMMENT ON TABLE k8s_workload_usage_hourly IS
    'Usage per workload-container per UTC hour, recomputed (never accumulated) from the raw samples. Monthly partitions. Not a JPA entity.';
COMMENT ON COLUMN k8s_workload_usage_hourly.sample_count IS
    'Raw (pod, container, snapshot) points behind this row: about 12 x replicas at the default 300 s agent interval. A small value means a thin hour.';
COMMENT ON COLUMN k8s_workload_usage_hourly.snapshot_count IS
    'Distinct agent snapshots in the hour (about 12 at 300 s). Lower after agent restarts, partial hours or a longer agent interval.';
COMMENT ON COLUMN k8s_workload_usage_hourly.mem_req_b IS
    'Memory request in bytes from the latest snapshot of the hour; NULL = not set on the container, never a fabricated 0.';
COMMENT ON COLUMN k8s_workload_usage_hourly.cpu_req_m IS
    'CPU request in millicores from the latest snapshot of the hour; NULL = not set on the container, never a fabricated 0.';
COMMENT ON COLUMN k8s_workload_usage_hourly.cpu_p95_m IS
    'p95 of the raw CPU points of the hour in millicores, linear interpolation (percentile_cont semantics).';

COMMENT ON TABLE k8s_workload_usage_daily IS
    'Daily downsample of k8s_workload_usage_hourly per workload-container per UTC day. Monthly partitions. Not a JPA entity.';
COMMENT ON COLUMN k8s_workload_usage_daily.mem_p50_b IS
    'Approximation: the linear-interpolated p50 of the hourly p50 values of the day, not a p50 of the raw samples.';
COMMENT ON COLUMN k8s_workload_usage_daily.mem_p95_b IS
    'Approximation: the linear-interpolated p95 of the hourly p95 values of the day, not a p95 of the raw samples.';
COMMENT ON COLUMN k8s_workload_usage_daily.cpu_p50_m IS
    'Approximation: the linear-interpolated p50 of the hourly p50 values of the day, not a p50 of the raw samples.';
COMMENT ON COLUMN k8s_workload_usage_daily.cpu_p95_m IS
    'Approximation: the linear-interpolated p95 of the hourly p95 values of the day, not a p95 of the raw samples.';
COMMENT ON COLUMN k8s_workload_usage_daily.mem_max_b IS
    'The maximum of the hourly maxima of the day: exact.';
COMMENT ON COLUMN k8s_workload_usage_daily.sample_count IS
    'Sum of the hourly sample_count values of the day.';
COMMENT ON COLUMN k8s_workload_usage_daily.snapshot_count IS
    'Sum of the hourly snapshot_count values of the day.';
COMMENT ON COLUMN k8s_workload_usage_daily.hours_covered IS
    'How many hourly rows (1..24) the day was computed from.';
COMMENT ON COLUMN k8s_workload_usage_daily.mem_req_b IS
    'Memory request in bytes from the latest hour of the day; NULL = not set on the container, never a fabricated 0.';
