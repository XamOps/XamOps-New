-- AutoSpotting Phase B (B1): mark every daily_spot_savings row legacy and non-billable.
--
-- Purpose (FR-LED-20, D-12)
--   daily_spot_savings holds the legacy savings figures. None of them is billing-grade, so every
--   row, existing or written later by old code, is legacy=true, billable=false, source='LEGACY'.
--   This is the one sanctioned data backfill of Phase B, and it is done by column defaults, not by
--   UPDATE: constant defaults mark existing rows in place without a table rewrite (PostgreSQL 11+).
--   Rows are never deleted or recomputed. ck_dss_legacy rejects any row that is not legacy or that
--   is billable.
--
-- Self-contained (design-core.md §10)
--   No migration creates daily_spot_savings; Hibernate ddl-auto did. The table is created here
--   when missing (an empty database, a new environment), matching the entity DailySpotSavings:
--   sequence daily_savings_id_seq and unique (account_id, date). The foreign key to cloud_account
--   is left to ddl-auto, as today. Where the table exists (prod) the CREATE statements are no-ops.
--
-- Idempotent
--   CREATE ... IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, and the constraint is added only when absent.
--   Running it again changes nothing.
--
-- Rollback
--   Redeploy the previous image. The columns stay; old code neither reads nor writes them, and the
--   defaults keep its inserts valid.

CREATE SEQUENCE IF NOT EXISTS daily_savings_id_seq INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS daily_spot_savings (      -- no-op where ddl-auto already created it (prod)
  id           BIGINT PRIMARY KEY,
  account_id   BIGINT NOT NULL,                      -- the FK to cloud_account is left to ddl-auto, as today
  date         DATE NOT NULL,
  amount_saved DOUBLE PRECISION NOT NULL,
  swap_count   INTEGER,
  CONSTRAINT uk_daily_spot_savings_account_date UNIQUE (account_id, date)
);
ALTER TABLE daily_spot_savings ADD COLUMN IF NOT EXISTS legacy   BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE daily_spot_savings ADD COLUMN IF NOT EXISTS billable BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE daily_spot_savings ADD COLUMN IF NOT EXISTS source   VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dss_legacy') THEN
    ALTER TABLE daily_spot_savings ADD CONSTRAINT ck_dss_legacy CHECK (legacy AND NOT billable) NOT VALID;
    ALTER TABLE daily_spot_savings VALIDATE CONSTRAINT ck_dss_legacy;
  END IF;
END $$;
