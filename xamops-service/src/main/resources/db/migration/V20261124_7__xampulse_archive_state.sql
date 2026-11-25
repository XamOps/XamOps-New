-- XamPulse: archive writer progress, failure counter and the bounded retention hold.
ALTER TABLE xp_account_config ADD COLUMN IF NOT EXISTS archive_watermark TIMESTAMPTZ;
ALTER TABLE xp_account_config ADD COLUMN IF NOT EXISTS archive_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE xp_account_config ADD COLUMN IF NOT EXISTS archive_last_error VARCHAR(200);
ALTER TABLE xp_account_config ADD COLUMN IF NOT EXISTS hold_extended_days INTEGER NOT NULL DEFAULT 0;
ALTER TABLE xp_account_config ADD COLUMN IF NOT EXISTS hold_extended_at TIMESTAMPTZ;
