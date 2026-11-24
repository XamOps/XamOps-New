-- AutoSpotting Phase B (B6) GCP template registry (plan-phase-27.md task 27.1; design-gcp.md §9.1).
-- One additive, guarded, idempotent migration, numbered above V20261110_1. The registry is the only
-- basis for deleting a template (FR-GCP-07): a template that is not a row here is never deleted.
-- Rules (MigrationRulesTest): CREATE ... IF NOT EXISTS; no DROP, no CASCADE, no DELETE/UPDATE without
-- WHERE; no foreign key to spot_group or cloud_account (PO-21 retention); every instant TIMESTAMPTZ.
-- No JPA entity: GcpTemplateRegistry reads and writes it with JdbcTemplate.
CREATE TABLE IF NOT EXISTS spot_gcp_template (
  id                 UUID PRIMARY KEY,
  group_id           UUID         NOT NULL,
  generation         INT          NOT NULL,
  role               VARCHAR(8)   NOT NULL,          -- SPOT | REBUILT
  name               VARCHAR(63)  NOT NULL,
  scope              VARCHAR(80)  NOT NULL,          -- 'global' or 'regions/<region>'
  self_link          VARCHAR(500) NOT NULL,
  gcp_id             VARCHAR(32),                    -- numeric id, filled once the template is read back
  source_self_link   VARCHAR(500) NOT NULL,
  source_gcp_id      VARCHAR(32),
  body_sha256        VARCHAR(64)  NOT NULL,
  insert_operation   VARCHAR(300),                   -- the insert Operation's self-link, polled by WAIT_TEMPLATE_OPERATION
  created_by_op      UUID         NOT NULL,
  status             VARCHAR(16)  NOT NULL,          -- CREATING | IN_USE | RETIRED | DELETING | DELETED | GONE
  retired_at         TIMESTAMPTZ,
  delete_after       TIMESTAMPTZ,
  created_at         TIMESTAMPTZ  NOT NULL,
  updated_at         TIMESTAMPTZ  NOT NULL
);
ALTER TABLE spot_gcp_template ADD COLUMN IF NOT EXISTS insert_operation VARCHAR(300);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sgt_name ON spot_gcp_template (scope, name, gcp_id);
-- DR-28: gcp_id is null while CREATING and NULLs are distinct, so one live row per name needs its own index.
CREATE UNIQUE INDEX IF NOT EXISTS ux_sgt_live_name ON spot_gcp_template (scope, name)
  WHERE status IN ('CREATING','IN_USE','RETIRED','DELETING');
CREATE INDEX IF NOT EXISTS ix_sgt_gc ON spot_gcp_template (status, delete_after);
