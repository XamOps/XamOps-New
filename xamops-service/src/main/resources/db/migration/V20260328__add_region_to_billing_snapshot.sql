-- Add region column to billing_history_snapshot for per-region cost breakdown
ALTER TABLE billing_history_snapshot ADD COLUMN IF NOT EXISTS region VARCHAR(100);
