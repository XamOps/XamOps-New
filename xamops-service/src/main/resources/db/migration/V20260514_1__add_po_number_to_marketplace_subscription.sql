ALTER TABLE marketplace_product_subscription
    ADD COLUMN IF NOT EXISTS po_number VARCHAR(255),
    ADD COLUMN IF NOT EXISTS po_notes  VARCHAR(1000);
