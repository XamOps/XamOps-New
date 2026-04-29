-- V20260429__add_sre_verdict.sql
ALTER TABLE sre_investigation
  ADD COLUMN IF NOT EXISTS verdict VARCHAR(20)
    CHECK (verdict IN ('RESOLVED','FALSE_POSITIVE','WRONG_ROOT_CAUSE')),
  ADD COLUMN IF NOT EXISTS verdict_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS verdict_note TEXT;

-- Update resource_type constraint to include GENERIC
ALTER TABLE sre_investigation
  DROP CONSTRAINT IF EXISTS sre_investigation_resource_type_check;
ALTER TABLE sre_investigation
  ADD CONSTRAINT sre_investigation_resource_type_check
    CHECK (resource_type IN ('K8S_POD', 'EC2', 'RDS', 'AZURE_VM', 'GCP_VM', 'GENERIC'));
