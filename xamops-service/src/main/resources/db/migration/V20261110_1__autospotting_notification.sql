-- AutoSpotting Phase B (B5) notification outbox (plan-phase-24.md task 24.1; design-ux-ops.md §7.1,
-- PO-24, PD-10). One additive, guarded, idempotent migration, numbered above V20261109_1 (the AWS
-- event pipeline migration, the highest on origin/main and in this branch) per g6-migrations.md.
--
-- Rules (design-core.md §10, MigrationRulesTest): CREATE ... IF NOT EXISTS everywhere; functions via
-- CREATE OR REPLACE; no DROP of a table or column, no CASCADE, no DELETE or UPDATE without WHERE; no
-- foreign key to cloud_account, client or spot_group (PO-21 retention: a notice outlives its account);
-- every instant is TIMESTAMPTZ. The ALTER on app_user is guarded by to_regclass because the
-- empty-schema harness has no app_user, and ADD COLUMN IF NOT EXISTS because ddl-auto adds the column
-- first in UAT and prod once the entity maps it. Running it twice changes nothing.
--
-- autospotting_notification has no JPA entity: JdbcTemplate reads and writes it, so this file is the
-- only place it is created in every environment.

-- ── autospotting_notification (ux §7.1): one row per notice, written in the transaction that causes it ──
CREATE TABLE IF NOT EXISTS autospotting_notification (
  id               BIGSERIAL PRIMARY KEY,
  event_type       VARCHAR(40)  NOT NULL,            -- ux §7.2
  audience         VARCHAR(12)  NOT NULL,            -- CUSTOMER | OPERATOR
  mandatory        BOOLEAN      NOT NULL,            -- true = ignores the customer's email opt-out (failures, PO-24)
  client_id        BIGINT       NOT NULL,
  cloud_account_id BIGINT       NOT NULL,
  group_id         UUID,
  operation_id     UUID,
  dedupe_key       VARCHAR(200) NOT NULL,
  payload          JSONB        NOT NULL,            -- redacted render inputs, no raw provider text
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  email_sent_at    TIMESTAMPTZ,
  attempts         INT          NOT NULL DEFAULT 0,
  last_error       VARCHAR(500),
  read_by          JSONB        NOT NULL DEFAULT '[]',
  next_attempt_at  TIMESTAMPTZ                       -- backoff after a failed send; null = due now
);
-- Added separately so a table made by an earlier run of this file without the column still gets it.
ALTER TABLE autospotting_notification ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ;
CREATE UNIQUE INDEX IF NOT EXISTS ux_asn_dedupe ON autospotting_notification (dedupe_key);
-- The notifier reads unsent rows oldest first.
CREATE INDEX IF NOT EXISTS ix_asn_pending ON autospotting_notification (id) WHERE email_sent_at IS NULL;
-- The in-app bell reads a client's notices newest first.
CREATE INDEX IF NOT EXISTS ix_asn_client ON autospotting_notification (client_id, id DESC);

-- ── append-only guard: the notice itself never changes; only its delivery state does ──
-- DELETE is refused. UPDATE may change email_sent_at (once, from null), attempts, last_error, read_by
-- and next_attempt_at; every other column is fixed, so a retried transition can't rewrite a notice.
CREATE OR REPLACE FUNCTION autospotting_notification_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'notification rows are append-only (%, %)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'P0001';
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.event_type IS DISTINCT FROM OLD.event_type
     OR NEW.audience IS DISTINCT FROM OLD.audience
     OR NEW.mandatory IS DISTINCT FROM OLD.mandatory
     OR NEW.client_id IS DISTINCT FROM OLD.client_id
     OR NEW.cloud_account_id IS DISTINCT FROM OLD.cloud_account_id
     OR NEW.group_id IS DISTINCT FROM OLD.group_id
     OR NEW.operation_id IS DISTINCT FROM OLD.operation_id
     OR NEW.dedupe_key IS DISTINCT FROM OLD.dedupe_key
     OR NEW.payload IS DISTINCT FROM OLD.payload
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'notification rows are append-only: the notice is fixed' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.email_sent_at IS NOT NULL AND NEW.email_sent_at IS DISTINCT FROM OLD.email_sent_at THEN
    RAISE EXCEPTION 'notification rows are append-only: a sent notice stays sent' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DO $$
BEGIN
  IF to_regclass('autospotting_notification') IS NOT NULL
     AND to_regprocedure('autospotting_notification_guard()') IS NOT NULL THEN
    EXECUTE 'DROP TRIGGER IF EXISTS trg_autospotting_notification_guard ON autospotting_notification';
    EXECUTE 'CREATE TRIGGER trg_autospotting_notification_guard BEFORE UPDATE OR DELETE ON autospotting_notification '
            'FOR EACH ROW EXECUTE FUNCTION autospotting_notification_guard()';
  END IF;
END $$;

-- ── app_user.autospotting_email_enabled (ux §7.3, PO-24): the one AutoSpotting email opt-out ──
-- Additive, default true. Read by NotificationRecipientResolver for non-mandatory notices only.
DO $$
BEGIN
  IF to_regclass('app_user') IS NOT NULL THEN
    ALTER TABLE app_user ADD COLUMN IF NOT EXISTS autospotting_email_enabled BOOLEAN NOT NULL DEFAULT TRUE;
  END IF;
END $$;
