-- =============================================================================
-- Per-workload policy for the Java workload-autoscaling (vertical rightsizing)
-- engine.
--
-- WHY THIS TABLE EXISTS
--
-- Vertical rightsizing moved out of the Go agent because its decision layer was
-- measured wrong in ways that are not tuning problems: on a real EKS cluster, 7
-- of 7 rightsizing rows were unsafe (five recommended a 1Mi-14Mi memory request,
-- two recommended a 0m CPU request), and a deliberately correctly-sized control
-- workload was changed by -32.8%. The new engine's guards -- floors, asymmetric
-- hysteresis, cooldown -- are therefore parameters a tenant can tune rather than
-- constants buried in code, because the specific numbers are a judgement
-- calibrated on one cluster shape (t3.medium, 1930m / 3294Mi allocatable) while
-- the STRUCTURE (relative AND absolute, asymmetric by direction) is the claim.
--
-- ABSENCE OF A ROW MEANS DEFAULTS APPLY.
--
-- There is no backfill, and no row is written when a cluster is registered. A
-- cluster registered tomorrow behaves exactly like one registered last year, and
-- the default mode is OFF, so this migration cannot change the behaviour of any
-- existing installation. Same reasoning as security_notification_preference.
-- The column defaults below are duplicated as Java constants in
-- WorkloadPolicyDefaults so the no-row case matches a written row; a unit test
-- asserts the two agree.
--
-- CANONICAL IDENTIFIERS -- and the column this table deliberately does NOT have
--
-- cluster_config_id -> kubernetes_cluster_config(id), client_id -> client(id).
--
-- There is NO cloud_account_id column. kubernetes_cluster_config.cloud_account_id
-- holds two different kinds of identifier depending on when the row was written:
-- the cloud_account primary key on modern rows, the 12-digit AWS account number
-- on legacy ones (audit finding B4). A new table copying that column would
-- inherit a field whose meaning depends on the row's age, and tenant scoping
-- would be a guess. kubernetes_cluster_config.id is a surrogate key, unambiguous
-- by construction. client_id is denormalised on purpose -- it is resolvable by
-- joining through the cluster config, but that join would traverse the ambiguous
-- column, so the tenant is stored once at write time from the entitled
-- CloudAccount.getClient().getId() instead.
--
-- GUARDS AND RE-RUNNABILITY
--
-- spring.jpa.hibernate.ddl-auto=update is set in every deployed profile and
-- spring.flyway.enabled=false in local, so this table may already have been
-- created by Hibernate before Flyway sees it, and prod runs with
-- flyway.out-of-order=true. Everything below is therefore IF NOT EXISTS and the
-- whole file is safe to run twice (precedent: V20260907_1__ticket_email_ingest).
--
-- REVERSAL
--
--   DROP TABLE IF EXISTS vertical_workload_policy;
--
-- Dropping it loses only per-workload tuning: with no rows, every workload falls
-- back to the Java defaults, whose mode is OFF. Nothing else references it.
-- =============================================================================

CREATE TABLE IF NOT EXISTS vertical_workload_policy (
    id                          BIGSERIAL    PRIMARY KEY,

    -- Tenant, stored explicitly so scoping never traverses cloud_account_id.
    client_id                   BIGINT       NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    -- The registered cluster. ON DELETE CASCADE because a policy for a cluster
    -- XamOps no longer knows about is not a policy, it is a dangling row that
    -- would silently apply if the cluster were ever re-registered with the same id.
    cluster_config_id           BIGINT       NOT NULL REFERENCES kubernetes_cluster_config(id) ON DELETE CASCADE,

    -- Workload identity. namespace is NOT NULL and part of the unique key
    -- because the Go engine identified a workload by Deployment name alone, with
    -- no namespace, and applied one metric to every container of every match
    -- (audit bug 8). 253 is the Kubernetes DNS-subdomain limit.
    namespace                   VARCHAR(253) NOT NULL,
    workload_kind               VARCHAR(32)  NOT NULL,
    workload_name               VARCHAR(253) NOT NULL,

    -- OFF | RECOMMEND | AUTO. Defaults to OFF: a new automation feature must do
    -- nothing until somebody asks for it. Stored as text rather than an enum type
    -- so adding a mode later needs no migration.
    --
    -- AUTO is storable but inert while the engine has no executor and readiness
    -- is false; the UI's policy drawer needs the value to exist.
    mode                        VARCHAR(16)  NOT NULL DEFAULT 'OFF',

    -- Measurement. p95 over 24h with 25% headroom. The Go engine used CPU p80,
    -- which the harness showed understates a throttled workload, and
    -- max_over_time for memory, which overshoots.
    headroom_pct                INTEGER      NOT NULL DEFAULT 25,
    percentile                  INTEGER      NOT NULL DEFAULT 95,
    window_hours                INTEGER      NOT NULL DEFAULT 24,

    -- Floors: 25m CPU / 250Mi memory, which are upstream VPA's
    -- --pod-min-cpu-millicores / --pod-min-memory-mb defaults AND the values VPA
    -- was measured stopping at on five of the nine reference workloads. They
    -- clamp DECREASES ONLY: a workload already below the floor is never raised to
    -- reach it, because increases are driven by measured demand and never by the
    -- floor's existence. This is the guard the Go engine lacked when it emitted
    -- 1m / 1Mi.
    cpu_floor_millicores        INTEGER      NOT NULL DEFAULT 25,
    memory_floor_bytes          BIGINT       NOT NULL DEFAULT 262144000,   -- 250Mi

    -- Hysteresis: a change is recommended only when it clears BOTH a relative and
    -- an absolute threshold, asymmetric by direction.
    --
    -- Why AND: the control workload (80m) was cut by -32.8%, i.e. by 26m. A
    -- relative-only band cannot suppress that without also suppressing a genuine
    -- 30% cut on a 1000m container (300m, real money). An absolute-only band
    -- cannot suppress a 1Mi -> 4Mi thrash step, which is a 300% delta. The Go
    -- engine's applyThresholdPercent: 5 was relative-only and could not stop
    -- either.
    --
    -- Why asymmetric: an increase relieves measured pressure; a decrease
    -- speculatively reclaims capacity and is the direction that causes outages.
    -- A CPU shortfall throttles, a memory shortfall OOM-kills immediately, which
    -- is why the memory decrease band is 4x the increase band.
    min_increase_pct            INTEGER      NOT NULL DEFAULT 15,  -- > measured ~17% agent/kubectl-top jitter
    min_decrease_pct            INTEGER      NOT NULL DEFAULT 30,
    min_increase_cpu_millicores INTEGER      NOT NULL DEFAULT 25,  -- one floor-unit
    min_decrease_cpu_millicores INTEGER      NOT NULL DEFAULT 100, -- ~4x the measured 26m false positive; ~5% of a t3.medium
    min_increase_memory_bytes   BIGINT       NOT NULL DEFAULT 33554432,    -- 32Mi
    min_decrease_memory_bytes   BIGINT       NOT NULL DEFAULT 134217728,   -- 128Mi

    -- IN_PLACE | IN_PLACE_AND_PERSIST. IN_PLACE is the default and needs no
    -- opt-in: the resize subresource was measured changing CPU and memory with
    -- the same pod UID, same containerID and restartCount 0.
    -- IN_PLACE_AND_PERSIST also patches the Deployment template so new replicas
    -- inherit the size -- which restarts every pod, needs a separate opt-in
    -- ClusterRole, and is refused on a GitOps-managed workload.
    apply_method                VARCHAR(32)  NOT NULL DEFAULT 'IN_PLACE',

    -- Anti-thrash. The measured loop wrote 1Mi on one cycle and 4Mi on the next,
    -- 60 seconds apart. Hysteresis against the CURRENT request already makes that
    -- impossible; the cooldown is the second, independent guard.
    cooldown_minutes            INTEGER      NOT NULL DEFAULT 60,

    -- A limit is never raised beyond this multiple of its current value, so
    -- relieving throttling cannot hand a container an unbounded limit.
    max_limit_increase_factor   NUMERIC(5,2) NOT NULL DEFAULT 4.00,

    -- A single-replica workload has no spare capacity: any disruption is a full
    -- outage. One of the two guards the Go rightsizer did honour correctly.
    protect_single_replica      BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Fraction of the largest schedulable node a pod's total requests may occupy.
    -- A recommendation that cannot be scheduled is worse than none: the measured
    -- team-a/unschedulable-giant asked for 32 CPU / 64Gi on 1930m / 3294Mi nodes
    -- and sat Pending for 8 hours.
    node_capacity_fraction      NUMERIC(4,3) NOT NULL DEFAULT 0.900,

    -- Drives cooldown. NULL means nothing has ever been applied, which is the
    -- state of every workload in this release: no executor exists yet.
    last_applied_at             TIMESTAMPTZ,

    created_at                  TIMESTAMP,
    updated_at                  TIMESTAMP
);

-- One policy per workload. The cluster scope is cluster_config_id, never
-- cloud_account_id, so the key cannot mean two different things on two rows.
CREATE UNIQUE INDEX IF NOT EXISTS uq_vertical_workload_policy_workload
    ON vertical_workload_policy (cluster_config_id, namespace, workload_kind, workload_name);

-- The read endpoint's access path: every policy for one cluster, tenant-scoped.
CREATE INDEX IF NOT EXISTS ix_vertical_workload_policy_client_cluster
    ON vertical_workload_policy (client_id, cluster_config_id);

COMMENT ON TABLE vertical_workload_policy IS
    'Per-workload policy for the Java workload-autoscaling engine. Absence of a row means the Java defaults apply, whose mode is OFF. Scoped by kubernetes_cluster_config.id and client.id, never by the ambiguous cloud_account_id.';
COMMENT ON COLUMN vertical_workload_policy.cpu_floor_millicores IS
    'Clamps decreases only; a workload already below this is never raised to reach it.';
COMMENT ON COLUMN vertical_workload_policy.min_decrease_cpu_millicores IS
    'Absolute half of the decrease band. 100m is ~4x the measured 26m false positive on a correctly-sized control workload, and ~5% of a t3.medium node.';
COMMENT ON COLUMN vertical_workload_policy.apply_method IS
    'IN_PLACE uses the pods/resize subresource (no restart). IN_PLACE_AND_PERSIST also patches the Deployment template, which restarts every pod and needs a separate opt-in ClusterRole.';
