-- Add agreement detail columns to marketplace_product_subscription
-- Populated from AWS Marketplace Agreement API (DescribeAgreement + GetAgreementTerms)

ALTER TABLE marketplace_product_subscription
    ADD COLUMN IF NOT EXISTS agreement_id        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS start_date          DATE,
    ADD COLUMN IF NOT EXISTS offer_type          VARCHAR(50),
    ADD COLUMN IF NOT EXISTS payment_schedule    VARCHAR(50),
    ADD COLUMN IF NOT EXISTS auto_renew          BOOLEAN,
    ADD COLUMN IF NOT EXISTS payment_term_summary VARCHAR(500);

CREATE INDEX IF NOT EXISTS idx_mp_sub_agreement_id
    ON marketplace_product_subscription(agreement_id);
