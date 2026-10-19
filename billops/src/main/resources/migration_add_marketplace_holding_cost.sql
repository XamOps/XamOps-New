-- Migration: editable, optional Holding Cost markup on the AWS Marketplace charge.
--
-- A per-invoice percentage the admin applies ONLY to invoice.marketplace_charge. billops
-- has no Flyway and runs ddl-auto=update, so Hibernate adds this nullable column on its
-- own; this file exists so the change is applied deliberately and the CHECK below -- which
-- ddl-auto does not emit -- actually exists. Idempotent; safe to run before or after deploy.
--
-- Nullable, with no default: null and 0 both mean "no markup", so old invoices read back
-- null and render identically to today. Nullable is also what lets ddl-auto add the column
-- to the already-populated invoice table at all.

ALTER TABLE invoice
    ADD COLUMN IF NOT EXISTS marketplace_holding_cost_percent NUMERIC(5, 2) NULL;

-- A negative markup would reduce the Marketplace charge, which is not what this is for;
-- the sane upper bound keeps a fat-fingered "1500" from billing 15x. 100% is a generous
-- cap for a holding cost and matches the frontend input bound and the controller check.
--
-- Wrapped in a DO block with an existence check because `ADD CONSTRAINT ... IF NOT EXISTS`
-- is not supported on all PostgreSQL versions, and there is no in-repo precedent for a
-- bare standalone ADD CONSTRAINT. This makes the file re-runnable, matching its header.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_invoice_holding_cost_range'
    ) THEN
        ALTER TABLE invoice
            ADD CONSTRAINT ck_invoice_holding_cost_range
                CHECK (marketplace_holding_cost_percent IS NULL
                       OR (marketplace_holding_cost_percent >= 0
                           AND marketplace_holding_cost_percent <= 100));
    END IF;
END $$;
