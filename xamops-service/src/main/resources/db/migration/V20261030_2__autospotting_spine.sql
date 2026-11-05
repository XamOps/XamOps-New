-- AutoSpotting Phase B (B2) spine schema.
--
-- One additive migration with every spine table, index and trigger (plan-phase-03.md task 3.1;
-- design-core.md §2.4, §3.2, §4.5, §6.1, §8, §8.1). It ships dark: no v2 code reads these tables
-- yet. Rules (design-core.md §10): CREATE ... IF NOT EXISTS everywhere; functions via CREATE OR
-- REPLACE FUNCTION; triggers via DROP TRIGGER IF EXISTS then CREATE TRIGGER; no FK to cloud_account
-- or client (PO-21 retention, NFR-06); TIMESTAMPTZ only (NFR-07). Self-contained and idempotent:
-- running it twice changes nothing. No DROP, no DELETE/UPDATE without WHERE (MigrationRulesTest,
-- FR-ROL-04, AC-ROL-04).
--
-- Ordering rule (NO-08, plan task 3.1): trg_sgt_monotonic is the LAST statement of the file, after
-- every CREATE TABLE and any future data step. This phase has no SQL backfill: legacy rows and
-- their first transitions are written at run time by discovery (Phase 08) through
-- GroupLifecycleService, in id order, after the trigger exists. asg_spot_config is read only by that
-- code to seed desired_config, never altered here.

-- ── spot_group: the lifecycle row (design-core.md §2.4) ───────────────────────
CREATE TABLE IF NOT EXISTS spot_group (
  id                    UUID PRIMARY KEY,
  provider              VARCHAR(8)   NOT NULL,          -- AWS | GCP
  cloud_account_id      BIGINT       NOT NULL,          -- CloudAccount PK; no FK (PO-21 retention)
  client_id             BIGINT       NOT NULL,          -- denormalized for retention and queries
  group_key             VARCHAR(300) NOT NULL,          -- AWS: region/asgName; GCP: project/location/migName
  provider_stable_id    VARCHAR(400),                   -- ASG ARN; MIG numeric id
  state                 VARCHAR(24)  NOT NULL,
  pause_reason          VARCHAR(24),
  paused_from_state     VARCHAR(24),
  drift_status          VARCHAR(16)  NOT NULL DEFAULT 'NONE',  -- NONE | MINOR | MATERIAL
  drift_detail          JSONB,
  desired_config        JSONB,                          -- profile + parameters (versioned schema)
  desired_fingerprint   VARCHAR(64),                    -- sha256 of canonical managed fields
  config_mode           VARCHAR(12)  NOT NULL DEFAULT 'MANAGED',  -- MANAGED | FALLBACK
  fallback_fingerprint  VARCHAR(64),                    -- set with config_mode=FALLBACK
  accepted_fields       JSONB,                          -- owned fields taken by ACCEPT_BASELINE
  snapshot_id           UUID,                           -- spot_group_snapshot
  owned_artifacts       JSONB        NOT NULL DEFAULT '[]',   -- e.g. GCP template self-links, LT versions
  current_operation_id  UUID,
  last_error_code       VARCHAR(64),
  last_error_message    VARCHAR(1000),                  -- redacted (FR-SEC-06)
  lease_holder          VARCHAR(160),                   -- per-acquisition token <instanceId>:<component>:<uuid>
  lease_expires_at      TIMESTAMPTZ,
  lease_generation      BIGINT       NOT NULL DEFAULT 0,  -- fencing token
  version               BIGINT       NOT NULL DEFAULT 0,  -- bumped only by lifecycle writes
  state_changed_at      TIMESTAMPTZ  NOT NULL,
  last_reconciled_at    TIMESTAMPTZ,                    -- runtime
  health_state          JSONB,                          -- runtime: health signals, preemption cursors
  last_capacity         JSONB,                          -- runtime: {desired, inService, healthy, spot, onDemand, observedAt}
  reconcile_hint_at     TIMESTAMPTZ,                    -- runtime: AWS SQS listener hint (aws §9.6)
  created_by            VARCHAR(160) NOT NULL,
  created_at            TIMESTAMPTZ  NOT NULL,
  updated_at            TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_spot_group_identity
  ON spot_group (provider, cloud_account_id, group_key) WHERE state <> 'GONE';
CREATE UNIQUE INDEX IF NOT EXISTS ux_spot_group_stable          -- one manager per cloud resource, across CloudAccount rows
  ON spot_group (provider, provider_stable_id)
  WHERE provider_stable_id IS NOT NULL AND state NOT IN ('GONE','UNMANAGED','REVERTED');
CREATE INDEX IF NOT EXISTS ix_spot_group_reconcile
  ON spot_group (provider, state, last_reconciled_at);

-- ── spot_group_snapshot: immutable once written (design-core.md §2.4) ─────────
CREATE TABLE IF NOT EXISTS spot_group_snapshot (
  id              UUID PRIMARY KEY,
  group_id        UUID        NOT NULL,
  provider        VARCHAR(8)  NOT NULL,
  schema_version  INT         NOT NULL,
  exact           BOOLEAN     NOT NULL,                 -- false for reconstructed (FR-SPN-12)
  payload         JSONB       NOT NULL,                 -- provider-specific original config
  payload_sha256  VARCHAR(64) NOT NULL,
  captured_at     TIMESTAMPTZ NOT NULL,
  captured_by_op  UUID
);

-- ── spot_group_transition: append-only; ledger reads state intervals (§2.4) ───
CREATE TABLE IF NOT EXISTS spot_group_transition (
  id            BIGSERIAL PRIMARY KEY,
  group_id      UUID        NOT NULL,
  from_state    VARCHAR(24),
  to_state      VARCHAR(24) NOT NULL,
  reason_code   VARCHAR(48) NOT NULL,
  actor_type    VARCHAR(12) NOT NULL,                   -- USER | SYSTEM | OPERATOR
  actor_id      VARCHAR(160),
  operation_id  UUID,
  occurred_at   TIMESTAMPTZ NOT NULL,
  effective_at  TIMESTAMPTZ NOT NULL                    -- when the change took effect; the ledger reads this
);
CREATE INDEX IF NOT EXISTS ix_sgt_group_time ON spot_group_transition (group_id, occurred_at);
CREATE INDEX IF NOT EXISTS ix_sgt_group_effective ON spot_group_transition (group_id, id, effective_at);

-- ── autospotting_job_lease: one row per scheduled job (design-core.md §3.2) ───
CREATE TABLE IF NOT EXISTS autospotting_job_lease (
  name              VARCHAR(64) PRIMARY KEY,
  holder            VARCHAR(160),
  generation        BIGINT      NOT NULL DEFAULT 0,
  acquired_at       TIMESTAMPTZ,
  expires_at        TIMESTAMPTZ NOT NULL,
  last_run_key      VARCHAR(64),                        -- e.g. '2026-10-06' for a daily job (§3.5)
  last_completed_at TIMESTAMPTZ
);

-- ── spot_operation + step: write-ahead operation records (design-core.md §4.5) ─
CREATE TABLE IF NOT EXISTS spot_operation (
  id                  UUID PRIMARY KEY,
  group_id            UUID        NOT NULL,
  cloud_account_id    BIGINT      NOT NULL,
  plan_id             UUID UNIQUE,                      -- null only for system ops (fallback)
  kind                VARCHAR(24) NOT NULL,             -- ENABLE | ADOPT | CONFIG_APPLY | PROFILE_CHANGE | REVERT | FALLBACK_ENTER | FALLBACK_EXIT | ACCEPT_BASELINE | MIG_POLICY_UPDATE
  action_class        VARCHAR(8)  NOT NULL,             -- PROGRESS | SAFETY (§6.2)
  status              VARCHAR(16) NOT NULL,             -- QUEUED | RUNNING | WAITING | SUCCEEDED | FAILED | CANCELLED
  phase               VARCHAR(12) NOT NULL DEFAULT 'FORWARD',  -- FORWARD | COMPENSATING
  wait_reason         VARCHAR(24),                      -- CLOUD_PROGRESS | SWITCH | FOREIGN_CHANGE | BACKOFF | STUCK
  next_attempt_at     TIMESTAMPTZ,
  step_seq            INT         NOT NULL DEFAULT 0,
  first_mutation_seq  INT,                              -- first mutating step that recorded intent; null = cloud untouched
  progress            JSONB,                            -- {replaced, total, percent}
  prior_state         VARCHAR(24) NOT NULL,             -- stable state to return to
  prior_pause_reason  VARCHAR(24),
  compensation_target VARCHAR(16) NOT NULL,             -- SNAPSHOT | PRIOR_CONFIG | FALLBACK | NONE
  compensation_config JSONB,                            -- owned fields to restore for PRIOR_CONFIG and FALLBACK
  target_config       JSONB,                            -- the confirmed target; copied to desired_config (§2.4)
  cancel_requested    BOOLEAN     NOT NULL DEFAULT false,
  cancel_reason       VARCHAR(32),                      -- PREEMPTED_BY_REVERT | PREEMPTED_BY_FALLBACK | NOTHING_TO_REVERT
  compensate_requested BOOLEAN    NOT NULL DEFAULT false,  -- set by the reconciler for a switch-paused op
  compensate_reason   VARCHAR(48),                      -- e.g. SPOT_SHORTFALL_DURING_ROLLOUT
  preempted_by_op     UUID,
  idempotency_key     VARCHAR(64),
  request_sha256      VARCHAR(64),                      -- canonical request body (§3.7)
  requested_by        VARCHAR(160) NOT NULL,
  dry_run             BOOLEAN     NOT NULL DEFAULT false,
  error_code          VARCHAR(64),
  error_message       VARCHAR(1000),
  deadline_at         TIMESTAMPTZ NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL,
  updated_at          TIMESTAMPTZ NOT NULL,
  finished_at         TIMESTAMPTZ,
  version             BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_spot_op_running ON spot_operation (group_id)
  WHERE status IN ('RUNNING','WAITING') AND NOT dry_run;
CREATE UNIQUE INDEX IF NOT EXISTS ux_spot_op_idem ON spot_operation (cloud_account_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS spot_operation_step (
  operation_id      UUID        NOT NULL,
  seq               INT         NOT NULL,
  name              VARCHAR(48) NOT NULL,               -- e.g. PERSIST_SNAPSHOT, CREATE_SPOT_TEMPLATE
  status            VARCHAR(20) NOT NULL,               -- INTENT_RECORDED | DONE | IN_PROGRESS | FAILED | SKIPPED
  idempotency_token VARCHAR(64) NOT NULL,               -- deterministic: uuidv5(operation_id, seq)
  cloud_request_ids JSONB,                              -- AWS RequestId, GCP operation name/id
  input             JSONB,                              -- redacted
  output            JSONB,                              -- redacted
  lease_generation  BIGINT      NOT NULL,
  attempts          INT         NOT NULL DEFAULT 0,
  intent_at         TIMESTAMPTZ NOT NULL,
  completed_at      TIMESTAMPTZ,
  PRIMARY KEY (operation_id, seq)
);

-- ── autospotting_switch + allowlist: safety controls (design-core.md §6.1) ────
CREATE TABLE IF NOT EXISTS autospotting_switch (
  scope_key   VARCHAR(96) PRIMARY KEY,     -- 'GLOBAL', 'CLOUD:aws', 'CONVERSIONS:gcp', 'ACCOUNT:123', 'FREEZE', ...
  engaged     BOOLEAN      NOT NULL,        -- true = blocking, for every scope
  reason      VARCHAR(500) NOT NULL,
  changed_by  VARCHAR(160) NOT NULL,
  changed_at  TIMESTAMPTZ  NOT NULL,
  version     BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS autospotting_allowlist (   -- row present = account allowed while allowlist-only is on
  cloud_account_id BIGINT PRIMARY KEY,
  provider         VARCHAR(8)   NOT NULL,
  billing_basis    VARCHAR(12)  NOT NULL,      -- CONTRACT | NO_FEE
  contract_id      UUID,                       -- the client's contract in force when the row was added (CONTRACT only)
  reason           VARCHAR(500) NOT NULL,
  added_by         VARCHAR(160) NOT NULL,
  added_at         TIMESTAMPTZ  NOT NULL,
  CONSTRAINT ck_asal_basis CHECK (billing_basis IN ('CONTRACT','NO_FEE')
                                  AND (billing_basis = 'CONTRACT') = (contract_id IS NOT NULL))
);

-- ── autospotting_audit: append-only audit log (design-core.md §8) ─────────────
CREATE TABLE IF NOT EXISTS autospotting_audit (
  id                   BIGSERIAL PRIMARY KEY,
  occurred_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  event_type           VARCHAR(32) NOT NULL,  -- PLAN_CREATED | PLAN_CONFIRMED | ACK | STEP_INTENT | CLOUD_CALL | STATE_TRANSITION | ...
  actor_type           VARCHAR(12) NOT NULL,  -- USER | SYSTEM | OPERATOR
  actor_user_id        BIGINT,
  actor_role           VARCHAR(32),
  impersonator_user_id BIGINT,                -- Login-As (F-06, AC-SPN-10)
  request_id           VARCHAR(64),
  operation_id         UUID,
  plan_id              UUID,
  step_seq             INT,
  provider             VARCHAR(8),
  client_id            BIGINT,
  cloud_account_id     BIGINT,
  group_id             UUID,
  group_key            VARCHAR(300),
  action               VARCHAR(48) NOT NULL,
  outcome              VARCHAR(16) NOT NULL,  -- OK | FAILED | BLOCKED | WOULD_DO
  error_code           VARCHAR(64),
  cloud_request_ids    JSONB,                 -- AWS x-amzn-RequestId; GCP operation id + our requestId
  before_state         JSONB,                 -- redacted
  after_state          JSONB,                 -- redacted
  details              JSONB
);
CREATE INDEX IF NOT EXISTS ix_asa_group_time   ON autospotting_audit (group_id, occurred_at);
CREATE INDEX IF NOT EXISTS ix_asa_account_time ON autospotting_audit (cloud_account_id, occurred_at);
CREATE INDEX IF NOT EXISTS ix_asa_op           ON autospotting_audit (operation_id);

-- ── spot_group_event: analytics events, one schema for both clouds (§8.1) ─────
CREATE TABLE IF NOT EXISTS spot_group_event (
  id            BIGSERIAL PRIMARY KEY,
  provider      VARCHAR(8)   NOT NULL,          -- AWS | GCP
  group_id      UUID         NOT NULL,
  event_type    VARCHAR(32)  NOT NULL,          -- INTERRUPTION_WARNING | REBALANCE_RECOMMENDATION | PREEMPTION | ...
  event_key     VARCHAR(128) NOT NULL,
  instance_id   VARCHAR(100),                   -- AWS instance id, or GCP instance name
  instance_type VARCHAR(64),
  zone          VARCHAR(64),                    -- AWS AZ or GCP zone
  lifecycle     VARCHAR(16),                    -- spot | on-demand | null
  failure_class VARCHAR(24),                    -- SPOT_CAPACITY | SPOT_QUOTA | ON_DEMAND | OTHER
  occurred_at   TIMESTAMPTZ  NOT NULL,
  source        VARCHAR(8)   NOT NULL,          -- EVENT | POLL
  recorded_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sge_dedupe ON spot_group_event (group_id, event_type, event_key);
CREATE INDEX IF NOT EXISTS ix_sge_group_time ON spot_group_event (group_id, occurred_at);

-- ── constraints and defaults Hibernate ddl-auto doesn't know (prod shape) ─────
-- In prod the tables are built by ddl-auto=update from the JPA entities before Flyway runs, so the
-- CREATE TABLE statements above are no-ops there (design-core.md §10; same shape as
-- V20261009_1). The entities carry the columns, primary keys and plan_id UNIQUE, and the partial
-- unique indexes above are named so they are created regardless. The CHECK constraint and the SQL
-- column DEFAULTs are not expressed by the entities, so they are added here, each guarded, so they
-- land whether the table was made by ddl-auto or by this migration. Running it again changes nothing.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_asal_basis') THEN
    ALTER TABLE autospotting_allowlist ADD CONSTRAINT ck_asal_basis
      CHECK (billing_basis IN ('CONTRACT','NO_FEE')
             AND (billing_basis = 'CONTRACT') = (contract_id IS NOT NULL)) NOT VALID;
    ALTER TABLE autospotting_allowlist VALIDATE CONSTRAINT ck_asal_basis;
  END IF;
END $$;

ALTER TABLE spot_group  ALTER COLUMN drift_status    SET DEFAULT 'NONE';
ALTER TABLE spot_group  ALTER COLUMN config_mode     SET DEFAULT 'MANAGED';
ALTER TABLE spot_group  ALTER COLUMN owned_artifacts SET DEFAULT '[]';
ALTER TABLE spot_group  ALTER COLUMN lease_generation SET DEFAULT 0;
ALTER TABLE spot_group  ALTER COLUMN version         SET DEFAULT 0;
ALTER TABLE spot_operation ALTER COLUMN phase               SET DEFAULT 'FORWARD';
ALTER TABLE spot_operation ALTER COLUMN step_seq            SET DEFAULT 0;
ALTER TABLE spot_operation ALTER COLUMN cancel_requested    SET DEFAULT false;
ALTER TABLE spot_operation ALTER COLUMN compensate_requested SET DEFAULT false;
ALTER TABLE spot_operation ALTER COLUMN dry_run             SET DEFAULT false;
ALTER TABLE spot_operation ALTER COLUMN version             SET DEFAULT 0;
ALTER TABLE spot_operation_step ALTER COLUMN attempts       SET DEFAULT 0;
ALTER TABLE autospotting_job_lease ALTER COLUMN generation  SET DEFAULT 0;
ALTER TABLE autospotting_switch ALTER COLUMN version        SET DEFAULT 0;
ALTER TABLE autospotting_audit ALTER COLUMN occurred_at     SET DEFAULT now();
ALTER TABLE spot_group_event ALTER COLUMN recorded_at       SET DEFAULT now();

-- ── append-only triggers (design-core.md §8, §2.4) ────────────────────────────
-- autospotting_audit, spot_group_snapshot and spot_group_transition are append-only: an UPDATE or
-- DELETE raises P0001. The function is reused by all three BEFORE UPDATE OR DELETE triggers.
CREATE OR REPLACE FUNCTION autospotting_reject_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'append-only table %, % not allowed', TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_asa_append_only ON autospotting_audit;
CREATE TRIGGER trg_asa_append_only
  BEFORE UPDATE OR DELETE ON autospotting_audit
  FOR EACH ROW EXECUTE FUNCTION autospotting_reject_mutation();

DROP TRIGGER IF EXISTS trg_sgs_append_only ON spot_group_snapshot;
CREATE TRIGGER trg_sgs_append_only
  BEFORE UPDATE OR DELETE ON spot_group_snapshot
  FOR EACH ROW EXECUTE FUNCTION autospotting_reject_mutation();

DROP TRIGGER IF EXISTS trg_sgt_append_only ON spot_group_transition;
CREATE TRIGGER trg_sgt_append_only
  BEFORE UPDATE OR DELETE ON spot_group_transition
  FOR EACH ROW EXECUTE FUNCTION autospotting_reject_mutation();

-- ── trg_sgt_monotonic: LAST statement (NO-08, DR-01, design-core.md §2.4) ─────
-- Transition effective_at is non-decreasing in id order per group. A BEFORE INSERT that lands an
-- earlier effective_at than the group's latest row raises P0001; the writer clamps and retries.
-- This is the last statement of the file: every table exists and any future data step runs before
-- the guard is armed.
CREATE OR REPLACE FUNCTION autospotting_sgt_monotonic() RETURNS trigger AS $$
DECLARE
  latest_effective_at TIMESTAMPTZ;
BEGIN
  SELECT t.effective_at INTO latest_effective_at
    FROM spot_group_transition t
   WHERE t.group_id = NEW.group_id
   ORDER BY t.id DESC
   LIMIT 1;
  IF latest_effective_at IS NOT NULL AND NEW.effective_at < latest_effective_at THEN
    RAISE EXCEPTION 'transition effective_at not monotonic' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sgt_monotonic ON spot_group_transition;
CREATE TRIGGER trg_sgt_monotonic
  BEFORE INSERT ON spot_group_transition
  FOR EACH ROW EXECUTE FUNCTION autospotting_sgt_monotonic();
