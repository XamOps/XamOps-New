-- =============================================================================
-- Durable history of Spot interruption notices and Spot node removals: one row per
-- interrupted node lifecycle, kept for 365 days.
--
-- WHY THIS TABLE EXISTS
--
-- The autoscaler's node-optimizer-report and the XamOps agent's k8s-resources
-- snapshot are latest-only caches in agent_data_cache: each push overwrites the
-- previous one, so an interruption notice that was visible for two minutes is gone
-- by the next cycle and nothing remembers it. SpotInterruptionTracker merges what
-- those pushes actually observed (NTH taints and legacy label, Karpenter
-- SpotInterrupted events, Spot nodes that vanished between two snapshots, XamOps
-- drain outcomes) into one row per (cluster, node name, node creation time), and
-- tracks how long the affected Deployments/StatefulSets took to be Ready again.
--
-- Rows only ever describe things a producer reported. Unknown is NULL, never an
-- empty value: affected_workloads NULL means "no snapshot listed the node", '[]'
-- means "the snapshot listed the node and it ran no workloads".
--
-- VERSION
--
-- First claimed as V20261024_20. V20261024_37 (usage rollups) reached main first,
-- and UAT runs Flyway with out-of-order=false, so this file takes the next free
-- version above it. The table, DDL and entity are unchanged by the renumber; IF NOT
-- EXISTS keeps it safe where ddl-auto=update already created the table.
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
-- UAT runs spring.jpa.hibernate.ddl-auto=update against the SHARED xamops_db_uat,
-- so Hibernate may create this table before Flyway sees it. Every column type
-- below is the one Hibernate maps the K8sSpotInterruptionEvent field to -- BIGINT
-- for the ids, TIMESTAMPTZ for every Instant, VARCHAR(n) equal to the entity's
-- @Column(length), BOOLEAN / INTEGER for the nullable wrappers, TEXT where the
-- entity says columnDefinition = "TEXT" -- and the unique constraint carries the
-- entity's name, so whichever of Flyway or Hibernate creates it first, the other
-- has NOTHING TO ALTER (SchemaUpdateNoAlterPgTest guards this). This file touches
-- no existing table or column.
--
-- GUARDS AND RE-RUNNABILITY
--
-- spring.jpa.hibernate.ddl-auto=update is set in every deployed profile and
-- spring.flyway.enabled=false in local, and prod runs flyway.out-of-order=true.
-- Everything below is therefore IF NOT EXISTS and the whole file is safe to run
-- twice (SpotInterruptionMigrationPgTest applies it twice).
--
-- RETENTION
--
-- 365 days by noticed_at (xamops.k8s.spot-interruptions.retention-days), purged
-- daily in batches of 5 000 from AgentDataCleanupScheduler.
--
-- REVERSAL
--
--   DROP TABLE IF EXISTS k8s_spot_interruption_event;
--
-- Dropping it loses only the interruption history. Nothing else references it.
-- =============================================================================
CREATE TABLE IF NOT EXISTS k8s_spot_interruption_event (
    id                           BIGSERIAL     PRIMARY KEY,
    -- The registered cluster. ON DELETE CASCADE because history for a cluster
    -- XamOps no longer knows about is a dangling row, not a record.
    cluster_config_id            BIGINT        NOT NULL REFERENCES kubernetes_cluster_config(id) ON DELETE CASCADE,
    node_name                    VARCHAR(253)  NOT NULL,
    node_created_at              TIMESTAMPTZ   NOT NULL,
    instance_id                  VARCHAR(32),
    instance_type                VARCHAR(64),
    zone                         VARCHAR(32),
    capacity_type                VARCHAR(16),
    nodegroup                    VARCHAR(255),
    notice_kind                  VARCHAR(16)   NOT NULL,   -- interruption | rebalance | unconfirmed
    sources                      VARCHAR(255)  NOT NULL,   -- sorted comma set of: nth-taint, nth-label-legacy, karpenter-event, node-removed
    noticed_at                   TIMESTAMPTZ   NOT NULL,   -- earliest notice or observation of any kind
    rebalance_noticed_at         TIMESTAMPTZ,
    interruption_noticed_at      TIMESTAMPTZ,
    node_last_seen_at            TIMESTAMPTZ,              -- last snapshot that still listed the node (t0); lower edge of an unconfirmed removal
    node_removed_at              TIMESTAMPTZ,              -- first snapshot without the node
    cordoned_before_removal      BOOLEAN,
    handling_mode                VARCHAR(16),              -- live | simulate | off | not-eligible | unknown
    handling_reason              VARCHAR(255),
    drain_status                 VARCHAR(16),              -- ActionRecord.status
    drain_reason                 TEXT,
    drain_dry_run                BOOLEAN,
    drain_finished_at            TIMESTAMPTZ,
    affected_workloads           TEXT,                     -- JSON array, see design B3.3
    pod_count                    INTEGER,
    workloads_snapshot_at        TIMESTAMPTZ,
    recovery_status              VARCHAR(20)   NOT NULL,   -- tracking | recovered | not-observed | node-not-removed | no-workloads | no-snapshot | not-measurable
    recovery_last_unrecovered_at TIMESTAMPTZ,
    recovered_observed_at        TIMESTAMPTZ,
    pending_first_seen_at        TIMESTAMPTZ,
    pending_last_seen_at         TIMESTAMPTZ,
    pending_max_pods             INTEGER,
    created_at                   TIMESTAMPTZ   NOT NULL,
    updated_at                   TIMESTAMPTZ   NOT NULL,
    -- One row per node lifecycle: EKS node names are IP-derived and reused, so the
    -- creation time tells two nodes with the same name apart.
    CONSTRAINT uq_k8s_spot_interruption_event_node UNIQUE (cluster_config_id, node_name, node_created_at)
);
-- The read API's access path: one cluster's events, newest first.
CREATE INDEX IF NOT EXISTS ix_k8s_spot_interruption_event_cluster_noticed
    ON k8s_spot_interruption_event (cluster_config_id, noticed_at DESC);
-- The retention purge's access path.
CREATE INDEX IF NOT EXISTS ix_k8s_spot_interruption_event_noticed
    ON k8s_spot_interruption_event (noticed_at);
COMMENT ON TABLE k8s_spot_interruption_event IS
    'One row per interrupted Spot node lifecycle (cluster, node name, node creation time): notices, removal, affected workloads, drain outcome and recovery. Scoped by kubernetes_cluster_config.id, never by the ambiguous cloud_account_id. Kept 365 days by noticed_at.';
COMMENT ON COLUMN k8s_spot_interruption_event.cluster_config_id IS
    'kubernetes_cluster_config.id -- the unambiguous surrogate key, never cloud_account_id (audit B4).';
COMMENT ON COLUMN k8s_spot_interruption_event.node_created_at IS
    'The node''s creation time (autoscaler CreationTimestamp or agent NodeDetail.age); with node_name it identifies one node lifecycle.';
COMMENT ON COLUMN k8s_spot_interruption_event.notice_kind IS
    'interruption (2-minute notice or Karpenter SpotInterrupted), rebalance (rebalance recommendation) or unconfirmed (a Spot node vanished with no recorded cause). Upgrades unconfirmed -> rebalance -> interruption, never down.';
COMMENT ON COLUMN k8s_spot_interruption_event.sources IS
    'Sorted comma-separated set of the producers that observed this row: nth-taint, nth-label-legacy, karpenter-event, node-removed.';
COMMENT ON COLUMN k8s_spot_interruption_event.noticed_at IS
    'Earliest notice or observation of any kind; the retention purge and the read API window use it.';
COMMENT ON COLUMN k8s_spot_interruption_event.node_last_seen_at IS
    'Last snapshot that still listed the node (t0). For an unconfirmed removal the real removal lies in (node_last_seen_at, node_removed_at], so it is the lower edge of the recovery bound.';
COMMENT ON COLUMN k8s_spot_interruption_event.node_removed_at IS
    'Server time of the first k8s-resources snapshot that no longer listed the node (t1).';
COMMENT ON COLUMN k8s_spot_interruption_event.cordoned_before_removal IS
    'spec.unschedulable of the node in the last snapshot that listed it; NULL when not observed.';
COMMENT ON COLUMN k8s_spot_interruption_event.handling_mode IS
    'XamOps spot-interruption handling at notice time: live, simulate, off, not-eligible (handling on but this notice is not drain input) or unknown (not reported).';
COMMENT ON COLUMN k8s_spot_interruption_event.drain_status IS
    'Status of the XamOps spot-interruption drain record for this node (ActionRecord.status), NULL when XamOps did not drain it.';
COMMENT ON COLUMN k8s_spot_interruption_event.affected_workloads IS
    'JSON array of {namespace, kind, name, podsOnNode} on the node at notice (pod owners normalized, ReplicaSet -> Deployment), capped at 50. NULL = unknown (no snapshot listed the node), [] = observed with no workloads.';
COMMENT ON COLUMN k8s_spot_interruption_event.pod_count IS
    'True number of qualifying pods on the node at notice, even when affected_workloads was capped.';
COMMENT ON COLUMN k8s_spot_interruption_event.workloads_snapshot_at IS
    'Server time of the k8s-resources snapshot affected_workloads was taken from.';
COMMENT ON COLUMN k8s_spot_interruption_event.recovery_status IS
    'tracking, recovered, not-observed (window expired, node gone), node-not-removed (window expired, node still listed), no-workloads, no-snapshot or not-measurable (no Deployment/StatefulSet affected). Derived by the writer; terminal values never change.';
COMMENT ON COLUMN k8s_spot_interruption_event.recovery_last_unrecovered_at IS
    'Last snapshot in which the affected workloads were not yet recovered; lower edge of the recovery range.';
COMMENT ON COLUMN k8s_spot_interruption_event.recovered_observed_at IS
    'First snapshot in which every affected Deployment/StatefulSet was Ready with no Pending pod and no pod left on the node; upper edge of the recovery range.';
COMMENT ON COLUMN k8s_spot_interruption_event.pending_max_pods IS
    'Largest number of Pending pods of the affected workloads seen in one snapshot while tracking.';
