-- Per-account OpenObserve org provisioning.
-- Each cloud account gets its own isolated O2 org.

CREATE TABLE IF NOT EXISTS account_observability_config (
    id                  BIGSERIAL PRIMARY KEY,
    client_id           BIGINT       NOT NULL,
    cloud_account_id    BIGINT       NOT NULL,
    provider_account_id VARCHAR(255) NOT NULL,
    account_name        VARCHAR(255),
    o2_org_id           VARCHAR(100) NOT NULL,
    o2_ingestion_key    VARCHAR(512),
    created_at          TIMESTAMP    DEFAULT NOW(),

    CONSTRAINT uq_acc_obs_config_account UNIQUE (client_id, cloud_account_id),
    CONSTRAINT fk_acc_obs_config_client  FOREIGN KEY (client_id)        REFERENCES client(id)        ON DELETE CASCADE,
    CONSTRAINT fk_acc_obs_config_account FOREIGN KEY (cloud_account_id) REFERENCES cloud_account(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_acc_obs_config_provider_acct
    ON account_observability_config(client_id, provider_account_id);
