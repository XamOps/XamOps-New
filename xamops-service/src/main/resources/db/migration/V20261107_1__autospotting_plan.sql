-- AutoSpotting Phase B (B3) plan schema (plan-phase-11.md task 11.1; design-core.md §7.3, §7.5, §3.7,
-- §11). One additive, guarded, idempotent migration. Numbered above the spine (V20261030_2) and
-- above everything on live origin/main and in every sibling worktree at authoring time.
--
-- Rules (design-core.md §10, MigrationRulesTest, P01-R4): CREATE ... IF NOT EXISTS everywhere;
-- functions via CREATE OR REPLACE FUNCTION; no DROP, no DROP ... CASCADE, no DELETE/UPDATE without
-- WHERE; no FK to cloud_account or client (PO-21 retention, NFR-06); TIMESTAMPTZ only (NFR-07).
-- Ships dark: no cloud-mutating path is enabled by this phase. Running it twice changes nothing.
--
-- In prod the tables are built by ddl-auto=update from the JPA entities before Flyway runs, so the
-- CREATE TABLE below is a no-op there (same shape as the spine). The entity carries the columns,
-- primary key and plan_id semantics; the partial unique indexes and the additive column are named so
-- they land whether the table was made by ddl-auto or by this migration.

-- ── spot_plan: the plan record (design-core.md §7.3) ──────────────────────────
CREATE TABLE IF NOT EXISTS spot_plan (
  id                       UUID PRIMARY KEY,
  group_id                 UUID        NOT NULL,
  cloud_account_id         BIGINT      NOT NULL,          -- CloudAccount PK; no FK (PO-21 retention)
  kind                     VARCHAR(24) NOT NULL,          -- ENABLE | ADOPT | CONFIG_APPLY | PROFILE_CHANGE | REVERT | ACCEPT_BASELINE | MIG_POLICY_UPDATE
  status                   VARCHAR(16) NOT NULL,          -- OPEN | CONFIRMED | EXPIRED | SUPERSEDED | STALE
  from_state               VARCHAR(24) NOT NULL,          -- group state at plan creation; PLANNED returns here on expiry
  version                  INT         NOT NULL DEFAULT 1,
  schema_version           INT         NOT NULL,
  live_fingerprint         VARCHAR(64) NOT NULL,          -- FR-PLN-03: the provider's managed-field hash
  body                     JSONB       NOT NULL,          -- PlanBody (design-core.md §7.3)
  requires_full_spot_optin BOOLEAN     NOT NULL,
  warning_ids              JSONB       NOT NULL,          -- ack ids confirm must echo
  dry_run                  BOOLEAN     NOT NULL,
  idempotency_key          VARCHAR(64),
  request_sha256           VARCHAR(64),                   -- canonical request body (§3.7)
  created_by               VARCHAR(160) NOT NULL,
  created_at               TIMESTAMPTZ NOT NULL,
  expires_at               TIMESTAMPTZ NOT NULL,
  confirmed_by             VARCHAR(160),
  confirmed_at             TIMESTAMPTZ,
  confirm_acks             JSONB,
  confirm_reason           VARCHAR(500),
  batch_id                 UUID                           -- "Revert all" batch (§7.5)
);

-- At most one OPEN plan per group (design-core.md §7.3, §7.4).
CREATE UNIQUE INDEX IF NOT EXISTS ux_spot_plan_open
  ON spot_plan (group_id) WHERE status = 'OPEN';
-- Idempotency-Key uniqueness per account, when the key is present (design-core.md §3.7).
CREATE UNIQUE INDEX IF NOT EXISTS ux_spot_plan_idem
  ON spot_plan (cloud_account_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
-- Reconciler step-6 expiry sweep reads OPEN plans by expiry (design-core.md §7.4).
CREATE INDEX IF NOT EXISTS ix_spot_plan_open_expiry
  ON spot_plan (expires_at) WHERE status = 'OPEN';
-- Batch view ("Revert all") reads a batch's plans (design-core.md §7.5).
CREATE INDEX IF NOT EXISTS ix_spot_plan_batch
  ON spot_plan (batch_id) WHERE batch_id IS NOT NULL;

-- SQL column DEFAULTs the entity doesn't express; guarded so they land whether the table was made by
-- ddl-auto or by this migration.
ALTER TABLE spot_plan ALTER COLUMN version SET DEFAULT 1;

-- ── user_cloud_account_access.autospotting_write (design-core.md §11, PO-14) ──
-- The PO14_RECOMMENDED per-user write grant (additive, FR-CMP-05). The table predates Flyway
-- (database_migration_user_cloud_account_access.sql at the repo root), so the ADD COLUMN is guarded
-- on the table existing. Default false: no per-user write capability exists today (F-06), and the
-- column is inert while autospotting.authz.mode=DEFAULT.
DO $$
BEGIN
  IF to_regclass('user_cloud_account_access') IS NOT NULL THEN
    ALTER TABLE user_cloud_account_access
      ADD COLUMN IF NOT EXISTS autospotting_write BOOLEAN NOT NULL DEFAULT false;
  END IF;
END $$;
