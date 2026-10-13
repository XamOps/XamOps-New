-- =============================================================================
-- Durable record of every apply the Java workload-autoscaling (vertical
-- rightsizing) engine carries, written from the SAME path a human's Apply takes.
--
-- WHY THIS TABLE EXISTS
--
-- Until now an apply left exactly one durable trace: vertical_workload_policy
-- .last_applied_at, a single timestamp that drives the cooldown. That is enough
-- to stop the engine thrashing, but it is not an audit trail: it cannot say what
-- changed, whether a human or the loop did it, whether it was a dry run, whether
-- any pod restarted, or what the outcome was. The measured Go rebalancer logged
-- "Rebalance completed: 2 nodes drained" under DRY_RUN=true -- an audit of events
-- that never happened -- so every one of those facts is a column here, recorded
-- from the executor's own ResizeProof rather than inferred.
--
-- This row is additive and inert on its own. Writing it changes no behaviour: an
-- apply still runs exactly as before, and this is the record left behind. It is
-- the prerequisite FEAT-002's scheduled loop needs to prove, per cluster, that it
-- is not re-applying what it just applied.
--
-- CANONICAL IDENTIFIERS -- and the column this table deliberately does NOT have
--
-- cluster_config_id -> kubernetes_cluster_config(id), client_id -> client(id).
--
-- There is NO cloud_account_id column, for the reason spelled out in full on
-- V20261010_1__vertical_workload_policy.sql: kubernetes_cluster_config
-- .cloud_account_id holds the cloud_account primary key on modern rows and the
-- 12-digit AWS account number on legacy ones (audit finding B4), so a table
-- copying it would inherit a field whose meaning depends on the row's age.
-- kubernetes_cluster_config.id is a surrogate key, unambiguous by construction;
-- client_id is denormalised at write time from the entitled
-- CloudAccount.getClient().getId() so tenant scoping never traverses the
-- ambiguous column.
--
-- COLUMN TYPES MATCH HIBERNATE
--
-- UAT runs spring.jpa.hibernate.ddl-auto=update against the SHARED xamops_db_uat
-- (also used by billops and media-ops), so Hibernate may create this table before
-- Flyway sees it. Every column type below is the one Hibernate maps the entity
-- field to -- VARCHAR for the text keys, TEXT for the before/after/detail blobs,
-- BOOLEAN for the flags, TIMESTAMPTZ for the Instant applied_at and a plain
-- TIMESTAMP for the LocalDateTime created_at -- so whichever of Flyway or
-- Hibernate creates it first, the other has NOTHING TO ALTER. This file touches
-- no existing table or column.
--
-- GUARDS AND RE-RUNNABILITY
--
-- spring.jpa.hibernate.ddl-auto=update is set in every deployed profile and
-- spring.flyway.enabled=false in local, and prod runs flyway.out-of-order=true.
-- Everything below is therefore IF NOT EXISTS and the whole file is safe to run
-- twice (precedent: V20261010_1__vertical_workload_policy.sql).
--
-- REVERSAL
--
--   DROP TABLE IF EXISTS vertical_workload_action;
--
-- Dropping it loses only the audit history of past applies. The cooldown still
-- works (it reads vertical_workload_policy.last_applied_at), and nothing else
-- references this table.
-- =============================================================================

CREATE TABLE IF NOT EXISTS vertical_workload_action (
    id                  BIGSERIAL        PRIMARY KEY,

    -- Tenant, stored explicitly so scoping never traverses cloud_account_id.
    client_id           BIGINT           NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    -- The registered cluster. ON DELETE CASCADE because an action for a cluster
    -- XamOps no longer knows about is a dangling audit row, not an action.
    cluster_config_id   BIGINT           NOT NULL REFERENCES kubernetes_cluster_config(id) ON DELETE CASCADE,

    -- Workload identity. 253 is the Kubernetes DNS-subdomain limit, 32 is the
    -- kind. Same widths as vertical_workload_policy.
    namespace           VARCHAR(253)     NOT NULL,
    workload_kind       VARCHAR(32)      NOT NULL,
    workload_name       VARCHAR(253)     NOT NULL,

    -- The command's idempotency key, so a row joins back to the executor's proof.
    correlation_id      VARCHAR(64),

    -- AUTO | MANUAL. Text, not an enum type, so a new trigger needs no migration.
    -- Defaults to MANUAL because the only caller that exists is the user-initiated
    -- apply; FEAT-002's loop is the one path that writes AUTO.
    trigger_source      VARCHAR(16)      NOT NULL DEFAULT 'MANUAL',

    -- Echoed, never inferred: a dry run that recorded as a real apply is exactly
    -- the measured Go failure this engine exists to prevent.
    dry_run             BOOLEAN          NOT NULL DEFAULT FALSE,

    -- The published five-state audit vocabulary:
    -- APPLIED | PARTIALLY_APPLIED | REFUSED | FAILED | EXPIRED. Text so the richer
    -- internal outcome can gain states without a migration here.
    outcome             VARCHAR(32)      NOT NULL,

    -- True only when every pod touched was PROVED not to have restarted.
    restart_free        BOOLEAN          NOT NULL DEFAULT FALSE,

    -- Submitted targets before, observed values after, and the proof's detail, so
    -- the record is legible without a join back to the command or the proof.
    before_json         TEXT,
    after_json          TEXT,
    detail              TEXT,

    -- When the change was applied; the audit timeline's instant and the key the
    -- newest-action lookup orders by.
    applied_at          TIMESTAMPTZ,

    created_at          TIMESTAMP
);

-- The lastAction lookup: the newest action for one workload. Ordered by
-- applied_at, scoped by the cluster + workload identity.
CREATE INDEX IF NOT EXISTS ix_vertical_workload_action_workload
    ON vertical_workload_action (cluster_config_id, namespace, workload_kind, workload_name);

-- The per-cluster, tenant-scoped access path for the read endpoint's single pass.
CREATE INDEX IF NOT EXISTS ix_vertical_workload_action_client_cluster
    ON vertical_workload_action (client_id, cluster_config_id);

COMMENT ON TABLE vertical_workload_action IS
    'Durable audit of every vertical-rightsizing apply, written from the same path a human Apply takes. Scoped by kubernetes_cluster_config.id and client.id, never by the ambiguous cloud_account_id.';
COMMENT ON COLUMN vertical_workload_action.trigger_source IS
    'AUTO | MANUAL. MANUAL for a user-initiated apply; AUTO only from the scheduled loop.';
COMMENT ON COLUMN vertical_workload_action.outcome IS
    'Published five-state audit outcome: APPLIED | PARTIALLY_APPLIED | REFUSED | FAILED | EXPIRED.';
COMMENT ON COLUMN vertical_workload_action.restart_free IS
    'True only when every pod touched was proved not to have restarted (same uid, same containerID, unchanged restartCount, still Ready).';
