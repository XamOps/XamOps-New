-- AutoSpotting Phase B (B6) GCP access mode and access type (plan-phase-29.md task 29.1; design-gcp.md §10.6).
-- One additive, guarded, idempotent migration, numbered above V20261110_2. Existing rows read gcp_auth_mode = 'KEY'
-- and gcp_access_type NULL ("not checked yet": a null type never blocks reads). IMPERSONATION (keyless) stays unused
-- until the Workload Identity pool is approved (PO-GCP-KEY), so no code path writes it yet.
-- Guarded by to_regclass because the empty-schema harness has no cloud_account, and ADD COLUMN IF NOT EXISTS because
-- ddl-auto adds the columns first in UAT and prod once the entity maps them. No DROP, no CASCADE, no UPDATE.
DO $$
BEGIN
  IF to_regclass('cloud_account') IS NOT NULL THEN
    ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS gcp_auth_mode VARCHAR(16) NOT NULL DEFAULT 'KEY';
    ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS gcp_access_type VARCHAR(16);
  END IF;
END $$;
