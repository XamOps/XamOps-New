-- XamSecure: Upwind CNAPP integration (Phase 1)

CREATE TABLE upwind_threat_detection (
    id BIGSERIAL PRIMARY KEY,
    upwind_id VARCHAR(128) NOT NULL UNIQUE,
    category VARCHAR(64),
    severity VARCHAR(16),
    status VARCHAR(32),
    title TEXT,
    cloud_provider VARCHAR(16),
    cloud_account_id VARCHAR(64),
    resource_id VARCHAR(256),
    resource_name VARCHAR(256),
    resource_type VARCHAR(128),
    mitre_tactics TEXT,
    occurrence_count INT,
    first_seen_time TIMESTAMPTZ,
    last_seen_time TIMESTAMPTZ,
    raw_payload TEXT,
    synced_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_utd_account ON upwind_threat_detection (cloud_account_id);
CREATE INDEX idx_utd_last_seen ON upwind_threat_detection (last_seen_time DESC);

CREATE TABLE upwind_vulnerability_finding (
    id BIGSERIAL PRIMARY KEY,
    upwind_id VARCHAR(128) NOT NULL UNIQUE,
    cve_id VARCHAR(64),
    vulnerability_name TEXT,
    severity VARCHAR(16),
    cvss_score VARCHAR(16),
    epss_score VARCHAR(16),
    exploitable BOOLEAN,
    status VARCHAR(32) DEFAULT 'OPEN',
    cloud_provider VARCHAR(16),
    cloud_account_id VARCHAR(64),
    region VARCHAR(64),
    resource_id VARCHAR(256),
    resource_name VARCHAR(256),
    resource_type VARCHAR(128),
    first_seen_time TIMESTAMPTZ,
    last_scan_time TIMESTAMPTZ,
    raw_payload TEXT,
    synced_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_uvf_account ON upwind_vulnerability_finding (cloud_account_id);
CREATE INDEX idx_uvf_severity ON upwind_vulnerability_finding (severity);

CREATE TABLE upwind_posture_finding (
    id BIGSERIAL PRIMARY KEY,
    upwind_id VARCHAR(128) NOT NULL UNIQUE,
    severity VARCHAR(16),
    status VARCHAR(32),
    title TEXT,
    framework_title VARCHAR(256),
    check_title TEXT,
    cloud_provider VARCHAR(16),
    cloud_account_id VARCHAR(64),
    region VARCHAR(64),
    resource_id VARCHAR(512),
    resource_name VARCHAR(256),
    resource_type VARCHAR(128),
    first_seen_time TIMESTAMPTZ,
    last_seen_time TIMESTAMPTZ,
    raw_payload TEXT,
    synced_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_upf_account ON upwind_posture_finding (cloud_account_id);
CREATE INDEX idx_upf_framework ON upwind_posture_finding (framework_title);

CREATE TABLE upwind_account_connection (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'NOT_CONNECTED',
    sensor_install_command TEXT,
    notes TEXT,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE upwind_sync_state (
    id BIGSERIAL PRIMARY KEY,
    domain VARCHAR(32) NOT NULL UNIQUE,
    last_success_at TIMESTAMPTZ,
    last_error TEXT,
    last_error_at TIMESTAMPTZ
);
