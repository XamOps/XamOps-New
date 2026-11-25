-- XamPulse (Observability 1.0): per-account configuration, regions, agents, log sources.
-- Tenant key is cloud_account_id. JSON is stored as text. Idempotent.

CREATE TABLE IF NOT EXISTS xp_account_config (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL UNIQUE REFERENCES cloud_account(id),
    client_id BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    bucket_mode VARCHAR(8) NOT NULL,
    bucket_name VARCHAR(63),
    bucket_region VARCHAR(32),
    kms_key_arn VARCHAR(2048),
    home_region VARCHAR(32) NOT NULL,
    stack_name VARCHAR(128),
    stack_id VARCHAR(2048),
    template_version VARCHAR(32),
    template_sha256 CHAR(64),
    archive_role_arn VARCHAR(2048),
    archive_external_id_enc TEXT,
    hot_days INT NOT NULL DEFAULT 14,
    ia_after_days INT NOT NULL DEFAULT 30,
    glacier_ir_after_days INT NOT NULL DEFAULT 90,
    expire_after_days INT NOT NULL DEFAULT 365,
    ingest_quota_gb_day NUMERIC(8,2),
    llm_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    settings_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_xp_account_config_client ON xp_account_config (client_id);

CREATE TABLE IF NOT EXISTS xp_account_region (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id),
    region VARCHAR(32) NOT NULL,
    kind VARCHAR(8) NOT NULL,
    stack_name VARCHAR(128),
    stack_id VARCHAR(2048),
    template_version VARCHAR(32),
    template_sha256 CHAR(64),
    state VARCHAR(16) NOT NULL,
    target_mode VARCHAR(12) NOT NULL DEFAULT 'INSTANCES',
    target_instance_ids_json TEXT,
    observe_tag_key VARCHAR(128),
    observe_tag_value VARCHAR(256),
    last_checked_at TIMESTAMPTZ,
    last_error TEXT,
    CONSTRAINT uq_xp_account_region UNIQUE (cloud_account_id, region)
);
CREATE INDEX IF NOT EXISTS idx_xp_account_region_account ON xp_account_region (cloud_account_id);

CREATE TABLE IF NOT EXISTS xp_agent (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id),
    instance_id VARCHAR(32) NOT NULL,
    region VARCHAR(32) NOT NULL,
    state VARCHAR(16) NOT NULL,
    agent_version VARCHAR(32),
    collector_version VARCHAR(32),
    os VARCHAR(16),
    last_seen_at TIMESTAMPTZ,
    config_version BIGINT NOT NULL DEFAULT 0,
    collection_level VARCHAR(4) NOT NULL DEFAULT 'T1',
    pending_command VARCHAR(16),
    CONSTRAINT uq_xp_agent_instance UNIQUE (cloud_account_id, instance_id)
);
CREATE INDEX IF NOT EXISTS idx_xp_agent_account ON xp_agent (cloud_account_id);

CREATE TABLE IF NOT EXISTS xp_agent_credential (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL REFERENCES xp_agent(id),
    family_id UUID NOT NULL,
    refresh_hash CHAR(64) NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_xp_agent_credential_agent ON xp_agent_credential (agent_id);

CREATE TABLE IF NOT EXISTS xp_log_source (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id),
    kind VARCHAR(12) NOT NULL,
    paths_json TEXT,
    parser VARCHAR(64),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    redaction_json TEXT,
    sampling_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_xp_log_source_account ON xp_log_source (cloud_account_id);
