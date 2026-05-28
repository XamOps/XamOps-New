-- ============================================================
-- CSP Billing Configuration
-- Stores GCP CSP (reseller) billing dataset configurations
-- ============================================================

-- 1. CSP Billing Config table
-- Stores one row per GCP reseller/CSP that shares billing data
CREATE TABLE csp_billing_config (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    gcp_project_id VARCHAR(255) NOT NULL,
    dataset_id VARCHAR(255) NOT NULL,
    table_id VARCHAR(255) NOT NULL,
    service_account_key TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Add CSP management columns to cloud_account
ALTER TABLE cloud_account
    ADD COLUMN csp_managed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN csp_billing_config_id BIGINT REFERENCES csp_billing_config(id);

-- 3. Create index for efficient lookups of CSP-managed accounts
CREATE INDEX idx_cloud_account_csp_managed ON cloud_account(csp_managed);
CREATE INDEX idx_cloud_account_csp_billing_config ON cloud_account(csp_billing_config_id);
