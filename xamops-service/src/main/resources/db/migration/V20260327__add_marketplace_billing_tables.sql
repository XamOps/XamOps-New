-- ============================================================
-- Marketplace Billing Tables
-- Stores payer account connection, discovered member accounts,
-- and billing history snapshot for all marketplace accounts
-- Date range: December 2024 – March 2026 (pre-Billing Conductor)
-- ============================================================

-- 1. Marketplace payer (management) account connection config
CREATE TABLE marketplace_payer_accounts (
    id                    BIGSERIAL     PRIMARY KEY,
    account_name          VARCHAR(255)  NOT NULL,
    payer_aws_account_id  VARCHAR(12)   NOT NULL UNIQUE,
    role_arn              VARCHAR(500)  NOT NULL,
    external_id           VARCHAR(255)  NOT NULL DEFAULT 'xamops-marketplace-sync',
    status                VARCHAR(50)   NOT NULL DEFAULT 'CONNECTED',
    connected_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    last_synced_at        TIMESTAMP
);

-- 2. All member accounts discovered under the payer
CREATE TABLE marketplace_accounts (
    id                    BIGSERIAL     PRIMARY KEY,
    payer_account_id      BIGINT        NOT NULL REFERENCES marketplace_payer_accounts(id) ON DELETE CASCADE,
    aws_account_id        VARCHAR(12)   NOT NULL UNIQUE,
    account_name          VARCHAR(255)  NOT NULL,
    contact_email         VARCHAR(255),
    category              VARCHAR(50)   NOT NULL,  -- MARKETPLACE_XAMOPS or MARKETPLACE_ONLY
    xamops_client_id      BIGINT        REFERENCES client(id),
    status                VARCHAR(50)   NOT NULL DEFAULT 'ACTIVE',
    billing_extracted     BOOLEAN       NOT NULL DEFAULT FALSE,
    discovered_at         TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mp_accounts_payer    ON marketplace_accounts(payer_account_id);
CREATE INDEX idx_mp_accounts_aws_id   ON marketplace_accounts(aws_account_id);
CREATE INDEX idx_mp_accounts_category ON marketplace_accounts(category);

-- 3. Monthly billing snapshot rows — one row per account + service + month
CREATE TABLE billing_history_snapshot (
    id               BIGSERIAL     PRIMARY KEY,
    aws_account_id   VARCHAR(12)   NOT NULL,
    account_name     VARCHAR(255),
    year             INT           NOT NULL,
    month            INT           NOT NULL,
    service_name     VARCHAR(255)  NOT NULL,
    cost_usd         DECIMAL(18,6) NOT NULL DEFAULT 0,
    snapshot_version VARCHAR(100)  NOT NULL DEFAULT 'PRE_BILLING_CONDUCTOR_V1',
    pulled_at        TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bhs_account_month ON billing_history_snapshot(aws_account_id, year, month);
CREATE INDEX idx_bhs_account       ON billing_history_snapshot(aws_account_id);
