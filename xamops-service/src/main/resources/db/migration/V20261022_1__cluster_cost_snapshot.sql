-- =============================================================================
-- Durable point-in-time record of a registered cluster's estimated monthly cost
-- -- the prior-period baseline every cost-trend comparison reads back against.
--
-- WHY THIS TABLE EXISTS
--
-- The authoritative cost engine computes a cluster's monthly cost on demand, but
-- nothing durably remembers what it was last period, so no trend ("up / down /
-- flat vs. the prior snapshot") can be shown. One row here is one measurement of
-- cluster_monthly_cost at captured_at; FEAT-002's scheduled loop writes a row per
-- cluster and FEAT-003 reads the newest prior row back to compute the delta.
--
-- This row is additive and inert on its own. Writing it changes no behaviour and
-- references no existing column's data; nothing reads or writes it yet.
--
-- CANONICAL IDENTIFIER -- and the column this table deliberately does NOT have
--
-- cluster_config_id -> kubernetes_cluster_config(id).
--
-- There is NO cloud_account_id column, for the reason spelled out in full on
-- V20261013_1__vertical_workload_action.sql: kubernetes_cluster_config
-- .cloud_account_id holds the cloud_account primary key on modern rows and the
-- 12-digit AWS account number on legacy ones (audit finding B4), so a table
-- copying it would inherit a field whose meaning depends on the row's age.
-- kubernetes_cluster_config.id is a surrogate key, unambiguous by construction.
--
-- COLUMN TYPES MATCH HIBERNATE
--
-- UAT runs spring.jpa.hibernate.ddl-auto=update against the SHARED xamops_db_uat
-- (also used by billops and media-ops), so Hibernate may create this table before
-- Flyway sees it. Every column type below is the one Hibernate maps the entity
-- field to -- BIGINT for the ids, DOUBLE PRECISION for the primitive double
-- cluster_monthly_cost, and TIMESTAMPTZ for the Instant captured_at -- so
-- whichever of Flyway or Hibernate creates it first, the other has NOTHING TO
-- ALTER. This file touches no existing table or column.
--
-- GUARDS AND RE-RUNNABILITY
--
-- spring.jpa.hibernate.ddl-auto=update is set in every deployed profile and
-- spring.flyway.enabled=false in local, and prod runs flyway.out-of-order=true.
-- Everything below is therefore IF NOT EXISTS and the whole file is safe to run
-- twice (precedent: V20261013_1__vertical_workload_action.sql).
--
-- REVERSAL
--
--   DROP TABLE IF EXISTS cluster_cost_snapshot;
--
-- Dropping it loses only the cost-trend history. Nothing else references it.
-- =============================================================================

CREATE TABLE IF NOT EXISTS cluster_cost_snapshot (
    id                      BIGSERIAL           PRIMARY KEY,

    -- The registered cluster. ON DELETE CASCADE because a snapshot for a cluster
    -- XamOps no longer knows about is a dangling trend row, not a measurement.
    cluster_config_id       BIGINT              NOT NULL REFERENCES kubernetes_cluster_config(id) ON DELETE CASCADE,

    -- The estimated monthly cost of the cluster at the captured instant.
    cluster_monthly_cost    DOUBLE PRECISION    NOT NULL,

    -- When this snapshot was taken; the key the prior-period read-back orders by.
    captured_at             TIMESTAMPTZ         NOT NULL
);

-- The prior-period read-back access path: the newest snapshot for one cluster at
-- or before a cutoff. Ordered by captured_at, scoped by the cluster.
CREATE INDEX IF NOT EXISTS ix_cluster_cost_snapshot_cluster_captured
    ON cluster_cost_snapshot (cluster_config_id, captured_at);

COMMENT ON TABLE cluster_cost_snapshot IS
    'Durable point-in-time record of a cluster''s estimated monthly cost, the prior-period baseline for cost-trend comparisons. Scoped by kubernetes_cluster_config.id, never by the ambiguous cloud_account_id.';
COMMENT ON COLUMN cluster_cost_snapshot.cluster_config_id IS
    'kubernetes_cluster_config.id -- the unambiguous surrogate key, never cloud_account_id (audit B4).';
COMMENT ON COLUMN cluster_cost_snapshot.cluster_monthly_cost IS
    'The estimated monthly cost of the cluster at captured_at.';
COMMENT ON COLUMN cluster_cost_snapshot.captured_at IS
    'When this snapshot was taken; the key the newest-prior lookup orders by.';
