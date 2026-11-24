-- XamPulse usage metering, audit trail and ClickHouse migration mirror.

CREATE TABLE IF NOT EXISTS xp_usage_daily (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id),
    day DATE NOT NULL,
    signal VARCHAR(8) NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT '',
    bytes_in BIGINT NOT NULL DEFAULT 0,
    bytes_stored BIGINT NOT NULL DEFAULT 0,
    row_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_xp_usage_daily UNIQUE (cloud_account_id, day, signal, source)
);
CREATE INDEX IF NOT EXISTS idx_xp_usage_daily_account ON xp_usage_daily (cloud_account_id, day);

CREATE TABLE IF NOT EXISTS xp_audit (
    id BIGSERIAL PRIMARY KEY,
    ts TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id BIGINT NOT NULL,
    cloud_account_id BIGINT REFERENCES cloud_account(id),
    action VARCHAR(64) NOT NULL,
    target VARCHAR(500),
    ip VARCHAR(64),
    detail_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_xp_audit_account ON xp_audit (cloud_account_id, ts);

CREATE TABLE IF NOT EXISTS xp_migration_log (
    id BIGSERIAL PRIMARY KEY,
    version VARCHAR(32) NOT NULL UNIQUE,
    checksum CHAR(64) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
