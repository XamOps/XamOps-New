-- Add usage_quantity for per-usage-type quantity data from Cost Explorer
ALTER TABLE billing_history_snapshot ADD COLUMN IF NOT EXISTS usage_quantity DOUBLE PRECISION;
