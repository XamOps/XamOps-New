-- CloudK8s Manual mode (Phase B): propose, a human approves each action, then execute.
-- Additive and inert: every new column defaults to AUTONOMOUS / NULL and both tables start empty.
-- Every statement is IF NOT EXISTS because ddl-auto=update runs next to Flyway in the local, preprod,
-- uat and prod profiles, so Hibernate may already have created these objects from the entities.
-- Column types equal Hibernate's mapping of the entity fields (TEXT via columnDefinition, no @Lob).

ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_approval_mode          VARCHAR(16) NOT NULL DEFAULT 'AUTONOMOUS'; -- AUTONOMOUS|MANUAL
-- When Hibernate (ddl-auto=update) created the column first it emitted NOT NULL with no DB-level
-- default (the default lives on the entity field, so only entity INSERTs get it). In that case the
-- ADD COLUMN above is a skipped no-op, so re-assert the default and backfill any NULL/raw-insert rows.
ALTER TABLE kubernetes_cluster_config ALTER COLUMN automation_approval_mode SET DEFAULT 'AUTONOMOUS';
UPDATE kubernetes_cluster_config SET automation_approval_mode = 'AUTONOMOUS' WHERE automation_approval_mode IS NULL;
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS approval_mode_changed_at          TIMESTAMPTZ;
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS approval_mode_changed_by          BIGINT;       -- users.id, no FK
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS policy_served_approval_mode       VARCHAR(16);  -- what the automation agent was last served
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS policy_served_at                  TIMESTAMPTZ;  -- when, to the automation key only
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_agent_features         VARCHAR(255); -- comma list of validated tokens, e.g. manual-v1
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_agent_features_seen_at TIMESTAMPTZ;  -- last leader automation heartbeat that carried the list
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_agent_drain_timeout_seconds INTEGER; -- agent DRAIN_TIMEOUT_SECONDS, sizes the dispatch lease
-- These eight columns are mapped updatable=false on the entity and written only by the
-- @Modifying queries on KubernetesClusterConfigRepository, so a stale wholesale save() (the agent
-- heartbeat does one) can never write AUTONOMOUS back over a MANUAL cluster.

CREATE TABLE IF NOT EXISTS automation_proposal (
  id                 VARCHAR(36)  PRIMARY KEY,
  cluster_config_id  BIGINT       NOT NULL REFERENCES kubernetes_cluster_config(id) ON DELETE CASCADE,
  client_id          BIGINT,
  action_type        VARCHAR(24)  NOT NULL,   -- WORKLOAD_RESIZE|NODE_DRAIN|NODE_CONSOLIDATE|REBALANCE
  operation          VARCHAR(16),             -- DRAIN|CORDON_ONLY (NODE_DRAIN), EMPTY|DRAIN (NODE_CONSOLIDATE)
  source             VARCHAR(8)   NOT NULL,   -- BACKEND|AGENT
  origin_controller  VARCHAR(24)  NOT NULL,   -- vertical-engine|node-optimizer|evictor|consolidation|rebalancer
  target_kind        VARCHAR(8)   NOT NULL,   -- WORKLOAD|NODE
  target_ref         VARCHAR(512) NOT NULL,   -- "ns/Kind/name" or node name
  target_identity    VARCHAR(256),            -- node uid
  dedupe_key         VARCHAR(640) NOT NULL,
  params_json        TEXT         NOT NULL,
  evidence_json      TEXT         NOT NULL,
  verdict            VARCHAR(8)   NOT NULL,   -- ALLOWED|REFUSED at proposal time
  refusal_codes      VARCHAR(512),
  refusal_reason     TEXT,
  gate_json          TEXT,
  dry_run            BOOLEAN      NOT NULL,
  plan_fingerprint   VARCHAR(80)  NOT NULL,
  status             VARCHAR(12)  NOT NULL,   -- PENDING|APPROVED|DISPATCHED|EXECUTED|FAILED|REJECTED|EXPIRED|STALE|REFUSED
  status_reason      VARCHAR(255),
  refusal_stage      VARCHAR(10),             -- PROPOSAL|EXECUTION when status=REFUSED
  proposed_at        TIMESTAMPTZ  NOT NULL,
  first_seen_at      TIMESTAMPTZ  NOT NULL,
  last_seen_at       TIMESTAMPTZ  NOT NULL,
  last_progress_at   TIMESTAMPTZ,
  dwell_seconds      INTEGER,                 -- NODE_CONSOLIDATE only
  approvable_after   TIMESTAMPTZ,             -- backend-owned: first_seen_at + dwell_seconds
  expires_at         TIMESTAMPTZ  NOT NULL,
  decided_by         BIGINT,
  decided_by_name    VARCHAR(255),
  decided_at         TIMESTAMPTZ,
  decision_note      VARCHAR(500),
  approved_until     TIMESTAMPTZ,
  dispatched_at      TIMESTAMPTZ,
  dispatch_attempts  INTEGER      NOT NULL DEFAULT 0,
  finished_at        TIMESTAMPTZ,
  outcome_status     VARCHAR(16),
  outcome_verified   BOOLEAN,
  outcome_json       TEXT,
  correlation_id     VARCHAR(64),
  version            BIGINT       NOT NULL DEFAULT 0
);
-- Same ddl-auto race as above: if Hibernate built automation_proposal first, CREATE TABLE IF NOT
-- EXISTS is a no-op and the NOT NULL columns carry no DB-level default, so a raw INSERT that omits
-- them (the agent/backend always insert through the entity, but migration tests do not) would fail.
-- Re-assert the two defaulted NOT NULL columns; the spine migration does the same for its tables.
ALTER TABLE automation_proposal ALTER COLUMN dispatch_attempts SET DEFAULT 0;
ALTER TABLE automation_proposal ALTER COLUMN version           SET DEFAULT 0;
CREATE INDEX IF NOT EXISTS ix_ap_cluster_status ON automation_proposal(cluster_config_id, status);
-- Backstop only: application-level dedupe under the cluster-row lock is what guarantees one live row.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ap_live_dedupe ON automation_proposal(cluster_config_id, dedupe_key)
  WHERE status IN ('PENDING','APPROVED','DISPATCHED') OR (status='REFUSED' AND refusal_stage='PROPOSAL');

CREATE TABLE IF NOT EXISTS automation_proposal_event (
  id                BIGSERIAL    PRIMARY KEY,
  proposal_id       VARCHAR(36)  NOT NULL,
  cluster_config_id BIGINT       NOT NULL,
  at                TIMESTAMPTZ  NOT NULL,
  actor_type        VARCHAR(8)   NOT NULL,    -- USER|AGENT|SYSTEM
  actor_id          BIGINT,
  actor_name        VARCHAR(255),
  event             VARCHAR(16)  NOT NULL,
  detail            TEXT
);
CREATE INDEX IF NOT EXISTS ix_ape_proposal ON automation_proposal_event(proposal_id, at);
