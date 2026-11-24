-- Label percentages, and labels on a whole service.
--
-- 1. resource_label_assignments.split_percent: the share of a resource's cost a label takes.
--    Null means the label takes the whole resource, which is what every existing row meant, so
--    nothing already stored changes. The shares on one resource add up to at most 100; whatever
--    is left is unallocated. Two decimal places, the same precision the cost view edits.
--
-- 2. service_label_assignments: a label on one billing service of one account, e.g. "Kiro" or
--    "Amazon Elastic Compute Cloud" — the service rows of the Charges by service card. Covers
--    spend the resource labels cannot reach: charges with no resource behind them, such as a
--    Marketplace subscription. Same percentage rule as above. service_name is the product name
--    exactly as the bill prints it, which is what the card shows and groups on.
--
-- IF NOT EXISTS throughout, because Hibernate ddl-auto may have created both first.
--
-- To reverse:
--   DROP TABLE IF EXISTS service_label_assignments;
--   ALTER TABLE resource_label_assignments DROP COLUMN IF EXISTS split_percent;

ALTER TABLE resource_label_assignments
    ADD COLUMN IF NOT EXISTS split_percent NUMERIC(5,2);

CREATE TABLE IF NOT EXISTS service_label_assignments (
    id               BIGSERIAL    PRIMARY KEY,
    cloud_account_id BIGINT       NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
    service_name     VARCHAR(255) NOT NULL,
    label_id         BIGINT       NOT NULL REFERENCES resource_labels(id) ON DELETE CASCADE,
    split_percent    NUMERIC(5,2),
    tagged_by        VARCHAR(100),
    tagged_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_service_label UNIQUE (cloud_account_id, service_name, label_id)
);

CREATE INDEX IF NOT EXISTS idx_sla_account ON service_label_assignments(cloud_account_id);
CREATE INDEX IF NOT EXISTS idx_sla_label   ON service_label_assignments(label_id);
