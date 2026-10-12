-- Migration: manual private pricing on invoices
--
-- Lets a BillOps admin bill part of an invoice at a rate they set -- quantity x rate --
-- instead of at the provider's cost, and optionally shield those charges from the
-- invoice's overall discount. The automatic, per-client CloudFront equivalent already
-- exists in cloudfront_private_rate; this is the manual, per-invoice form.
--
-- billops has no Flyway and runs ddl-auto=update, so Hibernate will create the new table
-- and add the new nullable columns on its own. This file is here so the change can be
-- applied deliberately and reviewed, and so the indexes and the CHECK below -- none of
-- which ddl-auto produces -- actually exist. Safe to run before or after deploying.
--
-- Run once against PostgreSQL.

-- ── Line-item columns ───────────────────────────────────────────────────────────
--
-- Private pricing overwrites invoice_line_item.cost in place, so that invoice totals,
-- the invoice PDF and the detail bill all follow without knowing a rate was involved.
-- original_cost is what makes that reversible: removing a rate restores the provider's
-- figure exactly, and until then it is what the margin is measured against.
--
-- Both are NULL on every line billed at provider cost, which is almost all of them.
-- Nullable is also what lets ddl-auto=update add them to a populated table at all.

ALTER TABLE invoice_line_item
    ADD COLUMN IF NOT EXISTS original_cost NUMERIC(19, 4) NULL;

ALTER TABLE invoice_line_item
    ADD COLUMN IF NOT EXISTS private_unit_rate NUMERIC(19, 10) NULL;

-- Scale 10, matching cloudfront_private_rate.private_rate: real CDN and per-request
-- rates run to several significant figures below a cent, and rounding the rate before
-- multiplying it by a quantity in the millions moves the charge by real money.

-- The unit the rate was quoted in, carried on the line because the detail bill prints one
-- row per line and must show the rate it actually billed at. Printing the rate against
-- the line's own unit instead would be wrong by the quoted divisor -- "$0.01 per 10000
-- Requests" shown as "$0.01 per Requests" overstates it by four orders of magnitude.
ALTER TABLE invoice_line_item
    ADD COLUMN IF NOT EXISTS private_rate_unit VARCHAR(255) NULL;

-- ── Invoice totals ──────────────────────────────────────────────────────────────
--
-- Derived from the line items, but stored next to the other totals by the same method
-- that derives those, so a finalized invoice keeps the figures it was finalized with.

ALTER TABLE invoice
    ADD COLUMN IF NOT EXISTS private_priced_total NUMERIC(19, 4) NULL;

ALTER TABLE invoice
    ADD COLUMN IF NOT EXISTS discount_shielded_total NUMERIC(19, 4) NULL;

-- ── The applied rates ───────────────────────────────────────────────────────────
--
-- One row per rate an admin applied, scoped like the bill's own breakdown: a service, a
-- service in one region, or a single usage type. NULL in region_name or usage_type means
-- "every one of them".

CREATE TABLE IF NOT EXISTS invoice_private_pricing (
    id                              BIGSERIAL PRIMARY KEY,
    invoice_id                      BIGINT          NOT NULL
                                        REFERENCES invoice (id) ON DELETE CASCADE,
    service_name                    VARCHAR(255)    NOT NULL,
    region_name                     VARCHAR(255)    NULL,
    usage_type                      VARCHAR(255)    NULL,
    unit_rate                       NUMERIC(19, 10) NOT NULL,
    rate_unit                       VARCHAR(255)    NULL,
    exclude_from_overall_discount   BOOLEAN         DEFAULT TRUE,
    line_count                      INTEGER         NULL,
    skipped_line_count              INTEGER         NULL,
    original_amount                 NUMERIC(19, 4)  NULL,
    priced_amount                   NUMERIC(19, 4)  NULL,
    description                     VARCHAR(255)    NULL,
    applied_at                      TIMESTAMP       NULL,

    -- A negative rate would bill a credit for usage. Zero is allowed: pricing something
    -- at nothing is a legitimate concession.
    CONSTRAINT ck_invoice_private_pricing_rate CHECK (unit_rate >= 0)
);

-- ON DELETE CASCADE matches the entity's orphanRemoval: deleting a draft takes its
-- pricing with it, and a rate has no meaning without its invoice.

-- The application refuses overlapping scopes before saving, which is the real guard --
-- it can explain which existing rate conflicts, and NULLs are not distinct enough for a
-- plain unique index to catch "whole service" against "one usage type of it". This
-- constraint is the backstop for the exact-duplicate case.
CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_private_pricing_scope
    ON invoice_private_pricing (invoice_id, service_name, region_name, usage_type);

-- Loaded per invoice on every read of the admin invoice detail page.
CREATE INDEX IF NOT EXISTS idx_invoice_private_pricing_invoice_id
    ON invoice_private_pricing (invoice_id);
