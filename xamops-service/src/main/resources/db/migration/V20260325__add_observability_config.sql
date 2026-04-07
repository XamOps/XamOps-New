-- =============================================
-- Observability Config — OpenObserve per-tenant provisioning
-- One row per client (tenant); created when tenant enables observability
-- =============================================

CREATE TABLE IF NOT EXISTS observability_config (
    id                  BIGSERIAL PRIMARY KEY,
    client_id           BIGINT       NOT NULL,
    o2_org_id           VARCHAR(100) NOT NULL,         -- e.g. "tenant-42"
    o2_ingestion_key    VARCHAR(512),                  -- service account token for collectors
    o2_api_key          VARCHAR(512),                  -- admin token for backend proxy queries
    logs_enabled        BOOLEAN      DEFAULT false,
    metrics_enabled     BOOLEAN      DEFAULT false,
    traces_enabled      BOOLEAN      DEFAULT false,
    retention_days      INT          DEFAULT 30,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),

    CONSTRAINT fk_obs_config_client
        FOREIGN KEY (client_id) REFERENCES client(id) ON DELETE CASCADE,
    CONSTRAINT uq_obs_config_client
        UNIQUE (client_id)
);

CREATE INDEX IF NOT EXISTS idx_obs_config_client ON observability_config(client_id);
