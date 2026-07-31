-- XamSecure: persist Upwind API-security endpoints.
--
-- Previously the APIs tab fetched every endpoint from Upwind on each cache miss
-- (~6.8k endpoints over multiple pages, ~20s blocking per request, 10-minute TTL).
-- Syncing them like detections/vulnerabilities/posture/inventory makes the tab read
-- locally and respond immediately.
--
-- Endpoint payloads carry no cloud_account_id; tenancy comes from the
-- cloud-account-id request parameter, so the sync stamps it per row.

CREATE TABLE upwind_api_endpoint (
    id BIGSERIAL PRIMARY KEY,
    upwind_id VARCHAR(128) NOT NULL UNIQUE,
    cloud_account_id VARCHAR(64) NOT NULL,
    method VARCHAR(16),
    uri TEXT,
    resource_id VARCHAR(128),
    internet_exposed BOOLEAN NOT NULL DEFAULT FALSE,
    auth_state VARCHAR(32),
    domains TEXT,
    first_seen_time TIMESTAMPTZ,
    last_seen_time TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    raw_payload TEXT,
    synced_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_uae_account ON upwind_api_endpoint (cloud_account_id);
CREATE INDEX idx_uae_account_status ON upwind_api_endpoint (cloud_account_id, status);
