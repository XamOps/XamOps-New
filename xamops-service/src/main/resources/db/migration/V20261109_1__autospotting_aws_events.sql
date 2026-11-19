-- AutoSpotting Phase B (B5) AWS event pipeline schema (plan-phase-22.md task 22.1; design-aws.md
-- §9.4, §9.5, §9.7, §13.1). One additive, guarded, idempotent migration, numbered above
-- V20261108_1 (the ledger migration) and above everything on origin/main and in every sibling
-- worktree at authoring time (V20261108_1 is the highest anywhere).
--
-- Rules (design-core.md §10, MigrationRulesTest): CREATE ... IF NOT EXISTS everywhere; no DROP, no
-- CASCADE, no DELETE or UPDATE without WHERE; no foreign key to cloud_account or spot_group (PO-21
-- retention); every instant is TIMESTAMPTZ. The ALTER on cloud_account is guarded by to_regclass
-- because the empty-schema harness has no cloud_account, and ADD COLUMN IF NOT EXISTS because
-- ddl-auto adds the column first in UAT and prod once the entity maps it. Running it twice changes
-- nothing.
--
-- None of these tables has a JPA entity: JdbcTemplate reads and writes them, so this file is the only
-- place they are created in every environment.
-- ── autospotting_region_coverage (§9.4): which regions of an account the event pipeline covers ──
CREATE TABLE IF NOT EXISTS autospotting_region_coverage (
  cloud_account_id BIGINT      NOT NULL,
  region           VARCHAR(32) NOT NULL,
  status           VARCHAR(16) NOT NULL,   -- COVERED | MISSING | SKIPPED | UNKNOWN
  rule_names       JSONB,
  checked_at       TIMESTAMPTZ NOT NULL,
  last_event_at    TIMESTAMPTZ,            -- informational; absence of events is not a failure
  PRIMARY KEY (cloud_account_id, region)
);
-- ── autospotting_event_inbox (§9.7): one row per EventBridge event id, the idempotency key ──
CREATE TABLE IF NOT EXISTS autospotting_event_inbox (
  provider_event_id VARCHAR(128) PRIMARY KEY,   -- EventBridge event id
  cloud_account_id  BIGINT,
  detail_type       VARCHAR(80)  NOT NULL,
  received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  outcome           VARCHAR(24)  NOT NULL       -- APPLIED | UNMANAGED | UNKNOWN_ACCOUNT | SENDER_MISMATCH
);
CREATE INDEX IF NOT EXISTS ix_aei_received ON autospotting_event_inbox (received_at);
-- ── spot_group_instance (§9.7): instance id to group, kept by each reconciler sweep ──
CREATE TABLE IF NOT EXISTS spot_group_instance (
  instance_id VARCHAR(32) PRIMARY KEY,
  group_id    UUID        NOT NULL,
  region      VARCHAR(32) NOT NULL,
  lifecycle   VARCHAR(16),
  seen_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_sgi_group ON spot_group_instance (group_id);
-- ── cloud_account.as_forwarder_role_id (§9.5): the forwarder role's unique id, for the sender check ──
DO $$
BEGIN
  IF to_regclass('cloud_account') IS NOT NULL THEN
    ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS as_forwarder_role_id VARCHAR(128);
  END IF;
END $$;
